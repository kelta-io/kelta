package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for bootstrap-related database queries.
 *
 * <p>Encapsulates SQL access for collections, tenants, OIDC providers,
 * and permission resolution used by the gateway bootstrap endpoints.
 */
@Repository
public class BootstrapRepository {

    private static final String SELECT_ACTIVE_COLLECTIONS = """
            SELECT id, name, path, system_collection, tenant_id
            FROM collection WHERE active = true
            """;

    private static final String SELECT_ROUTABLE_TENANTS = """
            SELECT id, slug FROM tenant
            WHERE status != 'DECOMMISSIONED' AND slug IS NOT NULL
            """;

    private static final String SELECT_TENANT_LIMITS = """
            SELECT id, limits FROM tenant
            WHERE status NOT IN ('DECOMMISSIONED', 'SUSPENDED')
            """;

    private static final String SELECT_TENANT_IP_ALLOWLISTS = """
            SELECT id, ip_allowlist_enabled, ip_allowlist_cidrs FROM tenant
            WHERE status NOT IN ('DECOMMISSIONED', 'SUSPENDED')
            """;

    private static final String SELECT_OIDC_PROVIDER_BY_ISSUER = """
            SELECT id, name, issuer, jwks_uri, audience, active,
                   client_id, client_secret_enc, roles_claim, roles_mapping,
                   groups_claim, groups_profile_mapping,
                   authorization_uri, token_uri, userinfo_uri, end_session_uri,
                   discovery_status
            FROM oidc_provider WHERE issuer = ? AND active = true
            LIMIT 1
            """;

    private static final String SELECT_OIDC_PROVIDER_BY_ISSUER_AND_TENANT = """
            SELECT id, name, issuer, jwks_uri, audience, active,
                   client_id, client_secret_enc, roles_claim, roles_mapping,
                   groups_claim, groups_profile_mapping,
                   authorization_uri, token_uri, userinfo_uri, end_session_uri,
                   discovery_status
            FROM oidc_provider WHERE issuer = ? AND tenant_id = ? AND active = true
            LIMIT 1
            """;

    private static final String SELECT_ACTIVE_OIDC_PROVIDERS_BY_TENANT = """
            SELECT id, name, issuer, jwks_uri, audience, active,
                   client_id, client_secret_enc, roles_claim, roles_mapping,
                   groups_claim, groups_profile_mapping,
                   authorization_uri, token_uri, userinfo_uri, end_session_uri,
                   discovery_status, email_claim, username_claim, name_claim
            FROM oidc_provider WHERE tenant_id = ? AND active = true
            """;

    private static final String SELECT_ACTIVE_SAML_PROVIDERS_BY_TENANT = """
            SELECT id, name, registration_id, idp_entity_id, sso_url, slo_url, idp_certificate,
                   name_id_format, email_attribute, profile_attribute, active
            FROM saml_provider WHERE tenant_id = ? AND active = true
            """;

    private static final String SELECT_SAML_PROVIDER_BY_ENTITY_ID_AND_TENANT = """
            SELECT id, name, registration_id, idp_entity_id, sso_url, slo_url, idp_certificate,
                   name_id_format, email_attribute, profile_attribute, active
            FROM saml_provider WHERE idp_entity_id = ? AND tenant_id = ? AND active = true
            LIMIT 1
            """;

    private static final String SELECT_USER_BY_EMAIL_ANY_STATUS = """
            SELECT id, profile_id, status FROM platform_user
            WHERE email = ? AND tenant_id = ?
            LIMIT 1
            """;

    private static final String SELECT_USER_BY_USERNAME_ANY_STATUS = """
            SELECT id, profile_id, status FROM platform_user
            WHERE username = ? AND tenant_id = ?
            LIMIT 1
            """;

    private static final String SELECT_USER_BY_EMAIL = """
            SELECT id, profile_id FROM platform_user
            WHERE email = ? AND tenant_id = ? AND status = 'ACTIVE'
            LIMIT 1
            """;

    private static final String SELECT_USER_IDENTITY = """
            SELECT u.id, u.profile_id, p.name AS profile_name
            FROM platform_user u
            LEFT JOIN profile p ON u.profile_id = p.id
            WHERE u.email = ? AND u.tenant_id = ? AND u.status = 'ACTIVE'
            LIMIT 1
            """;

    private static final String SELECT_PROFILE_SYSTEM_PERMISSIONS = """
            SELECT permission_name, granted
            FROM profile_system_permission WHERE profile_id = ?
            """;

    private static final String SELECT_PROFILE_OBJECT_PERMISSIONS = """
            SELECT collection_id, can_create, can_read, can_edit, can_delete
            FROM profile_object_permission WHERE profile_id = ?
            """;

    // Resolves collection/field UUIDs to API names: the UI stores row UUIDs (V100
    // FKs to collection.id / field.id guarantee they are UUIDs), but Cerbos
    // field-policy CEL is matched against the collection name (URL path segment) and
    // field name (JSON:API attribute key) the worker advices pass. COALESCE is a
    // defensive fallback only — under the FKs the joins always resolve.
    private static final String SELECT_PROFILE_FIELD_PERMISSIONS = """
            SELECT COALESCE(c.name, pfp.collection_id) AS collection_id,
                   COALESCE(f.name, pfp.field_id) AS field_id,
                   pfp.visibility
            FROM profile_field_permission pfp
            LEFT JOIN field f ON f.id = pfp.field_id
            LEFT JOIN collection c ON c.id = pfp.collection_id
            WHERE pfp.profile_id = ?
            """;

    private static final String SELECT_USER_GROUP_IDS = """
            SELECT group_id FROM group_membership
            WHERE member_type = 'USER' AND member_id = ?
            """;

    private static final String SELECT_PROFILE_BY_NAME = """
            SELECT id, name FROM profile
            WHERE tenant_id = ? AND name = ?
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public BootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    public List<Map<String, Object>> findActiveCollections() {
        return jdbcTemplate.queryForList(SELECT_ACTIVE_COLLECTIONS);
    }

    public List<Map<String, Object>> findRoutableTenants() {
        return jdbcTemplate.queryForList(SELECT_ROUTABLE_TENANTS);
    }

    public List<Map<String, Object>> findTenantLimits() {
        return jdbcTemplate.queryForList(SELECT_TENANT_LIMITS);
    }

    /**
     * Returns per-tenant IP allowlist configuration for all routable tenants.
     * Each row has {@code id}, {@code ip_allowlist_enabled}, and {@code ip_allowlist_cidrs}
     * (a JSONB array of CIDR strings). Consumed by the gateway to enforce network access.
     */
    public List<Map<String, Object>> findTenantIpAllowlists() {
        return jdbcTemplate.queryForList(SELECT_TENANT_IP_ALLOWLISTS);
    }

    public Optional<Map<String, Object>> findOidcProviderByIssuer(String issuer) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_OIDC_PROVIDER_BY_ISSUER, issuer);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findOidcProviderByIssuerAndTenant(String issuer, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_OIDC_PROVIDER_BY_ISSUER_AND_TENANT, issuer, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> findActiveOidcProvidersByTenant(String tenantId) {
        return jdbcTemplate.queryForList(SELECT_ACTIVE_OIDC_PROVIDERS_BY_TENANT, tenantId);
    }

    public List<Map<String, Object>> findActiveSamlProvidersByTenant(String tenantId) {
        return jdbcTemplate.queryForList(SELECT_ACTIVE_SAML_PROVIDERS_BY_TENANT, tenantId);
    }

    public Optional<Map<String, Object>> findSamlProviderByEntityIdAndTenant(String entityId, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_SAML_PROVIDER_BY_ENTITY_ID_AND_TENANT, entityId, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findUserByEmailAnyStatus(String email, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_USER_BY_EMAIL_ANY_STATUS, email, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findUserByUsernameAnyStatus(String username, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_USER_BY_USERNAME_ANY_STATUS, username, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findActiveUserByEmail(String email, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_USER_BY_EMAIL, email, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> findProfileSystemPermissions(String profileId) {
        return jdbcTemplate.queryForList(SELECT_PROFILE_SYSTEM_PERMISSIONS, profileId);
    }

    public List<Map<String, Object>> findProfileObjectPermissions(String profileId) {
        return jdbcTemplate.queryForList(SELECT_PROFILE_OBJECT_PERMISSIONS, profileId);
    }

    public List<Map<String, Object>> findProfileFieldPermissions(String profileId) {
        return jdbcTemplate.queryForList(SELECT_PROFILE_FIELD_PERMISSIONS, profileId);
    }

    public List<Map<String, Object>> findUserGroupIds(String userId) {
        return jdbcTemplate.queryForList(SELECT_USER_GROUP_IDS, userId);
    }

    public Optional<Map<String, Object>> findUserIdentity(String email, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_USER_IDENTITY, email, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Resolves a profile by its display name, scoped to the given tenant. Used
     * by kelta-auth's {@link io.kelta.auth.federation.FederatedUserMapper} to
     * translate OIDC group-to-profile mapping values into stable profile IDs
     * before JIT-provisioning a user.
     */
    public Optional<Map<String, Object>> findProfileByName(String name, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_PROFILE_BY_NAME, tenantId, name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
