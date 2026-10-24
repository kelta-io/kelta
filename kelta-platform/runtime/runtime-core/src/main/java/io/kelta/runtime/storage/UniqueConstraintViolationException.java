package io.kelta.runtime.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exception thrown when a unique constraint is violated during a storage operation.
 *
 * <p>This exception is thrown when attempting to create or update a record with
 * a value that already exists for a field marked as unique.
 *
 * <p>A violation names every field the violated constraint covers — one for a
 * single-column constraint, all of them for a composite one — and the conflicting
 * value of each field whose value may be shown to the client. A field present in
 * {@link #getFieldNames()} but absent from {@link #getValues()} had its value
 * withheld (server-only, encrypted or masked); an empty field list means the
 * constraint could not be identified.
 *
 * @since 1.0.0
 */
public class UniqueConstraintViolationException extends StorageException {

    private final String collectionName;
    private final List<String> fieldNames;
    private final Map<String, Object> values;

    /**
     * Creates a new UniqueConstraintViolationException.
     *
     * @param collectionName the collection where the violation occurred
     * @param fieldName the field with the unique constraint
     * @param value the duplicate value
     */
    public UniqueConstraintViolationException(String collectionName, String fieldName, Object value) {
        this(collectionName, singleField(fieldName), singleValue(fieldName, value), null);
    }

    /**
     * Creates a new UniqueConstraintViolationException with a cause.
     *
     * @param collectionName the collection where the violation occurred
     * @param fieldName the field with the unique constraint
     * @param value the duplicate value
     * @param cause the underlying cause
     */
    public UniqueConstraintViolationException(String collectionName, String fieldName, Object value, Throwable cause) {
        this(collectionName, singleField(fieldName), singleValue(fieldName, value), cause);
    }

    /**
     * Creates a new UniqueConstraintViolationException naming every field of the
     * violated constraint.
     *
     * @param collectionName the collection where the violation occurred
     * @param fieldNames the fields the violated constraint covers, in constraint order
     * @param values the conflicting value per field; omit a field to withhold its value
     * @param cause the underlying cause, may be null
     */
    public UniqueConstraintViolationException(String collectionName, List<String> fieldNames,
                                              Map<String, Object> values, Throwable cause) {
        super(buildMessage(collectionName, fieldNames, values), cause);
        this.collectionName = collectionName;
        this.fieldNames = List.copyOf(fieldNames);
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Gets the collection name where the violation occurred.
     *
     * @return the collection name
     */
    public String getCollectionName() {
        return collectionName;
    }

    /**
     * Gets the field name with the unique constraint — the fields joined with
     * {@code ", "} for a composite constraint, or null when unidentified.
     *
     * @return the field name
     */
    public String getFieldName() {
        return fieldNames.isEmpty() ? null : String.join(", ", fieldNames);
    }

    /**
     * Gets every field the violated constraint covers.
     *
     * @return the field names, empty when the constraint could not be identified
     */
    public List<String> getFieldNames() {
        return fieldNames;
    }

    /**
     * Gets the conflicting value per field; a field missing here had its value withheld.
     *
     * @return the values keyed by field name
     */
    public Map<String, Object> getValues() {
        return values;
    }

    /**
     * Gets the duplicate value that caused the violation — null for a composite constraint
     * or a withheld value.
     *
     * @return the duplicate value
     */
    public Object getValue() {
        return fieldNames.size() == 1 ? values.get(fieldNames.get(0)) : null;
    }

    /**
     * Describes the conflict on one field of the violated constraint, naming the
     * field and — unless withheld — its conflicting value.
     *
     * @param fieldName one of {@link #getFieldNames()}
     * @return a client-facing description of the conflict on that field
     */
    public String describeField(String fieldName) {
        String description = String.format("Unique constraint violation: field '%s' in collection '%s' %s",
            fieldName, collectionName,
            values.containsKey(fieldName)
                ? String.format("already has value '%s'", values.get(fieldName))
                : "already has that value");
        List<String> others = fieldNames.stream().filter(name -> !name.equals(fieldName)).toList();
        return others.isEmpty()
            ? description
            : description + " in combination with " + quoted(others);
    }

    private static String buildMessage(String collectionName, List<String> fieldNames, Map<String, Object> values) {
        if (fieldNames.isEmpty()) {
            return String.format("Unique constraint violation in collection '%s'", collectionName);
        }
        if (fieldNames.size() == 1) {
            String fieldName = fieldNames.get(0);
            return values.containsKey(fieldName)
                ? String.format("Unique constraint violation: field '%s' in collection '%s' already has value '%s'",
                    fieldName, collectionName, values.get(fieldName))
                : String.format("Unique constraint violation: field '%s' in collection '%s' already has that value",
                    fieldName, collectionName);
        }
        String shown = fieldNames.stream()
            .filter(values::containsKey)
            .map(name -> name + "='" + values.get(name) + "'")
            .collect(Collectors.joining(", "));
        return String.format("Unique constraint violation: fields %s in collection '%s' must be unique together%s",
            quoted(fieldNames), collectionName,
            shown.isEmpty() ? "" : "; the combination (" + shown + ") already exists");
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    private static List<String> singleField(String fieldName) {
        return fieldName == null ? List.of() : List.of(fieldName);
    }

    private static Map<String, Object> singleValue(String fieldName, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (fieldName != null) {
            values.put(fieldName, value);
        }
        return values;
    }
}
