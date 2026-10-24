package io.kelta.gateway.ratelimit;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.JwtAuthenticationFilter;
import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RateLimitConfig;
import io.kelta.jsonapi.JsonApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Global filter that enforces per-tenant rate limiting based on governor limits.
 * Runs after authentication (order -50, after -100) but before routing decisions.
 *
 * This filter:
 * - Extracts the tenant ID from the request context (set by TenantResolutionFilter)
 * - Looks up the tenant's governor limits (apiCallsPerDay) from the cache
 * - Calls RedisRateLimiter to check if the request is allowed within the per-minute window
 * - Returns 429 Too Many Requests if the rate limit is exceeded
 * - Adds rate limit headers to the response (X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset)
 * - Includes Retry-After header when rate limit is exceeded
 *
 * Rate limits are derived from the tenant's governor limit (apiCallsPerDay)
 * divided into per-minute windows: requestsPerMinute = apiCallsPerDay / 1440.
 *
 * Validates: Requirements 8.2, 8.4, 8.5
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * Fraction of the tenant window any single member may consume.
     *
     * <p>Deliberately close to 1: the tenant window is derived from the tenant's
     * <em>whole</em> governor budget, and a single admin, bulk import, or
     * integration PAT legitimately <em>is</em> most of a tenant. A half share was
     * tried first and immediately halved real single-user throughput — the e2e
     * suite, which is one admin driving one tenant, went from comfortably under
     * the cap to failing on 429. This window's job is to stop one runaway or
     * stolen credential consuming the <em>entire</em> budget, not to divide it
     * fairly; tenants with many members should tighten it.
     */
    static final double DEFAULT_USER_SHARE = 0.9;

    private final RedisRateLimiter rateLimiter;
    private final GatewayCacheManager cacheManager;
    private final GatewayMetrics metrics;
    private final RateLimitExemptionService exemptionService;
    private final double userShare;

    /**
     * Creates a new RateLimitFilter.
     *
     * @param rateLimiter      the Redis-based rate limiter
     * @param cacheManager     the gateway cache manager
     * @param metrics          the gateway metrics service
     * @param exemptionService decides whether the caller's IP bypasses limiting
     * @param userShare        fraction of the tenant window one member may consume;
     *                         out-of-range values fall back to the default rather
     *                         than disabling the limit by accident
     */
    public RateLimitFilter(RedisRateLimiter rateLimiter,
                          GatewayCacheManager cacheManager,
                          GatewayMetrics metrics,
                          RateLimitExemptionService exemptionService,
                          @Value("${kelta.gateway.rate-limit.user-share:" + DEFAULT_USER_SHARE + "}")
                          double userShare) {
        this.rateLimiter = rateLimiter;
        this.cacheManager = cacheManager;
        this.metrics = metrics;
        this.exemptionService = exemptionService;
        if (userShare <= 0 || userShare > 1) {
            log.warn("Ignoring out-of-range kelta.gateway.rate-limit.user-share={} (expected 0 < share <= 1); using {}",
                    userShare, DEFAULT_USER_SHARE);
            this.userShare = DEFAULT_USER_SHARE;
        } else {
            this.userShare = userShare;
        }
        log.info("RateLimitFilter initialized: per-user share of the tenant window = {}", this.userShare);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get the authenticated principal (set by JwtAuthenticationFilter)
        GatewayPrincipal principal = JwtAuthenticationFilter.getPrincipal(exchange);

        // If no principal, skip rate limiting (authentication filter will handle this)
        if (principal == null) {
            log.debug("No principal found, skipping rate limiting for path: {}",
                exchange.getRequest().getPath().value());
            return chain.filter(exchange);
        }

        // Get the tenant ID from the request context
        String tenantId = TenantResolutionFilter.getTenantId(exchange);
        if (tenantId == null || tenantId.isBlank()) {
            log.debug("No tenant context, skipping rate limiting for path: {}",
                exchange.getRequest().getPath().value());
            return chain.filter(exchange);
        }

        // Trusted infrastructure ranges bypass the tenant window entirely, so a
        // load test or in-cluster caller cannot consume a tenant's whole budget.
        if (exemptionService.isExempt(exchange)) {
            log.debug("Client IP is rate-limit exempt, skipping for tenant: {}", tenantId);
            return chain.filter(exchange);
        }

        // Get the rate limit config from the tenant's governor limits
        RateLimitConfig config = cacheManager.getRateLimitForTenant(tenantId);
        String rateLimitKey = tenantId;

        log.debug("Checking rate limit for tenant: {}, principal: {}, limit: {} requests per {}",
            tenantId, principal.getUsername(), config.getRequestsPerWindow(), config.getWindowDuration());

        // Per-user window FIRST, so one member cannot spend the tenant's budget
        // just by being rejected — a request that fails the user check must not
        // also increment the shared tenant counter.
        return checkPerUser(tenantId, principal, config)
            .flatMap(userResult -> {
                if (!userResult.isAllowed()) {
                    String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
                    log.warn("Per-user rate limit exceeded for tenant: {}, principal: {}, retry after: {}",
                        tenantId, principal.getUsername(), userResult.getRetryAfter());
                    metrics.recordRateLimitExceeded(tenantSlug);
                    return tooManyRequests(exchange, config, userResult);
                }
                return checkPerTenant(exchange, chain, tenantId, principal, config, rateLimitKey);
            });
    }

    /**
     * Per-user fixed window, keyed {@code tenantId + user:<principal>}.
     *
     * <p>The tenant window alone is not enough: it is shared, so a single member
     * (or a stolen PAT) hammering an endpoint locks out <em>everyone</em> in the
     * tenant — the exact shape of the 2026-07-11 lockout incident. The per-user
     * window bounds any one member's share of it.
     *
     * <p>The budget is {@code user-share} of the tenant's window (default half),
     * floored at 1. Deriving it from the tenant governor rather than looking up
     * the member's plan entitlement keeps this off the cross-service path; an
     * entitlement-driven per-member budget is a follow-up, and the seam for it is
     * this method.
     *
     * <p>A share of {@code >= 1.0} makes the user budget equal the tenant budget,
     * which is the pre-slice-7 behaviour (effectively off).
     *
     * <p>The principal's username is the key: kelta-auth JWTs and PATs both carry
     * the member's email there, so a member cannot double their budget by
     * switching credential type.
     */
    private Mono<RateLimitResult> checkPerUser(String tenantId, GatewayPrincipal principal,
                                               RateLimitConfig config) {
        long userLimit = Math.max(1, Math.round(config.getRequestsPerWindow() * userShare));
        if (userLimit >= config.getRequestsPerWindow()) {
            // Nothing to enforce beyond the tenant window — skip the Redis call.
            return Mono.just(RateLimitResult.allowed(userLimit));
        }
        return rateLimiter.checkRateLimit(tenantId, "user:" + principal.getUsername(),
                new RateLimitConfig((int) userLimit, config.getWindowDuration()));
    }

    private Mono<Void> checkPerTenant(ServerWebExchange exchange, GatewayFilterChain chain,
                                      String tenantId, GatewayPrincipal principal,
                                      RateLimitConfig config, String rateLimitKey) {
        // Check rate limit keyed by tenant (all users in a tenant share the limit)
        return rateLimiter.checkRateLimit(rateLimitKey, "tenant", config)
            .flatMap(result -> {
                String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
                if (result.isAllowed()) {
                    // Request allowed - add rate limit headers and continue
                    log.debug("Rate limit check passed for tenant: {}, remaining: {}",
                        tenantId, result.getRemainingRequests());

                    addRateLimitHeaders(exchange, config, result);

                    // Record rate limit remaining ratio metric
                    metrics.recordRateLimitRemaining(tenantSlug,
                            result.getRemainingRequests(), config.getRequestsPerWindow());

                    // Increment daily API call counter (fire-and-forget for the Governor Limits page)
                    rateLimiter.incrementDailyCounter(tenantId).subscribe();

                    return chain.filter(exchange);
                } else {
                    // Rate limit exceeded - return 429
                    log.warn("Rate limit exceeded for tenant: {}, principal: {}, retry after: {}",
                        tenantId, principal.getUsername(), result.getRetryAfter());

                    metrics.recordRateLimitExceeded(tenantSlug);

                    return tooManyRequests(exchange, config, result);
                }
            });
    }

    /**
     * Adds rate limit headers to the response.
     *
     * @param exchange the server web exchange
     * @param config the rate limit configuration
     * @param result the rate limit check result
     */
    private void addRateLimitHeaders(ServerWebExchange exchange, RateLimitConfig config, RateLimitResult result) {
        ResponseHelpers.applyHeaderIfWritable(exchange.getResponse(), () -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.add("X-RateLimit-Limit", String.valueOf(config.getRequestsPerWindow()));
            headers.add("X-RateLimit-Remaining", String.valueOf(result.getRemainingRequests()));
            long resetTimestamp = Instant.now().plus(config.getWindowDuration()).getEpochSecond();
            headers.add("X-RateLimit-Reset", String.valueOf(resetTimestamp));
        });
    }

    /**
     * Returns a 429 {@code RATE_LIMIT_EXCEEDED} JSON:API error with rate limit headers,
     * the same envelope {@code GlobalErrorHandler} renders.
     *
     * @param exchange the server web exchange
     * @param config the rate limit configuration
     * @param result the rate limit check result
     * @return a Mono that completes the response
     */
    private Mono<Void> tooManyRequests(ServerWebExchange exchange, RateLimitConfig config, RateLimitResult result) {
        long retryAfterSeconds = result.getRetryAfter().getSeconds();

        ResponseHelpers.applyHeaderIfWritable(exchange.getResponse(), () -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.add("X-RateLimit-Limit", String.valueOf(config.getRequestsPerWindow()));
            headers.add("X-RateLimit-Remaining", "0");
            long resetTimestamp = Instant.now().plus(config.getWindowDuration()).getEpochSecond();
            headers.add("X-RateLimit-Reset", String.valueOf(resetTimestamp));
            headers.add("Retry-After", String.valueOf(retryAfterSeconds));
        });

        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        JsonApiError error = new JsonApiError(
            String.valueOf(status.value()),
            "RATE_LIMIT_EXCEEDED",
            "RATE_LIMIT_EXCEEDED",
            "Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds."
        );
        error.setMeta(Map.of("requestId", ResponseHelpers.correlationId(exchange)));
        return ResponseHelpers.writeJsonApiError(exchange, status, error);
    }

    @Override
    public int getOrder() {
        return -50; // Run after authentication (-100) but before routing (0)
    }
}
