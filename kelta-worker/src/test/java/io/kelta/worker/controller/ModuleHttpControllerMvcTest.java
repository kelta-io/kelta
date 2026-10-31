package io.kelta.worker.controller;

import io.kelta.worker.module.ExternalModuleProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller's own request mapping, which nothing covered.
 *
 * <p>On prod, POST routes dispatched while GET routes returned 404 with the identical mapping,
 * identical registry contents and the module ACTIVE. Dispatch itself resolves both correctly in
 * isolation, so the gap was always the mapping — and it had no test.
 */
@DisplayName("ModuleHttpController mapping")
class ModuleHttpControllerMvcTest {

    private ExternalModuleProxy proxy;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        proxy = mock(ExternalModuleProxy.class);
        when(proxy.route(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new ExternalModuleProxy.RouteOutcome(new ExternalModuleProxy.Forwarded(200,
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)), null, null));
        mvc = MockMvcBuilders.standaloneSetup(controller(proxy)).build();
    }

    private static ModuleHttpController controller(ExternalModuleProxy proxy) {
        ModuleHttpController controller = new ModuleHttpController();
        controller.setExternalModules(proxy, null, null);
        return controller;
    }

    @Test
    @DisplayName("GET reaches the module")
    void getIsMapped() throws Exception {
        mvc.perform(get("/api/modules/sample-module/x/plans")
                        .header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
    }

    @Test
    @DisplayName("POST reaches the module")
    void postIsMapped() throws Exception {
        mvc.perform(post("/api/modules/sample-module/x/checkout-sessions")
                        .header("X-Tenant-ID", "t1")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a path deeper than the spelled-out patterns still reaches the module")
    void deepPathIsMapped() throws Exception {
        mvc.perform(get("/api/modules/sample-module/x/a/b/c/d").header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk());

        verify(proxy).route(eq("t1"), eq("sample-module"), eq("GET"), eq("a/b/c/d"), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("the path handed to the module excludes the platform prefix")
    void moduleReceivesModuleRelativePath() throws Exception {
        mvc.perform(get("/api/modules/sample-module/x/plans").header("X-Tenant-ID", "t1"));

        verify(proxy).route(eq("t1"), eq("sample-module"), eq("GET"), eq("plans"), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("with module support switched off every route is 404")
    void noModuleSupportIs404() throws Exception {
        MockMvc withoutModules = MockMvcBuilders.standaloneSetup(new ModuleHttpController()).build();

        withoutModules.perform(get("/api/modules/sample-module/x/plans").header("X-Tenant-ID", "t1"))
                .andExpect(status().isNotFound());
    }

    /**
     * The regression that cost a night. {@code DynamicCollectionRouter} maps
     * {@code /api/{parentName}/{parentId}/{childName}/{childId}} for GET, which matches a module
     * route exactly, and Spring ranks any pattern containing {@code **} last -- so the old bare
     * {@code /**} mapping lost the GET and the request was dispatched as a record read of a
     * collection called "modules". POST was unaffected because that router has no equally deep POST
     * mapping, which is precisely why testing this controller alone could never catch it.
     *
     * <p>So the competitor is registered alongside it here. Testing the mapping in isolation is
     * what let this ship.
     */
    @Test
    @DisplayName("GET beats the generic nested-collection route that shadowed it on prod")
    void getWinsAgainstNestedCollectionRoute() throws Exception {
        MockMvc withCompetitor = MockMvcBuilders
                .standaloneSetup(controller(proxy), new NestedRouteStub())
                .build();

        withCompetitor.perform(get("/api/modules/sample-module/x/plans")
                        .header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not("nested-collection-router")));
    }

    /** Stands in for {@code DynamicCollectionRouter}'s nested GET mapping, shape for shape. */
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/api")
    static class NestedRouteStub {
        @org.springframework.web.bind.annotation.GetMapping(
                "/{parentName}/{parentId}/{childName}/{childId}")
        String nested() {
            return "nested-collection-router";
        }
    }
}
