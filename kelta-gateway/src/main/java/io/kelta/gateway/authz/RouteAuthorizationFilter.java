package io.kelta.gateway.authz;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.JwtAuthenticationFilter;
import io.kelta.gateway.auth.PublicPathMatcher;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.filter.RequestLoggingFilter;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Global filter that enforces route-level authorization via Cerbos.
 *
 * <p>When {@code kelta.gateway.security.permissions-enabled} is false,
 * this filter only checks that the user is authenticated (valid JWT required).
 * Defaults to enabled (true).
 *
 * <p>When permissions are enabled, this filter checks:
 * <ul>
 *   <li>API_ACCESS system permission via Cerbos</li>
 *   <li>Object-level CRUD permissions via Cerbos (read/create/edit/delete on collection)</li>
 * </ul>
 *
 * <p>System permission overrides (VIEW_ALL_DATA, MODIFY_ALL_DATA) are encoded
 * in the generated Cerbos policies and handled transparently by Cerbos.
 *
 * <p>After authorization succeeds, forwards identity headers to the worker:
 * {@code X-User-Profile-Id}, {@code X-User-Email}, {@code X-Cerbos-Scope}.
 */
@Component
public class RouteAuthorizationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RouteAuthorizationFilter.class);

    private final RouteRegistry routeRegistry;
    private final boolean permissionsEnabled;
    private final PublicPathMatcher publicPathMatcher;
    private final GatewayMetrics metrics;
    private final ObjectMapper objectMapper;
    private final CerbosAuthorizationService cerbosService;

    public RouteAuthorizationFilter(
            RouteRegistry routeRegistry,
            @Value("${kelta.gateway.security.permissions-enabled:true}") boolean permissionsEnabled,
            PublicPathMatcher publicPathMatcher,
            GatewayMetrics metrics,
            ObjectMapper objectMapper,
            CerbosAuthorizationService cerbosService) {
        this.routeRegistry = routeRegistry;
        this.permissionsEnabled = permissionsEnabled;
        this.publicPathMatcher = publicPathMatcher;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.cerbosService = cerbosService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Allow public paths through without principal check
        if (publicPathMatcher.isPublicRequest(exchange)) {
            return chain.filter(exchange);
        }

        GatewayPrincipal principal = JwtAuthenticationFilter.getPrincipal(exchange);

        if (principal == null) {
            log.warn("No principal found in exchange for path: {}", path);
            String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
            String method = exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().name() : "unknown";
            metrics.recordAuthzDenied(tenantSlug, "unknown", method);
            return forbidden(exchange, "Authentication required");
        }

        log.debug("Authenticated user: {} accessing path: {}", principal.getUsername(), path);

        // If permissions enforcement is disabled, allow all authenticated users
        if (!permissionsEnabled) {
            setRouteAttribute(exchange, path);
            return forwardWithHeaders(exchange, chain, principal);
        }

        // Check that principal has identity resolved (profileId required for Cerbos)
        if (principal.getProfileId() == null || principal.getTenantId() == null) {
            log.warn("No profile/tenant resolved for user {} on path: {}", principal.getUsername(), path);
            String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
            String method = exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().name() : "unknown";
            metrics.recordAuthzDenied(tenantSlug, "unknown", method);
            return forbidden(exchange, "User identity not resolved");
        }

        // Check object-level permissions for API paths
        if (path.startsWith("/api/")) {
            return checkWithCerbos(exchange, chain, path, principal);
        }

        return forwardWithHeaders(exchange, chain, principal);
    }

    private Mono<Void> checkWithCerbos(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        String path,
                                        GatewayPrincipal principal) {
        String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
        String methodStr = exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().name() : "unknown";

        // 1. Check API_ACCESS system permission
        return cerbosService.checkSystemPermission(principal, "API_ACCESS")
                .flatMap(apiAccessAllowed -> {
                    if (!apiAccessAllowed) {
                        log.warn("User {} denied API access (no API_ACCESS) for path: {}",
                                principal.getUsername(), path);
                        metrics.recordAuthzDenied(tenantSlug, "unknown", methodStr);
                        return forbidden(exchange, "API access not permitted");
                    }

                    // 2. Look up the route to get collection info — the caller's own
                    // collection at this path; another tenant's same-named collection
                    // must never be the one we authorize against.
                    Optional<RouteDefinition> route = routeRegistry.findByPath(
                            path, TenantResolutionFilter.getTenantId(exchange));
                    if (route.isEmpty()) {
                        // No route found — not a collection API call, allow through
                        return forwardWithHeaders(exchange, chain, principal);
                    }

                    String collectionId = route.get().getId();
                    String collectionName = route.get().getCollectionName();
                    exchange.getAttributes().put(RequestLoggingFilter.ROUTE_ATTR, collectionName);

                    // Static routes (admin, me, metrics, search, etc.) are not
                    // real collections — they only require the API_ACCESS check
                    // above.  Skip the collection-level Cerbos check for them.
                    if (collectionId.startsWith("static-")) {
                        // Designating a module signing key is a grant of code-execution
                        // authority: a module JAR signed by a trusted key runs arbitrary Java in
                        // the worker JVM (SandboxedModuleClassLoader allows java.* wholesale).
                        // API_ACCESS — all the rest of /api/modules/** carries — is far too weak
                        // a gate for that, so writes here need MANAGE_CREDENTIALS. Reads are left
                        // at API_ACCESS: the keys are public, and the dependentModules count is
                        // what makes a rotation safe to plan.
                        if ("static-modules".equals(collectionId)
                                && path.startsWith("/api/modules/signing-keys")
                                && isWriteMethod(exchange)) {
                            return cerbosService.checkSystemPermission(principal, "MANAGE_CREDENTIALS")
                                    .flatMap(allowed -> {
                                        if (!allowed) {
                                            log.warn("User {} denied MANAGE_CREDENTIALS for module "
                                                    + "signing key write on path: {}",
                                                    principal.getUsername(), path);
                                            metrics.recordAuthzDenied(tenantSlug, collectionName, methodStr);
                                            return forbidden(exchange,
                                                    "Insufficient permissions: MANAGE_CREDENTIALS required");
                                        }
                                        return forwardWithHeaders(exchange, chain, principal);
                                    });
                        }
                        // Tenant management write operations require MANAGE_TENANTS
                        if ("static-tenants".equals(collectionId) && isWriteMethod(exchange)) {
                            return cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")
                                    .flatMap(allowed -> {
                                        if (!allowed) {
                                            log.warn("User {} denied MANAGE_TENANTS for tenant write on path: {}",
                                                    principal.getUsername(), path);
                                            metrics.recordAuthzDenied(tenantSlug, collectionName, methodStr);
                                            return forbidden(exchange,
                                                    "Insufficient permissions: MANAGE_TENANTS required");
                                        }
                                        return forwardWithHeaders(exchange, chain, principal);
                                    });
                        }
                        return forwardWithHeaders(exchange, chain, principal);
                    }

                    // Map HTTP method to Cerbos action
                    String action = mapMethodToAction(exchange.getRequest().getMethod());

                    // 3. Check collection-level permission
                    return cerbosService.checkObjectPermission(principal, collectionId, action)
                            .flatMap(allowed -> {
                                if (!allowed) {
                                    log.warn("User {} denied {} on collection '{}' (id={})",
                                            principal.getUsername(), action, collectionName, collectionId);
                                    metrics.recordAuthzDenied(tenantSlug, collectionName, methodStr);
                                    return forbidden(exchange,
                                            "Insufficient permissions for " + action + " on " + collectionName);
                                }
                                return forwardWithHeaders(exchange, chain, principal);
                            });
                });
    }

    /**
     * Forwards the request to the worker with identity headers for fine-grained checks.
     */
    private Mono<Void> forwardWithHeaders(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           GatewayPrincipal principal) {
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r
                        .header("X-User-Email", principal.getUsername())
                        .header("X-User-Profile-Id", principal.getProfileId() != null ? principal.getProfileId() : "")
                        .header("X-User-Profile-Name", principal.getProfileName() != null ? principal.getProfileName() : "")
                        .header("X-Cerbos-Scope", principal.getTenantId() != null ? principal.getTenantId() : ""))
                .build();
        return chain.filter(mutated);
    }

    private boolean isWriteMethod(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        return method == HttpMethod.POST || method == HttpMethod.PUT
                || method == HttpMethod.PATCH || method == HttpMethod.DELETE;
    }

    private String mapMethodToAction(HttpMethod method) {
        if (method == null) return "read";
        return switch (method.name()) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "edit";
            case "DELETE" -> "delete";
            default -> "read";
        };
    }

    /**
     * Sets the route attribute on the exchange for downstream metric recording.
     */
    private void setRouteAttribute(ServerWebExchange exchange, String path) {
        if (path != null && path.startsWith("/api/")) {
            routeRegistry.findByPath(path, TenantResolutionFilter.getTenantId(exchange))
                    .ifPresent(route -> exchange.getAttributes()
                            .put(RequestLoggingFilter.ROUTE_ATTR, route.getCollectionName()));
        }
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.FORBIDDEN)) {
            return Mono.empty();
        }

        ObjectNode error = objectMapper.createObjectNode();
        error.put("status", "403");
        error.put("code", "FORBIDDEN");
        error.put("detail", message);
        ObjectNode meta = error.putObject("meta");
        meta.put("path", exchange.getRequest().getPath().value());

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode errors = root.putArray("errors");
        errors.add(error);

        byte[] errorBytes;
        try {
            errorBytes = objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            errorBytes = "{\"errors\":[{\"status\":\"403\",\"code\":\"FORBIDDEN\"}]}".getBytes(StandardCharsets.UTF_8);
        }

        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(errorBytes))
        );
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
