package io.kelta.worker.controller;

import io.kelta.worker.config.SupersetConfig;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SupersetDatasetService;
import io.kelta.worker.service.SupersetGuestTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Provides Superset integration endpoints for the frontend.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/superset/guest-token} — generate a guest token for embedding a dashboard</li>
 *   <li>{@code GET /api/superset/dashboards} — list available dashboards</li>
 *   <li>{@code GET /api/superset/datasets} — list datasets for the current tenant</li>
 *   <li>{@code POST /api/superset/datasets/sync} — trigger dataset sync for the current tenant</li>
 * </ul>
 *
 * <p>Registered only with the Superset integration ({@code kelta.superset.url} set): its services
 * exist only then, so an unconditional controller would stop the worker booting without Superset.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/superset")
@Conditional(SupersetConfig.SupersetUrlConfigured.class)
public class SupersetController {

    private static final Logger log = LoggerFactory.getLogger(SupersetController.class);

    private final SupersetGuestTokenService guestTokenService;
    private final SupersetDatasetService datasetService;
    private final CerbosPermissionResolver permissionResolver;

    public SupersetController(SupersetGuestTokenService guestTokenService,
                               SupersetDatasetService datasetService,
                               CerbosPermissionResolver permissionResolver) {
        this.guestTokenService = guestTokenService;
        this.datasetService = datasetService;
        this.permissionResolver = permissionResolver;
    }

    @PostMapping("/guest-token")
    public ResponseEntity<Map<String, String>> getGuestToken(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String dashboardId = body.get("dashboardId");
        if (dashboardId == null || dashboardId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing dashboardId"));
        }

        String profileId = permissionResolver.getProfileId(request);
        String email = permissionResolver.getEmail(request);
        String tenantId = permissionResolver.getTenantId(request);

        if (profileId == null || email == null || tenantId == null) {
            log.warn("Missing identity headers for Superset guest token request");
            return ResponseEntity.badRequest().body(Map.of("error", "Missing user context"));
        }

        var result = guestTokenService.generateGuestToken(dashboardId, profileId, email, tenantId);
        if (result != null) {
            return ResponseEntity.ok(result);
        }

        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to generate guest token"));
    }

    @GetMapping("/dashboards")
    public ResponseEntity<List<Map<String, Object>>> listDashboards() {
        return ResponseEntity.ok(guestTokenService.listDashboards());
    }

    @GetMapping("/datasets")
    public ResponseEntity<List<Map<String, Object>>> listDatasets(HttpServletRequest request) {
        String tenantSlug = request.getHeader("X-Tenant-Slug");
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(datasetService.listDatasets(tenantSlug));
    }

    @PostMapping("/datasets/sync")
    public ResponseEntity<Map<String, String>> syncDatasets(HttpServletRequest request) {
        String tenantId = request.getHeader("X-Tenant-ID");
        String tenantSlug = request.getHeader("X-Tenant-Slug");

        if (tenantId == null || tenantSlug == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing tenant context"));
        }

        datasetService.syncDatasets(tenantId, tenantSlug);
        return ResponseEntity.ok(Map.of("status", "sync completed"));
    }
}
