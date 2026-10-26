package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.module.integration.mapping.PayloadMapperException;
import io.kelta.runtime.module.integration.mapping.PayloadMapperService;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Action handler that sends an email notification.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "to": "recipient@example.com",
 *   "subject": "Order Confirmed",
 *   "body": "Your order has been confirmed.",
 *   "templateId": "optional-template-id"
 * }
 * </pre>
 *
 * <p>When a {@code templateId} is provided, the handler loads the email template
 * and uses its subject/body. Otherwise, the subject/body from the config are used.
 *
 * <p><b>Placeholders.</b> The flow engine passes {@code subject} and {@code body}
 * unresolved ({@link #unresolvedParameterKeys()}); the handler expands them exactly once
 * against the flow state, so a {@code ${...}} that arrives inside state data is sent as
 * text, never expanded. Template subjects/bodies follow the same rule.
 * <ul>
 *   <li>{@code body}: each {@code ${$.path}} value is HTML-escaped
 *       ({@code & < > " '}). Write {@code ${raw:$.path}} to insert a value unescaped —
 *       only for trusted HTML, never member-typed text.</li>
 *   <li>{@code subject}: values are not HTML-escaped (it is a header, not HTML); CR and
 *       LF are stripped from the result so a value cannot inject headers.</li>
 *   <li>A field starting with {@code =} is a JSONata expression; its result is used
 *       as-is, without escaping.</li>
 * </ul>
 *
 * <p>Uses {@link EmailService} SPI to look up templates and queue emails.
 *
 * @since 1.0.0
 */
public class EmailAlertActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertActionHandler.class);
    private static final Set<String> UNRESOLVED_KEYS = Set.of("subject", "body");

    private final ObjectMapper objectMapper;
    private final EmailService emailService;
    private final PayloadMapperService payloadMapper;

    public EmailAlertActionHandler(ObjectMapper objectMapper, EmailService emailService) {
        this(objectMapper, emailService, null);
    }

    public EmailAlertActionHandler(ObjectMapper objectMapper, EmailService emailService,
                                    PayloadMapperService payloadMapper) {
        this.objectMapper = objectMapper;
        this.emailService = emailService;
        this.payloadMapper = payloadMapper != null ? payloadMapper
            : new PayloadMapperService(new StateDataResolver(objectMapper));
    }

    @Override
    public String getActionTypeKey() {
        return "EMAIL_ALERT";
    }

    @Override
    public Set<String> unresolvedParameterKeys() {
        return UNRESOLVED_KEYS;
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String to = (String) config.get("to");
            String subject;
            String body;

            // Load template if specified
            String templateId = (String) config.get("templateId");
            if (templateId != null && !templateId.isBlank()) {
                Optional<EmailService.EmailTemplate> templateOpt = emailService.getTemplate(templateId);
                if (templateOpt.isPresent()) {
                    subject = templateOpt.get().subject();
                    body = templateOpt.get().bodyHtml();
                } else {
                    log.warn("Email template not found: {}, falling back to config", templateId);
                    subject = (String) config.get("subject");
                    body = (String) config.get("body");
                }
            } else {
                subject = (String) config.get("subject");
                body = (String) config.get("body");
            }

            if (to == null || to.isBlank()) {
                return ActionResult.failure("Email 'to' address is required");
            }

            if (subject == null || subject.isBlank()) {
                return ActionResult.failure("Email 'subject' is required");
            }

            Map<String, Object> stateData = mappingState(context);
            try {
                subject = stripLineBreaks(Objects.toString(
                    payloadMapper.mapTemplate(subject, stateData, value -> value), ""));
                body = Objects.toString(
                    payloadMapper.mapTemplate(body, stateData, EmailAlertActionHandler::escapeHtml), null);
            } catch (PayloadMapperException e) {
                return ActionResult.failure("Mapper.Failure: " + e.getMessage());
            }

            String emailLogId = emailService.queueEmail(
                context.tenantId(), to, subject, body != null ? body : "",
                "WORKFLOW", context.workflowRuleId()
            );

            log.info("Email alert queued: to={}, subject='{}', workflowRule={}",
                to, subject, context.workflowRuleId());

            return ActionResult.success(Map.of(
                "emailLogId", emailLogId,
                "to", to,
                "subject", subject,
                "status", "QUEUED"
            ));
        } catch (Exception e) {
            log.error("Failed to execute email alert action: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    private Map<String, Object> mappingState(ActionContext context) {
        Map<String, Object> base = context.stateData() != null ? context.stateData()
            : context.resolvedData() != null ? context.resolvedData() : context.data();
        return base != null ? base : new HashMap<>();
    }

    static String escapeHtml(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripLineBreaks(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("to") == null) {
                throw new IllegalArgumentException("Config must contain 'to' email address");
            }

            String templateId = (String) config.get("templateId");
            if (templateId == null || templateId.isBlank()) {
                if (config.get("subject") == null) {
                    throw new IllegalArgumentException(
                        "Config must contain 'subject' when no templateId is provided");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
