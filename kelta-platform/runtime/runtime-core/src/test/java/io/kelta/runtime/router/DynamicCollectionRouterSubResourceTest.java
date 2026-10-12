package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.registry.CollectionRegistry;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
 * Tests for sub-resource routing in DynamicCollectionRouter.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>List children injects parent ID filter on the child's reference field</li>
 *   <li>Get child returns a single child record</li>
 *   <li>Create child auto-sets the parent reference field</li>
 *   <li>Update child delegates to query engine correctly</li>
 *   <li>Delete child delegates to query engine correctly</li>
 *   <li>Missing parent/child collections return 404</li>
 *   <li>Missing relationship between parent and child returns 404</li>
 *   <li>Read-only child collections are rejected</li>
 * </ul>
 */
class DynamicCollectionRouterSubResourceTest {

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
     * Creates a parent collection (e.g., workflow-rules).
     */
    private CollectionDefinition buildParentCollection() {
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
     * Creates a child collection with a reference to the parent.
     */
    private CollectionDefinition buildChildCollection() {
        return new CollectionDefinitionBuilder()
                .name("workflow-actions")
                .displayName("Workflow Actions")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.string("actionType"))
                .addField(new FieldDefinition("workflowRuleId", FieldType.MASTER_DETAIL, false, false, false,
                        null, null, null,
                        ReferenceConfig.masterDetail("workflow-rules", "Workflow Rule"), null))
                .systemCollection(true)
                .tenantScoped(true)
                .readOnly(false)
                .build();
    }

    /**
     * Creates a read-only child collection.
     */
    private CollectionDefinition buildReadOnlyChildCollection() {
        return new CollectionDefinitionBuilder()
                .name("workflow-execution-logs")
                .displayName("Workflow Execution Logs")
                .addField(FieldDefinition.string("message"))
                .addField(FieldDefinition.string("status"))
                .addField(new FieldDefinition("workflowRuleId", FieldType.MASTER_DETAIL, false, false, false,
                        null, null, null,
                        ReferenceConfig.masterDetail("workflow-rules", "Workflow Rule"), null))
                .systemCollection(true)
                .tenantScoped(true)
                .readOnly(true)
                .build();
    }

    /**
     * Creates a collection with no reference to the parent.
     */
    private CollectionDefinition buildUnrelatedCollection() {
        return new CollectionDefinitionBuilder()
                .name("users")
                .displayName("Users")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.string("email"))
                .systemCollection(true)
                .tenantScoped(false)
                .build();
    }

    private String jsonApiBody(Map<String, Object> attributes) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "test");
        data.put("attributes", attributes);
        Map<String, Object> body = new HashMap<>();
        body.put("data", data);
        return objectMapper.writeValueAsString(body);
    }

    /** Stubs the stored child record the sub-resource routes load before reading or writing it. */
    private void storedChild(String id, String parentId, String tenantId) {
        Map<String, Object> stored = new HashMap<>();
        stored.put("id", id);
        stored.put("name", "Existing");
        stored.put("workflowRuleId", parentId);
        stored.put("tenantId", tenantId);
        when(queryEngine.getById(any(CollectionDefinition.class), eq(id))).thenReturn(Optional.of(stored));
    }

    private void registerParentAndChild() {
        when(registry.get("workflow-rules")).thenReturn(buildParentCollection());
        when(registry.get("workflow-actions")).thenReturn(buildChildCollection());
    }

    // ==================== SubResourceResolver Tests ====================

    @Nested
    @DisplayName("SubResourceResolver")
    class SubResourceResolverTests {

        @Test
        @DisplayName("Should resolve relationship when child has reference to parent")
        void resolve_findsRelationship() {
            CollectionDefinition parent = buildParentCollection();
            CollectionDefinition child = buildChildCollection();

            Optional<SubResourceRelation> relation = SubResourceResolver.resolve(parent, child);

            assertTrue(relation.isPresent(), "Should find relationship");
            assertEquals("workflowRuleId", relation.get().parentRefFieldName());
            assertEquals(parent, relation.get().parentDef());
            assertEquals(child, relation.get().childDef());
        }

        @Test
        @DisplayName("Should return empty when no relationship exists")
        void resolve_returnsEmpty_whenNoRelationship() {
            CollectionDefinition parent = buildParentCollection();
            CollectionDefinition unrelated = buildUnrelatedCollection();

            Optional<SubResourceRelation> relation = SubResourceResolver.resolve(parent, unrelated);

            assertTrue(relation.isEmpty(), "Should not find relationship");
        }

        @Test
        @DisplayName("Should resolve STRING field with referenceConfig as fallback")
        void resolve_findsStringFieldWithReferenceConfig() {
            CollectionDefinition parent = buildParentCollection();
            // Child with STRING type foreign key (common in system collections)
            CollectionDefinition child = new CollectionDefinitionBuilder()
                    .name("child-records")
                    .displayName("Child Records")
                    .addField(FieldDefinition.requiredString("name"))
                    .addField(new FieldDefinition("parentId", FieldType.STRING, false, false, false,
                            null, null, null,
                            ReferenceConfig.toCollection("workflow-rules"), null))
                    .build();

            Optional<SubResourceRelation> relation = SubResourceResolver.resolve(parent, child);

            assertTrue(relation.isPresent(), "Should find STRING field with referenceConfig");
            assertEquals("parentId", relation.get().parentRefFieldName());
        }
    }

    // ==================== List Children Tests ====================

    @Nested
    @DisplayName("List Children - GET /{parent}/{parentId}/{child}")
    class ListChildrenTests {

        @Test
        @DisplayName("Should list children with parent ID filter injected")
        void listChildren_injectsParentIdFilter() throws Exception {
            registerParentAndChild();

            QueryResult emptyResult = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(any(CollectionDefinition.class), any(QueryRequest.class)))
                    .thenReturn(emptyResult);

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(any(CollectionDefinition.class), requestCaptor.capture());

            QueryRequest capturedRequest = requestCaptor.getValue();
            assertTrue(capturedRequest.hasFilters(), "Should have filters");

            boolean hasParentFilter = capturedRequest.filters().stream()
                    .anyMatch(f -> "workflowRuleId".equals(f.fieldName())
                            && FilterOperator.EQ == f.operator()
                            && "rule-1".equals(f.value()));
            assertTrue(hasParentFilter, "Should have parent ID filter on workflowRuleId=rule-1");
        }

        @Test
        @DisplayName("Should also inject tenant filter for tenant-scoped child")
        void listChildren_injectsTenantFilter() throws Exception {
            registerParentAndChild();

            QueryResult emptyResult = QueryResult.empty(Pagination.defaults());
            when(queryEngine.executeQuery(any(CollectionDefinition.class), any(QueryRequest.class)))
                    .thenReturn(emptyResult);

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryEngine).executeQuery(any(CollectionDefinition.class), requestCaptor.capture());

            QueryRequest capturedRequest = requestCaptor.getValue();

            boolean hasTenantFilter = capturedRequest.filters().stream()
                    .anyMatch(f -> "tenantId".equals(f.fieldName())
                            && FilterOperator.EQ == f.operator()
                            && "tenant-123".equals(f.value()));
            assertTrue(hasTenantFilter, "Should have tenant ID filter");
        }

        @Test
        @DisplayName("Should return 404 when parent collection not found")
        void listChildren_returns404_whenParentNotFound() throws Exception {
            when(registry.get("unknown-parent")).thenReturn(null);

            mockMvc.perform(get("/api/unknown-parent/id-1/workflow-actions"))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("Should return 404 when child collection not found")
        void listChildren_returns404_whenChildNotFound() throws Exception {
            when(registry.get("workflow-rules")).thenReturn(buildParentCollection());
            when(registry.get("unknown-child")).thenReturn(null);

            mockMvc.perform(get("/api/workflow-rules/rule-1/unknown-child"))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("Should return 404 when no relationship exists between parent and child")
        void listChildren_returns404_whenNoRelationship() throws Exception {
            when(registry.get("workflow-rules")).thenReturn(buildParentCollection());
            when(registry.get("users")).thenReturn(buildUnrelatedCollection());

            mockMvc.perform(get("/api/workflow-rules/rule-1/users"))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("Should return child records in JSON:API format")
        void listChildren_returnsJsonApiFormat() throws Exception {
            registerParentAndChild();

            List<Map<String, Object>> records = List.of(
                    Map.of("id", "action-1", "name", "Send Email", "actionType", "EMAIL",
                            "workflowRuleId", "rule-1"),
                    Map.of("id", "action-2", "name", "Create Task", "actionType", "TASK",
                            "workflowRuleId", "rule-1")
            );
            QueryResult result = new QueryResult(records,
                    new PaginationMetadata(2, 1, 20, 1));
            when(queryEngine.executeQuery(any(CollectionDefinition.class), any(QueryRequest.class)))
                    .thenReturn(result);

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].type").value("workflow-actions"))
                    .andExpect(jsonPath("$.data[0].id").value("action-1"))
                    .andExpect(jsonPath("$.metadata.totalCount").value(2));
        }
    }

    // ==================== Get Child Tests ====================

    @Nested
    @DisplayName("Get Child - GET /{parent}/{parentId}/{child}/{childId}")
    class GetChildTests {

        @Test
        @DisplayName("Should return child record by ID")
        void getChild_returnsRecord() throws Exception {
            registerParentAndChild();

            Map<String, Object> record = new HashMap<>();
            record.put("id", "action-1");
            record.put("name", "Send Email");
            record.put("workflowRuleId", "rule-1");
            when(queryEngine.getById(any(CollectionDefinition.class), eq("action-1")))
                    .thenReturn(Optional.of(record));

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.type").value("workflow-actions"))
                    .andExpect(jsonPath("$.data.id").value("action-1"));
        }

        @Test
        @DisplayName("Should return 404 when child record not found")
        void getChild_returns404_whenNotFound() throws Exception {
            registerParentAndChild();

            when(queryEngine.getById(any(CollectionDefinition.class), eq("nonexistent")))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 404 when the child references a different parent")
        void getChild_returns404_whenUnderAnotherParent() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-2", "tenant-123");

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 404 when the child belongs to another tenant")
        void getChild_returns404_whenOtherTenant() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-1", "tenant-456");

            mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should match a UUID parent id case-insensitively")
        void getChild_matchesUuidParentIgnoringCase() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "tenant-123");

            mockMvc.perform(get("/api/workflow-rules/AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE/workflow-actions/action-1")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());
        }
    }

    // ==================== Create Child Tests ====================

    @Nested
    @DisplayName("Create Child - POST /{parent}/{parentId}/{child}")
    class CreateChildTests {

        @Test
        @DisplayName("Should auto-set parent reference field on create")
        void createChild_autoSetsParentRef() throws Exception {
            registerParentAndChild();

            Map<String, Object> createdRecord = new HashMap<>();
            createdRecord.put("id", "action-new");
            createdRecord.put("name", "Send Email");
            createdRecord.put("workflowRuleId", "rule-1");
            when(queryEngine.create(any(CollectionDefinition.class), any())).thenReturn(createdRecord);

            String body = jsonApiBody(Map.of("name", "Send Email", "actionType", "EMAIL"));

            mockMvc.perform(post("/api/workflow-rules/rule-1/workflow-actions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-123")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isCreated());

            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(any(CollectionDefinition.class), dataCaptor.capture());

            Map<String, Object> capturedData = dataCaptor.getValue();
            assertEquals("rule-1", capturedData.get("workflowRuleId"),
                    "Parent reference field should be auto-set to parentId");
            assertEquals("Send Email", capturedData.get("name"));
            assertEquals("tenant-123", capturedData.get("tenantId"),
                    "tenantId should be injected");
            assertEquals("user-1", capturedData.get("createdBy"),
                    "createdBy should be injected");
        }

        @Test
        @DisplayName("Should return 403 for read-only child collection")
        void createChild_returns403_forReadOnly() throws Exception {
            when(registry.get("workflow-rules")).thenReturn(buildParentCollection());
            when(registry.get("workflow-execution-logs")).thenReturn(buildReadOnlyChildCollection());

            String body = jsonApiBody(Map.of("message", "test"));

            mockMvc.perform(post("/api/workflow-rules/rule-1/workflow-execution-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isForbidden());

            verify(queryEngine, never()).create(any(), any());
        }
    }

    // ==================== Update Child Tests ====================

    @Nested
    @DisplayName("Update Child - PUT/PATCH /{parent}/{parentId}/{child}/{childId}")
    class UpdateChildTests {

        @Test
        @DisplayName("Should update child record via PUT")
        void updateChild_viaPut() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-1", "tenant-123");

            Map<String, Object> updatedRecord = new HashMap<>();
            updatedRecord.put("id", "action-1");
            updatedRecord.put("name", "Updated Action");
            updatedRecord.put("workflowRuleId", "rule-1");
            when(queryEngine.update(any(CollectionDefinition.class), eq("action-1"), any()))
                    .thenReturn(Optional.of(updatedRecord));

            String body = jsonApiBody(Map.of("name", "Updated Action"));

            mockMvc.perform(put("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-123")
                            .header("X-User-Id", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("action-1"));

            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(any(CollectionDefinition.class), eq("action-1"), dataCaptor.capture());

            Map<String, Object> capturedData = dataCaptor.getValue();
            assertEquals("Updated Action", capturedData.get("name"));
            assertEquals("user-1", capturedData.get("updatedBy"));
        }

        @Test
        @DisplayName("Should update child record via PATCH")
        void updateChild_viaPatch() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-1", "tenant-123");

            Map<String, Object> updatedRecord = new HashMap<>();
            updatedRecord.put("id", "action-1");
            updatedRecord.put("name", "Patched Action");
            updatedRecord.put("workflowRuleId", "rule-1");
            when(queryEngine.update(any(CollectionDefinition.class), eq("action-1"), any()))
                    .thenReturn(Optional.of(updatedRecord));

            String body = jsonApiBody(Map.of("name", "Patched Action"));

            mockMvc.perform(patch("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 when child record not found for update")
        void updateChild_returns404_whenNotFound() throws Exception {
            registerParentAndChild();

            when(queryEngine.update(any(CollectionDefinition.class), eq("nonexistent"), any()))
                    .thenReturn(Optional.empty());

            String body = jsonApiBody(Map.of("name", "test"));

            mockMvc.perform(put("/api/workflow-rules/rule-1/workflow-actions/nonexistent")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 404 and write nothing when the child references a different parent")
        void updateChild_returns404_whenUnderAnotherParent() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-2", "tenant-123");

            String body = jsonApiBody(Map.of("name", "Moved"));

            mockMvc.perform(patch("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());

            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("Should return 404 and write nothing when the child belongs to another tenant")
        void updateChild_returns404_whenOtherTenant() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-1", "tenant-456");

            String body = jsonApiBody(Map.of("name", "Renamed"));

            mockMvc.perform(put("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());

            verify(queryEngine, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("Should return 403 for read-only child collection update")
        void updateChild_returns403_forReadOnly() throws Exception {
            when(registry.get("workflow-rules")).thenReturn(buildParentCollection());
            when(registry.get("workflow-execution-logs")).thenReturn(buildReadOnlyChildCollection());

            String body = jsonApiBody(Map.of("message", "test"));

            mockMvc.perform(put("/api/workflow-rules/rule-1/workflow-execution-logs/log-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            verify(queryEngine, never()).update(any(), any(), any());
        }
    }

    // ==================== Delete Child Tests ====================

    @Nested
    @DisplayName("Delete Child - DELETE /{parent}/{parentId}/{child}/{childId}")
    class DeleteChildTests {

        @Test
        @DisplayName("Should delete child record")
        void deleteChild_success() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-1", null);

            when(queryEngine.delete(any(CollectionDefinition.class), eq("action-1")))
                    .thenReturn(true);

            mockMvc.perform(delete("/api/workflow-rules/rule-1/workflow-actions/action-1"))
                    .andExpect(status().isNoContent());

            verify(queryEngine).delete(any(CollectionDefinition.class), eq("action-1"));
        }

        @Test
        @DisplayName("Should return 404 when child record not found for delete")
        void deleteChild_returns404_whenNotFound() throws Exception {
            registerParentAndChild();

            when(queryEngine.delete(any(CollectionDefinition.class), eq("nonexistent")))
                    .thenReturn(false);

            mockMvc.perform(delete("/api/workflow-rules/rule-1/workflow-actions/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 404 and delete nothing when the child references a different parent")
        void deleteChild_returns404_whenUnderAnotherParent() throws Exception {
            registerParentAndChild();
            storedChild("action-1", "rule-2", "tenant-123");

            mockMvc.perform(delete("/api/workflow-rules/rule-1/workflow-actions/action-1")
                            .header("X-Tenant-ID", "tenant-123"))
                    .andExpect(status().isNotFound());

            verify(queryEngine, never()).delete(any(), any());
        }

        @Test
        @DisplayName("Should return 403 for read-only child collection delete")
        void deleteChild_returns403_forReadOnly() throws Exception {
            when(registry.get("workflow-rules")).thenReturn(buildParentCollection());
            when(registry.get("workflow-execution-logs")).thenReturn(buildReadOnlyChildCollection());

            mockMvc.perform(delete("/api/workflow-rules/rule-1/workflow-execution-logs/log-1"))
                    .andExpect(status().isForbidden());

            verify(queryEngine, never()).delete(any(), any());
        }
    }
}
