package io.kelta.auth.session;

import io.kelta.auth.config.CookieDomainResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.List;

/**
 * Writes the HTTP session cookie: {@code HttpOnly}, path {@code /}, no {@code Max-Age} (it ends
 * with the browser session; the server-side session has its own idle timeout), {@code Secure}
 * when the browser reached the service over https, and the configured {@code SameSite}.
 *
 * <p>{@code Secure} follows {@link CookieDomainResolver#secureForRequest}, the rule the SSO
 * cookie uses too: the scheme the browser used (a TLS-terminating proxy's
 * {@code X-Forwarded-Proto}, else the request's own), or a fixed choice from
 * {@code kelta.auth.session.cookie-secure}. Without {@code Secure}, a configured
 * {@code SameSite=None} is written as {@code Lax}, because browsers reject {@code None} without
 * {@code Secure}; SP-initiated SAML sign-in, which needs {@code None}, therefore needs https.
 *
 * <p>The cookie has no {@code Domain}: only the host that serves the sign-in pages reads it, and
 * a parent-domain cookie would hand the session id to every sibling host.
 */
public final class SessionCookieSerializer implements CookieSerializer {

    private static final List<String> SAME_SITE_VALUES = List.of("Strict", "Lax", "None");

    private final DefaultCookieSerializer secureCookie;
    private final DefaultCookieSerializer plainHttpCookie;
    private final CookieDomainResolver hosts;

    public SessionCookieSerializer(String cookieName, String sameSite, CookieDomainResolver hosts) {
        String value = sameSiteValue(sameSite);
        this.secureCookie = cookie(cookieName, true, value);
        this.plainHttpCookie = cookie(cookieName, false, "None".equals(value) ? "Lax" : value);
        this.hosts = hosts;
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        boolean secure = hosts.secureForRequest(cookieValue.getRequest());
        (secure ? secureCookie : plainHttpCookie).writeCookieValue(cookieValue);
    }

    @Override
    public List<String> readCookieValues(HttpServletRequest request) {
        return secureCookie.readCookieValues(request);
    }

    /** The canonical {@code SameSite} value, or {@code null} (attribute left off) for blank. */
    static String sameSiteValue(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String trimmed = configured.trim();
        for (String value : SAME_SITE_VALUES) {
            if (value.equalsIgnoreCase(trimmed)) {
                return value;
            }
        }
        throw new IllegalArgumentException("kelta.auth.session.cookie-same-site must be one of "
                + SAME_SITE_VALUES + " or blank, not '" + configured + "'");
    }

    private static DefaultCookieSerializer cookie(String name, boolean secure, String sameSite) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName(name);
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite(sameSite);
        return cookie;
    }
}
