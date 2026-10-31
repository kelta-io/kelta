package io.kelta.runtime.messaging.nats;

import io.kelta.runtime.event.EventSubscription;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages NATS JetStream subscriptions for event handlers.
 *
 * <p>Supports two delivery modes:
 * <ul>
 *   <li>{@link EventSubscription.DeliveryMode#QUEUE_GROUP} — durable pull consumers
 *       with load balancing across instances</li>
 *   <li>{@link EventSubscription.DeliveryMode#BROADCAST} — ephemeral push consumers
 *       where every instance receives every message</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class NatsSubscriptionManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NatsSubscriptionManager.class);

    static final long RETRY_SECONDS = 5;

    /**
     * Bound redelivery of failing messages. Without a max-deliver cap a message
     * whose handler always throws is redelivered forever (a poison-message loop
     * that grinds one consumer and floods logs/DB with the same failure). After
     * {@code MAX_DELIVER} attempts JetStream stops redelivering the message.
     * The delayed NAK spaces attempts out instead of hot-looping.
     */
    static final long MAX_DELIVER = 5;
    static final Duration NAK_DELAY = Duration.ofSeconds(2);
    static final Duration ACK_WAIT = Duration.ofSeconds(30);

    private final NatsConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final NatsTracing tracing;
    private final MeterRegistry meterRegistry;
    private final List<EventSubscription> subscriptions = new ArrayList<>();
    private final List<JetStreamSubscription> activeSubscriptions = new ArrayList<>();
    private final ConcurrentHashMap<String, JetStreamSubscription> subsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lagByName = new ConcurrentHashMap<>();
    private final ExecutorService pullExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService lagPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nats-consumer-lag-poller");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public NatsSubscriptionManager(NatsConnectionManager connectionManager, ObjectMapper objectMapper) {
        this(connectionManager, objectMapper, NatsTracing.NOOP, null);
    }

    public NatsSubscriptionManager(NatsConnectionManager connectionManager,
                                   ObjectMapper objectMapper,
                                   NatsTracing tracing) {
        this(connectionManager, objectMapper, tracing, null);
    }

    public NatsSubscriptionManager(NatsConnectionManager connectionManager,
                                   ObjectMapper objectMapper,
                                   NatsTracing tracing,
                                   MeterRegistry meterRegistry) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.tracing = tracing != null ? tracing : NatsTracing.NOOP;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Registers a subscription to be activated on application startup.
     */
    public void register(EventSubscription subscription) {
        subscriptions.add(subscription);
    }

    /**
     * Starts all registered subscriptions. Ordered after
     * {@link JetStreamInitializer#initializeStreams()} so the streams exist before
     * the first subscribe attempt. Any subscription that still fails to start
     * (NATS not yet reachable, stream owned by a peer service not yet up) is
     * retried every {@link #RETRY_SECONDS}s until it attaches — a pod must never
     * run deaf until restart because of an unlucky startup race.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void startSubscriptions() {
        List<EventSubscription> failed = new ArrayList<>();
        for (EventSubscription sub : subscriptions) {
            if (!tryStartSubscription(sub, true)) {
                failed.add(sub);
            }
        }
        if (!failed.isEmpty()) {
            log.warn("{} NATS subscription(s) failed to start — retrying every {}s until attached",
                    failed.size(), RETRY_SECONDS);
            lagPoller.schedule(() -> retryPending(failed), RETRY_SECONDS, TimeUnit.SECONDS);
        }
        // Schedule consumer-lag polling after all subscriptions are registered.
        if (meterRegistry != null && !subscriptions.isEmpty()) {
            lagPoller.scheduleAtFixedRate(this::pollLagOnce, 30, 30, TimeUnit.SECONDS);
        }
    }

    boolean tryStartSubscription(EventSubscription sub, boolean firstAttempt) {
        try {
            switch (sub.deliveryMode()) {
                case QUEUE_GROUP -> startPullConsumer(sub);
                case BROADCAST -> startPushConsumer(sub);
            }
            return true;
        } catch (Exception e) {
            if (firstAttempt) {
                log.error("Failed to start subscription '{}' on {}: {}",
                        sub.name(), sub.subject(), e.getMessage(), e);
            } else {
                log.warn("Retry failed for subscription '{}' on {}: {}",
                        sub.name(), sub.subject(), e.getMessage());
            }
            return false;
        }
    }

    void retryPending(List<EventSubscription> pending) {
        if (!running) {
            return;
        }
        List<EventSubscription> still = new ArrayList<>();
        for (EventSubscription sub : pending) {
            if (tryStartSubscription(sub, false)) {
                log.info("Subscription '{}' attached on retry", sub.name());
            } else {
                still.add(sub);
            }
        }
        if (!still.isEmpty() && running) {
            lagPoller.schedule(() -> retryPending(still), RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void startPullConsumer(EventSubscription sub) throws Exception {
        JetStream js = connectionManager.jetStream();

        EventSubscription.Redelivery redelivery = sub.redelivery();
        ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .durable(sub.name())
                .filterSubject(sub.subject())
                .deliverPolicy(DeliverPolicy.Last)
                .ackWait(redelivery != null ? redelivery.ackWait() : ACK_WAIT)
                .maxDeliver(redelivery != null ? redelivery.maxAttempts() : MAX_DELIVER)
                .build();

        PullSubscribeOptions options = PullSubscribeOptions.builder()
                .configuration(cc)
                .build();

        JetStreamSubscription jsSub;
        try {
            jsSub = js.subscribe(sub.subject(), options);
        } catch (Exception e) {
            if (!isConsumerConfigDrift(e)) {
                throw e;
            }
            healConsumerConfigDrift(sub, cc, e);
            jsSub = js.subscribe(sub.subject(), options);
        }
        final JetStreamSubscription attached = jsSub;
        activeSubscriptions.add(attached);
        registerForLagPolling(sub.name(), attached);

        pullExecutor.submit(() -> {
            log.info("Pull consumer '{}' started on subject '{}'", sub.name(), sub.subject());
            while (running) {
                try {
                    List<Message> messages = attached.fetch(10, Duration.ofSeconds(1));
                    if (redelivery == null) {
                        for (Message msg : messages) {
                            processPulled(sub, msg);
                        }
                    } else {
                        // Concurrently: one slow delivery must not hold the rest of the batch
                        // past ackWait (JetStream would redeliver them while they still wait).
                        List<java.util.concurrent.Future<?>> inFlight = new ArrayList<>();
                        for (Message msg : messages) {
                            inFlight.add(pullExecutor.submit(() -> processPulled(sub, msg)));
                        }
                        for (java.util.concurrent.Future<?> delivery : inFlight) {
                            delivery.get();
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.warn("Pull consumer '{}' error: {}", sub.name(), e.getMessage());
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        });
    }

    /** Processes one pulled message: ack on success, NAK (bounded, or per the redelivery schedule) on failure. */
    void processPulled(EventSubscription sub, Message msg) {
        long start = System.nanoTime();
        try (NatsTracing.Scope ignored = tracing.extractAndOpen(msg.getHeaders())) {
            String data = new String(msg.getData(), StandardCharsets.UTF_8);
            if (!tenantEnvelopeValid(sub.name(), msg.getHeaders(), data)) {
                msg.ack(); // discard, don't redeliver a poisoned message
                recordProcessed(sub.name(), start);
                return;
            }
            dispatch(sub, msg, data);
            msg.ack();
            recordProcessed(sub.name(), start);
        } catch (Exception e) {
            if (sub.redelivery() != null) {
                nakScheduled(sub, msg, e);
            } else {
                nakBounded(sub.name(), msg, e);
            }
            recordFailed(sub.name(), start);
        }
    }

    /**
     * True when a subscribe failed because the durable consumer already exists
     * on the server with a different configuration. jnats pre-checks the
     * requested config against the existing durable and rejects the bind with
     * {@code [SUB-90016] Existing consumer cannot be modified} — a state a
     * rolling upgrade reaches whenever a release changes any consumer setting
     * (maxDeliver, ackWait, …), because the durable on the server keeps the
     * old config. Retrying the same subscribe can never succeed.
     */
    static boolean isConsumerConfigDrift(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("SUB-90016")
                || msg.toLowerCase(java.util.Locale.ROOT).contains("consumer cannot be modified");
    }

    /**
     * Self-heals consumer-config drift: updates the existing durable's config
     * in place via {@code addOrUpdateConsumer} — which preserves the delivery
     * cursor, so the pending backlog is delivered instead of dropped (delete +
     * recreate would lose the cursor) — then the caller re-subscribes.
     * The 2026-07-12 rollout outage (all six worker subscriptions down ~30 min
     * after MAX_DELIVER was introduced) is the incident this guards against.
     */
    private void healConsumerConfigDrift(EventSubscription sub, ConsumerConfiguration cc,
                                         Exception cause) throws Exception {
        JetStreamManagement jsm = connectionManager.jetStreamManagement();
        List<String> streams = jsm.getStreamNames(sub.subject());
        if (streams == null || streams.isEmpty()) {
            throw cause;
        }
        String stream = streams.get(0);
        log.warn("Durable consumer '{}' on '{}' has drifted server-side config ({}) — "
                        + "updating it in place on stream '{}' (delivery cursor preserved) and re-attaching",
                sub.name(), sub.subject(), cause.getMessage(), stream);
        jsm.addOrUpdateConsumer(stream, cc);
    }

    /**
     * NAKs a failed message with a redelivery delay, logging loudly on the final
     * attempt (after which JetStream stops redelivering it — see {@link #MAX_DELIVER}).
     */
    private static void nakBounded(String subName, Message msg, Exception e) {
        long delivered = deliveredCount(msg);
        if (delivered >= MAX_DELIVER) {
            log.error("Error processing message in '{}' on final delivery attempt {}/{} — "
                            + "message will NOT be redelivered: {}",
                    subName, delivered, MAX_DELIVER, e.getMessage(), e);
        } else {
            log.error("Error processing message in '{}' (attempt {}/{}): {}",
                    subName, delivered, MAX_DELIVER, e.getMessage(), e);
        }
        msg.nakWithDelay(NAK_DELAY);
    }

    /**
     * NAKs a failed delivery of a subscription with its own {@link EventSubscription.Redelivery}
     * schedule, delayed by that attempt's backoff. A failure on the last attempt is the handler's
     * to have settled; one that still throws is logged and not redelivered.
     */
    private static void nakScheduled(EventSubscription sub, Message msg, Exception e) {
        EventSubscription.Redelivery redelivery = sub.redelivery();
        long attempt = attemptOf(msg);
        if (attempt >= redelivery.maxAttempts()) {
            log.error("Error processing message in '{}' on final delivery attempt {}/{} — "
                            + "message will NOT be redelivered: {}",
                    sub.name(), attempt, redelivery.maxAttempts(), e.getMessage(), e);
        } else {
            log.warn("Delivery in '{}' failed (attempt {}/{}); redelivering in {}: {}",
                    sub.name(), attempt, redelivery.maxAttempts(), redelivery.delayAfter(attempt), e.getMessage());
        }
        msg.nakWithDelay(redelivery.delayAfter(attempt));
    }

    /** The 1-based delivery attempt of a message; 1 when JetStream metadata is unavailable. */
    static long attemptOf(Message msg) {
        long delivered = deliveredCount(msg);
        return delivered < 1 ? 1 : delivered;
    }

    private static long deliveredCount(Message msg) {
        try {
            return msg.metaData() != null ? msg.metaData().deliveredCount() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * Routes a message to the subscription's handler — the subject-aware
     * variant when registered (wildcard subjects carrying routing tokens),
     * else the body-only handler.
     */
    private static void dispatch(EventSubscription sub, Message msg, String data) {
        if (sub.redelivery() != null) {
            sub.redelivery().handler().handle(msg.getSubject(), data,
                    new EventSubscription.Attempt(attemptOf(msg), sub.redelivery().maxAttempts()));
        } else if (sub.subjectHandler() != null) {
            sub.subjectHandler().accept(msg.getSubject(), data);
        } else {
            sub.handler().accept(data);
        }
    }

    private void startPushConsumer(EventSubscription sub) throws Exception {
        Connection conn = connectionManager.getConnection();
        JetStream js = connectionManager.jetStream();
        Dispatcher dispatcher = conn.createDispatcher();

        ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .deliverPolicy(DeliverPolicy.New)
                .ackWait(ACK_WAIT)
                .maxDeliver(MAX_DELIVER)
                .build();

        PushSubscribeOptions options = PushSubscribeOptions.builder()
                .configuration(cc)
                .build();

        JetStreamSubscription jsSub = js.subscribe(sub.subject(), dispatcher, msg -> {
            long start = System.nanoTime();
            try (NatsTracing.Scope ignored = tracing.extractAndOpen(msg.getHeaders())) {
                String data = new String(msg.getData(), StandardCharsets.UTF_8);
                if (!tenantEnvelopeValid(sub.name(), msg.getHeaders(), data)) {
                    msg.ack(); // discard, don't redeliver a poisoned message
                    recordProcessed(sub.name(), start);
                    return;
                }
                dispatch(sub, msg, data);
                msg.ack();
                recordProcessed(sub.name(), start);
            } catch (Exception e) {
                nakBounded(sub.name(), msg, e);
                recordFailed(sub.name(), start);
            }
        }, false, options);

        activeSubscriptions.add(jsSub);
        registerForLagPolling(sub.name(), jsSub);
        log.info("Push consumer '{}' started on subject '{}'", sub.name(), sub.subject());
    }

    /**
     * Track this subscription for periodic lag polling and register a gauge
     * backed by the {@link AtomicLong} we update each tick. Idempotent —
     * second registration with the same name replaces the prior gauge.
     */
    void registerForLagPolling(String name, JetStreamSubscription jsSub) {
        if (meterRegistry == null) {
            return;
        }
        subsByName.put(name, jsSub);
        AtomicLong lag = lagByName.computeIfAbsent(name, k -> new AtomicLong(0L));
        Gauge.builder("kelta.nats.consume.lag", lag, AtomicLong::doubleValue)
                .description("NATS JetStream consumer pending message count (lag)")
                .tags("subscription", name)
                .register(meterRegistry);
    }

    /** Polls each tracked subscription for ConsumerInfo and updates lag gauges. */
    void pollLagOnce() {
        for (var entry : subsByName.entrySet()) {
            String name = entry.getKey();
            JetStreamSubscription sub = entry.getValue();
            try {
                ConsumerInfo info = sub.getConsumerInfo();
                long pending = info.getNumPending();
                AtomicLong lag = lagByName.get(name);
                if (lag != null) {
                    lag.set(pending);
                }
            } catch (Exception e) {
                log.debug("Lag poll failed for subscription '{}': {}", name, e.getMessage());
            }
        }
    }

    /**
     * Cross-checks the tenant identity declared by the NATS header
     * ({@link NatsEventPublisher#TENANT_ID_HEADER}) against the body's
     * {@code tenantId} field. Mismatches indicate either a publisher bug or
     * message tampering and cause the message to be dropped (ack-and-discard,
     * so it is not redelivered indefinitely).
     *
     * <p>Absence of the header is permitted for backward compatibility while
     * older publishers roll forward, and for legitimately global events that
     * carry no tenant context. In both cases the listener still receives the
     * body and can fall back to body-side tenant extraction.
     */
    private boolean tenantEnvelopeValid(String subscription, Headers headers, String body) {
        String headerTenant = headers != null ? headers.getFirst(NatsEventPublisher.TENANT_ID_HEADER) : null;
        if (headerTenant == null || headerTenant.isBlank()) {
            return true;
        }
        try {
            var tree = objectMapper.readTree(body);
            String bodyTenant = tree.path("tenantId").asText(null);
            if (bodyTenant != null && !bodyTenant.isBlank() && !headerTenant.equals(bodyTenant)) {
                log.warn("Dropping NATS message on '{}' — header X-Tenant-Id='{}' mismatches body tenantId='{}'",
                        subscription, headerTenant, bodyTenant);
                return false;
            }
        } catch (Exception e) {
            // Non-JSON body or parse error — don't drop on this alone; let the
            // listener surface its own parse error if it cares about structure.
            log.debug("Could not parse body for tenant cross-check on '{}': {}",
                    subscription, e.getMessage());
        }
        return true;
    }

    void recordProcessed(String subscription, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        Tags tags = Tags.of("subscription", subscription);
        Counter.builder("kelta.nats.consume.processed")
                .description("NATS messages successfully processed (including poisoned-message drops)")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        Timer.builder("kelta.nats.consume.latency")
                .description("End-to-end handler latency from message receipt to ack/nak")
                .tags(tags)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    void recordFailed(String subscription, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        Tags tags = Tags.of("subscription", subscription);
        Counter.builder("kelta.nats.consume.failed")
                .description("NATS messages whose handler threw and were nak'd for redelivery")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        Timer.builder("kelta.nats.consume.latency")
                .description("End-to-end handler latency from message receipt to ack/nak")
                .tags(tags)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void destroy() {
        running = false;
        lagPoller.shutdownNow();
        for (JetStreamSubscription sub : activeSubscriptions) {
            try {
                sub.drain(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("Error draining subscription: {}", e.getMessage());
            }
        }
        pullExecutor.shutdown();
    }
}
