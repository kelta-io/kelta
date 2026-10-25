package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.service.SecurityAuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * Admin API for MFA management: user MFA status, reset, and tenant MFA policy.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/admin/mfa")
public class MfaAdminController {

    private static final String PERMISSION = "MANAGE_USERS";

    private static final Logger log = LoggerFactory.getLogger(MfaAdminController.class);

    private final JdbcTemplate jdbcTemplate;
    private final AdminPermissionGuard permissionGuard;

    public MfaAdminController(JdbcTemplate jdbcTemplate, AdminPermissionGuard permissionGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping("/users/{userId}/status")
    public ResponseEntity<?> getUserMfaStatus(HttpServletRequest request, @PathVariable String userId) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        // Verify user belongs to tenant
        var users = jdbcTemplate.queryForList(
                "SELECT id, mfa_enabled FROM platform_user WHERE id = ? AND tenant_id = ?",
                userId, tenantId);
        if (users.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        boolean mfaEnabled = (boolean) users.get(0).get("mfa_enabled");

        // Check enrollment details
        var secrets = jdbcTemplate.queryForList(
                "SELECT verified, created_at FROM user_totp_secret WHERE user_id = ?", userId);
        boolean enrolled = !secrets.isEmpty() && (boolean) secrets.get(0).get("verified");
        Object enrolledAt = enrolled ? secrets.get(0).get("created_at") : null;

        // Count remaining recovery codes
        var codeCount = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS cnt FROM user_recovery_code WHERE user_id = ? AND used = false", userId);
        int remainingCodes = codeCount.isEmpty() ? 0 : ((Number) codeCount.get(0).get("cnt")).intValue();

        return ResponseEntity.ok(Map.of("data", Map.of(
                "userId", userId,
                "mfaEnabled", mfaEnabled,
                "enrolled", enrolled,
                "enrolledAt", enrolledAt != null ? enrolledAt.toString() : null,
                "remainingRecoveryCodes", remainingCodes
        )));
    }

    @PostMapping("/users/{userId}/reset")
    public ResponseEntity<?> resetUserMfa(HttpServletRequest request, @PathVariable String userId) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        // Verify user belongs to tenant
        var users = jdbcTemplate.queryForList(
                "SELECT id FROM platform_user WHERE id = ? AND tenant_id = ?", userId, tenantId);
        if (users.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Delete MFA data
        jdbcTemplate.update("DELETE FROM user_recovery_code WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_totp_secret WHERE user_id = ?", userId);
        jdbcTemplate.update(
                "UPDATE platform_user SET mfa_enabled = false, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), userId);

        SecurityAuditLogger.log(SecurityAuditLogger.EventType.MFA_RESET,
                "admin", userId, tenantId, "success", null);
        log.info("MFA reset for user {} by admin in tenant {}", userId, tenantId);

        return ResponseEntity.ok(Map.of("status", "reset", "userId", userId));
    }

    @GetMapping("/policy")
    public ResponseEntity<?> getMfaPolicy(HttpServletRequest request) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        var results = jdbcTemplate.queryForList(
                "SELECT mfa_required FROM password_policy WHERE tenant_id = ?", tenantId);

        boolean mfaRequired = !results.isEmpty() && Boolean.TRUE.equals(results.get(0).get("mfa_required"));

        return ResponseEntity.ok(Map.of("data", Map.of("mfaRequired", mfaRequired)));
    }

    @PutMapping("/policy")
    public ResponseEntity<?> updateMfaPolicy(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        Boolean mfaRequired = (Boolean) body.get("mfaRequired");
        if (mfaRequired == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "mfaRequired is required"));
        }

        // Update or insert policy
        int updated = jdbcTemplate.update(
                "UPDATE password_policy SET mfa_required = ?, updated_at = NOW() WHERE tenant_id = ?",
                mfaRequired, tenantId);

        if (updated == 0) {
            // No policy row exists — create one with defaults + mfa_required
            jdbcTemplate.update(
                    "INSERT INTO password_policy (id, tenant_id, mfa_required) VALUES (gen_random_uuid()::text, ?, ?)",
                    tenantId, mfaRequired);
        }

        SecurityAuditLogger.log(SecurityAuditLogger.EventType.PASSWORD_POLICY_UPDATED,
                "admin", tenantId, tenantId, "success", "mfa_required=" + mfaRequired);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
