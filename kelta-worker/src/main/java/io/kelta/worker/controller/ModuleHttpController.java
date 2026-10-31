package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.worker.module.ExternalModuleProxy;
import io.kelta.worker.module.RuntimeModuleManager;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The authenticated HTTP surface a module can serve, at
 * {@code /api/modules/{moduleId}/x/**}.
 *
 * <p>A module can never contribute a {@code @RestController} — Spring MVC resolves routes when the
 * context starts. This is the generic, platform-owned route that stands in for one, the same way
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

    /** Caps a runaway module response rather than streaming it to the caller. */
    private static final int MAX_RESPONSE_ENTRIES = 1000;

    /**
     * Resolved lazily rather than gated with {@code @ConditionalOnBean}.
     *
     * <p>{@code @ConditionalOnBean} on a component-scanned controller is evaluated during scanning,
     * before {@code @Configuration} bean methods are all registered, so whether it matches depends
     * on scan order. This controller lost that race and was silently not registered — every module
     * route answered 404 while the module itself reported ACTIVE with its routes registered, which
     * is a miserable thing to debug. An ObjectProvider is deterministic: the controller always
     * exists, and answers 404 when module support is switched off.
     */
    private final ObjectProvider<RuntimeModuleManager> runtimeModuleManager;

    /** External modules (part 9d); null when module support is switched off. */
    private ExternalModuleProxy externalModules;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;

    public ModuleHttpController(ObjectProvider<RuntimeModuleManager> runtimeModuleManager) {
        this.runtimeModuleManager = runtimeModuleManager;
    }

    /** Injected via setter so the existing constructor stays as it is; absent without module support. */
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

        if (externalModules != null && externalModules.isExternal(tenantId, moduleId)) {
            return proxy(tenantId, moduleId, path, rawBody, request);
        }

        RuntimeModuleManager manager = runtimeModuleManager.getIfAvailable();
        if (manager == null) {
            return ResponseEntity.notFound().build();
        }

        // The platform user UUID CallerContextFilter resolved, never the raw X-User-Id header: the
        // gateway sends the caller's email there, and a module that keys rows by it (billing's
        // Stripe client_reference_id) writes rows nothing else in the platform can find.
        String userId = CallerContext.current().map(CallerContext::userId).orElse(null);

        Optional<ActionResult> result;
        try {
            result = manager.dispatchRoute(tenantId, moduleId, userId,
                    request.getMethod(), path, queryParams(request), rawBody);
        } catch (RuntimeException e) {
            // Module code threw rather than returning a failure. The message is logged, never
            // returned: it can name internals of whatever the module talks to.
            log.error("Module '{}' of tenant {} threw serving {} {}: {}",
                    moduleId, tenantId, request.getMethod(), path, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "The module failed to handle this request"));
        }

        if (result.isEmpty()) {
            // Logged, not silent. A 404 here has three quite different causes -- the module is not
            // ACTIVE, it declares no such route, or the route names a handler that is not
            // registered -- and telling them apart from outside is otherwise impossible. Chasing
            // one of these without this line cost hours.
            log.warn("No module route for {} {} on module '{}' in tenant {} "
                    + "(module inactive, route not declared, or handler not registered)",
                    request.getMethod(), path, moduleId, tenantId);
            return ResponseEntity.notFound().build();
        }
        if (!result.get().successful()) {
            // A handler's own rejection is the caller's fault far more often than the server's,
            // and its message is written for the caller.
            return ResponseEntity.badRequest()
                    .body(Map.of("error", String.valueOf(result.get().errorMessage())));
        }

        Map<String, Object> output = result.get().outputData();
        if (output != null && output.size() > MAX_RESPONSE_ENTRIES) {
            log.error("Module '{}' of tenant {} returned {} entries for {} {} — refusing",
                    moduleId, tenantId, output.size(), request.getMethod(), path);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "The module returned an oversized response"));
        }
        return ResponseEntity.ok(output == null ? Map.of() : output);
    }

    /**
     * An external module's route: forwarded to {@code {baseUrl}/kelta/v1/routes/<path>}, and the
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

    private Map<String, Object> queryParams(HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                params.put(name, values.length == 1 ? values[0] : java.util.List.of(values));
            }
        });
        return params;
    }
}
