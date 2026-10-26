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

    @Test
    @DisplayName("validate() accepts transactional and replyTo, including a run-time placeholder")
    void validateAcceptsDeliveryOptions() {
        assertDoesNotThrow(() -> handler.validate("""
            {"to": "a@example.com", "subject": "s", "transactional": false, "replyTo": "help@example.com"}
            """));
        assertDoesNotThrow(() -> handler.validate("""
            {"to": "a@example.com", "subject": "s", "transactional": true, "replyTo": "${$.input.owner}"}
            """));
    }

    @Test
    @DisplayName("validate() rejects a non-boolean transactional")
    void validateRejectsNonBooleanTransactional() {
        for (String value : List.of("\"false\"", "0", "\"yes\"", "{}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> handler.validate(
                "{\"to\": \"a@example.com\", \"subject\": \"s\", \"transactional\": " + value + "}"));
            assertTrue(e.getMessage().contains("transactional"), e.getMessage());
        }
    }

    @Test
    @DisplayName("validate() rejects an unparseable or multi-address replyTo")
    void validateRejectsBadReplyTo() {
        for (String value : List.of("not-an-address", "a@example.com, b@example.com", "Help <help@example.com>",
                "help@example.com\\r\\nBcc: x@example.com", "help@", "@example.com", "12")) {
            String quoted = value.equals("12") ? value : "\"" + value + "\"";
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> handler.validate(
                "{\"to\": \"a@example.com\", \"subject\": \"s\", \"replyTo\": " + quoted + "}"),
                value);
            assertTrue(e.getMessage().contains("replyTo"), e.getMessage());
        }
    }

    @Test
    @DisplayName("An action without delivery options is queued through the original call, unchanged")
    void defaultOptionsUseOriginalCall() {
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("email-1");

        handler.execute(makeContext("""
            {"to": "user@example.com", "subject": "Hi", "body": "x", "transactional": true}
            """));

        verify(emailService).queueEmail("t1", "user@example.com", "Hi", "x", "WORKFLOW", "wf1");
        verify(emailService, never()).queueEmail(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), any(EmailService.FlowMailOptions.class));
    }

    @Test
    @DisplayName("transactional=false and replyTo are passed to the email service")
    void passesDeliveryOptions() {
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(EmailService.FlowMailOptions.class)))
            .thenReturn(EmailService.QueuedEmail.queued("email-9"));

        ActionResult result = handler.execute(makeContext("""
            {"to": "user@example.com", "subject": "News", "body": "x",
             "transactional": false, "replyTo": "help@example.com"}
            """));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("QUEUED", result.outputData().get("status"));
        assertEquals("email-9", result.outputData().get("emailLogId"));
        verify(emailService).queueEmail("t1", "user@example.com", "News", "x", "WORKFLOW", "wf1",
            new EmailService.FlowMailOptions(false, "help@example.com"));
    }

    @Test
    @DisplayName("A suppressed recipient is a successful step with status SUPPRESSED")
    void suppressedRecipientIsNotAFailure() {
        when(emailService.queueEmail(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(EmailService.FlowMailOptions.class)))
            .thenReturn(EmailService.QueuedEmail.suppressed("email-s"));

        ActionResult result = handler.execute(makeContext("""
            {"to": "user@example.com", "subject": "News", "body": "x", "transactional": false}
            """));

        assertTrue(result.successful(), result.errorMessage());
        assertEquals("SUPPRESSED", result.outputData().get("status"));
        assertEquals("email-s", result.outputData().get("emailLogId"));
    }

    @Test
    @DisplayName("A replyTo that resolves to an invalid address at run time fails the step without sending")
    void invalidResolvedReplyToFails() {
        ActionResult result = handler.execute(makeContext("""
            {"to": "user@example.com", "subject": "Hi", "body": "x", "replyTo": "not an address"}
            """));

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("replyTo"));
        verifyNoInteractions(emailService);
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
