package io.kelta.worker.module;

import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.ModuleMetadataProvisioner.ProvisionResult;
import io.kelta.worker.module.ModuleMetadataProvisioner.ProvisionedResource;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.service.ModuleServicePrincipalService.CreateRequest;
import io.kelta.worker.service.ModuleServicePrincipalService.ServicePrincipal;
import io.kelta.worker.service.PackageImportService;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExternalModuleService")
class ExternalModuleServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String BASE_URL = "https://billing.example.test";
    private static final String HASH = "a".repeat(64);
    /** The platform_user id the installer's email resolves to. */
    private static final String INSTALLER_ID = "0b6f7c1e-2d3a-4b5c-8d9e-0f1a2b3c4d5e";

    /** Keeps rows in memory so reloads see what the service wrote. */
    static final class InMemoryModuleStore implements ModuleStore {
        final Map<String, TenantModuleData> rows = new LinkedHashMap<>();
        final Map<String, String> errors = new HashMap<>();
        final List<TenantModuleData.TenantModuleActionData> actions = new ArrayList<>();

        @Override
        public String createModule(TenantModuleData data) {
            rows.put(data.id(), data);
            return data.id();
        }

        @Override
        public void createActions(List<TenantModuleData.TenantModuleActionData> created) {
            actions.addAll(created);
        }

        @Override
        public Optional<TenantModuleData> findByTenantAndModuleId(String tenantId, String moduleId) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.moduleId().equals(moduleId)).findFirst();
        }

        @Override
        public Optional<TenantModuleData> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<TenantModuleData> findByTenant(String tenantId) {
            return rows.values().stream().filter(r -> r.tenantId().equals(tenantId)).toList();
        }

        @Override
        public List<TenantModuleData> findByTenantAndStatus(String tenantId, String status) {
            return findByTenant(tenantId).stream().filter(r -> r.status().equals(status)).toList();
        }

        @Override
        public void updateStatus(String id, String status) {
            TenantModuleData r = rows.get(id);
            rows.put(id, new TenantModuleData(r.id(), r.tenantId(), r.moduleId(), r.name(), r.version(),
                    r.description(), r.sourceUrl(), r.jarChecksum(), r.jarSizeBytes(), r.moduleClass(), r.manifest(),
                    status, r.installedBy(), r.installedAt(), r.updatedAt(), r.s3Key(), r.actions(), r.baseUrl(),
                    r.protocolVersion(), r.connectedAppId(), r.serviceUserId()));
        }

        @Override
        public void recordLoadOutcome(String id, String status, String error) {
            updateStatusWithError(id, status, error);
        }

        @Override
        public void recordLoadError(String id, String error) {
            errors.put(id, error);
        }

        @Override
        public Map<String, Object> findLoadDiagnostics(String id) {
            return Map.of();
        }

        @Override
        public void updateStatusWithError(String id, String status, String error) {
            updateStatus(id, status);
            if (error == null) {
                errors.remove(id);
            } else {
                errors.put(id, error);
            }
        }

        @Override
        public void updateServicePrincipal(String id, String connectedAppId, String serviceUserId) {
            TenantModuleData r = rows.get(id);
            rows.put(id, new TenantModuleData(r.id(), r.tenantId(), r.moduleId(), r.name(), r.version(),
                    r.description(), r.sourceUrl(), r.jarChecksum(), r.jarSizeBytes(), r.moduleClass(), r.manifest(),
                    r.status(), r.installedBy(), r.installedAt(), r.updatedAt(), r.s3Key(), r.actions(), r.baseUrl(),
                    r.protocolVersion(), connectedAppId, serviceUserId));
        }

        @Override
        public void updateExternalManifest(String id, String version, String name, String description,
                                           String manifestJson, int protocolVersion) {
            TenantModuleData r = rows.get(id);
            rows.put(id, new TenantModuleData(r.id(), r.tenantId(), r.moduleId(), name, version, description,
                    r.sourceUrl(), r.jarChecksum(), r.jarSizeBytes(), r.moduleClass(), manifestJson, r.status(),
                    r.installedBy(), r.installedAt(), r.updatedAt(), r.s3Key(), r.actions(), r.baseUrl(),
                    protocolVersion, r.connectedAppId(), r.serviceUserId()));
        }

        @Override
        public void replaceActions(String moduleRowId, List<TenantModuleData.TenantModuleActionData> replaced) {
            actions.removeIf(a -> a.tenantModuleId().equals(moduleRowId));
            actions.addAll(replaced);
        }

        @Override
        public void deleteModule(String id) {
            rows.remove(id);
        }

        @Override
        public List<TenantModuleData> findAllActive() {
            return rows.values().stream().filter(r -> TenantModuleData.STATUS_ACTIVE.equals(r.status())).toList();
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExternalModuleManifest manifest =
            new ModuleManifestParser(mapper).parseExternal(ExternalModulePlannerTest.MANIFEST);
    private final List<Map<String, Object>> provenance = new ArrayList<>();

    private InMemoryModuleStore store;
    private ExternalModulePlanner planner;
    private ModuleProvenanceStore provenanceStore;
    private ModuleMetadataProvisioner provisioner;
    private ModuleServicePrincipalService principals;
    private ModuleAccessTokenService tokens;
    private ModuleHttpClient http;
    private ModuleConfigEventPublisher events;
    private ExternalModuleService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryModuleStore();
        planner = mock(ExternalModulePlanner.class);
        provenanceStore = mock(ModuleProvenanceStore.class);
        when(provenanceStore.findByModule(TENANT, "acme-billing")).thenReturn(provenance);
        provisioner = mock(ModuleMetadataProvisioner.class);
        when(provisioner.provisionPackage(eq(TENANT), eq("acme-billing"), anyMap(), anySet(), any()))
                .thenAnswer(inv -> {
                    // What the provenance store holds after the run: the collection is the module's.
                    provenance.add(Map.of("resource_type", "COLLECTION", "natural_key", "billing_plans",
                            "ownership", ModuleProvenanceStore.OWNERSHIP_CREATED));
                    return new ProvisionResult(List.of(new ProvisionedResource("COLLECTION", "billing_plans",
                            "col-plans", ModuleProvenanceStore.OWNERSHIP_CREATED, "h-plans")), List.of());
                });
        principals = mock(ModuleServicePrincipalService.class);
        when(principals.create(eq(TENANT), any()))
                .thenReturn(new ServicePrincipal("svc-1", "profile-1", "app-1", "client-1"));
        tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("service-token");
        http = mock(ModuleHttpClient.class);
        events = mock(ModuleConfigEventPublisher.class);
        TenantSlugResolver slugs = mock(TenantSlugResolver.class);
        when(slugs.resolveSlug(TENANT)).thenReturn(Optional.of("acme"));
        // Like JdbcUserIdResolver: the user's id, or the identifier itself when nothing matches.
        service = new ExternalModuleService(planner, store, provenanceStore, provisioner, principals, tokens, http,
                events, slugs, (identifier, tenant) -> "user-1".equals(identifier) ? INSTALLER_ID : identifier,
                mapper);
    }

    private ExternalModulePlan plan(String mode, TenantModuleData installed, List<Map<String, Object>> blockers) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("mode", mode);
        attributes.put("planHash", HASH);
        attributes.put("blockers", blockers);
        return new ExternalModulePlan(attributes, manifest, ExternalModulePlannerTest.MANIFEST, BASE_URL, installed);
    }

    private void planned(String mode) {
        when(planner.plan(TENANT, BASE_URL, "user-1")).thenReturn(plan(mode, null, List.of()));
    }

    private TenantModuleData installed() {
        planned(ExternalModulePlan.MODE_INSTALL);
        return service.install(TENANT, BASE_URL, HASH, "user-1");
    }

    @Test
    @DisplayName("install: metadata with provenance, the principal, actions, lifecycle installed, ACTIVE, one event")
    void installHappyPath() {
        TenantModuleData active = installed();

        assertThat(active.status()).isEqualTo(TenantModuleData.STATUS_ACTIVE);
        assertThat(active.baseUrl()).isEqualTo(BASE_URL);
        assertThat(active.sourceUrl()).isEqualTo(BASE_URL);
        assertThat(active.jarChecksum()).isNull();
        assertThat(active.connectedAppId()).isEqualTo("app-1");
        assertThat(active.serviceUserId()).isEqualTo("svc-1");
        assertThat(store.errors).doesNotContainKey(active.id());

        verify(provenanceStore).record(TENANT, "acme-billing", "1.0.0", "COLLECTION", "billing_plans",
                "col-plans", ModuleProvenanceStore.OWNERSHIP_CREATED, "h-plans");
        ArgumentCaptor<CreateRequest> principal = ArgumentCaptor.forClass(CreateRequest.class);
        verify(principals).create(eq(TENANT), principal.capture());
        assertThat(principal.getValue().moduleKey()).isEqualTo("acme-billing");
        assertThat(principal.getValue().ownedCollections()).containsExactly("billing_plans");
        assertThat(principal.getValue().permissions().system()).containsExactly("VIEW_SETUP");
        // created_by is a platform_user foreign key, so the installer's email is resolved to the id.
        assertThat(principal.getValue().createdBy()).isEqualTo(INSTALLER_ID);
        assertThat(store.actions).extracting(TenantModuleData.TenantModuleActionData::actionKey)
                .containsExactlyInAnyOrder("acme-billing:refund", "acme-billing:charge");

        ArgumentCaptor<ModuleHttpClient.LifecycleEvent> event =
                ArgumentCaptor.forClass(ModuleHttpClient.LifecycleEvent.class);
        verify(http).lifecycle(any(), eq("acme"), event.capture(), eq("service-token"));
        assertThat(event.getValue().event()).isEqualTo("installed");
        assertThat(event.getValue().moduleVersion()).isEqualTo("1.0.0");

        InOrder order = inOrder(provisioner, principals, http, events);
        order.verify(provisioner).provisionPackage(eq(TENANT), eq("acme-billing"), anyMap(), anySet(), any());
        order.verify(principals).create(eq(TENANT), any());
        order.verify(http).lifecycle(any(), any(), any(), any());
        order.verify(events).publishEvent(any(), eq(ModuleChangeType.INSTALLED));
    }

    @Test
    @DisplayName("an installer that resolves to no user (system, an unknown email) leaves created_by null")
    void unresolvedInstaller() {
        when(planner.plan(TENANT, BASE_URL, "system")).thenReturn(plan(ExternalModulePlan.MODE_INSTALL, null,
                List.of()));

        service.install(TENANT, BASE_URL, HASH, "system");

        ArgumentCaptor<CreateRequest> principal = ArgumentCaptor.forClass(CreateRequest.class);
        verify(principals).create(eq(TENANT), principal.capture());
        assertThat(principal.getValue().createdBy()).isNull();
        assertThat(store.rows.values()).extracting(TenantModuleData::installedBy).containsExactly("system");
    }

    @Test
    @DisplayName("a stale planHash is PlanChanged, a blocked plan PlanBlocked, a missing one refused; nothing written")
    void refusals() {
        planned(ExternalModulePlan.MODE_INSTALL);
        assertThatThrownBy(() -> service.install(TENANT, BASE_URL, "b".repeat(64), "user-1"))
                .isInstanceOf(ExternalModulePlan.PlanChangedException.class);
        assertThatThrownBy(() -> service.install(TENANT, BASE_URL, " ", "user-1"))
                .isInstanceOf(IllegalArgumentException.class);

        when(planner.plan(TENANT, BASE_URL, "user-1")).thenReturn(plan(ExternalModulePlan.MODE_BLOCKED, null,
                List.of(Map.of("code", ExternalModulePlanner.PLATFORM_TOO_OLD))));
        assertThatThrownBy(() -> service.install(TENANT, BASE_URL, HASH, "user-1"))
                .isInstanceOf(ExternalModulePlan.PlanBlockedException.class);

        assertThat(store.rows).isEmpty();
        verify(provisioner, never()).provisionPackage(any(), any(), anyMap(), anySet(), any());
    }

    @Test
    @DisplayName("a failing lifecycle installed marks the row FAILED with last_error; enable retries to ACTIVE")
    void lifecycleFailureThenRetry() {
        doThrow(new ModuleHttpClient.ModuleCallException(503, "lifecycle answered 503"))
                .when(http).lifecycle(any(), any(), any(), any());

        TenantModuleData failed = installed();

        assertThat(failed.status()).isEqualTo(TenantModuleData.STATUS_FAILED);
        assertThat(store.errors.get(failed.id())).contains("lifecycle 'installed' failed").contains("503");
        verify(events).publishEvent(any(), eq(ModuleChangeType.INSTALLED));

        // The module recovers; enable re-runs every (idempotent) step from the stored manifest.
        org.mockito.Mockito.reset(http);
        TenantModuleData retried = service.enable(TENANT, "acme-billing", "user-1");

        assertThat(retried.status()).isEqualTo(TenantModuleData.STATUS_ACTIVE);
        assertThat(store.errors).doesNotContainKey(retried.id());
        // The principal from the first attempt is reused, not created twice.
        verify(principals).create(eq(TENANT), any());
        verify(planner).plan(TENANT, BASE_URL, "user-1");
        verify(events).publishEvent(any(), eq(ModuleChangeType.ENABLED));
    }

    @Test
    @DisplayName("a failed metadata item stops before the principal is created")
    void metadataFailureStopsEarly() {
        when(provisioner.provisionPackage(eq(TENANT), eq("acme-billing"), anyMap(), anySet(), any()))
                .thenReturn(new ProvisionResult(List.of(), List.of(new PackageImportService.ItemResult(
                        "FIELD", "billing_plans.code", "FAILED", "bad type", null, List.of()))));

        TenantModuleData failed = installed();

        assertThat(failed.status()).isEqualTo(TenantModuleData.STATUS_FAILED);
        assertThat(store.errors.get(failed.id())).contains("billing_plans.code").contains("bad type");
        verify(principals, never()).create(any(), any());
        verify(http, never()).lifecycle(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an already-installed module must be upgraded; NO_OP returns the row unchanged")
    void reinstall() {
        TenantModuleData active = installed();

        when(planner.plan(TENANT, BASE_URL, "user-1"))
                .thenReturn(plan(ExternalModulePlan.MODE_UPGRADE, active, List.of()));
        assertThatThrownBy(() -> service.install(TENANT, BASE_URL, HASH, "user-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/upgrade");

        when(planner.plan(TENANT, BASE_URL, "user-1"))
                .thenReturn(plan(ExternalModulePlan.MODE_NO_OP, active, List.of()));
        assertThat(service.install(TENANT, BASE_URL, HASH, "user-1")).isSameAs(active);
    }

    @Test
    @DisplayName("upgrade rewrites the manifest, regenerates the profile, evicts the token and sends upgraded")
    void upgrade() {
        TenantModuleData active = installed();
        when(planner.planUpgrade(eq(TENANT), any(), eq("user-1")))
                .thenReturn(plan(ExternalModulePlan.MODE_UPGRADE, active, List.of()));
        store.updateExternalManifest(active.id(), "0.9.0", active.name(), null, active.manifest(), 1);

        TenantModuleData upgraded = service.upgrade(TENANT, "acme-billing", HASH, "user-1");

        assertThat(upgraded.status()).isEqualTo(TenantModuleData.STATUS_ACTIVE);
        assertThat(upgraded.version()).isEqualTo("1.0.0");
        verify(principals).updatePermissions(eq(TENANT), eq("svc-1"), any());
        verify(tokens).evict("app-1");
        ArgumentCaptor<ModuleHttpClient.LifecycleEvent> event =
                ArgumentCaptor.forClass(ModuleHttpClient.LifecycleEvent.class);
        verify(http, org.mockito.Mockito.times(2)).lifecycle(any(), any(), event.capture(), any());
        assertThat(event.getValue().event()).isEqualTo("upgraded");
        assertThat(event.getValue().fromVersion()).isEqualTo("0.9.0");
        verify(events).publishEvent(any(), eq(ModuleChangeType.UPGRADED));
    }

    @Test
    @DisplayName("disable then enable: DISABLED, ACTIVE again, best-effort lifecycle both ways")
    void disableEnable() {
        installed();
        doThrow(new ModuleHttpClient.ModuleCallException(500, "down"))
                .when(http).lifecycle(any(), any(), any(), any());

        assertThat(service.disable(TENANT, "acme-billing").status()).isEqualTo(TenantModuleData.STATUS_DISABLED);
        assertThat(service.enable(TENANT, "acme-billing", "user-1").status())
                .isEqualTo(TenantModuleData.STATUS_ACTIVE);
        verify(events).publishEvent(any(), eq(ModuleChangeType.DISABLED));
        verify(events).publishEvent(any(), eq(ModuleChangeType.ENABLED));
    }

    @Test
    @DisplayName("uninstall: lifecycle first (best effort), then principal off, token evicted, row deleted, event")
    void uninstall() {
        TenantModuleData active = installed();
        doThrow(new ModuleHttpClient.ModuleCallException(500, "down"))
                .when(http).lifecycle(any(), any(), any(), any());

        service.uninstall(active, true);

        InOrder order = inOrder(http, principals, tokens, events);
        order.verify(http).lifecycle(eq(active), eq("acme"),
                org.mockito.ArgumentMatchers.argThat(e -> "uninstalled".equals(e.event())
                        && Boolean.TRUE.equals(e.purgeData())), any());
        order.verify(principals).deactivate(TENANT, "svc-1");
        order.verify(tokens).evict("app-1");
        order.verify(events).publishEvent(active, ModuleChangeType.UNINSTALLED);
        assertThat(store.rows).isEmpty();
    }

    @Test
    @DisplayName("a JAR module is refused by the external paths")
    void jarModuleRefused() {
        store.createModule(new TenantModuleData("jar-1", TENANT, "legacy", "Legacy", "1.0.0", null,
                "https://downloads.example.test/legacy.jar", "abc", 10L, "io.example.Legacy", "{}",
                TenantModuleData.STATUS_ACTIVE, "user-1", null, null, "s3/key", List.of()));

        assertThatThrownBy(() -> service.disable(TENANT, "legacy")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.enable(TENANT, "missing", "user-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(http, never()).lifecycle(any(), anyString(), any(), anyString());
    }
}
