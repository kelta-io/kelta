package io.kelta.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for IdentityHeaderStripFilter.
 *
 * Tests verify that:
 * - Client-supplied internal identity headers are stripped before the chain continues
 * - Requests without any identity header pass through unchanged (same exchange instance)
 * - Every header in INTERNAL_IDENTITY_HEADERS is stripped
 * - Unrelated headers survive untouched
 * - The filter runs before the custom-domain filter (order < -310)
 */
@DisplayName("IdentityHeaderStripFilter Tests")
class IdentityHeaderStripFilterTest {

    private IdentityHeaderStripFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new IdentityHeaderStripFilter();
        chain = mock(GatewayFilterChain.class);
    }

    @Test
    @DisplayName("Should strip client-supplied identity headers before the chain")
    void shouldStripClientSuppliedIdentityHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header("X-User-Profile-Id", "forged-profile")
                .header("X-User-Email", "attacker@evil.example")
                .header("X-Cerbos-Scope", "forged-scope")
                .header("X-Forwarded-User", "forged-user")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Profile-Id")).isNull();
        assertThat(headers.getFirst("X-User-Email")).isNull();
        assertThat(headers.getFirst("X-Cerbos-Scope")).isNull();
        assertThat(headers.getFirst("X-Forwarded-User")).isNull();
    }

    @Test
    @DisplayName("Should pass exchange through unchanged when no identity headers present")
    void shouldPassThroughUnchangedWhenNoIdentityHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, times(1)).filter(any(ServerWebExchange.class));
        assertThat(capturedExchange[0]).isSameAs(exchange);
    }

    @Test
    @DisplayName("Should strip every header in INTERNAL_IDENTITY_HEADERS")
    void shouldStripEveryInternalIdentityHeader() {
        for (String headerName : IdentityHeaderStripFilter.INTERNAL_IDENTITY_HEADERS) {
            GatewayFilterChain localChain = mock(GatewayFilterChain.class);
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/users")
                    .header(headerName, "forged-value")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
            when(localChain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
                capturedExchange[0] = invocation.getArgument(0);
                return Mono.empty();
            });

            StepVerifier.create(filter.filter(exchange, localChain))
                    .verifyComplete();

            assertThat(capturedExchange[0])
                    .as("chain should be invoked for header %s", headerName)
                    .isNotNull();
            assertThat(capturedExchange[0].getRequest().getHeaders().getFirst(headerName))
                    .as("header %s should be stripped", headerName)
                    .isNull();
        }
    }

    @Test
    @DisplayName("Should preserve unrelated headers when stripping identity headers")
    void shouldPreserveUnrelatedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Tenant-ID", "tenant-1")
                .header("X-User-Email", "attacker@evil.example")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-token");
        assertThat(headers.getFirst("X-Tenant-ID")).isEqualTo("tenant-1");
        assertThat(headers.getFirst("X-User-Email")).isNull();
    }

    @Test
    @DisplayName("Leaves tenant headers for TenantResolutionFilter, which reads then strips them")
    void shouldLeaveTenantHeadersToTenantResolutionFilter() {
        assertThat(IdentityHeaderStripFilter.INTERNAL_IDENTITY_HEADERS)
                .doesNotContain(TenantResolutionFilter.TENANT_ID_HEADER, TenantResolutionFilter.TENANT_SLUG_HEADER);
        assertThat(filter.getOrder()).isLessThan(new TenantResolutionFilter(null, null).getOrder());
    }

    @Test
    @DisplayName("Should run before the custom-domain filter (order < -310)")
    void shouldRunBeforeCustomDomainFilter() {
        assertThat(filter.getOrder()).isLessThan(-310);
    }

    @Test
    @DisplayName("Should strip client-spoofed geo headers")
    void shouldStripSpoofedGeoHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header("X-Geo-Country", "US")
                .header("X-Geo-City", "Forgedville")
                .header("X-Geo-Lat", "1.0")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Geo-Country")).isNull();
        assertThat(headers.getFirst("X-Geo-City")).isNull();
        assertThat(headers.getFirst("X-Geo-Lat")).isNull();
    }
}
