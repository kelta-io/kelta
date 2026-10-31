package io.kelta.auth.service;

import io.kelta.auth.model.KeltaUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;

@Service
public class KeltaUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(KeltaUserDetailsService.class);

    /**
     * Request attribute holding the {@link KeltaUserDetails} this request's sign-in loaded. A
     * sign-in that must change its password fails after the password check with no principal on
     * the exception; {@code LoginFailureHandler} reads the user (tenant + id) from here.
     */
    public static final String LOADED_USER_ATTRIBUTE = KeltaUserDetailsService.class.getName() + ".LOADED_USER";

    private final JdbcTemplate jdbcTemplate;
    private final LoginTenantResolver tenantResolver;

    public KeltaUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantResolver = new LoginTenantResolver(jdbcTemplate);
    }

    @Override
    public UserDetails loadUserByUsername(String emailOrUsername) throws UsernameNotFoundException {
        String tenantId = tenantResolver.resolve();

        // Tenant context is required. The platform_user table has row-level security on
        // the public schema scoped by tenant_id; cross-tenant lookups are unsafe because
        // the same email/username (e.g. admin@kelta.local) can exist in multiple tenants.
        if (tenantId == null) {
            log.warn("Refusing to authenticate '{}': no tenant context in session", emailOrUsername);
            throw new UsernameNotFoundException(
                    "Tenant context is required to authenticate. "
                            + "Initiate login via /oauth2/authorize with a tenant-scoped redirect_uri "
                            + "or pass ?tenant=<slug> to /login.");
        }

        List<KeltaUserDetails> users = jdbcTemplate.query(
                """
                SELECT pu.id, pu.email, pu.tenant_id, pu.profile_id,
                       p.name AS profile_name,
                       COALESCE(pu.first_name || ' ' || pu.last_name, pu.email) AS display_name,
                       uc.password_hash,
                       pu.status,
                       pu.user_type,
                       uc.locked_until,
                       uc.force_change_on_login
                FROM platform_user pu
                JOIN user_credential uc ON uc.user_id = pu.id
                LEFT JOIN profile p ON p.id = pu.profile_id
                WHERE (pu.email = ? OR pu.username = ?)
                  AND pu.tenant_id = ?
                  AND pu.status = 'ACTIVE'
                  AND pu.user_type <> 'SERVICE'
                """,
                userDetailsRowMapper(),
                emailOrUsername, emailOrUsername, tenantId
        );

        if (users.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + emailOrUsername);
        }

        KeltaUserDetails user = users.get(0);
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(LOADED_USER_ATTRIBUTE, user, RequestAttributes.SCOPE_REQUEST);
        }
        return user;
    }

    private org.springframework.jdbc.core.RowMapper<KeltaUserDetails> userDetailsRowMapper() {
        return (rs, rowNum) -> new KeltaUserDetails(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("tenant_id"),
                rs.getString("profile_id"),
                rs.getString("profile_name"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                "ACTIVE".equals(rs.getString("status")),
                rs.getTimestamp("locked_until") != null
                        && rs.getTimestamp("locked_until").toInstant().isAfter(java.time.Instant.now()),
                rs.getBoolean("force_change_on_login"),
                rs.getString("user_type")
        );
    }
}
