package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for system collection behavior in DynamicCollectionRouter.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Tenant ID filters are injected for tenant-scoped system collection list queries</li>
 *   <li>Tenant ID is injected into create data for system collections</li>
 *   <li>Read-only collections return 403 Forbidden for create/update/delete</li>
 *   <li>Non-system collections are unaffected</li>
 * </ul>
 */
class DynamicCollectionRouterSystemCollectionTest {

    private CollectionRegistry registry;
    private QueryEngine queryEngine;
    private DynamicCollectionRouter router;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        router = new DynamicCollectionRouter(registry, queryEngine);
        mockMvc = MockMvcBuilders.standaloneSetup(router).build();
        objectMapper = new ObjectMapper();
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a tenant-scoped, writable system collection.
     */
    private CollectionDefinition buildTenantScopedSystemCollection() {
        return new CollectionDefinitionBuilder()
                .name("workflow-rules")
                .displayName("Workflow Rules")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.string("condition"))
                .systemCollection(true)
                .tenantScoped(true)
                .readOnly(false)
                .build();
    }

    /**
     * Creates a read-only system collection (e.g., audit logs).
     */
    private CollectionDefinition buildReadOnlySystemCollection() {
        return new CollectionDefinitionBuilder()
                .name("audit-logs")
                .displayName("Audit Logs")
                .addField(FieldDefinition.string("action"))
                .addField(FieldDefinition.string("details"))
                .systemCollection(true)
                .tenantScoped(true)
                .readOnly(true)
                .build();
    }

    /**
     * Creates a non-system collection.
     */
    private CollectionDefinition buildNonSystemCollection() {
        return new CollectionDefinitionBuilder()
                .name("products")
                .displayName("Products")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.doubleField("price"))
                .build();
    }

    /**
     * Creates a JSON:API formatted request body.
     */
    private String jsonApiBody(Map<String, Object> attributes) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "test");
        data.put("attributes", attributes);
        Map<String, Object> body = new HashMap<>();
        body.put("data", data);
        return objectMapper.writeValueAsString(body);
    }

    // ==================== Tenant Filter Injection Tests ====================

    @Nested
    @DisplayName("List - Tenant Filter Injection")
    class TenantFilterInjectionTests {

        @Test
        @DisplayName("Should inject tenant filter for tenant-scoped system collection list")
        void list_injectsTenantFilter_forTenantScopedSystemCollection() throws Exception {
            CollectionDefinition def = buildTenantScopedSystemCollection();
            when(registry.get("workflow-rules")).thenReturn(def);

            QueryResult emptyResult = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(emptyResult);

            mockMvc.perform(get("/api/workflow-rules")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());

            // Capture the QueryRequest passed to queryEngine.executeQuery
            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(eq(def), requestCaptor.capture());

            QueryRequest capturedRequest = requestCaptor.getValue();
            assertTrue(capturedRequest.hasFilters(),
                    "Request should have filters injected");

            // Find the tenantId filter
            boolean hasTenantFilter = capturedRequest.filters().stream()
                    .anyMatch(f -> "tenantId".equals(f.fieldName())
                            && FilterOperator.EQ == f.operator()
                            && "tenant-123".equals(f.value()));
            assertTrue(hasTenantFilter,
                    "Should have a tenantId=tenant-123 filter");
        }

        @Test
        @DisplayName("Should scope 'fields' list to caller tenant plus SYSTEM_TENANT_ID, like 'collections'")
        void list_scopesFieldsToTenantAndSystem() throws Exception {
            CollectionDefinition def = io.kelta.runtime.model.system.SystemCollectionDefinitions.fields();
            when(registry.get("fields")).thenReturn(def);

            QueryResult emptyResult = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(emptyResult);

            mockMvc.perform(get("/api/fields")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(eq(def), requestCaptor.capture());

            QueryRequest capturedRequest = requestCaptor.getValue();
            boolean hasScopedTenantFilter = capturedRequest.filters().stream()
                    .anyMatch(f -> "tenantId".equals(f.fieldName())
                            && FilterOperator.IN == f.operator()
                            && f.value() instanceof List<?> values
                            && values.contains("tenant-123")
                            && values.contains(io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID));
            assertTrue(hasScopedTenantFilter,
                    "Should have a tenantId IN (tenant-123, SYSTEM_TENANT_ID) filter, exposing system fields "
                            + "alongside the caller's own, and never another tenant's");
        }

        @Test
        @DisplayName("Should exclude a non-system collection row owned by the platform tenant from another tenant's list")
        void list_excludesStrayNonSystemCollectionOwnedByPlatformTenant() throws Exception {
            CollectionDefinition def = io.kelta.runtime.model.system.SystemCollectionDefinitions.collections();
            when(registry.get("collections")).thenReturn(def);

            String systemTenantId = io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID;

            Map<String, Object> genuineSystemRow = new HashMap<>();
            genuineSystemRow.put("id", "col-system-1");
            genuineSystemRow.put("name", "profiles");
            genuineSystemRow.put("tenantId", systemTenantId);
            genuineSystemRow.put("systemCollection", true);

            Map<String, Object> strayCustomRow = new HashMap<>();
            strayCustomRow.put("id", "col-stray-1");
            strayCustomRow.put("name", "e2e_wizard_123");
            strayCustomRow.put("tenantId", systemTenantId);
            strayCustomRow.put("systemCollection", false);

            Map<String, Object> ownRow = new HashMap<>();
            ownRow.put("id", "col-own-1");
            ownRow.put("name", "orders");
            ownRow.put("tenantId", "tenant-123");
            ownRow.put("systemCollection", false);

            QueryResult mixedResult = QueryResult.of(
                    List.of(genuineSystemRow, strayCustomRow, ownRow), 3, Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(mixedResult);

            MvcResult mvcResult = mockMvc.perform(get("/api/collections")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='col-system-1')]").exists())
                    .andExpect(jsonPath("$.data[?(@.id=='col-own-1')]").exists())
                    .andExpect(jsonPath("$.data[?(@.id=='col-stray-1')]").doesNotExist())
                    .andReturn();

            assertNotNull(mvcResult);
        }

        @Test
        @DisplayName("Should leave the platform tenant's own custom collections and fields in its own list")
        void list_platformTenantSeesItsOwnCustomRows() throws Exception {
            // The harness/e2e "default" tenant IS the platform tenant: a custom collection created
            // there has tenantId = SYSTEM_TENANT_ID and systemCollection = false. It is the caller's
            // own row, not a shared system row, and must not be narrowed away.
            CollectionDefinition collectionsDef = io.kelta.runtime.model.system.SystemCollectionDefinitions.collections();
            CollectionDefinition fieldsDef = io.kelta.runtime.model.system.SystemCollectionDefinitions.fields();
            when(registry.get("collections")).thenReturn(collectionsDef);
            when(registry.get("fields")).thenReturn(fieldsDef);

            String systemTenantId = io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID;

            Map<String, Object> ownCustomRow = new HashMap<>();
            ownCustomRow.put("id", "col-own-custom");
            ownCustomRow.put("name", "migrtest");
            ownCustomRow.put("tenantId", systemTenantId);
            ownCustomRow.put("systemCollection", false);
            when(queryEngine.executeQuery(eq(collectionsDef), any(QueryRequest.class)))
                    .thenReturn(QueryResult.of(List.of(ownCustomRow), 1, Pagination.defaults()));

            mockMvc.perform(get("/api/collections")
                            .header("X-Tenant-ID", systemTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='col-own-custom')]").exists());

            Map<String, Object> ownFieldRow = new HashMap<>();
            ownFieldRow.put("id", "field-own-custom");
            ownFieldRow.put("collectionId", "col-own-custom");
            ownFieldRow.put("tenantId", systemTenantId);
            when(queryEngine.executeQuery(eq(fieldsDef), any(QueryRequest.class)))
                    .thenReturn(QueryResult.of(List.of(ownFieldRow), 1, Pagination.defaults()));

            mockMvc.perform(get("/api/fields")
                            .header("X-Tenant-ID", systemTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='field-own-custom')]").exists());
        }

        @Test
        @DisplayName("Should leave an unbound (no X-Tenant-ID) collections list unfiltered, like injectTenantFilter/visibleToTenant")
        void list_leavesUnboundReadUnfiltered() throws Exception {
            CollectionDefinition def = io.kelta.runtime.model.system.SystemCollectionDefinitions.collections();
            when(registry.get("collections")).thenReturn(def);

            String systemTenantId = io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID;

            Map<String, Object> strayCustomRow = new HashMap<>();
            strayCustomRow.put("id", "col-stray-1");
            strayCustomRow.put("name", "e2e_wizard_123");
            strayCustomRow.put("tenantId", systemTenantId);
            strayCustomRow.put("systemCollection", false);

            QueryResult unfilteredResult = QueryResult.of(List.of(strayCustomRow), 1, Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(unfilteredResult);

            mockMvc.perform(get("/api/collections"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='col-stray-1')]").exists());
        }

        @Test
        @DisplayName("Should exclude fields of a stray platform-tenant custom collection from another tenant's list")
        void list_excludesFieldsOfStrayPlatformTenantCollection() throws Exception {
            CollectionDefinition fieldsDef = io.kelta.runtime.model.system.SystemCollectionDefinitions.fields();
            CollectionDefinition collectionsDef = io.kelta.runtime.model.system.SystemCollectionDefinitions.collections();
            when(registry.get("fields")).thenReturn(fieldsDef);
            when(registry.get("collections")).thenReturn(collectionsDef);

            String systemTenantId = io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID;

            Map<String, Object> systemCollectionRow = new HashMap<>();
            systemCollectionRow.put("id", "col-system-1");
            systemCollectionRow.put("systemCollection", true);
            QueryResult systemCollectionsResult = QueryResult.of(
                    List.of(systemCollectionRow), 1, new Pagination(1, Pagination.MAX_PAGE_SIZE));
            when(queryEngine.executeQuery(eq(collectionsDef), any(QueryRequest.class)))
                    .thenReturn(systemCollectionsResult);

            Map<String, Object> systemFieldRow = new HashMap<>();
            systemFieldRow.put("id", "field-system-1");
            systemFieldRow.put("collectionId", "col-system-1");
            systemFieldRow.put("tenantId", systemTenantId);

            Map<String, Object> strayFieldRow = new HashMap<>();
            strayFieldRow.put("id", "field-stray-1");
            strayFieldRow.put("collectionId", "col-stray-1");
            strayFieldRow.put("tenantId", systemTenantId);

            Map<String, Object> ownFieldRow = new HashMap<>();
            ownFieldRow.put("id", "field-own-1");
            ownFieldRow.put("collectionId", "col-own-1");
            ownFieldRow.put("tenantId", "tenant-123");

            QueryResult fieldsResult = QueryResult.of(
                    List.of(systemFieldRow, strayFieldRow, ownFieldRow), 3, Pagination.defaults());
            when(queryEngine.executeQuery(eq(fieldsDef), any(QueryRequest.class))).thenReturn(fieldsResult);

            mockMvc.perform(get("/api/fields")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='field-system-1')]").exists())
                    .andExpect(jsonPath("$.data[?(@.id=='field-own-1')]").exists())
                    .andExpect(jsonPath("$.data[?(@.id=='field-stray-1')]").doesNotExist());
        }

        @Test
        @DisplayName("Should not inject tenant filter for non-tenant-scoped collection list")
        void list_noTenantFilter_forNonTenantScopedCollection() throws Exception {
            // Create a system collection that is NOT tenant-scoped
            CollectionDefinition def = new CollectionDefinitionBuilder()
                    .name("global-config")
                    .displayName("Global Config")
                    .addField(FieldDefinition.string("key"))
                    .systemCollection(true)
                    .tenantScoped(false)
                    .build();
            when(registry.get("global-config")).thenReturn(def);

            QueryResult emptyResult = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(emptyResult);

            mockMvc.perform(get("/api/global-config")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(eq(def), requestCaptor.capture());

            QueryRequest capturedRequest = requestCaptor.getValue();
            boolean hasTenantFilter = capturedRequest.filters().stream()
                    .anyMatch(f -> "tenantId".equals(f.fieldName()));
            assertFalse(hasTenantFilter,
                    "Should NOT have a tenantId filter for non-tenant-scoped collection");
        }

        @Test
        @DisplayName("Should not inject tenant filter for non-system collection list")
        void list_noTenantFilter_forNonSystemCollection() throws Exception {
            CollectionDefinition def = buildNonSystemCollection();
            when(registry.get("products")).thenReturn(def);

            QueryResult emptyResult = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(eq(def), any(QueryRequest.class))).thenReturn(emptyResult);

            mockMvc.perform(get("/api/products")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(eq(def), requestCaptor.capture());

            QueryRequest capturedRequest = requestCaptor.getValue();
            boolean hasTenantFilter = capturedRequest.filters().stream()
                    .anyMatch(f -> "tenantId".equals(f.fieldName()));
            assertFalse(hasTenantFilter,
                    "Should NOT have a tenantId filter for non-system collection");
        }
    }

    // ==================== Get-by-id Tenant Visibility Tests ====================

    @Nested
    @DisplayName("Get-by-id - Tenant Visibility")
    class GetByIdTenantVisibilityTests {

        private static final String FIELD_ID = "0f9a3b1e-6c2d-4c9e-9d2a-1b2c3d4e5f60";

        private Map<String, Object> fieldRow(String tenantId) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", FIELD_ID);
            row.put("name", "secret_margin");
            row.put("collectionId", "c-1");
            row.put("tenantId", tenantId);
            return row;
        }

        @Test
        @DisplayName("Should 404 another tenant's 'fields' row even when the engine returns it (RLS no-op)")
        void get_hidesOtherTenantsRow() throws Exception {
            CollectionDefinition def = io.kelta.runtime.model.system.SystemCollectionDefinitions.fields();
            when(registry.get("fields")).thenReturn(def);
            when(queryEngine.getById(def, FIELD_ID)).thenReturn(Optional.of(fieldRow("tenant-other")));

            mockMvc.perform(get("/api/fields/" + FIELD_ID)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should serve the caller's own 'fields' row")
        void get_servesOwnRow() throws Exception {
            CollectionDefinition def = io.kelta.runtime.model.system.SystemCollectionDefinitions.fields();
            when(registry.get("fields")).thenReturn(def);
            when(queryEngine.getById(def, FIELD_ID)).thenReturn(Optional.of(fieldRow("tenant-123")));

            mockMvc.perform(get("/api/fields/" + FIELD_ID)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(FIELD_ID));
        }

        @Test
        @DisplayName("Should serve SYSTEM_TENANT_ID 'fields' rows to every tenant, like 'collections'")
        void get_servesSystemRow() throws Exception {
            CollectionDefinition def = io.kelta.runtime.model.system.SystemCollectionDefinitions.fields();
            when(registry.get("fields")).thenReturn(def);
            when(queryEngine.getById(def, FIELD_ID)).thenReturn(Optional.of(
                    fieldRow(io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID)));

            mockMvc.perform(get("/api/fields/" + FIELD_ID)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should not extend SYSTEM_TENANT_ID visibility to other tenant-scoped system collections")
        void get_systemRowNotSharedForOtherCollections() throws Exception {
            CollectionDefinition def = buildTenantScopedSystemCollection();
            when(registry.get("workflow-rules")).thenReturn(def);
            when(queryEngine.getById(def, FIELD_ID)).thenReturn(Optional.of(
                    fieldRow(io.kelta.runtime.model.system.SystemCollectionDefinitions.SYSTEM_TENANT_ID)));

            mockMvc.perform(get("/api/workflow-rules/" + FIELD_ID)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should not apply tenant visibility to non-system collections")
        void get_nonSystemCollectionUnaffected() throws Exception {
            CollectionDefinition def = buildNonSystemCollection();
            when(registry.get("products")).thenReturn(def);
            when(queryEngine.getById(def, FIELD_ID)).thenReturn(Optional.of(fieldRow("tenant-other")));

            mockMvc.perform(get("/api/products/" + FIELD_ID)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());
        }
    }

    // ==================== Read-Only Rejection Tests ====================

    @Nested
    @DisplayName("Read-Only Collection Rejection (403 Forbidden)")
    class ReadOnlyRejectionTests {

        @Test
        @DisplayName("Should return 403 Forbidden for create on read-only collection")
        void create_returnsForbidden_forReadOnlyCollection() throws Exception {
            CollectionDefinition def = buildReadOnlySystemCollection();
            when(registry.get("audit-logs")).thenReturn(def);

            Map<String, Object> attributes = Map.of("action", "LOGIN", "details", "User logged in");
            String body = jsonApiBody(attributes);

            mockMvc.perform(post("/api/audit-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors[0].status").value("403"))
                    .andExpect(jsonPath("$.errors[0].title").value("Forbidden"));

            // Verify the query engine was never called
            verify(queryEngine, never()).create(any(), any());
        }

        @Test
        @DisplayName("Should return 403 Forbidden for update on read-only collection")
        void update_returnsForbidden_forReadOnlyCollection() throws Exception {
            CollectionDefinition def = buildReadOnlySystemCollection();
            when(registry.get("audit-logs")).thenReturn(def);

            Map<String, Object> attributes = Map.of("details", "modified");
            String body = jsonApiBody(attributes);

            mockMvc.perform(put("/api/audit-logs/log-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isForbidden());

            // Also test PATCH
            mockMvc.perform(patch("/api/audit-logs/log-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isForbidden());

            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("Should return 403 Forbidden for delete on read-only collection")
        void delete_returnsForbidden_forReadOnlyCollection() throws Exception {
            CollectionDefinition def = buildReadOnlySystemCollection();
            when(registry.get("audit-logs")).thenReturn(def);

            mockMvc.perform(delete("/api/audit-logs/log-1")
                            .header("X-Tenant-ID", "tenant-1"))
                    .andExpect(status().isForbidden());

            verify(queryEngine, never()).delete(any(), any());
        }
    }

    // ==================== Tenant ID Injection on Create ====================

    @Nested
    @DisplayName("Tenant ID Injection on Create")
    class TenantIdInjectionOnCreateTests {

        @Test
        @DisplayName("Should inject tenantId into create data for tenant-scoped system collection")
        void create_injectsTenantId_forTenantScopedSystemCollection() throws Exception {
            CollectionDefinition def = buildTenantScopedSystemCollection();
            when(registry.get("workflow-rules")).thenReturn(def);

            Map<String, Object> createdRecord = new HashMap<>();
            createdRecord.put("id", "rule-1");
            createdRecord.put("name", "Auto-approve");
            createdRecord.put("tenantId", "tenant-456");
            when(queryEngine.create(eq(def), any())).thenReturn(createdRecord);

            Map<String, Object> attributes = Map.of("name", "Auto-approve", "condition", "amount < 100");
            String body = jsonApiBody(attributes);

            mockMvc.perform(post("/api/workflow-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-456")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isCreated());

            // Capture the data passed to queryEngine.create
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(def), dataCaptor.capture());

            Map<String, Object> capturedData = dataCaptor.getValue();
            assertEquals("tenant-456", capturedData.get("tenantId"),
                    "tenantId should be injected from X-Tenant-ID header");
        }

        @Test
        @DisplayName("Should not inject tenantId for non-system collection")
        void create_noTenantId_forNonSystemCollection() throws Exception {
            CollectionDefinition def = buildNonSystemCollection();
            when(registry.get("products")).thenReturn(def);

            Map<String, Object> createdRecord = new HashMap<>();
            createdRecord.put("id", "prod-1");
            createdRecord.put("name", "Widget");
            when(queryEngine.create(eq(def), any())).thenReturn(createdRecord);

            Map<String, Object> attributes = Map.of("name", "Widget", "price", 9.99);
            String body = jsonApiBody(attributes);

            mockMvc.perform(post("/api/products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-456")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isCreated());

            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(def), dataCaptor.capture());

            Map<String, Object> capturedData = dataCaptor.getValue();
            assertFalse(capturedData.containsKey("tenantId"),
                    "tenantId should NOT be injected for non-system collection");
        }
    }
}
