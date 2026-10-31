package io.kelta.worker.config;

import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.module.JdbcModuleStore;
import org.flywaydb.core.Flyway;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V226 is the contract half of the JAR-path removal (module-platform 9g): it drops the JAR columns,
 * their index and {@code tenant_module_signing_key}, which V225 left in place for the previous worker
 * image. It must keep every external install intact, leave V225's guarantees in force, and leave a
 * schema the current {@link JdbcModuleStore} reads and writes.
 *
 * <p>Runs the real migrations against Postgres as the application role (no BYPASSRLS), the way the
 * deploy-time migrate Job does. The methods share one database and run in order. Flyway stops at
 * V226, so a later migration does not change what this test sees.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("V226 — drop the JAR module columns")
class DropJarModuleColumnsMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String EXTERNAL_ROW = "aaaaaaaa-0000-0000-0000-00000000e001";
    static final String BASE_URL = "https://module.example.test";

    static final String[] DROPPED = {"jar_checksum", "jar_size_bytes", "module_class", "s3_key",
            "jar_signature", "jar_signature_key_fingerprint", "last_loaded_at", "load_attempts"};

    /** Container superuser: plants fixtures and inspects the schema. */
    static JdbcTemplate admin;

    @BeforeAll
    static void migrateToV225() {
        admin = new JdbcTemplate(dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector");
        admin.execute("CREATE ROLE app_rls LOGIN PASSWORD 'app_rls' NOBYPASSRLS");
        admin.execute("GRANT ALL ON SCHEMA public TO app_rls");
        admin.execute("ALTER SCHEMA public OWNER TO app_rls");
        admin.execute("ALTER ROLE app_rls SET app.current_tenant_id = ''");

        flyway("225").migrate();

        admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, 'tenant-a', 'tenant-a')", TENANT_A);
        // An external install as the previous image wrote it, its load diagnostics included.
        admin.update("INSERT INTO tenant_module (id, tenant_id, module_id, name, version, source_url, base_url, "
                + "protocol_version, manifest, status, installed_by, last_error, last_error_at, last_loaded_at, "
                + "load_attempts) VALUES (?, ?, 'sample-external', 'Sample', '1.0.0', ?, ?, 1, '{}'::jsonb, "
                + "'DEGRADED', 'admin', 'health probe failed: HTTP 503', NOW(), NOW(), 3)",
                EXTERNAL_ROW, TENANT_A, BASE_URL, BASE_URL);
    }

    @Test
    @Order(1)
    @DisplayName("drops the JAR columns, their index and the signing-key table, and keeps every external install")
    void dropsTheJarSchema() {
        flyway("226").migrate();

        for (String column : DROPPED) {
            assertThat(columnExists(column)).as(column).isFalse();
        }
        for (String column : new String[]{"base_url", "source_url", "protocol_version", "manifest", "status",
                "last_error", "last_error_at", "connected_app_id", "service_user_id", "ui_bundle_sha256",
                "ui_bundle_key"}) {
            assertThat(columnExists(column)).as(column).isTrue();
        }
        assertThat(relationExists("tenant_module_signing_key")).isFalse();
        assertThat(relationExists("idx_tenant_module_signature_key")).isFalse();
        assertThat(relationExists("tenant_module_action")).isTrue();

        var row = admin.queryForMap("SELECT base_url, status, last_error FROM tenant_module WHERE id = ?",
                EXTERNAL_ROW);
        assertThat(row.get("base_url")).isEqualTo(BASE_URL);
        assertThat(row.get("status")).isEqualTo("DEGRADED");
        assertThat(row.get("last_error")).isEqualTo("health probe failed: HTTP 503");
    }

    @Test
    @Order(2)
    @DisplayName("V225's guarantees still hold: base_url NOT NULL and no JAR-only status")
    void expandGuaranteesStillHold() {
        assertThat(admin.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'tenant_module' AND column_name = 'base_url'", String.class)).isEqualTo("NO");
        for (String status : new String[]{"QUARANTINED", "STUB"}) {
            assertThatThrownBy(() -> admin.update("UPDATE tenant_module SET status = ? WHERE id = ?",
                    status, EXTERNAL_ROW)).as(status).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> admin.update("UPDATE tenant_module SET base_url = NULL WHERE id = ?",
                EXTERNAL_ROW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(3)
    @DisplayName("the current JdbcModuleStore installs, reads and updates a module on the contracted schema")
    void currentStoreWorks() {
        JdbcModuleStore store = new JdbcModuleStore(new JdbcTemplate(dataSource("app_rls", "app_rls")));
        String id = "aaaaaaaa-0000-0000-0000-00000000e002";

        store.createModule(new TenantModuleData(id, TENANT_A, "sample-second", "Second", "1.0.0", null,
                BASE_URL, "{}", TenantModuleData.STATUS_INSTALLING, "admin", null, null, List.of(),
                BASE_URL, 1, null, null));
        store.updateStatusWithError(id, TenantModuleData.STATUS_FAILED, "enabled call failed");

        TenantModuleData read = store.findByTenantAndModuleId(TENANT_A, "sample-second").orElseThrow();
        assertThat(read.baseUrl()).isEqualTo(BASE_URL);
        assertThat(read.status()).isEqualTo(TenantModuleData.STATUS_FAILED);
        assertThat(store.findLastError(id)).containsEntry("lastError", "enabled call failed");
        assertThat(store.findByTenant(TENANT_A)).extracting(TenantModuleData::moduleId)
                .containsExactlyInAnyOrder("sample-external", "sample-second");
        assertThat(store.findAllExternal()).hasSize(2);
    }

    private static boolean columnExists(String column) {
        return Boolean.TRUE.equals(admin.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                + "WHERE table_name = 'tenant_module' AND column_name = ?)", Boolean.class, column));
    }

    private static boolean relationExists(String name) {
        return Boolean.TRUE.equals(admin.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "public." + name));
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
