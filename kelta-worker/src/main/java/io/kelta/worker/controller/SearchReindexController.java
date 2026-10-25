package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.worker.service.SearchIndexService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin controller for managing the full-text search index.
 *
 * <p>Provides endpoints to view search index statistics and trigger
 * a full reindex of all (or specific) collections.
 */
@RestController
@RequestMapping("/api/admin/search-reindex")
public class SearchReindexController {

    private static final String PERMISSION = "CUSTOMIZE_APPLICATION";

    private static final Logger log = LoggerFactory.getLogger(SearchReindexController.class);

    private final SearchIndexService searchIndexService;
    private final AdminPermissionGuard permissionGuard;

    public SearchReindexController(SearchIndexService searchIndexService, AdminPermissionGuard permissionGuard) {
        this.searchIndexService = searchIndexService;
        this.permissionGuard = permissionGuard;
    }

    /**
     * Returns search index statistics for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStats(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        permissionGuard.require(request, PERMISSION);
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request", "Missing X-Tenant-ID header"));
            }

            Map<String, Object> stats = searchIndexService.getSearchIndexStats(tenantId);

            return ResponseEntity.ok(JsonApiResponseBuilder.single("search-index-stats", tenantId, stats));
        } catch (Exception e) {
            log.error("Failed to get search index stats: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to get search index statistics"));
        }
    }

    /**
     * Triggers a search index rebuild for all collections or a specific collection.
     * The rebuild runs asynchronously; this endpoint returns immediately.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerReindex(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Tenant-Slug", required = false) String tenantSlug,
            @RequestBody(required = false) Map<String, Object> body) {
        permissionGuard.require(request, PERMISSION);
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request", "Missing X-Tenant-ID header"));
            }

            String collectionName = null;
            if (body != null && body.get("collectionName") != null) {
                collectionName = String.valueOf(body.get("collectionName"));
            }

            log.info("Search reindex triggered for tenant={}, slug={}, collection={}",
                    tenantId, tenantSlug, collectionName != null ? collectionName : "ALL");

            searchIndexService.rebuildAllCollectionsAsync(tenantId, tenantSlug, collectionName);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("status", "STARTED");
            attributes.put("collection", collectionName != null ? collectionName : "ALL");
            attributes.put("message", collectionName != null
                    ? "Reindex started for collection '" + collectionName + "'"
                    : "Reindex started for all collections");

            return ResponseEntity.accepted().body(
                    JsonApiResponseBuilder.single("search-reindex-job", tenantId, attributes));
        } catch (Exception e) {
            log.error("Failed to trigger search reindex: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error",
                            "Failed to trigger search reindex"));
        }
    }
}
