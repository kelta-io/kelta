package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Age-based pruning of flow and job execution history.
 *
 * <p>Nothing else in the platform deletes these tables — {@code JdbcFlowStore}'s
 * only DELETE is {@code deletePendingResume} — so they grow without bound. A
 * high-frequency flow makes that growth the dominant table in the database.
 *
 * <p><b>Only terminal executions are eligible.</b> {@code RUNNING} and
 * {@code WAITING} rows are never touched regardless of age: a Wait state can
 * legitimately park for months, and deleting one would strand a live flow with
 * no execution row to resume into.
 *
 * <p><b>Age uses {@code COALESCE(completed_at, started_at)}.</b> {@code
 * completed_at} is nullable, and a terminal row that somehow never got one would
 * be invisible to a plain {@code completed_at < cutoff} predicate — i.e. it would
 * leak forever, which is precisely the bug this sweep exists to fix.
 *
 * <p><b>Per-flow windows (PLT-603).</b> A flow's {@code retention_days}
 * replaces the global window for its executions, shorter or longer; NULL keeps
 * the global one.
 *
 * <p><b>Stale RUNNING executions.</b> An execution whose worker died mid-run
 * stays RUNNING forever and so is never eligible. {@link #reapStaleRunningBatch}
 * marks one FAILED ({@code error_code = 'Abandoned'}, {@code completed_at} set),
 * after which it ages out like any other failure. WAITING is never reaped.
 *
 * <p>Deletes are batched and claim rows with {@code FOR UPDATE SKIP LOCKED}, so
 * concurrent pods take disjoint slices instead of deadlocking. Hand-written SQL
 * on {@link JdbcTemplate} — no JPA.
 */
@Repository
public class FlowLogRetentionRepository {

    /**
     * Statuses safe to prune. Deliberately enumerated rather than expressed as
     * "not RUNNING/WAITING", so a future status added to the CHECK constraint is
     * excluded until someone decides it is terminal.
     */
    static final List<String> TERMINAL_STATUSES = List.of("COMPLETED", "FAILED", "CANCELLED");

    /**
     * An execution's window is its flow's {@code retention_days}, else the global
     * window (PLT-603). The join is on {@code flow}'s primary key and
     * {@code flow_execution.flow_id} is a NOT NULL foreign key, so every
     * execution resolves to exactly one flow. The cutoff is computed in SQL from
     * a single {@code now} parameter so one statement covers every window.
     */
    private static final String COUNT_DUE_EXECUTIONS = """
            SELECT COUNT(*) FROM flow_execution e
              JOIN flow f ON f.id = e.flow_id
             WHERE e.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
               AND COALESCE(e.completed_at, e.started_at)
                   < CAST(? AS timestamptz) - make_interval(days => COALESCE(f.retention_days, ?))
            """;

    /**
     * Cascades to {@code flow_step_log} and {@code flow_pending_resume} via their
     * {@code ON DELETE CASCADE} foreign keys — those are the only two tables
     * referencing {@code flow_execution}, both verified cascading, so no orphan
     * rows are left behind and no delete can fail on a dependent constraint.
     * {@code FOR UPDATE OF e} locks only the execution rows, never the flow.
     */
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

    /**
     * Last activity of a RUNNING execution: the latest step-log start or finish,
     * the execution's own checkpoint ({@code updated_at}, written after every
     * step even when {@code stepLogging} keeps no step row), or its start.
     * {@code GREATEST} ignores NULLs. The {@code updated_at < ?} and
     * {@code started_at < ?} predicates are implied by the activity predicate and
     * only let the planner discard fresh rows before the step-log lookup.
     */
    private static final String STALE_RUNNING_PREDICATE = """
            x.status = 'RUNNING'
                      AND x.started_at < ?
                      AND x.updated_at < ?
                      AND GREATEST(x.started_at, x.updated_at,
                              (SELECT MAX(GREATEST(s.started_at, s.completed_at))
                                 FROM flow_step_log s
                                WHERE s.execution_id = x.id)) < ?
            """;

    private static final String COUNT_STALE_RUNNING =
            "SELECT COUNT(*) FROM flow_execution x WHERE " + STALE_RUNNING_PREDICATE;

    /**
     * Fails stale RUNNING executions. The outer {@code status = 'RUNNING'} guard
     * is re-checked against the latest row version, so an execution that
     * finishes concurrently keeps its own outcome.
     */
    private static final String REAP_STALE_RUNNING = """
            UPDATE flow_execution
               SET status = 'FAILED', error_code = ?, error_message = ?,
                   completed_at = NOW(), updated_at = NOW()
             WHERE status = 'RUNNING'
               AND id IN (
                   SELECT x.id FROM flow_execution x
                    WHERE %s
                    ORDER BY x.started_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
            """.formatted(STALE_RUNNING_PREDICATE);

    /** {@code flow_execution.error_code} written by the stale-RUNNING reaper. */
    public static final String ABANDONED_ERROR_CODE = "Abandoned";

    private static final String COUNT_DUE_JOB_LOGS = """
            SELECT COUNT(*) FROM job_execution_log
             WHERE COALESCE(completed_at, started_at) < ?
            """;

    private static final String DELETE_JOB_LOGS = """
            DELETE FROM job_execution_log
             WHERE id IN (
                   SELECT id FROM job_execution_log
                    WHERE COALESCE(completed_at, started_at) < ?
                    ORDER BY COALESCE(completed_at, started_at)
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
            """;

    private final JdbcTemplate jdbc;

    public FlowLogRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * How many terminal executions are past their window: their flow's
     * {@code retention_days}, else {@code globalDays}, measured back from {@code now}.
     */
    public long countDueExecutions(Instant now, int globalDays) {
        Long count = jdbc.queryForObject(COUNT_DUE_EXECUTIONS, Long.class,
                Timestamp.from(now), globalDays);
        return count == null ? 0L : count;
    }

    /** Deletes up to {@code batchSize} due executions; returns rows removed. */
    public int deleteExecutionBatch(Instant now, int globalDays, int batchSize) {
        return jdbc.update(DELETE_EXECUTIONS, Timestamp.from(now), globalDays, batchSize);
    }

    /** How many RUNNING executions have had no activity since {@code staleBefore}. */
    public long countStaleRunning(Instant staleBefore) {
        Timestamp cutoff = Timestamp.from(staleBefore);
        Long count = jdbc.queryForObject(COUNT_STALE_RUNNING, Long.class, cutoff, cutoff, cutoff);
        return count == null ? 0L : count;
    }

    /**
     * Marks up to {@code batchSize} stale RUNNING executions FAILED with
     * {@link #ABANDONED_ERROR_CODE} and a completion time; returns rows updated.
     */
    public int reapStaleRunningBatch(Instant staleBefore, String errorMessage, int batchSize) {
        Timestamp cutoff = Timestamp.from(staleBefore);
        return jdbc.update(REAP_STALE_RUNNING, ABANDONED_ERROR_CODE, errorMessage,
                cutoff, cutoff, cutoff, batchSize);
    }

    /**
     * How many job-execution log rows are older than {@code cutoff}.
     *
     * <p>No status filter here, unlike executions: {@code job_execution_log} has
     * no resumable state to strand, and a row whose run started before the cutoff
     * and never completed is a dead scheduler run, not work in progress.
     */
    public long countDueJobLogs(Instant cutoff) {
        Long count = jdbc.queryForObject(COUNT_DUE_JOB_LOGS, Long.class, Timestamp.from(cutoff));
        return count == null ? 0L : count;
    }

    /** Deletes up to {@code batchSize} due job-log rows; returns rows removed. */
    public int deleteJobLogBatch(Instant cutoff, int batchSize) {
        return jdbc.update(DELETE_JOB_LOGS, Timestamp.from(cutoff), batchSize);
    }
}
