package io.kelta.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Manages per-tenant PostgreSQL users for Superset database connections.
 *
 * <p>Each tenant gets a dedicated PostgreSQL role ({@code superset_{slug}}) with:
 * <ul>
 *   <li>USAGE on only {@code public} and the tenant's schema</li>
 *   <li>SELECT on every table in the tenant's own schema, and in {@code public} only on
 *       the tables row-level security can scope to one tenant — a {@code tenant_id}
 *       column with RLS enabled on it. {@code user_credential}, {@code oauth2_*},
 *       {@code tenant} and the rest of the platform-wide control plane are not granted
 *       at all, so no policy has to be relied on to hide them</li>
 *   <li>A row in {@code tenant_db_role} pinning the role to its tenant inside the
 *       policies, plus a {@code app.current_tenant_id} session default</li>
 *   <li>A {@code search_path} restricted to the tenant schema + public</li>
 * </ul>
 *
 * <h3>Injection safety</h3>
 *
 * Postgres DDL (CREATE/ALTER/DROP ROLE, GRANT/REVOKE) cannot use JDBC parameter
 * binding — identifiers and role names are not bindable. Every dynamic value
 * used inside a statement here is therefore:
 *
 * <ol>
 *   <li>strictly validated at the boundary ({@link #validateSlug},
 *       {@link #validateTenantId}, {@link #validateDatabaseName}), so only
 *       characters safe inside Postgres identifiers and string literals can
 *       reach the SQL at all; and</li>
 *   <li>escaped by {@link #quoteIdent} / {@link #quoteLiteral} before
 *       concatenation, so even a validator regression cannot produce a
 *       broken-quote condition.</li>
 * </ol>
 *
 * Belt-and-suspenders — either layer alone would be sufficient; together they
 * survive a future edit that loosens one of them.
 */
public class SupersetDatabaseUserService {

    private static final Logger log = LoggerFactory.getLogger(SupersetDatabaseUserService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Tenant slug grammar — matches the slug validator used elsewhere in the
     * platform. Lowercase letters, digits, and hyphens; 1–63 chars.
     */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    /**
     * UUID format — matches Postgres-style 8-4-4-4-12 hex quads.
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Postgres identifier grammar for database names — letters/digits/underscore,
     * starting with a letter or underscore, max 63 chars.
     */
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    /** Token substituted with the quoted role name before {@link #GRANT_TENANT_SCOPED_TABLES} runs. */
    private static final String ROLE_PLACEHOLDER = "@role@";

    /**
     * Grants SELECT in {@code public} one table at a time, on exactly the tables a tenant
     * login may see: those with a {@code tenant_id} column and row-level security enabled on
     * it. Everything else in {@code public} — platform-wide config, another service's
     * secrets, the {@code tenant_db_role} pin itself — is simply never granted.
     *
     * <p>Evaluated at grant time, so a table added later is not covered until the role is
     * next rotated. That is deliberate: the previous {@code ALTER DEFAULT PRIVILEGES … GRANT
     * SELECT ON TABLES} handed every future table over automatically, which is how
     * {@code user_credential} became readable by a reporting login in the first place.
     */
    private static final String GRANT_TENANT_SCOPED_TABLES = """
            DO $$
            DECLARE
                t text;
            BEGIN
                FOR t IN
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relkind = 'r'
                      AND c.relrowsecurity
                      AND c.relname <> 'tenant_db_role'
                      AND EXISTS (SELECT 1 FROM pg_attribute a
                                  WHERE a.attrelid = c.oid AND a.attname = 'tenant_id'
                                    AND a.attnum > 0 AND NOT a.attisdropped)
                LOOP
                    EXECUTE format('GRANT SELECT ON public.%I TO %I', t, @role@);
                END LOOP;
            END $$;
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String databaseName;

    public SupersetDatabaseUserService(
            JdbcTemplate jdbcTemplate,
            @Value("${kelta.worker.superset.database-name:${spring.datasource.name:emf_control_plane}}")
            String databaseName) {
        validateDatabaseName(databaseName);
        this.jdbcTemplate = jdbcTemplate;
        this.databaseName = databaseName;
    }

    /**
     * Creates a per-tenant PostgreSQL user for Superset with proper schema
     * isolation and RLS session variable.
     *
     * @param tenantId   the tenant UUID (used for RLS)
     * @param tenantSlug the tenant slug (used as schema name and username suffix)
     * @return the generated password for the new user
     * @throws IllegalArgumentException if {@code tenantId} or {@code tenantSlug}
     *                                  fail validation
     */
    public String ensureTenantUser(String tenantId, String tenantSlug) {
        validateTenantId(tenantId);
        validateSlug(tenantSlug);

        String username = toUsername(tenantSlug);
        String password = generatePassword();
        String userIdent = quoteIdent(username);
        String schemaIdent = quoteIdent(tenantSlug);
        String dbIdent = quoteIdent(databaseName);

        try {
            boolean exists = Boolean.TRUE.equals(
                    jdbcTemplate.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = ?)",
                            Boolean.class, username));

            if (exists) {
                jdbcTemplate.execute(
                        "ALTER ROLE " + userIdent + " WITH PASSWORD " + quoteLiteral(password));
                log.info("Updated existing Superset DB user '{}'", username);
            } else {
                jdbcTemplate.execute(
                        "CREATE ROLE " + userIdent
                                + " WITH LOGIN PASSWORD " + quoteLiteral(password)
                                + " NOSUPERUSER NOCREATEDB NOCREATEROLE");
                log.info("Created Superset DB user '{}'", username);
            }

            // Set default session variables on the role so RLS works automatically
            jdbcTemplate.execute(
                    "ALTER ROLE " + userIdent
                            + " SET app.current_tenant_id = " + quoteLiteral(tenantId));

            // Set search_path so queries default to tenant schema + public
            jdbcTemplate.execute(
                    "ALTER ROLE " + userIdent
                            + " SET search_path = " + schemaIdent + ", public");

            // Grant CONNECT on the database
            jdbcTemplate.execute(
                    "GRANT CONNECT ON DATABASE " + dbIdent + " TO " + userIdent);

            // Grant USAGE on only public and tenant schema
            jdbcTemplate.execute(
                    "GRANT USAGE ON SCHEMA public TO " + userIdent);
            jdbcTemplate.execute(
                    "GRANT USAGE ON SCHEMA " + schemaIdent + " TO " + userIdent);

            // public holds the control plane, not this tenant's data: most of it is
            // platform-wide (oauth2_*, tenant, system_permission) or another service's
            // secrets (user_credential). Grant only the tables RLS can scope to this
            // tenant — see GRANT_TENANT_SCOPED_TABLES. Revoke first so a role created
            // before this narrowing loses the blanket grant on its next rotation.
            jdbcTemplate.execute(
                    "REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM " + userIdent);
            jdbcTemplate.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM " + userIdent);
            jdbcTemplate.execute(GRANT_TENANT_SCOPED_TABLES.replace(ROLE_PLACEHOLDER, quoteLiteral(username)));

            // The tenant's own schema is entirely its data, present and future.
            jdbcTemplate.execute(
                    "GRANT SELECT ON ALL TABLES IN SCHEMA " + schemaIdent + " TO " + userIdent);
            jdbcTemplate.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA " + schemaIdent
                            + " GRANT SELECT ON TABLES TO " + userIdent);

            // Pin the role to its tenant inside the RLS policies (V201): the role default
            // above is a convenience, not a boundary — any session can SET the GUC. The
            // policies prefer the pinned tenant for session_user, which SET cannot change.
            jdbcTemplate.update(
                    "INSERT INTO tenant_db_role (role_name, tenant_id) VALUES (?, ?) "
                            + "ON CONFLICT (role_name) DO UPDATE SET tenant_id = EXCLUDED.tenant_id",
                    username, tenantId);

            log.info("Configured Superset DB user '{}' with tenant_id={}, schema={}",
                    username, tenantId, tenantSlug);
            return password;

        } catch (Exception e) {
            log.error("Failed to create Superset DB user '{}': {}", username, e.getMessage(), e);
            throw new RuntimeException("Failed to create Superset DB user: " + e.getMessage(), e);
        }
    }

    /**
     * Drops the per-tenant PostgreSQL user.
     *
     * @param tenantSlug the tenant slug
     */
    public void dropTenantUser(String tenantSlug) {
        validateSlug(tenantSlug);

        String username = toUsername(tenantSlug);
        String userIdent = quoteIdent(username);
        String schemaIdent = quoteIdent(tenantSlug);
        try {
            boolean exists = Boolean.TRUE.equals(
                    jdbcTemplate.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = ?)",
                            Boolean.class, username));
            if (!exists) {
                log.info("Superset DB user '{}' does not exist — nothing to drop", username);
                return;
            }

            jdbcTemplate.execute(
                    "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM " + userIdent);
            jdbcTemplate.execute(
                    "REVOKE ALL ON ALL TABLES IN SCHEMA " + schemaIdent + " FROM " + userIdent);
            // Default privileges are a dependency of the role, not a grant on an object:
            // DROP ROLE fails while any remain, including the public-schema one roles created
            // before the grant narrowing still carry.
            jdbcTemplate.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM " + userIdent);
            jdbcTemplate.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA " + schemaIdent
                            + " REVOKE SELECT ON TABLES FROM " + userIdent);
            jdbcTemplate.execute(
                    "REVOKE USAGE ON SCHEMA public FROM " + userIdent);
            jdbcTemplate.execute(
                    "REVOKE USAGE ON SCHEMA " + schemaIdent + " FROM " + userIdent);
            // The database-level CONNECT grant is a dependency too — without this,
            // DROP ROLE fails with "privileges for database ..." and the role survives.
            jdbcTemplate.execute(
                    "REVOKE ALL ON DATABASE " + quoteIdent(databaseName) + " FROM " + userIdent);
            jdbcTemplate.execute(
                    "DROP ROLE IF EXISTS " + userIdent);
            jdbcTemplate.update("DELETE FROM tenant_db_role WHERE role_name = ?", username);

            log.info("Dropped Superset DB user '{}'", username);
        } catch (Exception e) {
            log.error("Failed to drop Superset DB user '{}': {}", username, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Validators
    // =========================================================================

    static void validateSlug(String tenantSlug) {
        if (tenantSlug == null || !SLUG_PATTERN.matcher(tenantSlug).matches()) {
            throw new IllegalArgumentException(
                    "Invalid tenant slug: must match " + SLUG_PATTERN.pattern());
        }
    }

    static void validateTenantId(String tenantId) {
        if (tenantId == null || !UUID_PATTERN.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid tenant ID: must be a UUID");
        }
    }

    static void validateDatabaseName(String databaseName) {
        if (databaseName == null || !IDENT_PATTERN.matcher(databaseName).matches()) {
            throw new IllegalArgumentException(
                    "Invalid database name: must match " + IDENT_PATTERN.pattern());
        }
    }

    // =========================================================================
    // SQL quoting helpers — defense-in-depth. Validators catch malformed input
    // before these run, but quoting guarantees that even a validator regression
    // cannot produce a broken-quote condition.
    // =========================================================================

    /**
     * Wraps an identifier in Postgres double-quotes and doubles any internal
     * double quote. Safe to splice into a DDL statement.
     */
    static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /**
     * Wraps a string literal in Postgres single-quotes and doubles any internal
     * single quote. Safe to splice into a SQL statement.
     */
    static String quoteLiteral(String literal) {
        return "'" + literal.replace("'", "''") + "'";
    }

    /**
     * Converts a tenant slug to a PostgreSQL username.
     * Format: superset_{slug} with hyphens replaced by underscores.
     */
    static String toUsername(String tenantSlug) {
        return "superset_" + tenantSlug.replaceAll("[^a-z0-9]", "_");
    }

    private String generatePassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
