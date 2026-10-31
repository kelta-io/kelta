package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.ScheduledJobRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only endpoints that surface the scheduler state for a flow.
 *
 * <p>Until now the only way to know whether a SCHEDULED flow was actually registered
 * with the cron executor was to {@code psql} the control-plane {@code scheduled_job}
 * and {@code job_execution_log} tables. A flow whose {@link
 * io.kelta.worker.listener.FlowScheduleSyncHook sync hook} silently failed (no
 * triggerConfig, blank cron, etc.) looked fine in the UI while never running.
 *
 * <p>{@link #getFlowSchedule} returns the {@code scheduled_job} row plus a derived
 * {@code scheduleStatus} ({@code ACTIVE}/{@code PAUSED}/{@code UNSYNCED}/{@code
 * NONE}); {@link #getFlowRuns} returns recent {@code job_execution_log} rows.
 *
 * <p>Both need internal staff holding {@code MANAGE_WORKFLOWS} or {@code VIEW_SETUP} (never a
 * portal member): the gateway checks only writes under {@code /api/flows/**}, so a read here
 * otherwise needs no more than {@code API_ACCESS}.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/flows")
public class FlowScheduleStatusController {

    private static final Logger log = LoggerFactory.getLogger(FlowScheduleStatusController.class);

    private final ScheduledJobRepository repository;
    private final AdminPermissionGuard permissionGuard;

    public FlowScheduleStatusController(ScheduledJobRepository repository, AdminPermissionGuard permissionGuard) {
        this.repository = repository;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping("/{flowId}/schedule")
    public ResponseEntity<Map<String, Object>> getFlowSchedule(@PathVariable String flowId,
                                                               HttpServletRequest request) {
        permissionGuard.requireInternalAny(request, FlowExecutionController.MANAGE_WORKFLOWS,
                FlowExecutionController.VIEW_SETUP);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        Optional<String> flowTypeOpt = repository.findFlowType(flowId, tenantId);
        if (flowTypeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String flowType = flowTypeOpt.get();

        Optional<Map<String, Object>> jobOpt = repository.findScheduleByFlowId(flowId, tenantId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowId", flowId);
        body.put("flowType", flowType);

        if (jobOpt.isEmpty()) {
            if ("SCHEDULED".equals(flowType)) {
                body.put("scheduleStatus", "UNSYNCED");
                body.put("reason", "No scheduled_job row registered for this flow");
            } else {
                body.put("scheduleStatus", "NONE");
            }
            log.debug("No scheduled_job for flowId={}, tenantId={}, flowType={}", flowId, tenantId, flowType);
            return ResponseEntity.ok(body);
        }

        Map<String, Object> job = jobOpt.get();
        boolean active = Boolean.TRUE.equals(job.get("active"));
        Instant nextRunAt = toInstant(job.get("next_run_at"));

        body.put("cron", job.get("cron_expression"));
        body.put("timezone", job.get("timezone"));
        body.put("active", active);
        body.put("lastRunAt", toIso(job.get("last_run_at")));
        body.put("lastStatus", job.get("last_status"));
        body.put("nextRunAt", toIso(job.get("next_run_at")));

        if ("SCHEDULED".equals(flowType) && active && nextRunAt == null) {
            body.put("scheduleStatus", "UNSYNCED");
            body.put("reason", "scheduled_job has no next_run_at — executor will never pick it up");
        } else {
            body.put("scheduleStatus", active ? "ACTIVE" : "PAUSED");
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{flowId}/runs")
    public ResponseEntity<Map<String, Object>> getFlowRuns(
            @PathVariable String flowId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        permissionGuard.requireInternalAny(request, FlowExecutionController.MANAGE_WORKFLOWS,
                FlowExecutionController.VIEW_SETUP);
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        Optional<String> flowTypeOpt = repository.findFlowType(flowId, tenantId);
        if (flowTypeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> rows = repository.findRecentRunsForFlow(flowId, tenantId, limit);
        List<Map<String, Object>> runs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", row.get("id"));
            r.put("jobId", row.get("job_id"));
            r.put("status", row.get("status"));
            r.put("startedAt", toIso(row.get("started_at")));
            r.put("completedAt", toIso(row.get("completed_at")));
            r.put("durationMs", row.get("duration_ms"));
            r.put("errorMessage", row.get("error_message"));
            runs.add(r);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowId", flowId);
        body.put("count", runs.size());
        body.put("runs", runs);
        return ResponseEntity.ok(body);
    }

    private static String toIso(Object v) {
        Instant i = toInstant(v);
        return i == null ? null : i.toString();
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.util.Date d) return d.toInstant();
        return null;
    }
}
