package io.kelta.gateway.filter;

import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.geo.ClientIpResolver;
import io.kelta.gateway.ratelimit.RateLimitExemptionService;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global filter applying per-IP rate limiting to unauthenticated endpoints — the
 * paths {@code RateLimitFilter} cannot cover, because that one keys on an
 * authenticated principal and returns early when there is none.
 *
 * <p><b>Redis-backed (consumer-alerting slice 7).</b> This used to count in
 * memory, which meant every gateway replica held its own bucket and the fleet
 * granted N× the configured budget — worthless for a public signup endpoint,
 * which is exactly what the budget exists to protect. The window now lives in
 * Redis via the shared {@link RedisRateLimiter#checkWindow} so all replicas
 * agree. It fails open when Redis is unreachable, matching the tenant limiter.
 *
 * <p>Limited paths and their budgets come from
 * {@code kelta.gateway.rate-limit.ip-paths}, a comma-separated list of
 * {@code <path-prefix>=<requests-per-window>} entries. Matching is by
 * <b>longest prefix</b>, so {@code /api/modules/webhooks} covers
 * {@code /api/modules/webhooks/{tenantId}/{moduleId}} while a more specific entry can
 * still override a broader one.
 *
 * <p>Each matched prefix gets its <b>own</b> counter per IP, so a burst against
 * one public endpoint cannot exhaust another endpoint's budget for that client.
 *
 * <p>IPs inside {@code kelta.gateway.rate-limit.exempt-cidrs} skip the check
 * entirely — see {@link RateLimitExemptionService}.
 *
 * <p>Returns 429 with a {@code Retry-After} reflecting the true remaining window.
 * Order: -150 (runs before JwtAuthenticationFilter at -100).
 */
@Component
public class IpRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimitFilter.class);

    /** Fallback budget for an entry configured without an explicit limit. */
    static final int DEFAULT_REQUESTS_PER_WINDOW = 100;
    static final Duration WINDOW = Duration.ofSeconds(60);
    /** Distinct from the tenant limiter's namespace so the two never collide. */
    private static final String KEY_PREFIX = "ratelimit:ip:";

    /**
     * Default budgets. The webhook allowances are deliberately generous: a payment
     * processor retries on its own schedule from a small set of shared egress
     * IPs, so a tight bucket would drop legitimate deliveries rather than abuse.
     * Operators can exempt those ranges outright via {@code exempt-cidrs}.
     * {@code /api/modules/webhooks} carries the same shape of traffic for
     * runtime-installed modules and gets the same budget.
     *
     * <p>{@code /api/webhooks} covers unauthenticated flow webhooks
     * ({@code POST /api/webhooks/{flowId}}), where each call starts a flow run; it
     * gets the module-webhook budget. The bucket key is prefix + IP, so every flow
     * webhook called from one IP shares that one budget. Longest-prefix matching
     * keeps the inbound-mail ({@code /api/webhooks/mail}) and SES notification
     * ({@code /api/webhooks/ses}) endpoints on their own, more generous buckets.
     *
     * <p><b>The portal public paths are deliberately NOT here.</b> {@code /portal/**}
     * lives on kelta-auth, which has its own ingress and does not transit the
     * gateway — an entry for {@code /portal/api/signup} in this map would never
     * match anything and would read as protection that does not exist. Those
     * budgets are enforced by kelta-auth's own {@code PortalPublicRateLimitFilter}.
     */
    static final String DEFAULT_IP_PATHS =
            "/actuator/health=100,/api/modules/webhooks=300,/api/webhooks=300,"
                    + "/api/webhooks/mail=600,/api/webhooks/ses=600";

    /** path prefix -> requests permitted per window, longest prefix first. */
    private final Map<String, Integer> pathBudgets;

    private final ClientIpResolver clientIpResolver;
    private final RateLimitExemptionService exemptionService;
    private final RedisRateLimiter rateLimiter;

    public IpRateLimitFilter(ClientIpResolver clientIpResolver,
                             RateLimitExemptionService exemptionService,
                             RedisRateLimiter rateLimiter,
                             @Value("${kelta.gateway.rate-limit.ip-paths:" + DEFAULT_IP_PATHS + "}")
                             List<String> ipPaths) {
        this.clientIpResolver = clientIpResolver;
        this.exemptionService = exemptionService;
        this.rateLimiter = rateLimiter;
        this.pathBudgets = parsePathBudgets(ipPaths);
        log.info("IpRateLimitFilter initialized: {} per-IP limited path(s) {} over a {}s window (Redis-backed)",
                pathBudgets.size(), pathBudgets, WINDOW.toSeconds());
    }

    /**
     * Parses {@code <prefix>=<limit>} entries into a longest-prefix-first map. A
     * malformed entry is logged and skipped — a typo must neither stop the
     * gateway booting nor silently disable limiting on the valid entries.
     */
    static Map<String, Integer> parsePathBudgets(List<String> entries) {
        if (entries == null) {
            return Map.of();
        }
        Map<String, Integer> parsed = new LinkedHashMap<>();
        entries.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                // Longest prefix first, so the first match in iteration order is
                // the most specific one.
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
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        String matched = matchPath(path);
        if (matched == null) {
            return chain.filter(exchange);
        }

        // Trusted infrastructure (uptime probes, in-cluster callers, a payment
        // processor's egress ranges) bypasses the bucket entirely.
        if (exemptionService.isExempt(exchange)) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(exchange);
        int limit = pathBudgets.getOrDefault(matched, DEFAULT_REQUESTS_PER_WINDOW);

        return rateLimiter.checkWindow(KEY_PREFIX + matched + ":" + clientIp, limit, WINDOW)
                .flatMap(result -> {
                    if (result.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    log.warn("IP rate limit exceeded for {} on path {} (bucket {}), limit {} per {}s",
                            clientIp, path, matched, limit, WINDOW.toSeconds());
                    return tooManyRequests(exchange, result.getRetryAfter());
                });
    }

    /**
     * Returns the longest configured prefix matching {@code path}, or null when
     * the path is not rate limited.
     */
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
     * Resolves the client IP via the shared trust-aware resolver.
     */
    String resolveClientIp(ServerWebExchange exchange) {
        String ip = clientIpResolver.resolve(exchange);
        return ip != null ? ip : "unknown";
    }

    /**
     * Returns 429 with an honest {@code Retry-After}: the window's real remaining
     * TTL, not the full window length.
     */
    private Mono<Void> tooManyRequests(ServerWebExchange exchange, Duration retryAfter) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS)) {
            return Mono.empty();
        }
        long seconds = retryAfter == null ? WINDOW.toSeconds() : Math.max(1, retryAfter.toSeconds());
        ResponseHelpers.applyHeaderIfWritable(exchange.getResponse(),
                () -> exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(seconds)));

        String errorJson = String.format(
                "{\"error\":{\"status\":429,\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded. Try again later.\",\"path\":\"%s\"}}",
                exchange.getRequest().getPath().value()
        );

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(errorJson.getBytes()))
        );
    }

    @Override
    public int getOrder() {
        return -150; // Run before JwtAuthenticationFilter (-100)
    }
}
