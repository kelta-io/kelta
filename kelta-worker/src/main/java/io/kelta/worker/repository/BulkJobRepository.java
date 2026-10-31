package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for bulk job persistence and result tracking.
 *
 * <p>Uses {@code SELECT FOR UPDATE SKIP LOCKED} for leader election in
 * multi-worker deployments — only one worker picks up each queued job.
 *
 * @since 1.0.0
 */
@Repository
public class BulkJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public BulkJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates a new bulk job record.
     */
    public String create(String tenantId, String collectionId, String operation,
                         String contentType, int batchSize, String externalIdField,
                         String createdBy, String dataPayload, String fileStorageKey,
                         int totalRecords) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO bulk_job (id, tenant_id, collection_id, operation, status,
                    total_records, content_type, batch_size, external_id_field,
                    created_by, data_payload, file_storage_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW())
                """,
                id, tenantId, collectionId, operation,
                totalRecords, contentType, batchSize, externalIdField,
                createdBy, dataPayload, fileStorageKey);
        return id;
    }

    /**
     * Finds a bulk job by ID and tenant (tenant-scoped access).
     */
    public Optional<Map<String, Object>> findByIdAndTenant(String jobId, String tenantId) {
        var results = jdbcTemplate.queryForList(
                "SELECT * FROM bulk_job WHERE id = ? AND tenant_id = ?",
                jobId, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Lists bulk jobs for a tenant, ordered by most recent first.
     */
    public List<Map<String, Object>> findByTenant(String tenantId, int limit, int offset) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM bulk_job WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                tenantId, limit, offset);
    }

    /**
     * Finds queued jobs that are ready for processing.
     * Uses SKIP LOCKED for leader election — concurrent workers won't pick up the same job.
     */
    public List<Map<String, Object>> findQueuedJobs(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id, tenant_id, collection_id, operation, batch_size,
                       external_id_field, content_type, data_payload, file_storage_key,
                       total_records, created_by
                FROM bulk_job
                WHERE status = 'QUEUED'
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, limit);
    }

    /**
     * Transitions a job to PROCESSING status.
     */
    public int markProcessing(String jobId) {
        return jdbcTemplate.update(
                "UPDATE bulk_job SET status = 'PROCESSING', started_at = NOW(), updated_at = NOW() WHERE id = ?",
                jobId);
    }

    /**
     * Updates progress counters during processing.
     */
    public void updateProgress(String jobId, int processedRecords, int successRecords, int errorRecords) {
        jdbcTemplate.update("""
                UPDATE bulk_job
                SET processed_records = ?, success_records = ?, error_records = ?, updated_at = NOW()
                WHERE id = ?
                """,
                processedRecords, successRecords, errorRecords, jobId);
    }

    /**
     * Marks a job as completed.
     */
    public void markCompleted(String jobId, int processedRecords, int successRecords, int errorRecords) {
        jdbcTemplate.update("""
                UPDATE bulk_job
                SET status = 'COMPLETED', processed_records = ?, success_records = ?,
                    error_records = ?, completed_at = NOW(), updated_at = NOW()
                WHERE id = ?
                """,
                processedRecords, successRecords, errorRecords, jobId);
    }

    /**
     * Marks a job as failed with an error message.
     */
    public void markFailed(String jobId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE bulk_job
                SET status = 'FAILED', error_message = ?, completed_at = NOW(), updated_at = NOW()
                WHERE id = ?
                """,
                errorMessage, jobId);
    }

    /**
     * Aborts a queued or processing job.
     */
    public int abort(String jobId, String tenantId) {
        return jdbcTemplate.update("""
                UPDATE bulk_job
                SET status = 'ABORTED', completed_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND status IN ('QUEUED', 'PROCESSING')
                """,
                jobId, tenantId);
    }

    /**
     * Inserts a batch of result records.
     */
    public void insertResults(String bulkJobId, List<BulkJobResultRecord> results) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO bulk_job_result (id, bulk_job_id, record_index, record_id, status, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                """,
                results.stream().map(r -> new Object[]{
                        UUID.randomUUID().toString(), bulkJobId,
                        r.recordIndex(), r.recordId(), r.status(), r.errorMessage()
                }).toList());
    }

    /**
     * Gets result records for a bulk job with pagination.
     */
    public List<Map<String, Object>> findResults(String bulkJobId, String tenantId,
                                                  int limit, int offset, String statusFilter) {
        if (statusFilter != null) {
            return jdbcTemplate.queryForList("""
                    SELECT r.* FROM bulk_job_result r
                    JOIN bulk_job j ON j.id = r.bulk_job_id
                    WHERE r.bulk_job_id = ? AND j.tenant_id = ? AND r.status = ?
                    ORDER BY r.record_index ASC LIMIT ? OFFSET ?
                    """,
                    bulkJobId, tenantId, statusFilter, limit, offset);
        }
        return jdbcTemplate.queryForList("""
                SELECT r.* FROM bulk_job_result r
                JOIN bulk_job j ON j.id = r.bulk_job_id
                WHERE r.bulk_job_id = ? AND j.tenant_id = ?
                ORDER BY r.record_index ASC LIMIT ? OFFSET ?
                """,
                bulkJobId, tenantId, limit, offset);
    }

    /**
     * Counts total results for a bulk job, optionally filtered by status.
     */
    public int countResults(String bulkJobId, String tenantId, String statusFilter) {
        String sql;
        Object[] params;
        if (statusFilter != null) {
            sql = """
                SELECT COUNT(*) FROM bulk_job_result r
                JOIN bulk_job j ON j.id = r.bulk_job_id
                WHERE r.bulk_job_id = ? AND j.tenant_id = ? AND r.status = ?
                """;
            params = new Object[]{bulkJobId, tenantId, statusFilter};
        } else {
            sql = """
                SELECT COUNT(*) FROM bulk_job_result r
                JOIN bulk_job j ON j.id = r.bulk_job_id
                WHERE r.bulk_job_id = ? AND j.tenant_id = ?
                """;
            params = new Object[]{bulkJobId, tenantId};
        }
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params);
        return count != null ? count : 0;
    }

    /**
     * Record for batch-inserting result rows.
     */
    public record BulkJobResultRecord(int recordIndex, String recordId, String status, String errorMessage) {}
}
