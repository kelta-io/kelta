package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.DashboardTreeService;
import io.kelta.worker.service.DashboardTreeService.ApplyResult;
import io.kelta.worker.service.DashboardTreeService.TreeCounts;
import io.kelta.worker.service.DashboardTreeService.TreeError;
import io.kelta.worker.service.DashboardTreeService.TreeValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/dashboards/{name}/tree} routing + response shapes.
 *
 * <p>The router stand-in is registered deliberately: {@code DynamicCollectionRouter} maps an
 * all-variable GET three segments deep ({@code /{parentName}/{parentId}/{childName}}), the same
 * depth as {@code /api/dashboards/{name}/tree} — a controller tested alone can pass while
 * production answers it as a child-record list (see {@code PageLayoutTreeControllerTest}).
 */
@DisplayName("Dashboard tree endpoint")
class DashboardTreeControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PROFILE = "profile-1";

    /** Stands in for {@code DynamicCollectionRouter}'s nested mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class NestedRouteStub {
        @GetMapping("/{collectionName}/{id}")
        String get() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String listChildren() {
            return "router";
        }

        @PutMapping("/{collectionName}/{id}")
        String update() {
            return "router";
        }
    }

    private DashboardTreeService treeService;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        treeService = mock(DashboardTreeService.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);

        when(permissionResolver.getProfileId(any())).thenReturn(PROFILE);
        grant("CUSTOMIZE_APPLICATION");

        mvc = MockMvcBuilders.standaloneSetup(
                new DashboardTreeController(treeService, permissionResolver, bootstrapRepository),
                new NestedRouteStub()).build();
    }

    private void grant(String permission) {
        when(bootstrapRepository.findProfileSystemPermissions(PROFILE)).thenReturn(
                List.of(Map.of("permission_name", permission, "granted", true)));
    }

    private ResultActions perform(RequestBuilder request) {
        return TenantContext.callWithTenant(TENANT, () -> {
            try {
                return mvc.perform(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("GET .../tree reaches the controller, not the generic child-list route")
    void getTreeIsNotSwallowedByTheRouter() throws Exception {
        when(treeService.readTree("Library Overview")).thenReturn(Map.of(
                "name", "Library Overview",
                "components", List.of(Map.of("title", "Total Books", "componentType", "metric"))));

        perform(get("/api/dashboards/Library Overview/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Library Overview"))
                .andExpect(jsonPath("$.components[0].title").value("Total Books"));
    }

    @Test
    @DisplayName("PUT .../tree returns the created/updated/deleted/unchanged diff")
    void putTreeReturnsCounts() throws Exception {
        when(treeService.applyTree(eq("Library Overview"), any())).thenReturn(
                new ApplyResult("dash-1", "Library Overview", new TreeCounts(3, 0, 0, 0)));

        perform(put("/api/dashboards/Library Overview/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"components\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboardId").value("dash-1"))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.unchanged").value(0));
    }

    @Test
    @DisplayName("A rejected component is a 400 whose source.pointer names the offending position")
    void validationErrorCarriesAPointer() throws Exception {
        when(treeService.applyTree(anyString(), any())).thenThrow(new TreeValidationException(
                List.of(new TreeError("/components/0/title", "'title' is required"))));

        perform(put("/api/dashboards/Library Overview/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"components\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/components/0/title"));
    }

    @Test
    @DisplayName("Without CUSTOMIZE_APPLICATION the tree is forbidden — /api/** only carries API_ACCESS")
    void requiresCustomizeApplication() throws Exception {
        grant("API_ACCESS");

        perform(put("/api/dashboards/Library Overview/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"components\":[]}"))
                .andExpect(status().isForbidden());
    }
}
