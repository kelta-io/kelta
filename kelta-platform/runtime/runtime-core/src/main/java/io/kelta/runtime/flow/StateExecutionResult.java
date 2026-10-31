package io.kelta.runtime.flow;

import io.kelta.runtime.workflow.ActionResult;

import java.util.Map;

/**
 * Result of executing a single state.
 *
 * @param nextStateId    the ID of the next state to transition to (null for terminal states)
 * @param updatedData    the state data after this step (may be modified by data flow)
 * @param status         the step status (SUCCEEDED, FAILED)
 * @param errorCode      error code if failed
 * @param errorMessage   error message if failed
 * @param terminal       true if this is a terminal state (Succeed/Fail)
 * @param awaiting       for a Task parked on work that completes later: what to resume it by, and
 *                       when it expires; null otherwise
 * @since 1.0.0
 */
public record StateExecutionResult(
    String nextStateId,
    Map<String, Object> updatedData,
    String status,
    String errorCode,
    String errorMessage,
    boolean terminal,
    ActionResult.Awaiting awaiting
) {

    public StateExecutionResult(String nextStateId, Map<String, Object> updatedData, String status,
                                String errorCode, String errorMessage, boolean terminal) {
        this(nextStateId, updatedData, status, errorCode, errorMessage, terminal, null);
    }

    /**
     * Creates a successful result that transitions to the next state.
     */
    public static StateExecutionResult success(String nextStateId, Map<String, Object> updatedData) {
        return new StateExecutionResult(nextStateId, updatedData, "SUCCEEDED", null, null, false);
    }

    /**
     * Creates a terminal success result (Succeed state).
     */
    public static StateExecutionResult terminalSuccess(Map<String, Object> data) {
        return new StateExecutionResult(null, data, "SUCCEEDED", null, null, true);
    }

    /**
     * Creates a terminal failure result (Fail state).
     */
    public static StateExecutionResult terminalFailure(String errorCode, String errorMessage, Map<String, Object> data) {
        return new StateExecutionResult(null, data, "FAILED", errorCode, errorMessage, true);
    }

    /**
     * Creates a failure result that may be caught by a Catch policy.
     */
    public static StateExecutionResult failure(String errorCode, String errorMessage, Map<String, Object> data) {
        return new StateExecutionResult(null, data, "FAILED", errorCode, errorMessage, false);
    }

    /**
     * Creates a waiting result (Wait state).
     */
    public static StateExecutionResult waiting(Map<String, Object> data) {
        return new StateExecutionResult(null, data, "WAITING", null, null, false);
    }

    /**
     * Creates a waiting result for a Task whose handler accepted work that completes later
     * ({@link ActionResult#awaiting}). The engine parks the execution on this state and resumes it
     * with the delivered outcome.
     */
    public static StateExecutionResult awaiting(Map<String, Object> data, ActionResult.Awaiting awaiting) {
        return new StateExecutionResult(null, data, "WAITING", null, null, false, awaiting);
    }
}
