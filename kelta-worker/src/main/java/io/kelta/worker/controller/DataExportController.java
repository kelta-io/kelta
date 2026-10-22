package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.DataExportRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.DataExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * REST controller for managing data exports.
 *
 * <p>Provides endpoints to create, list, check status, download, and cancel
 * tenant data exports in CSV or JSON format.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/data-exports")
public class DataExportController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(DataExportController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final Set<String> VALID_FORMATS = Set.of("CSV", "JSON");
    private static final Set<String> VALID_SCOPES = Set.of("FULL", "SELECTIVE");

    /**
     * Bulk data export is a whole-tenant read (one call can serialize every record in every
     * collection to a downloadable file), so it is gated on {@code VIEW_ALL_DATA} — the same
     * "read everything" authority required to see the data by other means. The gateway only
     * applies the blanket {@code API_ACCESS} check to {@code /api/*} routes, so the specific
     * permission must be enforced here (see kelta-worker/CLAUDE.md → Authorization).
     */
    private static final String EXPORT_PERMISSION = "VIEW_ALL_DATA";

    private final DataExportService dataExportService;
    private final DataExportRepository dataExportRepository;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public DataExportController(DataExportService dataExportService,
                                DataExportRepository dataExportRepository,
                                CerbosPermissionResolver permissionResolver,
                                BootstrapRepository bootstrapRepository) {
        this.dataExportService = dataExportService;
        this.dataExportRepository = dataExportRepository;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    private void requireExportPermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> EXPORT_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, EXPORT_PERMISSION + " permission required");
        }
    }

    /**
     * Creates a new data export request.
     */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> createExport(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        requireExportPermission(request);

        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String exportScope = (String) body.getOrDefault("exportScope", "SELECTIVE");
        String format = (String) body.getOrDefault("format", "CSV");
        Object collectionIdsObj = body.get("collectionIds");

        // Validate name
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Missing field", "name is required"));
        }

        // Validate scope
        if (!VALID_SCOPES.contains(exportScope.toUpperCase())) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid scope",
                            "exportScope must be one of: FULL, SELECTIVE"));
        }
        exportScope = exportScope.toUpperCase();

        // Validate format
        if (!VALID_FORMATS.contains(format.toUpperCase())) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid format",
                            "format must be one of: CSV, JSON"));
        }
        format = format.toUpperCase();

        // Validate collection IDs for selective export
        List<String> collectionIds = null;
        if ("SELECTIVE".equals(exportScope)) {
            if (collectionIdsObj == null || !(collectionIdsObj instanceof List<?> list) || list.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Missing collectionIds",
                                "collectionIds is required for SELECTIVE scope"));
            }
            collectionIds = (List<String>) collectionIdsObj;
        }

        // Create the export
        String exportId = dataExportService.createExport(
                tenantId, name, description, exportScope, collectionIds, format, userEmail);

        securityLog.info("security_event=DATA_EXPORT_CREATED user={} tenant={} exportId={} scope={} format={}",
                userEmail, tenantId, exportId, exportScope, format);

        // Trigger async execution, carrying the requester's identity so masked
        // fields they cannot unmask are masked in the exported file.
        DataExportService.ExportPrincipal principal = new DataExportService.ExportPrincipal(
                userEmail, permissionResolver.getProfileId(request));
        dataExportService.executeExport(exportId, tenantId, principal);

        log.info("Data export created: exportId={}, scope={}, format={}, tenant={}",
                exportId, exportScope, format, tenantId);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", name);
        attributes.put("exportScope", exportScope);
        attributes.put("format", format);
        attributes.put("status", "PENDING");
        attributes.put("totalRecords", 0);
        attributes.put("recordsExported", 0);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponseBuilder.single("data-exports", exportId, attributes));
    }

    /**
     * Gets the status and details of a data export.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getExport(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            HttpServletRequest request) {

        requireExportPermission(request);

        Optional<Map<String, Object>> exportOpt = dataExportRepository.findByIdAndTenant(id, tenantId);
        if (exportOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("data-exports", id, toExportAttributes(exportOpt.get())));
    }

    /**
     * Lists data exports for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listExports(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {

        requireExportPermission(request);

        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        int effectiveOffset = Math.max(offset, 0);

        List<Map<String, Object>> exports = dataExportRepository.findByTenant(
                tenantId, effectiveLimit, effectiveOffset);
        int totalCount = dataExportRepository.countByTenant(tenantId);

        List<Map<String, Object>> records = exports.stream()
                .map(e -> {
                    Map<String, Object> record = toExportAttributes(e);
                    record.put("id", e.get("id"));
                    return record;
                })
                .toList();

        Map<String, Object> meta = Map.of(
                "totalCount", totalCount,
                "limit", effectiveLimit,
                "offset", effectiveOffset);

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("data-exports", records, meta));
    }

    /**
     * Downloads a completed data export.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadExport(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            HttpServletRequest request) {

        requireExportPermission(request);

        Optional<Map<String, Object>> exportOpt = dataExportRepository.findByIdAndTenant(id, tenantId);
        if (exportOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> export = exportOpt.get();
        String status = (String) export.get("status");
        if (!"COMPLETED".equals(status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // Try presigned URL redirect first
        String downloadUrl = dataExportService.getDownloadUrl(id, tenantId);
        if (downloadUrl != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, downloadUrl)
                    .build();
        }

        // Fall back to direct streaming
        byte[] data = dataExportService.getExportData(id, tenantId);
        if (data == null) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        String format = (String) export.get("format");
        String name = (String) export.getOrDefault("name", "export");
        String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        String extension = "JSON".equals(format) ? "json" : "csv";
        String contentType = "JSON".equals(format) ? "application/json" : "text/csv";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "." + extension + "\"")
                .body(data);
    }

    /**
     * Cancels a pending data export.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelExport(
            @PathVariable String id,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            HttpServletRequest request) {

        requireExportPermission(request);

        int updated = dataExportRepository.cancel(id, tenantId);
        if (updated == 0) {
            Optional<Map<String, Object>> exportOpt = dataExportRepository.findByIdAndTenant(id, tenantId);
            if (exportOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.unprocessableEntity().body(
                    JsonApiResponseBuilder.error("422", "Cannot cancel",
                            "Export is not in PENDING status"));
        }

        securityLog.info("security_event=DATA_EXPORT_CANCELLED user={} tenant={} exportId={}",
                userEmail, tenantId, id);

        Optional<Map<String, Object>> exportOpt = dataExportRepository.findByIdAndTenant(id, tenantId);
        Map<String, Object> export = exportOpt.orElseGet(Map::of);
        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("data-exports", id, toExportAttributes(export)));
    }

    private Map<String, Object> toExportAttributes(Map<String, Object> export) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", export.get("name"));
        attributes.put("description", export.get("description"));
        attributes.put("exportScope", export.get("export_scope"));
        attributes.put("collectionIds", export.get("collection_ids"));
        attributes.put("format", export.get("format"));
        attributes.put("status", export.get("status"));
        attributes.put("totalRecords", export.get("total_records"));
        attributes.put("recordsExported", export.get("records_exported"));
        attributes.put("fileSizeBytes", export.get("file_size_bytes"));
        attributes.put("createdBy", export.get("created_by"));
        attributes.put("startedAt", export.get("started_at"));
        attributes.put("completedAt", export.get("completed_at"));
        attributes.put("errorMessage", export.get("error_message"));
        attributes.put("createdAt", export.get("created_at"));
        attributes.put("updatedAt", export.get("updated_at"));
        return attributes;
    }
}
