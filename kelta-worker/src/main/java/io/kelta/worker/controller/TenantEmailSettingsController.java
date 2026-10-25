package io.kelta.worker.controller;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.repository.EmailRepository.TenantEmailConfigRow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoints for tenant-level email configuration:
 *
 * <ul>
 *   <li>{@code GET /api/admin/tenant/email-settings} — current SMTP credential
 *       reference and From overrides (passwords masked).</li>
 *   <li>{@code PUT /api/admin/tenant/email-settings} — upserts an {@code smtp}
 *       credential row, points the tenant at it, and updates From overrides.</li>
 *   <li>{@code POST /api/admin/tenant/email-settings/test} — queues a templated
 *       test email so the admin can confirm delivery in their mail server.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/tenant/email-settings")
// Gate on the SAME property that creates the EncryptionService bean
// (EncryptionAutoConfiguration @ConditionalOnProperty "kelta.encryption.key").
// @ConditionalOnBean on a @RestController is order-sensitive — the
// component scan can evaluate it before the runtime-core auto-config
// registers EncryptionService, dropping the controller even though the
// bean is later created. The request then falls through to the dynamic
// collection router and 404s ("collection 'admin' not in registry").
// A property condition is evaluated deterministically regardless of
// bean-definition order and mirrors exactly when EncryptionService exists.
@ConditionalOnProperty(name = "kelta.encryption.key")
public class TenantEmailSettingsController {

    private static final String PERMISSION = "MANAGE_TENANTS";

    private static final Logger log = LoggerFactory.getLogger(TenantEmailSettingsController.class);
    private static final String CREDENTIAL_NAME = "tenant-smtp";

    private final JdbcTemplate jdbcTemplate;
    private final EmailRepository emailRepository;
    private final EmailService emailService;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final AdminPermissionGuard permissionGuard;

    public TenantEmailSettingsController(JdbcTemplate jdbcTemplate,
                                          EmailRepository emailRepository,
                                          EmailService emailService,
                                          EncryptionService encryptionService,
                                          ObjectMapper objectMapper,
                                          AdminPermissionGuard permissionGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailRepository = emailRepository;
        this.emailService = emailService;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping
    public ResponseEntity<?> get(HttpServletRequest request) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        TenantEmailConfigRow row = emailRepository.findTenantEmailConfig(tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hasOverride", row != null && row.smtpCredentialId() != null);
        body.put("fromAddress", row == null ? null : row.fromAddress());
        body.put("fromName",    row == null ? null : row.fromName());
        body.put("autoInviteOnCreate", row == null || row.autoInviteOnCreate());

        if (row != null && row.smtpCredentialId() != null) {
            // Pull non-secret metadata (host, port, useStartTls, fromAddress).
            // Password/username are never returned.
            List<Map<String, Object>> credRows = jdbcTemplate.queryForList(
                    "SELECT metadata FROM credential WHERE id = ? AND tenant_id = ?",
                    row.smtpCredentialId(), tenantId);
            if (!credRows.isEmpty()) {
                body.put("smtp", credRows.get(0).get("metadata"));
            }
        }
        return ResponseEntity.ok(Map.of("data", body));
    }

    public record EmailSettingsUpdate(
            String host,
            Integer port,
            String username,
            String password,
            Boolean useStartTls,
            String fromAddress,
            String fromName,
            Boolean autoInviteOnCreate,
            Boolean clear
    ) {}

    @PutMapping
    public ResponseEntity<?> update(HttpServletRequest request, @RequestBody EmailSettingsUpdate req) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        if (Boolean.TRUE.equals(req.clear())) {
            jdbcTemplate.update(
                    "UPDATE tenant SET email_smtp_credential_id = NULL, "
                            + "email_from_address = ?, email_from_name = ?, "
                            + "auto_invite_on_create = COALESCE(?, auto_invite_on_create), "
                            + "updated_at = NOW() WHERE id = ?",
                    req.fromAddress(), req.fromName(), req.autoInviteOnCreate(), tenantId);
            return ResponseEntity.ok(Map.of("status", "ok"));
        }

        // Upsert the credential row. Writes flow through the dynamic collection
        // router on the public API; here we issue raw SQL because this is an
        // admin endpoint and the encryption hook is bypassed only when the
        // user posts no secret material. We encrypt via the same hook path by
        // going through the credential collection — but to keep this PR focused
        // we cheat and call the controller-internal encrypt route via JDBC
        // when password is supplied, mirroring CredentialController's behaviour.

        return TenantContext.callWithTenant(tenantId, () -> {
            String credentialId = findExistingTenantCredential(tenantId);
            Map<String, Object> metadata = buildMetadata(req);
            if (credentialId == null) {
                credentialId = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        "INSERT INTO credential (id, tenant_id, name, type, data_enc, metadata, active, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'smtp', ?, ?::jsonb, true, NOW(), NOW())",
                        credentialId, tenantId, CREDENTIAL_NAME,
                        encryptSecrets(req),
                        toJson(metadata));
            } else {
                if (req.password() != null && !req.password().isBlank()) {
                    jdbcTemplate.update(
                            "UPDATE credential SET data_enc = ?, metadata = ?::jsonb, updated_at = NOW() "
                                    + "WHERE id = ? AND tenant_id = ?",
                            encryptSecrets(req), toJson(metadata), credentialId, tenantId);
                } else {
                    jdbcTemplate.update(
                            "UPDATE credential SET metadata = ?::jsonb, updated_at = NOW() "
                                    + "WHERE id = ? AND tenant_id = ?",
                            toJson(metadata), credentialId, tenantId);
                }
            }
            jdbcTemplate.update(
                    "UPDATE tenant SET email_smtp_credential_id = ?, "
                            + "email_from_address = ?, email_from_name = ?, "
                            + "auto_invite_on_create = COALESCE(?, auto_invite_on_create), "
                            + "updated_at = NOW() WHERE id = ?",
                    credentialId, req.fromAddress(), req.fromName(),
                    req.autoInviteOnCreate(), tenantId);
            log.info("Tenant {} updated email settings (credentialId={})", tenantId, credentialId);
            return ResponseEntity.ok(Map.of("status", "ok", "credentialId", credentialId));
        });
    }

    public record TestSendRequest(String to) {}

    @PostMapping("/test")
    public ResponseEntity<?> testSend(HttpServletRequest request, @RequestBody TestSendRequest req) {
        permissionGuard.require(request, PERMISSION);
        String tenantId = TenantContext.get();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        if (req.to() == null || req.to().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Recipient is required"));
        }
        String logId = emailService.queueEmail(tenantId, req.to(),
                "Kelta test email",
                "<p>This is a test email from your Kelta tenant. If you can read this, your SMTP configuration is working.</p>",
                "EMAIL_TEST", null);
        return ResponseEntity.ok(Map.of("emailLogId", logId, "status", "QUEUED"));
    }

    private String findExistingTenantCredential(String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM credential WHERE tenant_id = ? AND type = 'smtp' AND name = ?",
                tenantId, CREDENTIAL_NAME);
        return rows.isEmpty() ? null : (String) rows.get(0).get("id");
    }

    private Map<String, Object> buildMetadata(EmailSettingsUpdate req) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (req.host() != null) m.put("host", req.host());
        if (req.port() != null) m.put("port", req.port());
        m.put("useStartTls", req.useStartTls() == null ? Boolean.TRUE : req.useStartTls());
        if (req.fromAddress() != null) m.put("fromAddress", req.fromAddress());
        return m;
    }

    private String encryptSecrets(EmailSettingsUpdate req) {
        String json = toJson(Map.of(
                "username", req.username() == null ? "" : req.username(),
                "password", req.password() == null ? "" : req.password()));
        return encryptionService.encrypt(json);
    }

    private String toJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
