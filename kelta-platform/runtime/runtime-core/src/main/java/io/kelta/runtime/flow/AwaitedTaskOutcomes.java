package io.kelta.runtime.flow;

import java.util.Map;
import java.util.Optional;

/**
 * Where the flow engine reads the outcome of a parked Task whose handler answered
 * {@link io.kelta.runtime.workflow.ActionResult#awaiting}: work that runs outside the call and is
 * delivered later (an external module's long-running action, completed through
 * {@code POST /api/modules/self/tasks/{token}}).
 *
 * <p>The engine never holds an outcome in memory. Whoever records one durably then calls
 * {@code FlowEngine.resumeAwaitedTask}, which claims the parked execution's
 * {@code flow_pending_resume} row (tenant-scoped, {@code FOR UPDATE SKIP LOCKED}) on whichever pod
 * received it; the row's {@code resume_at} is the task's expiry, so the time-based resume poller
 * claims it instead once that passes. Either way the resuming pod calls {@link #settle} exactly
 * once per park.
 *
 * <p>Implementations are tenant-scoped: every method runs under the tenant it is given (RLS) and
 * must answer only for a {@code resumeKey} it issued, empty or {@code false} for any other.
 *
 * @since 1.0.0
 */
public interface AwaitedTaskOutcomes {

    /** No awaited tasks: every parked Task settles as {@link #TIMEOUT_CODE}. */
    AwaitedTaskOutcomes NONE = new AwaitedTaskOutcomes() {
        @Override
        public boolean delivered(String tenantId, String resumeKey) {
            return false;
        }

        @Override
        public Optional<TaskOutcome> settle(String tenantId, String resumeKey) {
            return Optional.empty();
        }
    };

    /** The engine's error code for a parked Task that has no outcome to settle with. */
    String TIMEOUT_CODE = "States.Timeout";

    /**
     * Whether an outcome already arrived. The engine asks right after it parks a step, because the
     * work may have finished — and its outcome been recorded — before the step was parked.
     */
    boolean delivered(String tenantId, String resumeKey);

    /**
     * Settles the task once, as the parked step resumes: the delivered outcome if there is one;
     * otherwise the task is closed so a later delivery is refused, and the answer is a timeout
     * failure in the implementation's own error code. Empty when the key is unknown (its owner was
     * removed), which the engine treats as {@link #TIMEOUT_CODE} with no retry.
     */
    Optional<TaskOutcome> settle(String tenantId, String resumeKey);

    /**
     * What a parked Task resumes with.
     *
     * @param success      whether the work succeeded
     * @param output       the work's output, merged per the Task's {@code ResultPath} on success
     * @param errorCode    the flow error code a {@code Retry}/{@code Catch} matches, on failure
     * @param errorMessage the failure's message
     * @param attempt      which try of the Task's {@code Retry} policy produced it (1-based), so a
     *                     retry continues the count instead of restarting it
     */
    record TaskOutcome(boolean success, Map<String, Object> output, String errorCode, String errorMessage,
                       int attempt) {

        public TaskOutcome {
            output = output == null ? Map.of() : output;
        }

        public static TaskOutcome succeeded(Map<String, Object> output, int attempt) {
            return new TaskOutcome(true, output, null, null, attempt);
        }

        public static TaskOutcome failed(String errorCode, String errorMessage, int attempt) {
            return new TaskOutcome(false, Map.of(), errorCode, errorMessage, attempt);
        }
    }
}
