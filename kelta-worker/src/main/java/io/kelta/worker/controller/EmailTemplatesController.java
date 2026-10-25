package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoints for system-default templates + tenant overrides.
 *
 * <ul>
 *   <li>{@code GET    /api/admin/email-templates} — list of (template_key, system row, tenant override?)</li>
 *   <li>{@code POST   /api/admin/email-templates/{key}/override} — clone the system row into a tenant-owned row.</li>
 *   <li>{@code DELETE /api/admin/email-templates/{key}/override} — delete the tenant override (revert to system default).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/email-templates")
public class EmailTemplatesController {

    private static final String PERMISSION = "MANAGE_EMAIL_TEMPLATES";

    private static final Logger log = LoggerFactory.getLogger(EmailTemplatesController.class);

    private final JdbcTemplate jdbcTemplate;
    private final AdminPermissionGuard permissionGuard;

    public EmailTemplatesController(JdbcTemplate jdbcTemplate, AdminPermissionGuard permissionGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT template_key, tenant_id, id, name, subject, body_html, body_text, "
                        + "       variables_schema "
                        + "FROM email_template "
                        + "WHERE template_key IS NOT NULL "
                        + "  AND tenant_id IN (?, 'system') "
                        + "ORDER BY template_key, CASE WHEN tenant_id = 'system' THEN 1 ELSE 0 END",
                tenantId);

        // Collapse to one entry per template_key with both system and tenant rows
        Map<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("template_key");
            Map<String, Object> entry = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("templateKey", k);
                return e;
            });
            boolean isSystem = "system".equals(row.get("tenant_id"));
            entry.put(isSystem ? "systemDefault" : "tenantOverride", projectTemplate(row));
        }
        return ResponseEntity.ok(Map.of("data", List.copyOf(byKey.values())));
    }

    @PostMapping("/{templateKey}/override")
    public ResponseEntity<?> override(HttpServletRequest request, @PathVariable String templateKey, @RequestBody(required = false) Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        // Skip if override already exists.
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT id FROM email_template WHERE tenant_id = ? AND template_key = ?",
                tenantId, templateKey);
        if (!existing.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "exists", "id", existing.get(0).get("id")));
        }

        // Find user id for created_by — fall back to 'system' if not available
        String createdBy = (String) (body != null ? body.getOrDefault("userId", "system") : "system");

        int copied = jdbcTemplate.update(
                "INSERT INTO email_template (id, tenant_id, template_key, name, description, "
                        + "    subject, body_html, body_text, variables_schema, is_active, "
                        + "    created_by, created_at, updated_at) "
                        + "SELECT ?, ?, template_key, name, description, subject, body_html, body_text, "
                        + "       variables_schema, true, ?, NOW(), NOW() "
                        + "FROM email_template WHERE tenant_id = 'system' AND template_key = ?",
                UUID.randomUUID().toString(), tenantId, createdBy, templateKey);

        if (copied == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "No system template for key " + templateKey));
        }
        log.info("Tenant {} created override for template {}", tenantId, templateKey);
        return ResponseEntity.ok(Map.of("status", "created"));
    }

    @DeleteMapping("/{templateKey}/override")
    public ResponseEntity<?> revert(HttpServletRequest request, @PathVariable String templateKey) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        int removed = jdbcTemplate.update(
                "DELETE FROM email_template WHERE tenant_id = ? AND template_key = ?",
                tenantId, templateKey);
        log.info("Tenant {} reverted template {} ({} row(s) deleted)", tenantId, templateKey, removed);
        return ResponseEntity.ok(Map.of("status", "reverted"));
    }

    private Map<String, Object> projectTemplate(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("name", row.get("name"));
        out.put("subject", row.get("subject"));
        out.put("bodyHtml", row.get("body_html"));
        out.put("bodyText", row.get("body_text"));
        out.put("variablesSchema", row.get("variables_schema"));
        return out;
    }
}
