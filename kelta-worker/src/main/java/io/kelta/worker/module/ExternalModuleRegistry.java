package io.kelta.worker.module;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.service.FieldMaskingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This pod's view of every installed external module, and the registrations that make the
 * platform call them ({@code specs/module-platform/9-external-modules.md} §3.5–§3.6). Built from
 * the {@code tenant_module} rows alone — nothing of an external module is loaded on a pod:
 *
 * <ul>
 *   <li><b>Action handlers</b> ({@link RemoteActionHandler}) are registered for every install row
 *       that exists, whatever its status, so a flow step naming a disabled module fails with
 *       {@code ModuleUnavailableException} instead of {@code ResourceNotFound}.</li>
 *   <li><b>Before-save hooks</b> ({@link RemoteBeforeSaveHook}) and the <b>entitlements port</b>
 *       ({@link RemoteEntitlementProvider}) are registered only while the install is callable
 *       ({@code ACTIVE} or {@code DEGRADED}): a disabled module must never block a write, and its
 *       members fall back to no entitlements.</li>
 * </ul>
 *
 * <p><b>Consistency (Critical Rule 1).</b> Every install change publishes
 * {@code kelta.config.module.changed.<tenantId>.<moduleId>}; every pod's {@link ModuleEventListener}
 * calls {@link #refresh} for it. {@link ExternalModuleService} also refreshes its own pod at once
 * (read-after-write), and {@link #reconcile} re-reads every external row every
 * {@code kelta.modules.external.reconcile-interval-ms} (60 s) so a missed event heals within a
 * minute. Registrations are rebuilt only when an install's fingerprint (version, manifest,
 * callability, principal) changes; the {@link Endpoint} snapshot — row and principal access —
 * is replaced on every refresh, so a profile edit reaches the record filter within one reconcile.
 *
 * <p><b>Health.</b> Each reconcile also probes {@code GET /kelta/v1/health} for an install that is
 * {@code DEGRADED} or whose breaker is open on this pod, so a module that came back recovers
 * without waiting for real traffic.
 */
public class ExternalModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalModuleRegistry.class);

    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    static final String ENTITLEMENTS_PORT = "entitlements";
    static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    /**
     * An install as this pod currently knows it.
     *
     * @param row      the {@code tenant_module} row (status as last read)
     * @param manifest the approved manifest the row stores
     * @param access   what the install's service principal may see and write
     */
    public record Endpoint(TenantModuleData row, ExternalModuleManifest manifest, ModulePrincipalAccess access) {}

    private record Registration(String fingerprint, Set<String> actionKeys, List<BeforeSaveHook> hooks,
                                RemoteEntitlementProvider entitlements) {}

    private final ModuleStore moduleStore;
    private final ActionHandlerRegistry actionHandlers;
    private final BeforeSaveHookRegistry hookRegistry;
    private final ModuleServiceRegistry moduleServices;
    private final ExternalModuleInvoker invoker;
    private final JdbcTemplate jdbcTemplate;
    private final CollectionRegistry collectionRegistry;
    private final FieldMaskingService masking;
    private final ObjectMapper objectMapper;
    private final ModuleManifestParser parser;

    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    /** Set once the first {@link #reconcile} has read every row; see {@link #ensureInitialized}. */
    private volatile boolean initialized;
    /** Issues long-running actions' tasks (part 9h); null, every action call is synchronous. */
    private volatile ModuleAsyncTaskStore asyncTasks;

    public ExternalModuleRegistry(ModuleStore moduleStore, ActionHandlerRegistry actionHandlers,
                                  BeforeSaveHookRegistry hookRegistry, ModuleServiceRegistry moduleServices,
                                  ExternalModuleInvoker invoker, JdbcTemplate jdbcTemplate,
                                  CollectionRegistry collectionRegistry, FieldMaskingService masking,
                                  ObjectMapper objectMapper) {
        this.moduleStore = Objects.requireNonNull(moduleStore);
        this.actionHandlers = Objects.requireNonNull(actionHandlers);
        this.hookRegistry = Objects.requireNonNull(hookRegistry);
        this.moduleServices = moduleServices;
        this.invoker = Objects.requireNonNull(invoker);
        this.jdbcTemplate = jdbcTemplate;
        this.collectionRegistry = collectionRegistry;
        this.masking = masking;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.parser = new ModuleManifestParser(objectMapper);
    }

    /** Lets action handlers registered from now on issue tasks for long-running actions (part 9h). */
    public void setAsyncTasks(ModuleAsyncTaskStore asyncTasks) {
        this.asyncTasks = asyncTasks;
    }

    /** The install's current snapshot on this pod, if the tenant has it installed. */
    public Optional<Endpoint> endpoint(String tenantId, String moduleId) {
        return Optional.ofNullable(endpoints.get(key(tenantId, moduleId)));
    }

    /**
     * The callable installs of {@code tenantId} whose manifest {@code events[]} subscribe to
     * {@code type} ({@code created}, {@code updated}, {@code deleted}) on {@code collection}
     * (part 9e, event delivery).
     */
    public List<Endpoint> eventSubscribers(String tenantId, String collection, String type) {
        List<Endpoint> subscribers = new ArrayList<>();
        for (Endpoint endpoint : endpoints.values()) {
            if (!endpoint.row().tenantId().equals(tenantId) || !ExternalModuleInvoker.callable(endpoint.row())) {
                continue;
            }
            for (ExternalModuleManifest.EventSubscription subscription : endpoint.manifest().events()) {
                if (subscription.collection().equals(collection) && subscription.types().contains(type)) {
                    subscribers.add(endpoint);
                    break;
                }
            }
        }
        return subscribers;
    }

    /**
     * Runs the first reconcile now if none has finished yet. A consumer that may see a message
     * before {@code ApplicationReadyEvent}'s reconcile completes (NATS subscriptions start in the
     * same event) calls this first, so a pod that just started never treats "not read yet" as
     * "not installed". No tenant may be bound: the reconcile reads every tenant's rows.
     */
    public void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (!initialized) {
                reconcile();
            }
        }
    }

    /** Re-reads one install (its {@code module.changed} event, or a local write) and re-registers it. */
    public void refresh(String tenantId, String moduleId) {
        try {
            Optional<TenantModuleData> row = TenantContext.callWithTenant(tenantId,
                    () -> moduleStore.findByTenantAndModuleId(tenantId, moduleId));
            if (row.isPresent() && row.get().isExternal()) {
                TenantContext.runWithTenant(tenantId, () -> apply(row.get()));
            } else {
                remove(tenantId, moduleId);
            }
        } catch (RuntimeException e) {
            // The next reconcile retries; a refresh never fails the caller's request or event.
            log.error("Could not refresh external module '{}' for tenant {}: {}", moduleId, tenantId,
                    e.getMessage(), e);
        }
    }

    /**
     * Re-reads every external install (no tenant bound: the row query runs under the RLS admin
     * bypass), re-registers what changed, drops what is gone, and probes the health of installs
     * that are {@code DEGRADED} or have an open breaker here.
     */
    public void reconcile() {
        List<TenantModuleData> rows = moduleStore.findAllExternal();
        Set<String> seen = new HashSet<>();
        for (TenantModuleData row : rows) {
            seen.add(key(row.tenantId(), row.moduleId()));
            try {
                TenantContext.runWithTenant(row.tenantId(), () -> apply(row));
            } catch (RuntimeException e) {
                log.error("Could not reconcile external module '{}' for tenant {}: {}", row.moduleId(),
                        row.tenantId(), e.getMessage(), e);
            }
        }
        for (String known : List.copyOf(endpoints.keySet())) {
            if (!seen.contains(known)) {
                int split = known.indexOf('\n');
                remove(known.substring(0, split), known.substring(split + 1));
            }
        }
        initialized = true;
        for (TenantModuleData row : rows) {
            if (ExternalModuleInvoker.callable(row) && (TenantModuleData.STATUS_DEGRADED.equals(row.status())
                    || invoker.breakerOpen(row.id()))) {
                probe(row);
            }
        }
    }

    /** Live health for {@code GET /api/modules/{id}/health}: the module's answer plus this pod's breaker. */
    public Map<String, Object> health(TenantModuleData row) {
        Map<String, Object> health = new LinkedHashMap<>();
        if (!ExternalModuleInvoker.callable(row)) {
            health.put("moduleHealth", null);
        } else {
            try {
                health.put("moduleHealth", TenantContext.callWithTenantIfUnbound(row.tenantId(), null,
                        () -> callHealth(row)));
            } catch (ModuleUnavailableException e) {
                health.put("moduleHealth", Map.of("status", "unreachable", "error", e.getMessage()));
            }
        }
        health.putAll(invoker.breakerState(row.id()));
        return health;
    }

    /**
     * Runs a schedule's action ({@code ScheduledJobExecutorService}, {@code MODULE_ACTION} jobs).
     *
     * @throws ModuleUnavailableException the install is gone, not callable, or no longer declares it
     */
    public ActionResult runScheduledAction(String tenantId, String moduleId, String actionKey,
                                           String idempotencyKey) {
        Endpoint endpoint = endpoint(tenantId, moduleId).orElseThrow(() -> ModuleUnavailableException.of(moduleId,
                "Module '" + moduleId + "' is not installed for this tenant"));
        String handlerKey = moduleId + ":" + actionKey;
        TenantModuleData.TenantModuleActionData action = endpoint.row().actions() == null ? null
                : endpoint.row().actions().stream().filter(a -> handlerKey.equals(a.actionKey())).findFirst()
                        .orElse(null);
        if (action == null) {
            throw ModuleUnavailableException.of(moduleId, "Module '" + moduleId + "' v" + endpoint.row().version()
                    + " no longer declares action '" + actionKey + "'");
        }
        ActionContext context = ActionContext.builder().tenantId(tenantId).build();
        return handler(tenantId, moduleId, action).run(context, idempotencyKey);
    }

    /**
     * Whether the install is callable on this pod right now (its last-read status). An install this
     * pod has not seen yet — a schedule firing before the first reconcile — is read first.
     */
    public boolean callable(String tenantId, String moduleId) {
        if (endpoint(tenantId, moduleId).isEmpty()) {
            refresh(tenantId, moduleId);
        }
        return endpoint(tenantId, moduleId).map(e -> ExternalModuleInvoker.callable(e.row())).orElse(false);
    }

    /**
     * The acting user for a call, from the request's {@link CallerContext}; {@code null} on a flow or
     * scheduled path, where no person is acting.
     */
    static ModuleAssertionSigner.Actor currentActor(String profileId) {
        return CallerContext.current()
                .filter(caller -> caller.userId() != null && !caller.userId().isBlank())
                .map(caller -> new ModuleAssertionSigner.Actor(caller.userId(),
                        caller.userType() == null ? null : caller.userType().name(), profileId))
                .orElse(null);
    }

    /** A protocol response body as a JSON object; anything else is an invalid response. */
    static JsonNode readObject(ObjectMapper objectMapper, OutboundHttpClient.Response response) {
        JsonNode json;
        try {
            json = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new ExternalModuleInvoker.InvalidResponseException("the body is not JSON");
        }
        if (json == null || !json.isObject()) {
            throw new ExternalModuleInvoker.InvalidResponseException("the body is not a JSON object");
        }
        return json;
    }

    // ── registration ──────────────────────────────────────────────────────────────────────

    private synchronized void apply(TenantModuleData row) {
        String key = key(row.tenantId(), row.moduleId());
        ExternalModuleManifest manifest;
        try {
            manifest = parser.parseExternal(row.manifest());
        } catch (RuntimeException e) {
            log.error("External module '{}' (tenant {}) has an unreadable stored manifest; unregistering it: {}",
                    row.moduleId(), row.tenantId(), e.getMessage());
            remove(row.tenantId(), row.moduleId());
            return;
        }
        ModulePrincipalAccess access = jdbcTemplate == null ? ModulePrincipalAccess.NONE
                : ModulePrincipalAccess.load(jdbcTemplate, row.tenantId(), row.serviceUserId());
        endpoints.put(key, new Endpoint(row, manifest, access));

        String fingerprint = fingerprint(row);
        Registration current = registrations.get(key);
        if (current != null && current.fingerprint().equals(fingerprint)) {
            return;
        }
        registrations.put(key, register(row, manifest, fingerprint, current));
        log.info("Registered external module '{}' v{} for tenant {} ({}): {} action(s), {} hook(s){}",
                row.moduleId(), row.version(), row.tenantId(), row.status(),
                row.actions() == null ? 0 : row.actions().size(),
                ExternalModuleInvoker.callable(row) ? manifest.hooks().size() : 0,
                ExternalModuleInvoker.callable(row) && declaresEntitlements(manifest) ? ", entitlements" : "");
    }

    private Registration register(TenantModuleData row, ExternalModuleManifest manifest, String fingerprint,
                                  Registration previous) {
        String tenantId = row.tenantId();
        boolean callable = ExternalModuleInvoker.callable(row);

        // Actions: put replaces by key, so the new handler takes over in one step; keys the new
        // version dropped are removed afterwards.
        Set<String> actionKeys = new HashSet<>();
        for (TenantModuleData.TenantModuleActionData action : row.actions() == null
                ? List.<TenantModuleData.TenantModuleActionData>of() : row.actions()) {
            actionHandlers.registerTenantHandler(tenantId, handler(tenantId, row.moduleId(), action));
            actionKeys.add(action.actionKey());
        }

        // Hooks: the new set is registered before the old one goes, so a write in between sees a
        // hook twice rather than not at all.
        List<BeforeSaveHook> hooks = new ArrayList<>();
        if (callable) {
            for (ExternalModuleManifest.Hook declaration : manifest.hooks()) {
                RemoteBeforeSaveHook hook = new RemoteBeforeSaveHook(tenantId, row.moduleId(), declaration, this,
                        invoker, collectionRegistry, masking, objectMapper);
                hookRegistry.registerTenantHook(tenantId, hook);
                hooks.add(hook);
            }
        }

        RemoteEntitlementProvider entitlements = null;
        if (previous != null) {
            Set<String> dropped = new HashSet<>(previous.actionKeys());
            dropped.removeAll(actionKeys);
            if (!dropped.isEmpty()) {
                actionHandlers.removeTenantHandlers(tenantId, dropped);
            }
            hookRegistry.removeTenantHooks(tenantId, previous.hooks());
            if (previous.entitlements() != null) {
                if (callable && declaresEntitlements(manifest)) {
                    // Keep the registered instance (the port admits one per tenant); only its
                    // cache belongs to the old version.
                    entitlements = previous.entitlements();
                    entitlements.invalidate();
                } else {
                    moduleServices.remove(tenantId, Map.of(EntitlementProvider.class, previous.entitlements()));
                }
            }
        }
        if (entitlements == null && callable && declaresEntitlements(manifest) && moduleServices != null) {
            RemoteEntitlementProvider provider = new RemoteEntitlementProvider(tenantId, row.moduleId(), this,
                    invoker, objectMapper);
            try {
                moduleServices.register(tenantId, EntitlementProvider.class, provider);
                entitlements = provider;
            } catch (IllegalArgumentException e) {
                log.error("External module '{}' declares the entitlements port, but tenant {} already has a "
                        + "provider; not registering it: {}", row.moduleId(), tenantId, e.getMessage());
            }
        }
        return new Registration(fingerprint, Set.copyOf(actionKeys), List.copyOf(hooks), entitlements);
    }

    private synchronized void remove(String tenantId, String moduleId) {
        String key = key(tenantId, moduleId);
        Endpoint endpoint = endpoints.remove(key);
        Registration registration = registrations.remove(key);
        if (registration != null) {
            if (!registration.actionKeys().isEmpty()) {
                actionHandlers.removeTenantHandlers(tenantId, registration.actionKeys());
            }
            hookRegistry.removeTenantHooks(tenantId, registration.hooks());
            if (registration.entitlements() != null && moduleServices != null) {
                moduleServices.remove(tenantId, Map.of(EntitlementProvider.class, registration.entitlements()));
            }
            log.info("Unregistered external module '{}' for tenant {}", moduleId, tenantId);
        }
        if (endpoint != null) {
            invoker.forget(endpoint.row().id());
        }
    }

    private RemoteActionHandler handler(String tenantId, String moduleId,
                                        TenantModuleData.TenantModuleActionData action) {
        return new RemoteActionHandler(tenantId, moduleId, action, this, invoker, collectionRegistry, masking,
                objectMapper, asyncTasks);
    }

    private void probe(TenantModuleData row) {
        try {
            TenantContext.runWithTenant(row.tenantId(), () -> callHealth(row));
            log.info("External module '{}' (tenant {}) answered its health check", row.moduleId(), row.tenantId());
        } catch (ModuleUnavailableException e) {
            log.debug("External module '{}' (tenant {}) health probe: {}", row.moduleId(), row.tenantId(),
                    e.getMessage());
        } catch (RuntimeException e) {
            log.warn("External module '{}' (tenant {}) health probe failed: {}", row.moduleId(), row.tenantId(),
                    e.getMessage());
        }
    }

    private Map<String, Object> callHealth(TenantModuleData row) {
        ModuleHttpClient.Call call = new ModuleHttpClient.Call("GET", ModuleHttpClient.HEALTH_PATH, null, null, null,
                Map.of(), HEALTH_TIMEOUT, null);
        return invoker.call(row, ExternalModuleInvoker.Kind.HEALTH, call, response -> {
            JsonNode json = readObject(objectMapper, response);
            JsonNode status = json.get("status");
            if (status == null || !status.isString()) {
                throw new ExternalModuleInvoker.InvalidResponseException("Health.status must be a string");
            }
            return objectMapper.convertValue(json, MAP_TYPE);
        });
    }

    private static boolean declaresEntitlements(ExternalModuleManifest manifest) {
        return manifest.services().stream().anyMatch(s -> ENTITLEMENTS_PORT.equals(s.port()));
    }

    private static String fingerprint(TenantModuleData row) {
        String actions = row.actions() == null ? "" : String.join(",", row.actions().stream()
                .map(TenantModuleData.TenantModuleActionData::actionKey).sorted().toList());
        return row.version() + "|" + sha256(row.manifest()) + "|" + ExternalModuleInvoker.callable(row) + "|"
                + row.serviceUserId() + "|" + actions;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String tenantId, String moduleId) {
        return tenantId + "\n" + moduleId;
    }
}
