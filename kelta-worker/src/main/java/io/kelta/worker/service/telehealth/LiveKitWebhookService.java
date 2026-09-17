package io.kelta.worker.service.telehealth;

import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.event.VideoSessionPayload;
import io.kelta.worker.service.SecurityAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Applies verified LiveKit webhook events to video sessions (telehealth
 * slice 5). Room names resolve the session (and its tenant — webhooks arrive
 * tenant-less on the platform connection); idempotency = INSERT into
 * {@code livekit_webhook_event} is the claim (duplicate delivery → zero rows →
 * skip), taken once the tenant is known so the claim row carries it;
 * lifecycle transitions publish
 * {@code kelta.video.session.<tenantId>.<sessionId>} for NATS-triggered flows.
 */
@Service
public class LiveKitWebhookService {

    static final String SUBJECT_PREFIX = "kelta.video.session.";
    static final String TRIGGER_SUBJECT_PREFIX = "kelta.trigger.";
    static final String TRIGGER_TOPIC = "video.session";

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final PlatformEventPublisher eventPublisher;
    private final ArchiveService archiveService;

    public LiveKitWebhookService(JdbcTemplate jdbcTemplate,
                                 PlatformEventPublisher eventPublisher,
                                 ArchiveService archiveService) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.archiveService = archiveService;
    }

    /** Processes one verified webhook body. Unknown rooms/events are ignored. */
    public void process(String rawBody) {
        JsonNode event;
        try {
            event = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Unparseable LiveKit webhook body: {}", e.getMessage());
            return;
        }
        String eventType = text(event, "event");
        String eventId = text(event, "id");
        if (eventType == null) {
            return;
        }
        String roomName = event.path("room").path("name").asText(null);
        if (roomName == null && event.has("egressInfo")) {
            roomName = event.path("egressInfo").path("roomName").asText(null);
        }
        if (roomName == null) {
            return;
        }
        Map<String, Object> session = findSession(roomName);
        if (session == null) {
            log.debug("LiveKit webhook {} for unknown room {} — ignoring", eventType, roomName);
            return;
        }
        String sessionId = String.valueOf(session.get("id"));
        String tenantId = String.valueOf(session.get("tenant_id"));

        // Claim after the room resolves, so the idempotency row can record the tenant it
        // belongs to (V202 gives livekit_webhook_event RLS keyed on that column). An event
        // for an unknown room is never claimed, which costs nothing: handling it is a no-op.
        if (eventId != null && !claim(eventId, eventType, tenantId)) {
            log.debug("Duplicate LiveKit webhook {} ({}) — skipping", eventId, eventType);
            return;
        }

        switch (eventType) {
            case "room_started" -> {
                jdbcTemplate.update(
                        "UPDATE video_session SET status = 'ACTIVE', "
                                + "started_at = COALESCE(started_at, NOW()), updated_at = NOW() "
                                + "WHERE id = ? AND status <> 'ENDED'",
                        sessionId);
                SecurityAuditLogger.log(SecurityAuditLogger.EventType.VIDEO_SESSION_STARTED,
                        "livekit", sessionId, tenantId, "success", null);
                publish(tenantId, session, "ACTIVE", null);
            }
            case "room_finished" -> {
                Integer duration = jdbcTemplate.queryForObject(
                        "UPDATE video_session SET status = 'ENDED', ended_at = NOW(), "
                                + "duration_seconds = GREATEST(0, EXTRACT(EPOCH FROM (NOW() - "
                                + "COALESCE(started_at, NOW())))::int), updated_at = NOW() "
                                + "WHERE id = ? RETURNING duration_seconds",
                        Integer.class, sessionId);
                SecurityAuditLogger.log(SecurityAuditLogger.EventType.VIDEO_SESSION_ENDED,
                        "livekit", sessionId, tenantId, "success",
                        "durationSeconds=" + duration);
                publish(tenantId, session, "ENDED", duration);
                // Auto-archive the ended session (telehealth slice 7). Best-effort:
                // the webhook must still 200 even if archival fails — the manual
                // archive-now path and the auto-archive sweep are the backstops.
                try {
                    archiveService.archiveVideoSession(tenantId, sessionId);
                } catch (Exception e) {
                    log.warn("Auto-archive of ended session {} failed: {}", sessionId, e.getMessage());
                }
            }
            case "egress_ended" -> {
                String recordingKey = firstFileResult(event);
                if (recordingKey != null) {
                    jdbcTemplate.update(
                            "UPDATE video_session SET recording_key = ?, updated_at = NOW() WHERE id = ?",
                            recordingKey, sessionId);
                    log.info("Recording stored for session {}: {}", sessionId, recordingKey);
                }
            }
            default -> {
                // participant_joined etc. — no state change in v1.
            }
        }
    }

    private boolean claim(String eventId, String eventType, String tenantId) {
        return jdbcTemplate.update(
                "INSERT INTO livekit_webhook_event (event_id, event_type, tenant_id, processed_at) "
                        + "VALUES (?, ?, ?, NOW()) ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType, tenantId) > 0;
    }

    private Map<String, Object> findSession(String roomName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, appointment_id, conversation_id FROM video_session "
                        + "WHERE room_name = ?", roomName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void publish(String tenantId, Map<String, Object> session, String status,
                         Integer durationSeconds) {
        String sessionId = String.valueOf(session.get("id"));
        VideoSessionPayload payload = new VideoSessionPayload(
                sessionId,
                str(session.get("appointment_id")),
                str(session.get("conversation_id")),
                status, durationSeconds);
        PlatformEvent<VideoSessionPayload> event =
                EventFactory.createEvent("kelta.video.session", payload);
        eventPublisher.publish(SUBJECT_PREFIX + tenantId + "." + sessionId, event);

        // Bridge onto the flow-trigger namespace so NATS_TRIGGERED flows with
        // trigger topic "video.session" fire (the documented post-visit
        // follow-up contract). The trigger listener hands the message body to
        // the flow as $.input, so flows read $.input.payload.status etc.
        eventPublisher.publish(TRIGGER_SUBJECT_PREFIX + tenantId + "." + TRIGGER_TOPIC, event);
    }

    private static String firstFileResult(JsonNode event) {
        JsonNode files = event.path("egressInfo").path("fileResults");
        if (files.isArray() && files.size() > 0) {
            JsonNode first = files.get(0);
            String location = first.path("location").asText(null);
            return location != null ? location : first.path("filename").asText(null);
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
