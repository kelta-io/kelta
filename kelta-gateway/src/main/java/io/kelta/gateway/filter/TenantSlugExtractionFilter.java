package io.kelta.gateway.filter;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the tenant slug from the first URL path segment and rewrites the
 * request path to strip it.
 * <p>
 * Incoming: {@code /{slug}/api/users/123} → rewritten to {@code /api/users/123}
 * with tenant attributes set on the exchange.
 * <p>
 * Implemented as a {@link WebFilter} (not a Gateway GlobalFilter) so that
 * path rewriting occurs <em>before</em> Spring Cloud Gateway's route matching.
 * This is essential because route predicates like {@code /internal/**} or
 * {@code /api/**} must see the bare (slug-stripped) path.
 * <p>
 * Platform paths (actuator, etc.) are exempted and pass through without a slug.
 * When {@code kelta.gateway.tenant-slug.require-prefix} is {@code false}
 * (migration mode), requests without a slug prefix also pass through.
 */
@Component
public class TenantSlugExtractionFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantSlugExtractionFilter.class);

    /** Tenant slug pattern matching the Tenant entity validation. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,61}[a-z0-9]$");

    /**
     * First-segment names that are slug-shaped but reserved as platform/API path
     * prefixes. The slug regex (3-63 lowercase chars) accepts short words like
     * {@code api}, so without this guard the filter would treat {@code /api/foo}
     * as an unresolved slug and strip {@code /api/}, breaking downstream route
     * matching against {@code /api/**}. Keep this aligned with platformPaths.
     */
    private static final Set<String> RESERVED_SEGMENTS = Set.of(
            "api", "actuator", "platform", "internal", "otel", "scim", "auth", "ws"
    );

    private final GatewayCacheManager cacheManager;
    private final GatewayMetrics metrics;
    private final boolean enabled;
    private final boolean requirePrefix;
    private final List<String> platformPaths;

    public TenantSlugExtractionFilter(
            GatewayCacheManager cacheManager,
            GatewayMetrics metrics,
            @Value("${kelta.gateway.tenant-slug.enabled:true}") boolean enabled,
            @Value("${kelta.gateway.tenant-slug.require-prefix:false}") boolean requirePrefix,
            @Value("${kelta.gateway.tenant-slug.platform-paths:/actuator,/platform}") List<String> platformPaths) {
        this.cacheManager = cacheManager;
        this.metrics = metrics;
        this.enabled = enabled;
        this.requirePrefix = requirePrefix;
        this.platformPaths = platformPaths;
    }

    @Override
    public int getOrder() {
        return -300;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        // Custom-domain resolution already attached a tenant; do not look for a
        // slug in the path. Paths arrive bare (e.g. /api/users) on custom domains.
        if (Boolean.TRUE.equals(exchange.getAttributes().get(CustomDomainFilter.CUSTOM_DOMAIN_RESOLVED))) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();

        // Platform endpoints bypass slug requirement
        if (isPlatformPath(path)) {
            return chain.filter(exchange);
        }

        // Extract the first path segment
        String firstSegment = extractFirstSegment(path);

        if (firstSegment == null) {
            // Root "/" or empty path
            if (requirePrefix) {
                return notFound(exchange, "A tenant identifier is required in the URL path.");
            }
            return chain.filter(exchange);
        }

        // Check if the first segment looks like a valid slug
        if (!SLUG_PATTERN.matcher(firstSegment).matches()) {
            // Not a slug-shaped segment; pass through in migration mode
            if (!requirePrefix) {
                return chain.filter(exchange);
            }
            return notFound(exchange, "Invalid tenant identifier: " + firstSegment);
        }

        // Slug-shaped but reserved as an API/platform path prefix (e.g. /api/**).
        // Pass through unchanged so downstream route matching works.
        if (RESERVED_SEGMENTS.contains(firstSegment)) {
            return chain.filter(exchange);
        }

        // Strip the slug segment from the path (must happen regardless of cache hit
        // so that downstream route matching sees bare paths like /api/**)
        String strippedPath = stripFirstSegment(path, firstSegment);
        if (strippedPath.isEmpty()) {
            strippedPath = "/";
        }

        // Resolve slug to tenant ID. Reactive: the lazy worker lookup behind a cache miss cannot
        // block this thread (it would throw and 404 a valid tenant — #1334).
        String slugSegment = firstSegment;
        String finalStrippedPath = strippedPath;
        return cacheManager.resolveTenantSlugReactive(slugSegment)
                .flatMap(tenantId -> continueWithTenant(exchange, chain, path, slugSegment,
                        finalStrippedPath, tenantId));
    }

    /**
     * Applies the resolution: sets tenant attributes when the slug is known, strips the segment
     * either way so downstream route matching sees a bare path.
     */
    private Mono<Void> continueWithTenant(ServerWebExchange exchange, WebFilterChain chain,
                                          String path, String firstSegment, String strippedPath,
                                          Optional<String> tenantId) {
        if (tenantId.isEmpty()) {
            metrics.recordTenantResolution("slug", "not_found");
            if (requirePrefix) {
                return notFound(exchange, "Tenant not found: " + firstSegment);
            }
            // Slug pattern matched but not in cache — strip the segment anyway so
            // route matching works, but don't set tenant attributes. The request runs with
            // no tenant: TenantResolutionFilter will not let a header fill it in.
            log.warn("Slug '{}' matches pattern but is not in cache; stripping path but no tenant context set", firstSegment);
        } else {
            metrics.recordTenantResolution("slug", "success");
            // Set tenant context on exchange attributes
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, tenantId.get());
            log.debug("Resolved tenant slug '{}' (id={}), rewriting path '{}' → '{}'",
                    firstSegment, tenantId.get(), path, strippedPath);
        }

        // Always set the slug attribute so HeaderTransformationFilter can propagate it
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, firstSegment);

        // Mutate the request with the stripped path
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .path(strippedPath)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        // Preserve original path for logging/error responses
        mutatedExchange.getAttributes().put("originalPath", path);

        return chain.filter(mutatedExchange);
    }

    /**
     * Extracts the first non-empty path segment.
     * For "/acme/api/users" returns "acme". For "/" returns null.
     */
    private String extractFirstSegment(String path) {
        if (path == null || path.length() <= 1) {
            return null;
        }
        // Skip leading slash
        String withoutLeading = path.substring(1);
        int slashIdx = withoutLeading.indexOf('/');
        return slashIdx > 0 ? withoutLeading.substring(0, slashIdx) : withoutLeading;
    }

    /**
     * Strips the first segment from the path.
     * "/acme/api/users" → "/api/users"
     * "/acme" → "/"
     */
    private String stripFirstSegment(String path, String segment) {
        // The segment starts after the leading "/"
        String prefix = "/" + segment;
        if (path.startsWith(prefix)) {
            return path.substring(prefix.length());
        }
        return path;
    }

    private boolean isPlatformPath(String path) {
        for (String prefix : platformPaths) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> notFound(ServerWebExchange exchange, String detail) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.NOT_FOUND)) {
            return Mono.empty();
        }

        String body = String.format(
                "{\"errors\":[{\"status\":\"404\",\"code\":\"TENANT_NOT_FOUND\",\"title\":\"Tenant Not Found\",\"detail\":\"%s\"}]}",
                detail.replace("\"", "\\\""));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
