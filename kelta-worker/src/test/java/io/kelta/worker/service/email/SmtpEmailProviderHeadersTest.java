package io.kelta.worker.service.email;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the reply-header path added for the support mailbox.
 *
 * <p>Uses a real {@link MimeMessage} rather than a mock — the point of these tests is
 * what actually lands on the wire format, which a mock cannot tell us. The sender is
 * mocked only to stop delivery being attempted.
 */
@DisplayName("SmtpEmailProvider — reply headers")
class SmtpEmailProviderHeadersTest {

    private JavaMailSenderImpl sender;
    private SmtpEmailProvider provider;
    private MimeMessage sent;

    @BeforeEach
    void setUp() {
        Session session = Session.getInstance(new Properties());
        sender = mock(JavaMailSenderImpl.class);
        when(sender.getHost()).thenReturn("smtp.platform.io");
        when(sender.getSession()).thenReturn(session);
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));

        // Capture the message and run saveChanges() the way a real Transport.send would,
        // so the Message-ID readback under test is exercised rather than bypassed.
        doAnswer(inv -> {
            sent = inv.getArgument(0);
            sent.saveChanges();
            return null;
        }).when(sender).send(any(MimeMessage.class));

        provider = new SmtpEmailProvider(sender, "noreply@platform.io", "Platform");
    }

    @Test
    @DisplayName("Threading and automation headers are written to the message")
    void writesReplyHeaders() throws Exception {
        EmailHeaders headers = new EmailHeaders(
                "support+t42.abc123@spotopened.com",
                "<inbound-1@mail.example.com>",
                "<root@mail.example.com> <inbound-1@mail.example.com>",
                "auto-replied",
                null,
                null,
                Map.of("X-Kelta-Mailbox", "mb-1"));

        provider.send(new EmailMessage("user@test.com", "Re: Help", "<p>Hi</p>", null,
                List.of(), headers), null);

        assertThat(sent).isNotNull();
        assertThat(sent.getHeader("In-Reply-To", null)).isEqualTo("<inbound-1@mail.example.com>");
        assertThat(sent.getHeader("References", null))
                .isEqualTo("<root@mail.example.com> <inbound-1@mail.example.com>");
        assertThat(sent.getHeader("Auto-Submitted", null)).isEqualTo("auto-replied");
        assertThat(sent.getHeader("X-Kelta-Mailbox", null)).isEqualTo("mb-1");
        assertThat(sent.getReplyTo()[0].toString()).isEqualTo("support+t42.abc123@spotopened.com");
    }

    @Test
    @DisplayName("Headers absent from EmailHeaders are not emitted at all")
    void omitsUnsetHeaders() throws Exception {
        EmailHeaders headers = new EmailHeaders(null, "<x@y.com>", null, null, null, null, Map.of());

        provider.send(new EmailMessage("user@test.com", "Re: Help", "<p>Hi</p>", null,
                List.of(), headers), null);

        assertThat(sent.getHeader("In-Reply-To", null)).isEqualTo("<x@y.com>");
        assertThat(sent.getHeader("Auto-Submitted", null)).isNull();
        assertThat(sent.getHeader("Precedence", null)).isNull();
        assertThat(sent.getHeader("List-Unsubscribe", null)).isNull();
    }

    @Test
    @DisplayName("A plain send emits no reply headers — existing callers are unaffected")
    void plainSendEmitsNoReplyHeaders() throws Exception {
        provider.send(new EmailMessage("user@test.com", "Welcome", "<p>Hi</p>", null), null);

        assertThat(sent.getHeader("In-Reply-To", null)).isNull();
        assertThat(sent.getHeader("References", null)).isNull();
        assertThat(sent.getHeader("Auto-Submitted", null)).isNull();
        // Assert the raw header, not getReplyTo(): MimeMessage.getReplyTo() falls back to
        // From when no Reply-To is present, so it can never be null on a sendable message.
        assertThat(sent.getHeader("Reply-To", null)).isNull();
    }

    @Test
    @DisplayName("sendAndReport returns the Message-ID the mail system actually stamped")
    void reportsStampedMessageId() throws Exception {
        SendResult result = provider.sendAndReport(
                new EmailMessage("user@test.com", "Welcome", "<p>Hi</p>", null), null);

        // The value is generated inside saveChanges(); we assert it round-trips rather
        // than asserting a literal, because the local part is host- and time-derived.
        assertThat(result.messageId())
                .isNotNull()
                .startsWith("<")
                .endsWith(">")
                .isEqualTo(sent.getMessageID());
    }

    @Test
    @DisplayName("A caller-supplied Message-ID cannot survive — which is why it is read back")
    void callerSuppliedMessageIdIsOverwritten() throws Exception {
        EmailHeaders headers = new EmailHeaders(null, null, null, null, null, null,
                Map.of("Message-ID", "<chosen-by-caller@example.com>"));

        SendResult result = provider.sendAndReport(
                new EmailMessage("user@test.com", "Welcome", "<p>Hi</p>", null, List.of(), headers), null);

        assertThat(result.messageId()).isNotEqualTo("<chosen-by-caller@example.com>");
        assertThat(sent.getMessageID()).isNotEqualTo("<chosen-by-caller@example.com>");
    }

    /** Tenant settings carrying a From override only — mail still relays through the platform SMTP. */
    private static TenantEmailSettings tenantFromOnly() {
        return new TenantEmailSettings("tenant-1", null, 587, null, null, true,
                "alerts@tenant-mail.example", "Tenant Alerts");
    }

    private static String header(MimeMessage message, String name) throws Exception {
        return message.getHeader(name, null);
    }

    @Test
    @DisplayName("From is the platform address and no Sender is set when the tenant has no From override")
    void platformFromHasNoSender() throws Exception {
        provider.send(new EmailMessage("user@test.com", "Hi", "<p>Hi</p>", null), null);

        assertThat(((InternetAddress) sent.getFrom()[0]).getAddress()).isEqualTo("noreply@platform.io");
        assertThat(header(sent, "Sender")).isNull();
    }

    @Test
    @DisplayName("A tenant From relayed by the platform SMTP carries Sender = the platform from-address")
    void tenantFromViaPlatformSetsSender() throws Exception {
        provider.send(new EmailMessage("user@test.com", "Hi", "<p>Hi</p>", null), tenantFromOnly());

        assertThat(((InternetAddress) sent.getFrom()[0]).getAddress()).isEqualTo("alerts@tenant-mail.example");
        assertThat(((InternetAddress) sent.getSender()).getAddress()).isEqualTo("noreply@platform.io");
    }

    @Test
    @DisplayName("A tenant From sent through the tenant's own SMTP has no Sender")
    void tenantFromViaTenantSmtpHasNoSender() throws Exception {
        TenantEmailSettings ownSmtp = new TenantEmailSettings("tenant-2", "smtp.tenant-mail.example", 587,
                "user", "pass", true, "alerts@tenant-mail.example", "Tenant Alerts");
        SmtpEmailProvider spy = org.mockito.Mockito.spy(provider);
        // Route the tenant send to the capturing double instead of a real SMTP connection.
        org.mockito.Mockito.doReturn(sender).when(spy).resolveSender(ownSmtp);

        spy.send(new EmailMessage("user@test.com", "Hi", "<p>Hi</p>", null), ownSmtp);

        assertThat(((InternetAddress) sent.getFrom()[0]).getAddress()).isEqualTo("alerts@tenant-mail.example");
        assertThat(header(sent, "Sender")).isNull();
    }

    @Test
    @DisplayName("Message-ID is on the domain of the From address — tenant override and platform fallback")
    void messageIdUsesFromDomain() throws Exception {
        SendResult tenant = provider.sendAndReport(
                new EmailMessage("user@test.com", "Hi", "<p>Hi</p>", null), tenantFromOnly());
        assertThat(tenant.messageId()).startsWith("<").endsWith("@tenant-mail.example>")
                .isEqualTo(sent.getMessageID());

        SendResult platform = provider.sendAndReport(
                new EmailMessage("user@test.com", "Hi", "<p>Hi</p>", null), null);
        assertThat(platform.messageId()).startsWith("<").endsWith("@platform.io>")
                .isEqualTo(sent.getMessageID());
        assertThat(platform.messageId()).isNotEqualTo(tenant.messageId());
    }
}
