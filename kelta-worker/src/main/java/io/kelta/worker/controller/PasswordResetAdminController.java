package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.AuthSessionRevocationClient;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.SecurityAuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * Admin API for password management: force password reset on next login.
 *
 * <p>Requires {@code MANAGE_USERS} — {@code /api/admin/**} is a static gateway route with only
 * the blanket API_ACCESS check, so the permission is enforced here. Delegated admins reset
 * through {@code POST /api/admin/delegated/users/{id}/reset-password}, which enforces their
 * scope instead.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/admin/users")
public class PasswordResetAdminController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetAdminController.class);
    private static final String PERMISSION = "MANAGE_USERS";

    private final JdbcTemplate jdbcTemplate;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final AuthSessionRevocationClient authSessions;

    public PasswordResetAdminController(JdbcTemplate jdbcTemplate,
                                        CerbosPermissionResolver permissionResolver,
                                        BootstrapRepository bootstrapRepository,
                                        AuthSessionRevocationClient authSessions) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.authSessions = authSessions;
    }

    /**
     * Force a user to change their password on next login.
     * Sets the force_change_on_login flag to true on the user_credential table, then signs the
     * user out of kelta-auth (sessions and refresh tokens) so the change is required now.
     */
    @PostMapping("/{userId}/reset-password")
    public ResponseEntity<?> resetPassword(HttpServletRequest request, @PathVariable String userId) {
        requirePermission(request);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        // Verify user belongs to tenant
        var users = jdbcTemplate.queryForList(
                "SELECT id, email FROM platform_user WHERE id = ? AND tenant_id = ?",
                userId, tenantId);
        if (users.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Set force_change_on_login flag
        int updated = jdbcTemplate.update(
                "UPDATE user_credential SET force_change_on_login = true, updated_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now()), userId);

        if (updated == 0) {
            log.warn("No user_credential row found for user {} in tenant {}", userId, tenantId);
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No credential record found for this user"));
        }

        SecurityAuditLogger.log(SecurityAuditLogger.EventType.PASSWORD_RESET_ADMIN,
                "admin", userId, tenantId, "success", "force_change_on_login=true");
        log.info("Password reset initiated for user {} by admin in tenant {}", userId, tenantId);
        authSessions.revokeAfterCommit(tenantId, userId);

        return ResponseEntity.ok(Map.of("status", "reset_initiated", "userId", userId));
    }

    private void requirePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }
}
