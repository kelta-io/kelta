package io.kelta.runtime.module.core.handlers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("UpdateRecordActionHandler")
class UpdateRecordActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CollectionRegistry registry = mock(CollectionRegistry.class);
    private final QueryEngine queryEngine = mock(QueryEngine.class);
    private final UpdateRecordActionHandler handler = new UpdateRecordActionHandler(objectMapper, registry, queryEngine);

    private ch.qos.logback.classic.Logger handlerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        handlerLogger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(UpdateRecordActionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        handlerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("Should log flow coordinates, record id and field names, never values, on a validation failure")
    void shouldLogFlowCoordinatesOnValidationFailure() {
        CollectionDefinition collDef = mock(CollectionDefinition.class);
        when(registry.get("orders")).thenReturn(collDef);
        when(queryEngine.update(eq(collDef), eq("rec-5"), anyMap()))
            .thenThrow(new ValidationException(ValidationResult.failure(List.of(
                FieldError.enumViolation("status", List.of("active", "paused", "deprecated")),
                FieldError.reference("customer_id", "customers", "cust-secret-42")))));

        String config = """
            {"targetCollectionName": "orders", "recordId": "rec-5", "updates": [
                {"field": "status", "value": "bogus-status"},
                {"field": "customer_id", "value": "cust-secret-42"},
                {"field": "reviewer", "sourceField": "name"}
            ]}
            """;
        ActionContext ctx = ActionContext.builder()
            .tenantId("tenant-7")
            .data(Map.of("name", "Private Person"))
            .actionConfigJson(config).workflowRuleId("flow-9").executionLogId("exec-3").build();

        ActionResult result = handler.execute(ctx);
        assertFalse(result.successful());

        List<ILoggingEvent> errors = logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size());
        String line = errors.get(0).getFormattedMessage();
        assertTrue(line.contains("Failed to execute update record action"), line);
        assertTrue(line.contains("tenantId=tenant-7"), line);
        assertTrue(line.contains("flowId=flow-9"), line);
        assertTrue(line.contains("executionId=exec-3"), line);
        assertTrue(line.contains("targetCollection=orders"), line);
        assertTrue(line.contains("recordId=rec-5"), line);
        assertTrue(line.contains("status"), line);
        assertTrue(line.contains("customer_id"), line);
        assertNull(errors.get(0).getThrowableProxy(),
            "the exception message embeds values, so it must not be attached");

        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains("bogus-status"), message);
            assertFalse(message.contains("cust-secret-42"), message);
            assertFalse(message.contains("Private Person"), message);
        }
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("UPDATE_RECORD", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should update with static values")
    void shouldUpdateWithStaticValues() {
        CollectionDefinition collDef = mock(CollectionDefinition.class);
        when(registry.get("orders")).thenReturn(collDef);
        when(queryEngine.update(eq(collDef), eq("r1"), anyMap()))
            .thenReturn(Optional.of(Map.of("id", "r1", "status", "Approved")));

        String config = """
            {"targetCollectionName": "orders", "updates": [{"field": "status", "value": "Approved"}]}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        @SuppressWarnings("unchecked")
        Map<String, Object> updatedFields = (Map<String, Object>) result.outputData().get("updatedFields");
        assertEquals("Approved", updatedFields.get("status"));
    }

    @Test
    @DisplayName("Should resolve record ID from source field")
    void shouldResolveRecordIdFromField() {
        CollectionDefinition collDef = mock(CollectionDefinition.class);
        when(registry.get("tasks")).thenReturn(collDef);
        when(queryEngine.update(eq(collDef), eq("task-456"), anyMap()))
            .thenReturn(Optional.of(Map.of("id", "task-456", "status", "Done")));

        String config = """
            {"targetCollectionName": "tasks", "recordIdField": "task_id",
             "updates": [{"field": "status", "value": "Done"}]}
            """;
        ActionContext ctx = ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of("task_id", "task-456"))
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();

        ActionResult result = handler.execute(ctx);
        assertTrue(result.successful());
        assertEquals("task-456", result.outputData().get("targetRecordId"));
    }

    @Test
    @DisplayName("Should use source field for update values")
    void shouldUseSourceFieldValues() {
        CollectionDefinition collDef = mock(CollectionDefinition.class);
        when(registry.get("orders")).thenReturn(collDef);
        when(queryEngine.update(eq(collDef), eq("r1"), anyMap()))
            .thenReturn(Optional.of(Map.of("id", "r1", "reviewer", "user-123")));

        String config = """
            {"targetCollectionName": "orders", "updates": [{"field": "reviewer", "sourceField": "userId"}]}
            """;
        ActionContext ctx = ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of("userId", "user-123"))
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();

        ActionResult result = handler.execute(ctx);
        assertTrue(result.successful());
        @SuppressWarnings("unchecked")
        Map<String, Object> updatedFields = (Map<String, Object>) result.outputData().get("updatedFields");
        assertEquals("user-123", updatedFields.get("reviewer"));
    }

    @Test
    @DisplayName("Should fail when no updates defined")
    void shouldFailWhenNoUpdates() {
        when(registry.get("orders")).thenReturn(
            mock(CollectionDefinition.class));

        String config = """
            {"targetCollectionName": "orders"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
    }

    @Test
    @DisplayName("Should stamp updatedBy from a UUID execution actor")
    void shouldStampUpdatedByFromActor() {
        CollectionDefinition collDef = mock(CollectionDefinition.class);
        when(registry.get("orders")).thenReturn(collDef);
        when(queryEngine.update(eq(collDef), eq("r1"), anyMap()))
            .thenReturn(Optional.of(Map.of("id", "r1")));
        String actor = "3f2b6c1e-9d4a-4f0b-8e2d-1a2b3c4d5e6f";

        String config = """
            {"targetCollectionName": "orders", "updates": [{"field": "status", "value": "Approved"}]}
            """;
        ActionResult result = handler.execute(makeContext(config, actor));
        assertTrue(result.successful());
        @SuppressWarnings("unchecked")
        Map<String, Object> updatedFields = (Map<String, Object>) result.outputData().get("updatedFields");
        assertEquals(actor, updatedFields.get("updatedBy"));
        assertFalse(updatedFields.containsKey("createdBy"));
    }

    @Test
    @DisplayName("Should not stamp updatedBy from a non-UUID actor")
    void shouldNotStampNonUuidActor() {
        CollectionDefinition collDef = mock(CollectionDefinition.class);
        when(registry.get("orders")).thenReturn(collDef);
        when(queryEngine.update(eq(collDef), eq("r1"), anyMap()))
            .thenReturn(Optional.of(Map.of("id", "r1")));

        String config = """
            {"targetCollectionName": "orders", "updates": [{"field": "status", "value": "Approved"}]}
            """;
        ActionResult result = handler.execute(makeContext(config, "webhook"));
        assertTrue(result.successful());
        @SuppressWarnings("unchecked")
        Map<String, Object> updatedFields = (Map<String, Object>) result.outputData().get("updatedFields");
        assertFalse(updatedFields.containsKey("updatedBy"));
    }

    private ActionContext makeContext(String config) {
        return makeContext(config, null);
    }

    private ActionContext makeContext(String config, String userId) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of("name", "Test"))
            .userId(userId)
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
