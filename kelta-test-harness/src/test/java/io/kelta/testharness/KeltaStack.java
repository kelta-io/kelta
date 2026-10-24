package io.kelta.testharness;

import io.kelta.testharness.fixtures.AuthFixture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Singleton stack of Testcontainers that backs all harness scenario tests.
 *
 * <p>Container startup order:
 * <ol>
 *   <li>Infrastructure: Postgres, Redis, NATS, Cerbos (in parallel)
 *   <li>kelta-worker (Flyway runs migrations + seeds)
 *   <li>kelta-auth (needs DB + worker for user lookups)
 *   <li>kelta-gateway (needs auth for JWKS + worker for route bootstrap)
 * </ol>
 *
 * <p>All containers share a single Docker network. Service containers are built
 * from the pre-built fat JARs in each service's {@code target/} directory —
 * run {@code mvn install -DskipTests} for all services before running these tests.
 *
 * <p>A fresh RSA-2048 JWK and AES-256 key are generated per test JVM run so
 * tests are fully self-contained.
 */
public final class KeltaStack {

    private static final Logger log = LoggerFactory.getLogger(KeltaStack.class);

    private static final String SERVICE_VERSION = "1.0.0-SNAPSHOT";
    private static final String ENCRYPTION_KEY;
    private static final String JWK_SET;
    private static final String INTERNAL_TOKEN = "harness-internal-token";

    /**
     * HMAC signing keys the worker refuses to start without — there is deliberately no
     * fallback, since an unset key would sign real visit links, campaign links and support-mailbox
     * Reply-To thread tokens with a value published in this repository. Harness-local literals,
     * never a deployed value.
     */
    private static final String VISIT_SECRET = "harness-visit-secret";
    private static final String CAMPAIGN_TRACKING_SECRET = "harness-campaign-tracking-secret";
    private static final String MAILBOX_VERP_SECRET = "harness-mailbox-verp-secret";

    /**
     * Datasource configuration for the worker + auth services. Built once from env
     * via {@link HarnessDbConfig#resolve}: external when {@code CI_DB_JDBC_URL}
     * is set (shared {@code kelta-ci-db} pool, schema-isolated per CI run), otherwise
     * a Testcontainers PG (local dev fallback).
     */
    static final HarnessDbConfig DB_CONFIG = HarnessDbConfig.resolve(System::getenv);

    /**
     * The role kelta-worker and kelta-auth connect as: {@code NOSUPERUSER NOBYPASSRLS}, shaped
     * like the deployed application role, so every query the services issue is subject to the
     * row-level-security policies exactly as it is in production. A superuser never evaluates
     * a policy, and a harness whose services connect as one passes every tenant-isolation
     * scenario whatever the policies say.
     *
     * <p>Production runs a single role for everything: it owns the control-plane database (and
     * with it schema {@code public}, which PG 15 assigns to {@code pg_database_owner}), the
     * migrate Job runs Flyway as it, and the worker and auth pods connect as it. It carries
     * {@code CREATEROLE} (the worker provisions per-tenant Superset logins) and
     * {@code CREATEDB}, never {@code SUPERUSER} or {@code BYPASSRLS}
     * ({@code playbooks.md} §8). {@link #provisionApplicationRole()} recreates that shape before
     * the worker starts, and the worker's Flyway run creates every table owned by it. Because
     * the role owns the tables, isolation rests on V200–V202 forcing every policy
     * ({@code FORCE ROW LEVEL SECURITY}) — as it does in production.
     *
     * <p>{@link #dbUsername()} stays the image's bootstrap superuser, for test-side fixture
     * planting and inspection only; it provisions this role and nothing in a service container
     * connects as it. {@code ServiceDatabaseRoleScenarioTest} fails the run if either service
     * connects as anything else, or if this role gains {@code SUPERUSER} or {@code BYPASSRLS}.
     */
    static final String APP_ROLE = "kelta_app";
    static final String APP_PASSWORD = "harness-app-role";

    /** How much of a failed service's log to print. Enough to carry a stack trace. */
    private static final int LOG_TAIL_LINES = 120;

    static {
        try {
            ENCRYPTION_KEY = generateEncryptionKey();
            JWK_SET       = generateJwkSet();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ── Docker network shared by all containers ──────────────────────────────

    public static final Network NETWORK = Network.newNetwork();

    // ── Infrastructure containers ────────────────────────────────────────────

    /**
     * Testcontainers PG. {@code null} when an external DB is supplied via
     * {@code CI_DB_JDBC_URL} — see {@link HarnessDbConfig#resolve}.
     */
    @SuppressWarnings("resource")
    public static final PostgreSQLContainer POSTGRES = DB_CONFIG.external()
            ? null
            : new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg15")
                    .asCompatibleSubstituteFor("postgres"))
                    .withNetworkAliases("postgres")
                    .withNetwork(NETWORK)
                    .withDatabaseName("kelta_control_plane")
                    .withUsername("kelta")
                    .withPassword("kelta");

    @SuppressWarnings({"resource", "unchecked"})
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withNetworkAliases("redis")
                    .withNetwork(NETWORK)
                    .withExposedPorts(6379)
                    // forListeningPort() does a plain TCP connect to port 6379.
                    // Avoids relying on docker log streaming (which can hang if the
                    // Docker daemon's log driver buffers or discards output on K8s).
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));

    @SuppressWarnings({"resource", "unchecked"})
    public static final GenericContainer<?> NATS =
            new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
                    .withNetworkAliases("nats")
                    .withNetwork(NETWORK)
                    .withCommand("--jetstream", "--store_dir", "/data/jetstream")
                    .withExposedPorts(4222)
                    .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @SuppressWarnings({"resource", "unchecked"})
    public static final GenericContainer<?> CERBOS =
            new GenericContainer<>(DockerImageName.parse("ghcr.io/cerbos/cerbos:0.40.0"))
                    .withNetworkAliases("cerbos")
                    .withNetwork(NETWORK)
                    .withCommand("server", "--config=/config/config.yaml")
                    // Use withCopyFileToContainer instead of withFileSystemBind.
                    // withFileSystemBind passes a host path to the Docker daemon for a bind-mount,
                    // but in K8s runners the workspace is an emptyDir whose real on-node path is
                    // unknown to Docker.  withCopyFileToContainer reads the files from the test
                    // JVM's filesystem (the pod) and streams them into the container via docker cp,
                    // which works regardless of the volume type.
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(cerbosConfigPath()),
                            "/config/")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(cerbosPoliciesPath()),
                            "/policies/")
                    .withExposedPorts(3592, 3593)
                    .waitingFor(Wait.forHttp("/_cerbos/health").forPort(3592).withStartupTimeout(Duration.ofSeconds(60)));

    // ── Service containers (built from pre-built JARs) ───────────────────────

    public static final GenericContainer<?> WORKER = serviceContainer("kelta-worker")
            .withNetworkAliases("kelta-worker")
            .withNetwork(NETWORK)
            .withEnv("SPRING_DATASOURCE_URL",      DB_CONFIG.jdbcUrl())
            // The production-shaped application role, never the bootstrap superuser (APP_ROLE).
            .withEnv("SPRING_DATASOURCE_USERNAME", APP_ROLE)
            .withEnv("SPRING_DATASOURCE_PASSWORD", APP_PASSWORD)
            .withEnv("SPRING_DATA_REDIS_HOST",     "redis")
            .withEnv("SPRING_DATA_REDIS_PORT",     "6379")
            .withEnv("NATS_URL",                   "nats://nats:4222")
            .withEnv("KELTA_AUTH_ISSUER_URI",      "http://kelta-auth:8080")
            .withEnv("KELTA_AUTH_INTERNAL_URL",    "http://kelta-auth:8080")
            .withEnv("EXTERNAL_BASE_URL",          "http://kelta-gateway:8080")
            .withEnv("CERBOS_HOST",                "cerbos")
            .withEnv("CERBOS_GRPC_PORT",           "3593")
            .withEnv("KELTA_ENCRYPTION_KEY",       ENCRYPTION_KEY)
            .withEnv("KELTA_INTERNAL_TOKEN",       INTERNAL_TOKEN)
            .withEnv("KELTA_TELEHEALTH_VISIT_SECRET", VISIT_SECRET)
            .withEnv("CAMPAIGN_TRACKING_SECRET",   CAMPAIGN_TRACKING_SECRET)
            .withEnv("KELTA_MAILBOX_VERP_SECRET",  MAILBOX_VERP_SECRET)
            .withEnv("EMAIL_ENABLED",              "false")
            .withEnv("SMTP_AUTH",                  "false")
            .withEnv("SMTP_STARTTLS",              "false")
            .withEnv("SCHEDULER_ENABLED",          "false")
            .withEnv("SPRING_FLYWAY_ENABLED",      "true")   // disabled by default (K8s PreSync Job); re-enable for the test harness
            // Media fields only ever presign (a local HMAC) — no object store runs in the harness,
            // so an unreachable endpoint is enough for MediaFieldScenarioTest.
            .withEnv("KELTA_MEDIA_ENABLED",        "true")
            .withEnv("KELTA_MEDIA_ENDPOINT",       "http://media-store.invalid:3900")
            .withEnv("KELTA_MEDIA_PUBLIC_ENDPOINT", "http://media-store.invalid:3900")
            .withEnv("KELTA_MEDIA_BUCKET",         "harness-media")
            .withEnv("KELTA_MEDIA_ACCESS_KEY",     "harness-access-key")
            .withEnv("KELTA_MEDIA_SECRET_KEY",     "harness-secret-key")
            .withExposedPorts(8080)
            .withLogConsumer(new org.testcontainers.containers.output.Slf4jLogConsumer(
                    org.slf4j.LoggerFactory.getLogger("kelta-worker-container")))
            .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(3)));

    public static final GenericContainer<?> AUTH = serviceContainer("kelta-auth")
            .withNetworkAliases("kelta-auth")
            .withNetwork(NETWORK)
            .withEnv("SPRING_DATASOURCE_URL",      DB_CONFIG.jdbcUrl())
            // The production-shaped application role, never the bootstrap superuser (APP_ROLE).
            .withEnv("SPRING_DATASOURCE_USERNAME", APP_ROLE)
            .withEnv("SPRING_DATASOURCE_PASSWORD", APP_PASSWORD)
            .withEnv("SPRING_DATA_REDIS_HOST",    "redis")
            .withEnv("SPRING_DATA_REDIS_PORT",    "6379")
            .withEnv("KELTA_AUTH_ISSUER_URI",     "http://kelta-auth:8080")
            .withEnv("WORKER_SERVICE_URL",        "http://kelta-worker:8080")
            .withEnv("KELTA_ENCRYPTION_KEY",      ENCRYPTION_KEY)
            .withEnv("JWK_SET",                   JWK_SET)
            .withEnv("KELTA_INTERNAL_TOKEN",      INTERNAL_TOKEN)
            .withEnv("COOKIE_DOMAIN",             "localhost")
            .withEnv("UI_BASE_URL",               "http://localhost:5173")
            .withEnv("CORS_ALLOWED_ORIGINS",      "http://localhost:5173")
            .withEnv("DIRECT_LOGIN_ENABLED",      "true")
            // Replaces the baseline platform admin's well-known password on first boot, with a
            // forced change that AuthFixture completes once (BaselineAdminPasswordInitializer).
            .withEnv("KELTA_BOOTSTRAP_ADMIN_PASSWORD", AuthFixture.BOOTSTRAP_ADMIN_PASSWORD)
            .withExposedPorts(8080)
            .withLogConsumer(new org.testcontainers.containers.output.Slf4jLogConsumer(
                    org.slf4j.LoggerFactory.getLogger("kelta-auth-container")))
            .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(2)));

    public static final GenericContainer<?> GATEWAY = serviceContainer("kelta-gateway")
            .withNetworkAliases("kelta-gateway")
            .withNetwork(NETWORK)
            .withEnv("REDIS_HOST",              "redis")
            .withEnv("REDIS_PORT",              "6379")
            .withEnv("NATS_URL",               "nats://nats:4222")
            .withEnv("KELTA_AUTH_ISSUER_URI",   "http://kelta-auth:8080")
            .withEnv("WORKER_SERVICE_URL",      "http://kelta-worker:8080")
            .withEnv("AI_SERVICE_URL",          "http://kelta-ai:8080")
            .withEnv("CERBOS_HOST",             "cerbos")
            .withEnv("CERBOS_GRPC_PORT",        "3593")
            .withEnv("CORS_ALLOWED_ORIGIN_PATTERN",   "http://localhost:5173")
            .withEnv("TENANT_SLUG_REQUIRE_PREFIX",    "true")
            .withEnv("PERMISSIONS_ENABLED",           "false")
            // The fixture provisions its tenant at runtime; refresh the gateway's slug→tenant
            // cache fast (default 60s) so the new tenant resolves promptly instead of 404ing
            // for up to a full refresh interval.
            .withEnv("KELTA_GATEWAY_TENANT_SLUG_CACHE_REFRESH_MS", "2000")
            .withExposedPorts(8080)
            .withLogConsumer(new org.testcontainers.containers.output.Slf4jLogConsumer(
                    org.slf4j.LoggerFactory.getLogger("kelta-gateway-container")))
            .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(2)));

    // ── Accessors ────────────────────────────────────────────────────────────

    /**
     * The AES-256 key every service container shares ({@code KELTA_ENCRYPTION_KEY}), for scenarios
     * that prove a stored ciphertext decrypts the way kelta-auth would decrypt it.
     */
    public static String encryptionKey() {
        return ENCRYPTION_KEY;
    }

    public static String workerBaseUrl() {
        return "http://" + WORKER.getHost() + ":" + WORKER.getMappedPort(8080);
    }

    public static String authBaseUrl() {
        return "http://" + AUTH.getHost() + ":" + AUTH.getMappedPort(8080);
    }

    public static String gatewayBaseUrl() {
        return "http://" + GATEWAY.getHost() + ":" + GATEWAY.getMappedPort(8080);
    }

    /**
     * JDBC URL for the harness database, reachable from the <em>test JVM</em>
     * (not the docker network). External CI URLs are host-reachable as-is; the
     * local Testcontainers PG needs its mapped-port URL. For CI, the URL keeps
     * its {@code currentSchema=ci_<run-tag>} pin so direct connections land in
     * the same isolated schema the services use.
     */
    public static String dbJdbcUrl() {
        return DB_CONFIG.external() ? DB_CONFIG.jdbcUrl() : POSTGRES.getJdbcUrl();
    }

    /**
     * The bootstrap superuser — test-side admin access for planting and inspecting fixtures.
     * Bypasses RLS; no service container connects as it.
     */
    public static String dbUsername() {
        return DB_CONFIG.username();
    }

    /** @see #dbUsername() */
    public static String dbPassword() {
        return DB_CONFIG.password();
    }

    /** The NOBYPASSRLS role the services connect as. Connect as this to observe RLS. */
    public static String appDbUsername() {
        return APP_ROLE;
    }

    /** @see #appDbUsername() */
    public static String appDbPassword() {
        return APP_PASSWORD;
    }

    // ── Startup ──────────────────────────────────────────────────────────────

    /**
     * Start infrastructure and services in the correct order.
     * Called once by {@link KeltaStackExtension}.
     */
    public static void start() {
        log.info("Starting Kelta test stack...");
        if (DB_CONFIG.external()) {
            // The production-shaped application role owns its whole database: schema public
            // (which the Flyway baseline comments on) and the per-tenant schemas the worker
            // creates. A per-run role on the shared kelta-ci-db instance cannot, and running the
            // services as the pool's superuser instead is the false green APP_ROLE exists to end.
            throw new IllegalStateException("CI_DB_JDBC_URL is set, but the shared CI database pool "
                    + "cannot host the harness's production-shaped application role, which must own "
                    + "its database. Unset CI_DB_JDBC_URL to run on a Testcontainers Postgres, or give "
                    + "each run its own database before re-enabling the pool.");
        }

        // Phase 1: infrastructure in parallel.
        // Use a dedicated 4-thread executor rather than ForkJoinPool.commonPool().
        // The common pool's parallelism equals (availableProcessors - 1), which can
        // be as low as 1 on a CPU-constrained K8s pod. With only 1-2 threads, the
        // 3rd and 4th tasks queue behind POSTGRES/NATS and never get scheduled before
        // allOf.join() times out or the runner dies. A fixed pool of 4 guarantees all
        // four containers start concurrently regardless of pod CPU allocation.
        log.info("Phase 1: starting infrastructure containers in parallel...");
        var infra = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "infra-starter-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        try {
            CompletableFuture<Void> postgresF = POSTGRES == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.runAsync(() -> {
                        log.info("  [POSTGRES] starting...");
                        POSTGRES.start();
                        log.info("  [POSTGRES] up");
                    }, infra);
            CompletableFuture<Void> redisF = CompletableFuture.runAsync(() -> {
                log.info("  [REDIS] starting...");
                REDIS.start();
                log.info("  [REDIS] up");
            }, infra);
            CompletableFuture<Void> natsF = CompletableFuture.runAsync(() -> {
                log.info("  [NATS] starting...");
                NATS.start();
                log.info("  [NATS] up");
            }, infra);
            CompletableFuture<Void> cerbosF = CompletableFuture.runAsync(() -> {
                log.info("  [CERBOS] starting...");
                CERBOS.start();
                log.info("  [CERBOS] up");
            }, infra);
            CompletableFuture.allOf(postgresF, redisF, natsF, cerbosF).join();
        } finally {
            infra.shutdown();
        }
        log.info("Infrastructure healthy");

        // The worker runs Flyway as the application role, so the role — and its ownership of
        // the database — has to exist before the worker starts.
        provisionApplicationRole();

        // Phase 2: worker (owns Flyway migrations)
        log.info("Phase 2: starting kelta-worker...");
        startService("kelta-worker", WORKER);
        log.info("kelta-worker healthy (Flyway complete)");

        // Phase 3: auth (needs DB + worker)
        log.info("Phase 3: starting kelta-auth...");
        startService("kelta-auth", AUTH);
        log.info("kelta-auth healthy");

        // Phase 4: gateway (needs auth JWKS + worker routes)
        log.info("Phase 4: starting kelta-gateway...");
        startService("kelta-gateway", GATEWAY);
        log.info("kelta-gateway healthy");

        // Phase 5: seed the ecommerce fixture tenant via the admin API. The Flyway
        // baseline seeds only the `default` tenant; the `threadline-clothing` tenant +
        // its customers/orders/products collections used to come from Flyway V50, which
        // the migration flatten removed. We recreate them at runtime through the gateway
        // so scenarios that treat that tenant as a fixture keep passing.
        log.info("Phase 5: seeding ecommerce fixture tenant...");
        new io.kelta.testharness.fixtures.EcommerceSeedFixture().seedOnce();
        log.info("ecommerce fixture seeded — stack ready");
    }

    /**
     * Starts a service container and, if it never reports healthy, logs the tail of its
     * output before rethrowing.
     *
     * <p>The wait strategy is an HTTP probe on {@code /actuator/health}, so a service that
     * dies during boot looks exactly like one that is merely slow: Testcontainers polls
     * until the startup timeout and then throws a {@code ContainerLaunchException} that says
     * nothing about why. Everything that matters — a Flyway error, a failed connection, a
     * missing privilege — is in the container's own log, which is otherwise discarded with
     * the container. One {@code docker logs} call at the point of failure is what makes a
     * red CI run diagnosable.
     */
    private static void startService(String name, GenericContainer<?> container) {
        try {
            container.start();
        } catch (RuntimeException e) {
            log.error("{} did not become healthy — last {} lines of its log follow:\n{}",
                    name, LOG_TAIL_LINES, tailLogs(container));
            throw e;
        }
    }

    /**
     * Fetches the tail of a container's log, on a daemon thread with a deadline. The fetch
     * is a one-shot {@code docker logs} rather than a follow, but this runs on the failure
     * path of a start that is already timing out — a diagnostic must not be able to become
     * the next hang.
     */
    private static String tailLogs(GenericContainer<?> container) {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kelta-stack-logs");
            t.setDaemon(true);
            return t;
        });
        try {
            return executor.submit(() -> {
                String[] lines = container.getLogs().split("\n");
                int from = Math.max(0, lines.length - LOG_TAIL_LINES);
                return String.join("\n", Arrays.asList(lines).subList(from, lines.length));
            }).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted while reading container logs)";
        } catch (Exception e) {
            return "(could not read container logs: " + e + ")";
        } finally {
            executor.shutdownNow();
        }
    }

    public static void stop() {
        GATEWAY.stop();
        AUTH.stop();
        WORKER.stop();
        CERBOS.stop();
        NATS.stop();
        REDIS.stop();
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
        NETWORK.close();
    }

    // ── Application role (NOBYPASSRLS) ───────────────────────────────────────

    /**
     * Creates {@link #APP_ROLE} in the shape of the deployed application role and hands it the
     * database, before any service starts.
     *
     * <p>Run as the bootstrap superuser: only a superuser may give away a database it does not
     * own, and only a superuser may set {@code NOBYPASSRLS} as an explicit attribute.
     *
     * <p>Ownership of the database is what lets the role run Flyway itself, as production's
     * migrate Job does: schema {@code public} belongs to {@code pg_database_owner} since PG 15
     * (the baseline's {@code COMMENT ON SCHEMA public} needs it), {@code pg_trgm} is a trusted
     * extension any database owner may create, and the worker creates per-tenant schemas at
     * runtime. Nothing is granted table by table — the role owns every table it migrates, as
     * production's does.
     *
     * <p>{@code app.current_tenant_id = ''} is the production role default: a connection that
     * binds no tenant is a platform session ({@code admin_bypass}), not one that sees nothing.
     */
    private static void provisionApplicationRole() {
        log.info("Provisioning NOBYPASSRLS application role '{}' as the database owner", APP_ROLE);
        try (Connection conn = adminConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE ROLE " + ident(APP_ROLE) + " LOGIN PASSWORD " + literal(APP_PASSWORD)
                    + " NOSUPERUSER NOBYPASSRLS CREATEROLE CREATEDB");
            st.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I OWNER TO %I', "
                    + "current_database(), " + literal(APP_ROLE) + "); END $$;");
            st.execute("ALTER ROLE " + ident(APP_ROLE) + " SET app.current_tenant_id = ''");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not provision the harness application role '" + APP_ROLE + "', which "
                            + "kelta-worker and kelta-auth connect as; the bootstrap user must be "
                            + "able to CREATE ROLE and ALTER DATABASE ... OWNER TO.", e);
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(dbJdbcUrl(), DB_CONFIG.username(), DB_CONFIG.password());
    }

    private static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds a lightweight JRE container that runs the pre-built fat JAR
     * for the given service.
     *
     * <p>In CI, set {@code KELTA_HARNESS_PREBUILT_IMAGES=true} and pre-build the
     * images with {@code docker build} before running the harness. This avoids
     * streaming 400 MB of JARs through the Docker socket inside the test JVM,
     * which can starve the runner's GitHub heartbeat thread and cause the
     * "runner lost communication" error on self-hosted K8s runners.
     *
     * <p>In local dev, the image is built on first run from the JAR in
     * {@code ../<service>/target/<service>-<version>.jar}. Subsequent runs reuse
     * the cached Docker image (Testcontainers content-addressable cache).
     */
    @SuppressWarnings("unchecked")
    private static GenericContainer<?> serviceContainer(String service) {
        String imageName = service + "-harness";

        // In CI, images are pre-built by the "Pre-build service images" step.
        // Using a pre-built image skips the 200–400 MB JAR streaming that would
        // otherwise happen inside the test JVM at container start-up time.
        if ("true".equalsIgnoreCase(System.getenv("KELTA_HARNESS_PREBUILT_IMAGES"))) {
            log.info("Using pre-built image {} (KELTA_HARNESS_PREBUILT_IMAGES=true)", imageName);
            // Build a thin wrapper FROM the pre-built image. The Dockerfile is a single
            // FROM line — no COPY, no file streaming — so the build context is ~50 bytes.
            // Docker resolves the FROM from its local image cache without a registry pull
            // (docker build does not pull by default when the image exists locally).
            // The ENTRYPOINT is inherited from the pre-built base image.
            // deleteOnExit=true removes the ephemeral wrapper image after each run so
            // stale references don't accumulate between CI runs.
            return new GenericContainer<>(
                    new ImageFromDockerfile(imageName + "-test", true)
                            .withDockerfileFromBuilder(b -> b.from(imageName))
            );
        }

        Path jarPath = resolveJar(service);
        if (!Files.exists(jarPath)) {
            throw new IllegalStateException(
                    "Service JAR not found: " + jarPath +
                    " — run 'mvn install -DskipTests' for " + service + " first");
        }

        return new GenericContainer<>(
                new ImageFromDockerfile(imageName, false)
                        .withFileFromPath("app.jar", jarPath)
                        .withDockerfileFromBuilder(b -> b
                                .from("eclipse-temurin:25-jre-alpine")
                                .copy("app.jar", "/app.jar")
                                // Cap heap and metaspace so three concurrent service JVMs don't
                                // exceed the runner pod's memory limit. Metaspace is unbounded by
                                // default and can add 200-400 MB per JVM for a complex Spring Boot app.
                                .entryPoint("java",
                                        "-Xmx512m",
                                        "-XX:MaxMetaspaceSize=256m",
                                        "-jar", "/app.jar")
                        )
        );
    }

    private static Path resolveJar(String service) {
        // harness.basedir is the kelta-test-harness/ directory (injected via Maven filtering)
        String basedir = harnessBasedir();
        return Path.of(basedir)
                   .getParent()
                   .resolve(service)
                   .resolve("target")
                   .resolve(service + "-" + SERVICE_VERSION + ".jar");
    }

    private static String harnessBasedir() {
        try (var in = KeltaStack.class.getResourceAsStream("/harness.properties")) {
            if (in == null) throw new IllegalStateException("harness.properties not found on classpath");
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("harness.basedir");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load harness.properties", e);
        }
    }

    private static String cerbosConfigPath() {
        return Path.of(harnessBasedir()).getParent()
                   .resolve("docker/cerbos").toAbsolutePath().toString();
    }

    private static String cerbosPoliciesPath() {
        return Path.of(harnessBasedir()).getParent()
                   .resolve("docker/cerbos/policies").toAbsolutePath().toString();
    }

    private static String generateEncryptionKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String generateJwkSet() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();

        RSAPublicKey pub   = (RSAPublicKey) pair.getPublic();
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) pair.getPrivate();

        String n  = Base64.getUrlEncoder().withoutPadding().encodeToString(pub.getModulus().toByteArray());
        String e  = Base64.getUrlEncoder().withoutPadding().encodeToString(pub.getPublicExponent().toByteArray());
        String d  = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getPrivateExponent().toByteArray());
        String p  = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getPrimeP().toByteArray());
        String q  = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getPrimeQ().toByteArray());
        String dp = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getPrimeExponentP().toByteArray());
        String dq = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getPrimeExponentQ().toByteArray());
        String qi = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getCrtCoefficient().toByteArray());

        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"harness-key\",\"use\":\"sig\",\"alg\":\"RS256\"" +
               ",\"n\":\"" + n + "\",\"e\":\"" + e + "\",\"d\":\"" + d +
               "\",\"p\":\"" + p + "\",\"q\":\"" + q +
               "\",\"dp\":\"" + dp + "\",\"dq\":\"" + dq + "\",\"qi\":\"" + qi + "\"}]}";
    }

    private KeltaStack() {}
}
