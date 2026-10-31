package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.repository.ScheduledJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps an external module's manifest {@code schedules[]} as {@code scheduled_job} rows of type
 * {@code MODULE_ACTION} ({@code specs/module-platform/9-external-modules.md} §3.6), which the
 * ordinary job poller ({@code ScheduledJobExecutorService}) runs.
 *
 * <p>One row per schedule: {@code job_reference_id} is the install ({@code tenant_module.id} —
 * the column is 36 characters, too short for {@code <moduleId>:<action>}), {@code name} is
 * {@code module:<moduleId>:<action>@<cron>} (unique per tenant, as the table requires), and
 * {@code config} is {@code {"moduleId", "action"}}. Crons run in UTC: a manifest names no zone and
 * a tenant has none of its own.
 *
 * <p>Synced on install, upgrade and a retried install: a schedule that is new gets a row, one the
 * manifest dropped loses its row and history (a changed cron is both); all are deleted with their
 * history on uninstall. A disabled install
 * keeps its rows — the poller skips them while the install is not callable.
 */
public class ModuleScheduleSync {

    private static final Logger log = LoggerFactory.getLogger(ModuleScheduleSync.class);

    static final int MAX_NAME_LENGTH = 200;

    private final ScheduledJobRepository repository;
    private final ObjectMapper objectMapper;

    public ModuleScheduleSync(ScheduledJobRepository repository, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** Makes the install's {@code MODULE_ACTION} rows match the manifest. */
    public void sync(TenantModuleData row, ExternalModuleManifest manifest) {
        Map<String, Map<String, Object>> existing = new HashMap<>();
        for (Map<String, Object> job : repository.findModuleSchedules(row.id(), row.tenantId())) {
            existing.put(String.valueOf(job.get("name")), job);
        }
        for (ExternalModuleManifest.Schedule schedule : manifest.schedules()) {
            String name = name(manifest.id(), schedule);
            String config = config(manifest.id(), schedule.action());
            if (existing.remove(name) == null) {
                repository.insertModuleSchedule(row.tenantId(), row.id(), name, schedule.cron(), config,
                        ScheduledJobRepository.calculateNextRunAt(schedule.cron(), "UTC"));
                log.info("Scheduled action '{}' of module '{}' for tenant {} on '{}' (UTC)", schedule.action(),
                        manifest.id(), row.tenantId(), schedule.cron());
            }
        }
        if (!existing.isEmpty()) {
            int deleted = repository.deleteModuleSchedules(row.id(), row.tenantId(),
                    existing.values().stream().map(job -> String.valueOf(job.get("id"))).toList());
            log.info("Removed {} schedule(s) module '{}' no longer declares for tenant {}", deleted, manifest.id(),
                    row.tenantId());
        }
    }

    /** Deletes every {@code MODULE_ACTION} row of the install, with its history (uninstall). */
    public void delete(TenantModuleData row) {
        int deleted = repository.deleteModuleSchedules(row.id(), row.tenantId(), null);
        if (deleted > 0) {
            log.info("Deleted {} schedule(s) of module '{}' for tenant {}", deleted, row.moduleId(), row.tenantId());
        }
    }

    /**
     * The row name: the schedule's identity within the install. The cron is part of it because a
     * manifest may run one action on two schedules; it is truncated to fit the column.
     */
    static String name(String moduleId, ExternalModuleManifest.Schedule schedule) {
        String name = "module:" + moduleId + ":" + schedule.action() + "@" + schedule.cron();
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private String config(String moduleId, String action) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("moduleId", moduleId);
        config.put("action", action);
        return objectMapper.writeValueAsString(config);
    }
}
