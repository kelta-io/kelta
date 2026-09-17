package io.kelta.worker.listener;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.worker.service.CerbosPolicySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Before-save hook for the "tenants" system collection that provisions
 * a new tenant with default profiles, an internal OIDC provider, and
 * an admin user after the tenant record is created.
 *
 * <p>Runs after the TenantLifecycleHook (order 0, schema creation) and
 * before SvixTenantLifecycleHook (order 200) and SupersetTenantLifecycleHook
 * (order 300).
 *
 * <p>After provisioning completes, the tenant status is transitioned from
 * PROVISIONING to ACTIVE. If any step fails, the tenant remains in
 * PROVISIONING status for manual intervention.
 *
 * @since 1.0.0
 */
public class TenantProvisioningHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningHook.class);

    /** BCrypt hash of "password" — same as V102 migration. */
    private static final String DEFAULT_PASSWORD_HASH =
            "$2a$10$zAQaSHX1XSR1bwUL3pz9EOzecplsxInVizZc9HwLf7xPluSiE1EP6";

    /** Visible for testing: the provisioning row count is derived from this. */
    static final List<String> ALL_PERMISSIONS = List.of(
            "VIEW_SETUP", "CUSTOMIZE_APPLICATION", "MANAGE_USERS", "MANAGE_GROUPS",
            "MANAGE_SHARING", "MANAGE_WORKFLOWS", "MANAGE_REPORTS", "MANAGE_EMAIL_TEMPLATES",
            "MANAGE_CONNECTED_APPS", "MANAGE_DATA", "API_ACCESS", "VIEW_ALL_DATA",
            "MODIFY_ALL_DATA", "MANAGE_APPROVALS", "MANAGE_LISTVIEWS", "MANAGE_TENANTS",
            "MANAGE_CREDENTIALS", "VIEW_CREDENTIALS",
            "MANAGE_API_SPECS", "VIEW_API_SPECS", "MANAGE_CAMPAIGNS",
            "MANAGE_DELEGATED_ADMINS", "MANAGE_SANDBOXES", "VIEW_ANALYTICS",
            "MANAGE_CHAT", "MANAGE_BILLING",
            "VIEW_SUPPORT_MAILBOX", "MANAGE_SUPPORT_MAILBOX"
    );

    private final JdbcTemplate jdbcTemplate;
    private final String authIssuerUri;
    private final CerbosPolicySyncService cerbosPolicySyncService;

    public TenantProvisioningHook(JdbcTemplate jdbcTemplate,
                                   String authIssuerUri,
                                   CerbosPolicySyncService cerbosPolicySyncService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authIssuerUri = authIssuerUri;
        this.cerbosPolicySyncService = cerbosPolicySyncService;
    }

    @Override
    public String getCollectionName() {
        return "tenants";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        String id = getString(record, "id");
        String slug = getString(record, "slug");
        if (id == null) {
            log.warn("Tenant provisioning skipped: no id in record");
            return;
        }

        // Every row below belongs to the tenant being created, not to the tenant whose
        // admin created it, so the connection must carry the new tenant's id or row-level
        // security refuses the writes outright. The ScopedValue binding is what
        // TenantContext.get() reads (the legacy ThreadLocal set() is only consulted when no
        // ScopedValue is bound — on this path the request filter has already bound the
        // *creating* tenant, so set() would be silently ignored and every insert here would
        // be rejected by the tenant_isolation WITH CHECK).
        Runnable provision = () -> {
            try {
                seedDefaultProfiles(id);
                seedOidcProvider(id);
                seedAdminUser(id, slug);
                syncCerbosPolicies(id);
                activateTenant(id);
                log.info("Tenant provisioning complete for tenant '{}' (slug={})", id, slug);
            } catch (Exception e) {
                log.error("Tenant provisioning failed for tenant '{}' (slug={}): {}",
                        id, slug, e.getMessage(), e);
            }
        };
        // The slug goes in the scope too where there is one: leaving the creating tenant's
        // slug bound would point any schema-qualified write at the wrong tenant's schema.
        if (slug != null) {
            TenantContext.runWithTenant(id, slug, provision);
        } else {
            TenantContext.runWithTenant(id, provision);
        }
    }

    /**
     * Pushes Cerbos policies for the newly seeded profiles. Required because
     * seedDefaultProfiles writes directly via JDBC and bypasses the profiles
     * collection BeforeSaveHook that normally triggers sync. Without this,
     * gateway API_ACCESS checks fail-closed for the new tenant.
     */
    void syncCerbosPolicies(String tenantId) {
        if (cerbosPolicySyncService == null) {
            log.debug("CerbosPolicySyncService not configured, skipping policy sync for tenant {}", tenantId);
            return;
        }
        cerbosPolicySyncService.syncTenant(tenantId);
    }

    /**
     * Seeds the 7 default profiles with system permissions for the tenant.
     * Mirrors the logic from V55 migration.
     */
    void seedDefaultProfiles(String tenantId) {
        // Check if profiles already exist for this tenant
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM profile WHERE tenant_id = ?",
                Integer.class, tenantId);
        if (count != null && count > 0) {
            log.debug("Profiles already exist for tenant {}, skipping", tenantId);
            return;
        }

        // Profile definitions: name, description, granted permissions
        List<ProfileDef> profileDefs = List.of(
                new ProfileDef("System Administrator",
                        "Full, unrestricted access to all features and data",
                        Set.of("VIEW_SETUP", "CUSTOMIZE_APPLICATION", "MANAGE_USERS", "MANAGE_GROUPS",
                                "MANAGE_SHARING", "MANAGE_WORKFLOWS", "MANAGE_REPORTS", "MANAGE_EMAIL_TEMPLATES",
                                "MANAGE_CONNECTED_APPS", "MANAGE_DATA", "API_ACCESS", "VIEW_ALL_DATA",
                                "MODIFY_ALL_DATA", "MANAGE_APPROVALS", "MANAGE_LISTVIEWS",
                                "MANAGE_CREDENTIALS", "VIEW_CREDENTIALS",
                                "MANAGE_API_SPECS", "VIEW_API_SPECS", "MANAGE_CAMPAIGNS",
                                "MANAGE_DELEGATED_ADMINS", "MANAGE_SANDBOXES", "VIEW_ANALYTICS",
                                "MANAGE_CHAT", "MANAGE_BILLING",
                                "VIEW_SUPPORT_MAILBOX", "MANAGE_SUPPORT_MAILBOX")),
                new ProfileDef("Standard User",
                        "Read, create, and edit records in all collections",
                        Set.of("API_ACCESS", "MANAGE_LISTVIEWS", "VIEW_CREDENTIALS", "VIEW_API_SPECS",
                                "VIEW_ANALYTICS")),
                new ProfileDef("Read Only",
                        "View all records and reports, no create/edit/delete capability",
                        Set.of("VIEW_ALL_DATA", "VIEW_ANALYTICS")),
                new ProfileDef("Marketing User",
                        "Standard User plus manage email templates and campaigns",
                        Set.of("API_ACCESS", "MANAGE_LISTVIEWS", "MANAGE_EMAIL_TEMPLATES", "MANAGE_CAMPAIGNS",
                                "VIEW_ANALYTICS")),
                new ProfileDef("Contract Manager",
                        "Standard User plus manage approval processes",
                        Set.of("API_ACCESS", "MANAGE_LISTVIEWS", "MANAGE_APPROVALS", "VIEW_ANALYTICS")),
                new ProfileDef("Solution Manager",
                        "Customize application structure: collections, fields, layouts, picklists, reports",
                        Set.of("VIEW_SETUP", "CUSTOMIZE_APPLICATION", "MANAGE_REPORTS",
                                "MANAGE_WORKFLOWS", "MANAGE_LISTVIEWS", "API_ACCESS", "VIEW_ANALYTICS")),
                new ProfileDef("Minimum Access",
                        "Login only, no data access until explicitly granted via Permission Sets",
                        Set.of()),
                new ProfileDef("Portal User",
                        "External portal user (telehealth patient) — login and API access only; data access is granted per record via participant shares",
                        Set.of("API_ACCESS"))
        );

        for (ProfileDef def : profileDefs) {
            String profileId = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                    INSERT INTO profile (id, tenant_id, name, description, is_system, created_at, updated_at)
                    VALUES (?, ?, ?, ?, TRUE, NOW(), NOW())
                    """, profileId, tenantId, def.name, def.description);

            for (String perm : ALL_PERMISSIONS) {
                jdbcTemplate.update("""
                        INSERT INTO profile_system_permission (id, tenant_id, profile_id, permission_name, granted)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), tenantId, profileId, perm, def.grantedPermissions.contains(perm));
            }
        }

        log.info("Seeded {} default profiles for tenant {}", profileDefs.size(), tenantId);
    }

    /**
     * Creates the internal OIDC provider record for the tenant.
     * Mirrors the logic from V101 migration.
     */
    void seedOidcProvider(String tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oidc_provider WHERE tenant_id = ? AND issuer = ?",
                Integer.class, tenantId, authIssuerUri);
        if (count != null && count > 0) {
            log.debug("OIDC provider already exists for tenant {} with issuer {}, skipping",
                    tenantId, authIssuerUri);
            return;
        }

        String jwksUri = authIssuerUri.endsWith("/")
                ? authIssuerUri + "oauth2/jwks"
                : authIssuerUri + "/oauth2/jwks";

        jdbcTemplate.update("""
                INSERT INTO oidc_provider (id, tenant_id, name, issuer, jwks_uri, client_id, active, is_internal, created_at, updated_at)
                VALUES (?, ?, 'Kelta Platform (Internal)', ?, ?, 'kelta-platform', TRUE, TRUE, NOW(), NOW())
                """, UUID.randomUUID().toString(), tenantId, authIssuerUri, jwksUri);

        log.info("Created internal OIDC provider for tenant {} (issuer={})", tenantId, authIssuerUri);
    }

    /**
     * Creates the default admin user and credential for the tenant.
     * Mirrors the logic from V102 migration.
     */
    void seedAdminUser(String tenantId, String slug) {
        String adminEmail = (slug != null ? slug : "admin") + "-admin@kelta.local";

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_user WHERE tenant_id = ? AND email = ?",
                Integer.class, tenantId, adminEmail);
        if (count != null && count > 0) {
            log.debug("Admin user {} already exists for tenant {}, skipping", adminEmail, tenantId);
            return;
        }

        // Find the System Administrator profile for this tenant
        List<String> profileIds = jdbcTemplate.queryForList(
                "SELECT id FROM profile WHERE tenant_id = ? AND name = 'System Administrator' LIMIT 1",
                String.class, tenantId);
        if (profileIds.isEmpty()) {
            log.warn("No System Administrator profile found for tenant {}, skipping admin user creation", tenantId);
            return;
        }
        String adminProfileId = profileIds.get(0);

        String adminUsername = (slug != null ? slug : "admin") + "-admin";
        String userId = UUID.randomUUID().toString();

        jdbcTemplate.update("""
                INSERT INTO platform_user (id, tenant_id, email, username, first_name, last_name,
                    status, profile_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'System', 'Administrator', 'ACTIVE', ?, NOW(), NOW())
                """, userId, tenantId, adminEmail, adminUsername, adminProfileId);

        jdbcTemplate.update("""
                INSERT INTO user_credential (id, user_id, password_hash, force_change_on_login, created_at)
                VALUES (?, ?, ?, TRUE, NOW())
                """, UUID.randomUUID().toString(), userId, DEFAULT_PASSWORD_HASH);

        log.info("Created admin user '{}' for tenant {} (slug={})", adminEmail, tenantId, slug);
    }

    /**
     * Transitions the tenant from PROVISIONING to ACTIVE.
     */
    void activateTenant(String tenantId) {
        int updated = jdbcTemplate.update(
                "UPDATE tenant SET status = 'ACTIVE', updated_at = NOW() WHERE id = ? AND status = 'PROVISIONING'",
                tenantId);
        if (updated > 0) {
            log.info("Tenant {} activated (PROVISIONING → ACTIVE)", tenantId);
        } else {
            log.debug("Tenant {} not in PROVISIONING status, skipping activation", tenantId);
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private record ProfileDef(String name, String description, Set<String> grantedPermissions) {}
}
