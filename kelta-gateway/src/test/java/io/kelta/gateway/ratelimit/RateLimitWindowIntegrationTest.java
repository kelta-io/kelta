package io.kelta.gateway.ratelimit;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.geo.ClientIpResolver;
import io.kelta.gateway.integration.GatewayContainers;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RateLimitConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fixed-window semantics of the gateway rate limiter against real Redis ({@link GatewayContainers}).
 *
 * <p>{@link RateLimitFilterTest} mocks {@link RedisRateLimiter} and {@link RedisRateLimiterTest}
 * only inspects the Lua script, so neither proves that the window actually closes at N, reopens
 * when it expires, or is one budget across pods. This drives the real {@link RateLimitFilter}
 * over a real {@link RedisRateLimiter}; only the tenant's governor lookup is stubbed, to get a
 * window short enough to watch expire.
 *
 * <p>No gateway context: the Spring config is empty and exists only so the inherited
 * {@code @DynamicPropertySource} resolves the container's host and port. Each test uses its own
 * tenant id, so its keys cannot collide with another test's.
 */
@SpringJUnitConfig(RateLimitWindowIntegrationTest.NoBeans.class)
class RateLimitWindowIntegrationTest extends GatewayContainers {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofSeconds(2);

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    private final List<LettuceConnectionFactory> connectionFactories = new ArrayList<>();
    private final AtomicInteger forwarded = new AtomicInteger();
    private String tenantId;
    private ReactiveRedisTemplate<String, String> redis;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        tenantId = "rl-window-it-" + UUID.randomUUID();
        redis = newTemplate();
        filter = filterOver(new RedisRateLimiter(redis));
    }

    @AfterEach
    void tearDown() {
        redis.keys("*" + tenantId + "*")
                .flatMap(redis::delete)
                .then()
                .block(Duration.ofSeconds(5));
        connectionFactories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    void allowsTheLimitThenAnswers429WithRetryAfter() throws Exception {
        for (int i = 1; i <= LIMIT; i++) {
            MockServerWebExchange allowed = send(filter);
            assertThat(allowed.getResponse().getStatusCode()).as("request %d", i).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(allowed.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                    .as("request %d", i).isEqualTo(String.valueOf(LIMIT - i));
        }
        assertThat(forwarded).hasValue(LIMIT);

        MockServerWebExchange denied = send(filter);

        assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(forwarded).as("the denied request is not forwarded").hasValue(LIMIT);
        String retryAfter = denied.getResponse().getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, WINDOW.toSeconds());
        JsonNode error = new ObjectMapper()
                .readTree(denied.getResponse().getBodyAsString().block(Duration.ofSeconds(5)))
                .get("errors").get(0);
        assertThat(error.get("status").asString()).isEqualTo("429");
        assertThat(error.get("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(error.get("title").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(error.get("meta").get("requestId").asString()).isNotBlank();
    }

    @Test
    void allowsRequestsAgainOnceTheWindowExpires() {
        for (int i = 0; i < LIMIT; i++) {
            send(filter);
        }
        assertThat(send(filter).getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Denied requests keep incrementing the counter but must not extend its TTL, so the window
        // still closes on schedule under continued traffic (the 2026-07-11 lockout).
        await().atMost(WINDOW.plusSeconds(1))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> send(filter).getResponse().getStatusCode() != HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void limitersOnSeparateConnectionsShareOneBudget() {
        RateLimitFilter podA = filterOver(new RedisRateLimiter(newTemplate()));
        RateLimitFilter podB = filterOver(new RedisRateLimiter(newTemplate()));
        assertThat(connectionFactories).hasSize(3);

        RateLimitFilter[] alternating = {podA, podB, podA};
        for (RateLimitFilter pod : alternating) {
            assertThat(send(pod).getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(forwarded).hasValue(LIMIT);

        assertThat(send(podB).getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send(podA).getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(forwarded).hasValue(LIMIT);
    }

    private MockServerWebExchange send(RateLimitFilter target) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts"));
        exchange.getAttributes().put("gateway.principal",
                new GatewayPrincipal("member@example.com", List.of("USER"), null));
        exchange.getAttributes().put("tenantId", tenantId);
        target.filter(exchange, ignored -> Mono.fromRunnable(forwarded::incrementAndGet))
                .block(Duration.ofSeconds(5));
        return exchange;
    }

    /** User share 1.0 makes the per-user window equal the tenant window, so only the tenant counter runs. */
    private RateLimitFilter filterOver(RedisRateLimiter limiter) {
        GatewayCacheManager cacheManager = mock(GatewayCacheManager.class);
        when(cacheManager.getRateLimitForTenant(tenantId)).thenReturn(new RateLimitConfig(LIMIT, WINDOW));
        return new RateLimitFilter(limiter, cacheManager, mock(GatewayMetrics.class),
                new RateLimitExemptionService(new ClientIpResolver(true), List.of()), 1.0);
    }

    private ReactiveRedisTemplate<String, String> newTemplate() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort));
        factory.afterPropertiesSet();
        factory.start();
        connectionFactories.add(factory);
        return new ReactiveRedisTemplate<>(factory, RedisSerializationContext.string());
    }
}
