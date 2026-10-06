-- The platform's default email templates (portal.invite, portal.login-link, user.welcome,
-- the telehealth and support-mailbox copies, ...) are stored once, under the literal
-- tenant_id 'system', and every reader asks for "the tenant's override, else the system
-- default" with tenant_id IN (<tenant>, 'system'). Those reads run under a bound tenant, so
-- once RLS was enforced (NOBYPASSRLS) the tenant_isolation policy hid the 'system' rows:
-- every tenant without its own copy got "No template found" and the email was silently
-- not sent, and the admin template list and its copy-to-override lost the defaults.
--
-- Same shape as V200's system collections: a SELECT-only policy makes the shared rows
-- readable by every tenant and writable by none. Writes to them stay on the no-tenant path
-- (migrations), which admin_bypass admits. tenant_isolation and admin_bypass are untouched.
CREATE POLICY system_rows_read ON email_template
    FOR SELECT
    USING ((tenant_id)::text = 'system');
