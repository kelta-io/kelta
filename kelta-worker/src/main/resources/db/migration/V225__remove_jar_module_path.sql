-- Module-platform slice 9g (specs/module-platform/9-external-modules.md): the in-process JAR
-- module path is deleted. Every module is now an external HTTPS service installed by URL, and a
-- tenant_module row is external exactly when base_url is set (V221). The worker no longer has a
-- classloader, a JAR store, a signature verifier or a load-retry loop, so it has nothing that can
-- run a JAR row.
--
-- 1. Refuse to run while any row is still a JAR install (base_url IS NULL), or still holds a
--    JAR-only status (QUARANTINED, STUB). Such a row would be silently orphaned: still listed,
--    its flow actions unregistered, and nothing able to load it. Move it out of process first
--    (install the external service by URL, then uninstall the JAR row), then re-run the deploy.
--    The migrate Job is the ArgoCD PreSync hook with backoffLimit 0, so this exception stops the
--    rollout before any worker pod starts.
-- 2. base_url becomes NOT NULL, so the invariant holds from now on.
-- 3. Drop what only the JAR path read or wrote:
--      tenant_module.jar_checksum, jar_size_bytes, module_class, s3_key, jar_signature,
--      jar_signature_key_fingerprint (and its index), last_loaded_at, load_attempts
--      (per-pod JAR load diagnostics; external installs record last_error / last_error_at);
--      tenant_module_signing_key (V186), with its RLS policies.
--    Kept: every other tenant_module column (source_url holds the install URL), last_error and
--    last_error_at, and tenant_module_action.
-- 4. chk_module_status loses QUARANTINED and STUB, which only the JAR loader wrote (V189).
--
-- tenant_module has FORCE ROW LEVEL SECURITY. The checks select the admin_bypass sentinel
-- explicitly (as V204 and V208 do) so they see every tenant's rows whatever the session carried
-- in; a check that saw no rows would pass for the wrong reason.
--
-- Not reversible in place: the dropped columns held JAR bytes' locations, checksums and
-- signatures that no code reads any more. To restore the JAR path, revert slice 9g's code and
-- re-add the columns and table from V1, V186 and V189.

DO $$
DECLARE
    jar_rows     integer;
    jar_list     text;
    status_rows  integer;
    status_list  text;
BEGIN
    PERFORM set_config('app.current_tenant_id', '', true);

    SELECT count(*), string_agg(tenant_id || '/' || module_id, ', ' ORDER BY tenant_id, module_id)
      INTO jar_rows, jar_list
      FROM tenant_module
     WHERE base_url IS NULL;

    IF jar_rows > 0 THEN
        RAISE EXCEPTION 'V225: % tenant_module row(s) are still JAR module installs (base_url IS NULL): %. '
                        'The JAR module path is removed; install each module as an external service by URL '
                        'and uninstall the JAR row before deploying this release.', jar_rows, jar_list;
    END IF;

    SELECT count(*), string_agg(tenant_id || '/' || module_id || ' (' || status || ')', ', '
                                ORDER BY tenant_id, module_id)
      INTO status_rows, status_list
      FROM tenant_module
     WHERE status IN ('QUARANTINED', 'STUB');

    IF status_rows > 0 THEN
        RAISE EXCEPTION 'V225: % tenant_module row(s) hold a JAR-only status: %. '
                        'Disable or uninstall them before deploying this release.', status_rows, status_list;
    END IF;
END $$;

ALTER TABLE tenant_module ALTER COLUMN base_url SET NOT NULL;

DROP INDEX IF EXISTS idx_tenant_module_signature_key;

ALTER TABLE tenant_module
    DROP COLUMN IF EXISTS jar_checksum,
    DROP COLUMN IF EXISTS jar_size_bytes,
    DROP COLUMN IF EXISTS module_class,
    DROP COLUMN IF EXISTS s3_key,
    DROP COLUMN IF EXISTS jar_signature,
    DROP COLUMN IF EXISTS jar_signature_key_fingerprint,
    DROP COLUMN IF EXISTS last_loaded_at,
    DROP COLUMN IF EXISTS load_attempts;

DROP TABLE IF EXISTS tenant_module_signing_key;

ALTER TABLE tenant_module DROP CONSTRAINT IF EXISTS chk_module_status;
ALTER TABLE tenant_module ADD CONSTRAINT chk_module_status
    CHECK (status IN ('INSTALLING', 'INSTALLED', 'ACTIVE', 'DISABLED', 'FAILED',
                      'UNINSTALLING', 'DEGRADED'));

COMMENT ON COLUMN tenant_module.base_url IS
    'External module base URL (module protocol, docs/module-protocol/openapi.yaml); every install has one';
