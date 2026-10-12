package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.ModuleConfigEventPublisher;
import io.kelta.worker.module.ModuleProvenanceStore;
import io.kelta.worker.module.ModuleSignatureException;
import io.kelta.worker.module.ModuleRouteRegistry;
import io.kelta.worker.module.RuntimeModuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for runtime module management.
 * <p>
 * Provides CRUD operations for installing, enabling, disabling,
 * and uninstalling tenant-scoped modules. Returns JSON:API format.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/modules")
@ConditionalOnBean(RuntimeModuleManager.class)
public class ModuleController {

    private static final Logger log = LoggerFactory.getLogger(ModuleController.class);

    private final RuntimeModuleManager runtimeModuleManager;

    /** Null when module support is switched off; the health payload then reports no route count. */
    private final ModuleRouteRegistry moduleRouteRegistry;
    private final ModuleConfigEventPublisher eventPublisher;

    /** Null when module support is switched off; provisioned resources then list empty. */
    private final ModuleProvenanceStore provenanceStore;

    public ModuleController(RuntimeModuleManager runtimeModuleManager,
                             ModuleConfigEventPublisher eventPublisher,
                            ObjectProvider<ModuleRouteRegistry> moduleRouteRegistry,
                            ObjectProvider<ModuleProvenanceStore> provenanceStore) {
        this.runtimeModuleManager = runtimeModuleManager;
        this.eventPublisher = eventPublisher;
        this.moduleRouteRegistry = moduleRouteRegistry.getIfAvailable();
        this.provenanceStore = provenanceStore.getIfAvailable();
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
            @RequestBody Map<String, Object> body) {
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
     * Installs a module with its JAR file (multipart upload).
     * The manifest is extracted from the request part, and the JAR is stored in S3.
     */
    @PostMapping(value = "/install-jar", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> installModuleWithJar(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestPart("manifest") String manifestJson,
            @RequestPart("jar") MultipartFile jarFile,
            @RequestPart(value = "signature", required = false) String signature) {
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
     */
    @PostMapping("/{moduleId}/enable")
    public ResponseEntity<Map<String, Object>> enableModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
        try {
            runtimeModuleManager.enableModule(tenantId, moduleId);

            var module = runtimeModuleManager.listModules(tenantId).stream()
                .filter(m -> m.moduleId().equals(moduleId))
                .findFirst();
            module.ifPresent(m -> eventPublisher.publishEvent(m, ModuleChangeType.ENABLED));

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", "enabled");
            attrs.put("moduleId", moduleId);
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
            @PathVariable String moduleId) {
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
     */
    @DeleteMapping("/{moduleId}")
    public ResponseEntity<Map<String, Object>> uninstallModule(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String moduleId) {
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
        return map;
    }

    /** The module's declared UI bundle path, or null when it ships no UI or has a bad manifest. */
    private String uiBundlePath(TenantModuleData m) {
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
        return attrs;
    }
}
