package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.model.system.SystemCollectionTenancy;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The router's request-level tenant scoping for <em>tenant-scoped system collections</em>, keyed on
 * the gateway-forwarded {@code X-Tenant-ID} header: the list filter, the get-by-id visibility check,
 * the post-query narrowing of shared platform rows, and the {@code tenantId} stamp on create.
 *
 * <p>This is a second line behind Postgres RLS (which is a no-op for superuser DB roles — local
 * docker-compose, the test harness); user collections are scoped by RLS alone. It reads the
 * header rather than {@code TenantContext}, exactly as the router always has.
 */
final class RecordTenantScope {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    private final CollectionRegistry registry;
    private final QueryEngine queryEngine;

    RecordTenantScope(CollectionRegistry registry, QueryEngine queryEngine) {
        this.registry = registry;
        this.queryEngine = queryEngine;
    }

    /**
     * Injects a tenant_id filter for tenant-scoped system collections.
     * This ensures list queries only return records for the current tenant.
     *
     * @param queryRequest the original query request
     * @param definition the collection definition
     * @param request the HTTP servlet request (used to extract tenant ID)
     * @return the query request with tenant filter injected, or the original if not applicable
     */
    QueryRequest injectTenantFilter(QueryRequest queryRequest, CollectionDefinition definition,
                                    HttpServletRequest request) {
        if (!definition.systemCollection() || !definition.tenantScoped()) {
            return queryRequest;
        }

        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            logger.warn("No tenant ID found for tenant-scoped system collection '{}'", definition.name());
            return queryRequest;
        }

        // For the 'collections' and 'fields' collections, include both tenant-specific
        // and system records so that system collections (and their fields) are visible
        // alongside custom ones.
        List<FilterCondition> filters = new ArrayList<>(queryRequest.filters());
        if (SystemCollectionTenancy.sharesSystemRows(definition)) {
            filters.add(new FilterCondition("tenantId", FilterOperator.IN,
                    List.of(tenantId, SystemCollectionDefinitions.SYSTEM_TENANT_ID)));
        } else {
            filters.add(new FilterCondition("tenantId", FilterOperator.EQ, tenantId));
        }
        return queryRequest.withFilters(filters);
    }

    /**
     * Get-by-id counterpart of {@link #injectTenantFilter}: a tenant-scoped system record
     * is visible only to its owning tenant (plus the platform's own rows for the collections
     * {@link SystemCollectionTenancy#sharesSystemRows} names). Non-tenant-scoped definitions,
     * requests without a tenant header and rows without a {@code tenantId} pass through, matching
     * the list path.
     */
    boolean visibleToTenant(Map<String, Object> record, CollectionDefinition definition,
                            HttpServletRequest request) {
        if (!definition.systemCollection() || !definition.tenantScoped()) {
            return true;
        }
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            return true;
        }
        Object owner = record.get("tenantId");
        if (owner == null) {
            return true;
        }
        String ownerId = String.valueOf(owner);
        return tenantId.equals(ownerId)
                || (SystemCollectionTenancy.sharesSystemRows(definition)
                        && SystemCollectionDefinitions.SYSTEM_TENANT_ID.equals(ownerId));
    }

    /**
     * List-query counterpart of the SYSTEM_TENANT_ID leak fixed here: {@link #injectTenantFilter}
     * can only express {@code tenantId IN (caller, SYSTEM_TENANT_ID)} — the generic filter
     * grammar ({@link FilterCondition}/{@link FilterOperator}) is AND-only and has no OR, so it
     * cannot also require "and that SYSTEM_TENANT_ID row is actually a system collection" in the
     * same query. This narrows the SQL-filtered result afterward instead: a custom collection (or
     * one of its fields) created directly in the platform tenant must not leak to every tenant's
     * list (kelta-io/kelta#1536).
     */
    QueryResult restrictSharedSystemRows(CollectionDefinition definition, QueryResult result,
                                         HttpServletRequest request) {
        if (!SystemCollectionTenancy.sharesSystemRows(definition) || result.data().isEmpty()) {
            return result;
        }
        List<Map<String, Object>> filtered = filterSharedSystemRows(definition, result.data(), request);
        if (filtered.size() == result.data().size()) {
            return result;
        }
        PaginationMetadata meta = result.metadata();
        long newTotal = Math.max(0, meta.totalCount() - (result.data().size() - filtered.size()));
        int newTotalPages = (int) Math.ceil((double) newTotal / meta.pageSize());
        return new QueryResult(filtered,
                new PaginationMetadata(newTotal, meta.currentPage(), meta.pageSize(), newTotalPages));
    }

    /**
     * Drops SYSTEM_TENANT_ID rows from {@code rows} that don't actually belong to a system
     * collection. Rows owned by the caller's own tenant are never touched. See
     * {@link #restrictSharedSystemRows} for why this can't be pushed into the SQL filter.
     *
     * <p>No-ops when the request carries no {@code X-Tenant-ID}, matching {@link #visibleToTenant}
     * and {@link #injectTenantFilter}: an unbound read is a platform/internal read (Flyway,
     * bootstrap, scheduler sweeps) that is deliberately left unscoped, not a tenant's list view.
     */
    List<Map<String, Object>> filterSharedSystemRows(CollectionDefinition definition,
                                                     List<Map<String, Object>> rows,
                                                     HttpServletRequest request) {
        if (!SystemCollectionTenancy.sharesSystemRows(definition) || rows.isEmpty()) {
            return rows;
        }
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            return rows;
        }
        // The platform tenant reading its own rows: every SYSTEM_TENANT_ID row is the caller's
        // own (custom collections created directly in that tenant included) — nothing to narrow.
        if (SystemCollectionDefinitions.SYSTEM_TENANT_ID.equals(tenantId)) {
            return rows;
        }
        boolean hasSystemTenantRow = rows.stream()
                .anyMatch(row -> SystemCollectionDefinitions.SYSTEM_TENANT_ID.equals(
                        String.valueOf(row.get("tenantId"))));
        if (!hasSystemTenantRow) {
            return rows;
        }
        if ("fields".equals(definition.name())) {
            Set<String> systemCollectionIds = fetchSystemCollectionIds();
            return rows.stream()
                    .filter(row -> !SystemCollectionDefinitions.SYSTEM_TENANT_ID.equals(
                                    String.valueOf(row.get("tenantId")))
                            || systemCollectionIds.contains(String.valueOf(row.get("collectionId"))))
                    .collect(Collectors.toList());
        }
        // "collections": the row itself carries systemCollection.
        return rows.stream()
                .filter(row -> !SystemCollectionDefinitions.SYSTEM_TENANT_ID.equals(
                                String.valueOf(row.get("tenantId")))
                        || Boolean.TRUE.equals(row.get("systemCollection")))
                .collect(Collectors.toList());
    }

    /**
     * IDs of the platform-tenant collections that are genuine system collections, for narrowing
     * shared {@code fields} rows in {@link #filterSharedSystemRows}.
     */
    private Set<String> fetchSystemCollectionIds() {
        CollectionDefinition collectionsDef = registry.get("collections");
        if (collectionsDef == null) {
            return Set.of();
        }
        List<FilterCondition> filters = List.of(
                new FilterCondition("tenantId", FilterOperator.EQ, SystemCollectionDefinitions.SYSTEM_TENANT_ID),
                new FilterCondition("systemCollection", FilterOperator.EQ, true));
        QueryRequest query = new QueryRequest(
                new Pagination(1, Pagination.MAX_PAGE_SIZE), List.of(), List.of("id"), filters);
        QueryResult result = queryEngine.executeQuery(collectionsDef, query);
        return result.data().stream()
                .map(row -> String.valueOf(row.get("id")))
                .collect(Collectors.toSet());
    }

    /**
     * Injects tenant ID into record data for tenant-scoped system collections, unless the caller
     * already supplied one.
     *
     * @param data the record data
     * @param definition the collection definition
     * @param request the HTTP servlet request (used to extract tenant ID)
     */
    void injectTenantId(Map<String, Object> data, CollectionDefinition definition,
                        HttpServletRequest request) {
        if (!definition.systemCollection() || !definition.tenantScoped()) {
            return;
        }

        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId != null && !tenantId.isBlank() && !data.containsKey("tenantId")) {
            data.put("tenantId", tenantId);
        }
    }
}
