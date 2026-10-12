package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Chat system collections (telehealth slice 2).
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class ChatSystemCollections {

    private ChatSystemCollections() {
        // Utility class
    }

    // ------------------------------------------------------------------
    // Chat (telehealth slice 2, specs/telehealth/2-chat-backend.md).
    // Message bodies are deliberately excluded from search/embedding
    // indexes and from realtime data push — content is served only over
    // the authorized /api/chat/** path (participant-checked in-controller).
    // ------------------------------------------------------------------

    public static CollectionDefinition chatQueues() {
        return systemBuilder("chat-queues", "Chat Queues", "chat_queue")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition chatConversations() {
        return systemBuilder("chat-conversations", "Chat Conversations", "chat_conversation")
            .displayFieldName("subject")
            .addImmutableField("origin")
            .addField(FieldDefinition.lookup("queueId", "chat-queues", "Queue")
                .withColumnName("queue_id")
                .withDescription("Chat queue the conversation is waiting in."))
            .addField(FieldDefinition.string("subject", 200)
                .withDescription("Short subject describing what the conversation is about."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("OPEN")
                .withEnumValues(List.of("OPEN", "ASSIGNED", "CLOSED", "ARCHIVED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.requiredString("origin", 20)
                .withDefault("INTERNAL")
                .withEnumValues(List.of("PORTAL", "INTERNAL"))
                .withDescription("Where the conversation was started from: PORTAL or INTERNAL."))
            .addField(FieldDefinition.lookup("assignedTo", "users", "Assigned To")
                .withColumnName("assigned_to")
                .withDescription("User currently responsible for this record."))
            .addField(FieldDefinition.string("contextRecordId", 36)
                .withColumnName("context_record_id")
                .withDescription("Record the conversation is about, when opened from a record page."))
            .addField(FieldDefinition.datetime("lastMessageAt").withColumnName("last_message_at")
                .withDescription("When the most recent message arrived."))
            .addField(FieldDefinition.datetime("closedAt").withColumnName("closed_at")
                .withDescription("When the record was closed."))
            .build();
    }

    public static CollectionDefinition chatMessages() {
        return systemBuilder("chat-messages", "Chat Messages", "chat_message")
            .displayFieldName("id")
            .addImmutableField("conversationId")
            .addImmutableField("senderId")
            .addImmutableField("senderType")
            .addField(FieldDefinition.masterDetail("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.lookup("senderId", "users", "Sender")
                .withColumnName("sender_id")
                .withDescription("User who sent the message."))
            .addField(FieldDefinition.requiredString("senderType", 20)
                .withColumnName("sender_type")
                .withEnumValues(List.of("INTERNAL", "PORTAL", "SYSTEM"))
                .withDescription("Whether the sender is an INTERNAL agent, a PORTAL user, or SYSTEM."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("TEXT")
                .withEnumValues(List.of("TEXT", "SYSTEM", "ATTACHMENT"))
                .withDescription("Message kind: TEXT, SYSTEM or ATTACHMENT."))
            .addField(FieldDefinition.requiredText("body")
                .withDescription("Message text; never published on the realtime event."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .build();
    }

    public static CollectionDefinition chatParticipants() {
        return systemBuilder("chat-participants", "Chat Participants", "chat_participant")
            .displayFieldName("id")
            .addImmutableField("conversationId")
            .addImmutableField("userId")
            .addField(FieldDefinition.masterDetail("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.lookup("userId", "users", "User")
                .withColumnName("user_id")
                .withDescription("User this row belongs to."))
            .addField(FieldDefinition.requiredString("role", 20)
                .withEnumValues(List.of("AGENT", "PORTAL"))
                .withDescription("Participant role: AGENT or PORTAL."))
            .addField(FieldDefinition.datetime("joinedAt").withColumnName("joined_at")
                .withDescription("When the participant joined the conversation."))
            .addField(FieldDefinition.datetime("lastReadAt").withColumnName("last_read_at")
                .withDescription("How far the participant has read; drives unread counts."))
            .build();
    }
}
