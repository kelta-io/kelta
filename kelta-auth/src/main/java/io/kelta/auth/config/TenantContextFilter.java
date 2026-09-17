package io.kelta.auth.config;

import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.runtime.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the tenant slug from the redirect_uri of an /oauth2/authorize request
 * and stores it in the HTTP session as "tenantId" so KeltaUserDetailsService can
 * scope the user lookup to the correct tenant.
 *
 * <p>If the incoming tenant slug differs from the one currently stored in the
 * session, the Spring Security context is cleared so the user re-authenticates
 * in the new tenant rather than reusing the principal from the previous one.
 * Without this, a cross-tenant navigation (e.g. /threadline/... → /default/...)
 * would silently issue a token carrying the prior tenant's identity, which the
 * gateway then rejects as a cross-tenant access attempt.
 *
 * <p>Registered as a servlet-level filter (before Spring Security) via {@code @Component}.
 *
 * <p>Must run before {@code springSecurityFilterChain} so the tenant is recorded in the
 * session before Spring Security redirects unauthenticated users to {@code /login}; otherwise
 * the subsequent POST to /login would reach {@code KeltaUserDetailsService} with no tenant
 * in session and authentication would fail.
 *
 * <h2>Binding the tenant to the database connection</h2>
 * <p>On <i>every</i> request the filter also resolves whatever tenant the session already
 * carries into a {@link TenantContext} binding, which {@link TenantAwareDataSourceConfig}
 * turns into a transaction-scoped {@code SET LOCAL app.current_tenant_id}. Row-level
 * security then filters {@code platform_user} and the per-user credential tables for the
 * rest of the request. Requests with no tenant to resolve (the client back-channel token
 * exchange, actuator, startup work) stay on the platform session exactly as before.
 *
 * <p>The session attribute may hold either a slug (what {@link #extractTenantSlug} and
 * {@code LoginController} write) or a UUID; only a UUID can be bound, so slugs are resolved
 * through the {@code tenant} table — itself tenant-less and unaffected by RLS — and cached.
 *
 * Path pattern: /{tenant-slug}/auth/callback
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10)
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private static final Pattern TENANT_SLUG_PATTERN =
            Pattern.compile("^/([^/]+)/auth/callback$");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Duration SLUG_CACHE_TTL = Duration.ofMinutes(5);
    private static final String NOT_FOUND = "__not_found__";

    static final String SESSION_TENANT_ATTR = "tenantId";

    private final AuthDomainResolver domainResolver;
    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, CachedTenantId> slugCache = new ConcurrentHashMap<>();

    private record CachedTenantId(String tenantId, Instant expiresAt) {}

    public TenantContextFilter(AuthDomainResolver domainResolver, JdbcTemplate jdbcTemplate) {
        this.domainResolver = domainResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if ("GET".equalsIgnoreCase(request.getMethod())
                && "/oauth2/authorize".equals(request.getServletPath())) {
            String slug = null;
            String redirectUri = request.getParameter("redirect_uri");
            if (redirectUri != null && !redirectUri.isBlank()) {
                slug = extractTenantSlug(redirectUri);
            }
            // Fall back to Host-based resolution when the redirect_uri does not
            // carry a tenant prefix (slug-less custom-domain mode).
            if (slug == null) {
                slug = domainResolver.resolveTenantSlug(request.getServerName()).orElse(null);
            }
            if (slug != null) {
                applyTenantContext(request, slug);
            }
        }

        String tenantId = resolveTenantId(request);
        if (tenantId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        runBound(tenantId, request, response, filterChain);
    }

    private void runBound(String tenantId, HttpServletRequest request, HttpServletResponse response,
                          FilterChain filterChain) throws ServletException, IOException {
        AtomicReference<IOException> ioErr = new AtomicReference<>();
        AtomicReference<ServletException> servletErr = new AtomicReference<>();

        ScopedValue.where(TenantContext.CURRENT_TENANT, tenantId).run(() -> {
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

    private void applyTenantContext(HttpServletRequest request, String slug) {
        HttpSession session = request.getSession();
        Object existing = session.getAttribute(SESSION_TENANT_ATTR);
        if (existing instanceof String prev && !prev.isBlank() && !prev.equals(slug)) {
            log.info("Tenant context changing from '{}' to '{}' — clearing stale Spring Security context",
                    prev, slug);
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            SecurityContextHolder.clearContext();
        }
        session.setAttribute(SESSION_TENANT_ATTR, slug);
        log.debug("Tenant '{}' applied to session from /oauth2/authorize redirect_uri", slug);
    }

    /**
     * Returns the tenant UUID this request belongs to, or {@code null} when it belongs to
     * none — in which case the request keeps the platform database session.
     */
    private String resolveTenantId(HttpServletRequest request) {
        String raw = null;
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_TENANT_ATTR) instanceof String attr
                && !attr.isBlank()) {
            raw = attr;
        }
        if (raw == null) {
            // The very first request of a login (/login?tenant=acme) binds before
            // LoginController has had a chance to copy the parameter into the session.
            String param = request.getParameter("tenant");
            if (param != null && !param.isBlank()) {
                raw = param;
            }
        }
        if (raw == null) {
            return null;
        }
        return UUID_PATTERN.matcher(raw).matches() ? raw : resolveSlug(raw);
    }

    private String resolveSlug(String slug) {
        CachedTenantId hit = slugCache.get(slug);
        Instant now = Instant.now();
        if (hit != null && hit.expiresAt().isAfter(now)) {
            return NOT_FOUND.equals(hit.tenantId()) ? null : hit.tenantId();
        }
        String resolved;
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM tenant WHERE slug = ?", String.class, slug);
            resolved = ids.isEmpty() ? NOT_FOUND : ids.get(0);
        } catch (RuntimeException e) {
            // A database hiccup must not turn every request into a 500 — the request simply
            // runs on the platform session, exactly as it did before this filter bound anything.
            log.warn("Could not resolve tenant slug '{}' for connection binding: {}", slug, e.getMessage());
            return null;
        }
        slugCache.put(slug, new CachedTenantId(resolved, now.plus(SLUG_CACHE_TTL)));
        return NOT_FOUND.equals(resolved) ? null : resolved;
    }

    private String extractTenantSlug(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            String path = uri.getPath();
            if (path == null) return null;
            Matcher m = TENANT_SLUG_PATTERN.matcher(path);
            return m.matches() ? m.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
