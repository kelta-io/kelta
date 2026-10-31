package io.kelta.worker.service.availability;

import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.service.entitlement.EntitlementService;
import io.kelta.worker.service.push.DefaultPushService;
import io.kelta.worker.service.sms.SmsProvider;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The webhook channel end to end: a real {@link AlertDispatchService} with a real
 * {@link WebhookAlertSender} POSTing to a loopback HTTP server, so each case is observed where it
 * takes effect — the delivery row the dispatcher records.
 */
@DisplayName("WebhookAlertSender Tests")
class WebhookAlertSenderTest {

    private static final String TENANT = "tenant-1";
    private static final Instant ALERT_CREATED_AT = Instant.parse("2026-08-01T12:00:00Z");
    /** Loopback is non-public; the operator allow-lists it, exactly as for an in-cluster receiver. */
    private static final OutboundUrlGuard LOOPBACK_ALLOWED = new OutboundUrlGuard("127.0.0.0/8");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(204);
    private final AtomicInteger delayMillis = new AtomicInteger(0);
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();

    private AlertDeliveryRepository deliveryRepository;
    private SmsProvider smsProvider;
    private EmailService emailService;
    private JdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try {
                Thread.sleep(delayMillis.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();

        deliveryRepository = mock(AlertDeliveryRepository.class);
        smsProvider = mock(SmsProvider.class);
        emailService = mock(EmailService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        when(deliveryRepository.createPending(anyString(), any())).thenReturn(List.of("delivery-webhook"));
        tenantSettings(null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private void tenantSettings(String json) {
        when(jdbcTemplate.queryForList(eq("SELECT settings FROM tenant WHERE id = ?"), eq(Object.class), eq(TENANT)))
                .thenReturn(json == null ? List.of() : List.of(json));
    }

    private WebhookAlertSender sender(OutboundUrlGuard guard, Duration deadline, String fallbackUrl) {
        return new WebhookAlertSender(new OutboundHttpClient(guard,
                new OutboundHttpClient.Limits(Duration.ofSeconds(1), deadline, 1024, 0)),
                jdbcTemplate, objectMapper, fallbackUrl);
    }

    private AlertDispatchService dispatcher(WebhookAlertSender sender) {
        EntitlementService entitlements = mock(EntitlementService.class);
        when(entitlements.listLimit(anyString(), anyString(), anyString())).thenReturn(List.of("webhook"));
        return new AlertDispatchService(deliveryRepository, mock(DefaultPushService.class), emailService,
                smsProvider, sender, entitlements, jdbcTemplate, objectMapper, meterRegistry);
    }

    private static AvailabilityMatchService.ClaimedAlert alert() {
        Watch watch = new Watch("w1", TENANT, "member-1", "target-1", "{}", "[\"webhook\"]",
                Watch.STATUS_ACTIVE, null);
        WatchTarget target = new WatchTarget("target-1", TENANT, "recgov", "site-1",
                "Site A", "campground", "{}", true);
        return new AvailabilityMatchService.ClaimedAlert("alert-1", watch, target,
                "2026-08-14", Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-14T00:00:00Z"), ALERT_CREATED_AT);
    }

    private String failureReason() {
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(deliveryRepository).markFailed(eq("delivery-webhook"), error.capture());
        verify(deliveryRepository, never()).markSent(anyString(), any());
        return error.getValue();
    }

    @Test
    @DisplayName("2xx: POSTs the alert as JSON to the tenant's URL, records SENT and its latency")
    void sentWithBodyFields() throws Exception {
        tenantSettings("{\"alerts\":{\"webhookUrl\":\"" + hookUrl() + "\"}}");
        Instant before = Instant.now();

        dispatcher(sender(LOOPBACK_ALLOWED, Duration.ofSeconds(5), null)).dispatch(TENANT, alert());

        JsonNode body = objectMapper.readTree(receivedBody.get());
        assertThat(receivedContentType.get()).isEqualTo("application/json");
        assertThat(body.path("watchId").stringValue()).isEqualTo("w1");
        assertThat(body.path("targetId").stringValue()).isEqualTo("target-1");
        assertThat(body.path("slotKey").stringValue()).isEqualTo("2026-08-14");
        assertThat(body.path("alertId").stringValue()).isEqualTo("alert-1");
        assertThat(body.path("eventTime").stringValue()).isEqualTo("2026-08-01T12:00:00Z");
        assertThat(Instant.parse(body.path("dispatchTime").stringValue()))
                .isBetween(before, Instant.now());

        verify(deliveryRepository).markSent(eq("delivery-webhook"), any());
        verify(deliveryRepository, never()).markFailed(anyString(), anyString());
        Timer timer = meterRegistry.find(AlertDispatchService.LATENCY_METRIC)
                .tags("tenant", TENANT, "channel", "webhook").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("non-2xx: recorded FAILED with the status in the error")
    void non2xxFailed() {
        tenantSettings("{\"alerts\":{\"webhookUrl\":\"" + hookUrl() + "\"}}");
        status.set(503);

        dispatcher(sender(LOOPBACK_ALLOWED, Duration.ofSeconds(5), null)).dispatch(TENANT, alert());

        assertThat(failureReason()).isEqualTo("webhook returned HTTP 503");
        assertThat(meterRegistry.find(AlertDispatchService.LATENCY_METRIC).timer()).isNull();
    }

    @Test
    @DisplayName("timeout: a receiver slower than the deadline is recorded FAILED with the reason")
    void timeoutFailed() {
        tenantSettings("{\"alerts\":{\"webhookUrl\":\"" + hookUrl() + "\"}}");
        delayMillis.set(2_000);

        dispatcher(sender(LOOPBACK_ALLOWED, Duration.ofMillis(300), null)).dispatch(TENANT, alert());

        assertThat(failureReason()).isEqualTo("webhook delivery failed: timed out after 300 ms");
    }

    @Test
    @DisplayName("guard-rejected URL: nothing is sent and it is recorded FAILED with the rejection reason")
    void guardRejectedFailed() {
        tenantSettings("{\"alerts\":{\"webhookUrl\":\"" + hookUrl() + "\"}}");

        // The production guard with no allow-list: loopback is a non-public address.
        dispatcher(sender(new OutboundUrlGuard(""), Duration.ofSeconds(5), null)).dispatch(TENANT, alert());

        assertThat(failureReason())
                .isEqualTo("webhook URL refused: the host resolves to a non-public address");
        assertThat(receivedBody.get()).as("the receiver was never contacted").isNull();
    }

    @Test
    @DisplayName("missing URL: no tenant setting and no fallback is recorded FAILED 'webhook URL not configured'")
    void missingUrlFailed() {
        tenantSettings("{\"alerts\":{}}");

        dispatcher(sender(LOOPBACK_ALLOWED, Duration.ofSeconds(5), "  ")).dispatch(TENANT, alert());

        assertThat(failureReason()).isEqualTo("webhook URL not configured");
    }

    @Test
    @DisplayName("the deployment fallback URL is used when the tenant has none")
    void fallbackUrlUsed() {
        tenantSettings("{\"push\":{}}");

        dispatcher(sender(LOOPBACK_ALLOWED, Duration.ofSeconds(5), hookUrl())).dispatch(TENANT, alert());

        assertThat(receivedBody.get()).contains("\"watchId\":\"w1\"");
        verify(deliveryRepository).markSent(eq("delivery-webhook"), any());
    }

    @Test
    @DisplayName("the tenant's URL wins over the deployment fallback")
    void tenantUrlWinsOverFallback() {
        tenantSettings("{\"alerts\":{\"webhookUrl\":\"https://hooks.example.com/a\"}}");

        assertThat(sender(LOOPBACK_ALLOWED, Duration.ofSeconds(5), "https://fallback.example.com/b")
                .resolveUrl(TENANT)).isEqualTo("https://hooks.example.com/a");
    }

    @Test
    @DisplayName("a webhook delivery never reaches the SMS provider or the email service")
    void neverReachesBillableProviders() {
        tenantSettings("{\"alerts\":{\"webhookUrl\":\"" + hookUrl() + "\"}}");

        dispatcher(sender(LOOPBACK_ALLOWED, Duration.ofSeconds(5), null)).dispatch(TENANT, alert());

        verifyNoInteractions(smsProvider, emailService);
    }
}
