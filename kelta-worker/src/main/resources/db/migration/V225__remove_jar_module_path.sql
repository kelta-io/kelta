-- Module-platform slice 9g (specs/module-platform/9-external-modules.md): the in-process JAR
-- module path is deleted. Every module is now an external HTTPS service installed by URL, and a
-- tenant_module row is external exactly when base_url is set (V221). The worker no longer has a
-- classloader, a JAR store, a signature verifier or a load-retry loop, so it has nothing that can
-- run a JAR row.
--
-- This is the EXPAND half of a two-step change. It only adds guarantees; it drops nothing,
-- because the previous worker image is still serving while the rollout replaces it, and that
-- image reads every JAR column on every module path (its row mapper reads them by name after
-- SELECT *, its install INSERT lists them, and its module list and health read last_loaded_at
-- and load_attempts). The contract migration that drops them ships separately, once no
-- old-image worker pod is left (concerns.md -> Module-platform 9g rollout).
--
-- 1. Refuse to run while any row is still a JAR install (base_url IS NULL), or still holds a
--    JAR-only status (QUARANTINED, STUB). Such a row would be silently orphaned: still listed,
--    its flow actions unregistered, and nothing able to load it. Move it out of process first
--    (install the external service by URL, then uninstall the JAR row), then re-run the deploy.
--    The migrate Job is the ArgoCD PreSync hook with backoffLimit 0, so this exception stops the
--    rollout before any worker pod starts.
-- 2. base_url becomes NOT NULL, so no JAR row can be created again, not even by an old pod during
--    the rollout. The previous image writes base_url NULL only on its JAR install paths
--    (POST /api/modules/install without a baseUrl, POST /api/modules/install-jar); this release
--    refuses both anyway. Its external install always sets base_url.
-- 3. chk_module_status loses QUARANTINED and STUB. The previous image writes them only from its
--    JAR loader, which returns before writing anything for an external row; with step 1 and
--    step 2 no JAR row can reach it.
--
-- Left in place for the contract migration, unread and unwritten by this release:
--   tenant_module.jar_checksum, jar_size_bytes, module_class, s3_key, jar_signature,
--   jar_signature_key_fingerprint (and idx_tenant_module_signature_key), last_loaded_at,
--   load_attempts; the tenant_module_signing_key table (V186). All are nullable or defaulted,
--   so this release's INSERT, which no longer lists them, still succeeds.
--
-- tenant_module has FORCE ROW LEVEL SECURITY. The checks select the admin_bypass sentinel
-- explicitly (as V204 and V208 do) so they see every tenant's rows whatever the session carried
-- in; a check that saw no rows would pass for the wrong reason.

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

ALTER TABLE tenant_module DROP CONSTRAINT IF EXISTS chk_module_status;
ALTER TABLE tenant_module ADD CONSTRAINT chk_module_status
    CHECK (status IN ('INSTALLING', 'INSTALLED', 'ACTIVE', 'DISABLED', 'FAILED',
                      'UNINSTALLING', 'DEGRADED'));

COMMENT ON COLUMN tenant_module.base_url IS
    'External module base URL (module protocol, docs/module-protocol/openapi.yaml); every install has one';
