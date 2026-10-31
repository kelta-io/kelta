package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventSubscription;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleRegistry.Endpoint;
import io.kelta.worker.service.FieldMaskingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Delivers record changes to the external modules that subscribed to them
 * ({@code specs/module-platform/9-external-modules.md} §3.6.1): a queue-group JetStream consumer,
 * {@value #SUBSCRIPTION}, on {@code kelta.record.changed.>} — the existing {@code KELTA_RECORDS}
 * stream, no new subject — so one pod handles each event.
 *
 * <p>For each event it finds this pod's callable installs of the event's tenant whose manifest
 * {@code events[]} name the collection and type ({@link ExternalModuleRegistry#eventSubscribers}),
 * and POSTs each one a {@code RecordEvent} at {@code {baseUrl}/kelta/v1/events} through
 * {@link ExternalModuleInvoker} — signed, with the install's service-principal token, behind its
 * circuit breaker and the module-host guard, like every other module call. {@code data} and
 * {@code previousData} are filtered for the principal exactly as hook records are
 * ({@link ModulePrincipalAccess}: server-only and {@code HIDDEN} fields removed, {@code MASKED}
 * masked), and so is {@code changedFields}.
 *
 * <p><b>At least once.</b> Deliveries to several installs run concurrently, capped at
 * {@value #MAX_IN_FLIGHT_PER_INSTALL} in flight per install so one slow module cannot take every
 * thread. Any install that did not answer 2xx fails the message, which is redelivered after the
 * configured backoff (default 5 s, 30 s, 2 min, 10 min, 1 h) — to every subscriber again, so a
 * module dedupes on {@code eventId}. On the last attempt each install that still fails gets a
 * {@code module_event_dead_letter} row and the message is acknowledged. A disabled install gets
 * nothing; its events are not kept for later.
 *
 * <p><b>Never blocks a write.</b> This runs off the {@code kelta.record.changed} event, after the
 * write committed; nothing here holds the writer.
 */
public class ModuleEventDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ModuleEventDeliveryListener.class);

    /** Durable consumer and queue-group name. */
    public static final String SUBSCRIPTION = "worker-module-events";
    static final String SUBJECT = "kelta.record.changed.>";
    /** The deadline of one delivery; the protocol gives events no per-subscription timeout. */
    static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);
    /** Deliveries in flight per install on this pod (§8, "Event delivery load"). */
    static final int MAX_IN_FLIGHT_PER_INSTALL = 8;
    /**
     * Ack wait of the consumer: a message waits at most one delivery for an in-flight slot and
     * then runs its own, so two deadlines plus slack never trips a redelivery mid-flight.
     */
    static final Duration ACK_WAIT = Duration.ofSeconds(60);
    /** Metric: events dead-lettered, tagged {@code module}. */
    static final String METRIC_DEAD_LETTERS = "kelta_worker_module_event_dead_letters";

    private static final Map<ChangeType, String> TYPES = Map.of(
            ChangeType.CREATED, "created", ChangeType.UPDATED, "updated", ChangeType.DELETED, "deleted");

    /** What one delivery came to. {@code status} is 0 when the module never answered. */
    record Outcome(boolean delivered, int status, String error) {
        static final Outcome DELIVERED = new Outcome(true, 200, null);
    }

    private final ExternalModuleRegistry registry;
    private final ExternalModuleInvoker invoker;
    private final ModuleEventDeadLetterStore deadLetters;
    private final CollectionRegistry collectionRegistry;
    private final FieldMaskingService masking;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final List<Duration> backoff;
    private final Map<String, Semaphore> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService deliveries = Executors.newVirtualThreadPerTaskExecutor();

    public ModuleEventDeliveryListener(ExternalModuleRegistry registry, ExternalModuleInvoker invoker,
                                       ModuleEventDeadLetterStore deadLetters, CollectionRegistry collectionRegistry,
                                       FieldMaskingService masking, ObjectMapper objectMapper,
                                       MeterRegistry meterRegistry, List<Duration> backoff) {
        this.registry = Objects.requireNonNull(registry);
        this.invoker = Objects.requireNonNull(invoker);
        this.deadLetters = Objects.requireNonNull(deadLetters);
        this.collectionRegistry = collectionRegistry;
        this.masking = masking;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.meterRegistry = meterRegistry;
        this.backoff = List.copyOf(backoff);
    }

    /**
     * Parses {@code kelta.modules.external.event-backoff} — comma-separated {@code 5s}, {@code 2m},
     * {@code 1h} (or ISO-8601) durations, one per redelivery.
     *
     * @throws IllegalArgumentException when it is empty or an entry is not a duration
     */
    public static List<Duration> parseBackoff(String value) {
        List<Duration> delays = new ArrayList<>();
        for (String raw : value == null ? new String[0] : value.split(",")) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            try {
                delays.add(entry.toUpperCase(Locale.ROOT).startsWith("P")
                        ? Duration.parse(entry) : Duration.parse("PT" + entry.toUpperCase(Locale.ROOT)));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("kelta.modules.external.event-backoff: '" + entry
                        + "' is not a duration (use 5s, 2m, 1h)");
            }
        }
        if (delays.isEmpty()) {
            throw new IllegalArgumentException("kelta.modules.external.event-backoff needs at least one delay");
        }
        return delays;
    }

    /** The queue-group subscription to register with the {@code NatsSubscriptionManager}. */
    public EventSubscription subscription() {
        return EventSubscription.queueGroupWithRedelivery(SUBSCRIPTION, SUBJECT, SUBSCRIPTION,
                new EventSubscription.Redelivery(backoff, ACK_WAIT, this::handle));
    }

    /**
     * One delivery of one {@code kelta.record.changed} message.
     *
     * @throws EventDeliveryException when an install did not take the event and another attempt follows
     */
    void handle(String subject, String body, EventSubscription.Attempt attempt) {
        JsonNode tree = objectMapper.readTree(body);
        String tenantId = text(tree, "tenantId");
        String eventId = text(tree, "eventId");
        if (tenantId == null || eventId == null || !tree.hasNonNull("payload")) {
            return;
        }
        RecordChangedPayload payload = objectMapper.treeToValue(tree.get("payload"), RecordChangedPayload.class);
        String type = payload.getChangeType() == null ? null : TYPES.get(payload.getChangeType());
        if (type == null || payload.getCollectionName() == null) {
            return;
        }
        // A pod that just started may get here before its first reconcile has read the installs.
        registry.ensureInitialized();
        List<Endpoint> subscribers = registry.eventSubscribers(tenantId, payload.getCollectionName(), type);
        if (subscribers.isEmpty()) {
            return;
        }
        String timestamp = timestamp(tree.get("timestamp"));
        Map<Endpoint, Future<Delivery>> pending = new LinkedHashMap<>();
        for (Endpoint endpoint : subscribers) {
            pending.put(endpoint, deliveries.submit(() -> TenantContext.callWithTenant(tenantId,
                    () -> deliver(endpoint, eventId, type, timestamp, payload))));
        }
        List<String> failed = new ArrayList<>();
        for (Map.Entry<Endpoint, Future<Delivery>> entry : pending.entrySet()) {
            Endpoint endpoint = entry.getKey();
            Delivery delivery = await(entry.getValue());
            if (delivery.outcome().delivered()) {
                continue;
            }
            String moduleId = endpoint.row().moduleId();
            if (attempt.isLast()) {
                deadLetter(endpoint, eventId, delivery, attempt.number());
            } else {
                failed.add(moduleId + " (" + delivery.outcome().error() + ")");
            }
        }
        if (!failed.isEmpty()) {
            throw new EventDeliveryException("event " + eventId + " on '" + payload.getCollectionName()
                    + "' not delivered (attempt " + attempt.number() + "/" + attempt.maxAttempts() + "): " + failed);
        }
    }

    /** The body sent and what came of sending it. */
    record Delivery(String body, Outcome outcome) {}

    private Delivery deliver(Endpoint endpoint, String eventId, String type, String timestamp,
                             RecordChangedPayload payload) {
        String body;
        try {
            body = objectMapper.writeValueAsString(recordEvent(endpoint, eventId, type, timestamp, payload));
        } catch (RuntimeException e) {
            return new Delivery("{}", new Outcome(false, 0, "could not build the event: " + e.getMessage()));
        }
        Semaphore slots = inFlight.computeIfAbsent(endpoint.row().id(), id -> new Semaphore(MAX_IN_FLIGHT_PER_INSTALL));
        try {
            if (!slots.tryAcquire(DELIVERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Delivery(body, new Outcome(false, 0, MAX_IN_FLIGHT_PER_INSTALL
                        + " deliveries already in flight to this module"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery(body, new Outcome(false, 0, "interrupted"));
        }
        try {
            ModuleHttpClient.Call call = ModuleHttpClient.Call.postJson(ModuleHttpClient.EVENTS_PATH,
                    body.getBytes(StandardCharsets.UTF_8), DELIVERY_TIMEOUT, null);
            Outcome outcome = invoker.call(endpoint.row(), ExternalModuleInvoker.Kind.EVENT, call,
                    ModuleEventDeliveryListener::outcome);
            return new Delivery(body, outcome);
        } catch (ModuleUnavailableException e) { // a timeout is one too
            return new Delivery(body, new Outcome(false, 0, e.getMessage()));
        } finally {
            slots.release();
        }
    }

    private static Outcome outcome(OutboundHttpClient.Response response) {
        if (response.isSuccess()) {
            return Outcome.DELIVERED;
        }
        return new Outcome(false, response.statusCode(), "answered " + response.statusCode()
                + ModuleHttpClient.snippet(response));
    }

    /** The protocol {@code RecordEvent}, filtered for the install's principal (§3.4). */
    private Map<String, Object> recordEvent(Endpoint endpoint, String eventId, String type, String timestamp,
                                            RecordChangedPayload payload) {
        String collection = payload.getCollectionName();
        CollectionDefinition definition = collectionRegistry == null ? null : collectionRegistry.get(collection);
        ModulePrincipalAccess access = endpoint.access();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("tenantId", endpoint.row().tenantId());
        event.put("collection", collection);
        event.put("recordId", payload.getRecordId());
        event.put("timestamp", timestamp);
        if (payload.isPayloadOmitted()) {
            event.put("data", null);
            event.put("previousData", null);
            event.put("payloadOmitted", true);
        } else {
            event.put("data", access.outbound(collection, definition, payload.getData(), masking));
            event.put("previousData", access.outbound(collection, definition, payload.getPreviousData(), masking));
        }
        event.put("changedFields", access.visibleFields(collection, definition, payload.getChangedFields()));
        return event;
    }

    private void deadLetter(Endpoint endpoint, String eventId, Delivery delivery, long attempts) {
        String tenantId = endpoint.row().tenantId();
        String moduleId = endpoint.row().moduleId();
        try {
            boolean written = TenantContext.callWithTenant(tenantId, () -> deadLetters.record(tenantId,
                    endpoint.row().id(), eventId, delivery.body(), delivery.outcome().status(),
                    delivery.outcome().error(), attempts));
            if (written) {
                log.error("Event {} dead-lettered for module '{}' (tenant {}) after {} attempt(s): {}", eventId,
                        moduleId, tenantId, attempts, delivery.outcome().error());
                if (meterRegistry != null) {
                    Counter.builder(METRIC_DEAD_LETTERS).tag("module", moduleId).register(meterRegistry).increment();
                }
            }
        } catch (RuntimeException e) {
            // Nothing redelivers a final attempt; the log line is all that is left of it.
            log.error("Event {} for module '{}' (tenant {}) failed its last attempt and could not be "
                    + "dead-lettered: {} (delivery: {})", eventId, moduleId, tenantId, e.getMessage(),
                    delivery.outcome().error(), e);
        }
    }

    private static Delivery await(Future<Delivery> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery("{}", new Outcome(false, 0, "interrupted"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Delivery("{}", new Outcome(false, 0, cause.getMessage()));
        }
    }

    private static String text(JsonNode tree, String field) {
        JsonNode node = tree.get(field);
        return node == null || !node.isString() || node.stringValue().isBlank() ? null : node.stringValue();
    }

    /** The event time as ISO-8601: the envelope carries an ISO string or epoch seconds. */
    static String timestamp(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            double seconds = node.doubleValue();
            long whole = (long) Math.floor(seconds);
            return Instant.ofEpochSecond(whole, Math.round((seconds - whole) * 1_000_000_000L)).toString();
        }
        if (!node.isString()) {
            return null;
        }
        String text = node.stringValue();
        try {
            return Instant.parse(text).toString();
        } catch (DateTimeParseException e) {
            try {
                return timestamp(JsonNodeFactory.instance.numberNode(Double.parseDouble(text)));
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
    }

    /** Fails a delivery attempt so JetStream redelivers it after the attempt's backoff. */
    static class EventDeliveryException extends RuntimeException {
        EventDeliveryException(String message) {
            super(message);
        }
    }
}
