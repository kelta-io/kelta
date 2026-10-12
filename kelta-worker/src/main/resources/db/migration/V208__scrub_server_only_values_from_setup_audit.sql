-- AuditBeforeSaveHook serialized the whole saved record into setup_audit_trail, so OIDC
-- provider entries carried the stored client-secret ciphertext (clientSecretEnc). That field
-- is now server-only (FieldDefinition.serverOnly) and the hook drops server-only fields before
-- writing; this removes the values already written. setup_audit_trail is readable through the
-- setup-audit-entries collection.
--
-- oidc-providers is the only audited collection with a server-only field. The audit rows keep
-- everything else.
--
-- Select the admin_bypass sentinel explicitly so the statement sees every tenant's rows
-- whatever the session carried in (setup_audit_trail is FORCE ROW LEVEL SECURITY).
DO $$
DECLARE
    affected_rows integer;
BEGIN
    PERFORM set_config('app.current_tenant_id', '', true);

    UPDATE setup_audit_trail
       SET new_value = new_value - 'clientSecretEnc'
     WHERE entity_type = 'oidc-provider'
       AND new_value ? 'clientSecretEnc';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;

    UPDATE setup_audit_trail
       SET old_value = old_value - 'clientSecretEnc'
     WHERE entity_type = 'oidc-provider'
       AND old_value ? 'clientSecretEnc';

    RAISE NOTICE 'V208: removed server-only values from % setup audit row(s)', affected_rows;
END $$;
