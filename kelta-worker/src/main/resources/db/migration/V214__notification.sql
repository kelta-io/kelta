-- In-app notifications (KLT-664): one row per message a flow's SEND_NOTIFICATION step
-- addresses to a user of the tenant. The recipient reads and acknowledges their own rows
-- through GET /api/me/notifications and POST /api/me/notifications/{id}/read | read-all.
--
-- Additive only: a new table, its index and its RLS policies; no existing table changes.
-- To reverse:
--   DROP TABLE IF EXISTS notification;

CREATE TABLE IF NOT EXISTS notification (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    -- platform_user.id of the recipient. No foreign key: a notification outlives neither
    -- its tenant nor matters once its user is gone, and the writer validates the recipient
    -- against platform_user before inserting.
    recipient_id character varying(36) NOT NULL,
    title character varying(255) NOT NULL,
    body text NOT NULL,
    link character varying(2048),
    level character varying(20) DEFAULT 'INFO' NOT NULL,
    read_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT notification_pkey PRIMARY KEY (id),
    CONSTRAINT notification_level_check CHECK (level IN ('INFO', 'WARNING', 'ERROR'))
);

-- The recipient's list and unread count both filter on exactly these three columns.
CREATE INDEX IF NOT EXISTS idx_notification_recipient
    ON notification (tenant_id, recipient_id, read_at);

-- RLS — tenant_isolation (explicit WITH CHECK, as V200) + admin_bypass.
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY notification FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON notification;
CREATE POLICY tenant_isolation ON notification
    FOR ALL
    USING ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true))
    WITH CHECK ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true));

DROP POLICY IF EXISTS admin_bypass ON notification;
CREATE POLICY admin_bypass ON notification
    USING ((current_setting('app.current_tenant_id'::text, true) = ''::text));
