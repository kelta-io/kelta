package io.kelta.worker.controller;

import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.analytics.AnalyticsCaptureService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the global full-text search endpoint.
 *
 * <p>Serves {@code GET /api/_search?q={query}&limit={limit}}.
 * Queries the centralized {@code search_index} table using PostgreSQL full-text search.
 *
 * <p>Index rows carry no authorization, so each hit is checked for {@code read} against its own
 * collection and record ({@link CerbosRecordAuthorizationAdvice#readableSearchHits}) before the
 * response is built. The index is paged until {@code limit} readable hits are found, the index is
 * exhausted, or {@link #MAX_SCANNED} rows were scanned, so hits the caller cannot read never fill
 * the page; {@code meta.total} is the number of hits returned.
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
    /** Index rows read per page while collecting readable hits. */
    static final int FETCH_SIZE = MAX_LIMIT;
    /** Upper bound on index rows scanned for one request, readable or not. */
    static final int MAX_SCANNED = 5 * MAX_LIMIT;

    private final SearchIndexService searchIndexService;
    private final AnalyticsCaptureService analyticsCaptureService;
    private final CerbosRecordAuthorizationAdvice recordAuthorization;

    public SearchController(SearchIndexService searchIndexService,
                           AnalyticsCaptureService analyticsCaptureService,
                           CerbosRecordAuthorizationAdvice recordAuthorization) {
        this.searchIndexService = searchIndexService;
        this.analyticsCaptureService = analyticsCaptureService;
        this.recordAuthorization = recordAuthorization;
    }

    /**
     * Performs a full-text search across all collections for the current tenant.
     *
     * @param tenantId the tenant ID from the gateway's X-Tenant-ID header
     * @param q        the search query string (minimum 3 characters)
     * @param limit    maximum number of results (default 20, max 100)
     * @return the readable search hits with collection info and display values
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String memberId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {

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
        List<Map<String, Object>> data = new ArrayList<>();
        for (int offset = 0; offset < MAX_SCANNED && data.size() < effectiveLimit; offset += FETCH_SIZE) {
            List<Map<String, Object>> rows = searchIndexService.search(
                    tenantId, q.trim(), Math.min(FETCH_SIZE, MAX_SCANNED - offset), offset);
            List<Map<String, Object>> hits = rows.stream().map(SearchController::toHit).toList();
            for (Map<String, Object> hit : recordAuthorization.readableSearchHits(request, hits)) {
                if (data.size() == effectiveLimit) {
                    break;
                }
                data.add(hit);
            }
            if (rows.size() < FETCH_SIZE) {
                break;
            }
        }

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

    private static Map<String, Object> toHit(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("record_id"));
        item.put("type", row.get("collection_name"));
        item.put("collectionName", row.get("collection_name"));
        item.put("collectionId", row.get("collection_id"));
        item.put("displayValue", row.get("display_value"));
        item.put("rank", row.get("rank"));
        return item;
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
