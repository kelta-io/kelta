package io.kelta.worker.controller;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleUninstaller;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module-platform 9g removed {@code POST /api/modules/install-jar} and {@code /api/modules/signing-keys}.
 * Neither may reach a handler now. Both are two segments under {@code /api}, where
 * {@code DynamicCollectionRouter} has a {@code GET /{collectionName}/{id}} but no POST, and
 * {@code ModuleController}'s only two-segment POSTs are the literals {@code /install} and {@code /plan}.
 *
 * <p>So a POST is {@code 405}, and a GET falls to the router, which answers {@code 404}: {@code modules}
 * is a static gateway segment ({@code GatewayStaticRoutes}) that {@code ReservedCollectionNameHook}
 * keeps any tenant collection from taking, so the router never resolves it. The router stand-in is
 * registered because the controller alone cannot see its mappings (CLAUDE.md pitfall).
 */
@DisplayName("Removed JAR module routes no longer reach a handler")
class RemovedModuleRoutesMappingTest {

    /**
     * Stands in for {@code DynamicCollectionRouter}'s mappings, method and shape for shape. Like the real
     * router for a collection it cannot resolve, every handler answers 404.
     */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        private static ResponseEntity<String> notFound() {
            return ResponseEntity.status(404).body("router: collection not found");
        }

        @GetMapping("/{collectionName}")
        ResponseEntity<String> list(@PathVariable String collectionName) {
            return notFound();
        }

        @GetMapping("/{collectionName}/{id}")
        ResponseEntity<String> get(@PathVariable String collectionName, @PathVariable String id) {
            return notFound();
        }

        @PostMapping("/{collectionName}")
        ResponseEntity<String> create(@PathVariable String collectionName) {
            return notFound();
        }

        @PutMapping("/{collectionName}/{id}")
        ResponseEntity<String> put(@PathVariable String collectionName, @PathVariable String id) {
            return notFound();
        }

        @PatchMapping("/{collectionName}/{id}")
        ResponseEntity<String> patch(@PathVariable String collectionName, @PathVariable String id) {
            return notFound();
        }

        @DeleteMapping("/{collectionName}/{id}")
        ResponseEntity<String> delete(@PathVariable String collectionName, @PathVariable String id) {
            return notFound();
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        ResponseEntity<String> childList(@PathVariable String parentName, @PathVariable String parentId,
                                         @PathVariable String childName) {
            return notFound();
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        ResponseEntity<String> childGet(@PathVariable String parentName, @PathVariable String parentId,
                                        @PathVariable String childName, @PathVariable String childId) {
            return notFound();
        }

        @PostMapping("/{parentName}/{parentId}/{childName}")
        ResponseEntity<String> childCreate(@PathVariable String parentName, @PathVariable String parentId,
                                           @PathVariable String childName) {
            return notFound();
        }

        @PutMapping("/{parentName}/{parentId}/{childName}/{childId}")
        ResponseEntity<String> childPut(@PathVariable String parentName, @PathVariable String parentId,
                                        @PathVariable String childName, @PathVariable String childId) {
            return notFound();
        }

        @PatchMapping("/{parentName}/{parentId}/{childName}/{childId}")
        ResponseEntity<String> childPatch(@PathVariable String parentName, @PathVariable String parentId,
                                          @PathVariable String childName, @PathVariable String childId) {
            return notFound();
        }

        @DeleteMapping("/{parentName}/{parentId}/{childName}/{childId}")
        ResponseEntity<String> childDelete(@PathVariable String parentName, @PathVariable String parentId,
                                           @PathVariable String childName, @PathVariable String childId) {
            return notFound();
        }
    }

    private ModuleStore moduleStore;
    private ExternalModuleService externalModules;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        moduleStore = mock(ModuleStore.class);
        externalModules = mock(ExternalModuleService.class);
        ObjectProvider<ExternalModuleService> externalProvider = mock(ObjectProvider.class);
        when(externalProvider.getIfAvailable()).thenReturn(externalModules);
        ObjectProvider<ModuleUninstaller> uninstallerProvider = mock(ObjectProvider.class);
        ObjectProvider<ModuleProvenanceStore> provenance = mock(ObjectProvider.class);
        ModuleController controller = new ModuleController(moduleStore, provenance,
                mock(CerbosPermissionResolver.class), mock(BootstrapRepository.class), externalProvider,
                uninstallerProvider);
        mvc = MockMvcBuilders.standaloneSetup(controller, new RouterStub()).build();
    }

    @Test
    @DisplayName("POST /api/modules/install-jar is 405: no POST is mapped at that depth")
    void installJarIsGone() throws Exception {
        mvc.perform(post("/api/modules/install-jar").header("X-Tenant-ID", "tenant-a"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(moduleStore, externalModules);
    }

    @Test
    @DisplayName("POST /api/modules/signing-keys is 405 and GET is the router's 404")
    void signingKeysAreGone() throws Exception {
        mvc.perform(post("/api/modules/signing-keys").header("X-Tenant-ID", "tenant-a")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/modules/signing-keys").header("X-Tenant-ID", "tenant-a"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("router: collection not found"));

        verifyNoInteractions(moduleStore, externalModules);
    }
}
