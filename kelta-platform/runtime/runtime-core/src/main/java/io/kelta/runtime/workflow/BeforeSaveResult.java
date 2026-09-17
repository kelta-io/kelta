package io.kelta.runtime.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a before-save lifecycle hook evaluation.
 *
 * <p>A before-save result can be:
 * <ul>
 *   <li><b>OK</b> — proceed with the save, no modifications</li>
 *   <li><b>Field updates</b> — proceed with modifications applied to the record</li>
 *   <li><b>Validation error</b> — block the save with an error message</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class BeforeSaveResult {

    private final boolean success;
    private final Map<String, Object> fieldUpdates;
    private final List<ValidationError> errors;

    private BeforeSaveResult(boolean success, Map<String, Object> fieldUpdates,
                              List<ValidationError> errors) {
        this.success = success;
        this.fieldUpdates = fieldUpdates != null ? fieldUpdates : Map.of();
        this.errors = errors != null ? errors : List.of();
    }

    /**
     * Creates a successful result with no field updates.
     */
    public static BeforeSaveResult ok() {
        return new BeforeSaveResult(true, Map.of(), List.of());
    }

    /**
     * Creates a successful result with field updates to apply before persist.
     *
     * @param fieldUpdates the field updates to merge into the record
     */
    public static BeforeSaveResult withFieldUpdates(Map<String, Object> fieldUpdates) {
        return new BeforeSaveResult(true, new HashMap<>(fieldUpdates), List.of());
    }

    /**
     * Creates a failed result with a single validation error.
     *
     * @param field the field that failed validation (null for record-level)
     * @param message the error message
     */
    public static BeforeSaveResult error(String field, String message) {
        return new BeforeSaveResult(false, Map.of(),
                List.of(new ValidationError(field, message)));
    }

    /**
     * Creates a failed result with multiple validation errors.
     *
     * @param errors the validation errors
     */
    public static BeforeSaveResult errors(List<ValidationError> errors) {
        return new BeforeSaveResult(false, Map.of(), errors);
    }

    /**
     * Returns true if the save should proceed.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns true if the result contains validation errors.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Returns true if the result contains field updates to apply.
     */
    public boolean hasFieldUpdates() {
        return !fieldUpdates.isEmpty();
    }

    /**
     * Returns the field updates to merge into the record data.
     */
    public Map<String, Object> getFieldUpdates() {
        return fieldUpdates;
    }

    /**
     * Returns the validation errors.
     */
    public List<ValidationError> getErrors() {
        return errors;
    }

    /**
     * A validation error from a lifecycle hook.
     *
     * @param field the field name (null for record-level errors)
     * @param message the error message
     * @param code a stable UPPER_SNAKE_CASE contract code for the constraint that was
     *             violated (e.g. {@code INVALID_ROW_LIMIT}), or {@code null} to let the
     *             caller fall back to a generic default (e.g. {@code VALIDATION_FAILED})
     */
    public record ValidationError(String field, String message, String code) {

        /**
         * Convenience constructor for hooks that don't have a specific contract code —
         * {@link #code()} defaults to {@code null}.
         *
         * @param field the field name (null for record-level errors)
         * @param message the error message
         */
        public ValidationError(String field, String message) {
            this(field, message, null);
        }
    }
}
