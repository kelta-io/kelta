package io.kelta.gateway.authz;

import tools.jackson.databind.ObjectMapper;
import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.PublicPathMatcher;
import io.kelta.gateway.filter.CustomDomainFilter;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.gateway.route.StaticCollectionCheck;
import io.kelta.gateway.service.RouteConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
        @DisplayName("Public bootstrap read with no resolved tenant is a 404, never forwarded")
        void publicBootstrapReadWithoutTenantIsNotFound() {
            for (String path : List.of("/api/ui-pages", "/api/ui-menus", "/api/oidc-providers", "/api/ui-translations")) {
                MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
                when(publicPathMatcher.isPublicRequest(exchange)).thenReturn(true);
                when(publicPathMatcher.isTenantScopedPublicPath(path)).thenReturn(true);

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }
            verify(filterChain, never()).filter(any());
        }

        @Test
        @DisplayName("Public bootstrap read for a resolved tenant is forwarded")
        void publicBootstrapReadWithTenantIsForwarded() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/ui-pages").build());
            exchange.getAttributes().put("tenantId", "tenant-1");
            when(publicPathMatcher.isPublicRequest(exchange)).thenReturn(true);
            lenient().when(publicPathMatcher.isTenantScopedPublicPath("/api/ui-pages")).thenReturn(true);

            StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

            verify(filterChain).filter(exchange);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("Public bootstrap read on a verified custom domain is forwarded")
        void publicBootstrapReadOnCustomDomainIsForwarded() {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/ui-menus").build());
            exchange.getAttributes().put(CustomDomainFilter.CUSTOM_DOMAIN_RESOLVED, true);
            exchange.getAttributes().put("tenantSlug", "acme");
            when(publicPathMatcher.isPublicRequest(exchange)).thenReturn(true);
            lenient().when(publicPathMatcher.isTenantScopedPublicPath("/api/ui-menus")).thenReturn(true);

            StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

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
        @DisplayName("Should deny a plain connected-app token (no profile) but authorize a run-as one as its user")
        void connectedAppTokenShapes() {
            Map<String, Object> appClaims = Map.of("sub", "kelta_app_client", "tenant_id", "tenant-1",
                    "connected_app_id", "app-1", "auth_method", "api_key");
            GatewayPrincipal plain = connectedAppPrincipal(appClaims);
            MockServerWebExchange plainExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users").build());
            plainExchange.getAttributes().put(PRINCIPAL_ATTR, plain);

            StepVerifier.create(filter.filter(plainExchange, filterChain)).expectComplete().verify();

            assertThat(plainExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(cerbosService);

            Map<String, Object> runAsClaims = new java.util.HashMap<>(appClaims);
            runAsClaims.putAll(Map.of("run_as_user_id", "22222222-2222-2222-2222-222222222222",
                    "user_type", "SERVICE", "profile_id", "module-profile",
                    "email", "acme-sync.1a2b3c4d@service.kelta.invalid"));
            GatewayPrincipal runAs = connectedAppPrincipal(runAsClaims);
            MockServerWebExchange runAsExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users").build());
            runAsExchange.getAttributes().put(PRINCIPAL_ATTR, runAs);
            when(cerbosService.checkSystemPermission(runAs, "API_ACCESS")).thenReturn(Mono.just(false));

            StepVerifier.create(filter.filter(runAsExchange, filterChain)).expectComplete().verify();

            // Past the identity gate: the decision is Cerbos's (API_ACCESS), not "identity not resolved".
            verify(cerbosService).checkSystemPermission(runAs, "API_ACCESS");
        }

        private GatewayPrincipal connectedAppPrincipal(Map<String, Object> claims) {
            org.springframework.security.oauth2.jwt.Jwt.Builder jwt =
                    org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token").header("alg", "RS256");
            claims.forEach(jwt::claim);
            return new io.kelta.gateway.auth.PrincipalExtractor().extractPrincipal(jwt.build());
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

        @Nested
        @DisplayName("Portal principals on /api/admin/**")
        class PortalOnAdminTests {

            private final RouteDefinition adminRoute = new RouteDefinition("static-admin", "/api/admin/**",
                    "http://worker:80", "admin");

            private GatewayPrincipal jwtPrincipal(String userType) {
                return new GatewayPrincipal("member@test.com", List.of(), Map.of("user_type", userType))
                        .withProfileId("portal-profile")
                        .withProfileName("Portal User")
                        .withTenantId("tenant-1");
            }

            /** A PAT principal as UserIdentityResolutionFilter leaves it: owner's user_type as a claim. */
            private GatewayPrincipal patPrincipal(String ownerUserType) {
                return new GatewayPrincipal("member@test.com", List.of(), Map.of("pat", "true"))
                        .withProfileId("portal-profile")
                        .withProfileName("Portal User")
                        .withTenantId("tenant-1")
                        .withClaim("user_type", ownerUserType);
            }

            private MockServerWebExchange run(GatewayPrincipal principal, MockServerHttpRequest request) {
                MockServerWebExchange exchange = MockServerWebExchange.from(request);
                exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
                when(routeRegistry.findByPath(eq(request.getPath().value()), any())).thenReturn(Optional.of(adminRoute));
                when(cerbosService.checkSystemPermission(principal, "API_ACCESS")).thenReturn(Mono.just(true));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();
                return exchange;
            }

            static Stream<Arguments> adminRequests() {
                return Stream.of(
                        Arguments.of("PUT", "/api/admin/tenant/email-settings"),
                        Arguments.of("POST", "/api/admin/tenant/email-settings/test"),
                        Arguments.of("POST", "/api/admin/profiles/p-1/custom-rules"),
                        Arguments.of("GET", "/api/admin/observability/request-logs"));
            }

            @ParameterizedTest(name = "{0} {1}")
            @MethodSource("adminRequests")
            @DisplayName("Portal JWT holding API_ACCESS is denied 403")
            void portalJwtIsForbidden(String method, String path) {
                MockServerWebExchange exchange = run(jwtPrincipal("PORTAL"),
                        MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                verify(filterChain, never()).filter(any());
            }

            @ParameterizedTest(name = "{0} {1}")
            @MethodSource("adminRequests")
            @DisplayName("PAT owned by a portal member holding API_ACCESS is denied 403")
            void portalPatIsForbidden(String method, String path) {
                MockServerWebExchange exchange = run(patPrincipal("PORTAL"),
                        MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                verify(filterChain, never()).filter(any());
            }

            @Test
            @DisplayName("Portal denial uses the JSON:API 403 envelope")
            void portalDenialBodyIsJsonApi() {
                MockServerWebExchange exchange = run(jwtPrincipal("PORTAL"),
                        MockServerHttpRequest.put("/api/admin/tenant/email-settings").build());

                assertThat(exchange.getResponse().getHeaders().getContentType())
                        .isEqualTo(MediaType.APPLICATION_JSON);
                StepVerifier.create(exchange.getResponse().getBodyAsString())
                        .assertNext(body -> assertThat(body)
                                .contains("\"status\":\"403\"")
                                .contains("Portal users may not access admin endpoints"))
                        .verifyComplete();
            }

            @ParameterizedTest(name = "{0} {1}")
            @MethodSource("adminRequests")
            @DisplayName("Internal JWT holding API_ACCESS still passes")
            void internalJwtPasses(String method, String path) {
                MockServerWebExchange exchange = run(jwtPrincipal("INTERNAL"),
                        MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());

                assertThat(exchange.getResponse().getStatusCode()).isNull();
                verify(filterChain).filter(any());
            }

            @Test
            @DisplayName("Internal-owned PAT holding API_ACCESS still passes")
            void internalPatPasses() {
                MockServerWebExchange exchange = run(patPrincipal("INTERNAL"),
                        MockServerHttpRequest.put("/api/admin/tenant/email-settings").build());

                assertThat(exchange.getResponse().getStatusCode()).isNull();
                verify(filterChain).filter(any());
            }

            @Test
            @DisplayName("A portal principal is not denied on non-admin static routes")
            void portalOnOtherStaticRoutePasses() {
                RouteDefinition meRoute = new RouteDefinition("static-me", "/api/me/**", "http://worker:80", "me");
                GatewayPrincipal principal = jwtPrincipal("PORTAL");
                MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/me/profile").build());
                exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
                when(routeRegistry.findByPath(eq("/api/me/profile"), any())).thenReturn(Optional.of(meRoute));
                when(cerbosService.checkSystemPermission(principal, "API_ACCESS")).thenReturn(Mono.just(true));

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                verify(filterChain).filter(any());
            }
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
            @DisplayName("Anonymous lookups see only the URL's own tenant, name and slug only")
            void anonymousListIsNarrowedToUrlTenantAndSafeFields() {
                when(publicPathMatcher.isPublicRequest(any(ServerWebExchange.class))).thenReturn(true);
                MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/tenants")
                        .queryParam("filter[slug][eq]", "acme")
                        .queryParam("filter[id][eq]", "victim-tenant")
                        .queryParam("fields[tenants]", "settings,limits,ipAllowlistCidrs")
                        .queryParam("include", "parentTenantId").build());
                exchange.getAttributes().put("tenantId", "tenant-1");

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                var params = forwarded().getRequest().getQueryParams();
                assertThat(params.get("filter[id][eq]")).containsExactly("tenant-1");
                assertThat(params.get("fields[tenants]")).containsExactly("name,slug");
                assertThat(params).doesNotContainKey("include");
                assertThat(params.getFirst("filter[slug][eq]")).isEqualTo("acme");
            }

            @Test
            @DisplayName("Anonymous lookup of another tenant's record is a 404")
            void anonymousOtherTenantRecordIsNotFound() {
                when(publicPathMatcher.isPublicRequest(any(ServerWebExchange.class))).thenReturn(true);
                MockServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/tenants/victim-tenant").build());
                exchange.getAttributes().put("tenantId", "tenant-1");

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                verify(filterChain, never()).filter(any());
            }

            @Test
            @DisplayName("Anonymous lookup with no resolved tenant is a 404")
            void anonymousWithoutResolvedTenantIsNotFound() {
                when(publicPathMatcher.isPublicRequest(any(ServerWebExchange.class))).thenReturn(true);
                MockServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/tenants").build());

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                verify(filterChain, never()).filter(any());
            }

            @Test
            @DisplayName("Anonymous lookup of the URL's own tenant record gets name and slug only")
            void anonymousOwnRecordGetsSafeFields() {
                when(publicPathMatcher.isPublicRequest(any(ServerWebExchange.class))).thenReturn(true);
                MockServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/tenants/tenant-1").build());
                exchange.getAttributes().put("tenantId", "tenant-1");

                StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();

                assertThat(forwarded().getRequest().getQueryParams().get("fields[tenants]")).containsExactly("name,slug");
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

    // ================================================================
    // Static routes that front a system collection (users, profiles, collections)
    // ================================================================

    @Nested
    @DisplayName("Static routes that front a system collection")
    class StaticCollectionRouteTests {

        private static final String VALID_USER_BODY = """
                {"data":{"type":"users","attributes":{"email":"new@test.com","userType":"INTERNAL",
                "status":"ACTIVE","firstName":"New","lastName":"User"}}}
                """;

        private final RouteDefinition usersRoute = new RouteDefinition("static-users", "/api/users/**",
                "http://worker:80", "users").withCollectionCheck(new StaticCollectionCheck(true, "MANAGE_USERS"));
        private final RouteDefinition usersCollection = new RouteDefinition("users-uuid", "/api/users/**",
                "http://worker:80", "users", null, 0, "platform-tenant").withSystemCollection(true);
        private final RouteDefinition profilesRoute = new RouteDefinition("static-profiles", "/api/profiles/**",
                "http://worker:80", "profiles").withCollectionCheck(new StaticCollectionCheck(false, "MANAGE_USERS"));
        private final RouteDefinition profilesCollection = new RouteDefinition("profiles-uuid", "/api/profiles/**",
                "http://worker:80", "profiles", null, 0, "platform-tenant").withSystemCollection(true);
        private final RouteDefinition collectionsRoute = new RouteDefinition("static-collections",
                "/api/collections/**", "http://worker:80", "collections")
                .withCollectionCheck(new StaticCollectionCheck(false, "CUSTOMIZE_APPLICATION",
                        Pattern.compile("^/api/collections/[^/]+/(import|duplicates|merge)(/.*)?$")));
        private final RouteDefinition collectionsCollection = new RouteDefinition("collections-uuid",
                "/api/collections/**", "http://worker:80", "collections", null, 0, "platform-tenant")
                .withSystemCollection(true);
        private final RouteDefinition scheduledJobsRoute = new RouteDefinition("static-scheduled-jobs",
                "/api/scheduled-jobs/**", "http://worker:80", "scheduled-jobs")
                .withCollectionCheck(new StaticCollectionCheck(false, "MANAGE_WORKFLOWS"));
        private final RouteDefinition scheduledJobsCollection = new RouteDefinition("scheduled-jobs-uuid",
                "/api/scheduled-jobs/**", "http://worker:80", "scheduled-jobs", null, 0, "platform-tenant")
                .withSystemCollection(true);

        private RouteAuthorizationFilter filter;
        private GatewayPrincipal principal;

        @BeforeEach
        void setUp() {
            filter = new RouteAuthorizationFilter(
                    routeRegistry, true, publicPathMatcher, metrics, new ObjectMapper(), cerbosService);
            principal = principalWithIdentity("pat@test.com");
            lenient().when(cerbosService.checkSystemPermission(principal, "API_ACCESS")).thenReturn(Mono.just(true));
            lenient().when(cerbosService.checkSystemPermission(principal, "MANAGE_USERS")).thenReturn(Mono.just(false));
            lenient().when(cerbosService.checkSystemPermission(principal, "CUSTOMIZE_APPLICATION"))
                    .thenReturn(Mono.just(false));
            lenient().when(routeRegistry.findCollectionRouteBehind(usersRoute)).thenReturn(Optional.of(usersCollection));
            lenient().when(routeRegistry.findCollectionRouteBehind(profilesRoute))
                    .thenReturn(Optional.of(profilesCollection));
            lenient().when(routeRegistry.findCollectionRouteBehind(collectionsRoute))
                    .thenReturn(Optional.of(collectionsCollection));
            lenient().when(cerbosService.checkSystemPermission(principal, "MANAGE_WORKFLOWS"))
                    .thenReturn(Mono.just(false));
            lenient().when(routeRegistry.findCollectionRouteBehind(scheduledJobsRoute))
                    .thenReturn(Optional.of(scheduledJobsCollection));
        }

        private MockServerWebExchange exchange(MockServerHttpRequest request, RouteDefinition route) {
            when(routeRegistry.findByPath(eq(request.getPath().value()), any())).thenReturn(Optional.of(route));
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
            return exchange;
        }

        private void run(MockServerWebExchange exchange) {
            StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();
        }

        private void assertDenied(MockServerWebExchange exchange) {
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(filterChain, never()).filter(any());
        }

        @Test
        @DisplayName("POST /api/users with an empty body is 403 without create — never reaches validation")
        void postUsersEmptyBodyWithoutCreateIsForbidden() {
            when(cerbosService.checkObjectPermission(principal, "users-uuid", "create")).thenReturn(Mono.just(false));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON).body(""), usersRoute);

            run(exchange);

            assertDenied(exchange);
        }

        @Test
        @DisplayName("POST /api/users with a fully valid body is 403 without create")
        void postUsersValidBodyWithoutCreateIsForbidden() {
            when(cerbosService.checkObjectPermission(principal, "users-uuid", "create")).thenReturn(Mono.just(false));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON).body(VALID_USER_BODY), usersRoute);

            run(exchange);

            assertDenied(exchange);
        }

        @Test
        @DisplayName("POST /api/users with create on the users collection is forwarded")
        void postUsersWithCreateIsForwarded() {
            when(cerbosService.checkObjectPermission(principal, "users-uuid", "create")).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON).body(VALID_USER_BODY), usersRoute);

            run(exchange);

            verify(filterChain).filter(any());
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("MANAGE_USERS admits a users write without a collection grant")
        void manageUsersAdmitsUsersWrite() {
            when(cerbosService.checkSystemPermission(principal, "MANAGE_USERS")).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.patch("/api/users/u-1")
                    .contentType(MediaType.APPLICATION_JSON).body(VALID_USER_BODY), usersRoute);

            run(exchange);

            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @Test
        @DisplayName("GET /api/users list is 403 without read")
        void getUsersListWithoutReadIsForbidden() {
            when(cerbosService.checkObjectPermission(principal, "users-uuid", "read")).thenReturn(Mono.just(false));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/users").build(), usersRoute);

            run(exchange);

            assertDenied(exchange);
        }

        @Test
        @DisplayName("GET /api/users/{id} is 403 without read, and MANAGE_USERS does not widen reads")
        void getUserByIdWithoutReadIsForbidden() {
            when(cerbosService.checkObjectPermission(principal, "users-uuid", "read")).thenReturn(Mono.just(false));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/users/u-1").build(), usersRoute);

            run(exchange);

            assertDenied(exchange);
            verify(cerbosService, never()).checkSystemPermission(principal, "MANAGE_USERS");
        }

        @Test
        @DisplayName("GET /api/users with read is forwarded")
        void getUsersWithReadIsForwarded() {
            when(cerbosService.checkObjectPermission(principal, "users-uuid", "read")).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/users").build(), usersRoute);

            run(exchange);

            verify(filterChain).filter(any());
        }

        @Test
        @DisplayName("Self-service /api/me (the caller's own user) stays API_ACCESS-only")
        void meStaysApiAccessOnly() {
            RouteDefinition meRoute = new RouteDefinition("static-me", "/api/me/**", "http://worker:80", "me");
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/me/permissions").build(), meRoute);

            run(exchange);

            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @Test
        @DisplayName("Fails closed when the collection behind the static route is not registered")
        void failsClosedWithoutBackingCollection() {
            when(routeRegistry.findCollectionRouteBehind(usersRoute)).thenReturn(Optional.empty());
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/users").build(), usersRoute);

            run(exchange);

            assertDenied(exchange);
        }

        @Test
        @DisplayName("GET /api/profiles stays API_ACCESS-only; PATCH needs edit")
        void profilesReadsOpenWritesChecked() {
            MockServerWebExchange read = exchange(MockServerHttpRequest.get("/api/profiles").build(), profilesRoute);
            run(read);
            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());

            when(cerbosService.checkObjectPermission(principal, "profiles-uuid", "edit")).thenReturn(Mono.just(false));
            MockServerWebExchange write = exchange(MockServerHttpRequest.patch("/api/profiles/p-1")
                    .contentType(MediaType.APPLICATION_JSON).body("{}"), profilesRoute);
            run(write);
            assertThat(write.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("GET /api/collections stays open; POST needs create or CUSTOMIZE_APPLICATION")
        void collectionsReadsOpenWritesChecked() {
            MockServerWebExchange read = exchange(MockServerHttpRequest.get("/api/collections").build(),
                    collectionsRoute);
            run(read);
            verify(filterChain).filter(any());

            when(cerbosService.checkObjectPermission(principal, "collections-uuid", "create"))
                    .thenReturn(Mono.just(false));
            MockServerWebExchange write = exchange(MockServerHttpRequest.post("/api/collections")
                    .contentType(MediaType.APPLICATION_JSON).body("{}"), collectionsRoute);
            run(write);
            assertThat(write.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Scheduled-job actions and writes need the collection grant or MANAGE_WORKFLOWS")
        void scheduledJobWritesChecked() {
            when(cerbosService.checkObjectPermission(principal, "scheduled-jobs-uuid", "create"))
                    .thenReturn(Mono.just(false));
            for (String action : List.of("pause", "resume", "execute")) {
                MockServerWebExchange exchange = exchange(
                        MockServerHttpRequest.post("/api/scheduled-jobs/job-1/" + action).build(), scheduledJobsRoute);
                run(exchange);
                assertDenied(exchange);
            }

            when(cerbosService.checkObjectPermission(principal, "scheduled-jobs-uuid", "edit"))
                    .thenReturn(Mono.just(false));
            MockServerWebExchange patch = exchange(MockServerHttpRequest.patch("/api/scheduled-jobs/job-1")
                    .contentType(MediaType.APPLICATION_JSON).body("{}"), scheduledJobsRoute);
            run(patch);
            assertDenied(patch);
        }

        @Test
        @DisplayName("MANAGE_WORKFLOWS admits a scheduled-job action without a collection grant")
        void manageWorkflowsAdmitsScheduledJobAction() {
            when(cerbosService.checkSystemPermission(principal, "MANAGE_WORKFLOWS")).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = exchange(
                    MockServerHttpRequest.post("/api/scheduled-jobs/job-1/pause").build(), scheduledJobsRoute);

            run(exchange);

            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @Test
        @DisplayName("GET /api/scheduled-jobs stays API_ACCESS-only")
        void scheduledJobReadsStayOpen() {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/scheduled-jobs").build(),
                    scheduledJobsRoute);

            run(exchange);

            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @Test
        @DisplayName("Per-collection data operations under /api/collections/{name}/ are exempt")
        void collectionDataOperationsAreExempt() {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/collections/orders/import/csv")
                    .build(), collectionsRoute);

            run(exchange);

            verify(filterChain).filter(any());
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }
    }

    // ================================================================
    // Setup-metadata static routes (PLT-656): writes collection-checked
    // ================================================================

    @Nested
    @DisplayName("Setup-metadata static routes: writes need the collection grant or the override")
    class SetupMetadataStaticRouteTests {

        private final RouteRegistry registry = new RouteRegistry();
        private RouteAuthorizationFilter filter;
        private GatewayPrincipal principal;

        @BeforeEach
        void setUp() {
            // The production table, wired as RouteConfigService registers it, with each system
            // collection's bootstrap route under the platform tenant behind it.
            RouteConfigService.COLLECTION_CHECKED_STATIC_ROUTES.forEach((name, check) -> {
                registry.addRoute(new RouteDefinition("static-" + name, "/api/" + name + "/**",
                        "http://worker:80", name).withCollectionCheck(check));
                registry.addRoute(new RouteDefinition(name + "-uuid", "/api/" + name + "/**",
                        "http://worker:80", name, null, 0, "platform-tenant").withSystemCollection(true));
            });
            filter = new RouteAuthorizationFilter(
                    registry, true, publicPathMatcher, metrics, new ObjectMapper(), cerbosService);
            principal = principalWithIdentity("member@test.com");
            lenient().when(cerbosService.checkSystemPermission(principal, "API_ACCESS")).thenReturn(Mono.just(true));
            lenient().when(cerbosService.checkSystemPermission(eq(principal), argThat(p -> !"API_ACCESS".equals(p))))
                    .thenReturn(Mono.just(false));
            lenient().when(cerbosService.checkObjectPermission(eq(principal), any(), any()))
                    .thenReturn(Mono.just(false));
        }

        private MockServerWebExchange run(GatewayPrincipal caller, HttpMethod method, String path) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path)
                    .contentType(MediaType.APPLICATION_JSON).body("{}"));
            exchange.getAttributes().put(PRINCIPAL_ATTR, caller);
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();
            return exchange;
        }

        private MockServerWebExchange run(HttpMethod method, String path) {
            return run(principal, method, path);
        }

        /** The filter forwards a decorated exchange; it shares the original's attribute map. */
        private ServerWebExchange forwarded(MockServerWebExchange exchange) {
            return argThat(e -> e.getAttributes() == exchange.getAttributes());
        }

        private void assertForwarded(MockServerWebExchange exchange) {
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(filterChain).filter(forwarded(exchange));
        }

        private void assertForbidden(MockServerWebExchange exchange) {
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(filterChain, never()).filter(forwarded(exchange));
        }

        /** Every collection PLT-656 covers, with the setup-page permission that overrides its writes. */
        static Stream<Arguments> covered() {
            return Stream.of(
                    Arguments.of("page-layouts", "CUSTOMIZE_APPLICATION"),
                    Arguments.of("list-views", "MANAGE_LISTVIEWS"),
                    Arguments.of("ui-menus", "CUSTOMIZE_APPLICATION"),
                    Arguments.of("ui-pages", "CUSTOMIZE_APPLICATION"),
                    Arguments.of("global-picklists", "CUSTOMIZE_APPLICATION"),
                    Arguments.of("oidc-providers", "MANAGE_CONNECTED_APPS"),
                    Arguments.of("email-templates", "MANAGE_EMAIL_TEMPLATES"),
                    Arguments.of("scripts", "MANAGE_CONNECTED_APPS"),
                    Arguments.of("connected-apps", "MANAGE_CONNECTED_APPS"),
                    Arguments.of("flows", "MANAGE_WORKFLOWS"),
                    Arguments.of("approval-processes", "MANAGE_APPROVALS"),
                    Arguments.of("reports", "MANAGE_REPORTS"),
                    Arguments.of("dashboards", "MANAGE_REPORTS"));
        }

        @Test
        @DisplayName("the production table checks writes only, for exactly these collections and overrides")
        void tableMatchesCoveredList() {
            covered().forEach(args -> {
                String name = (String) args.get()[0];
                StaticCollectionCheck check = RouteConfigService.COLLECTION_CHECKED_STATIC_ROUTES.get(name);
                assertThat(check).as(name).isNotNull();
                assertThat(check.includeReads()).as(name).isFalse();
                assertThat(check.writeOverridePermission()).as(name).isEqualTo(args.get()[1]);
            });
            assertThat(RouteConfigService.COLLECTION_CHECKED_STATIC_ROUTES).doesNotContainKey("webhooks");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("covered")
        @DisplayName("API_ACCESS only: POST, PATCH, PUT and DELETE are 403")
        void apiAccessOnlyWritesForbidden(String name, String override) {
            String collectionPath = "/api/" + name;
            assertForbidden(run(HttpMethod.POST, collectionPath));
            assertForbidden(run(HttpMethod.PATCH, collectionPath + "/r-1"));
            assertForbidden(run(HttpMethod.PUT, collectionPath + "/r-1"));
            assertForbidden(run(HttpMethod.DELETE, collectionPath + "/r-1"));
            verify(cerbosService).checkObjectPermission(principal, name + "-uuid", "create");
            verify(cerbosService, times(2)).checkObjectPermission(principal, name + "-uuid", "edit");
            verify(cerbosService).checkObjectPermission(principal, name + "-uuid", "delete");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("covered")
        @DisplayName("the collection's object create/edit/delete grant admits the write")
        void objectGrantAdmitsWrites(String name, String override) {
            for (String action : List.of("create", "edit", "delete")) {
                when(cerbosService.checkObjectPermission(principal, name + "-uuid", action))
                        .thenReturn(Mono.just(true));
            }
            String collectionPath = "/api/" + name;
            assertForwarded(run(HttpMethod.POST, collectionPath));
            assertForwarded(run(HttpMethod.PATCH, collectionPath + "/r-1"));
            assertForwarded(run(HttpMethod.PUT, collectionPath + "/r-1"));
            assertForwarded(run(HttpMethod.DELETE, collectionPath + "/r-1"));
        }

        @ParameterizedTest(name = "{0} via {1}")
        @MethodSource("covered")
        @DisplayName("the override system permission alone admits the write")
        void overrideAdmitsWrites(String name, String override) {
            when(cerbosService.checkSystemPermission(principal, override)).thenReturn(Mono.just(true));
            String collectionPath = "/api/" + name;
            assertForwarded(run(HttpMethod.POST, collectionPath));
            assertForwarded(run(HttpMethod.PATCH, collectionPath + "/r-1"));
            assertForwarded(run(HttpMethod.PUT, collectionPath + "/r-1"));
            assertForwarded(run(HttpMethod.DELETE, collectionPath + "/r-1"));
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("covered")
        @DisplayName("System Administrator keeps full access")
        void systemAdministratorAllowed(String name, String override) {
            GatewayPrincipal admin = new GatewayPrincipal("admin@test.com", List.of("ADMIN"), Map.of())
                    .withProfileId("profile-admin")
                    .withProfileName("System Administrator")
                    .withTenantId("tenant-1");
            // The System Administrator profile holds every system permission and, through
            // MODIFY_ALL_DATA / VIEW_ALL_DATA, every object action.
            when(cerbosService.checkSystemPermission(eq(admin), any())).thenReturn(Mono.just(true));
            lenient().when(cerbosService.checkObjectPermission(eq(admin), any(), any())).thenReturn(Mono.just(true));
            String collectionPath = "/api/" + name;
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
                assertForwarded(run(admin, method, collectionPath));
            }
            for (HttpMethod method : List.of(HttpMethod.PATCH, HttpMethod.PUT, HttpMethod.DELETE)) {
                assertForwarded(run(admin, method, collectionPath + "/r-1"));
            }
        }

        @ParameterizedTest(name = "GET /api/{0}")
        @ValueSource(strings = {
                "page-layouts", "list-views", "ui-menus", "ui-pages", "global-picklists"})
        @DisplayName("the app shell's reads stay open to an end user holding only API_ACCESS")
        void appShellReadsStayOpen(String name) {
            assertForwarded(run(HttpMethod.GET, "/api/" + name));
            assertForwarded(run(HttpMethod.GET, "/api/" + name + "/r-1"));
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "POST, /api/flows/f-1/execute",
                "POST, /api/scripts/s-1/execute",
                "POST, /api/reports/r-1/execute",
                "POST, /api/dashboards/d-1/data",
                "POST, /api/dashboards/d-1/components/c-1/data"
        })
        @DisplayName("end-user runs and dashboard data loads are exempt (gated in their controllers)")
        void endUserActionsExempt(String method, String path) {
            assertForwarded(run(HttpMethod.valueOf(method), path));
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "POST, /api/flows/f-1/publish, flows",
                "POST, /api/flows/executions/e-1/cancel, flows",
                "POST, /api/flows/executions/e-1/retry, flows",
                "POST, /api/connected-apps/app-1/tokens, connected-apps",
                "DELETE, /api/connected-apps/app-1/tokens/t-1, connected-apps",
                "PUT, /api/page-layouts/l-1/tree, page-layouts",
                "PUT, /api/ui-menus/main/tree, ui-menus",
                "PUT, /api/dashboards/sales/tree, dashboards",
                "POST, /api/dashboards/d-1/validate, dashboards",
                "POST, /api/ui-pages/validate, ui-pages",
                "POST, /api/scripts/s-1/execute/extra, scripts"
        })
        @DisplayName("other dedicated-controller writes under a covered route are checked")
        void dedicatedWritesChecked(String method, String path, String name) {
            assertForbidden(run(HttpMethod.valueOf(method), path));
            verify(cerbosService).checkObjectPermission(eq(principal), eq(name + "-uuid"), any());
        }

        @Test
        @DisplayName("inbound /api/webhooks receivers keep API_ACCESS-only (no collection behind them)")
        void webhooksUnchecked() {
            registry.addRoute(new RouteDefinition("static-webhooks", "/api/webhooks/**", "http://worker:80",
                    "webhooks"));
            assertForwarded(run(HttpMethod.POST, "/api/webhooks/f-1"));
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }
    }

    // ================================================================
    // Child-resource paths: /api/{parent}/{parentId}/{child}[/{childId}]
    // ================================================================

    @Nested
    @DisplayName("Child-resource paths under a collection route")
    class ChildResourcePathTests {

        private final RouteRegistry registry = new RouteRegistry();
        private RouteAuthorizationFilter filter;
        private GatewayPrincipal principal;

        @BeforeEach
        void setUp() {
            registry.addRoute(new RouteDefinition("accounts-uuid", "/api/accounts/**", "http://worker:80",
                    "accounts", null, 0, "tenant-1"));
            registry.addRoute(new RouteDefinition("contacts-uuid", "/api/contacts/**", "http://worker:80",
                    "contacts", null, 0, "tenant-1"));
            // Only another tenant has an "invoices" collection
            registry.addRoute(new RouteDefinition("invoices-uuid", "/api/invoices/**", "http://worker:80",
                    "invoices", null, 0, "tenant-2"));
            registry.addRoute(new RouteDefinition("fields-uuid", "/api/fields/**", "http://worker:80",
                    "fields", null, 0, "platform-tenant").withSystemCollection(true));
            registry.addRoute(new RouteDefinition("attachments-uuid", "/api/attachments/**", "http://worker:80",
                    "attachments", null, 0, "platform-tenant").withSystemCollection(true));
            RouteDefinition staticUsers = new RouteDefinition("static-users", "/api/users/**", "http://worker:80",
                    "users").withCollectionCheck(new StaticCollectionCheck(true, "MANAGE_USERS"));
            registry.addRoute(staticUsers);
            registry.addRoute(new RouteDefinition("users-uuid", "/api/users/**", "http://worker:80",
                    "users", null, 0, "platform-tenant").withSystemCollection(true));
            registry.addRoute(new RouteDefinition("static-admin", "/api/admin/**", "http://worker:80", "admin"));
            registry.addRoute(new RouteDefinition("static-tenants", "/api/tenants/**", "http://worker:80",
                    "tenants"));

            filter = new RouteAuthorizationFilter(
                    registry, true, publicPathMatcher, metrics, new ObjectMapper(), cerbosService);
            principal = principalWithIdentity("member@test.com");
            lenient().when(cerbosService.checkSystemPermission(principal, "API_ACCESS")).thenReturn(Mono.just(true));
            lenient().when(cerbosService.checkSystemPermission(eq(principal), argThat(p -> !"API_ACCESS".equals(p))))
                    .thenReturn(Mono.just(false));
            lenient().when(cerbosService.checkObjectPermission(eq(principal), any(), any()))
                    .thenReturn(Mono.just(false));
        }

        private void grant(String collectionId, String action) {
            lenient().when(cerbosService.checkObjectPermission(principal, collectionId, action)).thenReturn(Mono.just(true));
        }

        private MockServerWebExchange run(MockServerHttpRequest request) {
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            StepVerifier.create(filter.filter(exchange, filterChain)).expectComplete().verify();
            return exchange;
        }

        private void assertForwarded(MockServerWebExchange exchange) {
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(filterChain).filter(any());
        }

        private void assertStatus(MockServerWebExchange exchange, HttpStatus status) {
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
            verify(filterChain, never()).filter(any());
        }

        @Test
        @DisplayName("Listing children needs read on the parent and read on the child")
        void listWithBothGrantsIsForwarded() {
            grant("accounts-uuid", "read");
            grant("contacts-uuid", "read");

            assertForwarded(run(MockServerHttpRequest.get("/api/accounts/a1/contacts").build()));
            verify(cerbosService).checkObjectPermission(principal, "accounts-uuid", "read");
            verify(cerbosService).checkObjectPermission(principal, "contacts-uuid", "read");
        }

        @Test
        @DisplayName("A grant on the parent alone does not reach the child collection")
        void parentOnlyIsForbidden() {
            grant("accounts-uuid", "read");

            MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/accounts/a1/contacts").build());

            assertStatus(exchange, HttpStatus.FORBIDDEN);
            verify(metrics).recordAuthzDenied(any(), eq("contacts"), eq("GET"));
        }

        @Test
        @DisplayName("A grant on the child alone does not allow navigating through the parent")
        void childOnlyIsForbidden() {
            grant("contacts-uuid", "read");

            assertStatus(run(MockServerHttpRequest.get("/api/accounts/a1/contacts").build()), HttpStatus.FORBIDDEN);
            verify(cerbosService, never()).checkObjectPermission(principal, "contacts-uuid", "read");
        }

        @ParameterizedTest(name = "{0} {1} -> {2} on the child")
        @CsvSource({
                "GET, /api/accounts/a1/contacts/c1, read",
                "POST, /api/accounts/a1/contacts, create",
                "PUT, /api/accounts/a1/contacts/c1, edit",
                "PATCH, /api/accounts/a1/contacts/c1, edit",
                "DELETE, /api/accounts/a1/contacts/c1, delete"
        })
        @DisplayName("The HTTP method maps to the child's action; the parent always needs read")
        void methodMapsToTheChildAction(String method, String path, String action) {
            grant("accounts-uuid", "read");
            grant("contacts-uuid", action);

            assertForwarded(run(MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build()));
            verify(cerbosService).checkObjectPermission(principal, "contacts-uuid", action);
            verify(cerbosService, never()).checkObjectPermission(eq(principal), eq("accounts-uuid"),
                    argThat(a -> !"read".equals(a)));
        }

        @ParameterizedTest(name = "{0} {1} without {2} on the child")
        @CsvSource({
                "GET, /api/accounts/a1/contacts/c1, read",
                "POST, /api/accounts/a1/contacts, create",
                "PUT, /api/accounts/a1/contacts/c1, edit",
                "PATCH, /api/accounts/a1/contacts/c1, edit",
                "DELETE, /api/accounts/a1/contacts/c1, delete"
        })
        @DisplayName("Without the child's action the request is refused before the worker")
        void missingChildActionIsForbidden(String method, String path, String action) {
            grant("accounts-uuid", "read");
            grant("accounts-uuid", action);

            MockServerWebExchange exchange = run(MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());

            assertStatus(exchange, HttpStatus.FORBIDDEN);
            assertThat(exchange.getResponse().getBodyAsString().block())
                    .contains("Insufficient permissions for " + action + " on contacts");
        }

        @Test
        @DisplayName("A child segment naming only another tenant's collection gets the parent decision, never that collection's")
        void otherTenantsCollectionGetsParentDecision() {
            grant("accounts-uuid", "read");

            assertForwarded(run(MockServerHttpRequest.get("/api/accounts/a1/invoices").build()));
            verify(cerbosService).checkObjectPermission(principal, "accounts-uuid", "read");
            verify(cerbosService, never()).checkObjectPermission(any(), eq("invoices-uuid"), any());
        }

        @Test
        @DisplayName("Another tenant's same-named collection does not lift a parent denial")
        void otherTenantsCollectionDoesNotLiftParentDenial() {
            grant("invoices-uuid", "read");

            assertStatus(run(MockServerHttpRequest.get("/api/accounts/a1/invoices").build()), HttpStatus.FORBIDDEN);
            verify(cerbosService, never()).checkObjectPermission(any(), eq("invoices-uuid"), any());
        }

        @Test
        @DisplayName("A child segment that is no collection at all gets the parent route's decision for the method")
        void unknownChildGetsParentDecision() {
            grant("accounts-uuid", "delete");

            assertForwarded(run(MockServerHttpRequest.delete("/api/accounts/a1/nothing-here/x").build()));
            verify(cerbosService).checkObjectPermission(principal, "accounts-uuid", "delete");
            verify(cerbosService, never()).checkObjectPermission(principal, "accounts-uuid", "read");
        }

        @Test
        @DisplayName("Without the parent's action an unknown child segment is refused")
        void unknownChildWithoutParentActionIsForbidden() {
            grant("accounts-uuid", "read");

            MockServerWebExchange exchange = run(MockServerHttpRequest.delete("/api/accounts/a1/nothing-here/x").build());

            assertStatus(exchange, HttpStatus.FORBIDDEN);
            assertThat(exchange.getResponse().getBodyAsString().block())
                    .contains("Insufficient permissions for delete on accounts");
        }

        @Test
        @DisplayName("A system collection child is checked like a direct request to it")
        void systemCollectionChildIsChecked() {
            grant("accounts-uuid", "read");

            assertStatus(run(MockServerHttpRequest.get("/api/accounts/a1/fields").build()), HttpStatus.FORBIDDEN);
            verify(cerbosService).checkObjectPermission(principal, "fields-uuid", "read");
        }

        @Test
        @DisplayName("A child behind a collection-checked static route gets that route's check")
        void staticCollectionCheckedChildUsesItsCheck() {
            grant("accounts-uuid", "read");

            assertStatus(run(MockServerHttpRequest.get("/api/accounts/a1/users").build()), HttpStatus.FORBIDDEN);
            verify(cerbosService).checkObjectPermission(principal, "users-uuid", "read");
        }

        @Test
        @DisplayName("A child on an API_ACCESS-only static route keeps API_ACCESS")
        void plainStaticChildStaysApiAccessOnly() {
            grant("accounts-uuid", "read");

            assertForwarded(run(MockServerHttpRequest.get("/api/accounts/a1/admin").build()));
        }

        @Test
        @DisplayName("The own-tenant view of /api/tenants does not carry over to a child list")
        void narrowedTenantsChildIsForbidden() {
            grant("accounts-uuid", "read");

            assertStatus(run(MockServerHttpRequest.get("/api/accounts/a1/tenants").build()), HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("A static parent route keeps its own rule (child shape is not reinterpreted)")
        void staticParentIsUnchanged() {
            assertForwarded(run(MockServerHttpRequest.get("/api/admin/jobs/j1/runs").build()));
            verify(cerbosService, never()).checkObjectPermission(any(), any(), any());
        }

        @Test
        @DisplayName("A dedicated controller sub-path keeps the parent route's decision")
        void dedicatedSubResourceKeepsParentDecision() {
            grant("attachments-uuid", "create");

            assertForwarded(run(MockServerHttpRequest.post("/api/attachments/a1/finalize").build()));
            verify(cerbosService).checkObjectPermission(principal, "attachments-uuid", "create");
        }

        @Test
        @DisplayName("A dedicated sub-path keeps the parent decision even when the tenant has a same-named collection")
        void dedicatedSubResourceWinsOverSameNamedCollection() {
            registry.addRoute(new RouteDefinition("finalize-uuid", "/api/finalize/**", "http://worker:80",
                    "finalize", null, 0, "tenant-1"));
            grant("attachments-uuid", "create");

            assertForwarded(run(MockServerHttpRequest.post("/api/attachments/a1/finalize").build()));
            verify(cerbosService, never()).checkObjectPermission(any(), eq("finalize-uuid"), any());
        }

        @Test
        @DisplayName("The dedicated-path match is method-specific: GET on the same path is a child request")
        void dedicatedMatchIsMethodSpecific() {
            registry.addRoute(new RouteDefinition("finalize-uuid", "/api/finalize/**", "http://worker:80",
                    "finalize", null, 0, "tenant-1"));
            grant("attachments-uuid", "read");

            assertStatus(run(MockServerHttpRequest.get("/api/attachments/a1/finalize").build()), HttpStatus.FORBIDDEN);
            verify(cerbosService).checkObjectPermission(principal, "finalize-uuid", "read");
        }

        /**
         * Dedicated worker endpoints under parents whose gateway route is a collection route (the
         * system collection's bootstrap route rather than the {@code static-} one), with a tenant
         * collection named like the trailing segment: each keeps the parent's decision for its
         * method and is never authorized against that collection.
         */
        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "GET, /api/page-layouts/l1/tree, page-layouts, tree, read",
                "PUT, /api/page-layouts/l1/tree, page-layouts, tree, edit",
                "GET, /api/dashboards/sales/tree, dashboards, tree, read",
                "PUT, /api/dashboards/sales/tree, dashboards, tree, edit",
                "POST, /api/dashboards/d1/data, dashboards, data, create",
                "POST, /api/dashboards/d1/validate, dashboards, validate, create",
                "POST, /api/collections/accounts/duplicates, collections, duplicates, create",
                "POST, /api/collections/accounts/merge, collections, merge, create"
        })
        @DisplayName("Dedicated endpoints keep the parent decision over a same-named tenant collection")
        void dedicatedEndpointsKeepParentDecision(String method, String path, String parent, String segment,
                                                  String action) {
            registry.addRoute(new RouteDefinition(parent + "-uuid", "/api/" + parent + "/**", "http://worker:80",
                    parent, null, 0, "tenant-1"));
            registry.addRoute(new RouteDefinition(segment + "-uuid", "/api/" + segment + "/**", "http://worker:80",
                    segment, null, 0, "tenant-1"));
            grant(parent + "-uuid", action);

            assertForwarded(run(MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build()));
            verify(cerbosService).checkObjectPermission(principal, parent + "-uuid", action);
            verify(cerbosService, never()).checkObjectPermission(any(), eq(segment + "-uuid"), any());
        }

        @Test
        @DisplayName("A percent-encoded child name is resolved and checked as the decoded collection")
        void encodedChildNameIsChecked() {
            grant("accounts-uuid", "read");

            assertStatus(run(MockServerHttpRequest.method(HttpMethod.GET,
                    java.net.URI.create("/api/accounts/a1/c%6Fntacts")).build()), HttpStatus.FORBIDDEN);
            verify(cerbosService).checkObjectPermission(principal, "contacts-uuid", "read");
        }
    }
}
