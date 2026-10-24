package io.kelta.auth.service;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.auth.config.AuthProperties;
import io.kelta.runtime.context.TenantAwareDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthorizationRetentionSweep} against the real {@code oauth2_authorization} table — a
 * database migrated from kelta-worker's Flyway migrations, reached as a role without BYPASSRLS
 * through {@link TenantAwareDataSource}, as kelta-auth runs in production.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("oauth2_authorization retention — real Postgres schema")
class AuthorizationRetentionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    /** kelta-worker owns the migrations; Maven runs tests from the module directory. */
    static final Path MIGRATIONS = Path.of("../kelta-worker/src/main/resources/db/migration")
            .toAbsolutePath().normalize();

    static HikariDataSource pool;
    static JdbcTemplate app;

    final Instant now = Instant.now();
    final Instant longAgo = now.minus(Duration.ofDays(30));
    final Instant withinGrace = now.minus(Duration.ofHours(2));
    final Instant future = now.plus(Duration.ofDays(30));

    @BeforeAll
    static void migrate() {
        assertThat(MIGRATIONS).isDirectory();
        JdbcTemplate admin = new JdbcTemplate(dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector");
        admin.execute("CREATE ROLE auth_app LOGIN PASSWORD 'auth_app' NOBYPASSRLS");
        admin.execute("GRANT ALL ON SCHEMA public TO auth_app");
        admin.execute("ALTER SCHEMA public OWNER TO auth_app");
        admin.execute("ALTER ROLE auth_app SET app.current_tenant_id = ''");
        Flyway.configure()
                .dataSource(dataSource("auth_app", "auth_app"))
                .locations("filesystem:" + MIGRATIONS)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();

        pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("auth_app");
        pool.setPassword("auth_app");
        pool.setMaximumPoolSize(4);
        app = new JdbcTemplate(new TenantAwareDataSource(pool));
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void emptyTable() {
        app.update("DELETE FROM oauth2_authorization");
    }

    @Test
    @DisplayName("deletes rows whose every token expired before the grace period; keeps any row with a live or recent token")
    void deletesOnlySpentRows() {
        // Deleted: everything expired long ago.
        row("spent-code-only", Map.of("authorization_code", longAgo));
        row("spent-all", Map.of("authorization_code", longAgo, "access_token", longAgo,
                "oidc_id_token", longAgo, "refresh_token", longAgo));
        row("spent-device", Map.of("device_code", longAgo, "user_code", longAgo, "access_token", longAgo));
        // Kept.
        row("live-refresh", Map.of("authorization_code", longAgo, "access_token", longAgo,
                "oidc_id_token", longAgo, "refresh_token", future));
        row("live-access", Map.of("access_token", future));
        row("recent", Map.of("authorization_code", longAgo, "access_token", withinGrace));
        row("live-device", Map.of("device_code", future, "user_code", longAgo));
        row("pending-consent", Map.of());
        app.update("INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, "
                + "authorization_grant_type, access_token_value) VALUES ('no-expiry', 'c', 'p', 'authorization_code', 'v')");

        int deleted = sweep(2, 10).deleteExpired();

        assertThat(deleted).isEqualTo(3);
        assertThat(app.queryForList("SELECT id FROM oauth2_authorization ORDER BY id", String.class))
                .containsExactly("live-access", "live-device", "live-refresh", "no-expiry", "pending-consent", "recent");
    }

    @Test
    @DisplayName("a backlog drains in batches; two replicas sweeping at once delete each row once and never fail")
    void concurrentSweepsShareTheBacklog() throws Exception {
        for (int i = 0; i < 300; i++) {
            row("spent-" + i, Map.of("access_token", longAgo, "refresh_token", longAgo));
        }
        row("live", Map.of("refresh_token", future));

        ExecutorService pods = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> sweeps = List.of(() -> sweep(25, 100).deleteExpired(),
                    () -> sweep(25, 100).deleteExpired());
            int total = 0;
            for (Future<Integer> result : pods.invokeAll(sweeps)) {
                total += result.get();
            }
            assertThat(total).isEqualTo(300);
        } finally {
            pods.shutdownNow();
        }
        assertThat(app.queryForList("SELECT id FROM oauth2_authorization", String.class)).containsExactly("live");
    }

    @Test
    @DisplayName("max-batches bounds one sweep; the next sweep continues")
    void boundedSweep() {
        for (int i = 0; i < 10; i++) {
            row("spent-" + i, Map.of("access_token", longAgo));
        }

        assertThat(sweep(3, 2).deleteExpired()).isEqualTo(6);
        assertThat(sweep(3, 2).deleteExpired()).isEqualTo(4);
        assertThat(app.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the sweep and the sign-out lookup by login name can read through their indexes (V210)")
    void lookupsUseTheirIndexes() {
        String literal = "TIMESTAMPTZ '2026-01-01 00:00:00+00'";
        String batch = AuthorizationRetentionSweep.DELETE_BATCH;
        String select = batch.substring(batch.indexOf("SELECT id"), batch.indexOf("LIMIT ?"))
                .replace("?", literal);
        SingleConnectionDataSource connection = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        try {
            JdbcTemplate session = new JdbcTemplate(connection);
            // The table is empty here; turning sequential scans off shows whether an index applies.
            session.execute("SET enable_seqscan = off");
            assertThat(String.join("\n", session.queryForList("EXPLAIN " + select, String.class)))
                    .contains("idx_oauth2_authorization_latest_expiry");
            assertThat(String.join("\n", session.queryForList("EXPLAIN SELECT id FROM oauth2_authorization "
                    + "WHERE lower(principal_name) IN (lower('a@example.test'), lower('a'))", String.class)))
                    .contains("idx_oauth2_authorization_principal_name_lower");
        } finally {
            connection.destroy();
        }
    }

    private AuthorizationRetentionSweep sweep(int batchSize, int maxBatches) {
        AuthProperties.AuthorizationRetention settings = new AuthProperties.AuthorizationRetention();
        settings.setBatchSize(batchSize);
        settings.setMaxBatches(maxBatches);
        settings.setGrace(Duration.ofDays(1));
        return new AuthorizationRetentionSweep(app, settings, new SimpleMeterRegistry(), Clock.systemUTC());
    }

    /** An authorization holding {@code tokens} (token prefix → expiry), each issued an hour before it expires. */
    private void row(String id, Map<String, Instant> tokens) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("id", id);
        columns.put("registered_client_id", "kelta-platform-id");
        columns.put("principal_name", "user@example.test");
        columns.put("authorization_grant_type", "authorization_code");
        tokens.forEach((token, expires) -> {
            columns.put(token + "_value", token + "-" + id);
            columns.put(token + "_issued_at", Timestamp.from(expires.minus(Duration.ofHours(1))));
            columns.put(token + "_expires_at", Timestamp.from(expires));
        });
        List<String> names = new ArrayList<>(columns.keySet());
        app.update("INSERT INTO oauth2_authorization (" + String.join(", ", names) + ") VALUES ("
                + String.join(", ", names.stream().map(name -> "?").toList()) + ")", columns.values().toArray());
    }

    private static DriverManagerDataSource dataSource(String user, String password) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
    }
}
