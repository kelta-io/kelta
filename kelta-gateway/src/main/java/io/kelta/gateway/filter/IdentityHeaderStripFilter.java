package io.kelta.gateway.filter;

import io.kelta.gateway.geo.ClientIpResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Strips client-supplied internal identity headers at the head of the filter chain.
 *
 * <p>The worker trusts {@code X-User-Email} / {@code X-User-Profile-Id} /
 * {@code X-User-Profile-Name} / {@code X-Cerbos-Scope} (set by
 * {@code RouteAuthorizationFilter.forwardWithHeaders}, order 0) and
 * {@code X-Forwarded-User} / {@code X-User-Id} / {@code X-Forwarded-Groups} /
 * {@code X-Forwarded-Roles} (set by {@code HeaderTransformationFilter}, order 50) for identity
 * and permission checks, {@code X-Geo-*} (set by {@code GeoEnrichmentFilter}, order -45)
 * for request-origin geolocation, and {@code X-Kelta-Client-Ip} (set by
 * {@code ClientIpForwardingFilter}, order -390) for the client address it records. Authenticated requests get every one of these overwritten downstream,
 * but requests that skip those set-points (public paths, unauthenticated bootstrap, any future
 * forwarding branch) would otherwise pass a client-forged value straight through to the worker.
 *
 * <p>Runs at order -400 — before the custom-domain (-310) / tenant (-300/-200) / auth (-100)
 * filters — so nothing downstream ever sees a client-supplied value for these headers.
 *
 * <p>{@code X-Tenant-ID} / {@code X-Tenant-Slug} are deliberately not in this list:
 * {@link TenantResolutionFilter} (-200) must read them as the tenant <em>claim</em> of a
 * slug-less request, and strips them from the forwarded request itself once read (PLT-355).
 */
@Component
public class IdentityHeaderStripFilter implements GlobalFilter, Ordered {

    static final List<String> INTERNAL_IDENTITY_HEADERS = List.of(
            "X-User-Email",
            "X-User-Profile-Id",
            "X-User-Profile-Name",
            "X-Cerbos-Scope",
            "X-Forwarded-User",
            "X-User-Id",
            "X-User-Type",
            "X-Forwarded-Groups",
            "X-Forwarded-Roles",
            "X-Geo-Country",
            "X-Geo-Region",
            "X-Geo-City",
            "X-Geo-Lat",
            "X-Geo-Lon",
            "X-Geo-Accuracy-Km",
            ClientIpResolver.CLIENT_IP_HEADER);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean present = INTERNAL_IDENTITY_HEADERS.stream()
                .anyMatch(h -> exchange.getRequest().getHeaders().getFirst(h) != null);
        if (!present) {
            return chain.filter(exchange);
        }
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(headers -> INTERNAL_IDENTITY_HEADERS.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(stripped).build());
    }

    @Override
    public int getOrder() {
        return -400; // Before custom-domain (-310), tenant (-300/-200), and auth (-100) filters
    }
}
