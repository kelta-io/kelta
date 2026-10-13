package io.kelta.worker.service;

import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.*;
import org.postgresql.util.PGobject;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Package format v2 export: provenance stamping, extended type coverage,
 * user-id stripping, and full-tenant option collection.
 */
@DisplayName("PackageService export (format v2)")
class PackageServiceExportTest {

    private static final String TENANT = "t1";

    private PackageRepository repository;
    private PackageImportService importService;
    private JdbcTemplate jdbcTemplate;
    private PackageService service;

    @BeforeEach
    void setUp() {
        repository = mock(PackageRepository.class);
        importService = mock(PackageImportService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(repository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(TENANT)))
                .thenReturn(List.of());
        service = new PackageService(repository, new ObjectMapper(), importService);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    @DisplayName("stamps formatVersion 2 and source provenance (instanceId/tenantId/slug)")
    void stampsFormatVersionAndProvenance() {
        when(repository.findInstanceId()).thenReturn(Optional.of("inst-1"));
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.save(any(), any(), any(), any(), any(), any(), any())).thenReturn("hist-1");

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("name", "pkg");
        options.put("version", "1.0.0");

        var pkg = service.exportPackage(TENANT, options);

        assertThat(pkg.get("formatVersion")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) pkg.get("source");
        assertThat(source)
                .containsEntry("instanceId", "inst-1")
                .containsEntry("tenantId", TENANT)
                .containsEntry("tenantSlug", "acme");
    }

    @Test
    @DisplayName("exports the v2 types with natural-key join columns and strips user/tenant ids")
    @SuppressWarnings("unchecked")
    void exportsNewTypesAndStripsUserIds() {
        when(repository.findInstanceId()).thenReturn(Optional.of("inst-1"));
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.save(any(), any(), any(), any(), any(), any(), any())).thenReturn("hist-1");

        List<String> collectionIds = List.of("c1");
        List<String> layoutIds = List.of("pl1");

        when(repository.findCollectionsByIds(TENANT, collectionIds)).thenReturn(List.of(
                row("id", "c1", "name", "orders", "tenant_id", TENANT,
                        "created_by", "u-9", "updated_by", "u-9")));
        when(repository.findFieldsWithNamesByCollectionIds(TENANT, collectionIds)).thenReturn(List.of(
                row("id", "fld1", "name", "customer", "type", "LOOKUP",
                        "collection_name", "orders",
                        "reference_collection_id", "c2", "reference_collection_name", "customers",
                        "created_by", "u-9")));
        when(repository.findGlobalPicklistsByIds(TENANT, List.of("gp1"))).thenReturn(List.of(
                row("id", "gp1", "name", "colors", "created_by", "u-9")));
        when(repository.findGlobalPicklistValues(TENANT, List.of("gp1"))).thenReturn(List.of(
                row("id", "pv1", "value", "red", "picklist_source_type", "GLOBAL",
                        "picklist_name", "colors")));
        when(repository.findValidationRulesByIds(TENANT, List.of("vr1"))).thenReturn(List.of(
                row("id", "vr1", "name", "require_customer", "collection_name", "orders")));
        when(repository.findPageLayoutsByIds(TENANT, layoutIds)).thenReturn(List.of(
                row("id", "pl1", "name", "Default", "collection_name", "orders")));
        when(repository.findLayoutSectionsByLayoutIds(TENANT, layoutIds)).thenReturn(List.of(
                row("id", "ls1", "sort_order", 0, "layout_name", "Default",
                        "collection_name", "orders")));
        when(repository.findLayoutFieldsByLayoutIds(TENANT, layoutIds)).thenReturn(List.of(
                row("id", "lf1", "section_sort_order", 0, "layout_name", "Default",
                        "collection_name", "orders", "field_name", "customer",
                        "field_collection_name", "orders")));
        when(repository.findLayoutRelatedListsByLayoutIds(TENANT, layoutIds)).thenReturn(List.of(
                row("id", "rl1", "sort_order", 0, "layout_name", "Default",
                        "collection_name", "orders", "related_collection_name", "order_lines",
                        "relationship_field_name", "order",
                        "relationship_field_collection_name", "order_lines")));
        when(repository.findFlowsByIds(TENANT, List.of("f1"))).thenReturn(List.of(
                row("id", "f1", "name", "myflow", "flow_type", "AUTOLAUNCHED",
                        "tenant_id", TENANT, "created_by", "u-9")));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("name", "pkg");
        options.put("version", "1.0.0");
        options.put("collectionIds", collectionIds);
        options.put("globalPicklistIds", List.of("gp1"));
        options.put("validationRuleIds", List.of("vr1"));
        options.put("pageLayoutIds", layoutIds);
        options.put("flowIds", List.of("f1"));

        var pkg = service.exportPackage(TENANT, options);

        var items = (List<Map<String, Object>>) pkg.get("items");
        assertThat(items.stream().map(i -> i.get("type")).toList()).containsExactlyInAnyOrder(
                "COLLECTION", "FIELD", "GLOBAL_PICKLIST", "PICKLIST_VALUE",
                "VALIDATION_RULE", "PAGE_LAYOUT", "LAYOUT_SECTION", "LAYOUT_FIELD",
                "LAYOUT_RELATED_LIST", "FLOW");

        for (var item : items) {
            var data = (Map<String, Object>) item.get("data");
            assertThat(data).as("%s carries no source user ids", item.get("type"))
                    .doesNotContainKeys("created_by", "updated_by", "tenant_id");
        }

        // Natural-key join columns survive for the importer's remap.
        var field = items.stream().filter(i -> "FIELD".equals(i.get("type"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) field.get("data"))
                .containsEntry("collection_name", "orders")
                .containsEntry("reference_collection_name", "customers");
        var layoutField = items.stream().filter(i -> "LAYOUT_FIELD".equals(i.get("type"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) layoutField.get("data"))
                .containsEntry("layout_name", "Default")
                .containsEntry("field_collection_name", "orders");
        var relatedList = items.stream().filter(i -> "LAYOUT_RELATED_LIST".equals(i.get("type")))
                .findFirst().orElseThrow();
        assertThat((Map<String, Object>) relatedList.get("data"))
                .containsEntry("layout_name", "Default")
                .containsEntry("related_collection_name", "order_lines")
                .containsEntry("relationship_field_name", "order");
    }

    private static PGobject jsonb(String json) throws Exception {
        PGobject value = new PGobject();
        value.setType("jsonb");
        value.setValue(json);
        return value;
    }

    @Test
    @DisplayName("exports jsonb columns as JSON, not as the driver's {null,type,value} wrapper")
    @SuppressWarnings("unchecked")
    void exportsJsonbColumnsAsJson() throws Exception {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.findCollectionsByIds(TENANT, List.of("c1"))).thenReturn(List.of(
                row("id", "c1", "name", "orders")));
        when(repository.findFieldsWithNamesByCollectionIds(TENANT, List.of("c1"))).thenReturn(List.of(
                row("id", "fld1", "name", "amount", "type", "CURRENCY", "collection_name", "orders",
                        "field_type_config", jsonb("{\"precision\":2,\"currency\":\"USD\"}"),
                        "constraints", jsonb(null))));
        when(repository.findFlowsByIds(TENANT, List.of("f1"))).thenReturn(List.of(
                row("id", "f1", "name", "Stamp closed deals", "flow_type", "RECORD_TRIGGERED",
                        "definition", jsonb("{\"StartAt\":\"stamp\",\"States\":{\"stamp\":{\"Type\":\"Succeed\"}}}"))));

        var pkg = service.exportPackage(TENANT,
                Map.of("name", "pkg", "version", "1.0.0", "collectionIds", List.of("c1"),
                        "flowIds", List.of("f1")), false);
        String json = new ObjectMapper().writeValueAsString(pkg);

        assertThat(json)
                .doesNotContain("\"type\":\"jsonb\"")
                .doesNotContain("\"null\":false")
                .contains("\"field_type_config\":{\"precision\":2,\"currency\":\"USD\"}")
                .contains("\"definition\":{\"StartAt\":\"stamp\"")
                .contains("\"constraints\":null");
        var items = (List<Map<String, Object>>) pkg.get("items");
        var flow = (Map<String, Object>) items.stream().filter(i -> "FLOW".equals(i.get("type")))
                .findFirst().orElseThrow().get("data");
        assertThat((Map<String, Object>) flow.get("definition")).containsEntry("StartAt", "stamp");
    }

    @Test
    @DisplayName("a field bound to a global picklist exports the picklist's name (BUILD-LOG error 14)")
    @SuppressWarnings("unchecked")
    void exportsGlobalPicklistNameOnBoundField() throws Exception {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.findCollectionsByIds(TENANT, List.of("c1"))).thenReturn(List.of(
                row("id", "c1", "name", "deals")));
        when(repository.findFieldsWithNamesByCollectionIds(TENANT, List.of("c1"))).thenReturn(List.of(
                row("id", "fld1", "name", "stage", "type", "PICKLIST", "collection_name", "deals",
                        "field_type_config", jsonb("{\"globalPicklistId\":\"gp-1\"}"),
                        "global_picklist_name", "crm-deal-stage")));

        var pkg = service.exportPackage(TENANT,
                Map.of("name", "pkg", "version", "1.0.0", "collectionIds", List.of("c1")), false);
        Map<String, Object> received = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsString(pkg), Map.class);

        var field = ((List<Map<String, Object>>) received.get("items")).stream()
                .filter(i -> "FIELD".equals(i.get("type"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) field.get("data"))
                .containsEntry("global_picklist_name", "crm-deal-stage")
                .containsEntry("field_type_config", Map.of("globalPicklistId", "gp-1"));
    }

    @Test
    @DisplayName("a request naming no ids exports the whole tenant")
    @SuppressWarnings("unchecked")
    void noIdsMeansWholeTenant() {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(jdbcTemplate.queryForList(contains("FROM collection"), eq(String.class), eq(TENANT)))
                .thenReturn(List.of("c1"));
        when(repository.findAllIds("ui_menu", TENANT)).thenReturn(List.of("um1"));
        when(repository.findAllIds("page_layout", TENANT)).thenReturn(List.of("pl1"));
        when(repository.findAllIds("global_picklist", TENANT)).thenReturn(List.of("gp1"));
        when(repository.findCollectionsByIds(TENANT, List.of("c1"))).thenReturn(List.of(
                row("id", "c1", "name", "orders")));
        when(repository.findUiMenusByIds(TENANT, List.of("um1"))).thenReturn(List.of(
                row("id", "um1", "name", "main")));
        when(repository.findUiMenuItemsWithMenuNames(TENANT, List.of("um1"))).thenReturn(List.of(
                row("id", "mi1", "label", "Orders", "menu_name", "main")));
        when(repository.findPageLayoutsByIds(TENANT, List.of("pl1"))).thenReturn(List.of(
                row("id", "pl1", "name", "Default", "collection_name", "orders")));
        when(repository.findGlobalPicklistsByIds(TENANT, List.of("gp1"))).thenReturn(List.of(
                row("id", "gp1", "name", "statuses")));
        when(repository.findGlobalPicklistValues(TENANT, List.of("gp1"))).thenReturn(List.of(
                row("id", "pv1", "value", "open", "picklist_source_type", "GLOBAL",
                        "picklist_name", "statuses", "color", "#22c55e")));

        var pkg = service.exportPackage(TENANT, Map.of());

        assertThat(pkg).containsEntry("name", "acme").containsEntry("version", "1.0.0");
        var items = (List<Map<String, Object>>) pkg.get("items");
        assertThat(items.stream().map(i -> i.get("type")).toList())
                .contains("COLLECTION", "UI_MENU", "UI_MENU_ITEM", "PAGE_LAYOUT",
                        "GLOBAL_PICKLIST", "PICKLIST_VALUE");
        var value = items.stream().filter(i -> "PICKLIST_VALUE".equals(i.get("type")))
                .findFirst().orElseThrow();
        assertThat((Map<String, Object>) value.get("data")).containsEntry("color", "#22c55e");
    }

    /**
     * Two app collections ({@code sites}, {@code inspections}) share the tenant with
     * an unrelated {@code leads} collection that has its own layout, rule and flow,
     * plus a UI page and menu. The whole-tenant listings would return all of them.
     */
    private void stubTenantWithTwoApps() {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.findAllIds("ui_page", TENANT)).thenReturn(List.of("up1"));
        when(repository.findAllIds("ui_menu", TENANT)).thenReturn(List.of("um1"));
        when(repository.findAllIds("flow", TENANT)).thenReturn(List.of("f-insp", "f-leads"));
        when(repository.findAllIds("page_layout", TENANT)).thenReturn(List.of("pl-sites", "pl-leads"));
        when(repository.findAllIds("validation_rule", TENANT)).thenReturn(List.of("vr-insp", "vr-leads"));
        when(repository.findAllIds("global_picklist", TENANT)).thenReturn(List.of("gp-status", "gp-lead"));
        when(jdbcTemplate.queryForList(contains("FROM collection"), eq(String.class), eq(TENANT)))
                .thenReturn(List.of("c-sites", "c-insp", "c-leads"));

        List<String> appIds = List.of("c-sites", "c-insp");
        when(repository.findCollectionsByNames(TENANT, List.of("sites", "inspections"))).thenReturn(List.of(
                row("id", "c-sites", "name", "sites", "system_collection", false),
                row("id", "c-insp", "name", "inspections", "system_collection", false)));
        when(repository.findValidationRuleIdsByCollectionIds(TENANT, appIds)).thenReturn(List.of("vr-insp"));
        when(repository.findPageLayoutIdsByCollectionIds(TENANT, appIds)).thenReturn(List.of("pl-sites"));
        when(repository.findFlowIdsByTriggerCollections(TENANT, List.of("sites", "inspections")))
                .thenReturn(List.of("f-insp"));
        when(repository.findGlobalPicklistIdsReferencedByCollections(TENANT, appIds))
                .thenReturn(List.of("gp-status"));
        when(repository.findListViewIdsByCollectionIds(TENANT, appIds)).thenReturn(List.of("lv-sites"));
        when(repository.findDashboardIdsByComponentCollections(TENANT, List.of("sites", "inspections")))
                .thenReturn(List.of("d-insp"));
        when(repository.findListViewsByIds(TENANT, List.of("lv-sites"))).thenReturn(List.of(
                row("id", "lv-sites", "name", "All sites", "collection_name", "sites",
                        "columns", List.of("grade"))));
        when(repository.findDashboardsByIds(TENANT, List.of("d-insp"))).thenReturn(List.of(
                row("id", "d-insp", "name", "Inspection health", "column_count", 3)));
        when(repository.findDashboardComponentsByDashboardIds(TENANT, List.of("d-insp"))).thenReturn(List.of(
                row("id", "dc1", "dashboard_id", "d-insp", "component_type", "metric", "title", "Open",
                        "column_position", 1, "row_position", 1, "config",
                        Map.of("collectionName", "inspections"))));

        when(repository.findCollectionsByIds(eq(TENANT), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(1);
            return ids.stream().map(id -> row("id", id, "name", switch (id) {
                case "c-sites" -> "sites";
                case "c-insp" -> "inspections";
                default -> "leads";
            })).toList();
        });
        when(repository.findFieldsWithNamesByCollectionIds(TENANT, appIds)).thenReturn(List.of(
                row("id", "fld-site", "name", "site", "type", "LOOKUP", "collection_name", "inspections",
                        "reference_collection_name", "sites"),
                row("id", "fld-status", "name", "status", "type", "PICKLIST",
                        "collection_name", "inspections", "global_picklist_name", "inspection-status"),
                row("id", "fld-grade", "name", "grade", "type", "PICKLIST", "collection_name", "sites")));
        when(repository.findFieldPicklistValues(TENANT, appIds)).thenReturn(List.of(
                row("id", "pv-a", "value", "A", "picklist_source_type", "FIELD",
                        "field_name", "grade", "collection_name", "sites")));
        when(repository.findGlobalPicklistsByIds(TENANT, List.of("gp-status"))).thenReturn(List.of(
                row("id", "gp-status", "name", "inspection-status")));
        when(repository.findGlobalPicklistValues(TENANT, List.of("gp-status"))).thenReturn(List.of(
                row("id", "pv-open", "value", "open", "picklist_source_type", "GLOBAL",
                        "picklist_name", "inspection-status")));
        when(repository.findValidationRulesByIds(TENANT, List.of("vr-insp"))).thenReturn(List.of(
                row("id", "vr-insp", "name", "require_site", "collection_name", "inspections")));
        when(repository.findPageLayoutsByIds(TENANT, List.of("pl-sites"))).thenReturn(List.of(
                row("id", "pl-sites", "name", "Site Layout", "collection_name", "sites")));
        when(repository.findLayoutSectionsByLayoutIds(TENANT, List.of("pl-sites"))).thenReturn(List.of(
                row("id", "ls1", "name", "Details", "layout_name", "Site Layout", "collection_name", "sites")));
        when(repository.findLayoutFieldsByLayoutIds(TENANT, List.of("pl-sites"))).thenReturn(List.of(
                row("id", "lf1", "name", "grade", "layout_name", "Site Layout", "field_name", "grade")));
        when(repository.findLayoutRelatedListsByLayoutIds(TENANT, List.of("pl-sites"))).thenReturn(List.of(
                row("id", "rl1", "name", "inspections", "layout_name", "Site Layout",
                        "related_collection_name", "inspections")));
        when(repository.findFlowsByIds(TENANT, List.of("f-insp"))).thenReturn(List.of(
                row("id", "f-insp", "name", "notify_on_inspection", "flow_type", "RECORD_TRIGGERED")));
        // What a whole-tenant export would have pulled in on top.
        when(repository.findValidationRulesByIds(TENANT, List.of("vr-insp", "vr-leads"))).thenReturn(List.of(
                row("id", "vr-insp", "name", "require_site"), row("id", "vr-leads", "name", "require_email")));
        when(repository.findPageLayoutsByIds(TENANT, List.of("pl-sites", "pl-leads"))).thenReturn(List.of(
                row("id", "pl-sites", "name", "Site Layout"), row("id", "pl-leads", "name", "Lead Layout")));
        when(repository.findFlowsByIds(TENANT, List.of("f-insp", "f-leads"))).thenReturn(List.of(
                row("id", "f-insp", "name", "notify_on_inspection"), row("id", "f-leads", "name", "assign_lead")));
        when(repository.findUiPagesByIds(TENANT, List.of("up1"))).thenReturn(List.of(
                row("id", "up1", "name", "home")));
        when(repository.findUiMenusByIds(TENANT, List.of("um1"))).thenReturn(List.of(
                row("id", "um1", "name", "main")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> typedNames(Map<String, Object> pkg) {
        return ((List<Map<String, Object>>) pkg.get("items")).stream()
                .map(i -> {
                    var data = (Map<String, Object>) i.get("data");
                    return i.get("type") + ":" + data.getOrDefault("name", data.get("value"));
                })
                .toList();
    }

    @Test
    @DisplayName("collectionNames exports those collections and what hangs off them, nothing else (BUILD-LOG error 6)")
    void collectionNamesScopesToCollectionsAndDependents() {
        stubTenantWithTwoApps();

        var pkg = service.exportPackage(TENANT,
                Map.of("collectionNames", List.of("sites", "inspections")), false);

        assertThat(typedNames(pkg)).containsExactlyInAnyOrder(
                "COLLECTION:sites", "COLLECTION:inspections",
                "FIELD:site", "FIELD:status", "FIELD:grade",
                "PICKLIST_VALUE:A", "GLOBAL_PICKLIST:inspection-status", "PICKLIST_VALUE:open",
                "VALIDATION_RULE:require_site",
                "PAGE_LAYOUT:Site Layout", "LAYOUT_SECTION:Details", "LAYOUT_FIELD:grade",
                "LAYOUT_RELATED_LIST:inspections",
                "FLOW:notify_on_inspection",
                "LIST_VIEW:All sites", "DASHBOARD:Inspection health");
        assertThat(typedNames(pkg)).doesNotContain(
                "COLLECTION:leads", "PAGE_LAYOUT:Lead Layout", "VALIDATION_RULE:require_email",
                "FLOW:assign_lead", "UI_PAGE:home", "UI_MENU:main");
        verify(repository, never()).findAllIds(anyString(), anyString());
        verify(repository).findUiPagesByIds(TENANT, List.of());
        verify(repository).findUiMenusByIds(TENANT, List.of());
    }

    @Test
    @DisplayName("an explicit dependent id list wins over the collectionNames fill")
    void explicitFlowIdsWinOverCollectionNames() {
        stubTenantWithTwoApps();

        var pkg = service.exportPackage(TENANT,
                Map.of("collectionNames", List.of("sites", "inspections"), "flowIds", List.of()), false);

        assertThat(typedNames(pkg)).noneMatch(n -> n.startsWith("FLOW:"));
        assertThat(typedNames(pkg)).contains("COLLECTION:sites", "VALIDATION_RULE:require_site");
        verify(repository, never()).findFlowIdsByTriggerCollections(anyString(), anyList());
    }

    @Test
    @DisplayName("collectionNames unions with explicit collectionIds")
    void collectionNamesUnionWithCollectionIds() {
        stubTenantWithTwoApps();

        var options = service.resolveExportOptions(TENANT,
                Map.of("collectionNames", List.of("sites", "inspections"),
                        "collectionIds", List.of("c-leads", "c-sites")));

        assertThat(options.get("collectionIds")).isEqualTo(List.of("c-leads", "c-sites", "c-insp"));
    }

    @Test
    @DisplayName("an unknown or system collection name is a 400 naming it")
    void unknownCollectionNameIsBadRequest() {
        stubTenantWithTwoApps();
        when(repository.findCollectionsByNames(TENANT, List.of("sites", "nope", "users"))).thenReturn(List.of(
                row("id", "c-sites", "name", "sites", "system_collection", false),
                row("id", "c-users", "name", "users", "system_collection", true)));

        assertThatThrownBy(() -> service.exportPackage(TENANT,
                Map.of("collectionNames", List.of("sites", "nope", "users"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("nope").contains("users").doesNotContain("sites");
                });
        verify(repository, never()).save(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an empty options map still exports the whole tenant, other apps included")
    void emptyOptionsStillExportsWholeTenant() {
        stubTenantWithTwoApps();

        var pkg = service.exportPackage(TENANT, Map.of(), false);

        assertThat(typedNames(pkg)).contains(
                "COLLECTION:sites", "COLLECTION:inspections", "COLLECTION:leads",
                "PAGE_LAYOUT:Lead Layout", "VALIDATION_RULE:require_email", "FLOW:assign_lead",
                "UI_PAGE:home", "UI_MENU:main");
        verify(repository, never()).findCollectionsByNames(anyString(), anyList());
    }

    @Test
    @DisplayName("falls back to a generic package name when the tenant has no slug")
    void defaultsNameWithoutASlug() {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.empty());

        var pkg = service.exportPackage(TENANT, Map.of(), false);

        assertThat(pkg).containsEntry("name", "metadata").containsEntry("version", "1.0.0");
    }

    @Test
    @DisplayName("an explicitly empty id list is respected, not widened to the tenant")
    void explicitEmptyIdListExportsNothing() {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));

        var pkg = service.exportPackage(TENANT,
                Map.of("name", "pkg", "version", "1.0.0", "collectionIds", List.of()), false);

        assertThat((List<?>) pkg.get("items")).isEmpty();
        verify(repository, never()).findAllIds(anyString(), anyString());
    }

    @Test
    @DisplayName("recordHistory=false skips the package_history write")
    void recordHistoryFalseSkipsHistory() {
        when(repository.findInstanceId()).thenReturn(Optional.empty());
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.empty());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("name", "pkg");
        options.put("version", "1.0.0");

        service.exportPackage(TENANT, options, false);

        verify(repository, never()).save(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("exportAllOptions collects every packageable id list in the tenant")
    void exportAllOptionsCollectsEverything() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.getJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForList(contains("FROM collection"), eq(String.class), eq(TENANT)))
                .thenReturn(List.of("c1", "c2"));
        when(repository.findAllIds("ui_page", TENANT)).thenReturn(List.of("up1"));
        when(repository.findAllIds("ui_menu", TENANT)).thenReturn(List.of("um1"));
        when(repository.findAllIds("flow", TENANT)).thenReturn(List.of("f1"));
        when(repository.findAllIds("page_layout", TENANT)).thenReturn(List.of("pl1"));
        when(repository.findAllIds("validation_rule", TENANT)).thenReturn(List.of("vr1"));
        when(repository.findAllIds("global_picklist", TENANT)).thenReturn(List.of("gp1"));
        when(repository.findAllIds("list_view", TENANT)).thenReturn(List.of("lv1"));
        when(repository.findAllIds("dashboard", TENANT)).thenReturn(List.of("d1"));

        var options = service.exportAllOptions(TENANT, "sandbox-clone", "1.0.0");

        // role/policy are excluded — the legacy tables were dropped in V47
        assertThat(options)
                .containsEntry("name", "sandbox-clone")
                .containsEntry("version", "1.0.0")
                .containsEntry("collectionIds", List.of("c1", "c2"))
                .containsEntry("uiPageIds", List.of("up1"))
                .containsEntry("uiMenuIds", List.of("um1"))
                .containsEntry("flowIds", List.of("f1"))
                .containsEntry("pageLayoutIds", List.of("pl1"))
                .containsEntry("validationRuleIds", List.of("vr1"))
                .containsEntry("globalPicklistIds", List.of("gp1"))
                .containsEntry("listViewIds", List.of("lv1"))
                .containsEntry("dashboardIds", List.of("d1"))
                .doesNotContainKey("roleIds")
                .doesNotContainKey("policyIds");
        verify(repository, never()).findAllIds("role", TENANT);
        verify(repository, never()).findAllIds("policy", TENANT);
    }

    @Test
    @DisplayName("exports list views with their collection name and dashboards as tree documents, never seed records")
    @SuppressWarnings("unchecked")
    void exportsListViewsAndDashboardTrees() throws Exception {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.findListViewsByIds(TENANT, List.of("lv1"))).thenReturn(List.of(
                row("id", "lv1", "collection_id", "c1", "collection_name", "orders", "name", "Open",
                        "columns", jsonb("[\"status\"]"), "created_by", "u-9", "tenant_id", TENANT)));
        when(repository.findDashboardsByIds(TENANT, List.of("d1"))).thenReturn(List.of(
                row("id", "d1", "name", "Ops", "access_level", "PUBLIC", "is_dynamic", true,
                        "running_user_id", "u-9", "column_count", 4, "tenant_id", TENANT)));
        when(repository.findDashboardComponentsByDashboardIds(TENANT, List.of("d1"))).thenReturn(List.of(
                row("id", "dc1", "dashboard_id", "d1", "report_id", "r-1", "component_type", "metric",
                        "title", "Open orders", "column_position", 1, "row_position", 1, "column_span", 2,
                        "row_span", 1, "sort_order", 0,
                        "config", jsonb("{\"collectionName\":\"orders\",\"aggregate\":\"COUNT\"}")),
                row("id", "dc2", "dashboard_id", "d1", "component_type", "table", "title", null,
                        "column_position", 3, "row_position", 1, "sort_order", 1, "config", null)));

        var pkg = service.exportPackage(TENANT,
                Map.of("listViewIds", List.of("lv1"), "dashboardIds", List.of("d1")), false);

        assertThat(pkg).doesNotContainKey("records");
        var items = (List<Map<String, Object>>) pkg.get("items");
        assertThat(items).extracting(i -> i.get("type")).containsExactly("LIST_VIEW", "DASHBOARD");
        assertThat((Map<String, Object>) items.get(0).get("data"))
                .containsEntry("collection_name", "orders")
                .containsEntry("columns", List.of("status"))
                .doesNotContainKeys("created_by", "tenant_id");
        var dashboard = (Map<String, Object>) items.get(1).get("data");
        assertThat(dashboard)
                .containsEntry("name", "Ops")
                .containsEntry("accessLevel", "PUBLIC")
                .containsEntry("dynamic", true)
                .containsEntry("columnCount", 4)
                .doesNotContainKeys("runningUserId", "id", "tenant_id");
        assertThat((List<Map<String, Object>>) dashboard.get("components")).containsExactly(
                Map.of("title", "Open orders", "componentType", "metric", "columnPosition", 1,
                        "rowPosition", 1, "columnSpan", 2, "rowSpan", 1,
                        "config", Map.of("collectionName", "orders", "aggregate", "COUNT")),
                Map.of("title", "table 2", "componentType", "table", "columnPosition", 3,
                        "rowPosition", 1, "config", Map.of()));
    }

    @Test
    @DisplayName("excludeTypes drops list views and dashboards from a whole-tenant export")
    void excludeTypesDropsThem() {
        when(repository.findTenantSlug(TENANT)).thenReturn(Optional.of("acme"));
        when(repository.findAllIds("list_view", TENANT)).thenReturn(List.of("lv1"));
        when(repository.findAllIds("dashboard", TENANT)).thenReturn(List.of("d1"));
        when(repository.findAllIds("flow", TENANT)).thenReturn(List.of("f1"));

        var options = service.resolveExportOptions(TENANT,
                Map.of("excludeTypes", List.of("list_view", "DASHBOARD")));

        assertThat(options)
                .containsEntry("listViewIds", List.of())
                .containsEntry("dashboardIds", List.of())
                .containsEntry("flowIds", List.of("f1"))
                .doesNotContainKey("excludeTypes");
    }

    @Test
    @DisplayName("an excludeTypes entry that is not an excludable type is a 400 naming it")
    void unknownExcludeTypeIsBadRequest() {
        assertThatThrownBy(() -> service.resolveExportOptions(TENANT,
                Map.of("excludeTypes", List.of("COLLECTION"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("COLLECTION").contains("LIST_VIEW");
                });
    }
}
