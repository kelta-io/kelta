package io.kelta.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.RemoveXForwardedHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdentityHeaderForwardingFilter (PLT-767)")
class IdentityHeaderForwardingFilterTest {

    private final IdentityHeaderForwardingFilter filter = new IdentityHeaderForwardingFilter();

    /** A proxied request after HeaderTransformationFilter: identity headers plus proxy headers. */
    private static MockServerWebExchange routedExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/test/1")
                .header("X-Forwarded-User", "test-user")
                .header("X-Forwarded-Groups", "USER,ADMIN")
                .header("X-Forwarded-Roles", "USER,ADMIN")
                .header("X-User-Id", "user-1")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Host", "attacker.example")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token"));
    }

    @Test
    @DisplayName("identity headers survive Spring Cloud Gateway's X-Forwarded-* removal; proxy headers do not")
    void identityHeadersSurviveRemoveXForwardedHeadersFilter() {
        MockServerWebExchange exchange = routedExchange();
        // The chain NettyRoutingFilter runs when no trusted proxies are configured, in bean order.
        List<HttpHeadersFilter> filters = List.of(new RemoveXForwardedHeadersFilter(), filter);

        HttpHeaders outgoing = HttpHeadersFilter.filterRequest(filters, exchange);

        assertThat(outgoing.get("X-Forwarded-User")).containsExactly("test-user");
        assertThat(outgoing.get("X-Forwarded-Groups")).containsExactly("USER,ADMIN");
        assertThat(outgoing.get("X-Forwarded-Roles")).containsExactly("USER,ADMIN");
        assertThat(outgoing.get("X-User-Id")).containsExactly("user-1");
        assertThat(outgoing.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        assertThat(outgoing.containsHeader("X-Forwarded-For")).isFalse();
        assertThat(outgoing.containsHeader("X-Forwarded-Host")).isFalse();
    }

    @Test
    @DisplayName("without the gateway filter the identity headers are lost (the regression this guards)")
    void removeXForwardedHeadersFilterAloneDropsIdentityHeaders() {
        HttpHeaders outgoing = HttpHeadersFilter.filterRequest(
                List.of(new RemoveXForwardedHeadersFilter()), routedExchange());

        assertThat(outgoing.containsHeader("X-Forwarded-User")).isFalse();
        assertThat(outgoing.containsHeader("X-Forwarded-Groups")).isFalse();
    }

    @Test
    @DisplayName("returns the input untouched when no identity header was dropped")
    void passesThroughWhenNothingDropped() {
        MockServerWebExchange exchange = routedExchange();
        HttpHeaders input = HttpHeaders.copyOf(exchange.getRequest().getHeaders());

        assertThat(filter.filter(input, exchange)).isSameAs(input);
    }

    @Test
    @DisplayName("restores nothing the exchange does not carry")
    void restoresOnlyWhatTheGatewaySet() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test/1")
                .header("X-Forwarded-For", "203.0.113.7"));
        HttpHeaders input = new HttpHeaders();

        HttpHeaders outgoing = filter.filter(input, exchange);

        assertThat(outgoing.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("runs after Spring Cloud Gateway's header filters")
    void ordersLast() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(filter.getOrder()).isGreaterThan(new RemoveXForwardedHeadersFilter().getOrder());
    }
}
