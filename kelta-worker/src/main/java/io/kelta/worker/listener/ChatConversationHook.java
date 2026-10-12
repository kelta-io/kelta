package io.kelta.worker.listener;

import io.kelta.runtime.event.ChatConversationPayload;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * BeforeSaveHook for {@code chat-conversations} (telehealth slice 2):
 * publishes {@code kelta.chat.conversation.<tenantId>.<conversationId>}
 * lifecycle events (OPEN/ASSIGNED/CLOSED/ARCHIVED — ids and state only) so the
 * gateway can notify joined sockets and NATS-triggered automations can react
 * without polling.
 */
@Component
public class ChatConversationHook implements BeforeSaveHook {

    static final String SUBJECT_PREFIX = "kelta.chat.conversation.";

    private static final Logger log = LoggerFactory.getLogger(ChatConversationHook.class);

    private final PlatformEventPublisher eventPublisher;

    public ChatConversationHook(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getCollectionName() {
        return "chat-conversations";
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        publish(str(record.get("id")), record, tenantId);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                            Map<String, Object> previous, String tenantId) {
        publish(id != null ? id : str(record.get("id")), record, tenantId);
    }

    private void publish(String conversationId, Map<String, Object> record, String tenantId) {
        if (conversationId == null) {
            return;
        }
        ChatConversationPayload payload = new ChatConversationPayload(
                conversationId,
                str(record.get("status")),
                str(record.get("queueId")),
                str(record.get("assignedTo")));
        PlatformEvent<ChatConversationPayload> event =
                EventFactory.createEvent("kelta.chat.conversation", payload);
        String subject = SUBJECT_PREFIX + tenantId + "." + conversationId;
        eventPublisher.publish(subject, event);
        log.debug("Published chat conversation event {} status={}", conversationId, payload.getStatus());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
