package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In-app notifications ({@code notification}, V214). Every recipient-facing query filters on
 * both {@code tenant_id} and {@code recipient_id}: RLS scopes the tenant, the
 * {@code recipient_id} predicate is what keeps one user out of another's rows.
 */
@Repository
public class NotificationRepository {

    public record Notification(String id, String recipientId, String title, String body, String link,
                               String level, Instant readAt, Instant createdAt) {
    }

    private static final String COLUMNS = "id, recipient_id, title, body, link, level, read_at, created_at";

    private static final RowMapper<Notification> MAPPER = (rs, n) -> new Notification(
            rs.getString("id"),
            rs.getString("recipient_id"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("link"),
            rs.getString("level"),
            instant(rs.getTimestamp("read_at")),
            instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The {@code platform_user.id} of a tenant user named by id or email. */
    public Optional<String> findUserId(String tenantId, String idOrEmail) {
        List<String> byId = jdbcTemplate.queryForList(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, idOrEmail);
        if (!byId.isEmpty()) {
            return Optional.of(byId.get(0));
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM platform_user WHERE tenant_id = ? AND LOWER(email) = LOWER(?) ORDER BY id",
                String.class, tenantId, idOrEmail).stream().findFirst();
    }

    public String insert(String tenantId, String recipientId, String title, String body, String link,
                         String level) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO notification (id, tenant_id, recipient_id, title, body, link, level, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, recipientId, title, body, link, level, Timestamp.from(Instant.now()));
        return id;
    }

    /** The recipient's notifications, newest first. */
    public List<Notification> findByRecipient(String tenantId, String recipientId, int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification WHERE tenant_id = ? AND recipient_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, tenantId, recipientId, limit, offset);
    }

    public Optional<Notification> findByIdForRecipient(String tenantId, String recipientId, String id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM notification WHERE tenant_id = ? AND recipient_id = ? AND id = ?",
                MAPPER, tenantId, recipientId, id).stream().findFirst();
    }

    public long countByRecipient(String tenantId, String recipientId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE tenant_id = ? AND recipient_id = ?",
                Long.class, tenantId, recipientId);
        return count == null ? 0 : count;
    }

    public long countUnread(String tenantId, String recipientId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE tenant_id = ? AND recipient_id = ? AND read_at IS NULL",
                Long.class, tenantId, recipientId);
        return count == null ? 0 : count;
    }

    /**
     * Marks one of the recipient's notifications read, keeping the first read time.
     *
     * @return whether the recipient owns a notification with this id
     */
    public boolean markRead(String tenantId, String recipientId, String id) {
        return jdbcTemplate.update(
                "UPDATE notification SET read_at = COALESCE(read_at, ?) "
                        + "WHERE tenant_id = ? AND recipient_id = ? AND id = ?",
                Timestamp.from(Instant.now()), tenantId, recipientId, id) > 0;
    }

    /** Marks every unread notification of the recipient read; returns how many changed. */
    public int markAllRead(String tenantId, String recipientId) {
        return jdbcTemplate.update(
                "UPDATE notification SET read_at = ? "
                        + "WHERE tenant_id = ? AND recipient_id = ? AND read_at IS NULL",
                Timestamp.from(Instant.now()), tenantId, recipientId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
