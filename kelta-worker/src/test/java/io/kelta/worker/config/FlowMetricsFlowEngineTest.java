package io.kelta.worker.config;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowExecutionData;
import io.kelta.runtime.flow.FlowStepLogging;
import io.kelta.runtime.flow.FlowStore;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs a flow through a real {@link FlowEngine} with {@link FlowMetricsConfig} as its
 * listener and checks the execution meters carry the flow's name next to its id.
 */
class FlowMetricsFlowEngineTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String FLOW_ID = "a1b2c3d4-0000-0000-0000-000000000001";
    private static final String FLOW_NAME = "Order Fulfilment";
    private static final String EXECUTION_ID = "exec-1";
    private static final String DEFINITION = """
            {
                "StartAt": "Prepare",
                "States": {
                    "Prepare": { "Type": "Pass", "Result": { "ok": true }, "Next": "Done" },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;

    @Test
    void executionMetersCarryTheFlowNameAndId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FlowMetricsConfig metrics = new FlowMetricsConfig(registry,
                flowId -> FLOW_ID.equals(flowId) ? Optional.of(FLOW_NAME) : Optional.empty());

        FlowEngine engine = new FlowEngine(completingFlowStore(), new ActionHandlerRegistry(),
                new ObjectMapper(), 1, metrics);
        engine.startExecution(TENANT_ID, FLOW_ID, DEFINITION, Map.of(), "user-1", null, false);
        engine.shutdown();

        var counter = registry.find("kelta_flow_execution_total")
                .tag("flow_name", FLOW_NAME)
                .tag("flow_id", FLOW_ID)
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        var timer = registry.find("kelta_flow_execution_duration_seconds")
                .tag("flow_name", FLOW_NAME)
                .tag("flow_id", FLOW_ID)
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        assertNull(registry.find("kelta_flow_execution_total").tag("flow_name", "unknown").counter());
    }

    private static FlowStore completingFlowStore() {
        FlowStore store = mock(FlowStore.class);
        when(store.createExecution(eq(TENANT_ID), eq(FLOW_ID), anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(EXECUTION_ID);
        when(store.findStepLogging(TENANT_ID, FLOW_ID)).thenReturn(FlowStepLogging.FULL);
        when(store.loadExecution(EXECUTION_ID)).thenReturn(Optional.of(new FlowExecutionData(
                EXECUTION_ID, TENANT_ID, FLOW_ID, FlowExecutionData.STATUS_COMPLETED, "user-1", null,
                Map.of(), "Done", null, null, 2, 5, Map.of(), false, null, null)));
        return store;
    }
}
