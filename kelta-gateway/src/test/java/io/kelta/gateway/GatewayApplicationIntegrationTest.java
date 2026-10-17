package io.kelta.gateway;

import io.kelta.gateway.integration.GatewayContainers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test to verify the gateway application starts correctly with full context.
 * Requires Docker services (worker, Keycloak, Redis, NATS) to be running.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class GatewayApplicationIntegrationTest extends GatewayContainers {

    @Test
    void contextLoads() {
        // This test verifies that the Spring Boot application context loads successfully
        // with all external dependencies available
    }
}
