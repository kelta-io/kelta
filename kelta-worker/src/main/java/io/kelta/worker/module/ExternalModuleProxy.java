package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleRegistry.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The two pass-through proxies to an external module
 * ({@code specs/module-platform/9-external-modules.md} §3.6), behind the same platform-owned
 * routes a JAR module serves through:
 *
 * <ul>
 *   <li><b>Routes</b> — {@code /api/modules/{id}/x/<path>} → {@code {baseUrl}/kelta/v1/routes/<path>}
 *       for a path and method the manifest's {@code routes[]} declares, with the caller's query
 *       string, body and {@code Content-Type}, and the caller in the assertion's {@code act}. The
 *       module's status and body go back to the caller (as JSON, {@code nosniff}, at most 1 MiB).
 *       A module 5xx or a timeout is {@code 502 ModuleUnreachable}; a module that cannot be called
 *       (breaker open, unreachable, no token) is {@code 503 ModuleUnavailable}.</li>
 *   <li><b>Webhooks</b> — {@code /api/modules/webhooks/{tenantId}/{id}} →
 *       {@code {baseUrl}/kelta/v1/webhooks} with the raw body bytes and only the allowlisted
 *       headers, lower-cased; the module's status goes back to the sender (so it retries a non-2xx),
 *       never its body.</li>
 * </ul>
 *
 * <p>Neither says whether a tenant has a module: not installed, not callable, an undeclared route
 * or a disabled webhook are all the caller's uniform 404.
 */
public class ExternalModuleProxy {

    private static final Logger log = LoggerFactory.getLogger(ExternalModuleProxy.class);

    /** Route calls get the protocol's default action deadline; a route has no timeout of its own. */
    static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Webhook headers forwarded to the module, matched as lower-cased prefixes: everything a
     * signature scheme needs travels in one of these, while the rest carries gateway and
     * infrastructure detail. The same list the JAR path uses ({@code ModuleWebhookController}).
     */
    public static final Set<String> FORWARDED_WEBHOOK_HEADER_PREFIXES = Set.of(
            "x-", "stripe-", "signature", "digest", "content-type", "user-agent");

    /** What the caller gets. {@code body} is null for a webhook, which answers with a status only. */
    public record Forwarded(int status, byte[] body) {}

    /** Why a route was not forwarded. */
    public enum Refusal { NOT_FOUND, FORBIDDEN }

    /** The outcome of a route: exactly one of {@code forwarded} or {@code refusal}. */
    public record RouteOutcome(Forwarded forwarded, Refusal refusal, String permission) {
        static RouteOutcome refused(Refusal refusal, String permission) {
            return new RouteOutcome(null, refusal, permission);
        }
    }

    private final ExternalModuleRegistry registry;
    private final ExternalModuleInvoker invoker;

    public ExternalModuleProxy(ExternalModuleRegistry registry, ExternalModuleInvoker invoker) {
        this.registry = Objects.requireNonNull(registry);
        this.invoker = Objects.requireNonNull(invoker);
    }

    /** Whether the tenant has this module installed as an external module (on this pod's view). */
    public boolean isExternal(String tenantId, String moduleId) {
        return registry.endpoint(tenantId, moduleId).isPresent();
    }

    /**
     * Forwards a route call.
     *
     * @param path           the path under {@code /x/}, without a leading slash
     * @param hasPermission  whether the caller holds a named system permission
     * @param profileId      the caller's profile, for {@code act.profile_id}
     * @throws ModuleTimeoutException     the module did not answer in time (502 to the caller)
     * @throws ModuleUnavailableException the module could not be called (503 to the caller)
     */
    public RouteOutcome route(String tenantId, String moduleId, String method, String path, String rawQuery,
                              byte[] body, String contentType, Predicate<String> hasPermission, String profileId) {
        Endpoint endpoint = registry.endpoint(tenantId, moduleId).orElse(null);
        if (endpoint == null || !ExternalModuleInvoker.callable(endpoint.row())) {
            return RouteOutcome.refused(Refusal.NOT_FOUND, null);
        }
        Optional<ExternalModuleManifest.Route> route = declared(endpoint.manifest(), method, path);
        if (route.isEmpty()) {
            log.warn("No route {} {} declared by external module '{}' in tenant {}", method, path, moduleId,
                    tenantId);
            return RouteOutcome.refused(Refusal.NOT_FOUND, null);
        }
        String permission = route.get().permission();
        if (permission != null && !hasPermission.test(permission)) {
            return RouteOutcome.refused(Refusal.FORBIDDEN, permission);
        }
        boolean hasBody = body != null && body.length > 0;
        ModuleHttpClient.Call call = new ModuleHttpClient.Call(method, ModuleHttpClient.ROUTES_PATH + path, rawQuery,
                hasBody ? body : null, hasBody ? (contentType == null ? "application/json" : contentType) : null,
                Map.of(), ROUTE_TIMEOUT, ExternalModuleRegistry.currentActor(profileId));
        Forwarded forwarded = invoker.call(endpoint.row(), ExternalModuleInvoker.Kind.ROUTE, call,
                response -> new Forwarded(response.statusCode(), response.body()));
        return new RouteOutcome(forwarded, null, null);
    }

    /**
     * Forwards a webhook, or returns empty when there is nothing to forward to.
     *
     * @throws ModuleUnavailableException the module could not be called or timed out (503 to the sender)
     */
    public Optional<Forwarded> webhook(String tenantId, String moduleId, byte[] rawBody,
                                       Map<String, String> headers) {
        Endpoint endpoint = registry.endpoint(tenantId, moduleId).orElse(null);
        if (endpoint == null || !ExternalModuleInvoker.callable(endpoint.row())
                || !endpoint.manifest().webhookEnabled()) {
            return Optional.empty();
        }
        Map<String, String> forwarded = forwardableHeaders(headers);
        String contentType = forwarded.remove("content-type");
        ModuleHttpClient.Call call = new ModuleHttpClient.Call("POST", ModuleHttpClient.WEBHOOKS_PATH, null,
                rawBody == null ? new byte[0] : rawBody, contentType == null ? "application/octet-stream" : contentType,
                forwarded, WEBHOOK_TIMEOUT, null);
        // The webhook path is unauthenticated and carries no tenant header, so nothing bound one:
        // bind the install's tenant for the call's own reads (the principal's credentials).
        TenantModuleData row = endpoint.row();
        return Optional.of(TenantContext.callWithTenantIfUnbound(row.tenantId(), null, () -> invoker.call(row,
                ExternalModuleInvoker.Kind.WEBHOOK, call, response -> new Forwarded(response.statusCode(), null))));
    }

    /**
     * The allowlisted headers, names lower-cased so a module looks one up by a known key instead of
     * guessing the sender's capitalization.
     */
    public static Map<String, String> forwardableHeaders(Map<String, String> headers) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        if (headers == null) {
            return forwarded;
        }
        headers.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (value != null && FORWARDED_WEBHOOK_HEADER_PREFIXES.stream().anyMatch(lower::startsWith)) {
                forwarded.put(lower, value);
            }
        });
        return forwarded;
    }

    /** The manifest route for an exact path and method, if declared. */
    static Optional<ExternalModuleManifest.Route> declared(ExternalModuleManifest manifest, String method,
                                                          String path) {
        String upper = method.toUpperCase(Locale.ROOT);
        return manifest.routes().stream()
                .filter(r -> r.path().equals(path))
                .filter(r -> (r.methods() == null || r.methods().isEmpty() ? List.of("GET") : r.methods())
                        .stream().anyMatch(m -> m.equalsIgnoreCase(upper)))
                .findFirst();
    }
}
