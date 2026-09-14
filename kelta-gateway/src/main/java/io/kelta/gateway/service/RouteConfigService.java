package io.kelta.gateway.service;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.config.BootstrapConfig;
import io.kelta.gateway.config.CollectionConfig;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Service for fetching and managing route configuration from the worker service.
 *
 * <p>This service is responsible for:
 * <ul>
 *   <li>Fetching initial bootstrap configuration from the worker's internal API</li>
 *   <li>Parsing bootstrap response into RouteDefinition objects</li>
 *   <li>Validating required fields before adding routes to the registry</li>
 *   <li>Loading per-tenant governor limits for rate limiting</li>
 *   <li>Refreshing routes on demand</li>
 * </ul>
 *
 * <p>The worker exposes {@code /internal/bootstrap} which returns collections
 * and governor limits. This replaces the previous dependency on the control
 * plane's bootstrap endpoint, now replaced by the worker's
 * {@code /internal/bootstrap} endpoint.
 */
@Service
public class RouteConfigService {

    private static final Logger logger = LoggerFactory.getLogger(RouteConfigService.class);

    private static final String BOOTSTRAP_PATH = "/internal/bootstrap";

    private final WebClient webClient;
    private final RouteRegistry routeRegistry;
    private final GatewayCacheManager cacheManager;
    private final String workerServiceUrl;

    public RouteConfigService(
            WebClient.Builder webClientBuilder,
            RouteRegistry routeRegistry,
            GatewayCacheManager cacheManager,
            @Value("${kelta.gateway.worker-service-url:http://emf-worker:80}") String workerServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(workerServiceUrl).build();
        this.routeRegistry = routeRegistry;
        this.cacheManager = cacheManager;
        this.workerServiceUrl = workerServiceUrl;

        logger.info("Gateway → worker URL resolved to: {}", workerServiceUrl);
    }

    /**
     * Fetches the complete bootstrap configuration from the worker service.
     */
    public Mono<BootstrapConfig> fetchBootstrapConfig() {
        String url = workerServiceUrl + BOOTSTRAP_PATH;
        logger.info("Fetching bootstrap configuration from: {}", url);

        return webClient.get()
                .uri(BOOTSTRAP_PATH)
                .retrieve()
                .bodyToMono(BootstrapConfig.class)
                .doOnSuccess(config -> {
                    logger.info("Successfully fetched bootstrap configuration: {}", config);
                })
                .doOnError(error -> {
                    logger.error("Failed to fetch bootstrap configuration from {}: {}",
                               url, error.getMessage(), error);
                });
    }

    /**
     * Refreshes routes by fetching the latest bootstrap configuration
     * and updating the route registry.
     */
    public void refreshRoutes() {
        logger.info("Starting route refresh");

        // Register static routes UNCONDITIONALLY, before the bootstrap fetch. These are the
        // default admin/config/API routes (collections, users, flows, …). They must NOT depend
        // on the bootstrap response deserializing — otherwise any single bad DTO in the payload
        // (e.g. a native-image reflection gap) drops every static route and takes the whole API
        // offline. Bootstrap collection routes are registered afterwards and overwrite any
        // static route sharing the same path (bootstrap routes are more specific).
        registerStaticRoutes();

        fetchBootstrapConfig()
                .doOnNext(config -> {
                    if (config.getCollections() != null) {
                        int validRoutes = 0;
                        int invalidRoutes = 0;

                        for (CollectionConfig collection : config.getCollections()) {
                            RouteDefinition route = parseCollectionToRoute(collection);

                            if (route != null && validateRoute(route)) {
                                routeRegistry.addRoute(route);
                                validRoutes++;
                            } else {
                                invalidRoutes++;
                            }
                        }

                        logger.info("Route refresh completed: {} valid routes added, {} invalid routes skipped",
                                  validRoutes, invalidRoutes);
                    } else {
                        logger.warn("No collections found in bootstrap configuration");
                    }

                    // Load per-tenant governor limits for rate limiting
                    if (config.getGovernorLimits() != null) {
                        cacheManager.loadGovernorLimits(config.getGovernorLimits());
                        logger.info("Loaded governor limits for {} tenants",
                                config.getGovernorLimits().size());
                    } else {
                        logger.warn("No governor limits found in bootstrap configuration");
                    }

                    // Load per-tenant IP allowlists for network access enforcement
                    cacheManager.loadTenantIpConfigs(config.getIpAllowlists());
                })
                .doOnError(error -> {
                    logger.error("Route refresh failed: {}", error.getMessage(), error);
                })
                .block();
    }

    private RouteDefinition parseCollectionToRoute(CollectionConfig collection) {
        try {
            String collectionId = collection.getId();
            String collectionName = collection.getName();
            String path = collection.getPath();

            if (path != null && !path.endsWith("/**") && !path.endsWith("/*")) {
                path = path + "/**";
            }

            // Always use the configured worker service URL (K8s Service DNS) instead
            // of the pod-specific IP from the bootstrap response. Pod IPs are ephemeral
            // and become stale when pods restart, causing routing failures. The K8s
            // Service URL (e.g., http://emf-worker:80) is stable and load-balances
            // across all worker pods.
            RouteDefinition route = new RouteDefinition(
                collectionId,
                path,
                workerServiceUrl,
                collectionName
            );

            logger.debug("Parsed collection '{}' to route: {}", collectionId, route);
            return route;

        } catch (Exception e) {
            logger.error("Failed to parse collection to route: {}", collection, e);
            return null;
        }
    }

    /**
     * The static (non-collection) route table.
     *
     * <p>These endpoints are not returned in the bootstrap collection list because they are
     * not standard CRUD collections. They still need gateway routes so requests are proxied
     * to the worker instead of returning 404.
     *
     * <p>Package-visible so tests can derive the expected route count from it. Restating that
     * number as a literal in a test means every route added here fails an assertion that reads
     * like a routing bug but is only a stale constant.
     */
    static final String[][] STATIC_ROUTES = {
                // Core admin/config endpoints (not data collections)
                {"admin", "/api/admin/**", "admin"},
                {"me", "/api/me/**", "me"},
                {"metrics", "/api/metrics/**", "metrics"},
                {"search", "/api/_search/**", "_search"},
                // Setup & customization
                {"collections", "/api/collections/**", "collections"},
                {"modules", "/api/modules/**", "modules"},
                {"global-picklists", "/api/global-picklists/**", "global-picklists"},
                {"governor-limits", "/api/governor-limits/**", "governor-limits"},
                {"page-layouts", "/api/page-layouts/**", "page-layouts"},
                {"list-views", "/api/list-views/**", "list-views"},
                {"ui-pages", "/api/ui-pages/**", "ui-pages"},
                {"ui-menus", "/api/ui-menus/**", "ui-menus"},
                {"pages", "/api/pages/**", "pages"},
                // Security & identity
                {"whoami", "/api/whoami", "whoami"},
                {"profiles", "/api/profiles/**", "profiles"},
                {"users", "/api/users/**", "users"},
                {"oidc-providers", "/api/oidc-providers/**", "oidc-providers"},
                {"tenants", "/api/tenants/**", "tenants"},
                {"login-history", "/api/login-history/**", "login-history"},
                {"security-audit-logs", "/api/security-audit-logs/**", "security-audit-logs"},
                {"setup-audit-entries", "/api/setup-audit-entries/**", "setup-audit-entries"},
                {"field-history", "/api/field-history/**", "field-history"},
                {"record-versions", "/api/record-versions/**", "record-versions"},
                {"collection-versions", "/api/collection-versions/**", "collection-versions"},
                // Chat (telehealth slice 2) — controller enforces participant authz
                {"chat", "/api/chat/**", "chat"},
                // Scheduling (telehealth slice 4) — controller enforces owner/provider authz;
                // /api/telehealth/visits/** additionally rides the unauthenticated-paths list
                {"telehealth", "/api/telehealth/**", "telehealth"},
                // Support mailbox (support-mailbox slice 2) — a static- route, so only
                // API_ACCESS is checked here; MailboxAdminController enforces
                // MANAGE_SUPPORT_MAILBOX and, later, per-mailbox membership. Inbound mail does
                // NOT ride this path: it arrives on /api/webhooks/mail/** under the existing
                // unauthenticated-paths prefix, deliberately a different top-level segment so a
                // prefix-matching config change cannot open the authenticated surface.
                {"support", "/api/support/**", "support"},
                // Automation & integration
                {"flows", "/api/flows/**", "flows"},
                {"approval-processes", "/api/approval-processes/**", "approval-processes"},
                // Approval ACTION endpoints (submit/approve/reject/recall/status/history) —
                // distinct from the approval-processes CONFIG collection route above; without
                // this row ApprovalController is unreachable through the gateway (404).
                {"approvals", "/api/approvals/**", "approvals"},
                {"svix", "/api/svix/**", "svix"},
                {"webhooks", "/api/webhooks/**", "webhooks"},
                // Portal billing — a static- route, so only API_ACCESS is checked
                // here; the controller enforces member scoping. Inbound processor
                // webhooks no longer ride this path: they go to the module route
                // (/api/modules/webhooks/{tenantId}/{moduleId}), whose handler owns
                // the HMAC check. This route goes away with BillingController itself.
                {"billing", "/api/billing/**", "billing"},
                // Member-facing watch API (consumer-alerting slice 5) — a static-
                // route, so only API_ACCESS is checked here; WatchController owns
                // member scoping and WatchGuardHook covers the generic route.
                {"watches", "/api/watches/**", "watches"},
                {"connected-apps", "/api/connected-apps/**", "connected-apps"},
                {"email-templates", "/api/email-templates/**", "email-templates"},
                {"email", "/api/email/**", "email"},
                {"track", "/api/track/**", "track"},
                {"scripts", "/api/scripts/**", "scripts"},
                {"scheduled-jobs", "/api/scheduled-jobs/**", "scheduled-jobs"},
                // Reporting & monitoring
                {"reports", "/api/reports/**", "reports"},
                {"dashboards", "/api/dashboards/**", "dashboards"},
                {"superset", "/api/superset/**", "superset"},
                {"bulk-jobs", "/api/bulk-jobs/**", "bulk-jobs"},
                {"data-exports", "/api/data-exports/**", "data-exports"},
                {"migrations", "/api/migrations/**", "migrations"},
                {"migration-runs", "/api/migration-runs/**", "migration-runs"},
                // ALM & governance
                {"metadata", "/api/metadata/**", "metadata"},
                {"config-health", "/api/config-health/**", "config-health"},
                {"packages", "/api/packages/**", "packages"},
                {"environments", "/api/environments/**", "environments"},
                {"promotions", "/api/promotions/**", "promotions"},
                // Developer tools & media
                {"docs", "/api/docs/**", "docs"},
                {"files", "/api/files/**", "files"},
                {"images", "/api/images/**", "images"},
                {"operations", "/api/operations/**", "operations"},
                {"devices", "/api/devices/**", "devices"},
                // Analytics capture ingest (consumer-alerting slice 8) — API_ACCESS only,
                // owner-stamped from X-User-Id in the worker controller.
                {"analytics", "/api/analytics/**", "analytics"},
                // Win tracking + live ticker (consumer-alerting slice 9) — API_ACCESS only;
                // member scoping + ticker redaction enforced in WinController.
                {"wins", "/api/wins/**", "wins"},
                // SCIM 2.0 provisioning
                {"scim", "/scim/v2/**", "scim"},
        };

    /** Registers every {@link #STATIC_ROUTES} entry against the worker service URL. */
    private void registerStaticRoutes() {
        for (String[] routeDef : STATIC_ROUTES) {
            RouteDefinition route = new RouteDefinition(
                    "static-" + routeDef[0],
                    routeDef[1],
                    workerServiceUrl,
                    routeDef[2]
            );
            routeRegistry.addRoute(route);
            logger.debug("Registered static route: {}", route);
        }

        logger.info("Registered {} static routes", STATIC_ROUTES.length);
    }

    private boolean validateRoute(RouteDefinition route) {
        if (route == null) {
            logger.error("Cannot validate null route");
            return false;
        }

        boolean valid = true;
        StringBuilder errors = new StringBuilder();

        if (route.getId() == null || route.getId().isEmpty()) {
            errors.append("Missing collectionId (id); ");
            valid = false;
        }

        if (route.getPath() == null || route.getPath().isEmpty()) {
            errors.append("Missing path; ");
            valid = false;
        }

        if (route.getBackendUrl() == null || route.getBackendUrl().isEmpty()) {
            errors.append("Missing backendUrl; ");
            valid = false;
        }

        if (!valid) {
            logger.error("Route validation failed for route '{}': {}", route.getId(), errors.toString());
            logger.error("Invalid route details: {}", route);
        }

        return valid;
    }
}
