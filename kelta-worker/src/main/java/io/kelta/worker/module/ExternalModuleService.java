package io.kelta.worker.module;

import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.service.ModuleServicePrincipalService.CreateRequest;
import io.kelta.worker.service.ModuleServicePrincipalService.FieldGrant;
import io.kelta.worker.service.ModuleServicePrincipalService.ObjectGrant;
import io.kelta.worker.service.ModuleServicePrincipalService.Permissions;
import io.kelta.worker.service.TenantSlugResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Install, upgrade, enable, disable and uninstall for <b>external</b> modules — HTTPS services
 * installed by URL ({@code specs/module-platform/9-external-modules.md} §3.5). JAR modules keep
 * going through {@link RuntimeModuleManager}; nothing here loads code.
 *
 * <p><b>Install</b> re-plans and refuses a stale {@code planHash} (409) or a plan with blockers
 * (422), then: writes the {@code tenant_module} row ({@code INSTALLING}, with {@code base_url}),
 * provisions the metadata package with provenance, creates the service principal from the
 * approved permissions plus full access to the collections the module now owns, writes the
 * action rows, and sends {@code lifecycle installed}. Success is {@code ACTIVE}. A failing step
 * marks the row {@code FAILED} with {@code last_error} and stops; every step is idempotent, so
 * {@link #enable} retries from the stored (approved) manifest without re-fetching it.
 *
 * <p>Every outcome publishes {@code kelta.config.module.changed.<tenantId>.<moduleId>}
 * (Critical Rule 1). No pod loads anything for an external module; part 9d's endpoint registry
 * refreshes from the row on that event.
 */
public class ExternalModuleService {

    private static final Logger log = LoggerFactory.getLogger(ExternalModuleService.class);

    static final String EVENT_INSTALLED = "installed";
    static final String EVENT_UPGRADED = "upgraded";
    static final String EVENT_ENABLED = "enabled";
    static final String EVENT_DISABLED = "disabled";
    static final String EVENT_UNINSTALLED = "uninstalled";

    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ExternalModulePlanner planner;
    private final ModuleStore moduleStore;
    private final ModuleProvenanceStore provenanceStore;
    private final ModuleMetadataProvisioner provisioner;
    private final ModuleServicePrincipalService principals;
    private final ModuleAccessTokenService tokens;
    private final ModuleHttpClient http;
    private final ModuleConfigEventPublisher eventPublisher;
    private final TenantSlugResolver tenantSlugResolver;
    private final UserIdResolver userIdResolver;
    private final ModuleManifestParser parser;

    public ExternalModuleService(ExternalModulePlanner planner, ModuleStore moduleStore,
                                 ModuleProvenanceStore provenanceStore, ModuleMetadataProvisioner provisioner,
                                 ModuleServicePrincipalService principals, ModuleAccessTokenService tokens,
                                 ModuleHttpClient http, ModuleConfigEventPublisher eventPublisher,
                                 TenantSlugResolver tenantSlugResolver, UserIdResolver userIdResolver,
                                 ObjectMapper objectMapper) {
        this.planner = Objects.requireNonNull(planner);
        this.moduleStore = Objects.requireNonNull(moduleStore);
        this.provenanceStore = Objects.requireNonNull(provenanceStore);
        this.provisioner = Objects.requireNonNull(provisioner);
        this.principals = Objects.requireNonNull(principals);
        this.tokens = Objects.requireNonNull(tokens);
        this.http = Objects.requireNonNull(http);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.tenantSlugResolver = Objects.requireNonNull(tenantSlugResolver);
        this.userIdResolver = Objects.requireNonNull(userIdResolver);
        this.parser = new ModuleManifestParser(objectMapper);
    }

    /** {@code POST /api/modules/plan}. */
    public ExternalModulePlan plan(String tenantId, String baseUrl, String userId) {
        return planner.plan(tenantId, baseUrl, userId);
    }

    /**
     * {@code POST /api/modules/install} with a {@code baseUrl}.
     *
     * @throws IllegalArgumentException          no {@code planHash}, or a bad {@code baseUrl}
     * @throws ExternalModulePlan.PlanChangedException  the plan changed (409)
     * @throws ExternalModulePlan.PlanBlockedException  the plan has blockers (422)
     * @throws IllegalStateException             already installed: upgrade instead (409)
     */
    public TenantModuleData install(String tenantId, String baseUrl, String planHash, String userId) {
        if (planHash == null || planHash.isBlank()) {
            throw new IllegalArgumentException("planHash is required: POST /api/modules/plan and send its planHash");
        }
        ExternalModulePlan plan = checked(planner.plan(tenantId, baseUrl, userId), planHash);
        if (ExternalModulePlan.MODE_NO_OP.equals(plan.mode())) {
            return plan.installed();
        }
        if (plan.installed() != null) {
            throw new IllegalStateException("Module '" + plan.manifest().id() + "' is already installed; "
                    + "POST /api/modules/" + plan.manifest().id() + "/upgrade to upgrade it");
        }
        ExternalModuleManifest manifest = plan.manifest();
        String id = UUID.randomUUID().toString();
        TenantModuleData row = new TenantModuleData(id, tenantId, manifest.id(), manifest.name(),
                manifest.version(), manifest.description(), plan.baseUrl(), null, null, null,
                plan.manifestJson(), TenantModuleData.STATUS_INSTALLING, userId, null, null, null, List.of(),
                plan.baseUrl(), manifest.protocolVersion(), null, null);
        moduleStore.createModule(row);
        log.info("Installing external module '{}' v{} from {} for tenant {}", manifest.id(), manifest.version(),
                plan.baseUrl(), tenantId);
        return apply(reload(row), manifest, userId, EVENT_INSTALLED, null, ModuleChangeType.INSTALLED);
    }

    /** {@code POST /api/modules/{id}/upgrade}: same checks, from the stored base URL. */
    public TenantModuleData upgrade(String tenantId, String moduleId, String planHash, String userId) {
        TenantModuleData row = external(tenantId, moduleId);
        if (planHash == null || planHash.isBlank()) {
            throw new IllegalArgumentException("planHash is required: POST /api/modules/plan with the module's "
                    + "baseUrl and send its planHash");
        }
        ExternalModulePlan plan = checked(planner.planUpgrade(tenantId, row, userId), planHash);
        if (ExternalModulePlan.MODE_NO_OP.equals(plan.mode())) {
            return row;
        }
        ExternalModuleManifest manifest = plan.manifest();
        String fromVersion = row.version();
        moduleStore.updateExternalManifest(row.id(), manifest.version(), manifest.name(), manifest.description(),
                plan.manifestJson(), manifest.protocolVersion());
        log.info("Upgrading external module '{}' {} → {} for tenant {}", moduleId, fromVersion,
                manifest.version(), tenantId);
        return apply(reload(row), manifest, userId, EVENT_UPGRADED, fromVersion, ModuleChangeType.UPGRADED);
    }

    /**
     * {@code POST /api/modules/{id}/enable}. From {@code DISABLED}: back to {@code ACTIVE} and
     * {@code lifecycle enabled}. From {@code FAILED} (or an install that never finished): re-runs
     * the install steps from the stored manifest. {@code ACTIVE} is left alone.
     */
    public TenantModuleData enable(String tenantId, String moduleId, String userId) {
        TenantModuleData row = external(tenantId, moduleId);
        if (TenantModuleData.STATUS_ACTIVE.equals(row.status())) {
            return row;
        }
        if (TenantModuleData.STATUS_DISABLED.equals(row.status())) {
            moduleStore.updateStatusWithError(row.id(), TenantModuleData.STATUS_ACTIVE, null);
            TenantModuleData active = reload(row);
            notifyBestEffort(active, ModuleHttpClient.LifecycleEvent.of(EVENT_ENABLED, active));
            eventPublisher.publishEvent(active, ModuleChangeType.ENABLED);
            return active;
        }
        ExternalModuleManifest manifest = parser.parseExternal(row.manifest());
        log.info("Retrying the install of external module '{}' for tenant {} (was {})", moduleId, tenantId,
                row.status());
        return apply(row, manifest, userId, EVENT_INSTALLED, null, ModuleChangeType.ENABLED);
    }

    /** {@code POST /api/modules/{id}/disable}: no invocations from now on; {@code lifecycle disabled}. */
    public TenantModuleData disable(String tenantId, String moduleId) {
        TenantModuleData row = external(tenantId, moduleId);
        moduleStore.updateStatus(row.id(), TenantModuleData.STATUS_DISABLED);
        TenantModuleData disabled = reload(row);
        notifyBestEffort(disabled, ModuleHttpClient.LifecycleEvent.of(EVENT_DISABLED, disabled));
        eventPublisher.publishEvent(disabled, ModuleChangeType.DISABLED);
        return disabled;
    }

    /**
     * {@code DELETE /api/modules/{id}} for an external module: {@code lifecycle uninstalled}
     * (best effort, while the principal can still get a token), then the principal is deactivated
     * and the row deleted. Removing provisioned resources is the caller's next step
     * ({@link ModuleUninstaller#apply}), after this, so nothing of the module runs while they go.
     */
    public void uninstall(TenantModuleData row, boolean purgeData) {
        notifyBestEffort(row, new ModuleHttpClient.LifecycleEvent(EVENT_UNINSTALLED, row.tenantId(),
                row.version(), null, purgeData, null));
        if (row.serviceUserId() != null) {
            try {
                principals.deactivate(row.tenantId(), row.serviceUserId());
            } catch (IllegalArgumentException e) {
                log.warn("Service principal {} of module '{}' was already gone: {}", row.serviceUserId(),
                        row.moduleId(), e.getMessage());
            }
        }
        tokens.evict(row.connectedAppId());
        moduleStore.deleteModule(row.id());
        eventPublisher.publishEvent(row, ModuleChangeType.UNINSTALLED);
        log.info("Uninstalled external module '{}' from tenant {}", row.moduleId(), row.tenantId());
    }

    // ── the install steps ────────────────────────────────────────────────────────────────

    private TenantModuleData apply(TenantModuleData row, ExternalModuleManifest manifest, String userId,
                                   String lifecycleEvent, String fromVersion, ModuleChangeType changeType) {
        String tenantId = row.tenantId();
        ModuleMetadataProvisioner.ProvisionResult provisioned;
        try {
            provisioned = provisioner.provisionPackage(tenantId, manifest.id(), manifest.metadata(),
                    ownedKeys(tenantId, manifest.id()), userId);
            for (var resource : provisioned.resources()) {
                provenanceStore.record(tenantId, manifest.id(), manifest.version(), resource.type(),
                        resource.naturalKey(), resource.resourceId(), resource.ownership(), resource.contentHash());
            }
        } catch (RuntimeException e) {
            return fail(row, changeType, "metadata provisioning failed: " + e.getMessage());
        }
        if (provisioned.hasFailures()) {
            return fail(row, changeType, "metadata provisioning failed: " + provisioned.failures().stream()
                    .map(f -> f.type() + " " + f.naturalKey() + ": " + f.error()).toList());
        }

        CreateRequest principal = principalRequest(manifest, ownedCollections(tenantId, manifest),
                creatorId(tenantId, userId));
        try {
            if (row.serviceUserId() == null) {
                var created = principals.create(tenantId, principal);
                moduleStore.updateServicePrincipal(row.id(), created.connectedAppId(), created.userId());
            } else if (EVENT_UPGRADED.equals(lifecycleEvent)) {
                principals.updatePermissions(tenantId, row.serviceUserId(), principal);
                tokens.evict(row.connectedAppId());
            }
        } catch (RuntimeException e) {
            return fail(row, changeType, "service principal: " + e.getMessage());
        }

        moduleStore.replaceActions(row.id(), actions(row.id(), manifest));

        TenantModuleData current = reload(row);
        try {
            http.lifecycle(current, slug(tenantId), new ModuleHttpClient.LifecycleEvent(lifecycleEvent, tenantId,
                    current.version(), fromVersion, null, null), tokens.accessToken(current));
        } catch (RuntimeException e) {
            return fail(current, changeType, "lifecycle '" + lifecycleEvent + "' failed: " + e.getMessage());
        }

        moduleStore.updateStatusWithError(row.id(), TenantModuleData.STATUS_ACTIVE, null);
        TenantModuleData active = reload(row);
        eventPublisher.publishEvent(active, changeType);
        log.info("External module '{}' v{} is ACTIVE for tenant {}", active.moduleId(), active.version(), tenantId);
        return active;
    }

    private TenantModuleData fail(TenantModuleData row, ModuleChangeType changeType, String error) {
        log.error("External module '{}' is FAILED for tenant {}: {}", row.moduleId(), row.tenantId(), error);
        moduleStore.updateStatusWithError(row.id(), TenantModuleData.STATUS_FAILED, error);
        TenantModuleData failed = reload(row);
        eventPublisher.publishEvent(failed, changeType);
        return failed;
    }

    private void notifyBestEffort(TenantModuleData row, ModuleHttpClient.LifecycleEvent event) {
        try {
            http.lifecycle(row, slug(row.tenantId()), event, tokens.accessToken(row));
        } catch (RuntimeException e) {
            log.warn("Module '{}' (tenant {}) did not accept lifecycle '{}': {}", row.moduleId(), row.tenantId(),
                    event.event(), e.getMessage());
        }
    }

    private static ExternalModulePlan checked(ExternalModulePlan plan, String planHash) {
        if (!plan.planHash().equals(planHash)) {
            throw new ExternalModulePlan.PlanChangedException(plan.attributes());
        }
        if (!plan.blockers().isEmpty()) {
            throw new ExternalModulePlan.PlanBlockedException(plan.attributes(), plan.blockers());
        }
        return plan;
    }

    /**
     * The collections the module owns: those its package declares and its provenance says it
     * {@code CREATED}. An adopted collection is the tenant's, so it needs an explicit grant.
     */
    private List<String> ownedCollections(String tenantId, ExternalModuleManifest manifest) {
        Set<String> created = ownedKeys(tenantId, manifest.id());
        List<String> owned = new ArrayList<>();
        for (String collection : manifest.provisionedCollections()) {
            if (created.contains(ModuleMetadataProvisioner.COLLECTION + ":" + collection)) {
                owned.add(collection);
            }
        }
        return owned;
    }

    private Set<String> ownedKeys(String tenantId, String moduleId) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : provenanceStore.findByModule(tenantId, moduleId)) {
            if (ModuleProvenanceStore.OWNERSHIP_CREATED.equals(row.get("ownership"))) {
                keys.add(row.get("resource_type") + ":" + row.get("natural_key"));
            }
        }
        return keys;
    }

    static CreateRequest principalRequest(ExternalModuleManifest manifest, List<String> ownedCollections,
                                          String userId) {
        ExternalModuleManifest.Permissions permissions = manifest.permissions();
        return new CreateRequest(manifest.id(), manifest.name(), new Permissions(
                permissions.objects().stream().map(o -> new ObjectGrant(o.collection(), o.create(), o.read(),
                        o.edit(), o.delete())).toList(),
                permissions.fields().stream().map(f -> new FieldGrant(f.collection(), f.field(),
                        f.visibility())).toList(),
                permissions.system()),
                ownedCollections, userId);
    }

    /**
     * One action row per manifest action, keyed {@code <moduleId>:<key>} so two modules' actions
     * can never collide in a tenant.
     */
    static List<TenantModuleData.TenantModuleActionData> actions(String rowId, ExternalModuleManifest manifest) {
        return manifest.actions().stream().map(action -> new TenantModuleData.TenantModuleActionData(
                UUID.randomUUID().toString(), rowId, manifest.id() + ":" + action.key(), action.name(),
                action.category(), action.description(), action.configSchema(), action.inputSchema(),
                action.outputSchema())).toList();
    }

    /**
     * The installing user's {@code platform_user} id, for the principal's {@code created_by}
     * (a foreign key). {@code userId} is the gateway's {@code X-User-ID} — an email — or
     * {@code system}; anything that does not resolve to a user id is {@code null}, not an error.
     */
    private String creatorId(String tenantId, String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String resolved = userIdResolver.resolve(userId, tenantId);
        return resolved != null && UUID_SHAPE.matcher(resolved).matches() ? resolved : null;
    }

    private TenantModuleData external(String tenantId, String moduleId) {
        TenantModuleData row = moduleStore.findByTenantAndModuleId(tenantId, moduleId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Module '" + moduleId + "' not found for tenant " + tenantId));
        if (!row.isExternal()) {
            throw new IllegalStateException("Module '" + moduleId + "' is a JAR module");
        }
        return row;
    }

    private TenantModuleData reload(TenantModuleData row) {
        return moduleStore.findById(row.id()).orElse(row);
    }

    private String slug(String tenantId) {
        return tenantSlugResolver.resolveSlug(tenantId).orElse(null);
    }
}
