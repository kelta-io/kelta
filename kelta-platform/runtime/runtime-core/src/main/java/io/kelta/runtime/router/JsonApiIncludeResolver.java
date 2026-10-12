package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.router.jsonapi.JsonApiRecordSerializer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves JSON:API {@code ?include=} for {@link DynamicCollectionRouter}'s list and get-by-id
 * handlers: has-many, belongs-to, field-name ("lookup field") and transitive (grandchild)
 * includes. It queries storage, so it lives beside the router rather than in the pure
 * {@code router.jsonapi} package; included rows get the same tenant scoping as a list read
 * ({@link RecordTenantScope}).
 */
final class JsonApiIncludeResolver {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    /**
     * Page size used while paging through every included record.
     *
     * <p>Pages through every matching record so JSON:API include resolution stays complete. The
     * previous single-page (1×1000) cap silently truncated include results — e.g. on the bulk
     * {@code GET /api/collections?include=fields} call, ~92 collections × 10-20 fields each can
     * exceed 1000 included rows. The truncated set still returns 200, but newly-added fields
     * tail-end of the list disappear from the SPA's collection store, so layout placements that
     * reference them silently drop on render.
     */
    private static final int INCLUDE_PAGE_SIZE = 1000;

    private final QueryEngine queryEngine;
    private final CollectionResolver collections;
    private final RecordTenantScope tenantScope;

    JsonApiIncludeResolver(QueryEngine queryEngine, CollectionResolver collections,
                           RecordTenantScope tenantScope) {
        this.queryEngine = queryEngine;
        this.collections = collections;
        this.tenantScope = tenantScope;
    }

    /**
     * Parses the {@code include} query parameter into a list of relationship names.
     *
     * @param params the query parameters
     * @return list of include names, or empty list if not present
     */
    static List<String> parseIncludeParam(MultiValueMap<String, String> params) {
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
    List<Map<String, Object>> resolve(
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
            CollectionDefinition childDef = collections.resolve(includeName, request);
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
                    allIncluded.add(JsonApiRecordSerializer.resourceObject(childRecord, includeName, childDef));
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
                        allIncluded.add(JsonApiRecordSerializer.resourceObject(rec, includeName, childDef));
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
            CollectionDefinition grandchildDef = collections.resolve(includeName, request);
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
                        allIncluded.add(JsonApiRecordSerializer.resourceObject(record, includeName, grandchildDef));
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
                        allIncluded.add(JsonApiRecordSerializer.resourceObject(record, includeName, grandchildDef));
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
        CollectionDefinition targetDef = collections.resolve(targetCollectionName, request);
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
            JsonApiRecordSerializer.injectRelationshipEntry(resource, fieldIncludeName, targetCollectionName, fkValue);
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
            includedAccumulator.add(JsonApiRecordSerializer.resourceObject(rec, targetCollectionName, targetDef));
        }
        logger.debug("Resolved field include '{}' -> '{}': {} FK value(s), {} record(s) hydrated",
                fieldIncludeName, targetCollectionName, fkValueSet.size(), targetRecords.size());
        return true;
    }

    /**
     * Queries child records from a collection using an IN filter on the given reference field,
     * paging through every match (see {@link #INCLUDE_PAGE_SIZE}). A failing query is logged and
     * yields no included rows rather than failing the primary read.
     */
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
                childQuery = tenantScope.injectTenantFilter(childQuery, childDef, request);
                QueryResult childResult = queryEngine.executeQuery(childDef, childQuery);
                List<Map<String, Object>> data = childResult.data();
                if (data.isEmpty()) {
                    break;
                }
                aggregated.addAll(tenantScope.filterSharedSystemRows(childDef, data, request));
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
