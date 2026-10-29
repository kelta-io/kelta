package io.kelta.worker.service.availability;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The {@code webhook} availability-alert channel: one JSON {@code POST} per alert to a URL the
 * tenant configures.
 *
 * <p>The URL is the tenant's {@code settings.alerts.webhookUrl}; when that is blank, the
 * deployment-wide {@code kelta.alerts.webhook-url} ({@code KELTA_ALERTS_WEBHOOK_URL}) is used, so
 * a self-hosted install can point every tenant at one receiver. No URL at all is a FAILED delivery
 * ({@value #NOT_CONFIGURED}), not a refused watch.
 *
 * <p><b>Outbound safety.</b> The URL is tenant-supplied, so the request goes through
 * {@link OutboundHttpClient} with the worker's {@link OutboundUrlGuard}: it must resolve to a
 * public address unless its range is in {@code kelta.outbound.allowed-private-cidrs}, redirects
 * are not followed, and it is bounded by {@link #CONNECT_TIMEOUT} / {@link #REQUEST_DEADLINE}.
 * There are no retries — like the other channels, a failure is recorded, not repeated.
 */
@Component
public class WebhookAlertSender {

    static final String NOT_CONFIGURED = "webhook URL not configured";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(5);
    /** The receiver's answer is never read beyond its status. */
    static final int MAX_RESPONSE_BYTES = 4 * 1024;

    private final OutboundHttpClient httpClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String fallbackUrl;

    @Autowired
    public WebhookAlertSender(OutboundUrlGuard urlGuard,
                              JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              @Value("${kelta.alerts.webhook-url:}") String fallbackUrl) {
        this(new OutboundHttpClient(urlGuard, new OutboundHttpClient.Limits(
                        CONNECT_TIMEOUT, REQUEST_DEADLINE, MAX_RESPONSE_BYTES, 0)),
                jdbcTemplate, objectMapper, fallbackUrl);
    }

    WebhookAlertSender(OutboundHttpClient httpClient, JdbcTemplate jdbcTemplate,
                       ObjectMapper objectMapper, String fallbackUrl) {
        this.httpClient = httpClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.fallbackUrl = fallbackUrl;
    }

    /**
     * POSTs the alert to the tenant's webhook URL.
     *
     * @throws IllegalStateException with a reason safe to store on the delivery row when there is
     *         no URL, the guard refuses it, the request fails or times out, or the receiver
     *         answers with anything but 2xx
     */
    public void send(String tenantId, AvailabilityMatchService.ClaimedAlert alert, Instant dispatchTime) {
        String url = resolveUrl(tenantId);
        if (url == null) {
            throw new IllegalStateException(NOT_CONFIGURED);
        }
        OutboundHttpClient.Response response;
        try {
            response = httpClient.send(new OutboundHttpClient.Request("POST", url,
                    Map.of("Content-Type", "application/json"),
                    body(alert, dispatchTime).getBytes(StandardCharsets.UTF_8)));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            throw new IllegalStateException("webhook URL refused: " + e.getMessage());
        } catch (OutboundHttpClient.OutboundRequestException e) {
            throw new IllegalStateException("webhook delivery failed: " + e.getMessage());
        }
        if (!response.isSuccess()) {
            throw new IllegalStateException("webhook returned HTTP " + response.statusCode());
        }
    }

    String body(AvailabilityMatchService.ClaimedAlert alert, Instant dispatchTime) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("alertId", alert.alertId());
        node.put("watchId", alert.watch().id());
        node.put("targetId", alert.target().id());
        node.put("slotKey", alert.slotKey());
        node.put("eventTime", alert.createdAt() == null ? null : alert.createdAt().toString());
        node.put("dispatchTime", dispatchTime.toString());
        return node.toString();
    }

    /** The tenant's {@code settings.alerts.webhookUrl}, else the deployment fallback, else null. */
    String resolveUrl(String tenantId) {
        String tenantUrl = TenantContext.callWithTenant(tenantId, () -> {
            List<Object> rows = jdbcTemplate.queryForList(
                    "SELECT settings FROM tenant WHERE id = ?", Object.class, tenantId);
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            JsonNode url = readSettings(rows.get(0)).path("alerts").path("webhookUrl");
            return url.isTextual() ? url.stringValue() : null;
        });
        if (tenantUrl != null && !tenantUrl.isBlank()) {
            return tenantUrl.trim();
        }
        return fallbackUrl == null || fallbackUrl.isBlank() ? null : fallbackUrl.trim();
    }

    private JsonNode readSettings(Object settings) {
        try {
            return objectMapper.readTree(settings.toString());
        } catch (RuntimeException e) {
            return MissingNode.getInstance();
        }
    }
}
