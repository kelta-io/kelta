package io.kelta.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.kelta.auth.service.AuthDomainResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Drives the OIDC end-session endpoint ({@code /connect/logout}) through the real
 * authorization-server filter chain from {@link AuthorizationServerConfig}.
 *
 * <p>The platform UI signs out with a tenant-scoped {@code post_logout_redirect_uri}
 * ({@code {ui-origin}/{tenant-slug}?logged_out=true}). Post-logout redirect URIs were not
 * registered for the SPA client, so the endpoint rejected the request before ending the
 * session and the user stayed signed in at the authorization server.
 */
@SpringJUnitWebConfig(OidcLogoutEndpointTest.Config.class)
@DisplayName("OIDC end-session endpoint — post-logout redirect")
class OidcLogoutEndpointTest {

    private static final String UI_ORIGIN = "https://app.example.test";
    private static final String CUSTOM_DOMAIN = "brand.example.org";
    private static final String USER = "user@example.test";
    private static final String OTHER_APP_LOGOUT = "https://other-app.example.net/signed-out";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RegisteredClientRepository clients;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("a tenant-scoped URI on the UI origin ends the session and redirects to it")
    void tenantScopedUriEndsSessionAndRedirects() throws Exception {
        String target = UI_ORIGIN + "/tenant-a?logged_out=true";
        MockHttpSession session = signedInSession();

        MockHttpServletResponse response = logout("kelta-platform", target, session);

        assertThat(response.getStatus()).as("%s", response.getErrorMessage()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(target);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("a verified tenant custom domain ends the session and redirects to it")
    void verifiedCustomDomainEndsSessionAndRedirects() throws Exception {
        String target = "https://" + CUSTOM_DOMAIN + "/?logged_out=true";
        MockHttpSession session = signedInSession();

        MockHttpServletResponse response = logout("kelta-platform", target, session);

        assertThat(response.getStatus()).as("%s", response.getErrorMessage()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(target);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("an unrecognised origin is rejected without redirecting to it or echoing it")
    void unrecognisedOriginIsRejected() throws Exception {
        String target = "https://unrelated.example.com/tenant-a?logged_out=true";
        MockHttpSession session = signedInSession();

        MockHttpServletResponse response = logout("kelta-platform", target, session);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("unrelated.example.com");
        assertThat(response.getErrorMessage()).doesNotContain("unrelated.example.com");
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("other clients keep exact matching against their registered post-logout URIs")
    void otherClientsKeepExactMatching() throws Exception {
        MockHttpSession exactSession = signedInSession();
        MockHttpServletResponse exact = logout("other-app", OTHER_APP_LOGOUT, exactSession);
        assertThat(exact.getStatus()).isEqualTo(302);
        assertThat(exact.getRedirectedUrl()).isEqualTo(OTHER_APP_LOGOUT);
        assertThat(exactSession.isInvalid()).isTrue();

        MockHttpSession sameOriginSession = signedInSession();
        MockHttpServletResponse sameOrigin =
                logout("other-app", "https://other-app.example.net/elsewhere", sameOriginSession);
        assertThat(sameOrigin.getStatus()).isEqualTo(400);
        assertThat(sameOrigin.getRedirectedUrl()).isNull();
        assertThat(sameOriginSession.isInvalid()).isFalse();
    }

    private MockHttpServletResponse logout(String clientId, String postLogoutRedirectUri,
                                           MockHttpSession session) throws Exception {
        String idToken = issueIdToken(clientId);
        // The end-session endpoint reads GET parameters from the query string itself.
        return mvc.perform(get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                        idToken, postLogoutRedirectUri)
                        .session(session))
                .andReturn().getResponse();
    }

    private static MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(userAuthentication()));
        return session;
    }

    private static Authentication userAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                USER, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    /** Stores an authorization holding an ID token for {@code clientId}; returns its value. */
    private String issueIdToken(String clientId) {
        RegisteredClient client = clients.findByClientId(clientId);
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-" + UUID.randomUUID())
                .issuer("http://localhost")
                .subject(USER)
                .audience(List.of(clientId))
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(3600))
                .build();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(USER)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(idToken, metadata ->
                        metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, idToken.getClaims()))
                .attribute(Principal.class.getName(), userAuthentication())
                .build();
        authorizations.save(authorization);
        return idToken.getTokenValue();
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class Config {

        @Bean
        AuthDomainResolver authDomainResolver() {
            AuthDomainResolver resolver = Mockito.mock(AuthDomainResolver.class);
            Mockito.when(resolver.resolveTenantSlug(Mockito.anyString())).thenReturn(Optional.empty());
            Mockito.when(resolver.resolveTenantSlug(CUSTOM_DOMAIN)).thenReturn(Optional.of("tenant-b"));
            return resolver;
        }

        @Bean
        RegisteredClientRepository registeredClientRepository() {
            // Registered the way ConnectedAppRegistrar registers the SPA: a base redirect
            // URI only, no post-logout redirect URIs.
            RegisteredClient platform = RegisteredClient.withId("platform-id")
                    .clientId("kelta-platform")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(UI_ORIGIN + "/auth/callback")
                    .scope(OidcScopes.OPENID)
                    .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                    .build();
            RegisteredClient other = RegisteredClient.withId("other-id")
                    .clientId("other-app")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://other-app.example.net/callback")
                    .postLogoutRedirectUri(OTHER_APP_LOGOUT)
                    .scope(OidcScopes.OPENID)
                    .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                    .build();
            return new InMemoryRegisteredClientRepository(platform, other);
        }

        @Bean
        OAuth2AuthorizationService authorizationService() {
            return new InMemoryOAuth2AuthorizationService();
        }

        @Bean
        AuthorizationServerSettings authorizationServerSettings() {
            return AuthorizationServerSettings.builder().build();
        }

        @Bean
        JWKSource<SecurityContext> jwkSource() throws Exception {
            return new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate()));
        }

        @Bean
        JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
            return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", new CorsConfiguration());
            return source;
        }

        @Bean
        SecurityFilterChain authorizationServerSecurityFilterChain(
                HttpSecurity http, AuthDomainResolver resolver, RegisteredClientRepository clients)
                throws Exception {
            return new AuthorizationServerConfig().authorizationServerSecurityFilterChain(http, resolver, clients);
        }
    }
}
