-- KLT-817: anonymizing a user (POST /api/admin/users/{id}/anonymize, flow action ANONYMIZE_USER)
-- scrubs the platform_user row's personal data but keeps its id, so the records the user created
-- or owns keep pointing at it. The row is left in the terminal status ANONYMIZED, which every
-- kelta-auth sign-in path refuses (they all require status = 'ACTIVE').
--
-- setup_audit_trail gains the matching ANONYMIZED action verb for the audit row each anonymize
-- writes: SetupAuditService swallows insert failures, and inside the anonymize transaction a
-- rejected insert would abort the whole write.

ALTER TABLE platform_user DROP CONSTRAINT IF EXISTS chk_user_status;

ALTER TABLE platform_user ADD CONSTRAINT chk_user_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED', 'PENDING_ACTIVATION', 'ANONYMIZED'));

ALTER TABLE setup_audit_trail DROP CONSTRAINT IF EXISTS chk_audit_action;

ALTER TABLE setup_audit_trail ADD CONSTRAINT chk_audit_action
    CHECK (action IN ('CREATED', 'UPDATED', 'DELETED', 'ACTIVATED', 'DEACTIVATED',
                      'CREDENTIAL_RESOLVE', 'ANONYMIZED'));
