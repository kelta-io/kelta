package io.kelta.worker.config;

import io.kelta.runtime.messaging.nats.NatsConnectionManager;
import io.kelta.runtime.module.integration.handlers.PublishEventActionHandler;
import io.nats.client.JetStream;
import io.nats.client.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FlowConfig PUBLISH_EVENT publisher")
class FlowConfigEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Publishes the envelope as JSON to JetStream on the handler's subject")
    void publishesEnvelopeToJetStream() throws Exception {
        NatsConnectionManager nats = mock(NatsConnectionManager.class);
        JetStream jetStream = mock(JetStream.class);
        when(nats.jetStream()).thenReturn(jetStream);
        PublishEventActionHandler.EventPublisher publisher = FlowConfig.natsFlowEventPublisher(nats, objectMapper);

        publisher.publish("kelta.event.t1.order.approved", "t1:c1",
            Map.of("eventId", "e-1", "tenantId", "t1", "eventType", "order.approved"));

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(jetStream).publish(message.capture());
        assertEquals("kelta.event.t1.order.approved", message.getValue().getSubject());
        assertEquals("e-1", message.getValue().getHeaders().getFirst("Nats-Msg-Id"));
        assertEquals("t1", message.getValue().getHeaders().getFirst("X-Tenant-Id"));
        Map<?, ?> body = objectMapper.readValue(message.getValue().getData(), Map.class);
        assertEquals("order.approved", body.get("eventType"));
        assertEquals("t1", body.get("tenantId"));
    }

    @Test
    @DisplayName("A failed JetStream publish surfaces as an exception (the step fails)")
    void publishFailureThrows() throws Exception {
        NatsConnectionManager nats = mock(NatsConnectionManager.class);
        JetStream jetStream = mock(JetStream.class);
        when(nats.jetStream()).thenReturn(jetStream);
        when(jetStream.publish(any(Message.class))).thenThrow(new IOException("timeout"));
        PublishEventActionHandler.EventPublisher publisher = FlowConfig.natsFlowEventPublisher(nats, objectMapper);

        assertThrows(IllegalStateException.class,
            () -> publisher.publish("kelta.event.t1.x", "t1:c1", Map.of("tenantId", "t1")));
    }
}
