package io.kelta.worker.service.email;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * SMTP-based email provider using Jakarta Mail (RFC 5321).
 *
 * <p>This is the opinionated default provider. It sends emails via standard SMTP
 * and supports per-tenant SMTP configuration overrides. Tenant-specific
 * {@link JavaMailSender} instances are cached (Caffeine, 5-min TTL, max 100 entries)
 * to avoid creating new SMTP connections per email.
 *
 * <p><b>Sender identity.</b> {@code From} is the tenant's from-address when set, else the
 * platform's. When a tenant From goes out through the <em>platform</em> SMTP relay, the message
 * also carries {@code Sender: <platform from-address>} (RFC 5322 3.6.2) — the mailbox actually
 * transmitting it. {@code Message-ID} is generated on the domain of the From address, so it
 * aligns with the identity the recipient's provider authenticates.
 *
 * @since 1.0.0
 */
public class SmtpEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailProvider.class);

    private final JavaMailSender platformMailSender;
    private final String platformFromAddress;
    private final String platformFromName;

    private final Cache<String, JavaMailSenderImpl> tenantSenderCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public SmtpEmailProvider(JavaMailSender platformMailSender,
                             String platformFromAddress,
                             String platformFromName) {
        this.platformMailSender = platformMailSender;
        this.platformFromAddress = platformFromAddress;
        this.platformFromName = platformFromName;
    }

    @Override
    public void send(EmailMessage message, TenantEmailSettings tenantSettings) throws EmailDeliveryException {
        sendAndReport(message, tenantSettings);
    }

    @Override
    public SendResult sendAndReport(EmailMessage message, TenantEmailSettings tenantSettings)
            throws EmailDeliveryException {
        JavaMailSender sender = resolveSender(tenantSettings);
        String fromAddress = resolveFromAddress(tenantSettings);
        String fromName = resolveFromName(tenantSettings);

        try {
            // Prefer the resolved (possibly per-tenant) sender's factory so the message
            // is built against the session that will transmit it. Falling back to the
            // platform sender only matters for JavaMailSender implementations that are
            // not JavaMailSenderImpl, which in practice means test doubles.
            Session session = sender instanceof JavaMailSenderImpl impl
                    ? impl.getSession()
                    : platformMailSender.createMimeMessage().getSession();
            MimeMessage mimeMessage = new FromDomainMimeMessage(session, domainOf(fromAddress));

            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setTo(message.to());
            helper.setFrom(new InternetAddress(fromAddress, fromName));
            if (sendsTenantFromViaPlatform(tenantSettings, fromAddress)) {
                mimeMessage.setSender(new InternetAddress(platformFromAddress));
            }
            helper.setSubject(message.subject());

            applyHeaders(mimeMessage, helper, message.headers());

            if (message.bodyText() != null) {
                helper.setText(message.bodyText(), message.bodyHtml());
            } else {
                helper.setText(message.bodyHtml(), true);
            }

            for (EmailAttachment attachment : message.attachments()) {
                helper.addAttachment(attachment.filename(),
                        new org.springframework.core.io.ByteArrayResource(attachment.content()),
                        attachment.contentType());
            }

            sender.send(mimeMessage);

            // Read the id back rather than assigning one: saveChanges() — which send()
            // invokes — regenerates Message-ID unconditionally (FromDomainMimeMessage only
            // chooses its domain), so anything set before this point is already gone.
            // This is the only moment the real value exists.
            return SendResult.sent(readMessageId(mimeMessage));

        } catch (MailAuthenticationException e) {
            // Invalidate cached sender on auth failure — credentials may have changed
            if (tenantSettings != null && tenantSettings.hasSmtpOverride() && tenantSettings.tenantId() != null) {
                tenantSenderCache.invalidate(tenantSettings.tenantId());
                log.warn("SMTP auth failed for tenant {} (host {}), sender cache invalidated",
                        tenantSettings.tenantId(), tenantSettings.smtpHost());
            }
            throw new EmailDeliveryException("SMTP authentication failed for host " + resolveSmtpHost(tenantSettings), e);
        } catch (MailException e) {
            throw new EmailDeliveryException("SMTP delivery failed: " + e.getMessage(), e);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new EmailDeliveryException("Failed to build email message: " + e.getMessage(), e);
        }
    }

    /**
     * Applies the caller's RFC 5322 headers to the message under construction.
     *
     * <p>Values arrive already validated for CR/LF by {@link EmailHeaders}, so there is
     * no header-injection check here — doing it in the record means an unsafe
     * {@code EmailHeaders} cannot be constructed at all, rather than merely being
     * refused at send time by whichever provider remembered to look.
     *
     * <p>{@code Message-ID} is deliberately not settable: see {@link #readMessageId}.
     */
    private void applyHeaders(MimeMessage mimeMessage, MimeMessageHelper helper, EmailHeaders headers)
            throws MessagingException {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        if (headers.replyTo() != null) {
            helper.setReplyTo(headers.replyTo());
        }
        setIfPresent(mimeMessage, "In-Reply-To", headers.inReplyTo());
        setIfPresent(mimeMessage, "References", headers.references());
        setIfPresent(mimeMessage, "Auto-Submitted", headers.autoSubmitted());
        setIfPresent(mimeMessage, "Precedence", headers.precedence());
        setIfPresent(mimeMessage, "List-Unsubscribe", headers.listUnsubscribe());
        for (var entry : headers.extra().entrySet()) {
            setIfPresent(mimeMessage, entry.getKey(), entry.getValue());
        }
    }

    private void setIfPresent(MimeMessage mimeMessage, String name, String value)
            throws MessagingException {
        if (value != null) {
            mimeMessage.setHeader(name, value);
        }
    }

    /**
     * Reads back the {@code Message-ID} Jakarta Mail stamped during {@code saveChanges()}.
     *
     * <p>Never fails the send. The message is already handed to the MTA by the time this
     * runs, so throwing here would report a delivery failure for mail that was delivered —
     * and would do so to make a threading hint available. Losing the hint degrades
     * conversation threading on later replies; losing the send does not.
     */
    private String readMessageId(MimeMessage mimeMessage) {
        try {
            return mimeMessage.getMessageID();
        } catch (MessagingException e) {
            log.debug("Could not read back Message-ID after send: {}", e.getMessage());
            return null;
        }
    }

    /**
     * True when a tenant's own From address is relayed by the platform SMTP server — the one
     * case where the author (From) and the transmitting mailbox differ and {@code Sender} says so.
     */
    private boolean sendsTenantFromViaPlatform(TenantEmailSettings tenantSettings, String fromAddress) {
        return tenantSettings != null
                && tenantSettings.hasFromOverride()
                && !tenantSettings.hasSmtpOverride()
                && platformFromAddress != null
                && !platformFromAddress.equalsIgnoreCase(fromAddress);
    }

    /** The domain part of an address, lower-cased; {@code null} when it has none. */
    static String domainOf(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        String domain = address.substring(at + 1).strip().toLowerCase(Locale.ROOT);
        return domain.matches("[a-z0-9.-]+") ? domain : null;
    }

    /**
     * A {@link MimeMessage} whose generated {@code Message-ID} is {@code <uuid@from-domain>}
     * rather than Jakarta Mail's default, which is derived from the local host name — a pod
     * name nobody can authenticate. Falls back to the default when the From address has no
     * usable domain.
     */
    static final class FromDomainMimeMessage extends MimeMessage {

        private final String domain;

        FromDomainMimeMessage(Session session, String domain) {
            super(session);
            this.domain = domain;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            if (domain == null) {
                super.updateMessageID();
                return;
            }
            setHeader("Message-ID", "<" + UUID.randomUUID() + "@" + domain + ">");
        }
    }

    /**
     * Returns the SMTP host used for a given tenant settings (for audit trail).
     */
    public String resolveSmtpHost(TenantEmailSettings tenantSettings) {
        if (tenantSettings != null && tenantSettings.hasSmtpOverride()) {
            return tenantSettings.smtpHost();
        }
        return platformMailSender instanceof JavaMailSenderImpl impl ? impl.getHost() : "platform-default";
    }

    // Package-private so a test can substitute the per-tenant sender without an SMTP server.
    JavaMailSender resolveSender(TenantEmailSettings tenantSettings) {
        if (tenantSettings == null || !tenantSettings.hasSmtpOverride()) {
            return platformMailSender;
        }
        // Key by tenantId so config-change events can evict per tenant without
        // needing to know the previous host:port pair.
        String cacheKey = tenantSettings.tenantId() != null
                ? tenantSettings.tenantId()
                : tenantSettings.smtpHost() + ":" + tenantSettings.smtpPort();
        return tenantSenderCache.get(cacheKey, key -> createTenantSender(tenantSettings));
    }

    /**
     * Drops the cached tenant sender for {@code tenantId}. Invoked by the NATS
     * listener when {@code tenant.email_*} columns or the SMTP credential change.
     */
    public void evictTenant(String tenantId) {
        if (tenantId == null) return;
        tenantSenderCache.invalidate(tenantId);
        log.debug("Evicted SMTP sender cache for tenant {}", tenantId);
    }

    /** Drops all cached tenant senders. Intended for credential rotations. */
    public void evictAll() {
        tenantSenderCache.invalidateAll();
        log.debug("Evicted all tenant SMTP sender caches");
    }

    private JavaMailSenderImpl createTenantSender(TenantEmailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.smtpHost());
        sender.setPort(settings.smtpPort());
        if (settings.smtpUsername() != null) {
            sender.setUsername(settings.smtpUsername());
            sender.setPassword(settings.smtpPassword());
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", settings.smtpUsername() != null ? "true" : "false");
        props.put("mail.smtp.starttls.enable", String.valueOf(settings.smtpStartTls()));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");

        log.info("Created tenant SMTP sender for host {}:{}", settings.smtpHost(), settings.smtpPort());
        return sender;
    }

    private String resolveFromAddress(TenantEmailSettings tenantSettings) {
        if (tenantSettings != null && tenantSettings.hasFromOverride()) {
            return tenantSettings.fromAddress();
        }
        return platformFromAddress;
    }

    private String resolveFromName(TenantEmailSettings tenantSettings) {
        if (tenantSettings != null && tenantSettings.fromName() != null && !tenantSettings.fromName().isBlank()) {
            return tenantSettings.fromName();
        }
        return platformFromName;
    }
}
