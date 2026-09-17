package io.kelta.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SupersetDatabaseUserService")
class SupersetDatabaseUserServiceTest {

    private static final String TENANT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String DATABASE_NAME = "emf_control_plane";

    private JdbcTemplate jdbcTemplate;
    private SupersetDatabaseUserService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new SupersetDatabaseUserService(jdbcTemplate, DATABASE_NAME);
    }

    @Test
    @DisplayName("converts tenant slug to PostgreSQL username")
    void convertsSlugToUsername() {
        assertEquals("superset_acme", SupersetDatabaseUserService.toUsername("acme"));
        assertEquals("superset_threadline_clothing",
                SupersetDatabaseUserService.toUsername("threadline-clothing"));
        assertEquals("superset_my_company", SupersetDatabaseUserService.toUsername("my-company"));
    }

    @Test
    @DisplayName("creates new PostgreSQL user when role does not exist")
    void createsNewUser() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = ?)"),
                eq(Boolean.class), eq("superset_acme")))
                .thenReturn(false);

        String password = service.ensureTenantUser(TENANT_UUID, "acme");

        assertNotNull(password);
        assertFalse(password.isEmpty());

        // Verify CREATE ROLE was called
        verify(jdbcTemplate).execute(contains("CREATE ROLE \"superset_acme\""));

        // Verify app.current_tenant_id is set on the role
        verify(jdbcTemplate).execute(contains("ALTER ROLE \"superset_acme\" SET app.current_tenant_id"));

        // Verify search_path is set
        verify(jdbcTemplate).execute(contains("ALTER ROLE \"superset_acme\" SET search_path"));

        // Verify GRANT USAGE on public and tenant schema
        verify(jdbcTemplate).execute(contains("GRANT USAGE ON SCHEMA public"));
        verify(jdbcTemplate).execute(contains("GRANT USAGE ON SCHEMA \"acme\""));

        // Verify SELECT grants: the tenant's own schema wholesale, public table by table
        verify(jdbcTemplate).execute(contains("GRANT SELECT ON ALL TABLES IN SCHEMA \"acme\""));
        verify(jdbcTemplate).execute(contains("GRANT SELECT ON public.%I TO %I"));

        // Verify database name is quoted and matches the configured value
        verify(jdbcTemplate).execute(contains("GRANT CONNECT ON DATABASE \"emf_control_plane\""));

        // The role is pinned to its tenant inside the RLS policies, not only by the GUC default
        verify(jdbcTemplate).update(contains("INSERT INTO tenant_db_role"), eq("superset_acme"), eq(TENANT_UUID));
    }

    @Test
    @DisplayName("updates existing PostgreSQL user when role already exists")
    void updatesExistingUser() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = ?)"),
                eq(Boolean.class), eq("superset_acme")))
                .thenReturn(true);

        String password = service.ensureTenantUser(TENANT_UUID, "acme");

        assertNotNull(password);

        // Verify ALTER ROLE (update password) was called instead of CREATE ROLE
        verify(jdbcTemplate).execute(contains("ALTER ROLE \"superset_acme\" WITH PASSWORD"));
        verify(jdbcTemplate, never()).execute(contains("CREATE ROLE"));
    }

    @Test
    @DisplayName("drops tenant user and revokes privileges")
    void dropsTenantUser() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = ?)"),
                eq(Boolean.class), eq("superset_acme")))
                .thenReturn(true);

        service.dropTenantUser("acme");

        verify(jdbcTemplate).execute(contains("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM \"superset_acme\""));
        verify(jdbcTemplate).execute(contains("REVOKE USAGE ON SCHEMA public FROM \"superset_acme\""));
        verify(jdbcTemplate).execute(contains("REVOKE USAGE ON SCHEMA \"acme\" FROM \"superset_acme\""));
        verify(jdbcTemplate).execute(contains("DROP ROLE IF EXISTS \"superset_acme\""));
        verify(jdbcTemplate).update(contains("DELETE FROM tenant_db_role"), eq("superset_acme"));
        // Both are DROP ROLE dependencies: the role survives the drop without them.
        verify(jdbcTemplate).execute(contains(
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM \"superset_acme\""));
        verify(jdbcTemplate).execute(contains(
                "REVOKE ALL ON DATABASE \"emf_control_plane\" FROM \"superset_acme\""));
    }

    @Test
    @DisplayName("skips drop when user does not exist")
    void skipsDropWhenMissing() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = ?)"),
                eq(Boolean.class), eq("superset_acme")))
                .thenReturn(false);

        service.dropTenantUser("acme");

        verify(jdbcTemplate, never()).execute(contains("DROP ROLE"));
    }

    @Test
    @DisplayName("generates unique passwords on each call")
    void generatesUniquePasswords() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyString()))
                .thenReturn(false);

        String password1 = service.ensureTenantUser(TENANT_UUID, "acme");
        String password2 = service.ensureTenantUser(
                "22222222-3333-4444-5555-666666666666", "other");

        assertNotEquals(password1, password2);
    }

    // =========================================================================
    // Injection-safety tests
    // =========================================================================

    @Test
    @DisplayName("rejects tenant slug containing SQL injection attempt")
    void rejectsSlugWithInjectionAttempt() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser(TENANT_UUID, "acme\"; DROP ROLE postgres; --"));
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser(TENANT_UUID, "acme'; --"));
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser(TENANT_UUID, "ACME")); // uppercase
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser(TENANT_UUID, "")); // empty
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser(TENANT_UUID, null));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("rejects non-UUID tenant id")
    void rejectsInvalidTenantId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser("not-a-uuid", "acme"));
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser("'; DROP TABLE tenant; --", "acme"));
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureTenantUser(null, "acme"));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("rejects invalid database name at construction time")
    void rejectsInvalidDatabaseName() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupersetDatabaseUserService(jdbcTemplate, "db\"; DROP--"));
        assertThrows(IllegalArgumentException.class,
                () -> new SupersetDatabaseUserService(jdbcTemplate, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new SupersetDatabaseUserService(jdbcTemplate, null));
    }

    @Test
    @DisplayName("quoteIdent doubles internal double quotes")
    void quoteIdentEscapesQuotes() {
        assertEquals("\"plain\"", SupersetDatabaseUserService.quoteIdent("plain"));
        assertEquals("\"with\"\"quote\"", SupersetDatabaseUserService.quoteIdent("with\"quote"));
    }

    @Test
    @DisplayName("quoteLiteral doubles internal single quotes")
    void quoteLiteralEscapesQuotes() {
        assertEquals("'plain'", SupersetDatabaseUserService.quoteLiteral("plain"));
        assertEquals("'it''s'", SupersetDatabaseUserService.quoteLiteral("it's"));
        assertEquals("'''; DROP--'",
                SupersetDatabaseUserService.quoteLiteral("'; DROP--"));
    }

    // =========================================================================
    // Grant-scope tests — public is the control plane, not the tenant's data
    // =========================================================================

    /** Every statement the service issued, in order. */
    private List<String> executedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }

    @Test
    @DisplayName("never grants the whole public schema, and revokes an earlier blanket grant")
    void narrowsPublicSchemaGrants() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(false);

        service.ensureTenantUser(TENANT_UUID, "acme");

        List<String> sql = executedSql();
        assertTrue(sql.stream().noneMatch(s -> s.contains("GRANT SELECT ON ALL TABLES IN SCHEMA public")),
                "public must be granted table by table, never wholesale: " + sql);
        assertTrue(sql.stream().noneMatch(s ->
                        s.contains("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT")),
                "future public tables must not be granted automatically: " + sql);
        assertTrue(sql.stream().anyMatch(s ->
                        s.contains("REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM \"superset_acme\"")),
                "a role created before the narrowing must lose its blanket grant: " + sql);
        assertTrue(sql.stream().anyMatch(s ->
                        s.contains("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM \"superset_acme\"")),
                "the earlier future-table grant must be revoked too: " + sql);
    }

    @Test
    @DisplayName("grants only tenant-scoped public tables — user_credential and oauth2_* are not granted")
    void doesNotGrantPlatformWideTables() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(false);

        service.ensureTenantUser(TENANT_UUID, "acme");

        List<String> sql = executedSql();
        String grant = sql.stream()
                .filter(s -> s.contains("GRANT SELECT ON public.%I TO %I"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no per-table public grant issued: " + sql));

        // The grant is driven by the catalog, so the guarantee is in its predicate: a table
        // is granted only if it has a tenant_id column that row-level security is enforcing
        // on. user_credential (tenant only via platform_user) and every oauth2_* table have
        // no tenant_id, so neither can be selected by it.
        assertTrue(grant.contains("a.attname = 'tenant_id'"), grant);
        assertTrue(grant.contains("c.relrowsecurity"), grant);
        assertTrue(grant.contains("n.nspname = 'public'"), grant);
        assertTrue(grant.contains("c.relname <> 'tenant_db_role'"), grant);
        assertTrue(grant.contains("'superset_acme'"), "the grant must name the role: " + grant);

        // And nothing anywhere names them outright.
        assertTrue(sql.stream().noneMatch(s -> s.contains("user_credential")), sql.toString());
        assertTrue(sql.stream().noneMatch(s -> s.contains("oauth2_")), sql.toString());
    }
}
