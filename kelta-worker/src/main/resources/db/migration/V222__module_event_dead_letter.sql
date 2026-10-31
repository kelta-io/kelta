-- External modules part 9e (specs/module-platform/9-external-modules.md §3.6.1, §3.11): record
-- events the platform could not deliver to an external module.
--
-- The worker's worker-module-events consumer POSTs every kelta.record.changed event an install
-- subscribed to to the module's /kelta/v1/events, retrying with backoff (5 s, 30 s, 2 min,
-- 10 min, 1 h by default). After the last attempt fails it writes one row here per install and
-- acknowledges the message, so a module that was down can be shown what it missed.
--
-- payload is the body exactly as sent — already filtered for the module's service principal
-- (server-only and HIDDEN fields removed, MASKED fields masked) — so this table never holds
-- more of a record than the module was allowed to see. last_status is the module's last HTTP
-- status, 0 when it never answered.
--
-- Additive only: a new table, its indexes and its RLS policies. The rows go with their install
-- (ON DELETE CASCADE): an uninstalled module has nobody to redeliver to.
-- To reverse:
--   DROP TABLE IF EXISTS module_event_dead_letter;

CREATE TABLE IF NOT EXISTS module_event_dead_letter (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    tenant_module_id character varying(36) NOT NULL,
    event_id character varying(64) NOT NULL,
    payload jsonb NOT NULL,
    last_status integer NOT NULL DEFAULT 0,
    last_error text,
    attempts integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT module_event_dead_letter_pkey PRIMARY KEY (id),
    CONSTRAINT fk_module_event_dead_letter_module FOREIGN KEY (tenant_module_id)
        REFERENCES tenant_module (id) ON DELETE CASCADE,
    -- A redelivered final attempt (the ack lost after the insert) must not dead-letter twice.
    CONSTRAINT uq_module_event_dead_letter_event UNIQUE (tenant_module_id, event_id)
);

-- An install's dead letters, newest first.
CREATE INDEX IF NOT EXISTS idx_module_event_dead_letter_module
    ON module_event_dead_letter (tenant_id, tenant_module_id, created_at DESC);

-- RLS — tenant_isolation (explicit WITH CHECK, as V200) + admin_bypass.
ALTER TABLE module_event_dead_letter ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY module_event_dead_letter FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON module_event_dead_letter;
CREATE POLICY tenant_isolation ON module_event_dead_letter
    FOR ALL
    USING ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true))
    WITH CHECK ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true));

DROP POLICY IF EXISTS admin_bypass ON module_event_dead_letter;
CREATE POLICY admin_bypass ON module_event_dead_letter
    USING ((current_setting('app.current_tenant_id'::text, true) = ''::text));
