package io.kelta.auth.session;

import io.kelta.auth.config.CookieDomainResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.List;

/**
 * Writes the HTTP session cookie: {@code HttpOnly}, path {@code /}, no {@code Max-Age} (it ends
 * with the browser session; the server-side session has its own idle timeout), and
 * {@code Secure} plus the configured {@code SameSite} on every host except loopback.
 *
 * <p>{@code Secure} follows {@link CookieDomainResolver#secureForRequest}, the rule the SSO
 * cookie already uses: TLS ends at the ingress, so the request itself is plain http even in
 * production. Loopback hosts serve local development over plain http, so there the cookie is
 * not {@code Secure}, and {@code SameSite=None} is left off because browsers reject it without
 * {@code Secure}.
 *
 * <p>The cookie has no {@code Domain}: only the host that serves the sign-in pages reads it, and
 * a parent-domain cookie would hand the session id to every sibling host.
 */
public final class SessionCookieSerializer implements CookieSerializer {

    private static final List<String> SAME_SITE_VALUES = List.of("Strict", "Lax", "None");

    private final DefaultCookieSerializer secureCookie;
    private final DefaultCookieSerializer loopbackCookie;
    private final CookieDomainResolver hosts;

    public SessionCookieSerializer(String cookieName, String sameSite, CookieDomainResolver hosts) {
        String value = sameSiteValue(sameSite);
        this.secureCookie = cookie(cookieName, true, value);
        this.loopbackCookie = cookie(cookieName, false, "None".equals(value) ? null : value);
        this.hosts = hosts;
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        boolean secure = hosts.secureForRequest(cookieValue.getRequest());
        (secure ? secureCookie : loopbackCookie).writeCookieValue(cookieValue);
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
