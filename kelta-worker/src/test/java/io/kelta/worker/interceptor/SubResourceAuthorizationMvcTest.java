package io.kelta.worker.interceptor;

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
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.GlobalExceptionHandler;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The router's child-resource handlers with {@link CerbosSubResourceAuthorizer} wired in, next to
 * a dedicated controller at the same depth: the generic handlers are authorized against the child
 * collection, the dedicated controller is not touched.
 */
@DisplayName("Child-resource handlers authorized against the child collection")
@WithTenant("tenant-1")
class SubResourceAuthorizationMvcTest {

    private static final String EMAIL = "user@example.com";
    private static final String PROFILE = "profile-1";
    private static final String TENANT = "tenant-1";

    private CerbosAuthorizationService authz;
    private QueryEngine queryEngine;
    private MockMvc mockMvc;

    /** Stand-in for a dedicated controller at child depth under a static parent route. */
    @RestController
    static class DedicatedStandIn {
        @GetMapping("/api/flows/{flowId}/flow-executions")
        Map<String, Object> executions(@PathVariable String flowId) {
            return Map.of("data", java.util.List.of(), "meta", Map.of("flowId", flowId));
        }
    }

    @BeforeEach
    void setUp() {
        authz = mock(CerbosAuthorizationService.class);
        queryEngine = mock(QueryEngine.class);
        CollectionRegistry registry = mock(CollectionRegistry.class);
        CollectionLifecycleManager lifecycle = mock(CollectionLifecycleManager.class);
        when(lifecycle.getTenantVisibleCollectionId(anyString(), eq(TENANT)))
                .thenAnswer(inv -> "id-" + inv.getArgument(0));

        when(registry.get("scripts")).thenReturn(system("scripts", null));
        when(registry.get("script-execution-logs")).thenReturn(system("script-execution-logs", "scripts"));
        when(registry.get("flows")).thenReturn(system("flows", null));
        when(registry.get("flow-executions")).thenReturn(system("flow-executions", "flows"));
        when(queryEngine.executeQuery(any(CollectionDefinition.class), any(QueryRequest.class)))
                .thenReturn(QueryResult.empty(Pagination.defaults()));

        DynamicCollectionRouter router = new DynamicCollectionRouter(registry, queryEngine);
        router.setSubResourceAuthorizer(
                new CerbosSubResourceAuthorizer(authz, new CerbosPermissionResolver(), lifecycle, true));
        mockMvc = MockMvcBuilders.standaloneSetup(router, new DedicatedStandIn())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static CollectionDefinition system(String name, String parent) {
        CollectionDefinitionBuilder builder = new CollectionDefinitionBuilder()
                .name(name)
                .displayName(name)
                .addField(FieldDefinition.string("name"))
                .systemCollection(true);
        if (parent != null) {
            builder.addField(new FieldDefinition("parentId", FieldType.STRING, true, false, false,
                    null, null, null, ReferenceConfig.toCollection(parent), null));
        }
        return builder.build();
    }

    private static MockHttpServletRequestBuilder identified(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User-Email", EMAIL)
                .header("X-User-Profile-Id", PROFILE)
                .header("X-Cerbos-Scope", TENANT)
                .header("X-Tenant-ID", TENANT);
    }

    @Test
    @DisplayName("a static-parent child list without read on the child is a 403 envelope")
    void childListDenied() throws Exception {
        mockMvc.perform(identified(get("/api/scripts/s-1/script-execution-logs")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].status").value("403"))
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].detail")
                        .value("Insufficient permissions for read on script-execution-logs"))
                .andExpect(jsonPath("$.errors[0].meta.requestId").exists());

        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("the same list with read on the child is served")
    void childListAllowed() throws Exception {
        when(authz.checkCollectionAccess(EMAIL, PROFILE, TENANT, "id-script-execution-logs", "read"))
                .thenReturn(true);

        mockMvc.perform(identified(get("/api/scripts/s-1/script-execution-logs")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a child create without create on the child writes nothing")
    void childCreateDenied() throws Exception {
        when(authz.checkCollectionAccess(EMAIL, PROFILE, TENANT, "id-script-execution-logs", "read"))
                .thenReturn(true);

        mockMvc.perform(identified(post("/api/scripts/s-1/script-execution-logs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"type\":\"script-execution-logs\",\"attributes\":{\"name\":\"x\"}}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail")
                        .value("Insufficient permissions for create on script-execution-logs"));

        verify(queryEngine, never()).create(any(), any());
    }

    @Test
    @DisplayName("a dedicated controller at child depth is not routed through the authorizer")
    void dedicatedControllerUntouched() throws Exception {
        mockMvc.perform(identified(get("/api/flows/f-1/flow-executions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.flowId").value("f-1"));

        verifyNoInteractions(authz);
        verifyNoInteractions(queryEngine);
    }

    @Test
    @DisplayName("a service call without identity headers reaches the handler")
    void serviceCallUnchecked() throws Exception {
        mockMvc.perform(get("/api/scripts/s-1/script-execution-logs").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk());

        verifyNoInteractions(authz);
    }
}
