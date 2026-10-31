-- Module-platform slice 9g, CONTRACT half (specs/module-platform/9-external-modules.md §4;
-- concerns.md -> Module-platform 9g rollout). V225 was the expand half: it refused to run while a
-- JAR install was left, made base_url NOT NULL and narrowed chk_module_status to the external
-- statuses, and dropped nothing, because the previous worker image still read every JAR column
-- while the rollout replaced it.
--
-- Deploy this only after the release carrying V225 is deployed and no worker pod still runs the
-- image from before it. That image maps these columns by name after SELECT * on every
-- tenant_module read, so once this has run it fails every module read, and it can no longer be
-- rolled back to.
--
-- No JAR row can exist here: V225 refused to run while one did, and base_url NOT NULL plus the
-- narrowed chk_module_status have refused one since. Nothing in the current code reads or writes
-- what is dropped below.
--
-- Drops:
--   tenant_module.jar_checksum, jar_size_bytes, module_class, s3_key, jar_signature,
--   jar_signature_key_fingerprint (and idx_tenant_module_signature_key), last_loaded_at,
--   load_attempts;
--   tenant_module_signing_key (V186), with its RLS policies.
-- Kept: every other tenant_module column (source_url holds the install URL), last_error and
-- last_error_at, and tenant_module_action.
--
-- Not reversible in place: the dropped columns held JAR locations, checksums and signatures that
-- no code reads any more. To restore the JAR path, revert slice 9g's code and re-add the columns
-- and table from V1, V186 and V189.

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
