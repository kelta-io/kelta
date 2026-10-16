package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiError;
import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.RemotePromotionClient;
import io.kelta.worker.service.SandboxEnvironmentService;
import io.kelta.worker.service.SandboxProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Environment management: tenant-backed sandboxes (local clones of this
 * tenant's config), remote promotion targets on other clusters, snapshots,
 * and cross-tenant diffs.
 *
 * <p>Lives on the {@code /api/environments/**} static route and is gated
 * in-controller on {@code MANAGE_SANDBOXES} — static routes only get the
 * blanket {@code API_ACCESS} gateway check.
 */
@RestController
@RequestMapping("/api/environments")
public class EnvironmentController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentController.class);
    private static final String PERMISSION = "MANAGE_SANDBOXES";

    private final SandboxEnvironmentService environmentService;
    private final SandboxProvisioningService provisioningService;
    private final RemotePromotionClient remotePromotionClient;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public EnvironmentController(SandboxEnvironmentService environmentService,
                                 SandboxProvisioningService provisioningService,
                                 RemotePromotionClient remotePromotionClient,
                                 CerbosPermissionResolver permissionResolver,
                                 BootstrapRepository bootstrapRepository) {
        this.environmentService = environmentService;
        this.provisioningService = provisioningService;
        this.remotePromotionClient = remotePromotionClient;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @GetMapping
    public ResponseEntity<?> listEnvironments(HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        List<Map<String, Object>> environments = environmentService.listEnvironments(tenantId);
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("environments", environments));
    }

    @GetMapping("/{envId}")
    public ResponseEntity<?> getEnvironment(@PathVariable String envId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        var env = environmentService.getEnvironment(envId, tenantId);
        if (env.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var data = env.get();
        return ResponseEntity.ok(JsonApiResponseBuilder.single("environments", extractId(data), data));
    }

    /**
     * Creates a local tenant-backed sandbox (default) or registers a remote
     * promotion target when {@code remoteBaseUrl} is supplied. The parent
     * tenant and creator identity come from the authenticated request — the
     * body cannot claim a different parent or creator.
     */
    @PostMapping
    public ResponseEntity<?> createEnvironment(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String tenantId = requireTenant();
        requirePermission(request);

        Map<String, Object> attrs = unwrapJsonApiBody(body);
        String pointerPrefix = attrs == body ? "/" : "/data/attributes/";

        try {
            String name = stringAttribute(attrs, "name");
            String description = stringAttribute(attrs, "description");
            String type = stringAttribute(attrs, "type");
            String remoteBaseUrl = stringAttribute(attrs, "remoteBaseUrl");

            if (name == null || name.isBlank()) {
                throw new SandboxProvisioningService.InvalidEnvironmentRequestException(
                        "name", "Environment name is required");
            }

            if (remoteBaseUrl != null && !remoteBaseUrl.isBlank()) {
                Map<String, Object> env = provisioningService.createRemoteEnvironment(
                        tenantId, name, description, type, remoteBaseUrl,
                        stringAttribute(attrs, "remoteTenantSlug"),
                        stringAttribute(attrs, "credentialRef"), userId);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(JsonApiResponseBuilder.single("environments", extractId(env), env));
            }
            Map<String, Object> env = provisioningService.createSandbox(
                    tenantId, name, description, type == null ? "SANDBOX" : type, userId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(JsonApiResponseBuilder.single("environments", extractId(env), env));
        } catch (SandboxProvisioningService.EnvironmentConflictException e) {
            JsonApiError error = new JsonApiError("409", "ENVIRONMENT_NAME_CONFLICT", "Conflict", e.getMessage());
            error.setSource(Map.of("pointer", pointerPrefix + "name"));
            if (e.getEnvironmentId() != null) {
                Map<String, Object> conflicting = new LinkedHashMap<>();
                conflicting.put("id", e.getEnvironmentId());
                conflicting.put("name", e.getEnvironmentName());
                conflicting.put("status", e.getEnvironmentStatus());
                error.setMeta(Map.of("conflictingEnvironment", conflicting));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorDocument(error));
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent create of the same name past the pre-checks.
            log.warn("Environment create hit a unique index: {}", e.getMessage());
            JsonApiError error = new JsonApiError("409", "ENVIRONMENT_NAME_CONFLICT", "Conflict",
                    "An environment with this name was created concurrently in this tenant. Choose a different name.");
            error.setSource(Map.of("pointer", pointerPrefix + "name"));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorDocument(error));
        } catch (IllegalArgumentException e) {
            JsonApiError error = new JsonApiError("400", "VALIDATION_FAILED", "Validation Error", e.getMessage());
            if (e instanceof SandboxProvisioningService.InvalidEnvironmentRequestException invalid) {
                error.setSource(Map.of("pointer", pointerPrefix + invalid.getAttribute()));
            }
            return ResponseEntity.badRequest().body(errorDocument(error));
        }
    }

    /** Re-clones the parent's current config into the sandbox (destructive overwrite). */
    @PostMapping("/{envId}/refresh")
    public ResponseEntity<?> refreshEnvironment(@PathVariable String envId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            Map<String, Object> env = provisioningService.refreshSandbox(envId, tenantId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(JsonApiResponseBuilder.single("environments", extractId(env), env));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Verifies connectivity + credentials of a remote environment. */
    @PostMapping("/{envId}/test")
    public ResponseEntity<?> testEnvironment(@PathVariable String envId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            Map<String, Object> result = remotePromotionClient.testConnection(envId, tenantId);
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Archives the env row; for local sandboxes also decommissions the backing tenant. */
    @DeleteMapping("/{envId}")
    public ResponseEntity<?> deleteEnvironment(@PathVariable String envId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            var env = environmentService.getEnvironment(envId, tenantId);
            if (env.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (env.get().get("sandbox_tenant_id") != null) {
                provisioningService.deleteSandbox(envId, tenantId);
            } else {
                environmentService.archiveEnvironment(envId, tenantId);
            }
            return ResponseEntity.ok(Map.of("status", "archived"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{envId}")
    public ResponseEntity<?> updateEnvironment(@PathVariable String envId,
                                               @RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        Map<String, Object> attrs = unwrapJsonApiBody(body);
        String name = (String) attrs.get("name");
        String description = (String) attrs.get("description");

        String config = null;
        Object configObj = attrs.get("config");
        if (configObj instanceof Map) {
            try {
                config = new tools.jackson.databind.ObjectMapper().writeValueAsString(configObj);
            } catch (Exception e) {
                log.warn("Failed to serialize environment config", e);
            }
        }

        try {
            Map<String, Object> env = environmentService.updateEnvironment(envId, tenantId, name, description, config);
            return ResponseEntity.ok(JsonApiResponseBuilder.single("environments", extractId(env), env));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Legacy alias for {@code DELETE /{envId}} kept for existing callers. */
    @PostMapping("/{envId}/archive")
    public ResponseEntity<?> archiveEnvironment(@PathVariable String envId, HttpServletRequest request) {
        return deleteEnvironment(envId, request);
    }

    @PostMapping("/{envId}/snapshots")
    public ResponseEntity<?> createSnapshot(@PathVariable String envId,
                                            @RequestBody Map<String, Object> body,
                                            HttpServletRequest request,
                                            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String tenantId = requireTenant();
        requirePermission(request);

        Map<String, Object> attrs = unwrapJsonApiBody(body);
        String name = (String) attrs.getOrDefault("name", "Snapshot " + java.time.Instant.now());

        try {
            Map<String, Object> snapshot = environmentService.createSnapshot(tenantId, envId, name, userId);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(JsonApiResponseBuilder.single("snapshots", extractId(snapshot), snapshot));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{envId}/snapshots")
    public ResponseEntity<?> listSnapshots(@PathVariable String envId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        List<Map<String, Object>> snapshots = environmentService.listSnapshots(tenantId, envId);
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("snapshots", snapshots));
    }

    /** Real cross-tenant diff of a local sandbox against its parent tenant. */
    @GetMapping("/{envId}/diff")
    public ResponseEntity<?> diffEnvironment(@PathVariable String envId, HttpServletRequest request) {
        String tenantId = requireTenant();
        requirePermission(request);

        try {
            return ResponseEntity.ok(provisioningService.compareWithParent(envId, tenantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    private void requirePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }

    private static String stringAttribute(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new SandboxProvisioningService.InvalidEnvironmentRequestException(key, key + " must be a string");
    }

    private static Map<String, Object> errorDocument(JsonApiError error) {
        return Map.of("errors", List.of(error.toMap()));
    }

    private String extractId(Map<String, Object> data) {
        Object id = data.get("id");
        return id != null ? id.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapJsonApiBody(Map<String, Object> body) {
        Object dataObj = body.get("data");
        if (dataObj instanceof Map<?, ?> data) {
            Object attrObj = data.get("attributes");
            if (attrObj instanceof Map<?, ?> attributes) {
                return new LinkedHashMap<>((Map<String, Object>) attributes);
            }
        }
        return body;
    }
}
