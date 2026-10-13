package io.kelta.runtime.flow;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-flow step logging level (PLT-602): FULL keeps the historical rows and snapshots,
 * ERRORS_ONLY keeps a snapshot-free row per step plus a full row for the failing step,
 * NONE keeps only the failing step's row. The execution row is written at every level.
 */
@DisplayName("FlowEngine step logging level")
class FlowEngineStepLoggingTest {

    private static final String TENANT = "t1";
    private static final String FLOW = "f1";

    /** Three steps: two Pass states and a Succeed. */
    private static final String SUCCESS_FLOW = """
        {
            "StartAt": "A",
            "States": {
                "A": { "Type": "Pass", "Result": {"a": 1}, "ResultPath": "$.a", "Next": "B" },
                "B": { "Type": "Pass", "Result": {"b": 2}, "ResultPath": "$.b", "Next": "Done" },
                "Done": { "Type": "Succeed" }
            }
        }
        """;

    /** A Pass, then a Task whose handler fails uncaught. */
    private static final String FAILING_FLOW = """
        {
            "StartAt": "Prepare",
            "States": {
                "Prepare": { "Type": "Pass", "Result": {"ready": true}, "ResultPath": "$.prep", "Next": "Boom" },
                "Boom": { "Type": "Task", "Resource": "FAIL_HANDLER", "End": true }
            }
        }
        """;

    private RecordingFlowStore flowStore;
    private FlowEngine engine;

    @BeforeEach
    void setUp() {
        flowStore = new RecordingFlowStore();
        ActionHandlerRegistry handlers = new ActionHandlerRegistry();
        handlers.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "FAIL_HANDLER"; }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.failure("Handler intentionally failed");
            }
        });
        handlers.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "THROWS"; }

            @Override
            public ActionResult execute(ActionContext context) {
                throw new IllegalStateException("handler blew up");
            }
        });
        engine = new FlowEngine(flowStore, handlers, new ObjectMapper(), 2);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private FlowExecutionData run(FlowStepLogging level, String json) {
        flowStore.level = level;
        engine.executeSynchronous(TENANT, FLOW, json, Map.of("input", Map.of("k", "v")), "u1");
        return flowStore.loadExecution(flowStore.lastExecutionId).orElseThrow();
    }

    private List<FlowStepLogData> steps(FlowExecutionData execution) {
        return flowStore.loadStepLogs(execution.id());
    }

    @Test
    @DisplayName("the engine reads the level of the flow it runs")
    void readsLevelForTheFlow() {
        run(FlowStepLogging.NONE, SUCCESS_FLOW);

        assertThat(flowStore.levelLookups).containsExactly(TENANT + "/" + FLOW);
    }

    @Test
    @DisplayName("FULL: a RUNNING row at step entry, updated with the output — same rows and snapshots as before")
    void fullWritesRowsAndSnapshots() {
        FlowExecutionData execution = run(FlowStepLogging.FULL, SUCCESS_FLOW);

        List<FlowStepLogData> steps = steps(execution);
        assertThat(steps).extracting(FlowStepLogData::stateId).containsExactly("A", "B", "Done");
        assertThat(steps).extracting(FlowStepLogData::status)
            .containsOnly(FlowStepLogData.STATUS_SUCCEEDED);
        assertThat(steps).allSatisfy(s -> {
            assertThat(s.inputSnapshot()).isNotNull();
            assertThat(s.outputSnapshot()).isNotNull();
        });
        assertThat(steps.get(0).inputSnapshot()).containsEntry("input", Map.of("k", "v"));
        assertThat(steps.get(0).outputSnapshot()).containsEntry("a", Map.of("a", 1));
        assertThat(steps.get(1).inputSnapshot()).containsKey("a").doesNotContainKey("b");
        assertThat(steps.get(1).outputSnapshot()).containsKeys("a", "b");
        // Insert at entry (RUNNING), then update on completion — one each per step.
        assertThat(flowStore.insertedStatuses).containsOnly(FlowStepLogData.STATUS_RUNNING).hasSize(3);
        assertThat(flowStore.updates).isEqualTo(3);
    }

    @Test
    @DisplayName("FULL: the failing step keeps its input and output snapshots")
    void fullFailingStep() {
        FlowExecutionData execution = run(FlowStepLogging.FULL, FAILING_FLOW);

        List<FlowStepLogData> steps = steps(execution);
        assertThat(steps).extracting(FlowStepLogData::stateId).containsExactly("Prepare", "Boom");
        FlowStepLogData failed = steps.get(1);
        assertThat(failed.status()).isEqualTo(FlowStepLogData.STATUS_FAILED);
        assertThat(failed.inputSnapshot()).containsKey("prep");
        assertThat(failed.outputSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("ERRORS_ONLY: a row per step with id, name, type, status and duration, but no snapshots")
    void errorsOnlySuccessHasNoSnapshots() {
        FlowExecutionData execution = run(FlowStepLogging.ERRORS_ONLY, SUCCESS_FLOW);

        List<FlowStepLogData> steps = steps(execution);
        assertThat(steps).extracting(FlowStepLogData::stateId).containsExactly("A", "B", "Done");
        assertThat(steps).extracting(FlowStepLogData::stateName).containsExactly("A", "B", "Done");
        assertThat(steps).extracting(FlowStepLogData::stateType).containsExactly("Pass", "Pass", "Succeed");
        assertThat(steps).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo(FlowStepLogData.STATUS_SUCCEEDED);
            assertThat(s.durationMs()).isNotNull().isGreaterThanOrEqualTo(0);
            assertThat(s.inputSnapshot()).isNull();
            assertThat(s.outputSnapshot()).isNull();
        });
        // One insert per step once its outcome is known; no RUNNING row, no update.
        assertThat(flowStore.insertedStatuses).doesNotContain(FlowStepLogData.STATUS_RUNNING);
        assertThat(flowStore.updates).isZero();
    }

    @Test
    @DisplayName("ERRORS_ONLY: the failing step's row carries its input and output snapshots")
    void errorsOnlyFailingStepHasSnapshots() {
        FlowExecutionData execution = run(FlowStepLogging.ERRORS_ONLY, FAILING_FLOW);

        List<FlowStepLogData> steps = steps(execution);
        assertThat(steps).extracting(FlowStepLogData::stateId).containsExactly("Prepare", "Boom");
        FlowStepLogData ok = steps.get(0);
        assertThat(ok.inputSnapshot()).isNull();
        assertThat(ok.outputSnapshot()).isNull();

        FlowStepLogData failed = steps.get(1);
        assertThat(failed.status()).isEqualTo(FlowStepLogData.STATUS_FAILED);
        assertThat(failed.stateType()).isEqualTo("Task");
        assertThat(failed.errorMessage()).isEqualTo("Handler intentionally failed");
        assertThat(failed.durationMs()).isNotNull();
        // The input is the state the step was entered with, held from step entry.
        assertThat(failed.inputSnapshot())
            .containsEntry("prep", Map.of("ready", true))
            .containsEntry("input", Map.of("k", "v"));
        assertThat(failed.outputSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("NONE: a successful run writes no step rows")
    void noneSuccessWritesNothing() {
        FlowExecutionData execution = run(FlowStepLogging.NONE, SUCCESS_FLOW);

        assertThat(steps(execution)).isEmpty();
        assertThat(execution.stepCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("NONE: only the failing step's row is written, with its snapshots")
    void noneWritesOnlyFailingStep() {
        FlowExecutionData execution = run(FlowStepLogging.NONE, FAILING_FLOW);

        List<FlowStepLogData> steps = steps(execution);
        assertThat(steps).hasSize(1);
        FlowStepLogData failed = steps.get(0);
        assertThat(failed.stateId()).isEqualTo("Boom");
        assertThat(failed.status()).isEqualTo(FlowStepLogData.STATUS_FAILED);
        assertThat(failed.inputSnapshot()).containsKey("prep");
        assertThat(failed.outputSnapshot()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FlowStepLogging.class)
    @DisplayName("the execution row (status, timings, error) is written at every level")
    void executionRowAlwaysWritten(FlowStepLogging level) {
        FlowExecutionData succeeded = run(level, SUCCESS_FLOW);
        assertThat(succeeded.status()).isEqualTo(FlowExecutionData.STATUS_COMPLETED);
        assertThat(succeeded.startedAt()).isNotNull();
        assertThat(succeeded.completedAt()).isNotNull();
        assertThat(succeeded.durationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(succeeded.stepCount()).isEqualTo(3);
        assertThat(succeeded.errorMessage()).isNull();

        FlowExecutionData failed = run(level, FAILING_FLOW);
        assertThat(failed.status()).isEqualTo(FlowExecutionData.STATUS_FAILED);
        assertThat(failed.startedAt()).isNotNull();
        assertThat(failed.completedAt()).isNotNull();
        assertThat(failed.durationMs()).isNotNull();
        assertThat(failed.stepCount()).isEqualTo(2);
        assertThat(failed.errorMessage()).isEqualTo("Handler intentionally failed");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = FlowStepLogging.class, names = {"ERRORS_ONLY", "NONE"})
    @DisplayName("a handler that throws leaves the failing step's row")
    void thrownStepIsLogged(FlowStepLogging level) {
        flowStore.level = level;
        String json = """
            { "StartAt": "Throw", "States": { "Throw": { "Type": "Task", "Resource": "THROWS", "End": true } } }
            """;

        // TaskStateExecutor turns the handler exception into a FAILED result.
        engine.executeSynchronous(TENANT, FLOW, json, Map.of("x", 1), "u1");

        List<FlowStepLogData> steps = flowStore.loadStepLogs(flowStore.lastExecutionId);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).status()).isEqualTo(FlowStepLogData.STATUS_FAILED);
        assertThat(steps.get(0).inputSnapshot()).containsEntry("x", 1);
    }

    @Test
    @DisplayName("Parallel branches inherit the parent's level")
    void subFlowsInheritLevel() {
        String json = """
            {
                "StartAt": "Fan",
                "States": {
                    "Fan": {
                        "Type": "Parallel",
                        "Branches": [
                            { "StartAt": "X", "States": { "X": { "Type": "Pass", "End": true } } },
                            { "StartAt": "Y", "States": { "Y": { "Type": "Pass", "End": true } } }
                        ],
                        "ResultPath": "$.fan",
                        "End": true
                    }
                }
            }
            """;

        FlowExecutionData execution = run(FlowStepLogging.NONE, json);

        assertThat(steps(execution)).isEmpty();
    }

    @Test
    @DisplayName("unknown or missing stored values parse as FULL")
    void parsing() {
        assertThat(FlowStepLogging.fromValue("errors_only")).isEqualTo(FlowStepLogging.ERRORS_ONLY);
        assertThat(FlowStepLogging.fromValue("NONE")).isEqualTo(FlowStepLogging.NONE);
        assertThat(FlowStepLogging.fromValue(null)).isEqualTo(FlowStepLogging.FULL);
        assertThat(FlowStepLogging.fromValue("")).isEqualTo(FlowStepLogging.FULL);
        assertThat(FlowStepLogging.fromValue("VERBOSE")).isEqualTo(FlowStepLogging.FULL);
        assertThat(FlowStepLogging.names()).containsExactly("FULL", "ERRORS_ONLY", "NONE");
        assertThatThrownBy(() -> FlowStepLogging.valueOf("VERBOSE"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** In-memory store that serves a configurable level and records how step rows were written. */
    static class RecordingFlowStore extends FlowEngineTest.InMemoryFlowStore {
        volatile FlowStepLogging level = FlowStepLogging.FULL;
        final List<String> levelLookups = new ArrayList<>();
        final List<String> insertedStatuses = new ArrayList<>();
        int updates;
        volatile String lastExecutionId;

        @Override
        public String createExecution(String tenantId, String flowId, String startedBy,
                                      String triggerRecordId, Map<String, Object> initialInput,
                                      boolean isTest) {
            lastExecutionId = super.createExecution(tenantId, flowId, startedBy, triggerRecordId,
                initialInput, isTest);
            return lastExecutionId;
        }

        @Override
        public FlowStepLogging findStepLogging(String tenantId, String flowId) {
            levelLookups.add(tenantId + "/" + flowId);
            return level;
        }

        @Override
        public synchronized String logStepExecution(String executionId, String stateId, String stateName,
                                                    String stateType, Map<String, Object> inputSnapshot,
                                                    Map<String, Object> outputSnapshot, String status,
                                                    String errorMessage, String errorCode,
                                                    Integer durationMs, int attemptNumber) {
            insertedStatuses.add(status);
            return super.logStepExecution(executionId, stateId, stateName, stateType, inputSnapshot,
                outputSnapshot, status, errorMessage, errorCode, durationMs, attemptNumber);
        }

        @Override
        public synchronized void updateStepLog(String stepLogId, Map<String, Object> outputSnapshot,
                                               String status, String errorMessage, String errorCode,
                                               int durationMs) {
            updates++;
            super.updateStepLog(stepLogId, outputSnapshot, status, errorMessage, errorCode, durationMs);
        }
    }
}
