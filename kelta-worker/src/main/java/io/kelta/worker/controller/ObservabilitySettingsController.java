package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST controller for observability retention settings.
 *
 * <p>Settings are stored per-tenant in PostgreSQL (small config table).
 * Actual retention is enforced via OpenSearch ISM policies.
 */
@RestController
@RequestMapping("/api/admin/observability-settings")
public class ObservabilitySettingsController {

    private static final String PERMISSION = "CUSTOMIZE_APPLICATION";

    private static final Logger log = LoggerFactory.getLogger(ObservabilitySettingsController.class);

    private final JdbcTemplate jdbcTemplate;
    private final AdminPermissionGuard permissionGuard;

    public ObservabilitySettingsController(JdbcTemplate jdbcTemplate, AdminPermissionGuard permissionGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionGuard = permissionGuard;
    }

    /**
     * Get observability settings for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        permissionGuard.require(request, PERMISSION);
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request", "Missing X-Tenant-ID header"));
            }

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT setting_key, setting_value FROM observability_settings WHERE tenant_id = ? ORDER BY setting_key",
                    tenantId);

            List<Map<String, String>> settings = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, String> setting = new LinkedHashMap<>();
                setting.put("settingKey", (String) row.get("setting_key"));
                setting.put("settingValue", (String) row.get("setting_value"));
                settings.add(setting);
            }

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("settings", settings);

            return ResponseEntity.ok(JsonApiResponseBuilder.single("observability-settings", tenantId, attributes));
        } catch (Exception e) {
            log.error("Failed to get observability settings: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to get observability settings"));
        }
    }

    /**
     * Update observability settings for the current tenant.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateSettings(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestBody Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request", "Missing X-Tenant-ID header"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> settings = (List<Map<String, String>>) body.get("settings");
            if (settings == null || settings.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request", "No settings provided"));
            }

            String now = Instant.now().toString();
            for (Map<String, String> setting : settings) {
                String key = setting.get("settingKey");
                String value = setting.get("settingValue");

                if (key == null || value == null) continue;

                // Upsert: try update first, insert if no row affected
                int updated = jdbcTemplate.update(
                        "UPDATE observability_settings SET setting_value = ?, updated_at = NOW() WHERE tenant_id = ? AND setting_key = ?",
                        value, tenantId, key);

                if (updated == 0) {
                    jdbcTemplate.update(
                            "INSERT INTO observability_settings (id, tenant_id, setting_key, setting_value, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                            UUID.randomUUID().toString(), tenantId, key, value);
                }
            }

            log.info("Updated observability settings for tenant {}: {} settings", tenantId, settings.size());

            // Return updated settings
            return getSettings(request, tenantId);
        } catch (Exception e) {
            log.error("Failed to update observability settings: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to update observability settings"));
        }
    }
}
