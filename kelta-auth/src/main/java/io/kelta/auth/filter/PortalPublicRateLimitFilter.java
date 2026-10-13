package io.kelta.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-IP rate limiting for kelta-auth's public portal endpoints
 * (consumer-alerting slice 7) and its unauthenticated federation endpoints.
 *
 * <p><b>Why this is not in the gateway.</b> The obvious home for a per-IP budget
 * on {@code /portal/api/signup} is the gateway's {@code IpRateLimitFilter} — and
 * the slice spec put it there. But {@code /portal/**} is served by kelta-auth,
 * which has its own ingress; that traffic never passes through the gateway. A
 * gateway entry for these paths would match nothing while reading, in config and
 * in review, as though signup were rate limited. The control has to live where
 * the request actually lands.
 *
 * <p><b>Fixed window in Redis, TTL set only when the window is new.</b> Same
 * shape as the gateway's limiter, and the TTL rule is the load-bearing part:
 * refreshing the TTL on every request converts the fixed window into an
 * idle-expiry, so under sustained traffic the counter never resets and the caller
 * is locked out permanently — each rejected request extends its own lockout. That
 * regression cost a production incident on 2026-07-11; {@code windowTtlIsNotRefreshed}
 * in the test guards it here. The gateway keeps a parallel copy because it is
 * reactive and this one is servlet-blocking; the two must stay behaviourally
 * identical.
 *
 * <p><b>Fails open</b> if Redis is unreachable, matching the gateway. Losing the
 * cache must not take signup down, and the bot challenge and per-email budget
 * still apply.
 *
 * <p>Off unless {@code kelta.auth.rate-limit.ip-paths} is non-empty; it ships with
 * budgets for the three public portal paths.
 *
 * <p><b>Federation endpoints.</b> The unauthenticated SSO endpoints — OIDC initiation
 * and callback, SAML AuthnRequest, metadata, assertion consumer and Single Logout — each
 * resolve a registration through a worker lookup. They are budgeted from a separate
 * property, {@code kelta.auth.rate-limit.federation-paths}, so overriding the portal
 * list does not silently drop them. The login page counts too, but only a {@code GET
 * /login} that carries {@code tenant} or {@code idp_hint} (the parameters that trigger
 * the lookups), in its own bucket ({@code kelta.auth.rate-limit.login-page-limit});
 * the password form POST to {@code /login} is never counted here. Every prefix is its
 * own bucket, so a burst on one federation endpoint does not spend another's budget,
 * nor any portal budget.
 *
 * <p><b>Password reset.</b> {@code /forgot-password}, {@code /reset-password} and the JSON
 * {@code /auth/password/reset-request} and {@code /auth/password/reset} are budgeted from
 * {@code kelta.auth.rate-limit.password-reset-paths}, likewise separate.
 *
 * <p><b>Client IP.</b> With {@code kelta.security.trusted-proxies} set (CIDRs or bare
 * addresses), the same rule as the gateway's {@code ClientIpResolver}: forwarding headers
 * count only when the socket peer is a trusted proxy, and the client is the right-most
 * untrusted {@code X-Forwarded-For} hop. Otherwise the legacy
 * {@code kelta.auth.rate-limit.trusted-proxy-count} hop arithmetic applies.
 */
@Component
@Order(-150)
public class PortalPublicRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PortalPublicRateLimitFilter.class);

    static final int DEFAULT_REQUESTS_PER_WINDOW = 60;
    static final Duration WINDOW = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "authratelimit:ip:";

    /**
     * A human signs up once and a magic-link request that succeeded needs no
     * retry, so these are tight. The challenge endpoint is looser because a
     * legitimate client fetches one per form render (and may retry on reload).
     *
     * <p>{@code /portal/login/request} — the Thymeleaf form — is listed alongside
     * the JSON API because it sends the <em>same</em> email through the same
     * service. Limiting only the headless path would leave the identical abuse
     * open one URL over. Its prefix necessarily also covers {@code /portal/login}
     * and {@code /portal/login/verify}; that is fine — clicking a link you were
     * emailed is well inside a budget of 10 a minute.
     */
    public static final String DEFAULT_IP_PATHS =
            "/portal/api/signup=5,/portal/api/login/request=10,/portal/api/challenge=30,"
            + "/portal/login=10";

    /**
     * One federated sign-in touches each of these once (initiation, then the IdP's
     * callback), so a person stays far below the budget. It is looser than the portal
     * budgets because SSO is the primary sign-in for the tenants that enable it, and a
     * whole office can share one egress IP. Each entry is its own bucket.
     *
     * <p>Trailing slashes are deliberate: {@code /oauth2/authorization/} must not match the
     * authorization server's own {@code /oauth2/authorize}. {@code /saml2/metadata} has none
     * because Spring also serves the aggregate {@code /saml2/metadata}; Spring's metadata
     * resolver also answers the older {@code /saml2/service-provider-metadata/} form.
     */
    public static final String DEFAULT_FEDERATION_PATHS =
            "/oauth2/authorization/=60,/login/oauth2/code/=60,/saml2/authenticate/=60,"
            + "/saml2/metadata=60,/saml2/service-provider-metadata/=60,/login/saml2/sso/=60,"
            + "/logout/saml2/slo/=60";

    /**
     * The signed-out password reset (the pages and their JSON equivalents). Requesting a link
     * mails the address, so it is tight; the reset page is opened from the email and then
     * posted, perhaps more than once if the password does not meet the tenant's policy. The
     * longest prefix wins, so {@code /auth/password/reset} does not count the request endpoint.
     */
    public static final String DEFAULT_PASSWORD_RESET_PATHS =
            "/auth/password/reset-request=5,/auth/password/reset=10,/forgot-password=10,/reset-password=20";

    /** Budget for {@code GET /login} with {@code tenant}/{@code idp_hint}; 0 disables it. */
    static final int DEFAULT_LOGIN_PAGE_LIMIT = 60;

    /** Bucket name for the login page rule — not a path prefix, so it never collides. */
    static final String LOGIN_PAGE_BUCKET = "/login?federation";

    private static final String LOGIN_PAGE_PATH = "/login";
    private static final List<String> LOGIN_PAGE_LOOKUP_PARAMS = List.of("tenant", "idp_hint");

    /** See {@code RedisRateLimiter} in kelta-gateway — same semantics. */
    private static final RedisScript<Long> INCREMENT_WINDOW_SCRIPT = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Integer> pathBudgets;
    private final int loginPageLimit;
    private final int trustedProxyCount;
    private final List<Cidr> trustedProxies;

    public PortalPublicRateLimitFilter(
            StringRedisTemplate redisTemplate,
            @Value("${kelta.auth.rate-limit.ip-paths:" + DEFAULT_IP_PATHS + "}") List<String> ipPaths,
            @Value("${kelta.auth.rate-limit.federation-paths:" + DEFAULT_FEDERATION_PATHS + "}")
            List<String> federationPaths,
            @Value("${kelta.auth.rate-limit.password-reset-paths:" + DEFAULT_PASSWORD_RESET_PATHS + "}")
            List<String> passwordResetPaths,
            @Value("${kelta.auth.rate-limit.login-page-limit:" + DEFAULT_LOGIN_PAGE_LIMIT + "}")
            int loginPageLimit,
            @Value("${kelta.auth.rate-limit.trusted-proxy-count:1}") int trustedProxyCount,
            @Value("${kelta.security.trusted-proxies:}") List<String> trustedProxies) {
        this.redisTemplate = redisTemplate;
        this.pathBudgets = parsePathBudgets(concat(concat(ipPaths, federationPaths), passwordResetPaths));
        this.loginPageLimit = Math.max(0, loginPageLimit);
        this.trustedProxyCount = Math.max(0, trustedProxyCount);
        this.trustedProxies = parseTrustedProxies(trustedProxies);
        if (this.trustedProxies.isEmpty()) {
            log.info("PortalPublicRateLimitFilter initialized: {} path(s) {} and login page {}/window "
                            + "over a {}s window, {} trusted proxy hop(s) (kelta.security.trusted-proxies unset)",
                    pathBudgets.size(), pathBudgets, this.loginPageLimit, WINDOW.toSeconds(),
                    this.trustedProxyCount);
        } else {
            log.info("PortalPublicRateLimitFilter initialized: {} path(s) {} and login page {}/window "
                            + "over a {}s window, trusted-proxy mode with {} trusted range(s)",
                    pathBudgets.size(), pathBudgets, this.loginPageLimit, WINDOW.toSeconds(),
                    this.trustedProxies.size());
        }
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>();
        if (first != null) {
            all.addAll(first);
        }
        if (second != null) {
            all.addAll(second);
        }
        return all;
    }

    private static List<Cidr> parseTrustedProxies(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        List<Cidr> parsed = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Cidr cidr = Cidr.parse(entry);
            if (cidr == null) {
                log.warn("Ignoring invalid kelta.security.trusted-proxies entry: '{}'", entry);
                continue;
            }
            parsed.add(cidr);
        }
        return List.copyOf(parsed);
    }

    /** {@code <prefix>=<limit>} entries, longest prefix first; bad entries skipped. */
    static Map<String, Integer> parsePathBudgets(List<String> entries) {
        if (entries == null) {
            return Map.of();
        }
        Map<String, Integer> parsed = new LinkedHashMap<>();
        entries.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .sorted((a, b) -> Integer.compare(pathOf(b).length(), pathOf(a).length()))
                .forEach(entry -> {
                    String path = pathOf(entry);
                    if (path.isEmpty()) {
                        log.warn("Ignoring rate-limit path entry with empty path: '{}'", entry);
                        return;
                    }
                    int limit = DEFAULT_REQUESTS_PER_WINDOW;
                    int eq = entry.indexOf('=');
                    if (eq >= 0) {
                        try {
                            limit = Integer.parseInt(entry.substring(eq + 1).trim());
                        } catch (NumberFormatException e) {
                            log.warn("Invalid limit in rate-limit path entry '{}'; using default {}",
                                    entry, DEFAULT_REQUESTS_PER_WINDOW);
                        }
                    }
                    if (limit <= 0) {
                        log.warn("Ignoring non-positive limit in rate-limit path entry '{}'", entry);
                        return;
                    }
                    parsed.put(path, limit);
                });
        return Collections.unmodifiableMap(parsed);
    }

    private static String pathOf(String entry) {
        int eq = entry.indexOf('=');
        return (eq >= 0 ? entry.substring(0, eq) : entry).trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String matched = matchPath(pathForMatching(request));
        int limit;
        if (matched != null) {
            limit = pathBudgets.getOrDefault(matched, DEFAULT_REQUESTS_PER_WINDOW);
        } else if (loginPageLimit > 0 && isFederatedLoginPage(request)) {
            matched = LOGIN_PAGE_BUCKET;
            limit = loginPageLimit;
        } else {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        String key = KEY_PREFIX + matched + ":" + clientIp;
        long count = increment(key);

        if (count > limit) {
            log.warn("Portal rate limit exceeded for {} on {} (bucket {}): {} > {} per {}s",
                    clientIp, request.getRequestURI(), matched, count, limit, WINDOW.toSeconds());
            tooManyRequests(request, response, retryAfterSeconds(key));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The window's real remaining TTL. Reporting the full window instead would
     * tell a well-behaved client to back off far longer than necessary — and a
     * misbehaving one ignores the header anyway.
     */
    private long retryAfterSeconds(String key) {
        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl != null && ttl > 0 ? ttl : WINDOW.toSeconds();
        } catch (RuntimeException e) {
            return WINDOW.toSeconds();
        }
    }

    /**
     * {@code GET /login} with a non-blank {@code tenant} or {@code idp_hint} — the requests
     * on which {@code LoginController} resolves registrations through the worker. Only GET
     * is considered, so the password form POST is never counted and its body is never read
     * here.
     */
    static boolean isFederatedLoginPage(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !LOGIN_PAGE_PATH.equals(pathForMatching(request))) {
            return false;
        }
        for (String param : LOGIN_PAGE_LOOKUP_PARAMS) {
            String value = request.getParameter(param);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The decoded, normalized path the servlet container mapped — what Spring's own
     * request matchers see — so a percent-encoded or path-parameter variant of a limited
     * path is counted like the plain one. Falls back to the raw request URI when the
     * container exposes no servlet path.
     */
    static String pathForMatching(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            String pathInfo = request.getPathInfo();
            return pathInfo == null ? servletPath : servletPath + pathInfo;
        }
        return request.getRequestURI();
    }

    /** Longest configured prefix matching {@code path}, or null if unlimited. */
    String matchPath(String path) {
        if (path == null) {
            return null;
        }
        for (String prefix : pathBudgets.keySet()) {
            if (path.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Counts the request. Returns 0 (i.e. always under the limit) when Redis is
     * unavailable — see the fail-open note on the class.
     */
    private long increment(String key) {
        try {
            Long count = redisTemplate.execute(INCREMENT_WINDOW_SCRIPT, List.of(key),
                    String.valueOf(WINDOW.toSeconds()));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for portal rate limiting — allowing request: {}",
                    e.getMessage());
            return 0;
        }
    }

    /**
     * Resolves the client address for the limiter key.
     *
     * <p>Takes the hop the <b>trusted</b> proxy appended, counting from the right,
     * rather than the leftmost {@code X-Forwarded-For} entry. The leftmost entry
     * is whatever the client sent, so honouring it would let anyone mint a fresh
     * bucket per request by varying one header — the limiter would be decorative.
     * With the default of one trusted proxy that means the last entry.
     */
    String resolveClientIp(HttpServletRequest request) {
        if (!trustedProxies.isEmpty()) {
            String resolved = resolveThroughTrustedProxies(request);
            return resolved == null ? "unknown" : resolved;
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header != null && !header.isBlank() && trustedProxyCount > 0) {
            String[] hops = header.split(",");
            int index = hops.length - trustedProxyCount;
            if (index < 0) {
                // Fewer hops than expected: the leftmost is the most trustworthy
                // one available, but it may be client-supplied — never index
                // past the start into a spoofable position.
                index = 0;
            }
            String hop = hops[index].trim();
            if (!hop.isEmpty()) {
                return hop;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /**
     * Trusted-proxy rule, kept behaviourally identical to the gateway's
     * {@code ClientIpResolver}: an untrusted peer is the client and its headers are
     * ignored; from a trusted peer, walk {@code X-Forwarded-For} right to left past
     * trusted hops and return the first untrusted one (the left-most if all are trusted).
     * A hop that is not an address literal stops the walk at the nearest verified hop.
     * No {@code X-Forwarded-For} → {@code X-Real-IP} if it is a literal, else the peer.
     */
    private String resolveThroughTrustedProxies(HttpServletRequest request) {
        String peer = normalizeIp(request.getRemoteAddr());
        if (peer == null || !isTrustedProxy(peer)) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        var headers = request.getHeaders("X-Forwarded-For");
        while (headers != null && headers.hasMoreElements()) {
            String value = headers.nextElement();
            if (value == null) {
                continue;
            }
            for (String raw : value.split(",")) {
                String hop = normalizeIp(raw);
                hops.add(hop == null ? "" : hop);
            }
        }
        if (hops.size() == 1 && hops.get(0).isEmpty()) {
            hops.clear();
        }
        if (hops.isEmpty()) {
            String realIp = normalizeIp(request.getHeader("X-Real-IP"));
            return Cidr.isLiteral(realIp) ? realIp : peer;
        }
        String nearestVerified = peer;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!Cidr.isLiteral(hop)) {
                return nearestVerified;
            }
            if (!isTrustedProxy(hop)) {
                return hop;
            }
            nearestVerified = hop;
        }
        return nearestVerified;
    }

    private boolean isTrustedProxy(String ip) {
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.trim();
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            if (close > 0) {
                ip = ip.substring(1, close);
            }
        }
        int pct = ip.indexOf('%');
        if (pct >= 0) {
            ip = ip.substring(0, pct);
        }
        return ip.isEmpty() ? null : ip;
    }

    /**
     * Minimal CIDR block — a copy of the gateway's {@code CidrBlock} (kelta-auth does not
     * depend on kelta-gateway). Parses literals only via {@link InetAddress#ofLiteral}, so
     * a hostname in a forwarded header is never resolved via DNS.
     */
    record Cidr(byte[] network, int prefixLen) {

        /** {@code a.b.c.d/len}, an IPv6 equivalent, or a bare address (a single host). */
        static Cidr parse(String entry) {
            String s = entry.trim();
            int slash = s.indexOf('/');
            try {
                byte[] network = InetAddress.ofLiteral(slash < 0 ? s : s.substring(0, slash)).getAddress();
                int prefixLen = slash < 0 ? network.length * 8 : Integer.parseInt(s.substring(slash + 1).trim());
                if (prefixLen < 0 || prefixLen > network.length * 8) {
                    return null;
                }
                return new Cidr(network, prefixLen);
            } catch (RuntimeException e) {
                return null;
            }
        }

        static boolean isLiteral(String ip) {
            if (ip == null || ip.isEmpty()) {
                return false;
            }
            try {
                InetAddress.ofLiteral(ip);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        boolean contains(String ip) {
            byte[] target;
            try {
                target = InetAddress.ofLiteral(ip).getAddress();
            } catch (RuntimeException e) {
                return false;
            }
            if (target.length != network.length) {
                return false;
            }
            int fullBytes = prefixLen / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != target[i]) {
                    return false;
                }
            }
            int remBits = prefixLen % 8;
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                return (network[fullBytes] & mask) == (target[fullBytes] & mask);
            }
            return true;
        }
    }

    private void tooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                 long retryAfterSeconds) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));
        response.getOutputStream().write(String.format(
                "{\"error\":{\"status\":429,\"code\":\"TOO_MANY_REQUESTS\","
                        + "\"message\":\"Rate limit exceeded. Try again later.\",\"path\":\"%s\"}}",
                request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }
}
