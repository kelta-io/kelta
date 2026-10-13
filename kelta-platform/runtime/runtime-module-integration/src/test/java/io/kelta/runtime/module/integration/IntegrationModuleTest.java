package io.kelta.runtime.module.integration;

import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.runtime.module.integration.spi.PendingActionStore;
import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.module.ModuleContext;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IntegrationModule")
class IntegrationModuleTest {

    private final IntegrationModule module = new IntegrationModule();

    @Test
    @DisplayName("Should have correct module metadata")
    void shouldHaveCorrectMetadata() {
        assertEquals("kelta-integration", module.getId());
        assertEquals("Integration Module", module.getName());
        assertEquals("1.0.0", module.getVersion());
    }

    @Test
    @DisplayName("Should have no handlers before startup")
    void shouldHaveNoHandlersBeforeStartup() {
        assertTrue(module.getActionHandlers().isEmpty());
    }

    @Test
    @DisplayName("Should register 8 handlers after startup (without JDBC extensions)")
    void shouldRegister8HandlersAfterStartup() {
        ModuleContext context = new ModuleContext(null, null, null, new ObjectMapper());
        module.onStartup(context);
        // SQL_QUERY needs JdbcTemplate + TransactionTemplate extensions and is
        // skipped when missing; without them the count is 8 (incl. CALL_API).
        assertEquals(8, module.getActionHandlers().size());
    }

    @Test
    @DisplayName("Should register all expected action type keys")
    void shouldRegisterAllExpectedKeys() {
        ModuleContext context = new ModuleContext(null, null, null, new ObjectMapper());
        module.onStartup(context);

        Set<String> keys = module.getActionHandlers().stream()
            .map(ActionHandler::getActionTypeKey)
            .collect(Collectors.toSet());

        assertTrue(keys.contains("HTTP_CALLOUT"));
        assertTrue(keys.contains("OUTBOUND_MESSAGE"));
        assertTrue(keys.contains("SEND_NOTIFICATION"));
        assertTrue(keys.contains("PUBLISH_EVENT"));
        assertTrue(keys.contains("DELAY"));
        assertTrue(keys.contains("EMAIL_ALERT"));
        assertTrue(keys.contains("INVOKE_SCRIPT"));
        assertTrue(keys.contains("CALL_API"));
    }

    @Test
    @DisplayName("Should register SQL_QUERY when JdbcTemplate + TransactionTemplate extensions are provided")
    void shouldRegisterSqlQueryWithJdbcExtensions() {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.transaction.support.TransactionTemplate tx =
            org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);

        Map<Class<?>, Object> extensions = Map.of(
            org.springframework.jdbc.core.JdbcTemplate.class, jdbc,
            org.springframework.transaction.support.TransactionTemplate.class, tx
        );
        ModuleContext context = new ModuleContext(
            null, null, null, new ObjectMapper(), null, extensions);
        module.onStartup(context);

        Set<String> keys = module.getActionHandlers().stream()
            .map(ActionHandler::getActionTypeKey)
            .collect(Collectors.toSet());

        assertEquals(9, module.getActionHandlers().size());
        assertTrue(keys.contains("SQL_QUERY"));
    }

    @Test
    @DisplayName("Should use custom SPI implementations from extensions")
    void shouldUseCustomSpiFromExtensions() {
        PendingActionStore customStore = (t, e, w, a, r, s, snap) -> "custom-id";
        EmailService customEmail = new EmailService() {
            @Override public java.util.Optional<EmailTemplate> getTemplate(String id) {
                return java.util.Optional.empty();
            }
            @Override public String queueEmail(String t, String to, String s, String b, String src, String sid) {
                return "custom-email-id";
            }
            @Override public java.util.Optional<String> sendByKey(String t, String to, String key,
                    java.util.Map<String, Object> vars, String src, String sid) {
                return java.util.Optional.of("custom-email-id");
            }
            @Override public java.util.Optional<String> sendByName(String t, String to, String name,
                    java.util.Map<String, Object> vars, String src, String sid) {
                return java.util.Optional.of("custom-email-id");
            }
            @Override public java.util.Optional<String> sendById(String t, String to, String id,
                    java.util.Map<String, Object> vars, String src, String sid) {
                return java.util.Optional.of("custom-email-id");
            }
        };
        ScriptExecutor customScript = new ScriptExecutor() {
            @Override public java.util.Optional<ScriptInfo> getScript(String id) {
                return java.util.Optional.empty();
            }
            @Override public String queueExecution(String t, String s, String tt, String r) {
                return "custom-exec-id";
            }
            @Override public ScriptExecutionResult execute(ScriptExecutionRequest request) {
                return ScriptExecutionResult.success(java.util.Map.of(), 0);
            }
        };

        Map<Class<?>, Object> extensions = Map.of(
            PendingActionStore.class, customStore,
            EmailService.class, customEmail,
            ScriptExecutor.class, customScript
        );

        ModuleContext context = new ModuleContext(null, null, null, new ObjectMapper(), null, extensions);
        module.onStartup(context);

        assertEquals(8, module.getActionHandlers().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_CALLOUT", "OUTBOUND_MESSAGE", "CALL_API"})
    @DisplayName("Outbound handlers refuse private targets when no guard extension is supplied")
    void outboundWithoutGuardExtensionRefusesPrivateTargets(String actionType) {
        module.onStartup(new ModuleContext(null, null, null, new ObjectMapper()));

        ActionResult result = handler(actionType).execute(calloutContext("http://127.0.0.1:9/x"));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().toLowerCase().contains("refused"), result.errorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_CALLOUT", "OUTBOUND_MESSAGE", "CALL_API"})
    @DisplayName("Outbound handlers use the guard extension's allow-list")
    void outboundUsesGuardExtension(String actionType) {
        Map<Class<?>, Object> extensions = Map.of(OutboundUrlGuard.class, new OutboundUrlGuard("127.0.0.0/8"));
        module.onStartup(new ModuleContext(null, null, null, new ObjectMapper(), null, extensions));

        // Allowed by the guard, so the request is attempted; nothing listens on the discard port.
        ActionResult result = handler(actionType).execute(calloutContext("http://127.0.0.1:9/x"));

        assertFalse(result.successful());
        assertFalse(result.errorMessage().toLowerCase().contains("refused"), result.errorMessage());
    }

    private ActionHandler handler(String actionType) {
        return module.getActionHandlers().stream()
            .filter(h -> actionType.equals(h.getActionTypeKey()))
            .findFirst().orElseThrow();
    }

    private static ActionContext calloutContext(String url) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of()).userId("user-1")
            .actionConfigJson("{\"url\": \"" + url + "\"}")
            .workflowRuleId("wf1").executionLogId("log1").build();
    }
}
