package io.kelta.gateway.filter;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Gateway global filter that settles the request's tenant. Runs before JwtAuthenticationFilter
 * (-100) so tenant context is available to every auth and authz filter.
 *
 * <p>Resolution, in priority order:
 * <ol>
 *   <li><b>URL</b> — a verified custom domain ({@link CustomDomainFilter}) or the
 *       {@code /{slug}/...} prefix ({@link TenantSlugExtractionFilter}). Authoritative: client
 *       {@code X-Tenant-ID} / {@code X-Tenant-Slug} headers are ignored. A custom domain only
 *       names the slug, so the id is resolved from it here.</li>
 *   <li><b>Header</b> — only when the URL named no tenant: {@code X-Tenant-ID} (its slug
 *       resolved from the id, never taken from the client), else {@code X-Tenant-Slug} resolved
 *       to its id. Either way the tenant is forwarded with both id and slug. The result is marked
 *       {@link #TENANT_SOURCE_HEADER}: it is a <em>claim</em> the credential must prove.
 *       {@code JwtAuthenticationFilter} / {@code PatAuthenticationFilter} reject a token whose
 *       tenant is missing or differs, and an anonymous request never gets a header-selected
 *       tenant (no Guest admission, public paths see no tenant).</li>
 * </ol>
 *
 * <p>The client's tenant headers are always stripped from the forwarded request;
 * {@link HeaderTransformationFilter} re-adds them from the attributes settled here, so the
 * worker only ever sees a gateway-derived tenant (PLT-355).
 */
@Component
public class TenantResolutionFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantResolutionFilter.class);

    public static final String TENANT_ID_ATTR = "tenantId";
    public static final String TENANT_SLUG_ATTR = "tenantSlug";
    public static final String TENANT_SOURCE_ATTR = "tenantSource";
    public static final String TENANT_SOURCE_HEADER = "header";

    static final String TENANT_ID_HEADER = "X-Tenant-ID";
    static final String TENANT_SLUG_HEADER = "X-Tenant-Slug";

    private final GatewayMetrics metrics;
    private final GatewayCacheManager cacheManager;

    public TenantResolutionFilter(GatewayMetrics metrics, GatewayCacheManager cacheManager) {
        this.metrics = metrics;
        this.cacheManager = cacheManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String headerTenantId = trimToNull(request.getHeaders().getFirst(TENANT_ID_HEADER));
        String headerTenantSlug = trimToNull(request.getHeaders().getFirst(TENANT_SLUG_HEADER));
        boolean sentTenantHeader = request.getHeaders().containsHeader(TENANT_ID_HEADER)
                || request.getHeaders().containsHeader(TENANT_SLUG_HEADER);
        ServerWebExchange stripped = !sentTenantHeader ? exchange
                : exchange.mutate().request(request.mutate().headers(h -> {
                    h.remove(TENANT_ID_HEADER);
                    h.remove(TENANT_SLUG_HEADER);
                }).build()).build();

        String urlTenantId = getTenantId(stripped);
        String urlTenantSlug = getTenantSlug(stripped);

        if (urlTenantId != null && !urlTenantId.isBlank()) {
            logIgnoredHeader(urlTenantId, headerTenantId, headerTenantSlug, stripped);
            return chain.filter(stripped);
        }

        if (Boolean.TRUE.equals(stripped.getAttributes().get(CustomDomainFilter.CUSTOM_DOMAIN_RESOLVED))) {
            return cacheManager.resolveTenantSlugReactive(urlTenantSlug).flatMap(id -> {
                id.ifPresent(tenantId -> stripped.getAttributes().put(TENANT_ID_ATTR, tenantId));
                logIgnoredHeader(id.orElse(null), headerTenantId, headerTenantSlug, stripped);
                return chain.filter(stripped);
            });
        }

        if (urlTenantSlug != null) {
            // Slug-shaped URL segment that names no tenant: the URL still chose, so the header
            // must not fill in a tenant behind it.
            logIgnoredHeader(null, headerTenantId, headerTenantSlug, stripped);
            return chain.filter(stripped);
        }

        if (headerTenantId == null && headerTenantSlug == null) {
            log.debug("No tenant context in request to: {}", request.getPath().value());
            metrics.recordTenantResolution("none", "skipped");
            return chain.filter(stripped);
        }

        return resolveHeaderTenant(headerTenantId, headerTenantSlug).flatMap(resolved -> {
            resolved.ifPresentOrElse(tenant -> {
                stripped.getAttributes().put(TENANT_ID_ATTR, tenant.id());
                if (tenant.slug() != null) {
                    stripped.getAttributes().put(TENANT_SLUG_ATTR, tenant.slug());
                }
                stripped.getAttributes().put(TENANT_SOURCE_ATTR, TENANT_SOURCE_HEADER);
                log.debug("Tenant claimed by header: id={}, slug={}", tenant.id(), tenant.slug());
                metrics.recordTenantResolution("header", "success");
            }, () -> {
                log.debug("Tenant slug header '{}' names no tenant", headerTenantSlug);
                metrics.recordTenantResolution("header", "not_found");
            });
            return chain.filter(stripped);
        });
    }

    private record HeaderTenant(String id, String slug) {}

    /**
     * An {@code X-Tenant-ID} wins, and the slug forwarded with it is always the one that id maps
     * to: the worker selects the tenant's schema by slug, so an id alone would run tenant queries
     * against the public schema. A client {@code X-Tenant-Slug} is only ever used to find the id
     * when no {@code X-Tenant-ID} was sent. An id the slug map does not know is still claimed,
     * with no slug — the credential check decides whether it stands.
     */
    private Mono<Optional<HeaderTenant>> resolveHeaderTenant(String headerTenantId, String headerTenantSlug) {
        if (headerTenantId != null) {
            return cacheManager.resolveTenantIdToSlugReactive(headerTenantId)
                    .map(slug -> Optional.of(new HeaderTenant(headerTenantId, slug.orElse(null))));
        }
        return cacheManager.resolveTenantSlugReactive(headerTenantSlug)
                .map(slugId -> slugId.map(id -> new HeaderTenant(id, headerTenantSlug)));
    }

    private void logIgnoredHeader(String urlTenantId, String headerTenantId, String headerTenantSlug,
                                  ServerWebExchange exchange) {
        boolean idDiffers = headerTenantId != null && !headerTenantId.equals(urlTenantId);
        boolean slugDiffers = headerTenantSlug != null && !headerTenantSlug.equals(getTenantSlug(exchange));
        if (idDiffers || slugDiffers) {
            log.debug("Ignoring tenant header (id={}, slug={}) — URL tenant id={} slug={} wins",
                    headerTenantId, headerTenantSlug, urlTenantId, getTenantSlug(exchange));
            metrics.recordTenantResolution("header", "ignored");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public int getOrder() {
        return -200; // Before JwtAuthenticationFilter (-100)
    }

    /**
     * Gets the resolved tenant ID from the exchange attributes.
     */
    public static String getTenantId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(TENANT_ID_ATTR);
    }

    /**
     * Gets the resolved tenant slug from the exchange attributes.
     */
    public static String getTenantSlug(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(TENANT_SLUG_ATTR);
    }

    /**
     * True when the tenant came from a client header rather than the URL — an unproven claim
     * that only a credential for that same tenant may use.
     */
    public static boolean isHeaderSourced(ServerWebExchange exchange) {
        return TENANT_SOURCE_HEADER.equals(exchange.getAttributes().get(TENANT_SOURCE_ATTR));
    }

    /**
     * Drops a header-claimed tenant, so a request no credential vouches for runs with no tenant.
     */
    public static void clearHeaderSourcedTenant(ServerWebExchange exchange) {
        if (isHeaderSourced(exchange)) {
            exchange.getAttributes().remove(TENANT_ID_ATTR);
            exchange.getAttributes().remove(TENANT_SLUG_ATTR);
            exchange.getAttributes().remove(TENANT_SOURCE_ATTR);
        }
    }
}
