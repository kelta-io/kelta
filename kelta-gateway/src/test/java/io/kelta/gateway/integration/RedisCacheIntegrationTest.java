package io.kelta.gateway.integration;

import io.kelta.gateway.route.RateLimitConfig;
import io.kelta.gateway.ratelimit.RateLimitResult;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Redis cache operations.
 *
 * Tests:
 * - Rate limiting using Redis counters
 * - Cache miss handling
 * - Redis connection error handling
 *
 * Validates: Requirements 7.1, 7.2, 7.3, 7.4
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisCacheIntegrationTest extends GatewayContainers {

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // Real Redis (GatewayContainers) — start every test from an empty database.
        redisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushDb()
                .block(Duration.ofSeconds(2));
    }

    @Test
    void testRateLimiting_WithinLimit() {
        // Arrange - configure rate limit
        RateLimitConfig config = new RateLimitConfig(5, Duration.ofMinutes(1));

        String routeId = "test-route";
        String principal = "test-user";

        // Act & Assert - first request should be allowed
        Mono<RateLimitResult> resultMono = rateLimiter.checkRateLimit(routeId, principal, config);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.isAllowed()).isTrue();
                    assertThat(result.getRemainingRequests()).isLessThanOrEqualTo(4);
                })
                .verifyComplete();
    }

    @Test
    void testRateLimiting_ExceedsLimit() {
        // Arrange - configure low rate limit
        RateLimitConfig config = new RateLimitConfig(2, Duration.ofMinutes(1));

        String routeId = "limited-route";
        String principal = "test-user-2";

        // Act - make requests up to limit
        rateLimiter.checkRateLimit(routeId, principal, config).block(Duration.ofSeconds(2));
        rateLimiter.checkRateLimit(routeId, principal, config).block(Duration.ofSeconds(2));

        // Third request should exceed limit
        Mono<RateLimitResult> resultMono = rateLimiter.checkRateLimit(routeId, principal, config);

        // Assert - denied. (RedisRateLimiter fails open, so an allowed third request would mean
        // the limiter never reached Redis.)
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertThat(result.isAllowed()).isFalse();
                    assertThat(result.getRemainingRequests()).isEqualTo(0);
                    assertThat(result.getRetryAfter()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void testRateLimiting_TTL() throws Exception {
        // Arrange - configure rate limit with short window
        RateLimitConfig config = new RateLimitConfig(1, Duration.ofSeconds(2));

        String routeId = "ttl-test-route";
        String principal = "test-user-3";

        // Act - make first request
        rateLimiter.checkRateLimit(routeId, principal, config).block(Duration.ofSeconds(2));

        // Verify key exists in Redis
        String rateLimitKey = "ratelimit:" + routeId + ":" + principal;
        Boolean exists = redisTemplate.hasKey(rateLimitKey).block(Duration.ofSeconds(2));

        assertThat(exists).isTrue();

        // Assert - the window key carries the window's TTL
        Duration ttl = redisTemplate.getExpire(rateLimitKey).block(Duration.ofSeconds(2));
        assertThat(ttl).isNotNull();
        assertThat(ttl.getSeconds()).isGreaterThan(0);
        assertThat(ttl.getSeconds()).isLessThanOrEqualTo(2);
    }

    @Test
    void testRateLimiting_SeparateCountersPerPrincipal() {
        // A limit of one per principal: each principal's first request is allowed and spends
        // only its own window. (Fail-open on a Redis error is covered without Redis by
        // RedisRateLimiterTest.)
        RateLimitConfig config = new RateLimitConfig(1, Duration.ofMinutes(1));

        StepVerifier.create(rateLimiter.checkRateLimit("shared-route", "principal-a", config))
                .assertNext(result -> {
                    assertThat(result.isAllowed()).isTrue();
                    assertThat(result.getRemainingRequests()).isEqualTo(0);
                })
                .verifyComplete();
        StepVerifier.create(rateLimiter.checkRateLimit("shared-route", "principal-b", config))
                .assertNext(result -> assertThat(result.isAllowed()).isTrue())
                .verifyComplete();
        StepVerifier.create(rateLimiter.checkRateLimit("shared-route", "principal-a", config))
                .assertNext(result -> assertThat(result.isAllowed()).isFalse())
                .verifyComplete();
    }
}
