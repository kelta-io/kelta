package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PublishEventActionHandler")
class PublishEventActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<String> capturedSubject = new AtomicReference<>();
    private final AtomicReference<String> capturedKey = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> capturedEvent = new AtomicReference<>();
    private final PublishEventActionHandler.EventPublisher publisher = (subject, key, data) -> {
        capturedSubject.set(subject);
        capturedKey.set(key);
        capturedEvent.set(data);
    };

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        var handler = new PublishEventActionHandler(objectMapper, null);
        assertEquals("PUBLISH_EVENT", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Publishes to kelta.event.<tenantId>.<topic> with every documented envelope field")
    void publishesTenantScopedEnvelope() {
        var handler = new PublishEventActionHandler(objectMapper, publisher);
        String config = """
            {"topic": "order.approved", "eventType": "order.approved", "dataPayload": {"total": 42}}
            """;

        ActionResult result = handler.execute(makeContext(config));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("kelta.event.t1.order.approved", capturedSubject.get());
        assertEquals("kelta.event.t1.order.approved", result.outputData().get("subject"));
        assertEquals("t1:c1", capturedKey.get());

        Map<String, Object> event = capturedEvent.get();
        assertEquals(Set.of("eventId", "eventType", "tenantId", "collectionId", "collectionName",
            "recordId", "workflowRuleId", "timestamp", "data", "recordData"), event.keySet());
        assertDoesNotThrow(() -> java.util.UUID.fromString((String) event.get("eventId")));
        assertEquals("order.approved", event.get("eventType"));
        assertEquals("t1", event.get("tenantId"));
        assertEquals("c1", event.get("collectionId"));
        assertEquals("orders", event.get("collectionName"));
        assertEquals("r1", event.get("recordId"));
        assertEquals("wf1", event.get("workflowRuleId"));
        assertDoesNotThrow(() -> Instant.parse((String) event.get("timestamp")));
        assertEquals(Map.of("total", 42), event.get("data"));
        assertEquals(Map.of("status", "Active"), event.get("recordData"));
    }

    @Test
    @DisplayName("Fails, without reporting a sent event, when no publisher is wired")
    void failsWithoutPublisher() {
        var handler = new PublishEventActionHandler(objectMapper, null);

        ActionResult result = handler.execute(makeContext("{\"topic\": \"test.events\"}"));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("no event publisher"), result.errorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {">", "orders.>", "*", "orders.*", "a..b", ".a", "a.", "a b", "other-tenant.x\n"})
    @DisplayName("Rejects topics that would escape the tenant's namespace")
    void rejectsEscapingTopics(String topic) throws Exception {
        var handler = new PublishEventActionHandler(objectMapper, publisher);
        String config = objectMapper.writeValueAsString(Map.of("topic", topic));

        ActionResult result = handler.execute(makeContext(config));

        assertFalse(result.successful());
        assertNull(capturedSubject.get(), "nothing may be published");
    }

    @Test
    @DisplayName("Fails without a tenant to scope the subject")
    void failsWithoutTenant() {
        var handler = new PublishEventActionHandler(objectMapper, publisher);
        ActionContext ctx = ActionContext.builder()
            .data(Map.of()).actionConfigJson("{\"topic\": \"x\"}").build();

        ActionResult result = handler.execute(ctx);

        assertFalse(result.successful());
        assertNull(capturedSubject.get());
    }

    @Test
    @DisplayName("Fails when the publisher throws")
    void failsWhenPublisherThrows() {
        var handler = new PublishEventActionHandler(objectMapper, (s, k, d) -> {
            throw new IllegalStateException("no stream");
        });

        ActionResult result = handler.execute(makeContext("{\"topic\": \"x\"}"));

        assertFalse(result.successful());
    }

    @Test
    @DisplayName("Should default event type")
    void shouldDefaultEventType() {
        var handler = new PublishEventActionHandler(objectMapper, publisher);

        ActionResult result = handler.execute(makeContext("{\"topic\": \"test.events\"}"));

        assertTrue(result.successful());
        assertEquals("workflow.custom.event", result.outputData().get("eventType"));
        assertEquals("workflow.custom.event", capturedEvent.get().get("eventType"));
    }

    @Test
    @DisplayName("Should fail when topic is missing")
    void shouldFailWhenTopicMissing() {
        var handler = new PublishEventActionHandler(objectMapper, publisher);

        ActionResult result = handler.execute(makeContext("{}"));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("topic"));
    }

    private ActionContext makeContext(String config) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of("status", "Active")).userId("user-1")
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
