package io.kelta.worker.service;

import io.kelta.runtime.module.integration.spi.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Generates an invite token for a user and queues the {@code user.invite}
 * template email. The token piggy-backs on the existing {@code user_credential}
 * reset-token columns, so the emailed link opens kelta-auth's {@code /reset-password}
 * page ({@code ForgotPasswordController}), where the user sets a first password under
 * the tenant's password policy. The link is built on the configured kelta-auth issuer
 * ({@code kelta.auth.issuer-uri}) — never on a request's host — and carries only the token.
 *
 * <p>Auto-triggered from {@code ScimUserService.createUser} when the tenant
 * has {@code auto_invite_on_create=true}, and on demand from
 * {@code POST /api/admin/users/{id}/invite} for re-sends.
 */
@Service
public class UserInviteService {

    private static final Logger log = LoggerFactory.getLogger(UserInviteService.class);
    private static final Duration INVITE_EXPIRY = Duration.ofDays(7);

    private final JdbcTemplate jdbcTemplate;
    private final EmailService emailService;
    private final String authBaseUrl;

    public UserInviteService(JdbcTemplate jdbcTemplate,
                             EmailService emailService,
                             @Value("${kelta.auth.issuer-uri:https://auth.kelta.io}") String authIssuerUri) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailService = emailService;
        String base = authIssuerUri == null ? "" : authIssuerUri.trim();
        this.authBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * Generates a fresh invite token, stores it on {@code user_credential},
     * and queues the {@code user.invite} email.
     *
     * <p>A {@code SERVICE} user (a module's machine principal) reads as not found: it never
     * receives mail and never gets a password.
     *
     * @return the generated invite token, or null if the user was not found.
     */
    public String inviteUser(String tenantId, String userId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT pu.email, pu.first_name, t.name AS tenant_name "
                        + "FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                        + "WHERE pu.id = ? AND pu.tenant_id = ? AND pu.user_type <> 'SERVICE'",
                userId, tenantId);
        if (rows.isEmpty()) {
            log.warn("Cannot invite user — not found: tenant={}, user={}", tenantId, userId);
            return null;
        }
        var row = rows.get(0);
        String email     = (String) row.get("email");
        String firstName = (String) row.getOrDefault("first_name", "");
        String tenantName = (String) row.getOrDefault("tenant_name", "Kelta");

        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(INVITE_EXPIRY);

        // Upsert a user_credential row holding the invite token. kelta-auth's
        // /reset-password page (and /auth/password/reset) validates this same column.
        int updated = jdbcTemplate.update(
                "UPDATE user_credential SET reset_token = ?, reset_token_expires_at = ?, "
                        + "force_change_on_login = true, updated_at = ? WHERE user_id = ?",
                token, Timestamp.from(expiresAt), Timestamp.from(Instant.now()), userId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO user_credential (id, user_id, password_hash, reset_token, "
                            + "reset_token_expires_at, force_change_on_login, failed_attempts, "
                            + "created_at, updated_at) "
                            + "VALUES (?, ?, '', ?, ?, true, 0, NOW(), NOW())",
                    UUID.randomUUID().toString(), userId, token, Timestamp.from(expiresAt));
        }

        if (authBaseUrl.isEmpty()) {
            log.error("Invite for user {} in tenant {} not sent: kelta.auth.issuer-uri is blank", userId, tenantId);
            return token;
        }
        String actionUrl = authBaseUrl + "/reset-password?token=" + token;
        emailService.sendByKey(tenantId, email, "user.invite",
                Map.of(
                        "tenantName", tenantName,
                        "firstName", firstName == null ? "" : firstName,
                        "email", email,
                        "actionUrl", actionUrl,
                        "expiresIn", "7 days"),
                "USER_INVITE", userId);

        log.info("Invite queued for user {} in tenant {}", userId, tenantId);
        return token;
    }

    /** Returns true when the tenant has not opted out of automatic invites on user create. */
    public boolean isAutoInviteEnabled(String tenantId) {
        try {
            Boolean auto = jdbcTemplate.queryForObject(
                    "SELECT auto_invite_on_create FROM tenant WHERE id = ?",
                    Boolean.class, tenantId);
            return auto == null || auto;
        } catch (Exception e) {
            log.debug("Falling back to auto-invite=true after lookup failure for {}: {}",
                    tenantId, e.getMessage());
            return true;
        }
    }
}
