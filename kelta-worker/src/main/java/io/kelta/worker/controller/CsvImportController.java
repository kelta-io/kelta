package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.router.SubResourceAuthorizer.Decision;
import io.kelta.runtime.router.SubResourceAuthorizer.Operation;
import io.kelta.runtime.router.SubResourceAuthorizer.Outcome;
import io.kelta.worker.interceptor.CerbosSubResourceAuthorizer;
import io.kelta.worker.interceptor.FieldWriteGuard;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CsvImportService;
import io.kelta.worker.service.CsvImportService.ImportResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Accepts a CSV file upload and bulk-creates records in the named collection.
 *
 * <p>The CSV must have a header row with column names matching field names.
 * System fields (id, createdAt, updatedAt, createdBy, updatedBy) are ignored.
 * Rows that fail validation are reported per-row; successful rows are committed.
 *
 * <p>The gateway reaches this endpoint through the {@code collections} static route, which checks
 * only {@code API_ACCESS} for it. The import is a create on the target collection, so it is
 * authorized here as a direct {@code POST /api/{collectionName}} would be at the gateway
 * ({@link CerbosSubResourceAuthorizer#authorizeCollection}), and columns naming a field the
 * caller may not write are left out of every record, as {@link FieldWriteGuard} does for any
 * other create. Skipped columns are listed in the response.
 */
@RestController
@RequestMapping("/api/collections/{collectionName}/import")
public class CsvImportController {

    private static final Logger log = LoggerFactory.getLogger(CsvImportController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    private final CsvImportService csvImportService;
    private final CerbosSubResourceAuthorizer authorizer;
    private final FieldWriteGuard fieldWriteGuard;
    private final CerbosPermissionResolver permissionResolver;
    private final boolean permissionsEnabled;

    public CsvImportController(CsvImportService csvImportService,
                               CerbosSubResourceAuthorizer authorizer,
                               FieldWriteGuard fieldWriteGuard,
                               CerbosPermissionResolver permissionResolver,
                               @Value("${kelta.gateway.security.permissions-enabled:true}") boolean permissionsEnabled) {
        this.csvImportService = csvImportService;
        this.authorizer = authorizer;
        this.fieldWriteGuard = fieldWriteGuard;
        this.permissionResolver = permissionResolver;
        this.permissionsEnabled = permissionsEnabled;
    }

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importCsv(
            @PathVariable String collectionName,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Email") String userEmail,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Empty file", "No file content received"));
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "File too large",
                            "Maximum file size is 10 MB"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.toLowerCase().endsWith(".csv")) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Invalid file type",
                            "Only CSV files are supported"));
        }

        CollectionDefinition definition = csvImportService.resolveCollection(collectionName);
        if (definition == null) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Import failed", "Collection not found: " + collectionName));
        }

        Decision decision = authorizer.authorizeCollection(definition, Operation.CREATE, request);
        if (decision.outcome() == Outcome.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    JsonApiResponseBuilder.error("404", "Not Found", "Collection not found: " + collectionName));
        }
        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "Forbidden", decision.detail()));
        }

        ImportResult result;
        List<String> skippedColumns;
        try {
            byte[] content = file.getBytes();
            skippedColumns = writeDeniedColumns(definition, content, request);
            result = csvImportService.importCsv(definition, new ByteArrayInputStream(content),
                    new LinkedHashSet<>(skippedColumns));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Import failed", e.getMessage()));
        } catch (Exception e) {
            log.error("CSV import error: collection={}, tenant={}, error={}",
                    collectionName, tenantId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Import failed", e.getMessage()));
        }

        securityLog.info("security_event=CSV_IMPORT user={} tenant={} collection={} rows={} imported={}",
                userEmail, tenantId, collectionName, result.rowsProcessed(), result.rowsImported());

        log.info("CSV import completed: collection={}, tenant={}, rows={}, imported={}, errors={}",
                collectionName, tenantId, result.rowsProcessed(), result.rowsImported(),
                result.errors().size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rowsProcessed", result.rowsProcessed());
        body.put("rowsImported", result.rowsImported());
        body.put("errorCount", result.errors().size());
        if (!skippedColumns.isEmpty()) {
            body.put("skippedColumns", skippedColumns);
        }
        body.put("errors", result.errors().stream()
                .map(e -> Map.of("row", e.row(), "message", e.message()))
                .toList());

        return ResponseEntity.ok(body);
    }
    /**
     * The header columns naming a field the caller may not write. Same exemptions as the field
     * write advice: permissions disabled, no gateway identity, or a platform metadata collection.
     */
    private List<String> writeDeniedColumns(CollectionDefinition definition, byte[] content,
                                            HttpServletRequest request) throws IOException {
        if (!permissionsEnabled || !permissionResolver.hasIdentity(request)
                || FieldWriteGuard.isMetadataCollection(definition.name())) {
            return List.of();
        }
        List<String> columns = csvImportService.importableColumns(definition, new ByteArrayInputStream(content));
        if (columns.isEmpty()) {
            return List.of();
        }
        Map<String, Object> submitted = new LinkedHashMap<>();
        for (String column : columns) {
            submitted.put(column, Boolean.TRUE);
        }
        return fieldWriteGuard.stripUnwritable(submitted, null, permissionResolver.getEmail(request),
                permissionResolver.getProfileId(request), permissionResolver.getTenantId(request),
                definition.name());
    }
}
