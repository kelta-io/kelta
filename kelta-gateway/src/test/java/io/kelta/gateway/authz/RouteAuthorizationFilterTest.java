package io.kelta.gateway.authz;

import tools.jackson.databind.ObjectMapper;
import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.PublicPathMatcher;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.route.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RouteAuthorizationFilter}.
 *
 * <p>Tests both authentication-only mode (permissions disabled) and
 * full Cerbos-based authorization mode (permissions enabled).
 */
@ExtendWith(MockitoExtension.class)
class RouteAuthorizationFilterTest {

    /** Matches the private constant in JwtAuthenticationFilter. */
    private static final String PRINCIPAL_ATTR = "gateway.principal";

    @Mock
    private RouteRegistry routeRegistry;

    @Mock
    private PublicPathMatcher publicPathMatcher;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain filterChain;

    @Mock
    private CerbosAuthorizationService cerbosService;

    @BeforeEach
    void setUp() {
        lenient().when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        lenient().when(publicPathMatcher.isPublicRequest(any(ServerWebExchange.class))).thenReturn(false);
    }

    private GatewayPrincipal principalWithIdentity(String email) {
        return new GatewayPrincipal(email, List.of("USER"), Map.of())
                .withProfileId("profile-1")
                .withProfileName("Standard User")
                .withTenantId("tenant-1");
    }

    @Test
    void shouldHaveOrderZero() {
        RouteAuthorizationFilter filter = new RouteAuthorizationFilter(
                routeRegistry, false, publicPathMatcher, metrics, new ObjectMapper(), cerbosService);
        assertThat(filter.getOrder()).isEqualTo(0);
    }

    // ================================================================
    // Permissions disabled (authentication-only mode)
    // ================================================================

    @Nested
    @DisplayName("When permissions disabled")
    class PermissionsDisabledTests {

        private RouteAuthorizationFilter filter;

        @BeforeEach
        void setUp() {
            filter = new RouteAuthorizationFilter(
                    routeRegistry, false, publicPathMatcher, metrics, new ObjectMapper(), cerbosService);
        }

        @Test
        @DisplayName("Should allow public path without principal")
        void shouldAllowPublicPathWithoutPrincipal() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/ui-pages").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(publicPathMatcher.isPublicRequest(exchange)).thenReturn(true);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(exchange);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("Should return forbidden when no principal")
        void shouldReturnForbiddenWhenNoPrincipal() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(filterChain, never()).filter(exchange);
        }

        @Test
        @DisplayName("Should allow authenticated users without Cerbos check")
        void shouldAllowAuthenticatedUsersWithoutCerbosCheck() {
            GatewayPrincipal principal = new GatewayPrincipal("user1", List.of("USER"), Map.of());
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
            verifyNoInteractions(cerbosService);
        }
    }

    // ================================================================
    // Permissions enabled (Cerbos-based enforcement)
    // ================================================================

    @Nested
    @DisplayName("When permissions enabled")
    class PermissionsEnabledTests {

        private RouteAuthorizationFilter filter;

        @BeforeEach
        void setUp() {
            filter = new RouteAuthorizationFilter(
                    routeRegistry, true, publicPathMatcher, metrics, new ObjectMapper(), cerbosService);
        }

        @Test
        @DisplayName("Should allow public path without principal")
        void shouldAllowPublicPathWithoutPrincipal() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/oidc-providers").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(publicPathMatcher.isPublicRequest(exchange)).thenReturn(true);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(exchange);
        }

        @Test
        @DisplayName("Should return forbidden when no principal")
        void shouldReturnForbiddenWhenNoPrincipal() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Should deny when principal has no profileId resolved")
        void shouldDenyWhenNoProfileResolved() {
            GatewayPrincipal principal = new GatewayPrincipal("user@test.com", List.of("USER"), Map.of());
            // No profileId or tenantId set
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(cerbosService);
        }

        @Test
        @DisplayName("Should deny a parent-tenant principal with no membership in the sandbox "
                + "tenant on a sandbox-tenant path (kelta#1539)")
        void shouldDenyParentTenantPrincipalOnSandboxPath() {
            // A parent-tenant PAT carries the parent's tenantId from its own claims
            // (PatAuthenticationFilter.withTenantId), but UserIdentityResolutionFilter only
            // resolves profileId/profileName by looking up the caller's email against the
            // *target* tenant of the request. When that target is a sandbox the parent-tenant
            // caller has no membership in, the lookup finds no profile and leaves it unset —
            // there is no "run as platform tenant" fallback into a sandbox.
            GatewayPrincipal principal = new GatewayPrincipal("parent-admin@test.com", List.of("USER"), Map.of())
                    .withTenantId("parent-tenant");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/collections").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "sandbox-tenant");

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(cerbosService);
            verify(filterChain, never()).filter(any());
        }

        @Test
        @DisplayName("Should deny when missing API_ACCESS system permission")
        void shouldDenyWithoutApiAccess() {
            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Should allow non-API paths with identity")
        void shouldAllowNonApiPaths() {
            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
            verifyNoInteractions(cerbosService);
        }

        @Test
        @DisplayName("Resolves the route for the caller's own tenant, never a same-named collection elsewhere")
        void shouldLookUpTheRouteForTheCallersTenant() {
            RouteDefinition own = new RouteDefinition("coll-own", "/api/users/**",
                    "http://worker:80", "users", null, 0, "tenant-1");
            when(routeRegistry.findByPath(eq("/api/users"), eq("tenant-1"))).thenReturn(Optional.of(own));
            when(cerbosService.checkSystemPermission(any(), eq("API_ACCESS"))).thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(any(), eq("coll-own"), eq("read"))).thenReturn(Mono.just(true));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(routeRegistry).findByPath("/api/users", "tenant-1");
            verify(cerbosService).checkObjectPermission(any(), eq("coll-own"), eq("read"));
            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Should allow GET when Cerbos grants read")
        void shouldAllowGetWithCerbosRead() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "read"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Should deny GET when Cerbos denies read")
        void shouldDenyGetWhenCerbosDeniesRead() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "read"))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Should allow POST when Cerbos grants create")
        void shouldAllowPostWithCerbosCreate() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "create"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Should deny POST when Cerbos denies create")
        void shouldDenyPostWhenCerbosDeniesCreate() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "create"))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Should allow PUT when Cerbos grants edit")
        void shouldAllowPutWithCerbosEdit() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users/123"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.put("/api/users/123").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "edit"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Should allow DELETE when Cerbos grants delete")
        void shouldAllowDeleteWithCerbosDelete() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users/123"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.delete("/api/users/123").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "delete"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Should deny DELETE when Cerbos denies delete")
        void shouldDenyDeleteWhenCerbosDeniesDelete() {
            RouteDefinition route = new RouteDefinition("coll-1", "/api/users/**",
                    "http://worker:80", "users");
            when(routeRegistry.findByPath(eq("/api/users/123"), any())).thenReturn(Optional.of(route));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.delete("/api/users/123").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));
            when(cerbosService.checkObjectPermission(principal, "coll-1", "delete"))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Should allow through when no matching route found")
        void shouldAllowThroughWhenNoRouteFound() {
            when(routeRegistry.findByPath(eq("/api/unknown"), any())).thenReturn(Optional.empty());

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/unknown").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Should skip collection-level Cerbos check for static routes")
        void shouldSkipCollectionCheckForStaticRoutes() {
            RouteDefinition staticRoute = new RouteDefinition("static-admin", "/api/admin/**",
                    "http://worker:80", "admin");
            when(routeRegistry.findByPath(eq("/api/admin/collections"), any())).thenReturn(Optional.of(staticRoute));

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/admin/collections").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            // Should allow through after API_ACCESS check without collection-level check
            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @Test
        @DisplayName("Should forward identity headers to worker")
        void shouldForwardIdentityHeaders() {
            when(routeRegistry.findByPath(eq("/api/users"), any())).thenReturn(Optional.empty());

            GatewayPrincipal principal = principalWithIdentity("user@test.com");
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);

            when(cerbosService.checkSystemPermission(principal, "API_ACCESS"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .expectComplete()
                    .verify();

            // Verify the chain was called with mutated exchange containing headers
            verify(filterChain).filter(argThat(ex -> {
                ServerWebExchange mutated = (ServerWebExchange) ex;
                return "user@test.com".equals(mutated.getRequest().getHeaders().getFirst("X-User-Email"))
                        && "profile-1".equals(mutated.getRequest().getHeaders().getFirst("X-User-Profile-Id"))
                        && "tenant-1".equals(mutated.getRequest().getHeaders().getFirst("X-Cerbos-Scope"));
            }));
        }
        @Nested
        @DisplayName("Reads of /api/tenants")
        class TenantsReadScopingTests {

            private final RouteDefinition tenantsRoute = new RouteDefinition("static-tenants", "/api/tenants/**",
                    "http://worker:80", "tenants");

            private MockServerWebExchange exchangeFor(MockServerHttpRequest request, GatewayPrincipal principal) {
                MockServerWebExchange exchange = MockServerWebExchange.from(request);
                exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
                when(routeRegistry.findByPath(eq(request.getPath().value()), any())).thenReturn(Optional.of(tenantsRoute));
                when(cerbosService.checkSystemPermission(principal, "API_ACCESS")).thenReturn(Mono.just(true));
                return exchange;
            }

            private ServerWebExchange forwarded() {
                ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
                verify(filterChain).filter(captor.capture());
                return captor.getValue();
            }

            @Test
            @DisplayName("A list without MANAGE_TENANTS is narrowed to the caller's own tenant")
            void listIsNarrowedToOwnTenant() {
                GatewayPrincipal principal = principalWithIdentity("user@test.com");
                MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/tenants")
                        .queryParam("page[size]", "200").build(), principal);
                when(cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")).thenReturn(Mono.just(false));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                ServerWebExchange out = forwarded();
                assertThat(out.getRequest().getQueryParams().get("filter[id][eq]")).containsExactly("tenant-1");
                assertThat(out.getRequest().getQueryParams().getFirst("page[size]")).isEqualTo("200");
            }

            @Test
            @DisplayName("A caller-supplied id filter is dropped so it can never override the narrowing")
            void callerIdFilterIsDropped() {
                GatewayPrincipal principal = principalWithIdentity("user@test.com");
                MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/tenants")
                        .queryParam("filter[id][eq]", "victim-tenant")
                        .queryParam("filter[ID]", "victim-tenant")
                        .queryParam("filter[id][in]", "victim-tenant,tenant-1")
                        .queryParam("filter[slug][eq]", "acme").build(), principal);
                when(cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")).thenReturn(Mono.just(false));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                var params = forwarded().getRequest().getQueryParams();
                assertThat(params.get("filter[id][eq]")).containsExactly("tenant-1");
                assertThat(params).doesNotContainKeys("filter[ID]", "filter[id][in]");
                assertThat(params.getFirst("filter[slug][eq]")).isEqualTo("acme");
            }

            @Test
            @DisplayName("Another tenant's record is a 404 and never reaches the worker")
            void otherTenantRecordIsNotFound() {
                GatewayPrincipal principal = principalWithIdentity("user@test.com");
                MockServerWebExchange exchange = exchangeFor(
                        MockServerHttpRequest.get("/api/tenants/victim-tenant").build(), principal);
                when(cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")).thenReturn(Mono.just(false));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                verify(filterChain, never()).filter(any());
            }

            @Test
            @DisplayName("The caller's own tenant record passes through")
            void ownTenantRecordPasses() {
                GatewayPrincipal principal = principalWithIdentity("user@test.com");
                MockServerWebExchange exchange = exchangeFor(
                        MockServerHttpRequest.get("/api/tenants/tenant-1").build(), principal);
                when(cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")).thenReturn(Mono.just(false));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(forwarded().getRequest().getPath().value()).isEqualTo("/api/tenants/tenant-1");
            }

            @Test
            @DisplayName("A platform admin (MANAGE_TENANTS) keeps the full, unmodified list")
            void platformAdminListIsUnchanged() {
                GatewayPrincipal principal = principalWithIdentity("admin@test.com");
                MockServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/tenants")
                        .queryParam("filter[id][eq]", "other-tenant").build(), principal);
                when(cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")).thenReturn(Mono.just(true));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(forwarded().getRequest().getQueryParams().get("filter[id][eq]"))
                        .containsExactly("other-tenant");
            }
        }
    }
}
