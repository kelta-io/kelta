package io.kelta.runtime.flow;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FlowEngine")
class FlowEngineTest {

    private FlowEngine engine;
    private InMemoryFlowStore flowStore;
    private ActionHandlerRegistry handlerRegistry;

    @BeforeEach
    void setUp() {
        flowStore = new InMemoryFlowStore();
        handlerRegistry = new ActionHandlerRegistry();
        ObjectMapper objectMapper = new ObjectMapper();

        // Register a simple test handler
        handlerRegistry.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "LOG_MESSAGE"; }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.success(Map.of("logged", true));
            }
        });

        // Register an echo handler that returns its input
        handlerRegistry.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "ECHO"; }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.success(context.data() != null ? context.data() : Map.of());
            }
        });

        // Register a failing handler
        handlerRegistry.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "FAIL_HANDLER"; }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.failure("Handler intentionally failed");
            }
        });

        // Fails for items whose 'shouldFail' field is true; succeeds otherwise.
        // Used by Map error-aggregation tests so the same handler is hit per
        // item with different outcomes — closer to a real partial-failure case
        // than wiring two separate handlers.
        handlerRegistry.register(new ActionHandler() {
            @Override
            public String getActionTypeKey() { return "CONDITIONAL_FAIL"; }

            @Override
            public ActionResult execute(ActionContext context) {
                Object shouldFail = context.data() != null ? context.data().get("shouldFail") : null;
                if (Boolean.TRUE.equals(shouldFail)) {
                    return ActionResult.failure("Item flagged shouldFail=true");
                }
                return ActionResult.success(Map.of("processed", true));
            }
        });

        engine = new FlowEngine(flowStore, handlerRegistry, objectMapper, 2);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Nested
    @DisplayName("Simple Flows")
    class SimpleFlows {

        @Test
        @DisplayName("executes single Task → Succeed flow")
        void singleTaskToSucceed() {
            String json = """
                {
                    "StartAt": "Log",
                    "States": {
                        "Log": {
                            "Type": "Task",
                            "Resource": "LOG_MESSAGE",
                            "ResultPath": "$.logResult",
                            "Next": "Done"
                        },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> input = Map.of("message", "hello");
            Map<String, Object> result = engine.executeSynchronous("t1", "f1", json, input, "u1");

            assertNotNull(result);
            // Result should have the log result at $.logResult
            assertNotNull(result.get("logResult"));
        }

        @Test
        @DisplayName("executes Pass state with literal result")
        void passStateWithResult() {
            String json = """
                {
                    "StartAt": "Inject",
                    "States": {
                        "Inject": {
                            "Type": "Pass",
                            "Result": { "defaultPriority": "HIGH" },
                            "ResultPath": "$.defaults",
                            "Next": "Done"
                        },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> result = engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            assertNotNull(result.get("defaults"));
            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) result.get("defaults");
            assertEquals("HIGH", defaults.get("defaultPriority"));
        }

        @Test
        @DisplayName("Fail state produces failure")
        void failStateProducesFailure() {
            String json = """
                {
                    "StartAt": "Error",
                    "States": {
                        "Error": {
                            "Type": "Fail",
                            "Error": "ValidationFailed",
                            "Cause": "Missing required field"
                        }
                    }
                }
                """;

            engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            // The execution should be marked as FAILED
            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            assertFalse(executions.isEmpty());
            assertEquals(FlowExecutionData.STATUS_FAILED, executions.get(0).status());
        }
    }

    @Nested
    @DisplayName("Choice Branching")
    class ChoiceBranching {

        @Test
        @DisplayName("Choice routes to matching rule")
        void choiceRoutesToMatchingRule() {
            String json = """
                {
                    "StartAt": "Check",
                    "States": {
                        "Check": {
                            "Type": "Choice",
                            "Choices": [
                                {
                                    "Variable": "$.status",
                                    "StringEquals": "ACTIVE",
                                    "Next": "ActivePath"
                                }
                            ],
                            "Default": "DefaultPath"
                        },
                        "ActivePath": { "Type": "Succeed" },
                        "DefaultPath": {
                            "Type": "Fail",
                            "Error": "UnexpectedDefault",
                            "Cause": "Should not reach default"
                        }
                    }
                }
                """;

            Map<String, Object> input = Map.of("status", "ACTIVE");
            Map<String, Object> result = engine.executeSynchronous("t1", "f1", json, input, "u1");

            // Should have reached Succeed, not Fail
            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            assertEquals(FlowExecutionData.STATUS_COMPLETED, executions.get(0).status());
        }

        @Test
        @DisplayName("Choice falls through to default")
        void choiceFallsToDefault() {
            String json = """
                {
                    "StartAt": "Check",
                    "States": {
                        "Check": {
                            "Type": "Choice",
                            "Choices": [
                                {
                                    "Variable": "$.status",
                                    "StringEquals": "ACTIVE",
                                    "Next": "ActivePath"
                                }
                            ],
                            "Default": "DefaultPath"
                        },
                        "ActivePath": {
                            "Type": "Fail",
                            "Error": "UnexpectedActive",
                            "Cause": "Should not match"
                        },
                        "DefaultPath": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> input = Map.of("status", "INACTIVE");
            engine.executeSynchronous("t1", "f1", json, input, "u1");

            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            assertEquals(FlowExecutionData.STATUS_COMPLETED, executions.get(0).status());
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Catch policy redirects to fallback state")
        void catchRedirectsToFallback() {
            String json = """
                {
                    "StartAt": "Risky",
                    "States": {
                        "Risky": {
                            "Type": "Task",
                            "Resource": "FAIL_HANDLER",
                            "Next": "ShouldNotReach",
                            "Catch": [
                                {
                                    "ErrorEquals": ["States.ALL"],
                                    "Next": "HandleError",
                                    "ResultPath": "$.error"
                                }
                            ]
                        },
                        "ShouldNotReach": { "Type": "Succeed" },
                        "HandleError": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> result = engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            // Should have been caught and redirected to HandleError → Succeed
            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            assertEquals(FlowExecutionData.STATUS_COMPLETED, executions.get(0).status());

            // Error data should be at $.error
            assertNotNull(result.get("error"));
        }

        @Test
        @DisplayName("uncaught failure fails execution")
        void uncaughtFailureFailsExecution() {
            String json = """
                {
                    "StartAt": "Risky",
                    "States": {
                        "Risky": {
                            "Type": "Task",
                            "Resource": "FAIL_HANDLER",
                            "End": true
                        }
                    }
                }
                """;

            engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            assertEquals(FlowExecutionData.STATUS_FAILED, executions.get(0).status());
        }

        @Test
        @DisplayName("missing resource produces failure")
        void missingResourceFailsExecution() {
            String json = """
                {
                    "StartAt": "Bad",
                    "States": {
                        "Bad": {
                            "Type": "Task",
                            "Resource": "NON_EXISTENT",
                            "End": true
                        }
                    }
                }
                """;

            engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            assertEquals(FlowExecutionData.STATUS_FAILED, executions.get(0).status());
        }
    }

    @Nested
    @DisplayName("Data Flow")
    class DataFlow {

        @Test
        @DisplayName("InputPath → ResultPath → OutputPath pipeline")
        void fullDataFlowPipeline() {
            String json = """
                {
                    "StartAt": "Process",
                    "States": {
                        "Process": {
                            "Type": "Task",
                            "Resource": "ECHO",
                            "InputPath": "$.order",
                            "ResultPath": "$.processResult",
                            "Next": "Done"
                        },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("order", Map.of("id", "ord-1", "amount", 100));
            input.put("metadata", Map.of("source", "api"));

            Map<String, Object> result = engine.executeSynchronous("t1", "f1", json, input, "u1");

            // processResult should contain the echo of the order data
            assertNotNull(result.get("processResult"));
            // metadata should be preserved
            assertNotNull(result.get("metadata"));
        }

        @Test
        @DisplayName("chained tasks accumulate state")
        void chainedTasksAccumulateState() {
            String json = """
                {
                    "StartAt": "Step1",
                    "States": {
                        "Step1": {
                            "Type": "Pass",
                            "Result": { "step1": "done" },
                            "ResultPath": "$.results.step1",
                            "Next": "Step2"
                        },
                        "Step2": {
                            "Type": "Pass",
                            "Result": { "step2": "done" },
                            "ResultPath": "$.results.step2",
                            "Next": "Done"
                        },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> result = engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            @SuppressWarnings("unchecked")
            Map<String, Object> results = (Map<String, Object>) result.get("results");
            assertNotNull(results);
            assertNotNull(results.get("step1"));
            assertNotNull(results.get("step2"));
        }
    }

    @Nested
    @DisplayName("Step Logging")
    class StepLogging {

        @Test
        @DisplayName("records step logs for each state")
        void recordsStepLogs() {
            String json = """
                {
                    "StartAt": "A",
                    "States": {
                        "A": { "Type": "Pass", "Next": "B" },
                        "B": { "Type": "Pass", "Next": "C" },
                        "C": { "Type": "Succeed" }
                    }
                }
                """;

            engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            List<FlowExecutionData> executions = flowStore.getAllExecutions();
            String execId = executions.get(0).id();
            List<FlowStepLogData> steps = flowStore.loadStepLogs(execId);

            assertEquals(3, steps.size());
        }
    }

    @Nested
    @DisplayName("Map Error Aggregation")
    class MapErrorAggregation {

        // Map iterator that processes each item via CONDITIONAL_FAIL and
        // catches any error so the iteration completes "successfully". This is
        // the pattern the task focuses on: catches that previously swallowed
        // per-iteration failures and reported overall success.
        private String mapFlow(boolean failOnPartial) {
            return """
                {
                    "StartAt": "Process",
                    "States": {
                        "Process": {
                            "Type": "Map",
                            "ItemsPath": "$.items",
                            "ResultPath": "$.result",
                            %s
                            "Iterator": {
                                "StartAt": "Do",
                                "States": {
                                    "Do": {
                                        "Type": "Task",
                                        "Resource": "CONDITIONAL_FAIL",
                                        "End": true,
                                        "Catch": [
                                            {
                                                "ErrorEquals": ["States.ALL"],
                                                "Next": "Recovered",
                                                "ResultPath": "$.error"
                                            }
                                        ]
                                    },
                                    "Recovered": { "Type": "Succeed" }
                                }
                            },
                            "End": true
                        }
                    }
                }
                """.formatted(failOnPartial ? "\"FailOnPartial\": true," : "");
        }

        @Test
        @DisplayName("all-success Map reports failed=0 and preserves items")
        @SuppressWarnings("unchecked")
        void allSuccessReportsZeroFailed() {
            Map<String, Object> input = Map.of("items", List.of(
                Map.of("id", "a", "shouldFail", false),
                Map.of("id", "b", "shouldFail", false)
            ));

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", mapFlow(false), input, "u1");

            Map<String, Object> aggregate = (Map<String, Object>) result.get("result");
            assertNotNull(aggregate, "Map result should be at $.result");
            assertEquals(2, ((Number) aggregate.get("succeeded")).intValue());
            assertEquals(0, ((Number) aggregate.get("failed")).intValue());
            assertTrue(((List<?>) aggregate.get("errors")).isEmpty());
            assertEquals(2, ((List<?>) aggregate.get("items")).size());

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_COMPLETED, execution.status());
            assertEquals(0, execution.failedCount(),
                "No partial failures → no _failedCount on the execution summary");
        }

        @Test
        @DisplayName("partial-failure Map surfaces caught errors with sample and failedCount")
        @SuppressWarnings("unchecked")
        void partialFailureSurfacesCaughtErrors() {
            Map<String, Object> input = Map.of("items", List.of(
                Map.of("id", "a", "shouldFail", false),
                Map.of("id", "b", "shouldFail", true),
                Map.of("id", "c", "shouldFail", false),
                Map.of("id", "d", "shouldFail", true)
            ));

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", mapFlow(false), input, "u1");

            Map<String, Object> aggregate = (Map<String, Object>) result.get("result");
            assertEquals(2, ((Number) aggregate.get("succeeded")).intValue());
            assertEquals(2, ((Number) aggregate.get("failed")).intValue());

            List<Map<String, Object>> errors =
                (List<Map<String, Object>>) aggregate.get("errors");
            assertEquals(2, errors.size(), "Both caught errors should be sampled");
            // Index ordering preserves iteration order — items 1 and 3.
            assertEquals(1, ((Number) errors.get(0).get("index")).intValue());
            assertEquals(3, ((Number) errors.get(1).get("index")).intValue());
            // The catch fired from the Do task with an ActionFailed code.
            assertEquals("Do", errors.get(0).get("stateId"));
            assertEquals("ActionFailed", errors.get(0).get("error"));

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            // Default failOnPartial=false → status still COMPLETED, but the
            // execution summary now carries the failedCount so the truth is
            // not lost.
            assertEquals(FlowExecutionData.STATUS_COMPLETED, execution.status());
            assertEquals(2, execution.failedCount());
        }

        @Test
        @DisplayName("failOnPartial=true marks run FAILED when any iteration caught an error")
        @SuppressWarnings("unchecked")
        void failOnPartialMarksRunFailed() {
            Map<String, Object> input = Map.of("items", List.of(
                Map.of("id", "a", "shouldFail", false),
                Map.of("id", "b", "shouldFail", true)
            ));

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", mapFlow(true), input, "u1");

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_FAILED, execution.status());
            assertEquals(1, execution.failedCount());
            assertTrue(execution.errorMessage().contains("failOnPartial"),
                "Error message should reference the failOnPartial policy");

            // The aggregate is preserved at the configured ResultPath even when
            // the state fails, so callers can inspect the per-item sample.
            Map<String, Object> aggregate = (Map<String, Object>) result.get("result");
            assertNotNull(aggregate);
            assertEquals(1, ((Number) aggregate.get("failed")).intValue());
            assertEquals(1, ((Number) aggregate.get("succeeded")).intValue());
        }

        @Test
        @DisplayName("error sample is capped at MAX_ERROR_SAMPLES")
        @SuppressWarnings("unchecked")
        void errorSampleIsCapped() {
            // 12 failing items > MAX_ERROR_SAMPLES (10).
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                items.add(Map.of("id", "x" + i, "shouldFail", true));
            }

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", mapFlow(false), Map.of("items", items), "u1");

            Map<String, Object> aggregate = (Map<String, Object>) result.get("result");
            assertEquals(12, ((Number) aggregate.get("failed")).intValue());
            List<?> errors = (List<?>) aggregate.get("errors");
            assertEquals(io.kelta.runtime.flow.executor.MapStateExecutor.MAX_ERROR_SAMPLES,
                errors.size(), "errors list must not exceed the configured cap");
        }
    }

    @Nested
    @DisplayName("InvokeFlow")
    class InvokeFlowTests {

        /** A "worker" sub-flow that echoes its input under an {@code echoed} key. */
        private static final String WORKER_FLOW = """
            {
                "StartAt": "DoWork",
                "States": {
                    "DoWork": {
                        "Type": "Task",
                        "Resource": "ECHO",
                        "ResultPath": "$.echoed",
                        "End": true
                    }
                }
            }
            """;

        @Test
        @DisplayName("direct InvokeFlow merges sub-flow result at ResultPath")
        @SuppressWarnings("unchecked")
        void directInvokeMergesResult() {
            flowStore.registerFlow("t1", "worker-1", "Worker", WORKER_FLOW);

            String dispatcher = """
                {
                    "StartAt": "Call",
                    "States": {
                        "Call": {
                            "Type": "InvokeFlow",
                            "FlowId": "worker-1",
                            "Input": { "slug": "tubi" },
                            "ResultPath": "$.workerResult",
                            "Next": "Done"
                        },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", dispatcher, Map.of(), "u1");

            Map<String, Object> workerResult = (Map<String, Object>) result.get("workerResult");
            assertNotNull(workerResult, "Sub-flow output should be placed at $.workerResult");
            // The worker stores its input echo at $.echoed
            assertNotNull(workerResult.get("echoed"));
            // The dispatcher's original input was passed through to the worker
            Map<String, Object> echoed = (Map<String, Object>) workerResult.get("echoed");
            assertEquals("tubi", echoed.get("slug"));

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_COMPLETED, execution.status());
        }

        @Test
        @DisplayName("InvokeFlow by name resolves the target flow")
        @SuppressWarnings("unchecked")
        void invokeByName() {
            flowStore.registerFlow("t1", "worker-1", "Worker", WORKER_FLOW);

            String dispatcher = """
                {
                    "StartAt": "Call",
                    "States": {
                        "Call": {
                            "Type": "InvokeFlow",
                            "FlowName": "Worker",
                            "Input": { "slug": "${$.slug}" },
                            "ResultPath": "$.workerResult",
                            "End": true
                        }
                    }
                }
                """;

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", dispatcher, Map.of("slug", "pluto"), "u1");

            Map<String, Object> workerResult = (Map<String, Object>) result.get("workerResult");
            Map<String, Object> echoed = (Map<String, Object>) workerResult.get("echoed");
            assertEquals("pluto", echoed.get("slug"));
        }

        @Test
        @DisplayName("missing target flow surfaces FlowNotFound and fails the execution")
        void missingTargetFails() {
            String dispatcher = """
                {
                    "StartAt": "Call",
                    "States": {
                        "Call": {
                            "Type": "InvokeFlow",
                            "FlowId": "does-not-exist",
                            "End": true
                        }
                    }
                }
                """;

            engine.executeSynchronous("t1", "f1", dispatcher, Map.of(), "u1");

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_FAILED, execution.status());
            assertTrue(execution.errorMessage().contains("does-not-exist"),
                "Error message should name the missing flow");
        }

        @Test
        @DisplayName("missing flow is recoverable via Catch")
        @SuppressWarnings("unchecked")
        void missingFlowCaughtByCatch() {
            String dispatcher = """
                {
                    "StartAt": "Call",
                    "States": {
                        "Call": {
                            "Type": "InvokeFlow",
                            "FlowId": "does-not-exist",
                            "Next": "ShouldNotReach",
                            "Catch": [
                                {
                                    "ErrorEquals": ["States.ALL"],
                                    "Next": "Recovered",
                                    "ResultPath": "$.error"
                                }
                            ]
                        },
                        "ShouldNotReach": {
                            "Type": "Fail",
                            "Error": "ShouldNotReach"
                        },
                        "Recovered": { "Type": "Succeed" }
                    }
                }
                """;

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", dispatcher, Map.of(), "u1");

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_COMPLETED, execution.status());
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertNotNull(error);
            assertEquals("FlowNotFound", error.get("Error"));
        }

        @Test
        @DisplayName("InvokeFlow inside Map dispatches sub-flow per item")
        @SuppressWarnings("unchecked")
        void invokeInsideMap() {
            flowStore.registerFlow("t1", "worker-1", "Worker", WORKER_FLOW);

            // Dispatcher Maps over a list of {slug} items and invokes the
            // shared worker flow once per item with MaxConcurrency:1 — the
            // exact pattern the brief calls out (5 providers, 1 ingest flow).
            String dispatcher = """
                {
                    "StartAt": "Fanout",
                    "States": {
                        "Fanout": {
                            "Type": "Map",
                            "ItemsPath": "$.providers",
                            "MaxConcurrency": 1,
                            "ResultPath": "$.summary",
                            "Iterator": {
                                "StartAt": "RunPerSlug",
                                "States": {
                                    "RunPerSlug": {
                                        "Type": "InvokeFlow",
                                        "FlowName": "Worker",
                                        "Input": { "slug": "${$.slug}" },
                                        "ResultPath": "$.workerResult",
                                        "End": true
                                    }
                                }
                            },
                            "End": true
                        }
                    }
                }
                """;

            Map<String, Object> input = Map.of("providers", List.of(
                Map.of("slug", "tubi"),
                Map.of("slug", "pluto"),
                Map.of("slug", "roku")
            ));

            Map<String, Object> result = engine.executeSynchronous(
                "t1", "f1", dispatcher, input, "u1");

            Map<String, Object> summary = (Map<String, Object>) result.get("summary");
            assertEquals(3, ((Number) summary.get("succeeded")).intValue());
            assertEquals(0, ((Number) summary.get("failed")).intValue());

            List<Map<String, Object>> items = (List<Map<String, Object>>) summary.get("items");
            assertEquals(3, items.size());
            // Each iteration's state contains a workerResult.echoed.slug
            Map<String, Object> firstEcho = (Map<String, Object>)
                ((Map<String, Object>) items.get(0).get("workerResult")).get("echoed");
            assertEquals("tubi", firstEcho.get("slug"));
        }

        @Test
        @DisplayName("recursion past MAX_INVOKE_DEPTH fails with FlowDepthExceeded")
        void depthGuardStopsRecursion() {
            // Self-invoking flow: each invocation calls itself again.
            // Without the depth guard this would blow the stack.
            String selfInvoker = """
                {
                    "StartAt": "Recurse",
                    "States": {
                        "Recurse": {
                            "Type": "InvokeFlow",
                            "FlowName": "Recurser",
                            "End": true
                        }
                    }
                }
                """;
            flowStore.registerFlow("t1", "recurser-1", "Recurser", selfInvoker);

            engine.executeSynchronous("t1", "recurser-1", selfInvoker, Map.of(), "u1");

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_FAILED, execution.status());
            assertTrue(execution.errorMessage().contains("MAX_INVOKE_DEPTH"),
                "Error message should reference the depth cap; got: " + execution.errorMessage());
        }
    }

    @Nested
    @DisplayName("Async Execution")
    class AsyncExecution {

        @Test
        @DisplayName("startExecution returns execution ID immediately")
        void startExecutionReturnsImmediately() {
            String json = """
                {
                    "StartAt": "Done",
                    "States": {
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;

            String executionId = engine.startExecution("t1", "f1", json, Map.of(), "u1", null, false);
            assertNotNull(executionId);
            assertFalse(executionId.isEmpty());
        }

        @Test
        @DisplayName("cancel execution marks as cancelled")
        void cancelExecution() {
            String executionId = flowStore.createExecution("t1", "f1", "u1", null, Map.of(), false);
            engine.cancelExecution(executionId);

            Optional<FlowExecutionData> exec = flowStore.loadExecution(executionId);
            assertTrue(exec.isPresent());
            assertEquals(FlowExecutionData.STATUS_CANCELLED, exec.get().status());
        }
    }

    // -------------------------------------------------------------------------
    // In-Memory FlowStore for testing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Wait resume")
    class WaitResume {

        /** Long Wait (60s > inline cap) then a Task, then Succeed. */
        private static final String LONG_WAIT_FLOW = """
            {
                "StartAt": "Sleep",
                "States": {
                    "Sleep": { "Type": "Wait", "Seconds": 60, "Next": "AfterWait" },
                    "AfterWait": {
                        "Type": "Task",
                        "Resource": "LOG_MESSAGE",
                        "ResultPath": "$.after",
                        "Next": "Done"
                    },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;

        @Test
        @DisplayName("long Wait parks the execution WAITING (not COMPLETED) and schedules a timed resume")
        void longWaitParksWaitingAndSchedulesResume() {
            Instant before = Instant.now();
            engine.executeSynchronous("t1", "f1", LONG_WAIT_FLOW, Map.of("k", "v"), "u1");

            FlowExecutionData execution = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_WAITING, execution.status(),
                "a parked Wait must stay WAITING — not be overwritten COMPLETED");
            assertEquals("Sleep", execution.currentNodeId());

            List<Map<String, Object>> pending = flowStore.getPendingResumes();
            assertEquals(1, pending.size());
            Instant resumeAt = (Instant) pending.get(0).get("resumeAt");
            assertNotNull(resumeAt);
            assertFalse(resumeAt.isBefore(before.plusSeconds(59)), "resumeAt ≈ now+60s");
            assertNull(pending.get(0).get("resumeEvent"));
        }

        @Test
        @DisplayName("event Wait registers a pending resume under the event name")
        void eventWaitRegistersEventResume() {
            String json = """
                {
                    "StartAt": "AwaitApproval",
                    "States": {
                        "AwaitApproval": { "Type": "Wait", "EventName": "approval.granted", "Next": "Done" },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;
            engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");

            List<Map<String, Object>> pending = flowStore.getPendingResumes();
            assertEquals(1, pending.size());
            assertEquals("approval.granted", pending.get(0).get("resumeEvent"));
            assertNull(pending.get(0).get("resumeAt"));
        }

        @Test
        @DisplayName("resumeExecution continues from the Wait's Next state to completion")
        void resumeContinuesFromWaitNext() throws Exception {
            flowStore.registerFlow("t1", "flow-wait", "Waiter", LONG_WAIT_FLOW);
            engine.executeSynchronous("t1", "flow-wait", LONG_WAIT_FLOW, Map.of("k", "v"), "u1");
            FlowExecutionData waiting = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_WAITING, waiting.status());

            engine.resumeExecution(waiting.id());
            FlowExecutionData resumed = awaitStatus(waiting.id(), FlowExecutionData.STATUS_COMPLETED);

            assertEquals(FlowExecutionData.STATUS_COMPLETED, resumed.status());
            assertTrue(flowStore.getPendingResumes().isEmpty(), "pending resume row cleaned up");
            assertTrue(flowStore.loadStepLogs(waiting.id()).stream()
                    .anyMatch(s -> "AfterWait".equals(s.stateId())),
                "the post-Wait task must have executed");
        }

        @Test
        @DisplayName("resume binds the tenant slug so post-Wait steps resolve tenant-schema tables (#1577)")
        void resumeBindsTenantSlug() throws Exception {
            // The poller resumes from outside any tenant scope, so the slug cannot be
            // inherited; PhysicalTableStorageAdapter falls back to `public` without it.
            List<String> seenSlugs = Collections.synchronizedList(new ArrayList<>());
            handlerRegistry.register(new ActionHandler() {
                @Override
                public String getActionTypeKey() { return "CAPTURE_SLUG"; }

                @Override
                public ActionResult execute(ActionContext context) {
                    seenSlugs.add(TenantContext.getSlug());
                    return ActionResult.success(Map.of());
                }
            });
            String json = """
                {
                    "StartAt": "Sleep",
                    "States": {
                        "Sleep": { "Type": "Wait", "Seconds": 60, "Next": "AfterWait" },
                        "AfterWait": { "Type": "Task", "Resource": "CAPTURE_SLUG", "Next": "Done" },
                        "Done": { "Type": "Succeed" }
                    }
                }
                """;
            flowStore.registerTenant("t1", "acme");
            flowStore.registerFlow("t1", "flow-slug", "SlugWaiter", json);
            engine.executeSynchronous("t1", "flow-slug", json, Map.of(), "u1");
            FlowExecutionData waiting = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_WAITING, waiting.status());

            engine.resumeExecution(waiting.id());
            FlowExecutionData resumed = awaitStatus(waiting.id(), FlowExecutionData.STATUS_COMPLETED);

            assertEquals(FlowExecutionData.STATUS_COMPLETED, resumed.status());
            assertEquals(List.of("acme"), seenSlugs, "post-Wait step must run with the tenant slug bound");
        }

        @Test
        @DisplayName("resume fails the execution when the tenant is gone")
        void resumeMissingTenantFails() throws Exception {
            flowStore.registerFlow("t1", "flow-wait", "Waiter", LONG_WAIT_FLOW);
            engine.executeSynchronous("t1", "flow-wait", LONG_WAIT_FLOW, Map.of(), "u1");
            FlowExecutionData waiting = flowStore.getAllExecutions().get(0);
            flowStore.removeTenant("t1");

            engine.resumeExecution(waiting.id());
            FlowExecutionData failed = awaitStatus(waiting.id(), FlowExecutionData.STATUS_FAILED);

            assertEquals(FlowExecutionData.STATUS_FAILED, failed.status());
            assertTrue(failed.errorMessage().contains("tenant t1 no longer exists"));
            assertTrue(flowStore.getPendingResumes().isEmpty());
        }

        @Test
        @DisplayName("resume fails the execution when the flow definition is gone")
        void resumeMissingDefinitionFails() throws Exception {
            // Not registered in flowsById — findFlowDefinitionById returns empty
            engine.executeSynchronous("t1", "ghost-flow", LONG_WAIT_FLOW, Map.of(), "u1");
            FlowExecutionData waiting = flowStore.getAllExecutions().get(0);

            engine.resumeExecution(waiting.id());
            FlowExecutionData failed = awaitStatus(waiting.id(), FlowExecutionData.STATUS_FAILED);

            assertEquals(FlowExecutionData.STATUS_FAILED, failed.status());
            assertTrue(failed.errorMessage().contains("no longer exists"));
            assertTrue(flowStore.getPendingResumes().isEmpty());
        }

        @Test
        @DisplayName("resume of a non-WAITING execution is a no-op")
        void resumeNonWaitingIsNoop() {
            String json = """
                {
                    "StartAt": "Done",
                    "States": { "Done": { "Type": "Succeed" } }
                }
                """;
            engine.executeSynchronous("t1", "f1", json, Map.of(), "u1");
            FlowExecutionData completed = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_COMPLETED, completed.status());

            engine.resumeExecution(completed.id());

            assertEquals(FlowExecutionData.STATUS_COMPLETED,
                flowStore.loadExecution(completed.id()).orElseThrow().status());
        }

        @Test
        @DisplayName("terminal Wait (End=true) completes on resume")
        void terminalWaitCompletesOnResume() throws Exception {
            String json = """
                {
                    "StartAt": "Sleep",
                    "States": {
                        "Sleep": { "Type": "Wait", "Seconds": 60, "End": true }
                    }
                }
                """;
            flowStore.registerFlow("t1", "flow-tw", "TerminalWaiter", json);
            engine.executeSynchronous("t1", "flow-tw", json, Map.of(), "u1");
            FlowExecutionData waiting = flowStore.getAllExecutions().get(0);
            assertEquals(FlowExecutionData.STATUS_WAITING, waiting.status());

            engine.resumeExecution(waiting.id());
            FlowExecutionData resumed = awaitStatus(waiting.id(), FlowExecutionData.STATUS_COMPLETED);
            assertEquals(FlowExecutionData.STATUS_COMPLETED, resumed.status());
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
            return flowStore.loadExecution(executionId).orElseThrow();
        }
    }

    @Nested
    @DisplayName("Tenant-scoped handlers")
    class TenantScopedHandlers {

        /**
         * A step whose Resource comes from a runtime-loaded module, i.e. one
         * registered with {@code registerTenantHandler} rather than
         * {@code register}. Resolving against the global map alone made every
         * module-contributed step fail ResourceNotFound, which left the whole
         * runtime-module feature unreachable from a flow — installed, ACTIVE,
         * and silently unusable.
         */
        private static final String MODULE_STEP_FLOW = """
            {
                "StartAt": "Ingest",
                "States": {
                    "Ingest": {
                        "Type": "Task",
                        "Resource": "MODULE_STEP",
                        "ResultPath": "$.moduleResult",
                        "Next": "Done"
                    },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;

        private ActionHandler handlerReturning(String key, Map<String, Object> output) {
            return new ActionHandler() {
                @Override
                public String getActionTypeKey() { return key; }

                @Override
                public ActionResult execute(ActionContext context) {
                    return ActionResult.success(output);
                }
            };
        }

        @Test
        @DisplayName("a Task resolves a handler registered only for its tenant")
        void tenantHandlerResolves() {
            handlerRegistry.registerTenantHandler(
                "t1", handlerReturning("MODULE_STEP", Map.of("ranInModule", true)));

            Map<String, Object> result =
                engine.executeSynchronous("t1", "f1", MODULE_STEP_FLOW, Map.of(), "u1");

            assertNotNull(result.get("moduleResult"), "tenant-scoped handler was not resolved");
            @SuppressWarnings("unchecked")
            Map<String, Object> moduleResult = (Map<String, Object>) result.get("moduleResult");
            assertEquals(true, moduleResult.get("ranInModule"));
        }

        @Test
        @DisplayName("a tenant handler is invisible to another tenant")
        void tenantHandlerDoesNotLeakAcrossTenants() {
            // Modules are installed per tenant, so one tenant's step must not
            // become executable for everyone else.
            handlerRegistry.registerTenantHandler(
                "t1", handlerReturning("MODULE_STEP", Map.of("ranInModule", true)));

            FlowExecutionData failed = runToCompletion("t2", MODULE_STEP_FLOW);

            assertEquals(FlowExecutionData.STATUS_FAILED, failed.status());
            assertTrue(String.valueOf(failed.errorMessage()).contains("MODULE_STEP"),
                "expected ResourceNotFound naming the unresolved resource, got: "
                    + failed.errorMessage());
        }

        @Test
        @DisplayName("a tenant handler overrides a global one with the same key")
        void tenantHandlerTakesPriorityOverGlobal() {
            // The registry documents tenant handlers as taking priority; assert
            // it, since the override is the whole point of a per-tenant module.
            handlerRegistry.register(handlerReturning("OVERRIDABLE", Map.of("from", "global")));
            handlerRegistry.registerTenantHandler(
                "t1", handlerReturning("OVERRIDABLE", Map.of("from", "tenant")));

            String json = MODULE_STEP_FLOW.replace("MODULE_STEP", "OVERRIDABLE");

            @SuppressWarnings("unchecked")
            Map<String, Object> tenant = (Map<String, Object>)
                engine.executeSynchronous("t1", "f1", json, Map.of(), "u1").get("moduleResult");
            @SuppressWarnings("unchecked")
            Map<String, Object> other = (Map<String, Object>)
                engine.executeSynchronous("t2", "f1", json, Map.of(), "u1").get("moduleResult");

            assertEquals("tenant", tenant.get("from"));
            assertEquals("global", other.get("from"), "t2 should still see the global handler");
        }

        private FlowExecutionData runToCompletion(String tenantId, String definitionJson) {
            String executionId = engine.startExecution(
                tenantId, "f1", definitionJson, Map.of(), "u1", null, false);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Optional<FlowExecutionData> current = flowStore.loadExecution(executionId);
                if (current.isPresent()
                        && !FlowExecutionData.STATUS_RUNNING.equals(current.get().status())) {
                    return current.get();
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return flowStore.loadExecution(executionId).orElseThrow();
        }
    }

    static class InMemoryFlowStore implements FlowStore {
        private final Map<String, FlowExecutionData> executions = new ConcurrentHashMap<>();
        private final Map<String, List<FlowStepLogData>> stepLogs = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> pendingResumes = new ConcurrentHashMap<>();

        // Tenant-scoped flow definitions for InvokeFlow tests.
        // Key: tenantId + "::" + flowId (or flowName); Value: definition JSON
        private final Map<String, String> flowsById = new ConcurrentHashMap<>();
        private final Map<String, String> flowsByName = new ConcurrentHashMap<>();

        // Tenant slugs for resume tests. Unknown tenants resolve to "<id>-slug" so the
        // existing tests need no setup; removeTenant() simulates a deleted tenant.
        private final Map<String, String> tenantSlugs = new ConcurrentHashMap<>();
        private final Set<String> removedTenants = ConcurrentHashMap.newKeySet();

        void registerTenant(String tenantId, String slug) {
            tenantSlugs.put(tenantId, slug);
        }

        void removeTenant(String tenantId) {
            removedTenants.add(tenantId);
        }

        @Override
        public Optional<String> findTenantSlug(String tenantId) {
            if (removedTenants.contains(tenantId)) {
                return Optional.empty();
            }
            return Optional.of(tenantSlugs.getOrDefault(tenantId, tenantId + "-slug"));
        }

        void registerFlow(String tenantId, String flowId, String flowName, String definitionJson) {
            if (flowId != null) {
                flowsById.put(tenantId + "::" + flowId, definitionJson);
            }
            if (flowName != null) {
                flowsByName.put(tenantId + "::" + flowName, definitionJson);
            }
        }

        @Override
        public Optional<String> findFlowDefinitionById(String tenantId, String flowId) {
            return Optional.ofNullable(flowsById.get(tenantId + "::" + flowId));
        }

        @Override
        public Optional<String> findFlowDefinitionByName(String tenantId, String flowName) {
            return Optional.ofNullable(flowsByName.get(tenantId + "::" + flowName));
        }

        @Override
        public String createExecution(String tenantId, String flowId, String startedBy,
                                      String triggerRecordId, Map<String, Object> initialInput,
                                      boolean isTest) {
            String id = UUID.randomUUID().toString();
            executions.put(id, new FlowExecutionData(
                id, tenantId, flowId, FlowExecutionData.STATUS_RUNNING, startedBy,
                triggerRecordId, initialInput != null ? initialInput : Map.of(),
                null, null, 0, null, initialInput, isTest, Instant.now(), null));
            stepLogs.put(id, new ArrayList<>());
            return id;
        }

        @Override
        public Optional<FlowExecutionData> loadExecution(String executionId) {
            return Optional.ofNullable(executions.get(executionId));
        }

        @Override
        public void updateExecutionState(String executionId, String currentNodeId,
                                         Map<String, Object> stateData, String status, int stepCount) {
            FlowExecutionData existing = executions.get(executionId);
            if (existing != null) {
                executions.put(executionId, new FlowExecutionData(
                    existing.id(), existing.tenantId(), existing.flowId(), status,
                    existing.startedBy(), existing.triggerRecordId(), stateData,
                    currentNodeId, existing.errorMessage(), stepCount, existing.durationMs(),
                    existing.initialInput(), existing.isTest(), existing.startedAt(), null));
            }
        }

        @Override
        public void completeExecution(String executionId, String status, Map<String, Object> stateData,
                                      String errorMessage, int durationMs, int stepCount) {
            FlowExecutionData existing = executions.get(executionId);
            if (existing != null) {
                executions.put(executionId, new FlowExecutionData(
                    existing.id(), existing.tenantId(), existing.flowId(), status,
                    existing.startedBy(), existing.triggerRecordId(), stateData,
                    existing.currentNodeId(), errorMessage, stepCount, durationMs,
                    existing.initialInput(), existing.isTest(), existing.startedAt(), Instant.now()));
            }
        }

        @Override
        public void cancelExecution(String executionId) {
            FlowExecutionData existing = executions.get(executionId);
            if (existing != null) {
                executions.put(executionId, new FlowExecutionData(
                    existing.id(), existing.tenantId(), existing.flowId(),
                    FlowExecutionData.STATUS_CANCELLED,
                    existing.startedBy(), existing.triggerRecordId(), existing.stateData(),
                    existing.currentNodeId(), null, existing.stepCount(), existing.durationMs(),
                    existing.initialInput(), existing.isTest(), existing.startedAt(), Instant.now()));
            }
        }

        @Override
        public String logStepExecution(String executionId, String stateId, String stateName,
                                       String stateType, Map<String, Object> inputSnapshot,
                                       Map<String, Object> outputSnapshot, String status,
                                       String errorMessage, String errorCode,
                                       Integer durationMs, int attemptNumber) {
            String id = UUID.randomUUID().toString();
            FlowStepLogData log = new FlowStepLogData(
                id, executionId, stateId, stateName, stateType, status,
                inputSnapshot, outputSnapshot, errorMessage, errorCode,
                attemptNumber, durationMs, Instant.now(), null);
            stepLogs.computeIfAbsent(executionId, k -> new ArrayList<>()).add(log);
            return id;
        }

        @Override
        public void updateStepLog(String stepLogId, Map<String, Object> outputSnapshot,
                                  String status, String errorMessage, String errorCode, int durationMs) {
            // In-memory: find and replace
            for (List<FlowStepLogData> logs : stepLogs.values()) {
                for (int i = 0; i < logs.size(); i++) {
                    if (logs.get(i).id().equals(stepLogId)) {
                        FlowStepLogData old = logs.get(i);
                        logs.set(i, new FlowStepLogData(
                            old.id(), old.executionId(), old.stateId(), old.stateName(),
                            old.stateType(), status, old.inputSnapshot(), outputSnapshot,
                            errorMessage, errorCode, old.attemptNumber(), durationMs,
                            old.startedAt(), Instant.now()));
                        return;
                    }
                }
            }
        }

        @Override
        public List<FlowStepLogData> loadStepLogs(String executionId) {
            return stepLogs.getOrDefault(executionId, List.of());
        }

        @Override
        public List<FlowExecutionData> findExecutionsByFlow(String flowId, int limit, int offset) {
            return executions.values().stream()
                .filter(e -> e.flowId().equals(flowId))
                .toList();
        }

        @Override
        public List<FlowExecutionData> findWaitingExecutions() {
            return executions.values().stream()
                .filter(e -> FlowExecutionData.STATUS_WAITING.equals(e.status()))
                .toList();
        }

        @Override
        public String createPendingResume(String executionId, String tenantId,
                                          Instant resumeAt, String resumeEvent) {
            String id = UUID.randomUUID().toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("executionId", executionId);
            row.put("tenantId", tenantId);
            if (resumeAt != null) {
                row.put("resumeAt", resumeAt);
            }
            if (resumeEvent != null) {
                row.put("resumeEvent", resumeEvent);
            }
            pendingResumes.put(id, row);
            return id;
        }

        List<Map<String, Object>> getPendingResumes() {
            return new ArrayList<>(pendingResumes.values());
        }

        @Override
        public List<String> claimPendingResumes(String claimedBy, int limit) {
            return List.of();
        }

        @Override
        public Optional<String> claimPendingResumeByEvent(String resumeEvent, String claimedBy) {
            return Optional.empty();
        }

        @Override
        public void deletePendingResume(String executionId) {
            pendingResumes.values().removeIf(v -> executionId.equals(v.get("executionId")));
        }

        @Override
        public void logAuditEvent(String tenantId, String flowId, String action,
                                  String userId, Map<String, Object> details) {
            // No-op in tests
        }

        public List<FlowExecutionData> getAllExecutions() {
            return new ArrayList<>(executions.values());
        }
    }
}
