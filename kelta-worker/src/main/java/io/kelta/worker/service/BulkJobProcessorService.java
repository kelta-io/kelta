package io.kelta.worker.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.ActingUser;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BulkJobRepository;
import io.kelta.worker.util.TenantContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Polls for queued bulk jobs and processes them.
 *
 * <p>Polled by {@link io.kelta.worker.config.BulkJobProcessorConfig} on a fixed interval.
 * Uses {@code SELECT FOR UPDATE SKIP LOCKED} via {@link BulkJobRepository}
 * for leader election — only one worker instance processes each job.
 *
 * <p>A job's writes are its creator's: the poller has no request caller, so it resolves
 * {@code bulk_job.created_by} to a {@code platform_user} UUID and runs the job as that
 * {@link ActingUser} — the user its record-changed events name and the flows they trigger start
 * as. A creator that no longer resolves runs the job as nobody (the platform's {@code "system"}).
 *
 * @since 1.0.0
 */
@Service
public class BulkJobProcessorService {

    private static final Logger log = LoggerFactory.getLogger(BulkJobProcessorService.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final BulkJobRepository bulkJobRepository;
    private final BulkOperationService bulkOperationService;
    private final ObjectMapper objectMapper;
    private final UserIdResolver userIdResolver;

    public BulkJobProcessorService(BulkJobRepository bulkJobRepository,
                                    BulkOperationService bulkOperationService,
                                    ObjectMapper objectMapper,
                                    UserIdResolver userIdResolver) {
        this.bulkJobRepository = bulkJobRepository;
        this.bulkOperationService = bulkOperationService;
        this.objectMapper = objectMapper;
        this.userIdResolver = userIdResolver;
    }

    /**
     * Finds and executes all queued bulk jobs.
     * Called on each poll cycle by BulkJobProcessorConfig.
     */
    @SuppressWarnings("unchecked")
    public void processQueuedJobs() {
        List<Map<String, Object>> queuedJobs = bulkJobRepository.findQueuedJobs(10);
        if (queuedJobs.isEmpty()) {
            return;
        }

        log.debug("Found {} queued bulk jobs", queuedJobs.size());

        for (Map<String, Object> job : queuedJobs) {
            String jobId = (String) job.get("id");
            String tenantId = (String) job.get("tenant_id");
            String collectionId = (String) job.get("collection_id");
            String operation = (String) job.get("operation");
            int batchSize = ((Number) job.get("batch_size")).intValue();
            String externalIdField = (String) job.get("external_id_field");
            String createdBy = (String) job.get("created_by");

            try {
                TenantContextUtils.withTenant(tenantId, () -> {
                    List<Map<String, Object>> records = parseDataPayload(job);
                    if (records == null || records.isEmpty()) {
                        bulkJobRepository.markFailed(jobId, "No records found in data payload");
                        return;
                    }
                    String creatorId = resolveCreator(createdBy, tenantId);
                    ActingUser.runAs(creatorId, () -> bulkOperationService.processJob(jobId, collectionId,
                            operation, records, batchSize, externalIdField, creatorId));
                });
            } catch (Exception e) {
                log.error("Bulk job {} failed: {}", jobId, e.getMessage(), e);
                bulkJobRepository.markFailed(jobId, e.getMessage());
            }
        }
    }

    /**
     * The job creator's {@code platform_user} UUID — {@code created_by} holds the email the queueing
     * request carried — or null when it does not resolve (the resolver hands a miss back raw).
     */
    private String resolveCreator(String createdBy, String tenantId) {
        if (createdBy == null || createdBy.isBlank()) {
            return null;
        }
        String resolved = userIdResolver.resolve(createdBy, tenantId);
        return resolved != null && UUID_PATTERN.matcher(resolved).matches() ? resolved : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDataPayload(Map<String, Object> job) throws Exception {
        Object dataPayload = job.get("data_payload");
        if (dataPayload == null) {
            return null;
        }

        if (dataPayload instanceof String jsonStr) {
            return objectMapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
        } else if (dataPayload instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        } else {
            // PostgreSQL JSONB may come as a PGobject — convert via string
            String json = dataPayload.toString();
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        }
    }
}
