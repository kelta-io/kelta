package io.kelta.worker.config;

import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.module.ModuleSigningKeyStore;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.module.ModuleContext;
import io.kelta.worker.module.JdbcModuleSigningKeyStore;
import io.kelta.worker.module.JdbcModuleStore;
import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleJarService;
import io.kelta.worker.module.ModuleMetadataProvisioner;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.ModuleSignatureVerifier;
import io.kelta.worker.module.RuntimeModuleManager;
import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.TenantSlugResolver;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * via sandboxed ClassLoaders. Without S3, stub handlers are used.
 * <p>
 * Enabled by default. Disable with {@code kelta.modules.runtime.enabled=false}.
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "kelta.modules.runtime.enabled", havingValue = "true", matchIfMissing = true)
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
                                                       boolean stubModeEnabled) {
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
        return manager;
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
