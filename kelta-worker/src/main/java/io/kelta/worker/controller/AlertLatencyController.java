package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.availability.AlertLatencySummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only alert delivery latency summary for the caller's tenant.
 *
 * <p>{@code GET /api/alerts/latency?since=<ISO-8601>&channel=<push|email|sms>} returns the sample
 * count and p50/p90 of {@code alert_delivery.sent_at - alert.created_at} in seconds, for alerts
 * created at or after {@code since} (default: seven days ago). The latency definition and
 * percentile method live in {@link AlertLatencySummary}, shared with the
 * {@code kelta_alert_delivery_latency_seconds} timer.
 *
 * <p>{@code /api/alerts/**} is a {@code static-} gateway route, so only {@code API_ACCESS} is
 * checked there; this controller requires an internal operator holding {@code MANAGE_DATA}.
 * The tenant is the request's bound {@link TenantContext} — there is no cross-tenant view.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertLatencyController {

    static final String REQUIRED_PERMISSION = "MANAGE_DATA";
    static final Set<String> CHANNELS = Set.of("push", "email", "sms");
    static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private final AlertDeliveryRepository deliveryRepository;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public AlertLatencyController(AlertDeliveryRepository deliveryRepository,
                                  CerbosPermissionResolver permissionResolver,
                                  BootstrapRepository bootstrapRepository) {
        this.deliveryRepository = deliveryRepository;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @GetMapping("/latency")
    public ResponseEntity<Map<String, Object>> latency(
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "channel", required = false) String channel,
            HttpServletRequest request) {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        if (!hasManageData(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted");
        }
        Instant from = parseSince(since);
        String resolvedChannel = parseChannel(channel);

        AlertLatencySummary summary = AlertLatencySummary.summarize(
                deliveryRepository.findSentSince(tenantId, from, resolvedChannel), resolvedChannel);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", summary.count());
        data.put("p50Seconds", summary.p50Seconds());
        data.put("p90Seconds", summary.p90Seconds());
        data.put("since", from.toString());
        data.put("channel", resolvedChannel);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Instant.now().minus(DEFAULT_WINDOW);
        }
        try {
            return Instant.parse(since.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "since must be an ISO-8601 instant, e.g. 2026-01-01T00:00:00Z");
        }
    }

    private static String parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        if (!CHANNELS.contains(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "channel must be one of push, email, sms");
        }
        return channel;
    }

    /**
     * Internal staff holding {@link #REQUIRED_PERMISSION}; a PORTAL actor is never permitted.
     * Same check as {@code WatchController.hasSupportPermission}.
     */
    boolean hasManageData(HttpServletRequest request) {
        String userType = request.getHeader("X-User-Type");
        if (userType != null && "PORTAL".equalsIgnoreCase(userType)) {
            return false;
        }
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> REQUIRED_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }
}
