package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.SubResourceAuthorizer.Access;
import io.kelta.runtime.router.SubResourceAuthorizer.Decision;
import io.kelta.runtime.router.SubResourceAuthorizer.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@link SubResourceAuthorizer} hook on the sub-resource handlers: what each handler hands it,
 * and how each outcome is answered. The behaviour with no authorizer registered is pinned by
 * {@code DynamicCollectionRouterGoldenTest}.
 */
class DynamicCollectionRouterSubResourceAuthorizerTest {

    private static final String BODY = "{\"data\":{\"type\":\"workflow-actions\",\"attributes\":{\"name\":\"x\"}}}";

    private CollectionRegistry registry;
    private QueryEngine queryEngine;
    private MockMvc mockMvc;
    private final List<Access> seen = new ArrayList<>();
    private Decision decision = Decision.allow();

    @BeforeEach
    void setUp() {
        registry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        DynamicCollectionRouter router = new DynamicCollectionRouter(registry, queryEngine);
        router.setSubResourceAuthorizer((access, request) -> {
            seen.add(access);
            return decision;
        });
        mockMvc = MockMvcBuilders.standaloneSetup(router)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(registry.get("workflow-rules")).thenReturn(parent());
        when(registry.get("workflow-actions")).thenReturn(child());
    }

    private static CollectionDefinition parent() {
        return new CollectionDefinitionBuilder()
                .name("workflow-rules")
                .displayName("Workflow Rules")
                .addField(FieldDefinition.requiredString("name"))
                .build();
    }

    private static CollectionDefinition child() {
        return new CollectionDefinitionBuilder()
                .name("workflow-actions")
                .displayName("Workflow Actions")
                .addField(FieldDefinition.requiredString("name"))
                .addField(new FieldDefinition("workflowRuleId", FieldType.MASTER_DETAIL, false, false, false,
                        null, null, null,
                        ReferenceConfig.masterDetail("workflow-rules", "Workflow Rule"), null))
                .build();
    }

    private Access onlyAccess() {
        assertEquals(1, seen.size(), "authorizer is consulted exactly once");
        return seen.get(0);
    }

    @Test
    @DisplayName("list hands the authorizer READ on the child, with no child id")
    void listIsRead() throws Exception {
        when(queryEngine.executeQuery(any(CollectionDefinition.class), any(QueryRequest.class)))
                .thenReturn(QueryResult.empty(Pagination.defaults()));

        mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions"))
                .andExpect(status().isOk());

        Access access = onlyAccess();
        assertEquals("workflow-rules", access.parent().name());
        assertEquals("rule-1", access.parentId());
        assertEquals("workflow-actions", access.child().name());
        assertNull(access.childId());
        assertEquals(Operation.READ, access.operation());
    }

    @Test
    @DisplayName("each handler maps to its operation and child id")
    void operationsPerHandler() throws Exception {
        Map<String, Object> stored = new HashMap<>(Map.of("id", "a-1", "name", "n", "workflowRuleId", "rule-1"));
        when(queryEngine.getById(any(CollectionDefinition.class), eq("a-1"))).thenReturn(Optional.of(stored));
        when(queryEngine.create(any(CollectionDefinition.class), any())).thenReturn(stored);
        when(queryEngine.update(any(CollectionDefinition.class), eq("a-1"), any())).thenReturn(Optional.of(stored));
        when(queryEngine.delete(any(CollectionDefinition.class), eq("a-1"))).thenReturn(true);

        mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions/a-1"));
        mockMvc.perform(post("/api/workflow-rules/rule-1/workflow-actions")
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
        mockMvc.perform(put("/api/workflow-rules/rule-1/workflow-actions/a-1")
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
        mockMvc.perform(patch("/api/workflow-rules/rule-1/workflow-actions/a-1")
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
        mockMvc.perform(delete("/api/workflow-rules/rule-1/workflow-actions/a-1"));

        assertEquals(List.of(Operation.READ, Operation.CREATE, Operation.EDIT, Operation.EDIT, Operation.DELETE),
                seen.stream().map(Access::operation).toList());
        assertEquals(java.util.Arrays.asList("a-1", null, "a-1", "a-1", "a-1"),
                seen.stream().map(Access::childId).toList());
    }

    @Test
    @DisplayName("FORBIDDEN answers 403 in the JSON:API error envelope and touches no record")
    void forbiddenIs403() throws Exception {
        decision = Decision.forbidden("Insufficient permissions for create on workflow-actions");

        mockMvc.perform(post("/api/workflow-rules/rule-1/workflow-actions")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].status").value("403"))
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].detail")
                        .value("Insufficient permissions for create on workflow-actions"))
                .andExpect(jsonPath("$.errors[0].meta.requestId").exists());

        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("FORBIDDEN on delete is a 403 too")
    void forbiddenDelete() throws Exception {
        decision = Decision.forbidden("Insufficient permissions for delete on workflow-actions");

        mockMvc.perform(delete("/api/workflow-rules/rule-1/workflow-actions/a-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("NOT_FOUND answers the handler's own 404 and touches no record")
    void notFoundIs404() throws Exception {
        decision = Decision.notFound();

        mockMvc.perform(get("/api/workflow-rules/rule-1/workflow-actions/a-1"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("an unresolved relationship is a 404 before the authorizer is asked")
    void unresolvedRelationshipSkipsAuthorizer() throws Exception {
        when(registry.get("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/workflow-rules/rule-1/unknown"))
                .andExpect(status().isNotFound());

        assertEquals(List.of(), seen);
    }

    @Test
    @DisplayName("top-level handlers never consult the authorizer")
    void topLevelUntouched() throws Exception {
        when(queryEngine.executeQuery(any(CollectionDefinition.class), any(QueryRequest.class)))
                .thenReturn(QueryResult.empty(Pagination.defaults()));
        decision = Decision.forbidden("never used");

        mockMvc.perform(get("/api/workflow-actions"))
                .andExpect(status().isOk());

        assertEquals(List.of(), seen);
    }
}
