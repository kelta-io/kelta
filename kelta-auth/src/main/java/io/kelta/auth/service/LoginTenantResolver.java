package io.kelta.auth.service;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Resolves the tenant a password sign-in belongs to from the current request's session.
 *
 * <p>The session attribute "tenantId" may be a UUID (set during the OAuth2 authorize redirect)
 * or a slug (set from the {@code ?tenant=} query parameter or {@code /auth/direct-login});
 * slugs are resolved to UUIDs via the tenant table. Shared by {@link KeltaUserDetailsService},
 * which loads the signing-in user, and {@link AuthenticationEventListener}, which counts a
 * failed attempt against that same user — both must agree on the tenant.
 */
final class LoginTenantResolver {

    private static final Logger log = LoggerFactory.getLogger(LoginTenantResolver.class);

    private final JdbcTemplate jdbcTemplate;

    LoginTenantResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns the tenant UUID of the current sign-in, or {@code null} when none is known. */
    String resolve() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpSession session = attrs.getRequest().getSession(false);
            if (session == null) return null;
            Object raw = session.getAttribute("tenantId");
            if (!(raw instanceof String str) || str.isBlank()) return null;

            // Already a UUID
            if (str.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                return str;
            }

            // Resolve slug → UUID
            List<String> ids = jdbcTemplate.queryForList("SELECT id FROM tenant WHERE slug = ?", String.class, str);
            if (ids.isEmpty()) {
                log.warn("Tenant slug '{}' not found in tenant table", str);
                return null;
            }
            return ids.get(0);
        } catch (IllegalStateException e) {
            // No request context (e.g. called outside a request — shouldn't happen for login)
            return null;
        }
    }
}
