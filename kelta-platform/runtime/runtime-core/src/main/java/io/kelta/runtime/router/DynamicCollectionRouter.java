package io.kelta.runtime.router;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionTenancy;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.RecordWritePipeline.WriteTarget;
import io.kelta.runtime.router.jsonapi.JsonApiRecordSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dynamic REST controller for collection CRUD operations.
 *
 * <p>This controller provides a unified API for all collections registered in the
 * {@link CollectionRegistry}. It dynamically routes requests based on the collection
 * name in the URL path.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/{collectionName} - List records with pagination, sorting, filtering</li>
 *   <li>GET /api/{collectionName}/{id} - Get a single record by ID</li>
 *   <li>POST /api/{collectionName} - Create a new record</li>
 *   <li>PUT /api/{collectionName}/{id} - Update an existing record</li>
 *   <li>DELETE /api/{collectionName}/{id} - Delete a record</li>
 * </ul>
 *
 * <p>Sub-resource endpoints (for parent-child relationships):
 * <ul>
 *   <li>GET /api/{parent}/{parentId}/{child} - List child records for a parent</li>
 *   <li>GET /api/{parent}/{parentId}/{child}/{childId} - Get a child record</li>
 *   <li>POST /api/{parent}/{parentId}/{child} - Create a child record</li>
 *   <li>PUT /api/{parent}/{parentId}/{child}/{childId} - Update a child record</li>
 *   <li>DELETE /api/{parent}/{parentId}/{child}/{childId} - Delete a child record</li>
 * </ul>
 *
 * <p>Query Parameters for list endpoint:
 * <ul>
 *   <li>page[number] - Page number (default: 1)</li>
 *   <li>page[size] - Page size (default: 20)</li>
 *   <li>sort - Comma-separated sort fields (prefix with - for descending)</li>
 *   <li>fields - Comma-separated field names to return</li>
 *   <li>filter[field][op] - Filter conditions</li>
 * </ul>
 *
 * <p>This class owns the HTTP surface — mappings, tenant binding, collection resolution and the
 * read paths with their system-collection caching. The rest is delegated, and every collaborator
 * logs under this class's logger:
 * <ul>
 *   <li>{@link RecordWritePipeline} — create/update/delete for top-level and sub-resource routes;</li>
 *   <li>{@link JsonApiIncludeResolver} — {@code ?include=};</li>
 *   <li>{@link RecordTenantScope} — {@code X-Tenant-ID} scoping of tenant-scoped system collections;</li>
 *   <li>{@link CollectionResolver} — registry + on-demand collection lookup;</li>
 *   <li>{@code router.jsonapi} — request-body reading and record → JSON:API document shaping.</li>
 * </ul>
 * The handler signatures and mappings are pinned by {@code DynamicCollectionRouterRequestMappingTest}
 * and the behaviour by {@code DynamicCollectionRouterGoldenTest}.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api")
public class DynamicCollectionRouter {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final CollectionRegistry registry;
    private final QueryEngine queryEngine;

    private final CollectionResolver collections;
    private final RecordTenantScope tenantScope;
    private final JsonApiIncludeResolver includes;
    private final RecordWritePipeline writePipeline;

    /**
     * Optional on-demand loader that fetches unknown collections from the
     * control plane. When present, the router will try to load a collection
     * before returning 404.
     */
    private CollectionOnDemandLoader onDemandLoader;

    /**
     * Optional resolver that translates user identifiers (e.g., email) to
     * platform_user UUIDs for audit fields (created_by, updated_by).
     */
    private UserIdResolver userIdResolver;

    /**
     * Optional cache for system collection query results. When present, list
     * and get-by-id operations on system collections check the cache first and
     * store results on cache miss. Write operations evict the cache for the
     * affected collection.
     */
    private SystemCollectionCache systemCollectionCache;

    /**
     * Creates a new DynamicCollectionRouter.
     *
     * @param registry the collection registry
     * @param queryEngine the query engine
     */
    public DynamicCollectionRouter(CollectionRegistry registry, QueryEngine queryEngine) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine cannot be null");
        // The optional collaborators arrive through setter injection after construction, so the
        // helpers read them through suppliers rather than capturing them here.
        this.collections = new CollectionResolver(this.registry, () -> this.onDemandLoader);
        this.tenantScope = new RecordTenantScope(this.registry, this.queryEngine);
        this.includes = new JsonApiIncludeResolver(this.queryEngine, this.collections, this.tenantScope);
        this.writePipeline = new RecordWritePipeline(this.queryEngine, this.tenantScope,
                () -> this.userIdResolver, () -> this.systemCollectionCache);
    }

    @Autowired(required = false)
    public void setOnDemandLoader(CollectionOnDemandLoader onDemandLoader) {
        this.onDemandLoader = onDemandLoader;
    }

    @Autowired(required = false)
    public void setUserIdResolver(UserIdResolver userIdResolver) {
        this.userIdResolver = userIdResolver;
    }

    @Autowired(required = false)
    public void setSystemCollectionCache(SystemCollectionCache systemCollectionCache) {
        this.systemCollectionCache = systemCollectionCache;
    }

    /**
     * Lists records from a collection with pagination, sorting, and filtering.
     *
     * @param collectionName the collection name
     * @param params query parameters for pagination, sorting, filtering, and field selection
     * @param request the HTTP servlet request
     * @return the query result or 404 if collection not found
     */
    @GetMapping("/{collectionName}")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable("collectionName") String collectionName,
            @RequestParam(required = false) MultiValueMap<String, String> params,
            HttpServletRequest request) {

        logger.debug("List request for collection '{}' with params: {}", collectionName, params);

        return inRequestTenantScope(request, () -> {
            CollectionDefinition definition = collections.resolve(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            QueryRequest queryRequest = QueryRequest.fromParams(params);

            // For tenant-scoped system collections, inject tenant_id filter
            queryRequest = tenantScope.injectTenantFilter(queryRequest, definition, request);

            // Check system collection cache before querying
            String tenantId = request.getHeader("X-Tenant-ID");
            List<String> includeNames = JsonApiIncludeResolver.parseIncludeParam(params);
            if (cacheable(definition) && includeNames.isEmpty()) {
                String queryHash = buildQueryHash(queryRequest);
                Optional<Map<String, Object>> cached = systemCollectionCache.getListResponse(
                        tenantId, collectionName, queryHash);
                if (cached.isPresent()) {
                    logger.debug("Cache hit for system collection list: {}", collectionName);
                    return ResponseEntity.ok(cached.get());
                }
            }

            QueryResult result = queryEngine.executeQuery(definition, queryRequest);
            result = tenantScope.restrictSharedSystemRows(definition, result, request);

            Map<String, Object> response = JsonApiRecordSerializer.listDocument(
                    result, collectionName, definition, request.getRequestURI(), params);

            // Resolve JSON:API ?include= parameter for inverse (has-many) relationships
            if (!includeNames.isEmpty() && !result.data().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> dataResources =
                        (List<Map<String, Object>>) response.get("data");
                List<Map<String, Object>> included = includes.resolve(
                        includeNames, result.data(), dataResources,
                        collectionName, definition, request);
                if (!included.isEmpty()) {
                    response.put("included", included);
                }
            }

            // Cache the response for system collections (only when no includes, to keep cache simple)
            if (cacheable(definition) && includeNames.isEmpty()) {
                String queryHash = buildQueryHash(queryRequest);
                systemCollectionCache.putListResponse(tenantId, collectionName, queryHash, response);
            }

            return ResponseEntity.ok(response);
        });
    }

    /**
     * Gets a single record by ID.
     *
     * @param collectionName the collection name
     * @param id the record ID
     * @return the record in JSON:API format or 404 if not found
     */
    @GetMapping("/{collectionName}/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable("collectionName") String collectionName,
            @PathVariable("id") String id,
            @RequestParam(required = false) MultiValueMap<String, String> params,
            HttpServletRequest request) {

        logger.debug("Get request for collection '{}', id '{}'", collectionName, id);

        return inRequestTenantScope(request, () -> {
            CollectionDefinition definition = collections.resolve(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            // Parse JSON:API ?include= up front so cache lookup can match the put guard:
            // the cache only ever stores no-include responses, so include-bearing requests
            // must skip the lookup or they'd receive a stale parent without included children.
            List<String> includeNames = JsonApiIncludeResolver.parseIncludeParam(params);

            // Check system collection cache for get-by-id (no-include requests only)
            String tenantId = request.getHeader("X-Tenant-ID");
            if (cacheable(definition) && includeNames.isEmpty()) {
                Optional<Map<String, Object>> cached = systemCollectionCache.getByIdResponse(
                        tenantId, collectionName, id);
                if (cached.isPresent()) {
                    logger.debug("Cache hit for system collection get: {}/{}", collectionName, id);
                    return ResponseEntity.ok(cached.get());
                }
            }

            Optional<Map<String, Object>> record = queryEngine.getById(definition, id);

            // If not found by ID and the value is not a UUID, try display field lookup
            if (record.isEmpty() && !UUID_PATTERN.matcher(id).matches()) {
                record = resolveByDisplayField(definition, id, request);
            }

            // Another tenant's row must read as absent, not as a 403 existence oracle. RLS
            // is the real boundary; this mirrors injectTenantFilter for get-by-id so the
            // answer is the same wherever RLS is a no-op (superuser DB roles: local
            // docker-compose, the test harness). It also applies the shared-system-row rule
            // the list path applies, before anything is cached under the caller's tenant.
            if (record.isEmpty() || !tenantScope.visibleToTenant(record.get(), definition, request)) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = JsonApiRecordSerializer.document(record.get(), collectionName, definition);

            // Resolve JSON:API ?include= parameter for inverse (has-many) relationships
            if (!includeNames.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataResource = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> included = includes.resolve(
                        includeNames, List.of(record.get()), List.of(dataResource),
                        collectionName, definition, request);
                if (!included.isEmpty()) {
                    response.put("included", included);
                }
            }

            // Cache the response for system collections (only when no includes)
            if (cacheable(definition) && includeNames.isEmpty()) {
                systemCollectionCache.putByIdResponse(tenantId, collectionName, id, response);
            }

            // Optimistic-locking version token (unified record experience, slice 5).
            String etag = RecordETag.of(record.get());
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (etag != null) {
                ok.header(HttpHeaders.ETAG, etag);
            }
            return ok.body(response);
        });
    }

    /**
     * Creates a new record in the collection.
     *
     * @param collectionName the collection name
     * @param requestBody the JSON:API formatted request body
     * @return the created record with 201 status, or 404 if collection not found
     */
    @PostMapping("/{collectionName}")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable("collectionName") String collectionName,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        logger.debug("Create request for collection '{}' with data: {}", collectionName, requestBody);

        return inRequestTenantScope(request, () -> {
            CollectionDefinition definition = collections.resolve(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }
            return writePipeline.create(WriteTarget.collection(definition, collectionName), requestBody, request);
        });
    }

    /**
     * Updates an existing record in the collection (PUT).
     *
     * @param collectionName the collection name
     * @param id the record ID
     * @param requestBody the JSON:API formatted request body with updated data
     * @return the updated record in JSON:API format, or 404 if collection or record not found
     */
    @PutMapping("/{collectionName}/{id}")
    public ResponseEntity<Map<String, Object>> updatePut(
            @PathVariable("collectionName") String collectionName,
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        return performUpdate(collectionName, id, requestBody, request);
    }

    /**
     * Updates an existing record in the collection (PATCH).
     *
     * @param collectionName the collection name
     * @param id the record ID
     * @param requestBody the JSON:API formatted request body with updated data
     * @return the updated record in JSON:API format, or 404 if collection or record not found
     */
    @PatchMapping("/{collectionName}/{id}")
    public ResponseEntity<Map<String, Object>> updatePatch(
            @PathVariable("collectionName") String collectionName,
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        return performUpdate(collectionName, id, requestBody, request);
    }

    /**
     * Performs the update operation (PUT and PATCH are the same partial merge).
     */
    private ResponseEntity<Map<String, Object>> performUpdate(
            String collectionName,
            String id,
            Map<String, Object> requestBody,
            HttpServletRequest request) {

        logger.debug("Update request for collection '{}', id '{}' with data: {}", collectionName, id, requestBody);

        return inRequestTenantScope(request, () -> {
            CollectionDefinition definition = collections.resolve(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }
            return writePipeline.update(WriteTarget.collection(definition, collectionName), id, requestBody, request);
        });
    }

    /**
     * Deletes a record from the collection.
     *
     * @param collectionName the collection name
     * @param id the record ID
     * @return 204 No Content if deleted, 404 if collection or record not found
     */
    @DeleteMapping("/{collectionName}/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("collectionName") String collectionName,
            @PathVariable("id") String id,
            HttpServletRequest request) {

        logger.debug("Delete request for collection '{}', id '{}'", collectionName, id);

        return inRequestTenantScope(request, () -> {
            CollectionDefinition definition = collections.resolve(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }
            return writePipeline.delete(WriteTarget.collection(definition, collectionName), id, request);
        });
    }

    // ==================== Sub-Resource Endpoints ====================

    /**
     * Lists child records under a parent resource.
     *
     * <p>Resolves the relationship between parent and child collections,
     * then filters child records by the parent's ID.
     *
     * @param parentName the parent collection name
     * @param parentId the parent record ID
     * @param childName the child collection name
     * @param params query parameters for pagination, sorting, filtering
     * @param request the HTTP servlet request
     * @return child records filtered by parent, or 404 if collections/relationship not found
     */
    @GetMapping("/{parentName}/{parentId}/{childName}")
    public ResponseEntity<Map<String, Object>> listChildren(
            @PathVariable("parentName") String parentName,
            @PathVariable("parentId") String parentId,
            @PathVariable("childName") String childName,
            @RequestParam(required = false) MultiValueMap<String, String> params,
            HttpServletRequest request) {

        logger.debug("List children request: parent='{}', parentId='{}', child='{}'",
                parentName, parentId, childName);

        return inRequestTenantScope(request, () -> {
            SubResourceRelation relation = collections.resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            QueryRequest queryRequest = QueryRequest.fromParams(params);

            // Inject parent ID filter on the child's reference field
            List<FilterCondition> filters = new ArrayList<>(queryRequest.filters());
            filters.add(new FilterCondition(relation.parentRefFieldName(), FilterOperator.EQ, parentId));
            queryRequest = queryRequest.withFilters(filters);

            // Inject tenant filter if applicable
            queryRequest = tenantScope.injectTenantFilter(queryRequest, relation.childDef(), request);

            QueryResult result = queryEngine.executeQuery(relation.childDef(), queryRequest);
            result = tenantScope.restrictSharedSystemRows(relation.childDef(), result, request);

            return ResponseEntity.ok(JsonApiRecordSerializer.listDocument(
                    result, childName, relation.childDef(), request.getRequestURI(), params));
        });
    }

    /**
     * Gets a single child record under a parent resource.
     *
     * @param parentName the parent collection name
     * @param parentId the parent record ID
     * @param childName the child collection name
     * @param childId the child record ID
     * @param request the HTTP servlet request
     * @return the child record, or 404 if not found
     */
    @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
    public ResponseEntity<Map<String, Object>> getChild(
            @PathVariable("parentName") String parentName,
            @PathVariable("parentId") String parentId,
            @PathVariable("childName") String childName,
            @PathVariable("childId") String childId,
            HttpServletRequest request) {

        logger.debug("Get child request: parent='{}', parentId='{}', child='{}', childId='{}'",
                parentName, parentId, childName, childId);

        return inRequestTenantScope(request, () -> {
            SubResourceRelation relation = collections.resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            // Absent, not visible to the caller's tenant, or under another parent: all read as 404.
            Optional<Map<String, Object>> record = tenantScope.findChild(
                    relation.childDef(), relation.parentRefFieldName(), parentId, childId, request);
            return record.map(r -> ResponseEntity.ok(
                            JsonApiRecordSerializer.document(r, childName, relation.childDef())))
                         .orElse(ResponseEntity.notFound().build());
        });
    }

    /**
     * Creates a child record under a parent resource.
     *
     * <p>Automatically sets the parent reference field to the parent ID.
     *
     * @param parentName the parent collection name
     * @param parentId the parent record ID
     * @param childName the child collection name
     * @param requestBody the JSON:API formatted request body
     * @param request the HTTP servlet request
     * @return the created record with 201 status
     */
    @PostMapping("/{parentName}/{parentId}/{childName}")
    public ResponseEntity<Map<String, Object>> createChild(
            @PathVariable("parentName") String parentName,
            @PathVariable("parentId") String parentId,
            @PathVariable("childName") String childName,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        logger.debug("Create child request: parent='{}', parentId='{}', child='{}'",
                parentName, parentId, childName);

        return inRequestTenantScope(request, () -> {
            SubResourceRelation relation = collections.resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }
            return writePipeline.create(WriteTarget.child(relation, childName, parentId), requestBody, request);
        });
    }

    /**
     * Updates a child record under a parent resource (PUT).
     *
     * @param parentName the parent collection name
     * @param parentId the parent record ID
     * @param childName the child collection name
     * @param childId the child record ID
     * @param requestBody the JSON:API formatted request body
     * @param request the HTTP servlet request
     * @return the updated record, or 404 if not found
     */
    @PutMapping("/{parentName}/{parentId}/{childName}/{childId}")
    public ResponseEntity<Map<String, Object>> updateChild(
            @PathVariable("parentName") String parentName,
            @PathVariable("parentId") String parentId,
            @PathVariable("childName") String childName,
            @PathVariable("childId") String childId,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        return performChildUpdate(parentName, parentId, childName, childId, requestBody, request);
    }

    /**
     * Updates a child record under a parent resource (PATCH).
     *
     * @param parentName the parent collection name
     * @param parentId the parent record ID
     * @param childName the child collection name
     * @param childId the child record ID
     * @param requestBody the JSON:API formatted request body
     * @param request the HTTP servlet request
     * @return the updated record, or 404 if not found
     */
    @PatchMapping("/{parentName}/{parentId}/{childName}/{childId}")
    public ResponseEntity<Map<String, Object>> patchChild(
            @PathVariable("parentName") String parentName,
            @PathVariable("parentId") String parentId,
            @PathVariable("childName") String childName,
            @PathVariable("childId") String childId,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        return performChildUpdate(parentName, parentId, childName, childId, requestBody, request);
    }

    /**
     * Performs the child update operation.
     */
    private ResponseEntity<Map<String, Object>> performChildUpdate(
            String parentName, String parentId,
            String childName, String childId,
            Map<String, Object> requestBody,
            HttpServletRequest request) {

        logger.debug("Update child request: parent='{}', parentId='{}', child='{}', childId='{}'",
                parentName, parentId, childName, childId);

        return inRequestTenantScope(request, () -> {
            SubResourceRelation relation = collections.resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }
            return writePipeline.update(WriteTarget.child(relation, childName, parentId), childId,
                    requestBody, request);
        });
    }

    /**
     * Deletes a child record under a parent resource.
     *
     * @param parentName the parent collection name
     * @param parentId the parent record ID
     * @param childName the child collection name
     * @param childId the child record ID
     * @param request the HTTP servlet request
     * @return 204 No Content if deleted, 404 if not found
     */
    @DeleteMapping("/{parentName}/{parentId}/{childName}/{childId}")
    public ResponseEntity<Void> deleteChild(
            @PathVariable("parentName") String parentName,
            @PathVariable("parentId") String parentId,
            @PathVariable("childName") String childName,
            @PathVariable("childId") String childId,
            HttpServletRequest request) {

        logger.debug("Delete child request: parent='{}', parentId='{}', child='{}', childId='{}'",
                parentName, parentId, childName, childId);

        return inRequestTenantScope(request, () -> {
            SubResourceRelation relation = collections.resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }
            return writePipeline.delete(WriteTarget.child(relation, childName, parentId), childId, request);
        });
    }

    /**
     * Runs a handler body with the request's tenant (ID and slug) in {@link TenantContext}.
     *
     * <p>In the worker the scope is already bound by {@code TenantContextFilter} from these same
     * headers, and an outer binding wins, so this only binds what nothing upstream bound — the
     * precedence the removed ThreadLocal setters had, kept for callers (and tests) that drive the
     * router without that filter.
     */
    private <T> T inRequestTenantScope(HttpServletRequest request,
                                       ScopedValue.CallableOp<T, RuntimeException> body) {
        return TenantContext.callWithTenantIfUnbound(
                request.getHeader("X-Tenant-ID"), request.getHeader("X-Tenant-Slug"), body);
    }

    /**
     * Resolves a record by its display field value.
     *
     * <p>Looks up the collection's display field and verifies it is both unique
     * and required (non-nullable). If so, queries for a single record matching
     * the given value. This allows GET-by-id to accept human-readable values
     * (e.g., a slug or name) instead of UUIDs.
     *
     * @param definition the collection definition
     * @param value the display field value to look up
     * @param request the HTTP servlet request
     * @return the matching record, or empty if not found or display field is not eligible
     */
    private Optional<Map<String, Object>> resolveByDisplayField(
            CollectionDefinition definition, String value, HttpServletRequest request) {

        String displayFieldName = definition.displayFieldName();
        if (displayFieldName == null) {
            logger.debug("Collection '{}' has no display field configured", definition.name());
            return Optional.empty();
        }

        FieldDefinition displayField = definition.getField(displayFieldName);
        if (displayField == null) {
            logger.debug("Display field '{}' not found in collection '{}'",
                    displayFieldName, definition.name());
            return Optional.empty();
        }

        // Only allow display-field lookup when the field is unique and required
        if (!displayField.unique() || displayField.nullable()) {
            logger.debug("Display field '{}' on collection '{}' is not eligible for lookup " +
                    "(unique={}, nullable={})", displayFieldName, definition.name(),
                    displayField.unique(), displayField.nullable());
            return Optional.empty();
        }

        logger.debug("Resolving '{}' by display field '{}' = '{}'",
                definition.name(), displayFieldName, value);

        List<FilterCondition> filters = new ArrayList<>();
        filters.add(new FilterCondition(displayFieldName, FilterOperator.EQ, value));

        // Inject tenant filter if applicable
        QueryRequest queryRequest = new QueryRequest(
                new Pagination(1, 1), List.of(), List.of(), filters);
        queryRequest = tenantScope.injectTenantFilter(queryRequest, definition, request);

        QueryResult result = queryEngine.executeQuery(definition, queryRequest);
        if (result.data().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(result.data().get(0));
    }

    /**
     * Builds a deterministic hash string from a query request for use as a cache key.
     * Uses the record's toString() which includes all components (pagination, sorting,
     * fields, filters).
     *
     * @param queryRequest the query request
     * @return a deterministic string representation suitable for cache keys
     */
    private String buildQueryHash(QueryRequest queryRequest) {
        return queryRequest.toString();
    }

    /**
     * System collections that are writable through this router but whose rows are also mutated
     * out-of-band, so {@code readOnly} does not exclude them and caching them serves fiction.
     *
     * <p>{@code scheduled-jobs} is the case in hand. Every meaningful write to it happens via
     * {@code ScheduledJobRepository}'s direct JDBC — the scheduler stamps {@code last_run_at},
     * {@code last_status} and a recomputed {@code next_run_at} after <em>every</em> execution, and
     * {@code FlowScheduleSyncHook} rewrites {@code active}/{@code next_run_at} whenever a flow's
     * schedule changes. None of that goes through this router, so nothing evicts the entry.
     *
     * <p>Eviction on write would not fix it either: the cache is per-pod, so a pod that never
     * served the write keeps its own stale copy regardless. Not caching is the only correct
     * answer short of broadcasting invalidation over NATS, and this data is low-traffic
     * operational state where caching buys nothing.
     *
     * <p>The cost of getting this wrong is not a slightly stale list. It is that every tool for
     * answering "is this schedule healthy?" reports fiction — the endpoint kept serving
     * {@code active=false, next_run_at=null} for a job that was active with a correct next run,
     * which reads exactly like a broken scheduler and sent one investigation down a bug that did
     * not exist.
     */
    private static final Set<String> NEVER_CACHED_SYSTEM_COLLECTIONS = Set.of("scheduled-jobs");

    /**
     * Whether this collection's responses may be served from / stored in the
     * {@link SystemCollectionCache}. Read-only system collections (record-versions,
     * field-history, email-logs, login-history, audit logs, ...) are excluded:
     * they are written by backend services via direct JDBC, never through this
     * router, so no write path ever evicts their entries — caching them serves
     * stale, per-pod results until the TTL expires.
     *
     * <p>{@link #NEVER_CACHED_SYSTEM_COLLECTIONS} extends that exclusion to collections which are
     * writable here but still mutated out-of-band.
     *
     * <p>Self-scoped collections ({@code tenants}) are never cached either: the cache is keyed on
     * the {@code X-Tenant-ID} header, but what a {@code tenants} read returns depends on the bound
     * {@code TenantContext}, which the worker unbinds for a platform admin's tenant listing
     * ({@code TenantManagementScopeFilter}). A cached full listing would otherwise be served to
     * the next caller of the same tenant with the same query.
     *
     * <p>Collections whose reads are owner-scoped ({@code ownerField} + {@code ownerScopeReads})
     * are never cached for the same reason: the storage adapter narrows each read to the bound
     * caller's rows, but the cache key carries no caller, so one member's cached listing would be
     * served to the next member who sends the same query.
     */
    private boolean cacheable(CollectionDefinition definition) {
        return definition.systemCollection()
                && !definition.readOnly()
                && !NEVER_CACHED_SYSTEM_COLLECTIONS.contains(definition.name())
                && !SystemCollectionTenancy.isSelfScoped(definition)
                && !(definition.isOwnerScoped() && definition.ownerScopeReads())
                && systemCollectionCache != null;
    }
}
