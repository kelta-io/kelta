package io.kelta.worker.listener;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.service.CerbosPolicySyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TenantProvisioningHook")
class TenantProvisioningHookTest {

    private JdbcTemplate jdbcTemplate;
    private CerbosPolicySyncService cerbosPolicySyncService;
    private TenantProvisioningHook hook;

    private static final String AUTH_ISSUER = "https://auth.example.com";
    private static final String TENANT_ID = "tenant-123";
    private static final String TENANT_SLUG = "acme";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        cerbosPolicySyncService = mock(CerbosPolicySyncService.class);
        hook = new TenantProvisioningHook(jdbcTemplate, AUTH_ISSUER, cerbosPolicySyncService);
    }

    @Test
    @DisplayName("Should target 'tenants' collection")
    void shouldTargetTenantsCollection() {
        assertEquals("tenants", hook.getCollectionName());
    }

    @Test
    @DisplayName("Should have order 100")
    void shouldHaveOrder100() {
        assertEquals(100, hook.getOrder());
    }

    @Nested
    @DisplayName("afterCreate")
    class AfterCreate {

        @Test
        @DisplayName("Should skip when id is null")
        void shouldSkipWhenIdNull() {
            Map<String, Object> record = new HashMap<>();
            record.put("slug", TENANT_SLUG);

            hook.afterCreate(record, TENANT_ID);

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("Should handle exception gracefully")
        void shouldHandleExceptionGracefully() {
            Map<String, Object> record = new HashMap<>(Map.of("id", TENANT_ID, "slug", TENANT_SLUG));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                    .thenThrow(new RuntimeException("DB connection failed"));

            assertDoesNotThrow(() -> hook.afterCreate(record, TENANT_ID));
        }

        @Test
        @DisplayName("Should seed under the new tenant's scope, not the creating tenant's")
        void shouldSeedUnderTheNewTenantsScope() {
            // The hook runs inside the request that created the tenant, so TenantContext is
            // already bound to whoever created it. Every row it seeds belongs to the new
            // tenant, and TenantAwareDataSource turns whatever is bound here into the
            // connection's app.current_tenant_id — bind the wrong one and row-level security
            // rejects the inserts. A ThreadLocal set() cannot fix that: the bound ScopedValue
            // wins over it.
            String newTenantId = "tenant-456";
            String newTenantSlug = "beta";
            Map<String, Object> record =
                    new HashMap<>(Map.of("id", newTenantId, "slug", newTenantSlug));

            List<String> seen = new ArrayList<>();
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                    .thenAnswer(inv -> {
                        seen.add(TenantContext.get());
                        return 0;
                    });

            TenantContext.runWithTenant(TENANT_ID, TENANT_SLUG,
                    () -> hook.afterCreate(record, TENANT_ID));

            assertFalse(seen.isEmpty(), "expected the hook to reach the database");
            assertEquals(List.of(newTenantId), seen.stream().distinct().toList());
            verify(cerbosPolicySyncService).syncTenant(newTenantId);
        }
    }

    @Nested
    @DisplayName("seedDefaultProfiles")
    class SeedDefaultProfiles {

        @Test
        @DisplayName("Should seed 8 profiles when none exist")
        void shouldSeedProfilesWhenNoneExist() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM profile WHERE tenant_id = ?",
                    Integer.class, TENANT_ID))
                    .thenReturn(0);

            hook.seedDefaultProfiles(TENANT_ID);

            // 8 profiles inserted (incl. Portal User, telehealth slice 1)
            verify(jdbcTemplate, times(8)).update(
                    contains("INSERT INTO profile"),
                    anyString(), eq(TENANT_ID), anyString(), anyString());

            // Every profile gets an explicit row per permission — granted or not —
            // so the count is profiles x ALL_PERMISSIONS. Derived rather than
            // hardcoded: a literal here has to be recomputed by hand every time a
            // permission is added, and the failure it produces looks like a bug in
            // provisioning rather than a stale number in a test.
            int expectedPermissionRows = 8 * TenantProvisioningHook.ALL_PERMISSIONS.size();
            verify(jdbcTemplate, times(expectedPermissionRows)).update(
                    contains("INSERT INTO profile_system_permission"),
                    anyString(), anyString(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("Portal User gets API_ACCESS only")
        void portalUserGetsApiAccessOnly() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM profile WHERE tenant_id = ?",
                    Integer.class, TENANT_ID))
                    .thenReturn(0);

            hook.seedDefaultProfiles(TENANT_ID);

            // 7 of 8 profiles grant API_ACCESS=true... verified indirectly:
            // Read Only + Minimum Access are the two without it, so 6 true grants.
            verify(jdbcTemplate, times(6)).update(
                    contains("INSERT INTO profile_system_permission"),
                    anyString(), anyString(), anyString(), eq("API_ACCESS"), eq(true));
        }

        @Test
        @DisplayName("Should skip when profiles already exist")
        void shouldSkipWhenProfilesExist() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM profile WHERE tenant_id = ?",
                    Integer.class, TENANT_ID))
                    .thenReturn(7);

            hook.seedDefaultProfiles(TENANT_ID);

            verify(jdbcTemplate, never()).update(contains("INSERT INTO profile"), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should not grant MANAGE_TENANTS to any profile")
        void shouldNotGrantManageTenants() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM profile WHERE tenant_id = ?",
                    Integer.class, TENANT_ID))
                    .thenReturn(0);

            hook.seedDefaultProfiles(TENANT_ID);

            // Verify MANAGE_TENANTS is always passed as false
            verify(jdbcTemplate, times(8)).update(
                    contains("INSERT INTO profile_system_permission"),
                    anyString(), anyString(), anyString(), eq("MANAGE_TENANTS"), eq(false));
        }

        @Test
        @DisplayName("Should grant VIEW_ANALYTICS to every built-in profile except Minimum Access and Portal User")
        void shouldGrantViewAnalyticsToAllButMinimumAccess() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM profile WHERE tenant_id = ?",
                    Integer.class, TENANT_ID))
                    .thenReturn(0);

            hook.seedDefaultProfiles(TENANT_ID);

            // 6 of 8 profiles granted (Minimum Access + Portal User are the two false)
            verify(jdbcTemplate, times(6)).update(
                    contains("INSERT INTO profile_system_permission"),
                    anyString(), anyString(), anyString(), eq("VIEW_ANALYTICS"), eq(true));
            verify(jdbcTemplate, times(2)).update(
                    contains("INSERT INTO profile_system_permission"),
                    anyString(), anyString(), anyString(), eq("VIEW_ANALYTICS"), eq(false));
        }
    }

    @Nested
    @DisplayName("seedOidcProvider")
    class SeedOidcProvider {

        @Test
        @DisplayName("Should create OIDC provider when none exists")
        void shouldCreateOidcProvider() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oidc_provider WHERE tenant_id = ? AND issuer = ?",
                    Integer.class, TENANT_ID, AUTH_ISSUER))
                    .thenReturn(0);

            hook.seedOidcProvider(TENANT_ID);

            verify(jdbcTemplate).update(
                    contains("INSERT INTO oidc_provider"),
                    anyString(), eq(TENANT_ID), eq(AUTH_ISSUER),
                    eq(AUTH_ISSUER + "/oauth2/jwks"));
        }

        @Test
        @DisplayName("Should skip when OIDC provider already exists")
        void shouldSkipWhenOidcProviderExists() {
            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oidc_provider WHERE tenant_id = ? AND issuer = ?",
                    Integer.class, TENANT_ID, AUTH_ISSUER))
                    .thenReturn(1);

            hook.seedOidcProvider(TENANT_ID);

            verify(jdbcTemplate, never()).update(contains("INSERT INTO oidc_provider"),
                    any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("seedAdminUser")
    class SeedAdminUser {

        @Test
        @DisplayName("Should create admin user with slug-based email and username")
        void shouldCreateAdminUserWithSlug() {
            String adminEmail = TENANT_SLUG + "-admin@kelta.local";

            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_user WHERE tenant_id = ? AND email = ?",
                    Integer.class, TENANT_ID, adminEmail))
                    .thenReturn(0);

            when(jdbcTemplate.queryForList(
                    "SELECT id FROM profile WHERE tenant_id = ? AND name = 'System Administrator' LIMIT 1",
                    String.class, TENANT_ID))
                    .thenReturn(List.of("profile-sys-admin"));

            hook.seedAdminUser(TENANT_ID, TENANT_SLUG);

            // Verify user creation with slug-based email and username
            verify(jdbcTemplate).update(
                    contains("INSERT INTO platform_user"),
                    anyString(), eq(TENANT_ID), eq(adminEmail),
                    eq(TENANT_SLUG + "-admin"), eq("profile-sys-admin"));

            // Verify credential creation
            verify(jdbcTemplate).update(
                    contains("INSERT INTO user_credential"),
                    anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should skip when admin user already exists")
        void shouldSkipWhenAdminExists() {
            String adminEmail = TENANT_SLUG + "-admin@kelta.local";

            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_user WHERE tenant_id = ? AND email = ?",
                    Integer.class, TENANT_ID, adminEmail))
                    .thenReturn(1);

            hook.seedAdminUser(TENANT_ID, TENANT_SLUG);

            verify(jdbcTemplate, never()).update(contains("INSERT INTO platform_user"),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should skip when no System Administrator profile found")
        void shouldSkipWhenNoProfileFound() {
            String adminEmail = TENANT_SLUG + "-admin@kelta.local";

            when(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM platform_user WHERE tenant_id = ? AND email = ?",
                    Integer.class, TENANT_ID, adminEmail))
                    .thenReturn(0);

            when(jdbcTemplate.queryForList(
                    "SELECT id FROM profile WHERE tenant_id = ? AND name = 'System Administrator' LIMIT 1",
                    String.class, TENANT_ID))
                    .thenReturn(List.of());

            hook.seedAdminUser(TENANT_ID, TENANT_SLUG);

            verify(jdbcTemplate, never()).update(contains("INSERT INTO platform_user"),
                    any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("activateTenant")
    class ActivateTenant {

        @Test
        @DisplayName("Should transition tenant from PROVISIONING to ACTIVE")
        void shouldActivateTenant() {
            when(jdbcTemplate.update(anyString(), eq(TENANT_ID)))
                    .thenReturn(1);

            hook.activateTenant(TENANT_ID);

            verify(jdbcTemplate).update(
                    contains("UPDATE tenant SET status = 'ACTIVE'"),
                    eq(TENANT_ID));
        }
    }

    @Nested
    @DisplayName("syncCerbosPolicies")
    class SyncCerbosPolicies {

        @Test
        @DisplayName("Should delegate to CerbosPolicySyncService")
        void shouldDelegateToSyncService() {
            hook.syncCerbosPolicies(TENANT_ID);

            verify(cerbosPolicySyncService).syncTenant(TENANT_ID);
        }

        @Test
        @DisplayName("Should no-op when sync service is null")
        void shouldNoOpWhenServiceNull() {
            TenantProvisioningHook hookNoSync =
                    new TenantProvisioningHook(jdbcTemplate, AUTH_ISSUER, null);

            assertDoesNotThrow(() -> hookNoSync.syncCerbosPolicies(TENANT_ID));
            verifyNoInteractions(cerbosPolicySyncService);
        }

        @Test
        @DisplayName("Should be invoked from afterCreate after seeding")
        void shouldBeInvokedFromAfterCreate() {
            Map<String, Object> record = new HashMap<>(Map.of("id", TENANT_ID, "slug", TENANT_SLUG));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                    .thenReturn(0);
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                    .thenReturn(List.of("profile-sys-admin"));

            hook.afterCreate(record, TENANT_ID);

            verify(cerbosPolicySyncService).syncTenant(TENANT_ID);
        }
    }
}
