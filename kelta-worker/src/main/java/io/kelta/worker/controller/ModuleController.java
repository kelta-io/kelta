package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.module.ExternalModulePlan;
import io.kelta.worker.module.ExternalModuleService;
import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleHttpClient;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleSignatureException;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.ModuleUninstaller;
import io.kelta.worker.module.RuntimeModuleManager;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for runtime module management.
 * <p>
 * Provides CRUD operations for installing, enabling, disabling,
 * and uninstalling tenant-scoped modules. Returns JSON:API format.
 * <p>
 * {@code /api/modules/**} is a static gateway route, which carries only the blanket
 * {@code API_ACCESS} check (signing-key writes excepted), so installing, enabling, disabling and
 * uninstalling are gated here on {@code CUSTOMIZE_APPLICATION}: a module provisions collections,
 * flows and routes into the tenant. Reads stay at {@code API_ACCESS} — the UI loads module
 * bundles and actions for every user.
 * <p>
 * <b>External modules</b> (HTTPS services installed by URL, part 9c of
 * {@code specs/module-platform/9-external-modules.md}) share these endpoints: {@code POST /plan}
 * returns what installing a base URL would grant and do, {@code POST /install} with
 * {@code baseUrl} + {@code planHash} applies exactly that plan, {@code POST /{id}/upgrade}
 * re-plans from the stored URL, and enable / disable / delete dispatch on the row. A stale
 * {@code planHash} is {@code 409} with the fresh plan in {@code meta.plan}; blockers are
 * {@code 422} with one error per blocker code. All of them sit behind
 * {@value #MANAGE_PERMISSION}, including deleting what a module provisioned with
 * {@code purgeData=true}.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/modules")
@ConditionalOnBean(RuntimeModuleManager.class)
public class ModuleController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(ModuleController.class);
    static final String MANAGE_PERMISSION = "CUSTOMIZE_APPLICATION";

    private final RuntimeModuleManager runtimeModuleManager;

    /** Null when module support is switched off; the health payload then reports no route count. */
    private final ModuleRouteRegistry moduleRouteRegistry;
    private final ModuleConfigEventPublisher eventPublisher;

    /** Null when module support is switched off; provisioned resources then list empty. */
    private final ModuleProvenanceStore provenanceStore;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    /** Null when module support is switched off; external-module endpoints then answer 404. */
    private final ExternalModuleService externalModules;
    private final ModuleUninstaller uninstaller;

    public ModuleController(RuntimeModuleManager runtimeModuleManager,
                             ModuleConfigEventPublisher eventPublisher,
                            ObjectProvider<ModuleRouteRegistry> moduleRouteRegistry,
                            ObjectProvider<ModuleProvenanceStore> provenanceStore,
                            CerbosPermissionResolver permissionResolver,
                            BootstrapRepository bootstrapRepository,
                            ObjectProvider<ExternalModuleService> externalModules,
                            ObjectProvider<ModuleUninstaller> uninstaller) {
        this.runtimeModuleManager = runtimeModuleManager;
        this.eventPublisher = eventPublisher;
        this.moduleRouteRegistry = moduleRouteRegistry.getIfAvailable();
        this.provenanceStore = provenanceStore.getIfAvailable();
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.externalModules = externalModules.getIfAvailable();
        this.uninstaller = uninstaller.getIfAvailable();
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
        List<TenantModuleData> modules = runtimeModuleManager.listModules(tenantId);
        List<Map<String, Object>> records = modules.stream()
                .map(this::moduleToMap)
                .toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("modules", records));
    }

    /**
     * Installs a module from its manifest.
     */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireManagePermission(request);
        if (body.get("baseUrl") != null) {
            return installExternal(tenantId, userId, body);
        }
        try {
            String manifestJson = (String) body.get("manifest");
            String sourceUrl = (String) body.getOrDefault("sourceUrl", "local://upload");
            String checksum = (String) body.getOrDefault("checksum", "none");
            Long jarSizeBytes = body.containsKey("jarSizeBytes")
                ? ((Number) body.get("jarSizeBytes")).longValue() : null;

            if (manifestJson == null || manifestJson.isBlank()) {
                return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Validation Error", "manifest is required"));
            }

            TenantModuleData installed = runtimeModuleManager.installModule(
                tenantId, manifestJson, sourceUrl, checksum, jarSizeBytes,
                userId != null ? userId : "system");

            eventPublisher.publishEvent(installed, ModuleChangeType.INSTALLED);

            return ResponseEntity.ok(
                    JsonApiResponseBuilder.single("modules", installed.id(), moduleToAttributes(installed)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(
                    JsonApiResponseBuilder.error("409", "Conflict", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to install module: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", e.getMessage()));
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

    private ResponseEntity<Map<String, Object>> installExternal(String tenantId, String userId,
                                                                Map<String, Object> body) {
        if (externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            TenantModuleData installed = externalModules.install(tenantId, (String) body.get("baseUrl"),
                    (String) body.get("planHash"), userId != null ? userId : "system");
            return ResponseEntity.ok(
                    JsonApiResponseBuilder.single("modules", installed.id(), moduleToAttributes(installed)));
        } catch (RuntimeException e) {
            return planFailure(e, "install");
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
        if (!module.get().isExternal()) {
            return ResponseEntity.status(409).body(JsonApiResponseBuilder.error("409", "Conflict",
                    "Uninstall plans are for external modules; a JAR module's uninstall removes nothing"));
        }
        return ResponseEntity.ok(JsonApiResponseBuilder.single("module-uninstall-plans", moduleId,
                uninstaller.plan(module.get(), purgeData)));
    }

    /**
     * Installs a module with its JAR file (multipart upload).
     * The manifest is extracted from the request part, and the JAR is stored in S3.
     */
    @PostMapping(value = "/install-jar", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> installModuleWithJar(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestPart("manifest") String manifestJson,
            @RequestPart("jar") MultipartFile jarFile,
            @RequestPart(value = "signature", required = false) String signature,
            HttpServletRequest request) {
        requireManagePermission(request);
        try {
            if (manifestJson == null || manifestJson.isBlank()) {
                return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Validation Error", "manifest is required"));
            }
            if (jarFile == null || jarFile.isEmpty()) {
                return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Validation Error", "jar file is required"));
            }

            byte[] jarBytes = jarFile.getBytes();
            TenantModuleData installed = runtimeModuleManager.installModuleWithJar(
                tenantId, manifestJson, jarBytes,
                userId != null ? userId : "system", signature);

            eventPublisher.publishEvent(installed, ModuleChangeType.INSTALLED);

            return ResponseEntity.ok(
                    JsonApiResponseBuilder.single("modules", installed.id(), moduleToAttributes(installed)));
        } catch (ModuleSignatureException e) {
            log.warn("Rejected module JAR install — signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(403).body(
                    JsonApiResponseBuilder.error("403", "Signature Verification Failed", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(
                    JsonApiResponseBuilder.error("409", "Conflict", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to install module with JAR: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", e.getMessage()));
        }
    }

    /**
     * Enables a module, registering its action handlers.
     *
     * <p>Also how an admin re-enables a {@code QUARANTINED} module, or asks a module that is
     * {@code ACTIVE} but failing closed on a transient error to retry now rather than wait out its
     * backoff. The load re-runs on this pod synchronously and on every other pod via the
     * {@code ENABLED} NATS event, so no restart is needed. A re-enabled module is re-verified
     * (checksum, signature) like any other load, so a module whose fault is still there is
     * quarantined again — {@code moduleStatus} and {@code lastError} in the response say so.
     */
    @PostMapping("/{moduleId}/enable")
    public ResponseEntity<Map<String, Object>> enableModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId,
            HttpServletRequest request) {
        requireManagePermission(request);
        Optional<TenantModuleData> existing = findModule(tenantId, moduleId);
        if (existing.isPresent() && existing.get().isExternal() && externalModules != null) {
            TenantModuleData enabled = externalModules.enable(tenantId, moduleId, userIdOf(request));
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", "enabled");
            attrs.put("moduleId", moduleId);
            attrs.put("moduleStatus", enabled.status());
            putLoadDiagnostics(attrs, enabled);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
        }
        try {
            runtimeModuleManager.enableModule(tenantId, moduleId);

            var module = runtimeModuleManager.listModules(tenantId).stream()
                .filter(m -> m.moduleId().equals(moduleId))
                .findFirst();
            module.ifPresent(m -> eventPublisher.publishEvent(m, ModuleChangeType.ENABLED));

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", "enabled");
            attrs.put("moduleId", moduleId);
            // The outcome of the load on the pod that served this request.
            module.ifPresent(m -> {
                attrs.put("moduleStatus", m.status());
                putLoadDiagnostics(attrs, m);
            });
            return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to enable module '{}': {}", moduleId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error", e.getMessage()));
        }
    }

    /**
     * Disables a module, unregistering its action handlers.
     */
    @PostMapping("/{moduleId}/disable")
    public ResponseEntity<Map<String, Object>> disableModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId,
            HttpServletRequest request) {
        requireManagePermission(request);
        Optional<TenantModuleData> existing = findModule(tenantId, moduleId);
        if (existing.isPresent() && existing.get().isExternal() && externalModules != null) {
            externalModules.disable(tenantId, moduleId);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", "disabled");
            attrs.put("moduleId", moduleId);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
        }
        try {
            var module = runtimeModuleManager.listModules(tenantId).stream()
                .filter(m -> m.moduleId().equals(moduleId))
                .findFirst();

            runtimeModuleManager.disableModule(tenantId, moduleId);

            module.ifPresent(m -> eventPublisher.publishEvent(m, ModuleChangeType.DISABLED));

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", "disabled");
            attrs.put("moduleId", moduleId);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to disable module '{}': {}", moduleId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error", e.getMessage()));
        }
    }

    /**
     * Uninstalls a module entirely.
     *
     * <p>For an external module, {@code planHash} (from {@code GET /{id}/uninstall-plan} with the
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
        if (existing.isPresent() && existing.get().isExternal() && externalModules != null && uninstaller != null) {
            return uninstallExternal(existing.get(), planHash, purgeData);
        }
        if (planHash != null && existing.isPresent()) {
            return ResponseEntity.status(409).body(JsonApiResponseBuilder.error("409", "Conflict",
                    "planHash applies to external modules; a JAR module's uninstall removes nothing"));
        }
        try {
            var module = runtimeModuleManager.listModules(tenantId).stream()
                .filter(m -> m.moduleId().equals(moduleId))
                .findFirst();

            runtimeModuleManager.uninstallModule(tenantId, moduleId);

            module.ifPresent(m -> eventPublisher.publishEvent(m, ModuleChangeType.UNINSTALLED));

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", "uninstalled");
            attrs.put("moduleId", moduleId);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("modules", moduleId, attrs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to uninstall module '{}': {}", moduleId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    JsonApiResponseBuilder.error("500", "Internal Server Error", e.getMessage()));
        }
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

    /** Maps an external install/upgrade failure to its status: 409 plan changed, 422 blockers, … */
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
        return runtimeModuleManager.listModules(tenantId).stream()
                .filter(m -> m.moduleId().equals(moduleId))
                .findFirst();
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
        var module = runtimeModuleManager.listModules(tenantId).stream()
            .filter(m -> m.moduleId().equals(moduleId))
            .findFirst();

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
     * Reports whether this module is actually running <b>on this pod</b>, alongside the stored
     * status and the last load failure.
     *
     * <p>The two can disagree, and that is the point. {@code status} is a database column updated
     * by whichever pod last loaded the module; loading happens per pod, driven by a fire-and-forget
     * NATS event with no acknowledgement. A pod that missed the event serves traffic with the
     * module absent while {@code /api/modules} reports {@code ACTIVE}. Until now
     * {@code isLoaded()} existed and no endpoint returned it, so that divergence was invisible.
     *
     * <p>{@code podName} is included because the answer is only true of the pod that replied.
     */
    @GetMapping("/{moduleId}/health")
    public ResponseEntity<Map<String, Object>> getModuleHealth(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        var module = runtimeModuleManager.listModules(tenantId).stream()
            .filter(m -> m.moduleId().equals(moduleId))
            .findFirst();

        if (module.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("moduleId", moduleId);
        health.put("version", module.get().version());
        health.put("status", module.get().status());
        health.put("loadedOnThisPod", runtimeModuleManager.isLoaded(tenantId, moduleId));
        // loadedOnThisPod is also true of a module that failed closed (its refusing handlers are
        // registered); loadStateOnThisPod says which: LOADED, RETRYING (infrastructure failure,
        // with attempts and nextLoadRetryAt), FAILED (quarantined) or NOT_LOADED.
        health.putAll(runtimeModuleManager.podLoadState(tenantId, moduleId));
        health.put("podName", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        health.put("actionsDeclared", module.get().actions().size());
        // Routes registered on THIS pod, which is the question an operator actually has when a
        // module route 404s: the stored manifest can declare routes that this pod never registered.
        health.put("routesRegisteredOnThisPod",
            moduleRouteRegistry == null ? null : moduleRouteRegistry.routeCount(tenantId, moduleId));
        health.putAll(runtimeModuleManager.loadDiagnostics(module.get().id()));
        return ResponseEntity.ok(health);
    }

    /**
     * Serves a module's browser bundle from its installed JAR, so a module can ship UI in the same
     * single package as its backend code.
     *
     * <p>Scoped to the calling tenant's own ACTIVE modules — a tenant can never fetch a bundle
     * from a module it has not installed. The bytes come from a JAR that passed publisher-signature
     * verification at install and checksum + signature re-verification on read.
     *
     * <p><b>This is publisher-authored JavaScript served same-origin to the admin UI.</b> It runs
     * with the admin session's full DOM and cookie access; the JAR signature gate is the whole of
     * the trust model (see {@code ModuleSignatureVerifier}). Do not relax that gate without
     * replacing it with real isolation.
     */
    @GetMapping(value = "/{moduleId}/ui-bundle.js", produces = "application/javascript")
    public ResponseEntity<byte[]> getUiBundle(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        return runtimeModuleManager.readUiBundle(tenantId, moduleId)
                .map(bytes -> ResponseEntity.ok()
                        // The bundle is immutable for a given installed version, but a reinstall
                        // reuses this URL, so revalidate rather than cache hard.
                        .header("Cache-Control", "no-cache")
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
        // An external module is an HTTPS service installed by URL; null for a JAR module.
        map.put("baseUrl", m.baseUrl());
        map.put("protocolVersion", m.protocolVersion());
        putLoadDiagnostics(map, m);
        return map;
    }

    /**
     * Adds the persisted load diagnostics, so the admin UI can say why a module is
     * {@code QUARANTINED} and show one that is {@code ACTIVE} but failing closed on a transient
     * error ({@code loadRetrying}) instead of reporting it as healthy.
     *
     * <p>{@code loadRetrying} is derived, not stored: only a transient failure
     * ({@code ModuleStore.recordLoadError}) leaves an {@code ACTIVE} row with a {@code last_error},
     * and a successful load clears it. Loading is per pod, so this is the last pod to report.
     */
    private void putLoadDiagnostics(Map<String, Object> target, TenantModuleData m) {
        Map<String, Object> diagnostics = runtimeModuleManager.loadDiagnostics(m.id());
        Object lastError = diagnostics.get("lastError");
        target.put("lastError", lastError);
        target.put("lastErrorAt", isoInstant(diagnostics.get("lastErrorAt")));
        target.put("lastLoadedAt", isoInstant(diagnostics.get("lastLoadedAt")));
        target.put("loadAttempts", diagnostics.get("loadAttempts"));
        target.put("loadRetrying",
            TenantModuleData.STATUS_ACTIVE.equals(m.status()) && lastError != null);
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

    /** The module's declared UI bundle path, or null when it ships no UI or has a bad manifest. */
    private String uiBundlePath(TenantModuleData m) {
        if (m.isExternal()) {
            return null; // external UI bundles are pinned and served from part 9e on
        }
        try {
            return runtimeModuleManager.parseManifest(m.manifest()).uiBundlePath();
        } catch (RuntimeException e) {
            log.debug("Module '{}' has an unreadable manifest: {}", m.moduleId(), e.getMessage());
            return null;
        }
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
        if (m.isExternal()) {
            attrs.put("baseUrl", m.baseUrl());
            attrs.put("protocolVersion", m.protocolVersion());
            attrs.put("lastError", runtimeModuleManager.loadDiagnostics(m.id()).get("lastError"));
        }
        return attrs;
    }
}
