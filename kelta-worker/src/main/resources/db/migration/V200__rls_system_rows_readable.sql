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
