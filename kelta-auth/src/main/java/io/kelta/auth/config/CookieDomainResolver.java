package io.kelta.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the cookie {@code Domain} attribute for the session cookie based on
 * the request's Host. Centralised here so it stays consistent across all places
 * that emit auth cookies.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>kelta.io and any {@code *.kelta.io} host → {@code .kelta.io} so the
 *       session is shared across platform subdomains (auth, app, api, mcp).</li>
 *   <li>Anything else (custom customer domains, localhost) → {@code null}, which
 *       leaves the cookie scoped to the exact response host. Cross-site SSO
 *       isn't possible (and we don't want it: a customer's domain should not
 *       receive a cookie scoped to {@code .kelta.io}).</li>
 * </ul>
 */
@Component
public class CookieDomainResolver {

    /** Returns the cookie Domain attribute or {@code null} for host-only cookies. */
    public String resolve(HttpServletRequest request) {
        if (request == null) return null;
        return forHost(request.getServerName());
    }

    /** Same as {@link #resolve(HttpServletRequest)} but takes the host directly. */
    public String forHost(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.toLowerCase();
        if ("kelta.io".equals(h) || h.endsWith(".kelta.io")) return ".kelta.io";
        return null;
    }

    /**
     * Returns {@code true} when cookies should set {@code Secure}. Loopback hosts —
     * {@code localhost}, any {@code *.localhost} name (RFC 6761; the local stack serves
     * kelta-auth as {@code auth.localhost}) and {@code 127.0.0.1} — are excluded so
     * developer logins over plain http still work.
     */
    public boolean secureForRequest(HttpServletRequest request) {
        if (request == null) return true;
        return !isLoopbackHost(request.getServerName());
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return "localhost".equals(h) || h.endsWith(".localhost") || "127.0.0.1".equals(h);
    }
}
