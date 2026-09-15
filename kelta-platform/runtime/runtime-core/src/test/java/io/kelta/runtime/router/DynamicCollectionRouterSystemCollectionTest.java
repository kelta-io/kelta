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
