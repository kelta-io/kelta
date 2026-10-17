package io.kelta.gateway.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Redis and NATS (JetStream) for the gateway's {@code @SpringBootTest} integration tests.
 *
 * <p>The gateway context cannot start without both: {@code NatsAutoConfiguration} connects at
 * startup, and the rate limiter, route cache and health indicators use Redis. Before this, the
 * suites pointed at {@code localhost:6379}/{@code :4222} and could only pass with a stack already
 * running — so they never ran in CI. Extend this class to get both, wired in through
 * {@link DynamicPropertySource}.
 *
 * <p><b>Singleton containers</b>, started once per JVM and never stopped by the tests (the JVM
 * exit reclaims them). Not {@code @Container}: the JUnit extension stops a class's static
 * containers after that class and starts new ones, on new ports, for the next — while Spring's
 * context cache hands the next class with the same configuration the old context, still wired
 * to the stopped containers, so a suite could pass without ever reaching Redis or NATS.
 *
 * <p>Same images as {@code docker-compose.yml}. Requires Docker: these run only under
 * {@code -Pintegration-tests} (failsafe), and CI fails an integration suite that does not run
 * ({@code scripts/ci/assert-integration-tests-ran.sh}).
 */
public abstract class GatewayContainers {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static final GenericContainer<?> NATS =
            new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
                    .withCommand("-js")
                    .withExposedPorts(4222)
                    .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1));

    static {
        REDIS.start();
        NATS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("kelta.nats.url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }
}
