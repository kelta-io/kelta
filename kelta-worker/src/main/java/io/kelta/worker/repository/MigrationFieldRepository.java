package io.kelta.worker.repository;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.model.ValidationRules;
import io.kelta.runtime.model.system.SystemCollectionSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes to the {@code field} metadata table to bring a collection's stored schema into line with a
 * migration target. Used only by the destructive migration execute path so the {@code field} rows,
 * the in-memory registry, and the physical table all agree after an {@code ALTER}/drop. RLS scopes
 * every statement to the request-bound tenant.
 *
 * <p>Field-row encoding mirrors {@link SystemCollectionSeeder#insertField} (the same {@code type}
 * string map and {@code constraints}/reference columns) so a re-inserted field loads back through
 * {@code CollectionLifecycleManager.loadFieldsFromDb} identically.
 *
 * @since 1.0.0
 */
@Repository
public class MigrationFieldRepository {

    private static final Logger log = LoggerFactory.getLogger(MigrationFieldRepository.class);

    private static final String INSERT_FIELD = """
            INSERT INTO field (id, collection_id, name, display_name, type, required,
                               unique_constraint, indexed, default_value, constraints,
                               field_type_config, reference_target, reference_collection_id,
                               relationship_type, relationship_name, cascade_delete,
                               field_order, active, column_name, immutable, track_history,
                               auto_number_sequence_name, created_at, updated_at, tenant_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MigrationFieldRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Removes a field row by (collection, name). */
    public void deleteField(String collectionId, String fieldName) {
        jdbcTemplate.update("DELETE FROM field WHERE collection_id = ? AND name = ?",
                collectionId, fieldName);
    }

    /** Updates a field's stored {@code type} string (used for a TYPE_CHANGED step). */
    public void updateFieldType(String collectionId, FieldDefinition target) {
        jdbcTemplate.update(
                "UPDATE field SET type = ?, updated_at = ? WHERE collection_id = ? AND name = ?",
                SystemCollectionSeeder.mapFieldType(target.type()),
                Timestamp.from(Instant.now()), collectionId, target.name());
    }

    /** Inserts a field row reconstructed from a snapshot's {@link FieldDefinition}. */
    public void insertField(String collectionId, FieldDefinition f, int order) {
        Timestamp now = Timestamp.from(Instant.now());
        boolean required = !f.nullable();
        boolean indexed = f.unique() || f.referenceConfig() != null;

        String defaultValueJson = f.defaultValue() != null ? serialize(f.defaultValue()) : null;
        String constraintsJson = serializeConstraints(f.validationRules(), f.enumValues());
        String fieldTypeConfigJson = f.fieldTypeConfig() != null && !f.fieldTypeConfig().isEmpty()
                ? serialize(f.fieldTypeConfig()) : null;

        String referenceTarget = null;
        String referenceCollectionId = null;
        String relationshipType = null;
        String relationshipName = null;
        boolean cascadeDelete = false;
        ReferenceConfig ref = f.referenceConfig();
        if (ref != null) {
            referenceTarget = ref.targetCollection();
            referenceCollectionId = lookupCollectionId(referenceTarget);
            relationshipType = ref.relationshipType();
            relationshipName = ref.relationshipName();
            cascadeDelete = ref.cascadeDelete();
        }

        jdbcTemplate.update(INSERT_FIELD,
                UUID.randomUUID().toString(), collectionId, f.name(), f.name(),
                SystemCollectionSeeder.mapFieldType(f.type()), required,
                f.unique(), indexed, defaultValueJson, constraintsJson, fieldTypeConfigJson,
                referenceTarget, referenceCollectionId, relationshipType, relationshipName, cascadeDelete,
                order, true, f.columnName(), f.immutable(), false, null, now, now, TenantContext.get());
    }

    private String lookupCollectionId(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return null;
        }
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM collection WHERE name = ? ORDER BY created_at ASC",
                    String.class, collectionName);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            log.debug("Could not resolve reference_collection_id for '{}': {}", collectionName, e.getMessage());
            return null;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize field metadata value: {}", e.getMessage());
            return null;
        }
    }

    private String serializeConstraints(ValidationRules rules, List<String> enumValues) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        if (rules != null) {
            if (rules.minLength() != null) constraints.put("minLength", rules.minLength());
            if (rules.maxLength() != null) constraints.put("maxLength", rules.maxLength());
            if (rules.pattern() != null) constraints.put("pattern", rules.pattern());
            if (rules.minValue() != null) constraints.put("minValue", rules.minValue());
            if (rules.maxValue() != null) constraints.put("maxValue", rules.maxValue());
        }
        if (enumValues != null && !enumValues.isEmpty()) {
            constraints.put("enumValues", enumValues);
        }
        return constraints.isEmpty() ? null : serialize(constraints);
    }
}
