package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.interceptor.DerivedDataController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.ReportExecutionService;
import io.kelta.worker.service.ReportExecutionService.MaskingPrincipal;
import io.kelta.worker.service.ReportExecutionService.ReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST controller for executing reports and exporting report data.
 *
 * <p>Provides endpoints to run a report (producing paginated query results
 * from the report's configuration) and to export report data as CSV.
 *
 * <p>A {@link DerivedDataController}: a report run is not a {@code reports} record, so the
 * record-level advice leaves it alone and this controller requires the caller to be able to read
 * the report record itself; an unreadable report answers like a missing one.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/reports")
public class ReportExecutionController implements DerivedDataController {

    private static final Logger log = LoggerFactory.getLogger(ReportExecutionController.class);
    private static final String VIEW_PERMISSION = "VIEW_ANALYTICS";
    private static final String MANAGE_PERMISSION = "MANAGE_REPORTS";

    private final ReportExecutionService reportExecutionService;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final CerbosRecordAuthorizationAdvice recordAuthorization;

    public ReportExecutionController(ReportExecutionService reportExecutionService,
                                     QueryEngine queryEngine,
                                     CollectionRegistry collectionRegistry,
                                     CerbosPermissionResolver permissionResolver,
                                     BootstrapRepository bootstrapRepository,
                                     CerbosRecordAuthorizationAdvice recordAuthorization) {
        this.reportExecutionService = reportExecutionService;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.recordAuthorization = recordAuthorization;
    }

    /**
     * Gates report execution on the VIEW_ANALYTICS system permission; MANAGE_REPORTS
     * (the authoring permission) also passes. Fail-closed: no resolvable profile is rejected.
     * Must run before the endpoint's try/catch so the 403 is not converted to a 500.
     */
    private void requireAnalyticsAccess(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> (VIEW_PERMISSION.equals(p.get("permission_name"))
                        || MANAGE_PERMISSION.equals(p.get("permission_name")))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, VIEW_PERMISSION + " permission required");
        }
    }

    /** Builds the data-masking principal for the calling user from the gateway-forwarded identity headers. */
    private MaskingPrincipal principalOf(HttpServletRequest request) {
        return new MaskingPrincipal(
            permissionResolver.getEmail(request),
            permissionResolver.getProfileId(request),
            permissionResolver.getTenantId(request));
    }

    /**
     * Executes a report and returns paginated results.
     *
     * @param reportId   the report ID
     * @param pageNumber the page number (1-indexed, default 1)
     * @param pageSize   the page size (default 200, max 2000)
     * @return paginated report data with metadata
     */
    @PostMapping("/{reportId}/execute")
    public ResponseEntity<Map<String, Object>> executeReport(
            @PathVariable String reportId,
            @RequestParam(value = "page[number]", defaultValue = "1") int pageNumber,
            @RequestParam(value = "page[size]", defaultValue = "200") int pageSize,
            HttpServletRequest request) {

        requireAnalyticsAccess(request);

        try {
            // Load the report config from the reports system collection
            Map<String, Object> reportConfig = loadReportConfig(reportId);
            if (reportConfig == null || !recordAuthorization.isRecordReadable(request, "reports", reportConfig)) {
                return ResponseEntity.notFound().build();
            }

            ReportResult result = reportExecutionService.execute(
                reportConfig, pageNumber, pageSize, principalOf(request));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("reportId", reportId);
            attributes.put("reportName", reportConfig.get("name"));
            attributes.put("reportType", reportConfig.get("reportType"));
            attributes.put("columns", result.columns().stream()
                .map(c -> {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("fieldName", c.fieldName());
                    col.put("label", c.label());
                    col.put("type", c.type());
                    return col;
                })
                .toList());
            attributes.put("records", result.data());

            if (result.groups() != null) {
                attributes.put("groups", result.groups());
                attributes.put("groupAggregations", result.groupAggregations());
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("totalCount", result.metadata().totalCount());
            meta.put("currentPage", result.metadata().currentPage());
            meta.put("pageSize", result.metadata().pageSize());
            meta.put("totalPages", result.metadata().totalPages());

            return ResponseEntity.ok(
                JsonApiResponseBuilder.single("report-results", reportId, attributes, meta));

        } catch (ReportExecutionService.ReportExecutionException e) {
            log.warn("Report execution failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                JsonApiResponseBuilder.error("400", "Bad Request", e.getMessage()));
        } catch (Exception e) {
            log.error("Report execution error: reportId={}", reportId, e);
            return ResponseEntity.internalServerError().body(
                JsonApiResponseBuilder.error("500", "Internal Server Error",
                    "Failed to execute report"));
        }
    }

    /**
     * Exports report data as CSV.
     *
     * @param reportId the report ID
     * @param response the HTTP response for streaming
     */
    @GetMapping("/{reportId}/export")
    public void exportReport(
            @PathVariable String reportId,
            @RequestParam(defaultValue = "csv") String format,
            HttpServletRequest request,
            HttpServletResponse response) {

        requireAnalyticsAccess(request);

        try {
            Map<String, Object> reportConfig = loadReportConfig(reportId);
            if (reportConfig == null || !recordAuthorization.isRecordReadable(request, "reports", reportConfig)) {
                response.setStatus(404);
                return;
            }

            MaskingPrincipal principal = principalOf(request);
            String reportName = (String) reportConfig.getOrDefault("name", "report");
            String safeFileName = reportName.replaceAll("[^a-zA-Z0-9._-]", "_");

            if ("csv".equalsIgnoreCase(format)) {
                response.setContentType("text/csv; charset=UTF-8");
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + safeFileName + ".csv\"");

                try (Writer writer = new OutputStreamWriter(
                        response.getOutputStream(), StandardCharsets.UTF_8)) {
                    reportExecutionService.exportCsv(reportConfig, writer, principal);
                    writer.flush();
                }
            } else if ("pdf".equalsIgnoreCase(format)) {
                response.setContentType(MediaType.APPLICATION_PDF_VALUE);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + safeFileName + ".pdf\"");

                reportExecutionService.exportPdf(reportConfig, response.getOutputStream(), principal);
                response.getOutputStream().flush();
            } else {
                response.setStatus(400);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                    "{\"errors\":[{\"status\":\"400\",\"title\":\"Bad Request\","
                    + "\"detail\":\"Unsupported export format: " + format + ". Supported: csv, pdf\"}]}");
            }

        } catch (ReportExecutionService.ReportExecutionException e) {
            log.warn("Report export failed: {}", e.getMessage());
            try {
                response.setStatus(400);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                    "{\"errors\":[{\"status\":\"400\",\"title\":\"Bad Request\","
                    + "\"detail\":\"" + e.getMessage().replace("\"", "'") + "\"}]}");
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.error("Report export error: reportId={}", reportId, e);
            try {
                response.setStatus(500);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Loads a report configuration from the reports system collection.
     */
    private Map<String, Object> loadReportConfig(String reportId) {
        CollectionDefinition reportsDef = collectionRegistry.get("reports");
        if (reportsDef == null) {
            log.error("Reports system collection not found in registry");
            return null;
        }

        return queryEngine.getById(reportsDef, reportId).orElse(null);
    }
}
