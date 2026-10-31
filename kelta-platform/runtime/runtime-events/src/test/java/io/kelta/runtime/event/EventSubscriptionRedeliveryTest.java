package io.kelta.runtime.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventSubscription.Redelivery")
class EventSubscriptionRedeliveryTest {

    private static final EventSubscription.AttemptHandler NOOP = (subject, body, attempt) -> { };

    @Test
    @DisplayName("one attempt per backoff entry plus the first; each failure waits its own delay")
    void scheduleArithmetic() {
        var redelivery = new EventSubscription.Redelivery(
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)),
                Duration.ofSeconds(60), NOOP);

        assertEquals(4, redelivery.maxAttempts());
        assertEquals(Duration.ofSeconds(5), redelivery.delayAfter(1));
        assertEquals(Duration.ofMinutes(2), redelivery.delayAfter(3));
        // Out-of-range attempts clamp to the schedule rather than throwing.
        assertEquals(Duration.ofSeconds(5), redelivery.delayAfter(0));
        assertEquals(Duration.ofMinutes(2), redelivery.delayAfter(9));
    }

    @Test
    @DisplayName("an empty or negative schedule is refused")
    void invalidSchedules() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventSubscription.Redelivery(List.of(), Duration.ofSeconds(1), NOOP));
        assertThrows(IllegalArgumentException.class,
                () -> new EventSubscription.Redelivery(List.of(Duration.ofSeconds(-1)), Duration.ofSeconds(1), NOOP));
    }

    @Test
    @DisplayName("the last attempt is the one numbered maxAttempts")
    void lastAttempt() {
        assertTrue(new EventSubscription.Attempt(3, 3).isLast());
        assertFalse(new EventSubscription.Attempt(2, 3).isLast());
    }

    @Test
    @DisplayName("a redelivery subscription is a queue group with neither plain handler")
    void factory() {
        var redelivery = new EventSubscription.Redelivery(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(1), NOOP);
        EventSubscription sub = EventSubscription.queueGroupWithRedelivery("n", "s.>", "g", redelivery);

        assertEquals(EventSubscription.DeliveryMode.QUEUE_GROUP, sub.deliveryMode());
        assertNull(sub.handler());
        assertNull(sub.subjectHandler());
        assertSame(redelivery, sub.redelivery());
        assertNull(EventSubscription.queueGroup("n", "s", "g", body -> { }).redelivery());
    }
}
