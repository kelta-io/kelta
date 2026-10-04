package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The alert dedupe ledger.
 *
 * <p>Two independent guards against spamming a member, both enforced by the
 * database rather than by matcher logic:
 * <ul>
 *   <li><b>Episode dedupe</b> — {@code UNIQUE (tenant_id, watch_id, slot_key,
 *       episode_id)}. One alert per member per slot per opening. A racing second
 *       pod loses the insert instead of sending a duplicate.</li>
 *   <li><b>Suppression window</b> — even across genuinely distinct episodes, a
 *       slot that flaps open/closed repeatedly would otherwise alert every time.
 *       {@link #alertedRecently} bounds that.</li>
 * </ul>
 */
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A won claim: the new alert id and its database-assigned {@code created_at}, which is the
     * detection time delivery latency is measured from.
     */
    public record Claim(String alertId, Instant createdAt) {
    }

    /**
     * Claims the right to alert. Returns the new alert, or empty when this
     * (watch, slot, episode) was already alerted — the insert IS the claim, so
     * two pods racing on the same event produce exactly one notification.
     */
    public Optional<Claim> claim(String tenantId, String watchId, String targetId,
                                 String slotKey, String episodeId,
                                 Instant windowStart, Instant windowEnd) {
        String id = UUID.randomUUID().toString();
        List<Instant> createdAt = jdbc.query("""
                        INSERT INTO alert
                            (id, tenant_id, watch_id, target_id, slot_key, episode_id,
                             window_start, window_end, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                        ON CONFLICT (tenant_id, watch_id, slot_key, episode_id) DO NOTHING
                        RETURNING created_at
                        """,
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                id, tenantId, watchId, targetId, slotKey, episodeId,
                windowStart == null ? null : Timestamp.from(windowStart),
                windowEnd == null ? null : Timestamp.from(windowEnd));
        return createdAt.isEmpty() ? Optional.empty() : Optional.of(new Claim(id, createdAt.get(0)));
    }

    /**
     * True when this watch was already alerted about this slot inside the
     * suppression window — the guard against a flapping slot notifying a member
     * repeatedly with technically-distinct episodes.
     */
    public boolean alertedRecently(String tenantId, String watchId, String slotKey,
                                   Duration window, Instant now) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM alert
                         WHERE tenant_id = ? AND watch_id = ? AND slot_key = ?
                           AND created_at > ?
                        """,
                Integer.class, tenantId, watchId, slotKey,
                Timestamp.from(now.minus(window)));
        return count != null && count > 0;
    }

    /** Alert history for a watch, newest first — backs the slice-5 endpoint. */
    public int countForWatch(String tenantId, String watchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE tenant_id = ? AND watch_id = ?",
                Integer.class, tenantId, watchId);
        return count == null ? 0 : count;
    }
}
