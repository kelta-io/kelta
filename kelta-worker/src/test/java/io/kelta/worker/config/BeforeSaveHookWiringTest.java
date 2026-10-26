package io.kelta.worker.config;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.credential.CredentialTypeRegistry;
import io.kelta.runtime.embedding.EmbeddingService;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.service.RollupSummaryService;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.worker.repository.ApprovalRepository;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.DelegatedAdminScopeRepository;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.repository.FieldHistoryRepository;
import io.kelta.worker.repository.GovernorLimitsRepository;
import io.kelta.worker.repository.RecordTombstoneRepository;
import io.kelta.worker.repository.RecordVersionRepository;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.service.ApprovalService;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPolicySyncCoalescer;
import io.kelta.worker.service.CerbosPolicySyncService;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.service.DashboardComponentValidator;
import io.kelta.worker.service.FieldMaskingService;
import io.kelta.worker.service.FormulaRecomputeService;
import io.kelta.worker.service.ParticipantShareSupport;
import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.SetupAuditService;
import io.kelta.worker.service.TenantQuotaResolver;
import io.kelta.worker.service.UiPageConfigValidator;
import io.kelta.worker.service.UserInviteService;
import io.kelta.worker.service.VectorMaintenanceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

/**
 * Guards the exact set — and per-collection execution order — of platform {@link BeforeSaveHook}s
 * that end up in the {@link BeforeSaveHookRegistry}.
 *
 * <p>Boots the real {@link FlowConfig} (which also registers the compile-time modules' hooks via
 * {@code ModuleRegistry}), the real hook classes discovered by the same component-scan rule the
 * worker uses, {@link MultiCollectionHookConfig}, the presence-gated integration configs and the
 * {@link BeforeSaveHookRegistrar}, with every collaborator mocked. A hook that is dropped, added,
 * registered twice, or that moves relative to another hook on the same collection fails this
 * test. A missing or ambiguous bean only surfaces at worker boot, which no other unit test starts.
 *
 * <p>The expected lists were captured — and this test first run green — against the hand-wired
 * {@code FlowConfig} / {@code MailboxConfig} / {@code TenantProvisioningConfig}, before hooks
 * became discovered beans. A new platform hook is added to the expected lists here.
 *
 * <p>{@code OidcProviderSecretHook} and {@code OidcDiscoveryHook} are the two hooks added after
 * that capture: they had never been registered before, and were turned on deliberately.
 */
@DisplayName("BeforeSaveHook wiring")
class BeforeSaveHookWiringTest {

    /** Collection-specific hooks per collection key, in execution order (wildcard key "*"). */
    private static final Map<String, List<String>> EXPECTED_DEFAULT = expectedDefault();

    private static Map<String, List<String>> expectedDefault() {
        Map<String, List<String>> m = new TreeMap<>();
        m.put("*", List.of(
                "IdentityCollectionGuardHook",
                "OwnerScopeGuardHook",
                "ApprovalRecordLockHook",
                "RecordTypeEnforcementHook",
                "EmbeddingOnWriteHook",
                "AttachmentCleanupHook",
                "RecordTombstoneHook",
                "FormulaComputeHook",
                "RecordScriptHook",
                "FieldHistoryHook",
                "RecordVersionHook",
                "AuditBeforeSaveHook"));
        m.put("api-specs", List.of("ApiSpecConfigHook"));
        m.put("approval-processes", List.of("ApprovalProcessConfigHook"));
        m.put("chat-conversations", List.of("ChatConversationHook"));
        m.put("chat-messages", List.of("ChatMessageHook"));
        m.put("collections", List.of(
                "CollectionQuotaEnforcementHook",
                "CollectionDeletionGuardHook",
                "CollectionLifecycleHook",
                "CollectionOwnershipValidationHook",
                "ReservedCollectionNameHook",
                "CollectionConfigEventPublisher"));
        m.put("credentials", List.of("CredentialEventPublisher"));
        m.put("dashboard-components", List.of("DashboardComponentConfigHook"));
        m.put("delegated-admin-scopes", List.of("DelegatedAdminScopeValidationHook"));
        m.put("fields", List.of(
                "FieldQuotaEnforcementHook",
                "FieldLifecycleHook",
                "FieldConfigEventPublisher"));
        m.put("flows", List.of(
                "FlowDeletionGuardHook",
                "FlowDefinitionValidationHook",
                "FlowConfigEventPublisher",
                "FlowScheduleSyncHook"));
        m.put("layout-fields", List.of("LayoutFieldRefreshHook"));
        m.put("layout-related-lists", List.of("LayoutRelatedListRefreshHook"));
        m.put("layout-rules", List.of("LayoutRuleRefreshHook"));
        m.put("layout-sections", List.of("LayoutSectionRefreshHook"));
        m.put("list-views", List.of("ListViewConfigHook"));
        m.put("mailbox-templates", List.of("MailboxTemplateGuardHook"));
        m.put("oidc-providers", List.of("OidcProviderSecretHook", "OidcDiscoveryHook"));
        m.put("page-layouts", List.of("PageLayoutConfigEventPublisher"));
        m.put("picklist-values", List.of("PicklistValueRefreshHook"));
        m.put("profile-field-permissions", List.of("FieldPermissionSyncHook"));
        m.put("profile-object-permissions", List.of("ProfilePermissionSyncHook"));
        m.put("profile-system-permissions", List.of("ProfilePermissionSyncHook"));
        m.put("profiles", List.of("ProfileLifecycleHook", "CerbosPolicySyncHook"));
        m.put("telehealth-appointments", List.of("AppointmentHook"));
        m.put("tenants", List.of(
                "TenantLifecycleHook",
                "TenantProvisioningHook",
                "TenantEmailConfigEventPublisher",
                "TenantIpAllowlistConfigEventPublisher"));
        m.put("ui-menu-items", List.of("MenuConfigEventPublisher"));
        m.put("ui-menus", List.of("MenuConfigEventPublisher"));
        m.put("ui-pages", List.of(
                "UIPageSlugHook",
                "UiPageConfigHook",
                "UIPageConfigEventPublisher"));
        m.put("ui-translations", List.of("TranslationConfigEventPublisher"));
        m.put("users", List.of("UserLifecycleHook", "UserSessionRevocationHook"));
        m.put("validation-rules", List.of("ValidationRuleRefreshHook"));
        return m;
    }

    /** With the presence-gated integrations configured: encryption, Svix and Superset. */
    private static Map<String, List<String>> expectedAllIntegrations() {
        Map<String, List<String>> m = new TreeMap<>(EXPECTED_DEFAULT);
        m.put("credentials", List.of("CredentialEncryptionHook", "CredentialEventPublisher"));
        m.put("tenants", List.of(
                "TenantLifecycleHook",
                "TenantProvisioningHook",
                "TenantEmailConfigEventPublisher",
                "TenantIpAllowlistConfigEventPublisher",
                "SvixTenantLifecycleHook",
                "SupersetTenantLifecycleHook"));
        return m;
    }

    /** Component scan restricted to hook classes, using the worker's real (stereotype) rule. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "io.kelta.worker",
            excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                    classes = NotABeforeSaveHook.class))
    static class HookScan {
    }

    static class NotABeforeSaveHook implements TypeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            try {
                Class<?> type = Class.forName(reader.getClassMetadata().getClassName(), false,
                        BeforeSaveHookWiringTest.class.getClassLoader());
                return !BeforeSaveHook.class.isAssignableFrom(type);
            } catch (ClassNotFoundException e) {
                return true;
            }
        }
    }

    private static ApplicationContextRunner runner() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(BeforeSaveHookRegistrar.class, FlowConfig.class,
                        MultiCollectionHookConfig.class, SupersetConfig.class, SvixConfig.class,
                        HookScan.class)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(FlowMetricsConfig.class, () -> new FlowMetricsConfig(new SimpleMeterRegistry()))
                .withBean(RestClient.Builder.class, () -> mock(RestClient.Builder.class, RETURNS_SELF));
        for (Class<?> type : MOCKED) {
            runner = registerMock(runner, type);
        }
        return runner;
    }

    private static <T> ApplicationContextRunner registerMock(ApplicationContextRunner runner, Class<T> type) {
        return runner.withBean(type, () -> mock(type));
    }

    private static final List<Class<?>> MOCKED = List.of(
            JdbcTemplate.class, PlatformTransactionManager.class, FormulaEvaluator.class,
            QueryEngine.class, CollectionRegistry.class, RollupSummaryService.class,
            StorageAdapter.class, EmbeddingService.class, PlatformEventPublisher.class,
            ApprovalService.class, ApprovalRepository.class, BootstrapRepository.class,
            DelegatedAdminScopeRepository.class, EmailRepository.class, FieldHistoryRepository.class,
            GovernorLimitsRepository.class, RecordTombstoneRepository.class,
            RecordVersionRepository.class, ScheduledJobRepository.class,
            CerbosAuthorizationService.class, CerbosPolicySyncCoalescer.class,
            CerbosPolicySyncService.class, CollectionLifecycleManager.class,
            DashboardComponentValidator.class, FieldMaskingService.class,
            FormulaRecomputeService.class, ParticipantShareSupport.class, S3StorageService.class,
            SearchIndexService.class, SetupAuditService.class, TenantQuotaResolver.class,
            UiPageConfigValidator.class, UserInviteService.class, VectorMaintenanceService.class,
            EncryptionService.class, CredentialTypeRegistry.class, OutboundUrlGuard.class,
            io.kelta.worker.service.AuthSessionRevocationClient.class,
            io.kelta.worker.controller.PersonalAccessTokenController.class,
            io.kelta.worker.service.UserAccessRevocationMarker.class,
            io.kelta.worker.service.UserAnonymizationService.class);

    /** Collection key → the hooks registered directly on it (wildcard hooks excluded), in order. */
    private static Map<String, List<String>> registeredHooks(BeforeSaveHookRegistry registry) {
        int wildcardCount = registry.getHooks(BeforeSaveHookRegistry.WILDCARD).size();
        Map<String, List<String>> actual = new TreeMap<>();
        for (String collection : registry.getRegisteredCollections()) {
            List<BeforeSaveHook> hooks = registry.getHooks(collection);
            int own = BeforeSaveHookRegistry.WILDCARD.equals(collection)
                    ? hooks.size()
                    : hooks.size() - wildcardCount;
            List<String> names = new ArrayList<>();
            for (BeforeSaveHook hook : hooks.subList(0, own)) {
                names.add(hook.getClass().getSimpleName());
            }
            actual.put(collection, names);
        }
        return actual;
    }

    private static void assertRegistry(AssertableApplicationContext context,
                                       Map<String, List<String>> expected) {
        assertThat(context).hasNotFailed();
        BeforeSaveHookRegistry registry = context.getBean(BeforeSaveHookRegistry.class);
        assertThat(registeredHooks(registry)).containsExactlyEntriesOf(expected);
        int expectedCount = expected.values().stream().mapToInt(List::size).sum();
        assertThat(registry.getHookCount()).isEqualTo(expectedCount);

        // Every hook bean is registered, exactly once, by identity; the rest are module hooks.
        Map<String, BeforeSaveHook> beans = context.getBeansOfType(BeforeSaveHook.class);
        List<BeforeSaveHook> registered = new ArrayList<>();
        for (String collection : registry.getRegisteredCollections()) {
            List<BeforeSaveHook> hooks = registry.getHooks(collection);
            int own = BeforeSaveHookRegistry.WILDCARD.equals(collection)
                    ? hooks.size()
                    : hooks.size() - registry.getHooks(BeforeSaveHookRegistry.WILDCARD).size();
            registered.addAll(hooks.subList(0, own));
        }
        for (BeforeSaveHook bean : beans.values()) {
            assertThat(registered.stream().filter(h -> h == bean).count())
                    .as("registrations of %s", bean.getClass().getSimpleName())
                    .isEqualTo(1);
        }
        assertThat(registered.size() - beans.size())
                .as("module (non-bean) hooks")
                .isEqualTo(MODULE_HOOKS);
    }

    /** CollectionLifecycleHook, FieldLifecycleHook, ProfileLifecycleHook, TenantLifecycleHook, UserLifecycleHook. */
    private static final int MODULE_HOOKS = 5;

    /**
     * Registration order follows bean discovery, which is unspecified; execution order is the
     * registry's stable sort by {@code getOrder()}. So two hooks on one collection with the same
     * order would run in whatever order discovery happened to produce. Forbid it.
     */
    private static void assertNoOrderTies(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        BeforeSaveHookRegistry registry = context.getBean(BeforeSaveHookRegistry.class);
        int wildcardCount = registry.getHooks(BeforeSaveHookRegistry.WILDCARD).size();
        for (String collection : registry.getRegisteredCollections()) {
            List<BeforeSaveHook> hooks = registry.getHooks(collection);
            int own = BeforeSaveHookRegistry.WILDCARD.equals(collection)
                    ? hooks.size()
                    : hooks.size() - wildcardCount;
            List<String> orders = new ArrayList<>();
            for (BeforeSaveHook hook : hooks.subList(0, own)) {
                orders.add(String.valueOf(hook.getOrder()));
            }
            assertThat(orders)
                    .as("getOrder() values of the hooks on '%s' must be distinct", collection)
                    .doesNotHaveDuplicates();
        }
    }

    private static ApplicationContextRunner withAllIntegrations(ApplicationContextRunner runner) {
        return runner.withPropertyValues(
                "kelta.encryption.key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMTIzNDU=",
                "kelta.svix.auth-token=test-token",
                "kelta.superset.url=http://superset.invalid:8088",
                // Superset's tenant connections default to the worker's own database.
                "spring.datasource.url=jdbc:postgresql://db.invalid:5432/kelta");
    }

    @Test
    @DisplayName("registers exactly the platform hooks, in order, with no integrations configured")
    void registersDefaultHooks() {
        runner().run(context -> assertRegistry(context, EXPECTED_DEFAULT));
    }

    @Test
    @DisplayName("adds the presence-gated encryption, Svix and Superset hooks when configured")
    void registersPresenceGatedHooks() {
        withAllIntegrations(runner()).run(context -> assertRegistry(context, expectedAllIntegrations()));
    }

    @Test
    @DisplayName("no two hooks on one collection share an order, so discovery order cannot matter")
    void hookOrdersAreDistinctPerCollection() {
        runner().run(BeforeSaveHookWiringTest::assertNoOrderTies);
        withAllIntegrations(runner()).run(BeforeSaveHookWiringTest::assertNoOrderTies);
    }

    @Test
    @DisplayName("the OIDC provider hooks register even when no encryption key is configured")
    void oidcHooksRegisterWithoutEncryptionKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(BeforeSaveHookRegistrar.class)
                .withBean(BeforeSaveHookRegistry.class, BeforeSaveHookRegistry::new)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(OutboundUrlGuard.class, () -> new OutboundUrlGuard(""))
                .withBean(io.kelta.worker.listener.OidcProviderSecretHook.class)
                .withBean(io.kelta.worker.listener.OidcDiscoveryHook.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EncryptionService.class);
                    List<String> names = context.getBean(BeforeSaveHookRegistry.class)
                            .getHooks("oidc-providers").stream()
                            .map(hook -> hook.getClass().getSimpleName())
                            .toList();
                    assertThat(names).containsExactly("OidcProviderSecretHook", "OidcDiscoveryHook");
                });
    }

    @Test
    @DisplayName("the registrar registers nothing, and does not fail, when there is no registry")
    void registrarWithoutRegistry() {
        new ApplicationContextRunner()
                .withUserConfiguration(BeforeSaveHookRegistrar.class)
                .withBean(io.kelta.worker.listener.ChatConversationHook.class,
                        () -> new io.kelta.worker.listener.ChatConversationHook(
                                mock(PlatformEventPublisher.class)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BeforeSaveHookRegistry.class);
                });
    }
}
