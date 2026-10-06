package io.kelta.auth.config;

import io.kelta.auth.service.AuthDomainResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlatformRedirectUriValidator Tests")
class PlatformRedirectUriValidatorTest {

    private PlatformRedirectUriValidator validator;
    private AuthDomainResolver domainResolver;

    @BeforeEach
    void setUp() {
        domainResolver = Mockito.mock(AuthDomainResolver.class);
        Mockito.lenient().when(domainResolver.resolveTenantSlug(Mockito.anyString()))
                .thenReturn(Optional.empty());
        validator = new PlatformRedirectUriValidator(domainResolver);
    }

    @Test
    @DisplayName("accepts verified custom-domain callback for kelta-platform")
    void shouldAllowVerifiedCustomDomainCallback() {
        Mockito.when(domainResolver.resolveTenantSlug("acme.com"))
                .thenReturn(Optional.of("acme"));
        var context = buildContext("kelta-platform",
                "https://acme.com/auth/callback",
                "http://localhost:5173/auth/callback");
        assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rejected redirect_uri is not carried on the rejection (RFC 6749 §4.1.2.1)")
    void rejectionCarriesNoRedirectUri() {
        // The authorization endpoint redirects the error to the exception's redirect URI when
        // it has one; an invalid redirection URI must not be redirected to.
        for (String clientId : new String[] {"kelta-platform", "couchpicks-web", "kelta-cli"}) {
            var context = buildContext(clientId,
                    "https://example.com/cb",
                    "https://www.couchpicks.tv/couchpicks/auth/callback");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOfSatisfying(OAuth2AuthorizationCodeRequestAuthenticationException.class, e -> {
                        assertThat(e.getError().getDescription()).isEqualTo("invalid_redirect_uri");
                        assertThat(e.getAuthorizationCodeRequestAuthentication()).isNotNull();
                        assertThat(e.getAuthorizationCodeRequestAuthentication().getRedirectUri())
                                .as(clientId).isNull();
                        assertThat(e.getAuthorizationCodeRequestAuthentication().getClientId())
                                .isEqualTo(clientId);
                    });
        }
    }

    @Test
    @DisplayName("rejects unknown custom-domain callback")
    void shouldRejectUnknownCustomDomain() {
        var context = buildContext("kelta-platform",
                "https://attacker.com/auth/callback",
                "http://localhost:5173/auth/callback");
        assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
    }

    @Nested
    @DisplayName("Exact match")
    class ExactMatch {
        @Test
        void shouldAllowExactMatch() {
            var context = buildContext("kelta-platform",
                    "http://localhost:5173/auth/callback",
                    "http://localhost:5173/auth/callback");
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Platform client tenant-scoped URIs")
    class PlatformClient {
        @Test
        void shouldAllowTenantScopedAuthCallbackWithSameOrigin() {
            var context = buildContext("kelta-platform",
                    "http://localhost:5173/acme-corp/auth/callback",
                    "http://localhost:5173/auth/callback");
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        void shouldRejectDifferentOrigin() {
            var context = buildContext("kelta-platform",
                    "http://evil.com/acme-corp/auth/callback",
                    "http://localhost:5173/auth/callback");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        void shouldRejectPathNotEndingWithAuthCallback() {
            var context = buildContext("kelta-platform",
                    "http://localhost:5173/acme-corp/some-other-path",
                    "http://localhost:5173/auth/callback");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("Non-platform client")
    class NonPlatformClient {
        @Test
        void shouldRejectNonExactMatchForOtherClients() {
            var context = buildContext("other-client",
                    "http://localhost:5173/different/path",
                    "http://localhost:5173/auth/callback");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("CLI client RFC 8252 loopback redirect")
    class CliLoopback {

        private static final String CLI_REGISTERED_URI = "http://127.0.0.1/tenant/auth/callback";

        @Test
        @DisplayName("accepts 127.0.0.1 with any OS-assigned port")
        void shouldAllowIpv4LoopbackAnyPort() {
            var context = buildContext("kelta-cli",
                    "http://127.0.0.1:49152/acme/auth/callback", CLI_REGISTERED_URI);
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts [::1] with any OS-assigned port")
        void shouldAllowIpv6LoopbackAnyPort() {
            var context = buildContext("kelta-cli",
                    "http://[::1]:60321/acme/auth/callback", CLI_REGISTERED_URI);
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects the localhost hostname — DNS/hosts-file poisonable")
        void shouldRejectLocalhostHostname() {
            var context = buildContext("kelta-cli",
                    "http://localhost:49152/acme/auth/callback", CLI_REGISTERED_URI);
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("rejects a non-loopback host")
        void shouldRejectNonLoopbackHost() {
            var context = buildContext("kelta-cli",
                    "http://192.168.0.10:49152/acme/auth/callback", CLI_REGISTERED_URI);
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("rejects a path other than /callback")
        void shouldRejectOtherPaths() {
            var context = buildContext("kelta-cli",
                    "http://127.0.0.1:49152/other", CLI_REGISTERED_URI);
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("rejects https-scheme loopback (RFC 8252 loopback is http)")
        void shouldRejectHttpsScheme() {
            var context = buildContext("kelta-cli",
                    "https://127.0.0.1:49152/acme/auth/callback", CLI_REGISTERED_URI);
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("rejects userinfo, query, and fragment decorations")
        void shouldRejectDecoratedUris() {
            for (String uri : new String[]{
                    "http://user@127.0.0.1:49152/acme/auth/callback",
                    "http://127.0.0.1:49152/acme/auth/callback?x=1",
                    "http://127.0.0.1:49152/acme/auth/callback#frag"}) {
                var context = buildContext("kelta-cli", uri, CLI_REGISTERED_URI);
                assertThatThrownBy(() -> validator.accept(context))
                        .as("should reject %s", uri)
                        .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
            }
        }

        @Test
        @DisplayName("rejects a slug-less /callback — TenantContextFilter would find no tenant")
        void shouldRejectSlugLessCallback() {
            // Regression: this shape authorized fine, then failed the actual
            // login with "no tenant context in session" (the tenant is derived
            // from the redirect_uri path).
            var context = buildContext("kelta-cli",
                    "http://127.0.0.1:49152/callback", CLI_REGISTERED_URI);
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("rejects malformed or traversing slugs")
        void shouldRejectMalformedSlugs() {
            for (String uri : new String[]{
                    "http://127.0.0.1:49152//auth/callback",
                    "http://127.0.0.1:49152/../auth/callback",
                    "http://127.0.0.1:49152/Acme/auth/callback",
                    "http://127.0.0.1:49152/9acme/auth/callback",
                    "http://127.0.0.1:49152/acme/x/auth/callback",
                    "http://127.0.0.1:49152/acme/auth/callback/extra"}) {
                var context = buildContext("kelta-cli", uri, CLI_REGISTERED_URI);
                assertThatThrownBy(() -> validator.accept(context))
                        .as("should reject %s", uri)
                        .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
            }
        }

        @Test
        @DisplayName("the loopback rule does NOT apply to kelta-platform")
        void shouldNotApplyLoopbackRuleToPlatformClient() {
            var context = buildContext("kelta-platform",
                    "http://127.0.0.1:49152/acme/auth/callback",
                    "http://localhost:5173/auth/callback");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("the loopback rule does NOT apply to arbitrary clients")
        void shouldNotApplyLoopbackRuleToOtherClients() {
            var context = buildContext("other-client",
                    "http://127.0.0.1:49152/acme/auth/callback",
                    "https://myapp.com/oauth/callback");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("Connected app with multiple redirect URIs")
    class ConnectedAppClient {
        @Test
        void shouldAllowExactPathMatchWithSameOrigin() {
            var context = buildContextMultiRedirect("my-connected-app",
                    "https://myapp.com/oauth/callback",
                    "https://myapp.com/oauth/callback",
                    "https://myapp.com/oauth/callback-staging");
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        void shouldAllowSecondRegisteredRedirectUri() {
            var context = buildContextMultiRedirect("my-connected-app",
                    "https://myapp.com/oauth/callback-staging",
                    "https://myapp.com/oauth/callback",
                    "https://myapp.com/oauth/callback-staging");
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        void shouldRejectDifferentOriginForConnectedApp() {
            var context = buildContextMultiRedirect("my-connected-app",
                    "https://evil.com/oauth/callback",
                    "https://myapp.com/oauth/callback",
                    "https://myapp.com/oauth/callback-staging");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }

        @Test
        void shouldRejectDifferentPathForConnectedApp() {
            var context = buildContextMultiRedirect("my-connected-app",
                    "https://myapp.com/other/path",
                    "https://myapp.com/oauth/callback",
                    "https://myapp.com/oauth/callback-staging");
            assertThatThrownBy(() -> validator.accept(context))
                    .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("Null redirect URI")
    class NullRedirectUri {
        @Test
        void shouldReturnEarlyForNullRedirectUri() {
            var context = buildContext("kelta-platform", null, "http://localhost:5173/auth/callback");
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }

        @Test
        void shouldReturnEarlyForBlankRedirectUri() {
            var context = buildContext("kelta-platform", "", "http://localhost:5173/auth/callback");
            assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
        }
    }

    private OAuth2AuthorizationCodeRequestAuthenticationContext buildContext(
            String clientId, String requestedRedirectUri, String registeredRedirectUri) {

        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(registeredRedirectUri)
                .build();

        OAuth2AuthorizationCodeRequestAuthenticationToken authToken =
                new OAuth2AuthorizationCodeRequestAuthenticationToken(
                        "http://localhost:9000/oauth2/authorize",
                        clientId,
                        new TestingAuthenticationToken("test-principal", null),
                        requestedRedirectUri,
                        "state123",
                        Set.of("openid"),
                        Map.of()
                );

        return OAuth2AuthorizationCodeRequestAuthenticationContext
                .with(authToken)
                .registeredClient(registeredClient)
                .build();
    }

    private OAuth2AuthorizationCodeRequestAuthenticationContext buildContextMultiRedirect(
            String clientId, String requestedRedirectUri, String... registeredRedirectUris) {

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
        for (String uri : registeredRedirectUris) {
            builder.redirectUri(uri);
        }
        RegisteredClient registeredClient = builder.build();

        OAuth2AuthorizationCodeRequestAuthenticationToken authToken =
                new OAuth2AuthorizationCodeRequestAuthenticationToken(
                        "http://localhost:9000/oauth2/authorize",
                        clientId,
                        new TestingAuthenticationToken("test-principal", null),
                        requestedRedirectUri,
                        "state123",
                        Set.of("openid"),
                        Map.of()
                );

        return OAuth2AuthorizationCodeRequestAuthenticationContext
                .with(authToken)
                .registeredClient(registeredClient)
                .build();
    }
}
