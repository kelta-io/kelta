package io.kelta.worker.controller;

import tools.jackson.databind.ObjectMapper;
import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.BulkJobRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CsvImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * REST controller for managing bulk data operations.
 *
 * <p>Provides endpoints to create bulk jobs (INSERT, UPDATE, UPSERT, DELETE),
 * check job status, list jobs, retrieve per-record results, and abort jobs.
 *
 * <p>Bulk jobs are queued and processed asynchronously by
 * {@link io.kelta.worker.service.BulkJobProcessorService}.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/bulk-jobs")
public class BulkOperationsController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(BulkOperationsController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final int MAX_INLINE_RECORDS = 10_000;
    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final Set<String> VALID_OPERATIONS = Set.of("INSERT", "UPDATE", "UPSERT", "DELETE");

    /** System permission required to create or abort bulk jobs (concerns.md follow-up). */
    static final String WRITE_PERMISSION = "MANAGE_DATA";

    private final BulkJobRepository bulkJobRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CsvImportService csvImportService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public BulkOperationsController(BulkJobRepository bulkJobRepository,
                                     ObjectMapper objectMapper,
                                     JdbcTemplate jdbcTemplate,
                                     CsvImportService csvImportService,
                                     CerbosPermissionResolver permissionResolver,
                                     BootstrapRepository bootstrapRepository) {
        this.bulkJobRepository = bulkJobRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.csvImportService = csvImportService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    /**
     * Bulk writes bypass per-record Cerbos advice (power-user path), so every
     * endpoint is gated in-controller on {@code MANAGE_DATA} — fail-closed when
     * identity is missing. Reads too: {@code /api/bulk-jobs/**} is a static
     * gateway route with only the blanket {@code API_ACCESS} check, and a job's
     * results (and their CSV download, which no response advice sees) list record
     * ids and per-row error messages for collections the caller may not read.
     *
     * @return an error response when denied, or null when allowed
     */
    private ResponseEntity<Map<String, Object>> requireManageData(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "Forbidden", "No identity"));
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> WRITE_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "Forbidden",
                            WRITE_PERMISSION + " permission required for bulk operations"));
        }
        return null;
    }

    /**
     * Creates a new bulk job with inline data payload.
     *
     * <p>The job is queued for asynchronous processing. Poll the job status
     * endpoint to track progress.
     */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> createJob(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {

        ResponseEntity<Map<String, Object>> denied = requireManageData(httpRequest);
        if (denied != null) {
            return denied;
        }

        String collectionId = (String) body.get("collectionId");
        String operation = (String) body.get("operation");
        Object recordsObj = body.get("records");
        String externalIdField = (String) body.get("externalIdField");
        Integer batchSize = body.get("batchSize") instanceof Number n ? n.intValue() : null;

        // Validate required fields
        if (collectionId == null || collectionId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing field", "collectionId is required"));
        }
        if (operation == null || !VALID_OPERATIONS.contains(operation.toUpperCase())) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid operation",
                            "operation must be one of: INSERT, UPDATE, UPSERT, DELETE"));
        }
        if (recordsObj == null || !(recordsObj instanceof List<?> recordsList) || recordsList.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing records",
                            "records must be a non-empty array"));
        }

        operation = operation.toUpperCase();
        List<Map<String, Object>> records = (List<Map<String, Object>>) recordsObj;

        if (records.size() > MAX_INLINE_RECORDS) {
            return ResponseEntity.unprocessableEntity().body(
                    JsonApiResponseBuilder.error("422", "Too many records",
                            "Maximum " + MAX_INLINE_RECORDS + " records per request"));
        }

        // Validate UPSERT external ID field
        if ("UPSERT".equals(operation) && (externalIdField == null || externalIdField.isBlank())) {
            // Allow upsert by 'id' when no external ID field is provided
            externalIdField = null;
        }

        // Validate collection exists
        if (!collectionExists(collectionId, tenantId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    JsonApiResponseBuilder.error("404", "Collection not found",
                            "Collection " + collectionId + " not found or inactive"));
        }

        return queueJob(tenantId, userEmail, collectionId, operation, externalIdField, batchSize, records);
    }

    /**
     * Creates a bulk job from an uploaded CSV or JSON file (for payloads larger than a
     * practical inline request). The file is parsed to records here; each record is then
     * processed asynchronously through the normal write path (which coerces + validates
     * per field). CSV values are strings — use a JSON file for pre-typed values.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> uploadJob(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestParam("file") MultipartFile file,
            @RequestParam("collectionId") String collectionId,
            @RequestParam("operation") String operation,
            @RequestParam(value = "externalIdField", required = false) String externalIdField,
            @RequestParam(value = "batchSize", required = false) Integer batchSize,
            HttpServletRequest httpRequest) {

        ResponseEntity<Map<String, Object>> denied = requireManageData(httpRequest);
        if (denied != null) {
            return denied;
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing file", "file is required and must be non-empty"));
        }
        if (collectionId == null || collectionId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing field", "collectionId is required"));
        }
        if (operation == null || !VALID_OPERATIONS.contains(operation.toUpperCase())) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid operation",
                            "operation must be one of: INSERT, UPDATE, UPSERT, DELETE"));
        }
        operation = operation.toUpperCase();

        List<Map<String, Object>> records;
        try {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
            String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
            boolean json = name.endsWith(".json") || contentType.contains("json");
            if (json) {
                records = objectMapper.readValue(file.getInputStream(),
                        new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } else {
                records = csvImportService.parseCsvToRecords(file.getInputStream());
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Parse error",
                            "Failed to parse uploaded file: " + e.getMessage()));
        }

        if (records == null || records.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Empty file", "The uploaded file contained no records"));
        }
        if (records.size() > MAX_INLINE_RECORDS) {
            return ResponseEntity.unprocessableEntity().body(
                    JsonApiResponseBuilder.error("422", "Too many records",
                            "Maximum " + MAX_INLINE_RECORDS + " records per upload"));
        }
        if ("UPSERT".equals(operation) && (externalIdField == null || externalIdField.isBlank())) {
            externalIdField = null;
        }
        if (!collectionExists(collectionId, tenantId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    JsonApiResponseBuilder.error("404", "Collection not found",
                            "Collection " + collectionId + " not found or inactive"));
        }

        return queueJob(tenantId, userEmail, collectionId, operation, externalIdField, batchSize, records);
    }

    /**
     * Serializes the records, creates a QUEUED bulk job, and returns the JSON:API resource.
     * Shared by the inline-JSON ({@code POST}) and file-upload ({@code POST /upload}) paths.
     */
    private ResponseEntity<Map<String, Object>> queueJob(
            String tenantId, String userEmail, String collectionId, String operation,
            String externalIdField, Integer batchSize, List<Map<String, Object>> records) {

        int effectiveBatchSize = batchSize != null
                ? Math.min(Math.max(batchSize, 1), MAX_BATCH_SIZE)
                : DEFAULT_BATCH_SIZE;

        String dataPayload;
        try {
            dataPayload = objectMapper.writeValueAsString(records);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Serialization error",
                            "Failed to serialize records: " + e.getMessage()));
        }

        String jobId = bulkJobRepository.create(
                tenantId, collectionId, operation,
                "application/json", effectiveBatchSize, externalIdField,
                userEmail, dataPayload, null, records.size());

        securityLog.info("security_event=BULK_JOB_CREATED user={} tenant={} job={} operation={} records={}",
                userEmail, tenantId, jobId, operation, records.size());

        log.info("Bulk job created: jobId={}, operation={}, collection={}, records={}, batchSize={}",
                jobId, operation, collectionId, records.size(), effectiveBatchSize);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("status", "QUEUED");
        attributes.put("operation", operation);
        attributes.put("collectionId", collectionId);
        attributes.put("totalRecords", records.size());
        attributes.put("processedRecords", 0);
        attributes.put("successRecords", 0);
        attributes.put("errorRecords", 0);
        attributes.put("batchSize", effectiveBatchSize);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponseBuilder.single("bulk-jobs", jobId, attributes));
    }

    /**
     * Gets the status and progress of a bulk job.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getJob(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            HttpServletRequest httpRequest) {

        ResponseEntity<Map<String, Object>> denied = requireManageData(httpRequest);
        if (denied != null) {
            return denied;
        }

        Optional<Map<String, Object>> jobOpt = bulkJobRepository.findByIdAndTenant(id, tenantId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> job = jobOpt.get();
        return ResponseEntity.ok(JsonApiResponseBuilder.single("bulk-jobs", id, toJobAttributes(job)));
    }

    /**
     * Lists bulk jobs for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listJobs(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest httpRequest) {

        ResponseEntity<Map<String, Object>> denied = requireManageData(httpRequest);
        if (denied != null) {
            return denied;
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        int effectiveOffset = Math.max(offset, 0);

        List<Map<String, Object>> jobs = bulkJobRepository.findByTenant(tenantId, effectiveLimit, effectiveOffset);
        List<Map<String, Object>> records = jobs.stream()
                .map(job -> {
                    Map<String, Object> record = toJobAttributes(job);
                    record.put("id", job.get("id"));
                    return record;
                })
                .toList();

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("bulk-jobs", records));
    }

    /**
     * Gets per-record results for a bulk job.
     */
    @GetMapping("/{id}/results")
    public ResponseEntity<Map<String, Object>> getResults(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String status,
            HttpServletRequest httpRequest) {

        ResponseEntity<Map<String, Object>> denied = requireManageData(httpRequest);
        if (denied != null) {
            return denied;
        }

        // Verify job belongs to tenant
        Optional<Map<String, Object>> jobOpt = bulkJobRepository.findByIdAndTenant(id, tenantId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), 200);
        int effectiveOffset = Math.max(offset, 0);
        String statusFilter = status != null && Set.of("SUCCESS", "FAILURE").contains(status.toUpperCase())
                ? status.toUpperCase() : null;

        List<Map<String, Object>> results = bulkJobRepository.findResults(
                id, tenantId, effectiveLimit, effectiveOffset, statusFilter);
        int totalCount = bulkJobRepository.countResults(id, tenantId, statusFilter);

        List<Map<String, Object>> records = results.stream()
                .map(r -> {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("id", r.get("id"));
                    record.put("recordIndex", r.get("record_index"));
                    record.put("recordId", r.get("record_id"));
                    record.put("status", r.get("status"));
                    record.put("errorMessage", r.get("error_message"));
                    return record;
                })
                .toList();

        Map<String, Object> meta = Map.of(
                "totalCount", totalCount,
                "limit", effectiveLimit,
                "offset", effectiveOffset);

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("bulk-job-results", records, meta));
    }

    /**
     * Downloads all per-record results of a bulk job as a CSV attachment.
     *
     * <p>Streams the full result set in repository pages so a large job never
     * materializes every row in one list.
     */
    @GetMapping(value = "/{id}/results/download", produces = "text/csv")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> downloadResults(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            HttpServletRequest httpRequest) {

        if (requireManageData(httpRequest) != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<Map<String, Object>> jobOpt = bulkJobRepository.findByIdAndTenant(id, tenantId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            var writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8));
            writer.write("recordIndex,recordId,status,errorMessage");
            writer.newLine();
            int pageSize = 500;
            int offset = 0;
            while (true) {
                List<Map<String, Object>> page =
                        bulkJobRepository.findResults(id, tenantId, pageSize, offset, null);
                for (Map<String, Object> r : page) {
                    writer.write(csvRow(
                            r.get("record_index"), r.get("record_id"),
                            r.get("status"), r.get("error_message")));
                    writer.newLine();
                }
                if (page.size() < pageSize) {
                    break;
                }
                offset += pageSize;
            }
            writer.flush();
        };

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"bulk-job-" + id + "-results.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    private static String csvRow(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvEscape(values[i]));
        }
        return sb.toString();
    }

    private static String csvEscape(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /**
     * Aborts a queued or in-progress bulk job.
     */
    @PostMapping("/{id}/abort")
    public ResponseEntity<Map<String, Object>> abortJob(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            HttpServletRequest httpRequest) {

        ResponseEntity<Map<String, Object>> denied = requireManageData(httpRequest);
        if (denied != null) {
            return denied;
        }

        int updated = bulkJobRepository.abort(id, tenantId);
        if (updated == 0) {
            Optional<Map<String, Object>> jobOpt = bulkJobRepository.findByIdAndTenant(id, tenantId);
            if (jobOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.unprocessableEntity().body(
                    JsonApiResponseBuilder.error("422", "Cannot abort",
                            "Job is not in QUEUED or PROCESSING status"));
        }

        securityLog.info("security_event=BULK_JOB_ABORTED user={} tenant={} job={}",
                userEmail, tenantId, id);

        Optional<Map<String, Object>> jobOpt = bulkJobRepository.findByIdAndTenant(id, tenantId);
        Map<String, Object> job = jobOpt.orElseGet(Map::of);
        return ResponseEntity.ok(JsonApiResponseBuilder.single("bulk-jobs", id, toJobAttributes(job)));
    }

    private Map<String, Object> toJobAttributes(Map<String, Object> job) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("status", job.get("status"));
        attributes.put("operation", job.get("operation"));
        attributes.put("collectionId", job.get("collection_id"));
        attributes.put("totalRecords", job.get("total_records"));
        attributes.put("processedRecords", job.get("processed_records"));
        attributes.put("successRecords", job.get("success_records"));
        attributes.put("errorRecords", job.get("error_records"));
        attributes.put("batchSize", job.get("batch_size"));
        attributes.put("externalIdField", job.get("external_id_field"));
        attributes.put("contentType", job.get("content_type"));
        attributes.put("errorMessage", job.get("error_message"));
        attributes.put("createdBy", job.get("created_by"));
        attributes.put("startedAt", job.get("started_at"));
        attributes.put("completedAt", job.get("completed_at"));
        attributes.put("createdAt", job.get("created_at"));
        attributes.put("updatedAt", job.get("updated_at"));
        return attributes;
    }

    private boolean collectionExists(String collectionId, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM collection WHERE id = ? AND tenant_id = ? AND active = true LIMIT 1",
                collectionId, tenantId);
        return !rows.isEmpty();
    }
}
