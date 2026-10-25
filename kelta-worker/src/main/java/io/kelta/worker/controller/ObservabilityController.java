package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.worker.service.AuditQueryService;
import io.kelta.worker.service.ObservabilityQueryService;
import io.kelta.worker.service.ObservabilityQueryService.SearchResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST controller for observability data — request logs, application logs, and audit events.
 * Trace queries go to Tempo; log queries go to Loki; audit queries go to PostgreSQL.
 */
@RestController
@RequestMapping("/api/admin/observability")
public class ObservabilityController {

    private static final String PERMISSION = "VIEW_SETUP";

    private static final Logger log = LoggerFactory.getLogger(ObservabilityController.class);

    private final ObservabilityQueryService queryService;
    private final AuditQueryService auditQueryService;
    private final AdminPermissionGuard permissionGuard;

    public ObservabilityController(ObservabilityQueryService queryService,
                                   AuditQueryService auditQueryService,
                                   AdminPermissionGuard permissionGuard) {
        this.queryService = queryService;
        this.auditQueryService = auditQueryService;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping("/request-logs")
    public ResponseEntity<Map<String, Object>> searchRequestLogs(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-Slug", required = false) String tenantSlug,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        permissionGuard.require(request, PERMISSION);
        try {
            Instant endInstant = end != null ? Instant.parse(end) : Instant.now();
            Instant startInstant = start != null ? Instant.parse(start) : endInstant.minusSeconds(3600);

            Map<String, String> filters = new HashMap<>();
            if (method != null) filters.put("method", method);
            if (status != null) filters.put("status", status);
            if (path != null) filters.put("path", path);
            if (traceId != null) filters.put("traceId", traceId);
            if (userId != null) filters.put("userId", userId);

            SearchResult result = queryService.searchTraceSpans(tenantSlug, startInstant, endInstant,
                    filters, page, size);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("hits", result.hits());
            attributes.put("totalHits", result.totalHits());
            attributes.put("page", page);
            attributes.put("size", size);

            return ResponseEntity.ok(JsonApiResponseBuilder.single("request-logs", "search", attributes));
        } catch (Exception e) {
            log.error("Failed to search request logs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to search request logs"));
        }
    }

    @GetMapping("/request-logs/{traceId}")
    public ResponseEntity<Map<String, Object>> getRequestLog(
            HttpServletRequest request,
            @PathVariable String traceId) {
        permissionGuard.require(request, PERMISSION);
        try {
            List<Map<String, Object>> spans = queryService.getTraceDetail(traceId);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("spans", spans);
            attributes.put("traceId", traceId);

            return ResponseEntity.ok(JsonApiResponseBuilder.single("request-log-detail", traceId, attributes));
        } catch (Exception e) {
            log.error("Failed to get request log detail: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to get request log detail"));
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> searchLogs(
            HttpServletRequest request,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        permissionGuard.require(request, PERMISSION);
        try {
            Map<String, String> filters = new HashMap<>();
            if (query != null) filters.put("query", query);
            if (level != null) filters.put("level", level);
            if (service != null) filters.put("service", service);
            if (traceId != null) filters.put("traceId", traceId);
            if (start != null) filters.put("@timestamp_gte", start);
            if (end != null) filters.put("@timestamp_lte", end);

            SearchResult result = queryService.searchLogs(filters, page, size);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("hits", result.hits());
            attributes.put("totalHits", result.totalHits());
            attributes.put("page", page);
            attributes.put("size", size);

            return ResponseEntity.ok(JsonApiResponseBuilder.single("log-entries", "search", attributes));
        } catch (Exception e) {
            log.error("Failed to search logs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to search logs"));
        }
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> searchAudit(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestParam(required = false) String auditType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        permissionGuard.require(request, PERMISSION);
        try {
            Instant startInstant = start != null ? Instant.parse(start) : null;
            Instant endInstant = end != null ? Instant.parse(end) : null;

            SearchResult result = auditQueryService.searchAudit(
                    tenantId, auditType, action, userId,
                    startInstant, endInstant, page, size);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("hits", result.hits());
            attributes.put("totalHits", result.totalHits());
            attributes.put("page", page);
            attributes.put("size", size);

            return ResponseEntity.ok(JsonApiResponseBuilder.single("audit-entries", "search", attributes));
        } catch (Exception e) {
            log.error("Failed to search audit events: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to search audit events"));
        }
    }
}
