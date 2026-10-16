package io.kelta.runtime.messaging.nats;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NatsEventPublisher")
class NatsEventPublisherTest {

    @Mock
    private NatsConnectionManager connectionManager;

    @Mock
    private JetStream jetStream;

    private ObjectMapper objectMapper;

    private PlatformEvent<String> event() {
        return new PlatformEvent<>("evt-1", "test.event", "tenant-1",
                "corr-1", "user-1", Instant.now(), "payload");
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("successful publish increments success counter and releases permit")
    void successfulPublishReleasesPermit() throws Exception {
        when(connectionManager.jetStream()).thenReturn(jetStream);
        PublishAck ack = ackStub();
        when(jetStream.publishAsync(any(NatsMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(ack));
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null);

        publisher.publish("subject", event());

        await(() -> publisher.successCount() == 1L);
        assertThat(publisher.successCount()).isEqualTo(1L);
        assertThat(publisher.failureCount()).isEqualTo(0L);
        assertThat(publisher.droppedCount()).isEqualTo(0L);
        assertThat(publisher.availableInflightPermits()).isEqualTo(10);
    }

    @Test
    @DisplayName("failed publish future increments failure counter and releases permit")
    void failedPublishReleasesPermit() throws Exception {
        when(connectionManager.jetStream()).thenReturn(jetStream);
        CompletableFuture<PublishAck> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(jetStream.publishAsync(any(NatsMessage.class))).thenReturn(failed);
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null);

        publisher.publish("subject", event());

        await(() -> publisher.failureCount() == 1L);
        assertThat(publisher.failureCount()).isEqualTo(1L);
        assertThat(publisher.availableInflightPermits()).isEqualTo(10);
    }

    @Test
    @DisplayName("inflight cap drops publish and increments dropped counter")
    void inflightCapDropsExcessPublishes() throws Exception {
        when(connectionManager.jetStream()).thenReturn(jetStream);
        CompletableFuture<PublishAck> neverCompletes = new CompletableFuture<>();
        when(jetStream.publishAsync(any(NatsMessage.class))).thenReturn(neverCompletes);
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 1, null);

        publisher.publish("subject", event());
        publisher.publish("subject", event());

        assertThat(publisher.droppedCount()).isEqualTo(1L);
        assertThat(publisher.successCount()).isEqualTo(0L);
        assertThat(publisher.availableInflightPermits()).isEqualTo(0);
    }

    @Test
    @DisplayName("invokes NatsTracing.inject so trace context propagates to subscribers")
    void invokesTracingInject() throws Exception {
        when(connectionManager.jetStream()).thenReturn(jetStream);
        PublishAck ack = ackStub();
        when(jetStream.publishAsync(any(NatsMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(ack));
        NatsTracing tracing = mock(NatsTracing.class);
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null, tracing);

        publisher.publish("subject", event());

        ArgumentCaptor<Headers> captor = ArgumentCaptor.forClass(Headers.class);
        verify(tracing).inject(captor.capture());
        assertThat(captor.getValue().getFirst("Nats-Msg-Id")).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("synchronous publish failure releases permit and increments failure counter")
    void synchronousFailureReleasesPermit() throws Exception {
        when(connectionManager.jetStream()).thenReturn(jetStream);
        when(jetStream.publishAsync(any(NatsMessage.class)))
                .thenThrow(new RuntimeException("connection lost"));
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 5, null);

        publisher.publish("subject", event());

        assertThat(publisher.failureCount()).isEqualTo(1L);
        assertThat(publisher.availableInflightPermits()).isEqualTo(5);
    }

    private static final long ONE_MIB = 1024 * 1024;
    private static final String RECORD_SUBJECT = "kelta.record.changed.tenant-1.provider-snapshots";

    private static PlatformEvent<RecordChangedPayload> recordEvent(int dataBytes) {
        RecordChangedPayload payload = RecordChangedPayload.updated(
                        "provider-snapshots", "rec-1",
                        Map.of("id", "rec-1", "snapshot", "x".repeat(dataBytes)),
                        Map.of("id", "rec-1", "snapshot", "old"),
                        List.of("snapshot"))
                .withContainsMaskedFields(true)
                .withOwnerScoped(true);
        return new PlatformEvent<>("evt-big", "record.updated", "tenant-1",
                "corr-1", "user-1", Instant.parse("2026-10-15T12:00:00Z"), payload);
    }

    private NatsMessage publishAndCapture(NatsEventPublisher publisher, PlatformEvent<?> event)
            throws Exception {
        publisher.publish(RECORD_SUBJECT, event);
        ArgumentCaptor<NatsMessage> captor = ArgumentCaptor.forClass(NatsMessage.class);
        verify(jetStream, times(1)).publishAsync(captor.capture());
        return captor.getValue();
    }

    private void stubSuccessfulPublish() throws Exception {
        when(connectionManager.jetStream()).thenReturn(jetStream);
        PublishAck ack = ackStub();
        when(jetStream.publishAsync(any(NatsMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(ack));
    }

    @Test
    @DisplayName("oversized record event is published once in slim form with payloadOmitted")
    void oversizedRecordEventPublishesSlimForm() throws Exception {
        stubSuccessfulPublish();
        Logger logger = mock(Logger.class);
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null, NatsTracing.NOOP, ONE_MIB, logger);
        PlatformEvent<RecordChangedPayload> event = recordEvent(2 * 1024 * 1024);
        int originalSize = objectMapper.writeValueAsBytes(event).length;

        NatsMessage message = publishAndCapture(publisher, event);

        await(() -> publisher.successCount() == 1L);
        assertThat(message.getData().length).isLessThan((int) ONE_MIB);
        JsonNode body = objectMapper.readTree(new String(message.getData(), StandardCharsets.UTF_8));
        assertThat(body.path("eventId").asText()).isEqualTo("evt-big");
        assertThat(body.path("tenantId").asText()).isEqualTo("tenant-1");
        assertThat(body.path("correlationId").asText()).isEqualTo("corr-1");
        assertThat(body.path("userId").asText()).isEqualTo("user-1");
        JsonNode payload = body.path("payload");
        assertThat(payload.path("collectionName").asText()).isEqualTo("provider-snapshots");
        assertThat(payload.path("recordId").asText()).isEqualTo("rec-1");
        assertThat(payload.path("changeType").asText()).isEqualTo("UPDATED");
        assertThat(payload.path("changedFields").get(0).asText()).isEqualTo("snapshot");
        assertThat(payload.path("payloadOmitted").asBoolean()).isTrue();
        assertThat(payload.path("containsMaskedFields").asBoolean()).isTrue();
        assertThat(payload.path("ownerScoped").asBoolean()).isTrue();
        assertThat(payload.has("data")).isFalse();
        assertThat(payload.has("previousData")).isFalse();
        assertThat(message.getHeaders().getFirst("Nats-Msg-Id")).isEqualTo("evt-big");
        assertThat(message.getHeaders().getFirst(NatsEventPublisher.TENANT_ID_HEADER)).isEqualTo("tenant-1");
        assertThat(publisher.failureCount()).isEqualTo(0L);
        // The caller's event keeps its data.
        assertThat(event.getPayload().getData()).containsKey("snapshot");
        assertThat(event.getPayload().isPayloadOmitted()).isFalse();
        // WARN names event id, subject, collection, record and original size — never the body.
        verify(logger).warn(contains("payload omitted"), eq("evt-big"), eq(RECORD_SUBJECT),
                eq("provider-snapshots"), eq("rec-1"), eq(originalSize), eq(ONE_MIB), anyInt());
    }

    @Test
    @DisplayName("record event under the threshold is published byte-for-byte unchanged")
    void underThresholdRecordEventPublishedUnchanged() throws Exception {
        stubSuccessfulPublish();
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null, NatsTracing.NOOP, ONE_MIB);
        PlatformEvent<RecordChangedPayload> event = recordEvent(1024);

        NatsMessage message = publishAndCapture(publisher, event);

        assertThat(message.getData()).isEqualTo(objectMapper.writeValueAsBytes(event));
        String json = new String(message.getData(), StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("payloadOmitted");
        assertThat(objectMapper.readTree(json).path("payload").has("data")).isTrue();
    }

    @Test
    @DisplayName("oversized event without a slim form is not sent, counts a failure and logs WARN")
    void oversizedNonRecordEventIsDropped() throws Exception {
        Logger logger = mock(Logger.class);
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null, NatsTracing.NOOP, ONE_MIB, logger);
        PlatformEvent<String> event = new PlatformEvent<>("evt-str", "test.event", "tenant-1",
                "corr-1", "user-1", Instant.now(), "x".repeat(2 * 1024 * 1024));
        int originalSize = objectMapper.writeValueAsBytes(event).length;

        assertThatCode(() -> publisher.publish("subject", event)).doesNotThrowAnyException();

        verify(jetStream, never()).publishAsync(any(NatsMessage.class));
        assertThat(publisher.failureCount()).isEqualTo(1L);
        assertThat(publisher.availableInflightPermits()).isEqualTo(10);
        verify(logger).warn(contains("no slim form"), eq("evt-str"), eq("subject"),
                eq(originalSize), eq(ONE_MIB), eq("String"));
    }

    @Test
    @DisplayName("configured threshold wins over the server max_payload")
    void configuredThreshold() {
        NatsEventPublisher publisher = new NatsEventPublisher(
                connectionManager, objectMapper, 10, null, NatsTracing.NOOP, 500_000);

        assertThat(publisher.maxEventPayloadBytes()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("unset threshold defaults to server max_payload minus headroom")
    void defaultThresholdFromServerMaxPayload() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getMaxPayload()).thenReturn(ONE_MIB);
        when(connectionManager.getConnection()).thenReturn(connection);
        stubSuccessfulPublish();
        NatsProperties properties = new NatsProperties();
        NatsEventPublisher publisher = new NatsEventPublisher(connectionManager, objectMapper,
                10, null, NatsTracing.NOOP, properties.getMaxEventPayloadBytes());

        assertThat(properties.getMaxEventPayloadBytes()).isZero();
        assertThat(publisher.maxEventPayloadBytes())
                .isEqualTo(ONE_MIB - NatsEventPublisher.PAYLOAD_HEADROOM_BYTES);

        // ~1,000,000 bytes: under the server's 1 MiB but over the default threshold.
        NatsMessage message = publishAndCapture(publisher, recordEvent(1_000_000));
        JsonNode payload = objectMapper.readTree(
                new String(message.getData(), StandardCharsets.UTF_8)).path("payload");
        assertThat(payload.path("payloadOmitted").asBoolean()).isTrue();
        assertThat(payload.has("data")).isFalse();
    }

    @Test
    @DisplayName("unset threshold falls back to 1 MiB minus headroom without a connection")
    void defaultThresholdWithoutConnection() {
        NatsEventPublisher publisher = new NatsEventPublisher(connectionManager, objectMapper);

        assertThat(publisher.maxEventPayloadBytes())
                .isEqualTo(ONE_MIB - NatsEventPublisher.PAYLOAD_HEADROOM_BYTES);
    }

    private static PublishAck ackStub() {
        PublishAck ack = mock(PublishAck.class);
        when(ack.getSeqno()).thenReturn(1L);
        when(ack.getStream()).thenReturn("TEST");
        return ack;
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
