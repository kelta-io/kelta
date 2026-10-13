package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.module.ModuleContext;
import io.kelta.worker.service.TenantSlugResolver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages the lifecycle of runtime-loaded tenant modules.
 * <p>
 * Handles module installation, enabling, disabling, and uninstalling.
 * Coordinates with the {@link ActionHandlerRegistry} to register/unregister
 * tenant-scoped action handlers.
 * <p>
 * When a {@link ModuleJarService} is available, modules are loaded from their
 * JAR files via a sandboxed {@link SandboxedModuleClassLoader}. If no JAR service
 * is configured (e.g., S3 is disabled), stub handlers are used instead.
 * <p>
 * This class is the facade the rest of the worker calls. The work is done by package-private
 * collaborators it constructs itself (so Spring wiring and tests construct only this class):
 * <ul>
 *   <li>{@link ModuleInstaller} — install: signature gate, JAR upload, module rows, metadata
 *       provisioning and provenance;</li>
 *   <li>{@link ModuleLoader} — load/unload on this pod, and the sole owner of per-pod load state
 *       (loaded modules, classloaders, registered hooks/services, load failures and retries);</li>
 *   <li>{@link ModuleRegistrar} — stateless translation of a module into registry entries
 *       (hooks, services, stub and refusing handlers), used by the loader;</li>
 *   <li>{@link ModuleDispatcher} — routing inbound HTTP routes, webhooks and UI-bundle reads to
 *       a loaded module.</li>
 * </ul>
 * Enable, disable and uninstall stay here: each is a status write followed by a load or unload.
 *
 * @since 1.0.0
 */
public class RuntimeModuleManager {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);

    private final ModuleStore moduleStore;
    private final ModuleManifestParser manifestParser;
    private final ModuleJarService jarService;
    private final ModuleInstaller installer;
    private final ModuleLoader loader;
    private final ModuleDispatcher dispatcher;

    /** Metric: load failures, tagged {@code kind} (transient|permanent) and {@code phase}. */
    static final String METRIC_LOAD_FAILURES = "kelta_worker_module_load_failures";
    /** Metric: modules whose load recovered on a retry after a transient failure. */
    static final String METRIC_LOAD_RECOVERED = "kelta_worker_module_load_recovered";
    /** Metric: modules (plus the startup module list) awaiting a load retry on this pod. */
    static final String METRIC_LOAD_RETRY_PENDING = "kelta_worker_module_load_retry_pending";

    /**
     * Creates a RuntimeModuleManager with JAR loading support.
     */
    public RuntimeModuleManager(ModuleStore moduleStore,
                                 ActionHandlerRegistry actionHandlerRegistry,
                                 ObjectMapper objectMapper,
                                 ModuleJarService jarService,
                                 ModuleContext moduleContext,
                                 ModuleSignatureVerifier signatureVerifier,
                                 BeforeSaveHookRegistry beforeSaveHookRegistry,
                                 ModuleMetadataProvisioner metadataProvisioner,
                                 TenantSlugResolver tenantSlugResolver,
                                 ModuleServiceRegistry moduleServiceRegistry) {
        this.moduleStore = Objects.requireNonNull(moduleStore);
        Objects.requireNonNull(actionHandlerRegistry);
        Objects.requireNonNull(objectMapper);
        this.manifestParser = new ModuleManifestParser(objectMapper);
        this.jarService = jarService;
        this.installer = new ModuleInstaller(moduleStore, manifestParser, jarService,
            signatureVerifier, metadataProvisioner);
        this.loader = new ModuleLoader(moduleStore, actionHandlerRegistry, manifestParser,
            jarService, moduleContext, signatureVerifier, beforeSaveHookRegistry,
            moduleServiceRegistry, new ModuleRegistrar(actionHandlerRegistry,
                beforeSaveHookRegistry, moduleServiceRegistry));
        this.dispatcher = new ModuleDispatcher(moduleStore, actionHandlerRegistry, objectMapper,
            manifestParser, jarService, signatureVerifier, tenantSlugResolver);
    }

    /** Overload without a slug resolver — webhook dispatch then cannot resolve the tenant schema. */
    public RuntimeModuleManager(ModuleStore moduleStore,
                                 ActionHandlerRegistry actionHandlerRegistry,
                                 ObjectMapper objectMapper,
                                 ModuleJarService jarService,
                                 ModuleContext moduleContext,
                                 ModuleSignatureVerifier signatureVerifier,
                                 BeforeSaveHookRegistry beforeSaveHookRegistry,
                                 ModuleMetadataProvisioner metadataProvisioner) {
        this(moduleStore, actionHandlerRegistry, objectMapper, jarService, moduleContext,
            signatureVerifier, beforeSaveHookRegistry, metadataProvisioner, null, null);
    }

    /** Overload without a service registry -- a module then publishes no platform-callable services. */
    public RuntimeModuleManager(ModuleStore moduleStore,
                                 ActionHandlerRegistry actionHandlerRegistry,
                                 ObjectMapper objectMapper,
                                 ModuleJarService jarService,
                                 ModuleContext moduleContext,
                                 ModuleSignatureVerifier signatureVerifier,
                                 BeforeSaveHookRegistry beforeSaveHookRegistry,
                                 ModuleMetadataProvisioner metadataProvisioner,
                                 TenantSlugResolver tenantSlugResolver) {
        this(moduleStore, actionHandlerRegistry, objectMapper, jarService, moduleContext,
            signatureVerifier, beforeSaveHookRegistry, metadataProvisioner, tenantSlugResolver, null);
    }

    /**
     * Creates a RuntimeModuleManager without hook registration or collection provisioning
     * (e.g. in tests that only exercise action handlers).
     */
    public RuntimeModuleManager(ModuleStore moduleStore,
                                 ActionHandlerRegistry actionHandlerRegistry,
                                 ObjectMapper objectMapper,
                                 ModuleJarService jarService,
                                 ModuleContext moduleContext,
                                 ModuleSignatureVerifier signatureVerifier) {
        this(moduleStore, actionHandlerRegistry, objectMapper, jarService, moduleContext,
            signatureVerifier, null, null);
    }

    /**
     * Creates a RuntimeModuleManager with JAR loading but no signature verifier
     * (e.g. in tests). Equivalent to a disabled verifier — installs are not gated.
     */
    public RuntimeModuleManager(ModuleStore moduleStore,
                                 ActionHandlerRegistry actionHandlerRegistry,
                                 ObjectMapper objectMapper,
                                 ModuleJarService jarService,
                                 ModuleContext moduleContext) {
        this(moduleStore, actionHandlerRegistry, objectMapper, jarService, moduleContext, null);
    }

    /**
     * Creates a RuntimeModuleManager without JAR loading support (stub-only mode).
     */
    public RuntimeModuleManager(ModuleStore moduleStore,
                                 ActionHandlerRegistry actionHandlerRegistry,
                                 ObjectMapper objectMapper) {
        this(moduleStore, actionHandlerRegistry, objectMapper, null, null, null);
    }

    /**
     * Installs a module for a tenant.
     * Parses the manifest, persists metadata, and creates action records.
     * See {@link ModuleInstaller#installModule}.
     */
    public TenantModuleData installModule(String tenantId, String manifestJson,
                                           String sourceUrl, String checksum,
                                           Long jarSizeBytes, String installedBy) {
        return installer.installModule(tenantId, manifestJson, sourceUrl, checksum,
            jarSizeBytes, installedBy);
    }

    /**
     * Installs a module with its JAR file.
     * Uploads the JAR to S3 and persists the S3 key.
     *
     * @param tenantId     the tenant ID
     * @param manifestJson the module manifest JSON
     * @param jarBytes     the module JAR file bytes
     * @param installedBy  the user who installed the module
     * @return the persisted module data
     */
    public TenantModuleData installModuleWithJar(String tenantId, String manifestJson,
                                                   byte[] jarBytes, String installedBy) {
        return installModuleWithJar(tenantId, manifestJson, jarBytes, installedBy, null);
    }

    /**
     * Installs a module with its JAR file, verifying the JAR's publisher signature first.
     * See {@link ModuleInstaller#installModuleWithJar}.
     *
     * @throws ModuleSignatureException if signature verification fails
     */
    public TenantModuleData installModuleWithJar(String tenantId, String manifestJson,
                                                   byte[] jarBytes, String installedBy,
                                                   String signatureBase64) {
        return installer.installModuleWithJar(tenantId, manifestJson, jarBytes, installedBy,
            signatureBase64);
    }

    /**
     * Enables a module, registering its action handlers.
     *
     * @param tenantId the tenant ID
     * @param moduleId the module identifier
     */
    public void enableModule(String tenantId, String moduleId) {
        TenantModuleData module = moduleStore.findByTenantAndModuleId(tenantId, moduleId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Module '" + moduleId + "' not found for tenant " + tenantId));

        if (TenantModuleData.STATUS_ACTIVE.equals(module.status())) {
            // Already ACTIVE, but this pod may be failing closed and waiting out a transient-
            // failure backoff. An enable is the admin asking for another try now, so re-run a
            // failed load here; a healthy load is left alone (ensureLoaded is a no-op for it).
            // Other pods get the same request from the ENABLED event the controller publishes.
            log.debug("Module '{}' already active for tenant {}; retrying a failed load on this "
                + "pod if there is one", moduleId, tenantId);
            ensureLoaded(tenantId, module);
            return;
        }

        moduleStore.updateStatus(module.id(), TenantModuleData.STATUS_ACTIVE);
        // Re-enabling a QUARANTINED module is how an admin asks for another try, so a failed load
        // on this pod must not satisfy the "already loaded" short-circuit.
        ensureLoaded(tenantId, module);
        log.info("Enabled module '{}' for tenant {}", moduleId, tenantId);
    }

    /**
     * Loads a module, first discarding a failed attempt on this pod so the load actually re-runs.
     * See {@link ModuleLoader#ensureLoaded}.
     */
    public void ensureLoaded(String tenantId, TenantModuleData module) {
        loader.ensureLoaded(tenantId, module);
    }

    /**
     * Disables a module, unregistering its action handlers.
     *
     * @param tenantId the tenant ID
     * @param moduleId the module identifier
     */
    public void disableModule(String tenantId, String moduleId) {
        TenantModuleData module = moduleStore.findByTenantAndModuleId(tenantId, moduleId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Module '" + moduleId + "' not found for tenant " + tenantId));

        moduleStore.updateStatus(module.id(), TenantModuleData.STATUS_DISABLED);
        unloadModule(tenantId, module);
        log.info("Disabled module '{}' for tenant {}", moduleId, tenantId);
    }

    /**
     * Uninstalls a module entirely.
     *
     * @param tenantId the tenant ID
     * @param moduleId the module identifier
     */
    public void uninstallModule(String tenantId, String moduleId) {
        TenantModuleData module = moduleStore.findByTenantAndModuleId(tenantId, moduleId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Module '" + moduleId + "' not found for tenant " + tenantId));

        unloadModule(tenantId, module);

        // Clean up S3 JAR if present
        if (module.s3Key() != null && jarService != null) {
            try {
                jarService.deleteJar(module.s3Key());
            } catch (Exception e) {
                log.warn("Failed to delete JAR from S3 for module '{}': {}",
                    moduleId, e.getMessage());
            }
        }

        moduleStore.deleteModule(module.id());
        log.info("Uninstalled module '{}' from tenant {}", moduleId, tenantId);
    }

    /**
     * Loads a module's handlers into the registry (called on enable or pod startup).
     * Idempotent — no-op if already loaded. See {@link ModuleLoader#loadModule}.
     */
    public void loadModule(String tenantId, TenantModuleData module) {
        loader.loadModule(tenantId, module);
    }

    /**
     * Parses a stored module manifest. Exposed so callers can read declarative fields
     * (e.g. {@code uiBundlePath}) without keeping their own parser instance.
     *
     * @throws ModuleManifestParser.ModuleManifestException if the manifest is unreadable
     */
    public ModuleManifest parseManifest(String manifestJson) {
        return manifestParser.parse(manifestJson);
    }

    /**
     * Dispatches an authenticated HTTP request to the module handler its manifest routes it to.
     * See {@link ModuleDispatcher#dispatchRoute}.
     *
     * @return the handler's result, or empty when the module is not ACTIVE or serves no such route
     */
    public Optional<ActionResult> dispatchRoute(String tenantId, String moduleId, String userId,
                                                String method, String path,
                                                Map<String, Object> query, String rawBody) {
        return dispatcher.dispatchRoute(tenantId, moduleId, userId, method, path, query, rawBody);
    }

    /** Injected via setter so the existing constructor overloads stay as they are. */
    public void setModuleRouteRegistry(ModuleRouteRegistry moduleRouteRegistry) {
        // The loader registers and removes a module's routes; the dispatcher resolves them.
        loader.setModuleRouteRegistry(moduleRouteRegistry);
        dispatcher.setModuleRouteRegistry(moduleRouteRegistry);
    }

    /**
     * Dispatches a raw inbound webhook to the {@code webhookHandlerKey} the module's manifest
     * names. The platform authenticates nothing here — the handler owns its own trust anchor.
     * See {@link ModuleDispatcher#dispatchWebhook}.
     *
     * @return the handler's result, or empty if nothing was dispatched
     */
    public Optional<ActionResult> dispatchWebhook(String tenantId, String moduleId,
                                                  String rawBody, Map<String, String> headers) {
        return dispatcher.dispatchWebhook(tenantId, moduleId, rawBody, headers);
    }

    /**
     * Reads a module's UI bundle from its verified JAR. See {@link ModuleDispatcher#readUiBundle}.
     */
    public Optional<byte[]> readUiBundle(String tenantId, String moduleId) {
        return dispatcher.readUiBundle(tenantId, moduleId);
    }

    /**
     * Unloads a module's handlers from the registry.
     * Idempotent — no-op if not loaded.
     * See {@link ModuleLoader#unloadModule(String, TenantModuleData)}.
     */
    public void unloadModule(String tenantId, TenantModuleData module) {
        loader.unloadModule(tenantId, module);
    }

    /**
     * Unloads a module's handlers using only its identifier, without reading the module row.
     * Idempotent — no-op if not loaded. See {@link ModuleLoader#unloadModule(String, String)}.
     */
    public void unloadModule(String tenantId, String moduleId) {
        loader.unloadModule(tenantId, moduleId);
    }

    /**
     * Loads all active modules on pod startup. See {@link ModuleLoader#loadAllActiveModules}.
     */
    public void loadAllActiveModules() {
        loader.loadAllActiveModules();
    }

    /**
     * Re-attempts loads that failed on this pod for an infrastructure reason and are now due.
     * Called on a fixed delay by {@link ModuleLoadRetryScheduler}.
     * See {@link ModuleLoader#retryPendingLoads}.
     */
    public void retryPendingLoads() {
        loader.retryPendingLoads();
    }

    /** What this pod's last load of a module produced. */
    public enum PodLoadState {
        /** Loaded and running (or stub handlers under explicit stub mode). */
        LOADED,
        /** Failed for an infrastructure reason; refusing handlers registered, retry scheduled. */
        RETRYING,
        /** Failed and not being retried — quarantined, or its row stopped being ACTIVE. */
        FAILED,
        /** Not loaded on this pod. */
        NOT_LOADED
    }

    /**
     * This pod's view of a module's load, for the health endpoint: the state, and when retrying,
     * the attempt count, phase and next attempt time.
     */
    public Map<String, Object> podLoadState(String tenantId, String moduleId) {
        return loader.podLoadState(tenantId, moduleId);
    }

    /** Modules (plus the startup module list) awaiting a load retry on this pod. */
    public int pendingLoadRetries() {
        return loader.pendingLoadRetries();
    }

    /** This pod's failure record for a module, or null. Package-private for tests. */
    ModuleLoader.LoadFailure loadFailure(String tenantId, String moduleId) {
        return loader.loadFailure(tenantId, moduleId);
    }

    /** Backoff for transient load failures. Setter so the constructor overloads stay as they are. */
    public void setLoadRetryPolicy(ModuleLoadRetryPolicy retryPolicy) {
        loader.setLoadRetryPolicy(retryPolicy);
    }

    /** Test seam for backoff timing. */
    void setClock(Clock clock) {
        loader.setClock(clock);
    }

    /**
     * Enables load metrics: {@value #METRIC_LOAD_FAILURES} (tagged {@code kind}, {@code phase}),
     * {@value #METRIC_LOAD_RECOVERED}, and the {@value #METRIC_LOAD_RETRY_PENDING} gauge — the one
     * to alert on, since it stays non-zero for as long as a module is down on a pod.
     */
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        loader.setMeterRegistry(meterRegistry);
        if (meterRegistry != null) {
            Gauge.builder(METRIC_LOAD_RETRY_PENDING, this, RuntimeModuleManager::pendingLoadRetries)
                .description("Runtime modules awaiting a load retry on this pod after an "
                    + "infrastructure failure")
                .register(meterRegistry);
        }
    }

    /**
     * Lists all modules for a tenant.
     */
    public List<TenantModuleData> listModules(String tenantId) {
        return moduleStore.findByTenant(tenantId);
    }

    /**
     * Checks if a module is loaded for a tenant.
     */
    public boolean isLoaded(String tenantId, String moduleId) {
        return loader.isLoaded(tenantId, moduleId);
    }

    /** Load diagnostics for one module row, for the health endpoint. */
    public Map<String, Object> loadDiagnostics(String moduleRowId) {
        try {
            return moduleStore.findLoadDiagnostics(moduleRowId);
        } catch (Exception e) {
            log.warn("Could not read load diagnostics for {}: {}", moduleRowId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Enables stub mode ({@code kelta.modules.stub-mode}). Set from configuration rather than a
     * constructor parameter so the several existing constructor overloads stay as they are.
     */
    public void setStubModeEnabled(boolean stubModeEnabled) {
        loader.setStubModeEnabled(stubModeEnabled);
    }

    /** Injected via setter so the several existing constructor overloads stay as they are. */
    public void setProvenanceStore(ModuleProvenanceStore provenanceStore) {
        installer.setProvenanceStore(provenanceStore);
    }

    /**
     * Checks if JAR-based loading is available.
     */
    public boolean isJarLoadingEnabled() {
        return jarService != null;
    }
}
