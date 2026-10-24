package io.kelta.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InternalServiceAuthConfig")
class InternalServiceAuthConfigTest {

    private final InternalServiceAuthConfig config = new InternalServiceAuthConfig();

    @Test
    @DisplayName("token endpoint is on the auth-service URL when one is set (PLT-767)")
    void tokenUriUsesAuthServiceUrl() {
        ReactiveClientRegistrationRepository clients = config.internalClientRegistrations(
                "gateway-internal", "test-client-secret", "http://auth.localhost:8081", "http://kelta-auth:8081/");

        assertThat(tokenUri(clients)).isEqualTo("http://kelta-auth:8081/oauth2/token");
    }

    @Test
    @DisplayName("token endpoint is on the issuer when no auth-service URL is set")
    void tokenUriDefaultsToIssuer() {
        ReactiveClientRegistrationRepository clients = config.internalClientRegistrations(
                "gateway-internal", "test-client-secret", "https://auth.kelta.io/", "");

        assertThat(tokenUri(clients)).isEqualTo("https://auth.kelta.io/oauth2/token");
    }

    private static String tokenUri(ReactiveClientRegistrationRepository clients) {
        ClientRegistration registration = clients
                .findByRegistrationId(InternalServiceAuthConfig.CLIENT_REGISTRATION_ID).block();
        assertThat(registration).isNotNull();
        return registration.getProviderDetails().getTokenUri();
    }
}
