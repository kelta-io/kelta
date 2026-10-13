package io.kelta.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.kelta.auth.controller.ForcePasswordChangeController;
import io.kelta.auth.controller.ForgotPasswordController;
import io.kelta.auth.controller.PasswordController;
import io.kelta.auth.filter.PortalPublicRateLimitFilter;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.PasswordResetService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.session.InMemoryIndexedSessionRepository;
import io.kelta.auth.session.SignedInSessionTimeoutFilter;
import io.kelta.auth.session.UserSessionRevoker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.kelta.auth.config.PasswordChangeSignOutFlowTest.PasswordBeans.OTHER_USER_ID;
import static io.kelta.auth.config.PasswordChangeSignOutFlowTest.PasswordBeans.TENANT_ID;
import static io.kelta.auth.config.PasswordChangeSignOutFlowTest.PasswordBeans.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The signed-out password reset through kelta-auth's real filter chains, session filter and
 * per-IP rate limit, over a database with the user ({@code user@example.test}) in two tenants and
 * the real tenant password policy (dictionary included): no sign-in, no session; the JSON
 * endpoints without a CSRF token, the pages with one. Also the forced change at sign-in, which
 * applies the same policy.
 */
@SpringJUnitWebConfig(PasswordResetFlowTest.Config.class)
@DisplayName("Password reset — signed out, through the security chain")
class PasswordResetFlowTest {

    /** Meets the default policy: no dictionary word, nothing of the user's name or email. */
    static final String STRONG = "Qz7#Lm2!Vx9w";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerClient workerClient;
    @Autowired private PasswordEncoder encoder;
    @Autowired private InMemoryIndexedSessionRepository sessions;
    @Autowired private RateLimitCounts rateLimitCounts;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(PortalPublicRateLimitFilter.class),
                        context.getBean(SessionRepositoryFilter.class),
                        context.getBean(SignedInSessionTimeoutFilter.class))
                .apply(springSecurity())
                .build();
        Mockito.clearInvocations(workerClient);
        rateLimitCounts.counts().clear();
        jdbc.update("UPDATE user_credential SET reset_token = NULL, reset_token_expires_at = NULL, "
                + "force_change_on_login = false, password_hash = ?", OidcSignOutFlowTest.ServerBeans.PasswordHolder.HASH);
        jdbc.update("DELETE FROM password_history");
    }

    private MockHttpServletResponse json(String path, String body) throws Exception {
        return mvc.perform(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse requestReset(String email) throws Exception {
        return json("/auth/password/reset-request", "{\"email\":\"" + email + "\"}");
    }

    private MockHttpServletResponse reset(String token, String password) throws Exception {
        return json("/auth/password/reset", "{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
    }

    /** The link mailed to {@code userId}'s address. */
    @SuppressWarnings("unchecked")
    private String mailedLink(String userId) {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(workerClient).sendTemplateEmail(anyString(), eq(OidcSignOutFlowTest.USER),
                eq("user.password_reset"), vars.capture(), eq("PASSWORD_RESET"), eq(userId));
        return (String) vars.getValue().get("actionUrl");
    }

    private static String tokenOf(String link) {
        assertThat(link).startsWith("http://localhost/reset-password?token=");
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private String passwordHash(String userId) {
        return jdbc.queryForObject("SELECT password_hash FROM user_credential WHERE user_id = ?", String.class, userId);
    }

    @Test
    @DisplayName("reset-request: 200 without sign-in or CSRF token, the same answer for an unknown address")
    void requestIsOpenAndEnumerationSafe() throws Exception {
        MockHttpServletResponse known = requestReset(OidcSignOutFlowTest.USER);
        MockHttpServletResponse unknown = requestReset("nobody@example.test");

        assertThat(known.getStatus()).as("%s", known.getContentAsString()).isEqualTo(200);
        assertThat(unknown.getStatus()).isEqualTo(200);
        assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString());
        assertThat(known.getCookie(OidcSignOutFlowTest.Config.COOKIE_NAME)).as("no session started").isNull();
        // One link per account with that address (two tenants), none for the unknown address.
        assertThat(mailedLink(USER_ID)).isNotEqualTo(mailedLink(OTHER_USER_ID));
        Mockito.verify(workerClient, Mockito.times(2)).sendTemplateEmail(anyString(), anyString(),
                eq("user.password_reset"), anyMap(), anyString(), anyString());
    }

    @Test
    @DisplayName("reset: a valid token sets that one user's password (200), once; a bad token is 400")
    void resetWithTheEmailedToken() throws Exception {
        requestReset(OidcSignOutFlowTest.USER);
        String token = tokenOf(mailedLink(USER_ID));
        String otherBefore = passwordHash(OTHER_USER_ID);

        assertThat(reset("not-a-token", STRONG).getStatus()).isEqualTo(400);

        MockHttpServletResponse done = reset(token, STRONG);
        assertThat(done.getStatus()).as("%s", done.getContentAsString()).isEqualTo(200);
        assertThat(encoder.matches(STRONG, passwordHash(USER_ID))).isTrue();
        assertThat(passwordHash(OTHER_USER_ID)).as("same email, other tenant").isEqualTo(otherBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_history WHERE user_id = ?",
                Integer.class, USER_ID)).as("recorded in the history").isEqualTo(1);

        MockHttpServletResponse again = reset(token, "Wy4$Hn8@Tc3q");
        assertThat(again.getStatus()).as("a token is used once").isEqualTo(400);
        assertThat(again.getContentAsString()).contains("Invalid or expired reset token");
    }

    @Test
    @DisplayName("reset: a password the tenant's policy refuses is 400 with the reason; the token stays usable")
    void resetAppliesThePolicy() throws Exception {
        requestReset(OidcSignOutFlowTest.USER);
        String token = tokenOf(mailedLink(USER_ID));

        MockHttpServletResponse weak = reset(token, "password123");
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(weak.getContentAsString()).contains("common");
        assertThat(encoder.matches(OidcSignOutFlowTest.PASSWORD, passwordHash(USER_ID))).as("unchanged").isTrue();

        assertThat(reset(token, STRONG).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("reset-request: the sixth request from one IP within the window is 429")
    void requestIsRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(requestReset("nobody-" + i + "@example.test").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = requestReset("nobody-6@example.test");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("the pages: open without sign-in, the forms need the CSRF token, and the emailed link resets")
    void pages() throws Exception {
        MockHttpServletResponse page = mvc.perform(get("/forgot-password")).andReturn().getResponse();
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentAsString()).contains("Email me a reset link").contains("name=\"_csrf\"");
        assertThat(mvc.perform(post("/forgot-password").param("email", OidcSignOutFlowTest.USER))
                .andReturn().getResponse().getStatus()).as("no CSRF token").isEqualTo(403);
        Mockito.verifyNoInteractions(workerClient);

        MvcResult sent = mvc.perform(post("/forgot-password").param("email", OidcSignOutFlowTest.USER).with(csrf()))
                .andReturn();
        assertThat(sent.getModelAndView().getModel()).containsEntry("sent", true);
        assertThat(sent.getResponse().getContentAsString()).contains("If an account exists with that email");
        String token = tokenOf(mailedLink(USER_ID));

        MvcResult form = mvc.perform(get("/reset-password").param("token", token)).andReturn();
        assertThat(form.getResponse().getStatus()).isEqualTo(200);
        assertThat(form.getModelAndView().getModel()).containsEntry("token", token).doesNotContainKey("invalid");
        assertThat(form.getResponse().getContentAsString())
                .contains("value=\"" + token + "\"").contains("name=\"_csrf\"")
                .contains("<meta name=\"referrer\" content=\"same-origin\">")
                .contains("Choose a new password").contains("Reset Password");
        assertThat(mvc.perform(get("/reset-password").param("token", "not-a-token")).andReturn()
                .getModelAndView().getModel()).containsEntry("invalid", true);

        MvcResult mismatch = mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                .param("newPassword", STRONG).param("confirmPassword", "something-else")).andReturn();
        assertThat(mismatch.getModelAndView().getModel()).containsEntry("error", "New passwords do not match.");

        MvcResult done = mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                .param("newPassword", STRONG).param("confirmPassword", STRONG)).andReturn();
        assertThat(done.getModelAndView().getModel())
                .containsEntry("done", true)
                .containsEntry("appUrl", "https://app.example.test/tenant-a/app");
        assertThat(done.getResponse().getContentAsString()).contains("href=\"https://app.example.test/tenant-a/app\"");
        assertThat(encoder.matches(STRONG, passwordHash(USER_ID))).isTrue();
    }

    @Test
    @DisplayName("the forms post with the browser's Origin header: same-origin passes, \"null\" (no-referrer) does not")
    void formPostsCarryTheBrowsersOrigin() throws Exception {
        // A browser sends Origin on every form POST. The CORS filter compares it with the
        // request's own origin; under a no-referrer policy it is "null" and the post is refused.
        assertThat(mvc.perform(post("/forgot-password").header("Origin", "http://localhost").with(csrf())
                .param("email", "nobody@example.test")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/forgot-password").header("Origin", "null").with(csrf())
                .param("email", "nobody@example.test")).andReturn().getResponse().getStatus())
                .as("why the pages must not use no-referrer").isEqualTo(403);
        for (String page : List.of("/forgot-password", "/reset-password?token=x")) {
            assertThat(mvc.perform(get(page)).andReturn().getResponse().getContentAsString())
                    .contains("<meta name=\"referrer\" content=\"same-origin\">")
                    .doesNotContain("content=\"no-referrer\"");
        }
    }

    @Test
    @DisplayName("an invite link (an account with no password yet) opens the page worded for a first password")
    void inviteLinkSetsAFirstPassword() throws Exception {
        // As kelta-worker's UserInviteService leaves an invited user: no usable password, flagged.
        jdbc.update("UPDATE user_credential SET password_hash = '', force_change_on_login = true, "
                + "reset_token = 'invite-token', reset_token_expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)), USER_ID);

        MvcResult form = mvc.perform(get("/reset-password").param("token", "invite-token")).andReturn();
        assertThat(form.getModelAndView().getModel()).containsEntry("firstPassword", true);
        assertThat(form.getResponse().getContentAsString()).contains("Set your password").contains("Set Password");

        MvcResult done = mvc.perform(post("/reset-password").with(csrf()).param("token", "invite-token")
                .param("newPassword", STRONG).param("confirmPassword", STRONG)).andReturn();
        assertThat(done.getResponse().getContentAsString()).contains("Your password is set. Sign in with it.");
        assertThat(encoder.matches(STRONG, passwordHash(USER_ID))).isTrue();
        assertThat(jdbc.queryForObject("SELECT force_change_on_login FROM user_credential WHERE user_id = ?",
                Boolean.class, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("forced change at sign-in: a password the tenant's policy refuses is not set")
    void forcedChangeAppliesThePolicy() throws Exception {
        jdbc.update("UPDATE user_credential SET force_change_on_login = true WHERE user_id = ?", USER_ID);
        Cookie cookie = pendingForcedChange();

        MvcResult weak = mvc.perform(post("/change-password").cookie(cookie).with(csrf())
                .param("currentPassword", OidcSignOutFlowTest.PASSWORD)
                .param("newPassword", "password123").param("confirmPassword", "password123")).andReturn();

        assertThat(weak.getModelAndView().getViewName()).isEqualTo("change-password");
        assertThat((String) weak.getModelAndView().getModel().get("error")).contains("common");
        assertThat(encoder.matches(OidcSignOutFlowTest.PASSWORD, passwordHash(USER_ID))).as("unchanged").isTrue();

        MvcResult strong = mvc.perform(post("/change-password").cookie(cookie).with(csrf())
                .param("currentPassword", OidcSignOutFlowTest.PASSWORD)
                .param("newPassword", STRONG).param("confirmPassword", STRONG)).andReturn();

        assertThat(strong.getResponse().getRedirectedUrl()).isEqualTo("/login?passwordChanged");
        assertThat(encoder.matches(STRONG, passwordHash(USER_ID))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_history WHERE user_id = ?",
                Integer.class, USER_ID)).as("recorded in the history").isEqualTo(1);
    }

    /** A session in the state LoginFailureHandler leaves for the user; its cookie. */
    private Cookie pendingForcedChange() {
        MapSession session = sessions.createSession();
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL, OidcSignOutFlowTest.USER);
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID, USER_ID);
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID, TENANT_ID);
        sessions.save(session);
        return new Cookie(OidcSignOutFlowTest.Config.COOKIE_NAME,
                Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    /** Requests per rate-limit key in the current window. */
    record RateLimitCounts(Map<String, Long> counts) {}

    @Configuration
    @Import({OidcSignOutFlowTest.ServerBeans.class, OidcSignOutFlowTest.InMemorySessions.class})
    static class Config {

        @Bean
        DataSource dataSource() {
            return PasswordChangeSignOutFlowTest.PasswordBeans.database();
        }

        @Bean
        AuthProperties authProperties() {
            AuthProperties properties = new AuthProperties();
            properties.setUiBaseUrl("https://app.example.test");
            return properties;
        }

        @Bean
        JWKSource<SecurityContext> jwkSource() throws Exception {
            return new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate()));
        }

        @Bean
        WorkerClient workerClient() {
            return Mockito.mock(WorkerClient.class);
        }

        @Bean
        UserSessionRevoker userSessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions,
                                              org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations,
                                              JdbcTemplate jdbcTemplate) {
            return new UserSessionRevoker(sessions, authorizations, jdbcTemplate);
        }

        /** The real policy; Spring runs its dictionary load. */
        @Bean
        PasswordPolicyService passwordPolicyService(JdbcTemplate jdbcTemplate, PasswordEncoder encoder,
                                                    WorkerClient workerClient, AuthProperties properties) {
            return new PasswordPolicyService(jdbcTemplate, encoder, workerClient, properties);
        }

        @Bean
        PasswordResetService passwordResetService(JdbcTemplate jdbcTemplate, PasswordEncoder encoder,
                                                  PasswordPolicyService policies, UserSessionRevoker revoker,
                                                  WorkerClient workerClient, AuthDomainResolver domains,
                                                  AuthProperties properties) {
            return new PasswordResetService(jdbcTemplate, encoder, policies, revoker, workerClient, domains,
                    properties, Runnable::run);
        }

        @Bean
        PasswordController passwordController(JdbcTemplate jdbcTemplate, PasswordEncoder encoder,
                                              PasswordPolicyService policies, UserSessionRevoker revoker,
                                              PasswordResetService resets) {
            return new PasswordController(jdbcTemplate, encoder, policies, revoker, resets);
        }

        @Bean
        ForgotPasswordController forgotPasswordController(PasswordResetService resets, AuthProperties properties) {
            return new ForgotPasswordController(resets, properties);
        }

        @Bean
        ForcePasswordChangeController forcePasswordChangeController(JdbcTemplate jdbcTemplate, PasswordEncoder encoder,
                                                                    UserSessionRevoker revoker,
                                                                    PasswordPolicyService policies) {
            return new ForcePasswordChangeController(jdbcTemplate, encoder, revoker, policies);
        }

        @Bean
        RateLimitCounts rateLimitCounts() {
            return new RateLimitCounts(new ConcurrentHashMap<>());
        }

        /** The pages rendered by Thymeleaf from the real templates, with the CSRF field Spring Security adds. */
        @Bean
        ThymeleafViewResolver thymeleafViewResolver(ApplicationContext context) {
            SpringResourceTemplateResolver templates = new SpringResourceTemplateResolver();
            templates.setApplicationContext(context);
            templates.setPrefix("classpath:/templates/");
            templates.setSuffix(".html");
            templates.setTemplateMode(TemplateMode.HTML);
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templates);
            ThymeleafViewResolver resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");
            resolver.setOrder(0);
            return resolver;
        }

        /** The filter with its default budgets, over a Redis that counts like the window script. */
        @Bean
        @SuppressWarnings("unchecked")
        PortalPublicRateLimitFilter portalPublicRateLimitFilter(RateLimitCounts rateLimitCounts) {
            StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
            Mockito.when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(inv ->
                    rateLimitCounts.counts().merge(((List<String>) inv.getArgument(1)).getFirst(), 1L, Long::sum));
            return new PortalPublicRateLimitFilter(redis,
                    List.of(PortalPublicRateLimitFilter.DEFAULT_IP_PATHS.split(",")),
                    List.of(PortalPublicRateLimitFilter.DEFAULT_FEDERATION_PATHS.split(",")),
                    List.of(PortalPublicRateLimitFilter.DEFAULT_PASSWORD_RESET_PATHS.split(",")),
                    0, 0, List.of());
        }
    }
}
