package io.kelta.worker.service;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.runtime.context.TenantAwareDataSource;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.model.system.CoreSystemCollections;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.EnvironmentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An empty sandbox ({@code cloneMetadata=false}) against real migrations, as a role without
 * {@code BYPASSRLS}, through {@link TenantAwareDataSource} — the way the worker runs.
 *
 * <p>The parent holds one custom collection, one global picklist and one flow. The sandbox
 * created empty is ACTIVE at once, records {@code config.cloneMode = EMPTY}, imports nothing,
 * has a different tenant id from the parent, reads none of the parent's rows from its own
 * tenant binding, keeps an admin whose one-time password verifies, and refuses a refresh.
 * The real clone (the parent's three items appearing in a default sandbox) needs the whole
 * stack and is proved by the harness's {@code SandboxEmptyCreateScenarioTest}.
 *
 * <p>Fixtures follow {@link SandboxAdminHardeningRlsIntegrationTest}: the tenant-create call
 * is stubbed to plant what {@code TenantProvisioningHook} seeds. Runs only where Docker is
 * available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Empty sandbox create — under RLS, through TenantAwareDataSource")
class SandboxEmptyCreateRlsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String PARENT = "dddddddd-0000-0000-0000-000000000001";
    static final String PARENT_SLUG = "empty-parent";
    static final String UNUSABLE_HASH = "{noop}!unusable";

    static JdbcTemplate admin;
    static HikariDataSource pool;
    static JdbcTemplate app;

    @BeforeAll
    static void migrateAndSeed() {
        admin = new JdbcTemplate(dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector");
        admin.execute("CREATE ROLE app_rls LOGIN PASSWORD 'app_rls' NOBYPASSRLS");
        admin.execute("GRANT ALL ON SCHEMA public TO app_rls");
        admin.execute("ALTER SCHEMA public OWNER TO app_rls");
        admin.execute("ALTER ROLE app_rls SET app.current_tenant_id = ''");

        Flyway.configure()
                .dataSource(dataSource("app_rls", "app_rls"))
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();

        admin.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", PARENT, PARENT_SLUG, "Parent Co");
        admin.update("INSERT INTO collection (id, name, tenant_id, system_collection) VALUES (?, ?, ?, false)",
                UUID.randomUUID().toString(), "parent_orders", PARENT);
        admin.update("INSERT INTO global_picklist (id, tenant_id, name) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), PARENT, "parent_regions");
        admin.update("INSERT INTO flow (id, tenant_id, name, flow_type, definition) VALUES (?, ?, ?, ?, '{}'::jsonb)",
                UUID.randomUUID().toString(), PARENT, "parent_flow", "AUTOLAUNCHED");

        pool = new HikariDataSource();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("app_rls");
        pool.setPassword("app_rls");
        pool.setMaximumPoolSize(2);
        app = new JdbcTemplate(new TenantAwareDataSource(pool));
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    private static DriverManagerDataSource dataSource(String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static Map<String, Object> seedTenantLikeProvisioningHook(Map<String, Object> data) {
        String tenantId = UUID.randomUUID().toString();
        String slug = (String) data.get("slug");
        String userId = UUID.randomUUID().toString();
        String username = TenantAdminInviteService.seededAdminUsername(slug);
        admin.update("INSERT INTO tenant (id, slug, name, parent_tenant_id) VALUES (?, ?, ?, ?)",
                tenantId, slug, data.get("name"), data.get("parentTenantId"));
        admin.update("INSERT INTO platform_user (id, tenant_id, email, username, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                userId, tenantId, username + "@kelta.local", username);
        admin.update("INSERT INTO user_credential (id, user_id, password_hash, force_change_on_login) "
                + "VALUES (?, ?, ?, TRUE)", UUID.randomUUID().toString(), userId, UNUSABLE_HASH);
        return Map.of("id", tenantId);
    }

    private final PackageService packageService = mock(PackageService.class);
    private final PackageImportService packageImportService = mock(PackageImportService.class);

    private SandboxProvisioningService service() {
        QueryEngine queryEngine = mock(QueryEngine.class);
        when(queryEngine.create(any(), anyMap())).thenAnswer(inv -> seedTenantLikeProvisioningHook(inv.getArgument(1)));
        CollectionRegistry collectionRegistry = mock(CollectionRegistry.class);
        when(collectionRegistry.get("tenants")).thenReturn(CoreSystemCollections.tenants());

        EnvironmentRepository environmentRepository = new EnvironmentRepository(app);
        SandboxEnvironmentService environmentService = mock(SandboxEnvironmentService.class);
        when(environmentService.ensureProductionEnvironment(eq(PARENT), anyString())).thenAnswer(inv ->
                Map.of("id", environmentRepository.findProductionByTenant(PARENT)
                        .map(row -> (String) row.get("id"))
                        .orElseGet(() -> environmentRepository.create(PARENT, "Production", null,
                                "PRODUCTION", null, null, "admin"))));

        return new SandboxProvisioningService(environmentRepository, environmentService, packageService,
                packageImportService, queryEngine, collectionRegistry, mock(PlatformEventPublisher.class),
                new tools.jackson.databind.ObjectMapper(), "", "");
    }

    @Test
    @DisplayName("an empty sandbox is ACTIVE, records cloneMode EMPTY, imports nothing and keeps a working admin")
    void emptySandboxIsActiveWithAdmin() {
        SandboxProvisioningService service = service();
        Map<String, Object> result = TenantContext.callWithTenant(PARENT, PARENT_SLUG, () ->
                service.createSandbox(PARENT, "blank", null, "SANDBOX", "admin", false));

        verifyNoInteractions(packageService, packageImportService);
        assertThat(result).containsEntry("status", "ACTIVE").containsEntry("clone_mode", "EMPTY");

        Map<String, Object> row = admin.queryForMap(
                "SELECT status, config->>'cloneMode' AS clone_mode FROM environment WHERE id = ?",
                result.get("id"));
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("clone_mode", "EMPTY");

        String sandboxSlug = (String) result.get("sandboxSlug");
        String storedHash = admin.queryForObject(
                "SELECT c.password_hash FROM user_credential c JOIN platform_user u ON u.id = c.user_id "
                        + "JOIN tenant t ON t.id = u.tenant_id WHERE t.slug = ? AND u.username = ?",
                String.class, sandboxSlug, sandboxSlug + "-admin");
        assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .matches((String) result.get("adminInitialPassword"), storedHash))
                .as("the returned one-time password signs in")
                .isTrue();
    }

    @Test
    @DisplayName("the empty sandbox is a different tenant and reads none of the parent's rows")
    void emptySandboxIsIsolatedFromParent() {
        SandboxProvisioningService service = service();
        Map<String, Object> result = TenantContext.callWithTenant(PARENT, PARENT_SLUG, () ->
                service.createSandbox(PARENT, "isolated", null, "SANDBOX", "admin", false));
        String sandboxTenantId = (String) result.get("sandbox_tenant_id");
        String sandboxSlug = (String) result.get("sandboxSlug");

        assertThat(sandboxTenantId).isNotBlank().isNotEqualTo(PARENT);

        for (String table : List.of("collection", "global_picklist", "flow")) {
            Integer parentSees = TenantContext.callWithTenant(PARENT, PARENT_SLUG, () ->
                    app.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                            Integer.class, PARENT));
            assertThat(parentSees).as("parent sees its own %s row", table).isEqualTo(1);

            Integer sandboxSees = TenantContext.callWithTenant(sandboxTenantId, sandboxSlug, () ->
                    app.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            assertThat(sandboxSees).as("sandbox has no %s rows — nothing cloned, parent rows hidden", table)
                    .isZero();

            Integer sandboxSeesParent = TenantContext.callWithTenant(sandboxTenantId, sandboxSlug, () ->
                    app.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                            Integer.class, PARENT));
            assertThat(sandboxSeesParent).as("no parent %s row is readable from the sandbox", table).isZero();
        }
    }

    @Test
    @DisplayName("refreshing an empty sandbox is refused and leaves it ACTIVE")
    void refreshOfEmptySandboxIsRefused() {
        SandboxProvisioningService service = service();
        Map<String, Object> result = TenantContext.callWithTenant(PARENT, PARENT_SLUG, () ->
                service.createSandbox(PARENT, "norefresh", null, "SANDBOX", "admin", false));
        String envId = (String) result.get("id");

        assertThatThrownBy(() -> TenantContext.callWithTenant(PARENT, PARENT_SLUG, () ->
                service.refreshSandbox(envId, PARENT)))
                .isInstanceOf(SandboxProvisioningService.EmptySandboxRefreshException.class);

        assertThat(admin.queryForObject("SELECT status FROM environment WHERE id = ?", String.class, envId))
                .isEqualTo("ACTIVE");
        verifyNoInteractions(packageService, packageImportService);
    }
}
