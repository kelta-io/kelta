package io.kelta.worker.listener;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.controller.PersonalAccessTokenController;
import io.kelta.worker.controller.PersonalAccessTokenController.TokenRef;
import io.kelta.worker.service.AuthSessionRevocationClient;
import io.kelta.worker.service.SecurityAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Signs a user out of kelta-auth (every session, every refresh token) and revokes every personal
 * access token they hold when the {@code users} record stops being active or is deleted — through
 * the generic collection API, the JSON:API operations endpoint, CSV import or delegated
 * administration, all of which write through
 * QueryEngine and fire this hook. Calls {@link AuthSessionRevocationClient} and
 * {@link PersonalAccessTokenController#revokeTokens} after the write commits (the token update runs
 * in a transaction of its own); best effort, never fails the write.
 *
 * <p>A deleted user's row is gone when kelta-auth handles the call, so the login names kelta-auth
 * needs to find the user's tokens are read in {@link #beforeDelete} and handed over in
 * {@link #afterDelete}. The user's {@code user_api_token} rows go with it ({@code ON DELETE CASCADE}),
 * so their hashes are read there too, to evict them from the gateway's Redis cache.
 */
@Component
public class UserSessionRevocationHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(UserSessionRevocationHook.class);

    static final String ACTIVE = "ACTIVE";
    static final String SYSTEM_ACTOR = "system";
    /** Pending deletes whose names have been read; a delete that fails leaves its entry behind. */
    private static final int MAX_PENDING_DELETES = 1000;

    private final AuthSessionRevocationClient authClient;
    private final JdbcTemplate jdbcTemplate;
    private final PersonalAccessTokenController tokens;
    private final TransactionTemplate newTransaction;
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();

    /** What {@link #beforeDelete} read about a user that is about to be deleted. */
    private record PendingDelete(List<String> loginNames, List<TokenRef> tokens) {}

    public UserSessionRevocationHook(AuthSessionRevocationClient authClient, JdbcTemplate jdbcTemplate,
                                     PersonalAccessTokenController tokens,
                                     PlatformTransactionManager transactionManager) {
        this.authClient = authClient;
        this.jdbcTemplate = jdbcTemplate;
        this.tokens = tokens;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            revokeTokensAfterCommit(tenantId, id, List.of(), "reason=user_deactivated");
        }
    }

    @Override
    public BeforeSaveResult beforeDelete(String id, String tenantId) {
        List<String> names = new ArrayList<>(2);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT email, username FROM platform_user WHERE id = ? AND tenant_id = ?", id, tenantId);
            if (rows.isEmpty()) {
                return BeforeSaveResult.ok();
            }
            if (rows.getFirst().get("email") instanceof String email) {
                names.add(email);
            }
            if (rows.getFirst().get("username") instanceof String username) {
                names.add(username);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read user {} before delete: {}", id, e.getMessage());
        }
        List<TokenRef> liveTokens = List.of();
        try {
            liveTokens = tokens.findLiveTokens(id, tenantId);
        } catch (RuntimeException e) {
            log.warn("Could not read the access tokens of user {} before delete: {}", id, e.getMessage());
        }
        if (names.isEmpty() && liveTokens.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        if (pendingDeletes.size() >= MAX_PENDING_DELETES) {
            pendingDeletes.clear();
        }
        pendingDeletes.put(key(tenantId, id), new PendingDelete(List.copyOf(names), liveTokens));
        return BeforeSaveResult.ok();
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        PendingDelete pending = pendingDeletes.remove(key(tenantId, id));
        authClient.revokeAfterCommit(tenantId, id, pending == null ? List.of() : pending.loginNames());
        revokeTokensAfterCommit(tenantId, id, pending == null ? List.of() : pending.tokens(), "reason=user_deleted");
    }

    /**
     * Revokes every live token of the user once the current transaction commits (immediately when
     * there is none), plus {@code known} tokens whose rows the delete already removed. Never throws.
     */
    private void revokeTokensAfterCommit(String tenantId, String userId, List<TokenRef> known, String auditDetail) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revokeTokensNow(tenantId, userId, known, auditDetail);
                }
            });
        } else {
            revokeTokensNow(tenantId, userId, known, auditDetail);
        }
    }

    void revokeTokensNow(String tenantId, String userId, List<TokenRef> known, String auditDetail) {
        try {
            List<TokenRef> revoked = TenantContext.callWithTenant(tenantId, () -> newTransaction.execute(status ->
                    tokens.revokeTokens(userId, tenantId, null, SYSTEM_ACTOR,
                            SecurityAuditLogger.EventType.PAT_REVOKED, auditDetail)));
            Set<String> revokedIds = revoked == null ? Set.of()
                    : revoked.stream().map(TokenRef::id).collect(Collectors.toSet());
            List<TokenRef> cascaded = known.stream().filter(t -> !revokedIds.contains(t.id())).toList();
            tokens.revokeCached(cascaded, userId, tenantId, SYSTEM_ACTOR,
                    SecurityAuditLogger.EventType.PAT_REVOKED, auditDetail);
        } catch (RuntimeException e) {
            log.error("Could not revoke the access tokens of user {} (tenant {}): {}; they may still be valid",
                    userId, tenantId, e.toString());
        }
    }

    private static String key(String tenantId, String id) {
        return tenantId + ":" + id;
    }
}
