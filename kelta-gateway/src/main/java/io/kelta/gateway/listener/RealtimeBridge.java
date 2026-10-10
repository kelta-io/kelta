package io.kelta.gateway.listener;

import tools.jackson.databind.ObjectMapper;
import io.kelta.gateway.websocket.RealtimeWebSocketHandler;
import io.kelta.gateway.websocket.SubscriptionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bridges record change events to WebSocket subscribers.
 *
 * <p>Routes events to subscribed WebSocket sessions based on tenant and collection.
 *
 * @since 1.0.0
 */
@Component
public class RealtimeBridge {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubscriptionManager subscriptionManager;
    private final RealtimeWebSocketHandler webSocketHandler;

    public RealtimeBridge(SubscriptionManager subscriptionManager,
                          RealtimeWebSocketHandler webSocketHandler) {
        this.subscriptionManager = subscriptionManager;
        this.webSocketHandler = webSocketHandler;
    }

    @SuppressWarnings("unchecked")
    public void onRecordChanged(String message) {
        try {
            Map<String, Object> event = MAPPER.readValue(message, Map.class);

            String tenantId = (String) event.get("tenantId");
            Object payloadObj = event.get("payload");
            if (!(payloadObj instanceof Map<?, ?> payload)) {
                return;
            }

            String collectionName = (String) payload.get("collectionName");
            String recordId = (String) payload.get("recordId");
            String changeType = (String) payload.get("changeType");
            Object data = payload.get("data");

            // Chat collections are conversation-scoped (telehealth slice 2): their
            // record.changed events still feed flows/search/audit worker-side, but
            // the tenant-wide realtime fanout would hand message bodies to ANY
            // tenant subscriber. ChatMessageBridge (membership-checked chat.join)
            // is the only socket path for chat — skip them here entirely.
            if (collectionName != null && collectionName.startsWith("chat-")) {
                return;
            }

            // Collections with masking-configured fields: the bridge fans one event
            // out to every tenant subscriber and cannot apply per-user field
            // security, so record data is suppressed — clients refetch through the
            // JSON:API path, where FLS + masking apply per user.
            if (Boolean.TRUE.equals(payload.get("containsMaskedFields"))) {
                data = null;
            }

            // Owner-scoped collections (member data ownership): each row is readable only
            // by its owner, and the fanout has no per-subscriber row filter — so the event
            // is invalidation-only; clients refetch through the owner-scoped JSON:API path.
            if (Boolean.TRUE.equals(payload.get("ownerScoped"))) {
                data = null;
            }

            if (tenantId == null || collectionName == null) {
                return;
            }

            Set<WebSocketSession> subscribers = subscriptionManager.getSubscribers(tenantId, collectionName);
            if (subscribers.isEmpty()) {
                return;
            }

            // Build WebSocket event message
            Map<String, Object> wsEvent = new LinkedHashMap<>();
            wsEvent.put("event", "record.changed");
            wsEvent.put("collection", collectionName);
            wsEvent.put("changeType", changeType);
            wsEvent.put("recordId", recordId);
            if (data != null) {
                wsEvent.put("data", data);
            }
            wsEvent.put("timestamp", event.get("timestamp"));

            // Fan out to subscribers
            int delivered = 0;
            for (WebSocketSession session : subscribers) {
                try {
                    if (session.isOpen()) {
                        webSocketHandler.sendMessage(session, wsEvent);
                        delivered++;
                    }
                } catch (Exception e) {
                    log.debug("Failed to deliver event to session {}: {}", session.getId(), e.getMessage());
                }
            }

            if (delivered > 0) {
                log.debug("Routed {} event for {}/{} to {} sessions",
                        changeType, collectionName, recordId, delivered);
            }

        } catch (Exception e) {
            log.error("Failed to process record changed event for realtime bridge: {}", e.getMessage());
        }
    }
}
