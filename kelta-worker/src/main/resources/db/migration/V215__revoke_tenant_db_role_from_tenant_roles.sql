-- tenant_db_role (V201) maps every per-tenant database login to its tenant and has no RLS:
-- any role that can SELECT it lists every tenant's id and DB role. No tenant login needs a
-- privilege on it — kelta_pinned_tenant() is SECURITY DEFINER, owned by the migrating role,
-- with a fixed search_path — but superset_<slug> roles provisioned before the public-schema
-- grant narrowing in SupersetDatabaseUserService still hold the old blanket
-- GRANT SELECT ON ALL TABLES IN SCHEMA public, which covered it (and user_credential,
-- oauth2_*, tenant …). Those roles only lose it on their next rotation, so apply the same
-- narrowing here.
--
-- For every role pinned in tenant_db_role and every superset_% role:
--   * REVOKE ALL on tenant_db_role.
-- For every superset_% role additionally:
--   * REVOKE SELECT on every public table and the public default privilege, then
--   * re-grant SELECT on the RLS tables with a tenant_id column, excluding tenant_db_role —
--     the predicate of SupersetDatabaseUserService.GRANT_TENANT_SCOPED_TABLES — but only
--     when the role is pinned. An unpinned superset_% role (its tenant deleted, the pin row
--     cascaded away) could SET app.current_tenant_id = '' and land on admin_bypass, so it
--     keeps no public grant at all.
-- Tenant-schema grants are left untouched. Every statement is a no-op when there is nothing
-- to revoke, and roles listed in tenant_db_role but missing from pg_roles are skipped.

DO $$
DECLARE
    r record;
    t text;
BEGIN
    FOR r IN
        SELECT ro.rolname,
               ro.rolname LIKE 'superset\_%' AS is_superset,
               EXISTS (SELECT 1 FROM tenant_db_role p WHERE p.role_name = ro.rolname) AS pinned
        FROM pg_roles ro
        WHERE ro.rolname LIKE 'superset\_%'
           OR ro.rolname IN (SELECT role_name FROM tenant_db_role)
    LOOP
        EXECUTE format('REVOKE ALL ON public.tenant_db_role FROM %I', r.rolname);

        CONTINUE WHEN NOT r.is_superset;

        EXECUTE format('REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM %I', r.rolname);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM %I',
                       r.rolname);

        CONTINUE WHEN NOT r.pinned;

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
            EXECUTE format('GRANT SELECT ON public.%I TO %I', t, r.rolname);
        END LOOP;
    END LOOP;
END $$;
