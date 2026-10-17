package io.kelta.gateway.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Redis and NATS (JetStream) for the gateway's {@code @SpringBootTest} integration tests.
 *
 * <p>The gateway context cannot start without both: {@code NatsAutoConfiguration} connects at
 * startup, and the rate limiter, route cache and health indicators use Redis. Before this, the
 * suites pointed at {@code localhost:6379}/{@code :4222} and could only pass with a stack already
 * running — so they never ran in CI. Extend this class to get both: static {@link Container}s
 * started by the Testcontainers extension for each test class, wired in through
 * {@link DynamicPropertySource}.
 *
 * <p>Same images as {@code docker-compose.yml}. Skipped without Docker, like the other
 * Testcontainers suites; CI fails a fully-skipped suite ({@code scripts/ci/assert-integration-tests-ran.sh}).
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class GatewayContainers {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @Container
    static final GenericContainer<?> NATS =
            new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
                    .withCommand("-js")
                    .withExposedPorts(4222)
                    .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("kelta.nats.url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }
}
