package io.kelta.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Gives a session that holds a signed-in user the long idle timeout and every other session
 * the short one.
 *
 * <p>A browser gets a session before it signs in — the login form's CSRF token, the saved
 * authorization request, the MFA step, a federation round trip — and plenty never finish. Those
 * keep the short default ({@code spring.session.timeout}) so abandoned and automated visits
 * leave the session store quickly; a session switches to the long timeout once it holds a
 * signed-in user, and back if the user is cleared from it without ending the session.
 *
 * <p>Runs inside Spring Session's filter, after the request, so it sees the outcome of sign-in.
 * The change is saved with the rest of the session when that filter commits it.
 */
public final class SignedInSessionTimeoutFilter extends OncePerRequestFilter {

    private final int signedInSeconds;
    private final int defaultSeconds;

    public SignedInSessionTimeoutFilter(Duration signedInTimeout, Duration defaultTimeout) {
        this.signedInSeconds = Math.toIntExact(signedInTimeout.toSeconds());
        this.defaultSeconds = Math.toIntExact(defaultTimeout.toSeconds());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            applyTimeout(request);
        }
    }

    private void applyTimeout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        try {
            int wanted = isSignedIn(session) ? signedInSeconds : defaultSeconds;
            if (session.getMaxInactiveInterval() != wanted) {
                session.setMaxInactiveInterval(wanted);
            }
        } catch (IllegalStateException invalidated) {
            // The session ended during this request (sign-out); nothing to adjust.
        }
    }

    private static boolean isSignedIn(HttpSession session) {
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(context instanceof SecurityContext securityContext)) {
            return false;
        }
        Authentication authentication = securityContext.getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
