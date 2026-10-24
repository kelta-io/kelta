package io.kelta.auth.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.zaxxer.hikari.HikariDataSource;
import io.kelta.auth.controller.ForcePasswordChangeController;
import io.kelta.auth.controller.PasswordController;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.PasswordResetService;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.KeltaUserDetailsService;
import io.kelta.auth.session.UserSessionRevoker;
import io.kelta.runtime.context.TenantAwareDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.kelta.auth.config.BaselineAdminPasswordInitializer.ADMIN_EMAIL;
import static io.kelta.auth.config.BaselineAdminPasswordInitializer.BASELINE_HASH;
import static io.kelta.auth.config.BaselineAdminPasswordInitializer.PLATFORM_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots {@link BaselineAdminPasswordInitializer} against a database migrated from kelta-worker's
 * real Flyway migrations — the schema a fresh install starts on, with the platform admin
 * {@code admin@kelta.local} seeded as BCrypt("password") — and signs in through kelta-auth's real
 * {@link KeltaUserDetailsService}, password encoder and forced-change controller.
 *
 * <p>kelta-auth connects as a role without BYPASSRLS whose default {@code app.current_tenant_id}
 * is the empty platform sentinel, wrapped in {@link TenantAwareDataSource}, as in production; the
 * initializer runs with no tenant bound.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Baseline platform admin password — replaced on first boot")
class BaselineAdminPasswordIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String ADMIN_USER_ID = "63d8a151-211d-43ca-9c10-7c3ffe79f9de";
    static final String RACE_THREAD_PREFIX = "baseline-admin-race-";
    static final Pattern BANNER_PASSWORD = Pattern.compile("Password:\\s+(\\S+)");

    /** kelta-worker owns the migrations; Maven runs tests from the module directory. */
    static final Path MIGRATIONS = Path.of("../kelta-worker/src/main/resources/db/migration")
            .toAbsolutePath().normalize();

    static JdbcTemplate admin;
    static HikariDataSource pool;
    static JdbcTemplate app;
    static final PasswordEncoder ENCODER = new AuthorizationServerConfig().passwordEncoder();

    LogCapture logs;

    @BeforeAll
    static void migrate() {
        assertThat(MIGRATIONS).isDirectory();
        DriverManagerDataSource superDs = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        admin = new JdbcTemplate(superDs);
        // Same preparation as kelta-worker's RowLevelSecurityIntegrationTest: extensions need a
        // superuser, and the baseline (a pg_dump) needs the migrating role to own schema public.
        admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector");
        admin.execute("CREATE ROLE auth_app LOGIN PASSWORD 'auth_app' NOBYPASSRLS");
        admin.execute("GRANT ALL ON SCHEMA public TO auth_app");
        admin.execute("ALTER SCHEMA public OWNER TO auth_app");
        admin.execute("ALTER ROLE auth_app SET app.current_tenant_id = ''");

        Flyway.configure()
                .dataSource(dataSource("auth_app", "auth_app"))
                .locations("filesystem:" + MIGRATIONS)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();

        pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("auth_app");
        pool.setPassword("auth_app");
        pool.setMaximumPoolSize(4);
        app = new JdbcTemplate(new TenantAwareDataSource(pool));
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    /** Puts the platform admin back exactly as the baseline seeds it — a fresh install. */
    @BeforeEach
    void freshBaseline() {
        int rows = admin.update("UPDATE user_credential SET password_hash = ?, force_change_on_login = false, "
                + "password_changed_at = NULL WHERE user_id = ?", BASELINE_HASH, ADMIN_USER_ID);
        assertThat(rows).isEqualTo(1);
        logs = LogCapture.allLoggersOnCurrentThreadOr(RACE_THREAD_PREFIX);
    }

    @AfterEach
    void detach() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    private static DriverManagerDataSource dataSource(String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static void boot(String bootstrapPassword) {
        AuthProperties properties = new AuthProperties();
        properties.setBootstrapAdminPassword(bootstrapPassword);
        new BaselineAdminPasswordInitializer(app, ENCODER, properties).run(null);
    }

    /** Signs in to the platform tenant the way /login and /auth/direct-login do. */
    private static Authentication signIn(String password) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("tenantId", PLATFORM_TENANT_ID);
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new KeltaUserDetailsService(app));
            provider.setPasswordEncoder(ENCODER);
            return provider.authenticate(new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, password));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static Map<String, Object> credential() {
        return admin.queryForMap("SELECT password_hash, force_change_on_login, password_changed_at "
                + "FROM user_credential WHERE user_id = ?", ADMIN_USER_ID);
    }

    private List<ILoggingEvent> initializerEvents() {
        return logs.events().stream()
                .filter(e -> e.getLoggerName().equals(BaselineAdminPasswordInitializer.class.getName()))
                .toList();
    }

    private String latestHistoryHash() {
        return admin.queryForObject("SELECT password_hash FROM password_history WHERE user_id = ? "
                + "ORDER BY created_at DESC LIMIT 1", String.class, ADMIN_USER_ID);
    }

    /** The tenant password policy over the real schema (no dictionary file loaded here). */
    private PasswordPolicyService policies() {
        return new PasswordPolicyService(app, ENCODER, Mockito.mock(WorkerClient.class), new AuthProperties());
    }

    private PasswordController passwordController(UserSessionRevoker revoker) {
        PasswordPolicyService policies = policies();
        return new PasswordController(app, ENCODER, policies, revoker, new PasswordResetService(app, ENCODER,
                policies, revoker, Mockito.mock(WorkerClient.class), Mockito.mock(AuthDomainResolver.class),
                new AuthProperties(), Runnable::run));
    }

    private List<ILoggingEvent> warnings() {
        return initializerEvents().stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private List<ILoggingEvent> banners() {
        return warnings().stream()
                .filter(e -> e.getFormattedMessage().contains("Kelta platform admin initial password"))
                .toList();
    }

    @Test
    @DisplayName("/auth/password/change and the email-link reset write the real schema and sign the user out elsewhere")
    void passwordEndpointsOnPostgres() {
        boot("Initial-Value-1a2b3c");
        UserSessionRevoker revoker = Mockito.mock(UserSessionRevoker.class);
        PasswordController controller = passwordController(revoker);
        KeltaUserDetails user = new KeltaUserDetails(ADMIN_USER_ID, ADMIN_EMAIL, PLATFORM_TENANT_ID, null,
                null, "Admin", null, true, false, true);
        Authentication signedIn = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/password/change");
        String sessionId = request.getSession(true).getId();

        var changed = controller.changePassword(
                new PasswordController.ChangePasswordRequest("Initial-Value-1a2b3c", "Changed-Value-4d5e6f"),
                signedIn, request);

        assertThat(changed.getStatusCode().value()).as("%s", changed.getBody()).isEqualTo(200);
        assertThat(signIn("Changed-Value-4d5e6f").isAuthenticated()).isTrue();
        assertThat(credential().get("force_change_on_login")).isEqualTo(false);
        Mockito.verify(revoker).signOutElsewhere(ADMIN_USER_ID, sessionId);

        assertThat(controller.requestPasswordReset(new PasswordController.ResetRequestBody(ADMIN_EMAIL),
                new MockHttpServletRequest("POST", "/auth/password/reset-request"))
                .getStatusCode().value()).isEqualTo(200);
        String token = admin.queryForObject("SELECT reset_token FROM user_credential WHERE user_id = ?",
                String.class, ADMIN_USER_ID);
        var reset = controller.resetPassword(new PasswordController.ResetPasswordRequest(token, "Reset-Value-7g8h9i"),
                new MockHttpServletRequest("POST", "/auth/password/reset"));

        assertThat(reset.getStatusCode().value()).as("%s", reset.getBody()).isEqualTo(200);
        assertThat(signIn("Reset-Value-7g8h9i").isAuthenticated()).isTrue();
        Mockito.verify(revoker).signOutElsewhere(ADMIN_USER_ID, null);
        assertThat(latestHistoryHash()).as("the reset is recorded in the history")
                .isEqualTo(credential().get("password_hash"));

        // A password in the history is refused, and the token stays usable.
        controller.requestPasswordReset(new PasswordController.ResetRequestBody(ADMIN_EMAIL),
                new MockHttpServletRequest("POST", "/auth/password/reset-request"));
        String again = admin.queryForObject("SELECT reset_token FROM user_credential WHERE user_id = ?",
                String.class, ADMIN_USER_ID);
        var reused = controller.resetPassword(new PasswordController.ResetPasswordRequest(again, "Changed-Value-4d5e6f"),
                new MockHttpServletRequest("POST", "/auth/password/reset"));
        assertThat(reused.getStatusCode().value()).isEqualTo(400);
        assertThat(reused.getBody()).containsEntry("error", "Password was recently used");
        assertThat(admin.queryForObject("SELECT reset_token FROM user_credential WHERE user_id = ?",
                String.class, ADMIN_USER_ID)).isEqualTo(again);
    }

    @Test
    @DisplayName("same email in two tenants, both flagged: the forced change and the email-link reset change only the signed-in tenant's user")
    void sameEmailInAnotherTenantIsUntouched() throws Exception {
        String otherTenant = "bbbbbbbb-0000-4000-8000-0000000000b2";
        String otherUser = "bbbbbbbb-0000-4000-8000-0000000000c3";
        String shared = "Initial-Value-1a2b3c";
        boot(shared);
        admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, 'pw-scope-b', 'Tenant B')", otherTenant);
        admin.update("INSERT INTO platform_user (id, tenant_id, email, first_name, last_name, status) "
                + "VALUES (?, ?, ?, 'Other', 'Admin', 'ACTIVE')", otherUser, otherTenant, ADMIN_EMAIL);
        admin.update("INSERT INTO user_credential (id, user_id, password_hash, force_change_on_login) "
                + "VALUES (?, ?, ?, true)", "bbbbbbbb-0000-4000-8000-0000000000d4", otherUser, ENCODER.encode(shared));
        try {
            Map<String, Object> otherBefore = admin.queryForMap(
                    "SELECT password_hash, force_change_on_login FROM user_credential WHERE user_id = ?", otherUser);
            UserSessionRevoker revoker = Mockito.mock(UserSessionRevoker.class);

            // Sign in to the platform tenant: right password, change required.
            MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
            MockHttpSession loginSession = new MockHttpSession();
            loginSession.setAttribute("tenantId", PLATFORM_TENANT_ID);
            login.setSession(loginSession);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(login));
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new KeltaUserDetailsService(app));
            provider.setPasswordEncoder(ENCODER);
            CredentialsExpiredException expired = org.junit.jupiter.api.Assertions.assertThrows(
                    CredentialsExpiredException.class,
                    () -> provider.authenticate(new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, shared)));
            MockHttpServletResponse redirect = new MockHttpServletResponse();
            new LoginFailureHandler().onAuthenticationFailure(login, redirect, expired);
            RequestContextHolder.resetRequestAttributes();
            assertThat(redirect.getRedirectedUrl()).isEqualTo("/change-password");

            var session = login.getSession(false);
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID)).isEqualTo(ADMIN_USER_ID);
            assertThat(session.getAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID))
                    .isEqualTo(PLATFORM_TENANT_ID);
            String view = new ForcePasswordChangeController(app, ENCODER, revoker, policies()).changePassword(
                    shared, "Chosen-In-Tenant-A-77", "Chosen-In-Tenant-A-77", session, new ExtendedModelMap());

            assertThat(view).isEqualTo("redirect:/login?passwordChanged");
            assertThat(signIn("Chosen-In-Tenant-A-77").isAuthenticated()).isTrue();
            assertThat(admin.queryForMap(
                    "SELECT password_hash, force_change_on_login FROM user_credential WHERE user_id = ?", otherUser))
                    .as("the other tenant's user").isEqualTo(otherBefore);
            Mockito.verify(revoker).signOutElsewhere(ADMIN_USER_ID, session.getId());
            Mockito.verify(revoker, Mockito.never()).signOutElsewhere(Mockito.eq(otherUser), Mockito.any());

            // Email-link reset: one token per account; using one resets only that account.
            PasswordController passwords = passwordController(revoker);
            assertThat(passwords.requestPasswordReset(new PasswordController.ResetRequestBody(ADMIN_EMAIL),
                    new MockHttpServletRequest("POST", "/auth/password/reset-request"))
                    .getStatusCode().value()).isEqualTo(200);
            String tokenA = admin.queryForObject("SELECT reset_token FROM user_credential WHERE user_id = ?",
                    String.class, ADMIN_USER_ID);
            String tokenB = admin.queryForObject("SELECT reset_token FROM user_credential WHERE user_id = ?",
                    String.class, otherUser);
            assertThat(tokenA).isNotNull().isNotEqualTo(tokenB);
            assertThat(tokenB).isNotNull();

            var reset = passwords.resetPassword(new PasswordController.ResetPasswordRequest(tokenA, "Reset-In-A-88"),
                    new MockHttpServletRequest("POST", "/auth/password/reset"));

            assertThat(reset.getStatusCode().value()).as("%s", reset.getBody()).isEqualTo(200);
            assertThat(signIn("Reset-In-A-88").isAuthenticated()).isTrue();
            Map<String, Object> otherAfter = admin.queryForMap(
                    "SELECT password_hash, force_change_on_login, reset_token FROM user_credential WHERE user_id = ?",
                    otherUser);
            assertThat(otherAfter.get("password_hash")).isEqualTo(otherBefore.get("password_hash"));
            assertThat(otherAfter.get("force_change_on_login")).isEqualTo(true);
            assertThat(otherAfter.get("reset_token")).as("the other user's token is still unused").isEqualTo(tokenB);
            assertThat(passwords.resetPassword(new PasswordController.ResetPasswordRequest(tokenA, "Reset-Again-99"),
                    new MockHttpServletRequest("POST", "/auth/password/reset")).getStatusCode().value())
                    .as("a token is used once").isEqualTo(400);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            admin.update("DELETE FROM user_credential WHERE user_id = ?", otherUser);
            admin.update("DELETE FROM platform_user WHERE id = ?", otherUser);
            admin.update("DELETE FROM tenant WHERE id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("fresh install: \"password\" stops working and the admin must change the replacement")
    void baselinePasswordNoLongerAuthenticates() {
        assertThat(signIn("password")).as("precondition: the baseline seed").isNotNull();

        boot(null);

        assertThatThrownBy(() -> signIn("password")).isInstanceOf(BadCredentialsException.class);
        Map<String, Object> credential = credential();
        assertThat(credential.get("password_hash")).isNotEqualTo(BASELINE_HASH);
        assertThat(credential.get("force_change_on_login")).isEqualTo(true);
        assertThat(credential.get("password_changed_at")).isNotNull();
    }

    @Test
    @DisplayName("KELTA_BOOTSTRAP_ADMIN_PASSWORD: that value signs in, must be changed, and is never logged")
    void configuredPasswordIsAppliedAndNotLogged() {
        String secret = "Bootstrap-Value-7f3a9c";

        boot(secret);

        assertThatThrownBy(() -> signIn(secret)).isInstanceOf(CredentialsExpiredException.class);
        assertThatThrownBy(() -> signIn("password")).isInstanceOf(BadCredentialsException.class);
        assertThat(credential().get("force_change_on_login")).isEqualTo(true);

        assertThat(banners()).isEmpty();
        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains(ADMIN_EMAIL)
                .contains("KELTA_BOOTSTRAP_ADMIN_PASSWORD")
                .contains("choose a new password"));
        assertThat(logs.events()).noneMatch(e -> e.getFormattedMessage().contains(secret));
    }

    @Test
    @DisplayName("no env var: one banner with a generated password that signs in once and forces a change")
    void generatedPasswordBanner() {
        boot(null);

        assertThat(banners()).hasSize(1);
        assertThat(warnings()).as("the banner is the only WARN").hasSize(1);
        String banner = banners().get(0).getFormattedMessage();
        assertThat(banner).contains(ADMIN_EMAIL).contains("change it on first sign-in");
        Matcher m = BANNER_PASSWORD.matcher(banner);
        assertThat(m.find()).isTrue();
        String generated = m.group(1);
        assertThat(generated.length()).isGreaterThanOrEqualTo(20);

        // Signs in, but only as far as the forced change.
        assertThatThrownBy(() -> signIn(generated)).isInstanceOf(CredentialsExpiredException.class);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_EMAIL, ADMIN_EMAIL);
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_USER_ID, ADMIN_USER_ID);
        session.setAttribute(ForcePasswordChangeController.SESSION_ATTR_TENANT_ID, PLATFORM_TENANT_ID);
        String view = new ForcePasswordChangeController(app, ENCODER, Mockito.mock(UserSessionRevoker.class), policies()).changePassword(
                generated, "Chosen-By-The-Owner-42", "Chosen-By-The-Owner-42", session, new ExtendedModelMap());
        assertThat(view).isEqualTo("redirect:/login?passwordChanged");
        assertThat(signIn("Chosen-By-The-Owner-42").isAuthenticated()).isTrue();
        assertThatThrownBy(() -> signIn(generated)).isInstanceOf(BadCredentialsException.class);

        // Second boot: the hash is no longer the baseline, so nothing is printed.
        logs.close();
        logs = LogCapture.allLoggersOnCurrentThreadOr(RACE_THREAD_PREFIX);
        boot(null);
        assertThat(initializerEvents()).isEmpty();
    }

    @Test
    @DisplayName("second boot before anyone signs in: no banner, the first password still stands")
    void secondBootPrintsNothing() {
        boot(null);
        Object hashAfterFirstBoot = credential().get("password_hash");
        logs.close();
        logs = LogCapture.allLoggersOnCurrentThreadOr(RACE_THREAD_PREFIX);

        boot(null);

        assertThat(initializerEvents()).isEmpty();
        assertThat(credential().get("password_hash")).isEqualTo(hashAfterFirstBoot);
    }

    @Test
    @DisplayName("an admin hash that differs from the baseline is untouched, silently")
    void changedHashIsUntouched() {
        String chosen = ENCODER.encode("an-admin-chosen-password");
        admin.update("UPDATE user_credential SET password_hash = ?, force_change_on_login = false, "
                + "password_changed_at = TIMESTAMPTZ '2026-01-02 03:04:05+00' WHERE user_id = ?",
                chosen, ADMIN_USER_ID);
        Map<String, Object> before = credential();

        boot(null);
        boot("Bootstrap-Value-7f3a9c");

        assertThat(credential()).isEqualTo(before);
        assertThat(initializerEvents()).isEmpty();
    }

    @Test
    @DisplayName("existing install on the baseline: replaced, with one WARN naming the account and the action")
    void existingInstallGetsOneWarning() {
        boot(null);

        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains(ADMIN_EMAIL)
                .contains("Sign in with it")
                .contains("KELTA_BOOTSTRAP_ADMIN_PASSWORD"));
    }

    @Test
    @DisplayName("stored OAuth2 authorizations for the admin are revoked with the swap")
    void authorizationsAreRevoked() {
        admin.update("INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, "
                + "authorization_grant_type) VALUES ('baseline-admin-auth', 'kelta-platform', ?, "
                + "'authorization_code')", ADMIN_EMAIL);

        boot(null);

        assertThat(admin.queryForObject("SELECT count(*) FROM oauth2_authorization WHERE id = 'baseline-admin-auth'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("two replicas booting together: one replacement, one banner")
    void concurrentBootsPrintOneBanner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Thread[] replicas = new Thread[4];
        for (int i = 0; i < replicas.length; i++) {
            replicas[i] = Thread.ofPlatform().name(RACE_THREAD_PREFIX + i).start(() -> {
                try {
                    start.await();
                    boot(null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        for (Thread replica : replicas) {
            replica.join();
        }

        assertThat(banners()).hasSize(1);
        Matcher m = BANNER_PASSWORD.matcher(banners().get(0).getFormattedMessage());
        assertThat(m.find()).isTrue();
        assertThatThrownBy(() -> signIn(m.group(1))).isInstanceOf(CredentialsExpiredException.class);
    }
}
