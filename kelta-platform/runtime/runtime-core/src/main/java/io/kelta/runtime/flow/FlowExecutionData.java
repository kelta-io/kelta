package io.kelta.runtime.flow;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable data record representing a flow execution row.
 *
 * @param id              unique execution ID
 * @param tenantId        tenant that owns this execution
 * @param flowId          the flow being executed
 * @param status          current status (RUNNING, COMPLETED, FAILED, WAITING, CANCELLED)
 * @param startedBy       user ID who started the execution (null for trigger-started)
 * @param triggerRecordId record ID that triggered the execution (for RECORD_TRIGGERED flows)
 * @param stateData       current state data (JSON)
 * @param currentNodeId   the state currently being executed
 * @param errorMessage    error message if status is FAILED
 * @param errorCode       machine-readable failure code, when one was recorded
 *                        (e.g. {@code Abandoned} from the stale-RUNNING reaper)
 * @param stepCount       number of steps executed so far
 * @param durationMs      total execution duration in milliseconds
 * @param initialInput    the initial input data (JSON snapshot)
 * @param isTest          whether this is a test execution
 * @param startedAt       when execution started
 * @param completedAt     when execution completed (null if still running)
 * @since 1.0.0
 */
public record FlowExecutionData(
    String id,
    String tenantId,
    String flowId,
    String status,
    String startedBy,
    String triggerRecordId,
    Map<String, Object> stateData,
    String currentNodeId,
    String errorMessage,
    String errorCode,
    int stepCount,
    Integer durationMs,
    Map<String, Object> initialInput,
    boolean isTest,
    Instant startedAt,
    Instant completedAt
) {

    /** Execution is currently running. */
    public static final String STATUS_RUNNING = "RUNNING";

    /** Execution completed successfully. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Execution failed with an error. */
    public static final String STATUS_FAILED = "FAILED";

    /** Execution is waiting (Wait state or external event). */
    public static final String STATUS_WAITING = "WAITING";

    /** Execution was cancelled. */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * Reserved key written into {@code stateData} by the engine to surface the
     * total count of iterations that a Map state caught and aggregated. Visible
     * even when {@link #status} is COMPLETED — that is the whole point: catches
     * inside a Map iterator no longer make the failures vanish.
     */
    public static final String FAILED_COUNT_KEY = "_failedCount";

    /**
     * Returns true if the execution has reached a terminal state.
     */
    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status)
            || STATUS_FAILED.equals(status)
            || STATUS_CANCELLED.equals(status);
    }

    /**
     * Returns the aggregate count of caught iteration failures recorded by Map
     * states during this execution, or 0 if none were recorded.
     */
    public int failedCount() {
        if (stateData == null) {
            return 0;
        }
        Object value = stateData.get(FAILED_COUNT_KEY);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}
