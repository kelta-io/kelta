package io.kelta.worker.service.email;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.config.CampaignProperties;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.repository.EmailRepository.TenantEmailConfigRow;
import io.kelta.worker.repository.EmailSuppressionRepository;
import io.kelta.worker.service.campaign.TrackingTokenService;
import io.kelta.worker.service.credential.CredentialResolver;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@DisplayName("DefaultEmailService")
class DefaultEmailServiceTest {

    private EmailProvider emailProvider;
    private EmailRepository emailRepository;
    private CredentialResolver credentialResolver;
    private EmailSuppressionRepository suppressionRepository;
    private TrackingTokenService tokenService;
    private DefaultEmailService service;

    @BeforeEach
    void setUp() {
        emailProvider = mock(EmailProvider.class);
        emailRepository = mock(EmailRepository.class);
        credentialResolver = mock(CredentialResolver.class);
        suppressionRepository = mock(EmailSuppressionRepository.class);
        tokenService = new TrackingTokenService(new CampaignProperties(
                true, 15000, 5, 20, 50000, "https://api.example.com", "unit-test-secret"));
        service = new DefaultEmailService(
                emailProvider, emailRepository, credentialResolver,
                "noreply@kelta.io", "Kelta Platform", true,
                suppressionRepository, tokenService, "https://api.example.com");
    }

    @Nested
    @DisplayName("queueEmail")
    class QueueEmail {

        @Test
        @DisplayName("Should create log entry and delegate to provider with platform defaults")
        void shouldQueueWithPlatformDefaults() {
            when(emailRepository.createEmailLog("t1", "user@example.com", "Subject", "WORKFLOW", "flow-1"))
                    .thenReturn("log-123");
            when(emailRepository.findTenantEmailConfig("t1")).thenReturn(null);

            String logId = service.queueEmail("t1", "user@example.com", "Subject", "<p>Body</p>", "WORKFLOW", "flow-1");

            assertThat(logId).isEqualTo("log-123");
            verify(emailRepository).createEmailLog("t1", "user@example.com", "Subject", "WORKFLOW", "flow-1");
        }

        @Test
        @DisplayName("Should resolve credential-backed tenant SMTP settings")
        void shouldResolveCredentialBackedSettings() {
            when(emailRepository.createEmailLog(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn("log-cred");
            when(emailRepository.findTenantEmailConfig("t2"))
                    .thenReturn(new TenantEmailConfigRow("cred-1", "no-reply@tenant.com", "Tenant", true, null));
            when(credentialResolver.resolve(eq("t2"), eq("cred-1"), any()))
                    .thenReturn(new ResolvedCredential("cred-1", "smtp", "smtp",
                            Map.of("username", "u", "password", "p"),
                            Map.of("host", "smtp.tenant.com", "port", 587, "useStartTls", true),
                            Instant.now()));

            service.queueEmail("t2", "user@test.com", "Hello", "<p>Hi</p>", "SYSTEM", null);

            verify(credentialResolver).resolve(eq("t2"), eq("cred-1"), any());
            verify(emailRepository).createEmailLog("t2", "user@test.com", "Hello", "SYSTEM", null);
        }

        @Test
        @DisplayName("Non-flow sources carry no flow headers; flow mail carries Auto-Submitted")
        void flowSourceAddsAutoSubmitted() throws Exception {
            when(emailRepository.createEmailLog(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn("log-1");
            org.mockito.ArgumentCaptor<EmailMessage> sent = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);

            service.queueEmail("t1", "user@test.com", "Reset", "<p>x</p>", "PASSWORD_RESET", null);
            service.queueEmail("t1", "user@test.com", "Alert", "<p>x</p>", EmailService.FLOW_SOURCE, "flow-1");

            verify(emailProvider, times(2)).sendAndReport(sent.capture(), any());
            assertThat(sent.getAllValues().get(0).headers().isEmpty()).isTrue();
            assertThat(sent.getAllValues().get(1).headers().autoSubmitted()).isEqualTo("auto-generated");
            assertThat(sent.getAllValues().get(1).headers().listUnsubscribe()).isNull();
            verifyNoInteractions(suppressionRepository);
        }

        @Test
        @DisplayName("A replyTo that is not one valid address is refused before anything is logged")
        void rejectsInvalidReplyTo() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.queueEmail(
                    "t1", "user@test.com", "S", "B", EmailService.FLOW_SOURCE, null,
                    new EmailService.FlowMailOptions(false, "a@example.com, b@example.com")))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(emailRepository, never()).createEmailLog(anyString(), anyString(), anyString(), anyString(), any());
            verifyNoInteractions(emailProvider);
        }

        @Test
        @DisplayName("Should return dummy ID and skip delivery when disabled")
        void shouldSkipWhenDisabled() {
            DefaultEmailService disabledService = new DefaultEmailService(
                    emailProvider, emailRepository, credentialResolver,
                    "noreply@kelta.io", "Kelta Platform", false,
                    suppressionRepository, tokenService, "https://api.example.com");

            String logId = disabledService.queueEmail("t1", "user@test.com", "Subject", "Body", "TEST", null);

            assertThat(logId).isNotNull();
            verifyNoInteractions(emailProvider);
            verify(emailRepository, never()).createEmailLog(anyString(), anyString(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("sendByKey")
    class SendByKey {

        @Test
        @DisplayName("Should substitute variables and queue using the resolved template")
        void shouldSubstituteAndQueue() {
            when(emailRepository.findTemplateByKey("t1", "user.invite"))
                    .thenReturn(Optional.of(Map.of(
                            "subject", "Welcome ${firstName}",
                            "body_html", "<p>Hi ${firstName}, click ${actionUrl}</p>"
                    )));
            when(emailRepository.createEmailLog(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn("log-99");

            Optional<String> logId = service.sendByKey("t1", "u@x.com", "user.invite",
                    Map.of("firstName", "Ada", "actionUrl", "https://x.com/accept"),
                    "USER_INVITE", "user-1");

            assertThat(logId).contains("log-99");
            verify(emailRepository).createEmailLog("t1", "u@x.com",
                    "Welcome Ada", "USER_INVITE", "user-1");
        }

        @Test
        @DisplayName("Should leave unknown variables intact so missing keys are obvious")
        void shouldLeaveUnknownVarsIntact() {
            assertThat(DefaultEmailService.substitute(
                    "${a} ${b}", Map.of("a", "1")))
                    .isEqualTo("1 ${b}");
        }

        @Test
        @DisplayName("Should return empty when no template found")
        void shouldReturnEmptyWhenMissing() {
            when(emailRepository.findTemplateByKey("t1", "missing"))
                    .thenReturn(Optional.empty());

            Optional<String> logId = service.sendByKey("t1", "u@x.com", "missing",
                    Map.of(), "X", null);

            assertThat(logId).isEmpty();
        }
    }

    @Nested
    @DisplayName("sendByName")
    class SendByName {

        @Test
        @DisplayName("Should resolve template by name and substitute vars")
        void shouldResolveByNameAndSubstitute() {
            when(emailRepository.findTemplateByName("t1", "user_invite"))
                    .thenReturn(Optional.of(Map.of(
                            "subject", "You're invited to ${tenantName}",
                            "body_html", "<a href=\"${inviteLink}\">Accept</a>"
                    )));
            when(emailRepository.createEmailLog(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn("log-name-1");

            Optional<String> logId = service.sendByName("t1", "user@test.com", "user_invite",
                    Map.of("tenantName", "Acme", "inviteLink", "https://app.kelta.io/accept-invite?token=abc"),
                    "USER_INVITE", "abc");

            assertThat(logId).contains("log-name-1");
            verify(emailRepository).createEmailLog("t1", "user@test.com",
                    "You're invited to Acme", "USER_INVITE", "abc");
        }

        @Test
        @DisplayName("Should return empty when name-based template is missing")
        void shouldReturnEmptyWhenMissing() {
            when(emailRepository.findTemplateByName("t1", "unknown"))
                    .thenReturn(Optional.empty());

            Optional<String> logId = service.sendByName("t1", "u@x.com", "unknown",
                    Map.of(), "X", null);

            assertThat(logId).isEmpty();
            verify(emailRepository, never()).createEmailLog(anyString(), anyString(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("sendById")
    class SendById {

        @Test
        @DisplayName("Should resolve tenant template by id, substitute vars, and queue")
        void shouldResolveByIdAndSubstitute() {
            when(emailRepository.findTemplateByTenantAndId("t1", "tpl-1"))
                    .thenReturn(Optional.of(Map.of(
                            "subject", "Hello ${firstName}",
                            "body_html", "<p>Welcome ${firstName}</p>"
                    )));
            when(emailRepository.createEmailLog(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn("log-id-1");

            Optional<String> logId = service.sendById("t1", "u@x.com", "tpl-1",
                    Map.of("firstName", "Ada"), "QUICK_ACTION", null);

            assertThat(logId).contains("log-id-1");
            verify(emailRepository).createEmailLog("t1", "u@x.com",
                    "Hello Ada", "QUICK_ACTION", null);
        }

        @Test
        @DisplayName("Should return empty when the id-based template is missing (no system fallback)")
        void shouldReturnEmptyWhenMissing() {
            when(emailRepository.findTemplateByTenantAndId("t1", "missing"))
                    .thenReturn(Optional.empty());

            Optional<String> logId = service.sendById("t1", "u@x.com", "missing",
                    Map.of(), "QUICK_ACTION", null);

            assertThat(logId).isEmpty();
            verify(emailRepository, never()).createEmailLog(anyString(), anyString(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("sendAsync")
    class SendAsync {

        @Test
        @DisplayName("Should mark log as SENT on successful delivery")
        void shouldMarkSentOnSuccess() {
            service.sendAsync("log-1", "user@test.com", "Subject", "<p>Body</p>", null);

            verify(emailRepository).markSending("log-1");
            verify(emailProvider).sendAndReport(any(EmailMessage.class), isNull());
            verify(emailRepository).markSent(eq("log-1"), anyString());
        }

        @Test
        @DisplayName("Should mark log as FAILED when provider throws")
        void shouldMarkFailedOnProviderError() {
            doThrow(new EmailDeliveryException("Connection refused"))
                    .when(emailProvider).sendAndReport(any(), any());

            service.sendAsync("log-2", "user@test.com", "Subject", "<p>Body</p>", null);

            verify(emailRepository).markSending("log-2");
            verify(emailRepository).markFailed(eq("log-2"), eq("Connection refused"), anyString());
            verify(emailRepository, never()).markSent(anyString(), anyString());
        }

        @Test
        @DisplayName("Never hands a reserved .invalid address (a SERVICE user's) to the provider")
        void neverSendsToReservedInvalidDomain() {
            service.sendAsync("log-3", "billing.ab12cd34@service.kelta.invalid", "Subject", "<p>Body</p>", null);

            verify(emailProvider, never()).sendAndReport(any(), any());
            verify(emailRepository, never()).markSending(anyString());
            verify(emailRepository).markFailed(eq("log-3"), contains(".invalid"), anyString());
        }
    }

    @Nested
    @DisplayName("isReservedRecipient")
    class ReservedRecipient {

        @Test
        @DisplayName("matches only the .invalid top-level domain")
        void matchesOnlyInvalidTld() {
            assertThat(DefaultEmailService.isReservedRecipient("svc@service.kelta.invalid")).isTrue();
            assertThat(DefaultEmailService.isReservedRecipient(" Svc <SVC@Service.Kelta.INVALID> ")).isTrue();
            assertThat(DefaultEmailService.isReservedRecipient("svc@example.invalid.")).isTrue();
            assertThat(DefaultEmailService.isReservedRecipient("user@example.com")).isFalse();
            assertThat(DefaultEmailService.isReservedRecipient("user@invalid.example.com")).isFalse();
            assertThat(DefaultEmailService.isReservedRecipient("user@notinvalid")).isFalse();
            assertThat(DefaultEmailService.isReservedRecipient(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("getTemplate")
    class GetTemplate {

        @Test
        @DisplayName("Should return mapped template when found")
        @WithTenant("t1")
        void shouldReturnTemplateWhenFound() {
            when(emailRepository.findTemplateByTenantAndId("t1", "tpl-1"))
                    .thenReturn(Optional.of(Map.of(
                            "subject", "Welcome",
                            "body_html", "<p>Hello</p>"
                    )));

            Optional<EmailService.EmailTemplate> result = service.getTemplate("tpl-1");

            assertThat(result).isPresent();
            assertThat(result.get().subject()).isEqualTo("Welcome");
            assertThat(result.get().bodyHtml()).isEqualTo("<p>Hello</p>");
        }

        @Test
        @DisplayName("Should return empty when no tenant context")
        void shouldReturnEmptyWithoutTenantContext() {
            Optional<EmailService.EmailTemplate> result = service.getTemplate("tpl-1");

            assertThat(result).isEmpty();
            verify(emailRepository, never()).findTemplateByTenantAndId(anyString(), anyString());
        }
    }
}
