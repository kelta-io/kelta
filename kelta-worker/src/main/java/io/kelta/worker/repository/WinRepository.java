package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code win} table (consumer-alerting slice 9). Writes go through {@code QueryEngine}
 * in {@code WinController} so the platform hooks fire (the {@code wins} ownership metadata, enforced by
 * {@code OwnerScopeGuardHook}); this
 * repository is read-only plus the claimant-name lookup used at create time.
 *
 * <p>Runs under the request tenant context, so Postgres RLS scopes every row to the tenant; the
 * explicit {@code tenant_id} filter is defence-in-depth. Hand-written SQL on {@link JdbcTemplate}
 * — no JPA (the {@link FieldHistoryRepository} idiom).
 */
@Repository
public class WinRepository {

    /** Cap on rows returned by the public ticker feed. */
    public static final int MAX_RECENT = 50;

    private final JdbcTemplate jdbc;

    public WinRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Win(
            String id,
            String memberId,
            String targetId,
            String watchId,
            String alertId,
            String category,
            String summary,
            Integer quantity,
            boolean isPublic,
            String claimantName,
            Instant claimedAt) {
    }

    /** Aggregate success stats for one target. */
    public record TargetStats(String targetId, long winCount, Instant lastWinAt) {
    }

    private static final RowMapper<Win> WIN_MAPPER = (rs, i) -> new Win(
            rs.getString("id"),
            rs.getString("member_id"),
            rs.getString("target_id"),
            rs.getString("watch_id"),
            rs.getString("alert_id"),
            rs.getString("category"),
            rs.getString("summary"),
            (Integer) rs.getObject("quantity"),
            rs.getBoolean("is_public"),
            rs.getString("claimant_name"),
            toInstant(rs.getTimestamp("claimed_at")));

    private static final String SELECT_COLS = """
            id, member_id, target_id, watch_id, alert_id, category, summary, quantity,
            is_public, claimant_name, claimed_at
            """;

    /** The caller's own wins, newest first. */
    public List<Win> findByMember(String tenantId, String memberId) {
        return jdbc.query(
                "SELECT " + SELECT_COLS + " FROM win WHERE tenant_id = ? AND member_id = ?"
                        + " ORDER BY claimed_at DESC",
                WIN_MAPPER, tenantId, memberId);
    }

    /** Recent public wins for the live ticker, newest first, capped at {@code limit}. */
    public List<Win> findRecentPublic(String tenantId, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RECENT));
        return jdbc.query(
                "SELECT " + SELECT_COLS + " FROM win WHERE tenant_id = ? AND is_public = true"
                        + " ORDER BY claimed_at DESC LIMIT ?",
                WIN_MAPPER, tenantId, capped);
    }

    /** Win count and most-recent win time for one target (all wins, public or not). */
    public TargetStats statsForTarget(String tenantId, String targetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS n, MAX(claimed_at) AS last_at"
                        + " FROM win WHERE tenant_id = ? AND target_id = ?",
                (rs, i) -> new TargetStats(targetId, rs.getLong("n"),
                        toInstant(rs.getTimestamp("last_at"))),
                tenantId, targetId);
    }

    /**
     * The member's first name (falling back to username) for the public claimant label set
     * server-side at create time. Empty when the user has neither.
     */
    public Optional<String> findMemberDisplayFirstName(String tenantId, String memberId) {
        List<String> names = jdbc.query(
                "SELECT COALESCE(NULLIF(first_name, ''), username) AS label"
                        + " FROM platform_user WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("label"), tenantId, memberId);
        return names.isEmpty() ? Optional.empty() : Optional.ofNullable(names.get(0));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
