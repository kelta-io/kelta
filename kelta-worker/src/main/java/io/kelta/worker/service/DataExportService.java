package io.kelta.worker.service;

import io.kelta.runtime.event.EventFactory;
import io.kelta.worker.util.CsvFormatUtils;
import io.kelta.worker.util.TenantContextUtils;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.DataExportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for executing data exports — supports full-tenant and selective collection exports
 * in CSV and JSON formats. Export output is stored in S3 when available, or kept in-memory
 * for streaming download.
 *
 * @since 1.0.0
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);
    private static final int EXPORT_PAGE_SIZE = 1000;
    private static final String SUBJECT_EXPORT_COMPLETED = "kelta.data.export.completed";

    private final DataExportRepository exportRepository;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Optional<S3StorageService> s3StorageService;
    private final PlatformEventPublisher eventPublisher;
    private final RecordMaskingService recordMaskingService;
    private final TenantSlugResolver tenantSlugResolver;

    public DataExportService(DataExportRepository exportRepository,
                             QueryEngine queryEngine,
                             CollectionRegistry collectionRegistry,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper,
                             Optional<S3StorageService> s3StorageService,
                             PlatformEventPublisher eventPublisher,
                             RecordMaskingService recordMaskingService,
                             TenantSlugResolver tenantSlugResolver) {
        this.exportRepository = exportRepository;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.s3StorageService = s3StorageService;
        this.eventPublisher = eventPublisher;
        this.recordMaskingService = recordMaskingService;
        this.tenantSlugResolver = tenantSlugResolver;
    }

    /**
     * The user an export runs on behalf of, for data-masking. Bulk export is an
     * egress boundary the JSON:API read advice never sees, so masking-configured
     * fields the user cannot unmask are masked in the serialized output. A
     * {@code null}/blank principal (e.g. a legacy caller) exports unmasked —
     * callers on a request path always pass the real user.
     */
    public record ExportPrincipal(String email, String profileId) {
        boolean isPresent() {
            return profileId != null && !profileId.isBlank();
        }
    }

    /**
     * Creates a new data export request.
     *
     * @param tenantId      the tenant ID
     * @param name          the export name
     * @param description   optional description
     * @param exportScope   FULL or SELECTIVE
     * @param collectionIds list of collection IDs (null for FULL scope)
     * @param format        CSV or JSON
     * @param createdBy     the user who initiated the export
     * @return the export ID
     */
    public String createExport(String tenantId, String name, String description,
                               String exportScope, List<String> collectionIds,
                               String format, String createdBy) {
        String collectionIdsJson = null;
        if (collectionIds != null && !collectionIds.isEmpty()) {
            try {
                collectionIdsJson = objectMapper.writeValueAsString(collectionIds);
            } catch (Exception e) {
                throw new DataExportException("Failed to serialize collection IDs", e);
            }
        }

        return exportRepository.create(tenantId, name, description, exportScope,
                collectionIdsJson, format, createdBy);
    }

    /**
     * Executes a data export asynchronously. Called after creation to process the export.
     */
    @Async
    public void executeExport(String exportId, String tenantId) {
        executeExport(exportId, tenantId, null);
    }

    /**
     * Executes a data export asynchronously, masking every masking-configured
     * field the given principal may not unmask in the serialized output.
     */
    @Async
    public void executeExport(String exportId, String tenantId, ExportPrincipal principal) {
        var exportOpt = exportRepository.findPendingExport(exportId);
        if (exportOpt.isEmpty()) {
            log.warn("Export {} not found or not pending, skipping", exportId);
            return;
        }

        Map<String, Object> export = exportOpt.get();
        String exportScope = (String) export.get("export_scope");
        String collectionIdsJson = (String) export.get("collection_ids");
        String format = (String) export.get("format");
        String name = (String) export.get("name");

        exportRepository.markInProgress(exportId);

        try {
            // The slug too, not just the id: a tenant's own collections live in the schema its
            // slug names, and this runs off the request thread with nothing bound.
            String tenantSlug = tenantSlugResolver.resolveSlug(tenantId).orElse(null);
            TenantContextUtils.withTenant(tenantId, tenantSlug, () -> {
                // Resolve target collections
                List<CollectionInfo> collections = resolveCollections(tenantId, exportScope, collectionIdsJson);
                if (collections.isEmpty()) {
                    exportRepository.markFailed(exportId, "No collections found for export");
                    return;
                }

                // Execute export
                byte[] exportData;
                int totalRecords;
                int recordsExported;

                if ("JSON".equalsIgnoreCase(format)) {
                    ExportResult result = exportAsJson(exportId, tenantId, collections, principal);
                    exportData = result.data;
                    totalRecords = result.totalRecords;
                    recordsExported = result.recordsExported;
                } else {
                    ExportResult result = exportAsCsv(exportId, tenantId, collections, principal);
                    exportData = result.data;
                    totalRecords = result.totalRecords;
                    recordsExported = result.recordsExported;
                }

                // Store to S3 if available
                String storageKey = null;
                if (s3StorageService.isPresent()) {
                    String extension = "JSON".equalsIgnoreCase(format) ? "json" : "csv";
                    String contentType = "JSON".equalsIgnoreCase(format)
                            ? "application/json" : "text/csv";
                    storageKey = String.format("exports/%s/%s/%s.%s",
                            tenantId, exportId, sanitizeFileName(name), extension);
                    s3StorageService.get().uploadObject(storageKey, exportData, contentType);
                }

                exportRepository.markCompleted(exportId, totalRecords, recordsExported,
                        storageKey, exportData.length);

                // Publish event
                publishExportCompleted(tenantId, exportId, format, recordsExported);

                log.info("Data export completed: exportId={}, tenant={}, scope={}, format={}, " +
                                "collections={}, records={}, size={}",
                        exportId, tenantId, exportScope, format,
                        collections.size(), recordsExported, exportData.length);
            });
        } catch (Exception e) {
            log.error("Data export failed: exportId={}, tenant={}, error={}",
                    exportId, tenantId, e.getMessage(), e);
            exportRepository.markFailed(exportId, e.getMessage());
        }
    }

    /**
     * Gets the export data bytes for download. For S3-backed exports, streams from S3.
     * For small exports or when S3 is unavailable, re-executes the export.
     */
    public byte[] getExportData(String exportId, String tenantId) {
        var exportOpt = exportRepository.findByIdAndTenant(exportId, tenantId);
        if (exportOpt.isEmpty()) {
            return null;
        }

        Map<String, Object> export = exportOpt.get();
        String status = (String) export.get("status");
        if (!"COMPLETED".equals(status)) {
            return null;
        }

        String storageKey = (String) export.get("storage_key");
        if (storageKey != null && s3StorageService.isPresent()) {
            try (var storageObject = s3StorageService.get().streamObject(storageKey)) {
                return storageObject.content().readAllBytes();
            } catch (IOException e) {
                throw new DataExportException("Failed to read export from storage", e);
            }
        }

        return null;
    }

    /**
     * Gets a presigned download URL for a completed export.
     */
    public String getDownloadUrl(String exportId, String tenantId) {
        var exportOpt = exportRepository.findByIdAndTenant(exportId, tenantId);
        if (exportOpt.isEmpty()) {
            return null;
        }

        Map<String, Object> export = exportOpt.get();
        String storageKey = (String) export.get("storage_key");
        if (storageKey != null && s3StorageService.isPresent()) {
            return s3StorageService.get().getPresignedDownloadUrl(storageKey, s3StorageService.get().getDefaultExpiry());
        }
        return null;
    }

    // =========================================================================
    // Export execution
    // =========================================================================

    private ExportResult exportAsCsv(String exportId, String tenantId,
                                     List<CollectionInfo> collections,
                                     ExportPrincipal principal) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);

        int totalRecords = 0;
        int recordsExported = 0;

        for (CollectionInfo collInfo : collections) {
            CollectionDefinition definition = collInfo.definition;
            List<FieldDefinition> fields = definition.fields();

            if (fields == null || fields.isEmpty()) {
                continue;
            }

            // Section header for multi-collection exports
            if (collections.size() > 1) {
                writer.write("# Collection: " + definition.name() + "\n");
            }

            // CSV header
            List<String> fieldNames = fields.stream()
                    .map(FieldDefinition::name)
                    .toList();
            writer.write(fieldNames.stream()
                    .map(this::escapeCsv)
                    .collect(Collectors.joining(",")));
            writer.write("\n");

            // Fetch and write all pages
            int page = 1;
            while (true) {
                Pagination pagination = new Pagination(page, EXPORT_PAGE_SIZE);
                QueryRequest request = new QueryRequest(pagination, List.of(), fieldNames, List.of());
                QueryResult result = queryEngine.executeQuery(definition, request);

                if (page == 1) {
                    totalRecords += (int) result.metadata().totalCount();
                }

                maskRows(definition, result.data(), tenantId, principal);

                for (Map<String, Object> record : result.data()) {
                    writer.write(fieldNames.stream()
                            .map(f -> escapeCsv(formatValue(record.get(f))))
                            .collect(Collectors.joining(",")));
                    writer.write("\n");
                    recordsExported++;
                }

                // Update progress periodically
                if (page % 5 == 0) {
                    exportRepository.updateProgress(exportId, totalRecords, recordsExported);
                }

                if (result.data().isEmpty() || recordsExported >= result.metadata().totalCount()) {
                    break;
                }
                page++;
            }

            if (collections.size() > 1) {
                writer.write("\n");
            }
        }

        writer.flush();
        return new ExportResult(baos.toByteArray(), totalRecords, recordsExported);
    }

    @SuppressWarnings("unchecked")
    private ExportResult exportAsJson(String exportId, String tenantId,
                                      List<CollectionInfo> collections,
                                      ExportPrincipal principal) throws IOException {
        Map<String, Object> exportData = new LinkedHashMap<>();
        exportData.put("tenantId", tenantId);
        exportData.put("exportedAt", Instant.now().toString());
        exportData.put("format", "JSON");

        List<Map<String, Object>> collectionsData = new ArrayList<>();
        int totalRecords = 0;
        int recordsExported = 0;

        for (CollectionInfo collInfo : collections) {
            CollectionDefinition definition = collInfo.definition;
            List<FieldDefinition> fields = definition.fields();

            if (fields == null || fields.isEmpty()) {
                continue;
            }

            Map<String, Object> collData = new LinkedHashMap<>();
            collData.put("collectionId", collInfo.id);
            collData.put("collectionName", definition.name());

            // Export field schema
            List<Map<String, Object>> fieldSchema = fields.stream()
                    .map(f -> {
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("name", f.name());
                        fm.put("type", f.type().name());
                        fm.put("nullable", f.nullable());
                        return fm;
                    })
                    .toList();
            collData.put("fields", fieldSchema);

            // Export records
            List<String> fieldNames = fields.stream()
                    .map(FieldDefinition::name)
                    .toList();

            List<Map<String, Object>> records = new ArrayList<>();
            int page = 1;
            while (true) {
                Pagination pagination = new Pagination(page, EXPORT_PAGE_SIZE);
                QueryRequest request = new QueryRequest(pagination, List.of(), fieldNames, List.of());
                QueryResult result = queryEngine.executeQuery(definition, request);

                if (page == 1) {
                    totalRecords += (int) result.metadata().totalCount();
                }

                maskRows(definition, result.data(), tenantId, principal);
                records.addAll(result.data());
                recordsExported += result.data().size();

                if (page % 5 == 0) {
                    exportRepository.updateProgress(exportId, totalRecords, recordsExported);
                }

                if (result.data().isEmpty() || records.size() >= result.metadata().totalCount()) {
                    break;
                }
                page++;
            }

            collData.put("records", records);
            collData.put("recordCount", records.size());
            collectionsData.add(collData);
        }

        exportData.put("collections", collectionsData);
        exportData.put("totalRecords", recordsExported);

        byte[] data = objectMapper.writeValueAsBytes(exportData);
        return new ExportResult(data, totalRecords, recordsExported);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<CollectionInfo> resolveCollections(String tenantId, String exportScope,
                                                    String collectionIdsJson) {
        List<CollectionInfo> result = new ArrayList<>();

        if ("SELECTIVE".equals(exportScope) && collectionIdsJson != null) {
            // Selective: export only specified collections
            List<String> collectionIds;
            try {
                collectionIds = objectMapper.readValue(collectionIdsJson, List.class);
            } catch (Exception e) {
                throw new DataExportException("Failed to parse collection IDs", e);
            }

            for (String collId : collectionIds) {
                var rows = jdbcTemplate.queryForList(
                        "SELECT id, name FROM collection WHERE id = ? AND tenant_id = ? AND active = true",
                        collId, tenantId
                );
                if (!rows.isEmpty()) {
                    String name = (String) rows.get(0).get("name");
                    CollectionDefinition def = collectionRegistry.get(name);
                    if (def != null) {
                        result.add(new CollectionInfo(collId, name, def));
                    }
                }
            }
        } else {
            // Full: export all active tenant collections
            var rows = jdbcTemplate.queryForList(
                    "SELECT id, name FROM collection WHERE tenant_id = ? AND active = true ORDER BY name",
                    tenantId
            );
            for (Map<String, Object> row : rows) {
                String id = (String) row.get("id");
                String name = (String) row.get("name");
                CollectionDefinition def = collectionRegistry.get(name);
                if (def != null) {
                    result.add(new CollectionInfo(id, name, def));
                }
            }
        }

        return result;
    }

    private void publishExportCompleted(String tenantId, String exportId,
                                        String format, int recordCount) {
        try {
            Map<String, Object> payload = Map.of(
                    "exportId", exportId,
                    "format", format,
                    "recordCount", recordCount
            );
            PlatformEvent<Map<String, Object>> event = EventFactory.createEvent("data.export.completed", payload);
            event.setTenantId(tenantId);
            String subject = SUBJECT_EXPORT_COMPLETED + "." + tenantId + "." + exportId;
            eventPublisher.publish(subject, event);
        } catch (Exception e) {
            log.error("Failed to publish export completed event: exportId={}, error={}",
                    exportId, e.getMessage());
        }
    }

    /**
     * Masks, in place, every masking-configured field of {@code definition} that
     * the export's principal may not unmask. No-op for a system/null principal or
     * a collection without masking config (no Cerbos call in that case).
     */
    private void maskRows(CollectionDefinition definition, List<Map<String, Object>> rows,
                          String tenantId, ExportPrincipal principal) {
        if (principal == null || !principal.isPresent()) {
            return;
        }
        recordMaskingService.maskRows(definition, rows,
                principal.email(), principal.profileId(), tenantId);
    }

    private String formatValue(Object value) {
        return CsvFormatUtils.formatValue(value, objectMapper);
    }

    private String escapeCsv(String value) {
        return CsvFormatUtils.escapeCsv(value);
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // =========================================================================
    // Internal types
    // =========================================================================

    private record CollectionInfo(String id, String name, CollectionDefinition definition) {}

    private record ExportResult(byte[] data, int totalRecords, int recordsExported) {}

    public static class DataExportException extends RuntimeException {
        public DataExportException(String message) {
            super(message);
        }
        public DataExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
