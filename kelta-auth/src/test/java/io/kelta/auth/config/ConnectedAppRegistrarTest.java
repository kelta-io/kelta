package io.kelta.auth.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConnectedAppRegistrar")
class ConnectedAppRegistrarTest {

    private RegisteredClientRepository clientRepository;
    private ConnectedAppRegistrar registrar;

    @BeforeEach
    void setUp() {
        clientRepository = Mockito.mock(RegisteredClientRepository.class);
        PasswordEncoder passwordEncoder = Mockito.mock(PasswordEncoder.class);
        AuthProperties properties = new AuthProperties();
        registrar = new ConnectedAppRegistrar(clientRepository, passwordEncoder, properties);
    }

    @Test
    @DisplayName("registers kelta-cli as a PKCE public client with no refresh grant")
    void shouldRegisterCliClient() {
        registrar.run(null);

        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        Mockito.verify(clientRepository, Mockito.atLeastOnce()).save(captor.capture());
        List<RegisteredClient> saved = captor.getAllValues();

        RegisteredClient cli = saved.stream()
                .filter(c -> "kelta-cli".equals(c.getClientId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("kelta-cli client was not registered"));

        assertThat(cli.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(cli.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        // No refresh token by design: the access token only lives long enough to mint a PAT.
        assertThat(cli.getAuthorizationGrantTypes())
                .doesNotContain(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(cli.getRedirectUris()).containsExactly("http://127.0.0.1/tenant/auth/callback");
        assertThat(cli.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(cli.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(cli.getClientSecret()).isNull();
    }

    @Test
    @DisplayName("is idempotent — an existing kelta-cli registration is left untouched")
    void shouldSkipWhenCliClientExists() {
        RegisteredClient existing = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("kelta-cli")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1/tenant/auth/callback")
                .build();
        Mockito.when(clientRepository.findByClientId("kelta-cli")).thenReturn(existing);

        registrar.run(null);

        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        Mockito.verify(clientRepository, Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .noneMatch(c -> "kelta-cli".equals(c.getClientId()));
    }

    @Test
    @DisplayName("kelta-platform: a row with stale token lifetimes is re-saved under the same id")
    void shouldReconcileStalePlatformTokenSettings() {
        RegisteredClient existing = platformClient(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofHours(1))
                .refreshTokenTimeToLive(Duration.ofHours(8))
                .reuseRefreshTokens(false)
                .build());
        Mockito.when(clientRepository.findByClientId("kelta-platform")).thenReturn(existing);

        registrar.run(null);

        RegisteredClient saved = savedClient("kelta-platform");
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(saved.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(ConnectedAppRegistrar.PLATFORM_ACCESS_TOKEN_TTL);
        assertThat(saved.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(ConnectedAppRegistrar.PLATFORM_REFRESH_TOKEN_TTL);
        assertThat(saved.getTokenSettings().isReuseRefreshTokens()).isFalse();
    }

    @Test
    @DisplayName("kelta-platform: an up-to-date row is left untouched")
    void shouldSkipCurrentPlatformClient() {
        RegisteredClient existing = platformClient(ConnectedAppRegistrar.platformTokenSettings());
        Mockito.when(clientRepository.findByClientId("kelta-platform")).thenReturn(existing);

        registrar.run(null);

        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        Mockito.verify(clientRepository, Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).noneMatch(c -> "kelta-platform".equals(c.getClientId()));
    }

    private static RegisteredClient platformClient(TokenSettings tokenSettings) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("kelta-platform")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:5173/auth/callback")
                .tokenSettings(tokenSettings)
                .build();
    }

    private RegisteredClient savedClient(String clientId) {
        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        Mockito.verify(clientRepository, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(c -> clientId.equals(c.getClientId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(clientId + " was not saved"));
    }
}
