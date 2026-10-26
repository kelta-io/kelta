package io.kelta.worker.service.email;

import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.repository.EmailRepository.TenantEmailConfigRow;
import io.kelta.worker.repository.EmailSuppressionRepository;
import io.kelta.worker.service.campaign.TrackingTokenService;
import io.kelta.worker.service.credential.CredentialNotFoundException;
import io.kelta.worker.service.credential.CredentialResolver;
import io.kelta.worker.service.credential.ResolutionContext;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of the {@link EmailService} SPI.
 *
 * <p>Orchestrates email delivery by:
 * <ol>
 *   <li>Resolving per-tenant SMTP settings — preferring the credential vault row
 *       referenced by {@code tenant.email_smtp_credential_id}, falling back to
 *       the legacy {@code tenant.settings.email} JSONB blob.</li>
 *   <li>Logging the email attempt to {@code email_log}.</li>
 *   <li>Delegating delivery to the configured {@link EmailProvider}.</li>
 *   <li>Updating the log with success/failure status.</li>
 * </ol>
 *
 * <p>Delivery is asynchronous via {@code @Async("emailExecutor")} to avoid
 * blocking the caller. Failures are logged but never raised back to the caller.
 *
 * <p><b>Flow mail</b> (source {@value EmailService#FLOW_SOURCE}, and every send through
 * {@link #queueEmail(String, String, String, String, String, String, FlowMailOptions)})
 * carries {@code Auto-Submitted: auto-generated} and the action's {@code Reply-To}. A
 * non-transactional flow send is checked against {@code email_suppression} first — a
 * suppressed recipient gets an {@code email_log} row with status {@code SUPPRESSED} and no
 * mail — and otherwise carries RFC 8058 one-click {@code List-Unsubscribe} pointing at
 * {@code /api/track/email-unsubscribe} with a signed address token. Only the HTTPS form is
 * sent: the platform has no inbound address that processes {@code mailto:} unsubscribes.
 *
 * @since 1.0.0
 */
public class DefaultEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEmailService.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)\\}");
    static final String UNSUBSCRIBE_PATH = "/api/track/email-unsubscribe";
    private static final String AUTO_GENERATED = "auto-generated";

    private final EmailProvider emailProvider;
    private final EmailRepository emailRepository;
    private final CredentialResolver credentialResolver;
    private final String platformFromAddress;
    private final String platformFromName;
    private final boolean enabled;
    private final EmailSuppressionRepository suppressionRepository;
    private final TrackingTokenService tokenService;
    private final String trackingBaseUrl;

    public DefaultEmailService(EmailProvider emailProvider,
                               EmailRepository emailRepository,
                               CredentialResolver credentialResolver,
                               String platformFromAddress,
                               String platformFromName,
                               boolean enabled,
                               EmailSuppressionRepository suppressionRepository,
                               TrackingTokenService tokenService,
                               String trackingBaseUrl) {
        this.emailProvider = emailProvider;
        this.emailRepository = emailRepository;
        this.credentialResolver = credentialResolver;
        this.platformFromAddress = platformFromAddress;
        this.platformFromName = platformFromName;
        this.enabled = enabled;
        this.suppressionRepository = suppressionRepository;
        this.tokenService = tokenService;
        this.trackingBaseUrl = trackingBaseUrl;
    }

    @Override
    public Optional<EmailTemplate> getTemplate(String templateId) {
        String tenantId = io.kelta.runtime.context.TenantContext.get();
        if (tenantId == null) {
            log.warn("No tenant context available for template lookup");
            return Optional.empty();
        }

        return emailRepository.findTemplateByTenantAndId(tenantId, templateId)
                .map(row -> new EmailTemplate(
                        (String) row.get("subject"),
                        (String) row.get("body_html")
                ));
    }

    @Override
    public String queueEmail(String tenantId, String to, String subject, String body,
                             String source, String sourceId) {
        if (!enabled) {
            log.warn("Email delivery disabled — skipping email to {} with subject '{}'", to, subject);
            return UUID.randomUUID().toString();
        }

        EmailHeaders headers = FLOW_SOURCE.equals(source)
                ? flowHeaders(tenantId, to, FlowMailOptions.DEFAULT)
                : EmailHeaders.none();
        return logAndSend(tenantId, to, subject, body, source, sourceId, headers);
    }

    @Override
    public QueuedEmail queueEmail(String tenantId, String to, String subject, String body,
                                  String source, String sourceId, FlowMailOptions options) {
        FlowMailOptions opts = options != null ? options : FlowMailOptions.DEFAULT;
        if (!enabled) {
            log.warn("Email delivery disabled — skipping email to {} with subject '{}'", to, subject);
            return QueuedEmail.queued(UUID.randomUUID().toString());
        }
        // Built before the suppression check so an invalid replyTo fails the step whether or
        // not this particular recipient opted out.
        EmailHeaders headers = flowHeaders(tenantId, to, opts);
        if (!opts.transactional() && suppressionRepository.isSuppressed(tenantId, to)) {
            String logId = emailRepository.createEmailLog(tenantId, to, subject, source, sourceId);
            emailRepository.markSuppressed(logId);
            log.info("Email suppressed: logId={}, tenant={}, recipient is on the suppression list",
                    logId, tenantId);
            return QueuedEmail.suppressed(logId);
        }
        return QueuedEmail.queued(logAndSend(tenantId, to, subject, body, source, sourceId, headers));
    }

    private String logAndSend(String tenantId, String to, String subject, String body,
                              String source, String sourceId, EmailHeaders headers) {
        String logId = emailRepository.createEmailLog(tenantId, to, subject, source, sourceId);
        TenantEmailSettings tenantSettings = resolveTenantSettings(tenantId);
        sendAsync(logId, to, subject, body, tenantSettings, java.util.List.of(), headers);
        return logId;
    }

    /**
     * Headers of a flow send: always {@code Auto-Submitted} (RFC 3834) and the action's
     * {@code Reply-To}; for non-transactional mail also RFC 8058 one-click unsubscribe.
     */
    private EmailHeaders flowHeaders(String tenantId, String to, FlowMailOptions options) {
        String replyTo = singleAddress(options.replyTo());
        if (options.transactional()) {
            return new EmailHeaders(replyTo, null, null, AUTO_GENERATED, null, null, Map.of());
        }
        return new EmailHeaders(replyTo, null, null, AUTO_GENERATED, null,
                "<" + unsubscribeUrl(tenantId, to) + ">",
                Map.of("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
    }

    private String unsubscribeUrl(String tenantId, String to) {
        return trackingBaseUrl + UNSUBSCRIBE_PATH + "?t="
                + URLEncoder.encode(tokenService.signAddress(tenantId, to), StandardCharsets.UTF_8);
    }

    private static String singleAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            return new InternetAddress(address.strip(), true).getAddress();
        } catch (AddressException e) {
            throw new IllegalArgumentException("replyTo is not a single valid email address", e);
        }
    }

    @Override
    public Optional<String> sendByKey(String tenantId, String to, String templateKey,
                                      Map<String, Object> vars, String source, String sourceId) {
        var templateRow = emailRepository.findTemplateByKey(tenantId, templateKey);
        if (templateRow.isEmpty()) {
            log.warn("No template found for key '{}' (tenant {} or system fallback)", templateKey, tenantId);
            return Optional.empty();
        }
        Map<String, Object> safeVars = vars == null ? Map.of() : vars;
        String subject = substitute((String) templateRow.get().get("subject"), safeVars);
        String body    = substitute((String) templateRow.get().get("body_html"), safeVars);
        return Optional.of(queueEmail(tenantId, to, subject, body, source, sourceId));
    }

    @Override
    public Optional<String> sendByName(String tenantId, String to, String name,
                                       Map<String, Object> vars, String source, String sourceId) {
        var templateRow = emailRepository.findTemplateByName(tenantId, name);
        if (templateRow.isEmpty()) {
            log.warn("No template found for name '{}' (tenant {} or system fallback)", name, tenantId);
            return Optional.empty();
        }
        Map<String, Object> safeVars = vars == null ? Map.of() : vars;
        String subject = substitute((String) templateRow.get().get("subject"), safeVars);
        String body    = substitute((String) templateRow.get().get("body_html"), safeVars);
        return Optional.of(queueEmail(tenantId, to, subject, body, source, sourceId));
    }

    @Override
    public Optional<String> sendById(String tenantId, String to, String templateId,
                                     Map<String, Object> vars, String source, String sourceId) {
        var templateRow = emailRepository.findTemplateByTenantAndId(tenantId, templateId);
        if (templateRow.isEmpty()) {
            log.warn("No active template found for id '{}' (tenant {})", templateId, tenantId);
            return Optional.empty();
        }
        Map<String, Object> safeVars = vars == null ? Map.of() : vars;
        String subject = substitute((String) templateRow.get().get("subject"), safeVars);
        String body    = substitute((String) templateRow.get().get("body_html"), safeVars);
        return Optional.of(queueEmail(tenantId, to, subject, body, source, sourceId));
    }

    /**
     * Replaces {@code ${name}} placeholders in {@code template} with values from {@code vars}.
     * Unknown variables are left untouched so the message is still readable when a caller forgets
     * a key — and the gap is obvious in QA. Public so template-resolving callers outside this
     * package (appointment confirmations with attachments) render the same way.
     */
    public static String substitute(String template, Map<String, Object> vars) {
        if (template == null) return null;
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(
                    value == null ? m.group(0) : value.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Queues an email with file attachments. Same log/async pipeline as
     * {@link #queueEmail}; not part of the {@code EmailService} SPI — callers
     * needing attachments (scheduled report delivery) inject this class.
     *
     * @return the email log id
     */
    public String queueEmailWithAttachments(String tenantId, String to, String subject, String body,
                                            String source, String sourceId,
                                            java.util.List<EmailAttachment> attachments) {
        if (!enabled) {
            log.warn("Email delivery disabled — skipping email to {} with subject '{}'", to, subject);
            return UUID.randomUUID().toString();
        }
        String logId = emailRepository.createEmailLog(tenantId, to, subject, source, sourceId);
        TenantEmailSettings tenantSettings = resolveTenantSettings(tenantId);
        sendAsync(logId, to, subject, body, tenantSettings, attachments);
        return logId;
    }

    /**
     * Queues a reply: an email that belongs to an existing conversation.
     *
     * <p>Differs from {@link #queueEmail} only in carrying {@link EmailHeaders}, but that
     * difference is what makes it a reply rather than a fresh message — {@code In-Reply-To}
     * and {@code References} put it in the right thread in the recipient's client, and
     * {@code Auto-Submitted} keeps an automated one from provoking the far side's
     * autoresponder.
     *
     * <p>Not part of the {@code EmailService} SPI: adding it there would ripple into every
     * runtime module for a capability only the mailbox needs.
     *
     * <p><b>Callers are responsible for checking the suppression list.</b> This method does
     * not, matching the plain {@link #queueEmail(String, String, String, String, String, String)};
     * only a non-transactional flow send checks the list itself.
     *
     * @return a future completing with what the provider stamped on the message, notably
     *         the {@code Message-ID} needed to match the recipient's eventual reply back
     *         to this conversation. The email log id is available synchronously via
     *         {@link #queueEmail}-style logging and is included in the failure path.
     */
    public java.util.concurrent.CompletableFuture<SendResult> queueReply(
            String tenantId, String to, String subject, String body,
            String source, String sourceId, String fromAddress, String fromName,
            java.util.List<EmailAttachment> attachments, EmailHeaders headers) {
        if (!enabled) {
            log.warn("Email delivery disabled — skipping reply to {} with subject '{}'", to, subject);
            return java.util.concurrent.CompletableFuture.completedFuture(SendResult.notDelivered());
        }
        String logId = emailRepository.createEmailLog(tenantId, to, subject, source, sourceId);
        TenantEmailSettings tenantSettings = resolveTenantSettings(tenantId);
        // A reply sends as the mailbox, not as the tenant-wide identity. Without this the message
        // goes out from the tenant's from-address — usually noreply@ — while the database records
        // the mailbox address, so the wire and the row disagree and only the recipient can tell.
        if (fromAddress != null && !fromAddress.isBlank()) {
            tenantSettings = tenantSettings == null
                    ? new TenantEmailSettings(tenantId, null, 0, null, null, false,
                            fromAddress, fromName)
                    : tenantSettings.withFrom(fromAddress, fromName);
        }
        return sendAsyncReporting(logId, to, subject, body, tenantSettings, attachments, headers);
    }

    @Async("emailExecutor")
    public void sendAsync(String logId, String to, String subject, String body,
                          TenantEmailSettings tenantSettings) {
        doSend(logId, to, subject, body, tenantSettings, java.util.List.of(), EmailHeaders.none());
    }

    @Async("emailExecutor")
    public void sendAsync(String logId, String to, String subject, String body,
                          TenantEmailSettings tenantSettings,
                          java.util.List<EmailAttachment> attachments) {
        doSend(logId, to, subject, body, tenantSettings, attachments, EmailHeaders.none());
    }

    @Async("emailExecutor")
    public void sendAsync(String logId, String to, String subject, String body,
                          TenantEmailSettings tenantSettings,
                          java.util.List<EmailAttachment> attachments, EmailHeaders headers) {
        doSend(logId, to, subject, body, tenantSettings, attachments, headers);
    }

    /**
     * Async send that reports the provider's result back to the caller.
     *
     * <p>The {@code void} overloads above cannot do this, which is the whole reason this
     * exists: a conversation cannot be threaded without knowing the {@code Message-ID}
     * that went out, and that value only exists after the send completes.
     */
    @Async("emailExecutor")
    public java.util.concurrent.CompletableFuture<SendResult> sendAsyncReporting(
            String logId, String to, String subject, String body,
            TenantEmailSettings tenantSettings,
            java.util.List<EmailAttachment> attachments, EmailHeaders headers) {
        return java.util.concurrent.CompletableFuture.completedFuture(
                doSend(logId, to, subject, body, tenantSettings, attachments, headers));
    }

    /**
     * The single send implementation. Both {@code @Async} entry points delegate here so
     * that neither self-invokes the other — a self-call would bypass the Spring proxy and
     * silently run on the calling thread, which is a confusing way to lose asynchrony.
     *
     * <p>Never throws. Delivery failures are recorded on the email log and swallowed,
     * matching the pre-existing contract that callers are not notified of send failures.
     */
    private SendResult doSend(String logId, String to, String subject, String body,
                              TenantEmailSettings tenantSettings,
                              java.util.List<EmailAttachment> attachments, EmailHeaders headers) {
        String smtpHost = resolveSmtpHost(tenantSettings);

        try {
            emailRepository.markSending(logId);

            EmailMessage message = new EmailMessage(to, subject, body, null, attachments, headers);
            SendResult result = emailProvider.sendAndReport(message, tenantSettings);

            emailRepository.markSent(logId, smtpHost);
            log.info("Email sent: logId={}, to={}, smtpHost={}", logId, to, smtpHost);
            // A third-party provider is free to return null from the SPI; callers of
            // queueReply should not have to defend against it.
            return result != null ? result : SendResult.sent(null);

        } catch (EmailDeliveryException e) {
            emailRepository.markFailed(logId, e.getMessage(), smtpHost);
            log.error("Email delivery failed: logId={}, to={}, smtpHost={}, error={}",
                    logId, to, smtpHost, e.getMessage());
        } catch (Exception e) {
            emailRepository.markFailed(logId, e.getMessage(), smtpHost);
            log.error("Unexpected error during email delivery: logId={}, to={}", logId, to, e);
        }
        return SendResult.notDelivered();
    }

    private TenantEmailSettings resolveTenantSettings(String tenantId) {
        try {
            TenantEmailConfigRow row = emailRepository.findTenantEmailConfig(tenantId);
            if (row == null) {
                return null;
            }
            if (row.smtpCredentialId() != null && credentialResolver != null) {
                try {
                    var resolved = credentialResolver.resolve(
                            tenantId,
                            row.smtpCredentialId(),
                            ResolutionContext.forUser(null, "EMAIL_SEND"));
                    return TenantEmailSettings.fromCredential(
                            tenantId,
                            resolved.secretFields(),
                            new HashMap<>(resolved.metadataFields()),
                            row.fromAddress(),
                            row.fromName());
                } catch (CredentialNotFoundException e) {
                    log.warn("Tenant {} references missing SMTP credential {} — falling back",
                            tenantId, row.smtpCredentialId());
                }
            }
            // Legacy JSONB or From-only override
            TenantEmailSettings legacy = row.legacySettings() == null
                    ? null
                    : TenantEmailSettings.fromJsonNode(tenantId, row.legacySettings());
            if (legacy == null && row.fromAddress() == null && row.fromName() == null) {
                return null;
            }
            String fromAddr = row.fromAddress() != null ? row.fromAddress()
                    : (legacy != null ? legacy.fromAddress() : null);
            String fromName = row.fromName() != null ? row.fromName()
                    : (legacy != null ? legacy.fromName() : null);
            return new TenantEmailSettings(
                    tenantId,
                    legacy != null ? legacy.smtpHost() : null,
                    legacy != null ? legacy.smtpPort() : 587,
                    legacy != null ? legacy.smtpUsername() : null,
                    legacy != null ? legacy.smtpPassword() : null,
                    legacy == null || legacy.smtpStartTls(),
                    fromAddr,
                    fromName);
        } catch (Exception e) {
            log.warn("Failed to load tenant email settings for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    private String resolveSmtpHost(TenantEmailSettings tenantSettings) {
        if (emailProvider instanceof SmtpEmailProvider smtp) {
            return smtp.resolveSmtpHost(tenantSettings);
        }
        return "unknown-provider";
    }
}
