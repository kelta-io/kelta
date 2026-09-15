package io.kelta.runtime.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Definition of a single field within a collection.
 *
 * <p>Specifies the field's name, data type, validation constraints, and relationships.
 * This record is immutable and uses defensive copying for collection fields.
 *
 * @param name Field name (required, must be non-null and non-blank)
 * @param type Data type of the field (required)
 * @param nullable Whether the field accepts null values (default: true)
 * @param immutable Whether the field can be updated after creation (default: false)
 * @param unique Whether the field value must be unique across all records (default: false)
 * @param defaultValue Default value for the field when not provided
 * @param validationRules Validation constraints (min/max value, length, pattern)
 * @param enumValues List of allowed values for enum-type fields
 * @param referenceConfig Configuration for foreign key relationships
 * @param fieldTypeConfig Type-specific configuration (e.g., auto-number prefix/padding, currency code)
 * @param columnName Physical database column name for system collections (null = use field name)
 * @param trackHistory Whether value changes to this field are recorded in field_history (default: false)
 * @param description One-line, human-readable explanation of what the field holds. Surfaced by
 *                    {@code GET /api/collections/{name}/schema} and the generated OpenAPI document,
 *                    so an API client can learn a field's meaning without reading platform source.
 *
 * @since 1.0.0
 */
public record FieldDefinition(
    String name,
    FieldType type,
    boolean nullable,
    boolean immutable,
    boolean unique,
    Object defaultValue,
    ValidationRules validationRules,
    List<String> enumValues,
    ReferenceConfig referenceConfig,
    Map<String, Object> fieldTypeConfig,
    String columnName,
    boolean trackHistory,
    String description
) {
    /**
     * Compact constructor with validation and defensive copying.
     */
    public FieldDefinition {
        Objects.requireNonNull(name, "name cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        Objects.requireNonNull(type, "type cannot be null");

        // Defensive copy for enumValues
        enumValues = enumValues != null ? List.copyOf(enumValues) : null;
        // Defensive copy for fieldTypeConfig
        fieldTypeConfig = fieldTypeConfig != null ? Map.copyOf(fieldTypeConfig) : null;
    }

    /**
     * Backward-compatible constructor without the description parameter.
     * Delegates to the canonical constructor with description = null.
     */
    public FieldDefinition(
            String name, FieldType type, boolean nullable, boolean immutable,
            boolean unique, Object defaultValue, ValidationRules validationRules,
            List<String> enumValues, ReferenceConfig referenceConfig,
            Map<String, Object> fieldTypeConfig, String columnName, boolean trackHistory) {
        this(name, type, nullable, immutable, unique, defaultValue,
             validationRules, enumValues, referenceConfig, fieldTypeConfig, columnName,
             trackHistory, null);
    }

    /**
     * Backward-compatible constructor without the trackHistory parameter.
     * Delegates to the canonical constructor with trackHistory = false.
     */
    public FieldDefinition(
            String name, FieldType type, boolean nullable, boolean immutable,
            boolean unique, Object defaultValue, ValidationRules validationRules,
            List<String> enumValues, ReferenceConfig referenceConfig,
            Map<String, Object> fieldTypeConfig, String columnName) {
        this(name, type, nullable, immutable, unique, defaultValue,
             validationRules, enumValues, referenceConfig, fieldTypeConfig, columnName, false, null);
    }

    /**
     * Backward-compatible constructor without columnName or trackHistory parameters.
     * Delegates to the canonical constructor with columnName = null, trackHistory = false.
     */
    public FieldDefinition(
            String name, FieldType type, boolean nullable, boolean immutable,
            boolean unique, Object defaultValue, ValidationRules validationRules,
            List<String> enumValues, ReferenceConfig referenceConfig,
            Map<String, Object> fieldTypeConfig) {
        this(name, type, nullable, immutable, unique, defaultValue,
             validationRules, enumValues, referenceConfig, fieldTypeConfig, null, false, null);
    }

    /**
     * Returns the value of a type-specific configuration key.
     *
     * @param key the configuration key
     * @return the configuration value, or null if not present
     */
    public Object getConfigValue(String key) {
        return fieldTypeConfig != null ? fieldTypeConfig.get(key) : null;
    }
    
    /**
     * Creates a simple string field.
     * 
     * @param name the field name
     * @return a nullable string field definition
     */
    public static FieldDefinition string(String name) {
        return new FieldDefinition(name, FieldType.STRING, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a required string field.
     * 
     * @param name the field name
     * @return a non-nullable string field definition
     */
    public static FieldDefinition requiredString(String name) {
        return new FieldDefinition(name, FieldType.STRING, false, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a simple integer field.
     * 
     * @param name the field name
     * @return a nullable integer field definition
     */
    public static FieldDefinition integer(String name) {
        return new FieldDefinition(name, FieldType.INTEGER, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a required integer field.
     * 
     * @param name the field name
     * @return a non-nullable integer field definition
     */
    public static FieldDefinition requiredInteger(String name) {
        return new FieldDefinition(name, FieldType.INTEGER, false, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a simple long field.
     * 
     * @param name the field name
     * @return a nullable long field definition
     */
    public static FieldDefinition longField(String name) {
        return new FieldDefinition(name, FieldType.LONG, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a simple double field.
     * 
     * @param name the field name
     * @return a nullable double field definition
     */
    public static FieldDefinition doubleField(String name) {
        return new FieldDefinition(name, FieldType.DOUBLE, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a simple boolean field.
     * 
     * @param name the field name
     * @return a nullable boolean field definition
     */
    public static FieldDefinition bool(String name) {
        return new FieldDefinition(name, FieldType.BOOLEAN, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a boolean field with a default value.
     * 
     * @param name the field name
     * @param defaultValue the default value
     * @return a boolean field definition with default
     */
    public static FieldDefinition bool(String name, boolean defaultValue) {
        return new FieldDefinition(name, FieldType.BOOLEAN, false, false, false, defaultValue, null, null, null, null);
    }
    
    /**
     * Creates a date field.
     * 
     * @param name the field name
     * @return a nullable date field definition
     */
    public static FieldDefinition date(String name) {
        return new FieldDefinition(name, FieldType.DATE, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a datetime field.
     * 
     * @param name the field name
     * @return a nullable datetime field definition
     */
    public static FieldDefinition datetime(String name) {
        return new FieldDefinition(name, FieldType.DATETIME, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates a JSON field.
     * 
     * @param name the field name
     * @return a nullable JSON field definition
     */
    public static FieldDefinition json(String name) {
        return new FieldDefinition(name, FieldType.JSON, true, false, false, null, null, null, null, null);
    }
    
    /**
     * Creates an enum field with allowed values.
     * 
     * @param name the field name
     * @param values the allowed enum values
     * @return an enum field definition
     */
    public static FieldDefinition enumField(String name, List<String> values) {
        Objects.requireNonNull(values, "values cannot be null");
        return new FieldDefinition(name, FieldType.STRING, false, false, false, null, null, values, null, null);
    }
    
    /**
     * Returns the effective column name for this field.
     * If columnName is set, returns it; otherwise returns the field name.
     *
     * @return the effective database column name
     */
    public String effectiveColumnName() {
        return columnName != null ? columnName : name;
    }

    /**
     * Creates a new field definition with the specified column name.
     *
     * @param columnName the physical database column name
     * @return a new field definition with the column name set
     */
    public FieldDefinition withColumnName(String columnName) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Creates a reference field to another collection.
     *
     * @param name the field name
     * @param targetCollection the target collection name
     * @return a reference field definition
     */
    public static FieldDefinition reference(String name, String targetCollection) {
        return new FieldDefinition(name, FieldType.STRING, true, false, false, null, null, null,
            ReferenceConfig.toCollection(targetCollection), null);
    }

    /**
     * Creates a LOOKUP relationship field. Nullable, ON DELETE SET NULL.
     *
     * @param name the field name
     * @param targetCollection the target collection name
     * @param relationshipName the human-readable relationship name
     * @return a lookup field definition
     */
    public static FieldDefinition lookup(String name, String targetCollection, String relationshipName) {
        return new FieldDefinition(name, FieldType.LOOKUP, true, false, false, null, null, null,
            ReferenceConfig.lookup(targetCollection, relationshipName), null);
    }

    /**
     * Creates a MASTER_DETAIL relationship field. Required, ON DELETE CASCADE.
     *
     * @param name the field name
     * @param targetCollection the target collection name
     * @param relationshipName the human-readable relationship name
     * @return a master-detail field definition
     */
    public static FieldDefinition masterDetail(String name, String targetCollection, String relationshipName) {
        return new FieldDefinition(name, FieldType.MASTER_DETAIL, false, false, false, null, null, null,
            ReferenceConfig.masterDetail(targetCollection, relationshipName), null);
    }

    // =========================================================================
    // Convenience Factory Methods
    // =========================================================================

    /**
     * Creates a nullable string field with a maximum length constraint.
     *
     * @param name the field name
     * @param maxLength the maximum string length
     * @return a nullable string field with maxLength validation
     */
    public static FieldDefinition string(String name, int maxLength) {
        return new FieldDefinition(name, FieldType.STRING, true, false, false, null,
                ValidationRules.forString(null, maxLength), null, null, null);
    }

    /**
     * Creates a required string field with a maximum length constraint.
     *
     * @param name the field name
     * @param maxLength the maximum string length
     * @return a non-nullable string field with maxLength validation
     */
    public static FieldDefinition requiredString(String name, int maxLength) {
        return new FieldDefinition(name, FieldType.STRING, false, false, false, null,
                ValidationRules.forString(null, maxLength), null, null, null);
    }

    /**
     * Creates a required JSON field.
     *
     * @param name the field name
     * @return a non-nullable JSON field definition
     */
    public static FieldDefinition requiredJson(String name) {
        return new FieldDefinition(name, FieldType.JSON, false, false, false, null, null, null, null, null);
    }

    /**
     * Creates a nullable text field (unbounded string).
     *
     * @param name the field name
     * @return a nullable string field with no length constraint
     */
    public static FieldDefinition text(String name) {
        return new FieldDefinition(name, FieldType.STRING, true, false, false, null, null, null, null, null);
    }

    /**
     * Creates a required text field (unbounded string).
     *
     * @param name the field name
     * @return a non-nullable string field with no length constraint
     */
    public static FieldDefinition requiredText(String name) {
        return new FieldDefinition(name, FieldType.STRING, false, false, false, null, null, null, null, null);
    }

    // =========================================================================
    // With-er (copy) Methods
    // =========================================================================

    /**
     * Returns a copy of this field with the nullable flag changed.
     *
     * @param nullable whether the field accepts null values
     * @return a new field definition with the specified nullable flag
     */
    public FieldDefinition withNullable(boolean nullable) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with the unique flag changed.
     *
     * @param unique whether the field value must be unique
     * @return a new field definition with the specified unique flag
     */
    public FieldDefinition withUnique(boolean unique) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with a default value set.
     *
     * @param defaultValue the default value
     * @return a new field definition with the specified default value
     */
    public FieldDefinition withDefault(Object defaultValue) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with validation rules set.
     *
     * @param validationRules the validation rules
     * @return a new field definition with the specified validation rules
     */
    public FieldDefinition withValidation(ValidationRules validationRules) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with the immutable flag changed.
     *
     * @param immutable whether the field can be updated after creation
     * @return a new field definition with the specified immutable flag
     */
    public FieldDefinition withImmutable(boolean immutable) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with enum values set.
     *
     * @param enumValues the allowed enum values
     * @return a new field definition with the specified enum values
     */
    public FieldDefinition withEnumValues(java.util.List<String> enumValues) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with reference config set.
     *
     * @param referenceConfig the reference configuration
     * @return a new field definition with the specified reference config
     */
    public FieldDefinition withReferenceConfig(ReferenceConfig referenceConfig) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with the trackHistory flag changed.
     *
     * @param trackHistory whether value changes are recorded in field_history
     * @return a new field definition with the specified trackHistory flag
     */
    public FieldDefinition withTrackHistory(boolean trackHistory) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }

    /**
     * Returns a copy of this field with a description set.
     *
     * @param description one-line explanation of what the field holds
     * @return a new field definition with the specified description
     */
    public FieldDefinition withDescription(String description) {
        return new FieldDefinition(name, type, nullable, immutable, unique,
                defaultValue, validationRules, enumValues, referenceConfig,
                fieldTypeConfig, columnName, trackHistory, description);
    }
}
