-- V201 pinned per-tenant database logins (tenant_db_role, kelta_pinned_tenant()) by rewriting
-- every policy that existed at the time; it could not reach tables created later. Three tables
-- created after V201 kept the pre-V201 shape — notification (V214), module_event_dead_letter
-- (V222) and module_async_task (V223) key tenant_isolation and admin_bypass on
-- app.current_tenant_id alone, without the pin.
--
-- Re-run the V201 rewrite, with V201's exact expressions, for every policy whose USING or WITH
-- CHECK expression keys on the GUC without consulting kelta_pinned_tenant() — whichever tables
-- that turns out to be in a given database, kelta-ai's ai_* policies included (they share the
-- schema, and kelta-ai's own V6 pins them only where the function already existed). An
-- expression is rewritten only where the policy has one, so a WITH CHECK is kept and an
-- INSERT-only policy gets no USING. Every policy rewritten here has its own tenant_id column; a
-- policy that keys on the GUC some other way (a parent join, V202) cannot take this expression,
-- so the migration stops and names it rather than guess.
--
-- The schema is the migration's own (current_schema(): public in production, the run's schema
-- in CI), as in V202. Idempotent: a second run finds nothing unpinned and changes nothing.
-- RowLevelSecurityIntegrationTest.everyGucPolicyIsPinned now fails the build on any unpinned
-- policy, so the next table cannot copy the old shape.
-- To reverse: not meaningful — the old expressions are the vulnerability.

DO $$
DECLARE
    p         record;
    expr      text;
    isolation text := '((tenant_id)::text = COALESCE((SELECT kelta_pinned_tenant()), '
                      'current_setting(''app.current_tenant_id''::text, true)))';
    bypass    text := '((SELECT kelta_pinned_tenant()) IS NULL AND '
                      'current_setting(''app.current_tenant_id''::text, true) = ''''::text)';
BEGIN
    FOR p IN
        SELECT pol.schemaname, pol.tablename, pol.policyname, pol.qual, pol.with_check
        FROM pg_policies pol
        WHERE pol.schemaname = current_schema()
          AND ((pol.qual LIKE '%app.current_tenant_id%'
                AND pol.qual NOT LIKE '%kelta_pinned_tenant%')
            OR (pol.with_check LIKE '%app.current_tenant_id%'
                AND pol.with_check NOT LIKE '%kelta_pinned_tenant%'))
        ORDER BY pol.tablename, pol.policyname
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_attribute a
                       WHERE a.attrelid = format('%I.%I', p.schemaname, p.tablename)::regclass
                         AND a.attname = 'tenant_id' AND a.attnum > 0 AND NOT a.attisdropped) THEN
            RAISE EXCEPTION 'policy % on % keys on app.current_tenant_id without kelta_pinned_tenant(), '
                            'and the table has no tenant_id column to pin it on: rewrite it by hand '
                            '(V202 shows the parent-join shape)', p.policyname, p.tablename;
        END IF;

        expr := CASE WHEN p.policyname = 'admin_bypass' THEN bypass ELSE isolation END;

        IF p.qual IS NOT NULL AND p.with_check IS NOT NULL THEN
            EXECUTE format('ALTER POLICY %I ON %I.%I USING %s WITH CHECK %s',
                           p.policyname, p.schemaname, p.tablename, expr, expr);
        ELSIF p.qual IS NOT NULL THEN
            EXECUTE format('ALTER POLICY %I ON %I.%I USING %s',
                           p.policyname, p.schemaname, p.tablename, expr);
        ELSE
            EXECUTE format('ALTER POLICY %I ON %I.%I WITH CHECK %s',
                           p.policyname, p.schemaname, p.tablename, expr);
        END IF;
    END LOOP;
END $$;
