package io.kelta.gateway.auth;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PLT-355: a client-supplied {@code X-Tenant-ID} / {@code X-Tenant-Slug} is only a claim. Runs
 * the real {@link TenantResolutionFilter} → {@link JwtAuthenticationFilter} →
 * {@link PatAuthenticationFilter} chain and checks, per auth path, that the credential's tenant
 * wins and that an anonymous request cannot pick a tenant with the header.
 */
@DisplayName("Client tenant headers vs the credential's tenant")
class TenantHeaderTrustTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String JWT = "header.payload.sig";
    private static final String PAT = "klt_tenant_a_token";

    private GatewayCacheManager cacheManager;
    private DynamicReactiveJwtDecoder jwtDecoder;
    private PrincipalExtractor principalExtractor;
    private PublicPathMatcher publicPathMatcher;
    private ReactiveValueOperations<String, String> redisValues;
    private GatewayMetrics metrics;

    private TenantResolutionFilter tenantFilter;
    private JwtAuthenticationFilter jwtFilter;
    private PatAuthenticationFilter patFilter;

    private ServerWebExchange forwarded;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        metrics = mock(GatewayMetrics.class);
        cacheManager = mock(GatewayCacheManager.class);
        when(cacheManager.resolveTenantSlugReactive(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(cacheManager.resolveTenantSlugReactive("alpha")).thenReturn(Mono.just(Optional.of(TENANT_A)));
        when(cacheManager.resolveTenantSlugReactive("beta")).thenReturn(Mono.just(Optional.of(TENANT_B)));
        when(cacheManager.resolveGuestProfileReactive(anyString()))
                .thenReturn(Mono.just(Optional.of("guest-profile")));

        jwtDecoder = mock(DynamicReactiveJwtDecoder.class);
        principalExtractor = mock(PrincipalExtractor.class);
        publicPathMatcher = mock(PublicPathMatcher.class);
        when(publicPathMatcher.isPublicRequest(any())).thenAnswer(inv -> {
            ServerWebExchange ex = inv.getArgument(0);
            return ex.getRequest().getPath().value().startsWith("/api/ui-pages");
        });

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        redisValues = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(redisValues);
        String hash = PatAuthenticationFilter.sha256(PAT);
        when(redisValues.get("pat:revoked:" + hash)).thenReturn(Mono.empty());
        RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
        when(rateLimiter.incrementPatUsageCounter(any())).thenReturn(Mono.empty());

        tenantFilter = new TenantResolutionFilter(metrics, cacheManager);
        jwtFilter = new JwtAuthenticationFilter(jwtDecoder, principalExtractor, publicPathMatcher, metrics, cacheManager);
        patFilter = new PatAuthenticationFilter(redis, WebClient.builder(), "http://localhost:1", 300,
                metrics, rateLimiter);
    }

    private void jwtWithTenantClaim(String tenantId) {
        Jwt.Builder builder = Jwt.withTokenValue(JWT).header("alg", "RS256").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        Jwt jwt = builder.build();
        when(jwtDecoder.decode(anyString(), anyString())).thenReturn(Mono.just(jwt));
        GatewayPrincipal principal = new GatewayPrincipal("user-1", List.of(), Map.of());
        when(principalExtractor.extractPrincipal(jwt))
                .thenReturn(tenantId != null ? principal.withTenantId(tenantId) : principal);
    }

    private void patForTenant(String tenantId) {
        String json = tenantId != null
                ? "{\"userId\":\"u-1\",\"tenantId\":\"" + tenantId + "\",\"email\":\"a@b.c\"}"
                : "{\"userId\":\"u-1\",\"email\":\"a@b.c\"}";
        when(redisValues.get("pat:" + PatAuthenticationFilter.sha256(PAT))).thenReturn(Mono.just(json));
    }

    /** As {@code TenantSlugExtractionFilter} leaves it for a {@code /alpha/...} URL. */
    private static void urlSlugAlpha(ServerWebExchange exchange) {
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, TENANT_A);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, "alpha");
    }

    private MockServerWebExchange run(MockServerHttpRequest request) {
        return run(MockServerWebExchange.from(request));
    }

    private MockServerWebExchange run(MockServerWebExchange exchange) {
        forwarded = null;
        GatewayFilterChain terminal = ex -> {
            forwarded = ex;
            return Mono.empty();
        };
        GatewayFilterChain afterJwt = ex -> patFilter.filter(ex, terminal);
        GatewayFilterChain afterTenant = ex -> jwtFilter.filter(ex, afterJwt);
        tenantFilter.filter(exchange, afterTenant).block();
        return exchange;
    }

    private void assertRejected(MockServerWebExchange exchange) {
        assertThat(forwarded).as("request must not reach the backend").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Nested
    @DisplayName("JWT")
    class JwtPath {

        @Test
        @DisplayName("X-Tenant-ID naming another tenant than the token's is rejected")
        void headerIdMismatchRejected() {
            jwtWithTenantClaim(TENANT_A);

            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                    .header("X-Tenant-ID", TENANT_B)
                    .build()));
            verify(metrics).recordAuthFailure(any(), org.mockito.ArgumentMatchers.eq("tenant_mismatch"));
        }

        @Test
        @DisplayName("X-Tenant-Slug naming another tenant than the token's is rejected")
        void headerSlugMismatchRejected() {
            jwtWithTenantClaim(TENANT_A);

            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                    .header("X-Tenant-Slug", "beta")
                    .build()));
        }

        @Test
        @DisplayName("a token without a tenant_id claim cannot use a header-claimed tenant")
        void tokenWithoutTenantCannotUseHeaderTenant() {
            jwtWithTenantClaim(null);

            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                    .header("X-Tenant-ID", TENANT_B)
                    .build()));
        }

        @Test
        @DisplayName("a header naming the token's own tenant is accepted")
        void headerMatchingTokenAccepted() {
            jwtWithTenantClaim(TENANT_A);

            run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                    .header("X-Tenant-ID", TENANT_A)
                    .build());

            assertThat(forwarded).isNotNull();
            assertThat(TenantResolutionFilter.getTenantId(forwarded)).isEqualTo(TENANT_A);
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Tenant-ID")).isNull();
        }

        @Test
        @DisplayName("on a slug URL the header is ignored: the URL tenant is what the token must match")
        void urlTenantWinsOverHeader() {
            jwtWithTenantClaim(TENANT_A);
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                    .header("X-Tenant-ID", TENANT_B)
                    .build());
            urlSlugAlpha(exchange);

            run(exchange);

            assertThat(forwarded).isNotNull();
            assertThat(TenantResolutionFilter.getTenantId(forwarded)).isEqualTo(TENANT_A);
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Tenant-ID")).isNull();
        }
    }

    @Nested
    @DisplayName("PAT")
    class PatPath {

        @Test
        @DisplayName("X-Tenant-ID naming another tenant than the token's is rejected")
        void headerIdMismatchRejected() {
            patForTenant(TENANT_A);

            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                    .header("X-Tenant-ID", TENANT_B)
                    .build()));
            verify(metrics).recordAuthFailure(any(), org.mockito.ArgumentMatchers.eq("tenant_mismatch"));
        }

        @Test
        @DisplayName("X-Tenant-Slug alone naming another tenant is rejected (it resolves to an id now)")
        void headerSlugMismatchRejected() {
            patForTenant(TENANT_A);

            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                    .header("X-Tenant-Slug", "beta")
                    .build()));
        }

        @Test
        @DisplayName("a token that records no tenant cannot use a header-claimed tenant")
        void tokenWithoutTenantCannotUseHeaderTenant() {
            patForTenant(null);

            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                    .header("X-Tenant-ID", TENANT_B)
                    .build()));
        }

        @Test
        @DisplayName("a header naming the token's own tenant is accepted")
        void headerMatchingTokenAccepted() {
            patForTenant(TENANT_A);

            run(MockServerHttpRequest.get("/api/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT)
                    .header("X-Tenant-Slug", "alpha")
                    .build());

            assertThat(forwarded).isNotNull();
            assertThat(TenantResolutionFilter.getTenantId(forwarded)).isEqualTo(TENANT_A);
        }
    }

    @Nested
    @DisplayName("Guest / anonymous")
    class AnonymousPath {

        @Test
        @DisplayName("no slug: the header cannot select a tenant to be admitted as its Guest")
        void headerCannotSelectGuestTenant() {
            MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/accounts")
                    .header("X-Tenant-ID", TENANT_B)
                    .build());

            assertRejected(exchange);
            verify(cacheManager, never()).resolveGuestProfileReactive(anyString());
        }

        @Test
        @DisplayName("no slug: X-Tenant-Slug cannot select a tenant either")
        void slugHeaderCannotSelectGuestTenant() {
            assertRejected(run(MockServerHttpRequest.get("/api/accounts")
                    .header("X-Tenant-Slug", "beta")
                    .build()));
            verify(cacheManager, never()).resolveGuestProfileReactive(anyString());
        }

        @Test
        @DisplayName("slug URL: Guest is admitted for the URL tenant, never the header's")
        void urlTenantWinsForGuest() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts")
                    .header("X-Tenant-ID", TENANT_B)
                    .header("X-Tenant-Slug", "beta")
                    .build());
            urlSlugAlpha(exchange);

            run(exchange);

            assertThat(forwarded).isNotNull();
            assertThat(TenantResolutionFilter.getTenantId(forwarded)).isEqualTo(TENANT_A);
            assertThat(TenantResolutionFilter.getTenantSlug(forwarded)).isEqualTo("alpha");
            assertThat(JwtAuthenticationFilter.getPrincipal(forwarded).getTenantId()).isEqualTo(TENANT_A);
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Tenant-ID")).isNull();
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-Tenant-Slug")).isNull();
            verify(cacheManager).resolveGuestProfileReactive(TENANT_A);
            verify(cacheManager, never()).resolveGuestProfileReactive(TENANT_B);
        }

        @Test
        @DisplayName("public path without slug: the header-claimed tenant is dropped")
        void publicPathDropsHeaderTenant() {
            run(MockServerHttpRequest.get("/api/ui-pages")
                    .header("X-Tenant-ID", TENANT_B)
                    .build());

            assertThat(forwarded).isNotNull();
            assertThat(TenantResolutionFilter.getTenantId(forwarded)).isNull();
            assertThat(TenantResolutionFilter.getTenantSlug(forwarded)).isNull();
        }

        @Test
        @DisplayName("public path on a slug URL keeps the URL tenant")
        void publicPathKeepsUrlTenant() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/ui-pages")
                    .header("X-Tenant-ID", TENANT_B)
                    .build());
            urlSlugAlpha(exchange);

            run(exchange);

            assertThat(TenantResolutionFilter.getTenantId(forwarded)).isEqualTo(TENANT_A);
        }
    }
}
