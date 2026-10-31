package io.kelta.worker.service;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.worker.util.TenantContextUtils;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.runtime.module.integration.spi.ScriptExecutor.ScriptExecutionRequest;
import io.kelta.runtime.module.integration.spi.ScriptExecutor.ScriptExecutionResult;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.module.ExternalModuleRegistry;
import io.kelta.worker.repository.DataExportRepository;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.service.email.DefaultEmailService;
import io.kelta.worker.service.email.EmailAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes scheduled jobs that are due based on their cron expression.
 *
 * <p>Polled by {@link io.kelta.worker.config.SchedulerConfig} on a fixed interval, on every
 * worker pod. Each due job is claimed with {@link ScheduledJobRepository#claim} before it runs —
 * a conditional update that advances {@code next_run_at} — so exactly one pod runs each scheduled
 * slot. The claim is the only write of {@code next_run_at} on this path; recording the outcome
 * afterwards leaves it alone.
 *
 * <p>Supports job types:
 * <ul>
 *   <li><b>FLOW</b> — executes a flow definition via {@link FlowEngine}</li>
 *   <li><b>SCRIPT</b> — executes a JavaScript script via {@link ScriptExecutor}</li>
 *   <li><b>REPORT_EXPORT</b> — executes a report and exports as CSV via {@link ReportExecutionService}</li>
 *   <li><b>DATA_EXPORT</b> — triggers a tenant data export via {@link DataExportService}</li>
 *   <li><b>MODULE_ACTION</b> — runs an external module's scheduled action via
 *       {@link ExternalModuleRegistry#runScheduledAction}</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Service
public class ScheduledJobExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobExecutorService.class);

    private final ScheduledJobRepository repository;
    private final FlowEngine flowEngine;
    private final FlowActorResolver flowActorResolver;
    private final InitialStateBuilder initialStateBuilder;
    private final ObjectMapper objectMapper;
    private final ScriptExecutor scriptExecutor;
    private final ReportExecutionService reportExecutionService;
    private final DataExportService dataExportService;
    private final DataExportRepository dataExportRepository;
    private final TenantSlugResolver tenantSlugResolver;

    /**
     * Nullable: the {@link DefaultEmailService} bean only exists when
     * {@code kelta.email.enabled} is true (the harness runs with email
     * disabled). Absent → report delivery is skipped with a log note; the
     * export itself still runs.
     */
    private final DefaultEmailService emailService;

    /**
     * Runs {@code MODULE_ACTION} jobs. Setter-injected (absent when runtime modules are off), so the
     * constructor stays as it is.
     */
    private ExternalModuleRegistry externalModules;

    /** Reports larger than this are delivered as a notification without the CSV attached. */
    static final int MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

    public ScheduledJobExecutorService(ScheduledJobRepository repository,
                                        FlowEngine flowEngine,
                                        FlowActorResolver flowActorResolver,
                                        InitialStateBuilder initialStateBuilder,
                                        ObjectMapper objectMapper,
                                        ScriptExecutor scriptExecutor,
                                        ReportExecutionService reportExecutionService,
                                        DataExportService dataExportService,
                                        DataExportRepository dataExportRepository,
                                        TenantSlugResolver tenantSlugResolver,
                                        org.springframework.beans.factory.ObjectProvider<DefaultEmailService> emailServiceProvider) {
        this.repository = repository;
        this.flowEngine = flowEngine;
        this.flowActorResolver = flowActorResolver;
        this.initialStateBuilder = initialStateBuilder;
        this.objectMapper = objectMapper;
        this.scriptExecutor = scriptExecutor;
        this.reportExecutionService = reportExecutionService;
        this.dataExportService = dataExportService;
        this.dataExportRepository = dataExportRepository;
        this.tenantSlugResolver = tenantSlugResolver;
        this.emailService = emailServiceProvider.getIfAvailable();
    }

    @Autowired(required = false)
    public void setExternalModuleRegistry(ExternalModuleRegistry externalModules) {
        this.externalModules = externalModules;
    }

    /**
     * Finds and executes all due scheduled jobs.
     * Called on each poll cycle by SchedulerConfig.
     */
    @SuppressWarnings("unchecked")
    public void executeAll() {
        List<Map<String, Object>> dueJobs = repository.findDueJobs();
        if (dueJobs.isEmpty()) {
            return;
        }

        log.debug("Found {} due scheduled jobs", dueJobs.size());

        for (Map<String, Object> job : dueJobs) {
            String jobId = (String) job.get("id");
            String tenantId = (String) job.get("tenant_id");
            String jobType = (String) job.get("job_type");
            String jobReferenceId = (String) job.get("job_reference_id");
            String cronExpression = (String) job.get("cron_expression");
            String timezone = (String) job.get("timezone");
            Instant scheduledFor = toInstant(job.get("next_run_at"));

            // Every worker pod polls this table, and the SELECT above locks nothing once it
            // returns, so another pod may hold the same row in its own list right now. Claim the
            // slot before running it: the UPDATE advances next_run_at only while it still holds
            // the slot this poll read, so exactly one pod wins and the rest skip the job.
            if (!repository.claim(jobId, scheduledFor, safeCalculateNextRun(cronExpression, timezone))) {
                log.debug("Scheduled job {} (slot {}) was claimed by another worker — skipping", jobId, scheduledFor);
                continue;
            }

            Instant startedAt = Instant.now();

            // Scheduler thread has no inherited tenant context, so resolve the
            // slug and bind both ID and slug — every job type that touches
            // tenant-scoped tables (FLOW + REPORT_EXPORT + DATA_EXPORT) needs
            // schema-per-tenant resolution to work.
            String tenantSlug = tenantSlugResolver.resolveSlug(tenantId).orElse(null);
            if (tenantSlug == null) {
                log.warn("Could not resolve slug for tenant {} on scheduled job {} — execution will fall back to public schema",
                        tenantId, jobId);
            }
            try {
                TenantContextUtils.withTenant(tenantId, tenantSlug, () -> {
                    switch (jobType) {
                        case "FLOW" -> executeFlowJob(jobId, tenantId, jobReferenceId, startedAt);
                        case "SCRIPT" -> executeScriptJob(jobId, tenantId, jobReferenceId, startedAt);
                        case "REPORT_EXPORT" -> executeReportExportJob(jobId, tenantId, jobReferenceId, startedAt,
                                (String) job.get("name"), (String) job.get("config"));
                        case "DATA_EXPORT" -> executeDataExportJob(jobId, tenantId, jobReferenceId, startedAt);
                        case "MODULE_ACTION" -> executeModuleActionJob(jobId, tenantId, startedAt,
                                (String) job.get("config"), scheduledFor);
                        default -> {
                            log.warn("Unsupported job type '{}' for job {}, skipping", jobType, jobId);
                            repository.updateAfterExecution(jobId, "SKIPPED", startedAt);
                        }
                    }
                });
            } catch (Exception e) {
                Instant completedAt = Instant.now();
                long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();

                repository.updateAfterExecution(jobId, "FAILED", startedAt);
                repository.insertExecutionLog(jobId, "FAILED", e.getMessage(), startedAt, completedAt, durationMs);

                log.error("Scheduled job failed: jobId={}, type={}, refId={}, tenantId={}, error={}, duration={}ms",
                        jobId, jobType, jobReferenceId, tenantId, e.getMessage(), durationMs);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // MODULE_ACTION execution (an external module's manifest schedule)
    // ---------------------------------------------------------------------------

    /**
     * Runs the action through the module's endpoint ({@code POST /kelta/v1/actions/{key}}) with
     * {@code Idempotency-Key: <jobId>:<fireTime>}, so a module can drop a repeated run of the same
     * slot ({@link ScheduledJobRepository#claim} already lets only one pod start it). A disabled or
     * failed install is {@code SKIPPED}, not failed: nothing is wrong with the schedule. A module
     * that cannot be called throws, and the caller records FAILED.
     */
    @SuppressWarnings("unchecked")
    private void executeModuleActionJob(String jobId, String tenantId, Instant startedAt, String configJson,
                                        Instant scheduledFor) throws Exception {
        Map<String, Object> config = configJson == null ? Map.of() : objectMapper.readValue(configJson, Map.class);
        String moduleId = config.get("moduleId") instanceof String m ? m : null;
        String action = config.get("action") instanceof String a ? a : null;
        if (externalModules == null || moduleId == null || action == null) {
            String error = externalModules == null ? "Runtime modules are disabled on this worker"
                    : "MODULE_ACTION job has no moduleId/action in its config";
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", error, startedAt, Instant.now(),
                    Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }
        if (!externalModules.callable(tenantId, moduleId)) {
            log.info("Skipping scheduled action '{}' of module '{}' for tenant {}: the install is not active",
                    action, moduleId, tenantId);
            repository.updateAfterExecution(jobId, "SKIPPED", startedAt);
            repository.insertExecutionLog(jobId, "SKIPPED", "Module '" + moduleId + "' is not active",
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }
        Instant fireTime = scheduledFor != null ? scheduledFor : startedAt;
        ActionResult result = externalModules.runScheduledAction(tenantId, moduleId, action,
                jobId + ":" + fireTime);

        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
        if (result.successful()) {
            repository.updateAfterExecution(jobId, "SUCCESS", startedAt);
            repository.insertExecutionLog(jobId, "SUCCESS", null, startedAt, completedAt, durationMs);
            log.info("Scheduled job executed: jobId={}, type=MODULE_ACTION, module={}, action={}, tenantId={}, "
                    + "duration={}ms", jobId, moduleId, action, tenantId, durationMs);
        } else {
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", result.errorMessage(), startedAt, completedAt, durationMs);
            log.warn("Scheduled job failed: jobId={}, type=MODULE_ACTION, module={}, action={}, tenantId={}, "
                    + "error={}", jobId, moduleId, action, tenantId, result.errorMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // FLOW execution
    // ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void executeFlowJob(String jobId, String tenantId, String flowReferenceId,
                                 Instant startedAt) throws Exception {
        var flowOpt = repository.findFlowById(flowReferenceId);
        if (flowOpt.isEmpty()) {
            log.error("Flow {} not found for scheduled job {}", flowReferenceId, jobId);
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Flow not found: " + flowReferenceId,
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        Map<String, Object> flow = flowOpt.get();
        boolean flowActive = (Boolean) flow.get("active");
        if (!flowActive) {
            log.warn("Flow {} is inactive for scheduled job {}", flowReferenceId, jobId);
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Flow inactive: " + flowReferenceId,
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        // Build initial state
        String executionId = UUID.randomUUID().toString();
        Object triggerConfigRaw = flow.get("trigger_config");
        Map<String, Object> triggerConfig = null;
        if (triggerConfigRaw instanceof String s) {
            triggerConfig = objectMapper.readValue(s, Map.class);
        } else if (triggerConfigRaw instanceof Map) {
            triggerConfig = (Map<String, Object>) triggerConfigRaw;
        }

        Map<String, Object> initialState = initialStateBuilder.buildFromSchedule(
                triggerConfig, tenantId, flowReferenceId, executionId);

        String definitionJson = flow.get("definition") instanceof String s
                ? s : objectMapper.writeValueAsString(flow.get("definition"));

        // Execute flow (async — returns immediately)
        String flowExecutionId = flowEngine.startExecution(
                tenantId, flowReferenceId, definitionJson, initialState,
                flowActorResolver.resolve(tenantId, flowReferenceId, null), null, false);

        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();

        repository.updateAfterExecution(jobId, "SUCCESS", startedAt);
        repository.insertExecutionLog(jobId, "SUCCESS", null, startedAt, completedAt, durationMs);

        log.info("Scheduled job executed: jobId={}, type=FLOW, flowId={}, tenantId={}, executionId={}, duration={}ms",
                jobId, flowReferenceId, tenantId, flowExecutionId, durationMs);
    }

    // ---------------------------------------------------------------------------
    // SCRIPT execution
    // ---------------------------------------------------------------------------

    private void executeScriptJob(String jobId, String tenantId, String scriptReferenceId,
                                   Instant startedAt) {
        var scriptOpt = repository.findScriptById(scriptReferenceId);
        if (scriptOpt.isEmpty()) {
            log.error("Script {} not found for scheduled job {}", scriptReferenceId, jobId);
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Script not found: " + scriptReferenceId,
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        Map<String, Object> script = scriptOpt.get();
        boolean active = Boolean.TRUE.equals(script.get("active"));
        if (!active) {
            log.warn("Script {} is inactive for scheduled job {}", scriptReferenceId, jobId);
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Script inactive: " + scriptReferenceId,
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        String sourceCode = (String) script.get("source_code");
        if (sourceCode == null || sourceCode.isBlank()) {
            log.error("Script {} has no source code for scheduled job {}", scriptReferenceId, jobId);
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Script has no source code: " + scriptReferenceId,
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        // Build bindings for the script context
        Map<String, Object> bindings = Map.of(
                "context", Map.of(
                        "tenantId", tenantId,
                        "jobId", jobId,
                        "scriptId", scriptReferenceId,
                        "triggerType", "SCHEDULED"
                )
        );

        ScriptExecutionResult result = scriptExecutor.execute(
                new ScriptExecutionRequest(sourceCode, bindings));

        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();

        if (result.success()) {
            repository.updateAfterExecution(jobId, "SUCCESS", startedAt);
            repository.insertExecutionLog(jobId, "SUCCESS", null, startedAt, completedAt, durationMs);
            log.info("Scheduled job executed: jobId={}, type=SCRIPT, scriptId={}, tenantId={}, duration={}ms",
                    jobId, scriptReferenceId, tenantId, durationMs);
        } else {
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", result.errorMessage(), startedAt, completedAt, durationMs);
            log.error("Scheduled job failed: jobId={}, type=SCRIPT, scriptId={}, tenantId={}, error={}, duration={}ms",
                    jobId, scriptReferenceId, tenantId, result.errorMessage(), durationMs);
        }
    }

    // ---------------------------------------------------------------------------
    // REPORT_EXPORT execution
    // ---------------------------------------------------------------------------

    private void executeReportExportJob(String jobId, String tenantId, String reportReferenceId,
                                         Instant startedAt,
                                         String jobName, String configJson) {
        var reportOpt = repository.findReportById(reportReferenceId);
        if (reportOpt.isEmpty()) {
            log.error("Report {} not found for scheduled job {}", reportReferenceId, jobId);
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Report not found: " + reportReferenceId,
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        Map<String, Object> reportConfig = reportOpt.get();

        // Execute report export as CSV into a StringWriter
        StringWriter csvWriter = new StringWriter();
        try {
            reportExecutionService.exportCsv(reportConfig, csvWriter);
        } catch (Exception e) {
            Instant completedAt = Instant.now();
            long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Report export failed: " + e.getMessage(),
                    startedAt, completedAt, durationMs);
            log.error("Scheduled job failed: jobId={}, type=REPORT_EXPORT, reportId={}, tenantId={}, error={}, duration={}ms",
                    jobId, reportReferenceId, tenantId, e.getMessage(), durationMs);
            return;
        }

        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();

        // Count CSV rows (subtract 1 for header line)
        String csvOutput = csvWriter.toString();
        long lineCount = csvOutput.lines().count();
        int recordsProcessed = (int) Math.max(0, lineCount - 1);

        String deliveryNote = deliverReportByEmail(jobId, tenantId, jobName, configJson,
                csvOutput, recordsProcessed);

        repository.updateAfterExecution(jobId, "SUCCESS", startedAt);
        repository.insertExecutionLog(jobId, "SUCCESS", deliveryNote, startedAt, completedAt, durationMs);

        log.info("Scheduled job executed: jobId={}, type=REPORT_EXPORT, reportId={}, tenantId={}, records={}, duration={}ms",
                jobId, reportReferenceId, tenantId, recordsProcessed, durationMs);
    }

    /**
     * Emails the exported CSV to the recipients configured on the job
     * ({@code config.recipients}). No recipients → no-op. Oversized CSVs
     * (&gt; {@link #MAX_ATTACHMENT_BYTES}) send a notification without the
     * attachment. Delivery failures never fail the job — the export itself
     * succeeded — but are noted on the execution log.
     *
     * @return a note for the execution log, or null when nothing was sent
     */
    @SuppressWarnings("unchecked")
    private String deliverReportByEmail(String jobId, String tenantId, String jobName,
                                        String configJson, String csvOutput, int recordCount) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        if (emailService == null) {
            log.warn("Scheduled job {} has delivery config but email is disabled — skipping delivery", jobId);
            return "Delivery skipped: email disabled";
        }
        List<String> recipients;
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
            Object raw = config.get("recipients");
            recipients = raw instanceof List<?> list
                    ? list.stream().filter(Objects::nonNull).map(Object::toString)
                          .filter(s -> !s.isBlank()).toList()
                    : List.of();
        } catch (Exception e) {
            log.warn("Unparsable config for scheduled job {}: {}", jobId, e.getMessage());
            return "Delivery skipped: unparsable job config";
        }
        if (recipients.isEmpty()) {
            return null;
        }

        byte[] csvBytes = csvOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String reportName = jobName != null ? jobName : "Scheduled report";
        String date = java.time.LocalDate.now().toString();
        String subject = reportName + " — " + date;
        String filename = reportName.replaceAll("[^A-Za-z0-9._-]+", "-") + "-" + date + ".csv";

        boolean attach = csvBytes.length <= MAX_ATTACHMENT_BYTES;
        String body = attach
                ? "<p>Your scheduled report <strong>" + escapeHtml(reportName) + "</strong> ran successfully ("
                    + recordCount + " records). The CSV is attached.</p>"
                : "<p>Your scheduled report <strong>" + escapeHtml(reportName) + "</strong> ran successfully ("
                    + recordCount + " records), but the CSV ("
                    + (csvBytes.length / (1024 * 1024)) + " MB) exceeds the attachment limit."
                    + " Run the report export in the app to download it.</p>";
        List<EmailAttachment> attachments = attach
                ? List.of(new EmailAttachment(filename, "text/csv", csvBytes))
                : List.of();

        int sent = 0;
        for (String recipient : recipients) {
            try {
                emailService.queueEmailWithAttachments(tenantId, recipient, subject, body,
                        "SCHEDULED_REPORT", jobId, attachments);
                sent++;
            } catch (Exception e) {
                log.error("Report delivery to {} failed for job {}: {}", recipient, jobId, e.getMessage());
            }
        }
        String note = "Delivered to " + sent + "/" + recipients.size() + " recipient(s)"
                + (attach ? "" : " (attachment skipped: CSV too large)");
        return note;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------------------
    // DATA_EXPORT execution
    // ---------------------------------------------------------------------------

    private void executeDataExportJob(String jobId, String tenantId, String exportConfigJson,
                                       Instant startedAt) {
        // The job_reference_id stores an export configuration as JSON with name, scope, collectionIds, format
        Map<String, Object> config;
        try {
            config = objectMapper.readValue(exportConfigJson, Map.class);
        } catch (Exception e) {
            repository.updateAfterExecution(jobId, "FAILED", startedAt);
            repository.insertExecutionLog(jobId, "FAILED", "Invalid export config: " + e.getMessage(),
                    startedAt, Instant.now(), Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            return;
        }

        String name = (String) config.getOrDefault("name", "Scheduled Export");
        String exportScope = (String) config.getOrDefault("exportScope", "FULL");
        String format = (String) config.getOrDefault("format", "CSV");
        @SuppressWarnings("unchecked")
        List<String> collectionIds = (List<String>) config.get("collectionIds");

        // Create and execute the export
        String exportId = dataExportService.createExport(
                tenantId, name, null, exportScope, collectionIds, format, "system:scheduled-job:" + jobId);
        dataExportService.executeExport(exportId, tenantId);

        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();

        repository.updateAfterExecution(jobId, "SUCCESS", startedAt);
        repository.insertExecutionLog(jobId, "SUCCESS", null, startedAt, completedAt, durationMs);

        log.info("Scheduled job executed: jobId={}, type=DATA_EXPORT, exportId={}, tenantId={}, duration={}ms",
                jobId, exportId, tenantId, durationMs);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** {@code next_run_at} as the JDBC driver returns it ({@link java.sql.Timestamp}) — or null. */
    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof Instant i) return i;
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private Instant safeCalculateNextRun(String cronExpression, String timezone) {
        try {
            return ScheduledJobRepository.calculateNextRunAt(cronExpression, timezone);
        } catch (Exception e) {
            log.warn("Failed to calculate next run time: {}", e.getMessage());
            return null;
        }
    }
}
