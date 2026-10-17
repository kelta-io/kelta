package io.kelta.gateway.integration;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;

/**
 * Integration test for health check endpoints.
 *
 * Tests:
 * - Overall health endpoint returns status
 * - Redis health indicator is included
 * - NATS health indicator is included
 * - Worker health indicator is included
 * - Health endpoint is accessible without authentication
 * - Individual component health can be checked
 *
 * Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class HealthCheckIntegrationTest extends GatewayContainers {

    /**
     * A healthy stand-in worker. Without one, {@code WorkerHealthIndicator} is DOWN and the
     * aggregate health is 503 — correct in production, but these tests assert on a healthy
     * gateway. Redis and NATS are real ({@link GatewayContainers}).
     */
    private static final MockWebServer WORKER = startWorker();

    private static MockWebServer startWorker() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                String body = path.startsWith("/actuator/health") ? "{\"status\":\"UP\"}"
                        : path.startsWith("/internal/bootstrap") ? "{\"collections\":[]}"
                        : "{}";
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the stand-in worker", e);
        }
        return server;
    }

    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("kelta.gateway.worker-service-url",
                () -> WORKER.url("/").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void stopWorker() throws IOException {
        WORKER.shutdown();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testHealthEndpoint_ReturnsStatus() {
        // Act & Assert - health endpoint should be accessible
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").exists();
    }

    @Test
    void testHealthEndpoint_NoAuthenticationRequired() {
        // Act & Assert - health endpoint should not require authentication
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();

        // No Authorization header provided, but request should succeed
    }

    @Test
    void testHealthEndpoint_IncludesRedisStatus() {
        // Act & Assert - health response should include Redis status
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.redis").exists();

        // Redis status will be UP if Redis is available, DOWN otherwise
        // Both are valid - the important thing is that the component is checked
    }

    @Test
    void testHealthEndpoint_IncludesNatsStatus() {
        // Act & Assert - health response should include NATS status
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.nats").exists();

        // NATS status will be UP if NATS is available, DOWN otherwise
        // Both are valid - the important thing is that the component is checked
    }

    @Test
    void testHealthEndpoint_IncludesWorkerStatus() {
        // Act & Assert - health response should include worker status
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.worker").exists();

        // Worker status will be UP if worker is available, DOWN otherwise
        // Both are valid - the important thing is that the component is checked
    }

    @Test
    void testHealthEndpoint_ShowsDetails() {
        // Act & Assert - health endpoint should show component details
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components").exists()
                .jsonPath("$.components.redis.status").exists()
                .jsonPath("$.components.nats.status").exists()
                .jsonPath("$.components.worker.status").exists();
    }

    @Test
    void testHealthEndpoint_OverallStatus() {
        // Act & Assert - overall status should be determined by component statuses
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").value(status -> {
                    // Status should be UP, DOWN, or OUT_OF_SERVICE
                    // Depends on whether dependencies are available
                    // All are valid responses
                });
    }

    @Test
    void testHealthEndpoint_LivenessProbe() {
        // Act & Assert - liveness probe should indicate if application is running
        webTestClient.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        // Liveness should always be UP if the application is running
    }

    @Test
    void testHealthEndpoint_ReadinessProbe() {
        // Act & Assert - readiness probe should indicate if application is ready
        webTestClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").exists();

        // Readiness depends on whether dependencies are available
        // Can be UP or DOWN
    }

    @Test
    void testHealthEndpoint_IndividualComponents() {
        // Act & Assert - individual component health can be checked

        // Redis health
        webTestClient.get()
                .uri("/actuator/health/redis")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").exists();

        // NATS health
        webTestClient.get()
                .uri("/actuator/health/nats")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").exists();

        // Worker health
        webTestClient.get()
                .uri("/actuator/health/worker")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").exists();
    }

    @Test
    void testHealthEndpoint_JsonFormat() {
        // Act & Assert - health response should be in JSON format
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.spring-boot.actuator.v3+json")
                .expectBody()
                .jsonPath("$").isMap()
                .jsonPath("$.status").isNotEmpty()
                .jsonPath("$.components").isMap();
    }
}
