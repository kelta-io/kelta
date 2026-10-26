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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("CreateRecordActionHandler")
class CreateRecordActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CollectionRegistry registry = mock(CollectionRegistry.class);
    private final QueryEngine queryEngine = mock(QueryEngine.class);
    private final CreateRecordActionHandler handler =
        new CreateRecordActionHandler(objectMapper, registry, queryEngine);

    {
        when(queryEngine.create(any(), any())).thenAnswer(inv -> {
            Map<String, Object> created = new HashMap<>((Map<String, Object>) inv.getArgument(1));
            created.put("id", "test-id-001");
            return created;
        });
    }

    private ch.qos.logback.classic.Logger handlerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        handlerLogger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(CreateRecordActionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        handlerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("CREATE_RECORD", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should create record with static values")
    void shouldCreateRecordWithStaticValues() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));

        String config = """
            {"targetCollectionName": "orders", "fieldMappings": [
                {"targetField": "name", "value": "New Order"},
                {"targetField": "status", "value": "Open"}
            ]}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals("orders", result.outputData().get("targetCollectionName"));
    }

    @Test
    @DisplayName("Should create record with source field mapping")
    void shouldCreateRecordWithSourceField() {
        when(registry.get("tasks")).thenReturn(mock(CollectionDefinition.class));

        String config = """
            {"targetCollectionName": "tasks", "fieldMappings": [
                {"targetField": "source_id", "sourceField": "id"}
            ]}
            """;
        ActionContext ctx = ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of("id", "order-123"))
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();

        ActionResult result = handler.execute(ctx);
        assertTrue(result.successful());
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = (Map<String, Object>) result.outputData().get("recordData");
        assertEquals("order-123", recordData.get("source_id"));
    }

    @Test
    @DisplayName("Should fail when target collection not found")
    void shouldFailWhenCollectionNotFound() {
        when(registry.get("nonexistent")).thenReturn(null);

        String config = """
            {"targetCollectionName": "nonexistent"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("not found"));
    }

    @Test
    @DisplayName("Should fail when no target collection specified")
    void shouldFailWhenNoTarget() {
        String config = "{}";
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
    }

    @Test
    @DisplayName("Should support legacy targetCollectionId config")
    void shouldSupportLegacyId() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));

        String config = """
            {"targetCollectionId": "orders"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
    }

    @Test
    @DisplayName("Should stamp createdBy/updatedBy from a UUID execution actor")
    void shouldStampAuditUsersFromActor() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));
        String actor = "3f2b6c1e-9d4a-4f0b-8e2d-1a2b3c4d5e6f";

        String config = """
            {"targetCollectionName": "orders", "fieldMappings": [
                {"targetField": "name", "value": "New Order"}
            ]}
            """;
        ActionResult result = handler.execute(makeContext(config, actor));
        assertTrue(result.successful());

        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = (Map<String, Object>) result.outputData().get("recordData");
        assertEquals(actor, recordData.get("createdBy"));
        assertEquals(actor, recordData.get("updatedBy"));
    }

    @Test
    @DisplayName("Should not stamp audit users from a non-UUID actor (legacy 'webhook')")
    void shouldNotStampNonUuidActor() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));

        String config = """
            {"targetCollectionName": "orders", "fieldMappings": [
                {"targetField": "name", "value": "New Order"}
            ]}
            """;
        ActionResult result = handler.execute(makeContext(config, "webhook"));
        assertTrue(result.successful());

        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = (Map<String, Object>) result.outputData().get("recordData");
        assertFalse(recordData.containsKey("createdBy"));
        assertFalse(recordData.containsKey("updatedBy"));
    }

    @Test
    @DisplayName("Should not stamp audit users when the execution has no actor")
    void shouldNotStampWithoutActor() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));

        String config = """
            {"targetCollectionName": "orders", "fieldMappings": [
                {"targetField": "name", "value": "New Order"}
            ]}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());

        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = (Map<String, Object>) result.outputData().get("recordData");
        assertFalse(recordData.containsKey("createdBy"));
    }

    @Test
    @DisplayName("Should log flow coordinates and field names, never values, on a validation failure")
    void shouldLogFlowCoordinatesOnValidationFailure() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));
        doThrow(new ValidationException(ValidationResult.failure(List.of(
                FieldError.enumViolation("status", List.of("active", "paused", "deprecated")),
                FieldError.reference("customer_id", "customers", "cust-secret-42")))))
            .when(queryEngine).create(any(), any());

        String config = """
            {"targetCollectionName": "orders", "fieldMappings": [
                {"targetField": "status", "value": "bogus-status"},
                {"targetField": "customer_id", "value": "cust-secret-42"},
                {"targetField": "name", "sourceField": "name"}
            ]}
            """;
        ActionContext ctx = ActionContext.builder()
            .tenantId("tenant-7").userId(null)
            .data(Map.of("name", "Private Person"))
            .actionConfigJson(config).workflowRuleId("flow-9").executionLogId("exec-3").build();

        ActionResult result = handler.execute(ctx);
        assertFalse(result.successful());

        List<ILoggingEvent> errors = logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size());
        String line = errors.get(0).getFormattedMessage();
        assertTrue(line.contains("Failed to execute create record action"), line);
        assertTrue(line.contains("tenantId=tenant-7"), line);
        assertTrue(line.contains("flowId=flow-9"), line);
        assertTrue(line.contains("executionId=exec-3"), line);
        assertTrue(line.contains("targetCollection=orders"), line);
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
    @DisplayName("Should log flow coordinates and the error on a non-validation failure")
    void shouldLogFlowCoordinatesOnOtherFailure() {
        when(registry.get("orders")).thenReturn(mock(CollectionDefinition.class));
        doThrow(new IllegalStateException("connection reset"))
            .when(queryEngine).create(any(), any());

        String config = """
            {"targetCollectionName": "orders", "fieldMappings": [{"targetField": "name", "value": "x"}]}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());

        ILoggingEvent error = logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        String line = error.getFormattedMessage();
        assertTrue(line.contains("tenantId=t1"), line);
        assertTrue(line.contains("flowId=wf1"), line);
        assertTrue(line.contains("executionId=log1"), line);
        assertTrue(line.contains("targetCollection=orders"), line);
        assertTrue(line.contains("error=connection reset"), line);
        assertNotNull(error.getThrowableProxy());
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
