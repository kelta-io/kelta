package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import io.kelta.worker.controller.PersonalAccessTokenController;
import io.kelta.worker.controller.PersonalAccessTokenController.LiveToken;
import io.kelta.worker.service.AuthSessionRevocationClient;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.UserAccessRevocationMarker;
import io.kelta.worker.service.UserAnonymizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs a user out of kelta-auth (every session, every refresh token) when the {@code users}
 * record stops being active or is deleted — through the generic collection API, the JSON:API
 * operations endpoint, CSV import or delegated administration, all of which write through
 * QueryEngine and fire this hook. Calls {@link AuthSessionRevocationClient} after the write
 * commits; best effort, never fails the write.
 *
 * <p>The user's personal access tokens are revoked too, through
 * {@link PersonalAccessTokenController}: the rows are marked revoked, the gateway's
 * {@code pat:<hash>} cache entries dropped and {@code pat:revoked:<hash>} written, so automation
 * holding a cached token stops working. Also best effort: a failure is logged, never thrown.
 *
 * <p>The access tokens kelta-auth has already issued are self-contained JWTs, so
 * {@link UserAccessRevocationMarker} writes {@code user:revoked:<tenantId>:<userId>} after the
 * write commits and the gateway rejects every one of them issued before it. Best effort as well.
 *
 * <p>A deleted user's row is gone when kelta-auth handles the call, so the login names kelta-auth
 * needs to find the user's tokens are read in {@link #beforeDelete} and handed over in
 * {@link #afterDelete}. The same goes for the PATs, whose rows the delete cascades away. An
 * anonymized user's row no longer carries those names either: {@link UserAnonymizationService}
 * hands them over through {@link UserAnonymizationService#priorLoginNames()}.
 *
 * <p>{@code ANONYMIZED} is terminal: {@link #beforeUpdate} answers 409 to a write that moves an
 * anonymized user to another status or gives them an email, and to one that sets
 * {@code ANONYMIZED} anywhere but inside {@link UserAnonymizationService} (which scrubs the
 * personal data first).
 */
@Component
public class UserSessionRevocationHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(UserSessionRevocationHook.class);

    static final String ACTIVE = "ACTIVE";
    static final String ANONYMIZED = UserAnonymizationService.ANONYMIZED;
    static final String CONFLICT_CODE = "USER_ANONYMIZED";
    static final String AUDIT_ACTOR = "system";
    /** Pending deletes whose names have been read; a delete that fails leaves its entry behind. */
    private static final int MAX_PENDING_DELETES = 1000;

    private final AuthSessionRevocationClient authClient;
    private final JdbcTemplate jdbcTemplate;
    private final PersonalAccessTokenController tokens;
    private final UserAccessRevocationMarker accessRevocation;
    private final Map<String, List<String>> pendingDeletes = new ConcurrentHashMap<>();
    private final Map<String, List<LiveToken>> pendingTokenDeletes = new ConcurrentHashMap<>();

    public UserSessionRevocationHook(AuthSessionRevocationClient authClient, JdbcTemplate jdbcTemplate,
                                     PersonalAccessTokenController tokens,
                                     UserAccessRevocationMarker accessRevocation) {
        this.authClient = authClient;
        this.jdbcTemplate = jdbcTemplate;
        this.tokens = tokens;
        this.accessRevocation = accessRevocation;
    }

    @Override
    public String getCollectionName() {
        return "users";
    }

    /** After the audit and version hooks; no other hook on {@code users} uses this order. */
    @Override
    public int getOrder() {
        return 400;
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record, Map<String, Object> previous,
                                         String tenantId) {
        Object status = record.get("status");
        Object before = previous == null ? null : previous.get("status");
        if (ANONYMIZED.equals(before)) {
            if (status != null && !ANONYMIZED.equals(status)) {
                throw new HookConflictException(CONFLICT_CODE, "status",
                        "An anonymized user cannot be given another status", Map.of("userId", id));
            }
            Object email = record.get("email");
            if (email != null && !email.toString().isBlank() && !email.equals(previous.get("email"))) {
                throw new HookConflictException(CONFLICT_CODE, "email",
                        "An anonymized user cannot be given an email address", Map.of("userId", id));
            }
        } else if (ANONYMIZED.equals(status) && UserAnonymizationService.priorLoginNames().isEmpty()) {
            throw new HookConflictException(CONFLICT_CODE, "status",
                    "Anonymize a user with POST /api/admin/users/{id}/anonymize, which also erases their "
                            + "personal data", Map.of("userId", id));
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record, Map<String, Object> previous, String tenantId) {
        Object status = record == null ? null : record.get("status");
        Object before = previous == null ? null : previous.get("status");
        if (status != null && !ACTIVE.equals(status) && !Objects.equals(status, before)) {
            List<String> priorNames = UserAnonymizationService.priorLoginNames().orElse(List.of());
            if (priorNames.isEmpty()) {
                authClient.revokeAfterCommit(tenantId, id);
            } else {
                authClient.revokeAfterCommit(tenantId, id, priorNames);
            }
            accessRevocation.markAfterCommit(tenantId, id);
            try {
                tokens.revokeAllUserTokens(tenantId, id, AUDIT_ACTOR,
                        SecurityAuditLogger.EventType.PAT_REVOKED, "user status " + status);
            } catch (RuntimeException e) {
                log.error("Could not revoke the personal access tokens of user {} (tenant {}): {}",
                        id, tenantId, e.getMessage());
            }
        }
    }

    @Override
    public BeforeSaveResult beforeDelete(String id, String tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT email, username FROM platform_user WHERE id = ? AND tenant_id = ?", id, tenantId);
            if (!rows.isEmpty()) {
                List<String> names = new ArrayList<>(2);
                if (rows.getFirst().get("email") instanceof String email) {
                    names.add(email);
                }
                if (rows.getFirst().get("username") instanceof String username) {
                    names.add(username);
                }
                if (pendingDeletes.size() >= MAX_PENDING_DELETES) {
                    pendingDeletes.clear();
                }
                pendingDeletes.put(key(tenantId, id), List.copyOf(names));
            }
        } catch (RuntimeException e) {
            log.warn("Could not read user {} before delete: {}", id, e.getMessage());
        }
        try {
            List<LiveToken> live = tokens.findLiveTokens(tenantId, id);
            if (!live.isEmpty()) {
                if (pendingTokenDeletes.size() >= MAX_PENDING_DELETES) {
                    pendingTokenDeletes.clear();
                }
                pendingTokenDeletes.put(key(tenantId, id), live);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the personal access tokens of user {} before delete: {}", id, e.getMessage());
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        List<String> names = pendingDeletes.remove(key(tenantId, id));
        authClient.revokeAfterCommit(tenantId, id, names == null ? List.of() : names);
        accessRevocation.markAfterCommit(tenantId, id);
        List<LiveToken> live = pendingTokenDeletes.remove(key(tenantId, id));
        if (live != null) {
            try {
                tokens.revokeDeletedTokens(tenantId, live, AUDIT_ACTOR,
                        SecurityAuditLogger.EventType.PAT_REVOKED, "user deleted");
            } catch (RuntimeException e) {
                log.error("Could not revoke the personal access tokens of deleted user {} (tenant {}): {}",
                        id, tenantId, e.getMessage());
            }
        }
    }

    private static String key(String tenantId, String id) {
        return tenantId + ":" + id;
    }
}
