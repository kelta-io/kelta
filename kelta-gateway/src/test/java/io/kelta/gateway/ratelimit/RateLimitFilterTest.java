package io.kelta.gateway.ratelimit;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.geo.ClientIpResolver;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RateLimitFilter.
 * Tests per-tenant rate limiting based on governor limits, plus the per-user
 * window that bounds any single member's share of that tenant budget.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter Tests")
class RateLimitFilterTest {

    @Mock
    private RedisRateLimiter rateLimiter;

    @Mock
    private GatewayFilterChain chain;

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    @Mock
    private GatewayMetrics metrics;

    private GatewayCacheManager cacheManager;
    private RateLimitFilter filter;

    private static final String TENANT_ID = "test-tenant-id";

    /** Governor limit that resolves to a tenant window of exactly 10 requests. */
    private static final int GOVERNOR_FOR_TENANT_LIMIT_OF_10 = 576;

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);

        cacheManager = new GatewayCacheManager(webClientBuilder, "http://localhost:8080");
        // No exempt ranges configured, so every request is subject to the limiter.
        filter = filterWithShare(RateLimitFilter.DEFAULT_USER_SHARE);
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
        lenient().when(rateLimiter.incrementDailyCounter(anyString())).thenReturn(Mono.empty());
        // The per-user window runs ahead of the tenant window on every request, so
        // the tenant-focused tests below need it out of the way by default.
        lenient().when(rateLimiter.checkRateLimit(anyString(), startsWith("user:"),
                        any(RateLimitConfig.class)))
                .thenReturn(Mono.just(RateLimitResult.allowed(Integer.MAX_VALUE)));
    }

    private RateLimitFilter filterWithShare(double userShare) {
        return new RateLimitFilter(rateLimiter, cacheManager, metrics,
                new RateLimitExemptionService(new ClientIpResolver(true), List.of()),
                userShare);
    }

    @Test
    void testNoPrincipal_SkipsRateLimiting() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        // When & Then
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain).filter(exchange);
        verify(rateLimiter, never()).checkRateLimit(anyString(), anyString(), any());
    }

    @Test
    void testNoTenantContext_SkipsRateLimiting() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal("user@example.com", List.of("USER"), null);
        exchange.getAttributes().put("gateway.principal", principal);
        // No tenant ID set

        // When & Then
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain).filter(exchange);
        verify(rateLimiter, never()).checkRateLimit(anyString(), anyString(), any());
    }

    @Test
    void testRateLimitAllowed_AddsHeadersAndContinues() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal("user@example.com", List.of("USER"), null);
        exchange.getAttributes().put("gateway.principal", principal);
        exchange.getAttributes().put("tenantId", TENANT_ID);

        // Governor limits: 100,000 per day -> ~69 per minute
        cacheManager.updateGovernorLimit(TENANT_ID, 100_000);
        RateLimitConfig expectedConfig = cacheManager.getRateLimitForTenant(TENANT_ID);

        when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.allowed(50)));

        // When & Then
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain).filter(exchange);

        // Verify rate limit headers are added
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals(String.valueOf(expectedConfig.getRequestsPerWindow()), headers.getFirst("X-RateLimit-Limit"));
        assertEquals("50", headers.getFirst("X-RateLimit-Remaining"));
        assertNotNull(headers.getFirst("X-RateLimit-Reset"));
    }

    @Test
    void testRateLimitExceeded_Returns429() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal("user@example.com", List.of("USER"), null);
        exchange.getAttributes().put("gateway.principal", principal);
        exchange.getAttributes().put("tenantId", TENANT_ID);

        cacheManager.updateGovernorLimit(TENANT_ID, 100_000);

        when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.notAllowed(Duration.ofSeconds(60))));

        // When & Then
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain, never()).filter(exchange);

        // Verify response status
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());

        // Verify rate limit headers
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertNotNull(headers.getFirst("X-RateLimit-Limit"));
        assertEquals("0", headers.getFirst("X-RateLimit-Remaining"));
        assertEquals("60", headers.getFirst("Retry-After"));
        assertNotNull(headers.getFirst("X-RateLimit-Reset"));
        assertEquals("application/json", headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void testRateLimitExceeded_BodyIsJsonApiRateLimitError() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users")
            .header("X-Correlation-ID", "corr-429").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("gateway.principal",
            new GatewayPrincipal("user@example.com", List.of("USER"), null));
        exchange.getAttributes().put("tenantId", TENANT_ID);
        cacheManager.updateGovernorLimit(TENANT_ID, 100_000);
        when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.notAllowed(Duration.ofSeconds(60))));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        JsonNode error = new ObjectMapper()
            .readTree(exchange.getResponse().getBodyAsString().block()).get("errors").get(0);
        assertThat(error.get("status").asString()).isEqualTo("429");
        assertThat(error.get("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(error.get("title").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(error.get("detail").asString()).contains("60 seconds");
        assertThat(error.get("meta").get("requestId").asString()).isEqualTo("corr-429");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void testRateLimitExceeded_PathWithDoubleQuote_StillValidJson() throws Exception {
        MockServerWebExchange original = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/users").build());
        // A real decoded path can carry a quote; the URI builders reject one, so decorate.
        ServerWebExchange exchange = original.mutate()
            .request(new ServerHttpRequestDecorator(original.getRequest()) {
                @Override
                public RequestPath getPath() {
                    return RequestPath.parse("/api/us\"ers", null);
                }
            })
            .build();
        assertThat(exchange.getRequest().getPath().value()).contains("\"");
        exchange.getAttributes().put("gateway.principal",
            new GatewayPrincipal("user@example.com", List.of("USER"), null));
        exchange.getAttributes().put("tenantId", TENANT_ID);
        cacheManager.updateGovernorLimit(TENANT_ID, 100_000);
        when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.notAllowed(Duration.ofSeconds(5))));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        JsonNode body = new ObjectMapper().readTree(original.getResponse().getBodyAsString().block());
        assertThat(body.get("errors").get(0).get("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("errors").get(0).get("meta").get("requestId").asString()).isNotBlank();
    }

    @Test
    void testDifferentTenants_HaveSeparateLimits() {
        // Given
        String tenantId1 = "tenant-1";
        String tenantId2 = "tenant-2";

        cacheManager.updateGovernorLimit(tenantId1, 50_000);
        cacheManager.updateGovernorLimit(tenantId2, 200_000);

        // First tenant
        MockServerHttpRequest request1 = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange1 = MockServerWebExchange.from(request1);
        GatewayPrincipal principal1 = new GatewayPrincipal("user1@example.com", List.of("USER"), null);
        exchange1.getAttributes().put("gateway.principal", principal1);
        exchange1.getAttributes().put("tenantId", tenantId1);

        when(rateLimiter.checkRateLimit(eq(tenantId1), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.allowed(9)));

        // Second tenant
        MockServerHttpRequest request2 = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange2 = MockServerWebExchange.from(request2);
        GatewayPrincipal principal2 = new GatewayPrincipal("user2@example.com", List.of("USER"), null);
        exchange2.getAttributes().put("gateway.principal", principal2);
        exchange2.getAttributes().put("tenantId", tenantId2);

        when(rateLimiter.checkRateLimit(eq(tenantId2), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.allowed(9)));

        // When & Then
        StepVerifier.create(filter.filter(exchange1, chain))
            .verifyComplete();

        StepVerifier.create(filter.filter(exchange2, chain))
            .verifyComplete();

        verify(rateLimiter).checkRateLimit(eq(tenantId1), eq("tenant"), any(RateLimitConfig.class));
        verify(rateLimiter).checkRateLimit(eq(tenantId2), eq("tenant"), any(RateLimitConfig.class));
    }

    @Test
    void testDefaultLimitsUsedForUnknownTenant() {
        // Given - tenant not in cache, should use default (100,000/day)
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal("user@example.com", List.of("USER"), null);
        exchange.getAttributes().put("gateway.principal", principal);
        exchange.getAttributes().put("tenantId", "unknown-tenant");

        when(rateLimiter.checkRateLimit(eq("unknown-tenant"), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.allowed(50)));

        // When & Then
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain).filter(exchange);
        verify(rateLimiter).checkRateLimit(eq("unknown-tenant"), eq("tenant"), any(RateLimitConfig.class));
    }

    @Test
    void testFilterOrder() {
        // Filter should run after authentication (-100) but before routing (0)
        assertEquals(-50, filter.getOrder());
    }

    @Test
    void testRateLimitWithZeroRemaining() {
        // Given - last allowed request
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal("user@example.com", List.of("USER"), null);
        exchange.getAttributes().put("gateway.principal", principal);
        exchange.getAttributes().put("tenantId", TENANT_ID);

        cacheManager.updateGovernorLimit(TENANT_ID, 100_000);

        when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
            .thenReturn(Mono.just(RateLimitResult.allowed(0)));

        // When & Then
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain).filter(exchange);

        // Verify headers show 0 remaining but request is still allowed
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals("0", headers.getFirst("X-RateLimit-Remaining"));
    }

    /**
     * Builds an authenticated exchange for the given member of {@link #TENANT_ID}.
     */
    private ServerWebExchange authenticatedExchange(String username) {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());
        exchange.getAttributes().put("gateway.principal",
                new GatewayPrincipal(username, List.of("USER"), null));
        exchange.getAttributes().put("tenantId", TENANT_ID);
        exchange.getAttributes().put("tenantSlug", "acme");
        return exchange;
    }

    @Nested
    @DisplayName("Per-user window")
    class PerUserWindow {

        @Test
        @DisplayName("should reject with 429 without incrementing the shared tenant counter")
        void perUserRejectionLeavesTenantCounterUntouched() {
            // The whole point of the per-user window: one member burning through
            // their share must not also consume — or lock out — the tenant budget
            // everyone else in the tenant shares.
            cacheManager.updateGovernorLimit(TENANT_ID, GOVERNOR_FOR_TENANT_LIMIT_OF_10);
            ServerWebExchange exchange = authenticatedExchange("noisy@example.com");

            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("user:noisy@example.com"),
                    any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.notAllowed(Duration.ofSeconds(42))));

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("42");
            verify(chain, never()).filter(exchange);
            verify(rateLimiter, never())
                    .checkRateLimit(anyString(), eq("tenant"), any(RateLimitConfig.class));
            verify(rateLimiter, never()).incrementDailyCounter(anyString());
            verify(metrics).recordRateLimitExceeded("acme");
        }

        @Test
        @DisplayName("should fall through to the tenant window when the user window allows")
        void perUserAllowedRunsTenantCheck() {
            cacheManager.updateGovernorLimit(TENANT_ID, GOVERNOR_FOR_TENANT_LIMIT_OF_10);
            ServerWebExchange exchange = authenticatedExchange("user@example.com");

            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("user:user@example.com"),
                    any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(4)));
            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(9)));

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            verify(chain).filter(exchange);
            verify(rateLimiter).checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class));
            // Headers still reflect the tenant window, not the user's slice of it.
            assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                    .isEqualTo("9");
        }

        @Test
        @DisplayName("should key the user window on the principal's username")
        void perUserKeyUsesUsername() {
            // JWTs and PATs both carry the member's email here, so a member cannot
            // double their budget by switching credential type.
            cacheManager.updateGovernorLimit(TENANT_ID, GOVERNOR_FOR_TENANT_LIMIT_OF_10);
            ServerWebExchange exchange = authenticatedExchange("alice@example.com");

            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("user:alice@example.com"),
                    any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(4)));
            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(9)));

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            ArgumentCaptor<String> principalCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter, times(2))
                    .checkRateLimit(eq(TENANT_ID), principalCaptor.capture(), any(RateLimitConfig.class));
            assertThat(principalCaptor.getAllValues())
                    .containsExactly("user:alice@example.com", "tenant");
        }

        @Test
        @DisplayName("should budget the user window at the configured share of the tenant window")
        void perUserBudgetIsShareOfTenantWindow() {
            RateLimitConfig userConfig = captureUserConfig(filterWithShare(0.5));

            assertThat(userConfig.getRequestsPerWindow()).isEqualTo(5);
            // Same window length as the tenant's, only the budget differs.
            assertThat(userConfig.getWindowDuration())
                    .isEqualTo(cacheManager.getRateLimitForTenant(TENANT_ID).getWindowDuration());
        }

        @Test
        @DisplayName("should skip the user window entirely when the share is 1.0")
        void shareOfOneSkipsTheRedisCall() {
            // A full share adds nothing over the tenant window, so it must not cost
            // a Redis round trip on every request.
            cacheManager.updateGovernorLimit(TENANT_ID, GOVERNOR_FOR_TENANT_LIMIT_OF_10);
            ServerWebExchange exchange = authenticatedExchange("user@example.com");

            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(9)));

            StepVerifier.create(filterWithShare(1.0).filter(exchange, chain)).verifyComplete();

            verify(chain).filter(exchange);
            verify(rateLimiter, never())
                    .checkRateLimit(anyString(), startsWith("user:"), any(RateLimitConfig.class));
        }

        @Test
        @DisplayName("should leave most of the tenant window to a single busy member by default")
        void defaultShareLeavesRoomForOneBusyMember() {
            // The tenant window is derived from the tenant's WHOLE governor
            // budget, and one admin, bulk import, or integration PAT legitimately
            // IS most of a tenant. A 0.5 default halved real single-user
            // throughput and took the e2e suite — one admin driving one tenant —
            // from comfortably under the cap to failing on 429. This window bounds
            // a runaway or stolen credential; it does not divide fairly.
            assertThat(RateLimitFilter.DEFAULT_USER_SHARE)
                    .isGreaterThanOrEqualTo(0.9)
                    .isLessThan(1.0);
        }

        @Test
        @DisplayName("should fall back to the default share when configured as zero")
        void zeroShareFallsBackToDefault() {
            // A misconfigured share must not silently disable the limit.
            assertThat(captureUserConfig(filterWithShare(0)).getRequestsPerWindow()).isEqualTo(9);
        }

        @Test
        @DisplayName("should fall back to the default share when configured negative")
        void negativeShareFallsBackToDefault() {
            assertThat(captureUserConfig(filterWithShare(-1)).getRequestsPerWindow()).isEqualTo(9);
        }

        @Test
        @DisplayName("should fall back to the default share when configured above 1")
        void aboveOneShareFallsBackToDefault() {
            // 2.0 would otherwise make the user budget exceed the tenant's and skip
            // the check, which is the opposite of what an operator typing it meant.
            assertThat(captureUserConfig(filterWithShare(2.0)).getRequestsPerWindow()).isEqualTo(9);
        }

        @Test
        @DisplayName("should floor the user budget at one request")
        void perUserBudgetIsFlooredAtOne() {
            // Rounding a tiny share to 0 would reject every request from the member.
            cacheManager.updateGovernorLimit(TENANT_ID, 288); // tenant window of 5
            assertThat(cacheManager.getRateLimitForTenant(TENANT_ID).getRequestsPerWindow())
                    .isEqualTo(5);

            assertThat(captureUserConfig(filterWithShare(0.05)).getRequestsPerWindow()).isEqualTo(1);
        }

        /**
         * Runs one allowed request through {@code target} and returns the config the
         * per-user check was given.
         */
        private RateLimitConfig captureUserConfig(RateLimitFilter target) {
            if (cacheManager.getGovernorLimit(TENANT_ID).isEmpty()) {
                cacheManager.updateGovernorLimit(TENANT_ID, GOVERNOR_FOR_TENANT_LIMIT_OF_10);
            }
            ServerWebExchange exchange = authenticatedExchange("user@example.com");

            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("user:user@example.com"),
                    any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(1)));
            when(rateLimiter.checkRateLimit(eq(TENANT_ID), eq("tenant"), any(RateLimitConfig.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(1)));

            StepVerifier.create(target.filter(exchange, chain)).verifyComplete();

            ArgumentCaptor<RateLimitConfig> configCaptor =
                    ArgumentCaptor.forClass(RateLimitConfig.class);
            verify(rateLimiter).checkRateLimit(eq(TENANT_ID), eq("user:user@example.com"),
                    configCaptor.capture());
            return configCaptor.getValue();
        }
    }
}
