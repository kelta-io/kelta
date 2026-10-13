package io.kelta.auth.controller;

import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.session.UserSessionRevoker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The password change a user must make at sign-in ({@code force_change_on_login}).
 *
 * <p>The form-login failure handler ({@code LoginFailureHandler}) starts it with {@link #begin}
 * once the password was verified, recording the signed-in user's tenant and user id in the
 * session. This controller changes exactly that user's password: it re-reads the account by
 * tenant + id, requires the account to still be flagged and the current password to match, checks
 * the new password against the tenant's password policy and history ({@link PasswordPolicyService},
 * as {@code /auth/password/change} does), and updates only that user's credential, recording it in
 * the history. The user's other sessions and refresh tokens end
 * ({@link UserSessionRevoker}); this browser signs in next with the new password.
 */
@Controller
public class ForcePasswordChangeController {

    private static final Logger log = LoggerFactory.getLogger(ForcePasswordChangeController.class);

    /** Shown on the form. */
    public static final String SESSION_ATTR_EMAIL = "force_change_email";
    /** The user whose password must change — with {@link #SESSION_ATTR_TENANT_ID}, the account. */
    public static final String SESSION_ATTR_USER_ID = "force_change_user_id";
    public static final String SESSION_ATTR_TENANT_ID = "force_change_tenant_id";

    private static final String ACCOUNT_SELECT =
            "SELECT uc.password_hash, uc.force_change_on_login, pu.email, pu.first_name, pu.last_name "
                    + "FROM platform_user pu "
                    + "JOIN user_credential uc ON uc.user_id = pu.id "
                    + "WHERE pu.id = ? AND pu.tenant_id = ? AND pu.status = 'ACTIVE'";

    private static final String CREDENTIAL_UPDATE =
            "UPDATE user_credential SET password_hash = ?, password_changed_at = ?, "
                    + "force_change_on_login = false, updated_at = ? "
                    + "WHERE user_id = ? AND force_change_on_login = true "
                    + "AND EXISTS (SELECT 1 FROM platform_user pu WHERE pu.id = user_credential.user_id "
                    + "AND pu.tenant_id = ?)";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionRevoker sessionRevoker;
    private final PasswordPolicyService policyService;

    public ForcePasswordChangeController(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                                         UserSessionRevoker sessionRevoker, PasswordPolicyService policyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevoker = sessionRevoker;
        this.policyService = policyService;
    }

    /**
     * Starts the forced change for {@code user}, whose password was just verified: records the
     * account in the session and gives the session a new id.
     */
    public static void begin(HttpServletRequest request, KeltaUserDetails user) {
        HttpSession session = request.getSession();
        session.setAttribute(SESSION_ATTR_EMAIL, user.getEmail());
        session.setAttribute(SESSION_ATTR_USER_ID, user.getId());
        session.setAttribute(SESSION_ATTR_TENANT_ID, user.getTenantId());
        request.changeSessionId();
    }

    private record Pending(String userId, String tenantId, String email) {}

    private static Optional<Pending> pending(HttpSession session) {
        if (session.getAttribute(SESSION_ATTR_USER_ID) instanceof String userId && !userId.isBlank()
                && session.getAttribute(SESSION_ATTR_TENANT_ID) instanceof String tenantId && !tenantId.isBlank()) {
            Object email = session.getAttribute(SESSION_ATTR_EMAIL);
            return Optional.of(new Pending(userId, tenantId, email instanceof String value ? value : ""));
        }
        return Optional.empty();
    }

    private static void clear(HttpSession session) {
        session.removeAttribute(SESSION_ATTR_EMAIL);
        session.removeAttribute(SESSION_ATTR_USER_ID);
        session.removeAttribute(SESSION_ATTR_TENANT_ID);
    }

    @GetMapping("/change-password")
    public String showChangePasswordForm(HttpSession session, Model model) {
        Optional<Pending> pending = pending(session);
        if (pending.isEmpty()) {
            return "redirect:/login";
        }
        model.addAttribute("email", pending.get().email());
        return "change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            HttpSession session,
            Model model) {

        Optional<Pending> found = pending(session);
        if (found.isEmpty()) {
            return "redirect:/login";
        }
        Pending pending = found.get();
        model.addAttribute("email", pending.email());

        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New passwords do not match.");
            return "change-password";
        }

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(ACCOUNT_SELECT, pending.userId(), pending.tenantId());
        if (rows.size() != 1) {
            model.addAttribute("error", "Account not found.");
            return "change-password";
        }
        Map<String, Object> account = rows.getFirst();
        if (!Boolean.TRUE.equals(account.get("force_change_on_login"))) {
            // Nothing left to change (changed in another session, or the flag was cleared).
            clear(session);
            return "redirect:/login";
        }
        if (!(account.get("password_hash") instanceof String currentHash)
                || !passwordEncoder.matches(currentPassword, currentHash)) {
            model.addAttribute("error", "Current password is incorrect.");
            return "change-password";
        }

        // The tenant's password policy and history, as for a change made while signed in.
        String firstName = account.get("first_name") instanceof String value ? value : "";
        String lastName = account.get("last_name") instanceof String value ? value : "";
        List<String> violations = policyService.validatePassword(newPassword,
                account.get("email") instanceof String value ? value : pending.email(),
                (firstName + " " + lastName).trim(), pending.tenantId());
        if (!violations.isEmpty()) {
            model.addAttribute("error", String.join("; ", violations));
            return "change-password";
        }
        Optional<String> reused = policyService.checkHistory(pending.userId(), newPassword, pending.tenantId());
        if (reused.isPresent()) {
            model.addAttribute("error", reused.get());
            return "change-password";
        }

        Timestamp now = Timestamp.from(Instant.now());
        String newHash = passwordEncoder.encode(newPassword);
        int updated = jdbcTemplate.update(CREDENTIAL_UPDATE,
                newHash, now, now, pending.userId(), pending.tenantId());
        clear(session);
        if (updated != 1) {
            log.warn("Forced password change for user {} (tenant {}) updated {} rows",
                    pending.userId(), pending.tenantId(), updated);
            return "redirect:/login";
        }

        policyService.saveToHistory(pending.userId(), newHash, pending.tenantId());
        log.info("Forced password change completed for user {} (tenant {})", pending.userId(), pending.tenantId());
        // Sessions and refresh tokens from before the change end; this browser signs in next.
        sessionRevoker.signOutElsewhere(pending.userId(), session.getId());
        return "redirect:/login?passwordChanged";
    }
}
