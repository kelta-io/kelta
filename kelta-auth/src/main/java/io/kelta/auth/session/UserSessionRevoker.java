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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Signs a user out everywhere else after their password changed: ends every other HTTP session
 * of the user and deletes the user's OAuth2 authorizations, so no refresh token issued before
 * the change can be used again.
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
 * <p>Never fails the caller: the password has already been changed when this runs. A failure is
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

    /** What {@link #signOutElsewhere} ended. */
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
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT tenant_id, email, username FROM platform_user WHERE id = ?", userId);
        } catch (RuntimeException e) {
            log.error("Could not sign user {} out of other sessions after a password change: {}",
                    userId, e.getMessage(), e);
            return Result.NONE;
        }
        if (rows.isEmpty()) {
            return Result.NONE;
        }
        String tenantId = (String) rows.getFirst().get("tenant_id");
        String email = (String) rows.getFirst().get("email");
        String username = (String) rows.getFirst().get("username");
        int ended = endSessions(tenantId, userId, keepSessionId);
        int revoked = revokeAuthorizations(tenantId, userId, email, username);
        log.info("Password change: ended {} other session(s) and revoked {} authorization(s) of user {} (tenant {})",
                ended, revoked, userId, tenantId);
        return new Result(ended, revoked);
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
            log.error("Could not end the other sessions of user {} (tenant {}) after a password change: {}",
                    userId, tenantId, e.getMessage(), e);
            return 0;
        }
    }

    private int revokeAuthorizations(String tenantId, String userId, String email, String username) {
        if (email == null) {
            return 0;
        }
        List<String> ids;
        try {
            ids = jdbcTemplate.queryForList(
                    "SELECT id FROM oauth2_authorization WHERE lower(principal_name) IN (lower(?), lower(?))",
                    String.class, email, username != null ? username : email);
        } catch (RuntimeException e) {
            log.error("Could not revoke the authorizations of user {} (tenant {}) after a password change: {}",
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
