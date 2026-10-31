-- External modules part 9b (specs/module-platform/9-external-modules.md §3.3): the module
-- service principal.
--
-- 1. platform_user.user_type gains SERVICE: a machine principal that owns a profile (so Cerbos,
--    RLS and created_by audit work unchanged) but never signs in interactively, never receives
--    mail, is hidden from the default Users list and does not count against the `users`
--    governor limit.
-- 2. connected_app.run_as_user_id binds a client_credentials app to a SERVICE user. kelta-auth
--    issues such an app's tokens with that user's identity and profile; an app without it keeps
--    the plain machine token (no profile). Written only by the worker over JDBC
--    (ModuleServicePrincipalService) — it is not a field of the connected-apps collection, so the
--    generic API can neither read nor set it.
-- 3. connected_app.client_secret_enc keeps the app's plaintext client secret encrypted
--    (EncryptionService, AES-256-GCM) for the worker, which must request tokens with it;
--    connected_app otherwise stores only client_secret_hash. Like run_as_user_id it is JDBC-only:
--    not a field of the connected-apps collection, so no generic read path can serve it.

ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS platform_user_user_type_check;

ALTER TABLE platform_user ADD CONSTRAINT platform_user_user_type_check
    CHECK (user_type IN ('INTERNAL', 'PORTAL', 'SERVICE'));

ALTER TABLE connected_app ADD COLUMN IF NOT EXISTS run_as_user_id character varying(36);

ALTER TABLE connected_app ADD COLUMN IF NOT EXISTS client_secret_enc text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_connected_app_run_as_user') THEN
        ALTER TABLE connected_app
            ADD CONSTRAINT fk_connected_app_run_as_user
            FOREIGN KEY (run_as_user_id) REFERENCES platform_user(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_connected_app_run_as_user
    ON connected_app (run_as_user_id) WHERE run_as_user_id IS NOT NULL;

COMMENT ON COLUMN connected_app.run_as_user_id IS
    'SERVICE platform_user whose identity and profile client_credentials tokens carry (module service principal); NULL = plain machine token';
COMMENT ON COLUMN connected_app.client_secret_enc IS
    'EncryptionService ciphertext of the plaintext client secret, kept for the worker to request tokens; server-only';
