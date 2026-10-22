package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.interceptor.SelfScopedController;
import io.kelta.worker.repository.MailboxAttachmentRepository;
import io.kelta.worker.repository.MailboxEscalationRepository;
import io.kelta.worker.repository.MailboxMessageRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.repository.MailboxThreadRepository;
import io.kelta.worker.service.S3StorageService;
import io.kelta.worker.service.mailbox.AttachmentContentType;
import io.kelta.worker.service.mailbox.MailboxAccessGuard;
import io.kelta.worker.service.mailbox.MailboxReplyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The support console API: what an agent sees and does.
 *
 * <p>{@code /api/support/**} is a {@code static-} gateway route, so the gateway checks only
 * blanket {@code API_ACCESS}. Every authorization decision is made here, through
 * {@link MailboxAccessGuard}, against per-mailbox membership.
 *
 * <p>There is no {@code MANAGE_SUPPORT_MAILBOX} requirement on these endpoints: an agent is not an
 * administrator. Membership is the gate, and it is checked on every single read and write — a
 * thread id from another mailbox resolves to 404 the same as one that does not exist.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/support")
public class MailboxController implements SelfScopedController {

    private static final Logger log = LoggerFactory.getLogger(MailboxController.class);
    /** Separate channel so attachment reads are auditable without trawling application logs. */
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    private static final int MAX_PAGE = 100;
    /** Generous, but bounded: an unbounded body is a cheap way to fill the message table. */
    private static final int MAX_REPLY_CHARS = 100_000;

    /** Transitions an agent may drive from the console. */
    private static final Set<String> ALLOWED_TRANSITIONS = Set.of(
            "OPEN", "WAITING_ON_CUSTOMER", "RESOLVED", "CLOSED", "ARCHIVED", "SPAM");

    private final MailboxRepository mailboxRepository;
    private final MailboxThreadRepository threadRepository;
    private final MailboxMessageRepository messageRepository;
    private final MailboxAttachmentRepository attachmentRepository;
    private final MailboxEscalationRepository escalationRepository;
    private final MailboxAccessGuard accessGuard;
    private final MailboxReplyService replyService;
    private final S3StorageService storageService;

    public MailboxController(MailboxRepository mailboxRepository,
                             MailboxThreadRepository threadRepository,
                             MailboxMessageRepository messageRepository,
                             MailboxAttachmentRepository attachmentRepository,
                             MailboxEscalationRepository escalationRepository,
                             MailboxAccessGuard accessGuard,
                             MailboxReplyService replyService,
                             S3StorageService storageService) {
        this.mailboxRepository = mailboxRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.escalationRepository = escalationRepository;
        this.accessGuard = accessGuard;
        this.replyService = replyService;
        this.storageService = storageService;
    }

    // ------------------------------------------------------------------ Mailboxes

    /**
     * The mailboxes this user is a member of.
     *
     * <p>Returns an empty list rather than an error for a non-member, so the console renders an
     * honest empty state instead of an access-denied screen for someone who simply has not been
     * added to a mailbox yet.
     */
    @GetMapping("/my-mailboxes")
    public ResponseEntity<Map<String, Object>> myMailboxes(HttpServletRequest request) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        List<String> ids = accessGuard.visibleMailboxIds(tenantId, userId);

        List<Map<String, Object>> records = ids.stream()
                .map(id -> mailboxRepository.findById(id, tenantId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(row -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", row.get("id"));
                    out.put("name", row.get("name"));
                    out.put("address", row.get("address"));
                    out.put("active", row.get("active"));
                    return out;
                })
                .toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("mailboxes", records));
    }

    // ------------------------------------------------------------------ Threads

    @GetMapping("/threads")
    public ResponseEntity<Map<String, Object>> listThreads(
            HttpServletRequest request,
            @RequestParam(required = false) String mailboxId,
            @RequestParam(defaultValue = "open") String view,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        String tenantId = requireTenant();
        String userId = requireUser(request);

        List<String> mailboxIds = resolveScope(tenantId, userId, mailboxId);
        if (mailboxIds.isEmpty()) {
            return ResponseEntity.ok(JsonApiResponseBuilder.collection("mailbox-threads", List.of()));
        }

        List<Map<String, Object>> records = threadRepository
                .listForConsole(tenantId, mailboxIds, view, userId, clamp(limit), Math.max(0, offset))
                .stream().map(this::projectThread).toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("mailbox-threads", records));
    }

    /** Counts for the console header and the shell's notification badge. */
    @GetMapping("/threads/summary")
    public ResponseEntity<Map<String, Object>> summary(HttpServletRequest request,
                                                       @RequestParam(required = false) String mailboxId) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        List<String> mailboxIds = resolveScope(tenantId, userId, mailboxId);

        Map<String, Object> counts = threadRepository.summary(tenantId, mailboxIds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", counts.getOrDefault("open", 0));
        out.put("unassigned", counts.getOrDefault("unassigned", 0));
        out.put("atRisk", counts.getOrDefault("at_risk", 0));
        out.put("breached", counts.getOrDefault("breached", 0));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/threads/{threadId}")
    public ResponseEntity<Map<String, Object>> getThread(HttpServletRequest request,
                                                         @PathVariable String threadId) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        Map<String, Object> thread = loadThread(tenantId, threadId, userId);

        List<Map<String, Object>> messages = messageRepository.listForThread(tenantId, threadId)
                .stream().map(m -> projectMessage(tenantId, m)).toList();

        Map<String, Object> attrs = new LinkedHashMap<>(projectThread(thread));
        attrs.put("messages", messages);
        attrs.put("escalations", escalationRepository.listForThread(tenantId, threadId).stream()
                .map(this::projectEscalation).toList());

        // Opening a thread is what "read" means; there is no separate mark-read call to forget.
        threadRepository.markRead(tenantId, threadId, userId);

        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("mailbox-threads", threadId, attrs));
    }

    /** Takes ownership of a thread. */
    @PostMapping("/threads/{threadId}/claim")
    public ResponseEntity<Map<String, Object>> claim(HttpServletRequest request,
                                                     @PathVariable String threadId) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        Map<String, Object> thread = loadThread(tenantId, threadId, userId);
        accessGuard.requireAct(accessGuard.requireForThread(tenantId, thread, userId));

        threadRepository.assign(tenantId, threadId, userId, userId);
        return ResponseEntity.ok(reload(tenantId, threadId));
    }

    /**
     * Assigns to someone else, or unassigns.
     *
     * <p>Requires {@code MANAGER}: reassigning another agent's work is a supervisory act, and an
     * agent quietly moving a breaching thread onto a colleague is exactly the move this prevents.
     */
    @PostMapping("/threads/{threadId}/assign")
    public ResponseEntity<Map<String, Object>> assign(HttpServletRequest request,
                                                      @PathVariable String threadId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        Map<String, Object> thread = loadThread(tenantId, threadId, userId);
        accessGuard.requireManage(accessGuard.requireForThread(tenantId, thread, userId));

        String assignee = body == null ? null : text(body.get("assignedTo"));
        if (assignee != null && accessGuard.visibleMailboxIds(tenantId, assignee)
                .stream().noneMatch(id -> id.equals(thread.get("mailbox_id")))) {
            // Assigning to someone with no access would park the thread where its owner cannot
            // see it — a silent way to lose a conversation.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That user is not a member of this mailbox");
        }

        threadRepository.assign(tenantId, threadId, assignee, userId);
        return ResponseEntity.ok(reload(tenantId, threadId));
    }

    /** Moves a thread's state, settling or pausing the SLA clock as a side effect. */
    @PostMapping("/threads/{threadId}/status")
    public ResponseEntity<Map<String, Object>> setStatus(HttpServletRequest request,
                                                         @PathVariable String threadId,
                                                         @RequestBody Map<String, Object> body) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        Map<String, Object> thread = loadThread(tenantId, threadId, userId);
        accessGuard.requireAct(accessGuard.requireForThread(tenantId, thread, userId));

        String status = upper(body.get("status"));
        if (!ALLOWED_TRANSITIONS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be one of " + ALLOWED_TRANSITIONS);
        }

        threadRepository.transition(tenantId, threadId, status, userId);
        log.info("Thread {} moved to {} by {}", threadId, status, userId);
        return ResponseEntity.ok(reload(tenantId, threadId));
    }

    /**
     * Sends a reply on a thread.
     *
     * <p>The body carries {@code bodyText} and nothing else. There is deliberately <b>no recipient
     * parameter</b>: the address comes from the thread, so this endpoint cannot be used to send
     * mail from our domain to somewhere of the caller's choosing. That is a design constraint
     * rather than a validation, which is why there is no field to validate.
     */
    @PostMapping("/threads/{threadId}/reply")
    public ResponseEntity<Map<String, Object>> reply(HttpServletRequest request,
                                                     @PathVariable String threadId,
                                                     @RequestBody Map<String, Object> body) {
        String tenantId = requireTenant();
        String userId = requireUser(request);
        Map<String, Object> thread = loadThread(tenantId, threadId, userId);
        accessGuard.requireAct(accessGuard.requireForThread(tenantId, thread, userId));

        String bodyText = text(body.get("bodyText"));
        if (bodyText == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bodyText is required");
        }
        if (bodyText.length() > MAX_REPLY_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bodyText exceeds " + MAX_REPLY_CHARS + " characters");
        }

        MailboxReplyService.Result result =
                replyService.reply(tenantId, threadId, bodyText, userId, false);

        if (!result.sent()) {
            // 409 rather than 500: nothing went wrong, we declined. The reason is named so the
            // console can explain it instead of showing a generic failure.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    refusalMessage(result.refusal()));
        }
        return ResponseEntity.ok(reload(tenantId, threadId));
    }

    /**
     * Downloads one attachment.
     *
     * <p>Deliberately not routed through {@code /api/files/**}. {@code FileController} serves a
     * set of types {@code inline}, sets no {@code X-Content-Type-Options}, and authorizes by
     * comparing the key's first segment to the Cerbos scope — which is a tenant check, not a
     * mailbox-membership check. Any agent in the tenant could read any other mailbox's
     * attachments through it, and an HTML attachment would render in the app's own origin.
     *
     * <p>Every response here is a download:
     *
     * <ul>
     *   <li>{@code Content-Type} comes from {@link AttachmentContentType#serveAs} over the sniffed
     *       type — never the sender's header, and never an active type.</li>
     *   <li>{@code Content-Disposition: attachment} unconditionally. There is no inline case, so
     *       there is no list of types someone can widen later.</li>
     *   <li>{@code X-Content-Type-Options: nosniff}, so a browser cannot rescue the markup we just
     *       declined to name.</li>
     *   <li>{@code Content-Security-Policy: default-src 'none'; sandbox}, which neuters the
     *       document even if it is somehow rendered anyway.</li>
     * </ul>
     *
     * <p>The filename is echoed in {@code Content-Disposition} using RFC 5987 encoding rather than
     * interpolated raw: it is sender-chosen, and a quote or newline in it would otherwise let the
     * sender write headers of their own.
     */
    @GetMapping("/messages/{messageId}/attachments/{attachmentId}")
    public void downloadAttachment(HttpServletRequest request,
                                   HttpServletResponse response,
                                   @PathVariable String messageId,
                                   @PathVariable String attachmentId) throws IOException {
        String tenantId = requireTenant();
        String userId = requireUser(request);

        // Scoped to the message, so an attachment id alone is not a handle to someone else's file.
        Map<String, Object> attachment = attachmentRepository
                .findForDownload(tenantId, messageId, attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        // Membership is checked against the attachment's OWN mailbox. Without this, belonging to
        // any one mailbox would grant every attachment in the tenant.
        accessGuard.require(tenantId, (String) attachment.get("mailbox_id"), userId);

        if ("INFECTED".equals(attachment.get("scan_status"))) {
            // 403 rather than 404: the row exists and the agent may see it listed. Telling them why
            // it will not download is the whole point of having recorded a verdict.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This attachment failed a malware scan and cannot be downloaded");
        }

        String storageKey = (String) attachment.get("storage_key");
        if (storageKey == null || storageKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The attachment's content was not stored");
        }

        String filename = (String) attachment.get("filename");
        String served = AttachmentContentType.serveAs((String) attachment.get("content_type"));

        try (S3StorageService.StorageObject obj = storageService.streamObject(storageKey)) {
            response.setStatus(HttpStatus.OK.value());
            // The stored object's own content type is ignored: this response describes the bytes
            // on our terms, and a stored type is one more thing an attacker could have influenced.
            response.setContentType(served);
            if (obj.contentLength() > 0) {
                response.setContentLengthLong(obj.contentLength());
            }
            response.setHeader("Content-Disposition", contentDisposition(filename));
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "private, no-store");

            try (OutputStream out = response.getOutputStream()) {
                obj.content().transferTo(out);
            }
        }

        securityLog.info("security_event=MAILBOX_ATTACHMENT_SERVED user={} mailbox={} message={} "
                        + "attachment={} servedType={}",
                userId, attachment.get("mailbox_id"), messageId, attachmentId, served);
    }

    /**
     * Builds a {@code Content-Disposition} value that a sender-chosen filename cannot break out of.
     *
     * <p>Both forms are emitted, per RFC 6266: a conservative ASCII {@code filename} for old
     * clients, and {@code filename*} with RFC 5987 percent-encoding carrying the real name. The
     * ASCII form keeps only characters that cannot terminate the header or the quoted string.
     */
    static String contentDisposition(String filename) {
        String name = filename == null || filename.isBlank() ? "attachment" : filename;

        StringBuilder ascii = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            ascii.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' ? c : '_');
        }
        String fallback = ascii.toString().strip();
        if (fallback.isEmpty()) {
            fallback = "attachment";
        }

        StringBuilder encoded = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            boolean unreserved = (v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z')
                    || (v >= '0' && v <= '9') || v == '-' || v == '.' || v == '_' || v == '~';
            if (unreserved) {
                encoded.append((char) v);
            } else {
                encoded.append('%').append(String.format("%02X", v));
            }
        }

        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String refusalMessage(MailboxReplyService.Refusal refusal) {
        return switch (refusal) {
            case SUPPRESSED -> "That address has bounced or reported spam, so we no longer email it";
            case BOUNCE_OR_AUTOMATED -> "The last message on this thread was automated — replying "
                    + "would start a mail loop";
            case UNATTENDED_ADDRESS -> "The sender's address is unattended and cannot receive replies";
            case NO_RECIPIENT -> "This thread has no reply address";
            case EMAIL_DISABLED -> "Email delivery is disabled in this environment";
        };
    }

    // ------------------------------------------------------------------ Helpers

    /** The mailboxes a request applies to: one named mailbox, or every one the user can see. */
    private List<String> resolveScope(String tenantId, String userId, String mailboxId) {
        if (mailboxId != null && !mailboxId.isBlank()) {
            accessGuard.require(tenantId, mailboxId, userId);
            return List.of(mailboxId);
        }
        return accessGuard.visibleMailboxIds(tenantId, userId);
    }

    private Map<String, Object> loadThread(String tenantId, String threadId, String userId) {
        Map<String, Object> thread = threadRepository.findById(threadId, tenantId)
                .orElseThrow(MailboxAccessGuard::notFound);
        // Membership on the OWNING mailbox, not merely on some mailbox: without this a member of
        // any mailbox could read every thread in the tenant by id.
        accessGuard.requireForThread(tenantId, thread, userId);
        return thread;
    }

    private Map<String, Object> reload(String tenantId, String threadId) {
        Map<String, Object> row = threadRepository.findById(threadId, tenantId)
                .orElseThrow(MailboxAccessGuard::notFound);
        return JsonApiResponseBuilder.single("mailbox-threads", threadId, projectThread(row));
    }

    private Map<String, Object> projectThread(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("mailboxId", row.get("mailbox_id"));
        out.put("subject", row.get("subject"));
        out.put("status", row.get("status"));
        out.put("priority", row.get("priority"));
        out.put("assignedTo", row.get("assigned_to"));
        out.put("requesterEmail", row.get("requester_email"));
        out.put("requesterName", row.get("requester_name"));
        out.put("requesterVerified", row.get("requester_verified"));
        out.put("category", row.get("category"));
        out.put("messageCount", row.get("message_count"));
        out.put("lastMessageAt", row.get("last_message_at"));
        out.put("firstResponseAt", row.get("first_response_at"));
        out.put("slaFirstResponseDueAt", row.get("sla_first_response_due_at"));
        out.put("slaFirstResponseState", row.get("sla_first_response_state"));
        out.put("slaResolutionDueAt", row.get("sla_resolution_due_at"));
        out.put("slaResolutionState", row.get("sla_resolution_state"));
        out.put("resolvedAt", row.get("resolved_at"));
        out.put("closedAt", row.get("closed_at"));
        if (row.containsKey("last_read_at")) {
            out.put("lastReadAt", row.get("last_read_at"));
        }
        return out;
    }

    /**
     * Shapes a message for the console.
     *
     * <p>Serves {@code body_html_sanitized} and never {@code body_html}. The raw form is kept as
     * evidence and must not leave the database: the client renders whatever it is given inside a
     * sandboxed frame, but the server is not entitled to rely on that.
     */
    private Map<String, Object> projectMessage(String tenantId, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        String messageId = (String) row.get("id");
        out.put("id", messageId);
        out.put("direction", row.get("direction"));
        out.put("kind", row.get("kind"));
        out.put("fromAddress", row.get("from_address"));
        out.put("fromName", row.get("from_name"));
        out.put("toAddresses", row.get("to_addresses"));
        out.put("subject", row.get("subject"));
        out.put("bodyText", row.get("body_text"));
        out.put("bodyHtmlSanitized", row.get("body_html_sanitized"));
        out.put("snippet", row.get("snippet"));
        // Surfaced so the console can show a sender as unverified. DMARC proves the domain, not
        // the person, so the UI must not present a pass as an identity.
        out.put("spfResult", row.get("spf_result"));
        out.put("dkimResult", row.get("dkim_result"));
        out.put("dmarcResult", row.get("dmarc_result"));
        out.put("spamVerdict", row.get("spam_verdict"));
        out.put("isBulk", row.get("is_bulk"));
        out.put("isBounce", row.get("is_bounce"));
        out.put("autoSubmitted", row.get("auto_submitted"));
        out.put("deliveryStatus", row.get("delivery_status"));
        out.put("sentAt", row.get("sent_at"));
        out.put("receivedAt", row.get("received_at"));
        out.put("attachments", attachmentRepository.listForMessage(tenantId, messageId).stream()
                .map(a -> {
                    Map<String, Object> att = new LinkedHashMap<>();
                    att.put("id", a.get("id"));
                    att.put("filename", a.get("filename"));
                    // Ours, from the bytes.
                    att.put("contentType", a.get("content_type"));
                    // The sender's claim. Surfaced so the console can show the two disagreeing —
                    // a .png that sniffs as text/html is the thing an agent most needs to see
                    // before deciding whether to open it.
                    att.put("declaredContentType", a.get("declared_content_type"));
                    att.put("sizeBytes", a.get("size_bytes"));
                    att.put("inline", a.get("inline"));
                    att.put("contentId", a.get("content_id"));
                    att.put("scanStatus", a.get("scan_status"));
                    att.put("downloadable", a.get("storage_key") != null
                            && !"INFECTED".equals(a.get("scan_status")));
                    return att;
                }).toList());
        return out;
    }

    private Map<String, Object> projectEscalation(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("clock", row.get("clock"));
        out.put("level", row.get("level"));
        out.put("slaDueAt", row.get("sla_due_at"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    /**
     * The acting user, taken only from the gateway-stamped header.
     *
     * <p>Never from the body or a query parameter: those are caller-supplied, and this value is
     * the entire basis for membership checks below.
     */
    private String requireUser(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        // The gateway stamps this header with the user's EMAIL, not their id. Every store and
        // comparison below is in terms of platform_user.id, so it is resolved once here rather
        // than leaking two different notions of identity through the rest of the controller.
        String userId = accessGuard.resolveUserId(requireTenant(), header);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        return userId;
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String upper(Object o) {
        String s = text(o);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }
}
