package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the QueryEngine-path package importer: natural-key remapping,
 * SKIP/OVERWRITE conflict handling, dry-run, filters, per-item isolation, and
 * flow createdBy rebinding.
 */
@DisplayName("PackageImportService")
class PackageImportServiceTest {

    private static final String TENANT = "t1";

    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private PackageRepository repository;
    private JdbcTemplate jdbcTemplate;
    private PackageImportService service;

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        repository = mock(PackageRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        when(repository.getJdbcTemplate()).thenReturn(jdbcTemplate);
        // Real system collection definitions — the importer maps snake_case rows
        // through each definition's effectiveColumnName().
        when(collectionRegistry.get(anyString())).thenAnswer(inv ->
                SystemCollectionDefinitions.byName().get(inv.<String>getArgument(0)));
        // Target-tenant seed queries all default to empty (fresh tenant).
        when(jdbcTemplate.queryForList(anyString(), any(Object.class))).thenReturn(List.of());

        service = new PackageImportService(queryEngine, collectionRegistry, repository,
                new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private void seedCollections(Map<String, String> nameToId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        nameToId.forEach((name, id) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", name);
            rows.add(row);
        });
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE tenant_id"), eq(TENANT)))
                .thenReturn(rows);
    }

    private static Map<String, Object> item(String type, Map<String, Object> data) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("data", data);
        return item;
    }

    private static Map<String, Object> pkg(Map<String, Object>... items) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("items", List.of(items));
        return pkg;
    }

    private static Map<String, Object> collectionData(String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "src-" + name);
        data.put("name", name);
        data.put("display_name", name + " display");
        data.put("active", true);
        return data;
    }

    private static Map<String, Object> lookupFieldData(String referenceName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "src-field-1");
        data.put("collection_id", "src-orders");
        data.put("collection_name", "orders");
        data.put("name", "customer");
        data.put("type", "LOOKUP");
        data.put("reference_collection_id", "src-customers");
        data.put("reference_collection_name", referenceName);
        return data;
    }

    private static Map<String, Object> menuItemData(String id, String label, String parentId,
                                                    String parentLabel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("menu_id", "src-menu");
        data.put("menu_name", "main");
        data.put("label", label);
        data.put("parent_id", parentId);
        data.put("parent_label", parentLabel);
        data.put("display_order", 0);
        data.put("active", true);
        return data;
    }

    private static Map<String, Object> relatedListData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "src-rl-1");
        data.put("layout_id", "src-layout");
        data.put("layout_name", "Default");
        data.put("collection_name", "orders");
        data.put("related_collection_id", "src-lines");
        data.put("related_collection_name", "order_lines");
        data.put("relationship_field_id", "src-line-order");
        data.put("relationship_field_name", "order");
        data.put("relationship_field_collection_name", "order_lines");
        data.put("display_columns", List.of("name"));
        data.put("sort_order", 0);
        return data;
    }

    private static Map<String, Object> flowData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "src-flow-1");
        data.put("name", "myflow");
        data.put("flow_type", "AUTOLAUNCHED");
        data.put("active", false);
        data.put("version", 1);
        data.put("definition", Map.of("startAt", "s1"));
        return data;
    }

    // ------------------------------------------------------------------
    // Natural-key reference remapping
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("field reference remapping")
    class FieldReferenceRemapping {

        @Test
        @DisplayName("remaps referenceCollectionId to the target collection with the same name")
        void remapsReferenceByName() {
            seedCollections(Map.of("orders", "tgt-orders", "customers", "tgt-customers"));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "new-field"));

            var report = service.importPackage(TENANT, pkg(item("FIELD", lookupFieldData("customers"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).isEqualTo(1);
            assertThat(report.failed()).isZero();

            ArgumentCaptor<CollectionDefinition> defCaptor = ArgumentCaptor.forClass(CollectionDefinition.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(defCaptor.capture(), dataCaptor.capture());

            assertThat(defCaptor.getValue().name()).isEqualTo("fields");
            Map<String, Object> mapped = dataCaptor.getValue();
            assertThat(mapped.get("collectionId")).isEqualTo("tgt-orders");
            assertThat(mapped.get("referenceCollectionId"))
                    .as("source UUID replaced by the target tenant's collection id")
                    .isEqualTo("tgt-customers");
            assertThat(mapped.get("name")).isEqualTo("customer");
            assertThat(mapped).as("engine owns ids/audit fields").doesNotContainKey("id");
        }

        @Test
        @DisplayName("fails the item without writing when the referenced collection is unresolvable")
        void unresolvableReferenceFails() {
            seedCollections(Map.of("orders", "tgt-orders"));

            var report = service.importPackage(TENANT, pkg(item("FIELD", lookupFieldData("ghost"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items()).hasSize(1);
            assertThat(report.items().get(0).action()).isEqualTo("FAILED");
            assertThat(report.items().get(0).error()).contains("Collection not found in target: ghost");
            verify(queryEngine, never()).create(any(), anyMap());
            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("fails a v1-style item that carries a reference id but no reference name")
        void v1PackageWithoutReferenceNameFails() {
            seedCollections(Map.of("orders", "tgt-orders"));
            Map<String, Object> data = lookupFieldData("customers");
            data.remove("reference_collection_name");

            var report = service.importPackage(TENANT, pkg(item("FIELD", data)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items().get(0).error()).contains("cannot remap safely");
            verify(queryEngine, never()).create(any(), anyMap());
        }
    }

    // ------------------------------------------------------------------
    // Conflict modes
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("conflict handling on natural-key match")
    class ConflictHandling {

        @Test
        @DisplayName("SKIP leaves an existing collection untouched")
        void skipLeavesExisting() {
            seedCollections(Map.of("orders", "existing-id"));

            var report = service.importPackage(TENANT, pkg(item("COLLECTION", collectionData("orders"))),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.SKIP, false, null, null, null));

            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.created()).isZero();
            verify(queryEngine, never()).create(any(), anyMap());
            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("OVERWRITE updates the existing record by its target id")
        void overwriteUpdatesExisting() {
            seedCollections(Map.of("orders", "existing-id"));
            when(queryEngine.update(any(), eq("existing-id"), anyMap()))
                    .thenReturn(Optional.of(Map.of("id", "existing-id")));

            var report = service.importPackage(TENANT, pkg(item("COLLECTION", collectionData("orders"))),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.OVERWRITE, false, null, null, null));

            assertThat(report.updated()).isEqualTo(1);
            assertThat(report.failed()).isZero();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(any(), eq("existing-id"), dataCaptor.capture());
            assertThat(dataCaptor.getValue().get("name")).isEqualTo("orders");
            verify(queryEngine, never()).create(any(), anyMap());
        }
    }

    // ------------------------------------------------------------------
    // Collection displayFieldId (KLT-284)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("collection displayFieldId remapping")
    class DisplayFieldRemapping {

        @Test
        @DisplayName("creates the collection with displayFieldId unset, then patches it once the field exists")
        void selfReferencingDisplayFieldImportsFromScratch() {
            when(queryEngine.create(any(),
                    ArgumentMatchers.<Map<String, Object>>argThat(
                            m -> m != null && "orders".equals(m.get("name")) && m.containsKey("systemCollection"))))
                    .thenReturn(Map.of("id", "tgt-orders"));
            when(queryEngine.create(any(),
                    ArgumentMatchers.<Map<String, Object>>argThat(
                            m -> m != null && "name".equals(m.get("name")) && m.containsKey("collectionId"))))
                    .thenReturn(Map.of("id", "tgt-field"));

            Map<String, Object> collection = collectionData("orders");
            collection.put("display_field_id", "src-field-1");
            collection.put("display_field_name", "name");

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("id", "src-field-1");
            field.put("collection_id", "src-orders");
            field.put("collection_name", "orders");
            field.put("name", "name");
            field.put("type", "STRING");

            var report = service.importPackage(TENANT,
                    pkg(item("COLLECTION", collection), item("FIELD", field)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isZero();
            assertThat(report.created()).isEqualTo(2);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> createCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine, times(2)).create(any(), createCaptor.capture());
            Map<String, Object> collectionCreate = createCaptor.getAllValues().get(0);
            assertThat(collectionCreate.get("name")).isEqualTo("orders");
            assertThat(collectionCreate)
                    .as("the source tenant's raw field id must not ride straight through")
                    .doesNotContainKey("displayFieldId");

            ArgumentCaptor<CollectionDefinition> updateDefCaptor = ArgumentCaptor.forClass(CollectionDefinition.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> updateDataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(updateDefCaptor.capture(), eq("tgt-orders"), updateDataCaptor.capture());
            assertThat(updateDefCaptor.getValue().name()).isEqualTo("collections");
            assertThat(updateDataCaptor.getValue()).containsEntry("displayFieldId", "tgt-field");
        }

        @Test
        @DisplayName("leaves displayFieldId unset when the named field is missing in the target")
        void leavesUnsetWhenFieldMissing() {
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-orders"));

            Map<String, Object> collection = collectionData("orders");
            collection.put("display_field_id", "src-field-1");
            collection.put("display_field_name", "ghost");

            var report = service.importPackage(TENANT, pkg(item("COLLECTION", collection)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isZero();
            assertThat(report.created()).isEqualTo(1);
            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("skips the patch for a dry run — nothing was actually written")
        void dryRunSkipsPatch() {
            Map<String, Object> collection = collectionData("orders");
            collection.put("display_field_id", "src-field-1");
            collection.put("display_field_name", "name");

            var report = service.importPackage(TENANT, pkg(item("COLLECTION", collection)),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.SKIP, true, null, null, null));

            assertThat(report.created()).isEqualTo(1);
            verifyNoInteractions(queryEngine);
        }
    }

    // ------------------------------------------------------------------
    // Per-item isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one failing item does not stop the rest of the import")
    void perItemIsolation() {
        when(queryEngine.create(any(),
                ArgumentMatchers.<Map<String, Object>>argThat(m -> m != null && "alpha".equals(m.get("name")))))
                .thenThrow(new RuntimeException("boom"));
        when(queryEngine.create(any(),
                ArgumentMatchers.<Map<String, Object>>argThat(m -> m != null && "beta".equals(m.get("name")))))
                .thenReturn(Map.of("id", "tgt-beta"));

        var report = service.importPackage(TENANT,
                pkg(item("COLLECTION", collectionData("alpha")), item("COLLECTION", collectionData("beta"))),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.success()).isFalse();

        var failed = report.items().stream().filter(i -> "FAILED".equals(i.action())).findFirst().orElseThrow();
        assertThat(failed.naturalKey()).isEqualTo("alpha");
        assertThat(failed.error()).contains("boom");
        var created = report.items().stream().filter(i -> "CREATED".equals(i.action())).findFirst().orElseThrow();
        assertThat(created.naturalKey()).isEqualTo("beta");
    }

    // ------------------------------------------------------------------
    // Flow createdBy remapping
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("flow createdBy remapping")
    class FlowCreatedBy {

        @Test
        @DisplayName("binds imported flows to the executing user when supplied")
        void usesExecutingUserId() {
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-flow"));

            var report = service.importPackage(TENANT, pkg(item("FLOW", flowData())),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.SKIP, false, null, null, "exec-user"));

            assertThat(report.created()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), dataCaptor.capture());
            assertThat(dataCaptor.getValue().get("createdBy")).isEqualTo("exec-user");
        }

        @Test
        @DisplayName("falls back to the target tenant's first platform user")
        void fallsBackToFirstUser() {
            when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", "first-user")));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-flow"));

            var report = service.importPackage(TENANT, pkg(item("FLOW", flowData())),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), dataCaptor.capture());
            assertThat(dataCaptor.getValue().get("createdBy")).isEqualTo("first-user");
        }

        @Test
        @DisplayName("fails the flow when the target tenant has no users at all")
        void failsWithoutAnyUser() {
            var report = service.importPackage(TENANT, pkg(item("FLOW", flowData())),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items().get(0).error()).contains("no users");
            verify(queryEngine, never()).create(any(), anyMap());
        }
    }

    // ------------------------------------------------------------------
    // Dry run + filters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dryRun classifies items but never writes through the engine")
    void dryRunMakesNoWrites() {
        seedCollections(Map.of("orders", "existing-id"));

        var report = service.importPackage(TENANT,
                pkg(item("COLLECTION", collectionData("orders")), item("COLLECTION", collectionData("newcol"))),
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.OVERWRITE, true, null, null, null));

        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.created()).isEqualTo(1);
        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("typeFilter restricts the import to the listed types")
    void typeFilterFilters() {
        when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-1"));

        var report = service.importPackage(TENANT,
                pkg(item("COLLECTION", collectionData("orders")), item("FLOW", flowData())),
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.SKIP, false, Set.of("COLLECTION"), null, null));

        assertThat(report.items()).hasSize(1);
        assertThat(report.items().get(0).type()).isEqualTo("COLLECTION");
        verify(queryEngine, times(1)).create(any(), anyMap());
    }

    @Test
    @DisplayName("itemKeyFilter restricts the import to the listed natural keys")
    void itemKeyFilterFilters() {
        when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-1"));

        var report = service.importPackage(TENANT,
                pkg(item("COLLECTION", collectionData("alpha")), item("COLLECTION", collectionData("beta"))),
                new PackageImportService.ImportOptions(
                        PackageImportService.ConflictMode.SKIP, false, null, Set.of("COLLECTION:alpha"), null));

        assertThat(report.items()).hasSize(1);
        assertThat(report.items().get(0).naturalKey()).isEqualTo("alpha");
        verify(queryEngine, times(1)).create(any(), anyMap());
    }

    // ------------------------------------------------------------------
    // naturalKeyFor
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Nested menu items
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("nested ui menu items")
    class NestedMenuItems {

        @BeforeEach
        void seedMenu() {
            when(jdbcTemplate.queryForList(contains("FROM ui_menu WHERE tenant_id"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", "tgt-menu", "name", "main")));
        }

        @Test
        @DisplayName("points a child at the imported group's id, not the source parent id")
        void remapsParentIdThroughTheImport() {
            when(queryEngine.create(any(),
                    ArgumentMatchers.<Map<String, Object>>argThat(m -> m != null && "Sales".equals(m.get("label")))))
                    .thenReturn(Map.of("id", "tgt-group"));
            when(queryEngine.create(any(),
                    ArgumentMatchers.<Map<String, Object>>argThat(m -> m != null && "Orders".equals(m.get("label")))))
                    .thenReturn(Map.of("id", "tgt-child"));

            // Child first in the package: export order is display_order, not depth.
            var report = service.importPackage(TENANT, pkg(
                            item("UI_MENU_ITEM", menuItemData("src-child", "Orders", "src-group", "Sales")),
                            item("UI_MENU_ITEM", menuItemData("src-group", "Sales", null, null))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).isEqualTo(2);
            assertThat(report.failed()).isZero();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine, times(2)).create(any(), dataCaptor.capture());
            var written = dataCaptor.getAllValues();
            assertThat(written.get(0).get("label")).as("parent imported first").isEqualTo("Sales");
            assertThat(written.get(0)).doesNotContainKey("parentId");
            assertThat(written.get(1).get("label")).isEqualTo("Orders");
            assertThat(written.get(1).get("parentId"))
                    .as("child points at the id the group got here")
                    .isEqualTo("tgt-group");
        }

        @Test
        @DisplayName("reparents onto an existing group that the import skipped")
        void resolvesParentThatAlreadyExists() {
            when(jdbcTemplate.queryForList(contains("FROM ui_menu_item mi"), eq(TENANT)))
                    .thenReturn(List.of(mapOf("id", "existing-group", "label", "Sales",
                            "parent_label", null, "menu_name", "main")));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-child"));

            var report = service.importPackage(TENANT, pkg(
                            item("UI_MENU_ITEM", menuItemData("src-group", "Sales", null, null)),
                            item("UI_MENU_ITEM", menuItemData("src-child", "Orders", "src-group", "Sales"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.created()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), dataCaptor.capture());
            assertThat(dataCaptor.getValue().get("parentId")).isEqualTo("existing-group");
        }

        @Test
        @DisplayName("fails a child whose parent is absent rather than writing a source id")
        void failsWhenParentIsMissing() {
            var report = service.importPackage(TENANT, pkg(
                            item("UI_MENU_ITEM", menuItemData("src-child", "Orders", "src-ghost", "Ghost"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items().get(0).error()).contains("Parent menu item not found");
            verify(queryEngine, never()).create(any(), anyMap());
        }
    }

    // ------------------------------------------------------------------
    // Layout related lists
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("layout related lists")
    class LayoutRelatedLists {

        @BeforeEach
        void seedLayoutAndField() {
            seedCollections(Map.of("orders", "tgt-orders", "order_lines", "tgt-lines"));
            when(jdbcTemplate.queryForList(contains("FROM page_layout pl"), eq(TENANT)))
                    .thenReturn(List.of(mapOf("id", "tgt-layout", "name", "Default", "coll", "orders")));
            when(jdbcTemplate.queryForList(contains("FROM field f"), eq(TENANT)))
                    .thenReturn(List.of(mapOf("id", "tgt-line-order", "name", "order", "coll", "order_lines")));
        }

        @Test
        @DisplayName("remaps layout, related collection and relationship field by natural key")
        void remapsEveryReference() {
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-rl"));

            var report = service.importPackage(TENANT,
                    pkg(item("LAYOUT_RELATED_LIST", relatedListData())),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).isEqualTo(1);
            assertThat(report.items().get(0).naturalKey())
                    .isEqualTo("orders:Default:order_lines:order");

            ArgumentCaptor<CollectionDefinition> defCaptor = ArgumentCaptor.forClass(CollectionDefinition.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(defCaptor.capture(), dataCaptor.capture());
            assertThat(defCaptor.getValue().name()).isEqualTo("layout-related-lists");
            assertThat(dataCaptor.getValue())
                    .containsEntry("layoutId", "tgt-layout")
                    .containsEntry("relatedCollectionId", "tgt-lines")
                    .containsEntry("relationshipFieldId", "tgt-line-order")
                    .containsEntry("sortOrder", 0)
                    .containsEntry("displayColumns", List.of("name"));
        }

        @Test
        @DisplayName("fails the item when the relationship field is missing in the target")
        void failsOnMissingRelationshipField() {
            when(jdbcTemplate.queryForList(contains("FROM field f"), eq(TENANT))).thenReturn(List.of());

            var report = service.importPackage(TENANT,
                    pkg(item("LAYOUT_RELATED_LIST", relatedListData())),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items().get(0).error())
                    .contains("Relationship field not found in target: order_lines.order");
            verify(queryEngine, never()).create(any(), anyMap());
        }
    }

    // ------------------------------------------------------------------
    // Picklist values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("carries a picklist value's colour through to the target")
    void picklistValueColourRoundTrips() {
        when(jdbcTemplate.queryForList(contains("FROM global_picklist"), eq(TENANT)))
                .thenReturn(List.of(Map.of("id", "tgt-picklist", "name", "statuses")));
        when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-value"));

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "src-pv");
        value.put("picklist_source_type", "GLOBAL");
        value.put("picklist_source_id", "src-picklist");
        value.put("picklist_name", "statuses");
        value.put("value", "open");
        value.put("label", "Open");
        value.put("color", "#22c55e");

        var report = service.importPackage(TENANT, pkg(item("PICKLIST_VALUE", value)),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.created()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).create(any(), dataCaptor.capture());
        assertThat(dataCaptor.getValue())
                .containsEntry("color", "#22c55e")
                .containsEntry("picklistSourceId", "tgt-picklist");
    }

    @Test
    @DisplayName("naturalKeyFor composes cross-tenant identities per type")
    void naturalKeyComposition() {
        assertThat(PackageImportService.naturalKeyFor("COLLECTION", Map.of("name", "orders")))
                .isEqualTo("orders");
        assertThat(PackageImportService.naturalKeyFor("FIELD",
                Map.of("collection_name", "orders", "name", "customer")))
                .isEqualTo("orders.customer");
        assertThat(PackageImportService.naturalKeyFor("PAGE_LAYOUT",
                Map.of("collection_name", "orders", "name", "Default")))
                .isEqualTo("orders:Default");
        assertThat(PackageImportService.naturalKeyFor("LAYOUT_FIELD",
                Map.of("collection_name", "orders", "layout_name", "Default",
                        "section_sort_order", 0, "field_name", "customer")))
                .isEqualTo("orders:Default:0:customer");
        assertThat(PackageImportService.naturalKeyFor("PICKLIST_VALUE",
                Map.of("picklist_source_type", "GLOBAL", "picklist_name", "colors", "value", "red")))
                .isEqualTo("colors:red");
        assertThat(PackageImportService.naturalKeyFor("LAYOUT_RELATED_LIST",
                Map.of("collection_name", "orders", "layout_name", "Default",
                        "related_collection_name", "order_lines", "relationship_field_name", "order")))
                .isEqualTo("orders:Default:order_lines:order");
        assertThat(PackageImportService.naturalKeyFor("UI_MENU_ITEM",
                mapOf("menu_name", "main", "parent_label", null, "label", "Orders")))
                .as("top-level items carry an empty parent segment")
                .isEqualTo("main::Orders");
        assertThat(PackageImportService.naturalKeyFor("UI_MENU_ITEM",
                Map.of("menu_name", "main", "parent_label", "Sales", "label", "Orders")))
                .isEqualTo("main:Sales:Orders");
    }

    /** {@link Map#of} rejects nulls; several fixtures need a null parent label. */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
