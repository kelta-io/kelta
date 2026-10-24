package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.util.TenantContextUtils;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing the full-text search index.
 *
 * <p>Builds search content from record data using the collection's searchable fields
 * and display field, then stores it in the {@code search_index} table with a PostgreSQL
 * {@code tsvector} for fast full-text search.
 *
 * @since 1.0.0
 */
@Service
public class SearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    private static final String UPSERT_SEARCH_INDEX = """
            INSERT INTO search_index (id, tenant_id, collection_id, collection_name,
                                      record_id, display_value, search_content, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (tenant_id, collection_name, record_id)
            DO UPDATE SET display_value = EXCLUDED.display_value,
                          search_content = EXCLUDED.search_content,
                          updated_at = NOW()
            """;

    private static final String DELETE_SEARCH_INDEX = """
            DELETE FROM search_index
            WHERE tenant_id = ? AND collection_name = ? AND record_id = ?
            """;

    private static final String DELETE_COLLECTION_INDEX = """
            DELETE FROM search_index
            WHERE tenant_id = ? AND collection_name = ?
            """;

    private static final String COUNT_SEARCH_INDEX = """
            SELECT COUNT(*) FROM search_index si
            WHERE si.tenant_id = ?
              AND EXISTS (SELECT 1 FROM collection c
                          WHERE c.name = si.collection_name
                            AND c.active = true AND c.system_collection = false)
            """;

    private static final String STATS_BY_COLLECTION = """
            SELECT si.collection_name, si.collection_id, COUNT(*) AS indexed_count
            FROM search_index si
            WHERE si.tenant_id = ?
              AND EXISTS (SELECT 1 FROM collection c
                          WHERE c.name = si.collection_name
                            AND c.active = true AND c.system_collection = false)
            GROUP BY si.collection_name, si.collection_id
            ORDER BY si.collection_name
            """;

    private static final String DELETE_SYSTEM_COLLECTION_INDEX = """
            DELETE FROM search_index si
            WHERE si.tenant_id = ?
              AND EXISTS (SELECT 1 FROM collection c
                          WHERE c.name = si.collection_name
                            AND c.system_collection = true)
            """;

    private static final String SELECT_USER_COLLECTIONS = """
            SELECT id, name FROM collection
            WHERE tenant_id = ? AND active = true AND system_collection = false
            ORDER BY name
            """;

    private static final String SELECT_TENANT_SLUG = """
            SELECT slug FROM tenant WHERE id = ?
            """;

    private static final String SEARCH_QUERY = """
            SELECT collection_name, collection_id, record_id, display_value,
                   ts_rank(search_vector, to_tsquery('simple', ?)) AS rank
            FROM search_index
            WHERE tenant_id = ?
              AND search_vector @@ to_tsquery('simple', ?)
            ORDER BY rank DESC, collection_name, record_id
            LIMIT ? OFFSET ?
            """;

    /** Fallback display field names when no display field is configured. */
    private static final List<String> DISPLAY_FIELD_FALLBACKS = List.of(
            "name", "title", "displayName", "display_name", "label", "subject");

    /** Batch size for paginated index rebuild queries. 500 balances memory use and DB round-trips. */
    private static final int REBUILD_INDEX_PAGE_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final CollectionLifecycleManager lifecycleManager;
    private final CollectionRegistry collectionRegistry;
    private final StorageAdapter storageAdapter;
    private final FieldMaskingService fieldMaskingService;

    public SearchIndexService(JdbcTemplate jdbcTemplate,
                               CollectionLifecycleManager lifecycleManager,
                               CollectionRegistry collectionRegistry,
                               StorageAdapter storageAdapter,
                               FieldMaskingService fieldMaskingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleManager = lifecycleManager;
        this.collectionRegistry = collectionRegistry;
        this.storageAdapter = storageAdapter;
        this.fieldMaskingService = fieldMaskingService;
    }

    /**
     * Field names of the collection that carry a data-masking config. These are
     * excluded from the full-text index entirely — the search index is shared
     * across all viewers, so a masking-configured field's plaintext must never
     * enter {@code search_content} or {@code display_value} where a
     * {@code tsvector} match (or the returned display string) would leak it.
     * Per-viewer unmask rights don't apply here: index once, for nobody. Server-only fields
     * (encrypted secrets, token hashes) are excluded the same way.
     */
    private Set<String> maskingConfiguredFields(String collectionName) {
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null || definition.fields() == null) {
            return Set.of();
        }
        Set<String> masked = new HashSet<>();
        for (io.kelta.runtime.model.FieldDefinition field : definition.fields()) {
            if (field.serverOnly() || fieldMaskingService.configFor(field).isPresent()) {
                masked.add(field.name());
            }
        }
        return masked;
    }

    /**
     * Builds the concatenated search content string from a record's searchable field values.
     * Always includes the display field value regardless of the searchable flag.
     *
     * @param collectionName the collection name
     * @param recordData     the record data map
     * @return concatenated search content string
     */
    public String buildSearchContent(String collectionName, Map<String, Object> recordData) {
        Set<String> searchableFields = lifecycleManager.getSearchableFieldNames(collectionName);
        String displayFieldName = lifecycleManager.getDisplayFieldName(collectionName);
        Set<String> maskedFields = maskingConfiguredFields(collectionName);

        StringBuilder content = new StringBuilder();

        // Always include display field — unless it carries a masking config, whose
        // plaintext must not enter the shared full-text index.
        if (displayFieldName != null && !maskedFields.contains(displayFieldName)) {
            appendFieldValue(content, recordData.get(displayFieldName));
        }

        // Include all searchable fields (skip display field to avoid duplication;
        // skip masking-configured fields so their plaintext is never indexed)
        for (String fieldName : searchableFields) {
            if (!fieldName.equals(displayFieldName) && !maskedFields.contains(fieldName)) {
                appendFieldValue(content, recordData.get(fieldName));
            }
        }

        // If no display field configured, include fallback display fields
        if (displayFieldName == null) {
            for (String fallback : DISPLAY_FIELD_FALLBACKS) {
                Object val = recordData.get(fallback);
                if (val != null && !searchableFields.contains(fallback) && !maskedFields.contains(fallback)) {
                    appendFieldValue(content, val);
                    break;
                }
            }
        }

        return content.toString().trim();
    }

    /**
     * Extracts the display value from a record.
     *
     * @param collectionName the collection name
     * @param recordData     the record data map
     * @return the display value string
     */
    public String extractDisplayValue(String collectionName, Map<String, Object> recordData) {
        String displayFieldName = lifecycleManager.getDisplayFieldName(collectionName);
        Set<String> maskedFields = maskingConfiguredFields(collectionName);
        if (displayFieldName != null && !maskedFields.contains(displayFieldName)) {
            Object val = recordData.get(displayFieldName);
            if (val != null) {
                return String.valueOf(val);
            }
        }

        // Fallback chain (skip masking-configured fields — display_value is returned
        // to every searcher, so a masked field's plaintext must not surface here)
        for (String fallback : DISPLAY_FIELD_FALLBACKS) {
            if (maskedFields.contains(fallback)) {
                continue;
            }
            Object val = recordData.get(fallback);
            if (val != null) {
                return String.valueOf(val);
            }
        }

        // Last resort: use record ID
        Object id = recordData.get("id");
        return id != null ? String.valueOf(id) : "Unknown";
    }

    /**
     * Upserts a record into the search index.
     *
     * @param tenantId       the tenant ID
     * @param collectionId   the collection ID
     * @param collectionName the collection name
     * @param recordId       the record ID
     * @param recordData     the record data map
     */
    public void indexRecord(String tenantId, String collectionId, String collectionName,
                            String recordId, Map<String, Object> recordData) {
        try {
            String searchContent = buildSearchContent(collectionName, recordData);
            String displayValue = extractDisplayValue(collectionName, recordData);

            // Truncate display value to fit column
            if (displayValue != null && displayValue.length() > 500) {
                displayValue = displayValue.substring(0, 497) + "...";
            }

            jdbcTemplate.update(UPSERT_SEARCH_INDEX,
                    UUID.randomUUID().toString(),
                    tenantId, collectionId, collectionName,
                    recordId, displayValue, searchContent);

            log.debug("Indexed record {}/{} for search", collectionName, recordId);
        } catch (Exception e) {
            log.error("Failed to index record {}/{}: {}", collectionName, recordId, e.getMessage());
        }
    }

    /**
     * Removes a record from the search index.
     *
     * @param tenantId       the tenant ID
     * @param collectionName the collection name
     * @param recordId       the record ID
     */
    public void removeRecord(String tenantId, String collectionName, String recordId) {
        try {
            jdbcTemplate.update(DELETE_SEARCH_INDEX, tenantId, collectionName, recordId);
            log.debug("Removed record {}/{} from search index", collectionName, recordId);
        } catch (Exception e) {
            log.error("Failed to remove record {}/{} from search index: {}",
                    collectionName, recordId, e.getMessage());
        }
    }

    /**
     * Performs full-text search across all collections for a tenant.
     * Uses PostgreSQL tsquery with prefix matching for partial word support.
     * Rows are ordered by rank with a stable tiebreak, so consecutive offsets page the index
     * without gaps or repeats. Rows are not authorized here: the caller filters them per
     * collection and record.
     *
     * @param tenantId the tenant ID
     * @param query    the search query string
     * @param pageSize maximum number of results to return
     * @param offset   number of leading results to skip
     * @return list of search result maps
     */
    public List<Map<String, Object>> search(String tenantId, String query, int pageSize, int offset) {
        String tsQuery = buildTsQuery(query);
        if (tsQuery == null || tsQuery.isBlank()) {
            return List.of();
        }

        try {
            return jdbcTemplate.queryForList(SEARCH_QUERY, tsQuery, tenantId, tsQuery, pageSize, offset);
        } catch (Exception e) {
            log.error("Full-text search failed for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Rebuilds the search index for all records in a given collection.
     * Called asynchronously when searchable fields change.
     *
     * @param tenantId       the tenant ID
     * @param collectionName the collection name
     */
    @Async
    public void rebuildCollectionIndexAsync(String tenantId, String collectionName) {
        log.info("Rebuilding search index for collection '{}' (tenant={})", collectionName, tenantId);
        try {
            String slug = TenantContext.callWithTenant(tenantId, () -> resolveTenantSlug(tenantId, null));
            TenantContextUtils.withTenant(tenantId, slug, () -> {
                String collectionId = lifecycleManager.getCollectionIdByName(collectionName);
                if (collectionId == null) {
                    log.warn("Cannot rebuild index: collection '{}' not found", collectionName);
                    return;
                }

                CollectionDefinition definition = collectionRegistry.get(collectionName);
                if (definition == null) {
                    log.warn("Cannot rebuild index: collection '{}' not registered", collectionName);
                    return;
                }

                // Delete existing index entries for this collection
                jdbcTemplate.update(DELETE_COLLECTION_INDEX, tenantId, collectionName);

                // Re-index all records in batches
                int page = 1;
                final int pageSize = REBUILD_INDEX_PAGE_SIZE;
                int totalIndexed = 0;

                while (true) {
                    QueryRequest request = new QueryRequest(
                            new io.kelta.runtime.query.Pagination(page, pageSize),
                            List.of(), List.of(), List.of());

                    QueryResult result = storageAdapter.query(definition, request);
                    List<Map<String, Object>> records = result.data();

                    if (records == null || records.isEmpty()) {
                        break;
                    }

                    for (Map<String, Object> record : records) {
                        String recordId = String.valueOf(record.get("id"));
                        indexRecord(tenantId, collectionId, collectionName, recordId, record);
                        totalIndexed++;
                    }

                    if (records.size() < pageSize) {
                        break;
                    }
                    page++;
                }

                log.info("Search index rebuild complete for collection '{}': {} records indexed",
                        collectionName, totalIndexed);
            });
        } catch (Exception e) {
            log.error("Failed to rebuild search index for collection '{}': {}",
                    collectionName, e.getMessage(), e);
        }
    }

    /**
     * Returns search index statistics for a tenant, including per-collection counts.
     *
     * @param tenantId the tenant ID
     * @return map containing totalIndexed and collections list
     */
    public Map<String, Object> getSearchIndexStats(String tenantId) throws Exception {
        return TenantContextUtils.withTenant(tenantId, () -> {
            Long totalIndexed = jdbcTemplate.queryForObject(COUNT_SEARCH_INDEX, Long.class, tenantId);
            List<Map<String, Object>> collectionStats = jdbcTemplate.queryForList(STATS_BY_COLLECTION, tenantId);
            List<Map<String, Object>> allCollections = jdbcTemplate.queryForList(SELECT_USER_COLLECTIONS, tenantId);

            List<Map<String, Object>> collections = new ArrayList<>();
            Map<String, Map<String, Object>> statsMap = new HashMap<>();
            for (Map<String, Object> row : collectionStats) {
                statsMap.put((String) row.get("collection_name"), row);
            }

            for (Map<String, Object> col : allCollections) {
                Map<String, Object> entry = new LinkedHashMap<>();
                String name = (String) col.get("name");
                entry.put("collectionId", col.get("id"));
                entry.put("collectionName", name);
                Map<String, Object> stat = statsMap.get(name);
                entry.put("indexedRecords", stat != null ? ((Number) stat.get("indexed_count")).longValue() : 0L);
                collections.add(entry);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalIndexed", totalIndexed != null ? totalIndexed : 0L);
            result.put("collections", collections);
            return result;
        });
    }

    /**
     * Rebuilds the search index for all non-system collections in a tenant.
     * Runs asynchronously. Returns immediately.
     *
     * @param tenantId       the tenant ID
     * @param tenantSlug     the tenant slug (required for schema-qualified queries)
     * @param collectionName optional collection name to rebuild; if null, rebuilds all
     */
    @Async
    public void rebuildAllCollectionsAsync(String tenantId, String tenantSlug, String collectionName) {
        try {
            String slug = TenantContext.callWithTenant(tenantId,
                    () -> resolveTenantSlug(tenantId, tenantSlug));
            TenantContextUtils.withTenant(tenantId, slug, () -> {
                if (collectionName != null && !collectionName.isBlank()) {
                    rebuildCollectionIndexSync(tenantId, collectionName);
                    return;
                }

                log.info("Rebuilding search index for ALL collections (tenant={})", tenantId);
                // Clean up any stale system collection records that were indexed before the filter was fixed
                int purged = jdbcTemplate.update(DELETE_SYSTEM_COLLECTION_INDEX, tenantId);
                if (purged > 0) {
                    log.info("Purged {} stale system collection records from search index", purged);
                }

                List<Map<String, Object>> collections = jdbcTemplate.queryForList(SELECT_USER_COLLECTIONS, tenantId);
                log.info("Found {} user collections to reindex for tenant {}", collections.size(), tenantId);

                for (Map<String, Object> col : collections) {
                    String name = (String) col.get("name");
                    try {
                        rebuildCollectionIndexSync(tenantId, name);
                    } catch (Exception e) {
                        log.error("Failed to reindex collection '{}': {}", name, e.getMessage(), e);
                    }
                }

                log.info("Search index rebuild complete for ALL collections (tenant={})", tenantId);
            });
        } catch (Exception e) {
            log.error("Failed to rebuild search index for all collections: {}", e.getMessage(), e);
        }
    }

    /**
     * Synchronous version of collection reindex (for use within rebuildAllCollectionsAsync).
     */
    private void rebuildCollectionIndexSync(String tenantId, String collectionName) {
        log.info("Rebuilding search index for collection '{}' (tenant={})", collectionName, tenantId);

        String collectionId = lifecycleManager.getCollectionIdByName(collectionName);
        if (collectionId == null) {
            log.warn("Cannot rebuild index: collection '{}' not found", collectionName);
            return;
        }

        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            log.warn("Cannot rebuild index: collection '{}' not registered", collectionName);
            return;
        }

        // Delete existing index entries for this collection
        jdbcTemplate.update(DELETE_COLLECTION_INDEX, tenantId, collectionName);

        // Re-index all records in batches
        int page = 1;
        int pageSize = REBUILD_INDEX_PAGE_SIZE;
        int totalIndexed = 0;

        while (true) {
            QueryRequest request = new QueryRequest(
                    new io.kelta.runtime.query.Pagination(page, pageSize),
                    List.of(), List.of(), List.of());

            QueryResult result = storageAdapter.query(definition, request);
            List<Map<String, Object>> records = result.data();

            if (records == null || records.isEmpty()) {
                break;
            }

            for (Map<String, Object> record : records) {
                String recordId = String.valueOf(record.get("id"));
                indexRecord(tenantId, collectionId, collectionName, recordId, record);
                totalIndexed++;
            }

            if (records.size() < pageSize) {
                break;
            }
            page++;
        }

        log.info("Search index rebuild complete for collection '{}': {} records indexed",
                collectionName, totalIndexed);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Converts a user search query into a PostgreSQL tsquery string with prefix matching.
     * Example: "john smith" → "john:* & smith:*"
     */
    String buildTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        String result = Arrays.stream(query.trim().split("\\s+"))
                .filter(w -> !w.isBlank())
                .map(w -> w.replaceAll("[^a-zA-Z0-9@._-]", ""))
                .filter(w -> !w.isEmpty())
                .map(w -> w + ":*")
                .collect(Collectors.joining(" & "));

        return result.isBlank() ? null : result;
    }

    private void appendFieldValue(StringBuilder builder, Object value) {
        if (value == null) {
            return;
        }
        String str = String.valueOf(value);
        if (!str.isBlank() && !"null".equals(str)) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(str);
        }
    }

    /**
     * Resolves the tenant slug the rebuild binds for schema-qualified queries via
     * StorageAdapter: a slug already bound in scope wins, then the caller-supplied slug, then a
     * lookup by tenant ID. Returns {@code null} when none resolves.
     */
    private String resolveTenantSlug(String tenantId, String suppliedSlug) {
        String bound = TenantContext.getSlug();
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        if (suppliedSlug != null && !suppliedSlug.isBlank()) {
            return suppliedSlug;
        }
        try {
            String slug = jdbcTemplate.queryForObject(SELECT_TENANT_SLUG, String.class, tenantId);
            if (slug == null) {
                log.warn("Could not resolve slug for tenant {}", tenantId);
            }
            return slug;
        } catch (Exception e) {
            log.warn("Failed to resolve slug for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
