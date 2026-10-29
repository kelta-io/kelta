package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.availability.AvailabilityMatchService;
import io.kelta.worker.service.availability.SyntheticAvailabilityService;
import io.kelta.worker.service.availability.SyntheticAvailabilityService.Injection;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Injects a synthetic availability observation for a {@code synthetic} watch target.
 *
 * <p>{@code POST /api/admin/availability/synthetic-events} with
 * {@code {"targetId", "slotKey"?, "window": {"start", "end"}?, "quantity"?}} records one OPEN
 * observation through the production matcher and dispatcher (see
 * {@link SyntheticAvailabilityService}) and answers {@code 201} with the slot used and the alerts
 * it claimed.
 *
 * <p>{@code /api/admin/**} gets only {@code API_ACCESS} at the gateway (which also refuses portal
 * users), so this controller requires an internal caller holding {@code MANAGE_DATA}; a PORTAL
 * caller is refused whatever its profile grants. Refusals, each a JSON:API error:
 * <ul>
 *   <li>{@code 403 FORBIDDEN} — not an internal caller holding {@code MANAGE_DATA};</li>
 *   <li>{@code 400 INVALID_PAYLOAD} — no {@code targetId}, or a malformed window/quantity;</li>
 *   <li>{@code 404 TARGET_NOT_FOUND} — no such target in the caller's tenant;</li>
 *   <li>{@code 422 TARGET_NOT_SYNTHETIC} — the target is a real, poller-driven one.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/availability")
public class SyntheticAvailabilityController {

    private final SyntheticAvailabilityService syntheticService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public SyntheticAvailabilityController(SyntheticAvailabilityService syntheticService,
                                           CerbosPermissionResolver permissionResolver,
                                           BootstrapRepository bootstrapRepository) {
        this.syntheticService = syntheticService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @PostMapping("/synthetic-events")
    public ResponseEntity<Map<String, Object>> inject(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        if (!requireManageData(request)) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Injecting a synthetic availability event requires MANAGE_DATA");
        }
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "No tenant context");
        }

        Map<String, Object> attributes = body == null ? Map.of() : body;
        String targetId = text(attributes.get("targetId"));
        if (targetId == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "targetId is required");
        }
        Instant windowStart;
        Instant windowEnd;
        Integer quantity;
        try {
            Map<?, ?> window = attributes.get("window") instanceof Map<?, ?> w ? w : Map.of();
            windowStart = instant(window.get("start"));
            windowEnd = instant(window.get("end"));
            quantity = quantity(attributes.get("quantity"));
        } catch (IllegalArgumentException | DateTimeException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                    "window.start/window.end must be ISO-8601 instants and quantity an integer");
        }

        Injection injection = syntheticService.inject(tenantId, targetId,
                text(attributes.get("slotKey")), windowStart, windowEnd, quantity);
        return switch (injection.status()) {
            case TARGET_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND",
                    "No watch target '" + targetId + "'");
            case TARGET_NOT_SYNTHETIC -> error(HttpStatus.UNPROCESSABLE_CONTENT, "TARGET_NOT_SYNTHETIC",
                    "Watch target '" + targetId + "' is not synthetic; only synthetic targets "
                            + "accept injected events");
            case INJECTED -> ResponseEntity.status(HttpStatus.CREATED).body(injected(injection));
        };
    }

    /** Internal staff holding {@code MANAGE_DATA}; never a PORTAL actor. */
    boolean requireManageData(HttpServletRequest request) {
        return SupportPermissions.internalHoldsAny(request, permissionResolver, bootstrapRepository,
                Set.of(SupportPermissions.MANAGE_DATA));
    }

    private static Map<String, Object> injected(Injection injection) {
        List<String> alertIds = injection.alerts().stream()
                .map(AvailabilityMatchService.ClaimedAlert::alertId)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetId", injection.target().id());
        data.put("slotKey", injection.slotKey());
        data.put("status", "OPEN");
        data.put("alerts", alertIds.size());
        data.put("alertIds", alertIds);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        return response;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                             String detail) {
        return ResponseEntity.status(status).body(JsonApiResponseBuilder.error(
                String.valueOf(status.value()), code, status.getReasonPhrase(), detail));
    }

    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("not an instant");
        }
        return s.isBlank() ? null : Instant.parse(s.trim());
    }

    private static Integer quantity(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        throw new IllegalArgumentException("not an integer");
    }
}
