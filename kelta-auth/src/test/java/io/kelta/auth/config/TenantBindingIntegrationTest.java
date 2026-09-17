package io.kelta.auth.config;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.runtime.context.TenantAwareDataSource;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves that a kelta-auth request scoped to one tenant cannot read another tenant's rows.
 *
 * <p>kelta-auth ran every statement as the platform session: {@code connection-init-sql} set
 * {@code app.current_tenant_id = ''} on every connection, so {@code admin_bypass} matched
 * always and the only thing standing between a login and another tenant's users was the
 * {@code AND tenant_id = ?} in each query. {@link TenantContextFilter} now resolves the
 * request's tenant and {@link TenantAwareDataSourceConfig} binds it to the connection; this
 * test drives a request through the real filter, against a role without {@code BYPASSRLS},
 * and reads {@code platform_user} with no tenant predicate at all.
 *
 * <p>kelta-auth owns no migrations, so the two tables it authenticates against are recreated
 * here with the policy shapes kelta-worker's V200/V202 give them: {@code platform_user} keyed
 * on its own {@code tenant_id}, {@code user_credential} keyed on its parent's. The role
 * pinning of V201 is the control plane's own concern and is covered by
 * {@code RowLevelSecurityIntegrationTest}.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("kelta-auth — the request's tenant reaches the database connection")
class TenantBindingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"));

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000001";

    static JdbcTemplate admin;
    static HikariDataSource pool;
    static JdbcTemplate app;
    static TenantContextFilter filter;

    @BeforeAll
    static void createSchemaAndRole() {
        DriverManagerDataSource superDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        superDs.setDriverClassName("org.postgresql.Driver");
        admin = new JdbcTemplate(superDs);

        admin.execute("""
                CREATE TABLE tenant (
                    id   varchar(36) PRIMARY KEY,
                    slug varchar(63) NOT NULL UNIQUE
                )""");
        admin.execute("""
                CREATE TABLE platform_user (
                    id        varchar(36) PRIMARY KEY,
                    tenant_id varchar(36) NOT NULL REFERENCES tenant(id),
                    email     varchar(320) NOT NULL
                )""");
        admin.execute("""
                CREATE TABLE user_credential (
                    id            varchar(36) PRIMARY KEY,
                    user_id       varchar(36) NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
                    password_hash varchar(500) NOT NULL
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

        admin.update("INSERT INTO tenant (id, slug) VALUES (?, 'tenant-a')", TENANT_A);
        admin.update("INSERT INTO tenant (id, slug) VALUES (?, 'tenant-b')", TENANT_B);
        admin.update("INSERT INTO platform_user (id, tenant_id, email) VALUES ('u-a', ?, ?)",
                TENANT_A, "admin@tenant-a.example");
        admin.update("INSERT INTO platform_user (id, tenant_id, email) VALUES ('u-b', ?, ?)",
                TENANT_B, "admin@tenant-b.example");
        admin.update("INSERT INTO user_credential (id, user_id, password_hash) VALUES ('c-a', 'u-a', 'hash-a')");
        admin.update("INSERT INTO user_credential (id, user_id, password_hash) VALUES ('c-b', 'u-b', 'hash-b')");

        admin.execute("CREATE ROLE auth_rls LOGIN PASSWORD 'auth_rls' NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO auth_rls");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO auth_rls");
        admin.execute("ALTER ROLE auth_rls SET app.current_tenant_id = ''");

        pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("auth_rls");
        pool.setPassword("auth_rls");
        pool.setMaximumPoolSize(1);
        app = new JdbcTemplate(new TenantAwareDataSource(pool));

        AuthDomainResolver domainResolver = mock(AuthDomainResolver.class);
        when(domainResolver.resolveTenantSlug(anyString())).thenReturn(Optional.empty());
        filter = new TenantContextFilter(domainResolver, app);
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * Runs {@code query} inside a request whose session carries {@code sessionTenant} —
     * exactly what /oauth2/authorize and /login leave behind.
     */
    private static List<String> inRequestFor(String sessionTenant, String query) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        if (sessionTenant != null) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("tenantId", sessionTenant);
            request.setSession(session);
        }
        AtomicReference<List<String>> rows = new AtomicReference<>();
        FilterChain chain = (req, res) -> rows.set(app.queryForList(query, String.class));
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return rows.get();
    }

    @Test
    @DisplayName("a login scoped to tenant A cannot see tenant B's users, even with no tenant predicate")
    void loginScopedToOneTenantSeesOnlyItsUsers() throws Exception {
        assertThat(inRequestFor("tenant-a", "SELECT email FROM platform_user ORDER BY 1"))
                .containsExactly("admin@tenant-a.example");
        assertThat(inRequestFor("tenant-b", "SELECT email FROM platform_user ORDER BY 1"))
                .containsExactly("admin@tenant-b.example");
        // The session may hold the UUID rather than the slug; both bind.
        assertThat(inRequestFor(TENANT_A, "SELECT email FROM platform_user ORDER BY 1"))
                .containsExactly("admin@tenant-a.example");
    }

    @Test
    @DisplayName("password hashes follow their user's tenant — user_credential has no tenant_id of its own")
    void credentialsFollowTheirUsersTenant() throws Exception {
        assertThat(inRequestFor("tenant-a", "SELECT password_hash FROM user_credential ORDER BY 1"))
                .containsExactly("hash-a");
        assertThat(inRequestFor("tenant-b", "SELECT password_hash FROM user_credential ORDER BY 1"))
                .containsExactly("hash-b");
    }

    @Test
    @DisplayName("a request with no tenant keeps the platform session the token endpoint needs")
    void requestWithoutATenantStillSeesEverything() throws Exception {
        assertThat(inRequestFor(null, "SELECT email FROM platform_user ORDER BY 1"))
                .containsExactly("admin@tenant-a.example", "admin@tenant-b.example");
    }

    @Test
    @DisplayName("the binding is released with the request — the next borrow is a platform session again")
    void bindingDoesNotLeakToTheNextRequest() throws Exception {
        inRequestFor("tenant-a", "SELECT email FROM platform_user");
        assertThat(app.queryForList("SELECT email FROM platform_user ORDER BY 1", String.class))
                .containsExactly("admin@tenant-a.example", "admin@tenant-b.example");
    }
}
