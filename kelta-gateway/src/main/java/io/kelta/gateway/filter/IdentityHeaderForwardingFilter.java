package io.kelta.gateway.filter;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Keeps the gateway's own identity headers on the request it proxies to a backend.
 *
 * <p>Spring Cloud Gateway 5.0.2+ sanitises forwarded headers on every proxied request
 * (spring-cloud-gateway commit 9041b54, "Removes all forwarded headers if nothing is configured
 * to allow them through"): with no {@code spring.cloud.gateway.server.webflux.trusted-proxies}
 * its {@code RemoveXForwardedHeadersFilter} drops every request header whose name starts with
 * {@code X-Forwarded-}, and with one set its {@code XForwardedHeadersFilter} does the same for
 * an untrusted peer. That catches {@code X-Forwarded-User} / {@code X-Forwarded-Groups} /
 * {@code X-Forwarded-Roles}, which are not proxy headers but the identity
 * {@link HeaderTransformationFilter} sets from the authenticated principal and the worker reads
 * ({@code LoginTrackingFilter} provisioning and profile sync, {@code WhoAmIController}, log and
 * trace enrichment).
 *
 * <p>This filter runs after Spring Cloud Gateway's header filters and puts back every
 * {@link IdentityHeaderStripFilter#INTERNAL_IDENTITY_HEADERS} entry that the exchange's request
 * carries but the outgoing headers lost. That is safe because {@link IdentityHeaderStripFilter}
 * removes client-supplied copies of exactly these headers at the head of the chain: any value
 * still on the exchange at routing time was written by the gateway. Standard forwarding headers
 * ({@code X-Forwarded-For}, {@code -Proto}, {@code -Host}, ...) are not identity headers and stay
 * under Spring Cloud Gateway's trusted-proxy rules.
 */
@Component
public class IdentityHeaderForwardingFilter implements HttpHeadersFilter, Ordered {

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        HttpHeaders gatewaySet = exchange.getRequest().getHeaders();
        List<String> dropped = IdentityHeaderStripFilter.INTERNAL_IDENTITY_HEADERS.stream()
                .filter(name -> gatewaySet.containsHeader(name) && !input.containsHeader(name))
                .toList();
        if (dropped.isEmpty()) {
            return input;
        }
        HttpHeaders restored = HttpHeaders.copyOf(input);
        dropped.forEach(name -> restored.addAll(name, gatewaySet.get(name)));
        return restored;
    }

    @Override
    public int getOrder() {
        // After every Spring Cloud Gateway HttpHeadersFilter (Remove/XForwardedHeadersFilter run at 0).
        return Ordered.LOWEST_PRECEDENCE;
    }
}
