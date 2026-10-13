package io.kelta.auth.config;

import io.kelta.auth.controller.PasswordController;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.PasswordResetService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.session.InMemoryIndexedSessionRepository;
import io.kelta.auth.session.UserSessionRevoker;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Changing the password signs the user out everywhere else: the other HTTP sessions end, the
 * session the change was made in stays signed in, and no refresh token issued before the change
 * is accepted again. Another tenant's user with the same email address is untouched.
 *
 * <p>Real filter chains, JDBC authorization store, session filter, cookie and index (over an
 * in-memory store); {@code SharedHttpSessionIntegrationTest} runs the same against Redis.
 */
@SpringJUnitWebConfig(PasswordChangeSignOutFlowTest.Config.class)
@DisplayName("Password change — signs the user out of every other session and revokes refresh tokens")
class PasswordChangeSignOutFlowTest {

    static final String NEW_PASSWORD = "a-brand-new-passphrase-42";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private InMemoryIndexedSessionRepository sessions;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    @Autowired
    private RegisteredClientRepository clients;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = OidcSignOutFlowTest.Config.mockMvc(context);
    }

    @Test
    @DisplayName("the other session ends, the current one survives, and earlier refresh tokens are rejected")
    void signsOutEverywhereElse() throws Exception {
        OidcSignOutFlowTest.Browser laptop = new OidcSignOutFlowTest.Browser();
        String laptopRefresh = signIn(mvc, laptop, true).get("refresh_token").asString();
        OidcSignOutFlowTest.Browser phone = new OidcSignOutFlowTest.Browser();
        String phoneRefresh = signIn(mvc, phone, true).get("refresh_token").asString();
        String otherTenantSession = PasswordBeans.otherTenantSession(sessions);
        String otherTenantAuthorization = PasswordBeans.otherTenantAuthorization(authorizations, clients);

        // A refresh token works before the change (and rotates).
        MockHttpServletResponse refreshed = refresh(mvc, phoneRefresh);
        assertThat(refreshed.getStatus()).as("%s", refreshed.getContentAsString()).isEqualTo(200);
        phoneRefresh = JsonMapper.builder().build().readTree(refreshed.getContentAsString())
                .get("refresh_token").asString();

        MockHttpServletResponse changed = changePassword(mvc, laptop);
        assertThat(changed.getStatus()).as("%s", changed.getContentAsString()).isEqualTo(200);

        assertThat(sessions.findById(phone.sessionId())).as("the other session").isNull();
        assertThat(sessions.findById(laptop.sessionId())).as("the session the change was made in").isNotNull();
        assertThat(sessions.findById(otherTenantSession)).as("same email, other tenant").isNotNull();

        assertThat(refresh(mvc, phoneRefresh).getContentAsString()).contains("invalid_grant");
        assertThat(refresh(mvc, laptopRefresh).getContentAsString()).contains("invalid_grant");
        assertThat(authorizations.findById(otherTenantAuthorization)).as("other tenant's authorization").isNotNull();

        // The current browser is still signed in: a new authorization needs no login form.
        signIn(mvc, laptop, false);
        // The other browser has to sign in again.
        MockHttpServletResponse phoneAgain = phone.send(mvc,
                get(URI.create(OidcSignOutFlowTest.authorizeUrl("challenge"))).accept(MediaType.TEXT_HTML));
        assertThat(phoneAgain.getRedirectedUrl()).contains("/login");
    }

    static JsonNode signIn(MockMvc mvc, OidcSignOutFlowTest.Browser browser, boolean withLoginForm) throws Exception {
        return signIn(mvc, browser, withLoginForm, "");
    }

    /**
     * Authorize (signing in with the form when {@code withLoginForm}) and exchange the code, on
     * {@code origin} ({@code ""}: MockMvc's default); the token response.
     */
    static JsonNode signIn(MockMvc mvc, OidcSignOutFlowTest.Browser browser, boolean withLoginForm, String origin)
            throws Exception {
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        MockHttpServletResponse response = browser.send(mvc,
                get(URI.create(origin + OidcSignOutFlowTest.authorizeUrl(OidcSignOutFlowTest.sha256(verifier))))
                        .accept(MediaType.TEXT_HTML));
        if (withLoginForm) {
            assertThat(response.getRedirectedUrl()).contains("/login");
            MockHttpServletResponse loggedIn = browser.send(mvc, post(URI.create(origin + "/login"))
                    .param("username", OidcSignOutFlowTest.USER)
                    .param("password", OidcSignOutFlowTest.PASSWORD)
                    .with(csrf()));
            response = browser.send(mvc, get(URI.create(loggedIn.getRedirectedUrl())).accept(MediaType.TEXT_HTML));
        }
        assertThat(response.getRedirectedUrl()).startsWith(OidcSignOutFlowTest.CALLBACK);
        String code = UriComponentsBuilder.fromUriString(response.getRedirectedUrl())
                .build().getQueryParams().getFirst("code");
        MockHttpServletResponse token = mvc.perform(post(URI.create(origin + "/oauth2/token"))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", OidcSignOutFlowTest.CALLBACK)
                        .param("client_id", "kelta-platform")
                        .param("code_verifier", verifier))
                .andReturn().getResponse();
        assertThat(token.getStatus()).as("%s", token.getContentAsString()).isEqualTo(200);
        JsonNode body = JsonMapper.builder().build().readTree(token.getContentAsString());
        assertThat(body.get("refresh_token")).as("refresh token issued").isNotNull();
        return body;
    }

    static MockHttpServletResponse refresh(MockMvc mvc, String refreshToken) throws Exception {
        return refresh(mvc, refreshToken, "");
    }

    static MockHttpServletResponse refresh(MockMvc mvc, String refreshToken, String origin) throws Exception {
        return mvc.perform(post(URI.create(origin + "/oauth2/token"))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", "kelta-platform"))
                .andReturn().getResponse();
    }

    static MockHttpServletResponse changePassword(MockMvc mvc, OidcSignOutFlowTest.Browser browser) throws Exception {
        return changePassword(mvc, browser, "");
    }

    static MockHttpServletResponse changePassword(MockMvc mvc, OidcSignOutFlowTest.Browser browser, String origin)
            throws Exception {
        return browser.send(mvc, post(URI.create(origin + "/auth/password/change"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + OidcSignOutFlowTest.PASSWORD
                        + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .with(csrf()));
    }

    @Configuration
    @Import({OidcSignOutFlowTest.ServerBeans.class, OidcSignOutFlowTest.InMemorySessions.class, PasswordBeans.class})
    static class Config {

        @Bean
        DataSource dataSource() {
            return PasswordBeans.database();
        }

        @Bean
        AuthProperties authProperties() {
            return new AuthProperties();
        }

        @Bean
        JWKSource<SecurityContext> jwkSource() throws Exception {
            return new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate()));
        }
    }

    /**
     * The password endpoint and what it needs, over a database holding the signed-in user
     * ({@link OidcSignOutFlowTest.ServerBeans#user()}) and a user with the same email address in
     * another tenant. Needs an {@link AuthProperties} bean from elsewhere.
     */
    @Configuration
    static class PasswordBeans {

        static final String TENANT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
        static final String USER_ID = "11111111-0000-0000-0000-000000000001";
        static final String OTHER_TENANT_ID = "bbbbbbbb-0000-0000-0000-000000000002";
        static final String OTHER_USER_ID = "22222222-0000-0000-0000-000000000002";

        /** H2 with the authorization server's tables and the platform's user tables, seeded. */
        static EmbeddedDatabase database() {
            EmbeddedDatabase dataSource = new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")
                    .addScript("schema-test.sql")
                    .build();
            seed(new JdbcTemplate(dataSource));
            return dataSource;
        }

        static void seed(JdbcTemplate jdbc) {
            String hash = OidcSignOutFlowTest.ServerBeans.PasswordHolder.HASH;
            for (String[] user : new String[][] {{TENANT_ID, USER_ID, "tenant-a"}, {OTHER_TENANT_ID, OTHER_USER_ID, "tenant-b"}}) {
                jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", user[0], user[2], user[2]);
                jdbc.update("INSERT INTO platform_user (id, tenant_id, email, first_name, last_name) VALUES (?, ?, ?, 'Test', 'User')",
                        user[1], user[0], OidcSignOutFlowTest.USER);
                jdbc.update("INSERT INTO user_credential (id, user_id, password_hash) VALUES (?, ?, ?)",
                        UUID.randomUUID().toString(), user[1], hash);
            }
        }

        static KeltaUserDetails otherTenantUser() {
            return new KeltaUserDetails(OTHER_USER_ID, OidcSignOutFlowTest.USER, OTHER_TENANT_ID, "profile-2",
                    "Standard", "Test User", null, true, false, false);
        }

        /** A signed-in session of the other tenant's user, stored directly; its id. */
        static <S extends Session> String otherTenantSession(FindByIndexNameSessionRepository<S> sessions) {
            S session = sessions.createSession();
            KeltaUserDetails user = otherTenantUser();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                    UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities())));
            sessions.save(session);
            return session.getId();
        }

        /** An authorization (with a live refresh token) of the other tenant's user; its id. */
        static String otherTenantAuthorization(OAuth2AuthorizationService authorizations,
                                               RegisteredClientRepository clients) {
            KeltaUserDetails user = otherTenantUser();
            Instant now = Instant.now();
            OAuth2Authorization authorization = OAuth2Authorization
                    .withRegisteredClient(clients.findByClientId("kelta-platform"))
                    .id(UUID.randomUUID().toString())
                    .principalName(user.getUsername())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizedScopes(Set.of("openid"))
                    .attribute(Principal.class.getName(),
                            UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()))
                    .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "other-access", now,
                            now.plus(15, ChronoUnit.MINUTES), Set.of("openid")))
                    .refreshToken(new OAuth2RefreshToken("other-refresh", now, now.plus(1, ChronoUnit.DAYS)))
                    .build();
            authorizations.save(authorization);
            assertThat(authorizations.findByToken("other-refresh", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
            return authorization.getId();
        }

        @Bean
        UserSessionRevoker userSessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions,
                                              OAuth2AuthorizationService authorizations, JdbcTemplate jdbcTemplate) {
            return new UserSessionRevoker(sessions, authorizations, jdbcTemplate);
        }

        @Bean
        PasswordController passwordController(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                                              UserSessionRevoker revoker, AuthProperties properties) {
            PasswordPolicyService policies = Mockito.mock(PasswordPolicyService.class);
            return new PasswordController(jdbcTemplate, passwordEncoder, policies, revoker,
                    new PasswordResetService(jdbcTemplate, passwordEncoder, policies, revoker,
                            Mockito.mock(WorkerClient.class), Mockito.mock(AuthDomainResolver.class), properties,
                            Runnable::run));
        }
    }
}
