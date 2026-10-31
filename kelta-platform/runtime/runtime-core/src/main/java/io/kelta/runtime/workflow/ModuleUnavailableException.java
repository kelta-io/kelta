package io.kelta.runtime.workflow;

/**
 * Thrown when a module cannot do the work asked of it: a module that is disabled, unreachable,
 * answered with an error, or whose circuit breaker is open.
 *
 * <p>Thrown rather than returned as {@link ActionResult#failure(String)} because
 * {@code TaskStateExecutor} gives every failed {@code ActionResult} the generic error code
 * {@code ActionFailed}, while a thrown exception contributes its own simple name. A flow can
 * therefore Catch {@code ModuleUnavailableException} specifically, instead of catching every action
 * failure in the flow to handle one broken module.
 *
 * <p>On an HTTP request path (a before-save hook whose {@code failurePolicy} is {@code fail}) it
 * answers {@code 503 ModuleUnavailable}.
 *
 * @since 1.0.0
 */
public class ModuleUnavailableException extends RuntimeException {

    private final String moduleId;

    /** A failure described by {@code message} as it stands, for subclasses and {@link #of}. */
    protected ModuleUnavailableException(String moduleId, String message, boolean asIs) {
        super(message);
        this.moduleId = moduleId;
    }

    /** A module that could not serve this call; {@code message} names why. */
    public static ModuleUnavailableException of(String moduleId, String message) {
        return new ModuleUnavailableException(moduleId, message, true);
    }

    public String moduleId() {
        return moduleId;
    }
}
