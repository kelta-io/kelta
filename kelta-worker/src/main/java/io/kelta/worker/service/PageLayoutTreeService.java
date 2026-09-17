package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and applies a whole page layout — sections, field placements, related lists and the
 * layout header — as one document, addressed by <b>names</b> rather than ids.
 *
 * <p>Building a layout through the generic collection routes is four parent→child collections
 * ({@code page-layouts} → {@code layout-sections} → {@code layout-fields}, plus
 * {@code layout-related-lists}) and one request per row, with a field-id lookup per placement.
 * {@link #applyTree} collapses that into a single idempotent upsert: sections match on
 * {@code heading}, placements on the field's <b>name</b>, related lists on
 * (related collection, relationship field). Anything the body no longer lists is deleted.
 * Applying the same body twice reports {@code created=0, updated=0, deleted=0} — the counts
 * are the API's diff, so a caller can assert convergence instead of re-reading.
 *
 * <p>Every write goes through {@link QueryEngine}, the same path the admin API and MCP tools
 * use, so {@code PageLayoutConfigEventPublisher}, {@code LayoutSectionRefreshHook},
 * {@code LayoutFieldRefreshHook} and {@code LayoutRelatedListRefreshHook} fire and the
 * {@code kelta.config.layout.changed.<layoutId>} broadcast reaches every pod. Reads resolve
 * ids → names with plain SQL (the repository idiom) because neither the registry's
 * {@code CollectionDefinition} nor its {@code FieldDefinition} carries the metadata row id.
 *
 * <p><b>Scalar vs. tree semantics.</b> The child arrays are authoritative: a section, placement
 * or related list absent from the body is deleted. The layout's own scalars ({@code name},
 * {@code layoutType}, {@code isDefault}, {@code description}, {@code headerConfig}) are applied
 * only when the body carries the key, so a body that manages fields does not blank the header.
 * {@code relatedLists} is authoritative when present and left untouched when absent.
 *
 * @since 1.0.0
 */
@Service
public class PageLayoutTreeService {

    private static final Logger log = LoggerFactory.getLogger(PageLayoutTreeService.class);

    /**
     * Body keys the tree accepts. The read-only echo keys ({@code layoutId}, {@code collection})
     * let a GET body round-trip through PUT; they are not applied, but they must agree with the
     * layout the caller addressed — see {@link #parse}.
     */
    private static final Set<String> BODY_KEYS = Set.of(
            "name", "layoutType", "isDefault", "description", "headerConfig",
            "sections", "relatedLists", "layoutId", "collection");
    private static final Set<String> SECTION_KEYS = Set.of("heading", "columns", "collapsed", "fields");
    private static final Set<String> FIELD_KEYS = Set.of(
            "name", "column", "label", "helpText", "readOnly", "required");
    private static final Set<String> RELATED_KEYS = Set.of(
            "collection", "relationshipField", "displayColumns", "sortField", "sortDirection", "rowLimit");

    /** Layout scalars a PUT may set; the read-only echo keys are deliberately not here. */
    private static final Set<String> LAYOUT_SCALARS = Set.of(
            "name", "layoutType", "isDefault", "description", "headerConfig");

    private static final int MAX_COLUMNS = 4;

    // Every lookup carries the caller's tenant. A layout belongs to the tenant that authored it
    // (page_layout.tenant_id), even when its collection is a shared system collection — those
    // live once, in the platform tenant, and every tenant may lay them out. So a layout id from
    // another tenant reads as "not found", and a collection name resolves to the caller's own
    // collection or to a system collection, never to another tenant's.
    private static final String SELECT_LAYOUT = """
            SELECT id, collection_id, name, description, layout_type, is_default, header_config
            FROM page_layout WHERE id = ? AND tenant_id = ?
            """;

    private static final String SELECT_LAYOUTS_BY_COLLECTION = """
            SELECT id, name FROM page_layout WHERE collection_id = ? AND tenant_id = ?
            """;

    // A tenant's own collection wins over a system collection of the same name.
    private static final String SELECT_COLLECTION_ID_BY_NAME = """
            SELECT id FROM collection WHERE name = ? AND active = true
              AND (tenant_id = ? OR system_collection = true)
            ORDER BY system_collection LIMIT 1
            """;

    private static final String SELECT_COLLECTION_NAME_BY_ID = """
            SELECT name FROM collection WHERE id = ? AND active = true
              AND (tenant_id = ? OR system_collection = true)
            LIMIT 1
            """;

    private static final String SELECT_FIELDS_BY_COLLECTION = """
            SELECT id, name, reference_target, reference_collection_id
            FROM field WHERE collection_id = ? AND active = true
            ORDER BY field_order, created_at, id
            """;

    private static final String SELECT_SECTIONS = """
            SELECT id, heading, columns, collapsed, sort_order
            FROM layout_section WHERE layout_id = ?
            ORDER BY sort_order, id
            """;

    private static final String SELECT_PLACEMENTS = """
            SELECT lf.id, lf.section_id, lf.field_id, lf.column_number, lf.sort_order,
                   lf.is_required_on_layout, lf.is_read_only_on_layout,
                   lf.label_override, lf.help_text_override, f.name AS field_name
            FROM layout_field lf
            JOIN layout_section s ON s.id = lf.section_id
            JOIN field f ON f.id = lf.field_id
            WHERE s.layout_id = ?
            ORDER BY s.sort_order, lf.sort_order, lf.column_number, lf.id
            """;

    private static final String SELECT_RELATED_LISTS = """
            SELECT rl.id, rl.related_collection_id, rl.relationship_field_id, rl.display_columns,
                   rl.sort_field, rl.sort_direction, rl.row_limit, rl.sort_order,
                   c.name AS related_collection_name, f.name AS relationship_field_name
            FROM layout_related_list rl
            JOIN collection c ON c.id = rl.related_collection_id
            JOIN field f ON f.id = rl.relationship_field_id
            WHERE rl.layout_id = ?
            ORDER BY rl.sort_order, rl.id
            """;

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PageLayoutTreeService(QueryEngine queryEngine, CollectionRegistry collectionRegistry,
                                 JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Counts of what an apply did, per row across all four collections (the layout row included). */
    public record TreeCounts(int created, int updated, int deleted, int unchanged) {}

    /** Result of an apply: the resolved layout plus the diff it produced. */
    public record ApplyResult(String layoutId, String collection, String name, TreeCounts counts) {}

    /** One rejected body position: a JSON Pointer into the request body and what is wrong there. */
    public record TreeError(String pointer, String detail) {}

    /** Body rejected before anything was written; carries every position that failed. */
    public static class TreeValidationException extends RuntimeException {
        private final transient List<TreeError> errors;

        public TreeValidationException(List<TreeError> errors) {
            super(errors.isEmpty() ? "Invalid layout tree" : errors.get(0).detail());
            this.errors = List.copyOf(errors);
        }

        public List<TreeError> errors() {
            return errors;
        }
    }

    /** The addressed layout (or its collection) does not exist. */
    public static class LayoutNotFoundException extends RuntimeException {
        public LayoutNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Reads the layout as a name-addressed tree. The result is exactly the document
     * {@link #applyTree} accepts, so feeding it straight back reports no diff.
     */
    public Map<String, Object> readTree(String layoutId) {
        Map<String, Object> layout = loadLayout(layoutId);
        String collectionName = collectionNameById(asString(layout.get("collection_id")));

        List<Map<String, Object>> sectionRows = jdbcTemplate.queryForList(SELECT_SECTIONS, layoutId);
        List<Map<String, Object>> placementRows = jdbcTemplate.queryForList(SELECT_PLACEMENTS, layoutId);
        List<Map<String, Object>> relatedRows = jdbcTemplate.queryForList(SELECT_RELATED_LISTS, layoutId);

        Map<String, List<Map<String, Object>>> placementsBySection = new LinkedHashMap<>();
        for (Map<String, Object> row : placementRows) {
            placementsBySection
                    .computeIfAbsent(asString(row.get("section_id")), k -> new ArrayList<>())
                    .add(row);
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        for (Map<String, Object> sectionRow : sectionRows) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map<String, Object> placement
                    : placementsBySection.getOrDefault(asString(sectionRow.get("id")), List.of())) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", asString(placement.get("field_name")));
                field.put("column", intOr(placement.get("column_number"), 0));
                field.put("label", asString(placement.get("label_override")));
                field.put("helpText", asString(placement.get("help_text_override")));
                field.put("readOnly", boolOr(placement.get("is_read_only_on_layout"), false));
                field.put("required", boolOr(placement.get("is_required_on_layout"), false));
                fields.add(field);
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("heading", asString(sectionRow.get("heading")));
            section.put("columns", intOr(sectionRow.get("columns"), 2));
            section.put("collapsed", boolOr(sectionRow.get("collapsed"), false));
            section.put("fields", fields);
            sections.add(section);
        }

        List<Map<String, Object>> relatedLists = new ArrayList<>();
        for (Map<String, Object> row : relatedRows) {
            Map<String, Object> related = new LinkedHashMap<>();
            related.put("collection", asString(row.get("related_collection_name")));
            related.put("relationshipField", asString(row.get("relationship_field_name")));
            related.put("displayColumns", parseJson(row.get("display_columns")));
            related.put("sortField", asString(row.get("sort_field")));
            related.put("sortDirection", asString(row.get("sort_direction")));
            related.put("rowLimit", intOr(row.get("row_limit"), 10));
            relatedLists.add(related);
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("layoutId", layoutId);
        tree.put("collection", collectionName);
        tree.put("name", asString(layout.get("name")));
        tree.put("layoutType", asString(layout.get("layout_type")));
        tree.put("isDefault", boolOr(layout.get("is_default"), false));
        tree.put("description", asString(layout.get("description")));
        tree.put("headerConfig", parseJson(layout.get("header_config")));
        tree.put("sections", sections);
        tree.put("relatedLists", relatedLists);
        return tree;
    }

    /** Applies the tree to an existing layout addressed by id. */
    public ApplyResult applyTree(String layoutId, Map<String, Object> body) {
        Map<String, Object> layout = loadLayout(layoutId);
        String collectionId = asString(layout.get("collection_id"));
        String collectionName = collectionNameById(collectionId);

        DesiredTree desired = parse(body, collectionId, collectionName, null, layoutId);
        Counts counts = new Counts();
        applyLayoutScalars(layoutId, layout, body, counts);
        applyChildren(layoutId, collectionId, desired, counts);

        String name = body != null && body.get("name") instanceof String s && !s.isBlank()
                ? s : asString(layout.get("name"));
        return new ApplyResult(layoutId, collectionName, name, counts.toRecord());
    }

    /**
     * Applies the tree to the named layout of the named collection, creating the layout row
     * when it does not exist yet. The path name is the layout's identity: a body {@code name}
     * that disagrees is rejected rather than silently renaming the row the caller addressed.
     */
    public ApplyResult applyTreeByName(String collectionName, String layoutName, Map<String, Object> body) {
        String collectionId = collectionIdByName(collectionName);
        String layoutId = findLayoutIdByName(collectionId, layoutName);
        DesiredTree desired = parse(body, collectionId, collectionName, layoutName, layoutId);

        Counts counts = new Counts();
        if (layoutId == null) {
            layoutId = createLayout(collectionId, layoutName, body);
            counts.created++;
        } else {
            applyLayoutScalars(layoutId, loadLayout(layoutId), body, counts);
        }
        applyChildren(layoutId, collectionId, desired, counts);
        return new ApplyResult(layoutId, collectionName, layoutName, counts.toRecord());
    }

    // ------------------------------------------------------------------
    // Parsing + validation — the whole body is checked before anything is written
    // ------------------------------------------------------------------

    private record DesiredField(String name, String fieldId, int column, String label, String helpText,
                                boolean readOnly, boolean required, int sortOrder) {}

    private record DesiredSection(String heading, int columns, boolean collapsed,
                                  List<DesiredField> fields, int sortOrder) {}

    private record DesiredRelated(String collection, String collectionId, String relationshipField,
                                  String relationshipFieldId, List<Object> displayColumns,
                                  String sortField, String sortDirection, Integer rowLimit, int sortOrder) {}

    private record DesiredTree(List<DesiredSection> sections, List<DesiredRelated> relatedLists,
                               boolean relatedListsManaged) {}

    @SuppressWarnings("unchecked")
    private DesiredTree parse(Map<String, Object> body, String collectionId, String collectionName,
                              String pathLayoutName, String targetLayoutId) {
        List<TreeError> errors = new ArrayList<>();
        Map<String, Object> safeBody = body == null ? Map.of() : body;

        for (String key : safeBody.keySet()) {
            if (!BODY_KEYS.contains(key)) {
                errors.add(new TreeError("/" + key, "Unknown layout tree property '" + key + "'"));
            }
        }
        if (pathLayoutName != null && safeBody.get("name") instanceof String bodyName
                && !bodyName.equals(pathLayoutName)) {
            errors.add(new TreeError("/name", "Body name '" + bodyName
                    + "' does not match the layout named in the path ('" + pathLayoutName + "')"));
        }
        // The echo keys let a GET body round-trip through PUT unedited. They only ever describe
        // the layout the caller addressed, so a mismatch means one layout's tree is being pasted
        // over another — reject it rather than silently rewriting the addressed layout.
        if (safeBody.get("collection") instanceof String bodyCollection
                && !bodyCollection.equals(collectionName)) {
            errors.add(new TreeError("/collection", "Body collection '" + bodyCollection
                    + "' does not match the addressed layout's collection ('" + collectionName + "')"));
        }
        if (targetLayoutId != null && safeBody.get("layoutId") instanceof String bodyLayoutId
                && !bodyLayoutId.equals(targetLayoutId)) {
            errors.add(new TreeError("/layoutId", "Body layoutId '" + bodyLayoutId
                    + "' does not match the addressed layout ('" + targetLayoutId + "')"));
        }

        Map<String, String> fieldIdsByName = fieldIdsByName(collectionId);

        Object sectionsRaw = safeBody.get("sections");
        if (!(sectionsRaw instanceof List<?> sectionList)) {
            errors.add(new TreeError("/sections", "'sections' is required and must be an array"));
            throw new TreeValidationException(errors);
        }

        List<DesiredSection> sections = new ArrayList<>();
        Set<String> seenHeadings = new LinkedHashSet<>();
        Set<String> seenFieldNames = new LinkedHashSet<>();
        for (int i = 0; i < sectionList.size(); i++) {
            String sectionPointer = "/sections/" + i;
            if (!(sectionList.get(i) instanceof Map<?, ?> rawSection)) {
                errors.add(new TreeError(sectionPointer, "Section must be an object"));
                continue;
            }
            Map<String, Object> section = (Map<String, Object>) rawSection;
            for (String key : section.keySet()) {
                if (!SECTION_KEYS.contains(key)) {
                    errors.add(new TreeError(sectionPointer + "/" + key,
                            "Unknown section property '" + key + "'"));
                }
            }

            String heading = section.get("heading") instanceof String s && !s.isBlank() ? s : null;
            if (heading == null) {
                errors.add(new TreeError(sectionPointer + "/heading",
                        "'heading' is required — it is the key sections are matched on"));
            } else if (!seenHeadings.add(heading)) {
                errors.add(new TreeError(sectionPointer + "/heading",
                        "Duplicate section heading '" + heading + "'"));
            }

            int columns = 2;
            Object columnsRaw = section.get("columns");
            if (columnsRaw != null) {
                Integer parsed = asInteger(columnsRaw);
                if (parsed == null || parsed < 1 || parsed > MAX_COLUMNS) {
                    errors.add(new TreeError(sectionPointer + "/columns",
                            "'columns' must be an integer between 1 and " + MAX_COLUMNS));
                } else {
                    columns = parsed;
                }
            }
            boolean collapsed = boolOr(section.get("collapsed"), false);

            List<DesiredField> fields = new ArrayList<>();
            Object fieldsRaw = section.get("fields");
            if (fieldsRaw != null && !(fieldsRaw instanceof List<?>)) {
                errors.add(new TreeError(sectionPointer + "/fields", "'fields' must be an array"));
            } else {
                List<?> fieldList = fieldsRaw == null ? List.of() : (List<?>) fieldsRaw;
                for (int j = 0; j < fieldList.size(); j++) {
                    String fieldPointer = sectionPointer + "/fields/" + j;
                    if (!(fieldList.get(j) instanceof Map<?, ?> rawField)) {
                        errors.add(new TreeError(fieldPointer, "Field placement must be an object"));
                        continue;
                    }
                    Map<String, Object> field = (Map<String, Object>) rawField;
                    for (String key : field.keySet()) {
                        if (!FIELD_KEYS.contains(key)) {
                            errors.add(new TreeError(fieldPointer + "/" + key,
                                    "Unknown field placement property '" + key + "'"));
                        }
                    }

                    String name = field.get("name") instanceof String s && !s.isBlank() ? s : null;
                    String fieldId = name == null ? null : fieldIdsByName.get(name);
                    if (name == null) {
                        errors.add(new TreeError(fieldPointer + "/name", "'name' is required"));
                    } else if (fieldId == null) {
                        errors.add(new TreeError(fieldPointer + "/name",
                                "Field '" + name + "' does not exist on collection '" + collectionName + "'"));
                    } else if (!seenFieldNames.add(name)) {
                        errors.add(new TreeError(fieldPointer + "/name",
                                "Field '" + name + "' is placed more than once in this layout"));
                    }

                    int column = 0;
                    Object columnRaw = field.get("column");
                    if (columnRaw != null) {
                        Integer parsed = asInteger(columnRaw);
                        if (parsed == null || parsed < 0) {
                            errors.add(new TreeError(fieldPointer + "/column",
                                    "'column' must be a non-negative integer"));
                        } else if (parsed >= columns) {
                            errors.add(new TreeError(fieldPointer + "/column",
                                    "'column' " + parsed + " is outside the section's "
                                            + columns + " columns (0-based)"));
                        } else {
                            column = parsed;
                        }
                    }

                    fields.add(new DesiredField(name, fieldId, column,
                            asString(field.get("label")), asString(field.get("helpText")),
                            boolOr(field.get("readOnly"), false),
                            boolOr(field.get("required"), false), j));
                }
            }
            sections.add(new DesiredSection(heading, columns, collapsed, fields, i));
        }

        Object relatedRaw = safeBody.get("relatedLists");
        boolean relatedManaged = relatedRaw != null;
        List<DesiredRelated> relatedLists = new ArrayList<>();
        if (relatedRaw != null && !(relatedRaw instanceof List<?>)) {
            errors.add(new TreeError("/relatedLists", "'relatedLists' must be an array"));
        } else if (relatedRaw != null) {
            List<?> list = (List<?>) relatedRaw;
            Set<String> seenKeys = new LinkedHashSet<>();
            for (int i = 0; i < list.size(); i++) {
                String pointer = "/relatedLists/" + i;
                if (!(list.get(i) instanceof Map<?, ?> rawRelated)) {
                    errors.add(new TreeError(pointer, "Related list must be an object"));
                    continue;
                }
                Map<String, Object> related = (Map<String, Object>) rawRelated;
                for (String key : related.keySet()) {
                    if (!RELATED_KEYS.contains(key)) {
                        errors.add(new TreeError(pointer + "/" + key,
                                "Unknown related list property '" + key + "'"));
                    }
                }
                DesiredRelated parsed = parseRelated(related, pointer, i, collectionId,
                        collectionName, seenKeys, errors);
                if (parsed != null) {
                    relatedLists.add(parsed);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new TreeValidationException(errors);
        }
        return new DesiredTree(sections, relatedLists, relatedManaged);
    }

    private DesiredRelated parseRelated(Map<String, Object> related, String pointer, int index,
                                        String layoutCollectionId, String layoutCollectionName,
                                        Set<String> seenKeys, List<TreeError> errors) {
        String relatedName = related.get("collection") instanceof String s && !s.isBlank() ? s : null;
        if (relatedName == null) {
            errors.add(new TreeError(pointer + "/collection", "'collection' is required"));
            return null;
        }
        String relatedCollectionId = findCollectionIdByName(relatedName);
        if (relatedCollectionId == null) {
            errors.add(new TreeError(pointer + "/collection",
                    "Collection '" + relatedName + "' does not exist"));
            return null;
        }

        String relationshipField = related.get("relationshipField") instanceof String s && !s.isBlank()
                ? s : null;
        if (relationshipField == null) {
            errors.add(new TreeError(pointer + "/relationshipField", "'relationshipField' is required"));
            return null;
        }
        List<Map<String, Object>> relatedFields =
                jdbcTemplate.queryForList(SELECT_FIELDS_BY_COLLECTION, relatedCollectionId);
        Map<String, Object> relationshipRow = relatedFields.stream()
                .filter(row -> relationshipField.equals(asString(row.get("name"))))
                .findFirst().orElse(null);
        if (relationshipRow == null) {
            errors.add(new TreeError(pointer + "/relationshipField",
                    "Field '" + relationshipField + "' does not exist on collection '" + relatedName + "'"));
            return null;
        }
        if (!pointsAt(relationshipRow, layoutCollectionId, layoutCollectionName)) {
            errors.add(new TreeError(pointer + "/relationshipField",
                    "Field '" + relationshipField + "' is not a lookup to '" + layoutCollectionName + "'"));
            return null;
        }
        if (!seenKeys.add(relatedName + "." + relationshipField)) {
            errors.add(new TreeError(pointer + "/relationshipField",
                    "Duplicate related list for '" + relatedName + "." + relationshipField + "'"));
            return null;
        }

        List<Object> displayColumns = new ArrayList<>();
        Object columnsRaw = related.get("displayColumns");
        if (!(columnsRaw instanceof List<?> columnList) || columnList.isEmpty()) {
            errors.add(new TreeError(pointer + "/displayColumns",
                    "'displayColumns' is required and must be a non-empty array of field names"));
        } else {
            Set<String> relatedFieldNames = new LinkedHashSet<>();
            relatedFields.forEach(row -> relatedFieldNames.add(asString(row.get("name"))));
            for (int k = 0; k < columnList.size(); k++) {
                Object column = columnList.get(k);
                if (!(column instanceof String name) || name.isBlank()) {
                    errors.add(new TreeError(pointer + "/displayColumns/" + k,
                            "Display column must be a field name"));
                } else if (!relatedFieldNames.contains(name)) {
                    errors.add(new TreeError(pointer + "/displayColumns/" + k,
                            "Field '" + name + "' does not exist on collection '" + relatedName + "'"));
                } else {
                    displayColumns.add(name);
                }
            }
        }

        String sortField = asString(related.get("sortField"));
        if (sortField != null && relatedFields.stream()
                .noneMatch(row -> sortField.equals(asString(row.get("name"))))) {
            errors.add(new TreeError(pointer + "/sortField",
                    "Field '" + sortField + "' does not exist on collection '" + relatedName + "'"));
        }

        String sortDirection = asString(related.get("sortDirection"));
        if (sortDirection != null && !"ASC".equals(sortDirection) && !"DESC".equals(sortDirection)) {
            errors.add(new TreeError(pointer + "/sortDirection", "'sortDirection' must be ASC or DESC"));
        }

        Integer rowLimit = null;
        Object rowLimitRaw = related.get("rowLimit");
        if (rowLimitRaw != null) {
            rowLimit = asInteger(rowLimitRaw);
            if (rowLimit == null || rowLimit < 1) {
                errors.add(new TreeError(pointer + "/rowLimit", "'rowLimit' must be a positive integer"));
                rowLimit = null;
            }
        }

        return new DesiredRelated(relatedName, relatedCollectionId, relationshipField,
                asString(relationshipRow.get("id")), displayColumns, sortField, sortDirection,
                rowLimit, index);
    }

    /** A relationship field points at the layout's collection by FK id, or by target name for older rows. */
    private static boolean pointsAt(Map<String, Object> fieldRow, String collectionId, String collectionName) {
        String referenceCollectionId = asString(fieldRow.get("reference_collection_id"));
        if (referenceCollectionId != null) {
            return referenceCollectionId.equals(collectionId);
        }
        return collectionName.equals(asString(fieldRow.get("reference_target")));
    }

    // ------------------------------------------------------------------
    // Apply
    // ------------------------------------------------------------------

    private static final class Counts {
        int created;
        int updated;
        int deleted;
        int unchanged;

        TreeCounts toRecord() {
            return new TreeCounts(created, updated, deleted, unchanged);
        }
    }

    private void applyLayoutScalars(String layoutId, Map<String, Object> layoutRow,
                                    Map<String, Object> body, Counts counts) {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("name", asString(layoutRow.get("name")));
        current.put("layoutType", asString(layoutRow.get("layout_type")));
        current.put("isDefault", boolOr(layoutRow.get("is_default"), false));
        current.put("description", asString(layoutRow.get("description")));
        current.put("headerConfig", parseJson(layoutRow.get("header_config")));

        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : LAYOUT_SCALARS) {
            if (body != null && body.containsKey(key) && !Objects.equals(current.get(key), body.get(key))) {
                changes.put(key, body.get(key));
            }
        }
        if (changes.isEmpty()) {
            counts.unchanged++;
            return;
        }
        update("page-layouts", layoutId, changes);
        counts.updated++;
    }

    private String createLayout(String collectionId, String layoutName, Map<String, Object> body) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("collectionId", collectionId);
        attributes.put("name", layoutName);
        attributes.put("layoutType", body.get("layoutType") instanceof String s && !s.isBlank()
                ? s : "DETAIL");
        for (String key : List.of("isDefault", "description", "headerConfig")) {
            if (body.containsKey(key)) {
                attributes.put(key, body.get(key));
            }
        }
        return create("page-layouts", attributes);
    }

    private void applyChildren(String layoutId, String collectionId, DesiredTree desired, Counts counts) {
        Map<String, String> sectionIdsByHeading = applySections(layoutId, desired.sections(), counts);
        applyPlacements(layoutId, desired.sections(), sectionIdsByHeading, counts);
        deleteUnusedSections(layoutId, sectionIdsByHeading, counts);
        if (desired.relatedListsManaged()) {
            applyRelatedLists(layoutId, desired.relatedLists(), counts);
        }
        log.info("Applied layout tree (layoutId={}, collectionId={}, created={}, updated={}, "
                        + "deleted={}, unchanged={})",
                layoutId, collectionId, counts.created, counts.updated, counts.deleted, counts.unchanged);
    }

    private Map<String, String> applySections(String layoutId, List<DesiredSection> desired, Counts counts) {
        List<Map<String, Object>> existing =
                new ArrayList<>(jdbcTemplate.queryForList(SELECT_SECTIONS, layoutId));
        Map<String, String> idsByHeading = new LinkedHashMap<>();

        for (DesiredSection section : desired) {
            Map<String, Object> match = existing.stream()
                    .filter(row -> section.heading().equals(asString(row.get("heading"))))
                    .findFirst().orElse(null);

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("heading", section.heading());
            attributes.put("columns", section.columns());
            attributes.put("collapsed", section.collapsed());
            attributes.put("sortOrder", section.sortOrder());

            if (match == null) {
                attributes.put("layoutId", layoutId);
                idsByHeading.put(section.heading(), create("layout-sections", attributes));
                counts.created++;
                continue;
            }
            existing.remove(match);
            String sectionId = asString(match.get("id"));
            idsByHeading.put(section.heading(), sectionId);

            Map<String, Object> current = new LinkedHashMap<>();
            current.put("heading", asString(match.get("heading")));
            current.put("columns", intOr(match.get("columns"), 2));
            current.put("collapsed", boolOr(match.get("collapsed"), false));
            current.put("sortOrder", intOr(match.get("sort_order"), 0));
            applyDiff("layout-sections", sectionId, current, attributes, counts);
        }
        return idsByHeading;
    }

    private void applyPlacements(String layoutId, List<DesiredSection> desired,
                                 Map<String, String> sectionIdsByHeading, Counts counts) {
        List<Map<String, Object>> existing =
                new ArrayList<>(jdbcTemplate.queryForList(SELECT_PLACEMENTS, layoutId));

        for (DesiredSection section : desired) {
            String sectionId = sectionIdsByHeading.get(section.heading());
            for (DesiredField field : section.fields()) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("sectionId", sectionId);
                attributes.put("fieldId", field.fieldId());
                attributes.put("columnNumber", field.column());
                attributes.put("sortOrder", field.sortOrder());
                attributes.put("labelOverride", field.label());
                attributes.put("helpTextOverride", field.helpText());
                attributes.put("isReadOnlyOnLayout", field.readOnly());
                attributes.put("isRequiredOnLayout", field.required());

                Map<String, Object> match = existing.stream()
                        .filter(row -> field.name().equals(asString(row.get("field_name"))))
                        .findFirst().orElse(null);
                if (match == null) {
                    create("layout-fields", attributes);
                    counts.created++;
                    continue;
                }
                existing.remove(match);

                Map<String, Object> current = new LinkedHashMap<>();
                current.put("sectionId", asString(match.get("section_id")));
                current.put("fieldId", asString(match.get("field_id")));
                current.put("columnNumber", intOr(match.get("column_number"), 0));
                current.put("sortOrder", intOr(match.get("sort_order"), 0));
                current.put("labelOverride", asString(match.get("label_override")));
                current.put("helpTextOverride", asString(match.get("help_text_override")));
                current.put("isReadOnlyOnLayout", boolOr(match.get("is_read_only_on_layout"), false));
                current.put("isRequiredOnLayout", boolOr(match.get("is_required_on_layout"), false));
                applyDiff("layout-fields", asString(match.get("id")), current, attributes, counts);
            }
        }

        // Placements the body no longer lists — including any duplicate row for a field that
        // the generic collection routes allowed to be placed twice.
        for (Map<String, Object> orphan : existing) {
            delete("layout-fields", asString(orphan.get("id")));
            counts.deleted++;
        }
    }

    /**
     * Drops sections the body no longer lists. Runs after placements so a field moved out of a
     * deleted section is re-parented first — {@code layout_field.section_id} cascades on delete,
     * which would otherwise take the placement with it.
     */
    private void deleteUnusedSections(String layoutId, Map<String, String> keptSectionIds, Counts counts) {
        Set<String> kept = new LinkedHashSet<>(keptSectionIds.values());
        for (Map<String, Object> row : jdbcTemplate.queryForList(SELECT_SECTIONS, layoutId)) {
            String sectionId = asString(row.get("id"));
            if (!kept.contains(sectionId)) {
                delete("layout-sections", sectionId);
                counts.deleted++;
            }
        }
    }

    private void applyRelatedLists(String layoutId, List<DesiredRelated> desired, Counts counts) {
        List<Map<String, Object>> existing =
                new ArrayList<>(jdbcTemplate.queryForList(SELECT_RELATED_LISTS, layoutId));

        for (DesiredRelated related : desired) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("relatedCollectionId", related.collectionId());
            attributes.put("relationshipFieldId", related.relationshipFieldId());
            attributes.put("displayColumns", related.displayColumns());
            attributes.put("sortField", related.sortField());
            attributes.put("sortDirection", related.sortDirection() == null ? "DESC" : related.sortDirection());
            attributes.put("rowLimit", related.rowLimit() == null ? 10 : related.rowLimit());
            attributes.put("sortOrder", related.sortOrder());

            Map<String, Object> match = existing.stream()
                    .filter(row -> related.collection().equals(asString(row.get("related_collection_name")))
                            && related.relationshipField().equals(asString(row.get("relationship_field_name"))))
                    .findFirst().orElse(null);
            if (match == null) {
                attributes.put("layoutId", layoutId);
                create("layout-related-lists", attributes);
                counts.created++;
                continue;
            }
            existing.remove(match);

            Map<String, Object> current = new LinkedHashMap<>();
            current.put("relatedCollectionId", asString(match.get("related_collection_id")));
            current.put("relationshipFieldId", asString(match.get("relationship_field_id")));
            current.put("displayColumns", parseJson(match.get("display_columns")));
            current.put("sortField", asString(match.get("sort_field")));
            current.put("sortDirection", asString(match.get("sort_direction")));
            current.put("rowLimit", intOr(match.get("row_limit"), 10));
            current.put("sortOrder", intOr(match.get("sort_order"), 0));
            applyDiff("layout-related-lists", asString(match.get("id")), current, attributes, counts);
        }

        for (Map<String, Object> orphan : existing) {
            delete("layout-related-lists", asString(orphan.get("id")));
            counts.deleted++;
        }
    }

    /** Writes only the attributes that actually differ; an all-equal row counts as unchanged. */
    private void applyDiff(String collectionName, String id, Map<String, Object> current,
                           Map<String, Object> desired, Counts counts) {
        Map<String, Object> changes = new LinkedHashMap<>();
        desired.forEach((key, value) -> {
            if (!Objects.equals(current.get(key), value)) {
                changes.put(key, value);
            }
        });
        if (changes.isEmpty()) {
            counts.unchanged++;
            return;
        }
        update(collectionName, id, changes);
        counts.updated++;
    }

    // ------------------------------------------------------------------
    // QueryEngine + JDBC plumbing
    // ------------------------------------------------------------------

    private CollectionDefinition definition(String collectionName) {
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            throw new IllegalStateException("System collection not initialized: " + collectionName);
        }
        return definition;
    }

    /**
     * Tenant-scoped system collections need {@code tenantId} on the record: the storage adapter
     * writes {@code tenant_id} only when the data carries it, and the JSON:API layer that injects
     * it sits on the generic route, not here.
     */
    private String create(String collectionName, Map<String, Object> attributes) {
        CollectionDefinition definition = definition(collectionName);
        Map<String, Object> data = new LinkedHashMap<>(attributes);
        String tenantId = TenantContext.get();
        if (definition.tenantScoped() && tenantId != null) {
            data.putIfAbsent("tenantId", tenantId);
        }
        return asString(queryEngine.create(definition, data).get("id"));
    }

    private void update(String collectionName, String id, Map<String, Object> attributes) {
        queryEngine.update(definition(collectionName), id, attributes);
    }

    private void delete(String collectionName, String id) {
        queryEngine.delete(definition(collectionName), id);
    }

    /** The caller's tenant; a tree call outside a tenant context has nothing it may touch. */
    private static String tenantId() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new LayoutNotFoundException("No tenant context");
        }
        return tenantId;
    }

    private Map<String, Object> loadLayout(String layoutId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_LAYOUT, layoutId, tenantId());
        if (rows.isEmpty()) {
            throw new LayoutNotFoundException("Page layout '" + layoutId + "' not found");
        }
        return rows.get(0);
    }

    private String collectionIdByName(String collectionName) {
        String id = findCollectionIdByName(collectionName);
        if (id == null) {
            throw new LayoutNotFoundException("Collection '" + collectionName + "' not found");
        }
        return id;
    }

    private String findCollectionIdByName(String collectionName) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(SELECT_COLLECTION_ID_BY_NAME, collectionName, tenantId());
        return rows.isEmpty() ? null : asString(rows.get(0).get("id"));
    }

    private String collectionNameById(String collectionId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(SELECT_COLLECTION_NAME_BY_ID, collectionId, tenantId());
        if (rows.isEmpty()) {
            throw new LayoutNotFoundException("Collection '" + collectionId + "' not found");
        }
        return asString(rows.get(0).get("name"));
    }

    private String findLayoutIdByName(String collectionId, String layoutName) {
        return jdbcTemplate.queryForList(SELECT_LAYOUTS_BY_COLLECTION, collectionId, tenantId()).stream()
                .filter(row -> layoutName.equals(asString(row.get("name"))))
                .map(row -> asString(row.get("id")))
                .findFirst().orElse(null);
    }

    private Map<String, String> fieldIdsByName(String collectionId) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(SELECT_FIELDS_BY_COLLECTION, collectionId)) {
            ids.put(asString(row.get("name")), asString(row.get("id")));
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Value helpers
    // ------------------------------------------------------------------

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        return null;
    }

    private static int intOr(Object value, int fallback) {
        Integer parsed = asInteger(value);
        return parsed == null ? fallback : parsed;
    }

    private static boolean boolOr(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    /** JSONB columns arrive as {@code PGobject} (or a raw string); parse them back to structures. */
    private Object parseJson(Object value) {
        if (value == null) {
            return null;
        }
        String candidate = null;
        if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            candidate = value.toString();
        } else if (value instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                candidate = trimmed;
            }
        }
        if (candidate == null) {
            return value;
        }
        try {
            return objectMapper.readValue(candidate, Object.class);
        } catch (Exception e) {
            log.warn("Could not parse JSON column value, passing through: {}", e.getMessage());
            return value;
        }
    }
}
