package io.kelta.runtime.flow;

import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InitialStateBuilder}.
 */
class InitialStateBuilderTest {

    private InitialStateBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InitialStateBuilder();
    }

    // -------------------------------------------------------------------------
    // buildFromRecordEvent
    // -------------------------------------------------------------------------

    @Test
    void buildFromRecordEventContainsTriggerMetadata() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.created", "tenant-1", "user-1",
                RecordChangedPayload.created("orders", "rec-1",
                        Map.of("status", "ACTIVE")));

        Map<String, Object> state = builder.buildFromRecordEvent(event, "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) state.get("trigger");
        assertNotNull(trigger);
        assertEquals("RECORD_CHANGE", trigger.get("type"));
        assertEquals("CREATED", trigger.get("changeType"));
        assertEquals("orders", trigger.get("collectionName"));
    }

    @Test
    void buildFromRecordEventContainsRecordData() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.created", "tenant-1", "user-1",
                RecordChangedPayload.created("orders", "rec-1",
                        Map.of("status", "ACTIVE", "amount", 100)));

        Map<String, Object> state = builder.buildFromRecordEvent(event, "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) state.get("record");
        assertNotNull(record);
        assertEquals("rec-1", record.get("id"));
        assertEquals("orders", record.get("collectionName"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) record.get("data");
        assertEquals("ACTIVE", data.get("status"));
        assertEquals(100, data.get("amount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildFromRecordEventFlagsOmittedPayload() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1",
                RecordChangedPayload.updated("orders", "rec-1",
                                Map.of("status", "SHIPPED"), null, List.of("status"))
                        .withPayloadOmitted(true));

        Map<String, Object> record = (Map<String, Object>) builder
                .buildFromRecordEvent(event, "flow-1", "exec-1").get("record");

        assertEquals(true, record.get("payloadOmitted"));
        assertEquals("SHIPPED", ((Map<String, Object>) record.get("data")).get("status"));
        assertEquals(Map.of(), record.get("previousData"));
    }

    @Test
    void buildFromRecordEventOmitsFlagForFullPayload() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.created", "tenant-1", "user-1",
                RecordChangedPayload.created("orders", "rec-1", Map.of("status", "ACTIVE")));

        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) builder
                .buildFromRecordEvent(event, "flow-1", "exec-1").get("record");

        assertFalse(record.containsKey("payloadOmitted"));
    }

    @Test
    void buildFromRecordEventContainsChangedFields() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1",
                RecordChangedPayload.updated("orders", "rec-1",
                        Map.of("status", "ACTIVE"),
                        Map.of("status", "DRAFT"),
                        List.of("status")));

        Map<String, Object> state = builder.buildFromRecordEvent(event, "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) state.get("record");
        @SuppressWarnings("unchecked")
        Map<String, Object> previousData = (Map<String, Object>) record.get("previousData");
        assertEquals("DRAFT", previousData.get("status"));

        @SuppressWarnings("unchecked")
        List<String> changedFields = (List<String>) record.get("changedFields");
        assertEquals(List.of("status"), changedFields);
    }

    @Test
    void buildFromRecordEventContainsContext() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.created", "tenant-1", "user-1",
                RecordChangedPayload.created("orders", "rec-1",
                        Map.of("status", "ACTIVE")));

        Map<String, Object> state = builder.buildFromRecordEvent(event, "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) state.get("context");
        assertNotNull(context);
        assertEquals("tenant-1", context.get("tenantId"));
        assertEquals("user-1", context.get("userId"));
        assertEquals("flow-1", context.get("flowId"));
        assertEquals("exec-1", context.get("executionId"));
    }

    @Test
    void buildFromRecordEventHandlesNullData() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.deleted", "tenant-1", "user-1",
                RecordChangedPayload.deleted("orders", "rec-1", null));

        Map<String, Object> state = builder.buildFromRecordEvent(event, "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) state.get("record");
        assertNotNull(record.get("data"));
        assertTrue(((Map<?, ?>) record.get("data")).isEmpty());
    }

    // -------------------------------------------------------------------------
    // buildFromApiInvocation
    // -------------------------------------------------------------------------

    @Test
    void buildFromApiInvocationContainsTriggerMetadata() {
        Map<String, Object> input = Map.of("orderId", "ord-123");

        Map<String, Object> state = builder.buildFromApiInvocation(
                input, "tenant-1", "user-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) state.get("trigger");
        assertEquals("API_INVOCATION", trigger.get("type"));
    }

    @Test
    void buildFromApiInvocationContainsInput() {
        Map<String, Object> input = Map.of("orderId", "ord-123", "priority", "HIGH");

        Map<String, Object> state = builder.buildFromApiInvocation(
                input, "tenant-1", "user-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> stateInput = (Map<String, Object>) state.get("input");
        assertEquals("ord-123", stateInput.get("orderId"));
        assertEquals("HIGH", stateInput.get("priority"));
    }

    @Test
    void buildFromApiInvocationHandlesNullInput() {
        Map<String, Object> state = builder.buildFromApiInvocation(
                null, "tenant-1", "user-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> stateInput = (Map<String, Object>) state.get("input");
        assertNotNull(stateInput);
        assertTrue(stateInput.isEmpty());
    }

    @Test
    void buildFromApiInvocationContainsContext() {
        Map<String, Object> state = builder.buildFromApiInvocation(
                Map.of(), "tenant-1", "user-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) state.get("context");
        assertEquals("tenant-1", context.get("tenantId"));
        assertEquals("user-1", context.get("userId"));
        assertEquals("flow-1", context.get("flowId"));
        assertEquals("exec-1", context.get("executionId"));
    }

    // -------------------------------------------------------------------------
    // buildFromSchedule
    // -------------------------------------------------------------------------

    @Test
    void buildFromScheduleContainsTriggerMetadata() {
        Map<String, Object> triggerConfig = Map.of(
                "cronExpression", "0 0 8 * * MON-FRI",
                "inputData", Map.of("reportType", "daily")
        );

        Map<String, Object> state = builder.buildFromSchedule(
                triggerConfig, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) state.get("trigger");
        assertEquals("SCHEDULED", trigger.get("type"));
    }

    @Test
    void buildFromScheduleExtractsInputData() {
        Map<String, Object> triggerConfig = Map.of(
                "inputData", Map.of("reportType", "daily", "recipients", List.of("admin@test.com"))
        );

        Map<String, Object> state = builder.buildFromSchedule(
                triggerConfig, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) state.get("input");
        assertEquals("daily", input.get("reportType"));
    }

    @Test
    void buildFromScheduleHandlesNullTriggerConfig() {
        Map<String, Object> state = builder.buildFromSchedule(
                null, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) state.get("input");
        assertNotNull(input);
        assertTrue(input.isEmpty());
    }

    @Test
    void buildFromScheduleHandlesMissingInputData() {
        Map<String, Object> triggerConfig = Map.of("cronExpression", "0 0 * * *");

        Map<String, Object> state = builder.buildFromSchedule(
                triggerConfig, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) state.get("input");
        assertNotNull(input);
        assertTrue(input.isEmpty());
    }

    @Test
    void buildFromScheduleContainsContext() {
        Map<String, Object> state = builder.buildFromSchedule(
                Map.of(), "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) state.get("context");
        assertEquals("tenant-1", context.get("tenantId"));
        assertEquals("flow-1", context.get("flowId"));
        assertEquals("exec-1", context.get("executionId"));
    }

    // -------------------------------------------------------------------------
    // buildFromWebhook
    // -------------------------------------------------------------------------

    @Test
    void buildFromWebhookContainsTriggerMetadata() {
        Map<String, Object> payload = Map.of("orderId", "ord-123");
        Map<String, String> headers = Map.of("content-type", "application/json");

        Map<String, Object> state = builder.buildFromWebhook(
                payload, headers, Map.of(), "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) state.get("trigger");
        assertEquals("WEBHOOK", trigger.get("type"));
    }

    @Test
    void buildFromWebhookContainsInputAndHeaders() {
        Map<String, Object> payload = Map.of("orderId", "ord-123", "amount", 42);
        Map<String, String> headers = Map.of("content-type", "application/json", "user-agent", "TestAgent");

        Map<String, Object> state = builder.buildFromWebhook(
                payload, headers, Map.of(), "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) state.get("input");
        assertEquals("ord-123", input.get("orderId"));
        assertEquals(42, input.get("amount"));

        @SuppressWarnings("unchecked")
        Map<String, String> stateHeaders = (Map<String, String>) state.get("headers");
        assertEquals("application/json", stateHeaders.get("content-type"));
        assertEquals("TestAgent", stateHeaders.get("user-agent"));
    }

    @Test
    void buildFromWebhookHandlesNullPayloadAndHeaders() {
        Map<String, Object> state = builder.buildFromWebhook(
                null, null, null, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) state.get("input");
        assertNotNull(input);
        assertTrue(input.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) state.get("headers");
        assertNotNull(headers);
        assertTrue(headers.isEmpty());
    }

    @Test
    void buildFromWebhookPutsQueryBesideUnchangedHeaders() {
        Map<String, String> headers = Map.of("content-type", "application/json", "user-agent", "TestAgent");
        Map<String, Object> query = Map.of("a", "1", "tag", java.util.List.of("x", "y"));

        Map<String, Object> state = builder.buildFromWebhook(
                Map.of("orderId", "ord-123"), headers, query, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> stateQuery = (Map<String, Object>) state.get("query");
        assertEquals("1", stateQuery.get("a"));
        assertEquals(java.util.List.of("x", "y"), stateQuery.get("tag"));
        assertEquals(headers, state.get("headers"));
        assertEquals(Map.of("orderId", "ord-123"), state.get("input"));
    }

    @Test
    void buildFromWebhookDefaultsQueryToEmpty() {
        Map<String, Object> state = builder.buildFromWebhook(
                Map.of(), Map.of(), null, "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) state.get("query");
        assertNotNull(query);
        assertTrue(query.isEmpty());
    }

    @Test
    void buildFromWebhookContainsContext() {
        Map<String, Object> state = builder.buildFromWebhook(
                Map.of(), Map.of(), Map.of(), "tenant-1", "flow-1", "exec-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) state.get("context");
        assertEquals("tenant-1", context.get("tenantId"));
        assertEquals("flow-1", context.get("flowId"));
        assertEquals("exec-1", context.get("executionId"));
    }

    // -------------------------------------------------------------------------
    // State structure verification
    // -------------------------------------------------------------------------

    @Test
    void allBuildersProduceThreeTopLevelKeys() {
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.created", "tenant-1", "user-1",
                RecordChangedPayload.created("orders", "rec-1",
                        Map.of("status", "ACTIVE")));

        Map<String, Object> recordState = builder.buildFromRecordEvent(event, "flow-1", "exec-1");
        assertEquals(3, recordState.size());
        assertTrue(recordState.containsKey("trigger"));
        assertTrue(recordState.containsKey("record"));
        assertTrue(recordState.containsKey("context"));

        Map<String, Object> apiState = builder.buildFromApiInvocation(
                Map.of(), "tenant-1", "user-1", "flow-1", "exec-1");
        assertEquals(3, apiState.size());
        assertTrue(apiState.containsKey("trigger"));
        assertTrue(apiState.containsKey("input"));
        assertTrue(apiState.containsKey("context"));

        Map<String, Object> schedState = builder.buildFromSchedule(
                Map.of(), "tenant-1", "flow-1", "exec-1");
        assertEquals(3, schedState.size());
        assertTrue(schedState.containsKey("trigger"));
        assertTrue(schedState.containsKey("input"));
        assertTrue(schedState.containsKey("context"));

        Map<String, Object> webhookState = builder.buildFromWebhook(
                Map.of(), Map.of(), Map.of(), "tenant-1", "flow-1", "exec-1");
        assertEquals(5, webhookState.size());
        assertTrue(webhookState.containsKey("trigger"));
        assertTrue(webhookState.containsKey("input"));
        assertTrue(webhookState.containsKey("headers"));
        assertTrue(webhookState.containsKey("query"));
        assertTrue(webhookState.containsKey("context"));
    }
}
