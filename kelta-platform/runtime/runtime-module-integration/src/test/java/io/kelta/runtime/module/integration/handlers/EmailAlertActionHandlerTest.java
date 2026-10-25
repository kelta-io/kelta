package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.flow.FlowDefinition;
import io.kelta.runtime.flow.FlowExecutionContext;
import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.flow.StateDefinition;
import io.kelta.runtime.flow.StateExecutionResult;
import io.kelta.runtime.flow.executor.TaskStateExecutor;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EmailAlertActionHandler")
class EmailAlertActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailService emailService = mock(EmailService.class);
    private final EmailAlertActionHandler handler = new EmailAlertActionHandler(objectMapper, emailService);

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("EMAIL_ALERT", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should queue email with inline subject and body")
    void shouldQueueEmailInline() {
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("email-123");

        String config = """
            {"to": "user@example.com", "subject": "Order Confirmed", "body": "Your order is ready."}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals("email-123", result.outputData().get("emailLogId"));
        assertEquals("user@example.com", result.outputData().get("to"));
        assertEquals("QUEUED", result.outputData().get("status"));
    }

    @Test
    @DisplayName("Should use template when templateId provided")
    void shouldUseTemplate() {
        when(emailService.getTemplate("tmpl-1"))
            .thenReturn(Optional.of(new EmailService.EmailTemplate("Template Subject", "<h1>Body</h1>")));
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("email-456");

        String config = """
            {"to": "user@example.com", "templateId": "tmpl-1"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals("Template Subject", result.outputData().get("subject"));
    }

    @Test
    @DisplayName("Should fall back to config when template not found")
    void shouldFallbackToConfig() {
        when(emailService.getTemplate("missing"))
            .thenReturn(Optional.empty());
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("email-789");

        String config = """
            {"to": "user@example.com", "templateId": "missing", "subject": "Fallback", "body": "text"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals("Fallback", result.outputData().get("subject"));
    }

    @Test
    @DisplayName("Should fail when to address is missing")
    void shouldFailWhenToMissing() {
        String config = """
            {"subject": "Test", "body": "text"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("to"));
    }

    @Test
    @DisplayName("Should fail when subject is missing and no template")
    void shouldFailWhenSubjectMissing() {
        String config = """
            {"to": "user@example.com"}
            """;
        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("subject"));
    }

    @Test
    @DisplayName("Declares subject and body unresolved so the flow engine leaves them to the handler")
    void declaresSubjectAndBodyUnresolved() {
        assertEquals(Set.of("subject", "body"), handler.unresolvedParameterKeys());
    }

    @Test
    @DisplayName("A ${...} inside state data is sent as text, not expanded a second time")
    void expandsPlaceholdersOnce() {
        Map<String, Object> state = Map.of("name", "${$.secret}", "secret", "s3cr3t-value");

        StateExecutionResult result = runAsFlowTask(
            Map.of("to", "user@example.com", "subject", "For ${$.name}", "body", "Hi ${$.name}"), state);

        assertNotEquals("FAILED", result.status(), result.errorMessage());
        assertEquals("Hi ${$.secret}", queuedBody());
        assertEquals("For ${$.secret}", queuedSubject());
    }

    @Test
    @DisplayName("HTML-escapes interpolated body values by default")
    void escapesBodyValues() {
        Map<String, Object> state = Map.of("name", "<script>alert(\"x\")</script> & 'co'");

        handler.execute(makeFlowContext(
            "{\"to\": \"user@example.com\", \"subject\": \"Hi\", \"body\": \"<p>${$.name}</p>\"}", state));

        assertEquals("<p>&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;co&#39;</p>", queuedBody());
    }

    @Test
    @DisplayName("${raw:$.path} inserts a body value without escaping")
    void rawPlaceholderSkipsEscaping() {
        Map<String, Object> state = Map.of("html", "<b>bold</b>", "text", "<i>");

        handler.execute(makeFlowContext(
            "{\"to\": \"user@example.com\", \"subject\": \"Hi\", \"body\": \"${raw:$.html} ${$.text}\"}", state));

        assertEquals("<b>bold</b> &lt;i&gt;", queuedBody());
    }

    @Test
    @DisplayName("Subject values are not HTML-escaped but CR/LF are stripped")
    void subjectStripsLineBreaksWithoutEscaping() {
        Map<String, Object> state = Map.of("name", "Tom & <Jerry>\r\nBcc: victim@example.com\n");

        ActionResult result = handler.execute(makeFlowContext(
            "{\"to\": \"user@example.com\", \"subject\": \"Hello ${$.name}\", \"body\": \"x\"}", state));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("Hello Tom & <Jerry>Bcc: victim@example.com", queuedSubject());
    }

    @Test
    @DisplayName("Template subject and body follow the same once-only, escaped expansion")
    void templateFollowsSameRule() {
        when(emailService.getTemplate("tmpl-2"))
            .thenReturn(Optional.of(new EmailService.EmailTemplate("Re: ${$.name}", "<p>${$.name}</p>")));
        Map<String, Object> state = Map.of("name", "<${$.secret}>", "secret", "s3cr3t-value");

        handler.execute(makeFlowContext("{\"to\": \"user@example.com\", \"templateId\": \"tmpl-2\"}", state));

        assertEquals("<p>&lt;${$.secret}&gt;</p>", queuedBody());
        assertEquals("Re: <${$.secret}>", queuedSubject());
    }

    private StateExecutionResult runAsFlowTask(Map<String, Object> parameters, Map<String, Object> state) {
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn("email-1");
        TaskStateExecutor executor = new TaskStateExecutor(
            new ActionHandlerRegistry(List.of(handler)), new StateDataResolver(objectMapper), objectMapper);
        StateDefinition.TaskState task = new StateDefinition.TaskState("Send", null, "EMAIL_ALERT",
            parameters, null, null, "$.result", null, null, true, List.of(), List.of());
        FlowDefinition definition = new FlowDefinition(null, "Send", Map.of("Send", task), Map.of());
        return executor.execute(task,
            new FlowExecutionContext("exec-1", "t1", "flow-1", "u1", definition, state));
    }

    private String queuedSubject() {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).queueEmail(anyString(), anyString(), subject.capture(), anyString(), anyString(), any());
        return subject.getValue();
    }

    private String queuedBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).queueEmail(anyString(), anyString(), anyString(), body.capture(), anyString(), any());
        return body.getValue();
    }

    private ActionContext makeFlowContext(String config, Map<String, Object> state) {
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn("email-1");
        return ActionContext.builder()
            .tenantId("t1").userId("user-1").data(state).resolvedData(state).stateData(state)
            .actionConfigJson(config).build();
    }

    private ActionContext makeContext(String config) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .data(Map.of()).userId("user-1")
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
