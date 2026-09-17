package io.kelta.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.runtime.context.TenantAwareDataSource;
import io.kelta.runtime.context.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that a kelta-ai request scoped to one tenant cannot read another tenant's rows.
 *
 * <p>Both halves of that claim are new. The {@code ai_*} tables have carried a
 * {@code tenant_isolation} policy since V1 but kelta-ai owns them and they were not FORCE'd,
 * so the policy never ran; and every connection was pinned to the platform sentinel by
 * {@code connection-init-sql}, so even a forced policy would have matched
 * {@code admin_bypass} on every query. V6 forces the policies and
 * {@link TenantAwareDataSourceConfig} binds the request's tenant — this test drives both,
 * through the same {@link TenantAwareDataSource} the service registers, as a role without
 * {@code BYPASSRLS}.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("kelta-ai — the request's tenant reaches the database connection")
class TenantBindingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"));

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000001";

    static JdbcTemplate admin;
    static HikariDataSource pool;
    static JdbcTemplate app;

    @BeforeAll
    static void migrateAndSeed() {
        DriverManagerDataSource superDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        superDs.setDriverClassName("org.postgresql.Driver");
        Flyway.configure()
                .dataSource(superDs)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        admin = new JdbcTemplate(superDs);

        admin.execute("CREATE ROLE ai_rls LOGIN PASSWORD 'ai_rls' NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO ai_rls");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ai_rls");
        // The production role default: no tenant bound is a platform session, not a blind one.
        admin.execute("ALTER ROLE ai_rls SET app.current_tenant_id = ''");

        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            admin.update("INSERT INTO ai_conversation (tenant_id, user_id, title) VALUES (?, ?, ?)",
                    tenant, "user@" + tenant, "conversation for " + tenant);
            admin.update("INSERT INTO ai_config (tenant_id, config_key, config_value) VALUES (?, ?, ?)",
                    tenant, "model", "model-" + tenant);
        }

        pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("ai_rls");
        pool.setPassword("ai_rls");
        pool.setMaximumPoolSize(1);
        app = new JdbcTemplate(new TenantAwareDataSource(pool));
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    private static <T> T asTenant(String tenantId, java.util.function.Supplier<T> op) {
        return TenantContext.callWithTenant(tenantId, op::get);
    }

    @Test
    @DisplayName("the policies are actually enforced for the role kelta-ai connects as")
    void policiesAreEnforced() {
        assertThat(app.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .isFalse();
        assertThat(admin.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname IN ('ai_conversation', 'ai_message', 'ai_token_usage',
                                    'ai_config', 'ai_agent', 'ai_agent_execution')
                  AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                       OR NOT EXISTS (SELECT 1 FROM pg_policies p
                                      WHERE p.tablename = c.relname AND p.policyname = 'tenant_isolation')
                       OR NOT EXISTS (SELECT 1 FROM pg_policies p
                                      WHERE p.tablename = c.relname AND p.policyname = 'admin_bypass'))
                ORDER BY 1
                """, String.class))
                .as("an owner is exempt from a policy that is merely ENABLEd, and a table with "
                        + "no admin_bypass returns nothing to the platform session")
                .isEmpty();
    }

    @Test
    @DisplayName("a request scoped to tenant A reads only tenant A's conversations and config")
    void requestScopedToATenantSeesOnlyItsOwnRows() {
        assertThat(asTenant(TENANT_A, () ->
                app.queryForList("SELECT tenant_id FROM ai_conversation", String.class)))
                .containsExactly(TENANT_A);
        assertThat(asTenant(TENANT_B, () ->
                app.queryForList("SELECT config_value FROM ai_config WHERE config_key = 'model'", String.class)))
                .containsExactly("model-" + TENANT_B);

        // The unqualified read that used to return every tenant's rows.
        assertThat(asTenant(TENANT_A, () ->
                app.queryForObject("SELECT count(*) FROM ai_conversation", Integer.class)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a request cannot write a row into another tenant")
    void cannotWriteAcrossTenants() {
        assertThatThrownBy(() -> asTenant(TENANT_A, () -> app.update(
                "INSERT INTO ai_conversation (id, tenant_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), TENANT_B, "attacker")))
                .rootCause().hasMessageContaining("violates row-level security policy");

        assertThat(asTenant(TENANT_A, () -> app.update(
                "UPDATE ai_config SET config_value = 'stolen' WHERE tenant_id = ?", TENANT_B)))
                .isZero();
        assertThat(admin.queryForObject(
                "SELECT config_value FROM ai_config WHERE tenant_id = ? AND config_key = 'model'",
                String.class, TENANT_B))
                .isEqualTo("model-" + TENANT_B);
    }

    @Test
    @DisplayName("a request with no tenant is still the platform session — Flyway and startup are unaffected")
    void noTenantIsThePlatformSession() {
        assertThat(app.queryForList("SELECT tenant_id FROM ai_conversation", String.class))
                .containsExactlyInAnyOrder(TENANT_A, TENANT_B);
    }
}
