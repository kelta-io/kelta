package io.kelta.worker.service.availability;

import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.service.billing.EntitlementService;
import io.kelta.worker.service.push.DefaultPushService;
import io.kelta.worker.service.sms.SmsDeliveryException;
import io.kelta.worker.service.sms.SmsMessage;
import io.kelta.worker.service.sms.SmsProvider;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AlertDispatchService Tests")
class AlertDispatchServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String MEMBER = "member-1";

    private AlertDeliveryRepository deliveryRepository;
    private DefaultPushService pushService;
    private EmailService emailService;
    private SmsProvider smsProvider;
    private EntitlementService entitlementService;
    private JdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry meterRegistry;
    private AlertDispatchService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(AlertDeliveryRepository.class);
        pushService = mock(DefaultPushService.class);
        emailService = mock(EmailService.class);
        smsProvider = mock(SmsProvider.class);
        entitlementService = mock(EntitlementService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new AlertDispatchService(deliveryRepository, pushService, emailService,
                smsProvider, entitlementService, jdbcTemplate, new ObjectMapper(), meterRegistry);

        when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                .thenReturn(List.of("push", "email"));
        when(deliveryRepository.createPending(anyString(), any()))
                .thenAnswer(inv -> {
                    List<String> channels = inv.getArgument(1);
                    return channels.stream().map(c -> "delivery-" + c).toList();
                });
        when(pushService.sendToUser(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(1);
        when(emailService.sendByName(anyString(), anyString(), anyString(), anyMap(),
                anyString(), anyString())).thenReturn(Optional.of("email-log-1"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("member@example.com"));
    }

    private static final Instant ALERT_CREATED_AT = Instant.parse("2026-08-01T12:00:00Z");

    private static AvailabilityMatchService.ClaimedAlert alert(String channels) {
        Watch watch = new Watch("w1", TENANT, MEMBER, "target-1", "{}", channels,
                Watch.STATUS_ACTIVE, null);
        WatchTarget target = new WatchTarget("target-1", TENANT, "recgov", "site-1",
                "Site A", "campground", "{}", true);
        return new AvailabilityMatchService.ClaimedAlert("alert-1", watch, target,
                "2026-08-14", Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-14T00:00:00Z"), ALERT_CREATED_AT);
    }

    @Nested
    @DisplayName("Channel resolution")
    class ChannelResolution {

        @Test
        @DisplayName("delivers over the intersection of requested and entitled")
        void intersectsRequestedAndEntitled() {
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of("push"));

            assertThat(service.resolveChannels(TENANT, alert("[\"push\",\"email\"]")))
                    .containsExactly("push");
        }

        @Test
        @DisplayName("a downgraded member silently stops getting the lost channel")
        void downgradeDropsChannel() {
            // No one edits the watch; the entitlement change is enough.
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of("email"));

            service.dispatch(TENANT, alert("[\"push\",\"email\"]"));

            verify(pushService, never()).sendToUser(anyString(), anyString(), anyString(),
                    anyString(), anyMap());
            verify(emailService).sendByName(anyString(), anyString(), anyString(), anyMap(),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("a watch with no channels falls back to the member's entitlement")
        void noChannelsFallsBackToEntitlement() {
            assertThat(service.resolveChannels(TENANT, alert(null)))
                    .containsExactly("push", "email");
            assertThat(service.resolveChannels(TENANT, alert("[]")))
                    .containsExactly("push", "email");
        }

        @Test
        @DisplayName("no channels entitlement at all means the tenant is not gating")
        void noEntitlementMeansNoGating() {
            // Muting every alert because a tenant never configured a channels key
            // would be a silent outage.
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of());

            assertThat(service.resolveChannels(TENANT, alert("[\"push\"]")))
                    .containsExactly("push");
        }

        @Test
        @DisplayName("an empty intersection sends nothing and is not an error")
        void emptyIntersectionSendsNothing() {
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of("email"));

            service.dispatch(TENANT, alert("[\"sms\"]"));

            verifyNoInteractions(deliveryRepository);
        }

        @Test
        @DisplayName("unparseable channels fall back to the entitlement")
        void unparseableChannelsFallBack() {
            assertThat(service.resolveChannels(TENANT, alert("{not json")))
                    .containsExactly("push", "email");
        }
    }

    @Nested
    @DisplayName("Delivery")
    class Delivery {

        @Test
        @DisplayName("records PENDING before sending, then marks SENT")
        void recordsThenSends() {
            service.dispatch(TENANT, alert("[\"push\"]"));

            // The obligation is durable before any I/O, so a crash mid-dispatch
            // leaves evidence rather than losing it.
            verify(deliveryRepository).createPending("alert-1", List.of("push"));
            verify(pushService).sendToUser(eq(MEMBER), eq(TENANT), anyString(), anyString(), anyMap());
            verify(deliveryRepository).markSent(eq("delivery-push"), any());
        }

        @Test
        @DisplayName("a failed channel is marked FAILED and does not stop the others")
        void failureIsIsolated() {
            when(pushService.sendToUser(anyString(), anyString(), anyString(), anyString(), anyMap()))
                    .thenThrow(new IllegalStateException("provider timeout"));

            service.dispatch(TENANT, alert("[\"push\",\"email\"]"));

            verify(deliveryRepository).markFailed(eq("delivery-push"), anyString());
            // A stale push token must not cost the member their email.
            verify(emailService).sendByName(anyString(), anyString(), anyString(), anyMap(),
                    anyString(), anyString());
            verify(deliveryRepository).markSent(eq("delivery-email"), any());
        }

        @Test
        @DisplayName("dispatch never throws — the alert row is already committed")
        void neverThrows() {
            // Propagating would risk the caller treating a delivered alert as
            // unprocessed and redelivering it.
            when(pushService.sendToUser(anyString(), anyString(), anyString(), anyString(), anyMap()))
                    .thenThrow(new RuntimeException("boom"));
            when(emailService.sendByName(anyString(), anyString(), anyString(), anyMap(),
                    anyString(), anyString())).thenThrow(new RuntimeException("boom"));

            service.dispatch(TENANT, alert("[\"push\",\"email\"]"));

            verify(deliveryRepository).markFailed(eq("delivery-push"), anyString());
            verify(deliveryRepository).markFailed(eq("delivery-email"), anyString());
        }

        @Test
        @DisplayName("a missing email template fails that delivery, not the dispatch")
        void missingTemplateFailsDelivery() {
            when(emailService.sendByName(anyString(), anyString(), anyString(), anyMap(),
                    anyString(), anyString())).thenReturn(Optional.empty());

            service.dispatch(TENANT, alert("[\"email\"]"));

            verify(deliveryRepository).markFailed(eq("delivery-email"), anyString());
        }

        @Test
        @DisplayName("a member with no email address fails only the email channel")
        void missingEmailAddress() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(List.of());

            service.dispatch(TENANT, alert("[\"email\"]"));

            verify(deliveryRepository).markFailed(eq("delivery-email"), anyString());
        }

        @Test
        @DisplayName("sms is sent to the member's phone and marked SENT")
        void smsIsSent() {
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of("sms"));
            // The member's phone lookup returns a number (the shared jdbc stub returns a value).
            when(jdbcTemplate.queryForList(contains("phone_number"), eq(String.class), any(), any()))
                    .thenReturn(List.of("+14155550123"));

            service.dispatch(TENANT, alert("[\"sms\"]"));

            verify(smsProvider).send(any(SmsMessage.class));
            verify(deliveryRepository).markSent(eq("delivery-sms"), any());
        }

        @Test
        @DisplayName("a member with no phone number fails only the sms channel")
        void smsMissingPhone() {
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of("sms"));
            when(jdbcTemplate.queryForList(contains("phone_number"), eq(String.class), any(), any()))
                    .thenReturn(List.of());

            service.dispatch(TENANT, alert("[\"sms\"]"));

            verify(smsProvider, never()).send(any());
            verify(deliveryRepository).markFailed(eq("delivery-sms"), anyString());
        }

        @Test
        @DisplayName("an sms provider failure is marked FAILED, not propagated")
        void smsProviderFailureIsIsolated() {
            when(entitlementService.listLimit(anyString(), anyString(), anyString()))
                    .thenReturn(List.of("sms"));
            when(jdbcTemplate.queryForList(contains("phone_number"), eq(String.class), any(), any()))
                    .thenReturn(List.of("+14155550123"));
            doThrow(new SmsDeliveryException("twilio 401")).when(smsProvider).send(any());

            service.dispatch(TENANT, alert("[\"sms\"]"));

            verify(deliveryRepository).markFailed(eq("delivery-sms"), anyString());
            verify(deliveryRepository, never()).markSent(anyString(), any());
        }

        @Test
        @DisplayName("push reaching zero devices is not treated as a failure")
        void zeroDevicesIsNotFailure() {
            when(pushService.sendToUser(anyString(), anyString(), anyString(), anyString(), anyMap()))
                    .thenReturn(0);

            service.dispatch(TENANT, alert("[\"push\"]"));

            verify(deliveryRepository).markSent(eq("delivery-push"), any());
            verify(deliveryRepository, never()).markFailed(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Latency metric")
    class LatencyMetric {

        private Timer timer(String channel) {
            return meterRegistry.find(AlertDispatchService.LATENCY_METRIC)
                    .tags("tenant", TENANT, "channel", channel).timer();
        }

        @Test
        @DisplayName("a SENT push records one sample; a FAILED email records none")
        void sentRecordsFailedDoesNot() {
            when(emailService.sendByName(anyString(), anyString(), anyString(), anyMap(),
                    anyString(), anyString())).thenThrow(new IllegalStateException("smtp down"));

            service.dispatch(TENANT, alert("[\"push\",\"email\"]"));

            Timer push = timer("push");
            assertThat(push).isNotNull();
            assertThat(push.count()).isEqualTo(1);
            assertThat(timer("email"))
                    .as("a failed delivery has no latency")
                    .isNull();
        }

        @Test
        @DisplayName("the sample is the marked sent_at minus the alert's created_at")
        void sampleMatchesPersistedSentAt() {
            ArgumentCaptor<Instant> sentAt = ArgumentCaptor.forClass(Instant.class);

            service.dispatch(TENANT, alert("[\"push\"]"));

            verify(deliveryRepository).markSent(eq("delivery-push"), sentAt.capture());
            Duration expected = Duration.between(ALERT_CREATED_AT, sentAt.getValue());
            assertThat(timer("push").totalTime(TimeUnit.NANOSECONDS))
                    .as("metric and endpoint share one definition: sent_at - alert.created_at")
                    .isEqualTo((double) expected.toNanos());
        }

        @Test
        @DisplayName("an alert with no recorded creation time is delivered but not timed")
        void missingCreatedAtIsNotTimed() {
            Watch watch = new Watch("w1", TENANT, MEMBER, "target-1", "{}", "[\"push\"]",
                    Watch.STATUS_ACTIVE, null);
            WatchTarget target = new WatchTarget("target-1", TENANT, "recgov", "site-1",
                    "Site A", "campground", "{}", true);

            service.dispatch(TENANT, new AvailabilityMatchService.ClaimedAlert("alert-1", watch,
                    target, "2026-08-14", null, null, null));

            verify(deliveryRepository).markSent(eq("delivery-push"), any());
            assertThat(timer("push")).isNull();
        }
    }

    @Test
    @DisplayName("an unresolved entitlement still sends free channels but never a paid one")
    void ungatedTenantNeverGetsPaidChannels() {
        // Empty entitlement means "this tenant is not gating channels". For push and email that is
        // the right default -- a tenant with no billing, or a briefly unavailable authority, should
        // still get alerts. Applying it to SMS would spend money on members not entitled to it.
        when(entitlementService.listLimit(any(), any(), eq("channels"))).thenReturn(List.of());

        List<String> resolved = service.resolveChannels(TENANT,
                alert("[\"push\",\"email\",\"sms\"]"));

        assertThat(resolved).containsExactly("push", "email");
        assertThat(resolved).doesNotContain("sms");
    }

    @Test
    @DisplayName("an affirmative entitlement is what unlocks a paid channel")
    void affirmativeEntitlementAllowsSms() {
        when(entitlementService.listLimit(any(), any(), eq("channels")))
                .thenReturn(List.of("push", "email", "sms"));

        assertThat(service.resolveChannels(TENANT, alert("[\"sms\"]")))
                .containsExactly("sms");
    }
}
