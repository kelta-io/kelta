package io.kelta.worker.controller;

import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.ExternalModulePlan;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ExternalModuleUiBundles;
import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleHttpClient;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.ModuleUninstaller;
import io.kelta.worker.module.RuntimeModuleManager;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The external-module endpoints (part 9c): plan, install by URL, upgrade, uninstall plan, and how
 * each failure maps to a status — 409 plan changed, 422 blockers, 502 module unreachable.
 */
@DisplayName("ModuleController — external modules")
class ModuleControllerExternalTest {

    private static final String TENANT = "tenant-a";
    private static final String BASE_URL = "https://billing.example.test";

    private RuntimeModuleManager manager;
    private ExternalModuleService externalModules;
    private ModuleUninstaller uninstaller;
    private ExternalModuleUiBundles uiBundles;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(RuntimeModuleManager.class);
        externalModules = mock(ExternalModuleService.class);
        uninstaller = mock(ModuleUninstaller.class);
        ObjectProvider<ExternalModuleService> externalProvider = mock(ObjectProvider.class);
        when(externalProvider.getIfAvailable()).thenReturn(externalModules);
        ObjectProvider<ModuleUninstaller> uninstallerProvider = mock(ObjectProvider.class);
        when(uninstallerProvider.getIfAvailable()).thenReturn(uninstaller);
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        when(permissionResolver.getProfileId(any())).thenReturn("profile-1");
        BootstrapRepository bootstrapRepository = mock(BootstrapRepository.class);
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "CUSTOMIZE_APPLICATION", "granted", true)));
        ModuleController controller = new ModuleController(manager, mock(ModuleConfigEventPublisher.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), permissionResolver, bootstrapRepository,
                externalProvider, uninstallerProvider);
        uiBundles = mock(ExternalModuleUiBundles.class);
        controller.setExternalModuleUiBundles(uiBundles);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static Map<String, Object> planAttributes(String hash) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("moduleId", "acme-billing");
        attributes.put("mode", ExternalModulePlan.MODE_INSTALL);
        attributes.put("planHash", hash);
        attributes.put("blockers", List.of());
        return attributes;
    }

    private static TenantModuleData external(String status) {
        return new TenantModuleData("row-1", TENANT, "acme-billing", "Billing", "1.0.0", null, BASE_URL,
                null, null, null, "{}", status, "user-1", null, null, null, List.of(), BASE_URL, 1,
                "app-1", "svc-1");
    }

    @Test
    @DisplayName("POST /plan answers the plan document with its planHash")
    void plan() throws Exception {
        when(externalModules.plan(TENANT, BASE_URL, "user-1"))
                .thenReturn(new ExternalModulePlan(planAttributes("h1"), null, "{}", BASE_URL, null));

        mvc.perform(post("/api/modules/plan").header("X-Tenant-ID", TENANT).header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseUrl\":\"" + BASE_URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("module-install-plans"))
                .andExpect(jsonPath("$.data.attributes.planHash").value("h1"));
    }

    @Test
    @DisplayName("POST /plan: a refused URL is 400, an unreachable module 502")
    void planFailures() throws Exception {
        when(externalModules.plan(eq(TENANT), eq("http://10.0.0.5"), anyString()))
                .thenThrow(new IllegalArgumentException("baseUrl refused: private address"));
        when(externalModules.plan(eq(TENANT), eq(BASE_URL), anyString()))
                .thenThrow(new ModuleHttpClient.ModuleCallException(0, "connect timed out"));

        mvc.perform(post("/api/modules/plan").header("X-Tenant-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseUrl\":\"http://10.0.0.5\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/modules/plan").header("X-Tenant-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"baseUrl\":\"" + BASE_URL + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errors[0].code").value("ModuleUnreachable"));
    }

    @Test
    @DisplayName("POST /install with baseUrl: ACTIVE row; stale hash 409 with the fresh plan; blockers 422")
    void install() throws Exception {
        String body = "{\"baseUrl\":\"" + BASE_URL + "\",\"planHash\":\"h1\"}";
        when(externalModules.install(TENANT, BASE_URL, "h1", "user-1"))
                .thenReturn(external(TenantModuleData.STATUS_ACTIVE));
        mvc.perform(post("/api/modules/install").header("X-Tenant-ID", TENANT).header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value(TenantModuleData.STATUS_ACTIVE))
                .andExpect(jsonPath("$.data.attributes.baseUrl").value(BASE_URL));

        doThrow(new ExternalModulePlan.PlanChangedException(planAttributes("h2")))
                .when(externalModules).install(TENANT, BASE_URL, "h1", "user-1");
        mvc.perform(post("/api/modules/install").header("X-Tenant-ID", TENANT).header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("PlanChanged"))
                .andExpect(jsonPath("$.meta.plan.attributes.planHash").value("h2"));

        Map<String, Object> blocker = Map.of("code", "PLATFORM_TOO_OLD", "message", "needs 2.0.0");
        doThrow(new ExternalModulePlan.PlanBlockedException(planAttributes("h1"), List.of(blocker)))
                .when(externalModules).install(TENANT, BASE_URL, "h1", "user-1");
        mvc.perform(post("/api/modules/install").header("X-Tenant-ID", TENANT).header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("PLATFORM_TOO_OLD"))
                .andExpect(jsonPath("$.errors[0].detail").value("needs 2.0.0"))
                .andExpect(jsonPath("$.meta.plan.attributes.planHash").value("h1"));
        verify(manager, never()).installModule(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /{id}/uninstall-plan is for external modules; a JAR module is 409")
    void uninstallPlan() throws Exception {
        when(manager.listModules(TENANT)).thenReturn(List.of(external(TenantModuleData.STATUS_ACTIVE)));
        when(uninstaller.plan(any(), eq(true))).thenReturn(Map.of("moduleId", "acme-billing", "planHash", "u1"));

        mvc.perform(get("/api/modules/acme-billing/uninstall-plan").param("purgeData", "true")
                        .header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("module-uninstall-plans"))
                .andExpect(jsonPath("$.data.attributes.planHash").value("u1"));

        when(manager.listModules(TENANT)).thenReturn(List.of(new TenantModuleData("row-2", TENANT, "legacy",
                "Legacy", "1.0.0", null, "local://upload", "abc", 1L, "io.example.Legacy", "{}",
                TenantModuleData.STATUS_ACTIVE, "user-1", null, null, "s3/key", List.of())));
        mvc.perform(get("/api/modules/legacy/uninstall-plan").header("X-Tenant-ID", TENANT))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE an external module: a stale planHash is 409 and removes nothing; a matching one applies")
    void uninstallExternal() throws Exception {
        TenantModuleData module = external(TenantModuleData.STATUS_ACTIVE);
        when(manager.listModules(TENANT)).thenReturn(List.of(module));
        when(uninstaller.plan(module, false)).thenReturn(Map.of("moduleId", "acme-billing", "planHash", "u1"));

        mvc.perform(delete("/api/modules/acme-billing").param("planHash", "stale").header("X-Tenant-ID", TENANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("PlanChanged"))
                .andExpect(jsonPath("$.meta.plan.type").value("module-uninstall-plans"));
        verify(externalModules, never()).uninstall(any(), anyBoolean());

        when(uninstaller.apply(module, false)).thenReturn(Map.of("removed", List.of(Map.of("naturalKey", "x")),
                "retained", List.of(), "failed", List.of()));
        mvc.perform(delete("/api/modules/acme-billing").param("planHash", "u1").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.removed[0].naturalKey").value("x"));
        verify(externalModules).uninstall(module, false);
        verify(manager, never()).uninstallModule(anyString(), anyString());
    }

    @Test
    @DisplayName("DELETE without a planHash removes the module but keeps everything it provisioned")
    void uninstallKeepsAll() throws Exception {
        TenantModuleData module = external(TenantModuleData.STATUS_ACTIVE);
        when(manager.listModules(TENANT)).thenReturn(List.of(module));
        when(uninstaller.retainedAll(TENANT, "acme-billing"))
                .thenReturn(List.of(Map.of("naturalKey", "billing_plans")));

        mvc.perform(delete("/api/modules/acme-billing").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.retained[0].naturalKey").value("billing_plans"));
        verify(uninstaller, never()).apply(any(), anyBoolean());
    }

    private static TenantModuleData pinned(String status) {
        TenantModuleData row = external(status);
        return new TenantModuleData(row.id(), row.tenantId(), row.moduleId(), row.name(), row.version(), null,
                row.sourceUrl(), null, null, null, row.manifest(), status, row.installedBy(), null, null, null,
                List.of(), row.baseUrl(), 1, "app-1", "svc-1", "f".repeat(64), "modules/key.js");
    }

    @Test
    @DisplayName("GET /{id}/ui-bundle.js serves an external module's pinned bundle from storage, never the module")
    void servesThePinnedUiBundle() throws Exception {
        TenantModuleData degraded = pinned(TenantModuleData.STATUS_DEGRADED);
        when(manager.listModules(TENANT)).thenReturn(List.of(degraded));
        when(uiBundles.read(degraded)).thenReturn(java.util.Optional.of("console.log(1)".getBytes()));
        when(uiBundles.bundlePath(degraded)).thenReturn("/ui/bundle.js");

        mvc.perform(get("/api/modules/acme-billing/ui-bundle.js").header("X-Tenant-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + "f".repeat(64) + "\""))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string("console.log(1)"));
        verify(manager, never()).readUiBundle(anyString(), anyString());

        mvc.perform(get("/api/modules").header("X-Tenant-ID", TENANT))
                .andExpect(jsonPath("$.data[0].attributes.uiBundlePath").value("/ui/bundle.js"))
                .andExpect(jsonPath("$.data[0].attributes.uiBundleSha256").value("f".repeat(64)));
    }

    @Test
    @DisplayName("a disabled install's bundle, or one storage refuses, is 404")
    void refusesUnservableBundles() throws Exception {
        TenantModuleData disabled = pinned(TenantModuleData.STATUS_DISABLED);
        when(manager.listModules(TENANT)).thenReturn(List.of(disabled));

        mvc.perform(get("/api/modules/acme-billing/ui-bundle.js").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNotFound());
        verify(uiBundles, never()).read(any());

        TenantModuleData active = pinned(TenantModuleData.STATUS_ACTIVE);
        when(manager.listModules(TENANT)).thenReturn(List.of(active));
        when(uiBundles.read(active)).thenReturn(java.util.Optional.empty());
        mvc.perform(get("/api/modules/acme-billing/ui-bundle.js").header("X-Tenant-ID", TENANT))
                .andExpect(status().isNotFound());
    }
}
