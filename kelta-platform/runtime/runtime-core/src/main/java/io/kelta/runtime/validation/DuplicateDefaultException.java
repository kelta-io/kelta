package io.kelta.runtime.validation;

/**
 * Thrown by a {@link io.kelta.runtime.workflow.BeforeSaveHook} when a create/update
 * would produce a second "default" record within some uniqueness scope (e.g. a second
 * {@code isDefault=true} list view for the same collection + visibility).
 *
 * <p>This is a genuine conflict with existing state, not a shape/type problem, so it
 * maps to 409 Conflict rather than the 400 every {@link BeforeSaveResult} error produces
 * — see {@code GlobalExceptionHandler.handleDuplicateDefault}. {@code code} is caller-supplied
 * so each hook can report its own JSON:API error code (e.g. {@code DEFAULT_VIEW_EXISTS});
 * {@code existingId} names the record that already holds the default, surfaced in the
 * response's {@code meta.existingId} so the caller can PATCH it directly.
 *
 * @since 1.0.0
 */
public class DuplicateDefaultException extends RuntimeException {

    private final String code;
    private final String fieldName;
    private final String existingId;

    public DuplicateDefaultException(String code, String fieldName, String existingId, String message) {
        super(message);
        this.code = code;
        this.fieldName = fieldName;
        this.existingId = existingId;
    }

    public String getCode() {
        return code;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getExistingId() {
        return existingId;
    }
}
