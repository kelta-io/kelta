package io.kelta.runtime.model;

import java.util.List;
import java.util.Map;

/**
 * Builder for constructing {@link FieldDefinition} instances.
 * 
 * <p>Provides a fluent API with method chaining for building field definitions.
 * Required fields (name and type) must be set before calling {@link #build()}.
 * 
 * <p>Example usage:
 * <pre>{@code
 * FieldDefinition field = new FieldDefinitionBuilder()
 *     .name("email")
 *     .type(FieldType.STRING)
 *     .nullable(false)
 *     .unique(true)
 *     .validationRules(ValidationRules.forString(5, 255, "^[\\w.-]+@[\\w.-]+\\.\\w+$"))
 *     .build();
 * }</pre>
 * 
 * @since 1.0.0
 */
public class FieldDefinitionBuilder {
    
    private String name;
    private FieldType type;
    private boolean nullable = true;
    private boolean immutable = false;
    private boolean unique = false;
    private Object defaultValue;
    private ValidationRules validationRules;
    private List<String> enumValues;
    private ReferenceConfig referenceConfig;
    private Map<String, Object> fieldTypeConfig;
    private String columnName;
    private String description;

    /**
     * Creates a new field definition builder.
     */
    public FieldDefinitionBuilder() {
    }
    
    /**
     * Sets the field name.
     * 
     * @param name the field name (required)
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder name(String name) {
        this.name = name;
        return this;
    }
    
    /**
     * Sets the field type.
     * 
     * @param type the field type (required)
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder type(FieldType type) {
        this.type = type;
        return this;
    }
    
    /**
     * Sets whether the field accepts null values.
     * 
     * @param nullable true if null values are allowed (default: true)
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder nullable(boolean nullable) {
        this.nullable = nullable;
        return this;
    }
    
    /**
     * Sets whether the field is immutable after creation.
     * 
     * @param immutable true if the field cannot be updated (default: false)
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder immutable(boolean immutable) {
        this.immutable = immutable;
        return this;
    }
    
    /**
     * Sets whether the field value must be unique.
     * 
     * @param unique true if values must be unique (default: false)
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder unique(boolean unique) {
        this.unique = unique;
        return this;
    }
    
    /**
     * Sets the default value for the field.
     * 
     * @param defaultValue the default value
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder defaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }
    
    /**
     * Sets the validation rules for the field.
     * 
     * @param validationRules the validation rules
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder validationRules(ValidationRules validationRules) {
        this.validationRules = validationRules;
        return this;
    }
    
    /**
     * Sets the allowed enum values for the field.
     * 
     * @param enumValues list of allowed values
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder enumValues(List<String> enumValues) {
        this.enumValues = enumValues;
        return this;
    }
    
    /**
     * Sets the reference configuration for foreign key relationships.
     * 
     * @param referenceConfig the reference configuration
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder referenceConfig(ReferenceConfig referenceConfig) {
        this.referenceConfig = referenceConfig;
        return this;
    }

    /**
     * Sets the type-specific configuration for the field.
     *
     * @param fieldTypeConfig the type-specific configuration map
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder fieldTypeConfig(Map<String, Object> fieldTypeConfig) {
        this.fieldTypeConfig = fieldTypeConfig;
        return this;
    }

    /**
     * Sets the physical database column name for system collection fields.
     *
     * @param columnName the column name (null means use field name)
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder columnName(String columnName) {
        this.columnName = columnName;
        return this;
    }

    /**
     * Sets the one-line description surfaced by the schema endpoint and the OpenAPI document.
     *
     * @param description what the field holds, in one line
     * @return this builder for method chaining
     */
    public FieldDefinitionBuilder description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Builds the field definition.
     * 
     * @return the constructed field definition
     * @throws IllegalStateException if required fields (name, type) are not set
     */
    public FieldDefinition build() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Field name is required");
        }
        if (type == null) {
            throw new IllegalStateException("Field type is required");
        }
        
        return new FieldDefinition(
            name,
            type,
            nullable,
            immutable,
            unique,
            defaultValue,
            validationRules,
            enumValues,
            referenceConfig,
            fieldTypeConfig,
            columnName,
            false,
            description
        );
    }
    
    /**
     * Creates a new builder for constructing field definitions.
     * 
     * @return a new builder instance
     */
    public static FieldDefinitionBuilder builder() {
        return new FieldDefinitionBuilder();
    }
}
