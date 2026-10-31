package io.kelta.runtime.flow;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.context.TenantPropagatingExecutors;
import io.kelta.runtime.flow.executor.*;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
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
 * <p>
 * <b>Parking.</b> A long Wait, and a top-level Task whose handler answered
 * {@link io.kelta.runtime.workflow.ActionResult#awaiting}, park the execution WAITING with a
 * {@code flow_pending_resume} row. A Wait resumes at its time (the worker's resume poller) and
 * continues at its {@code Next}. An awaited Task's row carries its resume key and its expiry as
 * {@code resume_at}: {@link #resumeAwaitedTask} claims it when the outcome is delivered, or the
 * poller claims it once the expiry passes; either way the Task runs again with what
 * {@link AwaitedTaskOutcomes#settle} returns — the delivered outcome, or a timeout failure — and
 * its ResultPath, Retry and Catch apply as for a synchronous result. Both claims are database
 * claims, so the pod that resumes need not be the one that parked.
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

    /**
     * Maximum chain length of {@code TRIGGER_FLOW} hops. Each hop starts a new,
     * asynchronous execution, so {@link #MAX_INVOKE_DEPTH} (which lives in one
     * execution's context) cannot see it; the depth travels in the started
     * execution's initial input under {@link #TRIGGER_DEPTH_KEY} instead, which is
     * persisted with the execution row and therefore also survives a Wait/resume.
     */
    public static final int MAX_TRIGGER_DEPTH = MAX_INVOKE_DEPTH;

    /** Reserved initial-input key carrying the {@code TRIGGER_FLOW} hop count. */
    public static final String TRIGGER_DEPTH_KEY = "_triggerDepth";

    private final FlowStore flowStore;
    private final FlowDefinitionParser parser;
    private final StateDataResolver dataResolver;
    private final Map<String, StateExecutor> executors;
    private final ExecutorService threadPool;
    private final FlowExecutionListener listener;
    /** Claims pending resumes in this engine's name ({@code flow_pending_resume.claimed_by}). */
    private final String instanceId = "flow-engine-" + UUID.randomUUID();
    private volatile AwaitedTaskOutcomes awaitedOutcomes = AwaitedTaskOutcomes.NONE;

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

    /**
     * Where parked Tasks read their outcome ({@link AwaitedTaskOutcomes}). Unset, every awaited
     * Task settles as a timeout.
     */
    public void setAwaitedTaskOutcomes(AwaitedTaskOutcomes awaitedOutcomes) {
        this.awaitedOutcomes = awaitedOutcomes != null ? awaitedOutcomes : AwaitedTaskOutcomes.NONE;
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
     * Starts the saved flow {@code flowId} asynchronously on behalf of a
     * {@code TRIGGER_FLOW} step, recording {@code triggerDepth} in its initial input
     * under {@link #TRIGGER_DEPTH_KEY} so the next hop can be bounded by
     * {@link #MAX_TRIGGER_DEPTH}.
     *
     * @return the new execution's id, or empty when the tenant has no flow {@code flowId}
     */
    public Optional<String> startTriggeredExecution(String tenantId, String flowId,
                                                    Map<String, Object> input, String userId,
                                                    int triggerDepth) {
        Optional<String> definitionJson = flowStore.findFlowDefinitionById(tenantId, flowId);
        if (definitionJson.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> initialInput = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
        initialInput.put(TRIGGER_DEPTH_KEY, triggerDepth);
        return Optional.of(startExecution(tenantId, flowId, definitionJson.get(), initialInput,
            userId, null, false));
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
     * Wait was terminal). A parked Task runs again with the outcome
     * {@link AwaitedTaskOutcomes#settle} gives for its resume key. The
     * pending-resume row is consumed before the state loop restarts.
     *
     * <p>Without a claim: consumes whatever pending row the execution has. For callers that
     * hold no claim; a claimer uses {@link #resumeExecution(String, String)}.
     *
     * @param executionId the execution ID to resume
     */
    public void resumeExecution(String executionId) {
        resumeExecution(executionId, null);
    }

    /**
     * Resumes a waiting execution whose pending-resume row the caller claimed as
     * {@code claimedBy}. The resume runs only if it can still consume that row: when the claim
     * was taken over (the poller reclaims a claim left unconsumed past its lease) or the row is
     * gone, it is a no-op, so a slow resume and the reclaim of it never both run the step.
     * When the resume fails before it is queued, the claim is released, so the poller retries it.
     *
     * @param executionId the execution ID to resume
     * @param claimedBy   the claim held on its pending-resume row, or null for none
     */
    public void resumeExecution(String executionId, String claimedBy) {
        try {
            submitResume(executionId, claimedBy);
        } catch (RuntimeException e) {
            if (claimedBy != null) {
                try {
                    flowStore.releasePendingResume(executionId, claimedBy);
                } catch (RuntimeException releaseFailure) {
                    // The claim's lease runs out instead, and the poller takes the row over then.
                    log.warn("Could not release the resume claim of execution {}: {}", executionId,
                        releaseFailure.getMessage());
                }
            }
            throw e;
        }
    }

    private void submitResume(String executionId, String claimedBy) {
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
                // Consume the row first: only the resume that holds it runs. A claimed resume
                // that lost it (taken over after its lease, or already consumed) does nothing.
                Optional<FlowStore.ConsumedResume> consumed =
                    flowStore.consumePendingResume(resumeTenantId, executionId, claimedBy);
                if (consumed.isEmpty() && claimedBy != null) {
                    log.info("Execution {}: resume claim {} no longer holds its row, skipping",
                        executionId, claimedBy);
                    return;
                }
                // A parked Task resumes by the key its pending row carried.
                String resumeKey = consumed.map(FlowStore.ConsumedResume::resumeEvent).orElse(null);

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
                    definition, fresh.stateData(), 0,
                    flowStore.findStepLogging(resumeTenantId, flowId));
                context.setTriggerDepth(triggerDepthOf(fresh.initialInput()));
                context.setAwaitable(true);
                context.setCurrentStateId(nextStateId);
                if (waitState instanceof StateDefinition.TaskState) {
                    context.setAwaitedOutcome(settleAwaitedTask(resumeTenantId, resumeKey));
                }
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
     * Resumes the execution parked on {@code resumeKey}'s awaited Task, after its outcome was
     * recorded where {@link AwaitedTaskOutcomes} reads it. Claims the execution's pending-resume
     * row first (tenant-scoped; at most one claimer wins, on any pod), so a delivery racing the
     * expiry poller, or the step's own park, resumes it exactly once.
     *
     * @return true when this call claimed the execution and resumed it; false when nothing is
     *         parked on the key yet (the park will find the outcome) or another claimer won
     */
    public boolean resumeAwaitedTask(String tenantId, String resumeKey) {
        String claim = instanceId + ":" + UUID.randomUUID();
        Optional<String> executionId = flowStore.claimPendingResumeByEvent(tenantId, resumeKey, claim);
        executionId.ifPresent(id -> resumeExecution(id, claim));
        return executionId.isPresent();
    }

    /**
     * The outcome a parked Task resumes with: the delivered one, or a timeout. With no key (the
     * row was written by an older engine) or an unknown one, a timeout with no retry left.
     */
    private AwaitedTaskOutcomes.TaskOutcome settleAwaitedTask(String tenantId, String resumeKey) {
        Optional<AwaitedTaskOutcomes.TaskOutcome> outcome = resumeKey == null
            ? Optional.empty()
            : awaitedOutcomes.settle(tenantId, resumeKey);
        return outcome.orElseGet(() -> AwaitedTaskOutcomes.TaskOutcome.failed(AwaitedTaskOutcomes.TIMEOUT_CODE,
            "The awaited task has no outcome to resume with", Integer.MAX_VALUE));
    }

    /**
     * Parks a Task on awaited work: a pending-resume row under its resume key, due at its expiry
     * (so the resume poller fails it then). Then, because the work may already have finished
     * before the step parked, resumes at once if its outcome is already there. Unlike a Wait, a
     * park that cannot be recorded fails the execution: nothing would ever resume or expire it.
     */
    private void parkAwaitedTask(FlowExecutionContext context, ActionResult.Awaiting awaiting) {
        try {
            flowStore.createPendingResume(context.executionId(), context.tenantId(),
                awaiting.expiresAt(), awaiting.resumeKey());
        } catch (RuntimeException e) {
            log.error("Failed to park execution {} on awaited task '{}'", context.executionId(),
                awaiting.resumeKey(), e);
            String message = "Could not park the step on its awaited task: " + e.getMessage();
            flowStore.completeExecution(context.executionId(), FlowExecutionData.STATUS_FAILED,
                annotateWithFailedCount(context.stateData(), context), message,
                context.elapsedMs(), context.stepCount());
            context.markCompleted(FlowExecutionData.STATUS_FAILED, "States.AwaitFailed", message);
            return;
        }
        try {
            if (awaitedOutcomes.delivered(context.tenantId(), awaiting.resumeKey())) {
                log.info("Awaited task '{}' finished before execution {} parked; resuming",
                    awaiting.resumeKey(), context.executionId());
                resumeAwaitedTask(context.tenantId(), awaiting.resumeKey());
            }
        } catch (RuntimeException e) {
            // The row is due at the expiry, when the poller settles it with whatever arrived.
            log.warn("Could not check awaited task '{}' of execution {} after parking: {}",
                awaiting.resumeKey(), context.executionId(), e.getMessage());
        }
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
        // Close the execution's open awaited tasks, so their holders can no longer complete or
        // heartbeat them. Best effort: a task left open still expires at its own time.
        try {
            flowStore.loadExecution(executionId)
                .ifPresent(execution -> awaitedOutcomes.abandon(execution.tenantId(), executionId));
        } catch (RuntimeException e) {
            log.warn("Could not close the awaited tasks of cancelled execution {}: {}", executionId,
                e.getMessage());
        }
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
            subFlow, input, parent.invokeDepth(), parent.stepLogging());
        subContext.setTriggerDepth(parent.triggerDepth());

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
            targetFlow, input, parent.invokeDepth() + 1, parent.stepLogging());
        invokedContext.setTriggerDepth(parent.triggerDepth());

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
            executionId, tenantId, flowId, userId, definition, initialInput, 0,
            flowStore.findStepLogging(tenantId, flowId));
        context.setTriggerDepth(triggerDepthOf(initialInput));
        context.setAwaitable(true);

        log.info("Starting flow execution {} (flow={}, startAt={})",
            executionId, flowId, definition.startAt());

        Map<String, Object> finalData = runStateLoop(context);
        return completeIfNeeded(context, finalData);
    }

    private static int triggerDepthOf(Map<String, Object> initialInput) {
        if (initialInput != null && initialInput.get(TRIGGER_DEPTH_KEY) instanceof Number n) {
            return n.intValue();
        }
        return 0;
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

    /**
     * Writes the step row for a non-FULL level once the step's outcome is known, in one
     * insert. A failed step always gets its row with input and output snapshots (the
     * input was held in memory from step entry). ERRORS_ONLY also writes a snapshot-free
     * row for every other step; NONE writes nothing else.
     */
    private void logCompletedStep(FlowExecutionContext context, String stateId,
                                  StateDefinition state, Map<String, Object> stepInput,
                                  StateExecutionResult result, int stepDuration) {
        boolean failed = FlowStepLogData.STATUS_FAILED.equals(result.status());
        if (failed) {
            flowStore.logStepExecution(context.executionId(), stateId, state.name(), state.type(),
                stepInput, result.updatedData(), result.status(),
                result.errorMessage(), result.errorCode(), stepDuration, 1);
        } else if (context.stepLogging() == FlowStepLogging.ERRORS_ONLY) {
            flowStore.logStepExecution(context.executionId(), stateId, state.name(), state.type(),
                null, null, result.status(),
                result.errorMessage(), result.errorCode(), stepDuration, 1);
        }
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

            long stepStart = System.currentTimeMillis();
            String stateId = context.currentStateId();
            Map<String, Object> stepInput = context.stateData();
            FlowStepLogging stepLogging = context.stepLogging();
            String stepLogId = stepLogging == FlowStepLogging.FULL
                ? flowStore.logStepExecution(context.executionId(), stateId, currentState.name(),
                    stateType, stepInput, null, FlowStepLogData.STATUS_RUNNING, null, null, null, 1)
                : null;

            context.incrementStepCount();

            StateExecutionResult result;
            try {
                result = executor.execute(currentState, context);
            } catch (RuntimeException e) {
                if (stepLogId == null) {
                    flowStore.logStepExecution(context.executionId(), stateId, currentState.name(),
                        stateType, stepInput, null, FlowStepLogData.STATUS_FAILED,
                        e.getMessage(), e.getClass().getSimpleName(),
                        (int) (System.currentTimeMillis() - stepStart), 1);
                }
                throw e;
            }
            int stepDuration = (int) (System.currentTimeMillis() - stepStart);

            if (stepLogId != null) {
                flowStore.updateStepLog(stepLogId, result.updatedData(),
                    result.status(), result.errorMessage(), result.errorCode(), stepDuration);
            } else {
                logCompletedStep(context, stateId, currentState, stepInput, result, stepDuration);
            }

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
                if (result.awaiting() != null) {
                    parkAwaitedTask(context, result.awaiting());
                } else {
                    schedulePendingResume(context, currentState, result.updatedData());
                }
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
