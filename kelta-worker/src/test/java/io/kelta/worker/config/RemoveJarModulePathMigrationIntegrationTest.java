package io.kelta.worker.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V225 removes the JAR module path. It must refuse to run while any {@code tenant_module} row is
 * still a JAR install ({@code base_url IS NULL}) or holds a JAR-only status, so no install is ever
 * silently orphaned — and it must see every tenant's rows even when the migrating session carries a
 * tenant, since {@code tenant_module} has FORCE ROW LEVEL SECURITY.
 *
 * <p>V225 is the expand half of a two-step change: the previous worker image keeps serving during
 * the rollout and reads every JAR column, so V225 drops nothing. The last test checks that the
 * statements that image runs on an external module still work afterwards.
 *
 * <p>Runs the real migrations against Postgres as the application role (no BYPASSRLS), the way the
 * deploy-time migrate Job does. The methods share one database and run in order. Flyway stops at
 * V225, so a later contract migration does not change what this test sees.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("V225 — remove the JAR module path")
class RemoveJarModulePathMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String TENANT_B = "bbbbbbbb-0000-0000-0000-000000000001";
    static final String JAR_ROW = "aaaaaaaa-0000-0000-0000-00000000d001";
    static final String EXTERNAL_ROW = "aaaaaaaa-0000-0000-0000-00000000d002";

    /** Container superuser: plants fixtures and inspects the schema. */
    static JdbcTemplate admin;

    @BeforeAll
    static void migrateToV224() {
        admin = new JdbcTemplate(dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector");
        admin.execute("CREATE ROLE app_rls LOGIN PASSWORD 'app_rls' NOBYPASSRLS");
        admin.execute("GRANT ALL ON SCHEMA public TO app_rls");
        admin.execute("ALTER SCHEMA public OWNER TO app_rls");
        admin.execute("ALTER ROLE app_rls SET app.current_tenant_id = ''");

        flyway("224").migrate();

        for (String[] t : new String[][]{{TENANT_A, "tenant-a"}, {TENANT_B, "tenant-b"}}) {
            admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", t[0], t[1], t[1]);
        }
        // An external install, which V225 must leave alone.
        admin.update("INSERT INTO tenant_module (id, tenant_id, module_id, name, version, source_url, base_url, "
                + "protocol_version, manifest, status, installed_by) VALUES (?, ?, 'sample-external', 'Sample', "
                + "'1.0.0', 'https://module.example.test', 'https://module.example.test', 1, '{}'::jsonb, "
                + "'ACTIVE', 'admin')", EXTERNAL_ROW, TENANT_A);
    }

    @Test
    @Order(1)
    @DisplayName("a JAR install (base_url IS NULL) fails the migration, even from a session bound to another tenant")
    void refusesWhileAJarInstallRemains() {
        admin.update("INSERT INTO tenant_module (id, tenant_id, module_id, name, version, source_url, jar_checksum, "
                + "module_class, s3_key, manifest, status, installed_by) VALUES (?, ?, 'sample-jar', 'Sample JAR', "
                + "'1.0.0', 'local://upload', 'abc', 'x.Sample', 'modules/sample.jar', '{}'::jsonb, 'ACTIVE', "
                + "'admin')", JAR_ROW, TENANT_A);
        // A session that carries tenant B: without the explicit bypass the guard would see none of
        // tenant A's rows and pass for the wrong reason.
        admin.execute("ALTER ROLE app_rls SET app.current_tenant_id = '" + TENANT_B + "'");
        try {
            assertThatThrownBy(() -> flyway("225").migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V225: 1 tenant_module row(s) are still JAR module installs")
                    .hasMessageContaining(TENANT_A + "/sample-jar");
        } finally {
            admin.execute("ALTER ROLE app_rls SET app.current_tenant_id = ''");
        }

        // Rolled back: the JAR columns and the row are still there.
        assertThat(columnExists("jar_checksum")).isTrue();
        assertThat(admin.queryForObject("SELECT count(*) FROM tenant_module", Integer.class)).isEqualTo(2);
    }

    @Test
    @Order(2)
    @DisplayName("a row left in a JAR-only status (QUARANTINED, STUB) fails the migration too")
    void refusesAJarOnlyStatus() {
        // Moved out of process, but still QUARANTINED.
        admin.update("UPDATE tenant_module SET base_url = 'https://module.example.test', status = 'QUARANTINED' "
                + "WHERE id = ?", JAR_ROW);

        assertThatThrownBy(() -> flyway("225").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V225: 1 tenant_module row(s) hold a JAR-only status")
                .hasMessageContaining(TENANT_A + "/sample-jar (QUARANTINED)");
        assertThat(columnExists("jar_checksum")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("with no JAR install left it adds the guarantees, drops nothing, and keeps every external install")
    void addsTheGuaranteesAndDropsNothing() {
        admin.update("DELETE FROM tenant_module WHERE id = ?", JAR_ROW);

        flyway("225").migrate();

        // Deferred to the contract migration: the previous image still reads all of these.
        for (String column : new String[]{"jar_checksum", "jar_size_bytes", "module_class", "s3_key",
                "jar_signature", "jar_signature_key_fingerprint", "last_loaded_at", "load_attempts",
                "base_url", "source_url", "last_error", "last_error_at", "connected_app_id",
                "service_user_id", "ui_bundle_sha256"}) {
            assertThat(columnExists(column)).as(column).isTrue();
        }
        assertThat(admin.queryForObject("SELECT to_regclass('public.tenant_module_signing_key') IS NOT NULL",
                Boolean.class)).isTrue();
        assertThat(admin.queryForObject("SELECT to_regclass('public.idx_tenant_module_signature_key') IS NOT NULL",
                Boolean.class)).isTrue();
        assertThat(admin.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'tenant_module' AND column_name = 'base_url'", String.class)).isEqualTo("NO");
        assertThat(admin.queryForObject("SELECT status FROM tenant_module WHERE id = ?", String.class, EXTERNAL_ROW))
                .isEqualTo("ACTIVE");

        assertThatThrownBy(() -> admin.update("UPDATE tenant_module SET status = 'QUARANTINED' WHERE id = ?",
                EXTERNAL_ROW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> admin.update("UPDATE tenant_module SET status = 'STUB' WHERE id = ?",
                EXTERNAL_ROW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> admin.update("UPDATE tenant_module SET base_url = NULL WHERE id = ?",
                EXTERNAL_ROW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(4)
    @DisplayName("the previous image's statements on an external module still run after V225; its JAR install does not")
    void previousImageStatementsStillRun() {
        JdbcTemplate app = new JdbcTemplate(dataSource("app_rls", "app_rls"));
        String installed = "aaaaaaaa-0000-0000-0000-00000000d003";

        // Its install INSERT lists the JAR columns (null for an external module).
        app.update("""
                INSERT INTO tenant_module
                    (id, tenant_id, module_id, name, version, description, source_url,
                     jar_checksum, jar_size_bytes, module_class, manifest, status,
                     installed_by, s3_key, base_url, protocol_version, connected_app_id,
                     service_user_id, installed_at, updated_at)
                VALUES (?, ?, 'sample-second', 'Second', '1.0.0', null, 'https://module.example.test',
                        null, null, null, '{}'::jsonb, 'INSTALLING', 'admin', null,
                        'https://module.example.test', 1, null, null, NOW(), NOW())
                """, installed, TENANT_A);
        // Its row mapper reads every JAR column by name after SELECT *.
        app.query("SELECT * FROM tenant_module WHERE id = ?", rs -> {
            for (String column : new String[]{"jar_checksum", "jar_size_bytes", "module_class", "s3_key"}) {
                rs.getString(column);
            }
        }, installed);
        // Its module list and health read the load diagnostics.
        assertThat(app.queryForList("SELECT last_error, last_error_at, last_loaded_at, load_attempts "
                + "FROM tenant_module WHERE id = ?", installed)).hasSize(1);
        // The statuses it writes for an external module are all still allowed.
        for (String status : new String[]{"ACTIVE", "DEGRADED", "FAILED", "DISABLED", "UNINSTALLING"}) {
            app.update("UPDATE tenant_module SET status = ? WHERE id = ?", status, installed);
        }

        // Its JAR install writes base_url NULL, which V225 now refuses: no new JAR row mid-rollout.
        assertThatThrownBy(() -> app.update("INSERT INTO tenant_module (id, tenant_id, module_id, name, version, "
                + "source_url, jar_checksum, module_class, s3_key, manifest, status, installed_by) VALUES (?, ?, "
                + "'sample-jar-late', 'Late JAR', '1.0.0', 'local://upload', 'abc', 'x.Sample', "
                + "'modules/sample.jar', '{}'::jsonb, 'INSTALLED', 'admin')",
                "aaaaaaaa-0000-0000-0000-00000000d004", TENANT_A))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static boolean columnExists(String column) {
        return Boolean.TRUE.equals(admin.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                + "WHERE table_name = 'tenant_module' AND column_name = ?)", Boolean.class, column));
    }

    /** Flyway as the application role, the way the migrate Job runs it, stopping at {@code target}. */
    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource("app_rls", "app_rls"))
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .target(target)
                .load();
    }

    private static DriverManagerDataSource dataSource(String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }
}
