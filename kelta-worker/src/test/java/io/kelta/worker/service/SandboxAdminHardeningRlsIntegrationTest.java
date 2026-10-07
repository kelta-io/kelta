package io.kelta.worker.service;

import com.zaxxer.hikari.HikariDataSource;
import io.kelta.runtime.context.TenantAwareDataSource;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the sandbox admin's one-time credential is actually written when the worker connects
 * as a role without {@code BYPASSRLS}, which is how production runs.
 *
 * <p>{@link SandboxProvisioningService#createSandbox} is called on the request thread, where
 * {@link TenantContext} holds the <em>parent</em> tenant. The seeded admin belongs to the
 * sandbox tenant, so an UPDATE issued under the parent's binding sees no {@code platform_user}
 * row and changes nothing — the caller got a password that never logs in (KLT-417). The
 * harness's {@code SandboxAdminLoginScenarioTest} could not catch it: its service containers
 * connect as the Postgres superuser, which never evaluates a policy.
 *
 * <p>Modelled on {@code RowLevelSecurityIntegrationTest}: real migrations as a NOBYPASSRLS
 * role, the service's queries going through {@link TenantAwareDataSource}. The tenant-create
 * call is stubbed to plant what {@code TenantProvisioningHook} seeds (tenant row, admin user,
 * unusable credential) so the test exercises only the provisioning service's own writes.
 * Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Sandbox admin hardening — under RLS, through TenantAwareDataSource, parent tenant bound")
class SandboxAdminHardeningRlsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String PARENT = "cccccccc-0000-0000-0000-000000000001";
    static final String PARENT_SLUG = "parent-co";
    static final String UNUSABLE_HASH = "{noop}!unusable";

    /** Container superuser: runs setup and plants the fixtures (bypasses RLS). */
    static JdbcTemplate admin;
    static HikariDataSource pool;
    /** The application role, without BYPASSRLS, wrapped exactly as the worker wraps it. */
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

    /** Plants what TenantProvisioningHook seeds for a new tenant, as the superuser. */
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

    private SandboxProvisioningService service() {
        QueryEngine queryEngine = mock(QueryEngine.class);
        when(queryEngine.create(any(), anyMap())).thenAnswer(inv -> seedTenantLikeProvisioningHook(inv.getArgument(1)));
        CollectionRegistry collectionRegistry = mock(CollectionRegistry.class);
        when(collectionRegistry.get("tenants")).thenReturn(SystemCollectionDefinitions.tenants());

        EnvironmentRepository environmentRepository = new EnvironmentRepository(app);
        SandboxEnvironmentService environmentService = mock(SandboxEnvironmentService.class);
        when(environmentService.ensureProductionEnvironment(eq(PARENT), anyString())).thenAnswer(inv ->
                Map.of("id", environmentRepository.findProductionByTenant(PARENT)
                        .map(row -> (String) row.get("id"))
                        .orElseGet(() -> environmentRepository.create(PARENT, "Production", null,
                                "PRODUCTION", null, null, "admin"))));

        PackageService packageService = mock(PackageService.class);
        PackageImportService packageImportService = mock(PackageImportService.class);
        when(packageService.exportPackage(anyString(), any())).thenReturn(Map.of("items", List.of()));
        when(packageImportService.importPackage(anyString(), anyMap(), any()))
                .thenReturn(new PackageImportService.ImportReport(0, 0, 0, 0, List.of()));

        return new SandboxProvisioningService(environmentRepository, environmentService, packageService,
                packageImportService, queryEngine, collectionRegistry, mock(PlatformEventPublisher.class),
                new tools.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("the stored credential matches the returned one-time password and needs no forced change")
    void storedHashMatchesReturnedPassword() {
        Map<String, Object> result = TenantContext.callWithTenant(PARENT, PARENT_SLUG, () ->
                service().createSandbox(PARENT, "dev", null, "SANDBOX", "admin"));

        String sandboxSlug = (String) result.get("sandboxSlug");
        String password = (String) result.get("adminInitialPassword");
        assertThat(sandboxSlug).isEqualTo(PARENT_SLUG + "--dev");
        assertThat(password).isNotBlank();

        Map<String, Object> credential = admin.queryForMap(
                "SELECT c.password_hash, c.force_change_on_login FROM user_credential c "
                        + "JOIN platform_user u ON u.id = c.user_id "
                        + "JOIN tenant t ON t.id = u.tenant_id "
                        + "WHERE t.slug = ? AND u.username = ?",
                sandboxSlug, sandboxSlug + "-admin");
        String storedHash = (String) credential.get("password_hash");

        // kelta-auth verifies with exactly this encoder (AuthorizationServerConfig.passwordEncoder()).
        PasswordEncoder authEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        assertThat(storedHash)
                .as("the seeded unusable hash was replaced — the UPDATE ran under the sandbox's binding")
                .isNotEqualTo(UNUSABLE_HASH)
                .startsWith("{bcrypt}");
        assertThat(authEncoder.matches(password, storedHash))
                .as("the returned adminInitialPassword logs in")
                .isTrue();
        assertThat(credential.get("force_change_on_login")).isEqualTo(Boolean.FALSE);
    }
}
