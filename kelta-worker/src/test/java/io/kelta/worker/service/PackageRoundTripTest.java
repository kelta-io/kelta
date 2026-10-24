package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.runtime.storage.CompositeUniqueConstraintService.ConstraintInfo;
import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
            "menu_name", "parent_label", "global_picklist_name", "primary_collection_name",
            "folder_name", "approver_email", "approver_group_name");

    /** Owned by the engine (ids) or by the export (stripped before the item is built). */
    private static final Set<String> NON_ROUND_TRIP_COLUMNS = Set.of(
            "id", "created_at", "updated_at", "created_by", "updated_by", "tenant_id");

    private PackageRepository exportRepository;
    private PackageService packageService;

    private QueryEngine queryEngine;
    private JdbcTemplate importJdbc;
    private PackageImportService importService;
    private DashboardTreeService dashboardTreeService;
    /** Composite unique constraints the import created on the target, as the service lists them. */
    private List<ConstraintInfo> targetConstraints;

    /** Every create the importer made, in order: system collection → written record. */
    private List<Map.Entry<String, Map<String, Object>>> creates;
    private Map<Map<String, Object>, String> createdIds;

    @BeforeEach
    void setUp() {
        exportRepository = mock(PackageRepository.class);
        when(exportRepository.getJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        CollectionDefinition orders = new CollectionDefinitionBuilder().name("orders")
                .addField(FieldDefinition.string("status"))
                .addField(FieldDefinition.string("stage"))
                .build();
        CollectionRegistry exportRegistry = mock(CollectionRegistry.class);
        when(exportRegistry.get("orders")).thenReturn(orders);
        CompositeUniqueConstraintService exportConstraints = mock(CompositeUniqueConstraintService.class);
        when(exportConstraints.list(orders)).thenReturn(List.of(new ConstraintInfo(
                "uniq_src_orders_status_stage", List.of("status", "stage"), List.of("status", "stage"))));
        packageService = new PackageService(exportRepository, new ObjectMapper(),
                mock(PackageImportService.class), exportRegistry, exportConstraints);
        stubSourceTenant();

        queryEngine = mock(QueryEngine.class);
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get(anyString())).thenAnswer(inv ->
                SystemCollectionDefinitions.byName().get(inv.<String>getArgument(0)));
        when(registry.get("orders")).thenReturn(orders);
        targetConstraints = new ArrayList<>();
        CompositeUniqueConstraintService importConstraints = mock(CompositeUniqueConstraintService.class);
        when(importConstraints.list(orders)).thenAnswer(inv -> List.copyOf(targetConstraints));
        when(importConstraints.create(eq(orders), anyList())).thenAnswer(inv -> {
            List<String> fieldNames = inv.getArgument(1);
            ConstraintInfo info = new ConstraintInfo("uniq_tgt_orders_" + String.join("_", fieldNames),
                    fieldNames, fieldNames);
            targetConstraints.add(info);
            return info;
        });
        PackageRepository importRepository = mock(PackageRepository.class);
        importJdbc = mock(JdbcTemplate.class);
        when(importRepository.getJdbcTemplate()).thenReturn(importJdbc);
        when(importJdbc.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
        when(importJdbc.queryForList(contains("FROM platform_user"), eq(TARGET)))
                .thenReturn(List.of(Map.of("id", "tgt-user")));
        when(importJdbc.queryForList(contains("lower(email)"), eq(TARGET), eq("ann@acme.io")))
                .thenReturn(List.of(Map.of("id", "tgt-ann")));
        when(importJdbc.queryForList(contains("FROM user_group"), eq(TARGET), eq("Finance")))
                .thenReturn(List.of(Map.of("id", "tgt-finance")));

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

        dashboardTreeService = mock(DashboardTreeService.class);
        when(dashboardTreeService.applyTree(anyString(), anyMap())).thenAnswer(inv ->
                new DashboardTreeService.ApplyResult("tgt-dashboard-" + inv.getArgument(0),
                        inv.getArgument(0), new DashboardTreeService.TreeCounts(1, 0, 0, 0)));

        importService = new PackageImportService(queryEngine, registry, importRepository,
                new ObjectMapper(), (identifier, tenantId) -> identifier, dashboardTreeService,
                new DashboardComponentValidator(registry, mock(CollectionLifecycleManager.class), queryEngine),
                mock(PlatformTransactionManager.class), importConstraints);
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
        when(exportRepository.findAllIds("list_view", SOURCE)).thenReturn(List.of("src-view"));
        when(exportRepository.findAllIds("dashboard", SOURCE)).thenReturn(List.of("src-dash"));
        when(exportRepository.findAllIds("report", SOURCE)).thenReturn(List.of("src-report"));
        when(exportRepository.findAllIds("approval_process", SOURCE)).thenReturn(List.of("src-approval"));

        when(exportRepository.findCollectionsByIds(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-orders", "name", "orders", "display_name", "Orders", "active", true),
                row("id", "src-lines", "name", "order_lines", "display_name", "Lines", "active", true)));
        when(exportRepository.findFieldsWithNamesByCollectionIds(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-status", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "status", "type", "PICKLIST"),
                row("id", "src-order", "collection_id", "src-lines", "collection_name", "order_lines",
                        "name", "order", "type", "LOOKUP",
                        "reference_collection_id", "src-orders", "reference_collection_name", "orders"),
                row("id", "src-stage", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "stage", "type", "PICKLIST",
                        "field_type_config", Map.of("globalPicklistId", "src-picklist"),
                        "global_picklist_name", "statuses")));
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
        when(exportRepository.findListViewsByIds(SOURCE, List.of("src-view"))).thenReturn(List.of(
                row("id", "src-view", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "Open orders", "visibility", "PUBLIC", "is_default", false,
                        "columns", List.of("status", "stage"),
                        "filters", List.of(Map.of("field", "status", "operator", "eq", "value", "draft")),
                        "sort_field", "status", "sort_direction", "DESC", "row_limit", 25,
                        "view_type", "TABLE", "created_by", "src-user")));
        when(exportRepository.findDashboardsByIds(SOURCE, List.of("src-dash"))).thenReturn(List.of(
                row("id", "src-dash", "name", "Order health", "description", "Orders by status",
                        "access_level", "PUBLIC", "is_dynamic", false, "column_count", 2,
                        "running_user_id", "src-user", "created_by", "src-user")));
        when(exportRepository.findDashboardComponentsByDashboardIds(SOURCE, List.of("src-dash"))).thenReturn(List.of(
                row("id", "src-widget", "dashboard_id", "src-dash", "report_id", "src-report",
                        "component_type", "chart", "title", "By status", "column_position", 1,
                        "row_position", 1, "column_span", 2, "row_span", 1, "sort_order", 0,
                        "config", Map.of("groupByField", "status"))));
        when(exportRepository.findReportsByIds(SOURCE, List.of("src-report"))).thenReturn(List.of(
                row("id", "src-report", "name", "Orders by stage", "report_type", "SUMMARY",
                        "primary_collection_id", "src-orders", "primary_collection_name", "orders",
                        "columns", List.of(Map.of("fieldName", "status"), Map.of("fieldName", "stage")),
                        "group_by", "stage", "access_level", "PUBLIC", "created_by", "src-user")));
        when(exportRepository.findApprovalProcessesByIds(SOURCE, List.of("src-approval"))).thenReturn(List.of(
                row("id", "src-approval", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "Large order", "active", true, "record_editability", "LOCKED")));
        when(exportRepository.findApprovalStepsByProcessIds(SOURCE, List.of("src-approval"))).thenReturn(List.of(
                row("id", "src-step-1", "approval_process_id", "src-approval", "step_number", 1,
                        "name", "Manager", "approver_type", "USER", "approver_id", "src-ann",
                        "approver_email", "ann@acme.io", "approver_group_name", null),
                row("id", "src-step-2", "approval_process_id", "src-approval", "step_number", 2,
                        "name", "Finance", "approver_type", "GROUP", "approver_id", "src-finance",
                        "approver_email", null, "approver_group_name", "Finance")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exportItems() {
        return (List<Map<String, Object>>) packageService.exportPackage(SOURCE, Map.of(), false)
                .get("items");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a whole-tenant export carries at least one item of every supported type, and never seed records")
    void wholeTenantExportCoversEveryType() {
        var byType = new LinkedHashMap<String, Integer>();
        for (var item : exportItems()) {
            byType.merge((String) item.get("type"), 1, Integer::sum);
        }

        List<String> itemTypes = PackageImportService.supportedTypes().stream()
                .filter(type -> !"RECORD".equals(type))
                .toList();
        assertThat(byType.keySet()).containsExactlyInAnyOrderElementsOf(itemTypes);
        assertThat(byType.values()).allSatisfy(count -> assertThat(count).isPositive());
        assertThat(packageService.exportPackage(SOURCE, Map.of(), false)).doesNotContainKey("records");
    }

    @Test
    @DisplayName("every exported column is one the target system collection can store")
    @SuppressWarnings("unchecked")
    void exportedColumnsAllSurviveTheImport() {
        for (var item : exportItems()) {
            String type = (String) item.get("type");
            if ("DASHBOARD".equals(type) || "UNIQUE_CONSTRAINT".equals(type)) {
                continue; // a tree document / an index, not table columns
            }
            Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) item.get("data"));
            if ("APPROVAL_PROCESS".equals(type)) {
                for (var step : (List<Map<String, Object>>) data.remove("steps")) {
                    assertThat(lostColumns("approval-steps", step)).as("approval step columns dropped on import")
                            .isEmpty();
                }
            }
            assertThat(lostColumns(PackageImportService.systemCollectionFor(type), data))
                    .as("%s columns dropped on import", type).isEmpty();
        }
    }

    private static Set<String> lostColumns(String systemCollection, Map<String, Object> data) {
        CollectionDefinition def = SystemCollectionDefinitions.byName().get(systemCollection);
        Set<String> lost = new LinkedHashSet<>(data.keySet());
        def.fields().forEach(f -> lost.remove(f.effectiveColumnName()));
        lost.removeAll(NATURAL_KEY_COLUMNS);
        lost.removeAll(NON_ROUND_TRIP_COLUMNS);
        return lost;
    }

    @Test
    @DisplayName("unique constraints, reports and approval processes round-trip with every reference resolved by name")
    void constraintsReportsAndApprovalProcessesRoundTrip() {
        var report = importService.importPackage(TARGET, Map.of("items", exportItems()),
                PackageImportService.ImportOptions.defaults());
        assertThat(report.failed()).as("%s", report.items()).isZero();

        assertThat(targetConstraints).extracting(ConstraintInfo::fieldNames)
                .containsExactly(List.of("stage", "status"));
        assertThat(written("reports"))
                .containsEntry("name", "Orders by stage")
                .containsEntry("primaryCollectionId", idOf("collections", "orders"))
                .containsEntry("groupBy", "stage")
                .doesNotContainKeys("folderId", "createdBy");
        Map<String, Object> process = written("approval-processes");
        assertThat(process)
                .containsEntry("collectionId", idOf("collections", "orders"))
                .containsEntry("name", "Large order");
        assertThat(written("approval-steps", r -> Integer.valueOf(1).equals(r.get("stepNumber"))))
                .containsEntry("approvalProcessId", createdIds.get(process))
                .containsEntry("approverId", "tgt-ann");
        assertThat(written("approval-steps", r -> Integer.valueOf(2).equals(r.get("stepNumber"))))
                .containsEntry("approverId", "tgt-finance");
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

        @SuppressWarnings("unchecked")
        Map<String, Object> stageConfig = (Map<String, Object>)
                written("fields", r -> "stage".equals(r.get("name"))).get("fieldTypeConfig");
        assertThat(stageConfig.get("globalPicklistId"))
                .as("bound to the target's id for 'statuses', not the source id (BUILD-LOG error 14)")
                .isEqualTo(idOf("global-picklists", "statuses"))
                .isNotEqualTo("src-picklist");

        Map<String, Object> child = written("ui-menu-items", r -> "Orders".equals(r.get("label")));
        assertThat(child.get("parentId"))
                .isEqualTo(createdIds.get(written("ui-menu-items", r -> "Sales".equals(r.get("label")))));
    }

    @Test
    @DisplayName("list views and dashboards round-trip: export → apply writes what the source holds")
    @SuppressWarnings("unchecked")
    void listViewsAndDashboardsRoundTrip() {
        var report = importService.importPackage(TARGET, Map.of("items", exportItems()),
                PackageImportService.ImportOptions.defaults());
        assertThat(report.failed()).as("%s", report.items()).isZero();

        Map<String, Object> view = written("list-views");
        assertThat(view)
                .containsEntry("collectionId", idOf("collections", "orders"))
                .containsEntry("name", "Open orders")
                .containsEntry("visibility", "PUBLIC")
                .containsEntry("columns", List.of("status", "stage"))
                .containsEntry("filters", List.of(Map.of("field", "status", "operator", "eq", "value", "draft")))
                .containsEntry("sortField", "status")
                .containsEntry("sortDirection", "DESC")
                .containsEntry("rowLimit", 25)
                .containsEntry("viewType", "TABLE")
                .doesNotContainKeys("createdBy", "id");

        var tree = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(dashboardTreeService).applyTree(eq("Order health"), tree.capture());
        Map<String, Object> applied = tree.getValue();
        assertThat(applied)
                .containsEntry("description", "Orders by status")
                .containsEntry("accessLevel", "PUBLIC")
                .containsEntry("columnCount", 2)
                .containsEntry("dynamic", false)
                .doesNotContainKeys("runningUserId", "name", "id");
        assertThat((List<Map<String, Object>>) applied.get("components")).containsExactly(Map.of(
                "title", "By status", "componentType", "chart", "columnPosition", 1, "rowPosition", 1,
                "columnSpan", 2, "rowSpan", 1, "config", Map.of("groupByField", "status")));
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

        long constraints = items.stream().filter(i -> "UNIQUE_CONSTRAINT".equals(i.get("type"))).count();
        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(report.created()).isZero();
        assertThat(report.updated()).isEqualTo(items.size() - constraints);
        assertThat(report.skipped()).as("a field set has nothing to overwrite").isEqualTo(constraints);
        assertThat(creates.stream().filter(c -> c.getKey().equals("approval-steps")))
                .as("existing approval steps are updated by step number, not added again")
                .hasSize(2);
    }

    @Test
    @DisplayName("jsonb columns survive export → JSON → apply as structures (BUILD-LOG error 13)")
    @SuppressWarnings("unchecked")
    void jsonbColumnsSurviveTheHttpRoundTrip() throws Exception {
        List<String> collectionIds = List.of("src-orders", "src-lines");
        when(exportRepository.findFieldsWithNamesByCollectionIds(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-status", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "status", "type", "PICKLIST",
                        "field_type_config", jsonb("{\"restricted\":true}")),
                row("id", "src-stage", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "stage", "type", "STRING"),
                row("id", "src-order", "collection_id", "src-lines", "collection_name", "order_lines",
                        "name", "order", "type", "LOOKUP",
                        "reference_collection_id", "src-orders", "reference_collection_name", "orders")));
        when(exportRepository.findFlowsByIds(SOURCE, List.of("src-flow"))).thenReturn(List.of(
                row("id", "src-flow", "name", "Stamp closed deals", "flow_type", "RECORD_TRIGGERED",
                        "active", false, "version", 1,
                        "definition", jsonb("{\"StartAt\":\"stamp\",\"States\":{\"stamp\":{\"Type\":\"Succeed\"}}}"))));

        ObjectMapper http = new ObjectMapper();
        String body = http.writeValueAsString(packageService.exportPackage(SOURCE, Map.of(), false));
        Map<String, Object> received = http.readValue(body, Map.class);

        var report = importService.importPackage(TARGET, received,
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        Object definition = written("flows").get("definition");
        assertThat(definition).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) definition).containsEntry("StartAt", "stamp");
        Object fieldTypeConfig = written("fields", r -> "status".equals(r.get("name"))).get("fieldTypeConfig");
        assertThat(fieldTypeConfig).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) fieldTypeConfig).containsEntry("restricted", true);
    }

    @Test
    @DisplayName("a field's systemMaintained flag survives export → JSON → apply to a clean tenant (KLT-610)")
    void fieldSystemMaintainedRoundTrips() throws Exception {
        List<String> collectionIds = List.of("src-orders", "src-lines");
        when(exportRepository.findFieldsWithNamesByCollectionIds(SOURCE, collectionIds)).thenReturn(List.of(
                row("id", "src-failures", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "failure_count", "type", "INTEGER", "system_maintained", true),
                row("id", "src-status", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "status", "type", "PICKLIST", "system_maintained", false),
                row("id", "src-stage", "collection_id", "src-orders", "collection_name", "orders",
                        "name", "stage", "type", "STRING", "system_maintained", false),
                row("id", "src-order", "collection_id", "src-lines", "collection_name", "order_lines",
                        "name", "order", "type", "LOOKUP", "system_maintained", false,
                        "reference_collection_id", "src-orders", "reference_collection_name", "orders")));

        ObjectMapper http = new ObjectMapper();
        String body = http.writeValueAsString(packageService.exportPackage(SOURCE, Map.of(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> received = http.readValue(body, Map.class);

        var report = importService.importPackage(TARGET, received,
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(written("fields", r -> "failure_count".equals(r.get("name"))))
                .containsEntry("systemMaintained", true);
        assertThat(written("fields", r -> "status".equals(r.get("name"))))
                .containsEntry("systemMaintained", false);
    }

    @Test
    @DisplayName("a package exported before systemMaintained existed applies with the flag false (KLT-610)")
    void fieldWithoutSystemMaintainedKeyAppliesFalse() {
        var items = exportItems();
        assertThat(items.stream()
                .filter(item -> "FIELD".equals(item.get("type")))
                .map(item -> ((Map<?, ?>) item.get("data")).containsKey("system_maintained")))
                .as("fixture rows predate the column")
                .containsOnly(false);

        var report = importService.importPackage(TARGET, Map.of("items", items),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(written("fields", r -> "status".equals(r.get("name"))))
                .doesNotContainKey("systemMaintained");
        // The engine fills an absent attribute from the system collection's default on create.
        assertThat(SystemCollectionDefinitions.byName().get("fields").getField("systemMaintained")
                .defaultValue()).isEqualTo(false);
    }

    @Test
    @DisplayName("a flow's stepLogging survives export → JSON → apply (PLT-602)")
    void flowStepLoggingRoundTrips() throws Exception {
        when(exportRepository.findFlowsByIds(SOURCE, List.of("src-flow"))).thenReturn(List.of(
                row("id", "src-flow", "name", "High-volume ingest", "flow_type", "SCHEDULED",
                        "active", true, "version", 1, "step_logging", "ERRORS_ONLY",
                        "definition", jsonb("{\"StartAt\":\"done\",\"States\":{\"done\":{\"Type\":\"Succeed\"}}}"))));

        ObjectMapper http = new ObjectMapper();
        String body = http.writeValueAsString(packageService.exportPackage(SOURCE, Map.of(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> received = http.readValue(body, Map.class);

        var report = importService.importPackage(TARGET, received,
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(written("flows")).containsEntry("name", "High-volume ingest")
                .containsEntry("stepLogging", "ERRORS_ONLY");
    }

    @Test
    @DisplayName("a flow's retentionDays survives export → JSON → apply (PLT-603)")
    void flowRetentionDaysRoundTrips() throws Exception {
        when(exportRepository.findFlowsByIds(SOURCE, List.of("src-flow"))).thenReturn(List.of(
                row("id", "src-flow", "name", "Every-15-minutes poll", "flow_type", "SCHEDULED",
                        "active", true, "version", 1, "retention_days", 7,
                        "definition", jsonb("{\"StartAt\":\"done\",\"States\":{\"done\":{\"Type\":\"Succeed\"}}}"))));

        ObjectMapper http = new ObjectMapper();
        String body = http.writeValueAsString(packageService.exportPackage(SOURCE, Map.of(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> received = http.readValue(body, Map.class);

        var report = importService.importPackage(TARGET, received,
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).as("%s", report.items()).isZero();
        assertThat(written("flows")).containsEntry("name", "Every-15-minutes poll")
                .containsEntry("retentionDays", 7);
    }

    private static PGobject jsonb(String json) throws Exception {
        PGobject value = new PGobject();
        value.setType("jsonb");
        value.setValue(json);
        return value;
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
        seed("FROM list_view lv", "list-views",
                r -> row("id", createdIds.get(r), "name", r.get("name"),
                        "coll", nameOf("collections", r.get("collectionId"))));
        when(importJdbc.queryForList(contains("FROM dashboard WHERE"), eq(TARGET))).thenReturn(
                mockingDetails(dashboardTreeService).getInvocations().stream()
                        .filter(inv -> inv.getMethod().getName().equals("applyTree"))
                        .map(inv -> row("id", "tgt-dashboard-" + inv.getArgument(0), "name", inv.getArgument(0)))
                        .toList());
        seed("FROM report WHERE", "reports",
                r -> row("id", createdIds.get(r), "name", r.get("name")));
        seed("FROM approval_process ap", "approval-processes",
                r -> row("id", createdIds.get(r), "name", r.get("name"),
                        "coll", nameOf("collections", r.get("collectionId"))));
        when(importJdbc.queryForList(contains("FROM approval_step WHERE"), anyString())).thenAnswer(inv ->
                creates.stream()
                        .filter(c -> c.getKey().equals("approval-steps")
                                && inv.getArgument(1).equals(c.getValue().get("approvalProcessId")))
                        .map(c -> row("id", createdIds.get(c.getValue()), "step_number", c.getValue().get("stepNumber")))
                        .toList());
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
