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
import io.kelta.auth.session.InMemoryIndexedSessionRepository;
import io.kelta.auth.session.SerializedTypes;
import io.kelta.auth.session.SessionAttributeSerializer;
import io.kelta.auth.session.SessionCookieSerializer;
import io.kelta.auth.session.SessionPrincipalIndexResolver;
import io.kelta.auth.session.SignedInSessionTimeoutFilter;
import io.kelta.auth.session.TenantScopedSessionRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletResponse;
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
import org.springframework.session.DelegatingIndexResolver;
import org.springframework.session.MapSession;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.time.Duration;
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
 *
 * <p>Sessions go through Spring Session's filter and the production cookie, value serializer,
 * index and registry, over an in-memory store that keeps values serialized the way Redis does
 * ({@link InMemoryIndexedSessionRepository}). The browser is a cookie jar ({@link Browser}).
 */
@SpringJUnitWebConfig(OidcSignOutFlowTest.Config.class)
@DisplayName("OIDC sign-out — full browser flow through the JDBC authorization store")
class OidcSignOutFlowTest {

    static final String UI_ORIGIN = "http://app.example.test:8080";
    static final String TENANT_SLUG = "tenant-a";
    static final String CALLBACK = UI_ORIGIN + "/" + TENANT_SLUG + "/auth/callback";
    static final String USER = "user@example.test";
    static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private InMemoryIndexedSessionRepository sessions;

    @Autowired
    private SerializedTypes.Recording sessionSerializer;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = Config.mockMvc(context);
    }

    @Test
    @DisplayName("the issued ID token carries the session's sid, and sign-out ends that session")
    void signOutEndsTheSessionThatSignedIn() throws Exception {
        Browser browser = new Browser();
        String idToken = signIn(browser);
        String sessionId = browser.sessionId();

        String target = UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true";
        MockHttpServletResponse response = browser.send(mvc,
                        get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                                idToken, target));

        assertThat(response.getStatus()).as("%s", response.getErrorMessage()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(target);
        assertThat(sessions.findById(sessionId)).as("session after sign-out").isNull();
        assertThat(SignedJWT.parse(idToken).getJWTClaimsSet().getStringClaim("sid"))
                .as("sid claim of the issued ID token")
                .isEqualTo(sha256(sessionId));
    }

    @Test
    @DisplayName("an ID token issued to another session of the same user is rejected")
    void idTokenFromAnotherSessionIsRejected() throws Exception {
        Browser first = new Browser();
        String firstIdToken = signIn(first);
        Browser second = new Browser();
        signIn(second);

        MockHttpServletResponse response = second.send(mvc,
                        get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                                firstIdToken, UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).contains("invalid_token").contains("sid");
        assertThat(sessions.findById(second.sessionId())).isNotNull();
    }

    @Test
    @DisplayName("a session that signs in again silently gets an ID token naming itself, not a newer one")
    void silentReauthorizationNamesItsOwnSession() throws Exception {
        Browser laptop = new Browser();
        signIn(laptop);
        Browser phone = new Browser();
        signIn(phone);
        // The registry orders sessions by a millisecond timestamp; keep the next request later.
        Thread.sleep(20);
        // A new tab on the laptop: kelta-auth still knows the session, so no login form.
        String idToken = authorize(laptop, false);

        String laptopSession = laptop.sessionId();
        MockHttpServletResponse response = laptop.send(mvc,
                        get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                                idToken, UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true"));

        assertThat(response.getStatus()).as("%s", response.getErrorMessage()).isEqualTo(302);
        assertThat(sessions.findById(laptopSession)).isNull();
        assertThat(sessions.findById(phone.sessionId())).isNotNull();
    }

    @Test
    @DisplayName("each signed-in session is tracked once per user, not once per principal instance")
    void sessionsAreTrackedPerUser() throws Exception {
        Browser first = new Browser();
        signIn(first);
        Browser second = new Browser();
        signIn(second);

        KeltaUserDetails user = Config.user();
        assertThat(sessionRegistry.getAllSessions(user, false))
                .extracting(info -> info.getSessionId())
                .contains(first.sessionId(), second.sessionId());
    }

    @Test
    @DisplayName("a session that has not signed in keeps the short idle timeout; signing in extends it")
    void signingInExtendsTheIdleTimeout() throws Exception {
        Browser browser = new Browser();
        browser.send(mvc, get(URI.create(authorizeUrl("challenge"))).accept(MediaType.TEXT_HTML));
        assertThat(sessions.storedMaxInactiveInterval(browser.sessionId()))
                .as("before sign-in").isEqualTo(Config.DEFAULT_TIMEOUT);

        signIn(browser);
        assertThat(sessions.storedMaxInactiveInterval(browser.sessionId()))
                .as("after sign-in").isEqualTo(Config.SIGNED_IN_TIMEOUT);
    }

    @Test
    @DisplayName("every value the sign-in and sign-out flow stores is registered for native serialization")
    void storedValuesAreRegisteredForNativeSerialization() throws Exception {
        Browser browser = new Browser();
        String idToken = signIn(browser);
        browser.send(mvc, get("/connect/logout?id_token_hint={idToken}&post_logout_redirect_uri={uri}",
                idToken, UI_ORIGIN + "/" + TENANT_SLUG + "?logged_out=true"));

        assertThat(sessionSerializer.types()).isNotEmpty();
        assertThat(SerializedTypes.missingSerializationHints(SerializedTypes.nativeHints(), sessionSerializer.types()))
                .isEmpty();
    }

    /** Signs in with the login form; returns the issued ID token. */
    private String signIn(Browser browser) throws Exception {
        return authorize(browser, true);
    }

    /**
     * Drives authorize → (login → authorize) → token for {@code session}, the browser's cookie
     * jar; returns the issued ID token. The token request carries no session, as the SPA's
     * cross-origin token call does not.
     */
    private String authorize(Browser browser, boolean expectLoginForm) throws Exception {
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String authorize = authorizeUrl(sha256(verifier));

        MockHttpServletResponse toCallback = browser.send(mvc, get(URI.create(authorize))
                        .accept(MediaType.TEXT_HTML));
        if (expectLoginForm) {
            assertThat(toCallback.getStatus()).isEqualTo(302);
            assertThat(toCallback.getRedirectedUrl()).contains("/login");

            MockHttpServletResponse loggedIn = browser.send(mvc, post("/login")
                            .param("username", USER)
                            .param("password", PASSWORD)
                            .with(csrf()));
            assertThat(loggedIn.getStatus()).as("login").isEqualTo(302);
            String resumed = loggedIn.getRedirectedUrl();
            assertThat(resumed).contains("/oauth2/authorize");

            toCallback = browser.send(mvc, get(URI.create(resumed)).accept(MediaType.TEXT_HTML));
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

    static String authorizeUrl(String codeChallenge) {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "kelta-platform")
                .queryParam("redirect_uri", CALLBACK)
                .queryParam("scope", "openid profile email")
                .queryParam("state", "state-1")
                .queryParam("nonce", "nonce-1")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .encode().build().toUriString();
    }

    static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** A browser's cookie jar for the session cookie. */
    static final class Browser {

        private String cookie;

        MockHttpServletResponse send(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
            if (cookie != null) {
                request.cookie(new Cookie(Config.COOKIE_NAME, cookie));
            }
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            Cookie set = response.getCookie(Config.COOKIE_NAME);
            if (set != null) {
                cookie = set.getMaxAge() == 0 ? null : set.getValue();
            }
            return response;
        }

        String sessionId() {
            assertThat(cookie).as("session cookie").isNotNull();
            return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
        }
    }

    /**
     * The server beans (filter chains, clients, JDBC authorization store) over an H2 database
     * and an in-memory session store. {@link ServerBeans} alone is the part a test with another
     * session store or database reuses.
     */
    @Configuration
    @Import({ServerBeans.class, InMemorySessions.class})
    static class Config {

        static final String COOKIE_NAME = "kelta_auth_session";
        static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
        static final Duration SIGNED_IN_TIMEOUT = Duration.ofHours(8);

        /** A MockMvc with the session filters in front of Spring Security, as in the service. */
        static MockMvc mockMvc(WebApplicationContext context) {
            return MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(SessionRepositoryFilter.class),
                            context.getBean(SignedInSessionTimeoutFilter.class))
                    .apply(springSecurity())
                    .build();
        }

        /** A fresh instance per lookup, as the JDBC user lookup returns one per login. */
        static KeltaUserDetails user() {
            return ServerBeans.user();
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
        JWKSource<SecurityContext> jwkSource() throws Exception {
            return new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate()));
        }
    }

    /** The session store, cookie and registry the service uses, over an in-memory store. */
    @Configuration
    static class InMemorySessions {

        @Bean
        SerializedTypes.Recording sessionSerializer() {
            return new SerializedTypes.Recording(new SessionAttributeSerializer(getClass().getClassLoader()));
        }

        @Bean
        InMemoryIndexedSessionRepository sessionRepository(SerializedTypes.Recording serializer) {
            return new InMemoryIndexedSessionRepository(
                    new DelegatingIndexResolver<>(new SessionPrincipalIndexResolver()), serializer,
                    Config.DEFAULT_TIMEOUT);
        }

        @Bean
        SessionRegistry sessionRegistry(InMemoryIndexedSessionRepository sessions) {
            return new TenantScopedSessionRegistry<>(sessions);
        }

        @Bean
        SessionRepositoryFilter<MapSession> sessionRepositoryFilter(InMemoryIndexedSessionRepository sessions) {
            CookieHttpSessionIdResolver cookies = new CookieHttpSessionIdResolver();
            cookies.setCookieSerializer(new SessionCookieSerializer(Config.COOKIE_NAME, "None",
                    new CookieDomainResolver()));
            SessionRepositoryFilter<MapSession> filter = new SessionRepositoryFilter<>(sessions);
            filter.setHttpSessionIdResolver(cookies);
            return filter;
        }

        @Bean
        SignedInSessionTimeoutFilter signedInSessionTimeoutFilter() {
            return new SignedInSessionTimeoutFilter(Config.SIGNED_IN_TIMEOUT, Config.DEFAULT_TIMEOUT);
        }
    }

    /**
     * The authorization server as {@link AuthorizationServerConfig} builds it, needing a
     * {@link DataSource}, a {@link JWKSource} and a {@link SessionRegistry} from elsewhere.
     */
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class ServerBeans {

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
        AuthorizationServerSettings authorizationServerSettings() {
            return AuthorizationServerSettings.builder().build();
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
                HttpSecurity http, AuthDomainResolver resolver, RegisteredClientRepository clients) throws Exception {
            return serverConfig.authorizationServerSecurityFilterChain(http, resolver, clients);
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
                    policies,
                    null, null, null);
        }
    }
}
