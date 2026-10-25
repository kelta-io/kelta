package io.kelta.worker;

import dev.cerbos.sdk.CerbosBlockingClient;
import io.kelta.worker.service.CerbosPolicySyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole worker application context — every {@code @Configuration}, {@code @Component},
 * filter, hook and startup listener {@link WorkerApplication} scans — against real Postgres, and
 * serves one record route on a random port. Bean-wiring mistakes (a missing bean, two candidates,
 * an unresolvable {@code @Value}, a startup runner that throws) otherwise compile, pass the unit
 * tests and fail only at pod start.
 *
 * <p>Containers, each because the context cannot start without it:
 * <ul>
 *   <li><b>Postgres</b> ({@code pgvector/pgvector:pg15}, as production) — Flyway runs the real
 *       migrations on start and the system-collection seeder and bootstrap read them.</li>
 *   <li><b>Redis</b> — {@code CerbosPolicySeeder} takes its seed lock with
 *       {@code setIfAbsent} on {@code ApplicationReadyEvent} and does not catch a connection
 *       failure, so a missing Redis fails startup.</li>
 *   <li><b>NATS</b> (JetStream) — {@code NatsConnectionManager} connects in its constructor and
 *       {@code JetStreamInitializer} provisions the streams.</li>
 * </ul>
 *
 * <p>Replaced, each because it cannot run as a container here:
 * <ul>
 *   <li>{@link CerbosBlockingClient} ({@code @MockitoBean}) — the Cerbos gRPC PDP. With the real
 *       client {@code CerbosConfig} retries a warm-up check ten times on startup against a host
 *       that does not exist. The route under test sends no identity, so no check is made.</li>
 *   <li>{@link CerbosPolicySyncService} ({@code @MockitoBean}) — the Cerbos admin HTTP API that
 *       {@code CerbosPolicySeeder} pushes every tenant's policies to on startup.</li>
 *   <li>Svix, Superset, S3/media storage, SMTP, the scheduler and the campaign/reminder/retention
 *       pollers — switched off in {@code application-context-it.yml} (blank Svix token and
 *       Superset URL leave their configurations unregistered).</li>
 *   <li>kelta-auth — nothing calls it at startup: the {@code /internal/**} JWT chain is off by
 *       default and {@code kelta.auth.issuer-uri} is the baseline issuer, so
 *       {@code BaselineIdentityProviderReconciler} has nothing to repair.</li>
 *   <li>OTLP trace and metric export — disabled in the profile; no collector runs.</li>
 * </ul>
 *
 * <p>{@link ExcludeTestClassesFromScan} keeps the component scan off {@code target/test-classes}:
 * other tests nest stand-in {@code @RestController}s that copy {@code DynamicCollectionRouter}'s
 * mappings (e.g. {@code MyNotificationsControllerTest.RouterStub}), and Spring Boot's own test
 * filter only excludes nested classes of a class with a direct {@code @Test} method.
 *
 * <p>Runs only where Docker is available (Testcontainers); CI's worker job runs it.
 */
@SpringBootTest(classes = WorkerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = WorkerApplicationContextIntegrationTest.ExcludeTestClassesFromScan.class)
@ActiveProfiles("context-it")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Worker application context — boots on Postgres, Redis and NATS and serves a record route")
class WorkerApplicationContextIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
            .withCommand("--jetstream", "--store_dir", "/data/jetstream")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    /** The baseline's default tenant (V1__baseline), which owns the seeded system profiles. */
    static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
    /** The baseline's "System Administrator" profile in {@link #DEFAULT_TENANT}. */
    static final String SYSTEM_ADMINISTRATOR_PROFILE = "c3a47939-7890-4a6c-99e2-eb1fe7a5051a";

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("kelta.nats.url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }

    @MockitoBean
    CerbosBlockingClient cerbosBlockingClient;

    @MockitoBean
    CerbosPolicySyncService cerbosPolicySyncService;

    @Autowired
    ApplicationContext context;

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int port;

    @Test
    @DisplayName("the full application context loads")
    void contextLoads() {
        assertThat(context.getBean(WorkerApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("GET /api/profiles answers 200 with a JSON:API list of the seeded profile")
    void listsSeededSystemProfile() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/profiles?filter%5Bname%5D%5Beq%5D=System%20Administrator"))
                .header("X-Tenant-ID", DEFAULT_TENANT)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("data").isArray()).as(response.body()).isTrue();
        assertThat(body.path("data")).hasSize(1);
        JsonNode profile = body.path("data").get(0);
        assertThat(profile.path("type").asString()).isEqualTo("profiles");
        assertThat(profile.path("id").asString()).isEqualTo(SYSTEM_ADMINISTRATOR_PROFILE);
        assertThat(profile.path("attributes").path("name").asString()).isEqualTo("System Administrator");
        assertThat(body.path("meta").path("totalCount").asLong()).isEqualTo(1L);
    }

    /**
     * Registers a {@link TypeExcludeFilter} that drops every class compiled into
     * {@code target/test-classes} from {@link WorkerApplication}'s component scan, so the context
     * under test is the production one.
     */
    static class ExcludeTestClassesFromScan
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            applicationContext.getBeanFactory().registerSingleton(
                    ExcludeTestClassesFromScan.class.getName(), new TestClassesFilter());
        }
    }

    static class TestClassesFilter extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            return metadataReader.getResource().getURL().toString().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
