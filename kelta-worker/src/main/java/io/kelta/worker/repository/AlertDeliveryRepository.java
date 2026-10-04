package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-channel delivery outcome for an alert.
 *
 * <p>Rows are created PENDING before the send is attempted, so a crash mid-send
 * leaves evidence that a delivery was owed rather than losing it silently. Rows
 * carry no {@code tenant_id}: they are reachable only through their alert, which
 * is tenant-scoped and RLS'd, and the FK cascade ties their lifetimes together —
 * a denormalized copy would be a second source of truth that could drift.
 */
@Repository
public class AlertDeliveryRepository {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbc;

    public AlertDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records an owed delivery per channel, returning the new row ids in order. */
    public List<String> createPending(String alertId, List<String> channels) {
        return channels.stream().map(channel -> {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                            INSERT INTO alert_delivery (id, alert_id, channel, status, created_at)
                            VALUES (?, ?, ?, 'PENDING', NOW())
                            """,
                    id, alertId, channel);
            return id;
        }).toList();
    }

    /** Marks a delivery sent. */
    public void markSent(String deliveryId, Instant sentAt) {
        jdbc.update("UPDATE alert_delivery SET status = 'SENT', sent_at = ? WHERE id = ?",
                Timestamp.from(sentAt == null ? Instant.now() : sentAt), deliveryId);
    }

    /**
     * Marks a delivery failed. The error is truncated defensively — a provider
     * stack trace should not be able to bloat the row.
     */
    public void markFailed(String deliveryId, String error) {
        String trimmed = error == null ? null
                : error.length() > 2000 ? error.substring(0, 2000) : error;
        jdbc.update("UPDATE alert_delivery SET status = 'FAILED', error = ? WHERE id = ?",
                trimmed, deliveryId);
    }

    /** One SENT delivery joined to its alert, as read for the latency summary. */
    public record DeliveryLatencyRow(String alertId, String channel, String status,
                                     Instant alertCreatedAt, Instant sentAt) {
    }

    /**
     * SENT deliveries of the tenant's alerts created at or after {@code since}, optionally on one
     * channel. Read-only; backs {@code GET /api/alerts/latency}.
     *
     * <p>{@code alert_delivery} has no {@code tenant_id} — its only RLS is the parent-alert
     * policy — so the tenant is scoped here by an explicit {@code a.tenant_id = ?} on the joined
     * alert rather than trusting RLS alone. Callers run under the request's {@code TenantContext}.
     */
    public List<DeliveryLatencyRow> findSentSince(String tenantId, Instant since, String channel) {
        String sql = """
                SELECT d.alert_id, d.channel, d.status, a.created_at AS alert_created_at, d.sent_at
                  FROM alert_delivery d
                  JOIN alert a ON a.id = d.alert_id
                 WHERE a.tenant_id = ?
                   AND a.created_at >= ?
                   AND d.status = 'SENT'
                   AND d.sent_at IS NOT NULL
                """;
        List<Object> args = new ArrayList<>(List.of(tenantId, Timestamp.from(since)));
        if (channel != null) {
            sql += "   AND d.channel = ?\n";
            args.add(channel);
        }
        return jdbc.query(sql, (rs, rowNum) -> new DeliveryLatencyRow(
                        rs.getString("alert_id"),
                        rs.getString("channel"),
                        rs.getString("status"),
                        rs.getTimestamp("alert_created_at").toInstant(),
                        rs.getTimestamp("sent_at").toInstant()),
                args.toArray());
    }

    /** Delivery rows for an alert (diagnostics + the slice-5 alert history view). */
    public int countByStatus(String alertId, String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_delivery WHERE alert_id = ? AND status = ?",
                Integer.class, alertId, status);
        return count == null ? 0 : count;
    }
}
