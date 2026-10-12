package io.kelta.worker.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboundUrlGuard")
class OutboundUrlGuardTest {

    private final OutboundUrlGuard guard = new OutboundUrlGuard("");

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x", "http://127.9.9.9/x", "http://localhost/x", "http://0.0.0.0/x",
            "http://10.1.2.3/x", "http://172.16.0.1/x", "http://172.31.255.255/x", "http://192.168.1.1/x",
            "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/x", "http://198.18.0.1/x",
            "http://224.0.0.1/x", "http://255.255.255.255/x",
            "http://[::1]/x", "http://[::]/x", "http://[fe80::1]/x", "http://[fd00::1]/x",
            "http://[::ffff:10.0.0.1]/x", "http://[64:ff9b::a9fe:a9fe]/x", "http://[2002:c0a8:0101::1]/x",
            "http://[::7f00:1]/x"})
    @DisplayName("refuses non-public addresses, including ones embedded in IPv6")
    void refusesNonPublic(String url) {
        assertThatThrownBy(() -> guard.check(url))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class)
                .hasMessageContaining("non-public")
                .extracting(e -> ((OutboundUrlGuard.RejectedUrlException) e).reason())
                .isEqualTo(OutboundUrlGuard.Reason.NON_PUBLIC_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://8.8.8.8/.well-known/openid-configuration", "http://1.1.1.1:8443/x",
            "https://[2001:4860:4860::8888]/x", "https://172.32.0.1/x", "https://100.128.0.1/x"})
    @DisplayName("allows public addresses on http and https, any port")
    void allowsPublic(String url) {
        assertThat(guard.check(url).toString()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://8.8.8.8/x", "jar:http://8.8.8.8/x!/y", "gopher://8.8.8.8/"})
    @DisplayName("refuses schemes other than http and https")
    void refusesOtherSchemes(String url) {
        assertThatThrownBy(() -> guard.check(url))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class);
    }

    @Test
    @DisplayName("refuses URLs with credentials, without a host, or malformed")
    void refusesMalformed() {
        for (String url : new String[] {"https://user:pass@8.8.8.8/x", "https:///x", "not a url", "", null}) {
            assertThatThrownBy(() -> guard.check(url))
                    .as(String.valueOf(url))
                    .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class);
        }
    }

    @Test
    @DisplayName("refuses a host that does not resolve")
    void refusesUnresolvable() {
        assertThatThrownBy(() -> guard.check("https://does-not-exist.invalid/x"))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class)
                .hasMessageContaining("does not resolve")
                .extracting(e -> ((OutboundUrlGuard.RejectedUrlException) e).reason())
                .isEqualTo(OutboundUrlGuard.Reason.UNRESOLVABLE);
    }

    @Test
    @DisplayName("configured ranges (CIDR or bare address) are allowed; others stay refused")
    void allowedRanges() {
        OutboundUrlGuard configured = new OutboundUrlGuard(" 192.168.10.0/24 , 10.0.0.5, fd00:1::/32");

        assertThat(configured.check("https://192.168.10.20/x")).isNotNull();
        assertThat(configured.check("https://10.0.0.5/x")).isNotNull();
        assertThat(configured.check("https://[fd00:1::9]/x")).isNotNull();
        assertThatThrownBy(() -> configured.check("https://192.168.11.20/x"))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class);
        assertThatThrownBy(() -> configured.check("https://10.0.0.6/x"))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class);
        assertThatThrownBy(() -> configured.check("http://127.0.0.1/x"))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class);
    }

    @Test
    @DisplayName("a malformed allowed range fails at startup, not silently")
    void malformedRangeFailsFast() {
        assertThatThrownBy(() -> new OutboundUrlGuard("192.168.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OutboundUrlGuard.ALLOWED_CIDRS_PROPERTY);
        assertThatThrownBy(() -> new OutboundUrlGuard("internal.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isNonPublic classifies boundary addresses correctly")
    void boundaries() {
        assertThat(OutboundUrlGuard.isNonPublic(InetAddress.ofLiteral("172.15.255.255"))).isFalse();
        assertThat(OutboundUrlGuard.isNonPublic(InetAddress.ofLiteral("172.16.0.0"))).isTrue();
        assertThat(OutboundUrlGuard.isNonPublic(InetAddress.ofLiteral("100.63.255.255"))).isFalse();
        assertThat(OutboundUrlGuard.isNonPublic(InetAddress.ofLiteral("100.127.255.255"))).isTrue();
        assertThat(OutboundUrlGuard.isNonPublic(InetAddress.ofLiteral("223.255.255.255"))).isFalse();
        assertThat(OutboundUrlGuard.isNonPublic(InetAddress.ofLiteral("2606:4700::1111"))).isFalse();
    }
}
