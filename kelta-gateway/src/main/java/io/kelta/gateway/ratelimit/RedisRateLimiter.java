package io.kelta.gateway.ratelimit;

import io.kelta.gateway.route.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Redis-based rate limiter implementation.
 *
 * Uses Redis INCR command to track request counts per route and principal.
 * Implements a fixed window rate limiting algorithm with automatic TTL management.
 */
@Service("keltaRedisRateLimiter")
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "ratelimit:";
    private static final String DAILY_KEY_PREFIX = "api-calls-daily:";
    private static final String PAT_USAGE_KEY_PREFIX = "pat-usage:";
    private static final Duration PAT_USAGE_TTL = Duration.ofDays(400);

    /**
     * Atomically increments the window counter and sets its TTL only when the
     * window is new (count == 1) or a previous EXPIRE was lost (TTL < 0).
     *
     * <p>The TTL must NOT be refreshed on every request: doing so turns the
     * fixed window into an idle-expiry, so under continuous traffic the counter
     * never resets and the tenant ends up permanently locked out once the
     * cumulative count crosses the limit (each rejected request re-extends the
     * TTL, sustaining the lockout indefinitely).
     */
    private static final RedisScript<Long> INCREMENT_WINDOW_SCRIPT = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """,
            Long.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public RedisRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Checks if a request is allowed based on the rate limit configuration.
     *
     * Algorithm:
     * 1. Build Redis key: "ratelimit:{routeId}:{principal}"
     * 2. Atomically increment counter, setting TTL only for a new window
     * 3. If counter > limit, return not allowed with retry-after = remaining window TTL
     * 4. If counter <= limit, return allowed with remaining count
     *
     * @param routeId The route identifier
     * @param principal The authenticated principal (username)
     * @param config The rate limit configuration
     * @return Mono<RateLimitResult> indicating if the request is allowed
     */
    public Mono<RateLimitResult> checkRateLimit(String routeId, String principal, RateLimitConfig config) {
        return checkWindow(buildKey(routeId, principal),
                config.getRequestsPerWindow(), config.getWindowDuration());
    }

    /**
     * Fixed-window check against an arbitrary key — the single implementation
     * behind every limiter in the gateway.
     *
     * <p>The per-IP limiter for unauthenticated paths shares this rather than
     * keeping its own counter: an in-memory bucket is per-pod, so N replicas
     * silently grant N× the configured budget, and a public endpoint's budget is
     * the whole point. Anything counting requests here must go through this
     * method — a second copy of the window logic is how the TTL-refresh lockout
     * bug (2026-07-11) would come back.
     *
     * <p><b>Fails open.</b> If Redis is unreachable the request is allowed, matching
     * the pre-existing posture: a cache outage must not take the platform down. It
     * does mean a Redis outage removes rate limiting, so the failure is logged at
     * WARN rather than swallowed.
     *
     * @param key    the full Redis key (callers namespace their own keys)
     * @param limit  requests permitted per window
     * @param window the window duration
     */
    public Mono<RateLimitResult> checkWindow(String key, long limit, Duration window) {
        String windowSeconds = String.valueOf(Math.max(1, window.toSeconds()));

        return redisTemplate.execute(INCREMENT_WINDOW_SCRIPT, List.of(key), List.of(windowSeconds))
            .next()
            .flatMap(count -> {
                if (count > limit) {
                    // Rate limit exceeded — report the true time until the window
                    // resets, not the full window duration.
                    log.debug("Rate limit exceeded for key={}, count={}, limit={}", key, count, limit);
                    return redisTemplate.getExpire(key)
                        .map(ttl -> ttl.isPositive() ? ttl : window)
                        .defaultIfEmpty(window)
                        .map(RateLimitResult::notAllowed);
                } else {
                    // Request allowed
                    long remaining = limit - count;
                    log.debug("Rate limit check passed for key={}, count={}, remaining={}",
                        key, count, remaining);
                    return Mono.just(RateLimitResult.allowed(remaining));
                }
            })
            .onErrorResume(error -> {
                // Graceful degradation: if Redis is unavailable, allow the request
                log.warn("Redis error during rate limit check for key={}. Allowing request. Error: {}",
                    key, error.getMessage());
                return Mono.just(RateLimitResult.allowed(limit));
            })
            // A script that completes WITHOUT emitting is not an error, so it
            // slips past onErrorResume — and an empty Mono here means the calling
            // filter never reaches chain.filter(), so the request hangs with no
            // status and no body instead of failing open. Same outcome as an
            // error: allow, and say so.
            .switchIfEmpty(Mono.fromSupplier(() -> {
                log.warn("Rate limit check for key={} returned no result. Allowing request.", key);
                return RateLimitResult.allowed(limit);
            }));
    }

    /**
     * Increments the daily API call counter for a tenant.
     *
     * <p>Uses a Redis key with today's UTC date: {@code api-calls-daily:<tenantId>:<yyyy-MM-dd>}.
     * The key has a 48-hour TTL to ensure cleanup after the day ends.
     * This counter is read by the worker's GovernorLimitsController to display
     * daily API call usage on the Governor Limits page.
     *
     * @param tenantId the tenant ID to track
     * @return Mono that completes when the counter is incremented
     */
    public Mono<Void> incrementDailyCounter(String tenantId) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String key = DAILY_KEY_PREFIX + tenantId + ":" + today;

        return redisTemplate.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        // First call today — set TTL to 48 hours for cleanup
                        return redisTemplate.expire(key, Duration.ofHours(48))
                                .then();
                    }
                    return Mono.empty();
                })
                .onErrorResume(error -> {
                    log.warn("Failed to increment daily API call counter for tenant {}: {}",
                            tenantId, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Increments the per-PAT request counter.
     *
     * <p>Uses a Redis key {@code pat-usage:<tokenHash>}, mirroring
     * {@link #incrementDailyCounter(String)}. The TTL (400 days, longer than the
     * maximum 365-day token life) is set only on the first increment so it isn't
     * refreshed — and therefore never expires — on every request. This counter is
     * read back by the worker's {@code PersonalAccessTokenController} to show the
     * token owner their usage.
     *
     * @param tokenHash the SHA-256 hash of the PAT (never the raw token)
     * @return Mono that completes when the counter is incremented
     */
    public Mono<Void> incrementPatUsageCounter(String tokenHash) {
        String key = PAT_USAGE_KEY_PREFIX + tokenHash;

        return redisTemplate.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        return redisTemplate.expire(key, PAT_USAGE_TTL).then();
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(error -> {
                    log.warn("Failed to increment PAT usage counter for key={}: {}",
                            key, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Builds the Redis key for rate limiting.
     *
     * @param routeId The route identifier
     * @param principal The authenticated principal
     * @return Redis key in format "ratelimit:{routeId}:{principal}"
     */
    private String buildKey(String routeId, String principal) {
        return KEY_PREFIX + routeId + ":" + principal;
    }
}
