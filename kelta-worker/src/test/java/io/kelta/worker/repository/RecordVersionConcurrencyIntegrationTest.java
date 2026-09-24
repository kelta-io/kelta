package io.kelta.worker.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1578 against a real Postgres: many writers minting versions for the <em>same</em> record at
 * once must neither collide on {@code uq_record_version} nor drop a version.
 *
 * <p>{@link RecordVersionRepositoryTest} can only show that the advisory lock is requested before
 * the insert; the race itself needs concurrent sessions. This test runs the real migrations and
 * calls the repository through a Spring proxy, so {@code @Transactional} is live — without it the
 * transaction-scoped lock would be released before the insert and this test would fail the way
 * production did (the pre-fix SQL lost 41 of 200 versions under the same load).
 *
 * <p>Runs only where Docker is available (Testcontainers), under {@code -Pintegration-tests}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("record_version numbering under concurrent writers (#1578)")
class RecordVersionConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String COLLECTION = "aaaaaaaa-0000-0000-0000-00000000c001";
    static final String RECORD = "rec-hot";
    static final int WRITERS = 8;
    static final int VERSIONS_PER_WRITER = 25;

    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement
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
    }

    @BeforeAll
    static void migrateAndSeed() {
        context = new AnnotationConfigApplicationContext(Config.class);
        DataSource dataSource = context.getBean(DataSource.class);
        jdbc = context.getBean(JdbcTemplate.class);

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

        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, "tenant-a", "tenant-a");
        jdbc.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                COLLECTION, "orders", TENANT);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("every concurrent version is stored, numbered 1..N with no gap or collision")
    void concurrentWritersGetContiguousVersions() throws Exception {
        RecordVersionRepository repository = context.getBean(RecordVersionRepository.class);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int w = 0; w < WRITERS; w++) {
                results.add(pool.submit(() -> {
                    start.await();
                    int written = 0;
                    for (int i = 0; i < VERSIONS_PER_WRITER; i++) {
                        repository.recordVersion(TENANT, COLLECTION, RECORD, "UPDATED",
                                "{}", "[]", "user-1", "UI");
                        written++;
                    }
                    return written;
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                // Any DuplicateKeyException from a writer surfaces here as a failure.
                assertThat(result.get(2, TimeUnit.MINUTES)).isEqualTo(VERSIONS_PER_WRITER);
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS rows, COUNT(DISTINCT version_number) AS distinct_versions,
                       MIN(version_number) AS min_version, MAX(version_number) AS max_version
                FROM record_version WHERE tenant_id = ? AND collection_id = ? AND record_id = ?
                """, TENANT, COLLECTION, RECORD);
        int expected = WRITERS * VERSIONS_PER_WRITER;
        assertThat(((Number) stats.get("rows")).intValue()).isEqualTo(expected);
        assertThat(((Number) stats.get("distinct_versions")).intValue()).isEqualTo(expected);
        assertThat(((Number) stats.get("min_version")).intValue()).isEqualTo(1);
        assertThat(((Number) stats.get("max_version")).intValue()).isEqualTo(expected);
    }
}
