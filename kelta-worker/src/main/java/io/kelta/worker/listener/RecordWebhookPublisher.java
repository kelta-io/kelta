package io.kelta.worker.listener;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bridges record-change platform events to Svix for outbound webhook delivery,
 * scoped to the appropriate tenant.
 *
 * <p>Subscribes to {@code kelta.record.changed.>} as a queue group so only one
 * worker pod delivers each event. Talks to the Svix REST API directly via
 * {@link RestClient} — the Svix Java SDK 1.68.0 ships Jackson-2 model annotations
 * and doesn't deserialize cleanly under Spring Boot 4 / Jackson 3.
 *
 * <p>A slim event ({@code payloadOmitted}: the publisher dropped the record data because the
 * full event exceeded the NATS payload threshold) is forwarded as-is — no {@code data} /
 * {@code previousData}, {@code payloadOmitted: true} — so receivers refetch the record.
 *
 * @since 1.0.0
 */
public class RecordWebhookPublisher {

    private static final Logger log = LoggerFactory.getLogger(RecordWebhookPublisher.class);

    private static final Map<ChangeType, String> EVENT_TYPE_MAP = Map.of(
            ChangeType.CREATED, "record.created",
            ChangeType.UPDATED, "record.updated",
            ChangeType.DELETED, "record.deleted"
    );

    private final RestClient svixRestClient;
    private final ObjectMapper objectMapper;

    public RecordWebhookPublisher(RestClient svixRestClient, ObjectMapper objectMapper) {
        this.svixRestClient = svixRestClient;
        this.objectMapper = objectMapper;
    }

    public void onRecordChanged(String message) {
        try {
            PlatformEvent<RecordChangedPayload> event = objectMapper.readValue(
                    message,
                    new TypeReference<>() {}
            );

            String tenantId = event.getTenantId();
            if (tenantId == null || tenantId.isBlank()) {
                log.debug("Skipping Svix publish — no tenant ID in record change event (id={})",
                        event.getEventId());
                return;
            }

            RecordChangedPayload payload = event.getPayload();
            if (payload == null || payload.getChangeType() == null) {
                return;
            }

            String eventType = EVENT_TYPE_MAP.get(payload.getChangeType());
            if (eventType == null) {
                log.debug("Skipping Svix publish — unhandled change type: {}", payload.getChangeType());
                return;
            }

            Map<String, Object> webhookPayload = new HashMap<>();
            webhookPayload.put("recordId", payload.getRecordId());
            webhookPayload.put("collectionName", payload.getCollectionName());
            webhookPayload.put("changeType", payload.getChangeType().name());
            webhookPayload.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : null);
            if (payload.isPayloadOmitted()) {
                webhookPayload.put("payloadOmitted", true);
            } else {
                webhookPayload.put("data", payload.getData());
            }

            if (payload.getChangeType() == ChangeType.UPDATED) {
                if (!payload.isPayloadOmitted()) {
                    webhookPayload.put("previousData", payload.getPreviousData());
                }
                webhookPayload.put("changedFields", payload.getChangedFields());
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventType", eventType);
            body.put("payload", webhookPayload);
            body.put("eventId", event.getEventId());
            if (payload.getCollectionName() != null && !payload.getCollectionName().isBlank()) {
                body.put("channels", Set.of(payload.getCollectionName()));
            }

            svixRestClient.post()
                    .uri("/api/v1/app/{appId}/msg/", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Published Svix webhook: type={}, tenant={}, collection={}, record={}",
                    eventType, tenantId, payload.getCollectionName(), payload.getRecordId());

        } catch (Exception e) {
            log.error("Failed to publish record change event to Svix: {}", e.getMessage());
        }
    }
}
