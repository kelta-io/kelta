package io.kelta.gateway.auth;

import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import io.kelta.gateway.filter.TenantResolutionFilter;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
        filter = new PatAuthenticationFilter(redisTemplate, builder, "http://localhost", 300, 10_000, metrics, redisRateLimiter);
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
        @DisplayName("a token is accepted on its own tenant's URL")
        void sameTenantIsAccepted() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON));
            MockServerWebExchange exchange = exchangeFor("/api/titles");
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "t-1");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            verify(filterChain).filter(any(ServerWebExchange.class));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("a token is rejected on another tenant's URL")
        void otherTenantIsRejected() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON));
            MockServerWebExchange exchange = exchangeFor("/api/titles");
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "t-other");

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            verify(filterChain, never()).filter(any(ServerWebExchange.class));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(metrics).recordAuthFailure(any(), eq("tenant_mismatch"));
            assertThat(exchange.getAttributes()).doesNotContainKey("gateway.principal");
        }

        @Test
        @DisplayName("a sandbox's PAT is rejected on another tenant's MCP admin path, and the reverse")
        void sandboxScopedPatCannotReachAnotherTenant() {
            // `kelta mcp serve --tenant <sandbox> --token-env <VAR>` bridges
            // /<sandbox>/mcp/admin with the sandbox's own token. kelta-mcp only checks the
            // bearer's shape; this filter is what keeps that token inside its tenant.
            String sandboxToken = "klt_sandbox_admin_token";
            String sandboxHash = PatAuthenticationFilter.sha256(sandboxToken);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + sandboxHash)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + sandboxHash)).thenReturn(Mono.just("""
                    {"userId":"u-sbx","tenantId":"tenant-sandbox","email":"admin@sbx.test","scopes":"[\\"api\\"]"}"""));
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON)); // tenant t-1

            MockServerWebExchange sandboxOnParent = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/parent/mcp/admin")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + sandboxToken)
                    .build());
            sandboxOnParent.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "t-1");
            MockServerWebExchange parentOnSandbox = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/sandbox/mcp/admin")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                    .build());
            parentOnSandbox.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-sandbox");

            StepVerifier.create(filter.filter(sandboxOnParent, filterChain)).verifyComplete();
            StepVerifier.create(filter.filter(parentOnSandbox, filterChain)).verifyComplete();

            verify(filterChain, never()).filter(any(ServerWebExchange.class));
            for (MockServerWebExchange exchange : List.of(sandboxOnParent, parentOnSandbox)) {
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(exchange.getAttributes()).doesNotContainKey("gateway.principal");
            }
            verify(metrics, times(2)).recordAuthFailure(any(), eq("tenant_mismatch"));
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
                    redisTemplate, outageBuilder, "http://worker", 300, 10_000, metrics, redisRateLimiter);
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
            assertThat(graceFilter.graceCache().getIfPresent(HASH)).isEqualTo(PAT_JSON);

            // Second request: token is revoked — must get 401 even though the grace
            // cache has valid PAT data for this hash.
            MockServerWebExchange exchange2 = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(exchange2, filterChain)).verifyComplete();

            verify(filterChain, never()).filter(exchange2);
            assertThat(exchange2.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(metrics).recordAuthFailure(any(), eq("revoked_pat"));
        }

        @Test
        @DisplayName("a revoked hit evicts the grace entry; the token stays refused while the worker fails")
        void revokedHitEvictsGraceEntry() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH))
                    .thenReturn(Mono.empty())           // 1st: not revoked yet
                    .thenReturn(Mono.just("revoked"))   // 2nd: revoked
                    .thenReturn(Mono.empty());          // 3rd: revocation key gone (e.g. expired)
            when(valueOps.get("pat:" + HASH))
                    .thenReturn(Mono.just(PAT_JSON))    // 1st: Redis hit → grace entry written
                    .thenReturn(Mono.empty());          // 3rd: Redis miss → failing worker → grace

            MockServerWebExchange accepted = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(accepted, filterChain)).verifyComplete();
            assertThat(graceFilter.graceCache().getIfPresent(HASH)).isEqualTo(PAT_JSON);

            MockServerWebExchange revoked = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(revoked, filterChain)).verifyComplete();
            assertThat(revoked.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(graceFilter.graceCache().getIfPresent(HASH)).isNull();

            // Even if the revocation key were no longer visible, the worker is down and the
            // grace entry is gone, so the stale token data cannot authenticate the request.
            MockServerWebExchange afterRevocation = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(afterRevocation, filterChain)).verifyComplete();
            assertThat(afterRevocation.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(afterRevocation.getAttributes()).doesNotContainKey("gateway.principal");
            verify(filterChain, times(1)).filter(any(ServerWebExchange.class));
        }

        @Test
        @DisplayName("an expired PAT's JSON is never written to the grace cache")
        void expiredPatIsNeverCached() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH))
                    .thenReturn(Mono.just("""
                            {"userId":"u-2","tenantId":"t-2","email":"grace@b.c","expiresAt":"2020-01-01T00:00:00Z"}"""))
                    .thenReturn(Mono.empty());

            MockServerWebExchange expired = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(expired, filterChain)).verifyComplete();
            assertThat(expired.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(metrics).recordAuthFailure(any(), eq("expired_pat"));
            assertThat(graceFilter.graceCache().getIfPresent(HASH)).isNull();
            assertThat(graceFilter.graceCache().estimatedSize()).isZero();

            // Redis miss + worker outage: nothing to fall back on.
            MockServerWebExchange outage = exchangeFor("/api/records");
            StepVerifier.create(graceFilter.filter(outage, filterChain)).verifyComplete();
            assertThat(outage.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain, never()).filter(any(ServerWebExchange.class));
        }

        @Test
        @DisplayName("a cross-tenant rejection is not written to the grace cache")
        void rejectedTenantMismatchIsNeverCached() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("pat:revoked:" + HASH)).thenReturn(Mono.empty());
            when(valueOps.get("pat:" + HASH)).thenReturn(Mono.just(PAT_JSON));
            MockServerWebExchange exchange = exchangeFor("/api/records");
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "t-other");

            StepVerifier.create(graceFilter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(graceFilter.graceCache().getIfPresent(HASH)).isNull();
        }
    }

    @Nested
    @DisplayName("Grace cache — bounded size")
    class GraceCacheBound {

        private static final long MAX_ENTRIES = 1_000;
        private static final int DISTINCT_TOKENS = 100_000;

        @Test
        @DisplayName("100,000 distinct accepted tokens leave the grace cache at its configured bound")
        @SuppressWarnings("unchecked")
        void distinctTokenFloodStaysWithinBound() {
            // stubOnly: no invocation recording, so 100k requests don't pile up in the mocks.
            ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class, withSettings().stubOnly());
            ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class, withSettings().stubOnly());
            GatewayFilterChain chain = mock(GatewayFilterChain.class, withSettings().stubOnly());
            RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class, withSettings().stubOnly());
            GatewayMetrics gatewayMetrics = mock(GatewayMetrics.class, withSettings().stubOnly());
            when(redis.opsForValue()).thenReturn(ops);
            String patJson = """
                    {"userId":"u-f","tenantId":"t-f","email":"flood@b.c","scopes":"[\\"api\\"]"}""";
            when(ops.get(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).startsWith("pat:revoked:")
                    ? Mono.empty() : Mono.just(patJson));
            when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
            when(rateLimiter.incrementPatUsageCounter(any())).thenReturn(Mono.empty());

            PatAuthenticationFilter floodFilter = new PatAuthenticationFilter(redis, WebClient.builder(),
                    "http://localhost:1", 300, MAX_ENTRIES, gatewayMetrics, rateLimiter);

            for (int i = 0; i < DISTINCT_TOKENS; i++) {
                MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                        .get("/api/records")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer klt_" + UUID.randomUUID())
                        .build());
                floodFilter.filter(exchange, chain).block();
                if (i == 0) {
                    assertThat(exchange.getAttributes()).containsKey("gateway.principal");
                }
            }

            floodFilter.graceCache().cleanUp();
            assertThat(floodFilter.graceCache().estimatedSize()).isPositive().isLessThanOrEqualTo(MAX_ENTRIES);
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
