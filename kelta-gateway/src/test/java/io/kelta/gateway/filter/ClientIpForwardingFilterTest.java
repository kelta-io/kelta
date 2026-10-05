package io.kelta.gateway.filter;

import io.kelta.gateway.geo.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientIpForwardingFilter Tests")
class ClientIpForwardingFilterTest {

    private static ServerWebExchange forward(ClientIpForwardingFilter filter, MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        StepVerifier.create(filter.filter(MockServerWebExchange.from(request), chain)).verifyComplete();
        return captured.get();
    }

    @Test
    @DisplayName("stamps the resolved client IP, overwriting a client-supplied value")
    void stampsResolvedIpOverwritingForgedValue() {
        ClientIpForwardingFilter filter = new ClientIpForwardingFilter(
                new ClientIpResolver(true, List.of("10.42.0.0/16")));
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/x")
                .header(ClientIpResolver.CLIENT_IP_HEADER, "198.51.100.1")
                .header("X-Forwarded-For", "198.51.100.1, 203.0.113.7")
                .remoteAddress(new InetSocketAddress("10.42.0.3", 4711))
                .build();

        ServerWebExchange forwarded = forward(filter, request);

        assertThat(forwarded.getRequest().getHeaders().get(ClientIpResolver.CLIENT_IP_HEADER))
                .containsExactly("203.0.113.7");
    }

    @Test
    @DisplayName("an untrusted peer is recorded as itself")
    void untrustedPeerRecordedAsItself() {
        ClientIpForwardingFilter filter = new ClientIpForwardingFilter(
                new ClientIpResolver(true, List.of("10.42.0.0/16")));
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/x")
                .header("X-Forwarded-For", "198.51.100.1")
                .remoteAddress(new InetSocketAddress("192.0.2.4", 4711))
                .build();

        assertThat(forward(filter, request).getRequest().getHeaders()
                .getFirst(ClientIpResolver.CLIENT_IP_HEADER)).isEqualTo("192.0.2.4");
    }

    @Test
    @DisplayName("is stripped inbound by IdentityHeaderStripFilter and runs right after it")
    void strippedInboundAndOrdered() {
        assertThat(IdentityHeaderStripFilter.INTERNAL_IDENTITY_HEADERS)
                .contains(ClientIpResolver.CLIENT_IP_HEADER);
        ClientIpForwardingFilter filter = new ClientIpForwardingFilter(new ClientIpResolver(true));
        assertThat(filter.getOrder()).isGreaterThan(new IdentityHeaderStripFilter().getOrder());
    }
}
