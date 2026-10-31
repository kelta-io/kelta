package io.kelta.runtime.flow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable execution context that tracks state as the engine transitions
 * between states in a flow definition.
 *
 * @since 1.0.0
 */
public class FlowExecutionContext {

    private final String executionId;
    private final String tenantId;
    private final String flowId;
    private final String userId;
    private final FlowDefinition definition;
    private final Instant startedAt;
    private final int invokeDepth;
    private final FlowStepLogging stepLogging;
    private final List<CaughtError> caughtErrors = new ArrayList<>();

    private Map<String, Object> stateData;
    private String currentStateId;
    private int stepCount;
    private int failedCount;
    private int triggerDepth;
    private boolean cancelled;
    private boolean completed;
    private boolean waiting;
    private boolean awaitable;
    private AwaitedTaskOutcomes.TaskOutcome awaitedOutcome;
    private String finalStatus;
    private String finalErrorCode;
    private String finalErrorMessage;

    public FlowExecutionContext(String executionId, String tenantId, String flowId,
                                String userId, FlowDefinition definition,
                                Map<String, Object> initialState) {
        this(executionId, tenantId, flowId, userId, definition, initialState, 0);
    }

    public FlowExecutionContext(String executionId, String tenantId, String flowId,
                                String userId, FlowDefinition definition,
                                Map<String, Object> initialState,
                                int invokeDepth) {
        this(executionId, tenantId, flowId, userId, definition, initialState, invokeDepth,
            FlowStepLogging.FULL);
    }

    public FlowExecutionContext(String executionId, String tenantId, String flowId,
                                String userId, FlowDefinition definition,
                                Map<String, Object> initialState,
                                int invokeDepth, FlowStepLogging stepLogging) {
        this.executionId = executionId;
        this.tenantId = tenantId;
        this.flowId = flowId;
        this.userId = userId;
        this.definition = definition;
        this.stateData = initialState != null ? new LinkedHashMap<>(initialState) : new LinkedHashMap<>();
        this.currentStateId = definition.startAt();
        this.stepCount = 0;
        this.startedAt = Instant.now();
        this.cancelled = false;
        this.completed = false;
        this.finalStatus = null;
        this.invokeDepth = invokeDepth;
        this.stepLogging = stepLogging != null ? stepLogging : FlowStepLogging.FULL;
    }

    public String executionId() { return executionId; }
    public String tenantId() { return tenantId; }
    public String flowId() { return flowId; }
    public String userId() { return userId; }
    public FlowStepLogging stepLogging() { return stepLogging; }
    public FlowDefinition definition() { return definition; }
    public Instant startedAt() { return startedAt; }
    public String currentStateId() { return currentStateId; }
    public int stepCount() { return stepCount; }
    public boolean isCancelled() { return cancelled; }

    public Map<String, Object> stateData() { return stateData; }

    public void setStateData(Map<String, Object> stateData) {
        this.stateData = stateData != null ? new LinkedHashMap<>(stateData) : new LinkedHashMap<>();
    }

    public void setCurrentStateId(String stateId) {
        this.currentStateId = stateId;
    }

    public void incrementStepCount() {
        this.stepCount++;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCompleted() { return completed; }

    /**
     * True when the state loop parked this execution WAITING (long/event Wait).
     * A waiting execution must not be marked COMPLETED by the caller — it is
     * resumed later via {@code FlowEngine.resumeExecution}.
     */
    public boolean isWaiting() { return waiting; }

    public void markWaiting() { this.waiting = true; }

    /**
     * True when a Task in this context may park on work that completes later
     * ({@link io.kelta.runtime.workflow.ActionContext#awaitable()}): the top-level loop of an
     * execution, which the engine can resume by state id. False for Parallel, Map and InvokeFlow
     * branches, whose states the engine cannot resume.
     */
    public boolean awaitable() { return awaitable; }

    public void setAwaitable(boolean awaitable) { this.awaitable = awaitable; }

    /**
     * Hands the parked Task the outcome it resumes with; the Task executor takes it instead of
     * calling its handler.
     */
    public void setAwaitedOutcome(AwaitedTaskOutcomes.TaskOutcome outcome) { this.awaitedOutcome = outcome; }

    /** The outcome set by {@link #setAwaitedOutcome}, once; null when there is none. */
    public AwaitedTaskOutcomes.TaskOutcome takeAwaitedOutcome() {
        AwaitedTaskOutcomes.TaskOutcome outcome = awaitedOutcome;
        awaitedOutcome = null;
        return outcome;
    }

    public String finalStatus() { return finalStatus; }
    public String finalErrorCode() { return finalErrorCode; }
    public String finalErrorMessage() { return finalErrorMessage; }

    public void markCompleted(String status) {
        markCompleted(status, null, null);
    }

    public void markCompleted(String status, String errorCode, String errorMessage) {
        this.completed = true;
        this.finalStatus = status;
        this.finalErrorCode = errorCode;
        this.finalErrorMessage = errorMessage;
    }

    /**
     * Returns the current state definition.
     */
    public StateDefinition currentState() {
        return definition.getState(currentStateId);
    }

    /**
     * Returns elapsed time in milliseconds since execution started.
     */
    public int elapsedMs() {
        return (int) (System.currentTimeMillis() - startedAt.toEpochMilli());
    }

    /**
     * Records an error that was caught by a Catch policy. The execution still
     * proceeds to the catch's next state, but the swallowed error is preserved
     * so callers (e.g. Map state) can detect that an iteration had a failure
     * even though it was recovered from.
     */
    public void recordCaughtError(String stateId, String errorCode, String errorMessage) {
        caughtErrors.add(new CaughtError(stateId, errorCode, errorMessage));
    }

    /**
     * Returns the list of errors caught during this execution context's lifetime.
     */
    public List<CaughtError> caughtErrors() {
        return caughtErrors;
    }

    /**
     * Returns the running count of failed iterations attributed to this context
     * (incremented by states such as Map when their sub-flows record caught errors).
     */
    public int failedCount() {
        return failedCount;
    }

    /**
     * Returns the recursion depth of nested {@code InvokeFlow} executions
     * relative to the top-level run. Zero for the original execution; each
     * synchronous sub-flow invoked via {@code InvokeFlow} increments this by
     * one. The engine refuses to invoke when the depth would exceed
     * {@code FlowEngine.MAX_INVOKE_DEPTH}, preventing direct or transitive
     * self-recursion from running forever.
     */
    public int invokeDepth() {
        return invokeDepth;
    }

    /**
     * Returns how many asynchronous {@code TRIGGER_FLOW} hops led to this execution.
     * Zero for an execution started by anything else. Unlike {@link #invokeDepth()},
     * which only counts synchronous {@code InvokeFlow} nesting inside one execution,
     * this survives the hop into a new execution (it travels in the started
     * execution's initial input under {@code FlowEngine.TRIGGER_DEPTH_KEY}).
     */
    public int triggerDepth() {
        return triggerDepth;
    }

    public void setTriggerDepth(int triggerDepth) {
        this.triggerDepth = triggerDepth;
    }

    public void addFailedCount(int n) {
        this.failedCount += n;
    }

    /**
     * Describes an error that a Catch policy intercepted.
     */
    public record CaughtError(String stateId, String errorCode, String errorMessage) {}
}
