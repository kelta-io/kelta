package io.kelta.runtime.router.jsonapi;

import io.kelta.jsonapi.PaginationLinks;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryResult;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shapes collection records into the JSON:API documents {@code DynamicCollectionRouter} returns.
 *
 * <p>Pure and stateless — no I/O, no tenant or request state beyond what is passed in. This is the
 * single place a record becomes a resource object, so it is also the response-side gate for
 * server-only fields and orphan columns (see {@link #resourceObject}).
 *
 * <p>The documents are plain mutable {@code Map}s on purpose: the worker's controller advices
 * ({@code CerbosFieldSecurityAdvice}, masking, {@code AttachmentUrlEnricher}, record authorization)
 * rewrite them in place after the handler returns. {@code runtime-jsonapi}'s
 * {@code JsonApiResponseBuilder} does not model relationships, server-only/orphan filtering or the
 * list {@code meta}/{@code metadata}/{@code links} envelope, so the router does not use it; see
 * {@code concerns.md} for the remaining overlap.
 */
public final class JsonApiRecordSerializer {

    /**
     * Framework metadata keys that belong in every record's response envelope even though they
     * have no FieldDefinition. Aligned with PhysicalTableStorageAdapter's PAYLOAD_SYSTEM_KEYS
     * (minus "id", which is hoisted to the top level). Used by {@link #resourceObject} to
     * distinguish metadata from orphan columns left behind when a field is deleted.
     */
    private static final Set<String> SYSTEM_ATTRIBUTE_KEYS = Set.of(
            "createdAt", "updatedAt", "createdBy", "updatedBy",
            "createdGeo", "updatedGeo", "tenantId", "recordTypeId");

    /**
     * Suffixes appended to a primary field name to form companion column keys for CURRENCY and
     * GEOLOCATION fields. Used to keep {@code <field>_currency_code}, {@code <field>_longitude},
     * {@code <field>_latitude} in the response envelope while still dropping the same suffixes
     * when no live primary field exists.
     */
    private static final String[] COMPANION_SUFFIXES = {
            "_currency_code", "_longitude", "_latitude"};

    private JsonApiRecordSerializer() {
    }

    /**
     * Converts a record to a JSON:API resource object using the collection definition to identify
     * relationship fields.
     *
     * <p>Relationship fields (those with {@code type.isRelationship()}) are placed in the
     * {@code relationships} section with their target collection as the JSON:API type. All other
     * fields go in {@code attributes}.
     *
     * @param record the record data
     * @param type the resource type (collection name)
     * @param definition the collection definition for field metadata
     * @return the JSON:API resource object
     */
    public static Map<String, Object> resourceObject(Map<String, Object> record, String type,
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
            if (fieldDef != null && fieldDef.serverOnly()) {
                // The query engine already strips these; never serialize one that slipped in.
                continue;
            }
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

    private static boolean isSystemOrCompanionKey(String key, CollectionDefinition definition) {
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
    public static Map<String, Object> document(Map<String, Object> record, String type,
                                               CollectionDefinition definition) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("data", resourceObject(record, type, definition));
        return response;
    }

    /**
     * Converts a QueryResult into a JSON:API list response with proper relationship formatting,
     * pagination metadata, and a {@code links} block carrying {@code self} / {@code prev} /
     * {@code next} URLs.
     *
     * @param result the query result
     * @param type the resource type (collection name)
     * @param definition the collection definition
     * @param requestPath the request URI (used as the {@code self}/{@code prev}/{@code next} URL base)
     * @param params the original query parameters
     * @return the JSON:API document with {@code data} array, {@code metadata}, and {@code links}
     */
    public static Map<String, Object> listDocument(QueryResult result, String type,
                                                   CollectionDefinition definition,
                                                   String requestPath,
                                                   MultiValueMap<String, String> params) {
        List<Map<String, Object>> jsonApiData = new ArrayList<>();
        for (Map<String, Object> record : result.data()) {
            jsonApiData.add(resourceObject(record, type, definition));
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
     * Returns the raw {@code page[size]} value from the request params, or {@code null} if absent,
     * blank, or non-numeric. Used to detect when {@link Pagination#fromParams(Map)} clamped the
     * caller's value so the response can echo what was originally requested.
     */
    private static Integer parseRequestedPageSize(MultiValueMap<String, String> params) {
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

    /**
     * Merges the caller-supplied relationships from a request into a response document so that
     * POST/PATCH responses always echo the relationships the caller asserted. Field-derived
     * relationships (REFERENCE/LOOKUP/MASTER_DETAIL columns picked up by {@link #resourceObject})
     * already in the response are preserved and take precedence — request-supplied entries fill
     * the gaps for relationship names that don't map to a REFERENCE-typed field.
     */
    @SuppressWarnings("unchecked")
    public static void mergeRequestRelationships(Map<String, Object> response,
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
     * Ensures a {@code relationships.<fieldName>.data} entry exists on the given JSON:API resource
     * object. Creates the {@code relationships} block if it isn't present yet, and writes
     * {@code { type, id }} for a non-null FK or {@code null} for a null FK. Existing entries for the
     * same field name are left untouched — relationship metadata emitted by
     * {@link #resourceObject} wins.
     */
    @SuppressWarnings("unchecked")
    public static void injectRelationshipEntry(Map<String, Object> resource, String fieldName,
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
}
