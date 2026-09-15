package io.kelta.runtime.workflow;

/**
 * Thrown by a {@link BeforeSaveHook} that enforces "at most one default row per scope"
 * (e.g. one default list view per collection + visibility) when a second default is
 * submitted. {@link BeforeSaveResult}'s validation-error shape always maps to 400, so a
 * hook that needs a 409 naming the conflicting row throws this directly instead of
 * returning a result.
 *
 * <p>Mapped to HTTP 409 Conflict by {@code GlobalExceptionHandler}, with {@code code} as
 * the JSON:API error code and {@code existingId} surfaced in {@code meta.existingId} so
 * the client can offer "replace the current default" without a second lookup.
 *
 * @since 1.0.0
 */
public class DuplicateDefaultException extends RuntimeException {

    private final String code;
    private final String existingId;

    public DuplicateDefaultException(String code, String message, String existingId) {
        super(message);
        this.code = code;
        this.existingId = existingId;
    }

    public String getCode() {
        return code;
    }

    public String getExistingId() {
        return existingId;
    }
}
