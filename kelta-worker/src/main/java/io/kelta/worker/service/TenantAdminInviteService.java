package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Hands a tenant's seeded System Administrator to a real person: sets the admin's email and
 * sends the {@code user.invite} email through {@link UserInviteService}.
 *
 * <p>{@code TenantProvisioningHook} seeds every runtime-provisioned tenant's admin
 * ({@code <slug>-admin}) with an unusable credential, so an invite is the only way to claim
 * it. A platform admin's request to {@code POST /api/tenants/{id}/admin-invite} arrives bound to
 * the <em>caller's</em> tenant ({@code TenantManagementScopeFilter} rebinds only
 * {@code /api/tenants/{id}} itself), while every row touched here belongs to the managed
 * tenant — so the work runs under an explicit {@code callWithTenant} for that tenant, the same
 * per-tenant binding the other cross-tenant paths use.
 */
@Service
public class TenantAdminInviteService {

    private static final Logger log = LoggerFactory.getLogger(TenantAdminInviteService.class);
    private static final int MAX_EMAIL_LENGTH = 254;

    private final JdbcTemplate jdbcTemplate;
    private final UserInviteService userInviteService;

    public TenantAdminInviteService(JdbcTemplate jdbcTemplate, UserInviteService userInviteService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userInviteService = userInviteService;
    }

    public record AdminInvite(String userId, String email) {}

    /** A user of a managed tenant, resolved with the tenant's slug for a later {@code callWithTenant}. */
    public record TenantUser(String tenantId, String slug, String userId, String status) {}

    /** The username {@code TenantProvisioningHook} gives a tenant's seeded admin. */
    public static String seededAdminUsername(String slug) {
        return (slug != null ? slug : "admin") + "-admin";
    }

    /**
     * Trims and lower-cases an email address, or returns {@code null} when the value is not a
     * plausible single address (blank, whitespace inside, not exactly one {@code @}, too long).
     */
    public static String normalizeEmail(Object raw) {
        if (!(raw instanceof String s)) {
            return null;
        }
        String email = s.trim().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || email.chars().anyMatch(Character::isWhitespace)
                || at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return null;
        }
        return email;
    }

    /**
     * Points the managed tenant's seeded admin at {@code email} and sends the invite.
     *
     * @return the invited admin, or empty when the tenant or its seeded admin does not exist
     * @throws ResponseStatusException 409 when another user of the tenant already has the email
     */
    public Optional<AdminInvite> inviteSeededAdmin(String tenantId, String email) {
        List<String> slugs = jdbcTemplate.queryForList(
                "SELECT slug FROM tenant WHERE id = ?", String.class, tenantId);
        if (slugs.isEmpty()) {
            return Optional.empty();
        }
        String slug = slugs.get(0);
        return TenantContext.callWithTenant(tenantId, slug, () -> {
            List<Map<String, Object>> admins = jdbcTemplate.queryForList(
                    "SELECT id FROM platform_user WHERE tenant_id = ? AND username = ?",
                    tenantId, seededAdminUsername(slug));
            if (admins.isEmpty()) {
                log.warn("Tenant {} has no seeded admin '{}' to invite", tenantId, seededAdminUsername(slug));
                return Optional.<AdminInvite>empty();
            }
            String userId = String.valueOf(admins.get(0).get("id"));
            try {
                jdbcTemplate.update(
                        "UPDATE platform_user SET email = ?, updated_at = NOW() WHERE id = ? AND tenant_id = ?",
                        email, userId, tenantId);
            } catch (DuplicateKeyException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another user in this tenant already has that email");
            }
            userInviteService.inviteUser(tenantId, userId);
            return Optional.of(new AdminInvite(userId, email));
        });
    }

    /**
     * Finds a user of the managed tenant: {@code userId} when given, else the user with
     * {@code email}, else the seeded System Administrator. A user of any other tenant is never
     * returned — every lookup is filtered by {@code tenantId} and runs under that tenant's binding.
     *
     * @return the user, or empty when the tenant or a matching user in it does not exist
     */
    public Optional<TenantUser> findTenantUser(String tenantId, String userId, String email) {
        List<String> slugs = jdbcTemplate.queryForList(
                "SELECT slug FROM tenant WHERE id = ?", String.class, tenantId);
        if (slugs.isEmpty()) {
            return Optional.empty();
        }
        String slug = slugs.get(0);
        return TenantContext.callWithTenant(tenantId, slug, () -> {
            List<Map<String, Object>> users;
            if (userId != null) {
                users = jdbcTemplate.queryForList(
                        "SELECT id, status FROM platform_user WHERE tenant_id = ? AND id = ?", tenantId, userId);
            } else if (email != null) {
                users = jdbcTemplate.queryForList(
                        "SELECT id, status FROM platform_user WHERE tenant_id = ? AND LOWER(email) = ?", tenantId, email);
            } else {
                users = jdbcTemplate.queryForList(
                        "SELECT id, status FROM platform_user WHERE tenant_id = ? AND username = ?",
                        tenantId, seededAdminUsername(slug));
            }
            if (users.isEmpty()) {
                return Optional.<TenantUser>empty();
            }
            Map<String, Object> user = users.get(0);
            return Optional.of(new TenantUser(tenantId, slug, String.valueOf(user.get("id")),
                    (String) user.get("status")));
        });
    }
}
