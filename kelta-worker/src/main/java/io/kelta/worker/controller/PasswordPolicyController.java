package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.PasswordPolicyRepository;
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
 * Admin API for password policy management and account unlock.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/admin/password-policy")
public class PasswordPolicyController {

    private static final String PERMISSION = "MANAGE_USERS";

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicyController.class);

    private final PasswordPolicyRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final AdminPermissionGuard permissionGuard;

    // Default policy values (NIST SP 800-63B aligned)
    private static final Map<String, Object> DEFAULTS;
    static {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("minLength", 8);
        map.put("maxLength", 128);
        map.put("requireUppercase", false);
        map.put("requireLowercase", false);
        map.put("requireDigit", false);
        map.put("requireSpecial", false);
        map.put("historyCount", 3);
        map.put("dictionaryCheck", true);
        map.put("personalDataCheck", true);
        map.put("lockoutThreshold", 5);
        map.put("lockoutDurationMinutes", 30);
        map.put("maxAgeDays", null);
        DEFAULTS = java.util.Collections.unmodifiableMap(map);
    }

    public PasswordPolicyController(PasswordPolicyRepository repository, JdbcTemplate jdbcTemplate, AdminPermissionGuard permissionGuard) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping
    public ResponseEntity<?> getPolicy(HttpServletRequest request) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        var policy = repository.findByTenantId(tenantId);
        if (policy.isPresent()) {
            return ResponseEntity.ok(Map.of("data", mapToResponse(policy.get())));
        }
        return ResponseEntity.ok(Map.of("data", DEFAULTS));
    }

    @PutMapping
    public ResponseEntity<?> updatePolicy(HttpServletRequest request, @RequestBody Map<String, Object> policy) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        // Validate min_length >= 8
        Object minLength = policy.get("minLength");
        if (minLength instanceof Number n && n.intValue() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "Minimum password length cannot be less than 8"));
        }

        // Record before state for audit
        var before = repository.findByTenantId(tenantId).orElse(DEFAULTS);

        repository.upsert(tenantId, policy);

        SecurityAuditLogger.log(SecurityAuditLogger.EventType.PASSWORD_POLICY_UPDATED,
                "admin", tenantId, tenantId, "success",
                "before=" + before.toString());
        log.info("Password policy updated for tenant {}", tenantId);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/users/{userId}/unlock")
    public ResponseEntity<?> unlockAccount(HttpServletRequest request, @PathVariable String userId) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        // Tenant-scoped: verify user belongs to tenant
        var userResults = jdbcTemplate.queryForList(
                "SELECT id FROM platform_user WHERE id = ? AND tenant_id = ?",
                userId, tenantId
        );
        if (userResults.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        jdbcTemplate.update(
                "UPDATE user_credential SET failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now()), userId
        );

        SecurityAuditLogger.log(SecurityAuditLogger.EventType.ACCOUNT_UNLOCKED,
                "admin", userId, tenantId, "success", null);
        log.info("Account unlocked: userId={}, tenantId={}", userId, tenantId);
        return ResponseEntity.ok(Map.of("status", "unlocked", "userId", userId));
    }

    private Map<String, Object> mapToResponse(Map<String, Object> row) {
        return Map.ofEntries(
                Map.entry("minLength", row.get("min_length")),
                Map.entry("maxLength", row.get("max_length")),
                Map.entry("requireUppercase", row.get("require_uppercase")),
                Map.entry("requireLowercase", row.get("require_lowercase")),
                Map.entry("requireDigit", row.get("require_digit")),
                Map.entry("requireSpecial", row.get("require_special")),
                Map.entry("historyCount", row.get("history_count")),
                Map.entry("dictionaryCheck", row.get("dictionary_check")),
                Map.entry("personalDataCheck", row.get("personal_data_check")),
                Map.entry("lockoutThreshold", row.get("lockout_threshold")),
                Map.entry("lockoutDurationMinutes", row.get("lockout_duration_minutes")),
                Map.entry("maxAgeDays", row.get("max_age_days"))
        );
    }
}
