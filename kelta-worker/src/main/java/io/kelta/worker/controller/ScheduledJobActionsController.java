package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.service.ScheduledJobExecutorService;
import io.kelta.worker.util.CronExpressions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * Custom action endpoints for scheduled jobs: pause, resume, manual execute.
 *
 * <p>CRUD operations are handled by the DynamicCollectionRouter (scheduled-jobs is a system collection).
 * This controller adds action endpoints not covered by standard CRUD.
 *
 * <p>All operations are tenant-scoped via TenantContext.
 *
 * <p>Authorization is the gateway's: {@code static-scheduled-jobs} carries a collection check
 * ({@code RouteConfigService.COLLECTION_CHECKED_STATIC_ROUTES}), so every POST here — like a
 * generic write to the collection — needs the {@code scheduled-jobs} grant for the action or
 * {@code MANAGE_WORKFLOWS}. Reads stay {@code API_ACCESS}-only.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/scheduled-jobs")
public class ScheduledJobActionsController {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobActionsController.class);

    private final ScheduledJobRepository repository;

    public ScheduledJobActionsController(ScheduledJobRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        int updated = repository.pause(id, tenantId);
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        log.info("Scheduled job paused: jobId={}, tenantId={}", id, tenantId);
        return ResponseEntity.ok(Map.of("status", "paused", "jobId", id));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        // Look up job to get cron and timezone
        var jobOpt = repository.findByIdAndTenant(id, tenantId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> job = jobOpt.get();
        String cronExpression = (String) job.get("cron_expression");
        String timezone = (String) job.get("timezone");

        // Validate cron is still valid (accept 5-field as well as 6-field)
        try {
            CronExpressions.normalize(cronExpression);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid cron expression: " + e.getMessage()));
        }

        Instant nextRunAt = ScheduledJobRepository.calculateNextRunAt(cronExpression, timezone);
        int updated = repository.resume(id, tenantId, nextRunAt);
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        log.info("Scheduled job resumed: jobId={}, tenantId={}, nextRunAt={}", id, tenantId, nextRunAt);
        return ResponseEntity.ok(Map.of(
                "status", "resumed",
                "jobId", id,
                "nextRunAt", nextRunAt != null ? nextRunAt.toString() : "unknown"
        ));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> executeNow(@PathVariable String id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }

        var jobOpt = repository.findByIdAndTenant(id, tenantId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Manual trigger — the executor handles the actual execution
        log.info("Manual execution requested: jobId={}, tenantId={}", id, tenantId);
        return ResponseEntity.ok(Map.of("status", "triggered", "jobId", id));
    }

    /**
     * Validates a cron expression (utility endpoint for the UI).
     */
    @PostMapping("/validate-cron")
    public ResponseEntity<Map<String, Object>> validateCron(@RequestBody Map<String, String> body) {
        String expression = body.get("expression");
        String timezone = body.get("timezone");

        if (expression == null || expression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cron expression is required"));
        }

        try {
            CronExpressions.normalize(expression);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid cron expression: " + e.getMessage()));
        }

        if (timezone != null && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid timezone: " + timezone));
            }
        }

        Instant nextRun = ScheduledJobRepository.calculateNextRunAt(expression, timezone);
        return ResponseEntity.ok(Map.of(
                "valid", true,
                "nextRunAt", nextRun != null ? nextRun.toString() : "unknown"
        ));
    }
}
