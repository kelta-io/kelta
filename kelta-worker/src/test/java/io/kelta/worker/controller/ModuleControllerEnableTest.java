package io.kelta.worker.controller;

import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.RuntimeModuleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/modules/{moduleId}/enable} is how an admin brings back a {@code QUARANTINED}
 * module, or asks one that is failing closed on a transient error to retry now — without a
 * restart, on every pod.
 */
@DisplayName("ModuleController — enable / re-enable")
class ModuleControllerEnableTest {

    private static final String TENANT = "tenant-a";
    private static final String MODULE_ID = "sample-module";
    private static final String ROW_ID = "row-1";

    private RuntimeModuleManager manager;
    private ModuleConfigEventPublisher publisher;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(RuntimeModuleManager.class);
        publisher = mock(ModuleConfigEventPublisher.class);
        ObjectProvider<ModuleRouteRegistry> routes = mock(ObjectProvider.class);
        ObjectProvider<ModuleProvenanceStore> provenance = mock(ObjectProvider.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ModuleController(manager, publisher, routes, provenance)).build();
    }

    @Test
    @DisplayName("re-enabling a quarantined module reloads it here and broadcasts ENABLED to every pod")
    void reEnableBroadcastsAndReportsOutcome() throws Exception {
        TenantModuleData reloaded = row(TenantModuleData.STATUS_ACTIVE);
        when(manager.listModules(TENANT)).thenReturn(List.of(reloaded));
        when(manager.loadDiagnostics(ROW_ID)).thenReturn(diagnostics(null));

        mvc.perform(post("/api/modules/" + MODULE_ID + "/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("enabled"))
                .andExpect(jsonPath("$.data.attributes.moduleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.attributes.lastError").value(nullValue()))
                .andExpect(jsonPath("$.data.attributes.loadRetrying").value(false));

        verify(manager).enableModule(TENANT, MODULE_ID);
        // The NATS config event is what makes the other pods re-run the load.
        verify(publisher).publishEvent(reloaded, ModuleChangeType.ENABLED);
    }

    @Test
    @DisplayName("a re-enable whose load fails verification again reports QUARANTINED and the reason")
    void reEnableThatRequarantinesSaysSo() throws Exception {
        when(manager.listModules(TENANT)).thenReturn(List.of(row(TenantModuleData.STATUS_QUARANTINED)));
        when(manager.loadDiagnostics(ROW_ID))
                .thenReturn(diagnostics("ModuleSignatureException: checksum mismatch"));

        mvc.perform(post("/api/modules/" + MODULE_ID + "/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.moduleStatus").value("QUARANTINED"))
                .andExpect(jsonPath("$.data.attributes.lastError")
                        .value("ModuleSignatureException: checksum mismatch"))
                .andExpect(jsonPath("$.data.attributes.loadRetrying").value(false));

        // Still broadcast: every pod re-runs its own verification and reaches the same answer.
        verify(publisher).publishEvent(argThat(m -> MODULE_ID.equals(m.moduleId())),
                eq(ModuleChangeType.ENABLED));
    }

    @Test
    @DisplayName("an unknown module is 404 and broadcasts nothing")
    void unknownModuleIs404() throws Exception {
        doThrow(new IllegalArgumentException("not found"))
                .when(manager).enableModule(TENANT, "missing");

        mvc.perform(post("/api/modules/missing/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNotFound());

        verify(publisher, never()).publishEvent(any(), any());
    }

    @Test
    @DisplayName("the list reports load diagnostics, and an ACTIVE module with an error as retrying")
    void listReportsRetryingAndQuarantineReason() throws Exception {
        TenantModuleData retrying = row(TenantModuleData.STATUS_ACTIVE);
        TenantModuleData quarantined = new TenantModuleData("row-2", TENANT, "other-module",
                "Other", "2.0.0", null, "local://upload", "abc", 1L, "x.Other", "{}",
                TenantModuleData.STATUS_QUARANTINED, "user-1", Instant.now(), Instant.now(),
                null, List.of());
        when(manager.listModules(TENANT)).thenReturn(List.of(retrying, quarantined));
        when(manager.loadDiagnostics(ROW_ID))
                .thenReturn(diagnostics("SdkClientException: Connection refused"));
        when(manager.loadDiagnostics("row-2"))
                .thenReturn(diagnostics("no implementation uploaded (manifest-only install)"));

        mvc.perform(get("/api/modules").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].attributes.loadRetrying").value(true))
                .andExpect(jsonPath("$.data[0].attributes.lastError")
                        .value("SdkClientException: Connection refused"))
                .andExpect(jsonPath("$.data[0].attributes.lastErrorAt")
                        .value("2026-10-06T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].attributes.loadAttempts").value(3))
                .andExpect(jsonPath("$.data[1].attributes.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.data[1].attributes.loadRetrying").value(false))
                .andExpect(jsonPath("$.data[1].attributes.lastError")
                        .value("no implementation uploaded (manifest-only install)"));
    }

    private static TenantModuleData row(String status) {
        return new TenantModuleData(ROW_ID, TENANT, MODULE_ID, "Sample", "1.0.0", null,
                "local://upload", "abc", 1L, "x.Sample", "{}", status, "user-1",
                Instant.now(), Instant.now(), "modules/x.jar", List.of());
    }

    private static Map<String, Object> diagnostics(String lastError) {
        Map<String, Object> d = new HashMap<>();
        d.put("lastError", lastError);
        d.put("lastErrorAt", lastError == null ? null
                : Timestamp.from(Instant.parse("2026-10-06T10:00:00Z")));
        d.put("lastLoadedAt", Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        d.put("loadAttempts", 3);
        return d;
    }
}
