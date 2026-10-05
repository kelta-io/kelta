package io.kelta.gateway.filter;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("TenantResolutionFilter Tests")
class TenantResolutionFilterTest {

    private GatewayMetrics metrics;
    private GatewayCacheManager cacheManager;
    private TenantResolutionFilter filter;
    private GatewayFilterChain chain;
    private ServerWebExchange forwarded;

    @BeforeEach
    void setUp() {
        metrics = mock(GatewayMetrics.class);
        cacheManager = mock(GatewayCacheManager.class);
        when(cacheManager.resolveTenantSlugReactive(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(cacheManager.resolveTenantSlugReactive("my-company")).thenReturn(Mono.just(Optional.of("tenant-1")));
        when(cacheManager.resolveTenantSlugReactive("other-co")).thenReturn(Mono.just(Optional.of("tenant-2")));
        when(cacheManager.resolveTenantIdToSlugReactive(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(cacheManager.resolveTenantIdToSlugReactive("tenant-1")).thenReturn(Mono.just(Optional.of("my-company")));
        filter = new TenantResolutionFilter(metrics, cacheManager);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            forwarded = inv.getArgument(0);
            return Mono.empty();
        });
    }

    private HttpHeaders forwardedHeaders() {
        return forwarded.getRequest().getHeaders();
    }

    @Test
    void shouldHaveCorrectOrder() {
        assertEquals(-200, filter.getOrder());
    }

    @Nested
    @DisplayName("Header claim (no tenant in the URL)")
    class HeaderClaim {

        @Test
        void shouldResolveTenantFromIdHeaderAsHeaderSourced() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-1")
                    .build());

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
            assertTrue(TenantResolutionFilter.isHeaderSourced(exchange));
            verify(metrics).recordTenantResolution("header", "success");
        }

        @Test
        void shouldResolveSlugHeaderToItsTenantId() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-Slug", "my-company")
                    .build());

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
            assertEquals("my-company", TenantResolutionFilter.getTenantSlug(exchange));
            assertTrue(TenantResolutionFilter.isHeaderSourced(exchange));
        }

        @Test
        void shouldLeaveTenantUnsetForUnknownSlugHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-Slug", "no-such-tenant")
                    .build());

            filter.filter(exchange, chain).block();

            assertNull(TenantResolutionFilter.getTenantId(exchange));
            assertNull(TenantResolutionFilter.getTenantSlug(exchange));
            verify(metrics).recordTenantResolution("header", "not_found");
        }

        @Test
        void shouldKeepSlugHeaderThatNamesTheSameTenant() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-1")
                    .header("X-Tenant-Slug", "my-company")
                    .build());

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
            assertEquals("my-company", TenantResolutionFilter.getTenantSlug(exchange));
        }

        @Test
        void shouldDropSlugHeaderThatNamesAnotherTenant() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-1")
                    .header("X-Tenant-Slug", "other-co")
                    .build());

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
            // The client's slug for another tenant is dropped; the id's own slug replaces it.
            assertEquals("my-company", TenantResolutionFilter.getTenantSlug(exchange));
        }

        @Test
        void shouldResolveSlugForIdOnlyHeaderClaim() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-1")
                    .build());

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
            assertEquals("my-company", TenantResolutionFilter.getTenantSlug(exchange));
            assertEquals(TenantResolutionFilter.TENANT_SOURCE_HEADER,
                    exchange.getAttributes().get(TenantResolutionFilter.TENANT_SOURCE_ATTR));
        }

        @Test
        void shouldClaimUnknownIdWithoutSlug() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-unknown")
                    .build());

            assertDoesNotThrow(() -> filter.filter(exchange, chain).block());

            assertEquals("tenant-unknown", TenantResolutionFilter.getTenantId(exchange));
            assertNull(TenantResolutionFilter.getTenantSlug(exchange));
            assertTrue(TenantResolutionFilter.isHeaderSourced(exchange));
        }

        @Test
        void clearHeaderSourcedTenantDropsTheResolvedSlugOfAnIdClaim() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-1")
                    .build());
            filter.filter(exchange, chain).block();

            TenantResolutionFilter.clearHeaderSourcedTenant(exchange);

            assertNull(TenantResolutionFilter.getTenantId(exchange));
            assertNull(TenantResolutionFilter.getTenantSlug(exchange));
        }

        @Test
        void shouldSkipWhenNoTenantHeaders() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());

            filter.filter(exchange, chain).block();

            assertNull(TenantResolutionFilter.getTenantId(exchange));
            assertNull(TenantResolutionFilter.getTenantSlug(exchange));
            assertFalse(TenantResolutionFilter.isHeaderSourced(exchange));
            verify(metrics).recordTenantResolution("none", "skipped");
            assertSame(exchange, forwarded);
        }

        @Test
        void shouldTrimTenantIdWhitespace() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "  tenant-1  ")
                    .build());

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
        }

        @Test
        void shouldStripClientTenantHeadersFromForwardedRequest() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-1")
                    .header("X-Tenant-Slug", "my-company")
                    .build());

            filter.filter(exchange, chain).block();

            assertNull(forwardedHeaders().getFirst("X-Tenant-ID"));
            assertNull(forwardedHeaders().getFirst("X-Tenant-Slug"));
        }

        @Test
        void clearHeaderSourcedTenantDropsTheClaim() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-Slug", "my-company")
                    .build());
            filter.filter(exchange, chain).block();

            TenantResolutionFilter.clearHeaderSourcedTenant(exchange);

            assertNull(TenantResolutionFilter.getTenantId(exchange));
            assertNull(TenantResolutionFilter.getTenantSlug(exchange));
            assertFalse(TenantResolutionFilter.isHeaderSourced(exchange));
        }
    }

    @Nested
    @DisplayName("URL tenant wins over headers")
    class UrlTenantWins {

        @Test
        void shouldIgnoreHeadersWhenSlugResolvedTenant() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-overwrite")
                    .header("X-Tenant-Slug", "other-co")
                    .build());
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "already-resolved");
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, "url-slug");

            filter.filter(exchange, chain).block();

            assertEquals("already-resolved", TenantResolutionFilter.getTenantId(exchange));
            assertEquals("url-slug", TenantResolutionFilter.getTenantSlug(exchange));
            assertFalse(TenantResolutionFilter.isHeaderSourced(exchange));
            assertNull(forwardedHeaders().getFirst("X-Tenant-ID"));
            assertNull(forwardedHeaders().getFirst("X-Tenant-Slug"));
            verify(metrics).recordTenantResolution("header", "ignored");
        }

        @Test
        void shouldNotFillUnknownUrlSlugFromHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-2")
                    .build());
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, "unknown-slug");

            filter.filter(exchange, chain).block();

            assertNull(TenantResolutionFilter.getTenantId(exchange));
            assertEquals("unknown-slug", TenantResolutionFilter.getTenantSlug(exchange));
            assertNull(forwardedHeaders().getFirst("X-Tenant-ID"));
        }

        @Test
        void shouldResolveCustomDomainSlugToIdAndIgnoreHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                    .header("X-Tenant-ID", "tenant-2")
                    .build());
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, "my-company");
            exchange.getAttributes().put(CustomDomainFilter.CUSTOM_DOMAIN_RESOLVED, true);

            filter.filter(exchange, chain).block();

            assertEquals("tenant-1", TenantResolutionFilter.getTenantId(exchange));
            assertEquals("my-company", TenantResolutionFilter.getTenantSlug(exchange));
            assertFalse(TenantResolutionFilter.isHeaderSourced(exchange));
            assertNull(forwardedHeaders().getFirst("X-Tenant-ID"));
        }
    }
}
