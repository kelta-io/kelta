package io.kelta.gateway.filter;

import io.kelta.gateway.TestFixtures;
import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.PublicPathMatcher;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.config.TenantIpConfig;
import io.kelta.gateway.geo.ClientIpResolver;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantIpAllowlistFilter}: per-tenant IP allowlist enforcement,
 * "match anywhere in the chain" semantics, admin bypass, and the fail-open paths.
 */
@ExtendWith(MockitoExtension.class)
class TenantIpAllowlistFilterTest {

    @Mock private GatewayFilterChain chain;
    @Mock private WebClient.Builder webClientBuilder;
    @Mock private WebClient webClient;
    @Mock private CerbosAuthorizationService cerbos;
    @Mock private GatewayMetrics metrics;

    private GatewayCacheManager cacheManager;
    private TenantIpAllowlistFilter filter;

    private static final String TENANT_ID = TestFixtures.TENANT_ID;

    @BeforeEach
    void setUp() {
        lenient().when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        lenient().when(webClientBuilder.build()).thenReturn(webClient);
        cacheManager = new GatewayCacheManager(webClientBuilder, "http://localhost:8080");
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
        filter = newFilter(true, true);
    }

    private TenantIpAllowlistFilter newFilter(boolean enabled, boolean trustXff) {
        return newFilter(enabled, new ClientIpResolver(trustXff));
    }

    private TenantIpAllowlistFilter newFilter(boolean enabled, ClientIpResolver resolver) {
        PublicPathMatcher publicPathMatcher =
                new PublicPathMatcher(Collections.emptyList(), Collections.emptyList());
        return new TenantIpAllowlistFilter(cacheManager, cerbos, publicPathMatcher, metrics,
                new ObjectMapper(), resolver, enabled);
    }

    private ServerWebExchange exchange(String remoteIp, String xff) {
        return exchange(remoteIp, xff, null);
    }

    private ServerWebExchange exchange(String remoteIp, String xff, String realIp) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/customers");
        if (xff != null) {
            builder.header("X-Forwarded-For", xff);
        }
        if (realIp != null) {
            builder.header("X-Real-IP", realIp);
        }
        MockServerHttpRequest request = builder
                .remoteAddress(new InetSocketAddress(remoteIp, 40000))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("gateway.principal", TestFixtures.principal(TENANT_ID));
        exchange.getAttributes().put("tenantId", TENANT_ID);
        return exchange;
    }

    private void restrictTo(String... cidrs) {
        cacheManager.loadTenantIpConfigs(
                Map.of(TENANT_ID, new TenantIpConfig(true, List.of(cidrs))));
    }

    // ── In-range / out-of-range ───────────────────────────────────────────

    @Test
    void inRangeNonAdmin_allowed() {
        restrictTo("10.0.0.0/8");
        ServerWebExchange exchange = exchange("10.1.2.3", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(cerbos, never()).checkSystemPermission(any(), anyString());
    }

    @Test
    void outOfRangeNonAdmin_forbidden() {
        restrictTo("10.0.0.0/8");
        when(cerbos.checkSystemPermission(any(GatewayPrincipal.class), eq("MANAGE_TENANTS")))
                .thenReturn(Mono.just(false));
        ServerWebExchange exchange = exchange("203.0.113.5", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void outOfRangeAdmin_bypassAllowed() {
        restrictTo("10.0.0.0/8");
        when(cerbos.checkSystemPermission(any(GatewayPrincipal.class), eq("MANAGE_TENANTS")))
                .thenReturn(Mono.just(true));
        ServerWebExchange exchange = exchange("203.0.113.5", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    // ── Chain / spoofing semantics ────────────────────────────────────────

    @Test
    void matchInMiddleForwardedForHop_allowed() {
        restrictTo("10.0.0.0/8");
        // socket + first hop are outside; a middle hop is inside → allowed.
        ServerWebExchange exchange = exchange("203.0.113.5", "8.8.8.8, 10.0.0.7, 203.0.113.5");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(cerbos, never()).checkSystemPermission(any(), anyString());
    }

    @Test
    void forwardedForNotTrusted_spoofIgnored() {
        filter = newFilter(true, false); // trust-forwarded-for = false
        restrictTo("10.0.0.0/8");
        when(cerbos.checkSystemPermission(any(GatewayPrincipal.class), eq("MANAGE_TENANTS")))
                .thenReturn(Mono.just(false));
        // Spoofed allowed IP in XFF must be ignored; only the socket (out of range) counts.
        ServerWebExchange exchange = exchange("203.0.113.5", "10.0.0.7");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void ipv6InRange_allowed() {
        restrictTo("2001:db8::/32");
        ServerWebExchange exchange = exchange("2001:db8::1", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    // ── Trusted-proxy mode (kelta.security.trusted-proxies) ───────────────

    private static ClientIpResolver trustingIngress() {
        return new ClientIpResolver(true, List.of("10.42.0.0/16"));
    }

    @Test
    void trustedProxies_untrustedPeerForgingAllowedXffOrRealIp_forbidden() {
        filter = newFilter(true, trustingIngress());
        restrictTo("198.51.100.0/24");
        when(cerbos.checkSystemPermission(any(GatewayPrincipal.class), eq("MANAGE_TENANTS")))
                .thenReturn(Mono.just(false));
        ServerWebExchange exchange = exchange("203.0.113.5", "198.51.100.1", "198.51.100.2");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void trustedProxies_trustedPeerWithAllowedClient_allowed() {
        filter = newFilter(true, trustingIngress());
        restrictTo("198.51.100.0/24");
        ServerWebExchange exchange = exchange("10.42.0.7", "198.51.100.1");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(cerbos, never()).checkSystemPermission(any(), anyString());
    }

    @Test
    void trustedProxies_trustedPeerWithDisallowedClient_forbiddenEvenIfLeftHopAllowed() {
        filter = newFilter(true, trustingIngress());
        restrictTo("198.51.100.0/24");
        when(cerbos.checkSystemPermission(any(GatewayPrincipal.class), eq("MANAGE_TENANTS")))
                .thenReturn(Mono.just(false));
        // Client prepended an allowed address; the ingress appended the real one.
        ServerWebExchange exchange = exchange("10.42.0.7", "198.51.100.1, 203.0.113.9");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    // ── Fail-open paths ───────────────────────────────────────────────────

    @Test
    void restrictionDisabled_passthrough() {
        cacheManager.loadTenantIpConfigs(
                Map.of(TENANT_ID, new TenantIpConfig(false, List.of("10.0.0.0/8"))));
        ServerWebExchange exchange = exchange("203.0.113.5", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(cerbos, never()).checkSystemPermission(any(), anyString());
    }

    @Test
    void emptyCidrList_passthrough() {
        cacheManager.loadTenantIpConfigs(
                Map.of(TENANT_ID, new TenantIpConfig(true, List.of())));
        ServerWebExchange exchange = exchange("203.0.113.5", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void configMissing_failsOpen() {
        // Nothing loaded for this tenant (e.g. worker unreachable at bootstrap).
        ServerWebExchange exchange = exchange("203.0.113.5", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verify(cerbos, never()).checkSystemPermission(any(), anyString());
    }

    @Test
    void globalKillSwitchOff_passthrough() {
        filter = newFilter(false, true);
        restrictTo("10.0.0.0/8");
        ServerWebExchange exchange = exchange("203.0.113.5", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void nonApiPath_passthrough() {
        restrictTo("10.0.0.0/8");
        MockServerHttpRequest request = MockServerHttpRequest.get("/dashboard")
                .remoteAddress(new InetSocketAddress("203.0.113.5", 40000))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("gateway.principal", TestFixtures.principal(TENANT_ID));
        exchange.getAttributes().put("tenantId", TENANT_ID);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void noPrincipal_passthrough() {
        restrictTo("10.0.0.0/8");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/customers")
                .remoteAddress(new InetSocketAddress("203.0.113.5", 40000))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void filterOrder_runsBeforeRouteAuthz() {
        assertEquals(-40, filter.getOrder());
    }
}
