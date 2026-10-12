package io.kelta.gateway.authz;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.JwtAuthenticationFilter;
import io.kelta.gateway.auth.PublicPathMatcher;
import io.kelta.gateway.authz.cerbos.CerbosAuthorizationService;
import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.filter.CustomDomainFilter;
import io.kelta.gateway.filter.RequestLoggingFilter;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.gateway.route.StaticCollectionCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

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
 *   <li>For a child-resource path {@code /api/{parent}/{parentId}/{child}[/{childId}]} under a
 *       collection route whose child segment names a collection the caller's tenant can address:
 *       {@code read} on the parent and the child collection's own decision for the HTTP method
 *       (see {@code authorizeChildPath}). A child segment that names none, or a dedicated
 *       endpoint listed in {@link DedicatedSubResources}, keeps the parent route's decision.</li>
 * </ul>
 *
 * <p>System permission overrides (VIEW_ALL_DATA, MODIFY_ALL_DATA) are encoded
 * in the generated Cerbos policies and handled transparently by Cerbos.
 *
 * <p>After authorization succeeds, forwards identity headers to the worker:
 * {@code X-User-Profile-Id}, {@code X-User-Email}, {@code X-Cerbos-Scope}.
 */
@Component
@ImportRuntimeHints(DedicatedSubResources.Hints.class)
public class RouteAuthorizationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RouteAuthorizationFilter.class);

    private static final String TENANTS_PATH = "/api/tenants";

    /** Any caller-supplied filter on the tenants {@code id}: {@code filter[id]}, {@code filter[id][eq]}, … */
    private static final Pattern TENANT_ID_FILTER = Pattern.compile("(?i)^filter\\[\\s*id\\s*\\](\\[[^\\]]*\\])?$");

    /**
     * Worker endpoints of the child-resource shape served by a dedicated controller rather than the
     * generic child handlers ({@code GET /api/dashboards/{name}/tree}, …): their trailing segment
     * names an operation, so they keep the parent route's own decision even when a tenant has a
     * collection named like that segment. Loaded from {@value DedicatedSubResources#RESOURCE};
     * the worker's {@code DedicatedSubResourcesDriftTest} keeps that file in step with its
     * controllers.
     */
    private static final DedicatedSubResources DEDICATED_SUB_RESOURCES = DedicatedSubResources.loadDefault();

    /** Outcome of authorizing one route for one method. */
    private sealed interface Decision permits Allow, Deny, NotFound {}

    /**
     * Forward {@code exchange}. {@code narrowed} marks an allow that only holds for a rewritten
     * request (the own-tenant view of {@code /api/tenants}).
     */
    private record Allow(ServerWebExchange exchange, boolean narrowed) implements Decision {}

    /** 403 with {@code detail}. */
    private record Deny(String collectionName, String detail) implements Decision {}

    /** 404 — the response must not confirm the target exists. */
    private record NotFound(String collectionName) implements Decision {}

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
            if (isTenantsPath(path)) {
                return forwardAnonymousTenantLookup(exchange, chain, path);
            }
            if (publicPathMatcher.isTenantScopedPublicPath(path) && !hasResolvedTenant(exchange)) {
                // Bootstrap metadata belongs to one tenant. With none resolved (an unknown slug, or
                // no slug at all) nothing downstream scopes the read and it would return every
                // tenant's rows, so there is nothing to serve.
                return notFound(exchange);
            }
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
                    exchange.getAttributes().put(RequestLoggingFilter.ROUTE_ATTR, route.get().getCollectionName());

                    HttpMethod method = exchange.getRequest().getMethod();

                    // 3. A child-resource path under a collection route acts on the child
                    // collection, so it is authorized against the child as well as the parent.
                    PathContainer requestPath = exchange.getRequest().getPath().pathWithinApplication();
                    Optional<ChildResourcePath> child = ChildResourcePath.parse(requestPath);
                    if (child.isPresent() && !isStaticRoute(route.get())
                            && !DEDICATED_SUB_RESOURCES.matches(method, requestPath)) {
                        return authorizeChildPath(exchange, chain, principal, route.get(), child.get(), method);
                    }

                    // 4. Everything else: the route's own decision for this method
                    return decide(exchange, principal, path, method, route.get())
                            .flatMap(decision -> apply(exchange, chain, principal, decision));
                });
    }

    /**
     * Authorizes {@code /api/{parent}/{parentId}/{child}[/{childId}]}, which the worker's generic
     * collection router serves from the <em>child</em> collection. Two checks, both required:
     * <ul>
     *   <li>{@code read} on the parent collection — the caller addresses the child records through
     *       a parent record, whatever the operation on the child;</li>
     *   <li>exactly the decision a direct request to {@code /api/{child}[/{childId}]} with the same
     *       method gets ({@link #decide}), so the child's object permission (or, for a static
     *       route, that route's own rule) governs the operation.</li>
     * </ul>
     * The child segment is resolved among the collections the caller's tenant can address
     * ({@link RouteRegistry#findTenantVisibleByPath}) — never another tenant's same-named
     * collection. When it names none, the request gets the parent route's own decision for its
     * method, exactly as before child paths were recognized: the segment is then an operation
     * of a dedicated endpoint, or a child the gateway's route table does not know (yet). The
     * worker authorizes its generic child handlers itself, which covers a child collection
     * created after the gateway's last route refresh.
     */
    private Mono<Void> authorizeChildPath(ServerWebExchange exchange,
                                          GatewayFilterChain chain,
                                          GatewayPrincipal principal,
                                          RouteDefinition parentRoute,
                                          ChildResourcePath child,
                                          HttpMethod method) {
        String childPath = child.directPath();
        Optional<RouteDefinition> childRoute = child.isAddressable()
                ? routeRegistry.findTenantVisibleByPath(childPath, TenantResolutionFilter.getTenantId(exchange))
                : Optional.empty();
        if (childRoute.isEmpty()) {
            log.debug("Child segment '{}' under '{}' names no collection of the caller's tenant; "
                    + "keeping the parent route's decision", child.childName(), parentRoute.getCollectionName());
            return decide(exchange, principal, exchange.getRequest().getPath().value(), method, parentRoute)
                    .flatMap(decision -> apply(exchange, chain, principal, decision));
        }

        return decide(exchange, principal, exchange.getRequest().getPath().value(), HttpMethod.GET, parentRoute)
                .flatMap(parentDecision -> {
                    if (!isFullAllow(parentDecision)) {
                        return apply(exchange, chain, principal, asDenial(parentDecision, parentRoute, HttpMethod.GET));
                    }
                    return decide(exchange, principal, childPath, method, childRoute.get())
                            .map(childDecision -> isFullAllow(childDecision)
                                    // forward the request as sent, not the direct-path stand-in
                                    ? new Allow(exchange, false)
                                    : asDenial(childDecision, childRoute.get(), method))
                            .flatMap(decision -> apply(exchange, chain, principal, decision));
                });
    }

    /**
     * The route's authorization decision for {@code method} on {@code path}: the shared core of a
     * direct request and of each half of a child-resource request.
     */
    private Mono<Decision> decide(ServerWebExchange exchange,
                                  GatewayPrincipal principal,
                                  String path,
                                  HttpMethod method,
                                  RouteDefinition route) {
        String collectionId = route.getId();
        String collectionName = route.getCollectionName();

        // A static route that fronts a real system collection (users, profiles,
        // collections — declared in RouteConfigService) is authorized against that
        // collection, so the request is refused before the worker validates its body.
        StaticCollectionCheck staticCheck = route.getCollectionCheck();
        if (staticCheck != null && staticCheck.appliesTo(method, path)) {
            return decideStaticCollection(exchange, principal, path, method, route, staticCheck);
        }

        // Other static routes (admin, me, metrics, search, etc.) are not
        // real collections — they only require the API_ACCESS check
        // above.  Skip the collection-level Cerbos check for them.
        if (isStaticRoute(route)) {
            // Designating a module signing key is a grant of code-execution
            // authority: a module JAR signed by a trusted key runs arbitrary Java in
            // the worker JVM (SandboxedModuleClassLoader allows java.* wholesale).
            // API_ACCESS — all the rest of /api/modules/** carries — is far too weak
            // a gate for that, so writes here need MANAGE_CREDENTIALS. Reads are left
            // at API_ACCESS: the keys are public, and the dependentModules count is
            // what makes a rotation safe to plan.
            if ("static-modules".equals(collectionId)
                    && path.startsWith("/api/modules/signing-keys")
                    && StaticCollectionCheck.isWrite(method)) {
                return cerbosService.checkSystemPermission(principal, "MANAGE_CREDENTIALS")
                        .map(allowed -> {
                            if (!allowed) {
                                log.warn("User {} denied MANAGE_CREDENTIALS for module "
                                        + "signing key write on path: {}",
                                        principal.getUsername(), path);
                                return new Deny(collectionName,
                                        "Insufficient permissions: MANAGE_CREDENTIALS required");
                            }
                            return new Allow(exchange, false);
                        });
            }
            // Tenant management write operations require MANAGE_TENANTS
            if ("static-tenants".equals(collectionId) && StaticCollectionCheck.isWrite(method)) {
                return cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")
                        .map(allowed -> {
                            if (!allowed) {
                                log.warn("User {} denied MANAGE_TENANTS for tenant write on path: {}",
                                        principal.getUsername(), path);
                                return new Deny(collectionName,
                                        "Insufficient permissions: MANAGE_TENANTS required");
                            }
                            return new Allow(exchange, false);
                        });
            }
            // The tenants collection has no tenant_id column — each row *is* a tenant —
            // so nothing downstream narrows reads of it to the caller. Without
            // MANAGE_TENANTS (the platform-admin grant that already gates writes) a
            // caller may read only its own tenant's row.
            if ("static-tenants".equals(collectionId)) {
                return cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")
                        .map(platformAdmin -> platformAdmin
                                ? new Allow(exchange, false)
                                : ownTenantOnly(exchange, path, principal));
            }
            return Mono.just(new Allow(exchange, false));
        }

        // Collection route: the Cerbos object permission for the HTTP method's action
        String action = mapMethodToAction(method);
        return cerbosService.checkObjectPermission(principal, collectionId, action)
                .map(allowed -> {
                    if (!allowed) {
                        log.warn("User {} denied {} on collection '{}' (id={})",
                                principal.getUsername(), action, collectionName, collectionId);
                        return new Deny(collectionName,
                                "Insufficient permissions for " + action + " on " + collectionName);
                    }
                    return new Allow(exchange, false);
                });
    }

    /**
     * Collection-level check for a static route declared with a {@link StaticCollectionCheck}: the
     * Cerbos object permission for the HTTP action on the system collection behind the route, or
     * — for a write — the declared override system permission. Fails closed when the collection's
     * route is not registered (bootstrap not loaded): without its id there is nothing to check.
     */
    private Mono<Decision> decideStaticCollection(ServerWebExchange exchange,
                                                  GatewayPrincipal principal,
                                                  String path,
                                                  HttpMethod method,
                                                  RouteDefinition staticRoute,
                                                  StaticCollectionCheck staticCheck) {
        String collectionName = staticRoute.getCollectionName();
        String action = mapMethodToAction(method);

        Optional<RouteDefinition> backing = routeRegistry.findCollectionRouteBehind(staticRoute);
        if (backing.isEmpty()) {
            log.error("No collection route behind static route '{}' — denying {} on path: {}",
                    staticRoute.getId(), action, path);
            return Mono.just(new Deny(collectionName,
                    "Insufficient permissions for " + action + " on " + collectionName));
        }

        String override = staticCheck.writeOverridePermission();
        Mono<Boolean> overrideAllowed = override != null && StaticCollectionCheck.isWrite(method)
                ? cerbosService.checkSystemPermission(principal, override)
                : Mono.just(false);

        return overrideAllowed
                .flatMap(viaOverride -> viaOverride
                        ? Mono.just(true)
                        : cerbosService.checkObjectPermission(principal, backing.get().getId(), action))
                .map(allowed -> {
                    if (!allowed) {
                        log.warn("User {} denied {} on static collection route '{}' (collection id={})",
                                principal.getUsername(), action, collectionName, backing.get().getId());
                        return new Deny(collectionName,
                                "Insufficient permissions for " + action + " on " + collectionName);
                    }
                    return new Allow(exchange, false);
                });
    }

    /** Carries out a {@link Decision}: forward, 403 or 404 (both denials are recorded). */
    private Mono<Void> apply(ServerWebExchange exchange,
                             GatewayFilterChain chain,
                             GatewayPrincipal principal,
                             Decision decision) {
        return switch (decision) {
            case Allow allow -> forwardWithHeaders(allow.exchange(), chain, principal);
            case Deny deny -> {
                recordDenied(exchange, deny.collectionName());
                yield forbidden(exchange, deny.detail());
            }
            case NotFound notFound -> {
                recordDenied(exchange, notFound.collectionName());
                yield notFound(exchange);
            }
        };
    }

    private void recordDenied(ServerWebExchange exchange, String collectionName) {
        String methodStr = exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().name() : "unknown";
        metrics.recordAuthzDenied(TenantResolutionFilter.getTenantSlug(exchange), collectionName, methodStr);
    }

    /** An unconditional allow — not one that only holds for a narrowed rewrite of the request. */
    private static boolean isFullAllow(Decision decision) {
        return decision instanceof Allow allow && !allow.narrowed();
    }

    /**
     * Half of a child-resource decision that did not allow outright. A narrowed allow (the
     * own-tenant view of {@code /api/tenants}) is a denial here: the narrowing rewrites the
     * direct path's query, which is not the request being forwarded.
     */
    private Decision asDenial(Decision decision, RouteDefinition route, HttpMethod method) {
        if (decision instanceof Allow) {
            return new Deny(route.getCollectionName(),
                    "Insufficient permissions for " + mapMethodToAction(method) + " on " + route.getCollectionName());
        }
        return decision;
    }

    private static boolean isStaticRoute(RouteDefinition route) {
        return route.getId() != null && route.getId().startsWith("static-");
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

    /**
     * Narrows a read of {@code /api/tenants} to the caller's own tenant.
     *
     * <p>A list gets {@code filter[id][eq]=<caller tenant>} after every caller-supplied
     * {@code filter[id]…} parameter is dropped, so a duplicate key can never win over it; other
     * filters still apply and can only narrow further. A request for any other tenant's row (or
     * anything beneath it) is a 404, not a 403, so the response does not confirm the id exists.
     */
    private Decision ownTenantOnly(ServerWebExchange exchange, String path, GatewayPrincipal principal) {
        String ownTenantId = principal.getTenantId();
        String rest = path.length() > TENANTS_PATH.length() ? path.substring(TENANTS_PATH.length()) : "";
        if (rest.isEmpty() || "/".equals(rest)) {
            return new Allow(narrowTenantsList(exchange, ownTenantId, false), true);
        }
        String requestedId = rest.substring(1).split("/", 2)[0];
        if (!requestedId.equals(ownTenantId)) {
            log.warn("User {} denied read of another tenant's record on path: {}",
                    principal.getUsername(), path);
            return new NotFound("tenants");
        }
        return new Allow(exchange, false);
    }

    /**
     * The anonymous (no {@code Authorization} header) view of {@code /api/tenants}, which exists
     * only so the UI bootstrap can learn its own tenant's id and display name before sign-in.
     * Narrowed to the tenant the URL names ({@link TenantResolutionFilter}), and to
     * {@code name}/{@code slug} — never settings, limits, edition or the IP allowlist. Any other
     * tenant's record, or a request whose URL resolves no tenant, is a 404.
     */
    private Mono<Void> forwardAnonymousTenantLookup(ServerWebExchange exchange,
                                                    GatewayFilterChain chain,
                                                    String path) {
        String urlTenantId = TenantResolutionFilter.getTenantId(exchange);
        if (urlTenantId == null || urlTenantId.isBlank()) {
            return notFound(exchange);
        }
        String rest = path.length() > TENANTS_PATH.length() ? path.substring(TENANTS_PATH.length()) : "";
        if (rest.isEmpty() || "/".equals(rest)) {
            return chain.filter(narrowTenantsList(exchange, urlTenantId, true));
        }
        String[] segments = rest.substring(1).split("/", 2);
        if (!segments[0].equals(urlTenantId) || segments.length > 1) {
            return notFound(exchange);
        }
        return chain.filter(withQuery(exchange, anonymousFields(copyParams(exchange, false))));
    }

    /**
     * {@code /api/tenants} list narrowed to {@code tenantId}: every caller-supplied
     * {@code filter[id]…} parameter is dropped first so a duplicate key can never win over it.
     */
    private ServerWebExchange narrowTenantsList(ServerWebExchange exchange, String tenantId, boolean anonymous) {
        MultiValueMap<String, String> params = copyParams(exchange, true);
        params.set("filter[id][eq]", tenantId);
        return withQuery(exchange, anonymous ? anonymousFields(params) : params);
    }

    private MultiValueMap<String, String> copyParams(ServerWebExchange exchange, boolean dropIdFilters) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        exchange.getRequest().getQueryParams().forEach((key, values) -> {
            if (!(dropIdFilters && TENANT_ID_FILTER.matcher(key).matches())) {
                params.put(key, values);
            }
        });
        return params;
    }

    private MultiValueMap<String, String> anonymousFields(MultiValueMap<String, String> params) {
        params.keySet().removeIf(key -> key.toLowerCase().startsWith("fields[") || key.equalsIgnoreCase("include"));
        params.set("fields[tenants]", "name,slug");
        return params;
    }

    private ServerWebExchange withQuery(ServerWebExchange exchange, MultiValueMap<String, String> params) {
        URI uri = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                .replaceQueryParams(params)
                .encode()
                .build()
                .toUri();
        return exchange.mutate().request(r -> r.uri(uri)).build();
    }

    /**
     * A tenant is resolved when the URL slug (or an {@code X-Tenant-ID} header) produced a tenant
     * id, or a verified custom domain named the tenant (its id is resolved from the slug downstream).
     */
    private static boolean hasResolvedTenant(ServerWebExchange exchange) {
        String tenantId = TenantResolutionFilter.getTenantId(exchange);
        return (tenantId != null && !tenantId.isBlank())
                || Boolean.TRUE.equals(exchange.getAttributes().get(CustomDomainFilter.CUSTOM_DOMAIN_RESOLVED));
    }

    private static boolean isTenantsPath(String path) {
        return path.equals(TENANTS_PATH) || path.startsWith(TENANTS_PATH + "/");
    }

    private Mono<Void> notFound(ServerWebExchange exchange) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.NOT_FOUND)) {
            return Mono.empty();
        }
        byte[] body = "{\"errors\":[{\"status\":\"404\",\"code\":\"NOT_FOUND\",\"detail\":\"Not found\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
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
