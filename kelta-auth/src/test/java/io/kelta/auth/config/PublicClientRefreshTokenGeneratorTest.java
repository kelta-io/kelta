package io.kelta.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicClientRefreshTokenGenerator")
class PublicClientRefreshTokenGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final PublicClientRefreshTokenGenerator generator =
            new PublicClientRefreshTokenGenerator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("issues a refresh token with the client's TTL to a public client with the refresh grant")
    void issuesForPublicClientWithRefreshGrant() {
        RegisteredClient client = client(true);

        OAuth2RefreshToken token = generator.generate(context(client, OAuth2TokenType.REFRESH_TOKEN));

        assertThat(token).isNotNull();
        assertThat(token.getTokenValue()).hasSizeGreaterThanOrEqualTo(64);
        assertThat(token.getIssuedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("issues nothing to a client without the refresh_token grant")
    void skipsClientWithoutRefreshGrant() {
        assertThat(generator.generate(context(client(false), OAuth2TokenType.REFRESH_TOKEN))).isNull();
    }

    @Test
    @DisplayName("ignores requests for other token types")
    void ignoresOtherTokenTypes() {
        assertThat(generator.generate(context(client(true), OAuth2TokenType.ACCESS_TOKEN))).isNull();
    }

    @Test
    @DisplayName("generates a distinct value each time")
    void generatesDistinctValues() {
        RegisteredClient client = client(true);
        String first = generator.generate(context(client, OAuth2TokenType.REFRESH_TOKEN)).getTokenValue();
        String second = generator.generate(context(client, OAuth2TokenType.REFRESH_TOKEN)).getTokenValue();
        assertThat(first).isNotEqualTo(second);
    }

    private static RegisteredClient client(boolean refreshGrant) {
        RegisteredClient.Builder builder = RegisteredClient.withId("id")
                .clientId("kelta-platform")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.test/auth/callback")
                .tokenSettings(TokenSettings.builder().refreshTokenTimeToLive(Duration.ofDays(30)).build());
        if (refreshGrant) {
            builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }
        return builder.build();
    }

    private static DefaultOAuth2TokenContext context(RegisteredClient client, OAuth2TokenType type) {
        return DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .tokenType(type)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .build();
    }
}
