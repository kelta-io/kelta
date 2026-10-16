package io.kelta.runtime.messaging.nats;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.event.RecordChangedPayload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

/**
 * NATS JetStream implementation of {@link PlatformEventPublisher}.
 *
 * <p>Publishes events as JSON to JetStream subjects. Sets the {@code Nats-Msg-Id}
 * header to the event's unique ID for server-side deduplication.
 *
 * <p>Publishing is asynchronous. Inflight publishes are bounded by a semaphore
 * (default 1000, configurable via {@code kelta.nats.max-inflight-publishes}).
 * When the cap is reached new publishes are dropped immediately with a warning
 * and a {@code kelta.nats.publish.dropped} counter increment, rather than queuing
 * unbounded inside the client.
 *
 * <p>Events whose serialized body exceeds the payload threshold
 * ({@code kelta.nats.max-event-payload-bytes}; 0 = the server's {@code max_payload} minus
 * {@link #PAYLOAD_HEADROOM_BYTES}) would be refused by the server. A record-change event is
 * published in its slim form instead: {@code data} and {@code previousData} dropped and
 * {@code payloadOmitted=true}, so consumers re-read the record by id. Any other oversized event
 * is not sent and counts as a failure.
 *
 * <p>When a {@link MeterRegistry} is provided, three counters and one gauge are
 * registered: {@code kelta.nats.publish.success}, {@code kelta.nats.publish.failure},
 * {@code kelta.nats.publish.dropped}, and {@code kelta.nats.publish.inflight}.
 *
 * @since 1.0.0
 */
public class NatsEventPublisher implements PlatformEventPublisher {

    private final Logger log;

    /**
     * Name of the NATS header that carries the publishing tenant's ID.
     *
     * <p>Consumers can use this for tenant-scoped routing, filtering, or
     * cross-checking against the body's {@code tenantId} field as a
     * tamper-resistance signal. Set for every event that carries a tenant
     * context; events that are explicitly global (tenantId null/blank) omit
     * the header so receivers can distinguish them.
     */
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    /** Room left under the server's {@code max_payload} for headers and trace context. */
    static final long PAYLOAD_HEADROOM_BYTES = 64 * 1024;

    /** NATS server default {@code max_payload}, used when no connection is available. */
    static final long DEFAULT_SERVER_MAX_PAYLOAD_BYTES = 1024 * 1024;

    private final NatsConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final Semaphore inflight;
    private final NatsTracing tracing;
    private final long configuredMaxEventPayloadBytes;
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder droppedCount = new LongAdder();

    public NatsEventPublisher(NatsConnectionManager connectionManager, ObjectMapper objectMapper) {
        this(connectionManager, objectMapper, 1000, null, NatsTracing.NOOP);
    }

    public NatsEventPublisher(NatsConnectionManager connectionManager,
                              ObjectMapper objectMapper,
                              int maxInflightPublishes,
                              MeterRegistry meterRegistry) {
        this(connectionManager, objectMapper, maxInflightPublishes, meterRegistry, NatsTracing.NOOP);
    }

    public NatsEventPublisher(NatsConnectionManager connectionManager,
                              ObjectMapper objectMapper,
                              int maxInflightPublishes,
                              MeterRegistry meterRegistry,
                              NatsTracing tracing) {
        this(connectionManager, objectMapper, maxInflightPublishes, meterRegistry, tracing, 0);
    }

    /**
     * @param maxEventPayloadBytes largest event body sent as-is; 0 or less derives it from the
     *                             server's {@code max_payload} minus {@link #PAYLOAD_HEADROOM_BYTES}
     */
    public NatsEventPublisher(NatsConnectionManager connectionManager,
                              ObjectMapper objectMapper,
                              int maxInflightPublishes,
                              MeterRegistry meterRegistry,
                              NatsTracing tracing,
                              long maxEventPayloadBytes) {
        this(connectionManager, objectMapper, maxInflightPublishes, meterRegistry, tracing,
                maxEventPayloadBytes, LoggerFactory.getLogger(NatsEventPublisher.class));
    }

    NatsEventPublisher(NatsConnectionManager connectionManager,
                       ObjectMapper objectMapper,
                       int maxInflightPublishes,
                       MeterRegistry meterRegistry,
                       NatsTracing tracing,
                       long maxEventPayloadBytes,
                       Logger log) {
        this.log = log;
        this.connectionManager = connectionManager;
        this.configuredMaxEventPayloadBytes = maxEventPayloadBytes;
        this.objectMapper = objectMapper;
        this.inflight = new Semaphore(Math.max(1, maxInflightPublishes));
        this.tracing = tracing != null ? tracing : NatsTracing.NOOP;
        registerMetrics(meterRegistry, maxInflightPublishes);
    }

    private void registerMetrics(MeterRegistry registry, int capacity) {
        if (registry == null) {
            return;
        }
        Counter.builder("kelta.nats.publish.success")
                .description("NATS JetStream publishes that received an ack")
                .register(registry);
        Counter.builder("kelta.nats.publish.failure")
                .description("NATS JetStream publishes that failed (broker error, timeout, etc.)")
                .register(registry);
        Counter.builder("kelta.nats.publish.dropped")
                .description("NATS publishes dropped because the inflight cap was reached")
                .register(registry);
        Gauge.builder("kelta.nats.publish.inflight", inflight,
                        s -> (double) (capacity - s.availablePermits()))
                .description("Current number of in-flight NATS publishes")
                .register(registry);
        // Bridge LongAdder values into the registered counters via FunctionCounter would
        // double-count on each tick; instead callers should read the registered counters
        // which we increment directly inside publish().
    }

    @Override
    public void publish(String subject, PlatformEvent<?> event) {
        if (!inflight.tryAcquire()) {
            droppedCount.increment();
            log.warn("Dropping NATS publish event {} to {} — inflight cap reached ({} permits)",
                    event.getEventId(), subject, inflight.availablePermits());
            return;
        }
        try {
            byte[] data = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
            long threshold = maxEventPayloadBytes();
            if (data.length > threshold) {
                if (!(event.getPayload() instanceof RecordChangedPayload payload)) {
                    inflight.release();
                    failureCount.increment();
                    log.warn("Not publishing event {} to {}: payload is {} bytes, over the {}-byte "
                                    + "threshold, and {} has no slim form",
                            event.getEventId(), subject, data.length, threshold,
                            event.getPayload() == null ? "null" : event.getPayload().getClass().getSimpleName());
                    return;
                }
                byte[] slim = slimRecordEventJson(event, payload);
                if (slim.length > threshold) {
                    inflight.release();
                    failureCount.increment();
                    log.warn("Not publishing event {} to {} (collection={}): slim form is still {} bytes, "
                                    + "over the {}-byte threshold",
                            event.getEventId(), subject, payload.getCollectionName(), slim.length, threshold);
                    return;
                }
                log.warn("Event {} to {} (collection={}, record={}) is {} bytes, over the {}-byte threshold: "
                                + "payload omitted, publishing slim record event ({} bytes)",
                        event.getEventId(), subject, payload.getCollectionName(), payload.getRecordId(),
                        data.length, threshold, slim.length);
                data = slim;
            }

            Headers headers = new Headers();
            headers.add("Nats-Msg-Id", event.getEventId());
            String tenantId = event.getTenantId();
            if (tenantId != null && !tenantId.isBlank()) {
                headers.add(TENANT_ID_HEADER, tenantId);
            }
            tracing.inject(headers);

            NatsMessage message = NatsMessage.builder()
                    .subject(subject)
                    .headers(headers)
                    .data(data)
                    .build();

            JetStream js = connectionManager.jetStream();
            CompletableFuture<PublishAck> future = js.publishAsync(message);
            future.whenComplete((ack, ex) -> {
                try {
                    if (ex != null) {
                        failureCount.increment();
                        log.error("Failed to publish event {} to {}: {}",
                                event.getEventId(), subject, ex.getMessage());
                    } else {
                        successCount.increment();
                        log.debug("Published event {} to {} (stream: {}, seq: {})",
                                event.getEventId(), subject, ack.getStream(), ack.getSeqno());
                    }
                } finally {
                    inflight.release();
                }
            });
        } catch (Exception e) {
            inflight.release();
            failureCount.increment();
            log.error("Failed to publish event {} to {}: {}",
                    event.getEventId(), subject, e.getMessage());
        }
    }

    /**
     * JSON of a copy of a record event without the {@code data}/{@code previousData} keys,
     * flagged {@code payloadOmitted}. The caller's event and payload are left untouched.
     */
    private byte[] slimRecordEventJson(PlatformEvent<?> event, RecordChangedPayload payload) {
        RecordChangedPayload slim = new RecordChangedPayload(
                payload.getCollectionName(), payload.getRecordId(), payload.getChangeType(),
                null, null, payload.getChangedFields())
                .withContainsMaskedFields(payload.isContainsMaskedFields())
                .withOwnerScoped(payload.isOwnerScoped())
                .withPayloadOmitted(true);
        ObjectNode tree = objectMapper.valueToTree(new PlatformEvent<>(event.getEventId(),
                event.getEventType(), event.getTenantId(), event.getCorrelationId(), event.getUserId(),
                event.getTimestamp(), slim));
        if (tree.get("payload") instanceof ObjectNode payloadNode) {
            payloadNode.remove("data");
            payloadNode.remove("previousData");
        }
        return objectMapper.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Largest event body published as-is: the configured value, else the connected server's
     * {@code max_payload} minus {@link #PAYLOAD_HEADROOM_BYTES} (the NATS default when no
     * connection is available).
     */
    long maxEventPayloadBytes() {
        if (configuredMaxEventPayloadBytes > 0) {
            return configuredMaxEventPayloadBytes;
        }
        long serverMax = DEFAULT_SERVER_MAX_PAYLOAD_BYTES;
        try {
            Connection connection = connectionManager.getConnection();
            if (connection != null && connection.getMaxPayload() > 0) {
                serverMax = connection.getMaxPayload();
            }
        } catch (Exception e) {
            log.debug("Could not read NATS max_payload, using {} bytes: {}", serverMax, e.getMessage());
        }
        return Math.max(1, serverMax - PAYLOAD_HEADROOM_BYTES);
    }

    long successCount() {
        return successCount.sum();
    }

    long failureCount() {
        return failureCount.sum();
    }

    long droppedCount() {
        return droppedCount.sum();
    }

    int availableInflightPermits() {
        return inflight.availablePermits();
    }
}
