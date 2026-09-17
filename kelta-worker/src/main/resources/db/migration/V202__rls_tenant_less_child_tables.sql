-- V200 closed the gap for tables that carry a `tenant_id` column. It left a second,
-- less obvious class untouched: child and log tables that hold tenant data but store
-- no tenant_id of their own, reaching it only through their parent. Under a role with
-- NOBYPASSRLS those tables have no policy at all, so they are readable by every tenant
-- — flow step snapshots, approval routing, package contents, password hashes.
--
-- Each one gets the usual pair, keyed on the parent row rather than on a local column:
--
--   tenant_isolation  EXISTS (parent row whose tenant_id is the caller's tenant)
--   admin_bypass      the '' sentinel, unchanged from V200/V201
--
-- The EXISTS is evaluated against the parent's primary key, and the pinned-tenant
-- lookup is an uncorrelated scalar subquery the planner hoists into an InitPlan
-- (V201), so the added cost is one index probe per row. Postgres applies the parent's
-- own RLS inside the subquery too, which is harmless here: the predicate already
-- names the same tenant the parent policy would allow.
--
-- Three tables have no usable parent join and take a denormalised tenant_id instead:
--
--   kelta_migrations       runtime DDL history written by SchemaMigrationEngine. It is
--                          created lazily by the application, so this migration creates
--                          it if absent and adds the column if present. The column
--                          defaults to the session's bound tenant, which is how every
--                          writer already identifies itself — collection DDL runs under
--                          TenantContext, deploy-time bootstrap runs unbound (NULL,
--                          visible only to admin_bypass).
--   emf_migrations         the pre-rename name of the same table. No code in this repo
--                          writes it; it is handled only so a database carrying the
--                          legacy table is covered too.
--   livekit_webhook_event  webhook idempotency claims. LiveKit delivers tenant-less, so
--                          the claim now records the tenant of the video session the
--                          room resolves to (LiveKitWebhookService). Rows predating
--                          this migration stay NULL: platform-visible, tenant-invisible.

-- ---------------------------------------------------------------------------
-- 1. Child tables reached through a tenant-scoped parent.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    spec      record;
    isolation text;
    bypass    text := '((SELECT kelta_pinned_tenant()) IS NULL AND '
                      'current_setting(''app.current_tenant_id''::text, true) = ''''::text)';
BEGIN
    FOR spec IN SELECT * FROM (VALUES
            -- child                    fk column             tenant-scoped parent
            ('alert_delivery',          'alert_id',           'alert'),
            ('approval_step',           'approval_process_id','approval_process'),
            ('approval_step_instance',  'approval_instance_id','approval_instance'),
            ('bulk_job_result',         'bulk_job_id',        'bulk_job'),
            ('collection_version',      'collection_id',      'collection'),
            ('connected_app_token',     'connected_app_id',   'connected_app'),
            ('flow_step_log',           'execution_id',       'flow_execution'),
            ('flow_version',            'flow_id',            'flow'),
            ('job_execution_log',       'job_id',             'scheduled_job'),
            ('migration_step',          'migration_run_id',   'migration_run'),
            ('package_item',            'package_id',         'package'),
            ('record_type_picklist',    'record_type_id',     'record_type'),
            ('script_trigger',          'script_id',          'script'),
            -- The per-user credential family: one row per platform_user, no tenant_id,
            -- and between them every secret the auth service checks a password against.
            ('password_history',        'user_id',            'platform_user'),
            ('user_credential',         'user_id',            'platform_user'),
            ('user_recovery_code',      'user_id',            'platform_user'),
            ('user_totp_secret',        'user_id',            'platform_user')
        ) AS v(child, fk, parent)
    LOOP
        isolation := format(
            '(EXISTS (SELECT 1 FROM %I p WHERE p.id = %I.%I AND (p.tenant_id)::text = '
            'COALESCE((SELECT kelta_pinned_tenant()), '
            'current_setting(''app.current_tenant_id''::text, true))))',
            spec.parent, spec.child, spec.fk);

        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', spec.child);
        EXECUTE format('ALTER TABLE ONLY %I FORCE ROW LEVEL SECURITY', spec.child);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', spec.child);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING %s', spec.child, isolation);
        EXECUTE format('DROP POLICY IF EXISTS admin_bypass ON %I', spec.child);
        EXECUTE format('CREATE POLICY admin_bypass ON %I USING %s', spec.child, bypass);
    END LOOP;
END $$;

-- field_version hangs off collection_version, which itself has no tenant_id — two hops
-- to reach the tenant, so it does not fit the single-parent loop above.
ALTER TABLE field_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY field_version FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON field_version;
CREATE POLICY tenant_isolation ON field_version
    USING (EXISTS (SELECT 1
                   FROM collection_version cv
                   JOIN collection c ON c.id = cv.collection_id
                   WHERE cv.id = field_version.collection_version_id
                     AND (c.tenant_id)::text = COALESCE((SELECT kelta_pinned_tenant()),
                                                        current_setting('app.current_tenant_id'::text, true))));
DROP POLICY IF EXISTS admin_bypass ON field_version;
CREATE POLICY admin_bypass ON field_version
    USING ((SELECT kelta_pinned_tenant()) IS NULL
           AND current_setting('app.current_tenant_id'::text, true) = ''::text);

-- ---------------------------------------------------------------------------
-- 2. Tables with no parent to join: denormalised tenant_id.
-- ---------------------------------------------------------------------------

-- SchemaMigrationEngine creates this at runtime on first collection DDL, so it may or
-- may not exist yet. Create it to match the engine's DDL, then add the column either way.
CREATE TABLE IF NOT EXISTS kelta_migrations (
    id SERIAL PRIMARY KEY,
    collection_name VARCHAR(255) NOT NULL,
    migration_type VARCHAR(50) NOT NULL,
    sql_statement TEXT NOT NULL,
    executed_at TIMESTAMP NOT NULL
);

-- LiveKit delivers webhooks tenant-less; the tenant is whatever the room's video_session
-- says it is, so LiveKitWebhookService resolves the session before it claims the event.
ALTER TABLE livekit_webhook_event ADD COLUMN IF NOT EXISTS tenant_id character varying(36);

DO $$
DECLARE
    t         text;
    isolation text := '((tenant_id)::text = COALESCE((SELECT kelta_pinned_tenant()), '
                      'current_setting(''app.current_tenant_id''::text, true)))';
    bypass    text := '((SELECT kelta_pinned_tenant()) IS NULL AND '
                      'current_setting(''app.current_tenant_id''::text, true) = ''''::text)';
BEGIN
    FOREACH t IN ARRAY ARRAY['kelta_migrations', 'emf_migrations', 'livekit_webhook_event']
    LOOP
        -- Unqualified on purpose: resolve through search_path, which is the run's schema
        -- in CI and public in production.
        CONTINUE WHEN to_regclass(quote_ident(t)) IS NULL;

        -- The migration-history writers pass no tenant column, so the default carries the
        -- session's bound tenant ('' — a platform session — becomes NULL). current_setting
        -- is STABLE, so Postgres evaluates it once here: existing rows become NULL.
        IF t LIKE '%_migrations' THEN
            EXECUTE format(
                'ALTER TABLE %I ADD COLUMN IF NOT EXISTS tenant_id character varying(36) '
                'DEFAULT NULLIF(current_setting(''app.current_tenant_id''::text, true), '''')', t);
        END IF;

        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE ONLY %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING %s', t, isolation);
        EXECUTE format('DROP POLICY IF EXISTS admin_bypass ON %I', t);
        EXECUTE format('CREATE POLICY admin_bypass ON %I USING %s', t, bypass);
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_kelta_migrations_tenant ON kelta_migrations (tenant_id);
