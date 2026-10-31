package io.kelta.runtime.workflow;

/**
 * Thrown when a module cannot do the work asked of it: a quarantined JAR module's action handler
 * (installed, its action key registered, its code not running), or an external module that is
 * disabled, unreachable, answered with an error, or whose circuit breaker is open.
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

    public ModuleUnavailableException(String moduleId, String version, String reason) {
        super("Module '" + moduleId + "' v" + version + " is not running: " + reason);
        this.moduleId = moduleId;
    }

    /** A failure described by {@code message} as it stands, for subclasses and external modules. */
    protected ModuleUnavailableException(String moduleId, String message, boolean asIs) {
        super(message);
        this.moduleId = moduleId;
    }

    /** An external module that is running but could not serve this call; {@code message} names why. */
    public static ModuleUnavailableException of(String moduleId, String message) {
        return new ModuleUnavailableException(moduleId, message, true);
    }

    public String moduleId() {
        return moduleId;
    }
}
