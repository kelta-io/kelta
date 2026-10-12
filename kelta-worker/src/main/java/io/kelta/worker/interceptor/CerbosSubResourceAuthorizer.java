package io.kelta.worker.interceptor;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.router.SubResourceAuthorizer;
import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.CollectionLifecycleManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Authorizes the generic collection router's child-resource handlers
 * ({@code /api/{parent}/{parentId}/{child}[/{childId}]}) against the <em>child</em> collection.
 *
 * <p>Two checks, both required — the same pair the gateway's {@code RouteAuthorizationFilter}
 * applies to a child path under a collection route:
 * <ul>
 *   <li>{@code read} on the parent collection, since the child records are addressed through a
 *       parent record;</li>
 *   <li>the decision a direct request to {@code /api/{child}[/{childId}]} with the same method
 *       gets at the gateway: the child's Cerbos object permission for the operation
 *       (READ→{@code read}, CREATE→{@code create}, EDIT→{@code edit}, DELETE→{@code delete}),
 *       or, for a system collection the gateway fronts with a {@code static-} route, that route's
 *       rule ({@link GatewayStaticRoutes}).</li>
 * </ul>
 * Each decision uses the same Cerbos inputs as the gateway — the {@code collection} resource keyed
 * on the collection's id, and the {@code system_feature} resource for a system permission — so a
 * profile's {@code VIEW_ALL_DATA}/{@code MODIFY_ALL_DATA} grants and permission sets answer the
 * same way on both sides.
 *
 * <p>The gateway cannot apply this pair under a {@code static-} parent route, because dedicated
 * controllers share that path depth; here it runs on the router's child handlers only, so those
 * controllers are untouched. Under a collection parent it repeats the gateway's decision.
 *
 * <p>Requests without the gateway's identity headers (service-to-service calls inside the
 * cluster) are not checked, the same trust boundary as the record and field advices.
 */
@Component
public class CerbosSubResourceAuthorizer implements SubResourceAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(CerbosSubResourceAuthorizer.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private final CerbosAuthorizationService authzService;
    private final CerbosPermissionResolver permissionResolver;
    private final CollectionLifecycleManager lifecycleManager;
    private final boolean permissionsEnabled;

    public CerbosSubResourceAuthorizer(
            CerbosAuthorizationService authzService,
            CerbosPermissionResolver permissionResolver,
            CollectionLifecycleManager lifecycleManager,
            @Value("${kelta.gateway.security.permissions-enabled:true}") boolean permissionsEnabled) {
        this.authzService = authzService;
        this.permissionResolver = permissionResolver;
        this.lifecycleManager = lifecycleManager;
        this.permissionsEnabled = permissionsEnabled;
    }

    /** The identity the gateway forwarded. */
    private record Caller(String email, String profileId, String tenantId) {}

    @Override
    public Decision authorize(Access access, HttpServletRequest request) {
        if (!permissionsEnabled || !permissionResolver.hasIdentity(request)) {
            return Decision.allow();
        }
        Caller caller = new Caller(permissionResolver.getEmail(request),
                permissionResolver.getProfileId(request), permissionResolver.getTenantId(request));

        Decision parent = decide(caller, access.parent(), Operation.READ, access.parentId());
        if (!parent.allowed()) {
            denied(caller, access, access.parent().name(), Operation.READ);
            return parent;
        }
        Decision child = decide(caller, access.child(), access.operation(), access.childId());
        if (!child.allowed()) {
            denied(caller, access, access.child().name(), access.operation());
        }
        return child;
    }

    /**
     * The decision {@link #authorize} makes for the child alone: what a direct request to
     * {@code /api/{collection}} performing {@code operation} gets at the gateway. For a dedicated
     * endpoint that writes records of a collection named in its path, where the gateway only
     * applied the enclosing route's rule (the CSV import under {@code /api/collections/**}).
     *
     * <p>Same trust boundary as {@link #authorize}: requests without the gateway's identity
     * headers, or with permissions disabled, are allowed.
     */
    public Decision authorizeCollection(CollectionDefinition collection, Operation operation,
                                        HttpServletRequest request) {
        if (!permissionsEnabled || !permissionResolver.hasIdentity(request)) {
            return Decision.allow();
        }
        Caller caller = new Caller(permissionResolver.getEmail(request),
                permissionResolver.getProfileId(request), permissionResolver.getTenantId(request));
        Decision decision = decide(caller, collection, operation, null);
        if (!decision.allowed()) {
            securityLog.warn("security_event=COLLECTION_WRITE_DENIED user={} profile={} collection={} action={} path={}",
                    caller.email(), caller.profileId(), collection.name(), action(operation),
                    request.getRequestURI());
        }
        return decision;
    }

    /**
     * The gateway's decision for a direct request to {@code /api/{collection}[/{recordId}]}
     * performing {@code operation}.
     */
    private Decision decide(Caller caller, CollectionDefinition collection, Operation operation, String recordId) {
        String name = collection.name();
        // The gateway prefers the tenant's own collection route; a system collection is reached
        // through the static- route of the same name when one exists.
        if (collection.systemCollection() && GatewayStaticRoutes.SEGMENTS.contains(name)) {
            return decideStatic(caller, name, operation, recordId);
        }
        return objectPermission(caller, name, action(operation));
    }

    /** {@code RouteAuthorizationFilter.decide} for a {@code static-} route. */
    private Decision decideStatic(Caller caller, String name, Operation operation, String recordId) {
        boolean write = operation != Operation.READ;
        String action = action(operation);

        if (GatewayStaticRoutes.TENANTS.equals(name)) {
            if (systemPermission(caller, GatewayStaticRoutes.TENANTS_PERMISSION)) {
                return Decision.allow();
            }
            if (write) {
                return Decision.forbidden(
                        "Insufficient permissions: " + GatewayStaticRoutes.TENANTS_PERMISSION + " required");
            }
            if (recordId == null) {
                // The gateway narrows a tenants list to the caller's own row — a rewrite of that
                // request, which does not carry over to this one.
                return Decision.forbidden(insufficient(action, name));
            }
            return recordId.equals(caller.tenantId()) ? Decision.allow() : Decision.notFound();
        }

        if (GatewayStaticRoutes.MODULES.equals(name) && write && recordId != null
                && recordId.startsWith(GatewayStaticRoutes.MODULE_SIGNING_KEYS_PREFIX)) {
            return systemPermission(caller, GatewayStaticRoutes.MODULE_SIGNING_KEYS_PERMISSION)
                    ? Decision.allow()
                    : Decision.forbidden("Insufficient permissions: "
                            + GatewayStaticRoutes.MODULE_SIGNING_KEYS_PERMISSION + " required");
        }

        GatewayStaticRoutes.CollectionCheck check = GatewayStaticRoutes.COLLECTION_CHECKS.get(name);
        if (check != null && (check.includeReads() || write)) {
            if (write && check.writeOverridePermission() != null
                    && systemPermission(caller, check.writeOverridePermission())) {
                return Decision.allow();
            }
            return objectPermission(caller, name, action);
        }

        // Every other static route stops at API_ACCESS, which the gateway has already checked.
        return Decision.allow();
    }

    /** The Cerbos {@code collection} object permission, keyed on the collection id as at the gateway. */
    private Decision objectPermission(Caller caller, String collectionName, String action) {
        String tenantId = TenantContext.get() != null ? TenantContext.get() : caller.tenantId();
        String collectionId = lifecycleManager.getTenantVisibleCollectionId(collectionName, tenantId);
        if (collectionId == null) {
            // Nothing to check against — fail closed.
            log.warn("No collection id for '{}' (tenant {}) — denying {}", collectionName, tenantId, action);
            return Decision.forbidden(insufficient(action, collectionName));
        }
        boolean allowed = authzService.checkCollectionAccess(
                caller.email(), caller.profileId(), caller.tenantId(), collectionId, action);
        return allowed ? Decision.allow() : Decision.forbidden(insufficient(action, collectionName));
    }

    private boolean systemPermission(Caller caller, String permission) {
        return authzService.checkSystemPermission(caller.email(), caller.profileId(), caller.tenantId(), permission);
    }

    private void denied(Caller caller, Access access, String collectionName, Operation operation) {
        securityLog.warn("security_event=SUB_RESOURCE_DENIED user={} profile={} parent={} child={} "
                        + "collection={} action={}",
                caller.email(), caller.profileId(), access.parent().name(), access.child().name(),
                collectionName, action(operation));
    }

    private static String insufficient(String action, String collectionName) {
        return "Insufficient permissions for " + action + " on " + collectionName;
    }

    /** The Cerbos action the gateway maps the equivalent HTTP method to. */
    static String action(Operation operation) {
        return switch (operation) {
            case READ -> "read";
            case CREATE -> "create";
            case EDIT -> "edit";
            case DELETE -> "delete";
        };
    }
}
