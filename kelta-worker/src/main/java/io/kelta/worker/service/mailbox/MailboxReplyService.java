package io.kelta.worker.service.mailbox;

import io.kelta.worker.repository.EmailSuppressionRepository;
import io.kelta.worker.repository.MailboxMessageRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.repository.MailboxThreadRepository;
import io.kelta.worker.service.email.DefaultEmailService;
import io.kelta.worker.service.email.EmailHeaders;
import io.kelta.worker.service.email.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Sends a reply on a support thread.
 *
 * <p>The recipient is <b>never</b> a parameter. It is read from the thread's
 * {@code requester_email}, recorded when the thread was created. Accepting a recipient — or taking
 * one from the latest inbound message's headers — would turn an authenticated support console into
 * a relay that sends mail from our domain to an address of the caller's choosing.
 *
 * <p>Replies are plain text in this slice. Nothing here authors HTML, so nothing here can inject
 * it; templates own HTML and arrive with the canned-answer slice.
 *
 * @since 1.0.0
 */
@Service
public class MailboxReplyService {

    private static final Logger log = LoggerFactory.getLogger(MailboxReplyService.class);

    private final MailboxRepository mailboxRepository;
    private final MailboxThreadRepository threadRepository;
    private final MailboxMessageRepository messageRepository;
    private final EmailSuppressionRepository suppressionRepository;
    private final MailboxVerpService verpService;
    private final DefaultEmailService emailService;
    private final JdbcTemplate jdbcTemplate;

    public MailboxReplyService(MailboxRepository mailboxRepository,
                               MailboxThreadRepository threadRepository,
                               MailboxMessageRepository messageRepository,
                               EmailSuppressionRepository suppressionRepository,
                               MailboxVerpService verpService,
                               ObjectProvider<DefaultEmailService> emailServiceProvider,
                               JdbcTemplate jdbcTemplate) {
        this.mailboxRepository = mailboxRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.suppressionRepository = suppressionRepository;
        this.verpService = verpService;
        // Optional: the bean is absent when kelta.email.enabled=false, which is how the test
        // harness runs. A missing sender must fail the send, not the application context.
        this.emailService = emailServiceProvider.getIfAvailable();
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Why a reply was refused, when it was. */
    public enum Refusal {
        SUPPRESSED,
        BOUNCE_OR_AUTOMATED,
        UNATTENDED_ADDRESS,
        NO_RECIPIENT,
        EMAIL_DISABLED
    }

    public record Result(String messageId, Refusal refusal) {
        public boolean sent() {
            return refusal == null;
        }
    }

    /**
     * Sends {@code bodyText} to the thread's requester.
     *
     * <p>Must be called with the tenant bound and after the caller's membership has been checked —
     * this service authorizes nothing.
     *
     * @param automated true when the reply was generated rather than typed, which sets
     *                  {@code Auto-Submitted} so the far side's autoresponder stays quiet
     */
    public Result reply(String tenantId, String threadId, String bodyText,
                        String actorUserId, boolean automated) {
        if (emailService == null) {
            return refuse(threadId, Refusal.EMAIL_DISABLED, "email delivery is disabled");
        }

        Map<String, Object> thread = threadRepository.findById(threadId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        Map<String, Object> mailbox = mailboxRepository
                .findById((String) thread.get("mailbox_id"), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        String to = (String) thread.get("requester_email");
        if (to == null || to.isBlank()) {
            return refuse(threadId, Refusal.NO_RECIPIENT, "thread has no requester address");
        }

        // Never reply to a null sender or an automated message. This is backscatter — it reaches
        // nobody, and enough of it gets the sending domain blocklisted.
        Optional<Map<String, Object>> lastInbound = lastInboundMessage(tenantId, threadId);
        if (lastInbound.isPresent()) {
            Map<String, Object> m = lastInbound.get();
            if (Boolean.TRUE.equals(m.get("is_bounce"))) {
                return refuse(threadId, Refusal.BOUNCE_OR_AUTOMATED, "last inbound message is a bounce");
            }
            String autoSubmitted = (String) m.get("auto_submitted");
            if (autoSubmitted != null && !autoSubmitted.isBlank()
                    && !"no".equalsIgnoreCase(autoSubmitted.trim())) {
                return refuse(threadId, Refusal.BOUNCE_OR_AUTOMATED,
                        "last inbound message was auto-submitted");
            }
        }

        if (isUnattended(to)) {
            return refuse(threadId, Refusal.UNATTENDED_ADDRESS, "recipient is an unattended address");
        }

        // Checked explicitly, because DefaultEmailService.queueEmail does NOT consult the
        // suppression list — only CampaignRunnerService does. Replying to an address SES has
        // already reported as a hard bounce or a complaint burns the sending domain's reputation
        // for a message nobody will read.
        if (suppressionRepository.isSuppressed(tenantId, to)) {
            return refuse(threadId, Refusal.SUPPRESSED, "recipient is on the suppression list");
        }

        String subject = replySubject((String) thread.get("subject"));
        EmailHeaders headers = buildHeaders(tenantId, threadId, mailbox, lastInbound, automated);

        // The mailbox replies as itself. Falling back to the mailbox address keeps a mailbox with
        // no explicit reply_from_address sending as something the customer recognises, rather than
        // as the tenant's noreply@ identity.
        String fromAddress = firstNonBlank((String) mailbox.get("reply_from_address"),
                (String) mailbox.get("address"));
        String fromName = firstNonBlank((String) mailbox.get("reply_from_name"),
                (String) mailbox.get("name"));

        SendResult sendResult;
        try {
            sendResult = emailService.queueReply(tenantId, to, subject, bodyText,
                    "support-mailbox", threadId, fromAddress, fromName, List.of(), headers).join();
        } catch (Exception e) {
            log.error("Reply on thread {} could not be sent: {}", threadId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not send the reply");
        }

        String messageId = recordOutbound(tenantId, thread, mailbox, to, subject, bodyText,
                headers, sendResult, actorUserId);

        if (!sendResult.delivered()) {
            // The row is kept — an agent needs to see the attempt and that it failed — but the
            // clock keeps running. Stopping it here would mark the SLA met on the strength of a
            // reply the customer never received, which is the one outcome the clock exists to
            // prevent, and would suppress the escalation that should now fire.
            log.error("Reply on thread {} was recorded but not delivered", threadId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not send the reply");
        }

        // Stops the first-response clock. COALESCE inside means a second reply never moves it.
        threadRepository.recordFirstResponse(tenantId, threadId);

        log.info("Replied on thread {} to {} (automated={})", threadId, to, automated);
        return new Result(messageId, null);
    }

    /**
     * Builds the outbound headers.
     *
     * <p>{@code In-Reply-To} and {@code References} are copied from the message being answered, so
     * the reply lands in the customer's existing conversation rather than starting a new one.
     * Those values are attacker-controlled, which is exactly why {@link EmailHeaders} validates
     * them for CR/LF before they can reach the wire.
     */
    private EmailHeaders buildHeaders(String tenantId, String threadId, Map<String, Object> mailbox,
                                      Optional<Map<String, Object>> lastInbound, boolean automated) {
        String inReplyTo = lastInbound.map(m -> (String) m.get("message_id")).orElse(null);
        String references = buildReferences(
                lastInbound.map(m -> (String) m.get("references_header")).orElse(null), inReplyTo);

        String replyTo = verpService.replyToAddress(
                        localPart((String) mailbox.get("address")),
                        (String) mailbox.get("verp_domain"),
                        threadId)
                // No VERP domain configured: fall back to the mailbox's own address so replies
                // still arrive, they just thread by References alone.
                .orElse((String) mailbox.get("address"));

        try {
            return new EmailHeaders(
                    replyTo,
                    inReplyTo,
                    references,
                    automated ? "auto-replied" : null,
                    null,
                    null,
                    Map.of());
        } catch (IllegalArgumentException e) {
            // A header value from the inbound message failed validation — almost certainly an
            // injection attempt. Send without the threading hints rather than dropping the reply:
            // the customer still gets their answer, it just may not thread.
            log.warn("Dropping threading headers on thread {}: {}", threadId, e.getMessage());
            return new EmailHeaders(null, null, null, automated ? "auto-replied" : null,
                    null, null, Map.of());
        }
    }

    /**
     * Appends the answered message to its own References chain, per RFC 5322 §3.6.4.
     *
     * <p>Trimmed to the last 20 ids: a long-running thread would otherwise grow an unbounded
     * header, and some MTAs reject a message whose headers are too large.
     */
    private String buildReferences(String existing, String inReplyTo) {
        StringBuilder chain = new StringBuilder();
        if (existing != null && !existing.isBlank()) {
            chain.append(existing.trim());
        }
        if (inReplyTo != null && !inReplyTo.isBlank()
                && (existing == null || !existing.contains(inReplyTo))) {
            if (!chain.isEmpty()) {
                chain.append(' ');
            }
            chain.append(inReplyTo.trim());
        }
        if (chain.isEmpty()) {
            return null;
        }
        String[] ids = chain.toString().split("\\s+");
        if (ids.length <= 20) {
            return chain.toString();
        }
        return String.join(" ", java.util.Arrays.copyOfRange(ids, ids.length - 20, ids.length));
    }

    /** Records what we sent, including the Message-ID so the customer's reply can be threaded. */
    private String recordOutbound(String tenantId, Map<String, Object> thread,
                                  Map<String, Object> mailbox, String to, String subject,
                                  String bodyText, EmailHeaders headers, SendResult sendResult,
                                  String actorUserId) {
        String messageId = messageRepository.insertInbound(new MailboxMessageRepository.InboundInsert(
                tenantId, (String) thread.get("id"), (String) mailbox.get("id"),
                sendResult == null ? null : sendResult.messageId(),
                headers.inReplyTo(), headers.references(),
                (String) mailbox.get("reply_from_address"), (String) mailbox.get("reply_from_name"),
                to, null, headers.replyTo(), subject,
                bodyText, null, null, snippet(bodyText), "{}",
                null, null, null, null, null, null,
                headers.autoSubmitted(), null, false, false,
                null, (long) bodyText.length(), java.time.Instant.now()));

        // insertInbound writes direction='INBOUND'; correct it and stamp the author. Reusing the
        // insert keeps one column list rather than two that can drift apart.
        // QUEUED would be a lie by the time this runs: queueReply has already been joined, so the
        // send has either happened or failed. Left at QUEUED, every reply ever sent shows as
        // permanently pending in the console and a genuinely failed one is indistinguishable from
        // a delivered one.
        jdbcTemplate.update("""
                UPDATE mailbox_message
                   SET direction = 'OUTBOUND', author_user_id = ?, delivery_status = ?,
                       sent_at = now(), updated_at = now()
                 WHERE id = ? AND tenant_id = ?
                """, actorUserId, sendResult != null && sendResult.delivered() ? "SENT" : "FAILED",
                messageId, tenantId);

        return messageId;
    }

    /** First non-blank of the two, or null. Used to fall back from a configured value to a default. */
    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    private Optional<Map<String, Object>> lastInboundMessage(String tenantId, String threadId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT message_id, references_header, auto_submitted, is_bounce, is_bulk
                  FROM mailbox_message
                 WHERE tenant_id = ? AND thread_id = ? AND direction = 'INBOUND'
                 ORDER BY received_at DESC
                 LIMIT 1
                """, tenantId, threadId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private Result refuse(String threadId, Refusal refusal, String why) {
        log.warn("Refusing to reply on thread {}: {}", threadId, why);
        return new Result(null, refusal);
    }

    /** Shared with ingest, which declines to start an SLA clock for the same addresses. */
    static boolean isUnattended(String address) {
        return UnattendedAddress.isUnattended(address);
    }

    static String localPart(String address) {
        return UnattendedAddress.localPart(address);
    }

    /** Adds one "Re: " and never a second — mail clients already stack them badly enough. */
    static String replySubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "Re: (no subject)";
        }
        String trimmed = subject.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("re:") ? trimmed : "Re: " + trimmed;
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 299) + "…";
    }
}
