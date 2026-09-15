package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.PackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Applies a metadata package to the current tenant through the platform's
 * standard write path.
 *
 * <p>System-collection types (collections, fields, flows, layouts, validation
 * rules, picklists, UI pages/menus) import via {@link QueryEngine#create}/
 * {@link QueryEngine#update} against their system collections — the same path
 * the admin API and MCP tools use — so quota hooks, physical-table DDL, and the
 * {@code kelta.config.*} NATS broadcasts all fire (the
 * {@code ExternalEntityMaterializer} pattern). Legacy authz tables
 * (role/policy/route_policy/field_policy) were dropped in V47 and are never imported.
 *
 * <p>Cross-tenant references are resolved by <b>natural key</b> (names), never
 * by source UUID: a field's {@code reference_collection_id} is re-pointed at
 * the target tenant's collection with the same name, a layout field finds its
 * section by layout + sort order, and so on. An unresolvable reference fails
 * that item — a dangling UUID is never written.
 *
 * <p>There is deliberately no global transaction: collection imports run DDL
 * and publish NATS events that cannot roll back. Each item is applied in
 * isolation and reported in the {@link ImportReport}; re-running an import
 * converges because every type upserts on its natural key.
 */
@Service
public class PackageImportService {

    private static final Logger log = LoggerFactory.getLogger(PackageImportService.class);

    /**
     * Import order: referenced types strictly before referencing types.
     * Legacy authz types (ROLE/POLICY/ROUTE_POLICY/FIELD_POLICY) are not imported
     * — those tables were dropped in V47; per-tenant authz is profiles + Cerbos,
     * which a sandbox seeds itself via TenantProvisioningHook.
     */
    private static final List<String> TYPE_ORDER = List.of(
            "COLLECTION", "FIELD", "GLOBAL_PICKLIST", "PICKLIST_VALUE",
            "VALIDATION_RULE", "PAGE_LAYOUT", "LAYOUT_SECTION", "LAYOUT_FIELD",
            "LAYOUT_RELATED_LIST", "FLOW", "UI_PAGE", "UI_MENU", "UI_MENU_ITEM");

    /** Package type → system collection name (QueryEngine import path). */
    private static final Map<String, String> SYSTEM_COLLECTION_BY_TYPE = Map.ofEntries(
            Map.entry("COLLECTION", "collections"),
            Map.entry("FIELD", "fields"),
            Map.entry("GLOBAL_PICKLIST", "global-picklists"),
            Map.entry("PICKLIST_VALUE", "picklist-values"),
            Map.entry("VALIDATION_RULE", "validation-rules"),
            Map.entry("PAGE_LAYOUT", "page-layouts"),
            Map.entry("LAYOUT_SECTION", "layout-sections"),
            Map.entry("LAYOUT_FIELD", "layout-fields"),
            Map.entry("LAYOUT_RELATED_LIST", "layout-related-lists"),
            Map.entry("FLOW", "flows"),
            Map.entry("UI_PAGE", "ui-pages"),
            Map.entry("UI_MENU", "ui-menus"),
            Map.entry("UI_MENU_ITEM", "ui-menu-items"));

    private static final Set<String> AUDIT_FIELD_NAMES =
            Set.of("id", "createdAt", "updatedAt", "createdBy", "updatedBy");

    public enum ConflictMode { SKIP, OVERWRITE }

    public record ImportOptions(ConflictMode conflictMode,
                                boolean dryRun,
                                Set<String> typeFilter,
                                Set<String> itemKeyFilter,
                                String executingUserId) {

        public static ImportOptions defaults() {
            return new ImportOptions(ConflictMode.SKIP, false, null, null, null);
        }
    }

    public record ItemResult(String type, String naturalKey, String action, String error) {}

    public record ImportReport(int created, int updated, int skipped, int failed,
                               List<ItemResult> items) {

        public boolean success() {
            return failed == 0;
        }
    }

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final PackageRepository repository;
    private final ObjectMapper objectMapper;

    public PackageImportService(QueryEngine queryEngine,
                                CollectionRegistry collectionRegistry,
                                PackageRepository repository,
                                ObjectMapper objectMapper) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public ImportReport importPackage(String tenantId, Map<String, Object> pkg, ImportOptions options) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) pkg.getOrDefault("items", List.of());
        ImportContext ctx = new ImportContext(tenantId, options);

        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (var item : items) {
            byType.computeIfAbsent((String) item.get("type"), k -> new ArrayList<>()).add(item);
        }
        byType.computeIfPresent("UI_MENU_ITEM", (k, menuItems) -> parentsFirst(menuItems));

        // In-package id→natural-key maps drive the raw authz-table remaps

        List<ItemResult> results = new ArrayList<>();
        for (String type : TYPE_ORDER) {
            for (var item : byType.getOrDefault(type, List.of())) {
                Map<String, Object> data = (Map<String, Object>) item.get("data");
                String naturalKey = naturalKeyFor(type, data);
                if (!included(type, naturalKey, options)) {
                    continue;
                }
                try {
                    results.add(importItem(ctx, type, naturalKey, data));
                } catch (Exception e) {
                    log.warn("Package import failed for {} '{}' (tenant={})", type, naturalKey, tenantId, e);
                    results.add(new ItemResult(type, naturalKey, "FAILED", e.getMessage()));
                }
            }
        }

        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (var r : results) {
            switch (r.action()) {
                case "CREATED" -> created++;
                case "UPDATED" -> updated++;
                case "SKIPPED" -> skipped++;
                case "FAILED" -> failed++;
            }
        }
        return new ImportReport(created, updated, skipped, failed, results);
    }

    private boolean included(String type, String naturalKey, ImportOptions options) {
        if (options.typeFilter() != null && !options.typeFilter().contains(type)) {
            return false;
        }
        return options.itemKeyFilter() == null
                || options.itemKeyFilter().contains(type + ":" + naturalKey);
    }

    // ------------------------------------------------------------------
    // Per-item dispatch
    // ------------------------------------------------------------------

    private ItemResult importItem(ImportContext ctx, String type, String naturalKey,
                                  Map<String, Object> data) {
        return switch (type) {
            case "COLLECTION" -> importCollection(ctx, naturalKey, data);
            case "FIELD" -> importField(ctx, naturalKey, data);
            case "GLOBAL_PICKLIST" -> importSimpleByName(ctx, "GLOBAL_PICKLIST", naturalKey, data,
                    ctx.globalPicklistIdByName);
            case "PICKLIST_VALUE" -> importPicklistValue(ctx, naturalKey, data);
            case "VALIDATION_RULE" -> importCollectionChild(ctx, "VALIDATION_RULE", naturalKey, data,
                    ctx.validationRuleIdByKey);
            case "PAGE_LAYOUT" -> importCollectionChild(ctx, "PAGE_LAYOUT", naturalKey, data,
                    ctx.layoutIdByKey);
            case "LAYOUT_SECTION" -> importLayoutSection(ctx, naturalKey, data);
            case "LAYOUT_FIELD" -> importLayoutField(ctx, naturalKey, data);
            case "LAYOUT_RELATED_LIST" -> importLayoutRelatedList(ctx, naturalKey, data);
            case "FLOW" -> importFlow(ctx, naturalKey, data);
            case "UI_PAGE" -> importUiPage(ctx, naturalKey, data);
            case "UI_MENU" -> importSimpleByName(ctx, "UI_MENU", naturalKey, data, ctx.menuIdByName);
            case "UI_MENU_ITEM" -> importUiMenuItem(ctx, naturalKey, data);
            default -> new ItemResult(type, naturalKey, "SKIPPED", "Unsupported item type");
        };
    }

    /** Package types this importer understands, referenced types first. */
    public static List<String> supportedTypes() {
        return TYPE_ORDER;
    }

    /** System collection a package type is written to, or {@code null} if unsupported. */
    public static String systemCollectionFor(String type) {
        return SYSTEM_COLLECTION_BY_TYPE.get(type);
    }

    /** Cross-tenant identity of a package item — shared with environment diffing. */
    public static String naturalKeyFor(String type, Map<String, Object> data) {
        return switch (type) {
            case "FIELD" -> data.get("collection_name") + "." + data.get("name");
            case "PICKLIST_VALUE" -> {
                String source = "GLOBAL".equals(data.get("picklist_source_type"))
                        ? String.valueOf(data.get("picklist_name"))
                        : data.get("field_collection_name") + "." + data.get("field_name");
                yield source + ":" + data.get("value");
            }
            case "VALIDATION_RULE", "PAGE_LAYOUT" -> data.get("collection_name") + ":" + data.get("name");
            case "LAYOUT_SECTION" -> data.get("collection_name") + ":" + data.get("layout_name")
                    + ":" + data.get("sort_order");
            case "LAYOUT_FIELD" -> data.get("collection_name") + ":" + data.get("layout_name")
                    + ":" + data.get("section_sort_order") + ":" + data.get("field_name");
            case "LAYOUT_RELATED_LIST" -> data.get("collection_name") + ":" + data.get("layout_name")
                    + ":" + data.get("related_collection_name")
                    + ":" + data.get("relationship_field_name");
            case "UI_PAGE" -> String.valueOf(data.getOrDefault("path", data.get("name")));
            // Parent label included: two children with the same label under
            // different groups of the same menu are different items.
            case "UI_MENU_ITEM" -> data.get("menu_name") + ":" + blankIfNull(data.get("parent_label"))
                    + ":" + data.get("label");
            default -> String.valueOf(data.get("name"));
        };
    }

    // ------------------------------------------------------------------
    // QueryEngine-path importers
    // ------------------------------------------------------------------

    private ItemResult importCollection(ImportContext ctx, String key, Map<String, Object> data) {
        String name = (String) data.get("name");
        String existingId = ctx.collectionIdByName().get(name);
        CollectionDefinition def = systemDef("COLLECTION");

        Map<String, Object> mapped = mapRowToFields(def, data);
        mapped.put("tenantId", ctx.tenantId);
        mapped.put("systemCollection", false);

        return upsertViaEngine(ctx, "COLLECTION", key, def, existingId, mapped,
                id -> ctx.collectionIdByName().put(name, id));
    }

    private ItemResult importField(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("FIELD");
        String collectionId = ctx.requireCollection((String) data.get("collection_name"));

        Map<String, Object> mapped = mapRowToFields(def, data);
        mapped.put("collectionId", collectionId);

        Object refName = data.get("reference_collection_name");
        if (data.get("reference_collection_id") != null || refName != null) {
            if (refName == null) {
                throw new IllegalStateException(
                        "Field references a collection but the package carries no reference name "
                                + "(v1 package?) — cannot remap safely");
            }
            mapped.put("referenceCollectionId", ctx.requireCollection((String) refName));
        }

        String existingId = ctx.fieldIdByKey().get(key);
        return upsertViaEngine(ctx, "FIELD", key, def, existingId, mapped,
                id -> ctx.fieldIdByKey().put(key, id));
    }

    private ItemResult importSimpleByName(ImportContext ctx, String type, String key,
                                          Map<String, Object> data, Map<String, String> registry) {
        CollectionDefinition def = systemDef(type);
        Map<String, Object> mapped = mapRowToFields(def, data);
        String existingId = registry.get(key);
        return upsertViaEngine(ctx, type, key, def, existingId, mapped,
                id -> registry.put(key, id));
    }

    private ItemResult importPicklistValue(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("PICKLIST_VALUE");
        Map<String, Object> mapped = mapRowToFields(def, data);

        String sourceType = (String) data.get("picklist_source_type");
        String sourceId;
        if ("GLOBAL".equals(sourceType)) {
            String picklistName = (String) data.get("picklist_name");
            sourceId = ctx.globalPicklistIdByName.get(picklistName);
            if (sourceId == null) {
                throw new IllegalStateException("Global picklist not found in target: " + picklistName);
            }
        } else {
            String fieldKey = data.get("field_collection_name") + "." + data.get("field_name");
            sourceId = ctx.fieldIdByKey().get(fieldKey);
            if (sourceId == null) {
                throw new IllegalStateException("Picklist field not found in target: " + fieldKey);
            }
        }
        mapped.put("picklistSourceId", sourceId);

        String existingId = ctx.picklistValueIdByKey.get(sourceType + ":" + sourceId + ":" + data.get("value"));
        return upsertViaEngine(ctx, "PICKLIST_VALUE", key, def, existingId, mapped,
                id -> ctx.picklistValueIdByKey.put(sourceType + ":" + sourceId + ":" + data.get("value"), id));
    }

    /** Shared path for types whose only remapped reference is the owning collection. */
    private ItemResult importCollectionChild(ImportContext ctx, String type, String key,
                                             Map<String, Object> data, Map<String, String> registry) {
        CollectionDefinition def = systemDef(type);
        Map<String, Object> mapped = mapRowToFields(def, data);
        mapped.put("collectionId", ctx.requireCollection((String) data.get("collection_name")));
        String existingId = registry.get(key);
        return upsertViaEngine(ctx, type, key, def, existingId, mapped,
                id -> registry.put(key, id));
    }

    private ItemResult importLayoutSection(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("LAYOUT_SECTION");
        Map<String, Object> mapped = mapRowToFields(def, data);
        String layoutKey = data.get("collection_name") + ":" + data.get("layout_name");
        String layoutId = ctx.layoutIdByKey.get(layoutKey);
        if (layoutId == null) {
            throw new IllegalStateException("Layout not found in target: " + layoutKey);
        }
        mapped.put("layoutId", layoutId);
        String existingId = ctx.sectionIdByKey.get(key);
        return upsertViaEngine(ctx, "LAYOUT_SECTION", key, def, existingId, mapped,
                id -> ctx.sectionIdByKey.put(key, id));
    }

    private ItemResult importLayoutField(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("LAYOUT_FIELD");
        Map<String, Object> mapped = mapRowToFields(def, data);

        String sectionKey = data.get("collection_name") + ":" + data.get("layout_name")
                + ":" + data.get("section_sort_order");
        String sectionId = ctx.sectionIdByKey.get(sectionKey);
        if (sectionId == null) {
            throw new IllegalStateException("Layout section not found in target: " + sectionKey);
        }
        String fieldKey = data.get("field_collection_name") + "." + data.get("field_name");
        String fieldId = ctx.fieldIdByKey().get(fieldKey);
        if (fieldId == null) {
            throw new IllegalStateException("Layout field target not found: " + fieldKey);
        }
        mapped.put("sectionId", sectionId);
        mapped.put("fieldId", fieldId);

        String existingId = ctx.layoutFieldIdByKey.get(key);
        return upsertViaEngine(ctx, "LAYOUT_FIELD", key, def, existingId, mapped,
                id -> ctx.layoutFieldIdByKey.put(key, id));
    }

    private ItemResult importLayoutRelatedList(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("LAYOUT_RELATED_LIST");
        Map<String, Object> mapped = mapRowToFields(def, data);

        String layoutKey = data.get("collection_name") + ":" + data.get("layout_name");
        String layoutId = ctx.layoutIdByKey.get(layoutKey);
        if (layoutId == null) {
            throw new IllegalStateException("Layout not found in target: " + layoutKey);
        }
        String relatedCollectionName = (String) data.get("related_collection_name");
        // The relationship field lives on the related collection unless the
        // export says otherwise (pre-existing packages omit the owning name).
        String fieldCollectionName = (String) data.getOrDefault(
                "relationship_field_collection_name", relatedCollectionName);
        String fieldKey = fieldCollectionName + "." + data.get("relationship_field_name");
        String fieldId = ctx.fieldIdByKey().get(fieldKey);
        if (fieldId == null) {
            throw new IllegalStateException("Relationship field not found in target: " + fieldKey);
        }
        mapped.put("layoutId", layoutId);
        mapped.put("relatedCollectionId", ctx.requireCollection(relatedCollectionName));
        mapped.put("relationshipFieldId", fieldId);

        String existingId = ctx.relatedListIdByKey.get(key);
        return upsertViaEngine(ctx, "LAYOUT_RELATED_LIST", key, def, existingId, mapped,
                id -> ctx.relatedListIdByKey.put(key, id));
    }

    private ItemResult importFlow(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("FLOW");
        Map<String, Object> mapped = mapRowToFields(def, data);
        // flow.created_by is NOT NULL REFERENCES platform_user — source user ids
        // are stripped at export, so bind to the executing user (else the target
        // tenant's first user, i.e. the seeded admin).
        mapped.put("createdBy", ctx.resolveDefaultUserId());
        String existingId = ctx.flowIdByName.get(key);
        return upsertViaEngine(ctx, "FLOW", key, def, existingId, mapped,
                id -> ctx.flowIdByName.put(key, id));
    }

    private ItemResult importUiPage(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("UI_PAGE");
        Map<String, Object> mapped = mapRowToFields(def, data);
        String existingId = ctx.uiPageIdByPath.get(key);
        return upsertViaEngine(ctx, "UI_PAGE", key, def, existingId, mapped,
                id -> ctx.uiPageIdByPath.put(key, id));
    }

    private ItemResult importUiMenuItem(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("UI_MENU_ITEM");
        Map<String, Object> mapped = mapRowToFields(def, data);
        String menuName = (String) data.get("menu_name");
        String menuId = ctx.menuIdByName.get(menuName);
        if (menuId == null) {
            throw new IllegalStateException("Menu not found in target: " + menuName);
        }
        mapped.put("menuId", menuId);

        // parent_id is a SOURCE id — meaningless here. Remap it through the
        // ids the parents got in this import (ordered parents-first above);
        // writing it verbatim would break the FK or mis-parent the item.
        Object sourceParentId = data.get("parent_id");
        if (sourceParentId != null) {
            String parentId = ctx.menuItemIdBySourceId.get(String.valueOf(sourceParentId));
            if (parentId == null) {
                throw new IllegalStateException(
                        "Parent menu item not found in package: " + sourceParentId);
            }
            mapped.put("parentId", parentId);
        }

        Object sourceId = data.get("id");
        String existingId = ctx.menuItemIdByKey.get(key);
        return upsertViaEngine(ctx, "UI_MENU_ITEM", key, def, existingId, mapped, id -> {
            ctx.menuItemIdByKey.put(key, id);
            if (sourceId != null) {
                ctx.menuItemIdBySourceId.put(String.valueOf(sourceId), id);
            }
        });
    }

    private ItemResult upsertViaEngine(ImportContext ctx, String type, String key,
                                       CollectionDefinition def, String existingId,
                                       Map<String, Object> mapped,
                                       java.util.function.Consumer<String> register) {
        // Tenant-scoped system collections (ui-menus, layout-sections, picklist-
        // values, …) need tenant_id: the storage adapter writes it only when the
        // record carries "tenantId" (the JSON:API layer injects it on the HTTP
        // path; a direct queryEngine.create must set it itself, or the NOT NULL
        // tenant_id is violated).
        if (def.tenantScoped()) {
            mapped.putIfAbsent("tenantId", ctx.tenantId);
        }
        if (existingId != null) {
            // Register even when skipping: later items resolve their references
            // through these maps and must see the item that is already there.
            register.accept(existingId);
            if (ctx.options.conflictMode() == ConflictMode.SKIP) {
                return new ItemResult(type, key, "SKIPPED", null);
            }
            if (!ctx.options.dryRun()) {
                queryEngine.update(def, existingId, mapped);
            }
            return new ItemResult(type, key, "UPDATED", null);
        }
        if (ctx.options.dryRun()) {
            register.accept("dry-run:" + UUID.randomUUID());
            return new ItemResult(type, key, "CREATED", null);
        }
        Map<String, Object> created = queryEngine.create(def, mapped);
        register.accept(String.valueOf(created.get("id")));
        return new ItemResult(type, key, "CREATED", null);
    }


    // ------------------------------------------------------------------
    // Row → system-collection field mapping
    // ------------------------------------------------------------------

    /**
     * Menu items point at their parent by source id, so a parent has to be
     * imported before its children; the export is ordered by display_order,
     * which says nothing about nesting. Sorting by depth is stable, so
     * display_order still orders siblings.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parentsFirst(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (var item : items) {
            Object id = ((Map<String, Object>) item.get("data")).get("id");
            if (id != null) {
                byId.put(String.valueOf(id), item);
            }
        }
        Map<Map<String, Object>, Integer> depths = new IdentityHashMap<>();
        for (var item : items) {
            depths.put(item, depth(item, byId, items.size()));
        }
        return items.stream().sorted(Comparator.comparingInt(depths::get)).toList();
    }

    @SuppressWarnings("unchecked")
    private static int depth(Map<String, Object> item, Map<String, Map<String, Object>> byId, int limit) {
        int depth = 0;
        Map<String, Object> current = item;
        while (depth <= limit) {
            Object parentId = ((Map<String, Object>) current.get("data")).get("parent_id");
            Map<String, Object> parent = parentId == null ? null : byId.get(String.valueOf(parentId));
            if (parent == null || parent == item) {
                return depth;
            }
            current = parent;
            depth++;
        }
        return depth;
    }

    private static String blankIfNull(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private CollectionDefinition systemDef(String type) {
        String name = SYSTEM_COLLECTION_BY_TYPE.get(type);
        CollectionDefinition def = collectionRegistry.get(name);
        if (def == null) {
            throw new IllegalStateException("System collection not initialized: " + name);
        }
        return def;
    }

    /**
     * Maps an exported snake_case DB row onto the system collection's field
     * names. Audit fields and ids are dropped (the engine owns them); JSON
     * column values arrive as PGobject/String and are parsed back to structures
     * so the storage adapter serializes them exactly once.
     */
    private Map<String, Object> mapRowToFields(CollectionDefinition def, Map<String, Object> row) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (FieldDefinition fd : def.fields()) {
            if (AUDIT_FIELD_NAMES.contains(fd.name()) || "tenantId".equals(fd.name())) {
                continue;
            }
            String column = fd.effectiveColumnName();
            if (!row.containsKey(column)) {
                continue;
            }
            Object value = normalizeValue(row.get(column));
            if (value != null) {
                mapped.put(fd.name(), value);
            }
        }
        return mapped;
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        // JSONB columns come back as org.postgresql.util.PGobject from
        // queryForList, or as raw JSON strings from a deserialized package.
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
        if (candidate != null) {
            try {
                return objectMapper.readValue(candidate, Object.class);
            } catch (Exception e) {
                return value instanceof String ? value : candidate;
            }
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Import context: target-tenant natural-key registries
    // ------------------------------------------------------------------

    private class ImportContext {
        final String tenantId;
        final ImportOptions options;

        private Map<String, String> collectionIdByName;
        private Map<String, String> fieldIdByKey;
        final Map<String, String> globalPicklistIdByName = new HashMap<>();
        final Map<String, String> picklistValueIdByKey = new HashMap<>();
        final Map<String, String> validationRuleIdByKey = new HashMap<>();
        final Map<String, String> layoutIdByKey = new HashMap<>();
        final Map<String, String> sectionIdByKey = new HashMap<>();
        final Map<String, String> layoutFieldIdByKey = new HashMap<>();
        final Map<String, String> relatedListIdByKey = new HashMap<>();
        final Map<String, String> flowIdByName = new HashMap<>();
        final Map<String, String> uiPageIdByPath = new HashMap<>();
        final Map<String, String> menuIdByName = new HashMap<>();
        final Map<String, String> menuItemIdByKey = new HashMap<>();
        /** Source menu-item id → target id, filled as this import runs (parent remap). */
        final Map<String, String> menuItemIdBySourceId = new HashMap<>();

        private String defaultUserId;

        ImportContext(String tenantId, ImportOptions options) {
            this.tenantId = tenantId;
            this.options = options;
            seed();
        }

        void seed() {
            var jdbc = repository.getJdbcTemplate();
            collectionIdByName = new HashMap<>();
            jdbc.queryForList("SELECT id, name FROM collection WHERE tenant_id = ?", tenantId)
                    .forEach(r -> collectionIdByName.put((String) r.get("name"), (String) r.get("id")));
            fieldIdByKey = new HashMap<>();
            jdbc.queryForList(
                    "SELECT f.id, f.name, c.name AS coll FROM field f " +
                            "JOIN collection c ON f.collection_id = c.id WHERE c.tenant_id = ?", tenantId)
                    .forEach(r -> fieldIdByKey.put(r.get("coll") + "." + r.get("name"), (String) r.get("id")));
            jdbc.queryForList("SELECT id, name FROM global_picklist WHERE tenant_id = ?", tenantId)
                    .forEach(r -> globalPicklistIdByName.put((String) r.get("name"), (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT pv.id, pv.picklist_source_type, pv.picklist_source_id, pv.value " +
                            "FROM picklist_value pv WHERE pv.tenant_id = ?", tenantId)
                    .forEach(r -> picklistValueIdByKey.put(
                            r.get("picklist_source_type") + ":" + r.get("picklist_source_id") + ":" + r.get("value"),
                            (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT vr.id, vr.name, c.name AS coll FROM validation_rule vr " +
                            "JOIN collection c ON vr.collection_id = c.id WHERE vr.tenant_id = ?", tenantId)
                    .forEach(r -> validationRuleIdByKey.put(r.get("coll") + ":" + r.get("name"), (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT pl.id, pl.name, c.name AS coll FROM page_layout pl " +
                            "JOIN collection c ON pl.collection_id = c.id WHERE pl.tenant_id = ?", tenantId)
                    .forEach(r -> layoutIdByKey.put(r.get("coll") + ":" + r.get("name"), (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT ls.id, ls.sort_order, pl.name AS layout_name, c.name AS coll " +
                            "FROM layout_section ls JOIN page_layout pl ON ls.layout_id = pl.id " +
                            "JOIN collection c ON pl.collection_id = c.id WHERE pl.tenant_id = ?", tenantId)
                    .forEach(r -> sectionIdByKey.put(
                            r.get("coll") + ":" + r.get("layout_name") + ":" + r.get("sort_order"),
                            (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT lf.id, ls.sort_order AS section_sort, pl.name AS layout_name, " +
                            "c.name AS coll, f.name AS field_name " +
                            "FROM layout_field lf JOIN layout_section ls ON lf.section_id = ls.id " +
                            "JOIN page_layout pl ON ls.layout_id = pl.id " +
                            "JOIN collection c ON pl.collection_id = c.id " +
                            "JOIN field f ON lf.field_id = f.id WHERE pl.tenant_id = ?", tenantId)
                    .forEach(r -> layoutFieldIdByKey.put(
                            r.get("coll") + ":" + r.get("layout_name") + ":" + r.get("section_sort")
                                    + ":" + r.get("field_name"),
                            (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT rl.id, pl.name AS layout_name, c.name AS coll, " +
                            "rc.name AS related_coll, f.name AS field_name " +
                            "FROM layout_related_list rl " +
                            "JOIN page_layout pl ON rl.layout_id = pl.id " +
                            "JOIN collection c ON pl.collection_id = c.id " +
                            "JOIN collection rc ON rl.related_collection_id = rc.id " +
                            "JOIN field f ON rl.relationship_field_id = f.id " +
                            "WHERE pl.tenant_id = ?", tenantId)
                    .forEach(r -> relatedListIdByKey.put(
                            r.get("coll") + ":" + r.get("layout_name") + ":" + r.get("related_coll")
                                    + ":" + r.get("field_name"),
                            (String) r.get("id")));
            jdbc.queryForList("SELECT id, name FROM flow WHERE tenant_id = ?", tenantId)
                    .forEach(r -> flowIdByName.put((String) r.get("name"), (String) r.get("id")));
            // role/policy tables were dropped in V47 — not seeded, not imported.
            jdbc.queryForList("SELECT id, name, path FROM ui_page WHERE tenant_id = ?", tenantId)
                    .forEach(r -> uiPageIdByPath.put(
                            String.valueOf(r.get("path") != null ? r.get("path") : r.get("name")),
                            (String) r.get("id")));
            jdbc.queryForList("SELECT id, name FROM ui_menu WHERE tenant_id = ?", tenantId)
                    .forEach(r -> menuIdByName.put((String) r.get("name"), (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT mi.id, mi.label, p.label AS parent_label, m.name AS menu_name " +
                            "FROM ui_menu_item mi JOIN ui_menu m ON mi.menu_id = m.id " +
                            "LEFT JOIN ui_menu_item p ON mi.parent_id = p.id " +
                            "WHERE mi.tenant_id = ?", tenantId)
                    .forEach(r -> menuItemIdByKey.put(
                            r.get("menu_name") + ":" + blankIfNull(r.get("parent_label"))
                                    + ":" + r.get("label"),
                            (String) r.get("id")));
        }

        Map<String, String> collectionIdByName() {
            return collectionIdByName;
        }

        Map<String, String> fieldIdByKey() {
            return fieldIdByKey;
        }

        String requireCollection(String name) {
            String id = collectionIdByName.get(name);
            if (id == null) {
                throw new IllegalStateException("Collection not found in target: " + name);
            }
            return id;
        }

        String resolveDefaultUserId() {
            if (options.executingUserId() != null && !options.executingUserId().isBlank()) {
                return options.executingUserId();
            }
            if (defaultUserId == null) {
                var rows = repository.getJdbcTemplate().queryForList(
                        "SELECT id FROM platform_user WHERE tenant_id = ? ORDER BY created_at ASC LIMIT 1",
                        tenantId);
                if (rows.isEmpty()) {
                    throw new IllegalStateException("Target tenant has no users to own imported flows");
                }
                defaultUserId = (String) rows.get(0).get("id");
            }
            return defaultUserId;
        }
    }
}
