package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.service.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Wildcard after-delete hook that cascades attachment cleanup when a record is removed.
 *
 * <p>When any record is deleted, this hook removes the record's
 * {@code file_attachment} rows and deletes their underlying S3 objects so storage
 * is not orphaned. It runs late (order {@code 200}) so it executes after other
 * after-delete side effects.
 *
 * <p>Direct deletes of attachment records are handled by
 * {@code AttachmentUploadController#deleteAttachment}, not this hook: {@code afterDelete}
 * does not provide the deleted row, so the storage key cannot be recovered after the
 * fact. This hook covers the <em>parent</em> record case, where the
 * {@code file_attachment} rows still exist at delete time and can be looked up by
 * {@code record_id}. The {@code attachments} collection is therefore skipped here.
 *
 * @since 1.0.0
 */
@Component
public class AttachmentCleanupHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupHook.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final String WILDCARD = "*";
    private static final String ATTACHMENTS_COLLECTION = "attachments";

    private static final String SELECT_BY_RECORD = """
            SELECT id, storage_key FROM file_attachment
            WHERE record_id = ? AND tenant_id = ?
            """;

    private static final String DELETE_BY_RECORD = """
            DELETE FROM file_attachment WHERE record_id = ? AND tenant_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final S3StorageService storageService;

    public AttachmentCleanupHook(JdbcTemplate jdbcTemplate, S3StorageService storageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
    }

    @Override
    public String getCollectionName() {
        return WILDCARD;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void afterDelete(String collectionName, String id, String tenantId) {
        // Attachment self-deletes are handled by the dedicated controller, which can
        // recover the storage key before the row is gone. Skip them here to avoid a
        // redundant lookup (and the row is already deleted at this point anyway).
        if (ATTACHMENTS_COLLECTION.equals(collectionName) || tenantId == null || id == null) {
            return;
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(SELECT_BY_RECORD, id, tenantId);
        } catch (Exception e) {
            log.warn("Attachment cleanup lookup failed for record {} (tenant {}): {}",
                    id, tenantId, e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }

        if (storageService.isEnabled()) {
            for (Map<String, Object> row : rows) {
                String storageKey = (String) row.get("storage_key");
                if (storageKey != null && !storageKey.isBlank()) {
                    try {
                        storageService.deleteObject(storageKey);
                    } catch (Exception e) {
                        log.warn("Failed to delete S3 object '{}' during record cleanup: {}",
                                storageKey, e.getMessage());
                    }
                }
            }
        }

        try {
            int deleted = jdbcTemplate.update(DELETE_BY_RECORD, id, tenantId);
            if (deleted > 0) {
                securityLog.info(
                        "security_event=ATTACHMENTS_CASCADE_DELETED tenant={} record={} count={}",
                        tenantId, id, deleted);
            }
        } catch (Exception e) {
            log.warn("Attachment cleanup delete failed for record {} (tenant {}): {}",
                    id, tenantId, e.getMessage());
        }
    }
}
