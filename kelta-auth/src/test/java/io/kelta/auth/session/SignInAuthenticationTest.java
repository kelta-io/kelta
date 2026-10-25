package io.kelta.auth.session;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.kelta.auth.TestFixtures;
import io.kelta.auth.model.KeltaUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SignInAuthentication (PLT-767)")
class SignInAuthenticationTest {

    private static List<FactorGrantedAuthority> factors(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .filter(FactorGrantedAuthority.class::isInstance)
                .map(FactorGrantedAuthority.class::cast)
                .toList();
    }

    @Test
    @DisplayName("without an upstream authentication it adds the path's factor, issued now, to the user's authorities")
    void addsThePathFactor() {
        KeltaUserDetails user = TestFixtures.userDetails();
        Instant before = Instant.now();

        UsernamePasswordAuthenticationToken token =
                SignInAuthentication.authenticated(user, null, FactorGrantedAuthority.OTT_AUTHORITY);

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getPrincipal()).isSameAs(user);
        assertThat(token.getAuthorities()).containsAll(user.getAuthorities());
        assertThat(factors(token)).singleElement().satisfies(factor -> {
            assertThat(factor.getAuthority()).isEqualTo(FactorGrantedAuthority.OTT_AUTHORITY);
            assertThat(factor.getIssuedAt()).isBetween(before, Instant.now());
        });
    }

    @Test
    @DisplayName("keeps the upstream login's factors and their issued-at instead of the fallback")
    void carriesUpstreamFactors() {
        Instant signedInAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        FactorGrantedAuthority upstreamFactor = FactorGrantedAuthority
                .withAuthority(FactorGrantedAuthority.SAML_RESPONSE_AUTHORITY).issuedAt(signedInAt).build();
        Authentication upstream = new TestingAuthenticationToken("idp-user", null,
                List.of(upstreamFactor, new SimpleGrantedAuthority("ROLE_IDP_GROUP")));

        UsernamePasswordAuthenticationToken token = SignInAuthentication.authenticated(
                TestFixtures.userDetails(), upstream, FactorGrantedAuthority.SAML_RESPONSE_AUTHORITY);

        assertThat(factors(token)).containsExactly(upstreamFactor);
        assertThat(factors(token).get(0).getIssuedAt()).isEqualTo(signedInAt);
        // Only factors cross over, not the upstream identity's own authorities.
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .doesNotContain("ROLE_IDP_GROUP");
    }

    @Test
    @DisplayName("an upstream authentication without factors gets the path's factor")
    void upstreamWithoutFactorsGetsTheFallback() {
        Authentication upstream = new TestingAuthenticationToken("oidc-user", null, List.of());

        UsernamePasswordAuthenticationToken token = SignInAuthentication.authenticated(
                TestFixtures.userDetails(), upstream, FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);

        assertThat(factors(token)).extracting(FactorGrantedAuthority::getAuthority)
                .containsExactly(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
    }

    /**
     * The authorization server's ID token for an authorization-code grant, generated exactly as
     * {@code /oauth2/token} does when the session registry finds the signed-in session. Spring
     * Security 7.0.5+ (spring-security#18282) reads {@code auth_time} from the principal's
     * factors; the portal magic link's token had none, and the E2E portal sign-in got
     * "Token exchange failed: Internal Server Error".
     */
    @Nested
    @DisplayName("ID token issuance")
    class IdTokenIssuance {

        @Test
        @DisplayName("an ID token is issued for a magic-link session, with auth_time from its factor")
        void idTokenForSignInAuthentication() throws Exception {
            UsernamePasswordAuthenticationToken principal = SignInAuthentication.authenticated(
                    TestFixtures.userDetails(), null, FactorGrantedAuthority.OTT_AUTHORITY);
            Instant factorIssuedAt = factors(principal).get(0).getIssuedAt();

            Jwt idToken = generateIdToken(principal);

            assertThat(idToken.getClaimAsInstant(IdTokenClaimNames.AUTH_TIME))
                    .isEqualTo(factorIssuedAt.truncatedTo(ChronoUnit.MILLIS));
            assertThat(idToken.getClaimAsString("sid")).isEqualTo("session-1");
        }

        @Test
        @DisplayName("a token built from the user's authorities alone is refused (the pre-fix 500)")
        void idTokenRefusedWithoutFactor() {
            KeltaUserDetails user = TestFixtures.userDetails();
            UsernamePasswordAuthenticationToken principal =
                    UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());

            assertThatThrownBy(() -> generateIdToken(principal))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("authenticationTime cannot be null");
        }

        private Jwt generateIdToken(Authentication principal) throws Exception {
            JwtGenerator generator = new JwtGenerator(new NimbusJwtEncoder(new ImmutableJWKSet<>(
                    new JWKSet(new RSAKeyGenerator(2048).keyID("k1").generate()))));
            RegisteredClient client = RegisteredClient.withId("client-1")
                    .clientId("kelta-ui")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://app.example.test/acme/auth/callback")
                    .scope(OidcScopes.OPENID)
                    .build();
            OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri("https://auth.example.test/oauth2/authorize")
                    .clientId(client.getClientId())
                    .redirectUri("https://app.example.test/acme/auth/callback")
                    .scopes(Set.of(OidcScopes.OPENID))
                    .build();
            OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                    .principalName(principal.getName())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizedScopes(Set.of(OidcScopes.OPENID))
                    .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest)
                    .build();

            Jwt idToken = generator.generate(DefaultOAuth2TokenContext.builder()
                    .registeredClient(client)
                    .principal(principal)
                    .authorization(authorization)
                    .authorizedScopes(Set.of(OidcScopes.OPENID))
                    .tokenType(new OAuth2TokenType(OidcParameterNames.ID_TOKEN))
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .put(SessionInformation.class, new SessionInformation(principal.getPrincipal(), "session-1", new Date()))
                    .build());
            assertThat(idToken).isNotNull();
            return idToken;
        }
    }
}
