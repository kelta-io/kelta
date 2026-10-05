-- Until PLT-337, TenantProvisioningHook seeded every runtime-provisioned tenant's admin
-- (<slug>-admin@kelta.local) with a bare BCrypt hash of the literal "password" and
-- force_change_on_login = true. kelta-auth still matches prefix-less bcrypt hashes, so an
-- admin nobody had logged in as yet could be taken over by anyone who knew the pattern.
--
-- Swap that hash for the unusable empty value UserInviteService writes for invite-only
-- users. The predicate is exact on purpose:
--   * a credential whose password was changed no longer carries the default hash;
--   * force_change_on_login = false means the hash is deliberate (the Flyway baseline's
--     platform admin, test-only users), so it is left alone here — see concerns.md.
-- The affected admins are claimed through Setup › Tenants › Invite admin
-- (POST /api/tenants/{id}/admin-invite).
--
-- user_credential has no tenant_id; its RLS policy reaches the tenant through
-- platform_user (V202). Select the admin_bypass sentinel explicitly so the statement sees
-- every tenant's rows whatever the session carried in.
DO $$
DECLARE
    default_hash CONSTANT text := '$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6';
    affected_tenants text;
    affected_rows    integer;
BEGIN
    PERFORM set_config('app.current_tenant_id', '', true);

    SELECT string_agg(DISTINCT pu.tenant_id::text, ', ')
      INTO affected_tenants
      FROM user_credential uc
      JOIN platform_user pu ON pu.id = uc.user_id
     WHERE uc.password_hash = default_hash
       AND uc.force_change_on_login = true;

    UPDATE user_credential
       SET password_hash = '',
           updated_at = NOW()
     WHERE password_hash = default_hash
       AND force_change_on_login = true;
    GET DIAGNOSTICS affected_rows = ROW_COUNT;

    RAISE NOTICE 'V204: invalidated % default tenant-admin credential(s); tenants: %',
        affected_rows, COALESCE(affected_tenants, '(none)');
END $$;
