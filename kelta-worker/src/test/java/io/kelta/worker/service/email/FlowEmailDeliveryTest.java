package io.kelta.worker.service.email;

import io.kelta.runtime.module.integration.handlers.EmailAlertActionHandler;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.config.CampaignProperties;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.repository.EmailSuppressionRepository;
import io.kelta.worker.service.campaign.TrackingTokenService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code EMAIL_ALERT} from the action config to the wire: the real handler, the real
 * {@link DefaultEmailService} and {@link SmtpEmailProvider}, and a real {@link MimeMessage}.
 * Only the SMTP transport and the database are doubles.
 */
@DisplayName("EMAIL_ALERT delivery — transactional vs non-transactional")
class FlowEmailDeliveryTest {

    private static final String TENANT_A = "3f6c1d2e-0a4b-4c5d-8e9f-1a2b3c4d5e6f";
    private static final String TENANT_B = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d";
    private static final String RECIPIENT = "member@example.com";
    private static final Pattern UNSUBSCRIBE =
            Pattern.compile("^<https://api\\.example\\.com/api/track/email-unsubscribe\\?t=([^>]+)>$");

    private final TrackingTokenService tokens = new TrackingTokenService(
            new CampaignProperties(true, 15000, 5, 20, 50000, "https://api.example.com", "unit-test-secret"));
    private final List<MimeMessage> sent = new ArrayList<>();
    private final AtomicInteger logIds = new AtomicInteger();

    private EmailRepository emailRepository;
    private Suppressions suppressions;
    private EmailAlertActionHandler handler;

    @BeforeEach
    void setUp() {
        Session session = Session.getInstance(new Properties());
        JavaMailSenderImpl transport = mock(JavaMailSenderImpl.class);
        when(transport.getSession()).thenReturn(session);
        when(transport.getHost()).thenReturn("smtp.platform.io");
        doAnswer(inv -> {
            MimeMessage message = inv.getArgument(0);
            message.saveChanges();
            sent.add(message);
            return null;
        }).when(transport).send(any(MimeMessage.class));

        emailRepository = mock(EmailRepository.class);
        when(emailRepository.createEmailLog(anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> "log-" + logIds.incrementAndGet());
        suppressions = new Suppressions();

        DefaultEmailService service = new DefaultEmailService(
                new SmtpEmailProvider(transport, "noreply@platform.io", "Platform"),
                emailRepository, null, "noreply@platform.io", "Platform", true,
                suppressions, tokens, "https://api.example.com");
        handler = new EmailAlertActionHandler(new ObjectMapper(), service);
    }

    @Test
    @DisplayName("Transactional (default and explicit) has no List-Unsubscribe and reaches a suppressed recipient")
    void transactionalIgnoresSuppression() throws Exception {
        suppressions.add(TENANT_A, RECIPIENT, "UNSUBSCRIBE", null, null);

        for (String flag : List.of("", ", \"transactional\": true")) {
            sent.clear();
            ActionResult result = handler.execute(context(TENANT_A,
                    "{\"to\": \"" + RECIPIENT + "\", \"subject\": \"Password reset\", \"body\": \"x\"" + flag + "}"));

            assertThat(result.successful()).as(result.errorMessage()).isTrue();
            assertThat(result.outputData()).containsEntry("status", "QUEUED");
            assertThat(sent).hasSize(1);
            MimeMessage message = sent.getFirst();
            assertThat(message.getHeader("List-Unsubscribe", null)).isNull();
            assertThat(message.getHeader("List-Unsubscribe-Post", null)).isNull();
            assertThat(message.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
        }
        assertThat(suppressions.lookups).isZero();
    }

    @Test
    @DisplayName("Non-transactional carries one-click List-Unsubscribe with a signed address token, and Reply-To")
    void nonTransactionalCarriesListUnsubscribe() throws Exception {
        ActionResult result = handler.execute(context(TENANT_A,
                "{\"to\": \"" + RECIPIENT + "\", \"subject\": \"Weekly digest\", \"body\": \"x\","
                        + " \"transactional\": false, \"replyTo\": \"help@example.com\"}"));

        assertThat(result.successful()).as(result.errorMessage()).isTrue();
        assertThat(result.outputData()).containsEntry("status", "QUEUED");
        assertThat(sent).hasSize(1);
        MimeMessage message = sent.getFirst();

        Matcher link = UNSUBSCRIBE.matcher(message.getHeader("List-Unsubscribe", null));
        assertThat(link.matches()).as(message.getHeader("List-Unsubscribe", null)).isTrue();
        String token = URLDecoder.decode(link.group(1), StandardCharsets.UTF_8);
        assertThat(tokens.verifyAddress(token))
                .contains(new TrackingTokenService.SignedAddress(TENANT_A, RECIPIENT));
        assertThat(message.getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(message.getHeader("Reply-To", null)).isEqualTo("help@example.com");
        assertThat(message.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
    }

    @Test
    @DisplayName("Non-transactional to an address suppressed in this tenant is logged SUPPRESSED, not sent; another tenant still sends")
    void nonTransactionalHonoursTenantSuppression() {
        suppressions.add(TENANT_A, "Member@Example.com", "UNSUBSCRIBE", null, null);
        String config = "{\"to\": \"" + RECIPIENT + "\", \"subject\": \"Weekly digest\", \"body\": \"x\","
                + " \"transactional\": false}";

        ActionResult suppressed = handler.execute(context(TENANT_A, config));

        assertThat(suppressed.successful()).isTrue();
        assertThat(suppressed.outputData()).containsEntry("status", "SUPPRESSED");
        String logId = (String) suppressed.outputData().get("emailLogId");
        verify(emailRepository).createEmailLog(TENANT_A, RECIPIENT, "Weekly digest", "WORKFLOW", "wf-1");
        verify(emailRepository).markSuppressed(logId);
        verify(emailRepository, never()).markSending(logId);
        verify(emailRepository, never()).markSent(anyString(), any());
        assertThat(sent).isEmpty();

        ActionResult otherTenant = handler.execute(context(TENANT_B, config));

        assertThat(otherTenant.outputData()).containsEntry("status", "QUEUED");
        assertThat(sent).hasSize(1);
        verify(emailRepository).markSent((String) otherTenant.outputData().get("emailLogId"), "smtp.platform.io");
    }

    private static ActionContext context(String tenantId, String config) {
        return ActionContext.builder()
                .tenantId(tenantId).userId("user-1").data(Map.of())
                .actionConfigJson(config).workflowRuleId("wf-1").build();
    }

    /** Case-insensitive, tenant-keyed, like {@code email_suppression}. */
    private static final class Suppressions extends EmailSuppressionRepository {
        private final List<String[]> rows = new ArrayList<>();
        int lookups;

        Suppressions() {
            super(null);
        }

        @Override
        public boolean isSuppressed(String tenantId, String email) {
            lookups++;
            return rows.stream().anyMatch(r -> r[0].equals(tenantId) && r[1].equalsIgnoreCase(email));
        }

        @Override
        public boolean add(String tenantId, String email, String reason, String campaignId, String createdBy) {
            return rows.add(new String[] {tenantId, email});
        }
    }
}
