package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.controller.PersonalAccessTokenController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Erases a user's personal data while keeping the {@code platform_user} row and its id, so every
 * record the user created, updated or owns still points at it ({@code DELETE /api/users/{id}}
 * answers 409 for exactly those users). Behind {@code POST /api/admin/users/{id}/anonymize} and
 * the {@code ANONYMIZE_USER} flow action.
 *
 * <p>One transaction:
 * <ol>
 *   <li>the row's personal columns are scrubbed over JDBC (email becomes
 *       {@code deleted+<id>@invalid}; username, names, phone, verification token and settings are
 *       cleared) — first, so neither the record-change event nor the generic setup-audit row of
 *       the next step carries the old values;</li>
 *   <li>{@code status = ANONYMIZED} is written through {@link QueryEngine} (the {@code users}
 *       collection), so {@code UserSessionRevocationHook} signs the user out of kelta-auth, revokes
 *       their PATs and writes the gateway's {@code user:revoked} marker. The old login names are
 *       handed to the hook through {@link #priorLoginNames()}: kelta-auth finds the refresh tokens
 *       by the name they were issued under, which is no longer on the row;</li>
 *   <li>the password, TOTP, recovery-code, UI-preference, portal-login-token and push-device rows
 *       are deleted, and any PAT the hook did not reach is revoked;</li>
 *   <li>one {@code ANONYMIZED} setup-audit row naming actor, tenant and target id — no PII.</li>
 * </ol>
 * Anonymizing an anonymized user changes nothing and writes no second audit row.
 */
@Service
public class UserAnonymizationService {

    private static final Logger log = LoggerFactory.getLogger(UserAnonymizationService.class);

    public static final String ANONYMIZED = "ANONYMIZED";
    /** The platform's own {@code system} user (tenant {@code system}); never anonymized. */
    static final String SYSTEM_USER_ID = "system";
    static final String PAT_AUDIT_DETAIL = "user anonymized";

    private static final ScopedValue<List<String>> PRIOR_LOGIN_NAMES = ScopedValue.newInstance();

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final PersonalAccessTokenController tokens;
    private final SetupAuditService setupAuditService;

    public UserAnonymizationService(QueryEngine queryEngine, CollectionRegistry collectionRegistry,
                                    JdbcTemplate jdbcTemplate, PersonalAccessTokenController tokens,
                                    SetupAuditService setupAuditService) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.tokens = tokens;
        this.setupAuditService = setupAuditService;
    }

    /** The user does not exist in the tenant (a user of another tenant reads the same). */
    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String userId) {
            super("User not found: " + userId);
        }
    }

    /** The user may not be anonymized (the {@code system} user, or the caller themself). */
    public static class AnonymizationRefusedException extends RuntimeException {
        public AnonymizationRefusedException(String message) {
            super(message);
        }
    }

    /** {@code alreadyAnonymized} is true when the call found the user anonymized and changed nothing. */
    public record Result(String userId, boolean alreadyAnonymized) {}

    /** The placeholder that replaces an anonymized user's email; unique per user, never deliverable. */
    public static String placeholderEmail(String userId) {
        return "deleted+" + userId + "@invalid";
    }

    /**
     * The login names (email, username) the user held before the anonymize running on this
     * thread scrubbed them; empty outside an anonymize. Read by {@code UserSessionRevocationHook}.
     */
    public static Optional<List<String>> priorLoginNames() {
        return PRIOR_LOGIN_NAMES.isBound() ? Optional.of(PRIOR_LOGIN_NAMES.get()) : Optional.empty();
    }

    /**
     * Anonymizes {@code userId} in {@code tenantId}, which must be bound. {@code actorId} is
     * recorded as the actor.
     *
     * @throws UserNotFoundException when the tenant has no such user
     * @throws AnonymizationRefusedException for the {@code system} user
     */
    @Transactional
    public Result anonymize(String tenantId, String userId, String actorId) {
        if (userId == null || userId.isBlank()) {
            throw new UserNotFoundException(String.valueOf(userId));
        }
        if (SYSTEM_USER_ID.equals(userId)) {
            throw new AnonymizationRefusedException("The system user cannot be anonymized");
        }
        CollectionDefinition users = collectionRegistry.get("users");
        if (users == null) {
            throw new IllegalStateException("users collection not registered");
        }
        // Row lock first: a concurrent anonymize of the same user waits here and then sees ANONYMIZED.
        List<String> status = jdbcTemplate.queryForList(
                "SELECT status FROM platform_user WHERE id = ? AND tenant_id = ? FOR UPDATE",
                String.class, userId, tenantId);
        if (status.isEmpty()) {
            throw new UserNotFoundException(userId);
        }
        if (ANONYMIZED.equals(status.getFirst())) {
            return new Result(userId, true);
        }
        Map<String, Object> user = queryEngine.getById(users, userId)
                .filter(row -> tenantId.equals(row.get("tenantId")))
                .orElseThrow(() -> new UserNotFoundException(userId));

        List<String> loginNames = new ArrayList<>(2);
        if (user.get("email") instanceof String email && !email.isBlank()) {
            loginNames.add(email);
        }
        if (user.get("username") instanceof String username && !username.isBlank()) {
            loginNames.add(username);
        }

        jdbcTemplate.update("UPDATE platform_user SET email = ?, username = NULL, first_name = NULL, "
                        + "last_name = NULL, phone_number = NULL, email_verification_token = NULL, "
                        + "email_verification_expires_at = NULL, email_verified = false, mfa_enabled = false, "
                        + "settings = '{}'::jsonb WHERE id = ? AND tenant_id = ?",
                placeholderEmail(userId), userId, tenantId);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", ANONYMIZED);
        if (actorId != null && !actorId.isBlank()) {
            patch.put("updatedBy", actorId);
        }
        ScopedValue.where(PRIOR_LOGIN_NAMES, List.copyOf(loginNames))
                .run(() -> queryEngine.update(users, userId, patch));

        jdbcTemplate.update("DELETE FROM user_credential WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_totp_secret WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_recovery_code WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_ui_preference WHERE user_id = ? AND tenant_id = ?", userId, tenantId);
        jdbcTemplate.update("DELETE FROM portal_login_token WHERE user_id = ? AND tenant_id = ?", userId, tenantId);
        jdbcTemplate.update("DELETE FROM push_device WHERE user_id = ? AND tenant_id = ?", userId, tenantId);
        tokens.revokeAllUserTokens(tenantId, userId, actorId,
                SecurityAuditLogger.EventType.PAT_ADMIN_REVOKED, PAT_AUDIT_DETAIL);

        setupAuditService.log(tenantId, actorId, ANONYMIZED, "Users", "user", userId, null, null,
                "{\"status\":\"" + ANONYMIZED + "\"}");
        log.info("User {} anonymized in tenant {} by {}", userId, tenantId, actorId);
        return new Result(userId, false);
    }
}
