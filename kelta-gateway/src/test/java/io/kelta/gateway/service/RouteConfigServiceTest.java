package io.kelta.gateway.service;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.config.BootstrapConfig;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RouteConfigService.
 *
 * Tests bootstrap configuration fetching, parsing, and validation.
 */
class RouteConfigServiceTest {

    private MockWebServer mockWebServer;
    private RouteConfigService routeConfigService;
    private RouteRegistry routeRegistry;
    private GatewayCacheManager cacheManager;

    private String workerServiceUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        workerServiceUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
        routeRegistry = new RouteRegistry();
        cacheManager = new GatewayCacheManager(WebClient.builder(), workerServiceUrl);

        routeConfigService = new RouteConfigService(
            WebClient.builder(),
            routeRegistry,
            cacheManager,
            workerServiceUrl
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void testFetchBootstrapConfig_Success() {
        // Arrange
        String jsonResponse = """
            {
              "collections": [
                {
                  "id": "users-collection",
                  "name": "users",
                  "path": "/api/users",
                  "fields": [
                    {"name": "id", "type": "string"},
                    {"name": "email", "type": "string"}
                  ]
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
            .setBody(jsonResponse)
            .addHeader("Content-Type", "application/json"));

        // Act
        Mono<BootstrapConfig> result = routeConfigService.fetchBootstrapConfig();

        // Assert
        StepVerifier.create(result)
            .assertNext(config -> {
                assertNotNull(config);

                assertNotNull(config.getCollections());
                assertEquals(1, config.getCollections().size());
                assertEquals("users-collection", config.getCollections().get(0).getId());
            })
            .verifyComplete();
    }

    @Test
    void testFetchBootstrapConfig_ServerError() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .setBody("Internal Server Error"));

        // Act
        Mono<BootstrapConfig> result = routeConfigService.fetchBootstrapConfig();

        // Assert
        StepVerifier.create(result)
            .expectError()
            .verify();
    }

    @Test
    void testFetchBootstrapConfig_InvalidJson() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
            .setBody("{ invalid json }")
            .addHeader("Content-Type", "application/json"));

        // Act
        Mono<BootstrapConfig> result = routeConfigService.fetchBootstrapConfig();

        // Assert
        StepVerifier.create(result)
            .expectError()
            .verify();
    }

    @Test
    void testRefreshRoutes_ValidConfiguration() throws InterruptedException {
        // Arrange
        String jsonResponse = """
            {
              "collections": [
                {
                  "id": "users-collection",
                  "name": "users",
                  "path": "/api/users",
                  "fields": []
                },
                {
                  "id": "posts-collection",
                  "name": "posts",
                  "path": "/api/posts",
                  "fields": []
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
            .setBody(jsonResponse)
            .addHeader("Content-Type", "application/json"));

        // Act
        routeConfigService.refreshRoutes();

        // Give async processing time to complete
        Thread.sleep(500);

        // Assert (2 collection routes + 56 static routes; "users" bootstrap overwrites static-users)
        List<RouteDefinition> routes = routeRegistry.getAllRoutes();
        assertEquals(RouteConfigService.STATIC_ROUTES.length + 1, routes.size());

        // Verify bootstrap collection route (overwrites the static-users route for same path)
        RouteDefinition usersRoute = routeRegistry.findByPath("/api/users/**").orElse(null);
        assertNotNull(usersRoute);
        assertEquals("users-collection", usersRoute.getId());
        assertEquals("/api/users/**", usersRoute.getPath());
        assertEquals(workerServiceUrl, usersRoute.getBackendUrl());
        assertEquals("users", usersRoute.getCollectionName());

        // Verify second route (path has /** wildcard added)
        RouteDefinition postsRoute = routeRegistry.findByPath("/api/posts/**").orElse(null);
        assertNotNull(postsRoute);
        assertEquals("posts-collection", postsRoute.getId());

        // Approval ACTION endpoints route — regression guard: this row was missing, leaving
        // ApprovalController unreachable through the gateway (app-surfacing slice 2).
        RouteDefinition approvalsRoute = routeRegistry.findByPath("/api/approvals/**").orElse(null);
        assertNotNull(approvalsRoute);
        assertEquals("static-approvals", approvalsRoute.getId());
    }

    @Test
    void testStaticRoutes_IncludesWhoAmI() throws InterruptedException {
        // Regression guard: WhoAmIController maps /api/whoami exactly; without a static route
        // the gateway falls through to the 404 error handler (BUG-2026-09-14-0011).
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"collections\":[]}")
            .addHeader("Content-Type", "application/json"));

        routeConfigService.refreshRoutes();
        Thread.sleep(500);

        RouteDefinition whoAmIRoute = routeRegistry.findByPath("/api/whoami").orElse(null);
        assertNotNull(whoAmIRoute, "/api/whoami static route must be registered");
        assertEquals("static-whoami", whoAmIRoute.getId());
        assertEquals(workerServiceUrl, whoAmIRoute.getBackendUrl());
    }

    @Test
    void testRefreshRoutes_AlwaysUsesConfiguredServiceUrl() throws InterruptedException {
        // Arrange - bootstrap response includes pod-specific URLs, but gateway
        // should ignore them and always use the configured K8s Service URL.
        // Pod IPs are ephemeral and become stale when pods restart.
        String jsonResponse = """
            {
              "collections": [
                {
                  "id": "product-collection",
                  "name": "product",
                  "path": "/api/product",
                  "workerBaseUrl": "http://10.1.150.147:8080",
                  "fields": []
                },
                {
                  "id": "tasks-collection",
                  "name": "tasks",
                  "path": "/api/tasks",
                  "fields": []
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
            .setBody(jsonResponse)
            .addHeader("Content-Type", "application/json"));

        // Act
        routeConfigService.refreshRoutes();
        Thread.sleep(500);

        // Assert (2 collection routes + 56 static routes)
        List<RouteDefinition> routes = routeRegistry.getAllRoutes();
        assertEquals(RouteConfigService.STATIC_ROUTES.length + 2, routes.size());

        // Even though bootstrap included a pod IP, gateway should use configured service URL
        RouteDefinition productRoute = routeRegistry.findByPath("/api/product/**").orElse(null);
        assertNotNull(productRoute);
        assertEquals(workerServiceUrl, productRoute.getBackendUrl());

        // Collection without workerBaseUrl also uses configured service URL
        RouteDefinition tasksRoute = routeRegistry.findByPath("/api/tasks/**").orElse(null);
        assertNotNull(tasksRoute);
        assertEquals(workerServiceUrl, tasksRoute.getBackendUrl());
    }

    @Test
    void testRefreshRoutes_MissingPath() throws InterruptedException {
        // Arrange - collection with missing path
        String jsonResponse = """
            {
              "collections": [
                {
                  "id": "invalid-collection",
                  "name": "invalid",
                  "fields": []
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
            .setBody(jsonResponse)
            .addHeader("Content-Type", "application/json"));

        // Act
        routeConfigService.refreshRoutes();
        Thread.sleep(500);

        // Assert - invalid route should be skipped (only 56 static routes remain)
        List<RouteDefinition> routes = routeRegistry.getAllRoutes();
        assertEquals(RouteConfigService.STATIC_ROUTES.length, routes.size());
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-admin")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-collections")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-metrics")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-search")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-me")));
    }

    @Test
    void testRefreshRoutes_EmptyCollections() throws InterruptedException {
        // Arrange - no collections
        String jsonResponse = """
            {
              "collections": []
            }
            """;

        mockWebServer.enqueue(new MockResponse()
            .setBody(jsonResponse)
            .addHeader("Content-Type", "application/json"));

        // Act
        routeConfigService.refreshRoutes();
        Thread.sleep(500);

        // Assert (only 56 static routes remain)
        List<RouteDefinition> routes = routeRegistry.getAllRoutes();
        assertEquals(RouteConfigService.STATIC_ROUTES.length, routes.size());
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-admin")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-collections")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-me")));

        // ALM routes for the sandbox/promotion feature (packages, environments, promotions)
        RouteDefinition packagesRoute = routes.stream()
            .filter(r -> r.getId().equals("static-packages")).findFirst().orElse(null);
        assertNotNull(packagesRoute);
        assertEquals("/api/packages/**", packagesRoute.getPath());

        RouteDefinition environmentsRoute = routes.stream()
            .filter(r -> r.getId().equals("static-environments")).findFirst().orElse(null);
        assertNotNull(environmentsRoute);
        assertEquals("/api/environments/**", environmentsRoute.getPath());

        RouteDefinition promotionsRoute = routes.stream()
            .filter(r -> r.getId().equals("static-promotions")).findFirst().orElse(null);
        assertNotNull(promotionsRoute);
        assertEquals("/api/promotions/**", promotionsRoute.getPath());
    }

    @Test
    void testRefreshRoutes_MixedValidAndInvalid() throws InterruptedException {
        // Arrange - mix of valid and invalid routes
        String jsonResponse = """
            {
              "collections": [
                {
                  "id": "valid-collection",
                  "name": "valid",
                  "path": "/api/valid",
                  "fields": []
                },
                {
                  "id": "invalid-collection",
                  "name": "invalid",
                  "fields": []
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
            .setBody(jsonResponse)
            .addHeader("Content-Type", "application/json"));

        // Act
        routeConfigService.refreshRoutes();
        Thread.sleep(500);

        // Assert - only valid route + 56 static routes should be added
        List<RouteDefinition> routes = routeRegistry.getAllRoutes();
        assertEquals(RouteConfigService.STATIC_ROUTES.length + 1, routes.size());
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("valid-collection")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-admin")));
        assertTrue(routes.stream().anyMatch(r -> r.getId().equals("static-collections")));
    }
}
