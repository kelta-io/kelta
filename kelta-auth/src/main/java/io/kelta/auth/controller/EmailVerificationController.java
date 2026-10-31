package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.service.WorkerClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Email-ownership verification flow. The user receives a {@code user.email_verification}
 * link that hits {@link #verify(String)} — we flip {@code email_verified=true} and
 * clear the token. Tokens live 48 hours.
 */
@RestController
@RequestMapping("/auth/email")
public class EmailVerificationController {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationController.class);
    private static final Duration TOKEN_EXPIRY = Duration.ofHours(48);

    private final JdbcTemplate jdbcTemplate;
    private final WorkerClient workerClient;
    private final String uiBaseUrl;

    public EmailVerificationController(JdbcTemplate jdbcTemplate,
                                        WorkerClient workerClient,
                                        AuthProperties authProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.workerClient = workerClient;
        this.uiBaseUrl = authProperties.getUiBaseUrl();
    }

    public record SendVerificationRequest(@NotBlank @Email String email) {}

    @PostMapping("/send-verification")
    public ResponseEntity<Map<String, String>> sendVerification(@Valid @RequestBody SendVerificationRequest request) {
        var rows = jdbcTemplate.queryForList(
                "SELECT pu.id, pu.email, pu.first_name, pu.tenant_id, t.name AS tenant_name "
                        + "FROM platform_user pu JOIN tenant t ON t.id = pu.tenant_id "
                        + "WHERE pu.email = ? AND pu.status = 'ACTIVE' AND pu.user_type <> 'SERVICE'",
                request.email());
        if (rows.isEmpty()) {
            // Don't leak account existence.
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        var row = rows.get(0);
        String userId = (String) row.get("id");
        String tenantId = (String) row.get("tenant_id");

        String token = UUID.randomUUID().toString();
        Instant expires = Instant.now().plus(TOKEN_EXPIRY);
        jdbcTemplate.update(
                "UPDATE platform_user SET email_verification_token = ?, "
                        + "email_verification_expires_at = ? WHERE id = ?",
                token, Timestamp.from(expires), userId);

        String actionUrl = uiBaseUrl + "/auth/email/verify?token=" + token;
        workerClient.sendTemplateEmail(tenantId, request.email(), "user.email_verification",
                Map.of(
                        "tenantName", row.getOrDefault("tenant_name", "Kelta"),
                        "firstName",  row.getOrDefault("first_name", ""),
                        "email",      request.email(),
                        "actionUrl",  actionUrl,
                        "expiresIn",  "48 hours"),
                "EMAIL_VERIFICATION", userId);
        log.info("Email verification queued for {}", request.email());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@RequestParam String token) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, email_verification_expires_at FROM platform_user "
                        + "WHERE email_verification_token = ?",
                token);
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired token"));
        }
        var row = rows.get(0);
        Timestamp expires = (Timestamp) row.get("email_verification_expires_at");
        if (expires == null || Instant.now().isAfter(expires.toInstant())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired token"));
        }
        String userId = (String) row.get("id");
        jdbcTemplate.update(
                "UPDATE platform_user SET email_verified = true, "
                        + "email_verification_token = NULL, "
                        + "email_verification_expires_at = NULL, "
                        + "updated_at = NOW() WHERE id = ?",
                userId);
        log.info("Email verified for user {}", userId);
        return ResponseEntity.ok(Map.of("status", "verified"));
    }
}
