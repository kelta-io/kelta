package io.kelta.worker.module;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExternalModuleRegistry")
class ExternalModuleRegistryTest {

    private static final String MANIFEST = """
            {"manifestVersion": 2, "protocolVersion": 1, "id": "stub", "name": "Stub", "version": "%s",
             "actions": [ %s ],
             "hooks": [ { "collection": "orders", "events": ["beforeCreate"] } ],
             "events": [ { "collection": "orders", "types": ["created", "updated"] } ],
             "services": [ { "port": "entitlements", "cacheTtlSeconds": 0 } ]}
            """;

    private final ModuleStore store = mock(ModuleStore.class);
    private final ExternalModuleInvoker invoker = mock(ExternalModuleInvoker.class);
    private final ActionHandlerRegistry actions = new ActionHandlerRegistry();
    private final BeforeSaveHookRegistry hooks = new BeforeSaveHookRegistry();
    private final ModuleServiceRegistry services = new ModuleServiceRegistry();
    private ExternalModuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExternalModuleRegistry(store, actions, hooks, services, invoker, null, null, null,
                new ObjectMapper());
    }

    private static TenantModuleData row(String status, String version, String... actionKeys) {
        String declared = String.join(",", java.util.Arrays.stream(actionKeys)
                .map(k -> "{\"key\":\"" + k + "\",\"name\":\"" + k + "\"}").toList());
        TenantModuleData base = ExternalModuleFixtures.row(status, Instant.parse("2026-10-30T00:00:00Z"));
        return new TenantModuleData(base.id(), base.tenantId(), base.moduleId(), base.name(), version, null,
                base.sourceUrl(), null, null, null, MANIFEST.formatted(version, declared), status, "admin",
                base.installedAt(), base.updatedAt(), null,
                java.util.Arrays.stream(actionKeys).map(ExternalModuleFixtures::action).toList(), base.baseUrl(), 1,
                "app-1", "svc-1");
    }

    private void stored(TenantModuleData row) {
        when(store.findByTenantAndModuleId(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE))
                .thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("an ACTIVE install registers its actions, hooks and entitlements port")
    void activeRegistersEverything() {
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping"));

        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping"))
                .hasValueSatisfying(h -> assertThat(h).isInstanceOf(RemoteActionHandler.class));
        assertThat(hooks.getHooks(ExternalModuleFixtures.TENANT, "orders"))
                .anySatisfy(h -> assertThat(h).isInstanceOf(RemoteBeforeSaveHook.class));
        assertThat(services.find(ExternalModuleFixtures.TENANT, EntitlementProvider.class))
                .hasValueSatisfying(p -> assertThat(p).isInstanceOf(RemoteEntitlementProvider.class));
        assertThat(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).isPresent();
    }

    @Test
    @DisplayName("a DISABLED install keeps its actions (they refuse) but drops its hooks and port: writes never block")
    void disabledKeepsOnlyActions() {
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        stored(row(TenantModuleData.STATUS_DISABLED, "1.0.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping")).isPresent();
        assertThat(hooks.getHooks(ExternalModuleFixtures.TENANT, "orders"))
                .noneSatisfy(h -> assertThat(h).isInstanceOf(RemoteBeforeSaveHook.class));
        assertThat(services.has(ExternalModuleFixtures.TENANT, EntitlementProvider.class)).isFalse();
        assertThat(registry.callable(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).isFalse();
    }

    @Test
    @DisplayName("an upgrade replaces the hooks (one registration, not two) and drops actions it no longer declares")
    void upgradeReplaces() {
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping", "old"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);
        EntitlementProvider before = services.find(ExternalModuleFixtures.TENANT, EntitlementProvider.class)
                .orElseThrow();

        stored(row(TenantModuleData.STATUS_ACTIVE, "1.1.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:old")).isEmpty();
        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping")).isPresent();
        assertThat(hooks.getHooks(ExternalModuleFixtures.TENANT, "orders")
                .stream().filter(RemoteBeforeSaveHook.class::isInstance)).hasSize(1);
        assertThat(services.find(ExternalModuleFixtures.TENANT, EntitlementProvider.class))
                .as("the port keeps its one registration").containsSame(before);
    }

    @Test
    @DisplayName("an unchanged row re-registers nothing")
    void unchangedIsANoOp() {
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);
        var hook = hooks.getHooks(ExternalModuleFixtures.TENANT, "orders").getFirst();

        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(hooks.getHooks(ExternalModuleFixtures.TENANT, "orders").getFirst()).isSameAs(hook);
    }

    @Test
    @DisplayName("an uninstalled row unregisters everything and forgets the breaker")
    void uninstallUnregisters() {
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        when(store.findByTenantAndModuleId(anyString(), anyString())).thenReturn(Optional.empty());
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping")).isEmpty();
        assertThat(hooks.getHooks(ExternalModuleFixtures.TENANT, "orders")).isEmpty();
        assertThat(services.has(ExternalModuleFixtures.TENANT, EntitlementProvider.class)).isFalse();
        assertThat(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).isEmpty();
        verify(invoker).forget(ExternalModuleFixtures.INSTALL);
    }

    @Test
    @DisplayName("a tenant whose entitlements port is already taken (a JAR module) keeps that provider")
    void portConflictKeepsTheExisting() {
        EntitlementProvider jar = (tenantId, userId) -> MemberEntitlements.EMPTY;
        services.register(ExternalModuleFixtures.TENANT, EntitlementProvider.class, jar);
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping"));

        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(services.find(ExternalModuleFixtures.TENANT, EntitlementProvider.class)).containsSame(jar);
        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping")).isPresent();
    }

    @Test
    @DisplayName("reconcile registers every external row, drops what is gone, and probes a DEGRADED install")
    void reconcile() {
        TenantModuleData degraded = row(TenantModuleData.STATUS_DEGRADED, "1.0.0", "ping");
        when(store.findAllExternal()).thenReturn(List.of(degraded));

        registry.reconcile();

        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping")).isPresent();
        verify(invoker).call(any(), any(), any(), any());

        when(store.findAllExternal()).thenReturn(List.of());
        registry.reconcile();
        assertThat(actions.getHandler(ExternalModuleFixtures.TENANT, "stub:ping")).isEmpty();
    }

    @Test
    @DisplayName("an ACTIVE install with a closed breaker is not probed")
    void healthyIsNotProbed() {
        when(store.findAllExternal()).thenReturn(List.of(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping")));

        registry.reconcile();

        verify(invoker, never()).call(any(), any(), any(), any());
    }

    @Test
    @DisplayName("event subscribers: callable installs of the tenant whose events name the collection and type")
    void eventSubscribers() {
        stored(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);

        assertThat(registry.eventSubscribers(ExternalModuleFixtures.TENANT, "orders", "updated")).hasSize(1);
        assertThat(registry.eventSubscribers(ExternalModuleFixtures.TENANT, "orders", "deleted")).isEmpty();
        assertThat(registry.eventSubscribers(ExternalModuleFixtures.TENANT, "invoices", "created")).isEmpty();
        assertThat(registry.eventSubscribers("tenant-2", "orders", "created")).isEmpty();

        stored(row(TenantModuleData.STATUS_DISABLED, "1.0.0", "ping"));
        registry.refresh(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE);
        assertThat(registry.eventSubscribers(ExternalModuleFixtures.TENANT, "orders", "updated"))
                .as("a disabled install gets no events").isEmpty();
    }

    @Test
    @DisplayName("ensureInitialized runs the first reconcile once, and not at all after one has run")
    void ensureInitialized() {
        when(store.findAllExternal()).thenReturn(List.of(row(TenantModuleData.STATUS_ACTIVE, "1.0.0", "ping")));

        registry.ensureInitialized();
        registry.ensureInitialized();

        verify(store, times(1)).findAllExternal();
        assertThat(registry.eventSubscribers(ExternalModuleFixtures.TENANT, "orders", "created")).hasSize(1);
    }

    @Test
    @DisplayName("a flow step's actor is the run's user when it is an active INTERNAL or PORTAL user, else none")
    void flowActor() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExternalModuleRegistry withDb = new ExternalModuleRegistry(store, actions, hooks, services, invoker, jdbc,
                null, null, new ObjectMapper());
        String sql = "SELECT user_type FROM platform_user WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'";
        when(jdbc.queryForList(eq(sql), eq(String.class), eq("member-1"), eq(ExternalModuleFixtures.TENANT)))
                .thenReturn(List.of("PORTAL"));
        when(jdbc.queryForList(eq(sql), eq(String.class), eq("svc-1"), eq(ExternalModuleFixtures.TENANT)))
                .thenReturn(List.of("SERVICE"));
        when(jdbc.queryForList(eq(sql), eq(String.class), eq("gone-1"), eq(ExternalModuleFixtures.TENANT)))
                .thenReturn(List.of());

        assertThat(withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, "member-1"))
                .isEqualTo(new ModuleAssertionSigner.Actor("member-1", "PORTAL", null));
        assertThat(withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, "svc-1")).as("a service user").isNull();
        assertThat(withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, "gone-1")).as("unknown or inactive").isNull();
    }

    @Test
    @DisplayName("a flow step with no run user has no actor and reads nothing")
    void flowActorWithoutRunUser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExternalModuleRegistry withDb = new ExternalModuleRegistry(store, actions, hooks, services, invoker, jdbc,
                null, null, new ObjectMapper());

        assertThat(withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, null)).isNull();
        assertThat(withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, " ")).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("the run user's type comes from platform_user even on a request thread, and a failed read is a module error")
    void flowActorReadsTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExternalModuleRegistry withDb = new ExternalModuleRegistry(store, actions, hooks, services, invoker, jdbc,
                null, null, new ObjectMapper());
        when(jdbc.queryForList(anyString(), eq(String.class), eq("svc-2"), eq(ExternalModuleFixtures.TENANT)))
                .thenReturn(List.of("SERVICE"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("down-1"), eq(ExternalModuleFixtures.TENANT)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        CallerContext header = new CallerContext("svc-2", CallerContext.UserType.INTERNAL, false, false);
        assertThat(CallerContext.callAs(header,
                () -> withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, "svc-2")))
                .as("a SERVICE user the gateway header called INTERNAL").isNull();
        assertThatThrownBy(() -> withDb.flowActor(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE, "down-1"))
                .isInstanceOf(io.kelta.runtime.workflow.ModuleUnavailableException.class);
    }
}
