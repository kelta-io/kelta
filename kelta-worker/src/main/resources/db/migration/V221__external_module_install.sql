-- External modules part 9c (specs/module-platform/9-external-modules.md §3.11): a tenant_module
-- row can now be an external module — an HTTPS service installed by URL — instead of a JAR.
--
-- 1. base_url is what makes a row external (NULL = a JAR module). protocol_version is the module
--    protocol the install speaks. connected_app_id / service_user_id point at the install's
--    service principal (V220, ModuleServicePrincipalService). settings holds the per-install
--    values validated against the manifest's settings schema. ui_bundle_sha256 / ui_bundle_key
--    pin the admin UI bundle (part 9e fills them).
-- 2. The JAR columns become nullable: an external module has no JAR, checksum, signature or
--    class. Part 9g drops them.
-- 3. version widens to 64 characters: a semantic version with a pre-release and build metadata
--    (2.0.0-rc.1+build.20261030) does not fit in 20.
-- 4. scheduled_job.job_type accepts MODULE_ACTION, the job type manifest schedules become
--    (part 9d executes them).

ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS base_url text;
ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS protocol_version integer;
ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS connected_app_id character varying(36);
ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS service_user_id character varying(36);
ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS settings jsonb;
ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS ui_bundle_sha256 character varying(64);
ALTER TABLE tenant_module ADD COLUMN IF NOT EXISTS ui_bundle_key text;

ALTER TABLE tenant_module ALTER COLUMN jar_checksum DROP NOT NULL;
ALTER TABLE tenant_module ALTER COLUMN module_class DROP NOT NULL;
-- s3_key and jar_signature were already nullable (V1, V186).

ALTER TABLE tenant_module ALTER COLUMN version TYPE character varying(64);

ALTER TABLE scheduled_job DROP CONSTRAINT IF EXISTS chk_job_type;
ALTER TABLE scheduled_job ADD CONSTRAINT chk_job_type
    CHECK (job_type IN ('FLOW', 'SCRIPT', 'REPORT_EXPORT', 'DATA_EXPORT', 'MODULE_ACTION'));

COMMENT ON COLUMN tenant_module.base_url IS
    'External module base URL (module protocol, docs/module-protocol/openapi.yaml); NULL = JAR module';
COMMENT ON COLUMN tenant_module.connected_app_id IS
    'connected_app of the external module service principal (run_as_user_id = service_user_id)';
COMMENT ON COLUMN tenant_module.service_user_id IS
    'SERVICE platform_user the external module calls the public API as';
