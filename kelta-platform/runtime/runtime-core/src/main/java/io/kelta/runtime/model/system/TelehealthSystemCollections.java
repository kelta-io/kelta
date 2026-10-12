package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.ValidationRules;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Telehealth system collections: scheduling (slice 4), video sessions (slice 5) and archival
 * (slice 7).
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class TelehealthSystemCollections {

    private TelehealthSystemCollections() {
        // Utility class
    }

    // ------------------------------------------------------------------
    // Scheduling (telehealth slice 4, specs/telehealth/4-scheduling.md).
    // Like chat: no object-permission rows are seeded — /api/telehealth/**
    // (participant/provider-checked in-controller) is the product path.
    // ------------------------------------------------------------------

    public static CollectionDefinition telehealthAvailability() {
        return systemBuilder("telehealth-availability", "Telehealth Availability", "telehealth_availability")
            .displayFieldName("id")
            .addField(FieldDefinition.lookup("providerId", "users", "Provider")
                .withColumnName("provider_id")
                .withDescription("Provider (staff user) this row belongs to."))
            .addField(FieldDefinition.requiredString("kind", 20)
                .withDefault("RULE")
                .withEnumValues(List.of("RULE", "EXCEPTION"))
                .withDescription("Whether the row is a weekly pattern or a single-date exception."))
            .addField(FieldDefinition.integer("weekday")
                .withDescription("Day of week the rule applies to (0=Sunday)."))
            .addField(FieldDefinition.date("exceptionDate").withColumnName("exception_date")
                .withDescription("Specific date this row overrides the weekly pattern for."))
            // Wall-clock "HH:mm" / "HH:mm:ss" strings (varchar(8) columns since
            // V172); SlotService parses them with LocalTime.parse, so keep the
            // pattern in lockstep with what java.time accepts.
            .addField(FieldDefinition.string("startTime", 8).withColumnName("start_time")
                .withValidation(ValidationRules.forString(null, 8, "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$"))
                .withDescription("Local start time of the availability window."))
            .addField(FieldDefinition.string("endTime", 8).withColumnName("end_time")
                .withValidation(ValidationRules.forString(null, 8, "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$"))
                .withDescription("Local end time of the availability window."))
            .addField(FieldDefinition.requiredString("timezone", 50).withDefault("UTC")
                .withDescription("IANA timezone name (e.g. America/New_York)."))
            .addField(FieldDefinition.bool("closed").withDefault(false)
                .withDescription("Whether the provider is unavailable for the whole window."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition telehealthAppointments() {
        return systemBuilder("telehealth-appointments", "Telehealth Appointments", "telehealth_appointment")
            .displayFieldName("id")
            .addImmutableField("providerId")
            .addImmutableField("portalUserId")
            .addField(FieldDefinition.lookup("providerId", "users", "Provider")
                .withColumnName("provider_id")
                .withDescription("Provider (staff user) this row belongs to."))
            .addField(FieldDefinition.lookup("portalUserId", "users", "Portal User")
                .withColumnName("portal_user_id")
                .withDescription("Portal (external) user this row belongs to."))
            .addField(FieldDefinition.datetime("scheduledStart").withColumnName("scheduled_start")
                .withDescription("When the appointment is scheduled to start."))
            .addField(FieldDefinition.datetime("scheduledEnd").withColumnName("scheduled_end")
                .withDescription("When the appointment is scheduled to end."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("CONFIRMED")
                .withEnumValues(List.of("REQUESTED", "CONFIRMED", "CANCELLED", "COMPLETED", "NO_SHOW"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("visitType", 100).withColumnName("visit_type")
                .withDescription("Kind of visit being scheduled."))
            .addField(FieldDefinition.string("reason", 500)
                .withDescription("Patient-supplied reason for the visit."))
            .addField(FieldDefinition.lookup("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.string("videoSessionId", 36).withColumnName("video_session_id")
                .withDescription("Video session created for this appointment."))
            .addField(FieldDefinition.datetime("reminderSentAt").withColumnName("reminder_sent_at")
                .withDescription("When the appointment reminder was sent."))
            .addField(FieldDefinition.datetime("cancelledAt").withColumnName("cancelled_at")
                .withDescription("When the appointment was cancelled."))
            .build();
    }

    /** Video sessions (telehealth slice 5) — lifecycle owned by the LiveKit webhook. */
    public static CollectionDefinition videoSessions() {
        return systemBuilder("video-sessions", "Video Sessions", "video_session")
            .displayFieldName("id")
            .addImmutableField("roomName")
            .addField(FieldDefinition.lookup("appointmentId", "telehealth-appointments", "Appointment")
                .withColumnName("appointment_id")
                .withDescription("Appointment this row belongs to."))
            .addField(FieldDefinition.lookup("conversationId", "chat-conversations", "Conversation")
                .withColumnName("conversation_id")
                .withDescription("Chat conversation this row belongs to."))
            .addField(FieldDefinition.requiredString("roomName", 100)
                .withColumnName("room_name").withUnique(true)
                .withDescription("LiveKit room name the session runs in."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("CREATED")
                .withEnumValues(List.of("CREATED", "ACTIVE", "ENDED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at")
                .withDescription("When the video session became active."))
            .addField(FieldDefinition.datetime("endedAt").withColumnName("ended_at")
                .withDescription("When the session ended."))
            .addField(FieldDefinition.integer("durationSeconds").withColumnName("duration_seconds")
                .withDescription("Session length in seconds, stamped when it ends."))
            .addField(FieldDefinition.bool("recordingConsent").withColumnName("recording_consent")
                .withDefault(false)
                .withDescription("Whether all parties consented to recording."))
            .addField(FieldDefinition.string("recordingKey", 500).withColumnName("recording_key")
                .withDescription("Object-storage key of the session recording."))
            .build();
    }

    /**
     * Archived encounter records (telehealth slice 7) — one immutable row per
     * archived source (a closed chat conversation or an ended video session).
     * The artifact (canonical JSON transcript + PDF render) lives in S3 as
     * attachments owned by this row; {@code sha256} pins the JSON for tamper
     * evidence; {@code retentionUntil}/{@code legalHold} drive the purge sweep.
     */
    public static CollectionDefinition telehealthArchives() {
        return systemBuilder("telehealth-archives", "Telehealth Archives", "telehealth_archive")
            .displayFieldName("id")
            .addImmutableField("sourceType")
            .addImmutableField("sourceId")
            .addField(FieldDefinition.requiredString("sourceType", 20)
                .withColumnName("source_type")
                .withEnumValues(List.of("CONVERSATION", "VIDEO_SESSION"))
                .withDescription("Kind of object archived: a conversation or a video session."))
            .addField(FieldDefinition.requiredString("sourceId", 36).withColumnName("source_id")
                .withDescription("Id of the object this row was derived from."))
            .addField(FieldDefinition.lookup("appointmentId", "telehealth-appointments", "Appointment")
                .withColumnName("appointment_id")
                .withDescription("Appointment this row belongs to."))
            .addField(FieldDefinition.lookup("portalUserId", "users", "Portal User")
                .withColumnName("portal_user_id")
                .withDescription("Portal (external) user this row belongs to."))
            .addField(FieldDefinition.json("artifactAttachmentIds").withColumnName("artifact_attachment_ids")
                .withDescription("JSON array of attachment ids captured in this archive."))
            .addField(FieldDefinition.string("sha256", 64)
                .withDescription("SHA-256 digest of the archived artifacts."))
            .addField(FieldDefinition.datetime("archivedAt").withColumnName("archived_at")
                .withDescription("When the archive was written."))
            .addField(FieldDefinition.string("archivedBy", 64).withColumnName("archived_by")
                .withDescription("User or process that produced the archive."))
            .addField(FieldDefinition.datetime("retentionUntil").withColumnName("retention_until")
                .withDescription("Date before which the archive must not be purged."))
            .addField(FieldDefinition.bool("legalHold", false).withColumnName("legal_hold")
                .withDescription("Whether the archive is under legal hold and exempt from purge."))
            .addField(FieldDefinition.datetime("purgedAt").withColumnName("purged_at")
                .withDescription("When the archived artifacts were purged; the row survives as a tombstone."))
            .build();
    }
}
