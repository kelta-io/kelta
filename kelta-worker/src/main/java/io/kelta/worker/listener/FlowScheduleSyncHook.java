package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.util.CronExpressions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the {@code scheduled_job} table in sync with SCHEDULED flow configurations,
 * and validates the cron expression up-front so misconfigured flows fail loudly
 * instead of silently never running.
 *
 * <p>When a flow with {@code flowType = SCHEDULED} is created or updated, this hook:
 * <ul>
 *   <li>{@link #beforeCreate}/{@link #beforeUpdate} — validates
 *       {@code triggerConfig.cron} via {@link CronExpressions#normalize}. A 5-field
 *       expression is normalized to 6-field (prepending {@code "0 "} for seconds);
 *       anything still unparseable returns a {@link BeforeSaveResult} error that
 *       the query engine translates into a 400 response. Previously the parse
 *       failure was caught in {@code afterCreate} and logged at warn, so the flow
 *       persisted but no {@code scheduled_job} row was ever created.</li>
 *   <li>{@link #afterCreate}/{@link #afterUpdate} — upserts the corresponding
 *       {@code scheduled_job} row using the normalized cron expression.</li>
 *   <li>{@link #afterDelete} (or type change away from SCHEDULED) — removes the row.</li>
 * </ul>
 *
 * <p>This bridges the gap between saving a flow's trigger configuration in the UI and
 * the scheduler executor picking it up — the executor reads from {@code scheduled_job},
 * so the row must exist and stay current.
 *
 * @since 1.0.0
 */
@Component
public class FlowScheduleSyncHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FlowScheduleSyncHook.class);

    private final ScheduledJobRepository scheduledJobRepository;
    private final ObjectMapper objectMapper;

    public FlowScheduleSyncHook(ScheduledJobRepository scheduledJobRepository, ObjectMapper objectMapper) {
        this.scheduledJobRepository = scheduledJobRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCollectionName() {
        return "flows";
    }

    @Override
    public int getOrder() {
        // Run after FlowConfigEventPublisher (100) — job sync is a side effect, not a guard
        return 150;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validateAndNormalize(record);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return validateAndNormalize(record);
    }

    /**
     * Validates {@code triggerConfig.cron} for SCHEDULED flows and rewrites it to
     * the canonical 6-field form. Non-SCHEDULED flows, or SCHEDULED flows whose
     * payload does not include {@code triggerConfig} (e.g., a partial update that
     * only changes {@code active}), are passed through untouched.
     */
    private BeforeSaveResult validateAndNormalize(Map<String, Object> record) {
        if (!"SCHEDULED".equals(asString(record.get("flowType")))) {
            return BeforeSaveResult.ok();
        }
        Map<String, Object> triggerConfig = extractTriggerConfig(record);
        if (triggerConfig == null || triggerConfig.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        Object cronRaw = triggerConfig.get("cron");
        if (cronRaw == null || cronRaw.toString().isBlank()) {
            return BeforeSaveResult.ok();
        }
        String cron = cronRaw.toString();
        String normalized;
        try {
            normalized = CronExpressions.normalize(cron);
        } catch (IllegalArgumentException e) {
            return BeforeSaveResult.error("triggerConfig.cron", e.getMessage());
        }
        if (normalized.equals(cron)) {
            return BeforeSaveResult.ok();
        }
        Map<String, Object> updatedTriggerConfig = new LinkedHashMap<>(triggerConfig);
        updatedTriggerConfig.put("cron", normalized);
        return BeforeSaveResult.withFieldUpdates(Map.of("triggerConfig", updatedTriggerConfig));
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        if (!"SCHEDULED".equals(record.get("flowType"))) return;
        syncScheduledJob(record, tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        String newType = asString(record.get("flowType"));
        String prevType = previous != null ? asString(previous.get("flowType")) : null;

        if ("SCHEDULED".equals(newType)) {
            syncScheduledJob(record, tenantId);
        } else if ("SCHEDULED".equals(prevType)) {
            scheduledJobRepository.deleteForFlow(id, tenantId);
        }
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        scheduledJobRepository.deleteForFlow(id, tenantId);
    }

    private void syncScheduledJob(Map<String, Object> record, String tenantId) {
        String flowId = asString(record.get("id"));
        String flowName = asString(record.get("name"));
        Object activeObj = record.get("active");
        boolean active = activeObj instanceof Boolean b ? b : true;

        Map<String, Object> triggerConfig = extractTriggerConfig(record);
        if (triggerConfig == null || triggerConfig.isEmpty()) {
            log.debug("SCHEDULED flow {} has no triggerConfig — skipping job sync", flowId);
            return;
        }

        String cron = asString(triggerConfig.get("cron"));
        String timezone = asString(triggerConfig.get("timezone"));

        if (cron == null || cron.isBlank()) {
            log.debug("SCHEDULED flow {} triggerConfig has no cron — skipping job sync", flowId);
            return;
        }

        // beforeCreate/beforeUpdate validates and normalizes; this is defense-in-depth
        // for any caller that bypasses the lifecycle hooks (e.g., direct repository writes
        // during seed-data ingestion).
        String normalized;
        try {
            normalized = CronExpressions.normalize(cron);
        } catch (IllegalArgumentException e) {
            log.warn("SCHEDULED flow {} has invalid cron '{}' — skipping job sync: {}",
                    flowId, cron, e.getMessage());
            return;
        }

        Instant nextRunAt = active ? ScheduledJobRepository.calculateNextRunAt(normalized, timezone) : null;

        Optional<Map<String, Object>> existing = scheduledJobRepository.findByFlowId(flowId, tenantId);
        if (existing.isPresent()) {
            String jobId = asString(existing.get().get("id"));
            if (scheduledJobRepository.updateForFlow(
                    jobId, flowName, normalized, timezone, active, nextRunAt)) {
                return;
            }
            // The row was read a moment ago and is gone now — deleted concurrently, or never
            // visible to this connection for write. Fall through and recreate it rather than
            // leave the flow marked active with no job behind it, which never fires and shows
            // no symptom anywhere the flow itself is displayed.
            log.warn("scheduled_job {} for flow {} vanished between read and update — recreating",
                    jobId, flowId);
        }

        String createdBy = resolveCreatedBy(record, flowId);
        if (createdBy == null) {
            log.warn("Cannot create scheduled_job for flow {} — could not resolve createdBy", flowId);
            return;
        }
        scheduledJobRepository.insertForFlow(
            flowId, tenantId, flowName, normalized, timezone, active, nextRunAt, createdBy);
    }

    private String resolveCreatedBy(Map<String, Object> record, String flowId) {
        String fromRecord = asString(record.get("createdBy"));
        if (fromRecord == null) fromRecord = asString(record.get("created_by"));
        if (fromRecord != null) return fromRecord;
        return scheduledJobRepository.findFlowCreatedBy(flowId).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractTriggerConfig(Map<String, Object> record) {
        Object raw = record.get("triggerConfig");
        if (raw == null) raw = record.get("trigger_config");
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (raw instanceof String s && !s.isBlank()) {
            try {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            } catch (Exception e) {
                log.warn("Failed to parse triggerConfig JSON for flow: {}", e.getMessage());
            }
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
