package io.kelta.worker.listener;

import io.kelta.runtime.embedding.EmbeddingService;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.FieldMaskingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wildcard before-save hook that populates {@code VECTOR} fields from a configured source text
 * field ("embed-on-write"): it reads the source field, embeds it with the configured
 * {@link EmbeddingService}, and writes the pgvector literal into the VECTOR field before persist —
 * so {@code POST /api/{collection}/semantic-search} works without callers computing embeddings.
 *
 * <p>A VECTOR field opts in via its {@code fieldTypeConfig}:
 * <pre>{@code { "dimension": 384, "embeddingSource": "description" }}</pre>
 * Only fields declaring a non-blank {@code embeddingSource} are populated; collections without such
 * a field (including all system collections) are untouched.
 *
 * <p>Semantics:
 * <ul>
 *   <li><b>Create</b> — embed when the source field is present and non-blank.</li>
 *   <li><b>Update</b> — re-embed only when the source field is part of the update payload (so a
 *       partial update that doesn't touch the source leaves the existing vector intact).</li>
 *   <li><b>Explicit override</b> — if the caller supplies the VECTOR field value directly, it wins
 *       and is not overwritten.</li>
 *   <li><b>Dimension mismatch</b> — if the field's declared dimension differs from the embedding
 *       provider's, the field is skipped with a warning (an embedding of the wrong length would be
 *       rejected by pgvector anyway).</li>
 * </ul>
 *
 * <p>Runs with order {@code 120} — after record-locking ({@code 50}) and record-type defaults
 * ({@code 100}), so source values defaulted by earlier hooks are visible.
 *
 * @since 1.0.0
 */
@Component
public class EmbeddingOnWriteHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingOnWriteHook.class);

    private static final String WILDCARD = "*";

    /** {@code fieldTypeConfig} key naming the source text field to embed. */
    static final String SOURCE_CONFIG_KEY = "embeddingSource";

    /** {@code fieldTypeConfig} key holding the vector dimensionality. */
    private static final String DIMENSION_CONFIG_KEY = "dimension";

    /** Default when a VECTOR field omits {@code dimension} (OpenAI text-embedding-3-small). */
    private static final int DEFAULT_VECTOR_DIMENSION = 1536;

    private final CollectionRegistry collectionRegistry;
    private final EmbeddingService embeddingService;
    private final FieldMaskingService fieldMaskingService;

    public EmbeddingOnWriteHook(CollectionRegistry collectionRegistry,
                                EmbeddingService embeddingService,
                                FieldMaskingService fieldMaskingService) {
        this.collectionRegistry = collectionRegistry;
        this.embeddingService = embeddingService;
        this.fieldMaskingService = fieldMaskingService;
    }

    @Override
    public String getCollectionName() {
        return WILDCARD;
    }

    @Override
    public int getOrder() {
        return 120;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return populate(record, false);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        return populate(record, true);
    }

    private BeforeSaveResult populate(Map<String, Object> record, boolean isUpdate) {
        if (record == null) {
            return BeforeSaveResult.ok();
        }
        String collectionName = (String) record.get("__collectionName");
        if (collectionName == null) {
            return BeforeSaveResult.ok();
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            return BeforeSaveResult.ok();
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        for (FieldDefinition field : definition.fields()) {
            if (field.type() != FieldType.VECTOR) {
                continue;
            }
            String sourceField = sourceField(field);
            if (sourceField == null) {
                continue;
            }
            // A masking-configured source field must never be embedded: the vector
            // would encode its plaintext and semantic-search could match against it,
            // bypassing masking. Skip embedding rather than embed a masked value.
            FieldDefinition sourceDef = fieldByName(definition, sourceField);
            if (sourceDef != null && fieldMaskingService.configFor(sourceDef).isPresent()) {
                continue;
            }
            // On update, only re-embed when the source field is part of this payload.
            if (isUpdate && !record.containsKey(sourceField)) {
                continue;
            }
            // An explicitly supplied vector wins over auto-population.
            if (record.get(field.name()) != null) {
                continue;
            }
            Object sourceValue = record.get(sourceField);
            if (sourceValue == null) {
                continue;
            }
            String text = String.valueOf(sourceValue);
            if (text.isBlank()) {
                continue;
            }
            int dimension = vectorDimension(field);
            if (dimension != embeddingService.dimensions()) {
                log.warn("Skipping embed-on-write for {}.{}: field dimension {} != provider {} ({})",
                        collectionName, field.name(), dimension, embeddingService.dimensions(),
                        embeddingService.providerId());
                continue;
            }
            float[] embedding = embeddingService.embed(text);
            updates.put(field.name(), EmbeddingService.toVectorLiteral(embedding));
        }

        return updates.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.withFieldUpdates(updates);
    }

    private static FieldDefinition fieldByName(CollectionDefinition definition, String name) {
        for (FieldDefinition field : definition.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static String sourceField(FieldDefinition field) {
        Object configured = field.getConfigValue(SOURCE_CONFIG_KEY);
        if (configured instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private static int vectorDimension(FieldDefinition field) {
        Object configured = field.getConfigValue(DIMENSION_CONFIG_KEY);
        if (configured instanceof Number number) {
            return number.intValue();
        }
        if (configured instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return DEFAULT_VECTOR_DIMENSION;
            }
        }
        return DEFAULT_VECTOR_DIMENSION;
    }
}
