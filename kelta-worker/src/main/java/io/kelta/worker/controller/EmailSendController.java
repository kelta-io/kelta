package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transactional email-send endpoint used by UI Quick Actions (the {@code send_email} action).
 *
 * <p>{@code POST /api/email/send} renders an <em>admin-authored</em> email template (selected by
 * {@code templateId} or {@code templateKey}) against a merge context and queues it for delivery
 * through {@link EmailService}. This is a spam surface, so it is deliberately narrow:
 *
 * <ul>
 *   <li><b>No arbitrary body.</b> The client may only reference a stored template — subject and
 *       body always come from {@code email_template}, never from the request.</li>
 *   <li><b>Permission-gated.</b> Requires the {@code MANAGE_EMAIL_TEMPLATES} system permission.
 *       {@code /api/*} routes get only the blanket {@code API_ACCESS} check at the gateway, so the
 *       specific permission is enforced here (see kelta-worker/CLAUDE.md → Authorization).</li>
 *   <li><b>Rate-limited.</b> Capped per tenant per rolling window to blunt bulk abuse.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/email")
public class EmailSendController {

    private static final Logger log = LoggerFactory.getLogger(EmailSendController.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    /**
     * Sending a stored template is the same authoring authority as managing the templates
     * themselves, so gate on {@code MANAGE_EMAIL_TEMPLATES}.
     */
    private static final String SEND_PERMISSION = "MANAGE_EMAIL_TEMPLATES";

    /** Source tag written to {@code email_log} for sends originating from Quick Actions. */
    private static final String SOURCE = "QUICK_ACTION";

    /** Rate-limit window and cap: at most {@value #MAX_SENDS_PER_WINDOW} sends per tenant per window. */
    static final int RATE_LIMIT_WINDOW_MINUTES = 60;
    static final int MAX_SENDS_PER_WINDOW = 200;

    private final EmailService emailService;
    private final EmailRepository emailRepository;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public EmailSendController(EmailService emailService,
                              EmailRepository emailRepository,
                              CerbosPermissionResolver permissionResolver,
                              BootstrapRepository bootstrapRepository) {
        this.emailService = emailService;
        this.emailRepository = emailRepository;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    /**
     * Request body. Exactly one of {@code templateId} / {@code templateKey} must be supplied;
     * {@code to} is the (already-resolved) recipient address; {@code mergeContext} feeds
     * {@code ${var}} substitution in the template.
     */
    public record SendRequest(
            String templateId,
            String templateKey,
            @NotBlank @Email String to,
            Map<String, Object> mergeContext,
            String recordId
    ) {
        public SendRequest(String templateId, String templateKey, String to,
                           Map<String, Object> mergeContext) {
            this(templateId, templateKey, to, mergeContext, null);
        }
    }

    /**
     * Lists email log entries whose {@code source_id} is the given record —
     * consumed by the record activity timeline. RLS scopes rows to the tenant.
     *
     * <p>Gated on {@code MANAGE_EMAIL_TEMPLATES} like {@code /send}: {@code /api/email/**} is a
     * static gateway route with only the blanket {@code API_ACCESS} check, and the log names
     * recipients and subjects for any record id the caller supplies, whether or not they can
     * read that record. The timeline treats a refusal as "no email entries".
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> listLogsByRecord(
            @RequestParam String recordId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        requireSendPermission(httpRequest);

        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> logs = emailRepository.findLogsBySourceId(recordId, effectiveLimit);

        List<Map<String, Object>> records = logs.stream()
                .map(row -> {
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("id", row.get("id"));
                    record.put("recipientEmail", row.get("recipient_email"));
                    record.put("subject", row.get("subject"));
                    record.put("status", row.get("status"));
                    record.put("sentAt", row.get("sent_at") != null ? row.get("sent_at").toString() : null);
                    record.put("createdAt", row.get("created_at") != null ? row.get("created_at").toString() : null);
                    return record;
                })
                .toList();

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("email-logs", records));
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> send(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody SendRequest request,
            HttpServletRequest httpRequest) {

        requireSendPermission(httpRequest);

        boolean hasId = request.templateId() != null && !request.templateId().isBlank();
        boolean hasKey = request.templateKey() != null && !request.templateKey().isBlank();
        if (hasId == hasKey) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provide exactly one of templateId or templateKey"));
        }

        enforceRateLimit(tenantId, httpRequest);

        Map<String, Object> vars = request.mergeContext() == null ? Map.of() : request.mergeContext();
        // Stamp the originating record (when provided) as source_id so the
        // record's activity timeline can list this email.
        String sourceId = request.recordId() != null && !request.recordId().isBlank()
                ? request.recordId() : null;
        Optional<String> logId = hasId
                ? emailService.sendById(tenantId, request.to(), request.templateId(), vars, SOURCE, sourceId)
                : emailService.sendByKey(tenantId, request.to(), request.templateKey(), vars, SOURCE, sourceId);

        if (logId.isEmpty()) {
            String ref = hasId ? request.templateId() : request.templateKey();
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Template not found: " + ref));
        }

        securityLog.info("security_event=EMAIL_SENT user={} tenant={} template={} emailLogId={}",
                permissionResolver.getEmail(httpRequest), tenantId,
                hasId ? request.templateId() : request.templateKey(), logId.get());

        return ResponseEntity.ok(Map.of("emailLogId", logId.get(), "status", "QUEUED"));
    }

    private void requireSendPermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> SEND_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, SEND_PERMISSION + " permission required");
        }
    }

    private void enforceRateLimit(String tenantId, HttpServletRequest request) {
        Instant windowStart = Instant.now().minus(RATE_LIMIT_WINDOW_MINUTES, ChronoUnit.MINUTES);
        int recent = emailRepository.countRecentByTenant(tenantId, windowStart);
        if (recent >= MAX_SENDS_PER_WINDOW) {
            securityLog.warn("security_event=EMAIL_RATE_LIMITED user={} tenant={} recent={}",
                    permissionResolver.getEmail(request), tenantId, recent);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Email send rate limit exceeded — try again later");
        }
    }
}
