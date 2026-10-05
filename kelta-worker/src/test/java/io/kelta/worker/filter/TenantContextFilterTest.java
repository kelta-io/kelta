package io.kelta.worker.filter;

import io.kelta.runtime.context.RequestAuthentication;
import io.kelta.runtime.context.TenantContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PLT-354: every request binds its authentication state, so the storage adapter can fail closed
 * on an anonymous read of a tenant-scoped system collection that resolved no tenant.
 */
class TenantContextFilterTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private final TenantContextFilter filter = new TenantContextFilter();

    private record Seen(String tenantId, RequestAuthentication auth) {}

    private Seen run(MockHttpServletRequest request) throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(new Seen(TenantContext.get(), RequestAuthentication.current())));
        return seen.get();
    }

    @Test
    @DisplayName("no tenant and no X-User-Id: the request is bound ANONYMOUS with no tenant")
    void anonymousWithoutTenant() throws Exception {
        Seen seen = run(new MockHttpServletRequest("GET", "/api/ui-pages"));

        assertNull(seen.tenantId());
        assertEquals(RequestAuthentication.ANONYMOUS, seen.auth());
    }

    @Test
    @DisplayName("an anonymous request with a resolved tenant binds both")
    void anonymousWithTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ui-pages");
        request.addHeader("X-Tenant-ID", TENANT);

        Seen seen = run(request);

        assertEquals(TENANT, seen.tenantId());
        assertEquals(RequestAuthentication.ANONYMOUS, seen.auth());
    }

    @Test
    @DisplayName("a gateway-forwarded X-User-Id marks the request AUTHENTICATED, tenant or not")
    void authenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader("X-User-Id", "admin@example.com");

        Seen seen = run(request);

        assertNull(seen.tenantId());
        assertEquals(RequestAuthentication.AUTHENTICATED, seen.auth());
    }

    @Test
    @DisplayName("the binding does not outlive the request")
    void bindingIsScopedToTheChain() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/ui-pages"));

        assertNull(RequestAuthentication.current());
    }
}
