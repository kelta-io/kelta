package io.kelta.worker.controller;

import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.analytics.AnalyticsCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the global full-text search endpoint.
 *
 * <p>Serves {@code GET /api/_search?q={query}&limit={limit}}.
 * Queries the centralized {@code search_index} table using PostgreSQL full-text search.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/_search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MIN_QUERY_LENGTH = 3;

    private final SearchIndexService searchIndexService;
    private final AnalyticsCaptureService analyticsCaptureService;

    public SearchController(SearchIndexService searchIndexService,
                           AnalyticsCaptureService analyticsCaptureService) {
        this.searchIndexService = searchIndexService;
        this.analyticsCaptureService = analyticsCaptureService;
    }

    /**
     * Performs a full-text search across all collections for the current tenant.
     *
     * @param tenantId the tenant ID from the gateway's X-Tenant-ID header
     * @param q        the search query string (minimum 3 characters)
     * @param limit    maximum number of results (default 20, max 100)
     * @return search results with collection info and display values
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String memberId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit) {

        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    errorResponse("400", "MISSING_TENANT", "Bad Request",
                            "Missing X-Tenant-ID header"));
        }

        // Return empty results for short queries
        if (q == null || q.trim().length() < MIN_QUERY_LENGTH) {
            return ResponseEntity.ok(emptyResponse());
        }

        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        // TenantContextFilter has already bound the tenant from this same X-Tenant-ID header,
        // so the search below runs RLS-scoped to it without any binding here.
        List<Map<String, Object>> results = searchIndexService.search(
                tenantId, q.trim(), effectiveLimit);

        List<Map<String, Object>> data = results.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("record_id"));
            item.put("type", row.get("collection_name"));
            item.put("collectionName", row.get("collection_name"));
            item.put("collectionId", row.get("collection_id"));
            item.put("displayValue", row.get("display_value"));
            item.put("rank", row.get("rank"));
            return item;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        response.put("meta", Map.of("total", data.size()));

        // Capture the query for the demand-mining corpus (consumer-alerting slice 8).
        // Best-effort: an empty result set is the highest-value signal (unmet demand), and a
        // capture failure must never affect the search response.
        try {
            analyticsCaptureService.captureSearch(q.trim(), data.isEmpty(), null, memberId);
        } catch (RuntimeException captureError) {
            log.debug("search analytics capture failed: {}", captureError.toString());
        }

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> emptyResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", List.of());
        response.put("meta", Map.of("total", 0));
        return response;
    }

    private Map<String, Object> errorResponse(String status, String code, String title, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", status);
        error.put("code", code);
        error.put("title", title);
        error.put("detail", detail);
        return Map.of("errors", List.of(error));
    }
}
