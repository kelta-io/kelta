package io.kelta.runtime.event;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Describes a subscription to platform events on a messaging subject.
 *
 * <p>Subscriptions can be either queue-based (load-balanced across instances)
 * or broadcast (every instance receives every message). Queue subscriptions
 * use a shared durable consumer; broadcast subscriptions use ephemeral
 * per-instance consumers.
 *
 * <p>Handlers normally receive only the message body. When the concrete
 * subject matters (wildcard subscriptions that encode routing data in subject
 * tokens, e.g. {@code kelta.trigger.<tenantId>.<topic>}), use a
 * subject-aware subscription — {@code subjectHandler} then receives
 * {@code (subject, body)} and {@code handler} is null.
 *
 * <p>A queue-group subscription that owns its retry schedule carries a {@link Redelivery}
 * instead of either handler: its handler sees which delivery attempt it is on, a failure is
 * redelivered after that attempt's backoff, and the last attempt is the handler's to settle
 * (dead-letter it and return) — see {@link #queueGroupWithRedelivery}.
 *
 * @since 1.0.0
 */
public record EventSubscription(
        String name,
        String subject,
        DeliveryMode deliveryMode,
        String queueGroup,
        Consumer<String> handler,
        BiConsumer<String, String> subjectHandler,
        Redelivery redelivery
) {

    public EventSubscription(String name, String subject, DeliveryMode deliveryMode,
                             String queueGroup, Consumer<String> handler) {
        this(name, subject, deliveryMode, queueGroup, handler, null, null);
    }

    public EventSubscription(String name, String subject, DeliveryMode deliveryMode,
                             String queueGroup, Consumer<String> handler,
                             BiConsumer<String, String> subjectHandler) {
        this(name, subject, deliveryMode, queueGroup, handler, subjectHandler, null);
    }

    /**
     * Which delivery of a message a {@link Redelivery} handler is processing.
     *
     * @param number      1 for the first delivery, counting redeliveries
     * @param maxAttempts the subscription's last attempt number
     */
    public record Attempt(long number, int maxAttempts) {
        /** True on the last delivery: a failure now is not redelivered. */
        public boolean isLast() {
            return number >= maxAttempts;
        }
    }

    /** A handler that sees the subject, the raw body and the delivery attempt. */
    @FunctionalInterface
    public interface AttemptHandler {
        void handle(String subject, String body, Attempt attempt);
    }

    /**
     * A subscription's own retry schedule.
     *
     * <p>A handler that throws is redelivered after {@code backoff.get(n - 1)} where {@code n} is
     * the attempt that failed, so the subscription makes {@code backoff.size() + 1} attempts in
     * all. On the last one ({@link Attempt#isLast()}) nothing redelivers it, so the handler should
     * settle the message itself — record it somewhere durable — and return normally.
     *
     * <p>A fetched batch is processed concurrently, one virtual thread per message, so one slow
     * message does not hold the rest of its batch past {@code ackWait}.
     *
     * @param backoff delay before each redelivery, in attempt order; at least one entry
     * @param ackWait how long one delivery may run before JetStream redelivers it anyway
     * @param handler processes one delivery
     */
    public record Redelivery(List<Duration> backoff, Duration ackWait, AttemptHandler handler) {
        public Redelivery {
            backoff = List.copyOf(Objects.requireNonNull(backoff, "backoff"));
            if (backoff.isEmpty()) {
                throw new IllegalArgumentException("a redelivery schedule needs at least one backoff");
            }
            for (Duration delay : backoff) {
                if (delay == null || delay.isNegative()) {
                    throw new IllegalArgumentException("backoff delays must be zero or positive: " + backoff);
                }
            }
            Objects.requireNonNull(ackWait, "ackWait");
            Objects.requireNonNull(handler, "handler");
        }

        /** Deliveries in all: the first plus one per backoff entry. */
        public int maxAttempts() {
            return backoff.size() + 1;
        }

        /** The delay before redelivering after {@code failedAttempt} failed (clamped to the schedule). */
        public Duration delayAfter(long failedAttempt) {
            int index = (int) Math.min(Math.max(failedAttempt, 1), backoff.size()) - 1;
            return backoff.get(index);
        }
    }

    /**
     * Determines how messages are delivered to subscribers.
     */
    public enum DeliveryMode {
        /**
         * Messages are load-balanced across instances sharing the same queue group.
         * Only one instance processes each message.
         */
        QUEUE_GROUP,

        /**
         * Every instance receives every message independently.
         * Used for cache invalidation and per-pod state refresh.
         */
        BROADCAST
    }

    /**
     * Creates a queue group subscription where messages are load-balanced across instances.
     *
     * @param name       subscription name (used for durable consumer naming)
     * @param subject    the messaging subject pattern (e.g., "kelta.record.changed.>")
     * @param queueGroup the queue group name for load balancing
     * @param handler    message handler receiving the raw JSON string
     * @return the subscription descriptor
     */
    public static EventSubscription queueGroup(String name, String subject,
                                                String queueGroup, Consumer<String> handler) {
        return new EventSubscription(name, subject, DeliveryMode.QUEUE_GROUP, queueGroup, handler);
    }

    /**
     * Creates a broadcast subscription where every instance receives every message.
     *
     * @param name    subscription name
     * @param subject the messaging subject pattern
     * @param handler message handler receiving the raw JSON string
     * @return the subscription descriptor
     */
    public static EventSubscription broadcast(String name, String subject, Consumer<String> handler) {
        return new EventSubscription(name, subject, DeliveryMode.BROADCAST, null, handler);
    }

    /**
     * Creates a queue group subscription whose handler also receives the
     * concrete subject each message arrived on.
     *
     * @param name       subscription name (used for durable consumer naming)
     * @param subject    the messaging subject pattern (e.g., "kelta.trigger.&gt;")
     * @param queueGroup the queue group name for load balancing
     * @param handler    handler receiving {@code (subject, rawBody)}
     * @return the subscription descriptor
     */
    public static EventSubscription queueGroupWithSubject(String name, String subject,
                                                          String queueGroup,
                                                          BiConsumer<String, String> handler) {
        return new EventSubscription(name, subject, DeliveryMode.QUEUE_GROUP, queueGroup, null, handler);
    }

    /**
     * Creates a queue group subscription that runs its own retry schedule: its handler receives
     * {@code (subject, rawBody, attempt)} and a failure is redelivered after
     * {@link Redelivery#delayAfter} instead of the default short NAK.
     *
     * @param name       subscription name (used for durable consumer naming)
     * @param subject    the messaging subject pattern
     * @param queueGroup the queue group name for load balancing
     * @param redelivery the retry schedule and its handler
     * @return the subscription descriptor
     */
    public static EventSubscription queueGroupWithRedelivery(String name, String subject,
                                                             String queueGroup, Redelivery redelivery) {
        return new EventSubscription(name, subject, DeliveryMode.QUEUE_GROUP, queueGroup, null, null,
                Objects.requireNonNull(redelivery, "redelivery"));
    }
}
