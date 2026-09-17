-- Row-level security has been configured on every tenant-scoped control-plane table since
-- V77, but never enforced: the application role carried BYPASSRLS, so the policies were
-- never evaluated. Turning enforcement on (ALTER ROLE ... NOBYPASSRLS, an operational step
-- documented in docs/ops/rls-enforcement.md) exposes one gap in the policies themselves:
-- system collections and their fields live once, in the platform tenant
-- (00000000-0000-0000-0000-000000000001, system_collection = true), and every tenant reads
-- them — the JSON:API router already lists them with IN (tenant, SYSTEM). A strict
-- tenant_id = current_setting(...) policy would hide them the moment enforcement starts.
--
-- Split each table's policy in two:
--   tenant_isolation  ALL     rows of the caller's tenant, read and write (explicit WITH
--                             CHECK so a tenant can never insert or move a row into another
--                             tenant);
--   system_rows_read  SELECT  system collections and their fields, readable by every tenant
--                             and writable by none (writes to them happen only on the
--                             no-tenant path, which admin_bypass admits).
-- admin_bypass ('' sentinel) is untouched.

DROP POLICY IF EXISTS tenant_isolation ON collection;
CREATE POLICY tenant_isolation ON collection
    FOR ALL
    USING ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true))
    WITH CHECK ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true));
CREATE POLICY system_rows_read ON collection
    FOR SELECT
    USING (system_collection = true);

DROP POLICY IF EXISTS tenant_isolation ON field;
CREATE POLICY tenant_isolation ON field
    FOR ALL
    USING ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true))
    WITH CHECK ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true));
CREATE POLICY system_rows_read ON field
    FOR SELECT
    USING (EXISTS (SELECT 1 FROM collection c
                   WHERE c.id = field.collection_id AND c.system_collection = true));

-- Seventeen tables carry a tenant_id column but never got the policy pair: sixteen predate
-- the V77 RLS pass and were missed by it, billing_webhook_event (V178) came later. None holds
-- a NULL or cross-tenant row. RowLevelSecurityIntegrationTest now asserts from the catalog
-- that every public table with a tenant_id column has RLS enabled, forced, and both policies —
-- so the next table cannot be forgotten.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'billing_webhook_event', 'connected_app_audit', 'data_export', 'flow_audit_log',
        'flow_pending_resume', 'layout_assignment', 'observability_settings', 'package_history',
        'password_policy', 'profile_custom_rules', 'push_device', 'scim_client',
        'script_execution_log', 'sms_verification', 'tenant_custom_domain', 'tenant_module',
        'user_api_token']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE ONLY %I FORCE ROW LEVEL SECURITY', t);
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE tablename = t AND policyname = 'tenant_isolation') THEN
            EXECUTE format(
                'CREATE POLICY tenant_isolation ON %I '
                'USING (((tenant_id)::text = current_setting(''app.current_tenant_id''::text, true)))', t);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                       WHERE tablename = t AND policyname = 'admin_bypass') THEN
            EXECUTE format(
                'CREATE POLICY admin_bypass ON %I '
                'USING ((current_setting(''app.current_tenant_id''::text, true) = ''''::text))', t);
        END IF;
    END LOOP;
END $$;
