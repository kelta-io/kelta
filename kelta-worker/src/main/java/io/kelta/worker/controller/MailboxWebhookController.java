package io.kelta.worker.controller;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.worker.repository.MailboxInboundEventRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.service.credential.CredentialResolver;
import io.kelta.worker.service.credential.ResolutionContext;
import io.kelta.worker.service.mailbox.inbound.InboundMailAdapter;
import io.kelta.worker.service.mailbox.inbound.MailboxIngestService;
import io.kelta.runtime.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receives inbound mail. Unauthenticated at the gateway; authenticated here, per mailbox.
 *
 * <p>Sits under {@code /api/webhooks/**}, which is already on the gateway's platform and
 * unauthenticated prefix lists. That is deliberately a <b>different top-level segment</b> from the
 * authenticated {@code /api/support/**} product API: {@code PublicPathMatcher} matches by
 * {@code startsWith}, so an ingest path nested under the product path would mean one careless
 * prefix edit silently exposes the whole feature.
 *
 * <h2>Why almost everything returns 200</h2>
 *
 * <p>SNS retries on any non-2xx. A forged signature, an unparseable body or a duplicate delivery
 * will never succeed on retry, so answering with an error would buy an endless redelivery loop
 * and nothing else. The only 404 is an unknown mailbox key, because 32 random bytes are not
 * guessable and an operator who has misconfigured a provider needs to see it fail.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/webhooks/mail")
// Gated on the SAME property that creates EncryptionService and CredentialResolverImpl
// (both @ConditionalOnProperty "kelta.encryption.key"). Without the gate this component is a
// required dependency on beans that may not exist, and the whole worker fails to start in any
// environment without an encryption key. @ConditionalOnBean is deliberately not used here: it
// is order-sensitive during component scan and can drop the bean even when the dependency is
// later registered — see TenantEmailSettingsController for the same reasoning.
@ConditionalOnProperty(name = "kelta.encryption.key")
public class MailboxWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MailboxWebhookController.class);

    /** Hard cap before any parsing. SES tops out at 40 MB; this is headroom, not a limit. */
    private static final int MAX_BODY_BYTES = 45 * 1024 * 1024;

    /** Gateway-resolved client address; same header {@code LoginTrackingFilter} reads. */
    static final String CLIENT_IP_HEADER = "X-Kelta-Client-Ip";

    private final MailboxRepository mailboxRepository;
    private final MailboxInboundEventRepository eventRepository;
    private final MailboxIngestService ingestService;
    private final CredentialResolver credentialResolver;
    private final Map<String, InboundMailAdapter> adapters;

    public MailboxWebhookController(MailboxRepository mailboxRepository,
                                    MailboxInboundEventRepository eventRepository,
                                    MailboxIngestService ingestService,
                                    CredentialResolver credentialResolver,
                                    List<InboundMailAdapter> adapterBeans) {
        this.mailboxRepository = mailboxRepository;
        this.eventRepository = eventRepository;
        this.ingestService = ingestService;
        this.credentialResolver = credentialResolver;
        Map<String, InboundMailAdapter> byId = new LinkedHashMap<>();
        for (InboundMailAdapter adapter : adapterBeans) {
            byId.put(adapter.providerId(), adapter);
        }
        this.adapters = Collections.unmodifiableMap(byId);
    }

    /**
     * Providers post with all sorts of content types — SNS uses {@code text/plain} for JSON, a
     * raw-MIME poster uses {@code message/rfc822} — so the body is taken as bytes and interpreted
     * by the adapter. Bytes also matter because an HMAC covers exact bytes: re-serialising
     * through a JSON binder would change what is signed.
     */
    @PostMapping(value = "/{mailboxKey}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> receive(@PathVariable String mailboxKey,
                                        @RequestBody(required = false) byte[] rawBody,
                                        HttpServletRequest request) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        if (body.length > MAX_BODY_BYTES) {
            log.warn("Rejecting oversized inbound delivery: {} bytes", body.length);
            return ResponseEntity.ok().build();
        }

        // Resolved with NO tenant bound: this read rides the admin_bypass RLS policy because the
        // mailbox row is what determines the tenant. Same shape as the SES bounce path.
        Optional<Map<String, Object>> found = mailboxRepository.findByWebhookKey(mailboxKey);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> mailbox = found.get();

        if (!Boolean.TRUE.equals(mailbox.get("active"))) {
            // Drop rather than error: an inactive mailbox is a deliberate operator state, and a
            // non-2xx would have the provider retry it forever.
            return ResponseEntity.ok().build();
        }

        String tenantId = (String) mailbox.get("tenant_id");
        String provider = String.valueOf(mailbox.get("inbound_provider"));
        InboundMailAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            log.error("Mailbox {} is configured for provider '{}', which has no adapter",
                    mailbox.get("id"), provider);
            return ResponseEntity.ok().build();
        }

        String clientIp = clientIp(request);
        String allowedCidrs = (String) mailbox.get("inbound_allowed_cidrs");
        if (allowedCidrs != null && !allowedCidrs.isBlank() && !isAllowedSource(clientIp, allowedCidrs)) {
            // Same 200-and-drop as a failed signature: a caller outside the allowlist will not
            // succeed on retry either.
            log.warn("Discarding inbound delivery for mailbox {}: source {} is outside inbound_allowed_cidrs",
                    mailbox.get("id"), clientIp);
            return ResponseEntity.ok().build();
        }

        InboundMailAdapter.InboundRequest inbound =
                new InboundMailAdapter.InboundRequest(body, headersOf(request), clientIp);
        InboundMailAdapter.MailboxRef ref = refOf(mailbox);

        return TenantContext.callWithTenant(tenantId, () ->
                handle(adapter, inbound, ref, mailbox, tenantId, provider, body));
    }

    private ResponseEntity<Void> handle(InboundMailAdapter adapter,
                                        InboundMailAdapter.InboundRequest inbound,
                                        InboundMailAdapter.MailboxRef ref,
                                        Map<String, Object> mailbox,
                                        String tenantId,
                                        String provider,
                                        byte[] body) {
        InboundMailAdapter.InboundSecrets secrets = resolveSecrets(tenantId, mailbox);

        // Authenticate BEFORE anything else touches the payload, including the control path —
        // otherwise an unauthenticated caller could drive SNS subscription handling.
        if (!adapter.verify(inbound, ref, secrets)) {
            log.warn("Discarding inbound delivery for mailbox {}: verification failed", ref.id());
            return ResponseEntity.ok().build();
        }

        Optional<ResponseEntity<Void>> control = adapter.handleControl(inbound, ref);
        if (control.isPresent()) {
            return control.get();
        }

        InboundMailAdapter.InboundEnvelope envelope = adapter.extract(inbound, ref);
        if (envelope.ignore()) {
            return ResponseEntity.ok().build();
        }

        // Claim before parsing. A message that crashes the parser must not be retried forever;
        // recording it first makes the failure durable and stops the retry.
        String eventId = eventRepository.claim(tenantId, ref.id(), provider,
                        adapter.providerEventId(inbound).orElse(null),
                        MailboxIngestService.digestOf(body))
                .orElse(null);
        if (eventId == null) {
            log.debug("Duplicate delivery for mailbox {} — already ingested", ref.id());
            return ResponseEntity.ok().build();
        }

        try {
            ingestService.ingest(eventId, mailbox, envelope);
        } catch (Exception e) {
            // Left as RECEIVED rather than FAILED when the cause is transient (a fetch or a
            // database blip), so a repair job can re-drive it from the ledger. The raw MIME is
            // already stored by then in the common case.
            log.error("Ingest failed for mailbox {} (event {}): {}", ref.id(), eventId, e.getMessage(), e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Decrypts the mailbox's current and previous signing secrets.
     *
     * <p>The previous one is only offered while its overlap window is open — an expired secret is
     * not returned at all, so a leaked old key stops working on schedule rather than whenever
     * someone remembers to clean it up.
     */
    private InboundMailAdapter.InboundSecrets resolveSecrets(String tenantId, Map<String, Object> mailbox) {
        String current = secretOf(tenantId, (String) mailbox.get("inbound_secret_credential_id"));
        String previous = null;
        Object expiresAt = mailbox.get("inbound_prev_secret_expires_at");
        if (expiresAt instanceof Timestamp ts && ts.toInstant().isAfter(Instant.now())) {
            previous = secretOf(tenantId, (String) mailbox.get("inbound_prev_secret_credential_id"));
        }
        return new InboundMailAdapter.InboundSecrets(current, previous);
    }

    private String secretOf(String tenantId, String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return null;
        }
        try {
            ResolvedCredential resolved = credentialResolver.resolve(tenantId, credentialId,
                    ResolutionContext.forUser(null, "mailbox-inbound-verification"));
            Object secret = resolved.secret("secret");
            return secret == null ? null : secret.toString();
        } catch (Exception e) {
            // Never log the credential id's contents, and never fail open: an unresolvable secret
            // means this delivery cannot be verified, so it will be dropped.
            log.warn("Could not resolve an inbound signing secret for tenant {}: {}",
                    tenantId, e.getMessage());
            return null;
        }
    }

    private static InboundMailAdapter.MailboxRef refOf(Map<String, Object> mailbox) {
        return new InboundMailAdapter.MailboxRef(
                (String) mailbox.get("id"),
                (String) mailbox.get("tenant_id"),
                String.valueOf(mailbox.get("inbound_provider")),
                (String) mailbox.get("provider_topic_arn"),
                (String) mailbox.get("inbound_allowed_cidrs"),
                mailbox.get("max_message_bytes") instanceof Number n ? n.longValue() : 26_214_400L,
                mailbox.get("max_attachments") instanceof Number n ? n.intValue() : 20,
                mailbox.get("max_attachment_bytes") instanceof Number n ? n.longValue() : 26_214_400L);
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            out.put(name, request.getHeader(name));
        }
        return out;
    }

    /**
     * The caller's address: {@value #CLIENT_IP_HEADER}, which the gateway resolves through
     * {@code kelta.security.trusted-proxies} and stamps on every request after stripping any
     * client-sent copy. {@code X-Forwarded-For} is deliberately not read — the gateway no longer
     * forwards it, and where it arrives it is client-controlled.
     */
    static String clientIp(HttpServletRequest request) {
        String gatewayResolved = request.getHeader(CLIENT_IP_HEADER);
        if (gatewayResolved != null && !gatewayResolved.isBlank()) {
            return gatewayResolved.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Whether {@code ip} falls inside one of the comma-separated CIDRs (or bare addresses).
     * Fails closed: an unparseable caller address or entry never matches.
     */
    static boolean isAllowedSource(String ip, String allowedCidrs) {
        byte[] candidate;
        try {
            candidate = InetAddress.ofLiteral(ip).getAddress();
        } catch (RuntimeException e) {
            return false;
        }
        for (String entry : allowedCidrs.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty() && cidrContains(trimmed, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cidrContains(String cidr, byte[] candidate) {
        int slash = cidr.indexOf('/');
        byte[] network;
        int prefix;
        try {
            network = InetAddress.ofLiteral(slash < 0 ? cidr : cidr.substring(0, slash).trim()).getAddress();
            prefix = slash < 0 ? network.length * 8 : Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (RuntimeException e) {
            log.warn("Ignoring unparseable inbound_allowed_cidrs entry '{}'", cidr);
            return false;
        }
        if (network.length != candidate.length || prefix < 0 || prefix > network.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefix % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
