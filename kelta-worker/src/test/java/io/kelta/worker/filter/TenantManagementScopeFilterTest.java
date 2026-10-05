package io.kelta.worker.filter;

import io.kelta.runtime.context.RequestAuthentication;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PLT-330: {@code tenants} reads are self-scoped to the bound tenant in storage, so a platform
 * admin's tenant management must run unbound (listing) or bound to the managed tenant (one row) —
 * otherwise Setup › Tenants would list only the admin's own tenant.
 */
class TenantManagementScopeFilterTest {

    private static final String ADMIN_TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String ADMIN_PROFILE = "profile-platform-admin";
    private static final String PLAIN_PROFILE = "profile-standard";

    private BootstrapRepository bootstrapRepository;
    private TenantManagementScopeFilter filter;

    @BeforeEach
    void setUp() {
        bootstrapRepository = mock(BootstrapRepository.class);
        when(bootstrapRepository.findProfileSystemPermissions(ADMIN_PROFILE)).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_TENANTS", "granted", true)));
        when(bootstrapRepository.findProfileSystemPermissions(PLAIN_PROFILE)).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_TENANTS", "granted", false),
                Map.of("permission_name", "API_ACCESS", "granted", true)));
        filter = new TenantManagementScopeFilter(bootstrapRepository, new CerbosPermissionResolver());
    }

    /** Runs the filter under the caller's bound tenant and returns the tenant the chain saw. */
    private AtomicReference<String> run(String method, String path, String profileId) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (profileId != null) {
            request.addHeader("X-User-Profile-Id", profileId);
        }
        AtomicReference<String> seen = new AtomicReference<>("<chain not invoked>");
        TenantContext.runWithTenant(ADMIN_TENANT, () -> {
            try {
                filter.doFilter(request, new MockHttpServletResponse(),
                        (req, res) -> seen.set(TenantContext.get()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return seen;
    }

    @Test
    @DisplayName("platform admin listing GET /api/tenants runs unbound, so storage returns every tenant")
    void platformAdminListingIsUnbound() {
        assertNull(run("GET", "/api/tenants", ADMIN_PROFILE).get());
    }

    @Test
    @DisplayName("platform admin on /api/tenants/{id} is bound to the managed tenant")
    void platformAdminRecordIsBoundToManagedTenant() {
        assertEquals(OTHER_TENANT, run("GET", "/api/tenants/" + OTHER_TENANT, ADMIN_PROFILE).get());
        assertEquals(OTHER_TENANT, run("PATCH", "/api/tenants/" + OTHER_TENANT, ADMIN_PROFILE).get());
        assertEquals(OTHER_TENANT, run("DELETE", "/api/tenants/" + OTHER_TENANT, ADMIN_PROFILE).get());
    }

    @Test
    @DisplayName("without MANAGE_TENANTS the caller's own tenant stays bound")
    void nonAdminKeepsOwnTenant() {
        assertEquals(ADMIN_TENANT, run("GET", "/api/tenants", PLAIN_PROFILE).get());
        assertEquals(ADMIN_TENANT, run("GET", "/api/tenants/" + OTHER_TENANT, PLAIN_PROFILE).get());
    }

    @Test
    @DisplayName("an unauthenticated read (pre-login bootstrap) keeps the slug-resolved tenant")
    void anonymousKeepsBoundTenant() {
        assertEquals(ADMIN_TENANT, run("GET", "/api/tenants", null).get());
        verify(bootstrapRepository, never()).findProfileSystemPermissions(anyString());
    }

    @Test
    @DisplayName("create and deeper paths are left alone, even for a platform admin")
    void createAndChildPathsUnchanged() {
        assertEquals(ADMIN_TENANT, run("POST", "/api/tenants", ADMIN_PROFILE).get());
        assertEquals(ADMIN_TENANT, run("GET", "/api/tenants/" + OTHER_TENANT + "/users", ADMIN_PROFILE).get());
    }

    @Test
    @DisplayName("other paths never consult the permission lookup")
    void otherPathsSkipped() {
        assertEquals(ADMIN_TENANT, run("GET", "/api/tenants-archive", ADMIN_PROFILE).get());
        assertEquals(ADMIN_TENANT, run("GET", "/api/users", ADMIN_PROFILE).get());
        verify(bootstrapRepository, never()).findProfileSystemPermissions(anyString());
    }

    @Test
    @DisplayName("the unbound listing restores the caller's binding once the request finishes")
    void bindingIsScopedToTheChain() {
        AtomicReference<String> after = new AtomicReference<>();
        TenantContext.runWithTenant(ADMIN_TENANT, () -> {
            try {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
                request.addHeader("X-User-Profile-Id", ADMIN_PROFILE);
                filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            after.set(TenantContext.get());
        });
        assertTrue(ADMIN_TENANT.equals(after.get()));
    }

    @Test
    @DisplayName("PLT-354: the unbound listing is marked platform-scoped; managed-tenant reads are not")
    void unboundListingIsPlatformScoped() throws Exception {
        assertEquals(RequestAuthentication.PLATFORM_SCOPED, authSeenBy("/api/tenants"));
        assertEquals(RequestAuthentication.AUTHENTICATED, authSeenBy("/api/tenants/" + OTHER_TENANT));
    }

    private RequestAuthentication authSeenBy(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-User-Profile-Id", ADMIN_PROFILE);
        AtomicReference<RequestAuthentication> seen = new AtomicReference<>();
        ScopedValue.where(RequestAuthentication.CURRENT, RequestAuthentication.AUTHENTICATED)
                .where(TenantContext.CURRENT_TENANT, ADMIN_TENANT)
                .call(() -> {
                    filter.doFilter(request, new MockHttpServletResponse(),
                            (req, res) -> seen.set(RequestAuthentication.current()));
                    return null;
                });
        return seen.get();
    }
}
