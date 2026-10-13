-- Minimal schema for testing (H2-compatible)

CREATE TABLE IF NOT EXISTS tenant (
    id VARCHAR(36) PRIMARY KEY,
    slug VARCHAR(63) UNIQUE,
    name VARCHAR(200),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS profile (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) REFERENCES tenant(id),
    name VARCHAR(255),
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS platform_user (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenant(id),
    email VARCHAR(320) NOT NULL,
    username VARCHAR(100),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    profile_id VARCHAR(36) REFERENCES profile(id),
    last_login_at TIMESTAMP,
    login_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_credential (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    password_hash VARCHAR(500) NOT NULL,
    password_changed_at TIMESTAMP,
    force_change_on_login BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    reset_token VARCHAR(500),
    reset_token_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uq_user_credential_user UNIQUE (user_id)
);

-- Spring Authorization Server tables (H2 compatible)
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id VARCHAR(100) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    client_id_issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret VARCHAR(200),
    client_secret_expires_at TIMESTAMP,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(1000),
    post_logout_redirect_uris VARCHAR(1000),
    scopes VARCHAR(1000) NOT NULL,
    client_settings VARCHAR(2000) NOT NULL,
    token_settings VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS oauth2_authorization (
    id VARCHAR(100) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes VARCHAR(1000),
    attributes BLOB,
    state VARCHAR(500),
    authorization_code_value BLOB,
    authorization_code_issued_at TIMESTAMP,
    authorization_code_expires_at TIMESTAMP,
    authorization_code_metadata TEXT,
    access_token_value BLOB,
    access_token_issued_at TIMESTAMP,
    access_token_expires_at TIMESTAMP,
    access_token_metadata TEXT,
    access_token_type VARCHAR(100),
    access_token_scopes VARCHAR(1000),
    oidc_id_token_value BLOB,
    oidc_id_token_issued_at TIMESTAMP,
    oidc_id_token_expires_at TIMESTAMP,
    oidc_id_token_metadata TEXT,
    oidc_id_token_claims TEXT,
    refresh_token_value BLOB,
    refresh_token_issued_at TIMESTAMP,
    refresh_token_expires_at TIMESTAMP,
    refresh_token_metadata TEXT,
    user_code_value BLOB,
    user_code_issued_at TIMESTAMP,
    user_code_expires_at TIMESTAMP,
    user_code_metadata TEXT,
    device_code_value BLOB,
    device_code_issued_at TIMESTAMP,
    device_code_expires_at TIMESTAMP,
    device_code_metadata TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorities VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

-- Seed test data
INSERT INTO tenant (id, slug, name) VALUES ('test-tenant-1', 'test', 'Test Tenant');

INSERT INTO profile (id, tenant_id, name, is_system)
VALUES ('test-profile-1', 'test-tenant-1', 'System Administrator', true);

INSERT INTO platform_user (id, tenant_id, email, username, first_name, last_name, profile_id, status)
VALUES ('test-user-1', 'test-tenant-1', 'admin@test.com', 'admin', 'Test', 'Admin', 'test-profile-1', 'ACTIVE');

-- password: 'password123'
INSERT INTO user_credential (id, user_id, password_hash)
VALUES ('test-cred-1', 'test-user-1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy');

CREATE TABLE IF NOT EXISTS password_policy (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    min_length INTEGER DEFAULT 8 NOT NULL,
    max_length INTEGER DEFAULT 128 NOT NULL,
    require_uppercase BOOLEAN DEFAULT FALSE NOT NULL,
    require_lowercase BOOLEAN DEFAULT FALSE NOT NULL,
    require_digit BOOLEAN DEFAULT FALSE NOT NULL,
    require_special BOOLEAN DEFAULT FALSE NOT NULL,
    history_count INTEGER DEFAULT 3 NOT NULL,
    dictionary_check BOOLEAN DEFAULT TRUE NOT NULL,
    personal_data_check BOOLEAN DEFAULT TRUE NOT NULL,
    lockout_threshold INTEGER DEFAULT 5 NOT NULL,
    lockout_duration_minutes INTEGER DEFAULT 30 NOT NULL,
    max_age_days INTEGER,
    mfa_required BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS password_history (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    password_hash VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
