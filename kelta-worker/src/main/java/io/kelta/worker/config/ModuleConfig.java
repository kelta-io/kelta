package io.kelta.worker.config;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.module.ModuleSigningKeyStore;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.module.ModuleContext;
import io.kelta.runtime.outbound.ModuleHostGuard;
import io.kelta.worker.listener.CollectionDeletionGuardHook;
import io.kelta.worker.module.ExternalModuleInvoker;
import io.kelta.worker.module.ExternalModulePlanner;
import io.kelta.worker.module.ExternalModuleProxy;
import io.kelta.worker.module.ExternalModuleReconcileScheduler;
import io.kelta.worker.module.ExternalModuleRegistry;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ExternalModuleUiBundles;
import io.kelta.worker.module.JdbcModuleSigningKeyStore;
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
import io.kelta.worker.module.ModuleJarService;
import io.kelta.worker.module.ModuleLoadRetryPolicy;
import io.kelta.worker.module.ModuleLoadRetryScheduler;
import io.kelta.worker.module.ModuleMetadataProvisioner;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.ModuleScheduleSync;
import io.kelta.worker.module.ModuleSignatureVerifier;
import io.kelta.worker.module.ModuleTaskService;
import io.kelta.worker.module.RuntimeModuleManager;
import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.service.FieldMaskingService;
import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.TenantSlugResolver;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import io.kelta.runtime.event.PlatformEventPublisher;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring configuration for runtime module loading.
 * <p>
 * Wires the {@link RuntimeModuleManager} with its dependencies and
 * loads all active modules on application startup.
 * <p>
 * When S3 storage is available, modules can be loaded from their JAR files
 * via sandboxed ClassLoaders. Without S3, a module with a JAR fails closed on the pod (refusing
 * handlers) and is retried by {@link ModuleLoadRetryScheduler}; stub handlers exist only under
 * the explicit {@code kelta.modules.stub-mode} opt-in.
 * <p>
 * Enabled by default. Disable with {@code kelta.modules.runtime.enabled=false}.
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "kelta.modules.runtime.enabled", havingValue = "true", matchIfMissing = true)
@Import({ModuleLoadRetryScheduler.class, ExternalModuleReconcileScheduler.class})
public class ModuleConfig {

    private static final Logger log = LoggerFactory.getLogger(ModuleConfig.class);

    @Bean
    public ModuleStore moduleStore(JdbcTemplate jdbcTemplate) {
        return new JdbcModuleStore(jdbcTemplate);
    }

    /**
     * Per-tenant module signing keys — the trust anchors {@code ModuleSignatureVerifier} checks
     * a JAR signature against. Absent this bean the verifier falls back to the platform-wide
     * key alone.
     */
    @Bean
    public ModuleSigningKeyStore moduleSigningKeyStore(JdbcTemplate jdbcTemplate) {
        return new JdbcModuleSigningKeyStore(jdbcTemplate);
    }

    /**
     * Returns a {@link ModuleJarService} backed by S3 when storage is enabled at
     * runtime, otherwise {@code null}. The bean is always defined so AOT
     * processing for native images doesn't exclude it; the runtime decision is
     * based on {@link S3StorageService#isEnabled()}.
     */
    @Bean
    @Nullable
    public ModuleJarService moduleJarService(S3StorageService s3StorageService) {
        if (!s3StorageService.isEnabled()) {
            return null;
        }
        return new ModuleJarService(s3StorageService);
    }

    /**
     * Holds services published by runtime modules for platform code to call -- the direction
     * {@code ModuleContext} does not cover. Always defined: with no module publishing anything it
     * simply resolves empty and every caller takes its no-module default (for entitlements:
     * none -- see {@code ModuleEntitlementService}).
     */
    @Bean
    public ModuleServiceRegistry moduleServiceRegistry() {
        return new ModuleServiceRegistry();
    }

    @Bean
    public RuntimeModuleManager runtimeModuleManager(ModuleStore moduleStore,
                                                       ActionHandlerRegistry actionHandlerRegistry,
                                                       ObjectMapper objectMapper,
                                                       @Nullable ModuleJarService jarService,
                                                       QueryEngine queryEngine,
                                                       CollectionRegistry collectionRegistry,
                                                       @Nullable FormulaEvaluator formulaEvaluator,
                                                       ModuleSignatureVerifier signatureVerifier,
                                                       BeforeSaveHookRegistry beforeSaveHookRegistry,
                                                       ModuleMetadataProvisioner metadataProvisioner,
                                                       @Nullable CredentialResolverPort credentialResolverPort,
                                                       TenantSlugResolver tenantSlugResolver,
                                                       ModuleServiceRegistry moduleServiceRegistry,
                                                       ModuleProvenanceStore provenanceStore,
                                                       ModuleRouteRegistry moduleRouteRegistry,
                                                       @Value("${kelta.modules.stub-mode:false}")
                                                       boolean stubModeEnabled,
                                                       @Value("${kelta.modules.load-retry.initial-backoff-ms:30000}")
                                                       long retryInitialBackoffMs,
                                                       @Value("${kelta.modules.load-retry.max-backoff-ms:600000}")
                                                       long retryMaxBackoffMs,
                                                       @Value("${kelta.modules.load-retry.escalate-after-attempts:8}")
                                                       int retryEscalateAfterAttempts,
                                                       @Nullable MeterRegistry meterRegistry) {
        // Runtime-loaded modules get the same credential bridge the compile-time modules get
        // (FlowConfig wires the identical extension). Without it a module cannot reach the vault
        // at all, so anything talking to an external API — the case runtime modules exist for —
        // would have to carry its secret in plaintext config.
        Map<Class<?>, Object> extensions = new HashMap<>();
        if (credentialResolverPort != null) {
            extensions.put(CredentialResolverPort.class, credentialResolverPort);
            log.info("CredentialResolverPort wired into the runtime module context");
        }

        ModuleContext moduleContext = new ModuleContext(
            queryEngine, collectionRegistry, formulaEvaluator,
            objectMapper, actionHandlerRegistry, extensions);

        if (jarService != null) {
            log.info("Runtime module JAR loading enabled (S3 storage available)");
        } else {
            log.info("Runtime module JAR loading disabled (S3 storage not available, using stub handlers)");
        }

        RuntimeModuleManager manager = new RuntimeModuleManager(moduleStore, actionHandlerRegistry,
            objectMapper, jarService, moduleContext, signatureVerifier, beforeSaveHookRegistry,
            metadataProvisioner, tenantSlugResolver, moduleServiceRegistry);
        manager.setStubModeEnabled(stubModeEnabled);
        manager.setProvenanceStore(provenanceStore);
        manager.setModuleRouteRegistry(moduleRouteRegistry);
        // A load that fails for an infrastructure reason (object storage or database unreachable)
        // fails closed on the pod and is retried with this backoff instead of being quarantined.
        manager.setLoadRetryPolicy(ModuleLoadRetryPolicy.ofMillis(
            retryInitialBackoffMs, retryMaxBackoffMs, retryEscalateAfterAttempts));
        manager.setMeterRegistry(meterRegistry);
        return manager;
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
     * {@code POST /api/modules/self/tasks/{token}} and its {@code /heartbeat}: a module's service
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

    /**
     * Loads all active runtime modules when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            RuntimeModuleManager manager = event.getApplicationContext()
                .getBean(RuntimeModuleManager.class);
            log.info("Loading active runtime modules on startup...");
            manager.loadAllActiveModules();
        } catch (Exception e) {
            log.warn("Could not load runtime modules on startup: {}", e.getMessage());
        }
    }
}
