package io.kelta.worker.config;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.config.TenantAwareDataSourceConfig.TenantAwareDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the control plane's row-level security actually isolates tenants once the
 * application role stops bypassing it.
 *
 * <p>Every other test in this repository talks to Postgres as the container's superuser (or
 * to H2), and a superuser — like a role with {@code BYPASSRLS}, which is what production ran
 * with until 2026-09 — never evaluates a policy. This test runs the real migrations, creates a
 * role with {@code NOBYPASSRLS} and exactly the privileges the application needs, and drives
 * queries through {@link TenantAwareDataSource} the way the worker does: a tenant in
 * {@link TenantContext} becomes {@code SET LOCAL app.current_tenant_id}, no tenant becomes the
 * {@code ''} sentinel that selects the {@code admin_bypass} policy.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Row-level security — enforced for a role without BYPASSRLS, through TenantAwareDataSource")
class RowLevelSecurityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    /** The platform tenant that owns every system collection (V1 baseline). */
    static final String PLATFORM = "00000000-0000-0000-0000-000000000001";
    /** {@code __control-plane}, a system collection seeded by the baseline. */
    static final String SYSTEM_COLLECTION = "00000000-0000-0000-0000-000000000100";

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000001";
    static final String COLLECTION_A = "aaaaaaaa-0000-0000-0000-00000000c001";
    static final String COLLECTION_B = "bbbbbbbb-0000-0000-0000-00000000c001";
    static final String FIELD_SYSTEM = "00000000-0000-0000-0000-00000000f100";
    static final String FIELD_A = "aaaaaaaa-0000-0000-0000-00000000f001";
    static final String FIELD_B = "bbbbbbbb-0000-0000-0000-00000000f001";
    static final String LAYOUT_A = "aaaaaaaa-0000-0000-0000-00000000a001";
    static final String LAYOUT_B = "bbbbbbbb-0000-0000-0000-00000000a001";
    static final String USER_A = "aaaaaaaa-0000-0000-0000-00000000e001";
    static final String USER_B = "bbbbbbbb-0000-0000-0000-00000000e001";
    static final String PAGE_A = "aaaaaaaa-0000-0000-0000-00000000d001";
    static final String PAGE_B = "bbbbbbbb-0000-0000-0000-00000000d001";

    /** Container superuser: runs the migrations and plants the fixtures (bypasses RLS). */
    static JdbcTemplate admin;
    /** The application role, without BYPASSRLS, wrapped exactly as the worker wraps it. */
    static DataSource appDataSource;
    static JdbcTemplate app;

    @BeforeAll
    static void migrateAndSeed() {
        DriverManagerDataSource superDs = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(superDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();
        admin = new JdbcTemplate(superDs);

        admin.execute("CREATE ROLE app_rls LOGIN PASSWORD 'app_rls' NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO app_rls");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_rls");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_rls");
        // The production role default: a session that never binds a tenant is a platform
        // session (admin_bypass), not a session that sees nothing.
        admin.execute("ALTER ROLE app_rls SET app.current_tenant_id = ''");

        for (String[] t : new String[][]{{TENANT_A, "tenant-a"}, {TENANT_B, "tenant-b"}}) {
            admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", t[0], t[1], t[1]);
        }
        admin.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                COLLECTION_A, "orders", TENANT_A);
        admin.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                COLLECTION_B, "orders", TENANT_B);
        admin.update("INSERT INTO field (id, collection_id, name, type, tenant_id) VALUES (?, ?, ?, 'STRING', ?)",
                FIELD_SYSTEM, SYSTEM_COLLECTION, "label", PLATFORM);
        admin.update("INSERT INTO field (id, collection_id, name, type, tenant_id) VALUES (?, ?, ?, 'STRING', ?)",
                FIELD_A, COLLECTION_A, "amount", TENANT_A);
        admin.update("INSERT INTO field (id, collection_id, name, type, tenant_id) VALUES (?, ?, ?, 'STRING', ?)",
                FIELD_B, COLLECTION_B, "amount", TENANT_B);
        admin.update("INSERT INTO page_layout (id, tenant_id, collection_id, name) VALUES (?, ?, ?, ?)",
                LAYOUT_A, TENANT_A, COLLECTION_A, "Order");
        admin.update("INSERT INTO page_layout (id, tenant_id, collection_id, name) VALUES (?, ?, ?, ?)",
                LAYOUT_B, TENANT_B, COLLECTION_B, "Order");
        admin.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)",
                USER_A, TENANT_A, "alice@tenant-a.example");
        admin.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)",
                USER_B, TENANT_B, "bob@tenant-b.example");
        admin.update("INSERT INTO ui_page (id, tenant_id, name, path, slug) VALUES (?, ?, ?, ?, ?)",
                PAGE_A, TENANT_A, "Home", "/p/home", "home");
        admin.update("INSERT INTO ui_page (id, tenant_id, name, path, slug) VALUES (?, ?, ?, ?, ?)",
                PAGE_B, TENANT_B, "Home", "/p/home", "home");

        // A direct per-tenant login of the kind SupersetDatabaseUserService creates: public SELECT,
        // no wrapper, and pinned to tenant A through tenant_db_role (V201) rather than the GUC.
        admin.execute("CREATE ROLE pinned_a LOGIN PASSWORD 'pinned_a' NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO pinned_a");
        admin.execute("GRANT SELECT ON page_layout, platform_user, ui_page, collection, field TO pinned_a");
        admin.update("INSERT INTO tenant_db_role (role_name, tenant_id) VALUES ('pinned_a', ?)", TENANT_A);

        // One pooled physical connection, reused by every borrow — the shape in which a
        // tenant setting could leak from one operation to the next.
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("app_rls");
        pool.setPassword("app_rls");
        pool.setMaximumPoolSize(1);
        appDataSource = new TenantAwareDataSource(pool);
        app = new JdbcTemplate(appDataSource);
    }

    @AfterAll
    static void closePool() {
        if (appDataSource instanceof TenantAwareDataSource wrapped) {
            try {
                wrapped.unwrap(HikariDataSource.class).close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static DriverManagerDataSource dataSource(String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static <T> T asTenant(String tenantId, java.util.function.Supplier<T> op) {
        return TenantContext.callWithTenant(tenantId, op::get);
    }

    private static List<String> ids(String sql, Object... args) {
        return app.queryForList(sql, String.class, args).stream().sorted().toList();
    }

    // ------------------------------------------------------------------ preconditions

    @Test
    @DisplayName("the application role really is subject to RLS (the superuser that seeded the data is not)")
    void roleIsSubjectToRls() {
        Boolean appBypasses = app.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
        Boolean adminBypasses = admin.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(appBypasses).isFalse();
        assertThat(adminBypasses).isTrue();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE tablename IN ('page_layout','platform_user','ui_page','collection','field')",
                Integer.class)).isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("every table with a tenant_id column has RLS enabled, forced, and both policies")
    void everyTenantScopedTableIsCovered() {
        List<String> uncovered = admin.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  -- tenant_db_role is the pin itself: platform-owned, PUBLIC revoked, read only
                  -- through kelta_pinned_tenant() (a policy on it would recurse into that function)
                  AND c.relname <> 'tenant_db_role'
                  AND EXISTS (SELECT 1 FROM information_schema.columns col
                              WHERE col.table_schema = 'public' AND col.table_name = c.relname
                                AND col.column_name = 'tenant_id')
                  AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                       OR NOT EXISTS (SELECT 1 FROM pg_policies p
                                      WHERE p.tablename = c.relname AND p.policyname = 'tenant_isolation')
                       OR NOT EXISTS (SELECT 1 FROM pg_policies p
                                      WHERE p.tablename = c.relname AND p.policyname = 'admin_bypass'))
                ORDER BY 1
                """, String.class);
        assertThat(uncovered)
                .as("tables with a tenant_id column but no enforced RLS policy pair — add them to the migration")
                .isEmpty();
        // Sanity: the check is not vacuous.
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity", Integer.class))
                .isGreaterThan(120);
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("a tenant sees only its own rows on every tenant-scoped table")
    void tenantSeesOnlyItsOwnRows() {
        assertThat(asTenant(TENANT_A, () -> ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B)))
                .containsExactly(LAYOUT_A);
        assertThat(asTenant(TENANT_B, () -> ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B)))
                .containsExactly(LAYOUT_B);
        assertThat(asTenant(TENANT_A, () -> ids("SELECT email FROM platform_user WHERE id IN (?, ?)", USER_A, USER_B)))
                .containsExactly("alice@tenant-a.example");
        assertThat(asTenant(TENANT_B, () -> ids("SELECT id FROM ui_page WHERE slug = 'home'")))
                .containsExactly(PAGE_B);
        // The exact cross-tenant read observed in production: a page resolved by slug alone.
        assertThat(asTenant(TENANT_A, () -> app.queryForObject(
                "SELECT tenant_id FROM ui_page WHERE slug = 'home' LIMIT 1", String.class)))
                .isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("no tenant in context is the platform session: the '' sentinel sees everything")
    void noTenantContextSeesEverything() {
        assertThat(ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B))
                .containsExactly(LAYOUT_A, LAYOUT_B);
        assertThat(app.queryForObject("SELECT current_setting('app.current_tenant_id', true)", String.class))
                .isEmpty();
    }

    @Test
    @DisplayName("system collections and their fields are readable by every tenant, other tenants' are not")
    void systemRowsReadableByEveryTenant() {
        assertThat(asTenant(TENANT_A, () -> ids("SELECT id FROM collection WHERE id IN (?, ?, ?)",
                SYSTEM_COLLECTION, COLLECTION_A, COLLECTION_B)))
                .containsExactly(SYSTEM_COLLECTION, COLLECTION_A);
        assertThat(asTenant(TENANT_B, () -> ids("SELECT id FROM field WHERE id IN (?, ?, ?)",
                FIELD_SYSTEM, FIELD_A, FIELD_B)))
                .containsExactly(FIELD_SYSTEM, FIELD_B);
        // The by-name lookup the layout tree endpoint makes (kelta#1535) still finds the system row.
        assertThat(asTenant(TENANT_A, () -> app.queryForObject(
                "SELECT count(*) FROM collection WHERE name = '__control-plane' AND system_collection = true",
                Integer.class))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ writes

    @Test
    @DisplayName("system rows are readable under a tenant but not writable: the update touches nothing")
    void systemRowsAreNotWritableUnderATenant() {
        int collections = asTenant(TENANT_A, () -> app.update(
                "UPDATE collection SET description = 'tampered' WHERE id = ?", SYSTEM_COLLECTION));
        int fields = asTenant(TENANT_A, () -> app.update(
                "UPDATE field SET description = 'tampered' WHERE id = ?", FIELD_SYSTEM));
        assertThat(collections).isZero();
        assertThat(fields).isZero();
        assertThat(admin.queryForObject("SELECT description FROM field WHERE id = ?", String.class, FIELD_SYSTEM))
                .isNull();
    }

    @Test
    @DisplayName("a tenant can neither update another tenant's row nor insert a row into another tenant")
    void tenantCannotWriteAcrossTenants() {
        int updated = asTenant(TENANT_A, () -> app.update(
                "UPDATE page_layout SET name = 'hacked' WHERE id = ?", LAYOUT_B));
        assertThat(updated).isZero();
        assertThat(admin.queryForObject("SELECT name FROM page_layout WHERE id = ?", String.class, LAYOUT_B))
                .isEqualTo("Order");

        String smuggled = UUID.randomUUID().toString();
        assertThatThrownBy(() -> asTenant(TENANT_A, () -> app.update(
                "INSERT INTO page_layout (id, tenant_id, collection_id, name) VALUES (?, ?, ?, ?)",
                smuggled, TENANT_B, COLLECTION_B, "smuggled")))
                .rootCause().hasMessageContaining("violates row-level security policy");
        assertThat(admin.queryForObject("SELECT count(*) FROM page_layout WHERE id = ?", Integer.class, smuggled))
                .isZero();

        // Its own tenant is writable as before.
        String own = UUID.randomUUID().toString();
        int inserted = asTenant(TENANT_A, () -> app.update(
                "INSERT INTO page_layout (id, tenant_id, collection_id, name) VALUES (?, ?, ?, ?)",
                own, TENANT_A, COLLECTION_A, "Order (edit)"));
        assertThat(inserted).isEqualTo(1);
        admin.update("DELETE FROM page_layout WHERE id = ?", own);
    }

    // ------------------------------------------------------------------ pinned direct logins

    @Test
    @DisplayName("a role pinned in tenant_db_role cannot hop tenants by setting the GUC — SET '' or another id changes nothing")
    void pinnedRoleCannotHopTenantsBySettingTheGuc() {
        SingleConnectionDataSource direct = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "pinned_a", "pinned_a", true);
        direct.setDriverClassName("org.postgresql.Driver");
        try {
            JdbcTemplate pinned = new JdbcTemplate(direct);
            // No GUC at all: the pin alone scopes the session.
            assertThat(pinned.queryForObject("SELECT current_setting('app.current_tenant_id', true)", String.class))
                    .satisfiesAnyOf(v -> assertThat(v).isNull(), v -> assertThat(v).isEmpty());
            assertThat(pinned.queryForList("SELECT id FROM page_layout WHERE id IN (?, ?)", String.class, LAYOUT_A, LAYOUT_B))
                    .containsExactly(LAYOUT_A);

            // The escape hatch that works for an unpinned role: blank the setting (admin_bypass) …
            pinned.execute("SET app.current_tenant_id = ''");
            assertThat(pinned.queryForList("SELECT email FROM platform_user WHERE id IN (?, ?)", String.class, USER_A, USER_B))
                    .containsExactly("alice@tenant-a.example");
            // … or name another tenant outright.
            pinned.execute("SET app.current_tenant_id = '" + TENANT_B + "'");
            assertThat(pinned.queryForList("SELECT id FROM ui_page WHERE id IN (?, ?)", String.class, PAGE_A, PAGE_B))
                    .containsExactly(PAGE_A);
            assertThat(pinned.queryForList("SELECT id FROM page_layout WHERE id IN (?, ?)", String.class, LAYOUT_A, LAYOUT_B))
                    .containsExactly(LAYOUT_A);

            // Shared system rows stay readable; the mapping table itself is not.
            assertThat(pinned.queryForList("SELECT id FROM collection WHERE id IN (?, ?, ?)", String.class,
                    SYSTEM_COLLECTION, COLLECTION_A, COLLECTION_B))
                    .containsExactlyInAnyOrder(SYSTEM_COLLECTION, COLLECTION_A);
            assertThatThrownBy(() -> pinned.queryForObject("SELECT count(*) FROM tenant_db_role", Integer.class))
                    .rootCause().hasMessageContaining("permission denied");
        } finally {
            direct.destroy();
        }
    }

    @Test
    @DisplayName("the application role is unpinned: the GUC still drives it exactly as before")
    void unpinnedApplicationRoleStillFollowsTheGuc() {
        assertThat(app.queryForObject("SELECT kelta_pinned_tenant()", String.class)).isNull();
        assertThat(asTenant(TENANT_B, () -> ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B)))
                .containsExactly(LAYOUT_B);
        assertThat(ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B))
                .containsExactly(LAYOUT_A, LAYOUT_B);
    }

    // ------------------------------------------------------------------ transaction paths

    @Test
    @DisplayName("the scope holds inside a Spring-managed transaction, and does not leak to the next borrow")
    void springTransactionKeepsTheScopeAndReleasesIt() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        List<String> inside = asTenant(TENANT_B, () -> tx.execute(status ->
                ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B)));
        assertThat(inside).containsExactly(LAYOUT_B);

        // Back on the no-tenant path, the same pool of connections is a platform session again.
        assertThat(ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B))
                .containsExactly(LAYOUT_A, LAYOUT_B);
        assertThat(asTenant(TENANT_A, () -> ids("SELECT id FROM page_layout WHERE id IN (?, ?)", LAYOUT_A, LAYOUT_B)))
                .containsExactly(LAYOUT_A);
    }
}
