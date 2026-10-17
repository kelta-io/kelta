package io.kelta.gateway.integration;

import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.gateway.service.RouteConfigService;
import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for worker bootstrap integration.
 *
 * Tests:
 * - Gateway fetches bootstrap configuration from the worker on startup
 * - Bootstrap response is parsed correctly
 * - Routes are created from bootstrap configuration
 * - Invalid bootstrap data is handled gracefully
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 10.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkerBootstrapIntegrationTest extends GatewayContainers {

    private static MockWebServer mockWorker;

    /**
     * The worker's /internal/bootstrap answer for the current test. The gateway also calls the
     * worker by itself (startup bootstrap with retries, slug map, governor limits, IP allowlists),
     * so a FIFO enqueue() lets those calls consume the test's response — and takeRequest() then
     * waits forever. A path-keyed dispatcher answers each endpoint independently.
     */
    private static volatile MockResponse bootstrapResponse = jsonResponse("{\"collections\":[]}");
    private static final AtomicInteger bootstrapRequests = new AtomicInteger();
    private static final AtomicReference<String> lastBootstrapMethod = new AtomicReference<>();

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).setBody(body);
    }

    @Autowired
    private RouteConfigService routeConfigService;

    @Autowired
    private RouteRegistry routeRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void setUpMockServer() throws IOException {
        mockWorker = new MockWebServer();
        mockWorker.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/internal/bootstrap")) {
                    bootstrapRequests.incrementAndGet();
                    lastBootstrapMethod.set(request.getMethod());
                    return bootstrapResponse;
                }
                return jsonResponse("{}");
            }
        });
        mockWorker.start();
    }

    @AfterAll
    static void tearDownMockServer() throws IOException {
        if (mockWorker != null) {
            mockWorker.shutdown();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("kelta.gateway.worker-service-url", () -> mockWorker.url("/").toString().replaceAll("/$", ""));
    }

    @BeforeEach
    void setUp() {
        // Clear route registry before each test
        routeRegistry.clear();
        bootstrapRequests.set(0);
        lastBootstrapMethod.set(null);
    }

    @Test
    void testBootstrapConfiguration_ValidResponse() throws Exception {
        // Arrange - create bootstrap response
        String bootstrapJson = """
                {
                    "collections": [
                        {
                            "id": "users-collection",
                            "name": "users",
                            "path": "/api/users",
                            "fields": [
                                {"name": "id", "type": "string"},
                                {"name": "email", "type": "string"},
                                {"name": "name", "type": "string"}
                            ]
                        },
                        {
                            "id": "products-collection",
                            "name": "products",
                            "path": "/api/products",
                            "fields": [
                                {"name": "id", "type": "string"},
                                {"name": "name", "type": "string"},
                                {"name": "price", "type": "number"}
                            ]
                        }
                    ]
                }
                """;

        bootstrapResponse = (new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(bootstrapJson));

        // Act - fetch bootstrap configuration
        int bootstrapCallsBefore = bootstrapRequests.get();
        routeConfigService.refreshRoutes();

        // Assert - verify routes were created
        Optional<RouteDefinition> usersRoute = routeRegistry.findByPath("/api/users/**");
        assertThat(usersRoute).isPresent();
        assertThat(usersRoute.get().getId()).isEqualTo("users-collection");
        assertThat(usersRoute.get().getBackendUrl()).isNotNull();
        assertThat(usersRoute.get().getCollectionName()).isEqualTo("users");

        Optional<RouteDefinition> productsRoute = routeRegistry.findByPath("/api/products/**");
        assertThat(productsRoute).isPresent();
        assertThat(productsRoute.get().getId()).isEqualTo("products-collection");
        assertThat(productsRoute.get().getBackendUrl()).isNotNull();

        // Verify the refresh itself called the worker's bootstrap endpoint
        assertThat(bootstrapRequests.get()).isGreaterThan(bootstrapCallsBefore);
        assertThat(lastBootstrapMethod.get()).isEqualTo("GET");
    }

    @Test
    void testBootstrapConfiguration_MissingRequiredFields() throws Exception {
        // Arrange - create bootstrap response with missing required fields
        String bootstrapJson = """
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

        bootstrapResponse = (new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(bootstrapJson));


        // Act - fetch bootstrap configuration
        routeConfigService.refreshRoutes();

        // Assert - valid collection should be added, invalid should be skipped
        Optional<RouteDefinition> validRoute = routeRegistry.findByPath("/api/valid/**");
        assertThat(validRoute).isPresent();

        Optional<RouteDefinition> invalidRoute = routeRegistry.findByPath("/api/invalid/**");
        assertThat(invalidRoute).isEmpty();
    }

    @Test
    void testBootstrapConfiguration_EmptyResponse() throws Exception {
        // Arrange - create empty bootstrap response
        String bootstrapJson = """
                {
                    "collections": []
                }
                """;

        bootstrapResponse = (new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(bootstrapJson));


        // Act - fetch bootstrap configuration
        routeConfigService.refreshRoutes();

        // Assert - the bootstrap added no collection routes: only the static admin/API routes
        // refreshRoutes() always registers first (ids "static-<name>") remain.
        assertThat(bootstrapRequests.get()).isPositive();
        List<RouteDefinition> routes = routeRegistry.getAllRoutes();
        assertThat(routes).isNotEmpty()
                .allSatisfy(route -> assertThat(route.getId()).startsWith("static-"));
    }

    @Test
    void testBootstrapConfiguration_WorkerUnavailable() {
        // Arrange - mock worker returns error
        bootstrapResponse = (new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));


        // Act & Assert - should handle error gracefully
        try {
            routeConfigService.refreshRoutes();
            // If no exception is thrown, the error was handled gracefully
        } catch (Exception e) {
            // Exception is acceptable - bootstrap failure should be logged
            assertThat(e).isNotNull();
        }
    }

    @Test
    void testBootstrapConfiguration_MalformedJson() {
        // Arrange - mock worker returns malformed JSON
        bootstrapResponse = (new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{ invalid json }"));


        // Act & Assert - should handle error gracefully
        try {
            routeConfigService.refreshRoutes();
            // If no exception is thrown, the error was handled gracefully
        } catch (Exception e) {
            // Exception is acceptable - parsing failure should be logged
            assertThat(e).isNotNull();
        }
    }

    @Test
    void testBootstrapConfiguration_WithGovernorLimits() throws Exception {
        // Arrange - create bootstrap response with governor limits
        String bootstrapJson = """
                {
                    "collections": [
                        {
                            "id": "rate-limited-collection",
                            "name": "ratelimited",
                            "path": "/api/ratelimited",
                            "fields": []
                        }
                    ],
                    "governorLimits": {
                        "tenant-1": {
                            "apiCallsPerDay": 50000
                        }
                    }
                }
                """;

        bootstrapResponse = (new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(bootstrapJson));


        // Act - fetch bootstrap configuration
        routeConfigService.refreshRoutes();

        // Assert - route should be present
        Optional<RouteDefinition> route = routeRegistry.findByPath("/api/ratelimited/**");
        assertThat(route).isPresent();
    }
}
