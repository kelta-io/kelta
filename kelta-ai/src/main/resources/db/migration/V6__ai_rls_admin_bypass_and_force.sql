-- The ai_* tables have carried a tenant_isolation policy since V1, and it has never once
-- been evaluated: kelta-ai creates these tables itself, so the application role owns them,
-- and an owner is exempt from row-level security unless the table also says FORCE. The
-- policy was decoration.
--
-- Two changes make it real, in the shape the control-plane tables already use
-- (kelta-worker V200/V201):
--
--   FORCE ROW LEVEL SECURITY   the owner is subject to its own policies;
--   admin_bypass               the '' sentinel — kelta-ai's genuinely tenant-less paths
--                              (Flyway itself, startup) would otherwise match no policy at
--                              all and silently read nothing.
--
-- tenant_isolation is rewritten to prefer a tenant pinned to the database role over the
-- session variable, exactly as kelta-worker's V201 does, so a per-tenant login (Superset)
-- cannot SET its way into another tenant's conversations. That function lives in the
-- control-plane schema; when kelta-ai points at a database that does not have it (a
-- standalone ai database), the policies fall back to the session variable alone.
--
-- Enforcement only bites because kelta-ai now binds the request's tenant to the connection
-- (TenantAwareDataSourceConfig). Without that every query would run as '' and this
-- migration would change nothing.

DO $$
DECLARE
    t         text;
    pinned    boolean := to_regprocedure('kelta_pinned_tenant()') IS NOT NULL;
    tenant_expr text;
    isolation text;
    bypass    text;
BEGIN
    tenant_expr := CASE WHEN pinned
        THEN 'COALESCE((SELECT kelta_pinned_tenant()), current_setting(''app.current_tenant_id''::text, true))'
        ELSE 'current_setting(''app.current_tenant_id''::text, true)'
    END;
    isolation := format('((tenant_id)::text = %s)', tenant_expr);
    bypass := CASE WHEN pinned
        THEN '((SELECT kelta_pinned_tenant()) IS NULL AND '
             'current_setting(''app.current_tenant_id''::text, true) = ''''::text)'
        ELSE '(current_setting(''app.current_tenant_id''::text, true) = ''''::text)'
    END;

    FOREACH t IN ARRAY ARRAY['ai_conversation', 'ai_message', 'ai_token_usage', 'ai_config',
                             'ai_agent', 'ai_agent_execution']
    LOOP
        CONTINUE WHEN to_regclass(quote_ident(t)) IS NULL;

        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE ONLY %I FORCE ROW LEVEL SECURITY', t);
        -- V1/V2 named the policy <table>_tenant_isolation; standardise on tenant_isolation
        -- so the catalog checks that guard the control plane read these tables too.
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_tenant_isolation', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING %s WITH CHECK %s',
                       t, isolation, isolation);
        EXECUTE format('DROP POLICY IF EXISTS admin_bypass ON %I', t);
        EXECUTE format('CREATE POLICY admin_bypass ON %I USING %s', t, bypass);
    END LOOP;
END $$;
