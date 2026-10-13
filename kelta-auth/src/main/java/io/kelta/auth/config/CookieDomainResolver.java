package io.kelta.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves the cookie {@code Domain} and {@code Secure} attributes for the auth cookies
 * (the {@code kelta_session} SSO cookie and the HTTP session cookie) from the request.
 * Centralised here so it stays consistent across all places that emit auth cookies.
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

    private final SecureMode secureMode;

    /** {@code Secure} follows the scheme the browser used ({@link SecureMode#AUTO}). */
    public CookieDomainResolver() {
        this(SecureMode.AUTO);
    }

    @Autowired
    public CookieDomainResolver(AuthProperties properties) {
        this(SecureMode.parse(properties.getSession().getCookieSecure()));
    }

    public CookieDomainResolver(SecureMode secureMode) {
        this.secureMode = secureMode;
    }

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
     * Whether a cookie written in reply to {@code request} is {@code Secure}, per
     * {@code kelta.auth.session.cookie-secure}:
     * <ul>
     *   <li>{@code always} / {@code never} — fixed.</li>
     *   <li>{@code auto} (default) — the scheme the browser used. TLS usually ends at the
     *       ingress, so the request that reaches this service is plain http; the proxy's
     *       {@code X-Forwarded-Proto} (first value — the client-facing hop when several proxies
     *       append) or RFC 7239 {@code Forwarded: proto=} says what the browser used. Without
     *       either header the request's own scheme decides ({@code request.isSecure()}).</li>
     * </ul>
     * So production behind a TLS-terminating proxy gets {@code Secure}; local development on
     * {@code http://*.localhost} and a plain-http install on a LAN host name do not — a browser
     * ignores a {@code Secure} cookie set over http, which would make signing in impossible there.
     * A proxy that terminates TLS without sending either header needs {@code always}.
     *
     * <p>The header is taken from any peer: it only decides the attribute of the cookie in the
     * reply to that same request, so a client that forges it only weakens its own cookie.
     */
    public boolean secureForRequest(HttpServletRequest request) {
        return switch (secureMode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> request == null || browserUsedHttps(request);
        };
    }

    static boolean browserUsedHttps(HttpServletRequest request) {
        String forwarded = forwardedProto(request);
        if (forwarded != null) {
            return "https".equals(forwarded);
        }
        return request.isSecure();
    }

    /** The client-facing scheme a proxy reported, lower-cased, or {@code null} when none did. */
    static String forwardedProto(HttpServletRequest request) {
        String xfp = firstValue(request.getHeader("X-Forwarded-Proto"));
        if (xfp != null) {
            return xfp;
        }
        String forwarded = request.getHeader("Forwarded");
        if (forwarded == null) {
            return null;
        }
        // First element (the hop nearest the client), then its proto= pair.
        String element = forwarded.split(",", 2)[0];
        for (String pair : element.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "proto".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                return firstValue(pair.substring(eq + 1).replace("\"", ""));
            }
        }
        return null;
    }

    private static String firstValue(String header) {
        if (header == null) {
            return null;
        }
        String first = header.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
        return first.isEmpty() ? null : first;
    }

    /** {@code kelta.auth.session.cookie-secure}. */
    public enum SecureMode {
        AUTO, ALWAYS, NEVER;

        public static SecureMode parse(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            for (SecureMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "kelta.auth.session.cookie-secure must be auto, always or never, not '" + value + "'");
        }
    }
}
