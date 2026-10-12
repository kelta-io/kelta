package io.kelta.auth.config;

import io.kelta.auth.service.AuthDomainResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlatformPostLogoutRedirectUriValidator")
class PlatformPostLogoutRedirectUriValidatorTest {

    private static final String UI_ORIGIN = "https://app.example.test";
    private static final String CUSTOM_DOMAIN = "brand.example.org";

    private AuthDomainResolver domainResolver;
    private PlatformPostLogoutRedirectUriValidator validator;

    @BeforeEach
    void setUp() {
        domainResolver = Mockito.mock(AuthDomainResolver.class);
        Mockito.when(domainResolver.resolveTenantSlug(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(domainResolver.resolveTenantSlug(CUSTOM_DOMAIN)).thenReturn(Optional.of("tenant-b"));
        validator = new PlatformPostLogoutRedirectUriValidator(domainResolver);
    }

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            UI_ORIGIN + "/tenant-a?logged_out=true",
            UI_ORIGIN + "/?logged_out=true",
            UI_ORIGIN,
            UI_ORIGIN + "/tenant-a/app/home",
            "https://APP.example.test/tenant-a?logged_out=true",
            "HTTPS://app.example.test/tenant-a",
            "https://app.example.test:443/tenant-a",
            "https://" + CUSTOM_DOMAIN + "/?logged_out=true",
            "https://" + CUSTOM_DOMAIN + "/tenant-b?logged_out=true",
    })
    @DisplayName("platform client: a URI on a registered or verified origin, any path and query")
    void acceptsTrustedPlatformOrigins(String uri) {
        assertThatCode(() -> validator.accept(context(platformClient(UI_ORIGIN + "/auth/callback"), uri)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            // other origins
            "https://unrelated.example.com/tenant-a?logged_out=true",
            "https://app.example.test.unrelated.example.com/",
            "https://sub.app.example.test/",
            // scheme / port changes on the registered host
            "http://app.example.test/tenant-a",
            "https://app.example.test:8443/tenant-a",
            "ftp://app.example.test/",
            "javascript://app.example.test/%0aalert(1)",
            // userinfo, fragments, malformed and relative forms
            "https://app.example.test@unrelated.example.com/",
            "https://unrelated.example.com@app.example.test/",
            "https://app.example.test/tenant-a#frag",
            "https://unrelated.example.com\\@app.example.test/",
            "https://app.example.test/ tenant-a",
            "//app.example.test/tenant-a",
            "/tenant-a?logged_out=true",
            "app.example.test/tenant-a",
            // a custom domain over plain http, or one that is not verified
            "http://" + CUSTOM_DOMAIN + "/",
            "https://unverified.example.org/",
    })
    @DisplayName("platform client: everything else is rejected")
    void rejectsUntrustedTargets(String uri) {
        assertRejected(platformClient(UI_ORIGIN + "/auth/callback"), uri);
    }

    @Test
    @DisplayName("a registered plain-http dev origin is accepted on that origin only")
    void localDevOrigin() {
        RegisteredClient client = platformClient("http://localhost:5173/auth/callback");

        assertThatCode(() -> validator.accept(context(client, "http://localhost:5173/tenant-a?logged_out=true")))
                .doesNotThrowAnyException();
        assertRejected(client, "http://localhost:5174/tenant-a");
        assertRejected(client, "https://localhost:5173/tenant-a");
    }

    @Test
    @DisplayName("no post_logout_redirect_uri is left to the endpoint's default handling")
    void absentUriIsAllowed() {
        RegisteredClient client = platformClient(UI_ORIGIN + "/auth/callback");
        assertThatCode(() -> validator.accept(context(client, null))).doesNotThrowAnyException();
        assertThatCode(() -> validator.accept(context(client, " "))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("other clients keep exact matching on their registered post-logout URIs")
    void otherClientsExactMatchOnly() {
        RegisteredClient other = RegisteredClient.withId("other-id")
                .clientId("other-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://other-app.example.net/callback")
                .postLogoutRedirectUri("https://other-app.example.net/signed-out")
                .build();

        assertThatCode(() -> validator.accept(context(other, "https://other-app.example.net/signed-out")))
                .doesNotThrowAnyException();
        assertRejected(other, "https://other-app.example.net/elsewhere");
        assertRejected(other, "https://other-app.example.net/signed-out?x=1");
        // A verified custom domain is a platform-UI origin, not one any client may use.
        assertRejected(other, "https://" + CUSTOM_DOMAIN + "/");
    }

    @Test
    @DisplayName("the rejection names the parameter but never carries the supplied value")
    void rejectionDoesNotEchoTheUri() {
        String uri = "https://unrelated.example.com/tenant-a";
        assertThatThrownBy(() -> validator.accept(context(platformClient(UI_ORIGIN + "/auth/callback"), uri)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e -> {
                    assertThat(e.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST);
                    assertThat(e.getError().getDescription()).contains("post_logout_redirect_uri")
                            .doesNotContain("unrelated.example.com");
                    assertThat(e.getMessage()).doesNotContain("unrelated.example.com");
                });
    }

    private void assertRejected(RegisteredClient client, String uri) {
        assertThatThrownBy(() -> validator.accept(context(client, uri)))
                .as(uri)
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static RegisteredClient platformClient(String redirectUri) {
        return RegisteredClient.withId("platform-id")
                .clientId("kelta-platform")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .build();
    }

    private static OidcLogoutAuthenticationContext context(RegisteredClient client, String postLogoutRedirectUri) {
        OidcLogoutAuthenticationToken token = new OidcLogoutAuthenticationToken(
                "id-token", new TestingAuthenticationToken("user", null), "session-id",
                client.getClientId(), postLogoutRedirectUri, null);
        return OidcLogoutAuthenticationContext.with(token).registeredClient(client).build();
    }
}
