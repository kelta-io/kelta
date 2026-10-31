package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.BulkJobRepository;
import io.kelta.worker.repository.BulkJobRepository.BulkJobResultRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Executes bulk data operations (INSERT, UPDATE, UPSERT, DELETE) against collections.
 *
 * <p>Processes records in configurable batch sizes, tracking per-record results
 * for partial success support. Uses the existing {@link QueryEngine} CRUD infrastructure.
 *
 * <p>The audit fields are the job creator's, whatever the uploaded records carry: a record's
 * {@code createdBy}/{@code updatedBy} is dropped and the creator stamped, as
 * {@code RecordWritePipeline} does for a single write. Without a creator they stay unset.
 *
 * @since 1.0.0
 */
@Service
public class BulkOperationService {

    private static final Logger log = LoggerFactory.getLogger(BulkOperationService.class);

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final CollectionLifecycleManager lifecycleManager;
    private final BulkJobRepository bulkJobRepository;
    private final JdbcTemplate jdbcTemplate;

    public BulkOperationService(QueryEngine queryEngine,
                                 CollectionRegistry collectionRegistry,
                                 CollectionLifecycleManager lifecycleManager,
                                 BulkJobRepository bulkJobRepository,
                                 JdbcTemplate jdbcTemplate) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.lifecycleManager = lifecycleManager;
        this.bulkJobRepository = bulkJobRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Processes a bulk job by executing the operation against the collection.
     *
     * @param jobId           the bulk job ID
     * @param collectionId    the target collection ID
     * @param operation       INSERT, UPDATE, UPSERT, or DELETE
     * @param records         the records to process
     * @param batchSize       number of records per batch
     * @param externalIdField the field used for UPSERT matching (nullable)
     * @param creatorId       the job creator's {@code platform_user} UUID, stamped as the
     *                        records' {@code createdBy}/{@code updatedBy} (nullable)
     */
    @SuppressWarnings("unchecked")
    public void processJob(String jobId, String collectionId, String operation,
                           List<Map<String, Object>> records, int batchSize,
                           String externalIdField, String creatorId) {
        CollectionDefinition definition = resolveCollection(collectionId);
        if (definition == null) {
            bulkJobRepository.markFailed(jobId, "Collection not found or inactive: " + collectionId);
            return;
        }

        bulkJobRepository.markProcessing(jobId);

        int totalRecords = records.size();
        int processed = 0;
        int success = 0;
        int errors = 0;

        for (int batchStart = 0; batchStart < totalRecords; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, totalRecords);
            List<Map<String, Object>> batch = records.subList(batchStart, batchEnd);
            List<BulkJobResultRecord> batchResults = new ArrayList<>();

            for (int i = 0; i < batch.size(); i++) {
                int recordIndex = batchStart + i;
                Map<String, Object> record = batch.get(i);

                try {
                    String recordId = executeOperation(definition, operation, record, externalIdField, creatorId);
                    batchResults.add(new BulkJobResultRecord(recordIndex, recordId, "SUCCESS", null));
                    success++;
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    batchResults.add(new BulkJobResultRecord(recordIndex, null, "FAILURE", errorMsg));
                    errors++;
                    log.debug("Bulk operation record {} failed: {}", recordIndex, errorMsg);
                }

                processed++;
            }

            bulkJobRepository.insertResults(jobId, batchResults);
            bulkJobRepository.updateProgress(jobId, processed, success, errors);

            log.debug("Bulk job {} progress: {}/{} processed, {} success, {} errors",
                    jobId, processed, totalRecords, success, errors);
        }

        bulkJobRepository.markCompleted(jobId, processed, success, errors);
        log.info("Bulk job {} completed: {}/{} success, {} errors",
                jobId, success, totalRecords, errors);
    }

    private String executeOperation(CollectionDefinition definition, String operation,
                                     Map<String, Object> record, String externalIdField, String creatorId) {
        return switch (operation) {
            case "INSERT" -> {
                Map<String, Object> created = queryEngine.create(definition, forCreate(record, creatorId));
                yield (String) created.get("id");
            }
            case "UPDATE" -> {
                String id = extractRecordId(record);
                if (id == null) {
                    throw new IllegalArgumentException("Record 'id' is required for UPDATE operations");
                }
                queryEngine.update(definition, id, forUpdate(record, creatorId))
                        .orElseThrow(() -> new IllegalArgumentException("Record not found: " + id));
                yield id;
            }
            case "UPSERT" -> {
                yield executeUpsert(definition, record, externalIdField, creatorId);
            }
            case "DELETE" -> {
                String id = extractRecordId(record);
                if (id == null) {
                    throw new IllegalArgumentException("Record 'id' is required for DELETE operations");
                }
                boolean deleted = queryEngine.delete(definition, id);
                if (!deleted) {
                    throw new IllegalArgumentException("Record not found: " + id);
                }
                yield id;
            }
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    private String executeUpsert(CollectionDefinition definition, Map<String, Object> record,
                                  String externalIdField, String creatorId) {
        if (externalIdField == null || externalIdField.isBlank()) {
            // Fall back to 'id' field for matching
            String id = extractRecordId(record);
            if (id != null) {
                Optional<Map<String, Object>> existing = queryEngine.getById(definition, id);
                if (existing.isPresent()) {
                    queryEngine.update(definition, id, forUpdate(record, creatorId));
                    return id;
                }
            }
            Map<String, Object> created = queryEngine.create(definition, forCreate(record, creatorId));
            return (String) created.get("id");
        }

        // External ID matching: query for existing record by the external ID field
        Object externalIdValue = record.get(externalIdField);
        if (externalIdValue == null) {
            throw new IllegalArgumentException(
                    "External ID field '" + externalIdField + "' is missing from record");
        }

        // Search for existing record with matching external ID
        Map<String, String> params = new LinkedHashMap<>();
        params.put("filter[" + externalIdField + "]", externalIdValue.toString());
        params.put("page[size]", "1");
        QueryRequest queryRequest = QueryRequest.fromParams(params);
        QueryResult result = queryEngine.executeQuery(definition, queryRequest);

        if (!result.data().isEmpty()) {
            // Update existing
            Map<String, Object> existing = result.data().get(0);
            String existingId = (String) existing.get("id");
            queryEngine.update(definition, existingId, forUpdate(record, creatorId));
            return existingId;
        } else {
            // Insert new
            Map<String, Object> created = queryEngine.create(definition, forCreate(record, creatorId));
            return (String) created.get("id");
        }
    }

    /** An uploaded record as create data: its audit fields replaced by the creator's. */
    private static Map<String, Object> forCreate(Map<String, Object> record, String creatorId) {
        Map<String, Object> data = withoutAuditFields(record);
        if (creatorId != null) {
            data.put("createdBy", creatorId);
            data.put("updatedBy", creatorId);
        }
        return data;
    }

    /** An uploaded record as an update patch: no id, its audit fields replaced by the creator's. */
    private static Map<String, Object> forUpdate(Map<String, Object> record, String creatorId) {
        Map<String, Object> data = withoutAuditFields(record);
        data.remove("id");
        if (creatorId != null) {
            data.put("updatedBy", creatorId);
        }
        return data;
    }

    private static Map<String, Object> withoutAuditFields(Map<String, Object> record) {
        Map<String, Object> data = new LinkedHashMap<>(record);
        data.remove("createdBy");
        data.remove("updatedBy");
        return data;
    }

    private String extractRecordId(Map<String, Object> record) {
        Object id = record.get("id");
        return id != null ? id.toString() : null;
    }

    private CollectionDefinition resolveCollection(String collectionId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name FROM collection WHERE id = ? AND active = true LIMIT 1",
                    collectionId);
            if (rows.isEmpty()) {
                return null;
            }
            String collectionName = (String) rows.get(0).get("name");
            CollectionDefinition definition = collectionRegistry.get(collectionName);
            if (definition == null) {
                definition = lifecycleManager.loadCollectionByName(collectionName, null);
            }
            return definition;
        } catch (Exception e) {
            log.error("Failed to resolve collection: {}", collectionId, e);
            return null;
        }
    }
}
