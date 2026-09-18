package io.kelta.runtime.router;

import io.kelta.jsonapi.PaginationLinks;
import io.kelta.runtime.context.GeoHeaders;
import io.kelta.runtime.context.GeoStamp;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.model.system.SystemCollectionTenancy;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.ReadOnlyCollectionException;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.kelta.runtime.storage.StaleWriteException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api")
public class DynamicCollectionRouter {
    
    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Framework metadata keys that belong in every record's response envelope
    // even though they have no FieldDefinition. Aligned with PhysicalTableStorageAdapter's
    // PAYLOAD_SYSTEM_KEYS (minus "id", which is hoisted to the top level). Used by
    // toJsonApiResourceObject to distinguish metadata from orphan columns left behind
    // when a field is deleted.
    private static final Set<String> SYSTEM_ATTRIBUTE_KEYS = Set.of(
            "createdAt", "updatedAt", "createdBy", "updatedBy",
            "createdGeo", "updatedGeo", "tenantId", "recordTypeId");

    // Suffixes appended to a primary field name to form companion column keys
    // for CURRENCY and GEOLOCATION fields. Used to keep `<field>_currency_code`,
    // `<field>_longitude`, `<field>_latitude` in the response envelope while
    // still dropping the same suffixes when no live primary field exists.
    private static final String[] COMPANION_SUFFIXES = {
            "_currency_code", "_longitude", "_latitude"};

    private final CollectionRegistry registry;
    private final QueryEngine queryEngine;

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

        setTenantContext(request);
        try {
            CollectionDefinition definition = resolveCollection(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            QueryRequest queryRequest = QueryRequest.fromParams(params);

            // For tenant-scoped system collections, inject tenant_id filter
            queryRequest = injectTenantFilter(queryRequest, definition, request);

            // Check system collection cache before querying
            String tenantId = request.getHeader("X-Tenant-ID");
            List<String> includeNames = parseIncludeParam(params);
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
            result = restrictSharedSystemRows(definition, result, request);

            Map<String, Object> response = toJsonApiListResponse(
                    result, collectionName, definition, request.getRequestURI(), params);

            // Resolve JSON:API ?include= parameter for inverse (has-many) relationships
            if (!includeNames.isEmpty() && !result.data().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> dataResources =
                        (List<Map<String, Object>>) response.get("data");
                List<Map<String, Object>> included = resolveIncludes(
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
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            CollectionDefinition definition = resolveCollection(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            // Parse JSON:API ?include= up front so cache lookup can match the put guard:
            // the cache only ever stores no-include responses, so include-bearing requests
            // must skip the lookup or they'd receive a stale parent without included children.
            List<String> includeNames = parseIncludeParam(params);

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
            // docker-compose, the test harness).
            if (record.isEmpty() || !visibleToTenant(record.get(), definition, request)) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = toJsonApiResponse(record.get(), collectionName, definition);

            // Resolve JSON:API ?include= parameter for inverse (has-many) relationships
            if (!includeNames.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataResource = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> included = resolveIncludes(
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
            String etag = computeETag(record.get());
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (etag != null) {
                ok.header(HttpHeaders.ETAG, etag);
            }
            return ok.body(response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Derives an opaque optimistic-locking ETag from a record's {@code updatedAt} version. Returns
     * {@code null} when the record has no version (nothing to lock against). The token is
     * base64-encoded so it is header-safe and collision-free for a single record's timeline; the
     * client treats it as opaque and echoes it back via {@code If-Match}.
     */
    private static String computeETag(Map<String, Object> record) {
        Object updatedAt = record.get("updatedAt");
        if (updatedAt == null) {
            return null;
        }
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(updatedAt).getBytes(StandardCharsets.UTF_8));
        return "\"" + token + "\"";
    }

    /**
     * Enforces optimistic locking on a write. When the request carries an {@code If-Match} header,
     * the current record's ETag must match or a {@link StaleWriteException} (→ 409) is thrown.
     * No header ⇒ no check (back-compat). A missing record is left for the normal 404 path.
     */
    private void enforceIfMatch(CollectionDefinition definition, String id, HttpServletRequest request) {
        String ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return;
        }
        Optional<Map<String, Object>> existing = queryEngine.getById(definition, id);
        if (existing.isEmpty()) {
            return;
        }
        String current = computeETag(existing.get());
        if (current != null && !ifMatch.trim().equals(current)) {
            throw new StaleWriteException(definition.name(), id);
        }
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

        setTenantContext(request);
        try {
            CollectionDefinition definition = resolveCollection(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            // Reject writes to read-only collections
            if (definition.readOnly()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        toJsonApiErrorResponse("Collection '" + collectionName + "' is read-only"));
            }

            // Extract attributes from JSON:API format
            Map<String, Object> attributes = extractAttributes(requestBody);

            // Extract relationships from JSON:API format
            Map<String, Object> relationships = extractRelationships(requestBody);

            // Merge attributes and relationships for storage
            Map<String, Object> data = new java.util.HashMap<>(attributes);
            data.putAll(relationships);

            // Inject audit fields from gateway-forwarded user ID (resolved to platform_user UUID)
            String userId = resolveUserId(request);
            if (userId != null) {
                data.put("createdBy", userId);
                data.put("updatedBy", userId);
            }
            stampGeo(data, definition, request, true);

            // Inject tenant ID for tenant-scoped system collections
            injectTenantId(data, definition, request);

            Map<String, Object> created = queryEngine.create(definition, data);

            // Evict system collection cache after write
            evictSystemCollectionCache(definition, request);

            // Return in JSON:API format, echoing caller-supplied relationships
            Map<String, Object> response = toJsonApiResponse(created, collectionName, definition);
            mergeRequestRelationships(response, extractRawRelationships(requestBody));
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } finally {
            TenantContext.clear();
        }
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
     * Performs the update operation.
     *
     * @param collectionName the collection name
     * @param id the record ID
     * @param requestBody the JSON:API formatted request body with updated data
     * @param request the HTTP servlet request
     * @return the updated record in JSON:API format, or 404 if collection or record not found
     */
    private ResponseEntity<Map<String, Object>> performUpdate(
            String collectionName,
            String id,
            Map<String, Object> requestBody,
            HttpServletRequest request) {

        logger.debug("Update request for collection '{}', id '{}' with data: {}", collectionName, id, requestBody);

        setTenantContext(request);
        try {
            CollectionDefinition definition = resolveCollection(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            // Reject writes to read-only collections
            if (definition.readOnly()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        toJsonApiErrorResponse("Collection '" + collectionName + "' is read-only"));
            }

            // Extract attributes from JSON:API format
            Map<String, Object> attributes = extractAttributes(requestBody);

            // Extract relationships from JSON:API format
            Map<String, Object> relationships = extractRelationships(requestBody);

            // Merge attributes and relationships for storage
            Map<String, Object> data = new java.util.HashMap<>(attributes);
            data.putAll(relationships);

            // Inject audit field from gateway-forwarded user ID (resolved to platform_user UUID)
            String userId = resolveUserId(request);
            if (userId != null) {
                data.put("updatedBy", userId);
            }
            stampGeo(data, definition, request, false);

            // Optimistic locking: reject a stale If-Match before mutating (slice 5).
            enforceIfMatch(definition, id, request);

            Optional<Map<String, Object>> updated = queryEngine.update(definition, id, data);

            // Evict system collection cache after write
            if (updated.isPresent()) {
                evictSystemCollectionCache(definition, request);
            }

            Map<String, Object> rawRels = extractRawRelationships(requestBody);
            return updated.map(r -> {
                        Map<String, Object> response = toJsonApiResponse(r, collectionName, definition);
                        mergeRequestRelationships(response, rawRels);
                        return ResponseEntity.ok(response);
                    })
                    .orElse(ResponseEntity.notFound().build());
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            CollectionDefinition definition = resolveCollection(collectionName, request);
            if (definition == null) {
                logger.debug("Collection '{}' not found", collectionName);
                return ResponseEntity.notFound().build();
            }

            // Reject deletes on read-only collections
            if (definition.readOnly()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // Optimistic locking: reject a stale If-Match before deleting (slice 5).
            enforceIfMatch(definition, id, request);

            boolean deleted = queryEngine.delete(definition, id);

            // Evict system collection cache after delete
            if (deleted) {
                evictSystemCollectionCache(definition, request);
            }

            return deleted ? ResponseEntity.noContent().build()
                           : ResponseEntity.notFound().build();
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            SubResourceRelation relation = resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            QueryRequest queryRequest = QueryRequest.fromParams(params);

            // Inject parent ID filter on the child's reference field
            List<FilterCondition> filters = new ArrayList<>(queryRequest.filters());
            filters.add(new FilterCondition(relation.parentRefFieldName(), FilterOperator.EQ, parentId));
            queryRequest = queryRequest.withFilters(filters);

            // Inject tenant filter if applicable
            queryRequest = injectTenantFilter(queryRequest, relation.childDef(), request);

            QueryResult result = queryEngine.executeQuery(relation.childDef(), queryRequest);
            result = restrictSharedSystemRows(relation.childDef(), result, request);

            return ResponseEntity.ok(toJsonApiListResponse(
                    result, childName, relation.childDef(), request.getRequestURI(), params));
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            SubResourceRelation relation = resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            Optional<Map<String, Object>> record = queryEngine.getById(relation.childDef(), childId);
            return record.map(r -> ResponseEntity.ok(toJsonApiResponse(r, childName, relation.childDef())))
                         .orElse(ResponseEntity.notFound().build());
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            SubResourceRelation relation = resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            if (relation.childDef().readOnly()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        toJsonApiErrorResponse("Collection '" + childName + "' is read-only"));
            }

            Map<String, Object> attributes = extractAttributes(requestBody);
            Map<String, Object> relationships = extractRelationships(requestBody);

            Map<String, Object> data = new java.util.HashMap<>(attributes);
            data.putAll(relationships);

            // Auto-set the parent reference field
            data.put(relation.parentRefFieldName(), parentId);

            // Inject audit fields (resolved to platform_user UUID)
            String userId = resolveUserId(request);
            if (userId != null) {
                data.put("createdBy", userId);
                data.put("updatedBy", userId);
            }
            stampGeo(data, relation.childDef(), request, true);

            // Inject tenant ID for tenant-scoped system collections
            injectTenantId(data, relation.childDef(), request);

            Map<String, Object> created = queryEngine.create(relation.childDef(), data);

            evictSystemCollectionCache(relation.childDef(), request);

            Map<String, Object> response = toJsonApiResponse(created, childName, relation.childDef());
            mergeRequestRelationships(response, extractRawRelationships(requestBody));
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            SubResourceRelation relation = resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            if (relation.childDef().readOnly()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        toJsonApiErrorResponse("Collection '" + childName + "' is read-only"));
            }

            Map<String, Object> attributes = extractAttributes(requestBody);
            Map<String, Object> relationships = extractRelationships(requestBody);

            Map<String, Object> data = new java.util.HashMap<>(attributes);
            data.putAll(relationships);

            String userId = resolveUserId(request);
            if (userId != null) {
                data.put("updatedBy", userId);
            }
            stampGeo(data, relation.childDef(), request, false);

            Optional<Map<String, Object>> updated = queryEngine.update(relation.childDef(), childId, data);

            if (updated.isPresent()) {
                evictSystemCollectionCache(relation.childDef(), request);
            }

            Map<String, Object> rawRels = extractRawRelationships(requestBody);
            return updated.map(r -> {
                        Map<String, Object> response = toJsonApiResponse(r, childName, relation.childDef());
                        mergeRequestRelationships(response, rawRels);
                        return ResponseEntity.ok(response);
                    })
                    .orElse(ResponseEntity.notFound().build());
        } finally {
            TenantContext.clear();
        }
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

        setTenantContext(request);
        try {
            SubResourceRelation relation = resolveSubResource(parentName, childName, request);
            if (relation == null) {
                return ResponseEntity.notFound().build();
            }

            if (relation.childDef().readOnly()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            boolean deleted = queryEngine.delete(relation.childDef(), childId);

            if (deleted) {
                evictSystemCollectionCache(relation.childDef(), request);
            }

            return deleted ? ResponseEntity.noContent().build()
                           : ResponseEntity.notFound().build();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Resolves a sub-resource relationship between parent and child collections.
     *
     * @param parentName the parent collection name
     * @param childName the child collection name
     * @param request the HTTP request (for on-demand loading)
     * @return the SubResourceRelation, or null if not found
     */
    private SubResourceRelation resolveSubResource(String parentName, String childName,
                                                     HttpServletRequest request) {
        CollectionDefinition parentDef = resolveCollection(parentName, request);
        if (parentDef == null) {
            logger.debug("Parent collection '{}' not found", parentName);
            return null;
        }

        CollectionDefinition childDef = resolveCollection(childName, request);
        if (childDef == null) {
            logger.debug("Child collection '{}' not found", childName);
            return null;
        }

        Optional<SubResourceRelation> relation = SubResourceResolver.resolve(parentDef, childDef);
        if (relation.isEmpty()) {
            logger.debug("No relationship found between parent '{}' and child '{}'",
                    parentName, childName);
            return null;
        }

        return relation.get();
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
    private QueryRequest injectTenantFilter(QueryRequest queryRequest, CollectionDefinition definition,
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
    private boolean visibleToTenant(Map<String, Object> record, CollectionDefinition definition,
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
    private QueryResult restrictSharedSystemRows(CollectionDefinition definition, QueryResult result,
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
    private List<Map<String, Object>> filterSharedSystemRows(CollectionDefinition definition,
                                                               List<Map<String, Object>> rows,
                                                               HttpServletRequest request) {
        if (!SystemCollectionTenancy.sharesSystemRows(definition) || rows.isEmpty()) {
            return rows;
        }
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
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
     * Injects tenant ID into record data for tenant-scoped system collections.
     *
     * @param data the record data
     * @param definition the collection definition
     * @param request the HTTP servlet request (used to extract tenant ID)
     */
    /**
     * Resolves the user identifier from the X-User-Id header to a platform_user UUID.
     * If a resolver is configured and the identifier is not already a UUID, the resolver
     * is used to translate it. Otherwise, the raw identifier is returned.
     */
    private String resolveUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            return null;
        }
        if (userIdResolver != null && !UUID_PATTERN.matcher(userId).matches()) {
            String tenantId = request.getHeader("X-Tenant-ID");
            String resolved = userIdResolver.resolve(userId, tenantId);
            if (resolved != null) {
                return resolved;
            }
        }
        return userId;
    }

    /**
     * Stamps the request-origin geolocation (gateway {@code X-Geo-*} headers) into
     * {@code createdGeo}/{@code updatedGeo} for collections with {@code captureGeo}
     * enabled. Stamps null when the origin has no geolocation — on update this
     * intentionally clears a previous stamp, so the value always describes the
     * origin of the LAST write, never a stale one.
     */
    private void stampGeo(Map<String, Object> data, CollectionDefinition definition,
                          HttpServletRequest request, boolean isCreate) {
        if (!definition.captureGeo() || definition.systemCollection()) {
            return;
        }
        Map<String, Object> geo = GeoHeaders.parse(request).map(GeoStamp::toMap).orElse(null);
        if (isCreate) {
            data.put("createdGeo", geo);
        }
        data.put("updatedGeo", geo);
    }

    /**
     * Sets the tenant context (ID and slug) from the request headers.
     */
    private void setTenantContext(HttpServletRequest request) {
        String tenantId = request.getHeader("X-Tenant-ID");
        String tenantSlug = request.getHeader("X-Tenant-Slug");
        TenantContext.set(tenantId);
        TenantContext.setSlug(tenantSlug);
    }

    private void injectTenantId(Map<String, Object> data, CollectionDefinition definition,
                                 HttpServletRequest request) {
        if (!definition.systemCollection() || !definition.tenantScoped()) {
            return;
        }

        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId != null && !tenantId.isBlank() && !data.containsKey("tenantId")) {
            data.put("tenantId", tenantId);
        }
    }

    /**
     * Builds a JSON:API error response.
     *
     * @param message the error message
     * @return a JSON:API error document
     */
    private Map<String, Object> toJsonApiErrorResponse(String message) {
        Map<String, Object> error = new java.util.HashMap<>();
        error.put("status", "403");
        error.put("title", "Forbidden");
        error.put("detail", message);

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("errors", List.of(error));
        return response;
    }

    /**
     * Resolves a collection definition by name, with on-demand loading fallback.
     *
     * <p>First checks the local registry. If not found and an on-demand loader
     * is configured, attempts to load the collection from the control plane
     * using the tenant ID from the request's {@code X-Tenant-ID} header.
     *
     * @param collectionName the collection name
     * @param request the HTTP servlet request (used to extract tenant ID)
     * @return the collection definition, or {@code null} if not found
     */
    private CollectionDefinition resolveCollection(String collectionName, HttpServletRequest request) {
        // TenantContext is already set before this method is called,
        // so registry.get() will use the tenant-scoped key automatically
        CollectionDefinition definition = registry.get(collectionName);
        if (definition != null) {
            return definition;
        }

        // Fallback: try to load on demand from the control plane
        if (onDemandLoader != null) {
            String tenantId = request.getHeader("X-Tenant-ID");
            logger.info("Collection '{}' not in registry, attempting on-demand load (tenantId={})",
                    collectionName, tenantId);
            try {
                definition = onDemandLoader.load(collectionName, tenantId);
                if (definition != null) {
                    logger.info("Successfully loaded collection '{}' on demand", collectionName);
                }
            } catch (Exception e) {
                logger.warn("On-demand load failed for collection '{}': {}", collectionName, e.getMessage());
            }
        }

        return definition;
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
        queryRequest = injectTenantFilter(queryRequest, definition, request);

        QueryResult result = queryEngine.executeQuery(definition, queryRequest);
        if (result.data().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(result.data().get(0));
    }

    /**
     * Extracts attributes from a JSON:API formatted request body.
     * 
     * @param requestBody the JSON:API request body
     * @return the attributes map, or empty map if not found
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAttributes(Map<String, Object> requestBody) {
        if (requestBody.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) requestBody.get("data");
            if (data != null && data.containsKey("attributes")) {
                Object attributes = data.get("attributes");
                if (attributes instanceof Map) {
                    return (Map<String, Object>) attributes;
                }
            }
        }
        // If not JSON:API format, assume the whole body is attributes
        return requestBody;
    }
    
    /**
     * Extracts relationships from a JSON:API formatted request body.
     * Converts relationship references to field values using the relationship
     * name directly as the field name (e.g., "category" → field "category").
     *
     * @param requestBody the JSON:API request body
     * @return the relationships as field values, or empty map if not found
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRelationships(Map<String, Object> requestBody) {
        Map<String, Object> fieldValues = new java.util.HashMap<>();

        if (requestBody.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) requestBody.get("data");
            if (data != null && data.containsKey("relationships")) {
                Object relationships = data.get("relationships");
                if (relationships instanceof Map) {
                    Map<String, Object> relationshipsMap = (Map<String, Object>) relationships;

                    for (Map.Entry<String, Object> entry : relationshipsMap.entrySet()) {
                        String relationshipName = entry.getKey();
                        Object relationshipValue = entry.getValue();

                        if (relationshipValue instanceof Map) {
                            Map<String, Object> relationship = (Map<String, Object>) relationshipValue;
                            if (relationship.containsKey("data")) {
                                Object relationshipData = relationship.get("data");
                                if (relationshipData instanceof Map) {
                                    Map<String, Object> relData = (Map<String, Object>) relationshipData;
                                    if (relData.containsKey("id")) {
                                        // Use relationship name directly as field name
                                        fieldValues.put(relationshipName, relData.get("id"));
                                    }
                                } else if (relationshipData == null) {
                                    // Null relationship — clear the field
                                    fieldValues.put(relationshipName, null);
                                }
                            }
                        }
                    }
                }
            }
        }

        return fieldValues;
    }
    
    /**
     * Converts a record to a JSON:API resource object using the collection
     * definition to identify relationship fields.
     *
     * <p>Relationship fields (those with {@code type.isRelationship()}) are placed
     * in the {@code relationships} section with their target collection as the
     * JSON:API type. All other fields go in {@code attributes}.
     *
     * @param record the record data
     * @param type the resource type (collection name)
     * @param definition the collection definition for field metadata
     * @return the JSON:API resource object
     */
    private Map<String, Object> toJsonApiResourceObject(Map<String, Object> record, String type,
                                                         CollectionDefinition definition) {
        Map<String, Object> resourceObject = new java.util.HashMap<>();

        resourceObject.put("type", type);
        resourceObject.put("id", record.get("id"));

        Map<String, Object> attributes = new java.util.HashMap<>();
        Map<String, Object> relationships = new java.util.HashMap<>();

        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Skip id — it's at the top level
            if ("id".equals(key)) {
                continue;
            }

            // Use field schema to detect relationship fields
            FieldDefinition fieldDef = definition.getField(key);
            if (fieldDef != null && fieldDef.type().isRelationship()
                    && fieldDef.referenceConfig() != null) {
                String relType = fieldDef.referenceConfig().targetCollection();
                Map<String, Object> relationshipData = new java.util.HashMap<>();
                if (value != null) {
                    relationshipData.put("data", Map.of(
                        "type", relType,
                        "id", value
                    ));
                } else {
                    relationshipData.put("data", null);
                }
                relationships.put(key, relationshipData);
                // Also echo the raw id in attributes: a write accepts the same id via
                // attributes, and a client that diffs what it sent against what it reads
                // back must see it there too, not only nested under relationships.
                attributes.put(key, value);
            } else if (fieldDef != null || isSystemOrCompanionKey(key, definition)) {
                attributes.put(key, value);
            }
            // Else: drop. The key has no matching FieldDefinition and is neither
            // framework metadata nor a companion of a still-live field. It's an
            // orphan column left behind by a deleted field — SchemaMigrationEngine
            // only marks deleted columns deprecated (no DROP COLUMN), and SELECT *
            // still returns them. Filter here so the public response stays in sync
            // with the live field set.
        }

        resourceObject.put("attributes", attributes);
        if (!relationships.isEmpty()) {
            resourceObject.put("relationships", relationships);
        }

        return resourceObject;
    }

    private boolean isSystemOrCompanionKey(String key, CollectionDefinition definition) {
        if (SYSTEM_ATTRIBUTE_KEYS.contains(key)) {
            return true;
        }
        for (String suffix : COMPANION_SUFFIXES) {
            if (key.length() > suffix.length() && key.endsWith(suffix)) {
                String primaryName = key.substring(0, key.length() - suffix.length());
                FieldDefinition primary = definition.getField(primaryName);
                if (primary != null && primary.type().hasCompanionColumns()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wraps a single record in a JSON:API document envelope.
     *
     * @param record the record data
     * @param type the resource type (collection name)
     * @param definition the collection definition
     * @return the JSON:API document with {@code data} key
     */
    private Map<String, Object> toJsonApiResponse(Map<String, Object> record, String type,
                                                   CollectionDefinition definition) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("data", toJsonApiResourceObject(record, type, definition));
        return response;
    }

    /**
     * Returns the raw JSON:API {@code relationships} block from a request body,
     * preserving each entry's {@code {data: {type, id}}} structure. Used to echo
     * the caller's relationships back into create/update responses even when the
     * collection's field metadata doesn't surface them automatically.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRawRelationships(Map<String, Object> requestBody) {
        if (requestBody == null) {
            return Map.of();
        }
        Object dataObj = requestBody.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return Map.of();
        }
        Object rels = ((Map<String, Object>) data).get("relationships");
        if (!(rels instanceof Map<?, ?> relsMap) || relsMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.HashMap<>();
        for (Map.Entry<?, ?> e : relsMap.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Merges the caller-supplied relationships from a request into a response
     * document so that POST/PATCH responses always echo the relationships the
     * caller asserted. Field-derived relationships (REFERENCE/LOOKUP/MASTER_DETAIL
     * columns picked up by {@link #toJsonApiResourceObject}) already in the
     * response are preserved and take precedence — request-supplied entries fill
     * the gaps for relationship names that don't map to a REFERENCE-typed field.
     */
    @SuppressWarnings("unchecked")
    private void mergeRequestRelationships(Map<String, Object> response,
                                            Map<String, Object> requestRelationships) {
        if (response == null || requestRelationships == null || requestRelationships.isEmpty()) {
            return;
        }
        Object dataObj = response.get("data");
        if (!(dataObj instanceof Map)) {
            return;
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;
        Map<String, Object> relationships;
        Object existing = data.get("relationships");
        if (existing instanceof Map) {
            relationships = (Map<String, Object>) existing;
        } else {
            relationships = new java.util.HashMap<>();
        }
        for (Map.Entry<String, Object> entry : requestRelationships.entrySet()) {
            relationships.putIfAbsent(entry.getKey(), entry.getValue());
        }
        if (!relationships.isEmpty()) {
            data.put("relationships", relationships);
        }
    }

    /**
     * Converts a QueryResult into a JSON:API list response with proper
     * relationship formatting, pagination metadata, and a {@code links} block
     * carrying {@code self} / {@code prev} / {@code next} URLs.
     *
     * @param result the query result
     * @param type the resource type (collection name)
     * @param definition the collection definition
     * @param requestPath the request URI (used as the {@code self}/{@code prev}/{@code next} URL base)
     * @param params the original query parameters
     * @return the JSON:API document with {@code data} array, {@code metadata}, and {@code links}
     */
    private Map<String, Object> toJsonApiListResponse(QueryResult result, String type,
                                                       CollectionDefinition definition,
                                                       String requestPath,
                                                       MultiValueMap<String, String> params) {
        List<Map<String, Object>> jsonApiData = new ArrayList<>();
        for (Map<String, Object> record : result.data()) {
            jsonApiData.add(toJsonApiResourceObject(record, type, definition));
        }

        int effectivePageSize = result.metadata().pageSize();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("totalCount", result.metadata().totalCount());
        metadata.put("currentPage", result.metadata().currentPage());
        metadata.put("pageSize", effectivePageSize);
        metadata.put("totalPages", result.metadata().totalPages());

        // Surface page[size] clamping so callers don't have to infer it from
        // data.length vs. metadata.pageSize. Pagination.fromParams silently caps
        // an over-cap page[size] (e.g. 500) down to MAX_HTTP_PAGE_SIZE.
        Integer requestedPageSize = parseRequestedPageSize(params);
        if (requestedPageSize != null && requestedPageSize > effectivePageSize) {
            metadata.put("requestedPageSize", requestedPageSize);
            metadata.put("pageSizeClamped", true);
        }

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("data", jsonApiData);
        // Emit the JSON:API standard `meta` key, plus the legacy `metadata`
        // alias for backward compatibility. `metadata` is deprecated — new
        // clients should read `meta`. Both maps share the same instance so
        // they are guaranteed to stay in sync.
        response.put("meta", metadata);
        response.put("metadata", metadata);
        response.put("links", PaginationLinks.build(
            requestPath,
            params != null ? params.toSingleValueMap() : null,
            result.metadata().currentPage(),
            effectivePageSize,
            result.metadata().totalPages()
        ));
        return response;
    }

    /**
     * Returns the raw {@code page[size]} value from the request params, or
     * {@code null} if absent, blank, or non-numeric. Used to detect when
     * {@link Pagination#fromParams(Map)} clamped the caller's value so the
     * response can echo what was originally requested.
     */
    private Integer parseRequestedPageSize(MultiValueMap<String, String> params) {
        if (params == null) {
            return null;
        }
        String raw = params.getFirst("page[size]");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Include Resolution ====================

    /**
     * Parses the {@code include} query parameter into a list of relationship names.
     *
     * @param params the query parameters
     * @return list of include names, or empty list if not present
     */
    private List<String> parseIncludeParam(MultiValueMap<String, String> params) {
        if (params == null) {
            return List.of();
        }
        String includeParam = params.getFirst("include");
        if (includeParam == null || includeParam.isBlank()) {
            return List.of();
        }
        return Arrays.stream(includeParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Resolves included resources for JSON:API {@code ?include=} parameter.
     *
     * <p>Supports both direct and transitive (grandchild) includes. Direct includes
     * are child collections that have a field referencing the primary collection.
     * Transitive includes are grandchild collections that reference a direct child
     * collection rather than the primary collection itself.
     *
     * <p>Resolution proceeds in two passes:
     * <ol>
     *   <li><strong>Direct pass:</strong> Resolve includes that have a direct
     *       relationship to the primary collection.</li>
     *   <li><strong>Transitive pass:</strong> For any unresolved includes, check
     *       whether they reference an already-resolved direct child collection.
     *       If so, use the direct child records' IDs to query the grandchild
     *       collection.</li>
     * </ol>
     *
     * <p>Example: When fetching a {@code page-layouts} record with
     * {@code ?include=layout-sections,layout-fields,layout-related-lists}:
     * <ul>
     *   <li>{@code layout-sections} and {@code layout-related-lists} are resolved
     *       directly (both have {@code layoutId → page-layouts}).</li>
     *   <li>{@code layout-fields} is resolved transitively via {@code layout-sections}
     *       (it has {@code sectionId → layout-sections}).</li>
     * </ul>
     *
     * @param includeNames the requested include relationship names
     * @param primaryData the primary data records (raw, used to read FK values)
     * @param primaryResources the serialized JSON:API resource objects for the primary
     *        records (mutated in place to inject {@code relationships} entries for
     *        field-name includes; index-aligned with {@code primaryData})
     * @param primaryCollectionName the primary collection name
     * @param primaryDefinition the primary collection definition
     * @param request the HTTP servlet request
     * @return list of included resource objects in JSON:API format
     */
    private List<Map<String, Object>> resolveIncludes(
            List<String> includeNames,
            List<Map<String, Object>> primaryData,
            List<Map<String, Object>> primaryResources,
            String primaryCollectionName,
            CollectionDefinition primaryDefinition,
            HttpServletRequest request) {

        // Collect all primary record IDs
        List<Object> parentIds = primaryData.stream()
                .map(record -> record.get("id"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (parentIds.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> allIncluded = new ArrayList<>();

        // Track resolved collections and their raw data for transitive resolution
        List<String> unresolvedNames = new ArrayList<>();
        Map<String, List<Map<String, Object>>> resolvedChildData = new LinkedHashMap<>();
        Map<String, CollectionDefinition> resolvedChildDefs = new LinkedHashMap<>();

        // --- Pass 1: Direct includes ---
        for (String includeName : includeNames) {
            CollectionDefinition childDef = resolveCollection(includeName, request);
            if (childDef == null) {
                // Fallback: treat the include name as a field on the primary collection
                // whose referenceConfig points to another collection. This is the
                // "lookup field" include form: ?include=title where `title` is a
                // STRING/LOOKUP field on availabilities that stores a UUID FK.
                if (resolveFieldInclude(includeName, primaryData, primaryResources,
                        primaryDefinition, allIncluded, request)) {
                    continue;
                }
                logger.debug("Include target '{}' not found as collection or field, skipping",
                        includeName);
                continue;
            }

            // Check has-many: child has FK pointing to primary
            Optional<SubResourceRelation> relation =
                    SubResourceResolver.resolve(primaryDefinition, childDef);
            if (relation.isPresent()) {
                String parentRefField = relation.get().parentRefFieldName();
                logger.debug("Resolving has-many include '{}': querying where {} IN {} parent IDs",
                        includeName, parentRefField, parentIds.size());

                List<Map<String, Object>> childRecords =
                        queryChildRecords(childDef, parentRefField, parentIds, request);

                for (Map<String, Object> childRecord : childRecords) {
                    allIncluded.add(toJsonApiResourceObject(childRecord, includeName, childDef));
                }
                resolvedChildData.put(includeName, childRecords);
                resolvedChildDefs.put(includeName, childDef);
                logger.debug("Resolved {} has-many included records for '{}'",
                        childRecords.size(), includeName);
                continue;
            }

            // Check belongs-to: primary has FK pointing to child.
            // Use resolveAll to collect FK values from ALL fields referencing the
            // target collection (e.g., both created_by and updated_by → users).
            List<SubResourceRelation> belongsToAll =
                    SubResourceResolver.resolveAll(childDef, primaryDefinition);
            if (!belongsToAll.isEmpty()) {
                // Collect FK values from all matching fields
                java.util.Set<Object> fkValueSet = new java.util.LinkedHashSet<>();
                List<String> fkFieldNames = new java.util.ArrayList<>();
                for (SubResourceRelation rel : belongsToAll) {
                    String fkField = rel.parentRefFieldName();
                    fkFieldNames.add(fkField);
                    for (Map<String, Object> record : primaryData) {
                        Object val = record.get(fkField);
                        if (val != null) {
                            fkValueSet.add(val);
                        }
                    }
                }
                List<Object> fkValues = new java.util.ArrayList<>(fkValueSet);

                if (!fkValues.isEmpty()) {
                    logger.debug("Resolving belongs-to include '{}': querying where id IN {} FK values from fields {}",
                            includeName, fkValues.size(), fkFieldNames);
                    List<Map<String, Object>> referencedRecords =
                            queryChildRecords(childDef, "id", fkValues, request);
                    for (Map<String, Object> rec : referencedRecords) {
                        allIncluded.add(toJsonApiResourceObject(rec, includeName, childDef));
                    }
                    resolvedChildData.put(includeName, referencedRecords);
                    resolvedChildDefs.put(includeName, childDef);
                    logger.debug("Resolved {} belongs-to included records for '{}'",
                            referencedRecords.size(), includeName);
                }
                continue;
            }

            // No direct relationship — defer to transitive pass
            unresolvedNames.add(includeName);
        }

        // --- Pass 2: Transitive (grandchild) includes ---
        // Supports two chain types via an already-resolved intermediate collection:
        //   Has-many chain:   primary → intermediate (has-many) → grandchild (has-many)
        //                     e.g., page-layouts → layout-sections → layout-fields
        //                     grandchild has FK pointing to intermediate
        //   Belongs-to chain: primary → intermediate (has-many) → grandchild (belongs-to)
        //                     e.g., orders → order_items → products
        //                     intermediate has FK pointing to grandchild
        for (String includeName : unresolvedNames) {
            CollectionDefinition grandchildDef = resolveCollection(includeName, request);
            if (grandchildDef == null) {
                continue;
            }

            boolean resolved = false;
            // Check each already-resolved direct child to see if it's the
            // intermediate parent for this grandchild
            for (Map.Entry<String, CollectionDefinition> entry : resolvedChildDefs.entrySet()) {
                String intermediateCollectionName = entry.getKey();
                CollectionDefinition intermediateDef = entry.getValue();

                List<Map<String, Object>> intermediateData =
                        resolvedChildData.get(intermediateCollectionName);

                // Try has-many direction: grandchild has FK pointing to intermediate
                // e.g., layout-fields has sectionId FK pointing to layout-sections
                Optional<SubResourceRelation> hasManyRelation =
                        SubResourceResolver.resolve(intermediateDef, grandchildDef);
                if (hasManyRelation.isPresent()) {
                    String intermediateRefField = hasManyRelation.get().parentRefFieldName();

                    List<Object> intermediateIds = intermediateData.stream()
                            .map(r -> r.get("id"))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    if (intermediateIds.isEmpty()) {
                        logger.debug("No intermediate '{}' records to resolve transitive include '{}'",
                                intermediateCollectionName, includeName);
                        resolved = true;
                        break;
                    }

                    logger.debug("Resolving transitive include '{}' via '{}' (has-many): "
                                    + "querying where {} IN {} IDs",
                            includeName, intermediateCollectionName, intermediateRefField,
                            intermediateIds.size());

                    List<Map<String, Object>> grandchildRecords =
                            queryChildRecords(grandchildDef, intermediateRefField, intermediateIds,
                                    request);

                    for (Map<String, Object> record : grandchildRecords) {
                        allIncluded.add(toJsonApiResourceObject(record, includeName, grandchildDef));
                    }
                    logger.debug("Resolved {} transitive included records for '{}' via '{}'",
                            grandchildRecords.size(), includeName, intermediateCollectionName);
                    resolved = true;
                    break;
                }

                // Try belongs-to direction: intermediate has FK pointing to grandchild
                // e.g., order_items has product FK pointing to products
                Optional<SubResourceRelation> belongsToRelation =
                        SubResourceResolver.resolve(grandchildDef, intermediateDef);
                if (belongsToRelation.isPresent()) {
                    String fkField = belongsToRelation.get().parentRefFieldName();

                    // Extract FK values from intermediate records to query grandchild by ID
                    List<Object> fkValues = intermediateData.stream()
                            .map(r -> r.get(fkField))
                            .filter(Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());

                    if (fkValues.isEmpty()) {
                        logger.debug("No FK values in '{}' for transitive include '{}'",
                                intermediateCollectionName, includeName);
                        resolved = true;
                        break;
                    }

                    logger.debug("Resolving transitive include '{}' via '{}' (belongs-to): "
                                    + "querying where id IN {} FK values from field '{}'",
                            includeName, intermediateCollectionName, fkValues.size(), fkField);

                    List<Map<String, Object>> grandchildRecords =
                            queryChildRecords(grandchildDef, "id", fkValues, request);

                    for (Map<String, Object> record : grandchildRecords) {
                        allIncluded.add(toJsonApiResourceObject(record, includeName, grandchildDef));
                    }
                    logger.debug("Resolved {} transitive included records for '{}' via '{}'",
                            grandchildRecords.size(), includeName, intermediateCollectionName);
                    resolved = true;
                    break;
                }
            }

            if (!resolved) {
                logger.debug("No direct or transitive relationship from '{}' to '{}', "
                        + "skipping include", primaryCollectionName, includeName);
            }
        }

        return allIncluded;
    }

    /**
     * Resolves an include name as a field on the primary collection whose
     * {@link ReferenceConfig#targetCollection()} points to another collection.
     *
     * <p>Used when the include name does not match a registered collection. For
     * example, on {@code GET /availabilities?include=title} the field {@code title}
     * stores a UUID FK to the {@code titles} collection; this method:
     * <ol>
     *   <li>injects a {@code relationships.<field>.data = { type, id }} entry into
     *       each primary resource object (without removing the raw UUID from
     *       {@code attributes} — kept for back-compat),</li>
     *   <li>queries the target collection by id IN [fk values] and appends each
     *       referenced record to the {@code included[]} accumulator.</li>
     * </ol>
     *
     * <p>Works for any field with a non-null {@code referenceConfig}, regardless of
     * {@link FieldType} — covers both the LOOKUP / MASTER_DETAIL / REFERENCE typed
     * fields and legacy STRING fields that carry a {@code referenceConfig}.
     *
     * @return {@code true} when the include name was recognized as a reference field
     *         on the primary collection (regardless of whether any target rows existed)
     */
    private boolean resolveFieldInclude(
            String fieldIncludeName,
            List<Map<String, Object>> primaryData,
            List<Map<String, Object>> primaryResources,
            CollectionDefinition primaryDefinition,
            List<Map<String, Object>> includedAccumulator,
            HttpServletRequest request) {

        FieldDefinition fieldDef = primaryDefinition.getField(fieldIncludeName);
        if (fieldDef == null || fieldDef.referenceConfig() == null
                || fieldDef.referenceConfig().targetCollection() == null) {
            return false;
        }

        String targetCollectionName = fieldDef.referenceConfig().targetCollection();
        CollectionDefinition targetDef = resolveCollection(targetCollectionName, request);
        if (targetDef == null) {
            logger.debug("Field include '{}' references unknown collection '{}', skipping",
                    fieldIncludeName, targetCollectionName);
            // Still recognized as a field include — don't fall through to other passes.
            return true;
        }

        // Inject relationships.<field>.data on each primary resource and collect FK values.
        java.util.Set<Object> fkValueSet = new java.util.LinkedHashSet<>();
        for (int i = 0; i < primaryData.size(); i++) {
            Object fkValue = primaryData.get(i).get(fieldIncludeName);
            Map<String, Object> resource = primaryResources.get(i);
            injectRelationshipEntry(resource, fieldIncludeName, targetCollectionName, fkValue);
            if (fkValue != null) {
                fkValueSet.add(fkValue);
            }
        }

        if (fkValueSet.isEmpty()) {
            return true;
        }

        List<Map<String, Object>> targetRecords = queryChildRecords(
                targetDef, "id", new ArrayList<>(fkValueSet), request);
        for (Map<String, Object> rec : targetRecords) {
            includedAccumulator.add(toJsonApiResourceObject(rec, targetCollectionName, targetDef));
        }
        logger.debug("Resolved field include '{}' -> '{}': {} FK value(s), {} record(s) hydrated",
                fieldIncludeName, targetCollectionName, fkValueSet.size(), targetRecords.size());
        return true;
    }

    /**
     * Ensures a {@code relationships.<fieldName>.data} entry exists on the given
     * JSON:API resource object. Creates the {@code relationships} block if it
     * isn't present yet, and writes {@code { type, id }} for a non-null FK or
     * {@code null} for a null FK. Existing entries for the same field name are
     * left untouched — relationship metadata emitted by
     * {@link #toJsonApiResourceObject} wins.
     */
    @SuppressWarnings("unchecked")
    private void injectRelationshipEntry(Map<String, Object> resource, String fieldName,
                                          String targetType, Object fkValue) {
        if (resource == null) {
            return;
        }
        Map<String, Object> relationships = (Map<String, Object>) resource.get("relationships");
        if (relationships == null) {
            relationships = new java.util.HashMap<>();
            resource.put("relationships", relationships);
        }
        if (relationships.containsKey(fieldName)) {
            return;
        }
        Map<String, Object> relData = new java.util.HashMap<>();
        if (fkValue != null) {
            relData.put("data", Map.of("type", targetType, "id", fkValue));
        } else {
            relData.put("data", null);
        }
        relationships.put(fieldName, relData);
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
     */
    private boolean cacheable(CollectionDefinition definition) {
        return definition.systemCollection()
                && !definition.readOnly()
                && !NEVER_CACHED_SYSTEM_COLLECTIONS.contains(definition.name())
                && systemCollectionCache != null;
    }

    /**
     * Evicts cached entries for a system collection after a write operation.
     * No-op if the collection is not a system collection or no cache is configured.
     *
     * @param definition the collection definition
     * @param request the HTTP servlet request (used to extract tenant ID)
     */
    private void evictSystemCollectionCache(CollectionDefinition definition, HttpServletRequest request) {
        if (!definition.systemCollection() || systemCollectionCache == null) {
            return;
        }
        String tenantId = request.getHeader("X-Tenant-ID");
        systemCollectionCache.evict(tenantId, definition.name());
        logger.debug("Evicted system collection cache for: {} (tenant={})", definition.name(), tenantId);
    }

    /**
     * Queries child records from a collection using an IN filter on the given
     * reference field.
     *
     * <p>Pages through every matching record so JSON:API include resolution
     * stays complete. The previous single-page (1×1000) cap silently truncated
     * include results — e.g. on the bulk
     * {@code GET /api/collections?include=fields} call, ~92 collections × 10-20
     * fields each can exceed 1000 included rows. The truncated set still
     * returns 200, but newly-added fields tail-end of the list disappear from
     * the SPA's collection store, so layout placements that reference them
     * silently drop on render.
     */
    private static final int INCLUDE_PAGE_SIZE = 1000;

    private List<Map<String, Object>> queryChildRecords(
            CollectionDefinition childDef,
            String refField,
            List<Object> parentIds,
            HttpServletRequest request) {

        List<FilterCondition> filters = new ArrayList<>();
        filters.add(new FilterCondition(refField, FilterOperator.IN, parentIds));

        List<Map<String, Object>> aggregated = new ArrayList<>();
        try {
            int page = 1;
            while (true) {
                QueryRequest childQuery = new QueryRequest(
                        new Pagination(page, INCLUDE_PAGE_SIZE),
                        List.of(),
                        List.of(),
                        filters
                );
                childQuery = injectTenantFilter(childQuery, childDef, request);
                QueryResult childResult = queryEngine.executeQuery(childDef, childQuery);
                List<Map<String, Object>> data = childResult.data();
                if (data.isEmpty()) {
                    break;
                }
                aggregated.addAll(filterSharedSystemRows(childDef, data, request));
                if (data.size() < INCLUDE_PAGE_SIZE) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            logger.warn("Failed to query child records for '{}': {}", childDef.name(),
                    e.getMessage());
            return List.of();
        }
        return aggregated;
    }

}
