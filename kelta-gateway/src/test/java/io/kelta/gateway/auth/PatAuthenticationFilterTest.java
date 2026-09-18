package io.kelta.gateway.auth;

import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatAuthenticationFilter Tests")
class PatAuthenticationFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain filterChain;

    @Mock
    private RedisRateLimiter redisRateLimiter;

    private PatAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        WebClient.Builder builder = WebClient.builder();
        filter = new PatAuthenticationFilter(redisTemplate, builder, "http://localhost", 300, metrics, redisRateLimiter);
        lenient().when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        lenient().when(redisRateLimiter.incrementPatUsageCounter(any())).thenReturn(Mono.empty());
    }

    @Test
    void sha256ShouldProduceConsistentHash() {
        String hash1 = PatAuthenticationFilter.sha256("klt_test123");
        String hash2 = PatAuthenticationFilter.sha256("klt_test123");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 produces 64 hex chars
    }

    @Test
    void sha256ShouldProduceDifferentHashesForDifferentInputs() {
        String hash1 = PatAuthenticationFilter.sha256("klt_token1");
        String hash2 = PatAuthenticationFilter.sha256("klt_token2");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("unauthorized() short-circuits cleanly when the response is already committed")
    void unauthorizedShouldNotThrowWhenResponseAlreadyCommitted() {
        // Drive into unauthorized() via the revoked-PAT branch.
        String token = "klt_committed_response_test";
        String hash = PatAuthenticationFilter.sha256(token);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("pat:revoked:" + hash)).thenReturn(Mono.just("revoked"));

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Pre-commit the response so headers become ReadOnlyHttpHeaders. Any
        // mutation attempt would normally throw UnsupportedOperationException
        // and surface as a 500 instead of the intended 401.
        exchange.getResponse().setComplete().block();
        assertThat(exchange.getResponse().isCommitted()).isTrue();

        // Filter must complete cleanly — no exception bubbling out.
        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();
    }

    @Nested
    @DisplayName("Recognised vs unknown PAT")
    class TokenRecognition {

        private static final String TOKEN = "klt_valid_token";
        private static final String HASH = PatAuthenticationFilter.sha256(TOKEN);
        private static final String PAT_JSON = """
                {"userId":"u-1","tenantId":"t-1","email":"a@b.c","scopes":"[\\"api\\"]"}""";

        private MockServerWebExchange exchangeFor(String path) {
            return MockServerWebExchange.from(MockServerHttpRequest
                    .get(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                    .build());
        }

        @Test
        @DisplayName("a cache hit authenticates and records no auth failure")
        void cacheHitDoesNotReportUnknownPat() {
            // Regression: authenticateWithPat ends in chain.filter(...), a Mono<Void> that
            // always completes empty, so a switchIfEmpty hung off it fired on every SUCCESSFUL
            // request — ~10k bogus "Unknown PAT" warnings a day, each recording an unknown_pat
            // auth failure and poisoning the metric that security alerting keys on.
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON));

            MockServerWebExchange exchange = exchangeFor("/api/titles");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            verify(filterChain).filter(any(ServerWebExchange.class));
            verify(metrics, never()).recordAuthFailure(any(), eq("unknown_pat"));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(exchange.getAttributes()).containsKey("gateway.principal");
        }

        @Test
        @DisplayName("a successful authentication increments the per-token usage counter")
        void successfulAuthIncrementsUsageCounter() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON));

            MockServerWebExchange exchange = exchangeFor("/api/titles");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            verify(redisRateLimiter).incrementPatUsageCounter(HASH);
        }

        @Test
        @DisplayName("a token in neither Redis nor the worker is rejected as unknown")
        void unknownTokenIsRejected() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            // Empty cache; the worker fallback also yields nothing (WebClient points at a
            // dead local port, and fetchFromWorker maps any error to empty).
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.empty());

            MockServerWebExchange exchange = exchangeFor("/api/titles");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            verify(filterChain, never()).filter(any(ServerWebExchange.class));
            verify(metrics).recordAuthFailure(any(), eq("unknown_pat"));
            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Grace cache — worker-outage resilience")
    class GraceCacheResilience {

        private static final String TOKEN = "klt_grace_token";
        private static final String HASH = PatAuthenticationFilter.sha256(TOKEN);
        private static final String PAT_JSON = """
                {"userId":"u-2","tenantId":"t-2","email":"grace@b.c","scopes":"[\\"api\\"]"}""";

        // A separate filter whose WebClient is backed by an ExchangeFunction that always
        // throws a non-404 error — simulating a worker that is unreachable (503/connection
        // refused). The outer setUp's filter points at localhost which returns real HTTP 404s
        // for unknown paths; a 404 is the worker's "token not found" signal and must NOT
        // trigger the grace cache, so we cannot reuse that filter for outage tests.
        private PatAuthenticationFilter graceFilter;

        @BeforeEach
        void setUpGraceFilter() {
            WebClient.Builder outageBuilder = WebClient.builder()
                    .exchangeFunction(req -> Mono.error(
                            new RuntimeException("Simulated worker outage (connection refused)")));
            graceFilter = new PatAuthenticationFilter(
                    redisTemplate, outageBuilder, "http://worker", 300, metrics, redisRateLimiter);
        }

        private MockServerWebExchange exchangeFor(String path) {
            return MockServerWebExchange.from(MockServerHttpRequest
                    .get(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                    .build());
        }

        @Test
        @DisplayName("Redis miss + worker throws (non-404) + prior grace window → authenticates")
        void graceCacheAuthenticatesDuringWorkerOutage() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());

            // First call: Redis cache hit → populates the grace cache via doOnNext.
            // Second call: Redis misses → worker throws a non-404 error (simulated outage)
            // → fetchFromWorker consults the grace cache and serves the cached JSON
            // → request authenticates instead of receiving 401.
            when(valueOps.get("pat:" + HASH))
                    .thenReturn(Mono.just(PAT_JSON))  // first call: cache hit
                    .thenReturn(Mono.empty());         // second call: cache miss

            // First request: succeeds via Redis, populating the grace cache.
            MockServerWebExchange exchange1 = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(exchange1, filterChain)).verifyComplete();
            assertThat(exchange1.getAttributes()).containsKey("gateway.principal");

            // Second request: Redis misses, worker unreachable — grace cache must serve.
            MockServerWebExchange exchange2 = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(exchange2, filterChain)).verifyComplete();

            assertThat(exchange2.getAttributes()).containsKey("gateway.principal");
            assertThat(exchange2.getResponse().getStatusCode()).isNull();
            verify(metrics, never()).recordAuthFailure(any(), eq("unknown_pat"));
        }

        @Test
        @DisplayName("revoked PAT returns 401 even when grace cache holds its data")
        void revokedPatIsRejectedDespiteGraceCache() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            // First call: not revoked — populates the grace cache.
            // Second call: token is now revoked — must be rejected before the grace cache
            // is ever consulted; the fallback must never bypass revocation.
            when(valueOps.get("pat:revoked:" + HASH))
                    .thenReturn(Mono.empty())          // first call: not revoked
                    .thenReturn(Mono.just("revoked")); // second call: revoked
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON));

            // First request: succeeds and populates the grace cache.
            MockServerWebExchange exchange1 = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(exchange1, filterChain)).verifyComplete();
            assertThat(exchange1.getAttributes()).containsKey("gateway.principal");

            // Second request: token is revoked — must get 401 even though the grace
            // cache has valid PAT data for this hash.
            MockServerWebExchange exchange2 = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(exchange2, filterChain)).verifyComplete();

            verify(filterChain, never()).filter(exchange2);
            assertThat(exchange2.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(metrics).recordAuthFailure(any(), eq("revoked_pat"));
        }
    }

    @Nested
    @DisplayName("Filter Order")
    class FilterOrder {
        @Test
        void shouldRunAfterJwtFilter() {
            // PatAuthenticationFilter order is -99, JwtAuthenticationFilter is -100.
            // Lower order runs first, so JWT runs before PAT.
            assertThat(filter.getOrder()).isEqualTo(-99);
        }
    }
}
