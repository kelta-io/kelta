package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.worker.repository.PackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class PackageService {

    private static final Logger log = LoggerFactory.getLogger(PackageService.class);

    /** Option keys naming the ids to export; none present means "the whole tenant". */
    private static final List<String> ID_OPTION_KEYS = List.of(
            "collectionIds", "uiPageIds", "uiMenuIds", "flowIds", "pageLayoutIds",
            "validationRuleIds", "globalPicklistIds", "listViewIds", "dashboardIds",
            "reportIds", "approvalProcessIds");

    /** Collection API names whose collections and dependents (rules, layouts, flows, picklists, views) to export. */
    private static final String COLLECTION_NAMES_OPTION = "collectionNames";

    /** Package types to leave out of an export, each emptying the id list that selects it. */
    private static final String EXCLUDE_TYPES_OPTION = "excludeTypes";

    private static final Map<String, String> ID_OPTION_BY_EXCLUDABLE_TYPE = Map.of(
            "LIST_VIEW", "listViewIds",
            "REPORT", "reportIds",
            "APPROVAL_PROCESS", "approvalProcessIds",
            "DASHBOARD", "dashboardIds",
            "FLOW", "flowIds",
            "PAGE_LAYOUT", "pageLayoutIds",
            "VALIDATION_RULE", "validationRuleIds",
            "UI_PAGE", "uiPageIds",
            "UI_MENU", "uiMenuIds");

    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String DEFAULT_NAME = "metadata";

    private final PackageRepository repository;
    private final ObjectMapper objectMapper;
    private final PackageImportService importService;
    private final CollectionRegistry collectionRegistry;
    private final CompositeUniqueConstraintService uniqueConstraintService;

    public PackageService(PackageRepository repository, ObjectMapper objectMapper,
                          PackageImportService importService, CollectionRegistry collectionRegistry,
                          CompositeUniqueConstraintService uniqueConstraintService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.importService = importService;
        this.collectionRegistry = collectionRegistry;
        this.uniqueConstraintService = uniqueConstraintService;
    }

    /**
     * Gathers export options covering every packageable item in the tenant except reports and
     * approval processes. Used by sandbox cloning, full promotions and snapshots.
     */
    public Map<String, Object> exportAllOptions(String tenantId, String name, String version) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("name", name);
        options.put("version", version);
        options.put("collectionIds", repository.getJdbcTemplate().queryForList(
                "SELECT id FROM collection WHERE tenant_id = ? AND system_collection = false",
                String.class, tenantId));
        // role/policy/route_policy/field_policy were dropped in V47 (legacy
        // security tables) — per-tenant authz is now profiles + Cerbos, seeded
        // fresh in a sandbox by TenantProvisioningHook. Authz types are never
        // exported, cloned, or promoted.
        options.put("uiPageIds", repository.findAllIds("ui_page", tenantId));
        options.put("uiMenuIds", repository.findAllIds("ui_menu", tenantId));
        options.put("flowIds", repository.findAllIds("flow", tenantId));
        options.put("pageLayoutIds", repository.findAllIds("page_layout", tenantId));
        options.put("validationRuleIds", repository.findAllIds("validation_rule", tenantId));
        options.put("globalPicklistIds", repository.findAllIds("global_picklist", tenantId));
        options.put("listViewIds", repository.findAllIds("list_view", tenantId));
        options.put("dashboardIds", repository.findAllIds("dashboard", tenantId));
        // Reports and approval processes are left out, as they always were: a sandbox starts with
        // only its seeded admin, so a USER/GROUP approver can never resolve there, and one report
        // naming a since-deleted field would fail the whole clone. A whole-tenant
        // `metadata export` adds both (resolveExportOptions).
        return options;
    }

    public Map<String, Object> exportPackage(String tenantId, Map<String, Object> options) {
        return exportPackage(tenantId, options, true);
    }

    /**
     * Fills in what the caller left out: a request that names no ids at all
     * ({@code POST /api/packages/export {}} — the common {@code kelta metadata
     * export} case) means the whole tenant, and name/version fall back to the
     * tenant slug and {@value #DEFAULT_VERSION}. An explicit (even empty) id
     * list is respected as-is; {@code collectionNames} scopes the export to those
     * collections and what hangs off them; {@code excludeTypes} (e.g. {@code ["DASHBOARD"]})
     * drops whole item types. Seed records are never exported.
     */
    public Map<String, Object> resolveExportOptions(String tenantId, Map<String, Object> options) {
        String name = blankToNull((String) options.get("name"));
        String version = blankToNull((String) options.get("version"));
        if (name == null) {
            name = repository.findTenantSlug(tenantId).orElse(DEFAULT_NAME);
        }
        if (version == null) {
            version = DEFAULT_VERSION;
        }

        boolean scoped = options.containsKey(COLLECTION_NAMES_OPTION)
                || ID_OPTION_KEYS.stream().anyMatch(options::containsKey);
        Map<String, Object> resolved;
        if (scoped) {
            resolved = new LinkedHashMap<>(options);
        } else {
            resolved = exportAllOptions(tenantId, name, version);
            resolved.put("reportIds", repository.findAllIds("report", tenantId));
            resolved.put("approvalProcessIds", repository.findAllIds("approval_process", tenantId));
        }
        resolved.putAll(options);
        if (options.containsKey(COLLECTION_NAMES_OPTION)) {
            addCollectionDependents(tenantId, getStringList(options, COLLECTION_NAMES_OPTION), resolved);
        }
        for (String type : getStringList(options, EXCLUDE_TYPES_OPTION)) {
            String idOption = ID_OPTION_BY_EXCLUDABLE_TYPE.get(type.toUpperCase(Locale.ROOT));
            if (idOption == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot exclude type '" + type
                        + "'; excludable types: " + String.join(", ", new TreeSet<>(ID_OPTION_BY_EXCLUDABLE_TYPE.keySet())));
            }
            resolved.put(idOption, List.of());
        }
        resolved.remove(EXCLUDE_TYPES_OPTION);
        resolved.put("name", name);
        resolved.put("version", version);
        return resolved;
    }

    /**
     * Resolves {@code collectionNames} to ids (unioned with any explicit
     * {@code collectionIds}) and fills each dependent id list the caller did not
     * pass: the collections' validation rules, page layouts, list views, reports and approval
     * processes, the flows they trigger, the global picklists their fields are bound to, and
     * the dashboards with a widget on one of them. UI pages and menus are never pulled in implicitly.
     */
    private void addCollectionDependents(String tenantId, List<String> names, Map<String, Object> resolved) {
        List<String> distinctNames = names.stream().distinct().toList();
        var rows = repository.findCollectionsByNames(tenantId, distinctNames);
        Map<String, String> idsByName = new LinkedHashMap<>();
        for (var row : rows) {
            if (!Boolean.TRUE.equals(row.get("system_collection"))) {
                idsByName.put((String) row.get("name"), (String) row.get("id"));
            }
        }
        List<String> unknown = distinctNames.stream().filter(n -> !idsByName.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown collection(s) in collectionNames: " + String.join(", ", unknown));
        }

        List<String> namedIds = distinctNames.stream().map(idsByName::get).toList();
        Set<String> collectionIds = new LinkedHashSet<>(getStringList(resolved, "collectionIds"));
        collectionIds.addAll(namedIds);
        resolved.put("collectionIds", new ArrayList<>(collectionIds));

        resolved.computeIfAbsent("validationRuleIds",
                k -> repository.findValidationRuleIdsByCollectionIds(tenantId, namedIds));
        resolved.computeIfAbsent("pageLayoutIds",
                k -> repository.findPageLayoutIdsByCollectionIds(tenantId, namedIds));
        resolved.computeIfAbsent("flowIds",
                k -> repository.findFlowIdsByTriggerCollections(tenantId, distinctNames));
        resolved.computeIfAbsent("globalPicklistIds",
                k -> repository.findGlobalPicklistIdsReferencedByCollections(tenantId, namedIds));
        resolved.computeIfAbsent("listViewIds",
                k -> repository.findListViewIdsByCollectionIds(tenantId, namedIds));
        resolved.computeIfAbsent("dashboardIds",
                k -> repository.findDashboardIdsByComponentCollections(tenantId, distinctNames));
        resolved.computeIfAbsent("reportIds",
                k -> repository.findReportIdsByPrimaryCollectionIds(tenantId, namedIds));
        resolved.computeIfAbsent("approvalProcessIds",
                k -> repository.findApprovalProcessIdsByCollectionIds(tenantId, namedIds));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> exportPackage(String tenantId, Map<String, Object> rawOptions,
                                             boolean recordHistory) {
        Map<String, Object> options = resolveExportOptions(tenantId, rawOptions);
        String name = (String) options.get("name");
        String version = (String) options.get("version");
        String description = (String) options.getOrDefault("description", "");

        List<String> collectionIds = getStringList(options, "collectionIds");
        List<String> uiPageIds = getStringList(options, "uiPageIds");
        List<String> uiMenuIds = getStringList(options, "uiMenuIds");
        List<String> flowIds = getStringList(options, "flowIds");
        List<String> pageLayoutIds = getStringList(options, "pageLayoutIds");
        List<String> validationRuleIds = getStringList(options, "validationRuleIds");
        List<String> globalPicklistIds = getStringList(options, "globalPicklistIds");
        List<String> listViewIds = getStringList(options, "listViewIds");
        List<String> dashboardIds = getStringList(options, "dashboardIds");
        List<String> reportIds = getStringList(options, "reportIds");
        List<String> approvalProcessIds = getStringList(options, "approvalProcessIds");

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("formatVersion", 2);
        pkg.put("name", name);
        pkg.put("version", version);
        pkg.put("description", description);
        pkg.put("exportedAt", java.time.Instant.now().toString());

        // Provenance: the only cross-topology identity check an importer can make
        // is source != target — stamp where this package came from (V157).
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("instanceId", repository.findInstanceId().orElse(null));
        source.put("tenantId", tenantId);
        source.put("tenantSlug", repository.findTenantSlug(tenantId).orElse(null));
        pkg.put("source", source);

        List<Map<String, Object>> items = new ArrayList<>();

        // Export collections and their fields (fields carry owning + referenced
        // collection names so the importer can remap FKs by natural key)
        var collections = repository.findCollectionsByIds(tenantId, collectionIds);
        for (var col : collections) {
            items.add(buildItem("COLLECTION", col));
        }
        var fields = repository.findFieldsWithNamesByCollectionIds(tenantId, collectionIds);
        for (var field : fields) {
            items.add(buildItem("FIELD", field));
        }
        for (var col : collections) {
            items.addAll(uniqueConstraintItems((String) col.get("name")));
        }

        // Global picklists + values (GLOBAL-source and FIELD-source)
        var picklists = repository.findGlobalPicklistsByIds(tenantId, globalPicklistIds);
        for (var pl : picklists) {
            items.add(buildItem("GLOBAL_PICKLIST", pl));
        }
        for (var pv : repository.findGlobalPicklistValues(tenantId, globalPicklistIds)) {
            items.add(buildItem("PICKLIST_VALUE", pv));
        }
        for (var pv : repository.findFieldPicklistValues(tenantId, collectionIds)) {
            items.add(buildItem("PICKLIST_VALUE", pv));
        }

        // Validation rules
        for (var vr : repository.findValidationRulesByIds(tenantId, validationRuleIds)) {
            items.add(buildItem("VALIDATION_RULE", vr));
        }

        // Page layouts + sections + layout fields
        for (var pl : repository.findPageLayoutsByIds(tenantId, pageLayoutIds)) {
            items.add(buildItem("PAGE_LAYOUT", pl));
        }
        for (var ls : repository.findLayoutSectionsByLayoutIds(tenantId, pageLayoutIds)) {
            items.add(buildItem("LAYOUT_SECTION", ls));
        }
        for (var lf : repository.findLayoutFieldsByLayoutIds(tenantId, pageLayoutIds)) {
            items.add(buildItem("LAYOUT_FIELD", lf));
        }
        for (var rl : repository.findLayoutRelatedListsByLayoutIds(tenantId, pageLayoutIds)) {
            items.add(buildItem("LAYOUT_RELATED_LIST", rl));
        }

        // Flows
        for (var flow : repository.findFlowsByIds(tenantId, flowIds)) {
            items.add(buildItem("FLOW", flow));
        }

        // Legacy authz (role/policy/route_policy/field_policy) is intentionally
        // never exported — those tables were dropped in V47; authz is profiles + Cerbos.

        // Export UI pages
        var pages = repository.findUiPagesByIds(tenantId, uiPageIds);
        for (var page : pages) {
            items.add(buildItem("UI_PAGE", page));
        }

        // Export UI menus and their items
        var menus = repository.findUiMenusByIds(tenantId, uiMenuIds);
        for (var menu : menus) {
            items.add(buildItem("UI_MENU", menu));
        }
        var menuItems = repository.findUiMenuItemsWithMenuNames(tenantId, uiMenuIds);
        for (var mi : menuItems) {
            items.add(buildItem("UI_MENU_ITEM", mi));
        }

        for (var lv : repository.findListViewsByIds(tenantId, listViewIds)) {
            items.add(buildItem("LIST_VIEW", lv));
        }
        for (var report : repository.findReportsByIds(tenantId, reportIds)) {
            Map<String, Object> item = buildItem("REPORT", report);
            stripIds(item, "id", "primary_collection_id", "folder_id");
            items.add(item);
        }
        items.addAll(approvalProcessItems(tenantId, approvalProcessIds));
        items.addAll(dashboardItems(tenantId, dashboardIds));

        pkg.put("items", items);

        // Record in history
        List<Map<String, Object>> summaryItems = items.stream()
                .map(item -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("type", mapItemTypeToUiType((String) item.get("type")));
                    summary.put("id", ((Map<String, Object>) item.get("data")).get("id"));
                    summary.put("name", ((Map<String, Object>) item.get("data")).get("name"));
                    return summary;
                })
                .toList();

        if (recordHistory) {
            try {
                String itemsJson = objectMapper.writeValueAsString(summaryItems);
                String historyId = repository.save(tenantId, name, version, description, "export", "success", itemsJson);
                log.info("Package exported: id={}, name={}, version={}, items={}", historyId, name, version, items.size());
            } catch (Exception e) {
                log.error("Failed to record export history", e);
            }
        }

        return pkg;
    }

    /**
     * Read-only preview: classifies every package item as create/update/conflict
     * via the import engine's dry-run pass (same natural-key resolution as a real
     * import) and echoes the package's provenance for the caller to display.
     */
    public Map<String, Object> previewImport(String tenantId, Map<String, Object> pkg) {
        var report = importService.importPackage(tenantId, pkg,
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.SKIP, true, null, null, null));

        List<Map<String, Object>> creates = new ArrayList<>();
        List<Map<String, Object>> updates = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (var item : report.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", item.type());
            entry.put("name", item.naturalKey());
            switch (item.action()) {
                case "CREATED" -> creates.add(entry);
                case "UPDATED" -> updates.add(entry);
                default -> {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("item", entry);
                    conflict.put("existingItem", entry);
                    if (item.error() != null) {
                        conflict.put("error", item.error());
                    }
                    conflicts.add(conflict);
                }
            }
        }

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("creates", creates);
        preview.put("updates", updates);
        preview.put("conflicts", conflicts);
        preview.put("items", toItemMaps(report));
        if (pkg.get("source") != null) {
            preview.put("source", pkg.get("source"));
        }
        return preview;
    }

    public Map<String, Object> importPackage(String tenantId, Map<String, Object> pkg, boolean dryRun) {
        return importPackage(tenantId, pkg,
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.SKIP, dryRun, null, null, null));
    }

    /**
     * Applies (or dry-runs) a package via {@link PackageImportService} and records
     * package history. Response keeps the legacy keys the CLI/UI consume
     * (success/created/updated/skipped/errors) plus the per-item report.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importPackage(String tenantId, Map<String, Object> pkg,
                                             PackageImportService.ImportOptions options) {
        String name = (String) pkg.getOrDefault("name", "unnamed");
        String version = (String) pkg.getOrDefault("version", "0.0.0");

        var report = importService.importPackage(tenantId, pkg, options);

        List<Map<String, Object>> errors = new ArrayList<>();
        for (var item : report.items()) {
            if ("FAILED".equals(item.action())) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("item", Map.of("type", item.type(), "name", item.naturalKey()));
                error.put("message", item.error());
                errors.add(error);
            }
        }

        if (!options.dryRun()) {
            try {
                List<Map<String, Object>> summaryItems = report.items().stream()
                        .map(item -> {
                            Map<String, Object> summary = new LinkedHashMap<>();
                            summary.put("type", mapItemTypeToUiType(item.type()));
                            summary.put("name", item.naturalKey());
                            summary.put("action", item.action());
                            return summary;
                        })
                        .toList();
                String itemsJson = objectMapper.writeValueAsString(summaryItems);
                repository.save(tenantId, name, version, null, "import",
                        report.success() ? "success" : "failed", itemsJson);
            } catch (Exception e) {
                log.error("Failed to record import history", e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", report.success());
        result.put("created", report.created());
        result.put("updated", report.updated());
        result.put("skipped", report.skipped());
        result.put("failed", report.failed());
        result.put("items", toItemMaps(report));
        result.put("errors", errors);
        return result;
    }

    private List<Map<String, Object>> toItemMaps(PackageImportService.ImportReport report) {
        return report.items().stream()
                .map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", item.type());
                    m.put("naturalKey", item.naturalKey());
                    m.put("action", item.action());
                    if (item.error() != null) {
                        m.put("error", item.error());
                    }
                    return m;
                })
                .toList();
    }

    public List<Map<String, Object>> getHistory(String tenantId) {
        var rows = repository.findAllByTenantId(tenantId);
        return rows.stream().map(this::mapHistoryRow).toList();
    }

    private Map<String, Object> mapHistoryRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("name", row.get("name"));
        result.put("version", row.get("version"));
        result.put("type", row.get("type"));
        result.put("status", row.get("status"));
        result.put("createdAt", row.get("created_at") != null ? row.get("created_at").toString() : null);

        // Parse items JSON
        Object itemsRaw = row.get("items");
        if (itemsRaw instanceof String itemsStr && !itemsStr.isBlank()) {
            try {
                List<Map<String, Object>> items = objectMapper.readValue(itemsStr,
                        new TypeReference<List<Map<String, Object>>>() {});
                result.put("items", items);
            } catch (Exception e) {
                result.put("items", List.of());
            }
        } else {
            result.put("items", List.of());
        }

        return result;
    }

    /**
     * Dashboards export as the {@code PUT /api/dashboards/{name}/tree} document (name, grid
     * settings, widgets matched on title) — what {@code kelta dashboards apply} takes and what
     * the importer hands to {@link DashboardTreeService#applyTree}. {@code runningUserId} is a
     * source-tenant user and a widget's {@code reportId} a source-tenant report, so neither is
     * carried; a widget without a title gets one, since the tree matches widgets on it.
     */
    private List<Map<String, Object>> dashboardItems(String tenantId, List<String> dashboardIds) {
        Map<String, List<Map<String, Object>>> componentsByDashboard = new HashMap<>();
        for (var row : repository.findDashboardComponentsByDashboardIds(tenantId, dashboardIds)) {
            List<Map<String, Object>> components = componentsByDashboard
                    .computeIfAbsent((String) row.get("dashboard_id"), k -> new ArrayList<>());
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("title", row.get("title") != null
                    ? row.get("title") : row.get("component_type") + " " + (components.size() + 1));
            component.put("componentType", row.get("component_type"));
            component.put("columnPosition", row.get("column_position"));
            component.put("rowPosition", row.get("row_position"));
            putIfPresent(component, "columnSpan", row.get("column_span"));
            putIfPresent(component, "rowSpan", row.get("row_span"));
            Object config = jsonColumnValue(row.get("config"));
            component.put("config", config != null ? config : Map.of());
            components.add(component);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : repository.findDashboardsByIds(tenantId, dashboardIds)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", row.get("name"));
            putIfPresent(data, "description", row.get("description"));
            putIfPresent(data, "accessLevel", row.get("access_level"));
            putIfPresent(data, "columnCount", row.get("column_count"));
            putIfPresent(data, "dynamic", row.get("is_dynamic"));
            data.put("components", componentsByDashboard.getOrDefault((String) row.get("id"), List.of()));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "DASHBOARD");
            item.put("data", data);
            items.add(item);
        }
        return items;
    }

    /**
     * A collection's composite unique constraints, named by collection and field names — the
     * index name embeds the source table and is not carried. A single-field index backing a
     * field's own {@code unique} flag travels with that FIELD and is left out here.
     */
    private List<Map<String, Object>> uniqueConstraintItems(String collectionName) {
        CollectionDefinition def = collectionRegistry.get(collectionName);
        if (def == null) {
            log.warn("Collection '{}' is not registered; its unique constraints are not exported", collectionName);
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (var constraint : uniqueConstraintService.list(def)) {
            List<String> fieldNames = constraint.fieldNames();
            if (fieldNames.size() == 1) {
                FieldDefinition field = def.getField(fieldNames.get(0));
                if (field != null && field.unique()) {
                    continue;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("collection_name", collectionName);
            data.put("field_names", fieldNames);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "UNIQUE_CONSTRAINT");
            item.put("data", data);
            items.add(item);
        }
        return items;
    }

    /**
     * Approval processes with their steps nested under {@code steps}. A step's
     * {@code approver_id} is a source-tenant id, so it travels as the approver's email (USER) or
     * group name (GROUP/QUEUE) and is dropped for every other approver type.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> approvalProcessItems(String tenantId, List<String> processIds) {
        Map<String, List<Map<String, Object>>> stepsByProcess = new HashMap<>();
        for (var row : repository.findApprovalStepsByProcessIds(tenantId, processIds)) {
            Map<String, Object> step = new LinkedHashMap<>(row);
            String processId = (String) step.get("approval_process_id");
            String type = String.valueOf(step.get("approver_type")).toUpperCase(Locale.ROOT);
            if (!"USER".equals(type)) {
                step.remove("approver_email");
            }
            if (!"GROUP".equals(type) && !"QUEUE".equals(type)) {
                step.remove("approver_group_name");
            }
            for (String column : List.of("id", "approval_process_id", "approver_id",
                    "created_by", "updated_by")) {
                step.remove(column);
            }
            step.replaceAll((column, value) -> jsonColumnValue(value));
            stepsByProcess.computeIfAbsent(processId, k -> new ArrayList<>()).add(step);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : repository.findApprovalProcessesByIds(tenantId, processIds)) {
            String processId = (String) row.get("id");
            Map<String, Object> item = buildItem("APPROVAL_PROCESS", row);
            stripIds(item, "id", "collection_id");
            ((Map<String, Object>) item.get("data")).put("steps", stepsByProcess.getOrDefault(processId, List.of()));
            items.add(item);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static void stripIds(Map<String, Object> item, String... columns) {
        Map<String, Object> data = (Map<String, Object>) item.get("data");
        for (String column : columns) {
            data.remove(column);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> buildItem(String type, Map<String, Object> data) {
        Map<String, Object> cleanData = new LinkedHashMap<>(data);
        // Remove tenant-bound and user-bound values: ids are remapped on import,
        // and source-tenant user ids are meaningless in the target tenant.
        cleanData.remove("tenant_id");
        cleanData.remove("created_by");
        cleanData.remove("updated_by");
        cleanData.replaceAll((column, value) -> jsonColumnValue(value));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("data", cleanData);
        return item;
    }

    /**
     * json/jsonb columns come back from {@code queryForList} as the driver's
     * {@code PGobject}; serialized as-is that is {@code {"null","type","value"}}
     * and the importer stores the wrapper instead of the document (BUILD-LOG
     * error 13). Matched by class name: the driver is a runtime-only dependency.
     */
    private Object jsonColumnValue(Object value) {
        if (value == null || !"org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            return value;
        }
        String json = value.toString();
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .toList();
        }
        return List.of();
    }

    private String mapItemTypeToUiType(String type) {
        return switch (type) {
            case "COLLECTION" -> "collection";
            case "FIELD" -> "collection";
            case "UI_PAGE" -> "page";
            case "UI_MENU", "UI_MENU_ITEM" -> "menu";
            case "FLOW" -> "flow";
            case "PAGE_LAYOUT", "LAYOUT_SECTION", "LAYOUT_FIELD", "LAYOUT_RELATED_LIST" -> "layout";
            case "VALIDATION_RULE" -> "validation-rule";
            case "GLOBAL_PICKLIST", "PICKLIST_VALUE" -> "picklist";
            case "LIST_VIEW" -> "list-view";
            case "DASHBOARD" -> "dashboard";
            case "UNIQUE_CONSTRAINT" -> "unique-constraint";
            case "REPORT" -> "report";
            case "APPROVAL_PROCESS" -> "approval-process";
            case "RECORD" -> "record";
            default -> type.toLowerCase();
        };
    }
}
