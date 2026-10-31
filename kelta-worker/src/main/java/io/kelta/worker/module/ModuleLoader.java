package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.workflow.module.ModuleContext;
import io.kelta.worker.module.ModuleLoadFailureClassifier.Kind;
import io.kelta.worker.module.ModuleLoadFailureClassifier.Phase;
import io.kelta.worker.module.RuntimeModuleManager.PodLoadState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.kelta.worker.module.RuntimeModuleManager.METRIC_LOAD_FAILURES;
import static io.kelta.worker.module.RuntimeModuleManager.METRIC_LOAD_RECOVERED;

/**
 * Loads and unloads a tenant's modules on this pod, and owns everything that is per-pod about
 * that: which modules are loaded, the classloader, module instance, hooks and services each load
 * registered, and the load-failure / retry bookkeeping.
 *
 * <p>A load re-verifies the JAR (checksum, then signature), classloads it in a
 * {@link SandboxedModuleClassLoader}, runs {@code onStartup}, and registers what the module
 * provides. A failure fails closed — refusing handlers are registered — and is classified by
 * {@link ModuleLoadFailureClassifier}: a module fault is persisted {@code QUARANTINED}, an
 * infrastructure fault leaves the status alone and is retried by {@link ModuleLoadRetryScheduler}
 * (via {@link RuntimeModuleManager#retryPendingLoads()}).
 *
 * <p>This is the single owner of all mutable module-load state; nothing else holds a copy.
 */
final class ModuleLoader {

    /**
     * Logs under {@link RuntimeModuleManager}'s name rather than this class's: this code was split
     * out of that class, and log queries and alerts select on the logger name.
     */
    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);

    private final ModuleStore moduleStore;
    private final ActionHandlerRegistry actionHandlerRegistry;
    private final ModuleManifestParser manifestParser;
    private final ModuleJarService jarService;
    private final ModuleContext moduleContext;
    private final ModuleSignatureVerifier signatureVerifier;
    private final BeforeSaveHookRegistry beforeSaveHookRegistry;
    private final ModuleServiceRegistry moduleServiceRegistry;
    private final ModuleRegistrar registrar;

    /** Routes each loaded module serves. Null in tests that do not exercise HTTP dispatch. */
    private ModuleRouteRegistry moduleRouteRegistry;

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

    ModuleLoader(ModuleStore moduleStore, ActionHandlerRegistry actionHandlerRegistry,
                 ModuleManifestParser manifestParser, ModuleJarService jarService,
                 ModuleContext moduleContext, ModuleSignatureVerifier signatureVerifier,
                 BeforeSaveHookRegistry beforeSaveHookRegistry,
                 ModuleServiceRegistry moduleServiceRegistry, ModuleRegistrar registrar) {
        this.moduleStore = moduleStore;
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.manifestParser = manifestParser;
        this.jarService = jarService;
        this.moduleContext = moduleContext;
        this.signatureVerifier = signatureVerifier;
        this.beforeSaveHookRegistry = beforeSaveHookRegistry;
        this.moduleServiceRegistry = moduleServiceRegistry;
        this.registrar = registrar;
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
        if (module.isExternal()) {
            // An external module runs out of process: there is nothing to classload or verify on
            // a pod, and quarantining it for having no JAR would write a status every pod honours.
            // Its remote handlers are registered from the module's rows (part 9d).
            log.debug("Module '{}' for tenant {} is external; nothing to load on this pod",
                module.moduleId(), tenantId);
            return;
        }
        Set<String> loaded = loadedModules.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet());
        if (loaded.contains(module.moduleId())) {
            log.debug("Module '{}' already loaded for tenant {}", module.moduleId(), tenantId);
            return;
        }

        if (module.s3Key() != null && jarService != null) {
            loadFromJar(tenantId, module);
        } else if (stubModeEnabled) {
            // Explicit dev opt-in only. Never reached by falling back from an error.
            registrar.loadWithStubs(tenantId, module);
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
            verifyDownloadedJar(moduleStore, signatureVerifier, module, jarUrl);
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
            List<BeforeSaveHook> hooks = registrar.registerTenantHooks(tenantId, keltaModule);
            // Recorded immediately: anything that throws after this point must be able to find
            // these to unwind them, and registerTenantServices below can reject a bad port.
            registeredHooks.put(classLoaderKey, hooks);

            // Services this module publishes for platform code to call. Self-unwinding: a rejected
            // port removes the ports already accepted from the same module before rethrowing, so a
            // module never ends up half-published.
            ModuleManifest storedManifest = parseStoredManifest(module);
            Map<Class<?>, Object> services = registrar.registerTenantServices(
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
        registrar.registerRefusingHandlers(tenantId, module, reason);
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

        registrar.registerRefusingHandlers(tenantId, module,
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
    static void verifyDownloadedJar(ModuleStore moduleStore,
                                    ModuleSignatureVerifier signatureVerifier,
                                    TenantModuleData module, URL jarUrl) throws Exception {
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
                ModuleInstaller.markFailed(moduleStore, module.id());
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

    /** Enables load metrics; the pending-retry gauge is registered by the manager. */
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Checks if a module is loaded for a tenant.
     */
    public boolean isLoaded(String tenantId, String moduleId) {
        Set<String> loaded = loadedModules.get(tenantId);
        return loaded != null && loaded.contains(moduleId);
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

    /** Injected via setter so the existing constructor overloads stay as they are. */
    void setModuleRouteRegistry(ModuleRouteRegistry moduleRouteRegistry) {
        this.moduleRouteRegistry = moduleRouteRegistry;
    }
}
