package io.kelta.worker.service;

import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
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
                .doesNotContainKey("roleIds")
                .doesNotContainKey("policyIds");
        verify(repository, never()).findAllIds("role", TENANT);
        verify(repository, never()).findAllIds("policy", TENANT);
    }
}
