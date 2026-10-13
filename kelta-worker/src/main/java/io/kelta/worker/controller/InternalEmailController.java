package io.kelta.worker.controller;

import io.kelta.runtime.module.integration.spi.EmailService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Internal endpoint for cross-service email delivery (e.g., auth → worker).
 *
 * <p>Secured with a shared internal token ({@code X-Internal-Token} header).
 * This endpoint must NOT be routable through the gateway — it is for
 * service-to-service calls only within the Kubernetes cluster.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/internal/email")
public class InternalEmailController {

    private static final Logger log = LoggerFactory.getLogger(InternalEmailController.class);

    private final EmailService emailService;
    private final JdbcTemplate jdbcTemplate;
    private final String internalToken;
    private final String appBaseUrl;

    /**
     * {@code appBaseUrl} is the end-user app's host (https://app.kelta.io), the same setting the
     * support-mailbox emails link to; it falls back to {@code kelta.external-base-url} for local
     * single-host setups.
     */
    public InternalEmailController(
            EmailService emailService,
            JdbcTemplate jdbcTemplate,
            @Value("${kelta.internal.token:}") String internalToken,
            @Value("${kelta.mailbox.app-base-url:${kelta.external-base-url:}}") String appBaseUrl) {
        this.emailService = emailService;
        this.jdbcTemplate = jdbcTemplate;
        this.internalToken = internalToken;
        this.appBaseUrl = appBaseUrl == null ? "" : appBaseUrl.trim().replaceAll("/+$", "");
    }

    public record SendEmailRequest(
            @NotBlank String tenantId,
            @NotBlank @Email String to,
            @NotBlank String subject,
            @NotBlank String body,
            String source,
            String sourceId
    ) {}

    public record SendTemplateRequest(
            @NotBlank String tenantId,
            @NotBlank @Email String to,
            @NotBlank String templateKey,
            Map<String, Object> vars,
            String source,
            String sourceId
    ) {}

    public record SendInviteRequest(
            @NotBlank @Email String email,
            @NotBlank String tenantId,
            @NotBlank String inviteToken
    ) {}

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendEmail(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody SendEmailRequest request) {

        ResponseEntity<Map<String, String>> denial = checkInternalToken(token);
        if (denial != null) return denial;

        String emailLogId = emailService.queueEmail(
                request.tenantId(),
                request.to(),
                request.subject(),
                request.body(),
                request.source(),
                request.sourceId()
        );

        return ResponseEntity.ok(Map.of(
                "emailLogId", emailLogId,
                "status", "QUEUED"
        ));
    }

    @PostMapping("/send-template")
    public ResponseEntity<Map<String, String>> sendTemplate(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody SendTemplateRequest request) {

        ResponseEntity<Map<String, String>> denial = checkInternalToken(token);
        if (denial != null) return denial;

        return emailService.sendByKey(
                request.tenantId(), request.to(), request.templateKey(),
                request.vars(), request.source(), request.sourceId())
                .map(id -> ResponseEntity.ok(Map.of("emailLogId", id, "status", "QUEUED")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Template not found: " + request.templateKey())));
    }

    /**
     * Sends a user-invitation email using the {@code user_invite} system template — kelta-auth's
     * notice to a user it just provisioned at their first single sign-on.
     *
     * <p>Resolves the template via {@link io.kelta.worker.repository.EmailRepository#findTemplateByName}
     * (tenant override → {@code system} default seeded in V141), substitutes
     * {@code ${inviteLink}} and {@code ${tenantName}}, and queues delivery through
     * {@link EmailService}. Tenant SMTP credentials apply when configured; otherwise
     * the platform default SMTP is used.
     *
     * <p>The user signs in with their identity provider and has no password to set, so
     * {@code ${inviteLink}} is the tenant's app ({@code <app-base-url>/<slug>/app}), which starts
     * that sign-in. {@code inviteToken} only identifies the send in the email log.
     */
    @PostMapping("/invite")
    public ResponseEntity<Map<String, String>> sendInvite(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody SendInviteRequest request) {

        ResponseEntity<Map<String, String>> denial = checkInternalToken(token);
        if (denial != null) return denial;

        Tenant tenant = lookupTenant(request.tenantId());
        Map<String, Object> vars = Map.of(
                "inviteLink", appLink(tenant.slug()),
                "tenantName", tenant.name());

        return emailService.sendByName(
                request.tenantId(), request.email(), "user_invite",
                vars, "USER_INVITE", request.inviteToken())
                .map(id -> ResponseEntity.ok(Map.of("emailLogId", id, "status", "QUEUED")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Template not found: user_invite")));
    }

    private record Tenant(String name, String slug) {}

    private Tenant lookupTenant(String tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name, slug FROM tenant WHERE id = ?", tenantId);
            if (!rows.isEmpty()) {
                Object name = rows.get(0).get("name");
                Object slug = rows.get(0).get("slug");
                return new Tenant(name instanceof String s && !s.isBlank() ? s : "Kelta",
                        slug instanceof String s && !s.isBlank() ? s : null);
            }
        } catch (Exception e) {
            log.warn("Failed to look up tenant {}: {}", tenantId, e.getMessage());
        }
        return new Tenant("Kelta", null);
    }

    /** The tenant's end-user app, or the app host alone when the tenant is unknown. */
    private String appLink(String slug) {
        return slug == null ? appBaseUrl + "/" : appBaseUrl + "/" + URLEncoder.encode(slug, StandardCharsets.UTF_8) + "/app";
    }

    private ResponseEntity<Map<String, String>> checkInternalToken(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Internal email endpoint called but kelta.internal.token is not configured");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Internal token not configured"));
        }
        if (!internalToken.equals(token)) {
            log.warn("Internal email endpoint called with invalid token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid internal token"));
        }
        return null;
    }
}
