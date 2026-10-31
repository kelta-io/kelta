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
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The router's request-level tenant scoping for <em>tenant-scoped system collections</em>, keyed on
 * the gateway-forwarded {@code X-Tenant-ID} header: the list filter, the get-by-id visibility check,
 * the post-query narrowing of shared platform rows, and the {@code tenantId} stamp on create — plus
 * the sub-resource parent check ({@link #belongsToParent}) and, on every collection, the refusal
 * of a body {@code tenantId} naming another tenant ({@link #rejectForeignTenantId}).
 *
 * <p>This is a second line behind Postgres RLS (which is a no-op for superuser DB roles — local
 * docker-compose, the test harness); user collections are scoped by RLS alone. It reads the
 * header rather than {@code TenantContext}, exactly as the router always has; the static
 * {@link #injectTenantId} takes the tenant id from its caller.
 */
final class RecordTenantScope {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
     * Get-by-id counterpart of {@link #injectTenantFilter} plus {@link #filterSharedSystemRows}:
     * a tenant-scoped system record is visible only to its owning tenant, and a platform-tenant
     * row of a collection {@link SystemCollectionTenancy#sharesSystemRows} names is visible to
     * other tenants only when the list path would show it too — a genuine system collection, or a
     * field of one. Non-tenant-scoped definitions, requests without a tenant header and rows
     * without a {@code tenantId} pass through, matching the list path.
     *
     * <p>Used by the router's get-by-id and, through {@link #findChild}, by the sub-resource
     * routes before they read or write a child record.
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
        if (tenantId.equals(ownerId)) {
            return true;
        }
        if (!SystemCollectionTenancy.sharesSystemRows(definition)
                || !SystemCollectionDefinitions.SYSTEM_TENANT_ID.equals(ownerId)) {
            return false;
        }
        return !filterSharedSystemRows(definition, List.of(record), request).isEmpty();
    }

    /**
     * Loads the child record a sub-resource route ({@code /api/{parent}/{parentId}/{child}/{childId}})
     * names, as that route may see it: empty when the record does not exist, is not visible to the
     * caller's tenant ({@link #visibleToTenant}, as on a top-level get-by-id), or belongs to a
     * different parent ({@link #belongsToParent}). The three cases are indistinguishable to the
     * caller, so every one answers like a missing child.
     *
     * <p>The get, update and delete sub-resource handlers call this before they read or write.
     */
    Optional<Map<String, Object>> findChild(CollectionDefinition childDef, String parentRefField,
                                            String parentId, String childId, HttpServletRequest request) {
        Optional<Map<String, Object>> child = queryEngine.getById(childDef, childId);
        if (child.isEmpty()
                || !visibleToTenant(child.get(), childDef, request)
                || !belongsToParent(child.get(), parentRefField, parentId)) {
            return Optional.empty();
        }
        return child;
    }

    /**
     * Sub-resource check: whether {@code child} hangs off the parent the URL names, i.e. its
     * reference field to the parent ({@code parentRefField}) holds {@code parentId}. The same
     * predicate {@code listChildren} filters on, applied to a single loaded record, so a child is
     * reachable by id under exactly the parent it is listed under. UUIDs compare
     * case-insensitively; any other id compares exactly. A missing reference never matches.
     */
    static boolean belongsToParent(Map<String, Object> child, String parentRefField, String parentId) {
        Object link = child.get(parentRefField);
        if (link == null || parentId == null) {
            return false;
        }
        String linkId = String.valueOf(link);
        if (linkId.equals(parentId)) {
            return true;
        }
        return UUID_PATTERN.matcher(linkId).matches() && linkId.equalsIgnoreCase(parentId);
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
     * Injects {@code tenantId} into record data for tenant-scoped system collections. A no-op for
     * user collections, non-tenant-scoped system collections and a blank {@code tenantId} (an
     * unbound platform path). Callers refuse a caller-supplied value naming another tenant first
     * ({@link #rejectForeignTenantId} / {@link #foreignTenantId}), so a value already present
     * here is the request's own and is kept.
     *
     * <p>Takes the tenant id rather than the request so every create path shares it: the router
     * passes the gateway-forwarded {@code X-Tenant-ID}, {@link AtomicOperationExecutor} the bound
     * {@code TenantContext}. A request-origin stamp such as geo could take the same shape.
     *
     * @param data the record data
     * @param definition the collection definition
     * @param tenantId the request's tenant id, or null/blank when none is bound
     */
    static void injectTenantId(Map<String, Object> data, CollectionDefinition definition, String tenantId) {
        if (!definition.systemCollection() || !definition.tenantScoped() || !isBound(tenantId)) {
            return;
        }
        data.putIfAbsent("tenantId", tenantId);
    }

    /**
     * The caller-supplied {@code tenantId} in {@code data} when it names a tenant other than
     * {@code tenantId}; empty when it matches, is absent, or no tenant is bound. Applies to every
     * collection, not only the ones {@link #injectTenantId} stamps: on a tenant-scoped system
     * collection {@code tenantId} is the stored owner column, on any other collection it names
     * nothing the write stores, and on both the write's hooks and events run for the request's
     * tenant, so a different value is refused rather than ignored.
     */
    static Optional<String> foreignTenantId(Map<String, Object> data, String tenantId) {
        Object supplied = data.get("tenantId");
        if (!isBound(tenantId) || supplied == null) {
            return Optional.empty();
        }
        String value = String.valueOf(supplied);
        return tenantId.equals(value) ? Optional.empty() : Optional.of(value);
    }

    /**
     * Router form of {@link #foreignTenantId}: throws the {@code systemMaintained} validation
     * error on {@code tenantId} ({@link FieldError#foreignTenantId}) — the same error
     * {@code DefaultQueryEngine} raises for a payload naming another tenant than the bound one —
     * so the request gets the {@link GlobalExceptionHandler} 400 and nothing is written.
     */
    static void rejectForeignTenantId(Map<String, Object> data, CollectionDefinition definition,
                                      String tenantId) {
        if (foreignTenantId(data, tenantId).isPresent()) {
            throw new ValidationException(ValidationResult.failure(
                    List.of(FieldError.foreignTenantId(definition.name()))));
        }
    }

    private static boolean isBound(String tenantId) {
        return tenantId != null && !tenantId.isBlank();
    }
}
