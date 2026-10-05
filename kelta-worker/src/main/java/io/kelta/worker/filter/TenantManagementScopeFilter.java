package io.kelta.worker.filter;

import io.kelta.runtime.context.RequestAuthentication;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rebinds the tenant for a platform admin's tenant management on {@code /api/tenants}.
 *
 * <p>{@code PhysicalTableStorageAdapter} self-scopes every read of {@code tenants} to
 * {@code id = <bound tenant>} (PLT-330), and {@link TenantContextFilter} binds the caller's own
 * tenant on every request — so without this filter Setup › Tenants would list exactly one
 * tenant, and editing, suspending or deleting any other tenant would 404 on the pre-update
 * fetch. For a caller whose profile holds {@code MANAGE_TENANTS} (the grant the gateway already
 * requires for a full listing and for every write):
 * <ul>
 *   <li>{@code GET /api/tenants} runs with the tenant <b>unbound</b> — the platform path,
 *       which also selects RLS {@code admin_bypass} for the request, and is marked
 *       {@link RequestAuthentication#PLATFORM_SCOPED} so the cross-tenant read logs at DEBUG
 *       rather than as a possible leak;</li>
 *   <li>{@code /api/tenants/{id}} (any method) runs bound to <b>{@code id}</b>, the tenant
 *       being managed, so the self-scope admits that one row and the change is attributed to
 *       that tenant.</li>
 * </ul>
 * Everything else — {@code POST /api/tenants}, deeper paths, callers without the grant, the
 * unauthenticated pre-login bootstrap read — keeps the binding {@link TenantContextFilter} made.
 * The permission is checked here, not trusted from the gateway, with the same
 * {@code profile_system_permission} lookup the {@code /api/admin/**} controllers use; the
 * profile header is one the gateway strips from client requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantManagementScopeFilter extends OncePerRequestFilter {

    static final String TENANTS_PATH = "/api/tenants";
    static final String MANAGE_TENANTS = "MANAGE_TENANTS";

    private final BootstrapRepository bootstrapRepository;
    private final CerbosPermissionResolver permissionResolver;

    public TenantManagementScopeFilter(BootstrapRepository bootstrapRepository,
                                       CerbosPermissionResolver permissionResolver) {
        this.bootstrapRepository = bootstrapRepository;
        this.permissionResolver = permissionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(TENANTS_PATH) || path.startsWith(TENANTS_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rest = request.getRequestURI().substring(TENANTS_PATH.length());
        boolean listing = (rest.isEmpty() || "/".equals(rest)) && "GET".equals(request.getMethod());
        String managedTenantId = rest.matches("/[^/]+") ? rest.substring(1) : null;

        if ((!listing && managedTenantId == null) || !hasManageTenants(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        AtomicReference<IOException> ioErr = new AtomicReference<>();
        AtomicReference<ServletException> servletErr = new AtomicReference<>();
        ScopedValue.Carrier carrier = ScopedValue.where(TenantContext.CURRENT_TENANT, managedTenantId);
        if (managedTenantId == null) {
            carrier = carrier.where(RequestAuthentication.CURRENT, RequestAuthentication.PLATFORM_SCOPED);
        }
        carrier.run(() -> {
            try {
                filterChain.doFilter(request, response);
            } catch (IOException e) {
                ioErr.set(e);
            } catch (ServletException e) {
                servletErr.set(e);
            }
        });

        if (ioErr.get() != null) {
            throw ioErr.get();
        }
        if (servletErr.get() != null) {
            throw servletErr.get();
        }
    }

    private boolean hasManageTenants(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> MANAGE_TENANTS.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }
}
