package io.kelta.worker.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.kelta.worker.util.CronExpressions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for scheduled job persistence and execution tracking.
 *
 * <p>Every worker pod polls for due jobs. {@link #findDueJobs} only lists candidates;
 * {@link #claim} is what decides which pod runs a scheduled slot — a conditional
 * {@code UPDATE} that advances {@code next_run_at}, so exactly one pod wins each slot.
 *
 * @since 1.0.0
 */
@Repository
public class ScheduledJobRepository {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ScheduledJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lists due jobs: active and past their {@code next_run_at}. This is a candidate list, not a
     * claim — every pod may get the same rows, so the caller must win {@link #claim} for a job
     * before running it.
     *
     * <p>It used to end in {@code FOR UPDATE SKIP LOCKED} and was documented as leader election,
     * but a JdbcTemplate query with no tenant bound runs in autocommit, so the row locks were
     * released as soon as the statement returned. Two pods whose polls landed within a few
     * milliseconds of each other both ran every job they listed.
     */
    public List<Map<String, Object>> findDueJobs() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name, job_type, job_reference_id, cron_expression, timezone, config, " +
                        "next_run_at " +
                        "FROM scheduled_job " +
                        "WHERE active = true AND next_run_at <= NOW() " +
                        "ORDER BY next_run_at " +
                        "LIMIT 50"
        );
        // Normalize the jsonb config PGobject to its JSON string form (mirrors findFlowById)
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            normalized.computeIfPresent("config", (k, v) -> v.toString());
            return normalized;
        }).toList();
    }

    /**
     * Loads the flow definition for a scheduled job.
     *
     * <p>The {@code definition} and {@code trigger_config} columns are {@code jsonb},
     * which the JDBC driver returns as {@code PGobject}. Normalize them to their JSON
     * string form so callers can parse them directly — otherwise
     * {@code objectMapper.writeValueAsString(pgObject)} would serialize the wrapper
     * ({@code {"type":"jsonb","value":...}}) and the flow definition's {@code StartAt}
     * would appear missing. Mirrors {@link FlowRepository#findFlowById}.
     */
    public Optional<Map<String, Object>> findFlowById(String flowId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, definition, trigger_config, active FROM flow WHERE id = ?",
                flowId
        );
        if (results.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = new LinkedHashMap<>(results.get(0));
        row.computeIfPresent("definition", (k, v) -> v.toString());
        row.computeIfPresent("trigger_config", (k, v) -> v.toString());
        return Optional.of(row);
    }

    /**
     * Claims one scheduled slot of a job for this pod: moves {@code next_run_at} from
     * {@code scheduledFor} (the value {@link #findDueJobs} read) to {@code nextRunAt}, only if the
     * row still holds {@code scheduledFor} and is active.
     *
     * <p>When two pods race for the same slot, Postgres serializes the two {@code UPDATE}s on the
     * row lock and re-checks the loser's {@code WHERE} against the winner's committed row, where
     * {@code next_run_at} has already moved on — so the loser matches nothing. A schedule edit or
     * pause that lands between the read and the claim makes the claim fail the same way, and the
     * next poll sees the new schedule.
     *
     * <p>The claim is taken before the job runs, so a pod that dies mid-run does not repeat that
     * slot: each slot runs at most once, and the next slot is already scheduled.
     *
     * @param nextRunAt the slot after this one, or null if the cron yields none (the job stops)
     * @return whether this pod owns the slot and must run it
     */
    public boolean claim(String jobId, Instant scheduledFor, Instant nextRunAt) {
        if (scheduledFor == null) {
            log.warn("Scheduled job {} was listed as due with no next_run_at — not claiming it", jobId);
            return false;
        }
        return jdbcTemplate.update(
                "UPDATE scheduled_job SET next_run_at = ?, updated_at = ? " +
                        "WHERE id = ? AND active = true AND next_run_at = ?",
                nextRunAt != null ? Timestamp.from(nextRunAt) : null, Timestamp.from(Instant.now()),
                jobId, Timestamp.from(scheduledFor)
        ) == 1;
    }

    /**
     * Records the outcome of a run. Leaves {@code next_run_at} alone: {@link #claim} already
     * advanced it before the run, and writing it again here would overwrite a schedule edit made
     * during the run, or another pod's claim of the following slot.
     */
    public void updateAfterExecution(String jobId, String lastStatus, Instant lastRunAt) {
        jdbcTemplate.update(
                "UPDATE scheduled_job SET last_run_at = ?, last_status = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(lastRunAt), lastStatus, Timestamp.from(Instant.now()), jobId
        );
    }

    /**
     * Inserts an execution history record into job_execution_log.
     */
    public String insertExecutionLog(String jobId, String status, String errorMessage,
                                     Instant startedAt, Instant completedAt, long durationMs) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO job_execution_log (id, job_id, status, error_message, started_at, completed_at, duration_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, jobId, status, errorMessage,
                Timestamp.from(startedAt),
                completedAt != null ? Timestamp.from(completedAt) : null,
                (int) durationMs
        );
        return id;
    }

    /**
     * Calculates the next run time from NOW for a given cron expression and timezone.
     * Always calculates from NOW to prevent burst-fire after downtime.
     */
    public static Instant calculateNextRunAt(String cronExpression, String timezone) {
        CronExpression cron = CronExpressions.parse(cronExpression);
        ZoneId zone = (timezone != null && !timezone.isBlank()) ? ZoneId.of(timezone) : ZoneId.of("UTC");
        ZonedDateTime next = cron.next(ZonedDateTime.now(zone));
        return next != null ? next.toInstant() : null;
    }

    /**
     * Updates next_run_at for a job (used on create and resume).
     */
    public void updateNextRunAt(String jobId, Instant nextRunAt) {
        jdbcTemplate.update(
                "UPDATE scheduled_job SET next_run_at = ?, updated_at = ? WHERE id = ?",
                nextRunAt != null ? Timestamp.from(nextRunAt) : null, Timestamp.from(Instant.now()), jobId
        );
    }

    /**
     * Pauses a job — sets active=false and clears next_run_at.
     */
    public int pause(String jobId, String tenantId) {
        return jdbcTemplate.update(
                "UPDATE scheduled_job SET active = false, next_run_at = NULL, updated_at = ? " +
                        "WHERE id = ? AND tenant_id = ?",
                Timestamp.from(Instant.now()), jobId, tenantId
        );
    }

    /**
     * Resumes a job — sets active=true and recalculates next_run_at from NOW.
     */
    public int resume(String jobId, String tenantId, Instant nextRunAt) {
        return jdbcTemplate.update(
                "UPDATE scheduled_job SET active = true, next_run_at = ?, updated_at = ? " +
                        "WHERE id = ? AND tenant_id = ?",
                Timestamp.from(nextRunAt), Timestamp.from(Instant.now()), jobId, tenantId
        );
    }

    /**
     * Loads a script by ID for scheduled execution.
     */
    public Optional<Map<String, Object>> findScriptById(String scriptId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name, source_code, active, language FROM script WHERE id = ?",
                scriptId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Loads a report configuration by ID for scheduled export.
     */
    public Optional<Map<String, Object>> findReportById(String reportId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name, report_type, primary_collection_id AS \"primaryCollectionId\", " +
                        "columns, filters, sort_order AS \"sortOrder\", " +
                        "sort_by AS \"sortBy\", sort_direction AS \"sortDirection\", " +
                        "group_by AS \"groupBy\" " +
                        "FROM report WHERE id = ?",
                reportId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Finds a job by ID and tenant (tenant-scoped access).
     */
    public Optional<Map<String, Object>> findByIdAndTenant(String jobId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name, cron_expression, timezone, active FROM scheduled_job " +
                        "WHERE id = ? AND tenant_id = ?",
                jobId, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Finds the scheduled job for a specific flow (by job_reference_id).
     */
    public Optional<Map<String, Object>> findByFlowId(String flowId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name, cron_expression, timezone, active FROM scheduled_job " +
                        "WHERE job_reference_id = ? AND job_type = 'FLOW' AND tenant_id = ?",
                flowId, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Loads the full schedule state for a flow's job — including last/next run tracking —
     * so the UI can render whether the schedule is healthy.
     */
    public Optional<Map<String, Object>> findScheduleByFlowId(String flowId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name, cron_expression, timezone, active, " +
                        "last_run_at, last_status, next_run_at " +
                        "FROM scheduled_job " +
                        "WHERE job_reference_id = ? AND job_type = 'FLOW' AND tenant_id = ?",
                flowId, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns the most recent {@code job_execution_log} rows for a flow's scheduled job,
     * newest first. Returns an empty list if the flow has no scheduled_job row yet —
     * callers must not infer "no runs" from this without first checking
     * {@link #findScheduleByFlowId}, which would distinguish UNSYNCED from "registered
     * but never fired."
     */
    public List<Map<String, Object>> findRecentRunsForFlow(String flowId, String tenantId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT l.id, l.job_id, l.status, l.error_message, " +
                        "l.started_at, l.completed_at, l.duration_ms " +
                        "FROM job_execution_log l " +
                        "JOIN scheduled_job j ON j.id = l.job_id " +
                        "WHERE j.job_reference_id = ? AND j.job_type = 'FLOW' AND j.tenant_id = ? " +
                        "ORDER BY l.started_at DESC " +
                        "LIMIT ?",
                flowId, tenantId, capped
        );
    }

    /**
     * Returns the flow_type for a flow scoped to a tenant, or empty if the flow does
     * not exist for that tenant. Used to decide whether to expose schedule status at
     * all (only SCHEDULED flows can be "unsynced").
     */
    public Optional<String> findFlowType(String flowId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT flow_type FROM flow WHERE id = ? AND tenant_id = ?",
                flowId, tenantId
        );
        if (results.isEmpty()) return Optional.empty();
        Object v = results.get(0).get("flow_type");
        return Optional.ofNullable(v != null ? v.toString() : null);
    }

    /**
     * Inserts a new scheduled_job row for a FLOW.
     *
     * @return the new job id
     */
    public String insertForFlow(String flowId, String tenantId, String name, String cronExpression,
                                 String timezone, boolean active, Instant nextRunAt, String createdBy) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO scheduled_job (id, tenant_id, name, job_type, job_reference_id, " +
                        "cron_expression, timezone, active, next_run_at, created_by) " +
                        "VALUES (?, ?, ?, 'FLOW', ?, ?, ?, ?, ?, ?)",
                id, tenantId,
                name != null ? name : flowId,
                flowId,
                cronExpression,
                timezone != null && !timezone.isBlank() ? timezone : "UTC",
                active,
                nextRunAt != null ? Timestamp.from(nextRunAt) : null,
                createdBy
        );
        log.debug("Inserted scheduled_job {} for flow {}", id, flowId);
        return id;
    }

    /**
     * Updates an existing scheduled_job for a FLOW (cron, timezone, active, next_run_at, name).
     *
     * <p>Warns when the statement matches no row. Without that check this method logged
     * "Updated scheduled_job ..." whether it wrote one row or none, so a schedule that silently
     * stopped syncing was indistinguishable from one that synced fine — and the consequence is
     * invisible in the usual places: the flow still reads {@code active}, and a job left at
     * {@code active=false} or a null {@code next_run_at} simply never fires.
     *
     * @return whether a row was actually updated
     */
    public boolean updateForFlow(String jobId, String name, String cronExpression,
                                  String timezone, boolean active, Instant nextRunAt) {
        int updated = jdbcTemplate.update(
                "UPDATE scheduled_job SET name = ?, cron_expression = ?, timezone = ?, " +
                        "active = ?, next_run_at = ?, updated_at = ? WHERE id = ?",
                name,
                cronExpression,
                timezone != null && !timezone.isBlank() ? timezone : "UTC",
                active,
                nextRunAt != null ? Timestamp.from(nextRunAt) : null,
                Timestamp.from(Instant.now()),
                jobId
        );
        if (updated == 0) {
            log.warn("scheduled_job {} matched no row on update (cron={}, active={}) — "
                    + "the flow's schedule is now out of sync and it will not fire as configured",
                    jobId, cronExpression, active);
            return false;
        }
        log.debug("Updated scheduled_job {} (cron={}, active={})", jobId, cronExpression, active);
        return true;
    }

    /**
     * Deletes all scheduled_job rows for a FLOW. Called when a SCHEDULED flow is deleted
     * or its type changes away from SCHEDULED.
     */
    public void deleteForFlow(String flowId, String tenantId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM scheduled_job WHERE job_reference_id = ? AND job_type = 'FLOW' AND tenant_id = ?",
                flowId, tenantId
        );
        if (deleted > 0) {
            log.info("Deleted {} scheduled_job(s) for flow {}", deleted, flowId);
        }
    }

    // ── MODULE_ACTION: an external module's manifest schedules ─────────────────────────────

    /**
     * The {@code MODULE_ACTION} jobs of one external-module install ({@code job_reference_id} is
     * the {@code tenant_module} id), with their cron and {@code config} as JSON text.
     */
    public List<Map<String, Object>> findModuleSchedules(String installId, String tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT id, name, cron_expression, config FROM scheduled_job " +
                        "WHERE job_type = 'MODULE_ACTION' AND job_reference_id = ? AND tenant_id = ?",
                installId, tenantId
        ).stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            normalized.computeIfPresent("config", (k, v) -> v.toString());
            return normalized;
        }).toList();
    }

    /** Inserts a {@code MODULE_ACTION} job (UTC: a manifest cron names no zone). */
    public String insertModuleSchedule(String tenantId, String installId, String name, String cronExpression,
                                       String configJson, Instant nextRunAt) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO scheduled_job (id, tenant_id, name, job_type, job_reference_id, " +
                        "cron_expression, timezone, active, next_run_at, config) " +
                        "VALUES (?, ?, ?, 'MODULE_ACTION', ?, ?, 'UTC', true, ?, ?::jsonb)",
                id, tenantId, name, installId, cronExpression,
                nextRunAt != null ? Timestamp.from(nextRunAt) : null, configJson
        );
        return id;
    }

    /**
     * Deletes {@code MODULE_ACTION} jobs and their execution history (the history's foreign key
     * has no cascade). {@code jobIds} null deletes every job of the install.
     *
     * @return the number of jobs deleted
     */
    public int deleteModuleSchedules(String installId, String tenantId, Collection<String> jobIds) {
        List<String> ids = jobIds != null ? List.copyOf(jobIds) : jdbcTemplate.queryForList(
                "SELECT id FROM scheduled_job WHERE job_type = 'MODULE_ACTION' AND job_reference_id = ? " +
                        "AND tenant_id = ?", String.class, installId, tenantId);
        int deleted = 0;
        for (String id : ids) {
            jdbcTemplate.update("DELETE FROM job_execution_log WHERE job_id = ?", id);
            deleted += jdbcTemplate.update(
                    "DELETE FROM scheduled_job WHERE id = ? AND job_type = 'MODULE_ACTION' AND tenant_id = ?",
                    id, tenantId);
        }
        return deleted;
    }

    /**
     * Looks up the created_by user ID from the flow table (fallback for hook context).
     */
    public Optional<String> findFlowCreatedBy(String flowId) {
        var results = jdbcTemplate.queryForList(
                "SELECT created_by FROM flow WHERE id = ?", flowId);
        if (results.isEmpty()) return Optional.empty();
        Object v = results.get(0).get("created_by");
        return Optional.ofNullable(v != null ? v.toString() : null);
    }
}
