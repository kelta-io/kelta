package io.kelta.gateway.filter;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TenantSlugExtractionFilter.
 *
 * Tests verify that:
 * - Valid slug prefixes are extracted and paths are rewritten
 * - Platform paths bypass slug requirement
 * - Invalid or unknown slugs return 404 when require-prefix is true
 * - Requests without slugs pass through when require-prefix is false
 * - Filter has the correct order (-300)
 * - Slug pattern validation works correctly
 */
@ExtendWith(MockitoExtension.class)
class TenantSlugExtractionFilterTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Mock
    private WebFilterChain chain;

    @Mock
    private GatewayMetrics metrics;

    private GatewayCacheManager cacheManager;

    private static final List<String> PLATFORM_PATHS = List.of("/actuator", "/platform", "/internal");

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);

        cacheManager = new GatewayCacheManager(webClientBuilder, "http://localhost:8080");

        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    // --- Order ---

    @Test
    void shouldHaveOrderNegative300() {
        TenantSlugExtractionFilter filter = createFilter(true, true);
        assertThat(filter.getOrder()).isEqualTo(-300);
    }

    // --- Valid slug extraction ---

    @Test
    void shouldExtractSlugAndRewritePath() {
        populateCache(Map.of("acme", "tenant-uuid-123"));
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/acme/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Capture the mutated exchange passed to chain
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Verify chain was called with mutated exchange
        verify(chain).filter(any(ServerWebExchange.class));

        // Verify path was rewritten
        assertThat(capturedExchange[0]).isNotNull();
        assertThat(capturedExchange[0].getRequest().getPath().value()).isEqualTo("/api/users");

        // Verify tenant attributes were set
        assertThat(capturedExchange[0].getAttributes().get(TenantResolutionFilter.TENANT_ID_ATTR))
                .isEqualTo("tenant-uuid-123");
        assertThat(capturedExchange[0].getAttributes().get(TenantResolutionFilter.TENANT_SLUG_ATTR))
                .isEqualTo("acme");
    }

    @Test
    void shouldSetOriginalPathAttribute() {
        populateCache(Map.of("acme", "tenant-uuid-123"));
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/acme/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedExchange[0].getAttributes().get("originalPath"))
                .isEqualTo("/acme/api/users");
    }

    @Test
    void shouldRewriteSlugOnlyPathToRoot() {
        populateCache(Map.of("acme", "tenant-uuid-123"));
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/acme").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedExchange[0]).isNotNull();
        assertThat(capturedExchange[0].getRequest().getPath().value()).isEqualTo("/");
    }

    // --- Root endpoints (RootEndpointsFilter) ---

    @Test
    void shouldNotTreatRobotsTxtAsTenantSlugEvenWhenPrefixRequired() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/robots.txt"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getAttributes()).doesNotContainKey(TenantResolutionFilter.TENANT_SLUG_ATTR);
    }

    @Test
    void shouldStillRequireTenantForPathsUnderRobotsTxt() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/robots.txt/api/users"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Platform path bypass ---

    @Test
    void shouldBypassActuatorPaths() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Should pass through without slug extraction -- original exchange, not mutated
        verify(chain).filter(exchange);
    }

    @Test
    void shouldBypassPlatformPaths() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/platform/something").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Should pass through without slug extraction -- original exchange, not mutated
        verify(chain).filter(exchange);
    }

    @Test
    void shouldBypassInternalPaths() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/internal/bootstrap").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Should pass through without slug extraction -- "internal" is a platform path, not a slug
        verify(chain).filter(exchange);
    }

    // --- Disabled filter ---

    @Test
    void shouldPassThroughWhenDisabled() {
        TenantSlugExtractionFilter filter = createFilter(false, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/acme/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Should pass through directly without any processing
        verify(chain).filter(exchange);
    }

    // --- Unknown slug with require-prefix=true ---

    @Test
    void shouldReturn404ForUnknownSlugWhenRequirePrefixTrue() {
        // Cache is empty, so any slug-shaped segment will be unknown
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/unknown/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldReturn404ForInvalidSlugPatternWhenRequirePrefixTrue() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        // "ACME" is uppercase, doesn't match slug pattern
        MockServerHttpRequest request = MockServerHttpRequest.get("/ACME/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldReturn404ForRootPathWhenRequirePrefixTrue() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());
    }

    // --- No slug with require-prefix=false (migration mode) ---

    @Test
    void shouldPassThroughWhenNoSlugAndRequirePrefixFalse() {
        TenantSlugExtractionFilter filter = createFilter(true, false);

        MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void shouldPassThroughInvalidSlugPatternWhenRequirePrefixFalse() {
        TenantSlugExtractionFilter filter = createFilter(true, false);

        // "UPPER" is uppercase, not a valid slug -- passes through in migration mode
        MockServerHttpRequest request = MockServerHttpRequest.get("/UPPER/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void shouldStripUnknownSlugAndPassThroughWhenRequirePrefixFalse() {
        // Cache is empty, so slug-shaped segment won't resolve to a tenant ID,
        // but the path should still be stripped so route matching works
        TenantSlugExtractionFilter filter = createFilter(true, false);

        MockServerHttpRequest request = MockServerHttpRequest.get("/unknown-org/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));

        // Path should be rewritten (slug stripped)
        assertThat(capturedExchange[0]).isNotNull();
        assertThat(capturedExchange[0].getRequest().getPath().value()).isEqualTo("/api/users");

        // Slug attribute should be set (for header propagation)
        assertThat(capturedExchange[0].getAttributes().get(TenantResolutionFilter.TENANT_SLUG_ATTR))
                .isEqualTo("unknown-org");

        // But tenant ID should NOT be set (slug wasn't in cache)
        assertThat(capturedExchange[0].getAttributes().get(TenantResolutionFilter.TENANT_ID_ATTR))
                .isNull();
    }

    // --- Reserved segments (slug-shaped but reserved as API/platform path prefixes) ---

    @Test
    void shouldPassThroughApiPathWhenRequirePrefixFalse() {
        // "api" matches the slug regex (3 chars) but must not be treated as a slug;
        // otherwise /api/collections gets stripped to /collections and route
        // matching against /api/** fails.
        TenantSlugExtractionFilter filter = createFilter(true, false);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/collections").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Original exchange — path unchanged, no tenant attributes set
        verify(chain).filter(exchange);
        assertThat(exchange.getAttributes().get(TenantResolutionFilter.TENANT_SLUG_ATTR)).isNull();
        assertThat(exchange.getAttributes().get(TenantResolutionFilter.TENANT_ID_ATTR)).isNull();
    }

    @Test
    void shouldPassThroughApiPathWhenRequirePrefixTrue() {
        // Even in strict mode, /api/** is a reserved API prefix — downstream filters
        // (TenantResolutionFilter, JWT) resolve tenant from headers/token.
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/collections").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldPassThroughReservedSegments() {
        // Slug-shaped reserved names (api/auth/otel/scim) must never be stripped.
        TenantSlugExtractionFilter filter = createFilter(true, false);
        String[] reserved = {"/api/users", "/auth/login", "/otel/v1/traces", "/scim/v2/Users"};

        for (String path : reserved) {
            MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain, atLeastOnce()).filter(exchange);
            assertThat(exchange.getAttributes().get(TenantResolutionFilter.TENANT_SLUG_ATTR))
                    .as("Path '%s' must not set a slug attribute", path)
                    .isNull();
        }
    }

    // --- Custom domain short-circuit ---

    @Test
    void shouldShortCircuitWhenCustomDomainResolved() {
        // When CustomDomainFilter has already resolved the tenant from Host,
        // this filter must not attempt to strip a slug from the path.
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(CustomDomainFilter.CUSTOM_DOMAIN_RESOLVED, true);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, "acme");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Exchange passed through untouched
        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getRequest().getPath().value()).isEqualTo("/api/users");
    }

    // --- Slug pattern validation ---

    @Test
    void shouldAcceptValidSlugPatterns() {
        // Valid slugs: lowercase, starts with letter, 3-63 chars, ends with alphanumeric
        Map<String, String> slugMap = Map.of(
                "acme", "tenant-id-acme",
                "my-org", "tenant-id-my-org",
                "company-123", "tenant-id-company-123",
                "abc", "tenant-id-abc",
                "a-b", "tenant-id-a-b"
        );
        populateCache(slugMap);
        TenantSlugExtractionFilter filter = createFilter(true, true);

        for (Map.Entry<String, String> entry : slugMap.entrySet()) {
            String slug = entry.getKey();

            MockServerHttpRequest request = MockServerHttpRequest.get("/" + slug + "/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
            when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
                capturedExchange[0] = invocation.getArgument(0);
                return Mono.empty();
            });

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(capturedExchange[0].getAttributes().get(TenantResolutionFilter.TENANT_SLUG_ATTR))
                    .as("Slug '%s' should be accepted", slug)
                    .isEqualTo(slug);
        }
    }

    @Test
    void shouldRejectInvalidSlugPatterns() {
        TenantSlugExtractionFilter filter = createFilter(true, true);

        // Invalid slugs: too short (1 char), starts with number, uppercase, ends with dash
        String[] invalidSlugs = {"a", "1org", "ACME", "org-"};

        for (String slug : invalidSlugs) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/" + slug + "/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("Slug '%s' should be rejected with 404", slug)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void shouldRejectSingleCharacterSlug() {
        // Pattern requires at least 3 chars: ^[a-z][a-z0-9-]{1,61}[a-z0-9]$
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/a/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldRejectTwoCharacterSlug() {
        // Pattern requires at least 3 chars
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/ab/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- JSON error response ---

    @Test
    void shouldReturnJsonErrorBody() {
        // Cache is empty, so "notfound" won't resolve
        TenantSlugExtractionFilter filter = createFilter(true, true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/notfound/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .contains("application/json");
    }

    // --- Reactive resolution (#1334) ---

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveASlugMissingFromTheCacheWhileRunningOnAnEventLoopThread() {
        // The real filter chain runs on a reactor-http-epoll thread. The lazy slug lookup used to
        // block there, which threw, and the swallowed exception 404'd a live tenant (#1334).
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("/internal/tenants/slug-map")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(Map.of("acme", "tenant-uuid-123")));

        TenantSlugExtractionFilter filter = createFilter(true, true);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/acme/api/users"));

        // Cold cache + a non-blocking scheduler == the production failure conditions.
        StepVerifier.create(filter.filter(exchange, chain).subscribeOn(Schedulers.parallel()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .as("a valid tenant must not 404 just because the cache was cold")
                .isNotEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getAttributes().get(TenantResolutionFilter.TENANT_ID_ATTR))
                .isEqualTo("tenant-uuid-123");
    }

    // --- Helpers ---

    private TenantSlugExtractionFilter createFilter(boolean enabled, boolean requirePrefix) {
        return new TenantSlugExtractionFilter(cacheManager, metrics, enabled, requirePrefix, PLATFORM_PATHS);
    }

    @SuppressWarnings("unchecked")
    private void populateCache(Map<String, String> slugMap) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("/internal/tenants/slug-map")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(slugMap));

        cacheManager.refreshTenantSlugsFromWorker();
    }
}
