package io.kelta.worker.controller;

import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.RuntimeModuleManager;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/modules/{moduleId}/provisioned-resources} answers "what did this module leave in
 * my tenant?" — including for a module that is no longer installed.
 */
@DisplayName("ModuleController — provisioned resources")
class ModuleControllerProvenanceTest {

    private static final String TENANT = "tenant-a";

    private RuntimeModuleManager manager;
    private ModuleProvenanceStore store;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(RuntimeModuleManager.class);
        store = mock(ModuleProvenanceStore.class);
        when(store.findByModule(anyString(), anyString())).thenReturn(List.of());

        ObjectProvider<ModuleRouteRegistry> routes = mock(ObjectProvider.class);
        ObjectProvider<ModuleProvenanceStore> provenance = mock(ObjectProvider.class);
        when(provenance.getIfAvailable()).thenReturn(store);

        mvc = MockMvcBuilders.standaloneSetup(new ModuleController(
                manager, mock(ModuleConfigEventPublisher.class), routes, provenance,
                mock(CerbosPermissionResolver.class), mock(BootstrapRepository.class))).build();
    }

    private static Map<String, Object> row(String id, String type, String key, String ownership,
                                           String hash) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("resource_type", type);
        row.put("natural_key", key);
        row.put("resource_id", "res-" + id);
        row.put("ownership", ownership);
        row.put("content_hash", hash);
        row.put("module_version", "1.0.0");
        row.put("created_at", Timestamp.from(Instant.parse("2026-10-11T00:00:00Z")));
        return row;
    }

    @Test
    @DisplayName("returns the tenant's rows as JSON:API module-provisioned-resources")
    void returnsRows() throws Exception {
        when(store.findByModule(TENANT, "kelta-billing")).thenReturn(List.of(
                row("p1", "COLLECTION", "billing_plans", "CREATED", "a".repeat(64)),
                row("p2", "COLLECTION", "customers", "ADOPTED", null)));

        mvc.perform(get("/api/modules/kelta-billing/provisioned-resources")
                        .header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].type").value("module-provisioned-resources"))
                .andExpect(jsonPath("$.data[0].id").value("p1"))
                .andExpect(jsonPath("$.data[0].attributes.resourceType").value("COLLECTION"))
                .andExpect(jsonPath("$.data[0].attributes.naturalKey").value("billing_plans"))
                .andExpect(jsonPath("$.data[0].attributes.resourceId").value("res-p1"))
                .andExpect(jsonPath("$.data[0].attributes.ownership").value("CREATED"))
                .andExpect(jsonPath("$.data[0].attributes.contentHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.data[0].attributes.moduleVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].attributes.createdAt").value("2026-10-11T00:00:00Z"))
                .andExpect(jsonPath("$.data[1].attributes.ownership").value("ADOPTED"));
    }

    @Test
    @DisplayName("an unknown (or uninstalled) module id is 200 with empty data, not 404")
    void unknownModuleIsEmpty() throws Exception {
        mvc.perform(get("/api/modules/no-such-module/provisioned-resources")
                        .header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        // Answered from provenance alone — a removed module has no tenant_module row.
        verify(manager, never()).listModules(anyString());
    }

    @Test
    @DisplayName("queries the store with the X-Tenant-ID tenant only")
    void scopedToCallerTenant() throws Exception {
        mvc.perform(get("/api/modules/kelta-billing/provisioned-resources")
                        .header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk());

        verify(store).findByModule(TENANT, "kelta-billing");
        verify(store, never()).findByModule(org.mockito.ArgumentMatchers.argThat(t -> !TENANT.equals(t)),
                eq("kelta-billing"));
    }
}
