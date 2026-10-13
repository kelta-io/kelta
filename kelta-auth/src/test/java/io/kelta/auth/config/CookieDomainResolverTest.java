package io.kelta.auth.config;

import io.kelta.auth.config.CookieDomainResolver.SecureMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CookieDomainResolverTest {

    private final CookieDomainResolver resolver = new CookieDomainResolver();

    @Nested
    @DisplayName("Secure — auto (the scheme the browser used)")
    class Auto {

        @ParameterizedTest(name = "{0} over {1}, X-Forwarded-Proto={2} -> secure={3}")
        @CsvSource(value = {
                // Production: TLS ends at the ingress, the pod sees http plus the proxy's header.
                "auth.kelta.io,    http,  https,        true",
                "login.example.org, http, https,        true",
                // Several proxies appending: the first value is the client-facing hop.
                "auth.kelta.io,    http,  'https, http', true",
                "auth.kelta.io,    http,  HTTPS,        true",
                // Plain-http install on a LAN host name, with or without a proxy in front.
                "kelta.lan,        http,  NULL,         false",
                "kelta.lan,        http,  http,         false",
                "192.168.1.20,     http,  NULL,         false",
                // Local development.
                "auth.localhost,   http,  NULL,         false",
                "localhost,        http,  NULL,         false",
                "127.0.0.1,        http,  NULL,         false",
                // TLS straight to the service.
                "auth.kelta.io,    https, NULL,         true",
                "auth.localhost,   https, NULL,         true",
                // A proxy's word wins over the hop to it.
                "auth.kelta.io,    https, http,         false",
        }, nullValues = "NULL")
        void secureForRequest(String host, String scheme, String forwardedProto, boolean expected) {
            assertThat(resolver.secureForRequest(request(host, scheme, forwardedProto))).isEqualTo(expected);
        }

        @ParameterizedTest(name = "Forwarded: {0} -> secure={1}")
        @CsvSource(delimiter = '|', value = {
                "for=203.0.113.7;proto=https;host=auth.kelta.io | true",
                "for=203.0.113.7;proto=\"https\"                | true",
                "proto=http, proto=https                        | false",
                "for=203.0.113.7                                | false",
        })
        @DisplayName("RFC 7239 Forwarded is read when X-Forwarded-Proto is absent")
        void forwardedHeader(String forwarded, boolean expected) {
            MockHttpServletRequest request = request("auth.kelta.io", "http", null);
            request.addHeader("Forwarded", forwarded);

            assertThat(resolver.secureForRequest(request)).isEqualTo(expected);
        }

        @Test
        @DisplayName("without a request the cookie stays Secure")
        void noRequest() {
            assertThat(resolver.secureForRequest(null)).isTrue();
        }
    }

    @Test
    @DisplayName("always: Secure even over plain http")
    void always() {
        CookieDomainResolver always = new CookieDomainResolver(SecureMode.ALWAYS);

        assertThat(always.secureForRequest(request("auth.localhost", "http", null))).isTrue();
        assertThat(always.secureForRequest(request("kelta.lan", "http", "http"))).isTrue();
    }

    @Test
    @DisplayName("never: not Secure even over https")
    void never() {
        CookieDomainResolver never = new CookieDomainResolver(SecureMode.NEVER);

        assertThat(never.secureForRequest(request("auth.kelta.io", "http", "https"))).isFalse();
        assertThat(never.secureForRequest(request("auth.kelta.io", "https", null))).isFalse();
    }

    @Test
    @DisplayName("kelta.auth.session.cookie-secure selects the mode; blank is auto; anything else fails at startup")
    void fromProperties() {
        AuthProperties properties = new AuthProperties();
        assertThat(new CookieDomainResolver(properties).secureForRequest(request("kelta.lan", "http", null))).isFalse();

        properties.getSession().setCookieSecure("Always");
        assertThat(new CookieDomainResolver(properties).secureForRequest(request("kelta.lan", "http", null))).isTrue();

        assertThat(SecureMode.parse(" ")).isEqualTo(SecureMode.AUTO);
        assertThat(SecureMode.parse(null)).isEqualTo(SecureMode.AUTO);
        assertThatThrownBy(() -> SecureMode.parse("sometimes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cookie-secure");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(value = {
            "auth.kelta.io, .kelta.io",
            "kelta.io, .kelta.io",
            "auth.localhost, NULL",
            "localhost, NULL",
            "acme.com, NULL",
    }, nullValues = "NULL")
    @DisplayName("Domain is shared only across kelta.io subdomains")
    void forHost(String host, String expectedDomain) {
        assertThat(resolver.forHost(host)).isEqualTo(expectedDomain);
    }

    static MockHttpServletRequest request(String host, String scheme, String forwardedProto) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setServerName(host);
        request.setScheme(scheme);
        request.setSecure("https".equals(scheme));
        if (forwardedProto != null) {
            request.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        return request;
    }
}
