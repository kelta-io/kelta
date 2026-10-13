package io.kelta.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import io.kelta.auth.federation.FederatedUserMapper;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.WorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Signs a user in and out of the platform UI end to end, the way the browser does:
 * form login → {@code /oauth2/authorize} (PKCE) → {@code /oauth2/token} → {@code /connect/logout}
 * with the issued ID token as {@code id_token_hint}, all through the real filter chains from
 * {@link AuthorizationServerConfig} and the real JDBC authorization store.
 *
 * <p>The JDBC store matters: the code exchange reads the signed-in user back from the database,
 * so the principal it sees is a different {@link KeltaUserDetails} instance from the one in the
 * HTTP session. Spring Authorization Server looks the session up by that principal both when it
 * writes the ID token's {@code sid} claim and when the end-session endpoint checks it. When the
 * two instances did not compare equal, the ID token carried no {@code sid} while the end-session
 * endpoint still found the live session, and sign-out answered 400 ({@code invalid_token},
 * {@code sid}) without ending the session.
 */
@SpringJUnitWebConfig(OidcSignOutFlowTest.Config.class)
@DisplayName("OIDC sign-out — full browser flow through the JDBC authorization store")
class OidcSignOutFlowTest {

    private static final String UI_ORIGIN = "http://app.example.test:8080";
    private static final String TENANT_SLUG = "tenant-a";
    private static final String CALLBACK = UI_ORIGIN + "/" + TENANT_SLUG + "/auth/callback";
    private static final String USER = "user@example.test";
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionRegistry sessionRegistry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("the issued ID token carries the session's sid, and sign-out ends that session")
    void signOutEndsTheSessionThatSignedIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String idToken = signIn(session);
        String sessionId = session.getId();

        String target = UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true";
        MockHttpServletResponse response = mvc.perform(
                        get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                                idToken, target)
                                .session(session))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as("%s", response.getErrorMessage()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(target);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SignedJWT.parse(idToken).getJWTClaimsSet().getStringClaim("sid"))
                .as("sid claim of the issued ID token")
                .isEqualTo(sha256(sessionId));
    }

    @Test
    @DisplayName("an ID token issued to another session of the same user is rejected")
    void idTokenFromAnotherSessionIsRejected() throws Exception {
        MockHttpSession first = new MockHttpSession();
        String firstIdToken = signIn(first);
        MockHttpSession second = new MockHttpSession();
        signIn(second);

        MockHttpServletResponse response = mvc.perform(
                        get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                                firstIdToken, UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true")
                                .session(second))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).contains("invalid_token").contains("sid");
        assertThat(second.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("a session that signs in again silently gets an ID token naming itself, not a newer one")
    void silentReauthorizationNamesItsOwnSession() throws Exception {
        MockHttpSession laptop = new MockHttpSession();
        signIn(laptop);
        MockHttpSession phone = new MockHttpSession();
        signIn(phone);
        // The registry orders sessions by a millisecond timestamp; keep the next request later.
        Thread.sleep(20);
        // A new tab on the laptop: kelta-auth still knows the session, so no login form.
        String idToken = authorize(laptop, false);

        MockHttpServletResponse response = mvc.perform(
                        get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                                idToken, UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true")
                                .session(laptop))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as("%s", response.getErrorMessage()).isEqualTo(302);
        assertThat(laptop.isInvalid()).isTrue();
        assertThat(phone.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("each signed-in session is tracked once per user, not once per principal instance")
    void sessionsAreTrackedPerUser() throws Exception {
        MockHttpSession first = new MockHttpSession();
        signIn(first);
        MockHttpSession second = new MockHttpSession();
        signIn(second);

        KeltaUserDetails user = Config.user();
        assertThat(sessionRegistry.getAllSessions(user, false))
                .extracting(info -> info.getSessionId())
                .contains(first.getId(), second.getId());
    }

    /** Signs in with the login form; returns the issued ID token. */
    private String signIn(MockHttpSession session) throws Exception {
        return authorize(session, true);
    }

    /**
     * Drives authorize → (login → authorize) → token for {@code session}, the browser's cookie
     * jar; returns the issued ID token. The token request carries no session, as the SPA's
     * cross-origin token call does not.
     */
    private String authorize(MockHttpSession session, boolean expectLoginForm) throws Exception {
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String authorize = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "kelta-platform")
                .queryParam("redirect_uri", CALLBACK)
                .queryParam("scope", "openid profile email")
                .queryParam("state", "state-1")
                .queryParam("nonce", "nonce-1")
                .queryParam("code_challenge", sha256(verifier))
                .queryParam("code_challenge_method", "S256")
                .encode().build().toUriString();

        MockHttpServletResponse toCallback = mvc.perform(get(URI.create(authorize))
                        .accept(MediaType.TEXT_HTML).session(session))
                .andReturn().getResponse();
        if (expectLoginForm) {
            assertThat(toCallback.getStatus()).isEqualTo(302);
            assertThat(toCallback.getRedirectedUrl()).contains("/login");

            MockHttpServletResponse loggedIn = mvc.perform(post("/login")
                            .param("username", USER)
                            .param("password", PASSWORD)
                            .with(csrf())
                            .session(session))
                    .andReturn().getResponse();
            assertThat(loggedIn.getStatus()).as("login").isEqualTo(302);
            String resumed = loggedIn.getRedirectedUrl();
            assertThat(resumed).contains("/oauth2/authorize");

            toCallback = mvc.perform(get(URI.create(resumed))
                            .accept(MediaType.TEXT_HTML).session(session))
                    .andReturn().getResponse();
        }
        assertThat(toCallback.getStatus()).as("%s", toCallback.getErrorMessage()).isEqualTo(302);
        assertThat(toCallback.getRedirectedUrl()).startsWith(CALLBACK);
        String code = UriComponentsBuilder.fromUriString(toCallback.getRedirectedUrl())
                .build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        MockHttpServletResponse token = mvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", CALLBACK)
                        .param("client_id", "kelta-platform")
                        .param("code_verifier", verifier))
                .andReturn().getResponse();
        assertThat(token.getStatus()).as("%s", token.getContentAsString()).isEqualTo(200);
        return JsonMapper.builder().build()
                .readTree(token.getContentAsString()).get("id_token").asString();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class Config {

        private final AuthorizationServerConfig serverConfig = new AuthorizationServerConfig();

        /** A fresh instance per lookup, as the JDBC user lookup returns one per login. */
        static KeltaUserDetails user() {
            return new KeltaUserDetails("11111111-0000-0000-0000-000000000001", USER,
                    "aaaaaaaa-0000-0000-0000-000000000001", "profile-1", "Standard", "Test User",
                    PasswordHolder.HASH, true, false, false);
        }

        /** Encoded once so every {@link #user()} carries the same hash. */
        static final class PasswordHolder {
            static final String HASH = new AuthorizationServerConfig().passwordEncoder().encode(PASSWORD);
        }

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")
                    .build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        RegisteredClientRepository registeredClientRepository() {
            // Registered the way ConnectedAppRegistrar registers the SPA.
            RegisteredClient platform = RegisteredClient.withId("platform-id")
                    .clientId("kelta-platform")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri(UI_ORIGIN + "/auth/callback")
                    .scope(OidcScopes.OPENID)
                    .scope(OidcScopes.PROFILE)
                    .scope("email")
                    .clientSettings(ClientSettings.builder()
                            .requireAuthorizationConsent(false)
                            .requireProofKey(true)
                            .build())
                    .tokenSettings(ConnectedAppRegistrar.platformTokenSettings())
                    .build();
            return new InMemoryRegisteredClientRepository(platform);
        }

        @Bean
        OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                        RegisteredClientRepository clients) {
            return serverConfig.authorizationService(jdbcTemplate, clients);
        }

        @Bean
        SessionRegistry sessionRegistry() {
            return serverConfig.sessionRegistry();
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
        JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
            return new NimbusJwtEncoder(jwkSource);
        }

        @Bean
        OAuth2TokenGenerator<OAuth2Token> tokenGenerator(JwtEncoder jwtEncoder) {
            return serverConfig.tokenGenerator(jwtEncoder, ctx -> { });
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return serverConfig.passwordEncoder();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> {
                if (!USER.equals(username)) {
                    throw new UsernameNotFoundException("unknown user");
                }
                return user();
            };
        }

        @Bean
        AuthDomainResolver authDomainResolver() {
            AuthDomainResolver resolver = Mockito.mock(AuthDomainResolver.class);
            Mockito.when(resolver.resolveTenantSlug(Mockito.anyString())).thenReturn(Optional.empty());
            return resolver;
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", new CorsConfiguration());
            return source;
        }

        @Bean
        @Order(1)
        SecurityFilterChain authorizationServerSecurityFilterChain(
                HttpSecurity http, AuthDomainResolver resolver, RegisteredClientRepository clients,
                SessionRegistry sessionRegistry) throws Exception {
            return serverConfig.authorizationServerSecurityFilterChain(http, resolver, clients, sessionRegistry);
        }

        @Bean
        @Order(2)
        SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
            PasswordPolicyService policies = Mockito.mock(PasswordPolicyService.class);
            return serverConfig.defaultSecurityFilterChain(http,
                    Mockito.mock(FederatedUserMapper.class),
                    Mockito.mock(WorkerClient.class),
                    Mockito.mock(ClientRegistrationRepository.class),
                    null,
                    policies);
        }
    }
}
