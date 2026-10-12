package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.ParticipantShareSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * BeforeSaveHook for {@code telehealth-appointments} (telehealth slice 4).
 * Validates the window shape on every write path (incl. the admin-only
 * generic JSON:API route) and grants the portal user a participant
 * {@code record_share} on create so the appointment is visible to them through
 * every share-widened surface. Emails/slot verification live in
 * {@code AppointmentService} — the hook stays cheap and side-effect-minimal.
 */
@Component
public class AppointmentHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(AppointmentHook.class);

    private final ParticipantShareSupport participantShareSupport;

    public AppointmentHook(ParticipantShareSupport participantShareSupport) {
        this.participantShareSupport = participantShareSupport;
    }

    @Override
    public String getCollectionName() {
        return "telehealth-appointments";
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        String start = str(record.get("scheduledStart"));
        String end = str(record.get("scheduledEnd"));
        if (start == null || end == null) {
            return BeforeSaveResult.error("scheduledStart", "scheduledStart and scheduledEnd are required");
        }
        try {
            if (!Instant.parse(end).isAfter(Instant.parse(start))) {
                return BeforeSaveResult.error("scheduledEnd", "scheduledEnd must be after scheduledStart");
            }
        } catch (Exception e) {
            return BeforeSaveResult.error("scheduledStart", "Timestamps must be ISO-8601 instants");
        }
        if (str(record.get("providerId")) == null || str(record.get("portalUserId")) == null) {
            return BeforeSaveResult.error("providerId", "providerId and portalUserId are required");
        }
        return BeforeSaveResult.ok();
    }

    @Override
    public void afterCreate(Map<String, Object> record, String tenantId) {
        String appointmentId = str(record.get("id"));
        String portalUserId = str(record.get("portalUserId"));
        if (appointmentId == null || portalUserId == null) {
            return;
        }
        boolean granted = participantShareSupport.grant(
                "telehealth-appointments", appointmentId, portalUserId, "READ");
        if (granted) {
            log.debug("Granted participant share on appointment {} to {}", appointmentId, portalUserId);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() || "null".equals(s) ? null : s;
    }
}
