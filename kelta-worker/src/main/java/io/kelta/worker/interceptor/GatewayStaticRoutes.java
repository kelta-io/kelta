package io.kelta.worker.interceptor;

import java.util.Map;
import java.util.Set;

/**
 * The worker's copy of the gateway's {@code static-} route table — the part of it that decides
 * how a direct request to a collection is authorized ({@code RouteConfigService.STATIC_ROUTES},
 * {@code RouteConfigService.COLLECTION_CHECKED_STATIC_ROUTES} and the {@code static-tenants} /
 * {@code static-modules} rules in {@code RouteAuthorizationFilter.decide}).
 *
 * <p>{@link CerbosSubResourceAuthorizer} needs it to give a child-resource request the decision a
 * direct request to the child collection gets at the gateway. {@code GatewayStaticRoutesSyncTest}
 * reads the gateway sources and fails when the two drift.
 */
final class GatewayStaticRoutes {

    private GatewayStaticRoutes() {}

    /**
     * The {@code /api/<segment>/**} prefixes the gateway serves through a {@code static-} route.
     * For a system collection of that name the gateway applies the static route's rule (only
     * {@code API_ACCESS}, unless listed in {@link #COLLECTION_CHECKS} or special-cased below), not
     * the collection's object permission.
     */
    static final Set<String> SEGMENTS = Set.of(
            "admin", "me", "metrics", "_search",
            "collections", "modules", "global-picklists", "governor-limits", "page-layouts",
            "list-views", "ui-pages", "ui-menus", "pages",
            "profiles", "users", "oidc-providers", "tenants", "login-history", "security-audit-logs",
            "setup-audit-entries", "field-history", "record-versions", "collection-versions",
            "chat", "telehealth", "support",
            "flows", "approval-processes", "approvals", "svix", "webhooks", "watches", "alerts",
            "connected-apps", "email-templates", "email", "track", "scripts", "scheduled-jobs",
            "reports", "dashboards", "superset", "bulk-jobs", "data-exports", "migrations",
            "migration-runs",
            "metadata", "config-health", "packages", "environments", "promotions",
            "docs", "files", "images", "operations", "devices",
            "analytics", "wins");

    /**
     * A static route that fronts a system collection and is authorized against it
     * ({@code StaticCollectionCheck}).
     *
     * @param includeReads            whether reads are checked too, or only writes
     * @param writeOverridePermission system permission that admits a write without the
     *                                collection grant
     */
    record CollectionCheck(boolean includeReads, String writeOverridePermission) {}

    /**
     * {@code COLLECTION_CHECKED_STATIC_ROUTES}. The {@code collections} route's exempt sub-paths
     * ({@code /{name}/import|duplicates|merge}) are deeper than any direct request to a child
     * collection, so they never apply here.
     */
    static final Map<String, CollectionCheck> COLLECTION_CHECKS = Map.of(
            "users", new CollectionCheck(true, "MANAGE_USERS"),
            "profiles", new CollectionCheck(false, "MANAGE_USERS"),
            "collections", new CollectionCheck(false, "CUSTOMIZE_APPLICATION"));

    /** {@code static-tenants}: writes need this; reads need it or address the caller's own tenant. */
    static final String TENANTS = "tenants";
    static final String TENANTS_PERMISSION = "MANAGE_TENANTS";

    /** {@code static-modules}: writes under {@code /api/modules/signing-keys} need this. */
    static final String MODULES = "modules";
    static final String MODULE_SIGNING_KEYS_PREFIX = "signing-keys";
    static final String MODULE_SIGNING_KEYS_PERMISSION = "MANAGE_CREDENTIALS";
}
