package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.flow.ActionHandlerDescriptor;
import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.workflow.module.ModuleContext;
import io.kelta.worker.module.ModuleLoadFailureClassifier.Kind;
import io.kelta.worker.module.ModuleLoadFailureClassifier.Phase;
import io.kelta.worker.service.TenantSlugResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * @since 1.0.0
 */
public class RuntimeModuleManager {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);

    private final ModuleStore moduleStore;
    private final ActionHandlerRegistry actionHandlerRegistry;
    private final ModuleManifestParser manifestParser;
    private final ObjectMapper objectMapper;
    private final ModuleJarService jarService;
    private final ModuleContext moduleContext;
    private final ModuleSignatureVerifier signatureVerifier;
    private final BeforeSaveHookRegistry beforeSaveHookRegistry;
    private final ModuleMetadataProvisioner metadataProvisioner;
    /** Resolves tenantId -> slug for webhook dispatch; may be null in tests. */
    private final TenantSlugResolver tenantSlugResolver;

    /** Records what each module provisioned. Null in tests that do not exercise provisioning. */
    private ModuleProvenanceStore provenanceStore;

    /** Routes each loaded module serves. Null in tests that do not exercise HTTP dispatch. */
    private ModuleRouteRegistry moduleRouteRegistry;
    private final ModuleServiceRegistry moduleServiceRegistry;

    /**
     * When true, a module with no JAR loads inert stub handlers instead of quarantining. A dev and
     * test convenience: it makes every declared action succeed without doing anything, which is
     * exactly the state that must never be reachable by accident in production.
     */
    private boolean stubModeEnabled;

    /** Tracks which modules are loaded per tenant: tenantId -> Set<moduleId> */
    private final Map<String, Set<String>> loadedModules = new ConcurrentHashMap<>();

    /** Tracks active ClassLoaders for cleanup: "tenantId:moduleId" -> ClassLoader */
    private final Map<String, SandboxedModuleClassLoader> activeClassLoaders = new ConcurrentHashMap<>();

    /** Tracks loaded KeltaModule instances for lifecycle management */
    private final Map<String, KeltaModule> activeModuleInstances = new ConcurrentHashMap<>();

    /** The exact hook instances registered per load, keyed "tenantId:moduleId" — see LoadedModule. */
    private final Map<String, List<BeforeSaveHook>> registeredHooks = new ConcurrentHashMap<>();

    /**
     * Services each load published, so unload can remove the exact instances -- a module's
     * {@code getServices()} may build fresh objects per call, same reason hooks are tracked.
     */
    private final Map<String, Map<Class<?>, Object>> registeredServices = new ConcurrentHashMap<>();

    /**
     * What each load actually registered, keyed "tenantId:moduleId".
     *
     * <p>Held in memory so unloading never needs the database row. Uninstall deletes the row and
     * <em>then</em> publishes UNINSTALLED, so every pod except the one that served the request
     * sees the event after the row is already gone — see {@link #unloadModule(String, String)}.
     */
    private final Map<String, LoadedModule> loadedActionKeys = new ConcurrentHashMap<>();

    /**
     * The registry keys, hook instances, and cached JAR a single loaded module owns.
     *
     * <p>Hooks are held by instance because {@link BeforeSaveHookRegistry} removes them by
     * identity — a module's {@code getBeforeSaveHooks()} may build fresh objects on each call, so
     * unload must use the instances that were actually registered.
     */
    private record LoadedModule(Set<String> actionKeys, List<BeforeSaveHook> hooks, String s3Key) {
    }

    /** Metric: load failures, tagged {@code kind} (transient|permanent) and {@code phase}. */
    static final String METRIC_LOAD_FAILURES = "kelta_worker_module_load_failures";
    /** Metric: modules whose load recovered on a retry after a transient failure. */
    static final String METRIC_LOAD_RECOVERED = "kelta_worker_module_load_recovered";
    /** Metric: modules (plus the startup module list) awaiting a load retry on this pod. */
    static final String METRIC_LOAD_RETRY_PENDING = "kelta_worker_module_load_retry_pending";

    /**
     * What this pod knows about a module load that failed, keyed "tenantId:moduleId".
     *
     * <p>A failed module is still in {@link #loadedModules} — its refusing handlers are registered
     * and unload must remove them — so this map is what tells a healthy load apart from a
     * fail-closed one. {@code nextAttemptAt} is null when no retry is scheduled: a permanent
     * failure, or a transient one whose row stopped being {@code ACTIVE}.
     */
    record LoadFailure(String tenantId, String moduleId, String moduleRowId, Kind kind, Phase phase,
                       String reason, int attempts, Instant firstFailedAt, Instant nextAttemptAt,
                       boolean escalated) {

        boolean retryDue(Instant now) {
            return kind == Kind.TRANSIENT && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
        }
    }

    /**
     * Per-pod load-failure state. Deliberately local and never broadcast: each pod loads its own
     * classloaders from the JAR, so whether a load failed is a fact about this pod (its network,
     * its disk, its view of object storage) and not configuration. Critical Rule 1 — broadcast
     * every config change over NATS — governs shared configuration; the shared state here is the
     * {@code tenant_module} row, which a transient failure deliberately does not change.
     */
    private final Map<String, LoadFailure> loadFailures = new ConcurrentHashMap<>();

    private ModuleLoadRetryPolicy retryPolicy = ModuleLoadRetryPolicy.DEFAULTS;
    private Clock clock = Clock.systemUTC();
    private MeterRegistry meterRegistry;

    /**
     * Set when listing the ACTIVE modules at startup failed (database unreachable). Without a
     * retry nothing would ever load on this pod: there is no row-level failure to retry, because
     * no row was ever read.
     */
    private volatile boolean startupLoadPending;
    private volatile int startupLoadAttempts;
    private volatile Instant startupLoadRetryAt;

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
        this.actionHandlerRegistry = Objects.requireNonNull(actionHandlerRegistry);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.manifestParser = new ModuleManifestParser(objectMapper);
        this.jarService = jarService;
        this.moduleContext = moduleContext;
        this.signatureVerifier = signatureVerifier;
        this.beforeSaveHookRegistry = beforeSaveHookRegistry;
        this.metadataProvisioner = metadataProvisioner;
        this.tenantSlugResolver = tenantSlugResolver;
        this.moduleServiceRegistry = moduleServiceRegistry;
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
     *
     * @param tenantId    the tenant ID
     * @param manifestJson the module manifest JSON
     * @param sourceUrl   the original download URL
     * @param checksum    SHA-256 checksum
     * @param jarSizeBytes JAR file size
     * @param installedBy the user who installed the module
     * @return the persisted module data
     */
    public TenantModuleData installModule(String tenantId, String manifestJson,
                                           String sourceUrl, String checksum,
                                           Long jarSizeBytes, String installedBy) {
        ModuleManifest manifest = manifestParser.parse(manifestJson);

        // Check for existing installation
        Optional<TenantModuleData> existing = moduleStore.findByTenantAndModuleId(
            tenantId, manifest.id());
        if (existing.isPresent()) {
            throw new IllegalStateException(
                "Module '" + manifest.id() + "' is already installed for tenant " + tenantId);
        }

        String id = UUID.randomUUID().toString();
        TenantModuleData data = new TenantModuleData(
            id, tenantId, manifest.id(), manifest.name(), manifest.version(),
            manifest.description(), sourceUrl, checksum, jarSizeBytes,
            manifest.moduleClass(), manifestJson,
            TenantModuleData.STATUS_INSTALLED, installedBy,
            null, null, null, List.of()
        );

        moduleStore.createModule(data);

        // Create action records from manifest
        List<TenantModuleData.TenantModuleActionData> actions = new ArrayList<>();
        for (ModuleManifest.ActionHandlerManifest handler : manifest.actionHandlers()) {
            actions.add(new TenantModuleData.TenantModuleActionData(
                UUID.randomUUID().toString(), id, handler.key(), handler.name(),
                handler.category(), handler.description(), handler.configSchema(),
                handler.inputSchema(), handler.outputSchema()
            ));
        }
        if (!actions.isEmpty()) {
            moduleStore.createActions(actions);
        }

        provisionCollections(tenantId, manifest, id, installedBy);

        log.info("Installed module '{}' v{} for tenant {} with {} action handlers",
            manifest.name(), manifest.version(), tenantId, actions.size());

        return moduleStore.findById(id).orElse(data);
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
     * Installs a module with its JAR file, verifying the JAR's publisher signature
     * first (Rec 9). When signature verification is enabled
     * ({@code kelta.modules.signing.public-key} set), {@code signatureBase64} must
     * be a valid detached signature over the JAR bytes or the install is rejected
     * before anything is uploaded or persisted.
     *
     * @param signatureBase64 detached base64 signature over the JAR bytes (may be
     *                        {@code null} when verification is disabled)
     * @throws ModuleSignatureException if signature verification fails
     */
    public TenantModuleData installModuleWithJar(String tenantId, String manifestJson,
                                                   byte[] jarBytes, String installedBy,
                                                   String signatureBase64) {
        if (jarService == null) {
            throw new IllegalStateException("JAR upload requires S3 storage to be enabled");
        }

        // Authenticity gate — reject before any S3 upload or DB write. Verified against the
        // INSTALLING TENANT's own signing keys, so a JAR signed for one tenant cannot be
        // installed into another. Null when the tenant trusts no key and signing is not
        // required; a ModuleSignatureException otherwise.
        String signingKeyFingerprint = null;
        if (signatureVerifier != null) {
            signingKeyFingerprint = signatureVerifier.verify(tenantId, jarBytes, signatureBase64);
        }

        ModuleManifest manifest = manifestParser.parse(manifestJson);

        // Duplicate check BEFORE the upload. The other order leaves an orphan JAR in S3 on every
        // rejected install: uninstall only ever deletes the s3Key recorded on the module row, and
        // a rejected install never wrote one.
        Optional<TenantModuleData> existing = moduleStore.findByTenantAndModuleId(
            tenantId, manifest.id());
        if (existing.isPresent()) {
            throw new IllegalStateException(
                "Module '" + manifest.id() + "' is already installed for tenant " + tenantId);
        }

        String checksum = ModuleJarService.sha256(jarBytes);
        String s3Key = jarService.uploadJar(tenantId, manifest.id(), manifest.version(), jarBytes);

        String id = UUID.randomUUID().toString();
        TenantModuleData data = new TenantModuleData(
            id, tenantId, manifest.id(), manifest.name(), manifest.version(),
            manifest.description(), s3Key, checksum, (long) jarBytes.length,
            manifest.moduleClass(), manifestJson,
            TenantModuleData.STATUS_INSTALLED, installedBy,
            null, null, s3Key, List.of()
        );

        moduleStore.createModule(data);
        if (signatureBase64 != null && !signatureBase64.isBlank()) {
            // Keep the verified signature so every subsequent load can re-verify
            // the downloaded JAR (defense-in-depth vs S3 tamper), and the key that
            // verified it so a rotation can tell what still depends on that key.
            moduleStore.saveJarSignature(id, signatureBase64, signingKeyFingerprint);
        }

        // Create action records from manifest
        List<TenantModuleData.TenantModuleActionData> actions = new ArrayList<>();
        for (ModuleManifest.ActionHandlerManifest handler : manifest.actionHandlers()) {
            actions.add(new TenantModuleData.TenantModuleActionData(
                UUID.randomUUID().toString(), id, handler.key(), handler.name(),
                handler.category(), handler.description(), handler.configSchema(),
                handler.inputSchema(), handler.outputSchema()
            ));
        }
        if (!actions.isEmpty()) {
            moduleStore.createActions(actions);
        }

        provisionCollections(tenantId, manifest, id, installedBy);

        log.info("Installed module '{}' v{} for tenant {} with JAR (s3Key={}, {} bytes)",
            manifest.name(), manifest.version(), tenantId, s3Key, jarBytes.length);

        return moduleStore.findById(id).orElse(data);
    }

    /**
     * Provisions the metadata the manifest declares, in the installing tenant, and records what it
     * created versus adopted.
     *
     * <p>Failure is still not thrown: the module row and its action records are already persisted
     * at this point, so propagating would leave a half-installed module the admin cannot see or
     * uninstall. The module is marked {@code FAILED} instead — on a thrown error or on any failed
     * import item — which is the state an admin can actually see and act on. Provenance is still
     * recorded for the items that succeeded, so a retry finds its own half-created collection
     * already recorded as {@code CREATED}.
     *
     * @param moduleRowId the {@code tenant_module} row, so a failure can be recorded against it
     */
    private void provisionCollections(String tenantId, ModuleManifest manifest, String moduleRowId,
                                      String installedBy) {
        if (metadataProvisioner == null || manifest.collections().isEmpty()) {
            return;
        }
        try {
            var result = metadataProvisioner.provision(tenantId, manifest, installedBy);
            recordProvenance(tenantId, manifest, result);
            if (result.hasFailures()) {
                log.error("Module '{}' failed to provision {} item(s) for tenant {} and is marked "
                        + "FAILED: {}", manifest.id(), result.failures().size(), tenantId,
                    result.failures());
                markFailed(moduleRowId);
                return;
            }
            log.info("Module '{}' provisioned {} resource(s) for tenant {}",
                manifest.id(), result.resources().size(), tenantId);
        } catch (RuntimeException e) {
            log.error("Module '{}' failed to provision collections for tenant {} "
                    + "and is marked FAILED: {}",
                manifest.id(), tenantId, e.getMessage(), e);
            markFailed(moduleRowId);
        }
    }

    private void markFailed(String moduleRowId) {
        try {
            moduleStore.updateStatus(moduleRowId, TenantModuleData.STATUS_FAILED);
        } catch (RuntimeException ignored) {
            // Bookkeeping must not mask the provisioning error.
        }
    }

    /**
     * Records one provenance row per resource the run created or adopted.
     *
     * <p>Best-effort: losing the bookkeeping must not fail an install that otherwise succeeded. The
     * cost of a missing record is a conservative uninstall, which is the behaviour today anyway.
     */
    private void recordProvenance(String tenantId, ModuleManifest manifest,
                                  ModuleMetadataProvisioner.ProvisionResult result) {
        if (provenanceStore == null) {
            return;
        }
        try {
            for (var resource : result.resources()) {
                provenanceStore.record(tenantId, manifest.id(), manifest.version(),
                    resource.type(), resource.naturalKey(), resource.resourceId(),
                    resource.ownership(), resource.contentHash());
            }
        } catch (RuntimeException e) {
            log.warn("Could not record provenance for module '{}' in tenant {}: {}",
                manifest.id(), tenantId, e.getMessage());
        }
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
            log.debug("Module '{}' already active for tenant {}", moduleId, tenantId);
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
     *
     * <p>{@link #loadModule} is a no-op for anything already in {@link #loadedModules}, and a
     * fail-closed module is in there (its refusing handlers are registered). That is right for
     * INSTALLED events and startup, but it made an explicit enable of a quarantined module do
     * nothing on every pod that had already tried it. A healthy load is left alone, so the ENABLED
     * event arriving back at the pod that served the request does not churn its classloader.
     *
     * @param tenantId the tenant ID
     * @param module   the module row
     */
    public void ensureLoaded(String tenantId, TenantModuleData module) {
        if (loadFailures.containsKey(key(tenantId, module.moduleId()))) {
            unloadModule(tenantId, module.moduleId());
        }
        loadModule(tenantId, module);
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
     * <p>
     * If the module has an S3 JAR key and JAR service is available, loads real handlers
     * from the JAR using a sandboxed ClassLoader. Otherwise, falls back to stub handlers.
     * <p>
     * Idempotent — no-op if already loaded.
     *
     * @param tenantId the tenant ID
     * @param module the module data
     */
    public void loadModule(String tenantId, TenantModuleData module) {
        Set<String> loaded = loadedModules.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet());
        if (loaded.contains(module.moduleId())) {
            log.debug("Module '{}' already loaded for tenant {}", module.moduleId(), tenantId);
            return;
        }

        if (module.s3Key() != null && jarService != null) {
            loadFromJar(tenantId, module);
        } else if (stubModeEnabled) {
            // Explicit dev opt-in only. Never reached by falling back from an error.
            loadWithStubs(tenantId, module);
            recordOutcome(module, TenantModuleData.STATUS_STUB, null);
            onLoadSucceeded(tenantId, module);
        } else if (module.s3Key() == null) {
            // A module with no JAR has no implementation. Its declared actions used to return
            // success, which is indistinguishable from the work actually happening.
            quarantine(tenantId, module, Phase.SOURCE,
                "no implementation uploaded (manifest-only install)");
        } else {
            // The module has a JAR; this pod just cannot reach JAR storage. That is a fact about
            // the pod, so it must not write a terminal status every other pod would then honour.
            failClosedPendingRetry(tenantId, module, Phase.SOURCE,
                "JAR storage is unavailable on this pod", null);
        }

        loaded.add(module.moduleId());
        // Capture what was registered while the row is still in hand; unload works from this
        // rather than re-reading the database, which may no longer have the row by then.
        Set<String> registered = new HashSet<>();
        for (var action : module.actions()) {
            registered.add(action.actionKey());
        }
        String key = tenantId + ":" + module.moduleId();
        KeltaModule instance = activeModuleInstances.get(key);
        if (instance != null) {
            for (ActionHandler handler : instance.getActionHandlers()) {
                registered.add(handler.getActionTypeKey());
            }
        }
        loadedActionKeys.put(key,
            new LoadedModule(registered, registeredHooks.getOrDefault(key, List.of()),
                module.s3Key()));

        log.info("Loaded module '{}' v{} with {} handlers for tenant {}",
            module.name(), module.version(), module.actions().size(), tenantId);
    }

    /**
     * Loads module handlers from the JAR via a sandboxed ClassLoader.
     */
    private void loadFromJar(String tenantId, TenantModuleData module) {
        String classLoaderKey = tenantId + ":" + module.moduleId();
        // Which step is running decides whether a failure is the module's fault or the
        // infrastructure's — see ModuleLoadFailureClassifier.
        Phase phase = Phase.FETCH;
        try {
            URL jarUrl = jarService.downloadJarToCache(module.s3Key());
            phase = Phase.VERIFY;
            verifyDownloadedJar(module, jarUrl);
            phase = Phase.LOAD;
            SandboxedModuleClassLoader classLoader = new SandboxedModuleClassLoader(
                module.moduleId(), jarUrl, getClass().getClassLoader());

            // Load the KeltaModule implementation class
            @SuppressWarnings("unchecked")
            Class<? extends KeltaModule> moduleClass = (Class<? extends KeltaModule>)
                classLoader.loadClass(module.moduleClass());

            KeltaModule keltaModule = moduleClass.getDeclaredConstructor().newInstance();

            // Provide restricted module context and initialize
            if (moduleContext != null) {
                keltaModule.onStartup(moduleContext);
            }

            // Register real action handlers from the module
            List<ActionHandler> handlers = keltaModule.getActionHandlers();
            for (ActionHandler handler : handlers) {
                actionHandlerRegistry.registerTenantHandler(tenantId, handler);
            }

            // Register before-save hooks, tenant-scoped so a module installed by one tenant
            // never fires on another tenant's records.
            List<BeforeSaveHook> hooks = registerTenantHooks(tenantId, keltaModule);
            // Recorded immediately: anything that throws after this point must be able to find
            // these to unwind them, and registerTenantServices below can reject a bad port.
            registeredHooks.put(classLoaderKey, hooks);

            // Services this module publishes for platform code to call. Self-unwinding: a rejected
            // port removes the ports already accepted from the same module before rethrowing, so a
            // module never ends up half-published.
            ModuleManifest storedManifest = parseStoredManifest(module);
            Map<Class<?>, Object> services = registerTenantServices(
                tenantId, keltaModule, storedManifest);

            // Routes last: only a module whose code actually loaded should answer HTTP. A
            // quarantined module serves 404 rather than a refusing handler, because an HTTP caller
            // has no flow to attribute the failure to.
            if (moduleRouteRegistry != null && storedManifest != null) {
                moduleRouteRegistry.register(tenantId, module.moduleId(), storedManifest.routes());
            }
            registeredServices.put(classLoaderKey, services);

            activeClassLoaders.put(classLoaderKey, classLoader);
            activeModuleInstances.put(classLoaderKey, keltaModule);

            log.info("Loaded module '{}' from JAR with {} real handlers, {} hooks and {} services "
                    + "for tenant {}",
                module.moduleId(), handlers.size(), hooks.size(), services.size(), tenantId);

            // Clears any previous failure, so a module that recovers stops reporting a stale error.
            recordOutcome(module, TenantModuleData.STATUS_ACTIVE, null);
            onLoadSucceeded(tenantId, module);

        } catch (Exception | LinkageError e) {
            // LinkageError too: NoClassDefFoundError, UnsupportedClassVersionError and
            // ExceptionInInitializerError are what a bad JAR actually throws from loadClass and
            // newInstance, and as Errors they used to escape this catch — and the startup loop's —
            // leaving no refusing handlers and no recorded outcome.

            // Clean up partial ClassLoader on failure
            SandboxedModuleClassLoader cl = activeClassLoaders.remove(classLoaderKey);
            if (cl != null) {
                try { cl.close(); } catch (IOException ignored) {}
            }
            activeModuleInstances.remove(classLoaderKey);
            // Hooks may have been registered before the failure; strip them so a half-loaded
            // module cannot keep vetoing saves after falling back to stubs.
            List<BeforeSaveHook> partialHooks = registeredHooks.remove(classLoaderKey);
            if (partialHooks != null && beforeSaveHookRegistry != null) {
                beforeSaveHookRegistry.removeTenantHooks(tenantId, partialHooks);
            }
            // Same for services: a half-loaded module must not keep answering platform calls from
            // a ClassLoader that is about to be discarded.
            Map<Class<?>, Object> partialServices = registeredServices.remove(classLoaderKey);
            if (partialServices != null && moduleServiceRegistry != null) {
                moduleServiceRegistry.remove(tenantId, partialServices);
            }

            // Fail closed either way. Handlers are registered but refuse to run, so a flow step
            // fails with a specific, attributable error instead of ResourceNotFound (which reads
            // to an admin as a mistyped action key) or, worse, a success against code that never
            // ran. What differs is whether the failure is persisted as terminal.
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (ModuleLoadFailureClassifier.classify(phase, e) == Kind.PERMANENT) {
                // ERROR, not WARN: this module is not running. It used to fall back to stub
                // handlers that returned success, so a rejected JAR looked healthy at every level
                // an admin could see.
                log.error("Module '{}' failed to load for tenant {} ({} phase) and is QUARANTINED: {}",
                    module.moduleId(), tenantId, phase.tag(), e.getMessage(), e);
                quarantine(tenantId, module, phase, reason);
            } else {
                failClosedPendingRetry(tenantId, module, phase, reason, e);
            }
        }
    }

    /**
     * Fails a module closed for a reason that is the module's own — bad signature or checksum, a
     * class that will not load, {@code onStartup} throwing, a rejected service port, no JAR at all
     * — and persists {@code QUARANTINED}, which only an admin's re-enable clears.
     */
    private void quarantine(String tenantId, TenantModuleData module, Phase phase, String reason) {
        registerRefusingHandlers(tenantId, module, reason);
        recordOutcome(module, TenantModuleData.STATUS_QUARANTINED, reason);
        loadFailures.put(key(tenantId, module.moduleId()), new LoadFailure(tenantId,
            module.moduleId(), module.id(), Kind.PERMANENT, phase, reason, 1, clock.instant(),
            null, false));
        countFailure(Kind.PERMANENT, phase);
    }

    /**
     * Fails a module closed on this pod for an infrastructure reason, and schedules another try.
     *
     * <p>Same refusing handlers as {@link #quarantine} — nothing runs that did not load and verify
     * — but the persisted status is left as it is and only the error is recorded. The 2026-10-06
     * incident is why: object storage refused connections during a rollout, every module on every
     * tenant was written {@code QUARANTINED}, startup only loads {@code ACTIVE} rows, and they all
     * stayed down for five days with nothing wrong with any of them.
     *
     * <p>Logs ERROR on the first failure and once more on escalation; the retries in between are
     * WARN, so an outage does not bury the log in stack traces every tick.
     *
     * @param cause the failure, or null when there is no exception (no JAR storage on this pod)
     */
    private void failClosedPendingRetry(String tenantId, TenantModuleData module, Phase phase,
                                        String reason, Throwable cause) {
        String key = key(tenantId, module.moduleId());
        Instant now = clock.instant();
        LoadFailure failure = loadFailures.compute(key, (k, previous) -> {
            boolean continuing = previous != null && previous.kind() == Kind.TRANSIENT;
            int attempts = continuing ? previous.attempts() + 1 : 1;
            return new LoadFailure(tenantId, module.moduleId(), module.id(), Kind.TRANSIENT, phase,
                reason, attempts, continuing ? previous.firstFailedAt() : now,
                now.plus(retryPolicy.backoffAfter(attempts)), continuing && previous.escalated());
        });
        long waitSeconds = Duration.between(now, failure.nextAttemptAt()).toSeconds();

        if (failure.attempts() == 1) {
            log.error("Module '{}' could not be loaded for tenant {} ({} phase): {}. Treated as an "
                    + "infrastructure failure: its actions refuse on this pod, its status is NOT "
                    + "changed, and the load is retried in {}s",
                module.moduleId(), tenantId, phase.tag(), reason, waitSeconds, cause);
        } else if (!failure.escalated()
                && failure.attempts() >= retryPolicy.escalateAfterAttempts()) {
            log.error("Module '{}' for tenant {} has still not loaded after {} attempts since {} "
                    + "({} phase): {}. Retrying every {}s until it does",
                module.moduleId(), tenantId, failure.attempts(), failure.firstFailedAt(),
                phase.tag(), reason, retryPolicy.maxBackoff().toSeconds());
            loadFailures.computeIfPresent(key, (k, current) -> current == failure
                ? withEscalated(current) : current);
        } else {
            log.warn("Module '{}' load retry {} for tenant {} failed ({} phase): {}; "
                    + "next attempt in {}s",
                module.moduleId(), failure.attempts(), tenantId, phase.tag(), reason, waitSeconds);
        }

        registerRefusingHandlers(tenantId, module,
            "temporarily unavailable, the load will be retried (" + reason + ")");
        try {
            moduleStore.recordLoadError(module.id(), reason);
        } catch (Exception e) {
            log.warn("Could not record load error for module '{}': {}",
                module.moduleId(), e.getMessage());
        }
        countFailure(Kind.TRANSIENT, phase);
    }

    private static LoadFailure withEscalated(LoadFailure f) {
        return new LoadFailure(f.tenantId(), f.moduleId(), f.moduleRowId(), f.kind(), f.phase(),
            f.reason(), f.attempts(), f.firstFailedAt(), f.nextAttemptAt(), true);
    }

    /**
     * Clears this pod's failure state after a successful load, logging a recovery when the load
     * had been failing transiently.
     */
    private void onLoadSucceeded(String tenantId, TenantModuleData module) {
        LoadFailure previous = loadFailures.remove(key(tenantId, module.moduleId()));
        if (previous != null && previous.kind() == Kind.TRANSIENT) {
            log.warn("Module '{}' loaded for tenant {} after {} failed attempt(s) since {}",
                module.moduleId(), tenantId, previous.attempts(), previous.firstFailedAt());
            if (meterRegistry != null) {
                Counter.builder(METRIC_LOAD_RECOVERED)
                    .description("Module loads that succeeded on retry after a transient failure")
                    .register(meterRegistry)
                    .increment();
            }
        }
    }

    /**
     * Registers handlers that refuse to run for every action the manifest declares.
     *
     * <p>Registering rather than leaving the keys absent is deliberate: an absent key surfaces to a
     * flow as {@code ResourceNotFound}, which an admin reads as a typo in the step. A refusing
     * handler names the module and the reason.
     */
    private void registerRefusingHandlers(String tenantId, TenantModuleData module, String reason) {
        for (var action : module.actions()) {
            actionHandlerRegistry.registerTenantHandler(tenantId,
                createRefusingHandler(action, module, reason));
        }
    }

    /** Tagged by kind and phase only — never by tenant or module, which would be unbounded. */
    private void countFailure(Kind kind, Phase phase) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_LOAD_FAILURES)
            .description("Runtime module load failures on this pod")
            .tag("kind", kind.tag())
            .tag("phase", phase.tag())
            .register(meterRegistry)
            .increment();
    }

    private static String key(String tenantId, String moduleId) {
        return tenantId + ":" + moduleId;
    }

    /** Persists a load outcome, never letting a bookkeeping failure mask the load result. */
    private void recordOutcome(TenantModuleData module, String status, String error) {
        try {
            moduleStore.recordLoadOutcome(module.id(), status, error);
        } catch (Exception e) {
            log.warn("Could not record load outcome for module '{}': {}",
                module.moduleId(), e.getMessage());
        }
    }

    /**
     * The manifest as stored on the module row, used to check what the module is allowed to
     * publish. A module whose stored manifest cannot be parsed publishes nothing rather than
     * everything — the declaration is the authorisation, so an unreadable one is not a licence.
     */
    private ModuleManifest parseStoredManifest(TenantModuleData module) {
        try {
            return manifestParser.parse(module.manifest());
        } catch (RuntimeException e) {
            log.warn("Could not parse the stored manifest for module '{}': {}. "
                    + "It will not be permitted to publish any service.",
                module.moduleId(), e.getMessage());
            return null;
        }
    }

    /**
     * Re-verifies a JAR downloaded from S3 before it is classloaded
     * (defense-in-depth vs storage tamper — the install-time gate already ran):
     * the bytes must match the checksum persisted at install, and when
     * signature verification is enabled the install-time publisher signature
     * must still verify. Failure throws — the caller falls back to inert
     * stub handlers, never executing unverified code.
     */
    private void verifyDownloadedJar(TenantModuleData module, URL jarUrl) throws Exception {
        byte[] jarBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(jarUrl.toURI()));

        String expectedChecksum = module.jarChecksum();
        if (expectedChecksum != null && !expectedChecksum.isBlank()) {
            String actual = ModuleJarService.sha256(jarBytes);
            if (!expectedChecksum.equals(actual)) {
                throw new ModuleSignatureException(
                    "Module '" + module.moduleId() + "' JAR checksum mismatch on load "
                        + "(expected " + expectedChecksum + ", got " + actual
                        + ") — possible storage tamper, refusing to load");
            }
        }

        if (signatureVerifier != null && signatureVerifier.isEnabledFor(module.tenantId())) {
            String signature = moduleStore.findJarSignature(module.id()).orElse(null);
            // verify() throws when the signature is missing or invalid —
            // modules installed before signing was enabled must be re-installed
            // with a signature once a public key is configured.
            //
            // Re-verified against the tenant's keys AS THEY ARE NOW, not against the key
            // recorded at install: that is what lets a rotation be additive. The flip side is
            // that retiring a key whose modules were never re-signed makes them fail here and
            // fall back to stubs — see jar_signature_key_fingerprint and
            // GET /api/modules/signing-keys, which reports the dependent module count.
            signatureVerifier.verify(module.tenantId(), jarBytes, signature);
        }
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
     * Dispatches a raw inbound webhook to the {@code webhookHandlerKey} the module's manifest
     * names.
     *
     * <p><b>The platform authenticates nothing here.</b> The route is unauthenticated by design —
     * a payment processor or other external system cannot present a platform JWT — so the
     * handler owns its own trust anchor, typically an HMAC over the raw body verified against a
     * credential the module resolves itself. The {@code tenantId} in the path is untrusted input:
     * it selects which tenant's module (and therefore which secret) to dispatch to, nothing more.
     *
     * <p>Returns empty when the tenant has no such ACTIVE module, when that module declares no
     * webhook handler, or when its handler is not registered. All three collapse to one outcome
     * so an unauthenticated caller cannot tell them apart and enumerate a tenant's modules.
     *
     * @param tenantId  the tenant named in the path (untrusted)
     * @param moduleId  the module named in the path (untrusted)
     * @param rawBody   the unparsed request body — handlers that verify a body signature MUST use
     *                  this exact string, since re-serializing changes the bytes the HMAC covers
     * @param headers   inbound request headers (signature headers live here)
     * @return the handler's result, or empty if nothing was dispatched
     */
    /**
     * Dispatches an authenticated HTTP request to the module handler its manifest routes it to.
     *
     * <p>The counterpart of {@link #dispatchWebhook} for the authenticated prefix. Two differences
     * matter: the caller is a known platform user, so {@code userId} is bound and a handler can
     * scope to the actor; and the route table comes from {@link ModuleRouteRegistry}, which is
     * populated on load, so a disabled module stops answering at once.
     *
     * <p>Tenant context is bound with id <b>and slug</b>, as everywhere else the platform calls into
     * a module: a module cannot resolve a slug, and a null one silently reads the public schema
     * rather than failing.
     *
     * @return the handler's result, or empty when the module is not ACTIVE or serves no such route
     */
    public Optional<ActionResult> dispatchRoute(String tenantId, String moduleId, String userId,
                                                String method, String path,
                                                Map<String, Object> query, String rawBody) {
        if (moduleRouteRegistry == null) {
            return Optional.empty();
        }
        Optional<TenantModuleData> found = moduleStore.findByTenantAndModuleId(tenantId, moduleId);
        if (found.isEmpty() || !TenantModuleData.STATUS_ACTIVE.equals(found.get().status())) {
            return Optional.empty();
        }

        Optional<String> handlerKey = moduleRouteRegistry.resolve(tenantId, moduleId, method, path);
        if (handlerKey.isEmpty()) {
            // Log what IS registered. Knowing the route did not resolve is not the same as knowing
            // what was registered, and the difference is what turns a 404 into a diagnosis.
            log.warn("Module '{}' in tenant {} serves no route for '{} {}'. Registered: {}",
                moduleId, tenantId, method, path,
                moduleRouteRegistry.registeredKeys(tenantId, moduleId));
            return Optional.empty();
        }

        Optional<ActionHandler> handler = actionHandlerRegistry.getHandler(tenantId, handlerKey.get());
        if (handler.isEmpty()) {
            log.warn("Module '{}' of tenant {} routes {} {} to handler '{}', which is not registered",
                moduleId, tenantId, method, path, handlerKey.get());
            return Optional.empty();
        }

        Map<String, Object> input = new java.util.LinkedHashMap<>();
        if (query != null) {
            input.putAll(query);
        }
        // The JSON body has to become inputs too, not just ride along as rawBody. Without this a
        // POST handler reading its own documented fields finds nothing and answers "... is
        // required" against a request that supplied everything -- indistinguishable from real
        // validation, which is exactly how it reached production. Body wins over a same-named query
        // parameter: for a POST it is the primary source.
        input.putAll(parseBodyAsInputs(rawBody));
        // The flow engine passes a state envelope, and handlers read through ActionInputs, which
        // unwraps "input" when present. Matching that here means one handler serves both paths.
        Map<String, Object> resolved = Map.of(
            "input", input,
            "method", method,
            "path", path,
            "rawBody", rawBody == null ? "" : rawBody,
            "moduleId", moduleId);

        ActionContext context = ActionContext.builder()
            .tenantId(tenantId)
            .userId(userId)
            .resolvedData(resolved)
            .build();

        String tenantSlug = tenantSlugResolver == null ? null
            : tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        return Optional.of(TenantContext.callWithTenant(tenantId, tenantSlug,
            () -> handler.get().execute(context)));
    }

    /**
     * The request body as handler inputs, or empty when there is nothing usable to read.
     *
     * <p>Only a JSON object contributes inputs. A body that is an array, a scalar, or malformed
     * yields nothing rather than throwing: the handler then reports its own missing-field error,
     * which is a better message than a parse failure from the dispatcher. {@code rawBody} is still
     * passed through untouched, because a webhook handler must verify a signature over the exact
     * bytes and cannot use a re-serialised map.
     */
    private Map<String, Object> parseBodyAsInputs(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            node.propertyStream().forEach(e -> values.put(e.getKey(), toPlainValue(e.getValue())));
            return values;
        } catch (RuntimeException e) {
            log.debug("Module route body was not readable as JSON, passing rawBody only: {}",
                e.getMessage());
            return Map.of();
        }
    }

    /** Unwraps a JSON scalar to the Java value a handler expects; anything else stays a node. */
    private static Object toPlainValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.stringValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        return value;
    }

    /** Injected via setter so the existing constructor overloads stay as they are. */
    public void setModuleRouteRegistry(ModuleRouteRegistry moduleRouteRegistry) {
        this.moduleRouteRegistry = moduleRouteRegistry;
    }

    public Optional<ActionResult> dispatchWebhook(String tenantId, String moduleId,
                                                  String rawBody, Map<String, String> headers) {
        Optional<TenantModuleData> found = moduleStore.findByTenantAndModuleId(tenantId, moduleId);
        if (found.isEmpty() || !TenantModuleData.STATUS_ACTIVE.equals(found.get().status())) {
            return Optional.empty();
        }

        String handlerKey;
        try {
            handlerKey = manifestParser.parse(found.get().manifest()).webhookHandlerKey();
        } catch (RuntimeException e) {
            log.warn("Module '{}' of tenant {} has an unreadable manifest — cannot dispatch webhook",
                moduleId, tenantId);
            return Optional.empty();
        }
        if (handlerKey == null || handlerKey.isBlank()) {
            return Optional.empty();
        }

        Optional<ActionHandler> handler = actionHandlerRegistry.getHandler(tenantId, handlerKey);
        if (handler.isEmpty()) {
            log.warn("Module '{}' of tenant {} declares webhook handler '{}' which is not "
                + "registered — the module may have fallen back to stubs", moduleId, tenantId, handlerKey);
            return Optional.empty();
        }

        ActionContext context = ActionContext.builder()
            .tenantId(tenantId)
            .resolvedData(Map.of(
                "rawBody", rawBody == null ? "" : rawBody,
                "headers", headers == null ? Map.of() : headers,
                "moduleId", moduleId))
            .build();

        // Bind the tenant for the handler's data access (Critical Rule 3). The webhook route is
        // unauthenticated, so nothing upstream binds one — and a module CANNOT bind it itself:
        // io.kelta.runtime.context is outside SandboxedModuleClassLoader's parent allowlist. An
        // unbound connection leaves app.current_tenant_id empty, which matches the admin_bypass
        // RLS policy, so a module's reads and writes would silently run unscoped across tenants.
        //
        // The SLUG matters as much as the id, and binding only the id is not enough:
        // PhysicalTableStorageAdapter#getTableRef resolves a tenant collection's schema from
        // TenantContext.getSlug(), and with no slug it falls back to the PUBLIC schema. Every
        // query then hits an unqualified table that does not exist there — the module's own
        // collections live in the tenant schema. Same reasoning as FlowWebhookController, which
        // resolves the slug for the identical reason.
        //
        // Binding before the handler verifies its signature is correct and necessary: resolving
        // the tenant's own signing secret is itself a tenant-scoped read. The path tenantId is
        // untrusted, but it only selects which tenant's secret must match — a forged request
        // fails the handler's own check and touches nothing.
        String tenantSlug = tenantSlugResolver == null
            ? null
            : tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        if (tenantSlug == null) {
            log.warn("Could not resolve slug for tenant {} dispatching a webhook to module '{}' — "
                + "the module's tenant-schema queries will fail", tenantId, moduleId);
        }
        return Optional.of(TenantContext.callWithTenant(tenantId, tenantSlug,
            () -> handler.get().execute(context)));
    }

    /**
     * Reads a module's UI bundle from its verified JAR.
     *
     * <p>Streams the {@code uiBundlePath} resource out of the JAR the tenant already has
     * installed. That JAR passed signature verification at install and checksum + signature
     * re-verification on load, so this adds no new trust boundary — but it does serve
     * publisher-authored JavaScript same-origin to the admin UI, which is exactly what the
     * signature gate exists to make safe.
     *
     * <p>Empty when the tenant has no such ACTIVE module, the module declares no bundle, JAR
     * loading is unavailable, or the resource is absent from the JAR.
     */
    public Optional<byte[]> readUiBundle(String tenantId, String moduleId) {
        Optional<TenantModuleData> found = moduleStore.findByTenantAndModuleId(tenantId, moduleId);
        if (found.isEmpty() || !TenantModuleData.STATUS_ACTIVE.equals(found.get().status())) {
            return Optional.empty();
        }
        TenantModuleData module = found.get();
        if (module.s3Key() == null || jarService == null) {
            return Optional.empty();
        }

        String bundlePath;
        try {
            bundlePath = manifestParser.parse(module.manifest()).uiBundlePath();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (bundlePath == null || bundlePath.isBlank()) {
            return Optional.empty();
        }

        try {
            URL jarUrl = jarService.downloadJarToCache(module.s3Key());
            // Same gate the classloader path runs: never serve bytes out of a JAR whose
            // checksum or signature no longer verifies.
            verifyDownloadedJar(module, jarUrl);
            try (java.util.jar.JarFile jar =
                     new java.util.jar.JarFile(java.nio.file.Path.of(jarUrl.toURI()).toFile())) {
                java.util.jar.JarEntry entry = jar.getJarEntry(bundlePath);
                if (entry == null) {
                    log.warn("Module '{}' of tenant {} declares uiBundlePath '{}' which is not in "
                        + "its JAR", moduleId, tenantId, bundlePath);
                    return Optional.empty();
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    return Optional.of(in.readAllBytes());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read UI bundle for module '{}' of tenant {}: {}",
                moduleId, tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Registers a module's before-save hooks against the installing tenant, returning the exact
     * instances registered so unload can remove them by identity. Returns empty when no hook
     * registry is wired (stub-only test setups) — hooks are then simply not active.
     */
    /**
     * Publishes the module's platform-callable services for this tenant.
     *
     * <p>Unwinds itself: if one port is rejected -- a duplicate for the tenant, or an interface the
     * module bundled rather than the platform's -- the ports already accepted from this same module
     * are removed before the exception propagates, so the caller's fallback-to-stubs path never
     * leaves a partially published module answering platform calls.
     *
     * @return the exact instances registered, for unload to remove by identity
     */
    private Map<Class<?>, Object> registerTenantServices(String tenantId, KeltaModule keltaModule,
                                                         ModuleManifest manifest) {
        if (moduleServiceRegistry == null) {
            return Map.of();
        }
        Map<Class<?>, Object> declared = keltaModule.getServices();
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Set<String> allowed = manifest == null || manifest.services() == null
                ? Set.of() : Set.copyOf(manifest.services());
        Map<Class<?>, Object> registered = new java.util.LinkedHashMap<>();
        try {
            declared.forEach((port, service) -> {
                // A module may only publish ports its manifest declares. Without this, a module an
                // admin installed for one purpose can publish any platform port and silently become
                // the tenant's authority for something unrelated -- the code decides, and nobody is
                // ever shown the claim. The manifest entry is what an admin can be told about and
                // approve.
                if (!allowed.contains(port.getName())) {
                    throw new IllegalStateException("Module '"
                        + (manifest == null ? "?" : manifest.id())
                        + "' tried to publish " + port.getName()
                        + ", which its manifest does not declare in \"services\"");
                }
                moduleServiceRegistry.register(tenantId, port, service);
                registered.put(port, service);
            });
        } catch (RuntimeException e) {
            moduleServiceRegistry.remove(tenantId, registered);
            throw e;
        }
        return Map.copyOf(registered);
    }

    private List<BeforeSaveHook> registerTenantHooks(String tenantId, KeltaModule keltaModule) {
        if (beforeSaveHookRegistry == null) {
            return List.of();
        }
        List<BeforeSaveHook> hooks = List.copyOf(keltaModule.getBeforeSaveHooks());
        for (BeforeSaveHook hook : hooks) {
            beforeSaveHookRegistry.registerTenantHook(tenantId, hook);
        }
        return hooks;
    }

    /**
     * Loads stub handlers from manifest metadata (no JAR available).
     */
    private void loadWithStubs(String tenantId, TenantModuleData module) {
        for (var action : module.actions()) {
            ActionHandler stubHandler = createStubHandler(action, module);
            actionHandlerRegistry.registerTenantHandler(tenantId, stubHandler);
        }
        log.debug("Loaded module '{}' with stub handlers for tenant {}", module.moduleId(), tenantId);
    }

    /**
     * Unloads a module's handlers from the registry.
     * Idempotent — no-op if not loaded.
     *
     * @param tenantId the tenant ID
     * @param module the module data
     */
    public void unloadModule(String tenantId, TenantModuleData module) {
        unloadModule(tenantId, module.moduleId());
    }

    /**
     * Unloads a module's handlers using only its identifier.
     * Idempotent — no-op if not loaded.
     *
     * <p>Deliberately does <strong>not</strong> read the module row. Uninstall deletes the row and
     * only then publishes UNINSTALLED, so every pod other than the one that served the request
     * processes the event against a row that no longer exists. The previous implementation looked
     * the row up, found nothing, and returned without unloading — leaving {@code loadedModules}
     * holding the id. A subsequent reinstall then hit the "already loaded" early return in
     * {@link #loadModule} and silently registered nothing, so the module ran on the request-serving
     * pod and was missing from every other one while {@code /api/modules} reported ACTIVE. A flow
     * step would fail ResourceNotFound on some pods and not others.
     *
     * @param tenantId the tenant ID
     * @param moduleId the module identifier from the manifest
     */
    public void unloadModule(String tenantId, String moduleId) {
        // A disable, uninstall or explicit re-enable ends whatever this pod was retrying.
        loadFailures.remove(key(tenantId, moduleId));
        unloadRegistrations(tenantId, moduleId);
    }

    /**
     * Removes everything a load registered — real or refusing handlers, hooks, routes, services,
     * the classloader — but not this pod's failure bookkeeping, so a retry keeps counting attempts.
     */
    private void unloadRegistrations(String tenantId, String moduleId) {
        Set<String> loaded = loadedModules.get(tenantId);
        if (loaded == null || !loaded.contains(moduleId)) {
            log.debug("Module '{}' not loaded for tenant {}", moduleId, tenantId);
            return;
        }

        String classLoaderKey = tenantId + ":" + moduleId;
        LoadedModule record = loadedActionKeys.remove(classLoaderKey);
        Set<String> actionKeys = new HashSet<>(
            record != null ? record.actionKeys() : Set.<String>of());

        // Also remove any real handlers registered by the KeltaModule
        KeltaModule keltaModule = activeModuleInstances.remove(classLoaderKey);
        if (keltaModule != null) {
            for (ActionHandler handler : keltaModule.getActionHandlers()) {
                actionKeys.add(handler.getActionTypeKey());
            }
        }

        actionHandlerRegistry.removeTenantHandlers(tenantId, actionKeys);

        // Remove the tenant-scoped before-save hooks this load registered. Taken from the
        // in-memory record rather than by re-calling getBeforeSaveHooks(), which may return
        // fresh instances the registry would not match by identity.
        List<BeforeSaveHook> hooks = registeredHooks.remove(classLoaderKey);
        if (hooks == null && record != null) {
            hooks = record.hooks();
        }
        if (hooks != null && !hooks.isEmpty() && beforeSaveHookRegistry != null) {
            beforeSaveHookRegistry.removeTenantHooks(tenantId, hooks);
        }

        if (moduleRouteRegistry != null) {
            moduleRouteRegistry.remove(tenantId, moduleId);
        }

        // Remove published services before closing the ClassLoader below -- a platform bean that
        // resolved one after the close would call into a dead ClassLoader.
        Map<Class<?>, Object> services = registeredServices.remove(classLoaderKey);
        if (services != null && !services.isEmpty() && moduleServiceRegistry != null) {
            moduleServiceRegistry.remove(tenantId, services);
        }

        // Close the ClassLoader
        SandboxedModuleClassLoader classLoader = activeClassLoaders.remove(classLoaderKey);
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException e) {
                log.warn("Failed to close ClassLoader for module '{}': {}", moduleId, e.getMessage());
            }
        }

        // Evict JAR from local cache
        if (record != null && record.s3Key() != null && jarService != null) {
            jarService.evictFromCache(record.s3Key());
        }

        loaded.remove(moduleId);
        if (loaded.isEmpty()) {
            loadedModules.remove(tenantId);
        }
        log.info("Unloaded module '{}' for tenant {}", moduleId, tenantId);
    }

    /**
     * Loads all active modules on pod startup.
     */
    public void loadAllActiveModules() {
        List<TenantModuleData> activeModules;
        try {
            activeModules = moduleStore.findAllActive();
        } catch (RuntimeException e) {
            // Database unreachable at startup. This used to be logged once by the caller and then
            // nothing loaded on this pod until it restarted.
            int attempts = ++startupLoadAttempts;
            Duration wait = retryPolicy.backoffAfter(attempts);
            startupLoadRetryAt = clock.instant().plus(wait);
            startupLoadPending = true;
            if (attempts == 1 || attempts == retryPolicy.escalateAfterAttempts()) {
                log.error("Could not list active runtime modules (attempt {}): {}. No module is "
                        + "loaded on this pod yet; retrying in {}s",
                    attempts, e.getMessage(), wait.toSeconds(), e);
            } else {
                log.warn("Listing active runtime modules failed again (attempt {}): {}; "
                        + "retrying in {}s", attempts, e.getMessage(), wait.toSeconds());
            }
            countFailure(Kind.TRANSIENT, Phase.SOURCE);
            return;
        }
        if (startupLoadPending) {
            log.warn("Listed active runtime modules after {} failed attempt(s)", startupLoadAttempts);
        }
        startupLoadPending = false;
        startupLoadAttempts = 0;
        startupLoadRetryAt = null;
        log.info("Loading {} active runtime modules on startup", activeModules.size());
        for (TenantModuleData module : activeModules) {
            try {
                loadModule(module.tenantId(), module);
            } catch (Exception e) {
                // loadModule classifies and records its own failures; reaching here means a bug in
                // the load path itself, not in the module.
                log.error("Failed to load module '{}' for tenant {}: {}",
                    module.moduleId(), module.tenantId(), e.getMessage(), e);
                markFailed(module.id());
            }
        }
    }

    /**
     * Re-attempts loads that failed on this pod for an infrastructure reason and are now due.
     * Called on a fixed delay by {@link ModuleLoadRetryScheduler}.
     *
     * <p>Each due module's row is re-read first: a retry only runs while the module is still
     * {@code ACTIVE} in the database. A row that is gone is unloaded (its uninstall event was
     * missed); a row in any other status parks the failure — the refusing handlers stay, nothing
     * more is retried, and a later enable starts afresh via {@link #ensureLoaded}.
     *
     * <p>Runs per tenant under {@link TenantContext}, as the NATS-driven load path does.
     */
    public void retryPendingLoads() {
        Instant now = clock.instant();
        if (startupLoadPending && startupLoadRetryAt != null && !now.isBefore(startupLoadRetryAt)) {
            loadAllActiveModules();
        }
        for (LoadFailure failure : List.copyOf(loadFailures.values())) {
            if (!failure.retryDue(now)) {
                continue;
            }
            try {
                TenantContext.runWithTenant(failure.tenantId(), () -> retryLoad(failure));
            } catch (RuntimeException e) {
                // Could not even read the row — the database is the thing that is down. Push the
                // next attempt back as if the load itself had failed, without touching handlers.
                Instant retryAt = clock.instant()
                    .plus(retryPolicy.backoffAfter(failure.attempts() + 1));
                loadFailures.computeIfPresent(key(failure.tenantId(), failure.moduleId()),
                    (k, current) -> current == failure
                        ? new LoadFailure(current.tenantId(), current.moduleId(),
                            current.moduleRowId(), current.kind(), current.phase(),
                            current.reason(), current.attempts() + 1, current.firstFailedAt(),
                            retryAt, current.escalated())
                        : current);
                log.warn("Could not retry loading module '{}' for tenant {}: {}",
                    failure.moduleId(), failure.tenantId(), e.getMessage());
            }
        }
    }

    private void retryLoad(LoadFailure failure) {
        String key = key(failure.tenantId(), failure.moduleId());
        Optional<TenantModuleData> row = moduleStore.findById(failure.moduleRowId());
        if (loadFailures.get(key) != failure) {
            return; // an enable, disable or uninstall on another thread got there first
        }
        if (row.isEmpty()) {
            log.warn("Module '{}' for tenant {} no longer exists; dropping its load retry and "
                + "unloading it", failure.moduleId(), failure.tenantId());
            unloadModule(failure.tenantId(), failure.moduleId());
            return;
        }
        TenantModuleData module = row.get();
        if (!TenantModuleData.STATUS_ACTIVE.equals(module.status())) {
            log.warn("Module '{}' for tenant {} is now {}; no longer retrying its load",
                failure.moduleId(), failure.tenantId(), module.status());
            loadFailures.computeIfPresent(key, (k, current) -> current == failure
                ? new LoadFailure(current.tenantId(), current.moduleId(), current.moduleRowId(),
                    current.kind(), current.phase(), current.reason(), current.attempts(),
                    current.firstFailedAt(), null, current.escalated())
                : current);
            return;
        }
        log.info("Retrying load of module '{}' for tenant {} (attempt {})",
            failure.moduleId(), failure.tenantId(), failure.attempts() + 1);
        // Strip the refusing handlers so loadModule's already-loaded short-circuit does not
        // apply; the failure entry stays so the attempt count carries over.
        unloadRegistrations(failure.tenantId(), failure.moduleId());
        loadModule(failure.tenantId(), module);
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
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        LoadFailure failure = loadFailures.get(key(tenantId, moduleId));
        PodLoadState podState;
        if (!isLoaded(tenantId, moduleId)) {
            podState = PodLoadState.NOT_LOADED;
        } else if (failure == null) {
            podState = PodLoadState.LOADED;
        } else if (failure.kind() == Kind.TRANSIENT && failure.nextAttemptAt() != null) {
            podState = PodLoadState.RETRYING;
        } else {
            podState = PodLoadState.FAILED;
        }
        state.put("loadStateOnThisPod", podState.name());
        if (failure != null && podState != PodLoadState.NOT_LOADED) {
            state.put("loadFailureKind", failure.kind().name());
            state.put("loadFailurePhase", failure.phase().name());
            if (podState == PodLoadState.RETRYING) {
                state.put("loadRetryAttempts", failure.attempts());
                state.put("loadFailingSince", failure.firstFailedAt().toString());
                state.put("nextLoadRetryAt", failure.nextAttemptAt().toString());
            }
        }
        return state;
    }

    /** Modules (plus the startup module list) awaiting a load retry on this pod. */
    public int pendingLoadRetries() {
        int pending = (int) loadFailures.values().stream()
            .filter(f -> f.kind() == Kind.TRANSIENT && f.nextAttemptAt() != null)
            .count();
        return pending + (startupLoadPending ? 1 : 0);
    }

    /** This pod's failure record for a module, or null. Package-private for tests. */
    LoadFailure loadFailure(String tenantId, String moduleId) {
        return loadFailures.get(key(tenantId, moduleId));
    }

    /** Backoff for transient load failures. Setter so the constructor overloads stay as they are. */
    public void setLoadRetryPolicy(ModuleLoadRetryPolicy retryPolicy) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
    }

    /** Test seam for backoff timing. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Enables load metrics: {@value #METRIC_LOAD_FAILURES} (tagged {@code kind}, {@code phase}),
     * {@value #METRIC_LOAD_RECOVERED}, and the {@value #METRIC_LOAD_RETRY_PENDING} gauge — the one
     * to alert on, since it stays non-zero for as long as a module is down on a pod.
     */
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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
        Set<String> loaded = loadedModules.get(tenantId);
        return loaded != null && loaded.contains(moduleId);
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
        this.stubModeEnabled = stubModeEnabled;
        if (stubModeEnabled) {
            log.warn("Module stub mode is ENABLED: modules without a JAR will load inert handlers "
                + "that do nothing. This must never be enabled in production.");
        }
    }

    /** Injected via setter so the several existing constructor overloads stay as they are. */
    public void setProvenanceStore(ModuleProvenanceStore provenanceStore) {
        this.provenanceStore = provenanceStore;
    }

    /**
     * Checks if JAR-based loading is available.
     */
    public boolean isJarLoadingEnabled() {
        return jarService != null;
    }

    /**
     * A handler that refuses to run, naming the module and why.
     *
     * <p>The descriptor is still manifest-derived so the flow designer renders the step normally
     * rather than showing a blank, and the failure carries a stable {@code ModuleUnavailable} code
     * a flow's Catch clause can be written against.
     */
    private ActionHandler createRefusingHandler(TenantModuleData.TenantModuleActionData action,
                                                 TenantModuleData module, String reason) {
        return new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return action.actionKey();
            }

            @Override
            public ActionResult execute(ActionContext context) {
                // Null-safe on purpose: the refusal itself must be what surfaces, not an NPE
                // from the log line.
                log.error("Refused action '{}': module '{}' v{} is not running for tenant {} ({})",
                    action.actionKey(), module.moduleId(), module.version(),
                    context == null ? module.tenantId() : context.tenantId(), reason);
                throw new ModuleUnavailableException(
                    module.moduleId(), module.version(), reason);
            }

            @Override
            public ActionHandlerDescriptor getDescriptor() {
                return new ActionHandlerDescriptor() {
                    @Override public String getDisplayName() { return action.name(); }
                    @Override public String getCategory() { return action.category(); }
                    @Override public String getDescription() { return action.description(); }
                    @Override public String getConfigSchema() { return action.configSchema(); }
                    @Override public String getInputSchema() { return action.inputSchema(); }
                    @Override public String getOutputSchema() { return action.outputSchema(); }
                };
            }
        };
    }

    /**
     * Creates a stub action handler from manifest metadata.
     * Used when no JAR is available for real ClassLoader-based loading.
     */
    private ActionHandler createStubHandler(TenantModuleData.TenantModuleActionData action,
                                             TenantModuleData module) {
        return new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return action.actionKey();
            }

            @Override
            public ActionResult execute(ActionContext context) {
                log.info("Executing runtime module handler '{}' from module '{}' v{} (stub mode)",
                    action.actionKey(), module.name(), module.version());
                return ActionResult.success(Map.of(
                    "handler", action.actionKey(),
                    "module", module.moduleId(),
                    "status", "EXECUTED",
                    "mode", "stub"
                ));
            }

            @Override
            public ActionHandlerDescriptor getDescriptor() {
                return new ActionHandlerDescriptor() {
                    @Override public String getConfigSchema() { return action.configSchema(); }
                    @Override public String getInputSchema() { return action.inputSchema(); }
                    @Override public String getOutputSchema() { return action.outputSchema(); }
                    @Override public String getDisplayName() { return action.name(); }
                    @Override public String getCategory() { return action.category(); }
                    @Override public String getDescription() { return action.description(); }
                };
            }
        };
    }
}
