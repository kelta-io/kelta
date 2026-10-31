package io.kelta.auth.service;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.config.AuthorizationServerConfig;
import io.kelta.auth.config.TenantContextFilter;
import io.kelta.auth.controller.DirectLoginController;
import io.kelta.runtime.context.TenantAwareDataSource;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Failed password sign-ins count against the right account, and only against it.
 *
 * <p>Drives a POST /login the way production does — through {@link TenantContextFilter}, the
 * real {@link KeltaUserDetailsService}, Spring's {@link DaoAuthenticationProvider} and the
 * authentication events it publishes to {@link AuthenticationEventListener} and
 * {@link PasswordPolicyService} — against a role without {@code BYPASSRLS}, with the same
 * email registered in two tenants.
 *
 * <p>kelta-auth owns no migrations, so the tables it authenticates against are recreated here
 * with the columns these classes read and the RLS policy shapes kelta-worker gives them (as in
 * {@code TenantBindingIntegrationTest}).
 *
 * <p>{@code POST /auth/direct-login} goes through the same {@link ProviderManager}, so it is
 * driven here too: its failures count and a lockout refuses it, and an account the browser
 * sign-in would send to an MFA step (a verified TOTP row, read through RLS like the credential,
 * or a tenant policy requiring MFA) gets {@code 401 mfa_required} instead of tokens.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Failed sign-ins — counted per tenant, only for a loaded user, restarted after a lockout")
class FailedSignInLockoutIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:15-alpine"));

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000001";
    static final String SHARED_EMAIL = "member@example.com";
    static final String INACTIVE_EMAIL = "former@example.com";
    static final String SERVICE_EMAIL = "billing.ab12cd34@service.kelta.invalid";
    static final String PASSWORD_A = "Tenant-A-Password-1";
    static final String PASSWORD_B = "Tenant-B-Password-2";
    static final int THRESHOLD = PasswordPolicyService.PasswordPolicy.DEFAULTS.lockoutThreshold();

    static final PasswordEncoder ENCODER = new AuthorizationServerConfig().passwordEncoder();

    static JdbcTemplate admin;
    static HikariDataSource pool;
    static TenantContextFilter filter;
    static ProviderManager authenticationManager;
    static DirectLoginController directLogin;

    @BeforeAll
    static void createSchemaAndRole() {
        DriverManagerDataSource superDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        superDs.setDriverClassName("org.postgresql.Driver");
        admin = new JdbcTemplate(superDs);

        admin.execute("""
                CREATE TABLE tenant (
                    id   varchar(36) PRIMARY KEY,
                    slug varchar(63) NOT NULL UNIQUE,
                    name varchar(200) NOT NULL
                )""");
        admin.execute("CREATE TABLE profile (id varchar(36) PRIMARY KEY, name varchar(100) NOT NULL)");
        admin.execute("""
                CREATE TABLE platform_user (
                    id         varchar(36) PRIMARY KEY,
                    tenant_id  varchar(36) NOT NULL REFERENCES tenant(id),
                    email      varchar(320) NOT NULL,
                    username   varchar(100),
                    first_name varchar(100),
                    last_name  varchar(100),
                    profile_id varchar(36),
                    status     varchar(20) NOT NULL,
                    user_type  varchar(20) NOT NULL DEFAULT 'INTERNAL'
                )""");
        admin.execute("""
                CREATE TABLE user_credential (
                    id                    varchar(36) PRIMARY KEY,
                    user_id               varchar(36) NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
                    password_hash         varchar(500) NOT NULL,
                    password_changed_at   timestamp with time zone,
                    force_change_on_login boolean NOT NULL DEFAULT false,
                    failed_attempts       integer NOT NULL DEFAULT 0,
                    locked_until          timestamp with time zone,
                    updated_at            timestamp with time zone
                )""");
        admin.execute("""
                CREATE TABLE password_policy (
                    tenant_id                varchar(36) NOT NULL,
                    min_length               integer NOT NULL,
                    max_length               integer NOT NULL,
                    require_uppercase        boolean NOT NULL,
                    require_lowercase        boolean NOT NULL,
                    require_digit            boolean NOT NULL,
                    require_special          boolean NOT NULL,
                    history_count            integer NOT NULL,
                    dictionary_check         boolean NOT NULL,
                    personal_data_check      boolean NOT NULL,
                    lockout_threshold        integer NOT NULL,
                    lockout_duration_minutes integer NOT NULL,
                    max_age_days             integer,
                    mfa_required             boolean NOT NULL DEFAULT false
                )""");
        admin.execute("""
                CREATE TABLE user_totp_secret (
                    id                  varchar(36) PRIMARY KEY,
                    user_id             varchar(36) NOT NULL UNIQUE REFERENCES platform_user(id) ON DELETE CASCADE,
                    secret              varchar(500) NOT NULL,
                    verified            boolean NOT NULL DEFAULT false,
                    last_used_at        bigint,
                    mfa_failed_attempts integer NOT NULL DEFAULT 0,
                    mfa_locked_until    timestamp with time zone,
                    updated_at          timestamp with time zone
                )""");

        admin.execute("ALTER TABLE platform_user ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE ONLY platform_user FORCE ROW LEVEL SECURITY");
        admin.execute("""
                CREATE POLICY tenant_isolation ON platform_user
                    USING ((tenant_id)::text = current_setting('app.current_tenant_id', true))""");
        admin.execute("""
                CREATE POLICY admin_bypass ON platform_user
                    USING (current_setting('app.current_tenant_id', true) = '')""");
        admin.execute("ALTER TABLE user_credential ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE ONLY user_credential FORCE ROW LEVEL SECURITY");
        admin.execute("""
                CREATE POLICY tenant_isolation ON user_credential
                    USING (EXISTS (SELECT 1 FROM platform_user p
                                   WHERE p.id = user_credential.user_id
                                     AND (p.tenant_id)::text = current_setting('app.current_tenant_id', true)))""");
        admin.execute("""
                CREATE POLICY admin_bypass ON user_credential
                    USING (current_setting('app.current_tenant_id', true) = '')""");
        admin.execute("ALTER TABLE user_totp_secret ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE ONLY user_totp_secret FORCE ROW LEVEL SECURITY");
        admin.execute("""
                CREATE POLICY tenant_isolation ON user_totp_secret
                    USING (EXISTS (SELECT 1 FROM platform_user p
                                   WHERE p.id = user_totp_secret.user_id
                                     AND (p.tenant_id)::text = current_setting('app.current_tenant_id', true)))""");
        admin.execute("""
                CREATE POLICY admin_bypass ON user_totp_secret
                    USING (current_setting('app.current_tenant_id', true) = '')""");

        admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, 'tenant-a', 'Tenant A')", TENANT_A);
        admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, 'tenant-b', 'Tenant B')", TENANT_B);
        // Direct login puts the profile in the token, so every test user has one.
        admin.update("INSERT INTO profile (id, name) VALUES ('p-test', 'Test Profile')");
        insertUser("u-a", TENANT_A, SHARED_EMAIL, "ACTIVE", PASSWORD_A);
        insertUser("u-b", TENANT_B, SHARED_EMAIL, "ACTIVE", PASSWORD_B);
        insertUser("u-a-former", TENANT_A, INACTIVE_EMAIL, "INACTIVE", PASSWORD_A);
        insertUser("u-b-former", TENANT_B, INACTIVE_EMAIL, "ACTIVE", PASSWORD_B);
        // A module service principal. Real ones never get a credential; this one does, so the
        // test proves the user type alone keeps it off the sign-in form.
        insertUser("u-a-service", TENANT_A, SERVICE_EMAIL, "ACTIVE", PASSWORD_A);
        admin.update("UPDATE platform_user SET user_type = 'SERVICE' WHERE id = 'u-a-service'");

        admin.execute("CREATE ROLE auth_rls LOGIN PASSWORD 'auth_rls' NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO auth_rls");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO auth_rls");
        admin.execute("ALTER ROLE auth_rls SET app.current_tenant_id = ''");

        pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("auth_rls");
        pool.setPassword("auth_rls");
        pool.setMaximumPoolSize(2);
        JdbcTemplate app = new JdbcTemplate(new TenantAwareDataSource(pool));

        AuthDomainResolver domainResolver = mock(AuthDomainResolver.class);
        when(domainResolver.resolveTenantSlug(anyString())).thenReturn(Optional.empty());
        filter = new TenantContextFilter(domainResolver, app);

        PasswordPolicyService policyService =
                new PasswordPolicyService(app, ENCODER, mock(WorkerClient.class), new AuthProperties());
        AuthenticationEventListener listener = new AuthenticationEventListener(policyService, app);

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new KeltaUserDetailsService(app));
        provider.setPasswordEncoder(ENCODER);
        authenticationManager = new ProviderManager(provider);
        authenticationManager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(event -> {
            if (event instanceof AuthenticationFailureBadCredentialsEvent failure) {
                listener.onFailure(failure);
            } else if (event instanceof AuthenticationSuccessEvent success) {
                listener.onSuccess(success);
            }
        }));

        JwtEncoder jwtEncoder = mock(JwtEncoder.class);
        Jwt jwt = Jwt.withTokenValue("token-value").header("alg", "RS256").claim("sub", "user")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
        AuthProperties props = new AuthProperties();
        props.getDirectLogin().setEnabled(true);
        props.setIssuerUri("https://auth.example.com");
        TotpService totpService = new TotpService(app, ENCODER, null, mock(WorkerClient.class));
        directLogin = new DirectLoginController(authenticationManager, jwtEncoder, props,
                totpService, policyService);
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void clearCounters() {
        admin.update("UPDATE user_credential SET failed_attempts = 0, locked_until = NULL");
        admin.update("DELETE FROM user_totp_secret");
        admin.update("DELETE FROM password_policy");
    }

    private static void insertUser(String id, String tenantId, String email, String status, String password) {
        admin.update("INSERT INTO platform_user (id, tenant_id, email, first_name, last_name, profile_id, status) "
                + "VALUES (?, ?, ?, 'Test', 'User', 'p-test', ?)", id, tenantId, email, status);
        admin.update("INSERT INTO user_credential (id, user_id, password_hash) VALUES (?, ?, ?)",
                "c-" + id, id, ENCODER.encode(password));
    }

    /**
     * POSTs /login for {@code email} in a session that carries {@code sessionTenant} (or none),
     * returning the authentication or the exception it failed with.
     */
    private static Object signIn(String sessionTenant, String email, String password) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        if (sessionTenant != null) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("tenantId", sessionTenant);
            request.setSession(session);
        }
        AtomicReference<Object> outcome = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                Authentication result = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(email, password));
                outcome.set(result);
            } catch (AuthenticationException e) {
                outcome.set(e);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return outcome.get();
    }

    /**
     * POSTs /auth/direct-login the way a script does: no session yet (the controller creates it
     * and records the tenant slug), through {@link TenantContextFilter}. Returns the status and
     * the JSON body.
     */
    @SuppressWarnings("unchecked")
    private static Map.Entry<Integer, Map<String, Object>> directSignIn(String tenantSlug, String email,
                                                                       String password) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/direct-login");
        AtomicReference<ResponseEntity<?>> outcome = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                outcome.set(directLogin.login(
                        new DirectLoginController.DirectLoginRequest(email, password, tenantSlug),
                        request.getSession(true), request));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        ResponseEntity<?> response = outcome.get();
        return Map.entry(response.getStatusCode().value(), (Map<String, Object>) response.getBody());
    }

    private static Map<String, Object> credential(String userId) {
        return admin.queryForMap("SELECT failed_attempts, locked_until FROM user_credential WHERE user_id = ?",
                userId);
    }

    @Test
    @DisplayName("wrong passwords on tenant A lock only tenant A's account — the same email on B is untouched")
    void failuresCountOnlyInTheSignInTenant() throws Exception {
        for (int i = 0; i < THRESHOLD; i++) {
            assertThat(signIn("tenant-a", SHARED_EMAIL, "wrong")).isInstanceOf(BadCredentialsException.class);
        }

        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(THRESHOLD);
        assertThat(credential("u-a").get("locked_until")).isNotNull();
        assertThat(signIn("tenant-a", SHARED_EMAIL, PASSWORD_A)).isInstanceOf(LockedException.class);

        assertThat(credential("u-b").get("failed_attempts")).isEqualTo(0);
        assertThat(credential("u-b").get("locked_until")).isNull();
        assertThat(signIn("tenant-b", SHARED_EMAIL, PASSWORD_B))
                .isInstanceOfSatisfying(Authentication.class, a -> assertThat(a.isAuthenticated()).isTrue());
    }

    @Test
    @DisplayName("a sign-in with no tenant context is refused without counting against any account")
    void refusalWithoutTenantContextIsNotCounted() throws Exception {
        for (int i = 0; i < THRESHOLD + 1; i++) {
            assertThat(signIn(null, SHARED_EMAIL, PASSWORD_B)).isInstanceOf(BadCredentialsException.class);
        }

        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(0);
        assertThat(credential("u-b").get("failed_attempts")).isEqualTo(0);
        assertThat(credential("u-b").get("locked_until")).isNull();
    }

    @Test
    @DisplayName("a sign-in to an inactive account is refused without counting against an active one elsewhere")
    void inactiveUserRefusalIsNotCounted() throws Exception {
        for (int i = 0; i < THRESHOLD + 1; i++) {
            assertThat(signIn("tenant-a", INACTIVE_EMAIL, "wrong")).isInstanceOf(BadCredentialsException.class);
        }

        assertThat(credential("u-a-former").get("failed_attempts")).isEqualTo(0);
        assertThat(credential("u-b-former").get("failed_attempts")).isEqualTo(0);
    }

    @Test
    @DisplayName("a SERVICE user cannot sign in interactively, even with a correct password, and is never counted")
    void serviceUserCannotSignIn() throws Exception {
        assertThat(signIn("tenant-a", SERVICE_EMAIL, PASSWORD_A)).isInstanceOf(BadCredentialsException.class);
        for (int i = 0; i < THRESHOLD + 1; i++) {
            assertThat(signIn("tenant-a", SERVICE_EMAIL, "wrong")).isInstanceOf(BadCredentialsException.class);
        }

        assertThat(credential("u-a-service").get("failed_attempts")).isEqualTo(0);
    }

    @Test
    @DisplayName("once a lockout has expired, one more wrong password starts a fresh count instead of re-locking")
    void expiredLockoutRestartsTheCount() throws Exception {
        admin.update("UPDATE user_credential SET failed_attempts = ?, locked_until = now() - interval '1 minute' "
                + "WHERE user_id = 'u-a'", THRESHOLD);

        assertThat(signIn("tenant-a", SHARED_EMAIL, "wrong")).isInstanceOf(BadCredentialsException.class);

        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(1);
        assertThat(credential("u-a").get("locked_until")).isNull();
        assertThat(signIn("tenant-a", SHARED_EMAIL, PASSWORD_A))
                .isInstanceOfSatisfying(Authentication.class, a -> assertThat(a.isAuthenticated()).isTrue());
        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(0);
    }

    @Test
    @DisplayName("an unexpired lockout still holds — the count is not restarted early")
    void activeLockoutIsKept() throws Exception {
        admin.update("UPDATE user_credential SET failed_attempts = ?, locked_until = now() + interval '10 minutes' "
                + "WHERE user_id = 'u-a'", THRESHOLD);

        assertThat(signIn("tenant-a", SHARED_EMAIL, PASSWORD_A)).isInstanceOf(LockedException.class);

        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(THRESHOLD);
        assertThat(credential("u-a").get("locked_until")).isNotNull();
    }

    @Test
    @DisplayName("direct login: wrong passwords count against the sign-in tenant's account and a lockout refuses it")
    void directLoginFailuresCountAndLockoutApplies() throws Exception {
        for (int i = 0; i < THRESHOLD; i++) {
            var refused = directSignIn("tenant-a", SHARED_EMAIL, "wrong");
            assertThat(refused.getKey()).isEqualTo(401);
            assertThat(refused.getValue()).containsEntry("error", "invalid_credentials");
        }

        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(THRESHOLD);
        assertThat(credential("u-a").get("locked_until")).isNotNull();
        var locked = directSignIn("tenant-a", SHARED_EMAIL, PASSWORD_A);
        assertThat(locked.getKey()).isEqualTo(401);
        assertThat(locked.getValue()).containsEntry("error", "invalid_credentials");

        assertThat(credential("u-b").get("failed_attempts")).isEqualTo(0);
        var other = directSignIn("tenant-b", SHARED_EMAIL, PASSWORD_B);
        assertThat(other.getKey()).isEqualTo(200);
        assertThat(other.getValue()).containsKey("access_token");
    }

    @Test
    @DisplayName("direct login refuses an account with a verified TOTP enrollment")
    void directLoginRefusesEnrolledAccount() throws Exception {
        admin.update("INSERT INTO user_totp_secret (id, user_id, secret, verified) VALUES ('t-a', 'u-a', 'x', true)");

        var refused = directSignIn("tenant-a", SHARED_EMAIL, PASSWORD_A);

        assertThat(refused.getKey()).isEqualTo(401);
        assertThat(refused.getValue()).containsEntry("error", "mfa_required").doesNotContainKey("access_token");
        // The password was right: the attempt is not counted as a failure.
        assertThat(credential("u-a").get("failed_attempts")).isEqualTo(0);
        // An unverified (abandoned) enrollment is not an MFA requirement.
        admin.update("UPDATE user_totp_secret SET verified = false WHERE id = 't-a'");
        assertThat(directSignIn("tenant-a", SHARED_EMAIL, PASSWORD_A).getKey()).isEqualTo(200);
    }

    @Test
    @DisplayName("direct login refuses an unenrolled account when its tenant's policy requires MFA — only in that tenant")
    void directLoginRefusesWhenTenantRequiresMfa() throws Exception {
        admin.update("""
                INSERT INTO password_policy (tenant_id, min_length, max_length, require_uppercase,
                    require_lowercase, require_digit, require_special, history_count, dictionary_check,
                    personal_data_check, lockout_threshold, lockout_duration_minutes, mfa_required)
                VALUES (?, 8, 128, false, false, false, false, 0, false, false, 5, 30, true)""", TENANT_B);

        var refused = directSignIn("tenant-b", SHARED_EMAIL, PASSWORD_B);
        assertThat(refused.getKey()).isEqualTo(401);
        assertThat(refused.getValue()).containsEntry("error", "mfa_required");

        assertThat(directSignIn("tenant-a", SHARED_EMAIL, PASSWORD_A).getKey()).isEqualTo(200);
    }
}
