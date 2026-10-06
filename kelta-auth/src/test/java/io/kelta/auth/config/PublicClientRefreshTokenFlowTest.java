package io.kelta.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives Spring Authorization Server's real authorization-code and refresh-token providers
 * with the token generator kelta-auth configures, for the platform UI's public client.
 *
 * <p>Guards the bug that kept the UI logging users out: the default generator never issues
 * a refresh token to a public client, so the SPA's session ended when its access token did.
 */
@DisplayName("Public-client refresh token — code exchange and rotation")
class PublicClientRefreshTokenFlowTest {

    private static final String REDIRECT_URI = "https://app.example.test/auth/callback";

    private RegisteredClient platformClient;
    private OAuth2AuthorizationService authorizationService;
    private OAuth2TokenGenerator<OAuth2Token> tokenGenerator;

    @BeforeEach
    void setUp() throws Exception {
        platformClient = RegisteredClient.withId("platform-id")
                .clientId("kelta-platform")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(ConnectedAppRegistrar.platformTokenSettings())
                .build();
        authorizationService = new InMemoryOAuth2AuthorizationService();

        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("test").generate();
        tokenGenerator = new AuthorizationServerConfig().tokenGenerator(
                new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))), context -> { });

        AuthorizationServerSettings settings = AuthorizationServerSettings.builder().build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return "https://auth.example.test";
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return settings;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    @DisplayName("the code exchange issues a refresh token to the public platform client")
    void codeExchangeIssuesRefreshToken() {
        OAuth2AccessTokenAuthenticationToken result = exchangeCode();

        assertThat(result.getRefreshToken()).isNotNull();
        assertThat(result.getRefreshToken().getExpiresAt())
                .isCloseTo(Instant.now().plus(ConnectedAppRegistrar.PLATFORM_REFRESH_TOKEN_TTL),
                        org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("each refresh rotates the token, and the spent one is rejected with invalid_grant")
    void refreshRotatesAndRejectsSpentToken() {
        OAuth2RefreshToken first = exchangeCode().getRefreshToken();

        OAuth2AccessTokenAuthenticationToken second = refresh(first.getTokenValue());
        assertThat(second.getAccessToken()).isNotNull();
        assertThat(second.getRefreshToken()).isNotNull();
        assertThat(second.getRefreshToken().getTokenValue()).isNotEqualTo(first.getTokenValue());

        // The session keeps going on the rotated token…
        OAuth2AccessTokenAuthenticationToken third = refresh(second.getRefreshToken().getTokenValue());
        assertThat(third.getRefreshToken()).isNotNull();

        // …and a replayed, already-rotated token is dead.
        assertThatThrownBy(() -> refresh(first.getTokenValue()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
                .isEqualTo(OAuth2ErrorCodes.INVALID_GRANT);
    }

    @Test
    @DisplayName("a public client registered without the refresh_token grant still gets none")
    void publicClientWithoutRefreshGrantGetsNoRefreshToken() {
        platformClient = RegisteredClient.from(platformClient)
                .authorizationGrantTypes(types -> types.remove(AuthorizationGrantType.REFRESH_TOKEN))
                .build();

        assertThat(exchangeCode().getRefreshToken()).isNull();
    }

    private OAuth2AccessTokenAuthenticationToken exchangeCode() {
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://auth.example.test/oauth2/authorize")
                .clientId(platformClient.getClientId())
                .redirectUri(REDIRECT_URI)
                .scopes(Set.of("profile"))
                .build();
        Instant now = Instant.now();
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode("code-value", now, now.plusSeconds(300));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(platformClient)
                .principalName("user@example.test")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .token(code)
                .attribute(Principal.class.getName(),
                        UsernamePasswordAuthenticationToken.authenticated("user@example.test", null, java.util.List.of()))
                .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest)
                .build();
        authorizationService.save(authorization);

        OAuth2AuthorizationCodeAuthenticationProvider provider =
                new OAuth2AuthorizationCodeAuthenticationProvider(authorizationService, tokenGenerator);
        return (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                new OAuth2AuthorizationCodeAuthenticationToken(
                        "code-value", publicClientPrincipal(), REDIRECT_URI, Map.of()));
    }

    private OAuth2AccessTokenAuthenticationToken refresh(String refreshToken) {
        OAuth2RefreshTokenAuthenticationProvider provider =
                new OAuth2RefreshTokenAuthenticationProvider(authorizationService, tokenGenerator);
        return (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                new OAuth2RefreshTokenAuthenticationToken(refreshToken, publicClientPrincipal(), Set.of(), Map.of()));
    }

    private OAuth2ClientAuthenticationToken publicClientPrincipal() {
        return new OAuth2ClientAuthenticationToken(platformClient, ClientAuthenticationMethod.NONE, null);
    }
}
