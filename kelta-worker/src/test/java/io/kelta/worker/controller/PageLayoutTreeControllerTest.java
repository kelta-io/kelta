package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.PageLayoutTreeService;
import io.kelta.worker.service.PageLayoutTreeService.ApplyResult;
import io.kelta.worker.service.PageLayoutTreeService.LayoutNotFoundException;
import io.kelta.worker.service.PageLayoutTreeService.TreeCounts;
import io.kelta.worker.service.PageLayoutTreeService.TreeError;
import io.kelta.worker.service.PageLayoutTreeService.TreeValidationException;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /tree} routing + response shapes.
 *
 * <p>The router stand-in is registered deliberately: {@code DynamicCollectionRouter} maps
 * all-variable GETs three and four segments deep under {@code /api}, so a controller tested
 * alone can pass while production answers {@code /api/page-layouts/{id}/tree} as a child-record
 * read (see {@code concerns.md} → Fragile Areas).
 */
@DisplayName("Page layout tree endpoint")
class PageLayoutTreeControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String PROFILE = "profile-1";

    /** Stands in for {@code DynamicCollectionRouter}'s nested mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class NestedRouteStub {
        @GetMapping("/{collectionName}")
        String list() {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String get() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String listChildren() {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String getChild() {
            return "router";
        }

        @PutMapping("/{collectionName}/{id}")
        String update() {
            return "router";
        }

        @PutMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String updateChild() {
            return "router";
        }
    }

    private PageLayoutTreeService treeService;
    private BootstrapRepository bootstrapRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        treeService = mock(PageLayoutTreeService.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);

        when(permissionResolver.getProfileId(any())).thenReturn(PROFILE);
        grant("CUSTOMIZE_APPLICATION");

        mvc = MockMvcBuilders.standaloneSetup(
                new PageLayoutTreeController(treeService, permissionResolver, bootstrapRepository),
                new NestedRouteStub()).build();
    }

    private void grant(String permission) {
        when(bootstrapRepository.findProfileSystemPermissions(PROFILE)).thenReturn(
                List.of(Map.of("permission_name", permission, "granted", true)));
    }

    /** MockMvc runs on the calling thread, so binding the tenant here reaches the controller. */
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
    @DisplayName("GET .../tree reaches the controller, not the generic child-record route")
    void getTreeIsNotSwallowedByTheRouter() throws Exception {
        when(treeService.readTree("layout-1")).thenReturn(Map.of(
                "collection", "contacts",
                "name", "Contact Detail",
                "sections", List.of(Map.of("heading", "Overview", "columns", 2,
                        "fields", List.of(Map.of("name", "firstName", "column", 0))))));

        perform(get("/api/page-layouts/layout-1/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collection").value("contacts"))
                .andExpect(jsonPath("$.sections[0].fields[0].name").value("firstName"));
    }

    @Test
    @DisplayName("PUT .../tree returns the created/updated/deleted/unchanged diff")
    void putTreeReturnsCounts() throws Exception {
        when(treeService.applyTree(eq("layout-1"), any())).thenReturn(
                new ApplyResult("layout-1", "contacts", "Contact Detail", new TreeCounts(0, 0, 0, 4)));

        perform(put("/api/page-layouts/layout-1/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sections\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutId").value("layout-1"))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.unchanged").value(4));
    }

    @Test
    @DisplayName("PUT by collection + layout name reaches the by-name mapping")
    void putTreeByName() throws Exception {
        when(treeService.applyTreeByName(eq("contacts"), eq("Contact Detail"), any())).thenReturn(
                new ApplyResult("layout-1", "contacts", "Contact Detail", new TreeCounts(4, 0, 0, 0)));

        perform(put("/api/collections/contacts/layouts/Contact Detail/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sections\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(4))
                .andExpect(jsonPath("$.name").value("Contact Detail"));
    }

    @Test
    @DisplayName("A rejected placement is a 400 whose source.pointer names the offending position")
    void validationErrorCarriesAPointer() throws Exception {
        when(treeService.applyTree(anyString(), any())).thenThrow(new TreeValidationException(
                List.of(new TreeError("/sections/0/fields/2/name",
                        "Field 'nope' does not exist on collection 'contacts'"))));

        perform(put("/api/page-layouts/layout-1/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sections\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errors[0].status").value("400"))
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].detail").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].source.pointer")
                        .value("/sections/0/fields/2/name"));
    }

    @Test
    @DisplayName("An unknown layout is a 404 in the JSON:API error envelope")
    void unknownLayoutIsNotFound() throws Exception {
        when(treeService.readTree("nope")).thenThrow(new LayoutNotFoundException("Page layout 'nope' not found"));

        perform(get("/api/page-layouts/nope/tree"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Without CUSTOMIZE_APPLICATION the tree is forbidden — /api/** only carries API_ACCESS")
    void requiresCustomizeApplication() throws Exception {
        grant("API_ACCESS");

        perform(put("/api/page-layouts/layout-1/tree")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sections\":[]}"))
                .andExpect(status().isForbidden());
    }
}
