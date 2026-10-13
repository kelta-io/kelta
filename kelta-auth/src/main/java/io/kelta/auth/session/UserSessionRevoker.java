package io.kelta.auth.session;

import io.kelta.auth.model.KeltaUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Signs a user out: ends the user's HTTP sessions and deletes the user's OAuth2 authorizations,
 * so no refresh token issued earlier can be used again. {@link #signOutElsewhere} runs after the
 * user changed their own password (the session the change was made in is kept);
 * {@link #signOutEverywhere} runs when an administrator reset, deactivated or removed the account
 * (kelta-worker calls it through {@code InternalSessionRevocationController}).
 *
 * <p>Scoped to one platform user — tenant and user id. The same email address can belong to
 * users in several tenants; their sessions and authorizations are never touched:
 * <ul>
 *   <li>sessions are looked up in the session store's per-user index
 *       ({@link SessionPrincipalKeys#forUser});</li>
 *   <li>{@code oauth2_authorization} rows carry only the login name (the email) as
 *       {@code principal_name}, so the rows with the user's email or username (any case) are read
 *       back through the authorization service and only those whose stored principal is this
 *       tenant's user are removed.</li>
 * </ul>
 *
 * <p>Access tokens are signed JWTs, valid until they expire (minutes); refresh tokens and
 * signed-in sessions are what let a client stay signed in, and those end here.
 *
 * <p>Never fails the caller: the change has already been made when this runs. A failure is
 * logged at ERROR.
 */
@Component
public class UserSessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(UserSessionRevoker.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final OAuth2AuthorizationService authorizations;
    private final JdbcTemplate jdbcTemplate;

    public UserSessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions,
                              OAuth2AuthorizationService authorizations,
                              JdbcTemplate jdbcTemplate) {
        this.sessions = sessions;
        this.authorizations = authorizations;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** What a sign-out ended. */
    public record Result(int sessionsEnded, int authorizationsRevoked) {
        static final Result NONE = new Result(0, 0);
    }

    /**
     * Ends every HTTP session of platform user {@code userId} except {@code keepSessionId} (the
     * session the change was made in; {@code null} keeps none) and revokes all of the user's
     * OAuth2 authorizations.
     */
    public Result signOutElsewhere(String userId, String keepSessionId) {
        if (userId == null || userId.isBlank()) {
            return Result.NONE;
        }
        Optional<Account> account = findAccount(userId);
        if (account.isEmpty()) {
            return Result.NONE;
        }
        String tenantId = account.get().tenantId();
        int ended = endSessions(tenantId, userId, keepSessionId);
        int revoked = revokeAuthorizations(tenantId, userId, account.get().loginNames());
        log.info("Password change: ended {} other session(s) and revoked {} authorization(s) of user {} (tenant {})",
                ended, revoked, userId, tenantId);
        return new Result(ended, revoked);
    }

    /**
     * Ends every HTTP session of tenant {@code tenantId}'s user {@code userId} and revokes all of
     * the user's OAuth2 authorizations — after an administrator reset the user's password,
     * deactivated or removed the account. Nothing is kept.
     *
     * <p>The login names are read from the user's row. When the row no longer exists (the user
     * was deleted), {@code knownLoginNames} supplies them; they only select candidate rows, each
     * of which is still removed only when its stored principal is this tenant's user. A user id
     * that belongs to another tenant is left alone.
     */
    public Result signOutEverywhere(String tenantId, String userId, Collection<String> knownLoginNames) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return Result.NONE;
        }
        Optional<Account> account = findAccount(userId);
        if (account.isPresent() && !tenantId.equals(account.get().tenantId())) {
            log.warn("Sign-out request for user {} named tenant {}, which is not the user's tenant; ignored",
                    userId, tenantId);
            return Result.NONE;
        }
        // Sessions are indexed by tenant + user id and authorizations are checked against both,
        // so nothing outside this tenant's user is touched even when the row cannot be read.
        List<String> loginNames = account.map(Account::loginNames)
                .orElseGet(() -> knownLoginNames == null ? List.of() : List.copyOf(knownLoginNames));
        int ended = endSessions(tenantId, userId, null);
        int revoked = revokeAuthorizations(tenantId, userId, loginNames);
        log.info("Signed user {} (tenant {}) out everywhere: ended {} session(s), revoked {} authorization(s)",
                userId, tenantId, ended, revoked);
        return new Result(ended, revoked);
    }

    private record Account(String tenantId, List<String> loginNames) {}

    /** The user's tenant and login names (email, username); empty when there is no such user. */
    private Optional<Account> findAccount(String userId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT tenant_id, email, username FROM platform_user WHERE id = ?", userId);
        } catch (RuntimeException e) {
            log.error("Could not read user {} to sign them out: {}", userId, e.getMessage(), e);
            return Optional.empty();
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        List<String> names = new ArrayList<>(2);
        if (row.get("email") instanceof String email) {
            names.add(email);
        }
        if (row.get("username") instanceof String username) {
            names.add(username);
        }
        return Optional.of(new Account((String) row.get("tenant_id"), names));
    }

    private int endSessions(String tenantId, String userId, String keepSessionId) {
        try {
            int ended = 0;
            for (String sessionId : List.copyOf(sessions.findByIndexNameAndIndexValue(
                    FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                    SessionPrincipalKeys.forUser(tenantId, userId)).keySet())) {
                if (!sessionId.equals(keepSessionId)) {
                    sessions.deleteById(sessionId);
                    ended++;
                }
            }
            return ended;
        } catch (RuntimeException e) {
            log.error("Could not end the sessions of user {} (tenant {}): {}",
                    userId, tenantId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Deletes the user's authorizations. Rows are found by login name ({@code principal_name},
     * case-insensitive; indexed on {@code lower(principal_name)}) and removed only when the
     * stored principal is this tenant's user.
     */
    private int revokeAuthorizations(String tenantId, String userId, List<String> loginNames) {
        List<String> names = loginNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(2)
                .toList();
        if (names.isEmpty()) {
            return 0;
        }
        List<String> ids;
        try {
            ids = jdbcTemplate.queryForList(
                    "SELECT id FROM oauth2_authorization WHERE lower(principal_name) IN (lower(?), lower(?))",
                    String.class, names.getFirst(), names.getLast());
        } catch (RuntimeException e) {
            log.error("Could not revoke the authorizations of user {} (tenant {}): {}",
                    userId, tenantId, e.getMessage(), e);
            return 0;
        }
        int revoked = 0;
        for (String id : ids) {
            try {
                OAuth2Authorization authorization = authorizations.findById(id);
                if (authorization != null && belongsTo(authorization, tenantId, userId)) {
                    authorizations.remove(authorization);
                    revoked++;
                }
            } catch (RuntimeException e) {
                // A row that cannot be read back cannot be refreshed either.
                log.warn("Authorization {} could not be read while revoking user {}'s authorizations: {}",
                        id, userId, e.getMessage());
            }
        }
        return revoked;
    }

    static boolean belongsTo(OAuth2Authorization authorization, String tenantId, String userId) {
        Object principal = authorization.getAttribute(Principal.class.getName());
        Object user = principal instanceof Authentication authentication ? authentication.getPrincipal() : null;
        return user instanceof KeltaUserDetails details
                && Objects.equals(details.getTenantId(), tenantId)
                && Objects.equals(details.getId(), userId);
    }
}
