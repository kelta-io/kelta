package io.kelta.gateway.filter;

import io.kelta.gateway.geo.ClientIpResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stamps {@value ClientIpResolver#CLIENT_IP_HEADER} with the trust-aware resolved client
 * IP so downstream services (the worker's {@code login_history.source_ip} and
 * {@code LOGIN_SUCCESS} audit row) record the same address the gateway judged, instead
 * of re-parsing {@code X-Forwarded-For} behind the gateway where the left-most hop is
 * client-controlled.
 *
 * <p>Any client-supplied copy is removed by {@link IdentityHeaderStripFilter} (-400) and
 * this filter always {@code set}s (never appends), so the value downstream is the
 * gateway's own. Ordered at -390: right after the strip and before any filter that may
 * forward or short-circuit.
 */
@Component
public class ClientIpForwardingFilter implements GlobalFilter, Ordered {

    private final ClientIpResolver clientIpResolver;

    public ClientIpForwardingFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(ClientIpResolver.CLIENT_IP_HEADER);
                    if (clientIp != null) {
                        headers.set(ClientIpResolver.CLIENT_IP_HEADER, clientIp);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -390; // After IdentityHeaderStripFilter (-400), before custom-domain (-310)
    }
}
