package io.kelta.worker.repository;

import io.kelta.worker.service.RecordVersionRetentionSweep;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecordVersionRetentionSweep} and the retention SQL in {@link RecordVersionRepository}
 * against a real Postgres with the real Flyway migrations: the dry-run default deletes nothing,
 * the armed sweep deletes exactly the versions past the window or beyond the per-record cap, and
 * each batch is bounded and skips locked rows.
 *
 * <p>Runs only where Docker is available (Testcontainers), under {@code -Pintegration-tests}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("record_version retention sweep")
class RecordVersionRetentionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT_A = "bbbbbbbb-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000002";
    static final String COLLECTION_A = "bbbbbbbb-0000-0000-0000-00000000c001";
    static final String COLLECTION_B = "bbbbbbbb-0000-0000-0000-00000000c002";

    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static RecordVersionRepository repository;

    @Configuration
    @EnableTransactionManagement
    @Import(RecordVersionRetentionSweep.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            ds.setDriverClassName("org.postgresql.Driver");
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        RecordVersionRepository recordVersionRepository(JdbcTemplate jdbcTemplate) {
            return new RecordVersionRepository(jdbcTemplate);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @BeforeAll
    static void migrateAndSeed() {
        context = new AnnotationConfigApplicationContext(Config.class);
        DataSource dataSource = context.getBean(DataSource.class);
        jdbc = context.getBean(JdbcTemplate.class);
        repository = context.getBean(RecordVersionRepository.class);

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

        for (String[] t : List.of(new String[]{TENANT_A, COLLECTION_A, "retention-a"},
                new String[]{TENANT_B, COLLECTION_B, "retention-b"})) {
            jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", t[0], t[2], t[2]);
            jdbc.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                    t[1], "orders", t[0]);
        }
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void clearVersions() {
        jdbc.update("DELETE FROM record_version");
    }

    private static void version(String tenant, String collection, String record, int number, int daysAgo) {
        jdbc.update("""
                INSERT INTO record_version (id, tenant_id, collection_id, record_id, version_number,
                                            change_type, snapshot, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, 'UPDATED', '{}'::jsonb, 'user-1', ?)
                """, UUID.randomUUID().toString(), tenant, collection, record, number,
                Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo))));
    }

    private static long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM record_version", Long.class);
    }

    private static List<Integer> versionsOf(String tenant, String record) {
        return jdbc.queryForList("""
                SELECT version_number FROM record_version
                 WHERE tenant_id = ? AND record_id = ? ORDER BY version_number
                """, Integer.class, tenant, record);
    }

    private static RecordVersionRetentionSweep armed(int maxAgeDays, int maxVersionsPerRecord,
                                                     int batchSize, int maxBatches) {
        return new RecordVersionRetentionSweep(repository, new SimpleMeterRegistry(), true, false,
                maxAgeDays, maxVersionsPerRecord, batchSize, maxBatches);
    }

    @Test
    @DisplayName("default configuration is dry-run: reports the would-delete count and deletes nothing")
    void defaultConfigurationDeletesNothing() {
        // Past the 365-day default window, in two tenants.
        version(TENANT_A, COLLECTION_A, "old-a", 1, 400);
        version(TENANT_B, COLLECTION_B, "old-b", 1, 500);
        version(TENANT_A, COLLECTION_A, "fresh", 1, 1);
        long before = rowCount();

        long wouldDelete = context.getBean(RecordVersionRetentionSweep.class).runCycle();

        assertThat(wouldDelete).isEqualTo(2);
        assertThat(rowCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("armed: deletes only versions past the window or beyond the cap, keeping each record's newest")
    void armedDeletesOnlyDueVersions() {
        // Five recent versions: the cap of 3 keeps 3..5.
        for (int v = 1; v <= 5; v++) {
            version(TENANT_A, COLLECTION_A, "capped", v, 6 - v);
        }
        // Two versions past the 30-day window, two inside it.
        version(TENANT_A, COLLECTION_A, "mixed", 1, 90);
        version(TENANT_A, COLLECTION_A, "mixed", 2, 60);
        version(TENANT_A, COLLECTION_A, "mixed", 3, 10);
        version(TENANT_A, COLLECTION_A, "mixed", 4, 2);
        // Unchanged for longer than the window: every version goes.
        version(TENANT_B, COLLECTION_B, "dormant", 1, 400);
        version(TENANT_B, COLLECTION_B, "dormant", 2, 300);
        // Inside the window and under the cap: untouched.
        version(TENANT_B, COLLECTION_B, "quiet", 1, 5);
        version(TENANT_B, COLLECTION_B, "quiet", 2, 1);

        long deleted = armed(30, 3, 1000, 20).runCycle();

        assertThat(deleted).isEqualTo(6);
        assertThat(versionsOf(TENANT_A, "capped")).containsExactly(3, 4, 5);
        assertThat(versionsOf(TENANT_A, "mixed")).containsExactly(3, 4);
        assertThat(versionsOf(TENANT_B, "dormant")).isEmpty();
        assertThat(versionsOf(TENANT_B, "quiet")).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a cap of 1 keeps exactly the newest version of every record")
    void capOfOneKeepsNewest() {
        for (int v = 1; v <= 4; v++) {
            version(TENANT_A, COLLECTION_A, "r1", v, 1);
            version(TENANT_B, COLLECTION_B, "r2", v, 1);
        }

        armed(365, 1, 1000, 20).runCycle();

        assertThat(versionsOf(TENANT_A, "r1")).containsExactly(4);
        assertThat(versionsOf(TENANT_B, "r2")).containsExactly(4);
    }

    @Test
    @DisplayName("one batch deletes at most batch-size rows, on both the age and the cap path")
    void batchIsBounded() {
        for (int i = 0; i < 10; i++) {
            version(TENANT_A, COLLECTION_A, "old-" + i, 1, 400);
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(365));

        assertThat(repository.deleteDueForRetentionBatch(cutoff, 0, 3)).isEqualTo(3);
        assertThat(repository.deleteDueForRetentionBatch(cutoff, 2, 3)).isEqualTo(3);
        assertThat(rowCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("a cycle stops after max-batches, leaving the remainder for the next cycle")
    void cycleStopsAfterMaxBatches() {
        for (int i = 0; i < 10; i++) {
            version(TENANT_A, COLLECTION_A, "old-" + i, 1, 400);
        }

        long deleted = armed(365, 0, 2, 3).runCycle();

        assertThat(deleted).isEqualTo(6);
        assertThat(rowCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("rows locked by another transaction are skipped, not waited on")
    void skipsLockedRows() throws Exception {
        version(TENANT_A, COLLECTION_A, "locked", 1, 400);
        version(TENANT_A, COLLECTION_A, "free", 1, 400);
        Instant cutoff = Instant.now().minus(Duration.ofDays(365));

        try (Connection other = context.getBean(DataSource.class).getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT id FROM record_version WHERE record_id = 'locked' FOR UPDATE")) {
                lock.executeQuery();
            }

            assertThat(repository.deleteDueForRetentionBatch(cutoff, 0, 10)).isEqualTo(1);
            other.rollback();
        }
        assertThat(versionsOf(TENANT_A, "locked")).containsExactly(1);
        assertThat(versionsOf(TENANT_A, "free")).isEmpty();
    }
}
