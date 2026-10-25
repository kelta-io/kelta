package io.kelta.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InternalServiceAuthConfig")
class InternalServiceAuthConfigTest {

    @Test
    @DisplayName("token endpoint is on the auth-service URL when one is set (PLT-767)")
    void tokenUriUsesAuthServiceUrl() {
        assertThat(InternalServiceAuthConfig.tokenUri("http://auth.localhost:8081", "http://kelta-auth:8081/"))
                .isEqualTo("http://kelta-auth:8081/oauth2/token");
        assertThat(InternalServiceAuthConfig.tokenUri("http://auth.localhost:8081", " http://kelta-auth:8081 "))
                .isEqualTo("http://kelta-auth:8081/oauth2/token");
    }

    @Test
    @DisplayName("token endpoint is on the issuer when no auth-service URL is set")
    void tokenUriDefaultsToIssuer() {
        assertThat(InternalServiceAuthConfig.tokenUri("https://auth.kelta.io/", ""))
                .isEqualTo("https://auth.kelta.io/oauth2/token");
        assertThat(InternalServiceAuthConfig.tokenUri("https://auth.kelta.io", null))
                .isEqualTo("https://auth.kelta.io/oauth2/token");
    }
}
