package io.kelta.worker.filter;

import io.kelta.runtime.context.GeoContext;
import io.kelta.runtime.context.GeoHeaders;
import io.kelta.runtime.context.RequestAuthentication;
import io.kelta.runtime.context.TenantContext;
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
 * Servlet filter that binds tenant context for the duration of the request
 * using {@link TenantContext#CURRENT_TENANT} / {@link TenantContext#CURRENT_TENANT_SLUG}
 * (Java {@link ScopedValue}), rather than ThreadLocal.
 *
 * <p>The gateway's HeaderTransformationFilter adds {@code X-Tenant-ID} and
 * {@code X-Tenant-Slug} headers to all proxied requests. This filter reads
 * those headers and makes them available to controllers via TenantContext,
 * propagating correctly into any virtual threads / {@code StructuredTaskScope}
 * fan-out started within the request. When the gateway attached {@code X-Geo-*}
 * headers, the parsed {@link io.kelta.runtime.context.GeoStamp} is bound to
 * {@link GeoContext#CURRENT_GEO} in the same scope.
 *
 * <p>Every request also binds {@link RequestAuthentication#CURRENT}: {@code AUTHENTICATED} when
 * the gateway forwarded an {@code X-User-Id} (it strips client-supplied copies), else
 * {@code ANONYMOUS}. That is the opt-in that makes {@code PhysicalTableStorageAdapter} fail
 * closed on an anonymous, tenant-less read of a tenant-scoped system collection instead of
 * returning every tenant's rows.
 *
 * <p>Runs with highest precedence so TenantContext is available to all
 * downstream filters and controllers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    private static final String X_TENANT_ID_HEADER = "X-Tenant-ID";
    private static final String X_TENANT_SLUG_HEADER = "X-Tenant-Slug";
    private static final String X_USER_ID_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader(X_TENANT_ID_HEADER);
        String tenantSlug = request.getHeader(X_TENANT_SLUG_HEADER);

        String userId = request.getHeader(X_USER_ID_HEADER);

        boolean hasTenant = tenantId != null && !tenantId.isBlank();
        boolean hasSlug = tenantSlug != null && !tenantSlug.isBlank();

        ScopedValue.Carrier carrier = ScopedValue.where(RequestAuthentication.CURRENT,
                userId != null && !userId.isBlank()
                        ? RequestAuthentication.AUTHENTICATED
                        : RequestAuthentication.ANONYMOUS);
        if (hasTenant) {
            carrier = carrier.where(TenantContext.CURRENT_TENANT, tenantId);
        }
        if (hasSlug) {
            carrier = carrier.where(TenantContext.CURRENT_TENANT_SLUG, tenantSlug);
        }
        // Bind the gateway-attested request-origin geolocation alongside the tenant so
        // Cerbos principal building and record stamping can read it without the request.
        var geo = GeoHeaders.parse(request);
        if (geo.isPresent()) {
            carrier = carrier.where(GeoContext.CURRENT_GEO, geo.get());
        }

        AtomicReference<IOException> ioErr = new AtomicReference<>();
        AtomicReference<ServletException> servletErr = new AtomicReference<>();

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
}
