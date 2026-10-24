package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.runtime.storage.CompositeUniqueConstraintService.ConstraintInfo;
import io.kelta.worker.repository.PackageRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
    private DashboardTreeService dashboardTreeService;
    private PlatformTransactionManager transactionManager;
    private CompositeUniqueConstraintService uniqueConstraintService;
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

        dashboardTreeService = mock(DashboardTreeService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        uniqueConstraintService = mock(CompositeUniqueConstraintService.class);
        service = new PackageImportService(queryEngine, collectionRegistry, repository,
                new ObjectMapper(), new JdbcUserIdResolver(jdbcTemplate), dashboardTreeService,
                new DashboardComponentValidator(collectionRegistry, mock(CollectionLifecycleManager.class),
                        queryEngine),
                transactionManager, uniqueConstraintService);
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
        @DisplayName("resolves a LOOKUP to a platform system collection (users) outside the package")
        void resolvesReferenceToSystemCollection() {
            seedCollections(Map.of("orders", "tgt-orders"));
            when(jdbcTemplate.queryForList(contains("WHERE system_collection = true")))
                    .thenReturn(List.of(Map.of("id", "sys-users", "name", "users")));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "new-field"));

            var report = service.importPackage(TENANT, pkg(item("FIELD", lookupFieldData("users"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).as("report: %s", report.items()).isZero();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), dataCaptor.capture());
            assertThat(dataCaptor.getValue().get("referenceCollectionId")).isEqualTo("sys-users");
        }

        @Test
        @DisplayName("a package collection named like a system collection never resolves to the system row")
        void packageCollectionNeverResolvesToSystemRow() {
            when(jdbcTemplate.queryForList(contains("WHERE system_collection = true")))
                    .thenReturn(List.of(Map.of("id", "sys-users", "name", "users")));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tenant-users"));

            service.importPackage(TENANT, pkg(item("COLLECTION", collectionData("users"))),
                    PackageImportService.ImportOptions.defaults());

            verify(queryEngine).create(any(), anyMap());
            verify(queryEngine, never()).update(any(), eq("sys-users"), anyMap());
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
            // The existing row's id, so a caller can tell which record it skipped over.
            assertThat(report.items().get(0).resourceId()).isEqualTo("existing-id");
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
        @DisplayName("creates the collection without ownership, then applies it once fields exist")
        void ownershipAppliedAfterFields() {
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-orders"));

            Map<String, Object> collection = collectionData("orders");
            collection.put("owner_field", "member");
            collection.put("owner_scope", "PORTAL");
            collection.put("owner_scope_reads", false);

            var report = service.importPackage(TENANT, pkg(item("COLLECTION", collection)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isZero();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> createCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), createCaptor.capture());
            assertThat(createCaptor.getValue())
                    .doesNotContainKeys("ownerField", "ownerScope", "ownerScopeReads");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(any(), eq("tgt-orders"), updateCaptor.capture());
            assertThat(updateCaptor.getValue())
                    .containsEntry("ownerField", "member")
                    .containsEntry("ownerScope", "PORTAL")
                    .containsEntry("ownerScopeReads", false);
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
        @DisplayName("a second import SKIPS the existing collection and field without re-patching display field or ownership")
        void secondImportSkipsWithoutPatching() {
            seedCollections(Map.of("orders", "tgt-orders"));
            when(jdbcTemplate.queryForList(contains("FROM field f"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", "tgt-field", "name", "name", "coll", "orders")));

            Map<String, Object> collection = collectionData("orders");
            collection.put("display_field_id", "src-field-1");
            collection.put("display_field_name", "name");
            collection.put("owner_field", "member");
            collection.put("owner_scope", "PORTAL");
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("id", "src-field-1");
            field.put("collection_name", "orders");
            field.put("name", "name");
            field.put("type", "STRING");

            var report = service.importPackage(TENANT,
                    pkg(item("COLLECTION", collection), item("FIELD", field)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.items()).extracting(PackageImportService.ItemResult::action)
                    .containsExactly("SKIPPED", "SKIPPED");
            assertThat(report.created()).isZero();
            assertThat(report.updated()).isZero();
            verify(queryEngine, never()).create(any(), anyMap());
            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("OVERWRITE of an existing collection still patches its display field")
        void overwritePatchesDisplayField() {
            seedCollections(Map.of("orders", "tgt-orders"));
            when(jdbcTemplate.queryForList(contains("FROM field f"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", "tgt-field", "name", "name", "coll", "orders")));
            when(queryEngine.update(any(), anyString(), anyMap()))
                    .thenReturn(Optional.of(Map.of("id", "tgt-orders")));

            Map<String, Object> collection = collectionData("orders");
            collection.put("display_field_name", "name");

            var report = service.importPackage(TENANT, pkg(item("COLLECTION", collection)),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.OVERWRITE, false, null, null, null));

            assertThat(report.updated()).isEqualTo(1);
            verify(queryEngine).update(any(), eq("tgt-orders"), eq(Map.of("displayFieldId", "tgt-field")));
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

        private static final String EXEC_UUID = "11111111-2222-3333-4444-555555555555";

        private void seedPlatformUser(String id) {
            when(jdbcTemplate.queryForList(contains("AND id = ?"), eq(TENANT), eq(id)))
                    .thenReturn(List.of(Map.of("id", id)));
        }

        private void seedFirstUser(String id) {
            when(jdbcTemplate.queryForList(contains("ORDER BY created_at"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", id)));
        }

        private Object importFlowAs(String executingUserId) {
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-flow"));

            var report = service.importPackage(TENANT, pkg(item("FLOW", flowData())),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.SKIP, false, null, null, executingUserId));

            assertThat(report.created()).as("%s", report.items()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), dataCaptor.capture());
            return dataCaptor.getValue().get("createdBy");
        }

        @Test
        @DisplayName("passes a UUID executing user (PAT path) through unchanged")
        void usesExecutingUserUuid() {
            seedPlatformUser(EXEC_UUID);
            seedFirstUser("first-user");

            assertThat(importFlowAs(EXEC_UUID)).isEqualTo(EXEC_UUID);
            verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class), any(Object[].class));
        }

        @Test
        @DisplayName("resolves an email executing user (JWT path) to its platform_user id")
        void resolvesExecutingUserEmail() {
            when(jdbcTemplate.queryForObject(contains("AND email = ?"), eq(String.class),
                    eq(TENANT), eq("admin@kelta.local"))).thenReturn(EXEC_UUID);
            seedPlatformUser(EXEC_UUID);
            seedFirstUser("first-user");

            assertThat(importFlowAs("admin@kelta.local")).isEqualTo(EXEC_UUID);
        }

        @Test
        @DisplayName("falls back to the first user when the email has no platform_user in the target")
        void unknownEmailFallsBackToFirstUser() {
            when(jdbcTemplate.queryForObject(contains("AND email = ?"), eq(String.class),
                    eq(TENANT), eq("stranger@kelta.local")))
                    .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
            seedFirstUser("first-user");

            assertThat(importFlowAs("stranger@kelta.local")).isEqualTo("first-user");
        }

        @Test
        @DisplayName("falls back to the first user when the UUID has no row in the target tenant")
        void unknownUuidFallsBackToFirstUser() {
            seedFirstUser("first-user");

            assertThat(importFlowAs(EXEC_UUID)).isEqualTo("first-user");
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

    @Test
    @DisplayName("a CREATED item carries the id the engine assigned, and its field resolves to it")
    void createdItemCarriesResourceId() {
        when(queryEngine.create(any(), anyMap())).thenAnswer(inv -> {
            Map<String, Object> data = inv.getArgument(1);
            return Map.of("id", "tgt-" + data.get("name"));
        });
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("collection_name", "orders");
        field.put("name", "reference");
        field.put("type", "STRING");

        var report = service.importPackage(TENANT,
                pkg(item("COLLECTION", collectionData("orders")), item("FIELD", field)),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.items()).extracting(
                        PackageImportService.ItemResult::naturalKey,
                        PackageImportService.ItemResult::action,
                        PackageImportService.ItemResult::resourceId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("orders", "CREATED", "tgt-orders"),
                        org.assertj.core.groups.Tuple.tuple("orders.reference", "CREATED", "tgt-reference"));
    }

    @Test
    @DisplayName("a FAILED item has no resourceId, and the 4-argument constructor leaves it null")
    void failedItemHasNoResourceId() {
        var report = service.importPackage(TENANT,
                pkg(item("FIELD", lookupFieldData("customers"))),
                PackageImportService.ImportOptions.defaults());

        assertThat(report.items().get(0).action()).isEqualTo("FAILED");
        assertThat(report.items().get(0).resourceId()).isNull();
        assertThat(new PackageImportService.ItemResult("COLLECTION", "x", "SKIPPED", null).resourceId())
                .isNull();
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
    // Legacy jsonb wrappers (packages exported before BUILD-LOG error 13)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("legacy {null,type,value} jsonb wrapper")
    class LegacyJsonWrapper {

        private Map<String, Object> wrapper(boolean isNull, String value) {
            return mapOf("null", isNull, "type", "jsonb", "value", value);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> importFlow(Object definition) {
            when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", "u-1")));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-flow"));
            Map<String, Object> data = flowData();
            data.put("definition", definition);

            var report = service.importPackage(TENANT, pkg(item("FLOW", data)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).as("%s", report.items()).isZero();
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("a wrapped value imports as the parsed object")
        @SuppressWarnings("unchecked")
        void unwrapsToParsedObject() {
            Map<String, Object> written = importFlow(
                    wrapper(false, "{\"StartAt\":\"stamp\",\"States\":{\"stamp\":{\"Type\":\"Succeed\"}}}"));

            assertThat(written.get("definition")).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) written.get("definition"))
                    .containsEntry("StartAt", "stamp")
                    .doesNotContainKeys("null", "type", "value");
        }

        @Test
        @DisplayName("null:true imports as null")
        void nullWrapperImportsAsNull() {
            Map<String, Object> written = importFlow(wrapper(true, null));

            assertThat(written).doesNotContainKey("definition");
        }

        @Test
        @DisplayName("an unparseable value fails the item, naming the column")
        void malformedWrapperFailsItem() {
            when(jdbcTemplate.queryForList(contains("FROM platform_user"), eq(TENANT)))
                    .thenReturn(List.of(Map.of("id", "u-1")));
            Map<String, Object> data = flowData();
            data.put("definition", wrapper(false, "{not json"));

            var report = service.importPackage(TENANT, pkg(item("FLOW", data)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            var failed = report.items().get(0);
            assertThat(failed.action()).isEqualTo("FAILED");
            assertThat(failed.naturalKey()).isEqualTo("myflow");
            assertThat(failed.error()).contains("definition");
            verify(queryEngine, never()).create(any(), anyMap());
        }

        @Test
        @DisplayName("a Map without exactly the keys null/type/value passes through unchanged")
        void otherMapsPassThrough() {
            Map<String, Object> tenantJson = mapOf("type", "jsonb", "value", "{}", "label", "x");
            Map<String, Object> twoKeys = mapOf("type", "jsonb", "value", "{}");
            Map<String, Object> otherType = mapOf("null", false, "type", "text", "value", "{}");

            assertThat(service.normalizeValue("config", tenantJson)).isSameAs(tenantJson);
            assertThat(service.normalizeValue("config", twoKeys)).isSameAs(twoKeys);
            assertThat(service.normalizeValue("config", otherType)).isSameAs(otherType);
        }
    }

    // ------------------------------------------------------------------
    // Global picklist binding (BUILD-LOG error 14)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("supportedTypes() imports GLOBAL_PICKLIST before FIELD")
    void globalPicklistsImportBeforeFields() {
        List<String> order = PackageImportService.supportedTypes();
        assertThat(order.indexOf("GLOBAL_PICKLIST")).isLessThan(order.indexOf("FIELD"));
        assertThat(order).containsSubsequence("COLLECTION", "GLOBAL_PICKLIST", "FIELD", "PICKLIST_VALUE");
    }

    @Nested
    @DisplayName("field bound to a global picklist (BUILD-LOG error 14)")
    class GlobalPicklistBinding {

        private Map<String, Object> boundFieldData(String name, String sourcePicklistId, String picklistName) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", "src-" + name);
            data.put("collection_name", "deals");
            data.put("name", name);
            data.put("type", "PICKLIST");
            data.put("field_type_config", Map.of("globalPicklistId", sourcePicklistId, "restricted", true));
            if (picklistName != null) {
                data.put("global_picklist_name", picklistName);
            }
            return data;
        }

        private Map<String, Object> picklistData(String name) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", "src-gp-" + name);
            data.put("name", name);
            return data;
        }

        private void seedTargetPicklists(Map<String, String> nameToId) {
            List<Map<String, Object>> rows = new ArrayList<>();
            nameToId.forEach((name, id) -> rows.add(Map.of("id", id, "name", name)));
            when(jdbcTemplate.queryForList(contains("FROM global_picklist"), eq(TENANT))).thenReturn(rows);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> writtenFieldTypeConfig(String fieldName) {
            ArgumentCaptor<CollectionDefinition> defs = ArgumentCaptor.forClass(CollectionDefinition.class);
            ArgumentCaptor<Map<String, Object>> records = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine, atLeastOnce()).create(defs.capture(), records.capture());
            for (int i = 0; i < defs.getAllValues().size(); i++) {
                if ("fields".equals(defs.getAllValues().get(i).name())
                        && fieldName.equals(records.getAllValues().get(i).get("name"))) {
                    return (Map<String, Object>) records.getAllValues().get(i).get("fieldTypeConfig");
                }
            }
            throw new AssertionError("field not written: " + fieldName);
        }

        @Test
        @DisplayName("points the field at the id the same import gave the picklist")
        void remapsToPicklistCreatedByTheImport() {
            seedCollections(Map.of("deals", "tgt-deals"));
            when(queryEngine.create(argThat(d -> d != null && "global-picklists".equals(d.name())), anyMap()))
                    .thenReturn(Map.of("id", "tgt-gp-stage"));
            when(queryEngine.create(argThat(d -> d != null && "fields".equals(d.name())), anyMap()))
                    .thenReturn(Map.of("id", "tgt-field"));

            // FIELD listed first, as an export may order it — TYPE_ORDER must still run the picklist first.
            var report = service.importPackage(TENANT, pkg(
                    item("FIELD", boundFieldData("stage", "src-gp-crm-deal-stage", "crm-deal-stage")),
                    item("GLOBAL_PICKLIST", picklistData("crm-deal-stage"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).as("%s", report.items()).isZero();
            assertThat(writtenFieldTypeConfig("stage"))
                    .containsEntry("globalPicklistId", "tgt-gp-stage")
                    .containsEntry("restricted", true);
        }

        @Test
        @DisplayName("points the field at a picklist of the same name that already exists in the target")
        void remapsToExistingTargetPicklist() {
            seedCollections(Map.of("deals", "tgt-deals"));
            seedTargetPicklists(Map.of("crm-deal-stage", "tgt-existing-gp"));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-field"));

            var report = service.importPackage(TENANT,
                    pkg(item("FIELD", boundFieldData("stage", "src-gp-crm-deal-stage", "crm-deal-stage"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).as("%s", report.items()).isZero();
            assertThat(writtenFieldTypeConfig("stage")).containsEntry("globalPicklistId", "tgt-existing-gp");
        }

        @Test
        @DisplayName("an unknown picklist name fails only that field, naming collection, field and picklist")
        void unknownNameFailsOnlyThatField() {
            seedCollections(Map.of("deals", "tgt-deals"));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-new"));

            Map<String, Object> plain = new LinkedHashMap<>();
            plain.put("collection_name", "deals");
            plain.put("name", "amount");
            plain.put("type", "NUMBER");

            var report = service.importPackage(TENANT, pkg(
                    item("COLLECTION", collectionData("accounts")),
                    item("FIELD", boundFieldData("stage", "src-gp-x", "missing-picklist")),
                    item("FIELD", plain)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.created()).as("%s", report.items()).isEqualTo(2);
            var failed = report.items().stream().filter(i -> "FAILED".equals(i.action())).findFirst().orElseThrow();
            assertThat(failed.type()).isEqualTo("FIELD");
            assertThat(failed.naturalKey()).isEqualTo("deals.stage");
            assertThat(failed.error()).isEqualTo("Field deals.stage references global picklist "
                    + "'missing-picklist', which is neither in the package nor in the target tenant");
            assertThat(report.items()).extracting(PackageImportService.ItemResult::naturalKey)
                    .containsExactlyInAnyOrder("accounts", "deals.stage", "deals.amount");
            verify(queryEngine, never()).create(any(),
                    ArgumentMatchers.<Map<String, Object>>argThat(m -> m != null && "stage".equals(m.get("name"))));
        }

        @Test
        @DisplayName("a package without global_picklist_name keeps an id that exists in the target")
        void legacyPackageKeepsTargetId() {
            seedCollections(Map.of("deals", "tgt-deals"));
            seedTargetPicklists(Map.of("crm-deal-stage", "gp-same-tenant"));
            when(queryEngine.create(any(), anyMap())).thenReturn(Map.of("id", "tgt-field"));

            var report = service.importPackage(TENANT,
                    pkg(item("FIELD", boundFieldData("stage", "gp-same-tenant", null))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).as("%s", report.items()).isZero();
            assertThat(writtenFieldTypeConfig("stage")).containsEntry("globalPicklistId", "gp-same-tenant");
        }

        @Test
        @DisplayName("a package without global_picklist_name fails on an id the target does not have")
        void legacyPackageFailsOnForeignId() {
            seedCollections(Map.of("deals", "tgt-deals"));
            seedTargetPicklists(Map.of("crm-deal-stage", "gp-target"));

            var report = service.importPackage(TENANT,
                    pkg(item("FIELD", boundFieldData("stage", "gp-from-another-tenant", null))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items().get(0).error())
                    .contains("deals.stage")
                    .contains("gp-from-another-tenant")
                    .contains("re-export");
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
        assertThat(PackageImportService.naturalKeyFor("LIST_VIEW",
                Map.of("collection_name", "accounts", "name", "All accounts")))
                .isEqualTo("accounts:All accounts");
        assertThat(PackageImportService.naturalKeyFor("DASHBOARD", Map.of("name", "Sales")))
                .isEqualTo("Sales");
    }

    // ------------------------------------------------------------------
    // List views, dashboards and seed records (KLT-585)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("list views, dashboards and the records section")
    class ViewsDashboardsAndRecords {

        private final CollectionDefinition accounts = new CollectionDefinitionBuilder()
                .name("accounts")
                .displayName("Accounts")
                .addField(FieldDefinition.string("name"))
                .addField(FieldDefinition.string("industry"))
                .build();

        private final CollectionDefinition contacts = new CollectionDefinitionBuilder()
                .name("contacts")
                .displayName("Contacts")
                .addField(FieldDefinition.string("email"))
                .addField(FieldDefinition.string("account"))
                .build();

        @BeforeEach
        void registerUserCollections() {
            when(collectionRegistry.get("accounts")).thenReturn(accounts);
            when(collectionRegistry.get("contacts")).thenReturn(contacts);
            when(queryEngine.executeQuery(any(), any())).thenReturn(QueryResult.empty(new Pagination(1, 1)));
            when(queryEngine.create(any(), anyMap())).thenAnswer(inv ->
                    Map.of("id", "tgt-" + inv.<CollectionDefinition>getArgument(0).name()));
            when(dashboardTreeService.applyTree(anyString(), anyMap())).thenAnswer(inv ->
                    new DashboardTreeService.ApplyResult("tgt-dash", inv.getArgument(0),
                            new DashboardTreeService.TreeCounts(2, 0, 0, 0)));
        }

        private Map<String, Object> listViewData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", "src-lv");
            data.put("collection_id", "src-accounts");
            data.put("collection_name", "accounts");
            data.put("name", "All accounts");
            data.put("visibility", "PUBLIC");
            data.put("columns", List.of("name", "industry"));
            data.put("filters", List.of());
            data.put("row_limit", 25);
            return data;
        }

        private Map<String, Object> dashboardData(Map<String, Object> config) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("title", "Accounts");
            component.put("componentType", "metric");
            component.put("columnPosition", 1);
            component.put("rowPosition", 1);
            component.put("config", config);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", "Sales");
            data.put("columnCount", 3);
            data.put("components", List.of(component));
            return data;
        }

        private Map<String, Object> accountSeeds() {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("collection", "accounts");
            section.put("key", List.of("name"));
            section.put("rows", List.of(
                    mapOf("name", "Acme", "industry", "Tech"),
                    mapOf("name", "Globex", "industry", "Energy")));
            return section;
        }

        @SafeVarargs
        private Map<String, Object> pkgWithRecords(List<Map<String, Object>> records,
                                                   Map<String, Object>... items) {
            Map<String, Object> pkg = pkg(items);
            pkg.put("records", records);
            return pkg;
        }

        private org.mockito.ArgumentMatcher<CollectionDefinition> named(String name) {
            return def -> def != null && name.equals(def.name());
        }

        @Test
        @DisplayName("TYPE_ORDER runs collections before list views/dashboards before records, whatever the package order")
        void collectionsBeforeViewsAndDashboardsBeforeRecords() {
            List<String> order = PackageImportService.supportedTypes();
            assertThat(order).containsSubsequence(
                    "COLLECTION", "GLOBAL_PICKLIST", "FIELD", "PICKLIST_VALUE", "LIST_VIEW", "DASHBOARD", "RECORD");
            assertThat(order.getLast()).isEqualTo("RECORD");

            var report = service.importPackage(TENANT, pkgWithRecords(List.of(accountSeeds()),
                            item("DASHBOARD", dashboardData(Map.of())),
                            item("LIST_VIEW", listViewData()),
                            item("COLLECTION", collectionData("accounts"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).as("%s", report.items()).isZero();
            assertThat(report.items()).extracting(PackageImportService.ItemResult::type)
                    .containsExactly("COLLECTION", "LIST_VIEW", "DASHBOARD", "RECORD", "RECORD");
            InOrder order2 = inOrder(queryEngine, dashboardTreeService);
            order2.verify(queryEngine).create(argThat(named("collections")), anyMap());
            order2.verify(queryEngine).create(argThat(named("list-views")), anyMap());
            order2.verify(dashboardTreeService).applyTree(eq("Sales"), anyMap());
            order2.verify(queryEngine, times(2)).create(argThat(named("accounts")), anyMap());
        }

        @Test
        @DisplayName("applying the same list view + dashboard twice reports them unchanged and creates nothing new")
        @SuppressWarnings("unchecked")
        void secondApplyLeavesViewsAndDashboardsUnchanged() {
            seedCollections(Map.of("accounts", "tgt-accounts"));
            var pkg = pkg(item("LIST_VIEW", listViewData()), item("DASHBOARD", dashboardData(Map.of())));

            var first = service.importPackage(TENANT, pkg, PackageImportService.ImportOptions.defaults());

            assertThat(first.created()).as("%s", first.items()).isEqualTo(2);
            ArgumentCaptor<Map<String, Object>> view = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(argThat(named("list-views")), view.capture());
            assertThat(view.getValue())
                    .containsEntry("collectionId", "tgt-accounts")
                    .containsEntry("name", "All accounts")
                    .containsEntry("columns", List.of("name", "industry"))
                    .containsEntry("rowLimit", 25)
                    .doesNotContainKey("id");
            ArgumentCaptor<Map<String, Object>> tree = ArgumentCaptor.forClass(Map.class);
            verify(dashboardTreeService).applyTree(eq("Sales"), tree.capture());
            assertThat(tree.getValue()).containsEntry("columnCount", 3).doesNotContainKey("name");
            assertThat((List<?>) tree.getValue().get("components")).hasSize(1);

            // The target as the seed queries now read it: the rows the first apply created.
            when(jdbcTemplate.queryForList(contains("FROM list_view lv"), eq(TENANT))).thenReturn(List.of(
                    Map.of("id", "tgt-list-views", "name", "All accounts", "coll", "accounts")));
            when(jdbcTemplate.queryForList(contains("FROM dashboard WHERE"), eq(TENANT))).thenReturn(List.of(
                    Map.of("id", "tgt-dash", "name", "Sales")));

            var second = service.importPackage(TENANT, pkg, PackageImportService.ImportOptions.defaults());

            assertThat(second.failed()).as("%s", second.items()).isZero();
            assertThat(second.created()).isZero();
            assertThat(second.skipped()).isEqualTo(2);
            assertThat(second.items()).extracting(PackageImportService.ItemResult::resourceId)
                    .containsExactly("tgt-list-views", "tgt-dash");
            verify(queryEngine, times(1)).create(argThat(named("list-views")), anyMap());
            verify(dashboardTreeService, times(1)).applyTree(anyString(), anyMap());

            var overwrite = service.importPackage(TENANT, pkg, new PackageImportService.ImportOptions(
                    PackageImportService.ConflictMode.OVERWRITE, false, null, null, null));

            assertThat(overwrite.updated()).as("%s", overwrite.items()).isEqualTo(2);
            verify(queryEngine).update(argThat(named("list-views")), eq("tgt-list-views"), anyMap());
            verify(queryEngine, times(1)).create(argThat(named("list-views")), anyMap());
        }

        @Test
        @DisplayName("an invalid widget config fails the dashboard with the validator's message, writing nothing")
        void invalidWidgetConfigFailsTheDashboard() {
            var report = service.importPackage(TENANT,
                    pkg(item("DASHBOARD", dashboardData(Map.of("collectionName", "nope")))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            var failed = report.items().get(0);
            assertThat(failed.type()).isEqualTo("DASHBOARD");
            assertThat(failed.naturalKey()).isEqualTo("Sales");
            assertThat(failed.error())
                    .isEqualTo("Dashboard 'Sales' has invalid widget config: component 'Accounts' "
                            + "config/collectionName: Unknown collection 'nope'");
            verify(dashboardTreeService, never()).applyTree(anyString(), anyMap());
            verify(transactionManager, never()).getTransaction(any());
        }

        @Test
        @DisplayName("seed rows go through one atomic batch; a second apply skips rows whose key already exists")
        void secondApplySkipsExistingSeedRows() {
            var pkg = pkgWithRecords(List.of(accountSeeds()));

            var first = service.importPackage(TENANT, pkg, PackageImportService.ImportOptions.defaults());

            assertThat(first.created()).as("%s", first.items()).isEqualTo(2);
            assertThat(first.items()).extracting(PackageImportService.ItemResult::naturalKey)
                    .containsExactly("accounts:name=Acme", "accounts:name=Globex");
            verify(queryEngine, times(2)).create(argThat(named("accounts")), anyMap());
            verify(transactionManager, times(1)).getTransaction(any());
            verify(transactionManager, times(1)).commit(any());

            // Both rows now exist: the key lookup finds them.
            when(queryEngine.executeQuery(eq(accounts), any())).thenAnswer(inv -> {
                QueryRequest request = inv.getArgument(1);
                Object name = request.filters().get(0).value();
                return QueryResult.of(List.of(Map.of("id", "rec-" + name)), 1, new Pagination(1, 1));
            });

            var second = service.importPackage(TENANT, pkg, PackageImportService.ImportOptions.defaults());

            assertThat(second.failed()).as("%s", second.items()).isZero();
            assertThat(second.created()).isZero();
            assertThat(second.skipped()).isEqualTo(2);
            assertThat(second.items()).extracting(PackageImportService.ItemResult::resourceId)
                    .containsExactly("rec-Acme", "rec-Globex");
            verify(queryEngine, times(2)).create(argThat(named("accounts")), anyMap());
            verify(transactionManager, times(1)).getTransaction(any());
            verify(queryEngine, atLeastOnce()).executeQuery(eq(accounts), argThat(r ->
                    r.filters().equals(List.of(FilterCondition.eq("name", "Acme")))));
        }

        @Test
        @DisplayName("a failing row rolls the whole section back and fails every row it would have written")
        void failingRowRollsBackTheSection() {
            doAnswer(inv -> {
                Map<String, Object> row = inv.getArgument(1);
                if ("Globex".equals(row.get("name"))) {
                    throw new IllegalStateException("duplicate key value violates unique constraint");
                }
                return Map.of("id", "rec-1");
            }).when(queryEngine).create(argThat(named("accounts")), anyMap());

            var report = service.importPackage(TENANT, pkgWithRecords(List.of(accountSeeds())),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(2);
            assertThat(report.created()).isZero();
            assertThat(report.items()).allSatisfy(item -> assertThat(item.error())
                    .contains("accounts:name=Globex").contains("unique constraint"));
            verify(transactionManager).rollback(any());
            verify(transactionManager, never()).commit(any());
        }

        @Test
        @DisplayName("a reference field's value is resolved to the parent record's id by the declared field")
        @SuppressWarnings("unchecked")
        void resolvesReferencesByNaturalKey() {
            when(queryEngine.executeQuery(eq(accounts), any())).thenReturn(
                    QueryResult.of(List.of(Map.of("id", "rec-acme")), 1, new Pagination(1, 2)));
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("collection", "contacts");
            section.put("key", List.of("email"));
            section.put("references", Map.of("account", Map.of("collection", "accounts", "field", "name")));
            section.put("rows", List.of(mapOf("email", "ada@acme.io", "account", "Acme")));

            var report = service.importPackage(TENANT, pkgWithRecords(List.of(section)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).as("%s", report.items()).isEqualTo(1);
            ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(argThat(named("contacts")), written.capture());
            assertThat(written.getValue()).containsEntry("account", "rec-acme").containsEntry("email", "ada@acme.io");
            verify(queryEngine).executeQuery(eq(accounts), argThat(r ->
                    r.filters().equals(List.of(FilterCondition.eq("name", "Acme")))));
        }

        @Test
        @DisplayName("a dry run reports seed rows and dashboards without writing or opening a transaction")
        void dryRunWritesNothing() {
            var report = service.importPackage(TENANT, pkgWithRecords(List.of(accountSeeds()),
                            item("DASHBOARD", dashboardData(Map.of()))),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.SKIP, true, null, null, null));

            assertThat(report.created()).as("%s", report.items()).isEqualTo(3);
            verify(queryEngine, never()).create(any(), anyMap());
            verify(dashboardTreeService, never()).applyTree(anyString(), anyMap());
            verify(transactionManager, never()).getTransaction(any());
        }

        @Test
        @DisplayName("a malformed records section fails with a message naming it")
        void malformedSectionFails() {
            var report = service.importPackage(TENANT,
                    pkgWithRecords(List.of(Map.of("collection", "accounts", "rows", List.of()))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(1);
            assertThat(report.items().get(0).error())
                    .isEqualTo("records[0].key must be a non-empty array of field names");
        }
    }

    // ------------------------------------------------------------------
    // Unique constraints, reports, approval processes
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("UNIQUE_CONSTRAINT, REPORT and APPROVAL_PROCESS")
    class ConstraintsReportsApprovals {

        private final CollectionDefinition orders = new CollectionDefinitionBuilder().name("orders")
                .addField(FieldDefinition.string("region"))
                .addField(FieldDefinition.string("code"))
                .addField(FieldDefinition.string("owner"))
                .build();

        @BeforeEach
        void seedTarget() {
            seedCollections(Map.of("orders", "tgt-orders"));
            when(jdbcTemplate.queryForList(contains("FROM field f "), eq(TENANT))).thenReturn(List.of(
                    mapOf("id", "tgt-region", "name", "region", "coll", "orders"),
                    mapOf("id", "tgt-code", "name", "code", "coll", "orders"),
                    mapOf("id", "tgt-owner", "name", "owner", "coll", "orders")));
            when(collectionRegistry.get("orders")).thenReturn(orders);
            when(jdbcTemplate.queryForList(contains("lower(email)"), eq(TENANT), eq("ann@acme.io")))
                    .thenReturn(List.of(Map.of("id", "tgt-ann")));
            when(jdbcTemplate.queryForList(contains("FROM user_group"), eq(TENANT), eq("Finance")))
                    .thenReturn(List.of(Map.of("id", "tgt-finance")));
            when(queryEngine.create(any(), anyMap())).thenAnswer(inv ->
                    Map.of("id", "tgt-" + inv.<CollectionDefinition>getArgument(0).name()));
        }

        private Map<String, Object> constraintData(String... fields) {
            return mapOf("collection_name", "orders", "field_names", List.of(fields));
        }

        private Map<String, Object> reportData(String collection, String column) {
            return mapOf("name", "Open orders", "report_type", "TABULAR",
                    "primary_collection_name", collection,
                    "columns", List.of(Map.of("fieldName", column)), "group_by", "region");
        }

        private Map<String, Object> step(int number, Object... kv) {
            Map<String, Object> step = mapOf("step_number", number, "name", "Step " + number);
            step.putAll(mapOf(kv));
            return step;
        }

        private Map<String, Object> approvalData(List<Map<String, Object>> steps) {
            return mapOf("collection_name", "orders", "name", "Discount", "active", true,
                    "initial_submitter_field", "owner", "steps", steps);
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> writes(String systemCollection) {
            ArgumentCaptor<CollectionDefinition> defs = ArgumentCaptor.forClass(CollectionDefinition.class);
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine, atLeast(0)).create(defs.capture(), data.capture());
            List<Map<String, Object>> matching = new ArrayList<>();
            for (int i = 0; i < defs.getAllValues().size(); i++) {
                if (defs.getAllValues().get(i).name().equals(systemCollection)) {
                    matching.add(data.getAllValues().get(i));
                }
            }
            return matching;
        }

        @Test
        @DisplayName("natural keys: sorted collection:fields, report name, collection:process name")
        void naturalKeys() {
            assertThat(PackageImportService.naturalKeyFor("UNIQUE_CONSTRAINT", constraintData("region", "code")))
                    .isEqualTo("orders:code,region");
            assertThat(PackageImportService.naturalKeyFor("REPORT", reportData("orders", "code")))
                    .isEqualTo("Open orders");
            assertThat(PackageImportService.naturalKeyFor("APPROVAL_PROCESS", approvalData(List.of())))
                    .isEqualTo("orders:Discount");
        }

        @Test
        @DisplayName("TYPE_ORDER places the three types after COLLECTION and FIELD")
        void typeOrder() {
            List<String> order = PackageImportService.supportedTypes();
            for (String type : List.of("UNIQUE_CONSTRAINT", "REPORT", "APPROVAL_PROCESS")) {
                assertThat(order.indexOf(type)).as(type)
                        .isGreaterThan(order.indexOf("COLLECTION"))
                        .isGreaterThan(order.indexOf("FIELD"));
            }
        }

        @Test
        @DisplayName("a unique constraint is created through the service on the target collection by field names")
        void createsUniqueConstraint() {
            when(uniqueConstraintService.create(eq(orders), anyList()))
                    .thenReturn(new ConstraintInfo("uniq_orders_code_region", List.of("code", "region"),
                            List.of("code", "region")));

            var report = service.importPackage(TENANT, pkg(item("UNIQUE_CONSTRAINT", constraintData("region", "code"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).as("%s", report.items()).isEqualTo(1);
            assertThat(report.items().get(0).resourceId()).isEqualTo("uniq_orders_code_region");
            verify(uniqueConstraintService).create(orders, List.of("code", "region"));
        }

        @Test
        @DisplayName("a unique constraint whose field set already exists (in any order) is SKIPPED, even under OVERWRITE")
        void existingUniqueConstraintSkipped() {
            when(uniqueConstraintService.list(orders)).thenReturn(List.of(new ConstraintInfo(
                    "uniq_orders_region_code", List.of("region", "code"), List.of("region", "code"))));

            var report = service.importPackage(TENANT, pkg(item("UNIQUE_CONSTRAINT", constraintData("code", "region"))),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.OVERWRITE, false, null, null, null));

            assertThat(report.skipped()).isEqualTo(1);
            verify(uniqueConstraintService, never()).create(any(), anyList());
        }

        @Test
        @DisplayName("a report resolves its primary collection by name and checks its fields")
        void reportResolvesCollection() {
            var report = service.importPackage(TENANT, pkg(item("REPORT", reportData("orders", "code"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).as("%s", report.items()).isEqualTo(1);
            assertThat(writes("reports")).singleElement().satisfies(r -> assertThat(r)
                    .containsEntry("primaryCollectionId", "tgt-orders")
                    .containsEntry("name", "Open orders")
                    .containsEntry("columns", List.of(Map.of("fieldName", "code"))));
        }

        @Test
        @DisplayName("an approval step's USER approver resolves by email and GROUP approver by group name")
        void approvalProcessResolvesApprovers() {
            var report = service.importPackage(TENANT, pkg(item("APPROVAL_PROCESS", approvalData(List.of(
                            step(1, "approver_type", "USER", "approver_email", "ann@acme.io"),
                            step(2, "approver_type", "GROUP", "approver_group_name", "Finance"),
                            step(3, "approver_type", "FIELD", "approver_field", "owner"))))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.created()).as("%s", report.items()).isEqualTo(1);
            assertThat(report.items().get(0).naturalKey()).isEqualTo("orders:Discount");
            assertThat(writes("approval-processes")).singleElement().satisfies(p -> assertThat(p)
                    .containsEntry("collectionId", "tgt-orders")
                    .containsEntry("tenantId", TENANT)
                    .containsEntry("initialSubmitterField", "owner"));
            var steps = writes("approval-steps");
            assertThat(steps).extracting(s -> s.get("stepNumber")).containsExactly(1, 2, 3);
            assertThat(steps).allSatisfy(s -> assertThat(s).containsEntry("approvalProcessId", "tgt-approval-processes"));
            assertThat(steps.get(0)).containsEntry("approverId", "tgt-ann");
            assertThat(steps.get(1)).containsEntry("approverId", "tgt-finance");
            assertThat(steps.get(2)).containsEntry("approverField", "owner").containsEntry("approverId", null);
        }

        @Test
        @DisplayName("OVERWRITE updates an existing process's steps by step number and adds new ones")
        void overwriteMatchesStepsByNumber() {
            when(jdbcTemplate.queryForList(contains("FROM approval_process ap"), eq(TENANT))).thenReturn(List.of(
                    mapOf("id", "tgt-ap", "name", "Discount", "coll", "orders")));
            when(jdbcTemplate.queryForList(contains("FROM approval_step WHERE"), eq("tgt-ap"))).thenReturn(List.of(
                    mapOf("id", "tgt-step-1", "step_number", 1)));

            var report = service.importPackage(TENANT, pkg(item("APPROVAL_PROCESS", approvalData(List.of(
                            step(1, "approver_type", "USER", "approver_email", "ann@acme.io"),
                            step(2, "approver_type", "GROUP", "approver_group_name", "Finance"))))),
                    new PackageImportService.ImportOptions(
                            PackageImportService.ConflictMode.OVERWRITE, false, null, null, null));

            assertThat(report.updated()).as("%s", report.items()).isEqualTo(1);
            verify(queryEngine).update(argThat(d -> d.name().equals("approval-processes")), eq("tgt-ap"), anyMap());
            verify(queryEngine).update(argThat(d -> d.name().equals("approval-steps")), eq("tgt-step-1"),
                    argThat(m -> "tgt-ann".equals(m.get("approverId"))));
            assertThat(writes("approval-steps")).singleElement().satisfies(s -> assertThat(s)
                    .containsEntry("stepNumber", 2).containsEntry("approvalProcessId", "tgt-ap"));
        }

        @Test
        @DisplayName("an unresolved reference fails only that item, naming the missing reference")
        void unresolvedReferencesFailOnlyThatItem() {
            Map<String, Object> unknownUser = approvalData(List.of(
                    step(1, "approver_type", "USER", "approver_email", "ghost@acme.io")));
            Map<String, Object> unknownGroup = approvalData(List.of(
                    step(1, "approver_type", "GROUP", "approver_group_name", "Nobody")));
            unknownGroup.put("name", "Other");
            Map<String, Object> unknownCollection = reportData("invoices", "code");
            unknownCollection.put("name", "Invoices");
            Map<String, Object> unknownField = constraintData("region", "zone");

            var report = service.importPackage(TENANT, pkg(
                            item("APPROVAL_PROCESS", unknownUser),
                            item("APPROVAL_PROCESS", unknownGroup),
                            item("REPORT", unknownCollection),
                            item("UNIQUE_CONSTRAINT", unknownField),
                            item("REPORT", reportData("orders", "code"))),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.failed()).isEqualTo(4);
            assertThat(report.created()).as("the valid report still imports").isEqualTo(1);
            Map<String, String> errors = new HashMap<>();
            report.items().stream().filter(i -> "FAILED".equals(i.action()))
                    .forEach(i -> errors.put(i.type() + ":" + i.naturalKey(), i.error()));
            assertThat(errors.get("APPROVAL_PROCESS:orders:Discount")).contains("'ghost@acme.io' not found");
            assertThat(errors.get("APPROVAL_PROCESS:orders:Other")).contains("group 'Nobody' not found");
            assertThat(errors.get("REPORT:Invoices")).isEqualTo("Collection not found in target: invoices");
            assertThat(errors.get("UNIQUE_CONSTRAINT:orders:region,zone"))
                    .isEqualTo("Field not found in target: orders.zone");
            assertThat(writes("approval-processes")).as("nothing half-applied").isEmpty();
            assertThat(writes("approval-steps")).isEmpty();
            verify(uniqueConstraintService, never()).create(any(), anyList());
        }

        @Test
        @DisplayName("a report or approval process naming an unknown field fails with that field")
        void unknownFieldFails() {
            Map<String, Object> approval = approvalData(List.of(
                    step(1, "approver_type", "FIELD", "approver_field", "manager")));

            var report = service.importPackage(TENANT, pkg(
                            item("REPORT", reportData("orders", "total")),
                            item("APPROVAL_PROCESS", approval)),
                    PackageImportService.ImportOptions.defaults());

            assertThat(report.items()).extracting(PackageImportService.ItemResult::error).containsExactly(
                    "Field not found in target: orders.total",
                    "Field not found in target: orders.manager");
        }
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
