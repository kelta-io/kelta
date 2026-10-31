package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.module.ExternalModulePlan;
import io.kelta.worker.module.ExternalModuleRegistry;
import io.kelta.worker.module.ExternalModuleInvoker;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ExternalModuleUiBundles;
import io.kelta.worker.module.ModuleHttpClient;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleUninstaller;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for module management, in JSON:API format. Every module is an external HTTPS
 * service installed by URL ({@code specs/module-platform/9-external-modules.md}):
 * {@code POST /plan} returns what installing a base URL would grant and do, {@code POST /install}
 * with {@code baseUrl} + {@code planHash} applies exactly that plan, {@code POST /{id}/upgrade}
 * re-plans from the stored URL, and enable / disable / uninstall act on the install. A stale
 * {@code planHash} is {@code 409} with the fresh plan in {@code meta.plan}; blockers are
 * {@code 422} with one error per blocker code.
 * <p>
 * {@code /api/modules/**} is a static gateway route, which carries only the blanket
 * {@code API_ACCESS} check, so planning, installing, upgrading, enabling, disabling and
 * uninstalling are gated here on {@value #MANAGE_PERMISSION} — including deleting what a module
 * provisioned with {@code purgeData=true}: a module provisions collections and routes into the
 * tenant. Reads stay at {@code API_ACCESS} — the UI loads module bundles and actions for every
 * user.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/modules")
// Same switch as ModuleConfig, which supplies ModuleStore. A property condition rather than
// @ConditionalOnBean: on a component-scanned controller that depends on registration order.
@ConditionalOnProperty(name = "kelta.modules.runtime.enabled", havingValue = "true", matchIfMissing = true)
public class ModuleController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(ModuleController.class);
    static final String MANAGE_PERMISSION = "CUSTOMIZE_APPLICATION";

    private final ModuleStore moduleStore;

    /** Null when module support is switched off; provisioned resources then list empty. */
    private final ModuleProvenanceStore provenanceStore;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    /** Null when module support is switched off; external-module endpoints then answer 404. */
    private final ExternalModuleService externalModules;
    private final ModuleUninstaller uninstaller;
    private ExternalModuleRegistry externalRegistry;
    private ExternalModuleUiBundles externalUiBundles;

    public ModuleController(ModuleStore moduleStore,
                            ObjectProvider<ModuleProvenanceStore> provenanceStore,
                            CerbosPermissionResolver permissionResolver,
                            BootstrapRepository bootstrapRepository,
                            ObjectProvider<ExternalModuleService> externalModules,
                            ObjectProvider<ModuleUninstaller> uninstaller) {
        this.moduleStore = moduleStore;
        this.provenanceStore = provenanceStore.getIfAvailable();
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.externalModules = externalModules.getIfAvailable();
        this.uninstaller = uninstaller.getIfAvailable();
    }

    /** External-module endpoint registry (part 9d), for live health; setter-injected, absent when off. */
    @Autowired(required = false)
    public void setExternalModuleRegistry(ExternalModuleRegistry externalRegistry) {
        this.externalRegistry = externalRegistry;
    }

    /** Pinned external UI bundles (part 9e); setter-injected, absent when module support is off. */
    @Autowired(required = false)
    public void setExternalModuleUiBundles(ExternalModuleUiBundles externalUiBundles) {
        this.externalUiBundles = externalUiBundles;
    }

    /**
     * Gates module lifecycle writes on {@value #MANAGE_PERMISSION}. Fail-closed: no resolvable
     * profile is rejected. Must run before the handler's try/catch so the 403 is not turned
     * into a 400.
     */
    private void requireManagePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> MANAGE_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MANAGE_PERMISSION + " permission required");
        }
    }

    /**
     * Lists all installed modules for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listModules(
            @RequestHeader("X-Tenant-ID") String tenantId) {
        List<TenantModuleData> modules = moduleStore.findByTenant(tenantId);
        List<Map<String, Object>> records = modules.stream()
                .map(this::moduleToMap)
                .toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("modules", records));
    }

    /**
     * Installs the module served at {@code baseUrl}, applying the plan whose {@code planHash} the
     * admin reviewed ({@code POST /plan}). Both are required.
     */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireManagePermission(request);
        if (externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        if (!(body.get("baseUrl") instanceof String baseUrl) || baseUrl.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Validation Error", "baseUrl is required"));
        }
        try {
            TenantModuleData installed = externalModules.install(tenantId, baseUrl,
                    (String) body.get("planHash"), userId != null ? userId : "system");
            return ResponseEntity.ok(
                    JsonApiResponseBuilder.single("modules", installed.id(), moduleToAttributes(installed)));
        } catch (RuntimeException e) {
            return planFailure(e, "install");
        }
    }

    /**
     * Plans installing the external module served at {@code baseUrl}: fetches and validates its
     * manifest and dry-runs its metadata. Writes nothing. A plan with blockers is still {@code 200}
     * — the plan is the answer; {@code 400} for an unacceptable URL or a manifest that is not
     * JSON, {@code 502} when the manifest cannot be fetched.
     */
    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> planModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireManagePermission(request);
        if (externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            ExternalModulePlan plan = externalModules.plan(tenantId, (String) body.get("baseUrl"),
                    userId != null ? userId : "system");
            return ResponseEntity.ok(planDocument(plan.attributes()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", e.getMessage()));
        } catch (ModuleHttpClient.ModuleCallException e) {
            return moduleUnreachable(e);
        }
    }

    /**
     * Upgrades an installed external module from its stored base URL, applying the plan whose
     * {@code planHash} the admin reviewed ({@code POST /plan} with that base URL). The new version
     * must be strictly higher; the service principal's profile is regenerated.
     */
    @PostMapping("/{moduleId}/upgrade")
    public ResponseEntity<Map<String, Object>> upgradeModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @PathVariable String moduleId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireManagePermission(request);
        if (externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            TenantModuleData upgraded = externalModules.upgrade(tenantId, moduleId,
                    (String) body.get("planHash"), userId != null ? userId : "system");
            return ResponseEntity.ok(
                    JsonApiResponseBuilder.single("modules", upgraded.id(), moduleToAttributes(upgraded)));
        } catch (RuntimeException e) {
            return planFailure(e, "upgrade");
        }
    }

    /**
     * What uninstalling an external module would remove and keep, per provisioned resource
     * (slice 3's decision table). Writes nothing; send its {@code planHash} to {@code DELETE}.
     */
    @GetMapping("/{moduleId}/uninstall-plan")
    public ResponseEntity<Map<String, Object>> getUninstallPlan(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId,
            @RequestParam(value = "purgeData", defaultValue = "false") boolean purgeData,
            HttpServletRequest request) {
        requireManagePermission(request);
        Optional<TenantModuleData> module = findModule(tenantId, moduleId);
        if (module.isEmpty() || uninstaller == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(JsonApiResponseBuilder.single("module-uninstall-plans", moduleId,
                uninstaller.plan(module.get(), purgeData)));
    }

    /**
     * Enables a module: status {@code ACTIVE}, then its {@code enabled} lifecycle call. Also how an
     * admin retries an install that ended {@code FAILED}; {@code moduleStatus} and {@code lastError}
     * in the response say how it went.
     */
    @PostMapping("/{moduleId}/enable")
    public ResponseEntity<Map<String, Object>> enableModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId,
            HttpServletRequest request) {
        requireManagePermission(request);
        if (findModule(tenantId, moduleId).isEmpty() || externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        TenantModuleData enabled = externalModules.enable(tenantId, moduleId, userIdOf(request));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("status", "enabled");
        attrs.put("moduleId", moduleId);
        attrs.put("moduleStatus", enabled.status());
        putLastError(attrs, enabled);
        return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
    }

    /**
     * Disables a module: every invocation stops, then its {@code disabled} lifecycle call.
     */
    @PostMapping("/{moduleId}/disable")
    public ResponseEntity<Map<String, Object>> disableModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId,
            HttpServletRequest request) {
        requireManagePermission(request);
        if (findModule(tenantId, moduleId).isEmpty() || externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        externalModules.disable(tenantId, moduleId);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("status", "disabled");
        attrs.put("moduleId", moduleId);
        return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
    }

    /**
     * Uninstalls a module: its {@code uninstalled} lifecycle call (best effort), schedules, service
     * principal and row go.
     *
     * <p>{@code planHash} (from {@code GET /{id}/uninstall-plan} with the
     * same {@code purgeData}) also removes the provisioned resources the plan marks
     * {@code REMOVE}; a stale hash is {@code 409} with the fresh plan. Without it nothing
     * provisioned is removed and {@code retained} lists it all.
     */
    @DeleteMapping("/{moduleId}")
    public ResponseEntity<Map<String, Object>> uninstallModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId,
            @RequestParam(value = "planHash", required = false) String planHash,
            @RequestParam(value = "purgeData", defaultValue = "false") boolean purgeData,
            HttpServletRequest request) {
        requireManagePermission(request);
        Optional<TenantModuleData> existing = findModule(tenantId, moduleId);
        if (existing.isEmpty() || externalModules == null || uninstaller == null) {
            return ResponseEntity.notFound().build();
        }
        return uninstallExternal(existing.get(), planHash, purgeData);
    }

    private ResponseEntity<Map<String, Object>> uninstallExternal(TenantModuleData module, String planHash,
                                                                  boolean purgeData) {
        if (planHash != null) {
            Map<String, Object> plan = uninstaller.plan(module, purgeData);
            if (!planHash.equals(plan.get("planHash"))) {
                return planChanged(plan, "module-uninstall-plans");
            }
        }
        externalModules.uninstall(module, purgeData);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("status", "uninstalled");
        attrs.put("moduleId", module.moduleId());
        if (planHash != null) {
            attrs.putAll(uninstaller.apply(module, purgeData));
        } else {
            attrs.put("removed", List.of());
            attrs.put("retained", uninstaller.retainedAll(module.tenantId(), module.moduleId()));
            attrs.put("failed", List.of());
        }
        return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", module.moduleId(), attrs));
    }

    /** Maps an install/upgrade failure to its status: 409 plan changed, 422 blockers, … */
    private ResponseEntity<Map<String, Object>> planFailure(RuntimeException e, String operation) {
        return switch (e) {
            case ExternalModulePlan.PlanChangedException changed -> planChanged(changed.plan(), "module-install-plans");
            case ExternalModulePlan.PlanBlockedException blocked -> {
                List<Map<String, Object>> errors = new ArrayList<>();
                for (Map<String, Object> blocker : blocked.blockers()) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("status", "422");
                    error.put("code", blocker.get("code"));
                    error.put("title", "Plan Blocked");
                    error.put("detail", blocker.get("message"));
                    errors.add(error);
                }
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("errors", errors);
                document.put("meta", Map.of("plan", planDocument(blocked.plan()).get("data")));
                yield ResponseEntity.status(422).body(document);
            }
            case ModuleHttpClient.ModuleCallException call -> moduleUnreachable(call);
            case IllegalStateException conflict -> ResponseEntity.status(409).body(
                    JsonApiResponseBuilder.error("409", "Conflict", conflict.getMessage()));
            case IllegalArgumentException bad -> bad.getMessage() != null && bad.getMessage().contains("not found")
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(JsonApiResponseBuilder.error("400", "Bad Request", bad.getMessage()));
            default -> {
                log.error("External module {} failed: {}", operation, e.getMessage(), e);
                yield ResponseEntity.internalServerError().body(
                        JsonApiResponseBuilder.error("500", "Internal Server Error", e.getMessage()));
            }
        };
    }

    private static ResponseEntity<Map<String, Object>> planChanged(Map<String, Object> plan, String type) {
        Map<String, Object> document = new LinkedHashMap<>(JsonApiResponseBuilder.error("409", "PlanChanged",
                "Plan Changed", "The plan changed since it was reviewed; review meta.plan and send its planHash"));
        document.put("meta", Map.of("plan", planDocument(plan, type).get("data")));
        return ResponseEntity.status(409).body(document);
    }

    private static ResponseEntity<Map<String, Object>> moduleUnreachable(ModuleHttpClient.ModuleCallException e) {
        return ResponseEntity.status(502).body(JsonApiResponseBuilder.error("502", "ModuleUnreachable",
                "Module Unreachable", e.getMessage()));
    }

    private static Map<String, Object> planDocument(Map<String, Object> attributes) {
        return planDocument(attributes, "module-install-plans");
    }

    private static Map<String, Object> planDocument(Map<String, Object> attributes, String type) {
        Object id = attributes.get("moduleId");
        return JsonApiResponseBuilder.single(type, id == null ? "unknown" : String.valueOf(id), attributes);
    }

    private Optional<TenantModuleData> findModule(String tenantId, String moduleId) {
        return moduleStore.findByTenantAndModuleId(tenantId, moduleId);
    }

    private static String userIdOf(HttpServletRequest request) {
        String userId = request.getHeader("X-User-ID");
        return userId != null ? userId : "system";
    }

    /**
     * Gets the actions provided by a specific module.
     */
    @GetMapping("/{moduleId}/actions")
    public ResponseEntity<Map<String, Object>> getModuleActions(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        Optional<TenantModuleData> module = findModule(tenantId, moduleId);
        if (module.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> actionRecords = module.get().actions().stream()
                .map(a -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", a.id());
                    map.put("actionKey", a.actionKey());
                    map.put("name", a.name());
                    map.put("category", a.category());
                    map.put("description", a.description());
                    return map;
                })
                .toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("module-actions", actionRecords));
    }

    /**
     * Lists what a module provisioned in the caller's tenant: which collections and fields it
     * created and which it adopted.
     *
     * <p>Answers for a module that is no longer installed too — uninstall keeps these records, and
     * "what did the module I removed leave behind?" is the question this exists for. An unknown
     * module id is therefore an empty list, not a 404.
     */
    @GetMapping("/{moduleId}/provisioned-resources")
    public ResponseEntity<Map<String, Object>> getProvisionedResources(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        List<Map<String, Object>> rows = provenanceStore == null
            ? List.of() : provenanceStore.findByModule(tenantId, moduleId);
        List<Map<String, Object>> records = rows.stream()
                .map(row -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", row.get("id"));
                    map.put("resourceType", row.get("resource_type"));
                    map.put("naturalKey", row.get("natural_key"));
                    map.put("resourceId", row.get("resource_id"));
                    map.put("ownership", row.get("ownership"));
                    map.put("contentHash", row.get("content_hash"));
                    map.put("moduleVersion", row.get("module_version"));
                    map.put("createdAt", row.get("created_at") instanceof java.sql.Timestamp ts
                        ? ts.toInstant().toString() : row.get("created_at"));
                    return map;
                })
                .toList();
        return ResponseEntity.ok(
                JsonApiResponseBuilder.collection("module-provisioned-resources", records));
    }

    /**
     * A module's health: the stored status and last error, the module's own answer to
     * {@code GET /kelta/v1/health} (live, on request) and this pod's circuit breaker for it.
     *
     * <p>{@code podName} is included because the breaker state is only true of the pod that replied.
     */
    @GetMapping("/{moduleId}/health")
    public ResponseEntity<Map<String, Object>> getModuleHealth(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        Optional<TenantModuleData> module = findModule(tenantId, moduleId);
        if (module.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("moduleId", moduleId);
        health.put("version", module.get().version());
        health.put("status", module.get().status());
        health.put("podName", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        health.put("actionsDeclared", module.get().actions().size());
        putLastError(health, module.get());
        if (externalRegistry != null) {
            health.putAll(externalRegistry.health(module.get()));
        }
        return ResponseEntity.ok(health);
    }

    /**
     * Serves a module's browser bundle from the copy pinned in object storage at install or upgrade
     * (§3.9) — never fetched from the module live.
     *
     * <p>Scoped to the calling tenant's own modules — a tenant can never fetch a bundle from a
     * module it has not installed — and to callable ones ({@code ACTIVE} or {@code DEGRADED}: the
     * pinned bundle does not depend on the service answering). The bytes are re-hashed against the
     * pinned SHA-256 the admin approved in the plan, and refused on a mismatch.
     *
     * <p><b>This is publisher-authored JavaScript served same-origin to the admin UI.</b> It runs
     * with the admin session's full DOM and cookie access; the pinned hash is the whole of the trust
     * model (see {@code ExternalModuleUiBundles}). Do not relax it without replacing it with real
     * isolation.
     */
    @GetMapping(value = "/{moduleId}/ui-bundle.js", produces = "application/javascript")
    public ResponseEntity<byte[]> getUiBundle(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        Optional<TenantModuleData> module = findModule(tenantId, moduleId);
        if (module.isEmpty() || externalUiBundles == null || !ExternalModuleInvoker.callable(module.get())) {
            return ResponseEntity.notFound().build();
        }
        TenantModuleData m = module.get();
        return externalUiBundles.read(m)
                .map(bytes -> ResponseEntity.ok()
                        // A new bundle comes with an upgrade under the same URL: revalidate.
                        .header("Cache-Control", "no-cache")
                        .eTag("\"" + m.uiBundleSha256() + "\"")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private Map<String, Object> moduleToMap(TenantModuleData m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("moduleId", m.moduleId());
        map.put("name", m.name());
        map.put("version", m.version());
        map.put("description", m.description());
        map.put("status", m.status());
        map.put("installedBy", m.installedBy());
        map.put("installedAt", m.installedAt() != null ? m.installedAt().toString() : null);
        map.put("updatedAt", m.updatedAt() != null ? m.updatedAt().toString() : null);
        // Lets the admin UI decide which modules to fetch a bundle for, rather than probing
        // every installed module with a request that would 404 for most of them.
        map.put("uiBundlePath", uiBundlePath(m));
        // An external module's pinned bundle hash, the one the admin approved; null otherwise.
        map.put("uiBundleSha256", m.uiBundleSha256());
        // The admin page lists each module's action handlers from here.
        map.put("actions", m.actions());
        // The HTTPS service the module is, and the module protocol version it speaks.
        map.put("baseUrl", m.baseUrl());
        map.put("protocolVersion", m.protocolVersion());
        putLastError(map, m);
        return map;
    }

    /**
     * Adds the stored {@code lastError} and {@code lastErrorAt}: why an install ended
     * {@code FAILED}, or why its circuit breaker wrote {@code DEGRADED}.
     */
    private void putLastError(Map<String, Object> target, TenantModuleData m) {
        Map<String, Object> lastError = lastError(m.id());
        target.put("lastError", lastError.get("lastError"));
        target.put("lastErrorAt", isoInstant(lastError.get("lastErrorAt")));
    }

    private Map<String, Object> lastError(String moduleRowId) {
        try {
            return moduleStore.findLastError(moduleRowId);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String isoInstant(Object value) {
        return switch (value) {
            case null -> null;
            case java.sql.Timestamp ts -> ts.toInstant().toString();
            case java.time.OffsetDateTime odt -> odt.toInstant().toString();
            case java.time.Instant instant -> instant.toString();
            default -> value.toString();
        };
    }

    /** Where the admin UI fetches the module's pinned bundle, or null when it ships no UI. */
    private String uiBundlePath(TenantModuleData m) {
        return m.uiBundleSha256() == null || externalUiBundles == null ? null : externalUiBundles.bundlePath(m);
    }

    private Map<String, Object> moduleToAttributes(TenantModuleData m) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("moduleId", m.moduleId());
        attrs.put("name", m.name());
        attrs.put("version", m.version());
        attrs.put("description", m.description());
        attrs.put("status", m.status());
        attrs.put("installedBy", m.installedBy());
        attrs.put("installedAt", m.installedAt() != null ? m.installedAt().toString() : null);
        attrs.put("updatedAt", m.updatedAt() != null ? m.updatedAt().toString() : null);
        attrs.put("baseUrl", m.baseUrl());
        attrs.put("protocolVersion", m.protocolVersion());
        attrs.put("lastError", lastError(m.id()).get("lastError"));
        return attrs;
    }
}
