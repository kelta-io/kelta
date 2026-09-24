package io.kelta.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CookieDomainResolverTest {

    private final CookieDomainResolver resolver = new CookieDomainResolver();

    @ParameterizedTest(name = "{0} -> secure={1}")
    @CsvSource({
            "localhost, false",
            "LOCALHOST, false",
            "auth.localhost, false",
            "127.0.0.1, false",
            "auth.kelta.io, true",
            "acme.com, true",
            "localhost.evil.com, true",
            "notlocalhost, true",
    })
    @DisplayName("Secure is dropped only for loopback hosts, including *.localhost")
    void secureForRequest(String host, boolean expectedSecure) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServerName()).thenReturn(host);

        assertThat(resolver.secureForRequest(request)).isEqualTo(expectedSecure);
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
}
