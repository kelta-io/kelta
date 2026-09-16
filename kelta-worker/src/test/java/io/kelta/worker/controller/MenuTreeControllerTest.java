package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.MenuTreeService;
import io.kelta.worker.service.MenuTreeService.ApplyResult;
import io.kelta.worker.service.MenuTreeService.TreeCounts;
import io.kelta.worker.service.MenuTreeService.TreeError;
import io.kelta.worker.service.MenuTreeService.TreeValidationException;
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
 * {@code /api/ui-menus/{name}/tree} routing + response shapes — see
 * {@code DashboardTreeControllerTest} for why the router stand-in is registered deliberately.
 */
@DisplayName("Menu tree endpoint")
class MenuTreeControllerTest {

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

    private MenuTreeService treeService;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        treeService = mock(MenuTreeService.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);

        when(permissionResolver.getProfileId(any())).thenReturn(PROFILE);
        grant("CUSTOMIZE_APPLICATION");

        mvc = MockMvcBuilders.standaloneSetup(
                new MenuTreeController(treeService, permissionResolver, bootstrapRepository),
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
        when(treeService.readTree("Main")).thenReturn(Map.of(
                "name", "Main",
                "items", List.of(Map.of("label", "Catalog", "children", List.of()))));

        perform(get("/api/ui-menus/Main/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Main"))
                .andExpect(jsonPath("$.items[0].label").value("Catalog"));
    }

    @Test
    @DisplayName("PUT .../tree returns the created/updated/deleted/unchanged diff")
    void putTreeReturnsCounts() throws Exception {
        when(treeService.applyTree(eq("Main"), any())).thenReturn(
                new ApplyResult("menu-1", "Main", new TreeCounts(2, 0, 0, 0)));

        perform(put("/api/ui-menus/Main/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuId").value("menu-1"))
                .andExpect(jsonPath("$.created").value(2));
    }

    @Test
    @DisplayName("A rejected item is a 400 whose source.pointer names the offending position")
    void validationErrorCarriesAPointer() throws Exception {
        when(treeService.applyTree(anyString(), any())).thenThrow(new TreeValidationException(
                List.of(new TreeError("/items/0/label", "'label' is required"))));

        perform(put("/api/ui-menus/Main/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/items/0/label"));
    }

    @Test
    @DisplayName("Without CUSTOMIZE_APPLICATION the tree is forbidden — /api/** only carries API_ACCESS")
    void requiresCustomizeApplication() throws Exception {
        grant("API_ACCESS");

        perform(put("/api/ui-menus/Main/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}"))
                .andExpect(status().isForbidden());
    }
}
