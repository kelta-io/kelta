package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.controller.PersonalAccessTokenController;
import io.kelta.worker.controller.PersonalAccessTokenController.LiveToken;
import io.kelta.worker.service.AuthSessionRevocationClient;
import io.kelta.worker.service.SecurityAuditLogger;
import io.kelta.worker.service.UserAccessRevocationMarker;
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
 * {@link #afterDelete}. The same goes for the PATs, whose rows the delete cascades away.
 */
@Component
public class UserSessionRevocationHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(UserSessionRevocationHook.class);

    static final String ACTIVE = "ACTIVE";
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
    public void afterUpdate(String id, Map<String, Object> record, Map<String, Object> previous, String tenantId) {
        Object status = record == null ? null : record.get("status");
        Object before = previous == null ? null : previous.get("status");
        if (status != null && !ACTIVE.equals(status) && !Objects.equals(status, before)) {
            authClient.revokeAfterCommit(tenantId, id);
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
