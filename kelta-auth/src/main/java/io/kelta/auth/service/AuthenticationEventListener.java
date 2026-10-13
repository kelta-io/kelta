package io.kelta.auth.service;

import io.kelta.auth.model.KeltaUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Listens for Spring Security authentication events to enforce account lockout
 * and password expiration policies.
 *
 * <p>On success: resets failed attempts + checks password expiration.
 * On failure (bad credentials): increments failed attempts for the user of the sign-in's own
 * tenant + locks if threshold reached. Failures that loaded no user are not counted.
 *
 * @since 1.0.0
 */
@Component
public class AuthenticationEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationEventListener.class);

    private final PasswordPolicyService policyService;
    private final JdbcTemplate jdbcTemplate;
    private final LoginTenantResolver tenantResolver;

    public AuthenticationEventListener(PasswordPolicyService policyService, JdbcTemplate jdbcTemplate) {
        this.policyService = policyService;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantResolver = new LoginTenantResolver(jdbcTemplate);
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof KeltaUserDetails userDetails) {
            String userId = userDetails.getId();
            String tenantId = userDetails.getTenantId();

            policyService.resetFailedAttempts(userId);
            policyService.checkPasswordExpiration(userId, tenantId);

            log.debug("Authentication success for user {}, failed attempts reset", userId);
        }
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        // Only a wrong password for a user that was actually loaded counts. When no user was
        // loaded — unknown name, inactive account, or no tenant known for the request —
        // DaoAuthenticationProvider reports the UsernameNotFoundException as bad credentials
        // with it as the cause, and there is no account to count the attempt against.
        if (event.getException().getCause() instanceof UsernameNotFoundException) {
            log.debug("Authentication failure without a loaded user, not counted");
            return;
        }

        // The same email can exist in several tenants: count against the user of the tenant
        // this sign-in was for, resolved the way KeltaUserDetailsService resolved it.
        String tenantId = tenantResolver.resolve();
        if (tenantId == null) {
            log.debug("Authentication failure with no tenant context, not counted");
            return;
        }

        String username = event.getAuthentication().getName();
        List<String> userIds = jdbcTemplate.queryForList(
                "SELECT pu.id FROM platform_user pu "
                        + "WHERE (pu.email = ? OR pu.username = ?) AND pu.tenant_id = ? AND pu.status = 'ACTIVE'",
                String.class,
                username, username, tenantId
        );

        if (userIds.size() == 1) {
            String userId = userIds.get(0);
            policyService.incrementFailedAttempts(userId, tenantId);
            log.debug("Authentication failure for user {}, failed attempts incremented", userId);
        }
    }
}
