package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowExecutionData;
import io.kelta.runtime.flow.FlowStore;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TriggerFlowActionHandler")
class TriggerFlowActionHandlerTest {

    private static final String TENANT = "t1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowStore flowStore;
    private ActionHandlerRegistry registry;
    private FlowEngine engine;
    private TriggerFlowActionHandler handler;

    @BeforeEach
    void setUp() {
        flowStore = mock(FlowStore.class);
        AtomicInteger ids = new AtomicInteger();
        when(flowStore.createExecution(anyString(), anyString(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> "exec-" + ids.incrementAndGet());
        registry = new ActionHandlerRegistry();
        engine = new FlowEngine(flowStore, registry, objectMapper, 4);
        handler = new TriggerFlowActionHandler(objectMapper, engine);
        registry.register(handler);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private static String succeedFlow() {
        return """
            {"StartAt": "Done", "States": {"Done": {"Type": "Succeed"}}}
            """;
    }

    /** A flow whose only step TRIGGER_FLOWs {@code target}. */
    private static String triggeringFlow(String target) {
        return """
            {"StartAt": "Trigger", "States": {"Trigger": {
                "Type": "Task", "Resource": "TRIGGER_FLOW",
                "Parameters": {"flowId": "%s"}, "End": true}}}
            """.formatted(target);
    }

    private static ActionContext context(String config, int triggerDepth) {
        return ActionContext.builder()
            .tenantId(TENANT).userId("user-1").data(Map.of())
            .actionConfigJson(config).triggerDepth(triggerDepth).build();
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("TRIGGER_FLOW", handler.getActionTypeKey());
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should validate config requires flowId")
        void shouldValidateConfigRequiresFlowId() {
            assertThrows(IllegalArgumentException.class, () -> handler.validate("{}"));
        }

        @Test
        @DisplayName("Should accept valid config with flowId")
        void shouldAcceptValidConfigWithFlowId() {
            assertDoesNotThrow(() -> handler.validate("{\"flowId\": \"flow-123\"}"));
        }

        @Test
        @DisplayName("Should reject config that only carries the removed workflowRuleId key")
        void shouldRejectWorkflowRuleIdOnlyConfig() {
            assertThrows(IllegalArgumentException.class,
                () -> handler.validate("{\"workflowRuleId\": \"rule-123\"}"));
        }
    }

    @Nested
    @DisplayName("Execution")
    class ExecutionTests {

        @Test
        @DisplayName("Starts the target flow and returns the id of the execution row it created")
        void startsTargetFlowAndReturnsExecutionId() {
            when(flowStore.findFlowDefinitionById(TENANT, "target-flow")).thenReturn(Optional.of(succeedFlow()));

            ActionResult result = handler.execute(context("""
                {"flowId": "target-flow", "input": {"orderId": "o-1"}}
                """, 0));

            assertTrue(result.successful(), result.errorMessage());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
            verify(flowStore).createExecution(eq(TENANT), eq("target-flow"), eq("user-1"), isNull(),
                input.capture(), eq(false));
            assertEquals("exec-1", result.outputData().get("executionId"));
            assertEquals("target-flow", result.outputData().get("flowId"));
            assertFalse(result.outputData().containsKey("status"), "no hard-coded QUEUED status");
            assertEquals(Map.of("orderId", "o-1"), input.getValue().get("input"));
            assertEquals(Map.of("type", "TRIGGER_FLOW"), input.getValue().get("trigger"));
            assertEquals(1, input.getValue().get(FlowEngine.TRIGGER_DEPTH_KEY));
            verify(flowStore, timeout(5000)).completeExecution(eq("exec-1"),
                eq(FlowExecutionData.STATUS_COMPLETED), any(), isNull(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Fails for an unknown flowId and creates no execution")
        void failsForUnknownFlow() {
            when(flowStore.findFlowDefinitionById(TENANT, "ghost")).thenReturn(Optional.empty());

            ActionResult result = handler.execute(context("{\"flowId\": \"ghost\"}", 0));

            assertFalse(result.successful());
            assertTrue(result.errorMessage().contains("'ghost' not found"), result.errorMessage());
            verify(flowStore, never()).createExecution(any(), any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Fails at MAX_TRIGGER_DEPTH without starting anything")
        void failsAtDepthLimit() {
            ActionResult result = handler.execute(
                context("{\"flowId\": \"target-flow\"}", FlowEngine.MAX_TRIGGER_DEPTH));

            assertFalse(result.successful());
            assertTrue(result.errorMessage().contains("MAX_TRIGGER_DEPTH=" + FlowEngine.MAX_TRIGGER_DEPTH));
            verifyNoInteractions(flowStore);
        }

        @Test
        @DisplayName("Fails when no flow engine is wired")
        void failsWithoutEngine() {
            var unwired = new TriggerFlowActionHandler(objectMapper, null);

            ActionResult result = unwired.execute(context("{\"flowId\": \"target-flow\"}", 0));

            assertFalse(result.successful());
            assertTrue(result.errorMessage().contains("no flow engine"));
        }

        @Test
        @DisplayName("Should fail when flowId is missing")
        void shouldFailWhenFlowIdMissing() {
            ActionResult result = handler.execute(context("{}", 0));

            assertFalse(result.successful());
            assertTrue(result.errorMessage().contains("flowId"));
        }

        @Test
        @DisplayName("Should fail when input is not an object")
        void shouldFailWhenInputNotObject() {
            ActionResult result = handler.execute(context("{\"flowId\": \"f\", \"input\": [1]}", 0));

            assertFalse(result.successful());
            assertTrue(result.errorMessage().contains("'input'"));
        }
    }

    @Nested
    @DisplayName("Recursion guard")
    class RecursionTests {

        @Test
        @DisplayName("A flow that triggers itself stops after MAX_TRIGGER_DEPTH hops")
        void selfTriggerStopsAtDepthLimit() {
            when(flowStore.findFlowDefinitionById(TENANT, "flow-a")).thenReturn(Optional.of(triggeringFlow("flow-a")));

            engine.startExecution(TENANT, "flow-a", triggeringFlow("flow-a"), Map.of(), "user-1", null, false);

            assertChainStoppedAtLimit();
        }

        @Test
        @DisplayName("Two flows that trigger each other stop after MAX_TRIGGER_DEPTH hops")
        void mutualTriggerStopsAtDepthLimit() {
            when(flowStore.findFlowDefinitionById(TENANT, "flow-a")).thenReturn(Optional.of(triggeringFlow("flow-b")));
            when(flowStore.findFlowDefinitionById(TENANT, "flow-b")).thenReturn(Optional.of(triggeringFlow("flow-a")));

            engine.startExecution(TENANT, "flow-a", triggeringFlow("flow-b"), Map.of(), "user-1", null, false);

            assertChainStoppedAtLimit();
            verify(flowStore, times(5)).createExecution(eq(TENANT), eq("flow-b"), any(), any(), any(), anyBoolean());
        }

        private void assertChainStoppedAtLimit() {
            int limit = FlowEngine.MAX_TRIGGER_DEPTH;
            String lastId = "exec-" + (limit + 1);
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(flowStore, timeout(10_000)).completeExecution(eq(lastId),
                eq(FlowExecutionData.STATUS_FAILED), any(), message.capture(), anyInt(), anyInt());
            assertTrue(message.getValue().contains("MAX_TRIGGER_DEPTH=" + limit), message.getValue());

            // The original run plus one execution per allowed hop — then nothing more.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> inputs = ArgumentCaptor.forClass(Map.class);
            verify(flowStore, times(limit + 1)).createExecution(eq(TENANT), anyString(), any(), any(),
                inputs.capture(), anyBoolean());
            List<Map<String, Object>> all = inputs.getAllValues();
            for (int hop = 1; hop <= limit; hop++) {
                assertEquals(hop, all.get(hop).get(FlowEngine.TRIGGER_DEPTH_KEY));
            }
            // Every hop below the limit completed; only the last one failed.
            verify(flowStore, timeout(5000).times(limit)).completeExecution(anyString(),
                eq(FlowExecutionData.STATUS_COMPLETED), any(), isNull(), anyInt(), anyInt());
        }
    }
}
