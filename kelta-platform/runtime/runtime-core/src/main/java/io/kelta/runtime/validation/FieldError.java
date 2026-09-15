package io.kelta.runtime.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a validation error for a specific field.
 *
 * <p>Contains the field name, error message, and the constraint that was violated.
 *
 * @param fieldName the name of the field that failed validation
 * @param message a human-readable error message describing the validation failure
 * @param constraint the name of the constraint that was violated (e.g., "nullable", "minValue", "pattern")
 * @param meta optional structured detail merged into the JSON:API error's {@code meta}
 *             (e.g. the offending value / target collection for reference errors); may be {@code null}
 *
 * @since 1.0.0
 */
public record FieldError(
    String fieldName,
    String message,
    String constraint,
    Map<String, Object> meta
) {
    /**
     * Compact constructor with validation.
     */
    public FieldError {
        Objects.requireNonNull(fieldName, "fieldName cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(constraint, "constraint cannot be null");
    }

    /**
     * Convenience constructor for errors with no extra meta.
     */
    public FieldError(String fieldName, String message, String constraint) {
        this(fieldName, message, constraint, null);
    }

    /**
     * Creates a nullable constraint violation error.
     * 
     * @param fieldName the field name
     * @return a FieldError for nullable constraint violation
     */
    public static FieldError nullable(String fieldName) {
        return new FieldError(fieldName, "Field is required", "nullable");
    }
    
    /**
     * Creates a type validation error.
     * 
     * @param fieldName the field name
     * @param expectedType the expected type name
     * @return a FieldError for type validation failure
     */
    public static FieldError invalidType(String fieldName, String expectedType) {
        return new FieldError(fieldName, "Invalid type, expected " + expectedType, "type");
    }
    
    /**
     * Creates a minimum value constraint violation error.
     * 
     * @param fieldName the field name
     * @param minValue the minimum allowed value
     * @return a FieldError for minValue constraint violation
     */
    public static FieldError minValue(String fieldName, Number minValue) {
        return new FieldError(fieldName, "Value must be >= " + minValue, "minValue");
    }
    
    /**
     * Creates a maximum value constraint violation error.
     * 
     * @param fieldName the field name
     * @param maxValue the maximum allowed value
     * @return a FieldError for maxValue constraint violation
     */
    public static FieldError maxValue(String fieldName, Number maxValue) {
        return new FieldError(fieldName, "Value must be <= " + maxValue, "maxValue");
    }
    
    /**
     * Creates a minimum length constraint violation error.
     * 
     * @param fieldName the field name
     * @param minLength the minimum allowed length
     * @return a FieldError for minLength constraint violation
     */
    public static FieldError minLength(String fieldName, int minLength) {
        return new FieldError(fieldName, "Length must be >= " + minLength, "minLength");
    }
    
    /**
     * Creates a maximum length constraint violation error.
     * 
     * @param fieldName the field name
     * @param maxLength the maximum allowed length
     * @return a FieldError for maxLength constraint violation
     */
    public static FieldError maxLength(String fieldName, int maxLength) {
        return new FieldError(fieldName, "Length must be <= " + maxLength, "maxLength");
    }
    
    /**
     * Creates a pattern constraint violation error.
     * 
     * @param fieldName the field name
     * @return a FieldError for pattern constraint violation
     */
    public static FieldError pattern(String fieldName) {
        return new FieldError(fieldName, "Value does not match required pattern", "pattern");
    }
    
    /**
     * Creates an immutable constraint violation error.
     * 
     * @param fieldName the field name
     * @return a FieldError for immutable constraint violation
     */
    public static FieldError immutable(String fieldName) {
        return new FieldError(fieldName, "Field is immutable and cannot be updated", "immutable");
    }
    
    /**
     * Creates a unique constraint violation error.
     * 
     * @param fieldName the field name
     * @return a FieldError for unique constraint violation
     */
    public static FieldError unique(String fieldName) {
        return new FieldError(fieldName, "Value must be unique", "unique");
    }
    
    /**
     * Creates an enum constraint violation error.
     * 
     * @param fieldName the field name
     * @param allowedValues the list of allowed values
     * @return a FieldError for enum constraint violation
     */
    public static FieldError enumViolation(String fieldName, java.util.List<String> allowedValues) {
        return new FieldError(fieldName, "Value must be one of: " + String.join(", ", allowedValues), "enum");
    }
    
    /**
     * Creates a reference constraint violation error for a value that does not
     * resolve to an existing record in the target collection.
     *
     * @param fieldName the field name
     * @param targetCollection the target collection name
     * @param value the offending referenced id
     * @return a FieldError for reference constraint violation, with {@code meta}
     *         carrying {@code field}, {@code value} and {@code targetCollection}
     */
    public static FieldError reference(String fieldName, String targetCollection, String value) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("field", fieldName);
        meta.put("value", value);
        meta.put("targetCollection", targetCollection);
        return new FieldError(
            fieldName,
            "Referenced record '" + value + "' does not exist in collection '" + targetCollection + "' (field " + fieldName + ")",
            "reference",
            meta);
    }

    /**
     * Creates a reference constraint violation error for a field whose configured
     * target collection does not exist (a configuration error, not a bad value).
     *
     * @param fieldName the field name
     * @param targetCollection the (non-existent) target collection name
     * @return a FieldError for reference constraint violation, with {@code meta}
     *         carrying {@code field} and {@code targetCollection} only (no {@code value})
     */
    public static FieldError referenceTargetMissing(String fieldName, String targetCollection) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("field", fieldName);
        meta.put("targetCollection", targetCollection);
        return new FieldError(
            fieldName,
            "Referenced collection does not exist: " + targetCollection,
            "reference",
            meta);
    }
}
