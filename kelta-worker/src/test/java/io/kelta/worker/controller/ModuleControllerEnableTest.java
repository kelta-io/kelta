package io.kelta.worker.controller;

import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.interceptor.PermissionGatedControllers;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleUninstaller;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module lifecycle writes and their permission gate: {@code POST /api/modules/{moduleId}/enable} is
 * also how an admin retries an install that ended {@code FAILED}, and the response says how it went.
 */
@DisplayName("ModuleController — lifecycle and permission gate")
class ModuleControllerEnableTest {

    private static final String TENANT = "tenant-a";
    private static final String MODULE_ID = "sample-module";
    private static final String ROW_ID = "row-1";
    private static final String BASE_URL = "https://module.example.test";

    private ModuleStore moduleStore;
    private ExternalModuleService externalModules;
    private BootstrapRepository bootstrapRepository;
    private ModuleController controller;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        moduleStore = mock(ModuleStore.class);
        externalModules = mock(ExternalModuleService.class);
        ObjectProvider<ExternalModuleService> externalProvider = mock(ObjectProvider.class);
        when(externalProvider.getIfAvailable()).thenReturn(externalModules);
        ObjectProvider<ModuleUninstaller> uninstallerProvider = mock(ObjectProvider.class);
        when(uninstallerProvider.getIfAvailable()).thenReturn(mock(ModuleUninstaller.class));
        ObjectProvider<ModuleProvenanceStore> provenance = mock(ObjectProvider.class);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        when(permissionResolver.getProfileId(any())).thenReturn("profile-1");
        bootstrapRepository = mock(BootstrapRepository.class);
        grant(true);
        controller = new ModuleController(moduleStore, provenance, permissionResolver, bootstrapRepository,
                externalProvider, uninstallerProvider);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void grant(boolean granted) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "CUSTOMIZE_APPLICATION", "granted", granted)));
    }

    private void installed(TenantModuleData row) {
        when(moduleStore.findByTenant(TENANT)).thenReturn(List.of(row));
        when(moduleStore.findByTenantAndModuleId(TENANT, row.moduleId())).thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("gates every lifecycle write on CUSTOMIZE_APPLICATION; the reads every UI loads stay open")
    void everyHandlerIsPermissionGated() {
        grant(false);

        PermissionGatedControllers.assertEveryHandlerForbidden(controller, new MockHttpServletRequest(),
                "listModules", "getModuleActions", "getProvisionedResources", "getModuleHealth", "getUiBundle");
        verifyNoInteractions(moduleStore, externalModules);
    }

    @Test
    @DisplayName("lifecycle writes need CUSTOMIZE_APPLICATION — /api/modules is a static route with only API_ACCESS")
    void lifecycleWritesRequireCustomizeApplication() throws Exception {
        grant(false);
        installed(row(TenantModuleData.STATUS_ACTIVE));

        mvc.perform(post("/api/modules/install").header("X-Tenant-ID", TENANT)
                        .contentType("application/json")
                        .content("{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"h1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/modules/" + MODULE_ID + "/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/modules/" + MODULE_ID + "/disable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/modules/" + MODULE_ID).header("X-Tenant-ID", TENANT))
                .andExpect(status().isForbidden());

        verifyNoInteractions(externalModules);
    }

    @Test
    @DisplayName("reads stay open to API users — the UI loads module lists for everyone")
    void readsNeedNoManagePermission() throws Exception {
        grant(false);
        when(moduleStore.findByTenant(TENANT)).thenReturn(List.of());

        mvc.perform(get("/api/modules").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("install needs a baseUrl: there is no other kind of module")
    void installWithoutBaseUrlIs400() throws Exception {
        mvc.perform(post("/api/modules/install").header("X-Tenant-ID", TENANT)
                        .contentType("application/json").content("{\"manifest\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].detail").value("baseUrl is required"));

        verify(externalModules, never()).install(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("enabling reports the status the install reached and its last error")
    void enableReportsOutcome() throws Exception {
        installed(row(TenantModuleData.STATUS_FAILED));
        when(externalModules.enable(eq(TENANT), eq(MODULE_ID), any())).thenReturn(row(TenantModuleData.STATUS_ACTIVE));
        when(moduleStore.findLastError(ROW_ID)).thenReturn(lastError(null));

        mvc.perform(post("/api/modules/" + MODULE_ID + "/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("enabled"))
                .andExpect(jsonPath("$.data.attributes.moduleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.attributes.lastError").value(nullValue()));

        verify(externalModules).enable(eq(TENANT), eq(MODULE_ID), any());
    }

    @Test
    @DisplayName("an enable whose module still does not answer reports FAILED and the reason")
    void enableThatFailsSaysSo() throws Exception {
        installed(row(TenantModuleData.STATUS_FAILED));
        when(externalModules.enable(eq(TENANT), eq(MODULE_ID), any())).thenReturn(row(TenantModuleData.STATUS_FAILED));
        when(moduleStore.findLastError(ROW_ID)).thenReturn(lastError("enabled call failed: connect timed out"));

        mvc.perform(post("/api/modules/" + MODULE_ID + "/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.moduleStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.attributes.lastError").value("enabled call failed: connect timed out"))
                .andExpect(jsonPath("$.data.attributes.lastErrorAt").value("2026-10-06T10:00:00Z"));
    }

    @Test
    @DisplayName("an unknown module is 404 for enable, disable and uninstall, and nothing is called")
    void unknownModuleIs404() throws Exception {
        mvc.perform(post("/api/modules/missing/enable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/modules/missing/disable").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/modules/missing").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNotFound());

        verify(externalModules, never()).enable(anyString(), anyString(), any());
        verify(externalModules, never()).disable(anyString(), anyString());
        verify(externalModules, never()).uninstall(any(), anyBoolean());
    }

    @Test
    @DisplayName("the list and health report the install's last error")
    void listAndHealthReportLastError() throws Exception {
        installed(row(TenantModuleData.STATUS_DEGRADED));
        when(moduleStore.findLastError(ROW_ID)).thenReturn(lastError("health probe failed: HTTP 503"));

        mvc.perform(get("/api/modules").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data[0].attributes.baseUrl").value(BASE_URL))
                .andExpect(jsonPath("$.data[0].attributes.lastError").value("health probe failed: HTTP 503"))
                .andExpect(jsonPath("$.data[0].attributes.lastErrorAt").value("2026-10-06T10:00:00Z"));

        mvc.perform(get("/api/modules/" + MODULE_ID + "/health").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.lastError").value("health probe failed: HTTP 503"));
    }

    private static TenantModuleData row(String status) {
        return new TenantModuleData(ROW_ID, TENANT, MODULE_ID, "Sample", "1.0.0", null, BASE_URL, "{}", status,
                "user-1", Instant.now(), Instant.now(), List.of(), BASE_URL, 1, "app-1", "svc-1");
    }

    private static Map<String, Object> lastError(String message) {
        Map<String, Object> d = new HashMap<>();
        d.put("lastError", message);
        d.put("lastErrorAt", message == null ? null : Timestamp.from(Instant.parse("2026-10-06T10:00:00Z")));
        return d;
    }
}
