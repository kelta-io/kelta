package io.kelta.worker.service;

import io.kelta.worker.repository.PackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class PackageService {

    private static final Logger log = LoggerFactory.getLogger(PackageService.class);

    /** Option keys naming the ids to export; none present means "the whole tenant". */
    private static final List<String> ID_OPTION_KEYS = List.of(
            "collectionIds", "uiPageIds", "uiMenuIds", "flowIds", "pageLayoutIds",
            "validationRuleIds", "globalPicklistIds");

    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String DEFAULT_NAME = "metadata";

    private final PackageRepository repository;
    private final ObjectMapper objectMapper;
    private final PackageImportService importService;

    public PackageService(PackageRepository repository, ObjectMapper objectMapper,
                          PackageImportService importService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.importService = importService;
    }

    /**
     * Gathers export options covering every packageable item in the tenant.
     * Used by sandbox cloning and full promotions.
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
     * list is respected as-is.
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

        Map<String, Object> resolved = ID_OPTION_KEYS.stream().anyMatch(options::containsKey)
                ? new LinkedHashMap<>(options)
                : exportAllOptions(tenantId, name, version);
        resolved.putAll(options);
        resolved.put("name", name);
        resolved.put("version", version);
        return resolved;
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

    private Map<String, Object> buildItem(String type, Map<String, Object> data) {
        Map<String, Object> cleanData = new LinkedHashMap<>(data);
        // Remove tenant-bound and user-bound values: ids are remapped on import,
        // and source-tenant user ids are meaningless in the target tenant.
        cleanData.remove("tenant_id");
        cleanData.remove("created_by");
        cleanData.remove("updated_by");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("data", cleanData);
        return item;
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
            default -> type.toLowerCase();
        };
    }
}
