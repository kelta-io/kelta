package io.kelta.runtime.workflow;

import java.util.Map;

/**
 * Thrown by a {@link BeforeSaveHook} to block a write with a 409 Conflict that
 * carries a machine-readable {@code code} and structured {@code meta} — e.g. "a
 * default already exists, here is its id" — which {@link BeforeSaveResult}
 * cannot express (its error path always maps to 400 {@code VALIDATION_FAILED}
 * via {@code ValidationException}, see {@code DefaultQueryEngine}).
 *
 * <p>Thrown directly rather than returned, so it propagates past the
 * {@code BeforeSaveResult} contract straight to {@code GlobalExceptionHandler}.
 *
 * @since 1.0.0
 */
public class HookConflictException extends RuntimeException {

    private final String code;
    private final String fieldName;
    private final Map<String, Object> meta;

    /**
     * @param code machine-readable JSON:API error code, e.g. {@code DEFAULT_VIEW_EXISTS}
     * @param fieldName the conflicting field, for the error's source pointer (nullable)
     * @param message human-readable detail
     * @param meta structured detail merged into the JSON:API error's {@code meta} (nullable)
     */
    public HookConflictException(String code, String fieldName, String message, Map<String, Object> meta) {
        super(message);
        this.code = code;
        this.fieldName = fieldName;
        this.meta = meta != null ? meta : Map.of();
    }

    public String getCode() {
        return code;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }
}
