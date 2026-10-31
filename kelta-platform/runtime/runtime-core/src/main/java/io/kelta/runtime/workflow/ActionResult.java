package io.kelta.runtime.workflow;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Result returned by every {@link ActionHandler} after execution.
 * Contains success/failure status, optional error message, and output data.
 *
 * <p>A third outcome, {@link #awaiting}, says the work was accepted and finishes outside this
 * call: the flow engine parks the step and resumes it when the outcome is delivered under
 * {@link Awaiting#resumeKey()}, or fails it when {@link Awaiting#expiresAt()} passes first. Only a
 * handler called with {@link ActionContext#awaitable()} may answer it; an awaiting result is never
 * {@link #successful()}, so a caller that does not know the outcome treats it as not done.
 *
 * @param awaiting set only for an {@link #awaiting} result
 * @since 1.0.0
 */
public record ActionResult(
    boolean successful,
    String errorMessage,
    Map<String, Object> outputData,
    Awaiting awaiting
) {

    /**
     * Work a handler started that completes after the call returns.
     *
     * @param resumeKey names the outcome to the engine's {@code AwaitedTaskOutcomes}; unique per task
     * @param expiresAt when the step fails if no outcome has arrived
     */
    public record Awaiting(String resumeKey, Instant expiresAt) {
        public Awaiting {
            Objects.requireNonNull(resumeKey, "resumeKey");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** A synchronous result. */
    public ActionResult(boolean successful, String errorMessage, Map<String, Object> outputData) {
        this(successful, errorMessage, outputData, null);
    }

    /**
     * Creates a successful result with no output data.
     */
    public static ActionResult success() {
        return new ActionResult(true, null, Map.of());
    }

    /**
     * Creates a successful result with output data.
     */
    public static ActionResult success(Map<String, Object> outputData) {
        return new ActionResult(true, null, outputData);
    }

    /**
     * Creates a failure result with an error message.
     */
    public static ActionResult failure(String errorMessage) {
        return new ActionResult(false, errorMessage, Map.of());
    }

    /**
     * Creates a failure result from an exception.
     */
    public static ActionResult failure(Exception ex) {
        return new ActionResult(false, ex.getMessage(), Map.of());
    }

    /**
     * The work was accepted and completes later; see {@link Awaiting}. Only for a call whose
     * {@link ActionContext#awaitable()} is true.
     */
    public static ActionResult awaiting(String resumeKey, Instant expiresAt) {
        return new ActionResult(false, null, Map.of(), new Awaiting(resumeKey, expiresAt));
    }

    /** True for an {@link #awaiting} result. */
    public boolean isAwaiting() {
        return awaiting != null;
    }
}
