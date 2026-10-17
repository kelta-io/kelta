package io.kelta.gateway.integration;

import io.kelta.gateway.auth.DynamicReactiveJwtDecoder;
import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.route.RateLimitConfig;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRefresher;
import io.kelta.gateway.route.RouteRegistry;
import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for complete request flow through all gateway filters.
 *
 * Tests the complete request processing pipeline:
 * 1. JwtAuthenticationFilter - validates JWT and extracts principal
 * 2. RateLimitFilter - checks rate limits (if configured)
 * 3. RouteAuthorizationFilter - enforces authentication
 * 4. HeaderTransformationFilter - transforms headers for backend
 * 5. Backend routing - forwards to backend service
 *
 * Validates: Complete request flow through all filters
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class EndToEndRequestFlowIntegrationTest extends GatewayContainers {

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RouteRegistry routeRegistry;

    /** CachingRouteLocator only sees registry changes after a refresh (see RouteRefresher). */
    @Autowired
    private RouteRefresher routeRefresher;

    @MockitoBean
    private DynamicReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    private CerbosAuthorizationService cerbosService;

    @Autowired
    private ObjectMapper objectMapper;

    private MockWebServer mockBackend;

    @BeforeEach
    void setUp() throws IOException {
        // Start mock backend server
        mockBackend = new MockWebServer();
        mockBackend.start();

        // Clear registries
        routeRegistry.clear();

        // Configure mock JWT decoder with identity claims required for permission enforcement
        Jwt mockJwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .claim("roles", List.of("USER"))
                .claim("profile_id", "test-profile-id")
                .claim("profile_name", "Test User")
                .claim("tenant_id", "test-tenant-id")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        // The security chain calls the one-argument decode(String); the tenant-aware overload is
        // kept for callers that pass a tenant. Stub both — an unstubbed one returns null, which
        // Reactor rejects inside AuthenticationWebFilter as "The mapper returned a null Mono" (500).
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(mockJwt));
        when(jwtDecoder.decode(anyString(), anyString())).thenReturn(Mono.just(mockJwt));

        // Mock Cerbos to allow all permissions in end-to-end flow tests
        when(cerbosService.checkSystemPermission(any(GatewayPrincipal.class), anyString()))
                .thenReturn(Mono.just(true));
        when(cerbosService.checkObjectPermission(any(GatewayPrincipal.class), anyString(), anyString()))
                .thenReturn(Mono.just(true));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockBackend != null) {
            mockBackend.shutdown();
        }
    }

    @Test
    void testCompleteRequestFlow_WithAuthentication() throws Exception {
        // Arrange - set up route
        String backendUrl = mockBackend.url("/").toString();
        RouteDefinition route = new RouteDefinition(
                "test-collection",
                "/api/test/**",
                backendUrl,
                "test-collection"
        );
        routeRegistry.addRoute(route);
        routeRefresher.refresh();

        // Mock backend response
        String jsonApiResponse = """
                {
                    "data": {
                        "type": "test",
                        "id": "1",
                        "attributes": {
                            "name": "Test Item",
                            "value": "123"
                        }
                    }
                }
                """;

        mockBackend.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(jsonApiResponse));

        // Act - send request through gateway (X-Tenant-ID required for JWT validation)
        webTestClient.get()
                .uri("/api/test/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer mock-token")
                .header("X-Tenant-ID", "test-tenant-id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.data.type").isEqualTo("test")
                .jsonPath("$.data.id").isEqualTo("1")
                .jsonPath("$.data.attributes.name").isEqualTo("Test Item");

        // Assert - verify backend received transformed headers
        RecordedRequest backendRequest = mockBackend.takeRequest();
        // Collection routes forward the full path (stripPrefix 0): the worker serves /api/<collection>/<id>.
        assertThat(backendRequest.getPath()).isEqualTo("/api/test/1");
        assertThat(backendRequest.getHeader("X-Forwarded-User")).isEqualTo("test-user");
        assertThat(backendRequest.getHeader("X-Forwarded-Groups")).isEqualTo("USER");
        // Backward compatibility: X-Forwarded-Roles should also be present
        assertThat(backendRequest.getHeader("X-Forwarded-Roles")).isEqualTo("USER");
        // HeaderTransformationFilter deliberately preserves Authorization so backends can validate
        // the JWT themselves (documented on the filter since commit 72a6ae96).
        assertThat(backendRequest.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer mock-token");
    }

    @Test
    void testCompleteRequestFlow_WithRateLimiting() throws Exception {
        // Arrange - set up route with rate limiting
        String backendUrl = mockBackend.url("/").toString();
        RateLimitConfig rateLimitConfig = new RateLimitConfig(2, Duration.ofMinutes(1));

        RouteDefinition route = new RouteDefinition(
                "rate-limited-collection",
                "/api/ratelimited/**",
                backendUrl,
                "rate-limited-collection",
                rateLimitConfig
        );
        routeRegistry.addRoute(route);
        routeRefresher.refresh();

        // Mock backend responses
        for (int i = 0; i < 3; i++) {
            mockBackend.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .setBody("{\"data\": {\"type\": \"test\", \"id\": \"1\"}}"));
        }

        // Act & Assert - RateLimitFilter limits by the TENANT's governor budget
        // (apiCallsPerDay / 1440 per minute), not by the route's own RateLimitConfig, so these
        // requests stay within the limit. What the flow must show is the limiter counting in
        // real Redis: the headers are set and the remaining budget goes down by one per request.
        int first = remaining(webTestClient.get()
                .uri("/api/ratelimited/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer mock-token")
                .header("X-Tenant-ID", "test-tenant-id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-RateLimit-Limit")
                .returnResult(String.class).getResponseHeaders());
        int second = remaining(webTestClient.get()
                .uri("/api/ratelimited/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer mock-token")
                .header("X-Tenant-ID", "test-tenant-id")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders());

        assertThat(second).isEqualTo(first - 1);
    }

    private static int remaining(HttpHeaders headers) {
        String value = headers.getFirst("X-RateLimit-Remaining");
        assertThat(value).as("X-RateLimit-Remaining").isNotNull();
        return Integer.parseInt(value);
    }

    @Test
    void testCompleteRequestFlow_UnauthenticatedRequest() {
        // Arrange - set up route
        String backendUrl = mockBackend.url("/").toString();
        RouteDefinition route = new RouteDefinition(
                "test-collection",
                "/api/test/**",
                backendUrl,
                "test-collection"
        );
        routeRegistry.addRoute(route);
        routeRefresher.refresh();

        // Act & Assert - request without JWT should be unauthorized
        webTestClient.get()
                .uri("/api/test/1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                // JSON:API error envelope (conventions.md): errors[], status as a string.
                .jsonPath("$.errors[0].status").isEqualTo("401")
                .jsonPath("$.errors[0].code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void testCompleteRequestFlow_RouteNotFound() {
        // Act & Assert - request to non-existent route should return 404
        webTestClient.get()
                .uri("/api/nonexistent/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer mock-token")
                .header("X-Tenant-ID", "test-tenant-id")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void testCompleteRequestFlow_BackendError() throws Exception {
        // Arrange - set up route
        String backendUrl = mockBackend.url("/").toString();
        RouteDefinition route = new RouteDefinition(
                "test-collection",
                "/api/test/**",
                backendUrl,
                "test-collection"
        );
        routeRegistry.addRoute(route);
        routeRefresher.refresh();

        // Mock backend error response
        mockBackend.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"error\": \"Internal server error\"}"));

        // Act & Assert - backend error should be passed through
        webTestClient.get()
                .uri("/api/test/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer mock-token")
                .header("X-Tenant-ID", "test-tenant-id")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Internal server error");
    }
}
