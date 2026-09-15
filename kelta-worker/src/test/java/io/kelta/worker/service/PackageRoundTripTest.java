package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Export → apply → re-apply over a tenant that exercises every supported
 * package type. Guards the round trip as a whole: a type or a column that the
 * exporter emits but the importer cannot store is a silent loss, and a second
 * apply that re-creates anything means apply never converges.
 */
@DisplayName("Package round trip")
class PackageRoundTripTest {

    private static final String SOURCE = "src-tenant";
    private static final String TARGET = "tgt-tenant";

    /** Join columns the exporter adds purely to carry a natural key across tenants. */
    private static final Set<String> NATURAL_KEY_COLUMNS = Set.of(
            "collection_name", "reference_collection_name", "layout_name", "section_sort_order",
            "field_name", "field_collection_name", "picklist_name", "related_collection_name",
            "relationship_field_name", "relationship_field_collection_name",
            "menu_name", "parent_label");

    /** Owned by the engine (ids) or by the export (stripped before the item is built). */
    private static final Set<String> NON_ROUND_TRIP_COLUMNS = Set.of(
            "id", "created_at", "updated_at", "created_by", "updated_by", "tenant_id");

    private PackageRepository exportRepository;
    private PackageService packageService;

    private QueryEngine queryEngine;
    private JdbcTemplate importJdbc;
    private PackageImportService importService;

    /** Every create the importer made, in order: system collection → written record. */
    private List<Map.Entry<String, Map<String, Object>>> creates;
    private Map<Map<String, Object>, String> createdIds;

    @BeforeEach
    void setUp() {
        exportRepository = mock(PackageRepository.class);
        when(exportRepository.getJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        packageService = new PackageService(exportRepository, new ObjectMapper(),
                mock(PackageImportService.class));
        stubSourceTenant();

        queryEngine = mock(QueryEngine.class);
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(anyString())).thenAnswer(inv ->
                SystemCollectionDefinitions.byName().get(inv.<String>getArgument(0)));
        PackageRepository importRepository = mock(PackageRepository.class);
        importJdbc = mock(JdbcTemplate.class);
        when(importRepository.getJdbcTemplate()).thenReturn(importJdbc);
        when(importJdbc.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
        when(importJdbc.queryForList(contains("FROM platform_user"), eq(TARGET)))
                .thenReturn(List.of(Map.of("id", "tgt-user")));

        creates = new ArrayList<>();
        createdIds = new IdentityHashMap<>();
        when(queryEngine.create(any(), anyMap())).thenAnswer(inv -> {
            CollectionDefinition def = inv.getArgument(0);
            Map<String, Object> record = inv.getArgument(1);
            String id = "tgt-" + def.name() + "-" + creates.size();
            creates.add(Map.entry(def.name(), record));
            createdIds.put(record, id);
            return Map.of("id", id);
        });

        importService = new PackageImportService(queryEngine, registry, importRepository,
                new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // Source tenant fixture — one item of every supported type
    // ------------------------------------------------------------------

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private void stubSourceTenant() {
        List<String> collectionIds = List.of("src-orders", "src-lines");
        when(exportRepository.findTenantSlug(SOURCE)).thenReturn(Optional.of("acme"));
        when(exportRepository.getJdbcTemplate().queryForList(anyString(), eq(String.class), eq(SOURCE)))
                .thenReturn(collectionIds);
        when(exportRepository.findAllIds("ui_page", SOURCE)).thenReturn(List.of("src-page"));
        when(exportRepository.findAllIds("ui_menu", SOURCE)).thenReturn(List.of("src-menu"));
        when(exportRepository.findAllIds("flow", SOURCE)).thenReturn(List.of("src-flow"));
        when(exportRepository.findAllIds("page_layout", SOURCE)).thenReturn(List.of("src-layout"));
        when(exportRepository.findAllIds("validation_rule", SOURCE)).thenReturn(List.of("src-rule"));
        when(exportRepository.findAllIds("global_picklist", SOURCE)).thenReturn(List.of("src-picklist"));

        when(exportRepository.findCollectionsByIds(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-orders", "name", "orders", "display_name", "Orders", "active", true),
                row("id", "src-lines", "name", "order_lines", "display_name", "Lines", "active", true)));
        when(exportRepository.findFieldsWithNamesByCollectionIds(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-status", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "status", "type", "PICKLIST"),
                row("id", "src-order", "collection_id", "src-lines", "collection_name", "order_lines",
                        "name", "order", "type", "LOOKUP",
                        "reference_collection_id", "src-orders", "reference_collection_name", "orders")));
        when(exportRepository.findGlobalPicklistsByIds(SOURCE, List.of("src-picklist"))).thenReturn(List.of(
                row("id", "src-picklist", "name", "statuses")));
        when(exportRepository.findGlobalPicklistValues(SOURCE, List.of("src-picklist"))).thenReturn(List.of(
                row("id", "src-gv", "picklist_source_type", "GLOBAL", "picklist_source_id", "src-picklist",
                        "picklist_name", "statuses", "value", "open", "label", "Open",
                        "color", "#22c55e", "sort_order", 0)));
        when(exportRepository.findFieldPicklistValues(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-fv", "picklist_source_type", "FIELD", "picklist_source_id", "src-status",
                        "field_name", "status", "field_collection_name", "orders",
                        "value", "draft", "label", "Draft", "color", "#64748b", "sort_order", 0)));
        when(exportRepository.findValidationRulesByIds(SOURCE, List.of("src-rule"))).thenReturn(List.of(
                row("id", "src-rule", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "require_status", "error_message", "Status is required")));
        when(exportRepository.findPageLayoutsByIds(SOURCE, List.of("src-layout"))).thenReturn(List.of(
                row("id", "src-layout", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "Default")));
        when(exportRepository.findLayoutSectionsByLayoutIds(SOURCE, List.of("src-layout"))).thenReturn(List.of(
                row("id", "src-section", "layout_id", "src-layout", "layout_name", "Default",
                        "collection_name", "orders", "heading", "Details", "sort_order", 0)));
        when(exportRepository.findLayoutFieldsByLayoutIds(SOURCE, List.of("src-layout"))).thenReturn(List.of(
                row("id", "src-layout-field", "section_id", "src-section", "field_id", "src-status",
                        "section_sort_order", 0, "layout_name", "Default", "collection_name", "orders",
                        "field_name", "status", "field_collection_name", "orders", "sort_order", 0)));
        when(exportRepository.findLayoutRelatedListsByLayoutIds(SOURCE, List.of("src-layout"))).thenReturn(List.of(
                row("id", "src-related", "layout_id", "src-layout", "layout_name", "Default",
                        "collection_name", "orders",
                        "related_collection_id", "src-lines", "related_collection_name", "order_lines",
                        "relationship_field_id", "src-order", "relationship_field_name", "order",
                        "relationship_field_collection_name", "order_lines",
                        "display_columns", List.of("order"), "sort_order", 0)));
        when(exportRepository.findFlowsByIds(SOURCE, List.of("src-flow"))).thenReturn(List.of(
                row("id", "src-flow", "name", "myflow", "flow_type", "AUTOLAUNCHED",
                        "active", false, "version", 1, "definition", Map.of("startAt", "s1"))));
        when(exportRepository.findUiPagesByIds(SOURCE, List.of("src-page"))).thenReturn(List.of(
                row("id", "src-page", "name", "Home", "path", "/home")));
        when(exportRepository.findUiMenusByIds(SOURCE, List.of("src-menu"))).thenReturn(List.of(
                row("id", "src-menu", "name", "main")));
        when(exportRepository.findUiMenuItemsWithMenuNames(SOURCE, List.of("src-menu"))).thenReturn(List.of(
                row("id", "src-child", "menu_id", "src-menu", "menu_name", "main", "label", "Orders",
                        "path", "/orders", "parent_id", "src-group", "parent_label", "Sales",
                        "display_order", 1, "active", true),
                row("id", "src-group", "menu_id", "src-menu", "menu_name", "main", "label", "Sales",
                        "display_order", 0, "active", true)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exportItems() {
        return (List<Map<String, Object>>) packageService.exportPackage(SOURCE, Map.of(), false)
                .get("items");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a whole-tenant export carries at least one item of every supported type")
    void wholeTenantExportCoversEveryType() {
        var byType = new LinkedHashMap<String, Integer>();
        for (var item : exportItems()) {
            byType.merge((String) item.get("type"), 1, Integer::sum);
        }

        assertThat(byType.keySet()).containsExactlyInAnyOrderElementsOf(
                PackageImportService.supportedTypes());
        assertThat(byType.values()).allSatisfy(count -> assertThat(count).isPositive());
    }

    @Test
    @DisplayName("every exported column is one the target system collection can store")
    @SuppressWarnings("unchecked")
    void exportedColumnsAllSurviveTheImport() {
        for (var item : exportItems()) {
            String type = (String) item.get("type");
            CollectionDefinition def = SystemCollectionDefinitions.byName()
                    .get(PackageImportService.systemCollectionFor(type));
            Set<String> storable = new HashSet<>();
            def.fields().forEach(f -> storable.add(f.effectiveColumnName()));

            Set<String> lost = new LinkedHashSet<>(((Map<String, Object>) item.get("data")).keySet());
            lost.removeAll(storable);
            lost.removeAll(NATURAL_KEY_COLUMNS);
            lost.removeAll(NON_ROUND_TRIP_COLUMNS);

            assertThat(lost).as("%s columns dropped on import", type).isEmpty();
        }
    }

    @Test
    @DisplayName("applying to an empty tenant creates every item, remapping every reference")
    void firstApplyCreatesEverything() {
        var report = importService.importPackage(TARGET, Map.of("items", exportItems()),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(report.skipped()).isZero();
        assertThat(report.created()).isEqualTo(exportItems().size());

        Map<String, Object> relatedList = written("layout-related-lists");
        assertThat(relatedList.get("relatedCollectionId")).isEqualTo(idOf("collections", "order_lines"));
        assertThat(relatedList.get("relationshipFieldId")).isEqualTo(idOf("fields", "order"));
        assertThat(relatedList.get("layoutId")).isEqualTo(idOf("page-layouts", "Default"));

        assertThat(creates.stream()
                .filter(c -> c.getKey().equals("picklist-values"))
                .map(c -> c.getValue().get("color")))
                .containsExactlyInAnyOrder("#22c55e", "#64748b");

        Map<String, Object> child = written("ui-menu-items", r -> "Orders".equals(r.get("label")));
        assertThat(child.get("parentId"))
                .isEqualTo(createdIds.get(written("ui-menu-items", r -> "Sales".equals(r.get("label")))));
    }

    @Test
    @DisplayName("re-applying the same package to the tenant it built changes nothing")
    void secondApplyIsAllSkips() {
        var items = exportItems();
        importService.importPackage(TARGET, Map.of("items", items),
                PackageImportService.ImportOptions.defaults());
        seedTargetFromFirstApply();

        var report = importService.importPackage(TARGET, Map.of("items", items),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(items.size());
    }

    @Test
    @DisplayName("OVERWRITE updates in place rather than duplicating")
    void overwriteUpdatesInPlace() {
        var items = exportItems();
        importService.importPackage(TARGET, Map.of("items", items),
                PackageImportService.ImportOptions.defaults());
        seedTargetFromFirstApply();

        var report = importService.importPackage(TARGET, Map.of("items", items),
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.OVERWRITE, false, null, null, null));

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(report.created()).isZero();
        assertThat(report.updated()).isEqualTo(items.size());
    }

    // ------------------------------------------------------------------
    // Target-tenant state after the first apply, as the seed queries see it
    // ------------------------------------------------------------------

    /**
     * Replays the first apply's writes as the rows {@code ImportContext.seed()}
     * would read back, so the second apply resolves each item to what it
     * created. A key the seed SQL composes differently from
     * {@link PackageImportService#naturalKeyFor} shows up here as a re-create.
     */
    private void seedTargetFromFirstApply() {
        seed("FROM collection WHERE tenant_id", "collections",
                r -> row("id", createdIds.get(r), "name", r.get("name")));
        seed("FROM field f ", "fields",
                r -> row("id", createdIds.get(r), "name", r.get("name"),
                        "coll", nameOf("collections", r.get("collectionId"))));
        seed("FROM global_picklist", "global-picklists",
                r -> row("id", createdIds.get(r), "name", r.get("name")));
        seed("FROM picklist_value pv", "picklist-values",
                r -> row("id", createdIds.get(r), "picklist_source_type", r.get("picklistSourceType"),
                        "picklist_source_id", r.get("picklistSourceId"), "value", r.get("value")));
        seed("FROM validation_rule vr", "validation-rules",
                r -> row("id", createdIds.get(r), "name", r.get("name"),
                        "coll", nameOf("collections", r.get("collectionId"))));
        seed("FROM page_layout pl", "page-layouts",
                r -> row("id", createdIds.get(r), "name", r.get("name"),
                        "coll", nameOf("collections", r.get("collectionId"))));
        seed("FROM layout_section ls", "layout-sections",
                r -> row("id", createdIds.get(r), "sort_order", r.get("sortOrder"),
                        "layout_name", nameOf("page-layouts", r.get("layoutId")),
                        "coll", collectionOfLayout(r.get("layoutId"))));
        seed("FROM layout_field lf", "layout-fields",
                r -> row("id", createdIds.get(r),
                        "section_sort", sortOrderOf("layout-sections", r.get("sectionId")),
                        "layout_name", nameOf("page-layouts", layoutOfSection(r.get("sectionId"))),
                        "coll", collectionOfLayout(layoutOfSection(r.get("sectionId"))),
                        "field_name", nameOf("fields", r.get("fieldId"))));
        seed("FROM layout_related_list rl", "layout-related-lists",
                r -> row("id", createdIds.get(r),
                        "layout_name", nameOf("page-layouts", r.get("layoutId")),
                        "coll", collectionOfLayout(r.get("layoutId")),
                        "related_coll", nameOf("collections", r.get("relatedCollectionId")),
                        "field_name", nameOf("fields", r.get("relationshipFieldId"))));
        seed("FROM flow WHERE", "flows",
                r -> row("id", createdIds.get(r), "name", r.get("name")));
        seed("FROM ui_page WHERE", "ui-pages",
                r -> row("id", createdIds.get(r), "name", r.get("name"), "path", r.get("path")));
        seed("FROM ui_menu WHERE", "ui-menus",
                r -> row("id", createdIds.get(r), "name", r.get("name")));
        seed("FROM ui_menu_item mi", "ui-menu-items",
                r -> row("id", createdIds.get(r), "label", r.get("label"),
                        "parent_label", nameOf("ui-menu-items", r.get("parentId"), "label"),
                        "menu_name", nameOf("ui-menus", r.get("menuId"))));
    }

    private void seed(String sqlFragment, String systemCollection,
                      java.util.function.Function<Map<String, Object>, Map<String, Object>> toRow) {
        List<Map<String, Object>> rows = creates.stream()
                .filter(c -> c.getKey().equals(systemCollection))
                .map(c -> toRow.apply(c.getValue()))
                .toList();
        when(importJdbc.queryForList(contains(sqlFragment), eq(TARGET))).thenReturn(rows);
    }

    private Map<String, Object> written(String systemCollection) {
        return written(systemCollection, r -> true);
    }

    private Map<String, Object> written(String systemCollection,
                                        java.util.function.Predicate<Map<String, Object>> match) {
        return creates.stream()
                .filter(c -> c.getKey().equals(systemCollection))
                .map(Map.Entry::getValue)
                .filter(match)
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing written to " + systemCollection));
    }

    private String idOf(String systemCollection, String name) {
        return createdIds.get(written(systemCollection,
                r -> name.equals(r.get("name")) || name.equals(r.get("label"))));
    }

    private Object nameOf(String systemCollection, Object id) {
        return nameOf(systemCollection, id, "name");
    }

    private Object nameOf(String systemCollection, Object id, String nameField) {
        if (id == null) {
            return null;
        }
        return creates.stream()
                .filter(c -> c.getKey().equals(systemCollection) && id.equals(createdIds.get(c.getValue())))
                .map(c -> c.getValue().get(nameField))
                .findFirst()
                .orElse(null);
    }

    private Object sortOrderOf(String systemCollection, Object id) {
        return creates.stream()
                .filter(c -> c.getKey().equals(systemCollection) && id.equals(createdIds.get(c.getValue())))
                .map(c -> c.getValue().get("sortOrder"))
                .findFirst()
                .orElse(null);
    }

    private Object layoutOfSection(Object sectionId) {
        return creates.stream()
                .filter(c -> c.getKey().equals("layout-sections")
                        && sectionId.equals(createdIds.get(c.getValue())))
                .map(c -> c.getValue().get("layoutId"))
                .findFirst()
                .orElse(null);
    }

    private Object collectionOfLayout(Object layoutId) {
        Object collectionId = creates.stream()
                .filter(c -> c.getKey().equals("page-layouts") && layoutId.equals(createdIds.get(c.getValue())))
                .map(c -> c.getValue().get("collectionId"))
                .findFirst()
                .orElse(null);
        return nameOf("collections", collectionId);
    }
}
