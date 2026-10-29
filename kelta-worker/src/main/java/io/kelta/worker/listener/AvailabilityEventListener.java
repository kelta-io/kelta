package io.kelta.worker.listener;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.service.availability.AlertDispatchService;
import io.kelta.worker.service.availability.AvailabilityEvent;
import io.kelta.worker.service.availability.AvailabilityMatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes availability observations from external pollers and turns them into
 * alerts.
 *
 * <p>Subscribed on the <b>queue group</b> {@code worker-availability}, unlike the
 * broadcast cache-invalidation listeners: an observation must be processed by
 * exactly one pod, or every pod would race to claim the same alerts. (The dedupe
 * key would still make that harmless, but it would waste the work.)
 *
 * <p>The subject carries the tenant: {@code kelta.availability.event.<tenantId>.<source>}.
 * The body is <b>poller-authored JSON</b>, not a {@code PlatformEvent} — pollers
 * live outside this repository and should not have to construct a platform
 * envelope. It is parsed defensively as {@link JsonNode}; because no payload
 * record crosses NATS here, this adds no native reflect-config surface.
 *
 * <p>A poller's report on a {@code synthetic} target is dropped by the matcher before any
 * state is recorded: only the admin injector ({@code SyntheticAvailabilityService}) drives a
 * synthetic target, through {@link #process} with the {@code INJECTOR} origin.
 *
 * <p>After fanout, a compact summary is republished onto
 * {@code kelta.trigger.<tenantId>.availability} so tenant flows (digests, tickers,
 * custom automations) can react — deliberately <b>after</b> the hot path, so a
 * slow flow can never delay a member's notification.
 */
@Component
public class AvailabilityEventListener {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityEventListener.class);

    static final String TRIGGER_SUBJECT_PREFIX = "kelta.trigger.";
    static final String TRIGGER_TOPIC = "availability";

    private final AvailabilityMatchService matchService;
    private final AlertDispatchService dispatchService;
    private final PlatformEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AvailabilityEventListener(AvailabilityMatchService matchService,
                                     AlertDispatchService dispatchService,
                                     PlatformEventPublisher eventPublisher,
                                     ObjectMapper objectMapper) {
        this.matchService = matchService;
        this.dispatchService = dispatchService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles one observation.
     *
     * @param subject full NATS subject; the tenant is segment 3
     * @param message poller-authored JSON body
     */
    public void handleAvailabilityEvent(String subject, String message) {
        try {
            String tenantId = tenantFromSubject(subject);
            if (tenantId == null) {
                log.warn("Cannot extract tenant from availability subject '{}' — dropping", subject);
                return;
            }

            AvailabilityEvent event = parse(message, sourceFromSubject(subject));
            if (event == null) {
                return;
            }

            process(tenantId, event, AvailabilityMatchService.Origin.POLLER);
        } catch (Exception e) {
            // Swallow: a poison message must not wedge the subscription.
            log.error("Failed to process availability event on {}: {}",
                    subject, e.getMessage(), e);
        }
    }

    /**
     * Matches one observation, delivers every alert it claims, then bridges a summary to tenant
     * flows. The NATS path calls this with {@link AvailabilityMatchService.Origin#POLLER}; the
     * admin synthetic-event injector calls it with {@code INJECTOR}, so both run the same
     * match → dispatch → bridge code.
     *
     * @return the alerts newly claimed — empty when the slot did not just open, no watch
     *         matched, every match was deduped, or the target does not belong to {@code origin}
     */
    public List<AvailabilityMatchService.ClaimedAlert> process(
            String tenantId, AvailabilityEvent event, AvailabilityMatchService.Origin origin) {
        List<AvailabilityMatchService.ClaimedAlert> claimed =
                matchService.process(tenantId, event, origin);
        if (claimed.isEmpty()) {
            return claimed; // no transition, no matching watch, or all deduped
        }

        for (AvailabilityMatchService.ClaimedAlert alert : claimed) {
            // dispatch() never throws; one member's failed delivery must not
            // abandon the rest of the fanout.
            dispatchService.dispatch(tenantId, alert);
        }

        bridgeToFlows(tenantId, event, claimed);
        return claimed;
    }

    /**
     * Parses the poller's body. The {@code source} falls back to the subject
     * segment when the body omits it, so a poller publishing to its own subject
     * need not repeat itself.
     */
    AvailabilityEvent parse(String message, String subjectSource) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (RuntimeException e) {
            log.warn("Unparseable availability event body: {}", e.getMessage());
            return null;
        }
        if (!node.isObject()) {
            log.warn("Availability event body is not a JSON object — dropping");
            return null;
        }

        JsonNode window = node.path("window");
        return new AvailabilityEvent(
                text(node, "source") != null ? text(node, "source") : subjectSource,
                text(node, "targetExternalId"),
                text(node, "slotKey"),
                text(node, "status"),
                instant(window, "start"),
                instant(window, "end"),
                node.path("quantity").isIntegralNumber() ? node.path("quantity").intValue() : null,
                objectMapper.convertValue(node.path("meta"), Map.class),
                instant(node, "polledAt"));
    }

    /**
     * Republishes a compact summary for tenant flows — ids and counts only, no
     * member identities. Fire-and-forget: a bridge failure is logged but must not
     * fail the alert that already went out.
     */
    private void bridgeToFlows(String tenantId, AvailabilityEvent event,
                               List<AvailabilityMatchService.ClaimedAlert> claimed) {
        try {
            var first = claimed.get(0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("targetId", first.target().id());
            summary.put("targetName", first.target().name());
            summary.put("source", event.source());
            summary.put("slotKey", event.slotKey());
            summary.put("windowStart", event.windowStart() == null
                    ? null : event.windowStart().toString());
            summary.put("windowEnd", event.windowEnd() == null
                    ? null : event.windowEnd().toString());
            summary.put("matchedWatches", claimed.size());
            summary.put("synthetic", first.target().synthetic());

            // A plain Map, not a payload record — nothing new to register in the
            // native reflect-config (the presence-event precedent).
            eventPublisher.publish(TRIGGER_SUBJECT_PREFIX + tenantId + "." + TRIGGER_TOPIC,
                    io.kelta.runtime.event.EventFactory.createEvent(
                            "kelta.availability.matched", summary));
        } catch (Exception e) {
            log.warn("Availability trigger bridge failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** {@code kelta.availability.event.<tenantId>.<source>} — tenant is segment 3. */
    static String tenantFromSubject(String subject) {
        return segment(subject, 3);
    }

    /** Source is segment 4 of the subject. */
    static String sourceFromSubject(String subject) {
        return segment(subject, 4);
    }

    private static String segment(String subject, int index) {
        if (subject == null) {
            return null;
        }
        String[] parts = subject.split("\\.");
        if (parts.length <= index) {
            return null;
        }
        String value = parts[index];
        return value.isBlank() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String s = value.stringValue();
        return s.isBlank() ? null : s;
    }

    /**
     * Reads an ISO-8601 instant. A malformed timestamp yields null rather than
     * dropping the whole event — a missing window still leaves an actionable
     * "this slot opened".
     */
    private static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeException e) {
            log.debug("Ignoring unparseable timestamp '{}' in field {}", raw, field);
            return null;
        }
    }
}
