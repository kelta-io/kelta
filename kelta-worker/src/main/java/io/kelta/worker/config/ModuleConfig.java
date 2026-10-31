package io.kelta.worker.config;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.outbound.ModuleHostGuard;
import io.kelta.worker.listener.CollectionDeletionGuardHook;
import io.kelta.worker.module.ExternalModuleInvoker;
import io.kelta.worker.module.ExternalModulePlanner;
import io.kelta.worker.module.ExternalModuleProxy;
import io.kelta.worker.module.ExternalModuleReconcileScheduler;
import io.kelta.worker.module.ExternalModuleRegistry;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ExternalModuleUiBundles;
import io.kelta.worker.module.ModuleAccessTokenService;
import io.kelta.worker.module.ModuleAsyncTaskStore;
import io.kelta.worker.module.ModuleAssertionSigner;
import io.kelta.worker.module.ModuleHttpClient;
import io.kelta.worker.module.ModuleUninstaller;
import io.kelta.worker.module.PlatformVersion;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.module.JdbcModuleStore;
import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleCredentialService;
import io.kelta.worker.module.ModuleEventDeadLetterStore;
import io.kelta.worker.module.ModuleEventDeliveryListener;
import io.kelta.worker.module.ModuleMetadataProvisioner;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleScheduleSync;
import io.kelta.worker.module.ModuleTaskService;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.service.FieldMaskingService;
import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.TenantSlugResolver;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import io.kelta.runtime.event.PlatformEventPublisher;
import org.springframework.lang.Nullable;


/**
 * Spring configuration for modules: external HTTPS services installed by URL
 * ({@code specs/module-platform/9-external-modules.md}). Wires the module store, the install
 * planner and service, the per-pod endpoint registry and invoker, event delivery, schedules,
 * credential reads and long-running action tasks. No module code runs in the worker: the in-process
 * JAR path was removed in part 9g.
 * <p>
 * Enabled by default. Disable with {@code kelta.modules.runtime.enabled=false}.
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "kelta.modules.runtime.enabled", havingValue = "true", matchIfMissing = true)
@Import(ExternalModuleReconcileScheduler.class)
public class ModuleConfig {

    @Bean
    public ModuleStore moduleStore(JdbcTemplate jdbcTemplate) {
        return new JdbcModuleStore(jdbcTemplate);
    }

    /**
     * Holds the service ports external modules answer for platform code to call (the entitlements
     * port, {@code RemoteEntitlementProvider}). Always defined: with no module answering anything it
     * simply resolves empty and every caller takes its no-module default (for entitlements:
     * none -- see {@code ModuleEntitlementService}).
     */
    @Bean
    public ModuleServiceRegistry moduleServiceRegistry() {
        return new ModuleServiceRegistry();
    }

    /**
     * The module-host guard: module traffic only, never the general outbound guard
     * ({@code kelta.modules.allowed-hosts}, declared in {@code application.yml} as
     * {@code ${KELTA_MODULE_ALLOWED_HOSTS:}}). A malformed entry fails startup.
     */
    @Bean
    public ModuleHostGuard moduleHostGuard(
            @Value("${" + ModuleHostGuard.ALLOWED_HOSTS_PROPERTY + ":}") String allowedHosts) {
        return new ModuleHostGuard(allowedHosts);
    }

    @Bean
    public ModuleHttpClient moduleHttpClient(ModuleHostGuard moduleHostGuard, ModuleAssertionSigner signer,
                                             ObjectMapper objectMapper) {
        return new ModuleHttpClient(moduleHostGuard, signer, objectMapper);
    }

    /**
     * Pins external modules' admin UI bundles in object storage (part 9e). Always defined; with
     * storage off ({@link S3StorageService#isEnabled()} false at run time) a module that ships a UI
     * plans with a blocker instead.
     */
    @Bean
    public ExternalModuleUiBundles externalModuleUiBundles(ModuleHttpClient moduleHttpClient,
                                                           S3StorageService s3StorageService,
                                                           ObjectMapper objectMapper) {
        return new ExternalModuleUiBundles(moduleHttpClient, s3StorageService, objectMapper);
    }

    @Bean
    public ExternalModulePlanner externalModulePlanner(ModuleHttpClient moduleHttpClient, ModuleStore moduleStore,
                                                       ModuleProvenanceStore provenanceStore,
                                                       ModuleMetadataProvisioner metadataProvisioner,
                                                       PlatformVersion platformVersion,
                                                       ModuleAssertionSigner signer, JdbcTemplate jdbcTemplate,
                                                       ObjectMapper objectMapper,
                                                       ExternalModuleUiBundles externalModuleUiBundles) {
        return new ExternalModulePlanner(moduleHttpClient, moduleStore, provenanceStore, metadataProvisioner,
            platformVersion, signer, jdbcTemplate, objectMapper, externalModuleUiBundles);
    }

    @Bean
    public ModuleUninstaller moduleUninstaller(ModuleProvenanceStore provenanceStore,
                                               ModuleMetadataProvisioner metadataProvisioner,
                                               CollectionDeletionGuardHook deletionGuard, QueryEngine queryEngine,
                                               CollectionRegistry collectionRegistry,
                                               TenantSlugResolver tenantSlugResolver, ObjectMapper objectMapper) {
        return new ModuleUninstaller(provenanceStore, metadataProvisioner, deletionGuard, queryEngine,
            collectionRegistry, tenantSlugResolver, objectMapper);
    }

    @Bean
    public ExternalModuleService externalModuleService(ExternalModulePlanner planner, ModuleStore moduleStore,
                                                       ModuleProvenanceStore provenanceStore,
                                                       ModuleMetadataProvisioner metadataProvisioner,
                                                       ModuleServicePrincipalService principals,
                                                       ModuleAccessTokenService tokens,
                                                       ModuleHttpClient moduleHttpClient,
                                                       ModuleConfigEventPublisher eventPublisher,
                                                       TenantSlugResolver tenantSlugResolver,
                                                       UserIdResolver userIdResolver,
                                                       ExternalModuleRegistry externalModuleRegistry,
                                                       ModuleScheduleSync moduleScheduleSync,
                                                       ExternalModuleUiBundles externalModuleUiBundles,
                                                       ObjectMapper objectMapper) {
        ExternalModuleService service = new ExternalModuleService(planner, moduleStore, provenanceStore,
            metadataProvisioner, principals, tokens, moduleHttpClient, eventPublisher, tenantSlugResolver,
            userIdResolver, externalModuleUiBundles, objectMapper);
        service.setInvocation(externalModuleRegistry, moduleScheduleSync);
        return service;
    }

    /**
     * The single path of every call to an external module (part 9d): status gate, per-pod circuit
     * breaker, service-principal token, signed request through {@link ModuleHttpClient}, metrics.
     */
    @Bean
    public ExternalModuleInvoker externalModuleInvoker(ModuleStore moduleStore, ModuleHttpClient moduleHttpClient,
                                                       ModuleAccessTokenService tokens,
                                                       TenantSlugResolver tenantSlugResolver,
                                                       @Nullable MeterRegistry meterRegistry) {
        return new ExternalModuleInvoker(moduleStore, moduleHttpClient, tokens, tenantSlugResolver, meterRegistry);
    }

    /** This pod's registrations of external modules (handlers, hooks, entitlements port), from their rows. */
    @Bean
    public ExternalModuleRegistry externalModuleRegistry(ModuleStore moduleStore,
                                                         ActionHandlerRegistry actionHandlerRegistry,
                                                         BeforeSaveHookRegistry beforeSaveHookRegistry,
                                                         ModuleServiceRegistry moduleServiceRegistry,
                                                         ExternalModuleInvoker externalModuleInvoker,
                                                         JdbcTemplate jdbcTemplate,
                                                         CollectionRegistry collectionRegistry,
                                                         FieldMaskingService fieldMaskingService,
                                                         ObjectMapper objectMapper,
                                                         ModuleAsyncTaskStore moduleAsyncTaskStore) {
        ExternalModuleRegistry registry = new ExternalModuleRegistry(moduleStore, actionHandlerRegistry,
            beforeSaveHookRegistry, moduleServiceRegistry, externalModuleInvoker, jdbcTemplate, collectionRegistry,
            fieldMaskingService, objectMapper);
        registry.setAsyncTasks(moduleAsyncTaskStore);
        return registry;
    }

    /**
     * The tasks of long-running module actions (part 9h, {@code module_async_task}), and where the
     * flow engine reads a parked step's outcome ({@code FlowConfig} hands it to the engine). A
     * task's lifetime is the action's {@code maxDurationSeconds}, capped by
     * {@code kelta.modules.external.async-action-max-duration-seconds}; a value below 1 fails startup.
     */
    @Bean
    public ModuleAsyncTaskStore moduleAsyncTaskStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            @Value("${kelta.modules.external.async-action-max-duration-seconds:"
                + ModuleAsyncTaskStore.DEFAULT_MAX_DURATION_SECONDS + "}") long maxDurationSeconds) {
        return new ModuleAsyncTaskStore(jdbcTemplate, objectMapper, maxDurationSeconds);
    }

    /**
     * {@code POST /api/modules/self/tasks/complete} and {@code .../heartbeat}: a module's service
     * principal completes, or keeps alive, the task of a long-running action.
     */
    @Bean
    public ModuleTaskService moduleTaskService(ModuleStore moduleStore, ModuleServicePrincipalService principals,
                                               ModuleAsyncTaskStore moduleAsyncTaskStore,
                                               ModuleAccessTokenService tokens, FlowEngine flowEngine,
                                               ObjectMapper objectMapper) {
        return new ModuleTaskService(moduleStore, principals, moduleAsyncTaskStore, tokens, flowEngine, objectMapper);
    }

    @Bean
    public ExternalModuleProxy externalModuleProxy(ExternalModuleRegistry externalModuleRegistry,
                                                   ExternalModuleInvoker externalModuleInvoker) {
        return new ExternalModuleProxy(externalModuleRegistry, externalModuleInvoker);
    }

    /**
     * {@code GET /api/modules/self/credentials/{name}}: a module's service principal reads the
     * tenant credentials its approved manifest declares, audited through the credential resolver.
     */
    @Bean
    public ModuleCredentialService moduleCredentialService(ModuleStore moduleStore,
                                                           ModuleServicePrincipalService principals,
                                                           ObjectProvider<CredentialResolverPort> credentialResolverPort,
                                                           ObjectMapper objectMapper) {
        return new ModuleCredentialService(moduleStore, principals, credentialResolverPort, objectMapper);
    }

    @Bean
    public ModuleScheduleSync moduleScheduleSync(ScheduledJobRepository scheduledJobRepository,
                                                 ObjectMapper objectMapper) {
        return new ModuleScheduleSync(scheduledJobRepository, objectMapper);
    }

    @Bean
    public ModuleEventDeadLetterStore moduleEventDeadLetterStore(JdbcTemplate jdbcTemplate) {
        return new ModuleEventDeadLetterStore(jdbcTemplate);
    }

    /**
     * Record events to external modules (part 9e): the {@code worker-module-events} queue-group
     * consumer {@code NatsSubscriptionConfig} registers. A malformed
     * {@code kelta.modules.external.event-backoff} fails startup.
     */
    @Bean
    public ModuleEventDeliveryListener moduleEventDeliveryListener(
            ExternalModuleRegistry externalModuleRegistry, ExternalModuleInvoker externalModuleInvoker,
            ModuleEventDeadLetterStore moduleEventDeadLetterStore, CollectionRegistry collectionRegistry,
            FieldMaskingService fieldMaskingService, ObjectMapper objectMapper,
            @Nullable MeterRegistry meterRegistry,
            @Value("${kelta.modules.external.event-backoff:5s,30s,2m,10m,1h}") String eventBackoff) {
        return new ModuleEventDeliveryListener(externalModuleRegistry, externalModuleInvoker,
            moduleEventDeadLetterStore, collectionRegistry, fieldMaskingService, objectMapper, meterRegistry,
            ModuleEventDeliveryListener.parseBackoff(eventBackoff));
    }

    @Bean
    public ModuleConfigEventPublisher moduleConfigEventPublisher(
            PlatformEventPublisher eventPublisher) {
        return new ModuleConfigEventPublisher(eventPublisher);
    }
}
