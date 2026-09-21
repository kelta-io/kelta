package io.kelta.runtime.flow;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.context.TenantPropagatingExecutors;
import io.kelta.runtime.flow.executor.*;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core flow execution engine that interprets {@link FlowDefinition} state machines.
 * <p>
 * The engine traverses states from {@code startAt} until it reaches a terminal state
 * (Succeed or Fail), the execution is cancelled, or the step limit is reached.
 * <p>
 * Each state type is handled by a registered {@link StateExecutor}. The engine
 * manages the dispatch loop, state data flow, and execution persistence.
 * <p>
 * Execution is asynchronous by default — {@link #startExecution} dispatches to a
 * managed thread pool and returns the execution ID immediately. Use
 * {@link #executeSynchronous} for blocking execution.
 *
 * @since 1.0.0
 */
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);

    /** Maximum steps per execution to prevent infinite loops. */
    private static final int MAX_STEPS = 500;

    /**
     * Maximum nesting depth for {@code InvokeFlow} sub-executions. A flow that
     * invokes another flow that invokes another flow… is allowed up to this
     * many levels deep. Direct or transitive self-recursion is the main
     * scenario this bounds — without a cap, a flow that invokes itself would
     * spin until the JVM stack overflows.
     */
    public static final int MAX_INVOKE_DEPTH = 10;

    private final FlowStore flowStore;
    private final FlowDefinitionParser parser;
    private final StateDataResolver dataResolver;
    private final Map<String, StateExecutor> executors;
    private final ExecutorService threadPool;
    private final FlowExecutionListener listener;

    public FlowEngine(FlowStore flowStore,
                      ActionHandlerRegistry handlerRegistry,
                      ObjectMapper objectMapper,
                      int threadPoolSize) {
        this(flowStore, handlerRegistry, objectMapper, threadPoolSize, FlowExecutionListener.NOOP);
    }

    public FlowEngine(FlowStore flowStore,
                      ActionHandlerRegistry handlerRegistry,
                      ObjectMapper objectMapper,
                      int threadPoolSize,
                      FlowExecutionListener listener) {
        this.flowStore = flowStore;
        this.parser = new FlowDefinitionParser(objectMapper);
        this.dataResolver = new StateDataResolver(objectMapper);
        this.threadPool = TenantPropagatingExecutors.decorate(
                Executors.newFixedThreadPool(threadPoolSize));
        this.listener = listener != null ? listener : FlowExecutionListener.NOOP;

        // Register state executors
        ChoiceRuleEvaluator ruleEvaluator = new ChoiceRuleEvaluator(dataResolver);
        this.executors = new LinkedHashMap<>();
        registerExecutor(new TaskStateExecutor(handlerRegistry, dataResolver, objectMapper));
        registerExecutor(new ChoiceStateExecutor(ruleEvaluator));
        registerExecutor(new PassStateExecutor(dataResolver));
        registerExecutor(new WaitStateExecutor());
        registerExecutor(new SucceedStateExecutor());
        registerExecutor(new FailStateExecutor());
        registerExecutor(new ParallelStateExecutor(dataResolver, threadPool, this));
        registerExecutor(new MapStateExecutor(dataResolver, threadPool, this));
        registerExecutor(new InvokeFlowStateExecutor(flowStore, parser, dataResolver, this));
    }

    private void registerExecutor(StateExecutor executor) {
        executors.put(executor.stateType(), executor);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts a flow execution asynchronously. Returns the execution ID immediately.
     * The execution runs on the engine's thread pool.
     *
     * @param tenantId         tenant ID
     * @param flowId           flow ID
     * @param definitionJson   the flow definition JSON
     * @param initialInput     the initial state data
     * @param userId           user who initiated the execution (null for triggers)
     * @param triggerRecordId  ID of the record that triggered this execution
     *                         (null for manual runs, scheduled jobs, and webhooks)
     * @param isTest           whether this is a test execution
     * @return the execution ID
     */
    public String startExecution(String tenantId, String flowId, String definitionJson,
                                 Map<String, Object> initialInput, String userId,
                                 String triggerRecordId, boolean isTest) {
        FlowDefinition definition = parser.parse(definitionJson);
        String executionId = flowStore.createExecution(tenantId, flowId, userId, triggerRecordId,
                initialInput, isTest);

        threadPool.submit(() -> TenantContext.runWithTenant(tenantId, () -> {
            listener.onExecutionStarted(flowId);
            long start = System.currentTimeMillis();
            try {
                executeFlow(executionId, tenantId, flowId, userId, definition, initialInput);
            } catch (Exception e) {
                log.error("Flow execution {} failed unexpectedly", executionId, e);
                flowStore.completeExecution(executionId, FlowExecutionData.STATUS_FAILED,
                    initialInput, e.getMessage(), 0, 0);
                listener.onExecutionError(flowId, e.getClass().getSimpleName());
            } finally {
                long duration = System.currentTimeMillis() - start;
                // Determine final status from the execution store
                String finalStatus = flowStore.loadExecution(executionId)
                    .map(FlowExecutionData::status)
                    .orElse(FlowExecutionData.STATUS_FAILED);
                listener.onExecutionCompleted(flowId, finalStatus, duration, isTest);
            }
        }));

        return executionId;
    }

    /**
     * Executes a flow synchronously, blocking until completion.
     * Returns the final state data.
     *
     * @param tenantId        tenant ID
     * @param flowId          flow ID
     * @param definitionJson  the flow definition JSON
     * @param initialInput    the initial state data
     * @param userId          user who initiated the execution
     * @return the final state data after execution completes
     */
    public Map<String, Object> executeSynchronous(String tenantId, String flowId,
                                                   String definitionJson,
                                                   Map<String, Object> initialInput,
                                                   String userId) {
        FlowDefinition definition = parser.parse(definitionJson);
        String executionId = flowStore.createExecution(tenantId, flowId, userId, null, initialInput, false);
        return executeFlow(executionId, tenantId, flowId, userId, definition, initialInput);
    }

    /**
     * Resumes a waiting execution. The flow definition is re-loaded from the
     * {@link FlowStore}; the persisted Wait state is treated as satisfied and
     * execution continues from its {@code Next} transition (or completes if the
     * Wait was terminal). The pending-resume row is deleted before the state
     * loop restarts.
     *
     * @param executionId the execution ID to resume
     */
    public void resumeExecution(String executionId) {
        Optional<FlowExecutionData> executionOpt = flowStore.loadExecution(executionId);
        if (executionOpt.isEmpty()) {
            log.warn("Cannot resume execution {} — not found", executionId);
            flowStore.deletePendingResume(executionId);
            return;
        }

        FlowExecutionData execution = executionOpt.get();
        if (!FlowExecutionData.STATUS_WAITING.equals(execution.status())) {
            log.warn("Cannot resume execution {} — status is {}", executionId, execution.status());
            flowStore.deletePendingResume(executionId);
            return;
        }

        final String resumeTenantId = execution.tenantId();
        // The poller calls this from outside any tenant scope, so nothing is inherited
        // through the propagating executor. Bind the slug explicitly: schema-per-tenant
        // table resolution keys off it, and an ID-only scope falls back to `public`,
        // where tenant collections do not exist (#1577).
        Optional<String> tenantSlug = flowStore.findTenantSlug(resumeTenantId);
        if (tenantSlug.isEmpty()) {
            failResume(executionId, execution,
                "Cannot resume: tenant " + resumeTenantId + " no longer exists");
            return;
        }

        threadPool.submit(() -> TenantContext.runWithTenant(resumeTenantId, tenantSlug.get(), () -> {
            long start = System.currentTimeMillis();
            String flowId = execution.flowId();
            try {
                // Re-load under the tenant scope to get the current node + state data
                FlowExecutionData fresh = flowStore.loadExecution(executionId).orElseThrow();
                if (!FlowExecutionData.STATUS_WAITING.equals(fresh.status())) {
                    log.info("Execution {} no longer WAITING (status {}), skipping resume",
                        executionId, fresh.status());
                    flowStore.deletePendingResume(executionId);
                    return;
                }

                Optional<String> definitionJson =
                    flowStore.findFlowDefinitionById(resumeTenantId, flowId);
                if (definitionJson.isEmpty()) {
                    failResume(executionId, fresh,
                        "Cannot resume: flow " + flowId + " no longer exists");
                    return;
                }

                FlowDefinition definition = parser.parse(definitionJson.get());
                StateDefinition waitState = definition.getState(fresh.currentNodeId());
                if (waitState == null) {
                    // Waits inside Parallel/Map branch sub-definitions are not
                    // resumable — their state ids don't exist at the top level.
                    failResume(executionId, fresh,
                        "Cannot resume: state '" + fresh.currentNodeId()
                            + "' not found in flow definition");
                    return;
                }

                flowStore.deletePendingResume(executionId);
                log.info("Resuming execution {} from state '{}'", executionId, fresh.currentNodeId());

                // The Wait is satisfied — advance to its transition.
                if (waitState instanceof StateDefinition.WaitState wait && wait.end()) {
                    flowStore.completeExecution(executionId, FlowExecutionData.STATUS_COMPLETED,
                        fresh.stateData(), null,
                        (int) (System.currentTimeMillis() - start), fresh.stepCount());
                    listener.onExecutionCompleted(flowId, FlowExecutionData.STATUS_COMPLETED,
                        System.currentTimeMillis() - start, fresh.isTest());
                    return;
                }
                String nextStateId = (waitState instanceof StateDefinition.WaitState wait)
                    ? wait.next()
                    : fresh.currentNodeId();

                FlowExecutionContext context = new FlowExecutionContext(
                    executionId, resumeTenantId, flowId, fresh.startedBy(),
                    definition, fresh.stateData());
                context.setCurrentStateId(nextStateId);
                flowStore.updateExecutionState(executionId, nextStateId, fresh.stateData(),
                    FlowExecutionData.STATUS_RUNNING, fresh.stepCount());

                Map<String, Object> finalData = runStateLoop(context);
                completeIfNeeded(context, finalData);

                String finalStatus = flowStore.loadExecution(executionId)
                    .map(FlowExecutionData::status)
                    .orElse(FlowExecutionData.STATUS_FAILED);
                listener.onExecutionCompleted(flowId, finalStatus,
                    System.currentTimeMillis() - start, fresh.isTest());
            } catch (Exception e) {
                log.error("Failed to resume execution {}", executionId, e);
                flowStore.completeExecution(executionId, FlowExecutionData.STATUS_FAILED,
                    execution.stateData(), "Resume failed: " + e.getMessage(),
                    (int) (System.currentTimeMillis() - start), execution.stepCount());
                listener.onExecutionError(flowId, e.getClass().getSimpleName());
            }
        }));
    }

    /**
     * Records a pending-resume row for a Wait state that parked the execution.
     * Event waits register under their event name (resumed by a future event
     * hook or manual API); time waits register a wake-up instant claimed by the
     * worker's resume poller. Failure to schedule is logged but never fails the
     * execution — the row can be recreated manually.
     */
    private void schedulePendingResume(FlowExecutionContext context, StateDefinition state,
                                       Map<String, Object> stateData) {
        if (!(state instanceof StateDefinition.WaitState wait)) {
            return;
        }
        try {
            if (wait.eventName() != null) {
                flowStore.createPendingResume(context.executionId(), context.tenantId(),
                    null, wait.eventName());
                return;
            }
            flowStore.createPendingResume(context.executionId(), context.tenantId(),
                resolveResumeAt(wait, stateData), null);
        } catch (Exception e) {
            log.error("Failed to schedule pending resume for execution {}",
                context.executionId(), e);
        }
    }

    /**
     * Resolves when a time-based Wait should wake up: {@code seconds} → now+n,
     * {@code timestamp} → that instant, {@code timestampPath} → the instant read
     * from state data. An unparsable/missing timestamp falls back to "now" so a
     * misconfigured wait resumes immediately instead of sleeping forever.
     */
    private Instant resolveResumeAt(StateDefinition.WaitState wait, Map<String, Object> stateData) {
        if (wait.seconds() != null) {
            return Instant.now().plusSeconds(wait.seconds());
        }
        String timestamp = wait.timestamp();
        if (timestamp == null && wait.timestampPath() != null) {
            Object resolved = dataResolver.readPath(stateData, wait.timestampPath());
            timestamp = resolved != null ? resolved.toString() : null;
        }
        if (timestamp != null) {
            try {
                return Instant.parse(timestamp);
            } catch (DateTimeParseException e) {
                log.warn("Wait '{}' has unparsable timestamp '{}', resuming immediately",
                    wait.name(), timestamp);
            }
        }
        return Instant.now();
    }

    /**
     * Marks a WAITING execution FAILED because it can no longer be resumed
     * (definition or state missing), and clears its pending-resume row so the
     * poller doesn't re-claim it.
     */
    private void failResume(String executionId, FlowExecutionData execution, String message) {
        log.error("{} (execution {})", message, executionId);
        flowStore.deletePendingResume(executionId);
        flowStore.completeExecution(executionId, FlowExecutionData.STATUS_FAILED,
            execution.stateData(), message, 0, execution.stepCount());
    }

    /**
     * Cancels a running execution.
     *
     * @param executionId the execution ID to cancel
     */
    public void cancelExecution(String executionId) {
        flowStore.cancelExecution(executionId);
        log.info("Cancelled execution {}", executionId);
    }

    /**
     * Executes a sub-flow (used by Parallel and Map state executors).
     * Runs the sub-flow from startAt to terminal state and returns the final
     * state data along with any errors that were intercepted by Catch policies.
     *
     * @param subFlow   the sub-flow definition
     * @param input     the input state data for the sub-flow
     * @param parent    the parent execution context (for tenant/user info)
     * @return the sub-flow's final state data plus its list of caught errors
     */
    public SubFlowResult executeSubFlow(FlowDefinition subFlow,
                                         Map<String, Object> input,
                                         FlowExecutionContext parent) {
        // Sub-flows reuse the parent's executionId so their step rows fit the
        // flow_step_log.execution_id VARCHAR(36) FK to flow_execution(id). The
        // parent execution row already exists; sub-step rows simply attach to
        // it (their state_id and own row id disambiguate). Synthesizing a
        // longer id ("<parent>-sub-<uuid>") used to overflow the column and
        // would also FK-violate because no matching flow_execution row exists.
        FlowExecutionContext subContext = new FlowExecutionContext(
            parent.executionId(),
            parent.tenantId(), parent.flowId(), parent.userId(),
            subFlow, input, parent.invokeDepth());

        Map<String, Object> finalData = runStateLoop(subContext);
        return buildSubFlowResult(finalData, subContext);
    }

    /**
     * Executes a target flow synchronously as an {@code InvokeFlow} step.
     * <p>
     * Behaves like {@link #executeSubFlow}, but increments the parent's
     * {@code invokeDepth} so transitive {@code InvokeFlow} chains can be
     * bounded by {@link #MAX_INVOKE_DEPTH}. The state log rows still attach
     * to the parent's execution id — sub-executions don't get their own
     * {@code flow_execution} row.
     */
    public SubFlowResult executeInvokedFlow(FlowDefinition targetFlow,
                                             Map<String, Object> input,
                                             FlowExecutionContext parent) {
        FlowExecutionContext invokedContext = new FlowExecutionContext(
            parent.executionId(),
            parent.tenantId(), parent.flowId(), parent.userId(),
            targetFlow, input, parent.invokeDepth() + 1);

        Map<String, Object> finalData = runStateLoop(invokedContext);
        return buildSubFlowResult(finalData, invokedContext);
    }

    /**
     * Builds a {@link SubFlowResult} from a finished sub-context. Carries
     * caught errors and, when the sub-flow uncaught-failed, the terminal
     * error code/message so the parent state can propagate it.
     */
    private SubFlowResult buildSubFlowResult(Map<String, Object> finalData,
                                              FlowExecutionContext subContext) {
        if (subContext.isCompleted()
                && FlowExecutionData.STATUS_FAILED.equals(subContext.finalStatus())) {
            return new SubFlowResult(finalData, List.copyOf(subContext.caughtErrors()),
                subContext.finalErrorCode() != null ? subContext.finalErrorCode() : "SubFlowFailed",
                subContext.finalErrorMessage());
        }
        return new SubFlowResult(finalData, List.copyOf(subContext.caughtErrors()));
    }

    /**
     * Returns the parser used by the engine. Exposed so executors can parse
     * definition JSON loaded from the {@link FlowStore} at runtime (currently
     * only {@code InvokeFlow}).
     */
    public FlowDefinitionParser parser() {
        return parser;
    }

    /**
     * Shuts down the engine's thread pool gracefully.
     */
    public void shutdown() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Internal Execution
    // -------------------------------------------------------------------------

    private Map<String, Object> executeFlow(String executionId, String tenantId, String flowId,
                                            String userId, FlowDefinition definition,
                                            Map<String, Object> initialInput) {
        FlowExecutionContext context = new FlowExecutionContext(
            executionId, tenantId, flowId, userId, definition, initialInput);

        log.info("Starting flow execution {} (flow={}, startAt={})",
            executionId, flowId, definition.startAt());

        Map<String, Object> finalData = runStateLoop(context);
        return completeIfNeeded(context, finalData);
    }

    /**
     * Completes the execution row unless the state loop already did (terminal
     * Fail, missing state, …) or the loop parked the execution WAITING.
     * Returns the (possibly annotated) final state data.
     */
    private Map<String, Object> completeIfNeeded(FlowExecutionContext context,
                                                 Map<String, Object> finalData) {
        if (context.isWaiting()) {
            log.info("Flow execution {} parked WAITING at state '{}' ({} steps, {}ms)",
                context.executionId(), context.currentStateId(),
                context.stepCount(), context.elapsedMs());
            return finalData;
        }
        if (!context.isCompleted()) {
            String status = context.isCancelled()
                ? FlowExecutionData.STATUS_CANCELLED
                : FlowExecutionData.STATUS_COMPLETED;

            finalData = annotateWithFailedCount(finalData, context);
            flowStore.completeExecution(context.executionId(), status, finalData, null,
                context.elapsedMs(), context.stepCount());

            log.info("Flow execution {} completed with status {} (failedCount={}, {} steps, {}ms)",
                context.executionId(), status, context.failedCount(),
                context.stepCount(), context.elapsedMs());
        } else {
            log.info("Flow execution {} finished with status {} (failedCount={}, {} steps, {}ms)",
                context.executionId(), context.finalStatus(), context.failedCount(),
                context.stepCount(), context.elapsedMs());
        }

        return finalData;
    }

    /**
     * Embeds the context's failedCount into the final state data under
     * {@link FlowExecutionData#FAILED_COUNT_KEY} so consumers reading the
     * execution row can see how many iterations were caught even when the
     * run is marked COMPLETED.
     */
    private Map<String, Object> annotateWithFailedCount(Map<String, Object> data,
                                                        FlowExecutionContext context) {
        if (context.failedCount() <= 0) {
            return data;
        }
        Map<String, Object> annotated = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        annotated.put(FlowExecutionData.FAILED_COUNT_KEY, context.failedCount());
        return annotated;
    }

    private Map<String, Object> runStateLoop(FlowExecutionContext context) {
        while (!context.isCancelled() && context.stepCount() < MAX_STEPS) {
            StateDefinition currentState = context.currentState();
            if (currentState == null) {
                log.error("State '{}' not found in flow definition", context.currentStateId());
                flowStore.completeExecution(context.executionId(),
                    FlowExecutionData.STATUS_FAILED, annotateWithFailedCount(context.stateData(), context),
                    "State '" + context.currentStateId() + "' not found",
                    context.elapsedMs(), context.stepCount());
                context.markCompleted(FlowExecutionData.STATUS_FAILED,
                    "StateNotFound", "State '" + context.currentStateId() + "' not found");
                return context.stateData();
            }

            String stateType = currentState.type();
            StateExecutor executor = executors.get(stateType);
            if (executor == null) {
                log.error("No executor for state type '{}'", stateType);
                String missingMsg = "No executor for state type: " + stateType;
                flowStore.completeExecution(context.executionId(),
                    FlowExecutionData.STATUS_FAILED, annotateWithFailedCount(context.stateData(), context),
                    missingMsg, context.elapsedMs(), context.stepCount());
                context.markCompleted(FlowExecutionData.STATUS_FAILED, "ExecutorNotFound", missingMsg);
                return context.stateData();
            }

            // Log step start
            long stepStart = System.currentTimeMillis();
            String stepLogId = flowStore.logStepExecution(
                context.executionId(), context.currentStateId(), currentState.name(),
                stateType, context.stateData(), null,
                FlowStepLogData.STATUS_RUNNING, null, null, null, 1);

            context.incrementStepCount();

            // Execute the state
            StateExecutionResult result = executor.execute(currentState, context);
            int stepDuration = (int) (System.currentTimeMillis() - stepStart);

            // Update step log
            flowStore.updateStepLog(stepLogId, result.updatedData(),
                result.status(), result.errorMessage(), result.errorCode(), stepDuration);

            // Record step metrics
            String resource = (currentState instanceof StateDefinition.TaskState t) ? t.resource() : null;
            listener.onStepCompleted(context.flowId(), stateType, resource, result.status(), stepDuration);

            if ("FAILED".equals(result.status()) && result.errorCode() != null) {
                listener.onExecutionError(context.flowId(), result.errorCode());
            }

            // Handle result
            if ("WAITING".equals(result.status())) {
                // Persist execution as WAITING and schedule its resume
                context.setStateData(result.updatedData());
                context.markWaiting();
                flowStore.updateExecutionState(context.executionId(), context.currentStateId(),
                    result.updatedData(), FlowExecutionData.STATUS_WAITING, context.stepCount());
                schedulePendingResume(context, currentState, result.updatedData());
                return result.updatedData();
            }

            if (result.terminal()) {
                context.setStateData(result.updatedData());
                if ("FAILED".equals(result.status())) {
                    flowStore.completeExecution(context.executionId(),
                        FlowExecutionData.STATUS_FAILED,
                        annotateWithFailedCount(result.updatedData(), context),
                        result.errorMessage(), context.elapsedMs(), context.stepCount());
                    context.markCompleted(FlowExecutionData.STATUS_FAILED,
                        result.errorCode(), result.errorMessage());
                }
                return result.updatedData();
            }

            if ("FAILED".equals(result.status())) {
                // Uncaught failure
                flowStore.completeExecution(context.executionId(),
                    FlowExecutionData.STATUS_FAILED,
                    annotateWithFailedCount(result.updatedData(), context),
                    result.errorMessage(), context.elapsedMs(), context.stepCount());
                context.markCompleted(FlowExecutionData.STATUS_FAILED,
                    result.errorCode(), result.errorMessage());
                return result.updatedData();
            }

            // Transition to next state
            context.setStateData(result.updatedData());
            context.setCurrentStateId(result.nextStateId());

            // Persist checkpoint
            flowStore.updateExecutionState(context.executionId(), result.nextStateId(),
                result.updatedData(), FlowExecutionData.STATUS_RUNNING, context.stepCount());
        }

        // Step limit exceeded
        if (context.stepCount() >= MAX_STEPS) {
            log.error("Execution {} exceeded max step limit ({})", context.executionId(), MAX_STEPS);
            String stepMsg = "Execution exceeded maximum step limit (" + MAX_STEPS + ")";
            flowStore.completeExecution(context.executionId(),
                FlowExecutionData.STATUS_FAILED, annotateWithFailedCount(context.stateData(), context),
                stepMsg, context.elapsedMs(), context.stepCount());
            context.markCompleted(FlowExecutionData.STATUS_FAILED, "StepLimitExceeded", stepMsg);
        }

        return context.stateData();
    }
}
