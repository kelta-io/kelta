package io.kelta.auth.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.io.IOException;

/**
 * Recovers a login form that outlived its server session.
 *
 * <p>The login page is reached mid-flow: {@code /oauth2/authorize} saves itself in the HTTP
 * session and redirects to {@code /login}. Leave that page open past the session timeout and
 * the session — with its CSRF token and the saved authorize request — is gone, so submitting
 * the form used to fail with a 403 and leave the user stranded on kelta-auth with no way back
 * to the app short of navigating back to it by hand.
 *
 * <p>The login page therefore carries the pending authorize URL in a hidden
 * {@value #AUTHORIZE_URL_PARAM} field. On a CSRF failure of the login POST this handler
 * restarts that authorize request, which re-saves itself and lands on a fresh login form;
 * {@value #SESSION_EXPIRED_ATTR} makes that form say why. Only a same-origin
 * {@code /oauth2/authorize} URL is followed, so the field cannot be used as an open redirect.
 * Every other access-denied case keeps the default 403.
 */
public final class ExpiredLoginFormHandler implements AccessDeniedHandler {

    public static final String AUTHORIZE_URL_PARAM = "authorize_url";
    public static final String SESSION_EXPIRED_ATTR = "kelta.login.expired";

    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final Logger log = LoggerFactory.getLogger(ExpiredLoginFormHandler.class);

    private final AccessDeniedHandler delegate;

    public ExpiredLoginFormHandler() {
        this(new AccessDeniedHandlerImpl());
    }

    ExpiredLoginFormHandler(AccessDeniedHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (!(accessDeniedException instanceof CsrfException) || !isLoginPost(request)) {
            delegate.handle(request, response, accessDeniedException);
            return;
        }
        String authorizeUrl = request.getParameter(AUTHORIZE_URL_PARAM);
        request.getSession(true).setAttribute(SESSION_EXPIRED_ATTR, Boolean.TRUE);
        if (isAuthorizeUrl(authorizeUrl)) {
            log.info("Login form submitted after its session expired; restarting the authorization request");
            response.sendRedirect(authorizeUrl);
        } else {
            log.info("Login form submitted after its session expired; no authorization request to resume");
            response.sendRedirect(request.getContextPath() + "/login");
        }
    }

    /**
     * The path and query of the authorize request saved in this session, if the user is in
     * the middle of one — the value the login page carries in {@value #AUTHORIZE_URL_PARAM}.
     */
    public static String pendingAuthorizeUrl(SavedRequest savedRequest) {
        if (!(savedRequest instanceof DefaultSavedRequest saved)
                || !"GET".equals(saved.getMethod())
                || saved.getQueryString() == null) {
            return null;
        }
        String url = saved.getRequestURI() + "?" + saved.getQueryString();
        return isAuthorizeUrl(url) ? url : null;
    }

    static boolean isAuthorizeUrl(String url) {
        return url != null
                && url.startsWith(AUTHORIZE_PATH + "?")
                && url.chars().noneMatch(c -> c == '\r' || c == '\n' || c == '\\');
    }

    private static boolean isLoginPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/login".equals(request.getRequestURI().substring(request.getContextPath().length()));
    }
}
