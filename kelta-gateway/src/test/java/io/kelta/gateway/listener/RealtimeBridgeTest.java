package io.kelta.gateway.listener;

import io.kelta.gateway.websocket.RealtimeWebSocketHandler;
import io.kelta.gateway.websocket.SubscriptionManager;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RealtimeBridge.
 *
 * Tests the NATS-to-WebSocket bridge that fans record change events out to
 * subscribed sessions, including data suppression for collections whose
 * fields carry a masking configuration (containsMaskedFields).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RealtimeBridge Tests")
class RealtimeBridgeTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String COLLECTION = "accounts";
    private static final String RECORD_ID = "rec-1";

    @Mock
    private SubscriptionManager subscriptionManager;

    @Mock
    private RealtimeWebSocketHandler webSocketHandler;

    @Mock
    private WebSocketSession session;

    private ObjectMapper objectMapper;
    private RealtimeBridge bridge;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        bridge = new RealtimeBridge(subscriptionManager, webSocketHandler);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("chat-* collections never fan out on the tenant-wide channel (bodies stay off the socket)")
    void chatCollectionsAreSkipped() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collectionName", "chat-messages");
        payload.put("recordId", "m1");
        payload.put("changeType", "CREATED");
        payload.put("data", Map.of("body", "PRIVATE MESSAGE BODY"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tenantId", TENANT_ID);
        event.put("payload", payload);

        bridge.onRecordChanged(toJson(event));

        // Conversation-scoped fanout is ChatMessageBridge's job (membership-checked
        // chat.join); the tenant-wide realtime channel must stay silent for chat.
        verify(subscriptionManager, never()).getSubscribers(any(), any());
        verifyNoInteractions(webSocketHandler);
    }

    private String recordChangedEvent(Boolean containsMaskedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collectionName", COLLECTION);
        payload.put("recordId", RECORD_ID);
        payload.put("changeType", "UPDATED");
        payload.put("data", Map.of("name", "Acme", "phone", "555-0100"));
        if (containsMaskedFields != null) {
            payload.put("containsMaskedFields", containsMaskedFields);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tenantId", TENANT_ID);
        event.put("timestamp", "2026-07-04T12:00:00Z");
        event.put("payload", payload);
        return toJson(event);
    }

    private String ownerScopedEvent() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collectionName", COLLECTION);
        payload.put("recordId", RECORD_ID);
        payload.put("changeType", "CREATED");
        payload.put("data", Map.of("name", "Acme", "member", "22222222-2222-2222-2222-222222222222"));
        payload.put("ownerScoped", true);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tenantId", TENANT_ID);
        event.put("timestamp", "2026-10-08T12:00:00Z");
        event.put("payload", payload);
        return toJson(event);
    }

    @Test
    @DisplayName("Owner-scoped collections publish invalidation-only events (no record attributes)")
    void ownerScopedCollectionsAreInvalidationOnly() {
        when(subscriptionManager.getSubscribers(TENANT_ID, COLLECTION)).thenReturn(Set.of(session));
        when(session.isOpen()).thenReturn(true);

        bridge.onRecordChanged(ownerScopedEvent());

        Map<String, Object> wsEvent = captureSentWsEvent();
        assertFalse(wsEvent.containsKey("data"), "owner-scoped record data must not fan out");
        assertEquals(Set.of("event", "collection", "changeType", "recordId", "timestamp"), wsEvent.keySet());
        assertEquals(RECORD_ID, wsEvent.get("recordId"));
    }

    private Map<String, Object> captureSentWsEvent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> wsEventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).sendMessage(eq(session), wsEventCaptor.capture());
        return wsEventCaptor.getValue();
    }

    @Nested
    @DisplayName("Masked Fields Data Suppression Tests")
    class MaskedFieldsDataSuppressionTests {

        @BeforeEach
        void subscribeSession() {
            when(subscriptionManager.getSubscribers(TENANT_ID, COLLECTION)).thenReturn(Set.of(session));
            when(session.isOpen()).thenReturn(true);
        }

        @Test
        @DisplayName("Should omit data when containsMaskedFields is true")
        void shouldOmitDataWhenContainsMaskedFieldsIsTrue() {
            // Act
            bridge.onRecordChanged(recordChangedEvent(true));

            // Assert - event delivered without record data
            Map<String, Object> wsEvent = captureSentWsEvent();
            assertFalse(wsEvent.containsKey("data"), "data should be suppressed for masked collections");
            assertEquals("record.changed", wsEvent.get("event"));
            assertEquals(COLLECTION, wsEvent.get("collection"));
            assertEquals("UPDATED", wsEvent.get("changeType"));
            assertEquals(RECORD_ID, wsEvent.get("recordId"));
        }

        @Test
        @DisplayName("Should include data when containsMaskedFields is false")
        void shouldIncludeDataWhenContainsMaskedFieldsIsFalse() {
            // Act
            bridge.onRecordChanged(recordChangedEvent(false));

            // Assert
            Map<String, Object> wsEvent = captureSentWsEvent();
            assertTrue(wsEvent.containsKey("data"));
            assertEquals(Map.of("name", "Acme", "phone", "555-0100"), wsEvent.get("data"));
            assertEquals(COLLECTION, wsEvent.get("collection"));
            assertEquals(RECORD_ID, wsEvent.get("recordId"));
        }

        @Test
        @DisplayName("Should include data when containsMaskedFields is absent")
        void shouldIncludeDataWhenContainsMaskedFieldsIsAbsent() {
            // Act
            bridge.onRecordChanged(recordChangedEvent(null));

            // Assert
            Map<String, Object> wsEvent = captureSentWsEvent();
            assertTrue(wsEvent.containsKey("data"));
            assertEquals(Map.of("name", "Acme", "phone", "555-0100"), wsEvent.get("data"));
        }
    }

    @Nested
    @DisplayName("Event Routing Tests")
    class EventRoutingTests {

        @Test
        @DisplayName("Should not send when there are no subscribers")
        void shouldNotSendWhenNoSubscribers() {
            when(subscriptionManager.getSubscribers(TENANT_ID, COLLECTION)).thenReturn(Set.of());

            bridge.onRecordChanged(recordChangedEvent(true));

            verify(webSocketHandler, never()).sendMessage(any(), any());
        }

        @Test
        @DisplayName("Should not send when session is closed")
        void shouldNotSendWhenSessionIsClosed() {
            when(subscriptionManager.getSubscribers(TENANT_ID, COLLECTION)).thenReturn(Set.of(session));
            when(session.isOpen()).thenReturn(false);

            bridge.onRecordChanged(recordChangedEvent(false));

            verify(webSocketHandler, never()).sendMessage(any(), any());
        }

        @Test
        @DisplayName("Should skip event without tenant ID")
        void shouldSkipEventWithoutTenantId() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collectionName", COLLECTION);
            payload.put("recordId", RECORD_ID);
            payload.put("changeType", "CREATED");

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("payload", payload);

            bridge.onRecordChanged(toJson(event));

            verifyNoInteractions(subscriptionManager, webSocketHandler);
        }

        @Test
        @DisplayName("Should skip event without a payload map")
        void shouldSkipEventWithoutPayloadMap() {
            String json = "{\"tenantId\":\"" + TENANT_ID + "\",\"payload\":null}";

            assertDoesNotThrow(() -> bridge.onRecordChanged(json));
            verifyNoInteractions(subscriptionManager, webSocketHandler);
        }

        @Test
        @DisplayName("Should handle malformed JSON gracefully")
        void shouldHandleMalformedJsonGracefully() {
            assertDoesNotThrow(() -> bridge.onRecordChanged("not-valid-json"));
            verifyNoInteractions(subscriptionManager, webSocketHandler);
        }
    }
}
