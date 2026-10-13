package io.kelta.auth.controller;

import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.PasswordPolicyService;
import io.kelta.auth.service.PasswordResetService;
import io.kelta.auth.session.UserSessionRevoker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth/password")
public class PasswordController {

    private static final Logger log = LoggerFactory.getLogger(PasswordController.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService policyService;
    private final UserSessionRevoker sessionRevoker;
    private final PasswordResetService resetService;

    public PasswordController(JdbcTemplate jdbcTemplate,
                              PasswordEncoder passwordEncoder,
                              PasswordPolicyService policyService,
                              UserSessionRevoker sessionRevoker,
                              PasswordResetService resetService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.policyService = policyService;
        this.sessionRevoker = sessionRevoker;
        this.resetService = resetService;
    }

    private static final Map<String, String> RESET_REQUESTED = Map.of("status", "ok",
            "message", "If an account exists with that email, a reset link has been sent.");

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}

    public record ResetRequestBody(
            @NotBlank @Email String email
    ) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}

    /** The signed-in user's account and current password hash. */
    private static final String ACCOUNT_SELECT =
            "SELECT pu.id, pu.tenant_id, pu.email, pu.first_name, pu.last_name, uc.password_hash "
                    + "FROM platform_user pu JOIN user_credential uc ON uc.user_id = pu.id ";

    private record Account(String userId, String tenantId, String email, String displayName, String passwordHash) {}

    @PostMapping("/change")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        // If no account matches the authenticated principal — shouldn't happen in normal flows
        // but can if the account was deleted mid-session — return the same generic error as a
        // wrong password rather than bubbling an unchecked 500.
        Optional<Account> found = findAccount(authentication);
        if (found.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect"));
        }
        Account account = found.get();

        if (account.passwordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), account.passwordHash())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect"));
        }

        // Validate against password policy
        List<String> violations = policyService.validatePassword(
                request.newPassword(), account.email(), account.displayName(), account.tenantId());
        if (!violations.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", String.join("; ", violations)));
        }

        // Check password history
        Optional<String> historyViolation =
                policyService.checkHistory(account.userId(), request.newPassword(), account.tenantId());
        if (historyViolation.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", historyViolation.get()));
        }

        // Update password
        String newHash = passwordEncoder.encode(request.newPassword());
        jdbcTemplate.update(
                "UPDATE user_credential SET password_hash = ?, password_changed_at = ?, " +
                        "force_change_on_login = false, updated_at = ? " +
                        "WHERE user_id = ?",
                newHash, now(), now(), account.userId()
        );

        policyService.saveToHistory(account.userId(), newHash, account.tenantId());

        log.info("Password changed for user {} (tenant {})", account.userId(), account.tenantId());
        // Every other device signs in again with the new password; this session stays signed in.
        sessionRevoker.signOutElsewhere(account.userId(), currentSessionId(httpRequest));
        resetService.sendPasswordChangedEmail(account.userId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * The signed-in user's account: by tenant and user id when the principal is a platform user
     * (an email address can belong to users in several tenants), else by login name when exactly
     * one account has it.
     */
    private Optional<Account> findAccount(Authentication authentication) {
        List<Map<String, Object>> rows;
        if (authentication.getPrincipal() instanceof KeltaUserDetails user
                && user.getId() != null && user.getTenantId() != null) {
            rows = jdbcTemplate.queryForList(ACCOUNT_SELECT + "WHERE pu.id = ? AND pu.tenant_id = ?",
                    user.getId(), user.getTenantId());
        } else {
            rows = jdbcTemplate.queryForList(ACCOUNT_SELECT + "WHERE pu.email = ?", authentication.getName());
        }
        if (rows.size() != 1) {
            log.warn("Password change: {} accounts match the signed-in principal", rows.size());
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        String firstName = row.get("first_name") instanceof String value ? value : "";
        String lastName = row.get("last_name") instanceof String value ? value : "";
        return Optional.of(new Account((String) row.get("id"), (String) row.get("tenant_id"),
                (String) row.get("email"), (firstName + " " + lastName).trim(), (String) row.get("password_hash")));
    }

    /**
     * The current time as a JDBC timestamp. The PostgreSQL driver cannot bind a
     * {@code java.time.Instant} ("Can't infer the SQL type"), so every timestamp parameter goes
     * through {@link Timestamp}.
     */
    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static String currentSessionId(HttpServletRequest request) {
        HttpSession session = request == null ? null : request.getSession(false);
        return session == null ? null : session.getId();
    }

    /**
     * Mails a reset link to each active account with this email address
     * ({@link PasswordResetService#requestReset}). Open to signed-out callers, CSRF-exempt and
     * rate limited per IP; always the same answer, so the response does not tell whether the
     * address has an account.
     */
    @PostMapping("/reset-request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody ResetRequestBody request,
            HttpServletRequest httpRequest) {
        resetService.requestReset(request.email(), httpRequest);
        return ResponseEntity.ok(RESET_REQUESTED);
    }

    /** Sets a new password with an emailed reset token ({@link PasswordResetService#reset}). */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        return switch (resetService.reset(request.token(), request.newPassword(), currentSessionId(httpRequest))) {
            case PasswordResetService.ResetResult.Done done -> ResponseEntity.ok(Map.of("status", "ok"));
            case PasswordResetService.ResetResult.InvalidToken invalid ->
                    ResponseEntity.badRequest().body(Map.of("error", invalid.message()));
            case PasswordResetService.ResetResult.Rejected rejected ->
                    ResponseEntity.badRequest().body(Map.of("error", rejected.message()));
        };
    }
}
