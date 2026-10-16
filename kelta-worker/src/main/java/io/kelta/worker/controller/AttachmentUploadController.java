package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.service.S3StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Handles file attachment uploads via presigned PUT URLs.
 *
 * <p>Flow:
 * <ol>
 *   <li>Client calls {@code POST /api/attachments/upload-url} with file metadata</li>
 *   <li>Server validates metadata, checks storage limits, creates an attachment record,
 *       and returns a presigned PUT URL</li>
 *   <li>Client uploads the file directly to S3 using the presigned URL</li>
 *   <li>Client calls {@code POST /api/attachments/{id}/finalize} to mark the upload complete</li>
 * </ol>
 *
 * <p>Both steps require the caller to be able to <b>edit the parent record</b> the attachment
 * hangs off ({@code collectionId}/{@code recordId}). The gateway only checks the caller's
 * permission on the {@code attachments} system collection, and this controller writes
 * {@code file_attachment} with raw JDBC, so no record-level rule ever saw the parent before.
 * A parent that does not exist or that the caller cannot read answers 404 — never 403, which
 * would confirm it exists; a readable parent the caller cannot edit answers 403.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentUploadController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentUploadController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final long BYTES_PER_GB = 1_073_741_824L;

    private static final Set<String> BLOCKED_CONTENT_TYPES = Set.of(
            "application/x-executable",
            "application/x-sharedlib",
            "application/x-msdos-program"
    );

    // file_attachment.id is VARCHAR(36) (see V42 migration), so do NOT cast to ::uuid —
    // PostgreSQL has no implicit varchar=uuid operator and the comparison fails with
    // SQLState 42883 (BadSqlGrammarException). Pass UUID strings as plain text.
    private static final String INSERT_ATTACHMENT = """
            INSERT INTO file_attachment (id, tenant_id, collection_id, record_id,
                file_name, file_size, content_type, storage_key, uploaded_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private static final String UPDATE_ATTACHMENT_STORAGE_KEY = """
            UPDATE file_attachment SET storage_key = ?, uploaded_at = NOW(), updated_at = NOW()
            WHERE id = ? AND tenant_id = ?
            """;

    private static final String SELECT_ATTACHMENT = """
            SELECT id, tenant_id, collection_id, record_id, file_name, file_size,
                   content_type, storage_key, uploaded_by, uploaded_at, created_at
            FROM file_attachment WHERE id = ? AND tenant_id = ?
            """;

    private static final String SELECT_COLLECTION_NAME = """
            SELECT name FROM collection WHERE id = ? AND tenant_id = ?
            """;

    private static final String DELETE_ATTACHMENT = """
            DELETE FROM file_attachment WHERE id = ? AND tenant_id = ?
            """;

    private final S3StorageService storageService;
    private final GovernorLimitsRepository governorLimitsRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;
    private final CerbosRecordAuthorizationAdvice recordAuthorization;

    public AttachmentUploadController(S3StorageService storageService,
                                       GovernorLimitsRepository governorLimitsRepository,
                                       JdbcTemplate jdbcTemplate,
                                       CollectionRegistry collectionRegistry,
                                       QueryEngine queryEngine,
                                       CerbosRecordAuthorizationAdvice recordAuthorization) {
        this.storageService = storageService;
        this.governorLimitsRepository = governorLimitsRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
        this.recordAuthorization = recordAuthorization;
    }

    /**
     * Refuses an attachment write unless the caller may edit the parent record.
     *
     * @return the refusal (404 for a parent that is missing or unreadable, 403 for one the caller
     *         can read but not edit), or {@code null} when the write may proceed
     */
    private ResponseEntity<Map<String, Object>> requireEditableParent(HttpServletRequest request,
                                                                      String tenantId, String userEmail,
                                                                      String collectionId, String recordId) {
        ResponseEntity<Map<String, Object>> notFound = ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                JsonApiResponseBuilder.error("404", "Record not found",
                        "No record " + recordId + " in collection " + collectionId));

        List<String> names = jdbcTemplate.queryForList(SELECT_COLLECTION_NAME, String.class, collectionId, tenantId);
        CollectionDefinition definition = names.isEmpty() ? null : collectionRegistry.get(names.get(0));
        if (definition == null) {
            return notFound;
        }
        Optional<Map<String, Object>> parent = queryEngine.getById(definition, recordId);
        if (parent.isEmpty() || !recordAuthorization.isRecordReadable(request, definition.name(), parent.get())) {
            return notFound;
        }
        if (!recordAuthorization.isRecordEditable(request, definition.name(), parent.get())) {
            securityLog.info("security_event=ATTACHMENT_WRITE_DENIED user={} tenant={} collection={} record={}",
                    userEmail, tenantId, definition.name(), recordId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "Forbidden",
                            "Editing the record is required to attach files to it"));
        }
        return null;
    }

    /**
     * Requests a presigned PUT URL for uploading a file attachment.
     *
     * <p>Validates file metadata, checks storage governor limits, creates
     * a pending attachment record, and returns the presigned URL.
     *
     * @param tenantId     the tenant ID from gateway header
     * @param userEmail    the authenticated user's email
     * @param body         request body with file metadata
     * @return presigned URL and attachment metadata
     */
    @PostMapping("/upload-url")
    public ResponseEntity<Map<String, Object>> requestUploadUrl(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        if (!storageService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    JsonApiResponseBuilder.error("503", "Storage unavailable",
                            "S3 storage is not enabled on this server"));
        }

        String fileName = getStringRequired(body, "fileName");
        String contentType = getStringRequired(body, "contentType");
        Long fileSize = getLongRequired(body, "fileSize");
        String collectionId = getStringRequired(body, "collectionId");
        String recordId = getStringRequired(body, "recordId");

        if (fileName == null || contentType == null || fileSize == null
                || collectionId == null || recordId == null) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing required fields",
                            "Required: fileName, contentType, fileSize, collectionId, recordId"));
        }

        ResponseEntity<Map<String, Object>> denied =
                requireEditableParent(request, tenantId, userEmail, collectionId, recordId);
        if (denied != null) {
            return denied;
        }

        // Validate content type
        if (BLOCKED_CONTENT_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Blocked content type",
                            "Content type '" + contentType + "' is not allowed"));
        }

        // Validate file size
        long maxFileSize = storageService.getMaxFileSize();
        if (fileSize <= 0 || fileSize > maxFileSize) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid file size",
                            "File size must be between 1 and " + maxFileSize + " bytes"));
        }

        // Check storage governor limit
        long currentStorageBytes = governorLimitsRepository.sumStorageBytes(tenantId);
        long storageGbLimit = 10; // default
        try {
            Optional<Object> limitsOpt = governorLimitsRepository.findTenantLimits(tenantId);
            if (limitsOpt.isPresent() && limitsOpt.get() instanceof Map<?,?> limitsMap) {
                Object storageGbObj = limitsMap.get("storageGb");
                if (storageGbObj instanceof Number num) {
                    storageGbLimit = num.longValue();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read storage limits for tenant {}: {}", tenantId, e.getMessage());
        }

        long storageLimitBytes = storageGbLimit * BYTES_PER_GB;
        if (currentStorageBytes + fileSize > storageLimitBytes) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "Storage limit exceeded",
                            "Upload would exceed the " + storageGbLimit + " GB storage limit"));
        }

        // Generate storage key: tenantId/collectionId/recordId/attachmentId/fileName
        String attachmentId = UUID.randomUUID().toString();
        String sanitizedFileName = sanitizeFileName(fileName);
        String storageKey = tenantId + "/" + collectionId + "/" + recordId
                + "/" + attachmentId + "/" + sanitizedFileName;

        // Create pending attachment record (storageKey empty until finalized)
        try {
            jdbcTemplate.update(INSERT_ATTACHMENT,
                    attachmentId, tenantId, collectionId, recordId,
                    fileName, fileSize, contentType, "", userEmail);
        } catch (Exception e) {
            log.error("Failed to create attachment record for tenant {}: {}", tenantId, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Failed to create attachment record", e.getMessage()));
        }

        // Generate presigned PUT URL
        Duration expiry = storageService.getDefaultExpiry();
        String uploadUrl = storageService.getPresignedUploadUrl(storageKey, contentType, expiry);

        securityLog.info("security_event=UPLOAD_URL_GENERATED user={} tenant={} attachment={} file={}",
                userEmail, tenantId, attachmentId, sanitizedFileName);

        // Build response
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("attachmentId", attachmentId);
        attributes.put("uploadUrl", uploadUrl);
        attributes.put("storageKey", storageKey);
        attributes.put("expiresInSeconds", expiry.toSeconds());
        attributes.put("method", "PUT");
        attributes.put("headers", Map.of("Content-Type", contentType));

        return ResponseEntity.ok(JsonApiResponseBuilder.single("upload-urls", attachmentId, attributes));
    }

    /**
     * Finalizes an attachment upload by recording the storage key.
     *
     * <p>Called after the client has successfully uploaded the file to S3
     * using the presigned URL. Updates the attachment record with the
     * storage key and upload timestamp.
     *
     * @param id        the attachment ID
     * @param tenantId  the tenant ID from gateway header
     * @param userEmail the authenticated user's email
     * @return the finalized attachment record
     */
    @PostMapping("/{id}/finalize")
    public ResponseEntity<Map<String, Object>> finalizeUpload(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            HttpServletRequest request) {

        if (!storageService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    JsonApiResponseBuilder.error("503", "Storage unavailable",
                            "S3 storage is not enabled on this server"));
        }

        // Look up the attachment
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_ATTACHMENT, id, tenantId);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> attachment = rows.get(0);
        ResponseEntity<Map<String, Object>> denied = requireEditableParent(request, tenantId, userEmail,
                (String) attachment.get("collection_id"), (String) attachment.get("record_id"));
        if (denied != null) {
            return denied;
        }

        String existingKey = (String) attachment.get("storage_key");

        if (existingKey != null && !existingKey.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Already finalized",
                            "This attachment has already been finalized"));
        }

        // Reconstruct the storage key
        String collectionId = (String) attachment.get("collection_id");
        String recordId = (String) attachment.get("record_id");
        String fileName = (String) attachment.get("file_name");
        String storageKey = tenantId + "/" + collectionId + "/" + recordId
                + "/" + id + "/" + sanitizeFileName(fileName);

        // Verify the object exists in S3
        if (!storageService.objectExists(storageKey)) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Upload not found",
                            "File has not been uploaded to storage yet"));
        }

        // Update the attachment record
        int updated = jdbcTemplate.update(UPDATE_ATTACHMENT_STORAGE_KEY, storageKey, id, tenantId);
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        securityLog.info("security_event=UPLOAD_FINALIZED user={} tenant={} attachment={} storageKey={}",
                userEmail, tenantId, id, storageKey);

        // Return the finalized attachment
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("fileName", attachment.get("file_name"));
        attributes.put("fileSize", attachment.get("file_size"));
        attributes.put("contentType", attachment.get("content_type"));
        attributes.put("storageKey", storageKey);
        attributes.put("uploadedBy", attachment.get("uploaded_by"));
        attributes.put("uploadedAt", OffsetDateTime.now().toString());

        return ResponseEntity.ok(JsonApiResponseBuilder.single("attachments", id, attributes));
    }

    /**
     * Deletes an attachment: removes the underlying S3 object (if any) and the
     * attachment metadata record.
     *
     * <p>This handler intentionally overrides the generic
     * {@code DELETE /api/{collection}/{id}} route in {@code DynamicCollectionRouter}
     * for the {@code attachments} collection. A literal path segment
     * ({@code /api/attachments/{id}}) is more specific than the router's
     * {@code /api/{collectionName}/{id}}, so Spring dispatches here. The generic
     * router delete would drop the database row but orphan the S3 object; this
     * handler cleans up storage too.
     *
     * <p>S3 deletion is best-effort: a storage failure is logged but never blocks
     * the metadata delete (the row is the source of truth for what the user sees).
     *
     * @param id        the attachment ID
     * @param tenantId  the tenant ID from gateway header
     * @param userEmail the authenticated user's email
     * @return 204 No Content on success, 404 if the attachment does not exist,
     *         403 if the caller did not upload it
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail) {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_ATTACHMENT, id, tenantId);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // This controller bypasses the generic route entirely (raw JDBC, no
        // BeforeSaveHook path), so ownership has to be enforced here directly
        // rather than via a guard hook -- there is no hook invocation to guard.
        // uploaded_by is stamped server-side from the resolved caller identity
        // at upload-url time (never client-supplied), so it is a trustworthy
        // owner field to compare against.
        String uploadedBy = (String) rows.get(0).get("uploaded_by");
        if (uploadedBy != null && !uploadedBy.equals(userEmail)) {
            securityLog.info("security_event=ATTACHMENT_DELETE_DENIED user={} tenant={} attachment={} owner={}",
                    userEmail, tenantId, id, uploadedBy);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String storageKey = (String) rows.get(0).get("storage_key");

        if (storageService.isEnabled() && storageKey != null && !storageKey.isBlank()) {
            try {
                storageService.deleteObject(storageKey);
            } catch (Exception e) {
                log.warn("Failed to delete S3 object '{}' for attachment {}: {}",
                        storageKey, id, e.getMessage());
            }
        }

        jdbcTemplate.update(DELETE_ATTACHMENT, id, tenantId);

        securityLog.info("security_event=ATTACHMENT_DELETED user={} tenant={} attachment={} storageKey={}",
                userEmail, tenantId, id, storageKey);

        return ResponseEntity.noContent().build();
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "upload";
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String getStringRequired(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    private static Long getLongRequired(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        }
        return null;
    }
}
