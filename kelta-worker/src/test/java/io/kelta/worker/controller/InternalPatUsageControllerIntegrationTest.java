package io.kelta.worker.controller;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PAT last-used UPDATE against a real Postgres: the first call in a window stamps
 * {@code last_used_at}, later calls in the same window change nothing, and a revoked token is
 * never touched.
 *
 * <p>Runs only where Docker is available (Testcontainers), under {@code -Pintegration-tests}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("POST /internal/tokens/{hash}/last-used against Postgres")
class InternalPatUsageControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String USER = "aaaaaaaa-0000-0000-0000-00000000u001";

    static JdbcTemplate jdbc;
    static InternalPatUsageController controller;

    @BeforeAll
    static void migrateAndSeed() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();

        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, "tenant-pat", "tenant-pat");
        jdbc.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)",
                USER, TENANT, "pat-user@example.test");
        controller = new InternalPatUsageController(jdbc);
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM user_api_token WHERE user_id = ?", USER);
        }
    }

    private static String seedToken(boolean revoked) {
        String hash = "hash-" + UUID.randomUUID();
        jdbc.update("INSERT INTO user_api_token (user_id, tenant_id, name, token_prefix, token_hash, expires_at, revoked) "
                        + "VALUES (?, ?, ?, ?, ?, now() + interval '30 days', ?)",
                USER, TENANT, "token-" + hash, "klt_abcd", hash, revoked);
        return hash;
    }

    private static Timestamp lastUsedAt(String hash) {
        return jdbc.queryForObject("SELECT last_used_at FROM user_api_token WHERE token_hash = ?",
                Timestamp.class, hash);
    }

    @Test
    @DisplayName("the first call sets last_used_at; repeated calls within the window leave it unchanged")
    void firstCallStampsRepeatedCallsDoNot() {
        String hash = seedToken(false);
        assertThat(lastUsedAt(hash)).isNull();

        assertThat(controller.recordLastUsed(hash, 300).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Timestamp first = lastUsedAt(hash);
        assertThat(first).isNotNull();

        int laterUpdates = 0;
        for (int i = 0; i < 5; i++) {
            laterUpdates += controller.touchLastUsed(hash, 300);
        }
        assertThat(controller.recordLastUsed(hash, 300).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(laterUpdates).isZero();
        assertThat(lastUsedAt(hash)).isEqualTo(first);
    }

    @Test
    @DisplayName("exactly one row update across repeated calls in one window")
    void exactlyOneUpdatePerWindow() {
        String hash = seedToken(false);

        int updates = 0;
        for (int i = 0; i < 10; i++) {
            updates += controller.touchLastUsed(hash, 300);
        }

        assertThat(updates).isEqualTo(1);
    }

    @Test
    @DisplayName("a last_used_at older than the window is refreshed")
    void staleTimestampIsRefreshed() {
        String hash = seedToken(false);
        jdbc.update("UPDATE user_api_token SET last_used_at = now() - interval '10 minutes' WHERE token_hash = ?", hash);
        Timestamp stale = lastUsedAt(hash);

        assertThat(controller.touchLastUsed(hash, 300)).isEqualTo(1);
        assertThat(lastUsedAt(hash)).isAfter(stale);
    }

    @Test
    @DisplayName("a revoked token is never stamped, and still answers 204")
    void revokedTokenUntouched() {
        String hash = seedToken(true);

        assertThat(controller.recordLastUsed(hash, 300).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(lastUsedAt(hash)).isNull();
    }

    @Test
    @DisplayName("an unknown hash answers 204 and changes nothing")
    void unknownHashNoContent() {
        assertThat(controller.recordLastUsed("no-such-hash", 300).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.touchLastUsed("no-such-hash", 300)).isZero();
    }
}
