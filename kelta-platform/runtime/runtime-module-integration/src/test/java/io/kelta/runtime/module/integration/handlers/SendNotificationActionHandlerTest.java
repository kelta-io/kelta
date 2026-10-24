package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.module.integration.IntegrationModule;
import io.kelta.runtime.module.integration.spi.NotificationStore;
import io.kelta.runtime.module.integration.spi.NotificationStore.NewNotification;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.module.ModuleContext;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SendNotificationActionHandler")
class SendNotificationActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingStore store = new RecordingStore();
    private final SendNotificationActionHandler handler = new SendNotificationActionHandler(objectMapper, store);

    /** Records every write and answers with a fresh id; rejects recipients named "stranger". */
    static class RecordingStore implements NotificationStore {
        final List<String> tenants = new ArrayList<>();
        final List<NewNotification> written = new ArrayList<>();

        @Override
        public String create(String tenantId, NewNotification notification) {
            if ("stranger".equals(notification.recipient())) {
                throw new IllegalArgumentException("Recipient 'stranger' is not a user of this tenant");
            }
            tenants.add(tenantId);
            written.add(notification);
            return "notif-" + written.size();
        }
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("SEND_NOTIFICATION", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("writes recipient/title/body/link/level through the store and outputs the new id")
    void writesThroughStore() {
        String config = """
            {"userId": "user-2", "title": "Order Approved", "message": "Your order is ready.",
             "link": "/app/orders/r1", "level": "WARNING"}
            """;
        ActionResult result = handler.execute(makeContext(config));

        assertTrue(result.successful());
        assertEquals(List.of("t1"), store.tenants);
        assertEquals(List.of(new NewNotification(
            "user-2", "Order Approved", "Your order is ready.", "/app/orders/r1", "WARNING")), store.written);
        assertEquals("notif-1", result.outputData().get("notificationId"));
        assertEquals("user-2", result.outputData().get("userId"));
        assertEquals("/app/orders/r1", result.outputData().get("link"));
        assertFalse(result.outputData().containsKey("status"));
    }

    @Test
    @DisplayName("Should default userId to context user")
    void shouldDefaultUserId() {
        String config = """
            {"title": "Alert", "message": "Something happened"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals("user-1", store.written.get(0).recipient());
        assertEquals("user-1", result.outputData().get("userId"));
    }

    @Test
    @DisplayName("Should default level to INFO and link to none")
    void shouldDefaultLevel() {
        String config = """
            {"title": "Alert", "message": "Info message"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals("INFO", store.written.get(0).level());
        assertNull(store.written.get(0).link());
        assertEquals("INFO", result.outputData().get("level"));
    }

    @Test
    @DisplayName("fails, writing nothing, on an unknown level")
    void rejectsUnknownLevel() {
        ActionResult result = handler.execute(makeContext("""
            {"title": "Alert", "message": "m", "level": "CRITICAL"}
            """));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("level"));
        assertTrue(store.written.isEmpty());
    }

    @Test
    @DisplayName("fails when the recipient is not a user of the tenant")
    void failsForUnknownRecipient() {
        ActionResult result = handler.execute(makeContext("""
            {"userId": "stranger", "title": "Alert", "message": "m"}
            """));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("not a user"));
    }

    @Test
    @DisplayName("fails when there is neither a userId nor a flow user")
    void failsWithoutRecipient() {
        ActionContext context = ActionContext.builder()
            .tenantId("t1").data(Map.of())
            .actionConfigJson("{\"title\": \"Alert\", \"message\": \"m\"}").build();
        ActionResult result = handler.execute(context);
        assertFalse(result.successful());
        assertTrue(store.written.isEmpty());
    }

    @Test
    @DisplayName("fails instead of reporting success when no NotificationStore is wired")
    void failsWithoutStore() {
        SendNotificationActionHandler unwired = new SendNotificationActionHandler(objectMapper, null);
        ActionResult result = unwired.execute(makeContext("""
            {"userId": "user-2", "title": "Alert", "message": "m"}
            """));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("NotificationStore"));
    }

    @Test
    @DisplayName("IntegrationModule hands the NotificationStore extension to the handler")
    void moduleWiresStoreExtension() {
        IntegrationModule module = new IntegrationModule();
        module.onStartup(new ModuleContext(null, null, null, objectMapper, null,
            Map.of(NotificationStore.class, store)));
        ActionHandler registered = module.getActionHandlers().stream()
            .filter(h -> "SEND_NOTIFICATION".equals(h.getActionTypeKey()))
            .findFirst().orElseThrow();

        ActionResult result = registered.execute(makeContext("""
            {"title": "Alert", "message": "m"}
            """));
        assertTrue(result.successful());
        assertEquals(1, store.written.size());
    }

    @Test
    @DisplayName("Should fail when title is missing")
    void shouldFailWhenTitleMissing() {
        String config = """
            {"message": "No title"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("title"));
    }

    @Test
    @DisplayName("Should fail when message is missing")
    void shouldFailWhenMessageMissing() {
        String config = """
            {"title": "No message"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("message"));
    }

    private ActionContext makeContext(String config) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of()).userId("user-1")
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
