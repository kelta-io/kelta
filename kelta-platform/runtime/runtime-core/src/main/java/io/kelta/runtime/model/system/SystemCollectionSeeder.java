package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ValidationRules;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Seeds system collection definitions into the database.
 *
 * <p>JDBC-based equivalent of the control-plane's JPA-based SystemCollectionSeeder.
 * For each system collection defined in {@link SystemCollectionDefinitions}:
 * <ol>
 *   <li>Checks if the collection exists in the {@code collection} table</li>
 *   <li>If not present, creates the collection with its field definitions</li>
 *   <li>If present, verifies that all expected fields exist and adds any missing ones</li>
 * </ol>
 *
 * <p>System collections are marked with {@code system_collection=true} in the database.
 * Their physical tables are managed by Flyway migrations, NOT by the worker.
 *
 * @since 1.0.0
 */
public class SystemCollectionSeeder {

    private static final Logger log = LoggerFactory.getLogger(SystemCollectionSeeder.class);

    private static final String SYSTEM_TENANT_ID = SystemCollectionDefinitions.SYSTEM_TENANT_ID;

    private static final String SELECT_COLLECTION_BY_NAME =
            "SELECT id, system_collection FROM collection WHERE name = ? AND tenant_id = ?";

    private static final String INSERT_COLLECTION = """
            INSERT INTO collection (id, tenant_id, name, display_name, description, path,
                                    active, current_version, system_collection,
                                    created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SYSTEM_FLAG =
            "UPDATE collection SET system_collection = true, updated_at = ? WHERE id = ?";

    private static final String SELECT_FIELD_NAMES_BY_COLLECTION =
            "SELECT name FROM field WHERE collection_id = ?";

    private static final String INSERT_FIELD = """
            INSERT INTO field (id, collection_id, name, display_name, type, required,
                               unique_constraint, indexed, default_value, constraints,
                               field_type_config, reference_target, reference_collection_id,
                               relationship_type, relationship_name, cascade_delete,
                               field_order, active, column_name, immutable, track_history,
                               auto_number_sequence_name, created_at, updated_at, tenant_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_COLLECTION_ID_BY_NAME =
            "SELECT id FROM collection WHERE name = ? AND tenant_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SystemCollectionSeeder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Seeds all system collection definitions into the database.
     *
     * <p>This method is idempotent: it can be called multiple times safely.
     * Existing collections will be reconciled (missing fields added), not recreated.
     */
    public void seed() {
        List<CollectionDefinition> definitions = SystemCollectionDefinitions.all();
        log.info("Seeding {} system collection definitions...", definitions.size());

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (CollectionDefinition definition : definitions) {
            SeedResult result = seedCollection(definition);
            switch (result) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
        }

        // Forward-referenced relationships (e.g. fields seeded before their
        // target collection was inserted) leave `reference_collection_id` null
        // on insert. Resolve them all in one pass once every collection has
        // been written.
        backfillReferenceCollectionIds();

        log.info("System collection seeding complete: {} created, {} updated, {} unchanged",
                created, updated, unchanged);
    }

    private static final String BACKFILL_REFERENCE_COLLECTION_ID = """
            UPDATE field
            SET reference_collection_id = c.id
            FROM collection c
            WHERE field.reference_target = c.name
              AND field.reference_collection_id IS NULL
              AND field.reference_target IS NOT NULL
            """;

    private void backfillReferenceCollectionIds() {
        try {
            int rows = jdbcTemplate.update(BACKFILL_REFERENCE_COLLECTION_ID);
            if (rows > 0) {
                log.info("Backfilled reference_collection_id on {} field row(s)", rows);
            }
        } catch (Exception e) {
            log.warn("Failed to backfill reference_collection_id: {}", e.getMessage());
        }
    }

    /**
     * Seeds a single system collection definition into the database.
     */
    SeedResult seedCollection(CollectionDefinition definition) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_COLLECTION_BY_NAME, definition.name(), SYSTEM_TENANT_ID);

        if (rows.isEmpty()) {
            createCollection(definition);
            return SeedResult.CREATED;
        }

        Map<String, Object> row = rows.get(0);
        String collectionId = (String) row.get("id");
        Boolean systemCollection = (Boolean) row.get("system_collection");

        // Ensure it's marked as a system collection
        if (systemCollection == null || !systemCollection) {
            Timestamp now = Timestamp.from(Instant.now());
            jdbcTemplate.update(UPDATE_SYSTEM_FLAG, now, collectionId);
            log.info("Marked existing collection '{}' as system collection", definition.name());
        }

        // Sync fields — add any missing fields
        boolean fieldsUpdated = syncFields(collectionId, definition);
        if (fieldsUpdated) {
            return SeedResult.UPDATED;
        }

        return SeedResult.UNCHANGED;
    }

    /**
     * Creates a new collection with all its fields.
     */
    private void createCollection(CollectionDefinition definition) {
        String collectionId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());

        String path = definition.apiConfig() != null ? definition.apiConfig().basePath() : null;

        jdbcTemplate.update(INSERT_COLLECTION,
                collectionId,
                SYSTEM_TENANT_ID,
                definition.name(),
                definition.displayName(),
                definition.description(),
                path,
                true,   // active
                1,      // current_version
                true,   // system_collection
                now,
                now);

        // Insert field definitions
        int fieldOrder = 0;
        for (FieldDefinition fieldDef : definition.fields()) {
            insertField(collectionId, fieldDef, fieldOrder++, now);
        }

        log.info("Created system collection '{}' with {} fields (table: {})",
                definition.name(), definition.fields().size(),
                definition.storageConfig() != null ? definition.storageConfig().tableName() : "?");
    }

    /**
     * Syncs field definitions between the database and the definition.
     * Adds any missing fields. Does NOT remove extra fields (to be safe).
     *
     * @return true if any fields were added
     */
    private boolean syncFields(String collectionId, CollectionDefinition definition) {
        List<String> existingFieldNames = jdbcTemplate.queryForList(
                SELECT_FIELD_NAMES_BY_COLLECTION, String.class, collectionId);
        Set<String> existingNames = new HashSet<>(existingFieldNames);

        boolean updated = false;
        int fieldOrder = existingFieldNames.size();
        Timestamp now = Timestamp.from(Instant.now());

        for (FieldDefinition fieldDef : definition.fields()) {
            if (!existingNames.contains(fieldDef.name())) {
                insertField(collectionId, fieldDef, fieldOrder++, now);
                updated = true;
                log.info("Added missing field '{}' to system collection '{}'",
                        fieldDef.name(), definition.name());
            }
        }

        return updated;
    }

    /**
     * Inserts a single field record into the database.
     */
    private void insertField(String collectionId, FieldDefinition fieldDef, int order, Timestamp now) {
        String fieldId = UUID.randomUUID().toString();
        String typeString = mapFieldType(fieldDef.type());
        boolean required = !fieldDef.nullable();
        boolean indexed = fieldDef.unique() || fieldDef.referenceConfig() != null;

        // Serialize default value
        String defaultValueJson = fieldDef.defaultValue() != null
                ? serializeToJson(fieldDef.defaultValue())
                : null;

        // Serialize constraints
        String constraintsJson = serializeConstraints(fieldDef.validationRules(), fieldDef.enumValues());

        // Serialize field type config
        String fieldTypeConfigJson = fieldDef.fieldTypeConfig() != null && !fieldDef.fieldTypeConfig().isEmpty()
                ? serializeToJson(fieldDef.fieldTypeConfig())
                : null;

        // Reference config
        String referenceTarget = null;
        String referenceCollectionId = null;
        String relationshipType = null;
        String relationshipName = null;
        boolean cascadeDelete = false;
        if (fieldDef.referenceConfig() != null) {
            referenceTarget = fieldDef.referenceConfig().targetCollection();
            referenceCollectionId = lookupCollectionIdByName(referenceTarget);
            relationshipType = fieldDef.referenceConfig().relationshipType();
            relationshipName = fieldDef.referenceConfig().relationshipName();
            cascadeDelete = fieldDef.referenceConfig().cascadeDelete();
        }

        jdbcTemplate.update(INSERT_FIELD,
                fieldId,
                collectionId,
                fieldDef.name(),
                fieldDef.name(),  // display_name defaults to field name
                typeString,
                required,
                fieldDef.unique(),
                indexed,
                defaultValueJson,
                constraintsJson,
                fieldTypeConfigJson,
                referenceTarget,
                referenceCollectionId,
                relationshipType,
                relationshipName,
                cascadeDelete,
                order,
                true,  // active
                fieldDef.columnName(),
                fieldDef.immutable(),
                false,  // track_history default
                null,   // auto_number_sequence_name
                now,
                now,
                SYSTEM_TENANT_ID);
    }

    /**
     * Looks up the UUID of a system collection by name.
     *
     * <p>Used at field-insert time to populate the {@code reference_collection_id}
     * column alongside {@code reference_target}. Returns {@code null} if the
     * target collection has not yet been seeded — in that case a follow-up
     * pass populates the column (see V69 migration for the pattern). System
     * collections are seeded in {@link SystemCollectionDefinitions#all()}
     * order, so a forward declaration that references a not-yet-seeded
     * collection will leave this null until the next reconciliation pass.
     */
    private String lookupCollectionIdByName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return null;
        }
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    SELECT_COLLECTION_ID_BY_NAME, String.class,
                    collectionName, SYSTEM_TENANT_ID);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Could not resolve reference_collection_id for '{}': {}",
                    collectionName, e.getMessage());
            return null;
        }
    }

    /**
     * Maps runtime FieldType to the string type stored in the database.
     * Matches the control-plane's SystemCollectionSeeder.mapFieldType() exactly.
     */
    public static String mapFieldType(FieldType fieldType) {
        return switch (fieldType) {
            case STRING -> "string";
            case INTEGER -> "number";
            case LONG -> "number";
            case DOUBLE -> "number";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "datetime";
            case JSON -> "object";
            case ARRAY -> "array";
            case PICKLIST -> "string";
            case MULTI_PICKLIST -> "array";
            case CURRENCY -> "number";
            case PERCENT -> "number";
            case AUTO_NUMBER -> "string";
            case PHONE -> "string";
            case EMAIL -> "string";
            case URL -> "string";
            case TEXT -> "text";
            case RICH_TEXT -> "rich_text";
            case VECTOR -> "vector";
            case ENCRYPTED -> "string";
            case EXTERNAL_ID -> "string";
            case GEOLOCATION -> "number";
            case REFERENCE -> "reference";
            case LOOKUP -> "lookup";
            case MASTER_DETAIL -> "master_detail";
            case FORMULA -> "string";
            case ROLLUP_SUMMARY -> "string";
        };
    }

    /**
     * Serializes a value to JSON string for JSONB columns.
     */
    String serializeToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            log.warn("Failed to serialize value to JSON: {}", value, e);
            return null;
        }
    }

    /**
     * Serializes validation rules and enum values into a structured JSON constraints object.
     *
     * @return JSON string or null if no constraints
     */
    String serializeConstraints(ValidationRules rules, List<String> enumValues) {
        Map<String, Object> constraints = new LinkedHashMap<>();

        if (rules != null) {
            if (rules.minLength() != null) {
                constraints.put("minLength", rules.minLength());
            }
            if (rules.maxLength() != null) {
                constraints.put("maxLength", rules.maxLength());
            }
            if (rules.pattern() != null) {
                constraints.put("pattern", rules.pattern());
            }
            if (rules.minValue() != null) {
                constraints.put("minValue", rules.minValue());
            }
            if (rules.maxValue() != null) {
                constraints.put("maxValue", rules.maxValue());
            }
        }

        if (enumValues != null && !enumValues.isEmpty()) {
            constraints.put("enumValues", enumValues);
        }

        if (constraints.isEmpty()) {
            return null;
        }

        return serializeToJson(constraints);
    }

    enum SeedResult {
        CREATED,
        UPDATED,
        UNCHANGED
    }
}
