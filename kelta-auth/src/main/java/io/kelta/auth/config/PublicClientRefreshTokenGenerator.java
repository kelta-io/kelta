package io.kelta.auth.config;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues refresh tokens to every client registered with the {@code refresh_token} grant —
 * public clients included.
 *
 * <p>Spring Authorization Server's {@code OAuth2RefreshTokenGenerator} returns {@code null}
 * for a public client ({@code ClientAuthenticationMethod.NONE}) on the
 * {@code authorization_code} grant. The platform UI is exactly that client, so it never
 * received a refresh token: every session ended when its access token expired, and the
 * refresh path {@link PublicClientRefreshTokenAuthenticationProvider} was built for had no
 * token to work with.
 *
 * <p>The registered grant types are the opt-in. A client gets a refresh token only when its
 * registration includes {@code refresh_token}: {@code kelta-platform} does; the
 * {@code kelta-cli} public client deliberately does not, and stays access-token-only.
 * Browser-held refresh tokens follow the OAuth 2.0 for Browser-Based Apps guidance —
 * rotated on every use ({@code reuseRefreshTokens(false)}) and bounded by the client's
 * refresh-token TTL.
 */
public final class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    // Same token shape as Spring's OAuth2RefreshTokenGenerator: 96 random bytes, base64url.
    private final StringKeyGenerator refreshTokenGenerator =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    private final Clock clock;

    public PublicClientRefreshTokenGenerator() {
        this(Clock.systemUTC());
    }

    PublicClientRefreshTokenGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        if (!context.getRegisteredClient().getAuthorizationGrantTypes()
                .contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            return null;
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(
                context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(refreshTokenGenerator.generateKey(), issuedAt, expiresAt);
    }
}
