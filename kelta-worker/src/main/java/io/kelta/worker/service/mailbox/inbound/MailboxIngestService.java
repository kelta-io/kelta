package io.kelta.worker.service.mailbox.inbound;

import io.kelta.worker.repository.MailboxAttachmentRepository;
import io.kelta.worker.service.mailbox.AttachmentContentType;
import io.kelta.worker.repository.MailboxInboundEventRepository;
import io.kelta.worker.repository.MailboxMessageRepository;
import io.kelta.worker.repository.MailboxThreadRepository;
import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.mailbox.MailboxHtmlSanitizer;
import io.kelta.worker.service.mailbox.SesInboundMailStore;
import io.kelta.worker.service.mailbox.UnattendedAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a verified delivery into stored rows.
 *
 * <p>Runs after the adapter has authenticated the request and after the delivery has been claimed
 * in the idempotency ledger, so this class can assume it is the only one handling this message.
 *
 * @since 1.0.0
 */
@Service
public class MailboxIngestService {

    private static final Logger log = LoggerFactory.getLogger(MailboxIngestService.class);

    private static final int SNIPPET_LENGTH = 300;

    private final MimeParser mimeParser;
    private final MailboxHtmlSanitizer sanitizer;
    private final MailboxThreadResolver threadResolver;
    private final MailboxThreadRepository threadRepository;
    private final MailboxMessageRepository messageRepository;
    private final MailboxAttachmentRepository attachmentRepository;
    private final MailboxInboundEventRepository eventRepository;
    private final SesInboundMailStore sesInboundMailStore;
    private final S3StorageService storageService;
    private final ObjectMapper objectMapper;

    public MailboxIngestService(MimeParser mimeParser,
                                MailboxHtmlSanitizer sanitizer,
                                MailboxThreadResolver threadResolver,
                                MailboxThreadRepository threadRepository,
                                MailboxMessageRepository messageRepository,
                                MailboxAttachmentRepository attachmentRepository,
                                MailboxInboundEventRepository eventRepository,
                                SesInboundMailStore sesInboundMailStore,
                                S3StorageService storageService,
                                ObjectMapper objectMapper) {
        this.mimeParser = mimeParser;
        this.sanitizer = sanitizer;
        this.threadResolver = threadResolver;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.eventRepository = eventRepository;
        this.sesInboundMailStore = sesInboundMailStore;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Ingests one delivery. Must be called with the mailbox's tenant bound.
     *
     * @param eventId the already-claimed ledger row
     */
    public void ingest(String eventId, Map<String, Object> mailbox,
                       InboundMailAdapter.InboundEnvelope envelope) {
        String tenantId = (String) mailbox.get("tenant_id");
        String mailboxId = (String) mailbox.get("id");

        byte[] raw = envelope.rawMime();
        if (raw == null && envelope.deferredFetch() != null) {
            // Fetching may fail — the AWS bucket could be unreachable or the object not yet
            // consistent. Leave the ledger row in RECEIVED so a repair job can re-drive it, and
            // let the exception surface: silently swallowing here loses a real customer email.
            var fetch = envelope.deferredFetch();
            raw = sesInboundMailStore.fetch(fetch.bucket(), fetch.key());
        }
        if (raw == null) {
            eventRepository.markRejected(eventId, tenantId, "REJECTED", "No message content");
            return;
        }

        // Persist the original BEFORE parsing. If the parser then throws, the bytes are still
        // durable and the message can be re-parsed once the parser is fixed, rather than being
        // known only as a stack trace.
        String rawKey = storeRaw(tenantId, mailboxId, eventId, raw);
        if (rawKey != null) {
            eventRepository.markRawStored(eventId, tenantId, rawKey);
        }

        MimeParser.Limits limits = new MimeParser.Limits(
                longOf(mailbox.get("max_message_bytes"), 26_214_400L),
                intOf(mailbox.get("max_attachments"), 20),
                longOf(mailbox.get("max_attachment_bytes"), 26_214_400L));

        NormalizedInboundMail mail;
        try {
            mail = mimeParser.parse(raw, envelope.verdicts(), limits);
        } catch (MimeParser.MailParseException e) {
            log.warn("Could not parse inbound message for mailbox {}: {}", mailboxId, e.getMessage());
            eventRepository.markRejected(eventId, tenantId, "FAILED", e.getMessage());
            return;
        }

        // A virus verdict means the attachments are hostile. Store the message so an operator can
        // see what arrived, but never the attachments, and never start an SLA clock on it.
        boolean infected = mail.verdicts().virusFailed();
        boolean spam = mail.verdicts().spamFailed();

        MailboxHtmlSanitizer.Result sanitized = sanitizer.sanitize(mail.bodyHtml());
        String bodyText = MimeParser.normaliseVisibleText(
                MimeParser.textFallback(mail.bodyText(), sanitized.html()));

        // DMARC pass is a necessary condition for treating a sender as who they claim to be, and
        // deliberately not a sufficient one — it proves the domain, not the person. requester_verified
        // stays false here; identity matching is the console's job, and auto-reply's veto list
        // reads dmarc_result directly.
        boolean requesterVerified = false;
        String verificationMethod = null;

        MailboxThreadResolver.Resolution resolution =
                threadResolver.resolve(tenantId, mailbox, mail, requesterVerified, verificationMethod);

        String messageId = messageRepository.insertInbound(new MailboxMessageRepository.InboundInsert(
                tenantId, resolution.threadId(), mailboxId,
                mail.messageId(), mail.inReplyTo(), mail.references(),
                mail.fromAddress(), mail.fromName(), mail.toAddresses(), mail.ccAddresses(),
                mail.replyToAddress(), mail.subject(),
                bodyText, mail.bodyHtml(), sanitized.html(), snippet(bodyText),
                headersJson(mail.headers()),
                mail.verdicts().spf(), mail.verdicts().dkim(), mail.verdicts().dmarc(),
                mail.verdicts().dmarcPolicy(), mail.verdicts().spam(), mail.verdicts().virus(),
                mail.autoSubmitted(), mail.precedence(), mail.bulk(), mail.bounce(),
                rawKey, mail.rawSizeBytes(), mail.sentAt()));

        threadRepository.recordInbound(tenantId, resolution.threadId());

        if (!infected) {
            storeAttachments(tenantId, mailboxId, messageId, mail);
        } else {
            log.warn("Message {} failed the virus check — attachments discarded", messageId);
        }

        if (spam || infected) {
            threadRepository.markSpam(tenantId, resolution.threadId());
        } else if (resolution.created() && unanswerable(mail)) {
            // No clock. A first-response promise on mail the reply service will refuse to answer
            // is one the system already knows it cannot keep: it breaches on schedule and pages a
            // human about a DMARC report, a bounce, or a mailing-list digest. The thread still
            // exists and is still visible; it just carries no deadline.
            log.info("Thread {} opened by unanswerable mail from {} — no SLA clock started",
                    resolution.threadId(), mail.fromAddress());
        } else if (resolution.created()) {
            // The clock starts once, on the thread, at creation — never on a follow-up message,
            // or a chatty customer would keep resetting their own deadline.
            threadRepository.setSlaClock(tenantId, resolution.threadId(),
                    integerOf(mailbox.get("sla_first_response_minutes")),
                    integerOf(mailbox.get("sla_resolution_minutes")),
                    intOf(mailbox.get("sla_risk_threshold_pct"), 75));
        }

        eventRepository.markRouted(eventId, tenantId, messageId, rawKey);
        log.info("Ingested message {} into thread {} for mailbox {} (new thread: {})",
                messageId, resolution.threadId(), mailboxId, resolution.created());
    }

    /**
     * Mail nobody is waiting on a reply to. Mirrors the refusals in {@code MailboxReplyService}:
     * an unattended sender, an auto-submitted message, a bounce, or bulk/list mail.
     */
    static boolean unanswerable(NormalizedInboundMail mail) {
        if (UnattendedAddress.isUnattended(mail.fromAddress())) {
            return true;
        }
        String auto = mail.autoSubmitted();
        if (auto != null && !auto.isBlank() && !"no".equalsIgnoreCase(auto.trim())) {
            return true;
        }
        return mail.bounce() || mail.bulk();
    }

    /**
     * Copies the original into Garage, the platform's durable store.
     *
     * <p>The AWS bucket SES writes to is a spool with a short lifecycle, so this copy is the one
     * that survives. The key must begin with the tenant id or {@code /api/files/**} cannot serve
     * it — that endpoint authorizes by comparing the first key segment to the caller's scope.
     */
    private String storeRaw(String tenantId, String mailboxId, String eventId, byte[] raw) {
        if (!storageService.isEnabled()) {
            log.debug("Object storage disabled — not persisting raw MIME for mailbox {}", mailboxId);
            return null;
        }
        try {
            String key = tenantId + "/mailbox/" + mailboxId + "/raw/" + eventId + ".eml";
            storageService.uploadObject(key, raw, "message/rfc822");
            return key;
        } catch (Exception e) {
            // Not fatal: the message itself is far more valuable than the archive copy of it.
            log.warn("Failed to store raw MIME for mailbox {}: {}", mailboxId, e.getMessage());
            return null;
        }
    }

    private void storeAttachments(String tenantId, String mailboxId, String messageId,
                                  NormalizedInboundMail mail) {
        for (NormalizedInboundMail.ParsedAttachment att : mail.attachments()) {
            String checksum = sha256(att.content());

            // The type is read out of the bytes. att.contentType() is the sender's MIME header —
            // a claim, and one it costs nothing to falsify — so it is kept only as
            // declared_content_type. A disagreement between the two is worth logging: it is the
            // clearest signal available that a file is trying to be something it is not.
            String declared = att.contentType();
            String sniffed = AttachmentContentType.sniff(att.content());
            if (declared != null && !declared.equalsIgnoreCase(sniffed)) {
                log.info("Attachment '{}' on message {} declared {} but sniffed as {}",
                        att.filename(), messageId, declared, sniffed);
            }

            String key = null;
            if (storageService.isEnabled()) {
                try {
                    key = tenantId + "/mailbox/" + mailboxId + "/" + messageId + "/" + checksum;
                    // Stored under the type we are prepared to serve, not the sniffed one: an
                    // object store that hands back its own stored Content-Type must never be the
                    // thing that reintroduces text/html on a presigned URL, which bypasses the
                    // download controller and every header it sets.
                    storageService.uploadObject(key, att.content(),
                            AttachmentContentType.serveAs(sniffed));
                } catch (Exception e) {
                    log.warn("Failed to store attachment '{}' on message {}: {}",
                            att.filename(), messageId, e.getMessage());
                    key = null;
                }
            }
            attachmentRepository.insert(tenantId, messageId, mailboxId,
                    att.filename(), sniffed, declared, att.size(),
                    att.contentId(), att.inline(), key, checksum, "UNKNOWN");
        }
    }

    private String headersJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String snippet(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return null;
        }
        String s = bodyText.replaceAll("\\s+", " ").trim();
        return s.length() <= SNIPPET_LENGTH ? s : s.substring(0, SNIPPET_LENGTH - 1) + "…";
    }

    /** Content-addressed so a redelivery re-uploading the same bytes is a no-op. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String digestOf(byte[] body) {
        return sha256(body);
    }

    private static long longOf(Object v, long fallback) {
        return v instanceof Number n ? n.longValue() : fallback;
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static Integer integerOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
