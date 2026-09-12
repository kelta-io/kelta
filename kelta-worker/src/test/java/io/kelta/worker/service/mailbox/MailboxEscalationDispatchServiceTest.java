package io.kelta.worker.service.mailbox;

import tools.jackson.databind.ObjectMapper;
import io.kelta.worker.repository.MailboxEscalationRepository;
import io.kelta.worker.service.email.DefaultEmailService;
import io.kelta.worker.service.push.DefaultPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how the escalation body is resolved.
 *
 * <p>{@code email_template} carries both a {@code name} and a {@code template_key}, and for this
 * row they are different strings: the name is "Support SLA escalation", the key is
 * "support.sla_escalation". Dispatch passed the key to {@code sendByName}, which matches on
 * {@code name}, so every escalation email failed with "template not found" while the seeded row sat
 * in the table. The claim, the delivery ledger and the retry logic were all working; the message
 * simply never rendered.
 *
 * <p>Both lookups are legitimate — {@code AlertDispatchService} deliberately resolves a
 * tenant-authored template by {@code name} — which is why the wrong one reads as correct code and
 * why this is worth a test rather than a comment.
 */
@DisplayName("MailboxEscalationDispatchService")
class MailboxEscalationDispatchServiceTest {

    private static final String TENANT = "t1";
    private static final String MAILBOX = "mb1";
    private static final String USER = "u1";

    private MailboxEscalationRepository escalationRepository;
    private DefaultEmailService emailService;
    private JdbcTemplate jdbcTemplate;
    private MailboxEscalationDispatchService service;

    private final MailboxEscalationRepository.Claimed escalation =
            new MailboxEscalationRepository.Claimed(
                    "esc-1", TENANT, MAILBOX, "th-1", "FIRST_RESPONSE", "BREACH", Instant.now());

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        escalationRepository = mock(MailboxEscalationRepository.class);
        emailService = mock(DefaultEmailService.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        ObjectProvider<DefaultEmailService> emailProvider = mock(ObjectProvider.class);
        when(emailProvider.getIfAvailable()).thenReturn(emailService);
        ObjectProvider<DefaultPushService> pushProvider = mock(ObjectProvider.class);
        when(pushProvider.getIfAvailable()).thenReturn(null);

        service = new MailboxEscalationDispatchService(escalationRepository, jdbcTemplate,
                emailProvider, pushProvider, new ObjectMapper(), "https://app.example.com");

        when(escalationRepository.contactsFor(TENANT, MAILBOX, "BREACH"))
                .thenReturn(List.of(Map.of("user_id", USER, "channels", "[\"email\"]")));
        when(escalationRepository.createPending(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of("del-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "subject", "Booking question",
                        "requester_email", "alex@example.com",
                        "mailbox_name", "Support",
                        "slug", "acme")));
        // userEmail() uses the queryForList(sql, Class, args) overload, not queryForObject.
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("agent@example.com"));
        when(emailService.sendByKey(anyString(), anyString(), anyString(), any(), anyString(),
                anyString())).thenReturn(Optional.of("log-1"));
    }

    @Test
    @DisplayName("Resolves the body by template_key, never by name")
    void resolvesByKeyNotName() {
        service.dispatch(escalation);

        verify(emailService).sendByKey(eq(TENANT), eq("agent@example.com"),
                eq("support.sla_escalation"), any(), eq("support-escalation"), eq("esc-1"));
        // sendByName matches on the name column, where this value does not exist.
        verify(emailService, never()).sendByName(anyString(), anyString(), anyString(), any(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("The thread link is on the app host and carries the tenant slug")
    @SuppressWarnings("unchecked")
    void threadLinkIsUsable() {
        // Built on kelta.external-base-url this was https://api.kelta.io/app/mailbox?thread=…:
        // the gateway host, no slug, a 404 in every escalation email ever sent.
        service.dispatch(escalation);

        org.mockito.ArgumentCaptor<Map<String, Object>> vars =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendByKey(anyString(), anyString(), anyString(), vars.capture(),
                anyString(), anyString());
        assertThat(vars.getValue().get("threadUrl"))
                .isEqualTo("https://app.example.com/acme/app/mailbox?thread=th-1");
    }

    @Test
    @DisplayName("A delivered escalation is marked SENT")
    void marksSent() {
        service.dispatch(escalation);

        verify(escalationRepository).markSent(TENANT, "del-1");
        verify(escalationRepository, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A missing template marks the delivery FAILED rather than throwing")
    void missingTemplateIsRecordedNotThrown() {
        // The empty Optional is exactly what the key-versus-name mismatch produced in production.
        when(emailService.sendByKey(anyString(), anyString(), anyString(), any(), anyString(),
                anyString())).thenReturn(Optional.empty());

        service.dispatch(escalation);

        verify(escalationRepository).markFailed(eq(TENANT), eq("del-1"), anyString());
        verify(escalationRepository, never()).markSent(anyString(), anyString());
    }

    @Test
    @DisplayName("No contacts at this level is a warning, not a delivery")
    void noContactsSendsNothing() {
        when(escalationRepository.contactsFor(TENANT, MAILBOX, "BREACH")).thenReturn(List.of());

        service.dispatch(escalation);

        verify(escalationRepository, never()).createPending(anyString(), anyString(), anyString(),
                any());
        verify(emailService, never()).sendByKey(anyString(), anyString(), anyString(), any(),
                anyString(), anyString());
    }
}
