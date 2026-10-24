package io.kelta.worker.service;

import io.kelta.jsonapi.AtomicOperation;
import io.kelta.jsonapi.AtomicResult;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.AtomicOperationExecutor;
import io.kelta.runtime.router.AtomicOperationExecutor.AtomicOperationException;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.PackageRepository;
import io.kelta.worker.service.DashboardTreeService.TreeValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * <p>List views upsert on (collection name, view name) like any other collection child.
 * Dashboards carry their widgets inline (the {@code PUT /api/dashboards/{name}/tree}
 * document) and are applied through {@link DashboardTreeService#applyTree} — the path
 * {@code kelta dashboards apply} uses — after every widget config has passed
 * {@link DashboardComponentValidator}. The optional top-level {@code records} section seeds
 * user-collection rows last, one {@link AtomicOperationExecutor} batch per section (the
 * {@code records bulk} path); a row whose key fields match an existing record is skipped.
 *
 * <p>Composite unique constraints have no system collection — they are Postgres indexes — and
 * apply through {@link CompositeUniqueConstraintService}, matched on (collection, field set).
 * Reports upsert on name, approval processes on (collection name, process name) with their
 * steps nested in the item and matched on step number; a step's user approver travels as an
 * email and a group approver as the group's name.
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
            "COLLECTION", "GLOBAL_PICKLIST", "FIELD", "PICKLIST_VALUE",
            "VALIDATION_RULE", "PAGE_LAYOUT", "LAYOUT_SECTION", "LAYOUT_FIELD",
            "LAYOUT_RELATED_LIST", "FLOW", "UI_PAGE", "UI_MENU", "UI_MENU_ITEM",
            "LIST_VIEW", "UNIQUE_CONSTRAINT", "REPORT", "APPROVAL_PROCESS", "DASHBOARD", "RECORD");

    /** Seed rows: the package's top-level {@code records} section, never an {@code items} entry. */
    static final String RECORD = "RECORD";

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
            Map.entry("UI_MENU_ITEM", "ui-menu-items"),
            Map.entry("LIST_VIEW", "list-views"),
            Map.entry("REPORT", "reports"),
            Map.entry("APPROVAL_PROCESS", "approval-processes"),
            Map.entry("DASHBOARD", "dashboards"));

    /** Approval steps are nested in their APPROVAL_PROCESS item, never an item of their own. */
    private static final String APPROVAL_STEPS_COLLECTION = "approval-steps";

    /** Dashboard-tree document keys a DASHBOARD item may carry besides its name. */
    private static final List<String> DASHBOARD_TREE_KEYS =
            List.of("description", "accessLevel", "columnCount", "dynamic");

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

    /**
     * One item's outcome. {@code resourceId} is the target row the item created, updated or
     * skipped over (a {@code dry-run:} placeholder on a dry-run create), and null when it failed.
     */
    public record ItemResult(String type, String naturalKey, String action, String error,
                             String resourceId) {

        public ItemResult(String type, String naturalKey, String action, String error) {
            this(type, naturalKey, action, error, null);
        }
    }

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
    private final UserIdResolver userIdResolver;
    private final DashboardTreeService dashboardTreeService;
    private final DashboardComponentValidator componentValidator;
    private final AtomicOperationExecutor atomicExecutor;
    private final TransactionTemplate transactionTemplate;
    private final CompositeUniqueConstraintService uniqueConstraintService;

    public PackageImportService(QueryEngine queryEngine,
                                CollectionRegistry collectionRegistry,
                                PackageRepository repository,
                                ObjectMapper objectMapper,
                                UserIdResolver userIdResolver,
                                DashboardTreeService dashboardTreeService,
                                DashboardComponentValidator componentValidator,
                                PlatformTransactionManager transactionManager,
                                CompositeUniqueConstraintService uniqueConstraintService) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.userIdResolver = userIdResolver;
        this.dashboardTreeService = dashboardTreeService;
        this.componentValidator = componentValidator;
        this.atomicExecutor = new AtomicOperationExecutor(queryEngine, collectionRegistry);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.uniqueConstraintService = uniqueConstraintService;
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
            if (RECORD.equals(type)) {
                continue; // seeded after the second pass below, once ownership is in place
            }
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

        patchDisplayFields(ctx, byType.getOrDefault("COLLECTION", List.of()), written("COLLECTION", results),
                options);
        results.addAll(importRecords(ctx, pkg.get("records")));

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

    /**
     * Second pass over already-imported collections, for settings that name a field and so can
     * only be written once FIELD imports have created it:
     * <ul>
     *   <li>resolves each {@code display_field_name} (natural key, same pattern as a FIELD's
     *       {@code reference_collection_name}) against the just-imported FIELD rows and patches
     *       {@code displayFieldId};</li>
     *   <li>re-applies the ownership settings ({@code ownerField}/{@code ownerScope}/
     *       {@code ownerScopeReads}) — {@code CollectionOwnershipValidationHook} rejects a
     *       LOOKUP owner field that does not exist yet.</li>
     * </ul>
     * Skipped for a collection whose own import failed, was excluded by a filter or was SKIPPED
     * (an existing collection under SKIP is left untouched, so a re-apply writes nothing), and
     * entirely for dry runs (nothing was actually written).
     */
    @SuppressWarnings("unchecked")
    private void patchDisplayFields(ImportContext ctx, List<Map<String, Object>> collectionItems,
                                    Set<String> writtenCollections, ImportOptions options) {
        if (options.dryRun() || collectionItems.isEmpty()) {
            return;
        }
        CollectionDefinition def = systemDef("COLLECTION");
        for (var item : collectionItems) {
            Map<String, Object> data = (Map<String, Object>) item.get("data");
            String collectionName = (String) data.get("name");
            if (!writtenCollections.contains(collectionName)) {
                continue;
            }
            String collectionId = ctx.collectionIdByName().get(collectionName);
            if (collectionId == null) {
                continue; // the collection's own import failed — nothing to patch
            }
            Object displayFieldName = data.get("display_field_name");
            if (displayFieldName != null) {
                String fieldKey = collectionName + "." + displayFieldName;
                String fieldId = ctx.fieldIdByKey().get(fieldKey);
                if (fieldId == null) {
                    log.warn("Display field not found in target, leaving unset: {} (collection={})",
                            fieldKey, collectionName);
                } else {
                    queryEngine.update(def, collectionId, Map.of("displayFieldId", fieldId));
                }
            }
            patchOwnership(def, collectionId, collectionName, data);
        }
    }

    /** Natural keys of the items of {@code type} this import created or updated. */
    private static Set<String> written(String type, List<ItemResult> results) {
        Set<String> keys = new HashSet<>();
        for (var r : results) {
            if (type.equals(r.type()) && ("CREATED".equals(r.action()) || "UPDATED".equals(r.action()))) {
                keys.add(r.naturalKey());
            }
        }
        return keys;
    }

    private void patchOwnership(CollectionDefinition def, String collectionId, String collectionName,
                                Map<String, Object> data) {
        OwnerScope scope = OwnerScope.parse(data.get("owner_scope"));
        Object ownerField = data.get("owner_field");
        if (scope == OwnerScope.NONE && ownerField == null) {
            return;
        }
        Map<String, Object> ownership = new HashMap<>();
        ownership.put("ownerField", ownerField);
        ownership.put("ownerScope", scope.name());
        ownership.put("ownerScopeReads", !Boolean.FALSE.equals(data.get("owner_scope_reads")));
        try {
            queryEngine.update(def, collectionId, ownership);
        } catch (RuntimeException e) {
            log.error("Could not apply ownership ({} by {}) to imported collection {} — it is NOT "
                    + "owner-scoped: {}", scope, ownerField, collectionName, e.getMessage());
        }
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
            case "LIST_VIEW" -> importListView(ctx, naturalKey, data);
            case "UNIQUE_CONSTRAINT" -> importUniqueConstraint(ctx, naturalKey, data);
            case "REPORT" -> importReport(ctx, naturalKey, data);
            case "APPROVAL_PROCESS" -> importApprovalProcess(ctx, naturalKey, data);
            case "DASHBOARD" -> importDashboard(ctx, naturalKey, data);
            default -> new ItemResult(type, naturalKey, "SKIPPED", "Unsupported item type");
        };
    }

    /**
     * Package types this importer understands, referenced types first. {@code RECORD} is the
     * {@code records} section, applied last; it is never an {@code items} entry.
     */
    public static List<String> supportedTypes() {
        return TYPE_ORDER;
    }

    /**
     * System collection a package type is written to, or {@code null} for an unsupported type
     * and for {@code RECORD} (seed rows go to the user collection each section names).
     */
    public static String systemCollectionFor(String type) {
        return SYSTEM_COLLECTION_BY_TYPE.get(type);
    }

    /**
     * Cross-tenant identity of a package item — shared with environment diffing.
     * <ul>
     *   <li>UNIQUE_CONSTRAINT: {@code <collection>:<field>,<field>} with the field names sorted —
     *       never the index name, which embeds the source table name;</li>
     *   <li>REPORT: {@code <name>} (the default case);</li>
     *   <li>APPROVAL_PROCESS: {@code <collection>:<name>}; its nested steps are matched on
     *       {@code <collection>:<name>:<stepNumber>}.</li>
     * </ul>
     */
    public static String naturalKeyFor(String type, Map<String, Object> data) {
        return switch (type) {
            case "FIELD" -> data.get("collection_name") + "." + data.get("name");
            case "PICKLIST_VALUE" -> {
                String source = "GLOBAL".equals(data.get("picklist_source_type"))
                        ? String.valueOf(data.get("picklist_name"))
                        : data.get("field_collection_name") + "." + data.get("field_name");
                yield source + ":" + data.get("value");
            }
            case "VALIDATION_RULE", "PAGE_LAYOUT", "LIST_VIEW", "APPROVAL_PROCESS" ->
                    data.get("collection_name") + ":" + data.get("name");
            case "UNIQUE_CONSTRAINT" -> data.get("collection_name") + ":"
                    + String.join(",", sortedFieldNames(data.get("field_names")));
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
        // The source tenant's raw field UUID never resolves in the target, and
        // FIELD imports strictly after COLLECTION per TYPE_ORDER anyway — leave
        // it unset here and patch it once fields exist (patchDisplayFields).
        mapped.remove("displayFieldId");
        // Ownership may name a LOOKUP field that FIELD imports have not created yet — applied
        // in the same second pass.
        mapped.remove("ownerField");
        mapped.remove("ownerScope");
        mapped.remove("ownerScopeReads");

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
            mapped.put("referenceCollectionId", ctx.requireReferenceCollection((String) refName));
        }
        remapGlobalPicklist(ctx, key, data, mapped);

        String existingId = ctx.fieldIdByKey().get(key);
        return upsertViaEngine(ctx, "FIELD", key, def, existingId, mapped,
                id -> ctx.fieldIdByKey().put(key, id));
    }

    /**
     * {@code fieldTypeConfig.globalPicklistId} is a source-tenant id: resolve it through the
     * exported {@code global_picklist_name} (GLOBAL_PICKLIST imports before FIELD, so a picklist
     * the same package creates is already registered). A package exported before the name was
     * carried keeps the id only when it is a global picklist of the target tenant.
     */
    @SuppressWarnings("unchecked")
    private void remapGlobalPicklist(ImportContext ctx, String key, Map<String, Object> data,
                                     Map<String, Object> mapped) {
        if (!(mapped.get("fieldTypeConfig") instanceof Map<?, ?> config)
                || config.get("globalPicklistId") == null) {
            return;
        }
        String sourceId = String.valueOf(config.get("globalPicklistId"));
        Object picklistName = data.get("global_picklist_name");
        String targetId;
        if (picklistName != null) {
            targetId = ctx.globalPicklistIdByName.get(String.valueOf(picklistName));
            if (targetId == null) {
                throw new IllegalStateException("Field " + key + " references global picklist '"
                        + picklistName + "', which is neither in the package nor in the target tenant");
            }
        } else if (ctx.globalPicklistIdByName.containsValue(sourceId)) {
            targetId = sourceId;
        } else {
            throw new IllegalStateException("Field " + key + " references global picklist id '"
                    + sourceId + "', which is not in the target tenant, and the package carries no "
                    + "global_picklist_name to remap it by — re-export the package and apply again");
        }
        Map<String, Object> remapped = new LinkedHashMap<>((Map<String, Object>) config);
        remapped.put("globalPicklistId", targetId);
        mapped.put("fieldTypeConfig", remapped);
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

    /**
     * A list view is keyed on (collection name, view name) — the key {@code kelta list-views
     * apply} and the MCP {@code apply_listview} tool upsert on. Views on a platform system
     * collection (e.g. {@code users}) resolve to that global collection.
     */
    private ItemResult importListView(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("LIST_VIEW");
        Map<String, Object> mapped = mapRowToFields(def, data);
        mapped.put("collectionId", ctx.requireReferenceCollection((String) data.get("collection_name")));
        String existingId = ctx.listViewIdByKey.get(key);
        return upsertViaEngine(ctx, "LIST_VIEW", key, def, existingId, mapped,
                id -> ctx.listViewIdByKey.put(key, id));
    }

    /**
     * A composite unique constraint is a Postgres index, not a row: it applies through
     * {@link CompositeUniqueConstraintService} once its collection and fields exist, and is
     * matched on the collection plus its field set (order-insensitive). An existing match is
     * SKIPPED under either conflict mode — a field set has nothing to overwrite.
     */
    private ItemResult importUniqueConstraint(ImportContext ctx, String key, Map<String, Object> data) {
        String collectionName = (String) data.get("collection_name");
        List<String> fieldNames = sortedFieldNames(data.get("field_names"));
        if (fieldNames.isEmpty()) {
            throw new IllegalArgumentException("Unique constraint " + key + " names no field_names");
        }
        String collectionId = ctx.requireCollection(collectionName);
        for (String fieldName : fieldNames) {
            ctx.requireField(collectionName, fieldName);
        }
        if (ctx.options.dryRun() && collectionId.startsWith("dry-run:")) {
            return new ItemResult("UNIQUE_CONSTRAINT", key, "CREATED", null, "dry-run:" + UUID.randomUUID());
        }
        CollectionDefinition def = collectionRegistry.get(collectionName);
        if (def == null) {
            throw new IllegalStateException("Collection not registered in target: " + collectionName);
        }
        for (var existing : uniqueConstraintService.list(def)) {
            if (sortedFieldNames(existing.fieldNames()).equals(fieldNames)) {
                return new ItemResult("UNIQUE_CONSTRAINT", key, "SKIPPED", null, existing.indexName());
            }
        }
        if (ctx.options.dryRun()) {
            return new ItemResult("UNIQUE_CONSTRAINT", key, "CREATED", null, "dry-run:" + UUID.randomUUID());
        }
        var created = uniqueConstraintService.create(def, fieldNames);
        return new ItemResult("UNIQUE_CONSTRAINT", key, "CREATED", null, created.indexName());
    }

    private static List<String> sortedFieldNames(Object raw) {
        if (!(raw instanceof Collection<?> names)) {
            return List.of();
        }
        return names.stream().map(String::valueOf).sorted().toList();
    }

    /**
     * A report is keyed on its name. Its primary collection travels as
     * {@code primary_collection_name}; every plain field it names (columns, group-by, sort-by)
     * must exist on that collection in the target. Its folder is matched by name and left unset
     * when the target has no folder of that name.
     */
    @SuppressWarnings("unchecked")
    private ItemResult importReport(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("REPORT");
        Map<String, Object> mapped = mapRowToFields(def, data);
        String collectionName = (String) data.get("primary_collection_name");
        if (collectionName == null) {
            throw new IllegalArgumentException("Report '" + key + "' names no primary_collection_name");
        }
        mapped.put("primaryCollectionId", ctx.requireReferenceCollection(collectionName));

        List<String> fieldNames = new ArrayList<>();
        if (mapped.get("columns") instanceof List<?> columns) {
            for (Object column : columns) {
                if (column instanceof Map<?, ?> c && c.get("fieldName") != null) {
                    fieldNames.add(String.valueOf(c.get("fieldName")));
                }
            }
        }
        for (String column : List.of("group_by", "sort_by")) {
            if (data.get(column) != null) {
                fieldNames.add(String.valueOf(data.get(column)));
            }
        }
        for (String fieldName : fieldNames) {
            if (!fieldName.contains(".")) { // a dotted name reads through relatedJoins
                ctx.requireField(collectionName, fieldName);
            }
        }

        mapped.remove("folderId");
        Object folderName = data.get("folder_name");
        if (folderName != null) {
            String folderId = ctx.reportFolderId(String.valueOf(folderName));
            if (folderId != null) {
                mapped.put("folderId", folderId);
            } else {
                log.warn("Report folder '{}' not found in target, leaving report '{}' unfiled", folderName, key);
            }
        }

        String existingId = ctx.reportIdByName.get(key);
        return upsertViaEngine(ctx, "REPORT", key, def, existingId, mapped,
                id -> ctx.reportIdByName.put(key, id));
    }

    /**
     * An approval process is keyed on (collection name, process name) and carries its steps in
     * {@code steps}, each matched on its {@code step_number}. Every reference — the collection,
     * the submitter and approver fields, a USER approver's {@code approver_email}, a GROUP or
     * QUEUE approver's {@code approver_group_name} — is resolved before anything is written, and
     * the process and its steps are written in one transaction, so an unresolved reference fails
     * the item with nothing half-applied. OVERWRITE updates matching steps and adds new ones;
     * a target step the package does not carry is left in place.
     */
    @SuppressWarnings("unchecked")
    private ItemResult importApprovalProcess(ImportContext ctx, String key, Map<String, Object> data) {
        CollectionDefinition def = systemDef("APPROVAL_PROCESS");
        CollectionDefinition stepDef = collectionRegistry.get(APPROVAL_STEPS_COLLECTION);
        if (stepDef == null) {
            throw new IllegalStateException("System collection not initialized: " + APPROVAL_STEPS_COLLECTION);
        }
        String collectionName = (String) data.get("collection_name");
        Map<String, Object> mapped = mapRowToFields(def, data);
        mapped.put("collectionId", ctx.requireReferenceCollection(collectionName));
        if (data.get("initial_submitter_field") != null) {
            ctx.requireField(collectionName, String.valueOf(data.get("initial_submitter_field")));
        }

        Object rawSteps = data.getOrDefault("steps", List.of());
        if (!(rawSteps instanceof List<?> stepRows)) {
            throw new IllegalArgumentException("Approval process " + key + ": 'steps' must be an array");
        }
        Map<Integer, Map<String, Object>> steps = new LinkedHashMap<>();
        for (Object rawStep : stepRows) {
            if (!(rawStep instanceof Map<?, ?> stepRow) || !(stepRow.get("step_number") instanceof Number number)) {
                throw new IllegalArgumentException("Approval process " + key + ": every step needs a step_number");
            }
            String where = "Approval process " + key + " step " + number.intValue();
            Map<String, Object> step = mapRowToFields(stepDef, (Map<String, Object>) stepRow);
            step.remove("approvalProcessId");
            step.put("approverId", resolveApprover(ctx, where, collectionName, (Map<String, Object>) stepRow));
            if (steps.put(number.intValue(), step) != null) {
                throw new IllegalArgumentException(where + " appears more than once");
            }
        }

        String existingId = ctx.approvalProcessIdByKey.get(key);
        if (existingId != null && ctx.options.conflictMode() == ConflictMode.SKIP) {
            return new ItemResult("APPROVAL_PROCESS", key, "SKIPPED", null, existingId);
        }
        String action = existingId == null ? "CREATED" : "UPDATED";
        if (ctx.options.dryRun()) {
            String id = existingId != null ? existingId : "dry-run:" + UUID.randomUUID();
            ctx.approvalProcessIdByKey.put(key, id);
            return new ItemResult("APPROVAL_PROCESS", key, action, null, id);
        }
        mapped.put("tenantId", ctx.tenantId);
        String processId = transactionTemplate.execute(status -> {
            String id = existingId;
            Map<Integer, String> stepIds = new HashMap<>();
            if (id == null) {
                id = String.valueOf(queryEngine.create(def, mapped).get("id"));
            } else {
                queryEngine.update(def, id, mapped);
                repository.getJdbcTemplate().queryForList(
                                "SELECT id, step_number FROM approval_step WHERE approval_process_id = ?", id)
                        .forEach(r -> stepIds.put(((Number) r.get("step_number")).intValue(), (String) r.get("id")));
            }
            for (var step : steps.entrySet()) {
                String stepId = stepIds.get(step.getKey());
                if (stepId != null) {
                    queryEngine.update(stepDef, stepId, step.getValue());
                } else {
                    Map<String, Object> created = new LinkedHashMap<>(step.getValue());
                    created.put("approvalProcessId", id);
                    queryEngine.create(stepDef, created);
                }
            }
            return id;
        });
        ctx.approvalProcessIdByKey.put(key, processId);
        return new ItemResult("APPROVAL_PROCESS", key, action, null, processId);
    }

    /**
     * The target-tenant {@code approverId} of a step: a USER approver resolved by email, a GROUP
     * or QUEUE approver by group name. Other approver types name no one directly (a FIELD
     * approver's field must exist on the process's collection), so their id stays unset.
     */
    private String resolveApprover(ImportContext ctx, String where, String collectionName,
                                   Map<String, Object> step) {
        String type = String.valueOf(step.get("approver_type")).toUpperCase(Locale.ROOT);
        switch (type) {
            case "USER" -> {
                Object email = step.get("approver_email");
                if (email == null) {
                    throw new IllegalArgumentException(where + ": USER approver carries no approver_email");
                }
                String userId = ctx.userIdByEmail(String.valueOf(email));
                if (userId == null) {
                    throw new IllegalStateException(where + ": approver user '" + email + "' not found in target");
                }
                return userId;
            }
            case "GROUP", "QUEUE" -> {
                Object groupName = step.get("approver_group_name");
                if (groupName == null) {
                    throw new IllegalArgumentException(where + ": " + type + " approver carries no approver_group_name");
                }
                String groupId = ctx.groupIdByName(String.valueOf(groupName));
                if (groupId == null) {
                    throw new IllegalStateException(where + ": approver group '" + groupName + "' not found in target");
                }
                return groupId;
            }
            case "FIELD" -> {
                if (step.get("approver_field") == null) {
                    throw new IllegalArgumentException(where + ": FIELD approver carries no approver_field");
                }
                ctx.requireField(collectionName, String.valueOf(step.get("approver_field")));
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * A dashboard is keyed on its name and carries its widgets inline. Every widget config is
     * checked by {@link DashboardComponentValidator} before anything is written, then the
     * whole tree is applied in one transaction through {@link DashboardTreeService#applyTree}
     * (widgets matched on title; under OVERWRITE a widget missing from the item is removed).
     */
    @SuppressWarnings("unchecked")
    private ItemResult importDashboard(ImportContext ctx, String name, Map<String, Object> data) {
        Object rawComponents = data.getOrDefault("components", List.of());
        if (!(rawComponents instanceof List<?> components)) {
            throw new IllegalArgumentException("Dashboard '" + name + "': 'components' must be an array");
        }
        validateComponents(ctx, name, (List<Object>) components);

        String existingId = ctx.dashboardIdByName.get(name);
        if (existingId != null && ctx.options.conflictMode() == ConflictMode.SKIP) {
            return new ItemResult("DASHBOARD", name, "SKIPPED", null, existingId);
        }
        String action = existingId == null ? "CREATED" : "UPDATED";
        if (ctx.options.dryRun()) {
            String id = existingId != null ? existingId : "dry-run:" + UUID.randomUUID();
            ctx.dashboardIdByName.put(name, id);
            return new ItemResult("DASHBOARD", name, action, null, id);
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        for (String key : DASHBOARD_TREE_KEYS) {
            if (data.get(key) != null) {
                tree.put(key, data.get(key));
            }
        }
        tree.put("components", components);
        DashboardTreeService.ApplyResult applied;
        try {
            applied = transactionTemplate.execute(status -> dashboardTreeService.applyTree(name, tree));
        } catch (TreeValidationException e) {
            throw new IllegalArgumentException("Dashboard '" + name + "': " + e.errors().stream()
                    .map(error -> error.pointer() + " " + error.detail())
                    .reduce((a, b) -> a + "; " + b).orElse(e.getMessage()));
        }
        ctx.dashboardIdByName.put(name, applied.dashboardId());
        return new ItemResult("DASHBOARD", name, action, null, applied.dashboardId());
    }

    /**
     * Fails the dashboard with every validation message when a widget is invalid. A dry run
     * does not check widgets on a collection the same dry run would create — it is not in the
     * target yet, so the validator could only call it unknown.
     */
    @SuppressWarnings("unchecked")
    private void validateComponents(ImportContext ctx, String dashboardName, List<Object> components) {
        List<String> problems = new ArrayList<>();
        for (Object raw : components) {
            if (!(raw instanceof Map<?, ?> rawComponent)) {
                continue; // applyTree reports the malformed entry with its pointer
            }
            Map<String, Object> component = (Map<String, Object>) rawComponent;
            Object config = component.get("config");
            Object collectionName = config instanceof Map<?, ?> c ? c.get("collectionName") : null;
            if (ctx.options.dryRun() && collectionName != null && String.valueOf(
                    ctx.collectionIdByName().get(String.valueOf(collectionName))).startsWith("dry-run:")) {
                continue;
            }
            for (BeforeSaveResult.ValidationError error : componentValidator.validate(component)) {
                problems.add("component '" + component.get("title") + "' " + error.field()
                        + ": " + error.message());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Dashboard '" + dashboardName + "' has invalid widget config: "
                    + String.join("; ", problems));
        }
    }

    // ------------------------------------------------------------------
    // Seed records (the package's top-level "records" section)
    // ------------------------------------------------------------------

    /**
     * One section of the {@code records} array: rows to seed into {@code collection}, identified
     * by the {@code key} fields. {@code references} maps a reference field to the parent
     * collection + field its row value names, resolved to the parent record's id.
     */
    private record RecordSection(String collection, List<String> key,
                                 Map<String, Map<String, Object>> references,
                                 List<Map<String, Object>> rows) {}

    /** A row about to be inserted: its natural key, the attributes to write, and its result slot. */
    private record PendingRow(String naturalKey, Map<String, Object> attributes, int resultIndex) {}

    /**
     * Seeds every {@code records} section in package order. Rows whose key fields match an
     * existing record are SKIPPED; the remaining rows of a section are inserted as one
     * {@link AtomicOperationExecutor} batch in its own transaction, so a section writes all of
     * its new rows or none. Seed rows are insert-only: OVERWRITE never updates a record.
     */
    private List<ItemResult> importRecords(ImportContext ctx, Object rawSections) {
        if (rawSections == null) {
            return List.of();
        }
        if (!(rawSections instanceof List<?> sections)) {
            return List.of(new ItemResult(RECORD, "records", "FAILED", "'records' must be an array"));
        }
        List<ItemResult> results = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            RecordSection section;
            try {
                section = parseSection(sections.get(i), i);
            } catch (IllegalArgumentException e) {
                results.add(new ItemResult(RECORD, "records[" + i + "]", "FAILED", e.getMessage()));
                continue;
            }
            results.addAll(importSection(ctx, section));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static RecordSection parseSection(Object raw, int index) {
        String where = "records[" + index + "]";
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " must be an object");
        }
        if (!(map.get("collection") instanceof String collection) || collection.isBlank()) {
            throw new IllegalArgumentException(where + ".collection is required");
        }
        if (!(map.get("key") instanceof List<?> key) || key.isEmpty()
                || !key.stream().allMatch(k -> k instanceof String s && !s.isBlank())) {
            throw new IllegalArgumentException(where + ".key must be a non-empty array of field names");
        }
        if (!(map.get("rows") instanceof List<?> rows)
                || !rows.stream().allMatch(r -> r instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(where + ".rows must be an array of objects");
        }
        Map<String, Map<String, Object>> references = new LinkedHashMap<>();
        Object rawRefs = map.get("references");
        if (rawRefs != null) {
            if (!(rawRefs instanceof Map<?, ?> refs)) {
                throw new IllegalArgumentException(where + ".references must be an object");
            }
            for (var entry : refs.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> target)
                        || !(target.get("collection") instanceof String)
                        || !(target.get("field") instanceof String)) {
                    throw new IllegalArgumentException(where + ".references." + entry.getKey()
                            + " must be {\"collection\": ..., \"field\": ...}");
                }
                references.put(String.valueOf(entry.getKey()), (Map<String, Object>) target);
            }
        }
        return new RecordSection(collection, (List<String>) key, references,
                (List<Map<String, Object>>) rows);
    }

    private List<ItemResult> importSection(ImportContext ctx, RecordSection section) {
        CollectionDefinition def = collectionRegistry.get(section.collection());
        boolean pendingCollection = def == null && ctx.options.dryRun()
                && ctx.collectionIdByName().containsKey(section.collection());

        List<ItemResult> results = new ArrayList<>();
        List<PendingRow> pending = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String sectionError = null;
        for (Map<String, Object> row : section.rows()) {
            String naturalKey = recordKey(section, row);
            if (!included(RECORD, naturalKey, ctx.options)) {
                continue;
            }
            if (!seen.add(naturalKey)) {
                results.add(new ItemResult(RECORD, naturalKey, "SKIPPED", "Duplicate key in package"));
                continue;
            }
            try {
                if (def == null && !pendingCollection) {
                    throw new IllegalStateException("Collection not found in target: " + section.collection());
                }
                Map<String, Object> attributes = resolveReferences(ctx, section, row);
                for (String keyField : section.key()) {
                    if (attributes.get(keyField) == null) {
                        throw new IllegalArgumentException("Key field '" + keyField + "' is missing");
                    }
                }
                String existingId = def == null ? null : findExistingRecord(def, section.key(), attributes);
                if (existingId != null) {
                    results.add(new ItemResult(RECORD, naturalKey, "SKIPPED", null, existingId));
                    continue;
                }
                pending.add(new PendingRow(naturalKey, attributes, results.size()));
                results.add(null);
            } catch (RuntimeException e) {
                sectionError = naturalKey + ": " + e.getMessage();
                results.add(new ItemResult(RECORD, naturalKey, "FAILED", e.getMessage()));
            }
        }

        if (sectionError != null) {
            failPending(results, pending, "Section '" + section.collection() + "' not applied — " + sectionError);
        } else if (ctx.options.dryRun()) {
            for (PendingRow row : pending) {
                String id = "dry-run:" + UUID.randomUUID();
                ctx.dryRunRecords.add(Map.entry(section.collection(), withId(row.attributes(), id)));
                results.set(row.resultIndex(), new ItemResult(RECORD, row.naturalKey(), "CREATED", null, id));
            }
        } else if (!pending.isEmpty()) {
            insertSection(section, pending, results);
        }
        return results.stream().filter(Objects::nonNull).toList();
    }

    private void insertSection(RecordSection section, List<PendingRow> pending, List<ItemResult> results) {
        List<AtomicOperation> operations = pending.stream()
                .map(row -> new AtomicOperation("add", null, new AtomicOperation.ResourceData(
                        section.collection(), null, null, row.attributes(), null)))
                .toList();
        String actingUserId = CallerContext.current().map(CallerContext::userId).orElse(null);
        try {
            List<AtomicResult> written = transactionTemplate.execute(
                    status -> atomicExecutor.execute(operations, actingUserId));
            for (int i = 0; i < pending.size(); i++) {
                PendingRow row = pending.get(i);
                results.set(row.resultIndex(), new ItemResult(RECORD, row.naturalKey(), "CREATED", null,
                        written.get(i).data().id()));
            }
        } catch (AtomicOperationException e) {
            int failedIndex = e.getOperationIndex();
            String failedKey = failedIndex >= 0 && failedIndex < pending.size()
                    ? pending.get(failedIndex).naturalKey() : section.collection();
            log.warn("Seed records for '{}' rolled back at {}: {}", section.collection(), failedKey, e.getMessage());
            failPending(results, pending, "Section '" + section.collection() + "' rolled back — "
                    + failedKey + ": " + e.getMessage());
        }
    }

    private static void failPending(List<ItemResult> results, List<PendingRow> pending, String message) {
        for (PendingRow row : pending) {
            results.set(row.resultIndex(), new ItemResult(RECORD, row.naturalKey(), "FAILED", message));
        }
    }

    /** {@code <collection>:<keyField>=<value>,...} — the row's identity in the import report. */
    private static String recordKey(RecordSection section, Map<String, Object> row) {
        StringJoiner key = new StringJoiner(",", section.collection() + ":", "");
        for (String field : section.key()) {
            key.add(field + "=" + row.get(field));
        }
        return key.toString();
    }

    /** Copies the row, replacing each declared reference value with the parent record's id. */
    private Map<String, Object> resolveReferences(ImportContext ctx, RecordSection section,
                                                  Map<String, Object> row) {
        Map<String, Object> attributes = new LinkedHashMap<>(row);
        section.references().forEach((field, target) -> {
            Object value = row.get(field);
            if (value != null) {
                attributes.put(field, resolveReference(ctx, field, String.valueOf(target.get("collection")),
                        String.valueOf(target.get("field")), value));
            }
        });
        return attributes;
    }

    private String resolveReference(ImportContext ctx, String field, String parentCollection,
                                    String parentField, Object value) {
        CollectionDefinition parent = collectionRegistry.get(parentCollection);
        if (parent != null) {
            var matches = queryEngine.executeQuery(parent, new QueryRequest(new Pagination(1, 2),
                    List.of(), List.of("id"), List.of(FilterCondition.eq(parentField, value)))).data();
            if (matches.size() > 1) {
                throw new IllegalStateException("Reference " + field + "='" + value + "' matches more than one "
                        + parentCollection + " record by " + parentField);
            }
            if (matches.size() == 1) {
                return String.valueOf(matches.get(0).get("id"));
            }
        }
        // A dry run has not written the parent rows an earlier section would create.
        for (var seeded : ctx.dryRunRecords) {
            if (seeded.getKey().equals(parentCollection)
                    && Objects.equals(String.valueOf(seeded.getValue().get(parentField)), String.valueOf(value))) {
                return String.valueOf(seeded.getValue().get("id"));
            }
        }
        throw new IllegalStateException("Reference " + field + "='" + value + "' not found in "
                + parentCollection + "." + parentField);
    }

    private String findExistingRecord(CollectionDefinition def, List<String> key, Map<String, Object> attributes) {
        List<FilterCondition> filters = key.stream()
                .map(field -> FilterCondition.eq(field, attributes.get(field)))
                .toList();
        var matches = queryEngine.executeQuery(def,
                new QueryRequest(new Pagination(1, 1), List.of(), List.of("id"), filters)).data();
        return matches.isEmpty() ? null : String.valueOf(matches.get(0).get("id"));
    }

    private static Map<String, Object> withId(Map<String, Object> attributes, String id) {
        Map<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put("id", id);
        return copy;
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
                return new ItemResult(type, key, "SKIPPED", null, existingId);
            }
            if (!ctx.options.dryRun()) {
                queryEngine.update(def, existingId, mapped);
            }
            return new ItemResult(type, key, "UPDATED", null, existingId);
        }
        if (ctx.options.dryRun()) {
            String placeholderId = "dry-run:" + UUID.randomUUID();
            register.accept(placeholderId);
            return new ItemResult(type, key, "CREATED", null, placeholderId);
        }
        Map<String, Object> created = queryEngine.create(def, mapped);
        String createdId = String.valueOf(created.get("id"));
        register.accept(createdId);
        return new ItemResult(type, key, "CREATED", null, createdId);
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
            Object value = normalizeValue(column, row.get(column));
            if (value != null) {
                mapped.put(fd.name(), value);
            }
        }
        return mapped;
    }

    Object normalizeValue(String column, Object value) {
        if (value == null) {
            return null;
        }
        if (isLegacyJsonWrapper(value)) {
            return unwrapLegacyJson(column, (Map<?, ?>) value);
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

    /**
     * Packages exported before BUILD-LOG error 13 was fixed carry each json/jsonb
     * column as the serialized driver object {@code {"null","type","value"}}.
     * Only that exact key set is treated as the wrapper, so tenant JSON that
     * merely has a {@code type} key is left alone.
     */
    private static boolean isLegacyJsonWrapper(Object value) {
        return value instanceof Map<?, ?> map
                && map.size() == 3
                && map.keySet().equals(Set.of("null", "type", "value"))
                && ("json".equals(map.get("type")) || "jsonb".equals(map.get("type")));
    }

    private Object unwrapLegacyJson(String column, Map<?, ?> wrapper) {
        Object json = wrapper.get("value");
        if (Boolean.TRUE.equals(wrapper.get("null")) || json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(String.valueOf(json), Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Column '" + column
                    + "' holds a legacy " + wrapper.get("type") + " wrapper whose value is not valid JSON");
        }
    }

    // ------------------------------------------------------------------
    // Import context: target-tenant natural-key registries
    // ------------------------------------------------------------------

    private class ImportContext {
        final String tenantId;
        final ImportOptions options;

        private Map<String, String> collectionIdByName;
        /**
         * Platform system collections (users, profiles, …) — global rows outside every tenant and
         * never in a package, but a valid LOOKUP target (an owner field is a LOOKUP to users).
         * Kept apart from {@link #collectionIdByName} so a package collection can never resolve to,
         * and so overwrite, a system row.
         */
        private Map<String, String> systemCollectionIdByName;
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
        final Map<String, String> listViewIdByKey = new HashMap<>();
        final Map<String, String> dashboardIdByName = new HashMap<>();
        final Map<String, String> reportIdByName = new HashMap<>();
        final Map<String, String> approvalProcessIdByKey = new HashMap<>();
        /** Rows a dry run would seed (collection → attributes + placeholder id), for reference lookups. */
        final List<Map.Entry<String, Map<String, Object>>> dryRunRecords = new ArrayList<>();

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
            systemCollectionIdByName = new HashMap<>();
            jdbc.queryForList("SELECT id, name FROM collection WHERE system_collection = true")
                    .forEach(r -> systemCollectionIdByName.put((String) r.get("name"), (String) r.get("id")));
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
            jdbc.queryForList(
                    "SELECT lv.id, lv.name, c.name AS coll FROM list_view lv " +
                            "JOIN collection c ON lv.collection_id = c.id WHERE lv.tenant_id = ?", tenantId)
                    .forEach(r -> listViewIdByKey.put(r.get("coll") + ":" + r.get("name"), (String) r.get("id")));
            jdbc.queryForList("SELECT id, name FROM dashboard WHERE tenant_id = ?", tenantId)
                    .forEach(r -> dashboardIdByName.putIfAbsent((String) r.get("name"), (String) r.get("id")));
            jdbc.queryForList("SELECT id, name FROM report WHERE tenant_id = ?", tenantId)
                    .forEach(r -> reportIdByName.putIfAbsent((String) r.get("name"), (String) r.get("id")));
            jdbc.queryForList(
                    "SELECT ap.id, ap.name, c.name AS coll FROM approval_process ap " +
                            "JOIN collection c ON ap.collection_id = c.id WHERE ap.tenant_id = ?", tenantId)
                    .forEach(r -> approvalProcessIdByKey.putIfAbsent(r.get("coll") + ":" + r.get("name"),
                            (String) r.get("id")));
        }

        /** A field the package names, in the package, the target tenant, or a system collection. */
        void requireField(String collectionName, String fieldName) {
            if (AUDIT_FIELD_NAMES.contains(fieldName)
                    || fieldIdByKey.containsKey(collectionName + "." + fieldName)) {
                return;
            }
            CollectionDefinition def = systemCollectionIdByName.containsKey(collectionName)
                    ? collectionRegistry.get(collectionName) : null;
            if (def == null || def.getField(fieldName) == null) {
                throw new IllegalStateException("Field not found in target: " + collectionName + "." + fieldName);
            }
        }

        String userIdByEmail(String email) {
            return firstId("SELECT id FROM platform_user WHERE tenant_id = ? AND lower(email) = lower(?)", email);
        }

        String groupIdByName(String name) {
            return firstId("SELECT id FROM user_group WHERE tenant_id = ? AND name = ?", name);
        }

        String reportFolderId(String name) {
            return firstId("SELECT id FROM report_folder WHERE tenant_id = ? AND name = ?", name);
        }

        private String firstId(String sql, String value) {
            var rows = repository.getJdbcTemplate().queryForList(sql + " ORDER BY id LIMIT 1", tenantId, value);
            return rows.isEmpty() ? null : (String) rows.get(0).get("id");
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

        String requireReferenceCollection(String name) {
            String id = collectionIdByName.get(name);
            if (id == null) {
                id = systemCollectionIdByName.get(name);
            }
            if (id == null) {
                throw new IllegalStateException("Collection not found in target: " + name);
            }
            return id;
        }

        /**
         * The executing user's {@code platform_user.id} in the target tenant, else the
         * tenant's first user. JWT callers arrive as an email and promotion's executor
         * may not exist in the target tenant, so the identifier is resolved and then
         * checked against {@code platform_user} rather than trusted as an id.
         */
        String resolveDefaultUserId() {
            if (defaultUserId != null) {
                return defaultUserId;
            }
            String executing = options.executingUserId();
            if (executing != null && !executing.isBlank()) {
                String resolved = userIdResolver.resolve(executing, tenantId);
                if (resolved != null && !repository.getJdbcTemplate().queryForList(
                        "SELECT id FROM platform_user WHERE tenant_id = ? AND id = ?",
                        tenantId, resolved).isEmpty()) {
                    defaultUserId = resolved;
                    return defaultUserId;
                }
                log.warn("Executing user '{}' has no platform_user in tenant {}; "
                        + "imported flows fall back to the tenant's first user", executing, tenantId);
            }
            var rows = repository.getJdbcTemplate().queryForList(
                    "SELECT id FROM platform_user WHERE tenant_id = ? ORDER BY created_at ASC LIMIT 1",
                    tenantId);
            if (rows.isEmpty()) {
                throw new IllegalStateException("Target tenant has no users to own imported flows");
            }
            defaultUserId = (String) rows.get(0).get("id");
            return defaultUserId;
        }
    }
}
