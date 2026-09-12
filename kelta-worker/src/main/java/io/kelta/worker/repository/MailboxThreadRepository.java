package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes {@code mailbox_thread}.
 *
 * @since 1.0.0
 */
@Repository
public class MailboxThreadRepository {

    /** Statuses a late reply must not be threaded onto. */
    private static final String CLOSED_STATUSES = "('RESOLVED','CLOSED','SPAM','ARCHIVED')";

    private final JdbcTemplate jdbcTemplate;

    public MailboxThreadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Map<String, Object>> findById(String id, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM mailbox_thread WHERE id = ? AND tenant_id = ?", id, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Finds an open thread by normalized subject and requester, within the mailbox's configured
     * window.
     *
     * <p>The {@code requester_email} match is not an optimisation — it is a safety constraint.
     * Threading on subject alone would splice two customers' correspondence into one thread the
     * first time both write "Re: Booking question", showing each of them the other's history.
     * That is a disclosure bug dressed as a convenience heuristic.
     */
    public Optional<String> findBySubject(String tenantId, String mailboxId,
                                          String normalizedSubject, String requesterEmail, int days) {
        if (days <= 0 || normalizedSubject == null || normalizedSubject.isBlank()) {
            return Optional.empty();
        }
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT id FROM mailbox_thread
                 WHERE tenant_id = ?
                   AND mailbox_id = ?
                   AND normalized_subject = ?
                   AND lower(requester_email) = lower(?)
                   AND status NOT IN """ + CLOSED_STATUSES + """
                   AND last_message_at >= NOW() - make_interval(days => ?)
                 ORDER BY last_message_at DESC
                 LIMIT 1
                """, String.class, tenantId, mailboxId, normalizedSubject, requesterEmail, days);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    /** True when the thread is in a state a new message must not join. */
    public boolean isClosed(String tenantId, String threadId) {
        List<String> status = jdbcTemplate.queryForList(
                "SELECT status FROM mailbox_thread WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, threadId);
        return status.isEmpty()
                || List.of("RESOLVED", "CLOSED", "SPAM", "ARCHIVED").contains(status.getFirst());
    }

    public String create(String tenantId, String mailboxId, String subject, String normalizedSubject,
                         String requesterEmail, String requesterName, boolean requesterVerified,
                         String verificationMethod, String status, String parentThreadId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO mailbox_thread
                    (id, tenant_id, mailbox_id, subject, normalized_subject, status,
                     requester_email, requester_name, requester_verified, verification_method,
                     parent_thread_id, message_count, last_message_at, last_inbound_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW(), NOW(), NOW())
                """, id, tenantId, mailboxId, subject, normalizedSubject, status,
                requesterEmail, requesterName, requesterVerified, verificationMethod, parentThreadId);
        return id;
    }

    /** Bumps activity counters after an inbound message is stored. */
    public void recordInbound(String tenantId, String threadId) {
        jdbcTemplate.update("""
                UPDATE mailbox_thread
                   SET message_count   = message_count + 1,
                       last_message_at = NOW(),
                       last_inbound_at = NOW(),
                       updated_at      = NOW()
                 WHERE id = ? AND tenant_id = ?
                """, threadId, tenantId);
    }

    /**
     * Sets the SLA clock, once, at thread creation.
     *
     * <p>Absolute instants rather than a policy reference: the promise is made at a point in time
     * and must not move when an admin later edits the mailbox's policy. Deriving it at read would
     * retroactively breach or un-breach every open thread and rewrite history in every SLA report
     * already run.
     */
    public void setSlaClock(String tenantId, String threadId,
                            Integer firstResponseMinutes, Integer resolutionMinutes,
                            int riskThresholdPct) {
        if (firstResponseMinutes == null && resolutionMinutes == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE mailbox_thread
                   SET sla_first_response_due_at =
                           CASE WHEN ?::int IS NULL THEN NULL
                                ELSE NOW() + make_interval(mins => ?::int) END,
                       sla_first_response_state =
                           CASE WHEN ?::int IS NULL THEN 'NONE' ELSE 'PENDING' END,
                       sla_resolution_due_at =
                           CASE WHEN ?::int IS NULL THEN NULL
                                ELSE NOW() + make_interval(mins => ?::int) END,
                       sla_resolution_state =
                           CASE WHEN ?::int IS NULL THEN 'NONE' ELSE 'PENDING' END,
                       updated_at = NOW()
                 WHERE id = ? AND tenant_id = ?
                """,
                firstResponseMinutes, firstResponseMinutes, firstResponseMinutes,
                resolutionMinutes, resolutionMinutes, resolutionMinutes,
                threadId, tenantId);
    }

    /** Marks a thread as spam and stops any SLA clock on it. */
    public void markSpam(String tenantId, String threadId) {
        // The clock is cleared as well as the status set. Leaving it running would have the
        // escalation sweep page a human at 3am about a spam message.
        jdbcTemplate.update("""
                UPDATE mailbox_thread
                   SET status = 'SPAM',
                       sla_first_response_state = 'NONE', sla_first_response_due_at = NULL,
                       sla_resolution_state = 'NONE',     sla_resolution_due_at = NULL,
                       updated_at = NOW()
                 WHERE id = ? AND tenant_id = ?
                """, threadId, tenantId);
    }

    /**
     * Lists threads for the console.
     *
     * <p>{@code view} filters the same list rather than switching data sources, which is what
     * makes the tabs cheap. Ordering is by SLA due time ascending with nulls last, so the most
     * urgent conversation is always at the top and a thread with no SLA never outranks one that
     * is about to breach.
     */
    public List<Map<String, Object>> listForConsole(String tenantId, List<String> mailboxIds,
                                                    String view, String userId, int limit, int offset) {
        if (mailboxIds == null || mailboxIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(mailboxIds);

        StringBuilder where = new StringBuilder(
                "t.tenant_id = ? AND t.mailbox_id IN (" + placeholders + ")");

        switch (view == null ? "open" : view) {
            case "mine" -> {
                where.append(" AND t.assigned_to = ? AND t.status NOT IN ")
                        .append(CLOSED_STATUSES);
                args.add(userId);
            }
            case "unassigned" -> where.append(" AND t.assigned_to IS NULL AND t.status NOT IN ")
                    .append(CLOSED_STATUSES);
            case "atRisk" -> where.append(" AND t.status NOT IN ").append(CLOSED_STATUSES)
                    .append(" AND (t.sla_first_response_state IN ('AT_RISK','BREACHED')")
                    .append(" OR t.sla_resolution_state IN ('AT_RISK','BREACHED'))");
            case "closed" -> where.append(" AND t.status IN ").append(CLOSED_STATUSES);
            case "all" -> { /* every thread in the visible mailboxes, open or not */ }
            default -> where.append(" AND t.status NOT IN ").append(CLOSED_STATUSES);
        }

        args.add(limit);
        args.add(offset);

        return jdbcTemplate.queryForList("""
                SELECT t.*,
                       (SELECT r.last_read_at FROM mailbox_thread_read r
                         WHERE r.thread_id = t.id AND r.user_id = ?) AS last_read_at
                  FROM mailbox_thread t
                 WHERE """ + " " + where + """
                 ORDER BY COALESCE(t.sla_first_response_due_at, t.sla_resolution_due_at)
                          ASC NULLS LAST,
                          t.last_message_at DESC
                 LIMIT ? OFFSET ?
                """, prepend(userId, args).toArray());
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> out = new java.util.ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /** Counts for the console header: what needs attention right now. */
    public Map<String, Object> summary(String tenantId, List<String> mailboxIds) {
        if (mailboxIds == null || mailboxIds.isEmpty()) {
            return Map.of("open", 0, "unassigned", 0, "atRisk", 0, "breached", 0);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(mailboxIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(mailboxIds);

        // Plain concatenation rather than a text block: a text block's opening delimiter must be
        // followed by a line terminator, so it cannot be interleaved with `+` on the same line.
        String sql = "SELECT"
                + "  count(*) FILTER (WHERE status NOT IN " + CLOSED_STATUSES + ") AS open,"
                + "  count(*) FILTER (WHERE status NOT IN " + CLOSED_STATUSES
                + "                     AND assigned_to IS NULL) AS unassigned,"
                // Both SLA counts exclude closed threads, or an archived breach stays in the red
                // badge forever — the count would say "11 breached" over an empty inbox.
                + "  count(*) FILTER (WHERE status NOT IN " + CLOSED_STATUSES
                + "                     AND (sla_first_response_state = 'AT_RISK'"
                + "                       OR sla_resolution_state = 'AT_RISK')) AS at_risk,"
                + "  count(*) FILTER (WHERE status NOT IN " + CLOSED_STATUSES
                + "                     AND (sla_first_response_state = 'BREACHED'"
                + "                       OR sla_resolution_state = 'BREACHED')) AS breached"
                + "  FROM mailbox_thread"
                + " WHERE tenant_id = ? AND mailbox_id IN (" + placeholders + ")";

        return jdbcTemplate.queryForMap(sql, args.toArray());
    }

    /** Assigns, or clears the assignment when {@code userId} is null. */
    public int assign(String tenantId, String threadId, String userId, String actor) {
        return jdbcTemplate.update("""
                UPDATE mailbox_thread
                   SET assigned_to = ?,
                       status = CASE WHEN ?::varchar IS NULL THEN 'OPEN' ELSE 'ASSIGNED' END,
                       updated_at = now(), updated_by = ?
                 WHERE id = ? AND tenant_id = ? AND status NOT IN """ + CLOSED_STATUSES + """
                """, userId, userId, actor, threadId, tenantId);
    }

    /**
     * Moves a thread to a terminal or waiting state.
     *
     * <p>Stamps {@code resolved_at}/{@code closed_at} and settles the SLA states in the same
     * statement. Doing it here rather than letting a generic PATCH set {@code status} is exactly
     * why the collection is read-only over the JSON:API — a bare status write would leave a row
     * that reports RESOLVED while its SLA clock still says PENDING, and every report built on it
     * would be wrong.
     *
     * <p>{@code WAITING_ON_CUSTOMER} pauses the clock instead of settling it: the time spent
     * waiting for a reply is not time we owe.
     */
    public int transition(String tenantId, String threadId, String status, String actor) {
        boolean resolved = "RESOLVED".equals(status);
        boolean closed = "CLOSED".equals(status) || "ARCHIVED".equals(status);
        boolean waiting = "WAITING_ON_CUSTOMER".equals(status);
        boolean terminal = resolved || closed;

        return jdbcTemplate.update("""
                UPDATE mailbox_thread
                   SET status      = ?,
                       resolved_at = CASE WHEN ?::boolean THEN COALESCE(resolved_at, now()) ELSE resolved_at END,
                       closed_at   = CASE WHEN ?::boolean THEN COALESCE(closed_at, now())   ELSE closed_at END,
                       sla_paused_at = CASE WHEN ?::boolean THEN COALESCE(sla_paused_at, now()) ELSE NULL END,
                       sla_resolution_state = CASE
                           WHEN ?::boolean AND sla_resolution_state = 'PENDING' THEN 'MET'
                           ELSE sla_resolution_state END,
                       sla_first_response_state = CASE
                           WHEN ?::boolean AND sla_first_response_state = 'PENDING' THEN 'MET'
                           ELSE sla_first_response_state END,
                       updated_at = now(), updated_by = ?
                 WHERE id = ? AND tenant_id = ?
                """, status, resolved, closed, waiting, terminal, terminal, actor, threadId, tenantId);
    }

    /**
     * Records the first outbound reply, which is what stops the first-response clock.
     *
     * <p>{@code COALESCE} so a second reply never moves the timestamp — the promise was about the
     * first one.
     */
    public void recordFirstResponse(String tenantId, String threadId) {
        jdbcTemplate.update("""
                UPDATE mailbox_thread
                   SET first_response_at = COALESCE(first_response_at, now()),
                       last_outbound_at  = now(),
                       last_message_at   = now(),
                       sla_first_response_state = CASE
                           WHEN sla_first_response_state = 'PENDING' THEN 'MET'
                           ELSE sla_first_response_state END,
                       updated_at = now()
                 WHERE id = ? AND tenant_id = ?
                """, threadId, tenantId);
    }

    /** Marks a thread read up to now for one user. */
    public void markRead(String tenantId, String threadId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO mailbox_thread_read
                    (id, tenant_id, thread_id, user_id, last_read_at, created_at, updated_at)
                VALUES (gen_random_uuid()::text, ?, ?, ?, now(), now(), now())
                ON CONFLICT (thread_id, user_id)
                DO UPDATE SET last_read_at = now(), updated_at = now()
                """, tenantId, threadId, userId);
    }

    /**
     * Strips reply and forward prefixes so "Re: Re: Fwd: Booking" threads with "Booking".
     *
     * <p>Covers the common non-English prefixes too — a German client sends {@code AW:} and a
     * French one {@code RE:}/{@code TR:}, and missing them means every reply starts a new thread.
     */
    public static String normalizeSubject(String subject) {
        if (subject == null) {
            return null;
        }
        String s = subject.trim();
        String previous;
        do {
            previous = s;
            s = s.replaceFirst("(?i)^\\s*(re|aw|fw|fwd|tr|antw|sv|vs|vb|res)\\s*(\\[\\d+\\])?\\s*:\\s*", "");
        } while (!s.equals(previous));
        s = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
