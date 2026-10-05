package io.kelta.gateway.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientIpResolver Tests")
class ClientIpResolverTest {

    @Test
    @DisplayName("takes the leftmost X-Forwarded-For hop when trusted")
    void takesLeftmostForwardedHop() {
        ClientIpResolver resolver = new ClientIpResolver(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/x")
                .header("X-Forwarded-For", "203.0.113.50, 10.0.0.1")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 4711))
                .build());

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.50");
    }

    @Test
    @DisplayName("ignores X-Forwarded-For when trust is disabled")
    void ignoresForwardedWhenUntrusted() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/x")
                .header("X-Forwarded-For", "203.0.113.50")
                .remoteAddress(new InetSocketAddress("192.168.1.7", 4711))
                .build());

        assertThat(resolver.resolve(exchange)).isEqualTo("192.168.1.7");
    }

    @Test
    @DisplayName("falls back to the socket address when no X-Forwarded-For")
    void fallsBackToSocketAddress() {
        ClientIpResolver resolver = new ClientIpResolver(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/x")
                .remoteAddress(new InetSocketAddress("198.51.100.9", 4711))
                .build());

        assertThat(resolver.resolve(exchange)).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("normalizes brackets and IPv6 scope suffixes")
    void normalizes() {
        assertThat(ClientIpResolver.normalizeIp(" [::1] ")).isEqualTo("::1");
        assertThat(ClientIpResolver.normalizeIp("fe80::1%eth0")).isEqualTo("fe80::1");
        assertThat(ClientIpResolver.normalizeIp("  ")).isNull();
        assertThat(ClientIpResolver.normalizeIp(null)).isNull();
    }

    // ── Trusted-proxy mode (kelta.security.trusted-proxies) ───────────────

    private static ClientIpResolver trusting(String... cidrs) {
        return new ClientIpResolver(true, List.of(cidrs));
    }

    private static MockServerWebExchange request(String peer, String xff, String realIp) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/x");
        if (xff != null) {
            builder.header("X-Forwarded-For", xff);
        }
        if (realIp != null) {
            builder.header("X-Real-IP", realIp);
        }
        return MockServerWebExchange.from(builder.remoteAddress(new InetSocketAddress(peer, 4711)).build());
    }

    @Test
    @DisplayName("trusted peer: the forwarded client is returned")
    void trustedPeerForwardedClient() {
        ClientIpResolver resolver = trusting("10.42.0.0/16");

        assertThat(resolver.isTrustedProxyMode()).isTrue();
        assertThat(resolver.resolve(request("10.42.3.4", "203.0.113.7", null))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("untrusted peer: a forged X-Forwarded-For / X-Real-IP is ignored")
    void untrustedPeerIgnoresForgedHeaders() {
        ClientIpResolver resolver = trusting("10.42.0.0/16");

        assertThat(resolver.resolve(request("192.0.2.10", "198.51.100.1", "198.51.100.2")))
                .isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("multi-hop: the right-most untrusted hop wins, not the client-supplied left-most")
    void multiHopRightMostUntrusted() {
        ClientIpResolver resolver = trusting("10.1.0.0/16");

        assertThat(resolver.resolve(request("10.1.0.9", "198.51.100.1, 203.0.113.7, 10.1.0.5", null)))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("every hop trusted: the left-most hop is returned")
    void allHopsTrustedReturnsLeftMost() {
        ClientIpResolver resolver = trusting("10.1.0.0/16");

        assertThat(resolver.resolve(request("10.1.0.9", "10.1.0.3, 10.1.0.5", null)))
                .isEqualTo("10.1.0.3");
    }

    @Test
    @DisplayName("a malformed hop stops the walk and is never resolved via DNS")
    void malformedHopStopsWalk() {
        ClientIpResolver resolver = trusting("10.1.0.0/16");

        // "localhost" would resolve to 127.0.0.1 via getByName — it must not be treated as an address.
        assertThat(resolver.resolve(request("10.1.0.9", "203.0.113.7, localhost, 10.1.0.5", null)))
                .isEqualTo("10.1.0.5");
        assertThat(resolver.resolve(request("10.1.0.9", "203.0.113.7, not-an-ip!!", null)))
                .isEqualTo("10.1.0.9");
    }

    @Test
    @DisplayName("trusted peer without X-Forwarded-For: X-Real-IP, then the peer")
    void trustedPeerFallsBackToRealIpThenPeer() {
        ClientIpResolver resolver = trusting("10.1.0.0/16");

        assertThat(resolver.resolve(request("10.1.0.9", null, "203.0.113.8"))).isEqualTo("203.0.113.8");
        assertThat(resolver.resolve(request("10.1.0.9", null, "evil.example"))).isEqualTo("10.1.0.9");
        assertThat(resolver.resolve(request("10.1.0.9", null, null))).isEqualTo("10.1.0.9");
    }

    @Test
    @DisplayName("bare addresses and IPv6 ranges are accepted; invalid entries are skipped")
    void parsesBareAddressesAndIpv6() {
        ClientIpResolver resolver = trusting("garbage", "10.1.0.9", "2001:db8::/32");

        assertThat(resolver.resolve(request("10.1.0.9", "203.0.113.7", null))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("2001:db8::1", "2001:db8::5, 2001:db9::7", null)))
                .isEqualTo("2001:db9::7");
        assertThat(resolver.resolve(request("10.1.0.10", "203.0.113.7", null))).isEqualTo("10.1.0.10");
    }

    @Test
    @DisplayName("allowlist candidates: one resolved IP in trusted-proxy mode, every hop in legacy mode")
    void allowlistCandidates() {
        MockServerWebExchange exchange = request("10.1.0.9", "198.51.100.1, 203.0.113.7", "192.0.2.5");

        assertThat(trusting("10.1.0.0/16").allowlistCandidates(exchange)).containsExactly("203.0.113.7");
        assertThat(new ClientIpResolver(true).allowlistCandidates(exchange))
                .containsExactly("10.1.0.9", "198.51.100.1", "203.0.113.7", "192.0.2.5");
        assertThat(new ClientIpResolver(false).allowlistCandidates(exchange)).containsExactly("10.1.0.9");
    }

    @Test
    @DisplayName("empty trusted-proxy list keeps legacy left-most behaviour")
    void emptyListIsLegacy() {
        ClientIpResolver resolver = new ClientIpResolver(true, List.of());

        assertThat(resolver.isTrustedProxyMode()).isFalse();
        assertThat(resolver.resolve(request("10.1.0.9", "198.51.100.1, 203.0.113.7", null)))
                .isEqualTo("198.51.100.1");
    }

    @Test
    @DisplayName("no gateway class other than ClientIpResolver reads X-Forwarded-For / X-Real-IP")
    void onlyResolverParsesForwardingHeaders() throws IOException {
        Path sources = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("ClientIpResolver.java"))
                    .filter(p -> {
                        try {
                            String src = Files.readString(p);
                            return src.contains("\"X-Forwarded-For\"") || src.contains("\"X-Real-IP\"");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }
}
