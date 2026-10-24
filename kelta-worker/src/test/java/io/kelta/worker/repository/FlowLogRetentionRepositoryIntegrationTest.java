package io.kelta.worker.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlowLogRetentionRepository} against real Postgres (PLT-603): per-flow
 * {@code retention_days} windows, and the stale-RUNNING reaper.
 *
 * <p>The connection is the container superuser, which never evaluates an RLS policy — the
 * same view the sweep gets in production, where it runs with no tenant bound.
 *
 * <p>Runs only where Docker is available (Testcontainers), under {@code mvn verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Flow-log retention repository — per-flow windows and stale RUNNING reaper")
class FlowLogRetentionRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "cccccccc-0000-0000-0000-000000000603";
    static final int GLOBAL_DAYS = 60;

    static JdbcTemplate jdbc;
    static FlowLogRetentionRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        repository = new FlowLogRetentionRepository(jdbc);

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

        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, "retention", "retention");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM flow_execution");
        jdbc.execute("DELETE FROM flow");
    }

    @Test
    @DisplayName("a flow's retentionDays replaces the global window, shorter or longer")
    void perFlowWindowShorterAndLonger() {
        Instant now = Instant.now();
        String shortFlow = flow(7);
        String longFlow = flow(120);
        String globalFlow = flow(null);

        // 8 days old: past the 7-day flow only.
        String short8 = execution(shortFlow, "COMPLETED", now.minus(Duration.ofDays(8)));
        String global8 = execution(globalFlow, "COMPLETED", now.minus(Duration.ofDays(8)));
        // 6 days old: inside every window.
        String short6 = execution(shortFlow, "COMPLETED", now.minus(Duration.ofDays(6)));
        // 90 days old: past the global window, inside the 120-day flow's.
        String long90 = execution(longFlow, "FAILED", now.minus(Duration.ofDays(90)));
        String global90 = execution(globalFlow, "FAILED", now.minus(Duration.ofDays(90)));
        // 130 days old: past the 120-day flow's window too.
        String long130 = execution(longFlow, "CANCELLED", now.minus(Duration.ofDays(130)));
        String stepLog = stepLog(short8, now.minus(Duration.ofDays(8)), null);

        assertThat(repository.countDueExecutions(now, GLOBAL_DAYS)).isEqualTo(3);
        assertThat(repository.deleteExecutionBatch(now, GLOBAL_DAYS, 1000)).isEqualTo(3);

        assertThat(exists("flow_execution", short8)).as("7-day flow, 8 days old").isFalse();
        assertThat(exists("flow_step_log", stepLog)).as("step log cascades").isFalse();
        assertThat(exists("flow_execution", global90)).as("global window, 90 days old").isFalse();
        assertThat(exists("flow_execution", long130)).as("120-day flow, 130 days old").isFalse();

        assertThat(exists("flow_execution", global8)).as("global window, 8 days old").isTrue();
        assertThat(exists("flow_execution", short6)).as("7-day flow, 6 days old").isTrue();
        assertThat(exists("flow_execution", long90)).as("120-day flow keeps 90 days").isTrue();
    }

    @Test
    @DisplayName("deletes stay bounded by the batch size")
    void deleteHonoursBatchSize() {
        Instant now = Instant.now();
        String shortFlow = flow(7);
        for (int i = 0; i < 5; i++) {
            execution(shortFlow, "COMPLETED", now.minus(Duration.ofDays(10 + i)));
        }

        assertThat(repository.deleteExecutionBatch(now, GLOBAL_DAYS, 2)).isEqualTo(2);
        assertThat(repository.countDueExecutions(now, GLOBAL_DAYS)).isEqualTo(3);
    }

    @Test
    @DisplayName("the reaper fails a stale RUNNING execution and spares WAITING and active RUNNING")
    void reapsOnlyStaleRunning() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofHours(24));
        Instant old = now.minus(Duration.ofDays(3));
        String flow = flow(null);

        String staleNoSteps = execution(flow, "RUNNING", old);
        String staleWithSteps = execution(flow, "RUNNING", old);
        stepLog(staleWithSteps, old, old.plus(Duration.ofMinutes(5)));
        String waiting = execution(flow, "WAITING", old);
        String active = execution(flow, "RUNNING", old);
        stepLog(active, old, old.plus(Duration.ofMinutes(1)));
        stepLog(active, now.minus(Duration.ofMinutes(10)), null);
        String activeFinishedStep = execution(flow, "RUNNING", old);
        stepLog(activeFinishedStep, old, now.minus(Duration.ofHours(1)));

        assertThat(repository.countStaleRunning(staleBefore)).isEqualTo(2);
        assertThat(repository.reapStaleRunningBatch(staleBefore, "abandoned after 24h", 1000)).isEqualTo(2);

        for (String id : new String[]{staleNoSteps, staleWithSteps}) {
            Map<String, Object> row = execution(id);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error_code")).isEqualTo(FlowLogRetentionRepository.ABANDONED_ERROR_CODE);
            assertThat(row.get("error_message")).isEqualTo("abandoned after 24h");
            assertThat(row.get("completed_at")).isNotNull();
        }
        assertThat(execution(waiting).get("status")).as("WAITING is never reaped").isEqualTo("WAITING");
        assertThat(execution(waiting).get("error_code")).isNull();
        assertThat(execution(active).get("status")).as("recent step start").isEqualTo("RUNNING");
        assertThat(execution(activeFinishedStep).get("status")).as("recent step finish").isEqualTo("RUNNING");

        assertThat(repository.reapStaleRunningBatch(staleBefore, "abandoned after 24h", 1000))
                .as("second pass is a no-op").isZero();
    }

    @Test
    @DisplayName("a recent checkpoint keeps a RUNNING execution whose steps write no log rows")
    void checkpointCountsAsActivity() {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(3));
        String id = execution(flow(null), "RUNNING", old);
        jdbc.update("UPDATE flow_execution SET updated_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofMinutes(5))), id);

        assertThat(repository.reapStaleRunningBatch(now.minus(Duration.ofHours(24)), "x", 1000)).isZero();
        assertThat(execution(id).get("status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a reaped execution is deleted by the sweep once past its window")
    void reapedExecutionIsRetentionEligible() {
        Instant now = Instant.now();
        String flow = flow(7);
        String stuck = execution(flow, "RUNNING", now.minus(Duration.ofDays(3)));

        assertThat(repository.countDueExecutions(now, GLOBAL_DAYS))
                .as("RUNNING is never eligible").isZero();

        repository.reapStaleRunningBatch(now.minus(Duration.ofHours(24)), "abandoned", 1000);
        assertThat(execution(stuck).get("status")).isEqualTo("FAILED");

        assertThat(repository.countDueExecutions(now, GLOBAL_DAYS))
                .as("completed_at is the reap time, so not yet past 7 days").isZero();

        Instant eightDaysLater = now.plus(Duration.ofDays(8));
        assertThat(repository.deleteExecutionBatch(eightDaysLater, GLOBAL_DAYS, 1000)).isEqualTo(1);
        assertThat(exists("flow_execution", stuck)).isFalse();
    }

    @Test
    @DisplayName("the database rejects a non-positive retention_days")
    void checkConstraintRejectsNonPositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> flow(0))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------- Helpers

    private static String flow(Integer retentionDays) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO flow (id, tenant_id, name, flow_type, definition, retention_days)
                VALUES (?, ?, ?, 'AUTOLAUNCHED', '{}'::jsonb, ?)
                """, id, TENANT, "retention-" + id, retentionDays);
        return id;
    }

    /** A terminal row is completed at {@code at}; a RUNNING/WAITING row started and last checkpointed then. */
    private static String execution(String flowId, String status, Instant at) {
        String id = UUID.randomUUID().toString();
        boolean terminal = !status.equals("RUNNING") && !status.equals("WAITING");
        Timestamp ts = Timestamp.from(at);
        jdbc.update("""
                INSERT INTO flow_execution (id, tenant_id, flow_id, status, started_at, completed_at,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, TENANT, flowId, status, ts, terminal ? ts : null, ts, ts);
        return id;
    }

    private static String stepLog(String executionId, Instant startedAt, Instant completedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO flow_step_log (id, execution_id, state_id, state_name, state_type, status,
                                           started_at, completed_at)
                VALUES (?, ?, 'n1', 'Step', 'TASK', ?, ?, ?)
                """, id, executionId, completedAt == null ? "RUNNING" : "COMPLETED",
                Timestamp.from(startedAt), completedAt == null ? null : Timestamp.from(completedAt));
        return id;
    }

    private static Map<String, Object> execution(String id) {
        return jdbc.queryForMap(
                "SELECT status, error_code, error_message, completed_at FROM flow_execution WHERE id = ?", id);
    }

    private static boolean exists(String table, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}
