package io.kelta.runtime.flow;

import io.kelta.runtime.flow.AwaitedTaskOutcomes.TaskOutcome;
import io.kelta.runtime.flow.executor.TaskStateExecutor;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A top-level Task whose handler answers {@link ActionResult#awaiting} parks its execution and
 * resumes with the outcome delivered for it (external modules part 9h): by a delivery claim, by the
 * expiry poller, or at once when the outcome arrived before the step parked. ResultPath, Retry and
 * Catch apply to the delivered outcome as to a synchronous one. A claim left unconsumed is taken
 * over after its lease, and a resume that lost its claim does nothing.
 */
@DisplayName("FlowEngine — awaited tasks")
class FlowEngineAwaitedTaskTest {

    private static final String TENANT = "t1";
    /** The code the worker's store settles an expired module task with. */
    private static final String MODULE_TIMEOUT = "ModuleTimeoutException";

    private FlowEngine engine;
    private FlowEngineTest.InMemoryFlowStore flowStore;
    private ActionHandlerRegistry registry;
    private FakeOutcomes outcomes;
    private final List<ActionContext> calls = new CopyOnWriteArrayList<>();
    /** What the AWAIT handler answers, per call. */
    private volatile Function<ActionContext, ActionResult> answer;

    /** Outcomes as the worker's store keeps them: delivered by key, settled once. */
    static final class FakeOutcomes implements AwaitedTaskOutcomes {
        final Map<String, TaskOutcome> delivered = new ConcurrentHashMap<>();
        final Map<String, Integer> issuedAttempt = new ConcurrentHashMap<>();
        final Map<String, String> settled = new ConcurrentHashMap<>();
        final List<String> abandoned = new CopyOnWriteArrayList<>();

        @Override
        public void abandon(String tenantId, String executionId) {
            abandoned.add(tenantId + "/" + executionId);
        }

        @Override
        public boolean delivered(String tenantId, String resumeKey) {
            return TENANT.equals(tenantId) && delivered.containsKey(resumeKey);
        }

        @Override
        public Optional<TaskOutcome> settle(String tenantId, String resumeKey) {
            if (!TENANT.equals(tenantId) || !issuedAttempt.containsKey(resumeKey)) {
                return Optional.empty();
            }
            TaskOutcome outcome = delivered.get(resumeKey);
            if (outcome != null) {
                settled.put(resumeKey, "COMPLETED");
                return Optional.of(outcome);
            }
            settled.put(resumeKey, "EXPIRED");
            return Optional.of(TaskOutcome.failed(MODULE_TIMEOUT, "did not complete", issuedAttempt.get(resumeKey)));
        }
    }

    @BeforeEach
    void setUp() {
        flowStore = new FlowEngineTest.InMemoryFlowStore();
        outcomes = new FakeOutcomes();
        registry = new ActionHandlerRegistry();
        registry.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return "AWAIT";
            }

            @Override
            public ActionResult execute(ActionContext context) {
                calls.add(context);
                return answer.apply(context);
            }
        });
        registry.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return "LOG_MESSAGE";
            }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.success(Map.of("logged", true));
            }
        });
        // Parks every call under a key per attempt, recording the attempt as the worker's store does.
        answer = context -> park(context, Instant.now().plusSeconds(3600));
        engine = engine(flowStore, 2);
    }

    private FlowEngine engine(FlowStore store, int threads) {
        FlowEngine created = new FlowEngine(store, registry, new ObjectMapper(), threads);
        created.setAwaitedTaskOutcomes(outcomes);
        return created;
    }

    private void resumeAsPoller(List<String> executionIds) {
        executionIds.forEach(id -> engine.resumeExecution(id, FlowEngineTest.InMemoryFlowStore.POLLER));
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private ActionResult park(ActionContext context, Instant expiresAt) {
        String key = key(context.attempt());
        outcomes.issuedAttempt.put(key, context.attempt());
        return ActionResult.awaiting(key, expiresAt);
    }

    private static String key(int attempt) {
        return "task-" + attempt;
    }

    private static String flow(String taskExtras) {
        return """
            {
                "StartAt": "Ingest",
                "States": {
                    "Ingest": {
                        "Type": "Task",
                        "Resource": "AWAIT",
                        "ResultPath": "$.ingest",
                        "Next": "After"%s
                    },
                    "After": { "Type": "Task", "Resource": "LOG_MESSAGE", "ResultPath": "$.after", "Next": "Done" },
                    "Caught": { "Type": "Pass", "Next": "Done" },
                    "Done": { "Type": "Succeed" }
                }
            }
            """.formatted(taskExtras);
    }

    private String start(String json) throws Exception {
        flowStore.registerFlow(TENANT, "flow-1", "Ingest", json);
        String executionId = engine.startExecution(TENANT, "flow-1", json, Map.of("k", "v"), "u1", null, false);
        awaitStatus(executionId, FlowExecutionData.STATUS_WAITING);
        return executionId;
    }

    @Test
    @DisplayName("an awaiting Task parks WAITING on its state with a pending resume keyed and due at expiry")
    void parks() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        answer = context -> park(context, expiresAt);

        String executionId = start(flow(""));

        FlowExecutionData execution = flowStore.loadExecution(executionId).orElseThrow();
        assertEquals("Ingest", execution.currentNodeId());
        List<Map<String, Object>> pending = flowStore.getPendingResumes();
        assertEquals(1, pending.size());
        assertEquals(key(1), pending.getFirst().get("resumeEvent"));
        assertEquals(expiresAt, pending.getFirst().get("resumeAt"));
        assertEquals(TENANT, pending.getFirst().get("tenantId"));
        assertTrue(calls.getFirst().awaitable(), "a top-level Task may wait");
        assertEquals(1, calls.getFirst().attempt());
    }

    @Test
    @DisplayName("a delivered success resumes the Task: ResultPath applies and the flow continues")
    void deliveredSuccessResumes() throws Exception {
        String executionId = start(flow(""));

        outcomes.delivered.put(key(1), TaskOutcome.succeeded(Map.of("upserted", 42), 1));
        assertTrue(engine.resumeAwaitedTask(TENANT, key(1)));

        FlowExecutionData done = awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
        assertEquals(FlowExecutionData.STATUS_COMPLETED, done.status());
        assertEquals(Map.of("upserted", 42), done.stateData().get("ingest"));
        assertEquals(Map.of("logged", true), done.stateData().get("after"), "the next state ran");
        assertEquals(1, calls.size(), "the handler is not called again");
        assertTrue(flowStore.getPendingResumes().isEmpty());
        assertEquals("COMPLETED", outcomes.settled.get(key(1)));
    }

    @Test
    @DisplayName("a delivery is claimed once: a second resume, or one for another tenant, claims nothing")
    void claimedOnceAndTenantScoped() throws Exception {
        String executionId = start(flow(""));
        outcomes.delivered.put(key(1), TaskOutcome.succeeded(Map.of(), 1));

        assertFalse(engine.resumeAwaitedTask("t2", key(1)), "another tenant's key is not this tenant's row");
        assertEquals(FlowExecutionData.STATUS_WAITING, flowStore.loadExecution(executionId).orElseThrow().status());

        assertTrue(engine.resumeAwaitedTask(TENANT, key(1)));
        assertFalse(engine.resumeAwaitedTask(TENANT, key(1)), "the row is already claimed");
        awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("a delivered failure takes the Task's Catch with the module's error")
    void deliveredFailureIsCaught() throws Exception {
        String executionId = start(flow("""
            ,
                        "Catch": [ { "ErrorEquals": ["ActionFailed"], "ResultPath": "$.error", "Next": "Caught" } ]"""));

        outcomes.delivered.put(key(1), TaskOutcome.failed("ActionFailed", "EXPORT_GONE: 404 from source", 1));
        engine.resumeAwaitedTask(TENANT, key(1));

        FlowExecutionData done = awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
        assertEquals(Map.of("Error", "ActionFailed", "Cause", "EXPORT_GONE: 404 from source"),
            done.stateData().get("error"));
    }

    @Test
    @DisplayName("a delivered failure takes the Task's Retry: the handler is called with the next attempt")
    void deliveredFailureRetries() throws Exception {
        String executionId = start(flow("""
            ,
                        "Retry": [ { "ErrorEquals": ["ActionFailed"], "IntervalSeconds": 0, "MaxAttempts": 2 } ]"""));
        answer = context -> context.attempt() == 1 ? park(context, Instant.now().plusSeconds(3600))
            : ActionResult.success(Map.of("second", true));

        outcomes.delivered.put(key(1), TaskOutcome.failed("ActionFailed", "transient", 1));
        engine.resumeAwaitedTask(TENANT, key(1));

        FlowExecutionData done = awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
        assertEquals(Map.of("second", true), done.stateData().get("ingest"));
        assertEquals(2, calls.size());
        assertEquals(2, calls.get(1).attempt(), "the retry continues the attempt count");
    }

    @Test
    @DisplayName("a retry that parks again waits on a new key, due at its own expiry")
    void retryParksAgain() throws Exception {
        String executionId = start(flow("""
            ,
                        "Retry": [ { "ErrorEquals": ["States.ALL"], "IntervalSeconds": 0, "MaxAttempts": 3 } ]"""));

        outcomes.delivered.put(key(1), TaskOutcome.failed("ActionFailed", "transient", 1));
        engine.resumeAwaitedTask(TENANT, key(1));

        awaitPending(key(2));
        assertEquals(FlowExecutionData.STATUS_WAITING, flowStore.loadExecution(executionId).orElseThrow().status());
        outcomes.delivered.put(key(2), TaskOutcome.succeeded(Map.of("ok", true), 2));
        engine.resumeAwaitedTask(TENANT, key(2));
        assertEquals(FlowExecutionData.STATUS_COMPLETED,
            awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED).status());
    }

    @Test
    @DisplayName("past its expiry the poller's claim settles the task as a timeout, and the step fails with it")
    void expiryFailsTheStep() throws Exception {
        String executionId = start(flow(""));

        List<String> due = flowStore.claimDuePendingResumes(Instant.now().plusSeconds(7200));
        assertEquals(List.of(executionId), due);
        resumeAsPoller(due);

        FlowExecutionData failed = awaitStatus(executionId, FlowExecutionData.STATUS_FAILED);
        assertEquals(FlowExecutionData.STATUS_FAILED, failed.status());
        assertEquals("did not complete", failed.errorMessage());
        assertEquals("EXPIRED", outcomes.settled.get(key(1)));
        assertEquals(1, calls.size());
        assertTrue(flowStore.loadStepLogs(executionId).stream()
            .anyMatch(s -> MODULE_TIMEOUT.equals(s.errorCode())));
    }

    @Test
    @DisplayName("a timeout is caught like any error: Catch on ModuleTimeoutException")
    void expiryIsCatchable() throws Exception {
        String executionId = start(flow("""
            ,
                        "Catch": [ { "ErrorEquals": ["ModuleTimeoutException"], "ResultPath": "$.error", "Next": "Caught" } ]"""));

        resumeAsPoller(flowStore.claimDuePendingResumes(Instant.now().plusSeconds(7200)));

        FlowExecutionData done = awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
        assertEquals("ModuleTimeoutException", ((Map<?, ?>) done.stateData().get("error")).get("Error"));
    }

    @Test
    @DisplayName("a claim left unconsumed past its lease is taken over; the resume that lost it does nothing")
    void staleClaimIsTakenOverOnce() throws Exception {
        String executionId = start(flow(""));
        outcomes.delivered.put(key(1), TaskOutcome.succeeded(Map.of("upserted", 7), 1));
        // A pod claims the delivery, then dies (or its queued resume is dropped) before resuming.
        assertEquals(Optional.of(executionId), flowStore.claimPendingResumeByEvent(TENANT, key(1), "slow"));

        assertEquals(List.of(), flowStore.claimDue(Instant.now(), "poller-2", Duration.ofMinutes(5)),
            "a claim within its lease is left alone, and the row is not due before its expiry");
        flowStore.ageClaims(Duration.ofMinutes(6));
        assertEquals(List.of(executionId), flowStore.claimDue(Instant.now(), "poller-2", Duration.ofMinutes(5)),
            "past the lease the poller takes the claim over, before the expiry");

        // The old claimer's resume runs late: it no longer holds the row, so it does nothing.
        FlowEngine late = engine(flowStore, 1);
        late.resumeExecution(executionId, "slow");
        late.shutdown();
        assertEquals(FlowExecutionData.STATUS_WAITING, flowStore.loadExecution(executionId).orElseThrow().status());
        assertTrue(outcomes.settled.isEmpty(), "the lost claim settled nothing");
        assertEquals(1, flowStore.getPendingResumes().size());

        engine.resumeExecution(executionId, "poller-2");
        FlowExecutionData done = awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
        assertEquals(Map.of("upserted", 7), done.stateData().get("ingest"));
        assertEquals(1, calls.size(), "the step ran once");
        assertEquals(Map.of(key(1), "COMPLETED"), outcomes.settled);
    }

    @Test
    @DisplayName("a claimed resume that fails before it is queued releases its claim; the poller resumes it")
    void failedResumeReleasesItsClaim() throws Exception {
        FlowEngineTest.InMemoryFlowStore flaky = new FlowEngineTest.InMemoryFlowStore() {
            private final AtomicBoolean failed = new AtomicBoolean();

            @Override
            public Optional<String> findTenantSlug(String tenantId) {
                if (getPendingResumes().stream().anyMatch(row -> row.containsKey("claimedBy"))
                        && failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("database unavailable");
                }
                return super.findTenantSlug(tenantId);
            }
        };
        engine.shutdown();
        flowStore = flaky;
        engine = engine(flaky, 2);
        String executionId = start(flow(""));
        outcomes.delivered.put(key(1), TaskOutcome.succeeded(Map.of(), 1));

        assertThrows(IllegalStateException.class, () -> engine.resumeAwaitedTask(TENANT, key(1)));

        Map<String, Object> row = flaky.getPendingResumes().getFirst();
        assertFalse(row.containsKey("claimedBy"), "the claim is released");
        assertFalse(((Instant) row.get("resumeAt")).isAfter(Instant.now()), "and the row is due now");
        assertEquals(FlowExecutionData.STATUS_WAITING, flaky.loadExecution(executionId).orElseThrow().status());

        resumeAsPoller(flaky.claimDuePendingResumes(Instant.now()));
        assertEquals(FlowExecutionData.STATUS_COMPLETED,
            awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED).status());
    }

    @Test
    @DisplayName("cancelling an execution closes its open awaited tasks")
    void cancelAbandonsTasks() throws Exception {
        String executionId = start(flow(""));

        engine.cancelExecution(executionId);

        assertEquals(FlowExecutionData.STATUS_CANCELLED, flowStore.loadExecution(executionId).orElseThrow().status());
        assertEquals(List.of(TENANT + "/" + executionId), outcomes.abandoned);
    }

    @Test
    @DisplayName("an outcome delivered before the step parked resumes it as soon as it parks")
    void earlyDeliveryResumesOnPark() throws Exception {
        answer = context -> {
            ActionResult parked = park(context, Instant.now().plusSeconds(3600));
            // The module completed the task while its 202 was still on the way back.
            outcomes.delivered.put(key(context.attempt()), TaskOutcome.succeeded(Map.of("fast", true), 1));
            return parked;
        };
        flowStore.registerFlow(TENANT, "flow-1", "Ingest", flow(""));
        String executionId = engine.startExecution(TENANT, "flow-1", flow(""), Map.of(), "u1", null, false);

        FlowExecutionData done = awaitStatus(executionId, FlowExecutionData.STATUS_COMPLETED);
        assertEquals(FlowExecutionData.STATUS_COMPLETED, done.status());
        assertEquals(Map.of("fast", true), done.stateData().get("ingest"));
    }

    @Test
    @DisplayName("a key no source knows settles as States.Timeout with no retry")
    void unknownKeyTimesOut() throws Exception {
        answer = context -> ActionResult.awaiting("unknown-" + context.attempt(), Instant.now().plusSeconds(60));
        String executionId = start(flow("""
            ,
                        "Retry": [ { "ErrorEquals": ["States.ALL"], "IntervalSeconds": 0, "MaxAttempts": 3 } ]"""));

        resumeAsPoller(flowStore.claimDuePendingResumes(Instant.now().plusSeconds(120)));

        FlowExecutionData failed = awaitStatus(executionId, FlowExecutionData.STATUS_FAILED);
        assertEquals(FlowExecutionData.STATUS_FAILED, failed.status());
        assertEquals(1, calls.size(), "no retry for a task nobody can settle");
        assertTrue(flowStore.loadStepLogs(executionId).stream()
            .anyMatch(s -> AwaitedTaskOutcomes.TIMEOUT_CODE.equals(s.errorCode())));
    }

    @Test
    @DisplayName("inside a Parallel branch a Task cannot wait: the handler is told so, and an awaiting answer fails")
    void branchCannotWait() {
        String json = """
            {
                "StartAt": "Both",
                "States": {
                    "Both": {
                        "Type": "Parallel",
                        "Branches": [ { "StartAt": "Inner", "States": {
                            "Inner": { "Type": "Task", "Resource": "AWAIT", "End": true,
                                "Catch": [ { "ErrorEquals": ["States.AwaitNotSupported"],
                                    "ResultPath": "$.error", "Next": "InnerCaught" } ] },
                            "InnerCaught": { "Type": "Pass", "End": true } } } ],
                        "ResultPath": "$.branches",
                        "Next": "Done"
                    },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;
        Map<String, Object> result = engine.executeSynchronous(TENANT, "flow-1", json, Map.of(), "u1");

        assertFalse(calls.getFirst().awaitable(), "a branch step is told it cannot wait");
        assertTrue(flowStore.getPendingResumes().isEmpty(), "nothing parked");
        FlowExecutionData execution = flowStore.getAllExecutions().getFirst();
        assertEquals(FlowExecutionData.STATUS_COMPLETED, execution.status());
        List<?> branches = (List<?>) result.get("branches");
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) branches.getFirst()).get("error");
        assertEquals(TaskStateExecutor.AWAIT_NOT_SUPPORTED, error.get("Error"));
    }

    private void awaitPending(String resumeEvent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (flowStore.getPendingResumes().stream().anyMatch(r -> resumeEvent.equals(r.get("resumeEvent")))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no pending resume " + resumeEvent + ": " + flowStore.getPendingResumes());
    }

    private FlowExecutionData awaitStatus(String executionId, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Optional<FlowExecutionData> current = flowStore.loadExecution(executionId);
            if (current.isPresent() && status.equals(current.get().status())) {
                return current.get();
            }
            Thread.sleep(20);
        }
        FlowExecutionData last = flowStore.loadExecution(executionId).orElseThrow();
        assertNotNull(last);
        return last;
    }
}
