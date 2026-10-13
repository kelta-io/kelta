package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The signed-out "forgot password" pages: {@code /forgot-password} asks for the email address
 * and mails a reset link, {@code /reset-password?token=…} (the link) sets the new password. Both
 * are form posts with the session's CSRF token, like the login form; the JSON equivalents for an
 * external frontend are {@code /auth/password/reset-request} and {@code /auth/password/reset}
 * ({@link PasswordController}). The work is {@link PasswordResetService}'s.
 *
 * <p>Every handler takes the {@link HttpSession}, so the session exists before the page renders:
 * the form's CSRF token is stored in it while Thymeleaf writes the form, and by then part of the
 * response may already be sent ("Cannot create a session after the response has been committed").
 * The emailed link is often opened in a browser with no session yet.
 */
@Controller
public class ForgotPasswordController {

    /** Where {@code HttpSessionRequestCache} keeps the authorization request sign-in resumes. */
    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";

    private final PasswordResetService resetService;
    private final String uiBaseUrl;

    public ForgotPasswordController(PasswordResetService resetService, AuthProperties authProperties) {
        this.resetService = resetService;
        String base = authProperties.getUiBaseUrl() == null ? "" : authProperties.getUiBaseUrl();
        this.uiBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @GetMapping("/forgot-password")
    public String showForgotPasswordForm(HttpSession session) {
        return "forgot-password";
    }

    /** Always the same page, whether or not the address has an account. */
    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam("email") String email, HttpServletRequest request,
                               HttpSession session, Model model) {
        resetService.requestReset(email, request);
        model.addAttribute("sent", true);
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String showResetForm(@RequestParam(value = "token", required = false) String token,
                                HttpSession session, Model model) {
        if (!resetService.isUsable(token)) {
            model.addAttribute("invalid", true);
            return "reset-password";
        }
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam("token") String token,
                                @RequestParam("newPassword") String newPassword,
                                @RequestParam("confirmPassword") String confirmPassword,
                                HttpSession session,
                                Model model) {
        model.addAttribute("token", token);
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New passwords do not match.");
            return "reset-password";
        }
        return switch (resetService.reset(token, newPassword, session.getId())) {
            case PasswordResetService.ResetResult.Done done -> {
                // Came from the sign-in page of an app: sign in there, which resumes it.
                if (session.getAttribute(SAVED_REQUEST) != null) {
                    yield "redirect:/login?passwordReset";
                }
                // Opened from the email in a fresh browser: the tenant's app starts the sign-in.
                model.addAttribute("done", true);
                model.addAttribute("appUrl", done.tenantSlug() == null ? null : uiBaseUrl + "/" + done.tenantSlug() + "/app");
                yield "reset-password";
            }
            case PasswordResetService.ResetResult.InvalidToken invalid -> {
                model.addAttribute("invalid", true);
                yield "reset-password";
            }
            case PasswordResetService.ResetResult.Rejected rejected -> {
                model.addAttribute("error", rejected.message());
                yield "reset-password";
            }
        };
    }
}
