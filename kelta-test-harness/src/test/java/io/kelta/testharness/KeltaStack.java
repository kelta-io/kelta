package io.kelta.testharness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
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
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

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
     * The role the services connect as. Deliberately <b>not</b> {@link HarnessDbConfig#username()}:
     * that is the image's bootstrap superuser, and a superuser never evaluates a row-level
     * security policy — so every scenario the harness has ever run proved nothing about
     * tenant isolation at the database layer. This role is created NOBYPASSRLS, like
     * production's, so the policies are live for the whole stack.
     *
     * <p>Named after the run's schema on the shared CI pool, where several runs share one
     * Postgres instance and a fixed name would collide. {@code scripts/ci/release-db.sh}
     * drops it alongside the schema.
     */
    static final String APP_ROLE = applicationRoleName();
    static final String APP_PASSWORD = "harness-app-role";

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
    public static final PostgreSQLContainer<?> POSTGRES = DB_CONFIG.external()
            ? null
            : new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
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
            .withEnv("SPRING_DATASOURCE_USERNAME", APP_ROLE)
            .withEnv("SPRING_DATASOURCE_PASSWORD", APP_PASSWORD)
            .withEnv("SPRING_DATA_REDIS_HOST",     "redis")
            .withEnv("SPRING_DATA_REDIS_PORT",     "6379")
            .withEnv("NATS_URL",                   "nats://nats:4222")
            .withEnv("KELTA_AUTH_ISSUER_URI",      "http://kelta-auth:8080")
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
            .withExposedPorts(8080)
            .withLogConsumer(new org.testcontainers.containers.output.Slf4jLogConsumer(
                    org.slf4j.LoggerFactory.getLogger("kelta-worker-container")))
            .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(3)));

    public static final GenericContainer<?> AUTH = serviceContainer("kelta-auth")
            .withNetworkAliases("kelta-auth")
            .withNetwork(NETWORK)
            .withEnv("SPRING_DATASOURCE_URL",      DB_CONFIG.jdbcUrl())
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
            .withExposedPorts(8080)
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

    /** The bootstrap superuser — test-side admin access, bypasses RLS. */
    public static String dbUsername() {
        return DB_CONFIG.username();
    }

    /** @see #dbUsername() */
    public static String dbPassword() {
        return DB_CONFIG.password();
    }

    /** The NOBYPASSRLS role the services run as. Connect as this to observe RLS. */
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
            log.info("Using external Postgres via $CI_DB_JDBC_URL — skipping Testcontainers PG");
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

        provisionApplicationRole();

        // Phase 2: worker (owns Flyway migrations)
        log.info("Phase 2: starting kelta-worker...");
        WORKER.start();
        log.info("kelta-worker healthy (Flyway complete)");

        // Phase 3: auth (needs DB + worker)
        log.info("Phase 3: starting kelta-auth...");
        AUTH.start();
        log.info("kelta-auth healthy");

        // Phase 4: gateway (needs auth JWKS + worker routes)
        log.info("Phase 4: starting kelta-gateway...");
        GATEWAY.start();
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

    public static void stop() {
        GATEWAY.stop();
        AUTH.stop();
        WORKER.stop();
        CERBOS.stop();
        NATS.stop();
        REDIS.stop();
        if (DB_CONFIG.external()) {
            // The local Testcontainers PG is thrown away wholesale; the shared CI instance is
            // not, so the run's role must go with the run. release-db.sh repeats this in case
            // the JVM never reaches here.
            dropApplicationRole();
        }
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
        NETWORK.close();
    }

    // ── Application role (NOBYPASSRLS) ───────────────────────────────────────

    /**
     * Creates the role the services connect as, with row-level security enforced.
     *
     * <p>Run as the bootstrap superuser, because two of these steps need one: creating a role,
     * and installing {@code pg_trgm} — the baseline migration's
     * {@code CREATE EXTENSION IF NOT EXISTS} would fail for an ordinary role, but becomes a
     * no-op once the extension is already there.
     *
     * <p>The role gets CREATE on the target schema and on the database, and nothing else:
     * Flyway runs as it, so every table is created by it and owned by it. That matters — a
     * table's owner is exempt from its own policies unless the table says FORCE, which is
     * exactly why the migrations force RLS rather than merely enabling it.
     *
     * <p>{@code app.current_tenant_id = ''} is the production role default: a connection that
     * binds no tenant is a platform session ({@code admin_bypass}), not one that sees nothing.
     */
    private static void provisionApplicationRole() {
        String schema = targetSchema();
        log.info("Provisioning NOBYPASSRLS application role '{}' on schema '{}'", APP_ROLE, schema);
        try (Connection conn = adminConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            // Idempotent: a re-run inside the same CI schema reuses the role rather than
            // failing on the objects it already owns.
            st.execute("DO $$ BEGIN "
                    + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = " + literal(APP_ROLE) + ") THEN "
                    + "  ALTER ROLE " + ident(APP_ROLE)
                    + "    WITH LOGIN PASSWORD " + literal(APP_PASSWORD) + " NOBYPASSRLS; "
                    + "ELSE "
                    + "  CREATE ROLE " + ident(APP_ROLE)
                    + "    LOGIN PASSWORD " + literal(APP_PASSWORD) + " NOBYPASSRLS; "
                    + "END IF; END $$;");
            st.execute("GRANT ALL ON SCHEMA " + ident(schema) + " TO " + ident(APP_ROLE));
            // Per-tenant collection tables live in a schema named after the tenant slug, which
            // the worker creates at runtime — that needs CREATE on the database, which PUBLIC
            // does not have.
            st.execute("DO $$ BEGIN EXECUTE format("
                    + "'GRANT CREATE, CONNECT, TEMPORARY ON DATABASE %I TO %I', "
                    + "current_database(), " + literal(APP_ROLE) + "); END $$;");
            st.execute("ALTER ROLE " + ident(APP_ROLE) + " SET app.current_tenant_id = ''");
            st.execute("ALTER ROLE " + ident(APP_ROLE) + " SET search_path = " + ident(schema));
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not provision the harness application role '" + APP_ROLE + "'. The harness "
                            + "runs the stack as a NOBYPASSRLS role so RLS is actually exercised; the "
                            + "bootstrap user must be able to CREATE ROLE.", e);
        }
    }

    private static void dropApplicationRole() {
        try (Connection conn = adminConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP OWNED BY " + ident(APP_ROLE) + " CASCADE");
            st.execute("DROP ROLE IF EXISTS " + ident(APP_ROLE));
        } catch (SQLException e) {
            log.warn("Could not drop harness application role '{}': {}", APP_ROLE, e.getMessage());
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(dbJdbcUrl(), DB_CONFIG.username(), DB_CONFIG.password());
    }

    /** {@code app_<run schema>} on the shared CI pool, a fixed name on a throwaway local PG. */
    private static String applicationRoleName() {
        String schema = targetSchema();
        return "public".equals(schema) ? "kelta_app" : "app_" + schema;
    }

    /** The schema the services' JDBC URL pins, or {@code public} when it pins none. */
    private static String targetSchema() {
        String url = DB_CONFIG.jdbcUrl();
        int at = url.indexOf("currentSchema=");
        if (at < 0) {
            return "public";
        }
        String rest = url.substring(at + "currentSchema=".length());
        int end = rest.indexOf('&');
        return end < 0 ? rest : rest.substring(0, end);
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
