package io.kelta.worker.listener;

import io.kelta.runtime.event.ChatMessagePayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * BeforeSaveHook for {@code chat-messages} (telehealth slice 2).
 *
 * <p>Write-side enforcement runs here so it applies to EVERY path that can
 * create a message — the /api/chat controller AND the admin-only generic
 * JSON:API route: the conversation must exist and be writable (not
 * CLOSED/ARCHIVED) and the sender must be a participant (SYSTEM messages
 * exempt). Messages are immutable — updates are rejected.
 *
 * <p>afterCreate publishes {@code kelta.chat.message.<tenantId>.<conversationId>}
 * (ids + metadata only, NEVER the body — the gateway fans this to
 * conversation-joined sockets as an invalidation signal) and bumps the
 * conversation's {@code last_message_at} via direct JDBC (deliberately not
 * QueryEngine: the bump must not recurse through hooks or spam record.changed).
 */
@Component
public class ChatMessageHook implements BeforeSaveHook {

    static final String SUBJECT_PREFIX = "kelta.chat.message.";

    private static final Logger log = LoggerFactory.getLogger(ChatMessageHook.class);

    private final PlatformEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public ChatMessageHook(PlatformEventPublisher eventPublisher, JdbcTemplate jdbcTemplate) {
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "chat-messages";
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        String conversationId = str(record.get("conversationId"));
        if (conversationId == null) {
            return BeforeSaveResult.error("conversationId", "conversationId is required");
        }
        String status = jdbcTemplate.query(
                        "SELECT status FROM chat_conversation WHERE id = ?",
                        (rs, i) -> rs.getString("status"), conversationId).stream()
                .findFirst().orElse(null);
        if (status == null) {
            return BeforeSaveResult.error("conversationId", "Conversation not found");
        }
        if ("CLOSED".equals(status) || "ARCHIVED".equals(status)) {
            return BeforeSaveResult.error("conversationId", "Conversation is " + status);
        }

        String senderType = str(record.get("senderType"));
        String senderId = str(record.get("senderId"));
        if (!"SYSTEM".equals(senderType)) {
            if (senderId == null) {
                return BeforeSaveResult.error("senderId", "senderId is required");
            }
            Integer member = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_participant WHERE conversation_id = ? AND user_id = ?",
                    Integer.class, conversationId, senderId);
            if (member == null || member == 0) {
                return BeforeSaveResult.error("senderId", "Sender is not a conversation participant");
            }
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        return BeforeSaveResult.error("id", "Chat messages are immutable");
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        String conversationId = str(record.get("conversationId"));
        String messageId = str(record.get("id"));
        if (conversationId == null || messageId == null) {
            return;
        }

        jdbcTemplate.update(
                "UPDATE chat_conversation SET last_message_at = ?, updated_at = NOW() WHERE id = ?",
                Timestamp.from(Instant.now()), conversationId);

        ChatMessagePayload payload = new ChatMessagePayload(
                conversationId, messageId,
                str(record.get("senderId")),
                str(record.get("senderType")),
                str(record.get("kind")));
        PlatformEvent<ChatMessagePayload> event =
                EventFactory.createEvent("kelta.chat.message", payload);
        String subject = SUBJECT_PREFIX + tenantId + "." + conversationId;
        eventPublisher.publish(subject, event);
        log.debug("Published chat message event {} on {}", messageId, subject);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
