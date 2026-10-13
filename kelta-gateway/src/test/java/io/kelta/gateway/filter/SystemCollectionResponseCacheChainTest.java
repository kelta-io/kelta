package io.kelta.gateway.filter;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.PublicPathMatcher;
import io.kelta.gateway.authz.RouteAuthorizationFilter;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Runs the system-collection response cache together with {@link RouteAuthorizationFilter}, in
 * gateway order, in front of a stand-in worker whose answer depends on the caller.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("System-collection response cache in the filter chain")
class SystemCollectionResponseCacheChainTest {

    private static final String TENANT = "tenant-1";
    private static final String PRINCIPAL_ATTR = "gateway.principal";
    /** What the stand-in worker answered; written to the client by {@link #writeResponseStandIn}. */
    private static final String BACKEND_BODY_ATTR = "test.backendBody";
    private static final String BACKEND_STATUS_ATTR = "test.backendStatus";

    @Mock
    private RouteRegistry routeRegistry;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private CerbosAuthorizationService cerbos;

    private GatewayCacheManager cacheManager;
    private List<GlobalFilter> filters;
    private final AtomicInteger workerCalls = new AtomicInteger();
    private final List<String> workerQueries = new ArrayList<>();
    private HttpStatus workerStatus = HttpStatus.OK;

    @BeforeEach
    void setUp() {
        cacheManager = new GatewayCacheManager(WebClient.builder(), "http://worker.invalid");
        PublicPathMatcher publicPaths = new PublicPathMatcher(
                List.of("/api/ui-pages", "/api/tenants"), List.of(), List.of("/api/tenants"));
        RouteAuthorizationFilter routeAuthorization = new RouteAuthorizationFilter(
                routeRegistry, true, publicPaths, metrics, new ObjectMapper(), cerbos);

        filters = new ArrayList<>(CacheFilters.create(cacheManager));
        filters.add(routeAuthorization);
        filters.add(writeResponseStandIn());

        when(routeRegistry.findByPath(anyString(), eq(TENANT))).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            if (path.startsWith("/api/tenants")) {
                return Optional.of(new RouteDefinition("static-tenants", "/api/tenants/**",
                        "http://worker:80", "tenants"));
            }
            if (path.startsWith("/api/page-layouts")) {
                return Optional.of(new RouteDefinition("coll-page-layouts", "/api/page-layouts/**",
                        "http://worker:80", "page-layouts", null, 0, TENANT));
            }
            return Optional.empty();
        });
        when(cerbos.checkSystemPermission(any(), eq("API_ACCESS"))).thenReturn(Mono.just(true));
        when(cerbos.checkSystemPermission(any(), eq("MANAGE_TENANTS")))
                .thenAnswer(inv -> Mono.just("platform-admin".equals(
                        ((GatewayPrincipal) inv.getArgument(0)).getProfileId())));
        when(cerbos.checkObjectPermission(any(), eq("coll-page-layouts"), eq("read")))
                .thenAnswer(inv -> Mono.just(!"no-layouts".equals(
                        ((GatewayPrincipal) inv.getArgument(0)).getProfileId())));
        when(cerbos.checkObjectPermission(any(), eq("coll-page-layouts"), eq("edit"))).thenReturn(Mono.just(true));
    }

    @Test
    @DisplayName("a second caller with another profile gets the worker's answer for that caller")
    void anotherProfileIsAnsweredForItself() {
        assertThat(get("/api/page-layouts?include=sections", caller("a@example.test", "profile-a")).body)
                .contains("a@example.test");

        Response second = get("/api/page-layouts?include=sections", caller("b@example.test", "profile-b"));

        assertThat(second.status).isEqualTo(HttpStatus.OK);
        assertThat(second.body).contains("b@example.test").doesNotContain("a@example.test");
        assertThat(workerCalls).hasValue(2);
    }

    @Test
    @DisplayName("a second user on the same profile gets the worker's answer for that user")
    void anotherUserOnTheSameProfileIsAnsweredForItself() {
        get("/api/page-layouts", caller("a@example.test", "profile-a"));

        Response second = get("/api/page-layouts", caller("b@example.test", "profile-a"));

        assertThat(second.body).contains("b@example.test").doesNotContain("a@example.test");
        assertThat(workerCalls).hasValue(2);
    }

    @Test
    @DisplayName("route authorization runs for every request, including one whose response is cached")
    void routeAuthorizationRunsOnEveryRequest() {
        GatewayPrincipal allowed = caller("a@example.test", "profile-a");
        get("/api/page-layouts", allowed);
        assertThat(get("/api/page-layouts", allowed).status).isEqualTo(HttpStatus.OK);

        // The same user after an administrator moved them to a profile without read access.
        Response denied = get("/api/page-layouts", caller("a@example.test", "no-layouts"));

        assertThat(denied.status).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.body).doesNotContain("\"caller\"");
        assertThat(workerCalls).hasValue(1);
    }

    @Test
    @DisplayName("the same caller repeating a read is answered from the cache")
    void sameCallerIsServedFromCache() {
        GatewayPrincipal a = caller("a@example.test", "profile-a");

        Response first = get("/api/page-layouts?page[size]=50", a);
        Response second = get("/api/page-layouts?page[size]=50", a);

        assertThat(second.status).isEqualTo(HttpStatus.OK);
        assertThat(second.body).isEqualTo(first.body);
        assertThat(workerCalls).hasValue(1);
    }

    @Test
    @DisplayName("an anonymous tenants lookup gets its own narrowed answer after a platform admin's read")
    void anonymousTenantsLookupIsNarrowedAfterAnAdminRead() {
        Response admin = get("/api/tenants", caller("admin@example.test", "platform-admin"));
        assertThat(admin.body).contains("admin@example.test");

        Response anonymous = get("/api/tenants", null);

        assertThat(anonymous.status).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.body).contains("\"caller\":\"anonymous\"").doesNotContain("admin@example.test");
        assertThat(workerQueries.getLast()).contains("filter%5Bid%5D%5Beq%5D=" + TENANT);
        assertThat(workerCalls).hasValue(2);
    }

    @Test
    @DisplayName("an authenticated tenants read does not get a cached anonymous answer")
    void authenticatedTenantsReadAfterAnonymousLookup() {
        get("/api/tenants", null);

        Response member = get("/api/tenants", caller("a@example.test", "profile-a"));

        assertThat(member.body).contains("a@example.test");
        assertThat(workerCalls).hasValue(2);
    }

    @Test
    @DisplayName("a write evicts the cached reads of every caller")
    void writeEvictsEveryCallersEntries() {
        GatewayPrincipal a = caller("a@example.test", "profile-a");
        GatewayPrincipal b = caller("b@example.test", "profile-b");
        get("/api/page-layouts", a);
        get("/api/page-layouts", b);
        assertThat(cacheManager.systemCollectionResponseCacheSize()).isEqualTo(2);

        run(MockServerHttpRequest.patch("/api/page-layouts/1").build(), a);

        assertThat(cacheManager.systemCollectionResponseCacheSize()).isZero();
    }

    @Test
    @DisplayName("NATS-driven eviction by collection name still removes the caller-keyed entries")
    void collectionEvictionRemovesCallerKeyedEntries() {
        get("/api/page-layouts", caller("a@example.test", "profile-a"));
        get("/api/page-layouts", caller("b@example.test", "profile-b"));

        cacheManager.evictSystemCollectionResponses("page-layouts");

        assertThat(cacheManager.systemCollectionResponseCacheSize()).isZero();
    }

    @Test
    @DisplayName("a non-200 worker answer is not cached")
    void errorsAreNotCached() {
        GatewayPrincipal a = caller("a@example.test", "profile-a");
        workerStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        get("/api/page-layouts", a);
        workerStatus = HttpStatus.OK;

        assertThat(get("/api/page-layouts", a).status).isEqualTo(HttpStatus.OK);
        assertThat(workerCalls).hasValue(2);
    }

    // ------------------------------------------------------------------

    private static GatewayPrincipal caller(String email, String profileId) {
        return new GatewayPrincipal(email, List.of(), Map.of())
                .withProfileId(profileId)
                .withTenantId(TENANT);
    }

    private record Response(HttpStatus status, String body) {}

    private Response get(String uri, GatewayPrincipal principal) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(uri);
        if (principal != null) {
            request.header("Authorization", "Bearer token-for-" + principal.getUsername());
        }
        return run(request.build(), principal);
    }

    private Response run(MockServerHttpRequest request, GatewayPrincipal principal) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, TENANT);
        if (principal != null) {
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
        }
        chain(0).filter(exchange).block();
        HttpStatus status = HttpStatus.resolve(exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value() : 200);
        String body = exchange.getResponse().getBodyAsString().blockOptional().orElse("");
        return new Response(status, body);
    }

    /** Spring Cloud Gateway's chain: global filters by order, then the routed backend call. */
    private GatewayFilterChain chain(int index) {
        List<GlobalFilter> ordered = filters.stream()
                .sorted(Comparator.comparingInt(f -> ((Ordered) f).getOrder()))
                .toList();
        if (index >= ordered.size()) {
            return this::worker;
        }
        return exchange -> Mono.defer(() -> ordered.get(index).filter(exchange, chain(index + 1)));
    }

    /** The routed call: records the worker's answer for {@link #writeResponseStandIn}. */
    private Mono<Void> worker(ServerWebExchange exchange) {
        workerCalls.incrementAndGet();
        workerQueries.add(String.valueOf(exchange.getRequest().getURI().getRawQuery()));
        String email = exchange.getRequest().getHeaders().getFirst("X-User-Email");
        String caller = email != null ? email : "anonymous";
        exchange.getAttributes().put(BACKEND_STATUS_ATTR, workerStatus);
        exchange.getAttributes().put(BACKEND_BODY_ATTR,
                "{\"data\":[{\"caller\":\"" + caller + "\"}]}");
        return Mono.empty();
    }

    /**
     * Stand-in for {@code NettyWriteResponseFilter} (order -1): once the chain returns, it writes
     * the backend answer to the response of the exchange <em>it</em> was given — so only a
     * response decorator installed by a filter ordered before it sees the body.
     */
    private static GlobalFilter writeResponseStandIn() {
        return new OrderedFilter(-1) {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                return chain.filter(exchange).then(Mono.defer(() -> {
                    String body = exchange.getAttribute(BACKEND_BODY_ATTR);
                    if (body == null) {
                        return Mono.empty();
                    }
                    exchange.getResponse().setStatusCode(exchange.getAttribute(BACKEND_STATUS_ATTR));
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return exchange.getResponse().writeWith(Mono.just(
                            exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
                }));
            }
        };
    }

    private abstract static class OrderedFilter implements GlobalFilter, Ordered {
        private final int order;

        OrderedFilter(int order) {
            this.order = order;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    /** The cache's global filters. */
    private static final class CacheFilters {
        static List<GlobalFilter> create(GatewayCacheManager cacheManager) {
            return List.of(new SystemCollectionResponseCaptureFilter(cacheManager),
                    new SystemCollectionResponseCacheFilter(cacheManager));
        }
    }
}
