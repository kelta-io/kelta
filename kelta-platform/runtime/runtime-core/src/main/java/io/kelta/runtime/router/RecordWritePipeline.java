package io.kelta.runtime.router;

import io.kelta.runtime.context.GeoHeaders;
import io.kelta.runtime.context.GeoStamp;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.router.jsonapi.JsonApiRecordSerializer;
import io.kelta.runtime.router.jsonapi.JsonApiRequestBody;
import io.kelta.runtime.storage.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The single write path behind {@link DynamicCollectionRouter}'s create / update (PUT and PATCH) /
 * delete handlers, for both top-level ({@code /api/{collection}[/{id}]}) and sub-resource
 * ({@code /api/{parent}/{parentId}/{child}[/{childId}]}) routes.
 *
 * <p>The router resolves the target collection (and 404s when it can't); everything after that is
 * here, in this fixed order — it is the order the goldens in
 * {@code DynamicCollectionRouterGoldenTest} pin, so do not reorder:
 * <ol>
 *   <li>read-only collection → {@code 403} (JSON:API error body for create/update, empty for delete);</li>
 *   <li>sub-resource update/delete only: the child must exist, be visible to the caller's tenant and
 *       reference the URL's parent ({@link RecordTenantScope#findChild}) — else {@code 404} with
 *       nothing written;</li>
 *   <li>storage data = body attributes + relationship ids (+ the parent reference on a child create);</li>
 *   <li>audit stamp from {@code X-User-Id}, resolved to a platform-user UUID
 *       ({@code createdBy}+{@code updatedBy} on create, {@code updatedBy} on update);</li>
 *   <li>geo stamp ({@code createdGeo}/{@code updatedGeo}) for {@code captureGeo} collections;</li>
 *   <li>create: {@code tenantId} for tenant-scoped system collections;
 *       update/delete: the {@code If-Match} optimistic-lock check (top-level routes only);</li>
 *   <li>the storage call (which runs before-save hooks, validation and the record-changed event);</li>
 *   <li>system-collection cache eviction — after the write, and only when it took effect;</li>
 *   <li>the JSON:API response, echoing the caller's relationships.</li>
 * </ol>
 *
 * <p>Per-route differences are explicit in {@link WriteTarget}, not branches on the URL shape.
 */
final class RecordWritePipeline {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * What a write targets and how its route differs from the others.
     *
     * @param definition the collection being written
     * @param resourceType the URL's collection segment — the JSON:API {@code type} of the response
     *        and the name in the read-only error
     * @param parentRefField on a sub-resource route, the child's field referencing the parent
     *        ({@code null} on top-level routes)
     * @param parentId on a sub-resource route, the URL's parent id ({@code null} on top-level routes)
     * @param optimisticLocking whether {@code If-Match} is enforced on update/delete. True for
     *        top-level routes; the sub-resource routes have never checked it.
     */
    record WriteTarget(CollectionDefinition definition, String resourceType,
                       String parentRefField, String parentId, boolean optimisticLocking) {

        static WriteTarget collection(CollectionDefinition definition, String collectionName) {
            return new WriteTarget(definition, collectionName, null, null, true);
        }

        static WriteTarget child(SubResourceRelation relation, String childName, String parentId) {
            return new WriteTarget(relation.childDef(), childName, relation.parentRefFieldName(),
                    parentId, false);
        }
    }

    private final QueryEngine queryEngine;
    private final RecordTenantScope tenantScope;
    private final Supplier<UserIdResolver> userIdResolver;
    private final Supplier<SystemCollectionCache> systemCollectionCache;

    RecordWritePipeline(QueryEngine queryEngine, RecordTenantScope tenantScope,
                        Supplier<UserIdResolver> userIdResolver,
                        Supplier<SystemCollectionCache> systemCollectionCache) {
        this.queryEngine = queryEngine;
        this.tenantScope = tenantScope;
        this.userIdResolver = userIdResolver;
        this.systemCollectionCache = systemCollectionCache;
    }

    ResponseEntity<Map<String, Object>> create(WriteTarget target, Map<String, Object> requestBody,
                                               HttpServletRequest request) {
        CollectionDefinition definition = target.definition();
        if (definition.readOnly()) {
            return readOnly(target);
        }

        Map<String, Object> data = storageData(requestBody);
        if (target.parentRefField() != null) {
            // Auto-set the parent reference field
            data.put(target.parentRefField(), target.parentId());
        }

        // Inject audit fields from gateway-forwarded user ID (resolved to platform_user UUID)
        String userId = resolveUserId(request);
        if (userId != null) {
            data.put("createdBy", userId);
            data.put("updatedBy", userId);
        }
        stampGeo(data, definition, request, true);

        // Inject tenant ID for tenant-scoped system collections
        RecordTenantScope.injectTenantId(data, definition, request.getHeader("X-Tenant-ID"));

        Map<String, Object> created = queryEngine.create(definition, data);

        evictSystemCollectionCache(definition, request);

        // Return in JSON:API format, echoing caller-supplied relationships
        Map<String, Object> response = JsonApiRecordSerializer.document(created, target.resourceType(), definition);
        JsonApiRecordSerializer.mergeRequestRelationships(response, JsonApiRequestBody.rawRelationships(requestBody));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    ResponseEntity<Map<String, Object>> update(WriteTarget target, String id, Map<String, Object> requestBody,
                                               HttpServletRequest request) {
        CollectionDefinition definition = target.definition();
        if (definition.readOnly()) {
            return readOnly(target);
        }
        if (!childInScope(target, id, request)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> data = storageData(requestBody);

        // Inject audit field from gateway-forwarded user ID (resolved to platform_user UUID)
        String userId = resolveUserId(request);
        if (userId != null) {
            data.put("updatedBy", userId);
        }
        stampGeo(data, definition, request, false);

        if (target.optimisticLocking()) {
            // Optimistic locking: reject a stale If-Match before mutating (slice 5).
            enforceIfMatch(definition, id, request);
        }

        Optional<Map<String, Object>> updated = queryEngine.update(definition, id, data);

        // Evict system collection cache after write
        if (updated.isPresent()) {
            evictSystemCollectionCache(definition, request);
        }

        Map<String, Object> rawRels = JsonApiRequestBody.rawRelationships(requestBody);
        return updated.map(r -> {
                    Map<String, Object> response = JsonApiRecordSerializer.document(
                            r, target.resourceType(), definition);
                    JsonApiRecordSerializer.mergeRequestRelationships(response, rawRels);
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    ResponseEntity<Void> delete(WriteTarget target, String id, HttpServletRequest request) {
        CollectionDefinition definition = target.definition();
        // Reject deletes on read-only collections
        if (definition.readOnly()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!childInScope(target, id, request)) {
            return ResponseEntity.notFound().build();
        }

        if (target.optimisticLocking()) {
            // Optimistic locking: reject a stale If-Match before deleting (slice 5).
            enforceIfMatch(definition, id, request);
        }

        boolean deleted = queryEngine.delete(definition, id);

        // Evict system collection cache after delete
        if (deleted) {
            evictSystemCollectionCache(definition, request);
        }

        return deleted ? ResponseEntity.noContent().build()
                       : ResponseEntity.notFound().build();
    }

    /**
     * On a sub-resource route, whether the record being updated or deleted is one the route may
     * touch: it exists, the caller's tenant may see it, and it references the URL's parent — the
     * same rule the sub-resource get applies. Always true on a top-level route.
     */
    private boolean childInScope(WriteTarget target, String id, HttpServletRequest request) {
        if (target.parentRefField() == null) {
            return true;
        }
        return tenantScope.findChild(target.definition(), target.parentRefField(), target.parentId(), id, request)
                .isPresent();
    }

    private static ResponseEntity<Map<String, Object>> readOnly(WriteTarget target) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                JsonApiRecordSerializer.forbidden("Collection '" + target.resourceType() + "' is read-only"));
    }

    /** Attributes plus relationship ids, merged into a fresh mutable map for storage. */
    private static Map<String, Object> storageData(Map<String, Object> requestBody) {
        Map<String, Object> attributes = JsonApiRequestBody.attributes(requestBody);
        Map<String, Object> relationships = JsonApiRequestBody.relationshipFieldValues(requestBody);

        Map<String, Object> data = new java.util.HashMap<>(attributes);
        data.putAll(relationships);
        return data;
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
        String current = RecordETag.of(existing.get());
        if (current != null && !ifMatch.trim().equals(current)) {
            throw new StaleWriteException(definition.name(), id);
        }
    }

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
        UserIdResolver resolver = userIdResolver.get();
        if (resolver != null && !UUID_PATTERN.matcher(userId).matches()) {
            String tenantId = request.getHeader("X-Tenant-ID");
            String resolved = resolver.resolve(userId, tenantId);
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
    private static void stampGeo(Map<String, Object> data, CollectionDefinition definition,
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
     * Evicts cached entries for a system collection after a write operation.
     * No-op if the collection is not a system collection or no cache is configured.
     */
    private void evictSystemCollectionCache(CollectionDefinition definition, HttpServletRequest request) {
        SystemCollectionCache cache = systemCollectionCache.get();
        if (!definition.systemCollection() || cache == null) {
            return;
        }
        String tenantId = request.getHeader("X-Tenant-ID");
        cache.evict(tenantId, definition.name());
        logger.debug("Evicted system collection cache for: {} (tenant={})", definition.name(), tenantId);
    }
}
