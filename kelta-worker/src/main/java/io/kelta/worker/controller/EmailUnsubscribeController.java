package io.kelta.worker.controller;

import io.kelta.worker.repository.EmailSuppressionRepository;
import io.kelta.worker.service.campaign.TrackingTokenService;
import io.kelta.worker.service.campaign.TrackingTokenService.SignedAddress;
import io.kelta.worker.util.TenantContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Public (unauthenticated) opt-out for non-transactional flow mail ({@code EMAIL_ALERT} with
 * {@code transactional: false}), the target of its {@code List-Unsubscribe} header.
 *
 * <p>Reached through the gateway's {@code /api/track} {@code unauthenticated-paths} entry, so
 * the address token ({@link TrackingTokenService#signAddress}) is the sole authenticator. It
 * carries the tenant and the address; a verified token adds that address to the tenant's
 * {@code email_suppression} list ({@code reason = UNSUBSCRIBE}, no campaign).
 *
 * <ul>
 *   <li>{@code POST ?t=} — RFC 8058 one-click unsubscribe. Idempotent: a repeat is 200 and
 *       writes nothing new.</li>
 *   <li>{@code GET ?t=} — a confirmation page whose form POSTs. GET never writes, because
 *       mail security scanners prefetch every link in a message.</li>
 * </ul>
 *
 * A bad token is 400 with one generic page; which part failed is never revealed.
 */
@RestController
@RequestMapping("/api/track")
public class EmailUnsubscribeController {

    private static final Logger log = LoggerFactory.getLogger(EmailUnsubscribeController.class);
    static final String REASON = "UNSUBSCRIBE";

    private final EmailSuppressionRepository suppressionRepository;
    private final TrackingTokenService tokenService;

    public EmailUnsubscribeController(EmailSuppressionRepository suppressionRepository,
                                      TrackingTokenService tokenService) {
        this.suppressionRepository = suppressionRepository;
        this.tokenService = tokenService;
    }

    @GetMapping(value = "/email-unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(@RequestParam(value = "t", required = false) String token) {
        if (tokenService.verifyAddress(token).isEmpty()) {
            return invalid();
        }
        String action = "email-unsubscribe?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return noStore(HttpStatus.OK, page("Unsubscribe",
                "<p style=\"color:#555\">Stop receiving these emails?</p>"
                        + "<form method=\"post\" action=\"" + action + "\">"
                        + "<button type=\"submit\">Unsubscribe</button></form>"));
    }

    @PostMapping(value = "/email-unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam(value = "t", required = false) String token) {
        Optional<SignedAddress> address = tokenService.verifyAddress(token);
        if (address.isEmpty()) {
            return invalid();
        }
        SignedAddress a = address.get();
        try {
            TenantContextUtils.withTenant(a.tenantId(),
                    () -> suppressionRepository.add(a.tenantId(), a.email(), REASON, null, null));
        } catch (Exception e) {
            log.warn("Failed to record flow-mail unsubscribe for tenant {}: {}", a.tenantId(), e.getMessage());
            return noStore(HttpStatus.SERVICE_UNAVAILABLE, page("Try again later",
                    "<p style=\"color:#555\">We could not process your request. Please try again later.</p>"));
        }
        return noStore(HttpStatus.OK, page("Unsubscribed",
                "<p style=\"color:#555\">You have been unsubscribed and will no longer receive these emails.</p>"));
    }

    private static ResponseEntity<String> invalid() {
        return noStore(HttpStatus.BAD_REQUEST, page("Invalid link",
                "<p style=\"color:#555\">This unsubscribe link is invalid.</p>"));
    }

    private static ResponseEntity<String> noStore(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .body(body);
    }

    private static String page(String title, String content) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\">"
                + "<title>" + title + "</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:480px;margin:80px auto;text-align:center\">"
                + "<h1 style=\"font-size:20px\">" + title + "</h1>" + content + "</body></html>";
    }
}
