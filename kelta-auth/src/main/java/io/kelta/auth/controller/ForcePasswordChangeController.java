package io.kelta.auth.controller;

import io.kelta.auth.session.UserSessionRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;

import java.util.List;

@Controller
public class ForcePasswordChangeController {

    private static final Logger log = LoggerFactory.getLogger(ForcePasswordChangeController.class);
    public static final String SESSION_ATTR_EMAIL = "force_change_email";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionRevoker sessionRevoker;

    public ForcePasswordChangeController(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                                         UserSessionRevoker sessionRevoker) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevoker = sessionRevoker;
    }

    @GetMapping("/change-password")
    public String showChangePasswordForm(HttpSession session, Model model) {
        String email = (String) session.getAttribute(SESSION_ATTR_EMAIL);
        if (email == null) {
            return "redirect:/login";
        }
        model.addAttribute("email", email);
        return "change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            HttpSession session,
            Model model) {

        String email = (String) session.getAttribute(SESSION_ATTR_EMAIL);
        if (email == null) {
            return "redirect:/login";
        }

        // Validate new password
        if (newPassword.length() < 8) {
            model.addAttribute("email", email);
            model.addAttribute("error", "New password must be at least 8 characters.");
            return "change-password";
        }

        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("email", email);
            model.addAttribute("error", "New passwords do not match.");
            return "change-password";
        }

        // Verify current password
        var results = jdbcTemplate.queryForList(
                "SELECT uc.password_hash, uc.user_id FROM user_credential uc " +
                        "JOIN platform_user pu ON pu.id = uc.user_id " +
                        "WHERE (pu.email = ? OR pu.username = ?) AND pu.status = 'ACTIVE'",
                email, email
        );

        if (results.isEmpty()) {
            model.addAttribute("email", email);
            model.addAttribute("error", "Account not found.");
            return "change-password";
        }

        String currentHash = (String) results.get(0).get("password_hash");
        if (!passwordEncoder.matches(currentPassword, currentHash)) {
            model.addAttribute("email", email);
            model.addAttribute("error", "Current password is incorrect.");
            return "change-password";
        }

        // Update password and clear force flag for ALL tenant records with this email
        List<String> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM platform_user WHERE (email = ? OR username = ?) AND status = 'ACTIVE'",
                String.class, email, email);
        String newHash = passwordEncoder.encode(newPassword);
        jdbcTemplate.update(
                "UPDATE user_credential SET password_hash = ?, password_changed_at = NOW(), " +
                        "force_change_on_login = false, updated_at = NOW() " +
                        "WHERE user_id IN (SELECT id FROM platform_user WHERE (email = ? OR username = ?) AND status = 'ACTIVE')",
                newHash, email, email
        );

        session.removeAttribute(SESSION_ATTR_EMAIL);
        log.info("Forced password change completed for user {}", email);
        // Sessions and refresh tokens from before the change end; this browser signs in next.
        for (String userId : userIds) {
            sessionRevoker.signOutElsewhere(userId, session.getId());
        }

        return "redirect:/login?passwordChanged";
    }
}
