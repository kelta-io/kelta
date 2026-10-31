package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.worker.module.ExternalModuleProxy;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The authenticated HTTP surface a module can serve, at
 * {@code /api/modules/{moduleId}/x/**}: forwarded to the module's
 * {@code {baseUrl}/kelta/v1/routes/<path>} for a path and method its manifest declares
 * ({@link ExternalModuleProxy#route}).
 *
 * <p>A module can never contribute a {@code @RestController} — it is another service. This is the
 * generic, platform-owned route that stands in for one, the same way
 * {@link ModuleWebhookController} does for unauthenticated inbound traffic.
 *
 * <p><b>Why this prefix and not an arbitrary path.</b> {@code /api/modules/**} is already a static
 * gateway route, so nothing here needs a gateway change, a bootstrap change, or a NATS event, and
 * there is no collision surface against tenant collection routes. It is also already authenticated:
 * the gateway enforces {@code API_ACCESS} before a request reaches this controller, so a manifest
 * can never make an authenticated prefix unauthenticated. Unauthenticated traffic keeps exactly one
 * home, {@code /api/modules/webhooks/}, which stays a short and auditable entry in the gateway's
 * allowlist.
 *
 * <p>A module that is not ACTIVE, or serves no matching route, gets a uniform 404 — the same
 * non-enumerable answer the webhook route gives, so a caller cannot probe which modules a tenant has
 * installed.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/modules/{moduleId}/x")
public class ModuleHttpController {

    private static final Logger log = LoggerFactory.getLogger(ModuleHttpController.class);

    /*
     * Not gated with @ConditionalOnBean. On a component-scanned controller that condition is
     * evaluated during scanning, before @Configuration bean methods are all registered, so whether
     * it matches depends on scan order. This controller once lost that race and was silently not
     * registered — every module route answered 404 while the module reported ACTIVE. Optional
     * setter injection is deterministic: the controller always exists, and answers 404 when module
     * support is switched off.
     */

    /** Null when module support is switched off. */
    private ExternalModuleProxy externalModules;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;

    /** Absent without module support ({@code kelta.modules.runtime.enabled=false}). */
    @Autowired(required = false)
    public void setExternalModules(ExternalModuleProxy externalModules, CerbosPermissionResolver permissionResolver,
                                   BootstrapRepository bootstrapRepository) {
        this.externalModules = externalModules;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    // Depth-1..3 patterns are listed explicitly and the catch-all only backstops deeper paths.
    // This is not stylistic. DynamicCollectionRouter maps "/api/{parentName}/{parentId}/{childName}/
    // {childId}" for GET, which matches "/api/modules/<id>/x/plans" exactly, and Spring's
    // PathPattern specificity comparator ranks ANY pattern containing "**" last -- so a bare "/**"
    // here loses to it and the request is dispatched as a record read of a collection named
    // "modules". There is no equally deep @PostMapping on that router, which is why POST worked and
    // GET returned 404 with no log line anywhere. Spelling the segments out keeps the literal
    // "modules" and "x" in the pattern, which outranks the router's all-variable one.
    @RequestMapping(value = {"/{p1}", "/{p1}/{p2}", "/{p1}/{p2}/{p3}", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<?> dispatch(@RequestHeader("X-Tenant-ID") String tenantId,
                                      @PathVariable String moduleId,
                                      @RequestBody(required = false) String rawBody,
                                      HttpServletRequest request) {
        String path = modulePath(request, moduleId);
        if (path == null) {
            log.warn("Refusing {} {}: it does not sit under this module's own route prefix",
                    request.getMethod(), request.getRequestURI());
            return ResponseEntity.notFound().build();
        }

        if (externalModules == null) {
            return ResponseEntity.notFound().build();
        }
        return proxy(tenantId, moduleId, path, rawBody, request);
    }

    /**
     * A module route: forwarded to {@code {baseUrl}/kelta/v1/routes/<path>}, and the
     * module's status and JSON body passed back ({@link ExternalModuleProxy#route}).
     */
    private ResponseEntity<?> proxy(String tenantId, String moduleId, String path, String rawBody,
                                    HttpServletRequest request) {
        String profileId = permissionResolver == null ? null : permissionResolver.getProfileId(request);
        ExternalModuleProxy.RouteOutcome outcome;
        try {
            outcome = externalModules.route(tenantId, moduleId, request.getMethod(),
                    path.startsWith("/") ? path.substring(1) : path, request.getQueryString(),
                    rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8), request.getContentType(),
                    permission -> holds(profileId, permission), profileId);
        } catch (ModuleTimeoutException e) {
            // A module that timed out answered nothing: the protocol makes that the caller's 502.
            log.warn("External module '{}' of tenant {} timed out serving {} {}", moduleId, tenantId,
                    request.getMethod(), path);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(JsonApiResponseBuilder.error("502",
                    "ModuleUnreachable", "Bad Gateway", "Module '" + moduleId + "' did not answer in time"));
        }
        // ModuleUnavailableException (breaker open, unreachable) propagates to the global 503.
        if (outcome.refusal() == ExternalModuleProxy.Refusal.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (outcome.refusal() == ExternalModuleProxy.Refusal.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(JsonApiResponseBuilder.error("403",
                    "FORBIDDEN", "Forbidden", outcome.permission() + " permission required"));
        }
        ExternalModuleProxy.Forwarded forwarded = outcome.forwarded();
        if (forwarded.status() >= 500) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(JsonApiResponseBuilder.error("502",
                    "ModuleUnreachable", "Bad Gateway", "Module '" + moduleId + "' failed to handle this request"));
        }
        // Always JSON and never sniffed: the body is the module's, served from the platform origin.
        return ResponseEntity.status(forwarded.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(forwarded.body() == null ? new byte[0] : forwarded.body());
    }

    /** Whether the caller's profile holds a system permission. Fail-closed without a profile. */
    private boolean holds(String profileId, String permission) {
        if (profileId == null || profileId.isBlank() || bootstrapRepository == null) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> permission.equals(p.get("permission_name")) && Boolean.TRUE.equals(p.get("granted")));
    }

    /**
     * The path the module sees: everything after {@code /api/modules/{moduleId}/x}.
     *
     * <p>Returns null when the request does not actually sit under that prefix, so a crafted URI
     * cannot produce a path the module never declared.
     */
    private String modulePath(HttpServletRequest request, String moduleId) {
        String uri = request.getRequestURI();
        String prefix = "/api/modules/" + moduleId + "/x";
        int at = uri.indexOf(prefix);
        if (at < 0) {
            return null;
        }
        String path = uri.substring(at + prefix.length());
        if (path.isEmpty()) {
            path = "/";
        }
        return path.contains("..") ? null : path;
    }
}
