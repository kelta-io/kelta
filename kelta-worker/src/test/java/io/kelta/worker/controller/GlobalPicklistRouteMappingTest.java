package io.kelta.worker.controller;

import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.worker.filter.TenantContextFilter;
import io.kelta.worker.service.GlobalPicklistDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GlobalPicklistController} maps {@code DELETE /api/global-picklists/{id}} beside
 * {@code DynamicCollectionRouter}'s all-variable mappings. A controller tested alone cannot see a
 * competing mapping win (concerns.md → Fragile Areas), so the router's shapes are registered here.
 */
@DisplayName("Global picklist delete route vs the generic collection router")
class GlobalPicklistRouteMappingTest {

    private static final String TENANT = "tenant-1";

    /** Stands in for {@code DynamicCollectionRouter}'s mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}/{id}")
        String get() {
            return "router";
        }

        @DeleteMapping("/{collectionName}/{id}")
        String delete() {
            return "router";
        }
    }

    private DynamicCollectionRouter router;
    private GlobalPicklistDeletionService deletionService;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        router = mock(DynamicCollectionRouter.class);
        deletionService = mock(GlobalPicklistDeletionService.class);
        when(deletionService.delete(any(), any()))
                .thenAnswer(inv -> ((Supplier<ResponseEntity<Void>>) inv.getArgument(1)).get());
        when(router.delete(eq("global-picklists"), eq("gp-1"), any()))
                .thenReturn(ResponseEntity.noContent().build());

        mvc = MockMvcBuilders
                .standaloneSetup(new GlobalPicklistController(deletionService, router), new RouterStub())
                .addFilters(new TenantContextFilter())
                .build();
    }

    @Test
    @DisplayName("DELETE /api/global-picklists/{id} reaches the picklist controller, which delegates the picklist delete to the router")
    void deleteReachesPicklistController() throws Exception {
        mvc.perform(delete("/api/global-picklists/gp-1").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNoContent());

        verify(deletionService).delete(eq("gp-1"), any());
        verify(router).delete(eq("global-picklists"), eq("gp-1"), any());
    }

    @Test
    @DisplayName("GET /api/global-picklists/{id} still reaches the router")
    void getStillReachesRouter() throws Exception {
        mvc.perform(get("/api/global-picklists/gp-1").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(content().string("router"));
        verify(deletionService, never()).delete(any(), any());
    }

    @Test
    @DisplayName("DELETE on another collection still reaches the router")
    void otherCollectionDeleteReachesRouter() throws Exception {
        mvc.perform(delete("/api/picklist-values/v-1").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(content().string("router"));
        verify(deletionService, never()).delete(any(), any());
    }
}
