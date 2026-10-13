-- Indexes for the two kelta-auth statements that read oauth2_authorization by something other
-- than its primary key (Spring Authorization Server's own lookups are by id or token value).
--
-- 1. Signing a user out (io.kelta.auth.session.UserSessionRevoker) finds the user's rows by
--    login name, case-insensitively: WHERE lower(principal_name) IN (lower(?), lower(?)).
-- 2. The retention sweep (io.kelta.auth.service.AuthorizationRetentionSweep) selects rows whose
--    latest token expiry is before the cutoff: WHERE GREATEST(<six expiry columns>) < ?, alongside
--    its per-token conditions. The expression below must stay identical to the sweep's.
--
-- Plain CREATE INDEX (no CONCURRENTLY): Flyway runs each migration in a transaction, and the
-- table is kept small by the retention sweep.

CREATE INDEX IF NOT EXISTS idx_oauth2_authorization_principal_name_lower
    ON oauth2_authorization (lower(principal_name));

CREATE INDEX IF NOT EXISTS idx_oauth2_authorization_latest_expiry
    ON oauth2_authorization (GREATEST(authorization_code_expires_at, access_token_expires_at,
                                      oidc_id_token_expires_at, refresh_token_expires_at,
                                      device_code_expires_at, user_code_expires_at));
