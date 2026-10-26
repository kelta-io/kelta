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
import java.util.regex.Pattern;

/**
 * Action handler that sends an email notification.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "to": "recipient@example.com",
 *   "subject": "Order Confirmed",
 *   "body": "Your order has been confirmed.",
 *   "templateId": "optional-template-id",
 *   "transactional": true,
 *   "replyTo": "support@example.com"
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
 * <p><b>Delivery mode.</b> {@code transactional} (boolean, default {@code true}) marks mail
 * the recipient must receive — no unsubscribe headers, no suppression check. With
 * {@code false} the email service skips a recipient the tenant has on its suppression list
 * (the result's {@code status} is {@code SUPPRESSED}, which is not a failure) and adds
 * RFC 8058 one-click {@code List-Unsubscribe} headers. {@code replyTo} is one address for
 * the {@code Reply-To} header. An action that sets neither is queued exactly as before.
 *
 * <p>Uses {@link EmailService} SPI to look up templates and queue emails.
 *
 * @since 1.0.0
 */
public class EmailAlertActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertActionHandler.class);
    private static final Set<String> UNRESOLVED_KEYS = Set.of("subject", "body");
    /** One bare addr-spec: no display name, no list, no whitespace or control characters. */
    private static final String ATOM = "[^\\s\\p{Cntrl}@<>()\\[\\],;:\"\\\\]+";
    private static final Pattern SINGLE_ADDRESS =
        Pattern.compile("^" + ATOM + "@" + ATOM + "\\." + ATOM + "$");

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

            EmailService.FlowMailOptions options;
            try {
                options = new EmailService.FlowMailOptions(
                    transactional(config.get("transactional")), replyTo(config.get("replyTo"), true));
            } catch (IllegalArgumentException e) {
                return ActionResult.failure(e.getMessage());
            }

            EmailService.QueuedEmail queued;
            if (options.equals(EmailService.FlowMailOptions.DEFAULT)) {
                queued = EmailService.QueuedEmail.queued(emailService.queueEmail(
                    context.tenantId(), to, subject, body != null ? body : "",
                    EmailService.FLOW_SOURCE, context.workflowRuleId()
                ));
            } else {
                queued = emailService.queueEmail(
                    context.tenantId(), to, subject, body != null ? body : "",
                    EmailService.FLOW_SOURCE, context.workflowRuleId(), options
                );
            }

            String status = queued.suppressed() ? "SUPPRESSED" : "QUEUED";
            log.info("Email alert {}: to={}, subject='{}', workflowRule={}",
                status.toLowerCase(), to, subject, context.workflowRuleId());

            return ActionResult.success(Map.of(
                "emailLogId", queued.emailLogId(),
                "to", to,
                "subject", subject,
                "status", status
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

    private static boolean transactional(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException("Config 'transactional' must be a boolean");
    }

    /**
     * Returns the single {@code replyTo} address, or {@code null} when absent.
     *
     * @param resolved {@code false} at design-time validation, where a placeholder or
     *                 JSONata expression is accepted because it only resolves at run time
     */
    private static String replyTo(Object value, boolean resolved) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String address)) {
            throw new IllegalArgumentException("Config 'replyTo' must be a single email address");
        }
        String trimmed = address.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!resolved && (trimmed.contains("${") || trimmed.startsWith("="))) {
            return trimmed;
        }
        if (!SINGLE_ADDRESS.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                "Config 'replyTo' must be a single email address, got: " + stripLineBreaks(trimmed));
        }
        return trimmed;
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

            transactional(config.get("transactional"));
            replyTo(config.get("replyTo"), false);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
