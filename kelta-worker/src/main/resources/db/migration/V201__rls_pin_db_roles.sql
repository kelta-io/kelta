-- Row-level security keyed on app.current_tenant_id alone is a boundary for the trusted
-- application role only: the setting is a plain custom GUC, so any session — including a
-- per-tenant database login such as the Superset users the platform creates — can
-- `SET app.current_tenant_id = ''` and land on admin_bypass, or set another tenant's id.
-- Observed 2026-09-17 from a tenant login: one SET turned 10 visible users into 66.
--
-- Pin such roles by *who they are*: tenant_db_role maps a database role to the one tenant
-- it may see, kelta_pinned_tenant() reads it for session_user (which SET ROLE / SET cannot
-- change), and every policy prefers the pinned tenant over the GUC. Unmapped roles (the
-- application role) behave exactly as before.

CREATE TABLE IF NOT EXISTS tenant_db_role (
    role_name  text PRIMARY KEY,
    tenant_id  varchar(36) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tenant_db_role_tenant ON tenant_db_role (tenant_id);
REVOKE ALL ON tenant_db_role FROM PUBLIC;

-- SECURITY DEFINER so a mapped role needs no privilege on the mapping table; session_user,
-- not current_user, so the definer context does not mask the caller.
CREATE OR REPLACE FUNCTION kelta_pinned_tenant() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER PARALLEL SAFE
    SET search_path = pg_catalog, public
AS $$
    SELECT tenant_id::text FROM public.tenant_db_role WHERE role_name = session_user::text
$$;
REVOKE ALL ON FUNCTION kelta_pinned_tenant() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kelta_pinned_tenant() TO PUBLIC;

-- Rewrite every policy that keys on the GUC (the tenant_isolation / admin_bypass pair on
-- each tenant-scoped table, plus kelta-ai's ai_*_tenant_isolation). The pinned lookup is an
-- uncorrelated scalar subquery, so the planner evaluates it once per query as an InitPlan —
-- no per-row cost. Policies with an explicit WITH CHECK (collection, field) get the same
-- expression there.
DO $$
DECLARE
    p record;
    isolation text := '((tenant_id)::text = COALESCE((SELECT kelta_pinned_tenant()), '
                      'current_setting(''app.current_tenant_id''::text, true)))';
    bypass    text := '((SELECT kelta_pinned_tenant()) IS NULL AND '
                      'current_setting(''app.current_tenant_id''::text, true) = ''''::text)';
BEGIN
    FOR p IN
        SELECT tablename, policyname, with_check
        FROM pg_policies
        WHERE schemaname = 'public'
          AND (qual LIKE '%app.current_tenant_id%' OR with_check LIKE '%app.current_tenant_id%')
    LOOP
        IF p.policyname = 'admin_bypass' THEN
            EXECUTE format('ALTER POLICY admin_bypass ON %I USING %s', p.tablename, bypass);
        ELSIF p.with_check IS NOT NULL THEN
            EXECUTE format('ALTER POLICY %I ON %I USING %s WITH CHECK %s',
                           p.policyname, p.tablename, isolation, isolation);
        ELSE
            EXECUTE format('ALTER POLICY %I ON %I USING %s', p.policyname, p.tablename, isolation);
        END IF;
    END LOOP;
END $$;

-- Pin the per-tenant Superset roles that already exist (superset_<slug>, hyphens → _).
INSERT INTO tenant_db_role (role_name, tenant_id)
SELECT r.rolname, t.id
FROM pg_roles r
JOIN tenant t ON r.rolname = 'superset_' || regexp_replace(t.slug, '[^a-z0-9]', '_', 'g')
ON CONFLICT (role_name) DO UPDATE SET tenant_id = EXCLUDED.tenant_id;
