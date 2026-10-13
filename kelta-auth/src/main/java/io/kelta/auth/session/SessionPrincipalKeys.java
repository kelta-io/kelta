package io.kelta.auth.session;

import io.kelta.auth.model.KeltaUserDetails;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;

import java.security.Principal;

/**
 * The key a signed-in user's sessions are indexed under in the session store.
 *
 * <p>A platform user is identified by tenant and user id, never by login name: the same email
 * address can belong to different users in different tenants, and the OIDC session lookup (the
 * ID token's {@code sid}, the end-session check) must only ever see the sessions of the user it
 * asks about. This matches {@link KeltaUserDetails#equals(Object)}, which the previous in-memory
 * registry keyed on. The key holds ids only, so no email address ends up in a Redis key name.
 */
public final class SessionPrincipalKeys {

    private SessionPrincipalKeys() {
    }

    /** The index key for {@code principal} (an authentication's principal object). */
    public static String of(Object principal) {
        if (principal instanceof KeltaUserDetails user
                && user.getTenantId() != null && user.getId() != null) {
            return forUser(user.getTenantId(), user.getId());
        }
        if (principal instanceof UserDetails user) {
            return user.getUsername();
        }
        if (principal instanceof AuthenticatedPrincipal named) {
            return named.getName();
        }
        if (principal instanceof Principal named) {
            return named.getName();
        }
        return String.valueOf(principal);
    }

    /** The index key of a platform user's sessions. */
    public static String forUser(String tenantId, String userId) {
        return tenantId + ":" + userId;
    }

    /** The index key of the user signed in to {@code session}, or {@code null} when none is. */
    static String forSession(Session session) {
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context instanceof SecurityContext securityContext) {
            Authentication authentication = securityContext.getAuthentication();
            if (authentication != null && authentication.getPrincipal() != null) {
                return of(authentication.getPrincipal());
            }
        }
        return null;
    }
}
