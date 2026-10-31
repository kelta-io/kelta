package io.kelta.runtime.workflow;

/**
 * An external module did not answer within its timeout (the manifest's {@code timeoutMs}).
 *
 * <p>A subclass of {@link ModuleUnavailableException} so code that handles an unavailable module
 * handles this too, but a flow sees its own error code: {@code TaskStateExecutor} uses the thrown
 * class's simple name, so a Catch on {@code ModuleTimeoutException} matches only timeouts and one
 * on {@code ModuleUnavailableException} does not match them. On an HTTP request path it answers
 * {@code 503 ModuleTimeout}.
 *
 * @since 1.0.0
 */
public class ModuleTimeoutException extends ModuleUnavailableException {

    public ModuleTimeoutException(String moduleId, String message) {
        super(moduleId, message, true);
    }
}
