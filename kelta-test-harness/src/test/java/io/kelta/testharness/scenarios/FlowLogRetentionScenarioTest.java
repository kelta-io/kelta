package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flow-log retention against real Postgres (consumer-alerting slice 2).
 *
 * <p>What only a real database can prove, and what a mocked sweep test cannot:
 * <ul>
 *   <li>The delete statement is <b>valid SQL</b> — {@code FOR UPDATE SKIP LOCKED}
 *       inside an {@code IN (SELECT …)} either works or fails at runtime, never
 *       at compile time.</li>
 *   <li>Deleting a {@code flow_execution} actually <b>cascades</b> to
 *       {@code flow_step_log} and {@code flow_pending_resume}. The sweep relies
 *       on FK behaviour it does not itself implement; if a future migration drops
 *       the cascade, the sweep silently leaves orphans.</li>
 *   <li>The terminal-status filter really spares {@code WAITING} rows. A parked
 *       Wait can sit for months, and collecting one strands a live flow.</li>
 *   <li>A flow's {@code retention_days} replaces the global window for its own
 *       executions only (PLT-603); the {@code make_interval} arithmetic and the
 *       {@code FOR UPDATE OF e} over a join are runtime-only SQL too.</li>
 *   <li>{@code COALESCE(completed_at, started_at)} ages out a terminal row whose
 *       {@code completed_at} is NULL — otherwise it leaks forever, which is the
 *       exact bug this sweep exists to prevent.</li>
 * </ul>
 *
 * <p>The harness has no dependency on kelta-worker, so it cannot invoke the sweep
 * bean. The statements below are copied verbatim from
 * {@code FlowLogRetentionRepository} — <b>keep them in sync</b>, the same
 * contract {@code RecordShareWideningScenarioTest} follows for its RLS SQL.
 */
@DisplayName("Flow Log Retention Scenario")
class FlowLogRetentionScenarioTest extends ScenarioBase {

    /** Verbatim from FlowLogRetentionRepository.DELETE_EXECUTIONS. */
    private static final String DELETE_EXECUTIONS = """
            DELETE FROM flow_execution
             WHERE id IN (
                   SELECT e.id FROM flow_execution e
                     JOIN flow f ON f.id = e.flow_id
                    WHERE e.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                      AND COALESCE(e.completed_at, e.started_at)
                          < CAST(? AS timestamptz) - make_interval(days => COALESCE(f.retention_days, ?))
                    ORDER BY COALESCE(e.completed_at, e.started_at)
                    LIMIT ?
                    FOR UPDATE OF e SKIP LOCKED)
            """;

    /** Verbatim from FlowLogRetentionRepository.COUNT_DUE_EXECUTIONS. */
    private static final String COUNT_DUE_EXECUTIONS = """
            SELECT COUNT(*) FROM flow_execution e
              JOIN flow f ON f.id = e.flow_id
             WHERE e.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
               AND COALESCE(e.completed_at, e.started_at)
                   < CAST(? AS timestamptz) - make_interval(days => COALESCE(f.retention_days, ?))
            """;

    /** The global kelta.flow.retention.max-age-days default. */
    private static final int GLOBAL_DAYS = 60;

    @Test
    @DisplayName("prunes old terminal executions and their step logs, sparing WAITING and fresh")
    void prunesOldTerminalExecutionsOnly() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);

        String suffix = Long.toHexString(System.nanoTime());
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(90));

        try (Connection db = openDbConnection()) {
            String flowId = seedFlow(db, tenantId, suffix, null);
            // Old + terminal, with a completed_at → must go, with its step log.
            String oldCompleted = seedExecution(db, tenantId, flowId, "COMPLETED", old, old);
            String oldStepLog = seedStepLog(db, oldCompleted);
            // Old + terminal but completed_at NULL → must still go (COALESCE).
            String oldNullCompleted = seedExecution(db, tenantId, flowId, "FAILED", old, null);
            // Old but WAITING → must survive regardless of age.
            String oldWaiting = seedExecution(db, tenantId, flowId, "WAITING", old, null);
            // Old but RUNNING → must survive.
            String oldRunning = seedExecution(db, tenantId, flowId, "RUNNING", old, null);
            // Fresh + terminal → must survive.
            String freshCompleted = seedExecution(db, tenantId, flowId, "COMPLETED", now, now);

            try {
                long due = countDue(db, now);
                assertThat(due).as("both old terminal rows are due").isGreaterThanOrEqualTo(2);

                int deleted = deleteBatch(db, now, 1000);
                assertThat(deleted).as("delete statement executes and removes rows")
                        .isGreaterThanOrEqualTo(2);

                assertThat(exists(db, "flow_execution", oldCompleted))
                        .as("old completed execution purged").isFalse();
                assertThat(exists(db, "flow_execution", oldNullCompleted))
                        .as("old terminal row with NULL completed_at purged via COALESCE")
                        .isFalse();

                assertThat(exists(db, "flow_step_log", oldStepLog))
                        .as("step log removed by ON DELETE CASCADE").isFalse();

                assertThat(exists(db, "flow_execution", oldWaiting))
                        .as("WAITING is never collected, however old").isTrue();
                assertThat(exists(db, "flow_execution", oldRunning))
                        .as("RUNNING is never collected").isTrue();
                assertThat(exists(db, "flow_execution", freshCompleted))
                        .as("fresh terminal execution retained").isTrue();

                // Idempotent: a second pass finds nothing new to do.
                assertThat(deleteBatch(db, now, 1000))
                        .as("second run is a no-op").isZero();
            } finally {
                for (String id : new String[]{oldCompleted, oldNullCompleted, oldWaiting,
                        oldRunning, freshCompleted}) {
                    exec(db, "DELETE FROM flow_execution WHERE id = ?", id);
                }
                exec(db, "DELETE FROM flow WHERE id = ?", flowId);
            }
        }
    }

    @Test
    @DisplayName("a flow's retentionDays overrides the global window; a flow without one keeps it")
    void perFlowRetentionDaysOverridesGlobalWindow() throws Exception {
        String adminToken = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(adminToken);

        String suffix = Long.toHexString(System.nanoTime());
        Instant now = Instant.now();
        Instant eightDaysAgo = now.minus(Duration.ofDays(8));

        try (Connection db = openDbConnection()) {
            String flowA = seedFlow(db, tenantId, suffix + "-a", 7);
            String flowB = seedFlow(db, tenantId, suffix + "-b", null);
            String executionA = seedExecution(db, tenantId, flowA, "COMPLETED", eightDaysAgo, eightDaysAgo);
            String stepLogA = seedStepLog(db, executionA);
            String executionB = seedExecution(db, tenantId, flowB, "COMPLETED", eightDaysAgo, eightDaysAgo);

            try {
                assertThat(deleteBatch(db, now, 1000))
                        .as("flow A's execution is past its 7-day window").isGreaterThanOrEqualTo(1);

                assertThat(exists(db, "flow_execution", executionA))
                        .as("flow A (retentionDays 7) loses its 8-day-old execution").isFalse();
                assertThat(exists(db, "flow_step_log", stepLogA))
                        .as("and its step log, by cascade").isFalse();
                assertThat(exists(db, "flow_execution", executionB))
                        .as("flow B (no override, 60-day window) keeps its 8-day-old execution").isTrue();
            } finally {
                exec(db, "DELETE FROM flow_execution WHERE id = ?", executionA);
                exec(db, "DELETE FROM flow_execution WHERE id = ?", executionB);
                exec(db, "DELETE FROM flow WHERE id = ?", flowA);
                exec(db, "DELETE FROM flow WHERE id = ?", flowB);
            }
        }
    }

    // ------------------------------------------------------------- Helpers

    private long countDue(Connection db, Instant now) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(COUNT_DUE_EXECUTIONS)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setInt(2, GLOBAL_DAYS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private int deleteBatch(Connection db, Instant now, int batchSize) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(DELETE_EXECUTIONS)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setInt(2, GLOBAL_DAYS);
            ps.setInt(3, batchSize);
            return ps.executeUpdate();
        }
    }

    private boolean exists(Connection db, String table, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String seedFlow(Connection db, String tenantId, String suffix, Integer retentionDays)
            throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO flow (id, tenant_id, name, description, flow_type, active,
                                  definition, retention_days, created_at, updated_at)
                VALUES (?, ?, ?, 'harness retention fixture', 'AUTOLAUNCHED', false,
                        '{}'::jsonb, ?, NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, "harness-retention-" + suffix);
            if (retentionDays == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, retentionDays);
            }
            ps.executeUpdate();
        }
        return id;
    }

    private String seedExecution(Connection db, String tenantId, String flowId, String status,
                                 Instant startedAt, Instant completedAt) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO flow_execution
                    (id, tenant_id, flow_id, status, started_at, completed_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, flowId);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(startedAt));
            ps.setTimestamp(6, completedAt == null ? null : Timestamp.from(completedAt));
            ps.executeUpdate();
        }
        return id;
    }

    private String seedStepLog(Connection db, String executionId) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO flow_step_log
                    (id, execution_id, state_id, state_name, state_type, status,
                     started_at, created_at, updated_at)
                VALUES (?, ?, 'n1', 'Step One', 'TASK', 'COMPLETED', NOW(), NOW(), NOW())
                """)) {
            ps.setString(1, id);
            ps.setString(2, executionId);
            ps.executeUpdate();
        }
        return id;
    }

    private void exec(Connection db, String sql, String param) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
    }
}
