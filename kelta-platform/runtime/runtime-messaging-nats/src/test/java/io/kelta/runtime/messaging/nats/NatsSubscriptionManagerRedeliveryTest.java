package io.kelta.runtime.messaging.nats;

import io.kelta.runtime.event.EventSubscription;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.impl.NatsJetStreamMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NatsSubscriptionManager — subscriptions with their own redelivery schedule")
class NatsSubscriptionManagerRedeliveryTest {

    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30));

    private NatsConnectionManager connectionManager;
    private NatsSubscriptionManager manager;
    private final List<EventSubscription.Attempt> attempts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        connectionManager = mock(NatsConnectionManager.class);
        manager = new NatsSubscriptionManager(connectionManager, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
    }

    private EventSubscription subscription(boolean fail) {
        return EventSubscription.queueGroupWithRedelivery("worker-module-events", "kelta.record.changed.>",
                "worker-module-events", new EventSubscription.Redelivery(BACKOFF, Duration.ofSeconds(60),
                        (subject, body, attempt) -> {
                            attempts.add(attempt);
                            if (fail) {
                                throw new IllegalStateException("module down");
                            }
                        }));
    }

    private static Message message(long delivered) {
        Message msg = mock(Message.class);
        when(msg.getData()).thenReturn("{\"tenantId\":\"t1\"}".getBytes(StandardCharsets.UTF_8));
        when(msg.getSubject()).thenReturn("kelta.record.changed.t1.orders");
        NatsJetStreamMetaData meta = mock(NatsJetStreamMetaData.class);
        when(meta.deliveredCount()).thenReturn(delivered);
        when(msg.metaData()).thenReturn(meta);
        return msg;
    }

    @Test
    @DisplayName("the durable consumer takes maxDeliver and ackWait from the schedule")
    void consumerConfigFollowsTheSchedule() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription jsSub = mock(JetStreamSubscription.class);
        when(connectionManager.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(anyString(), any(PullSubscribeOptions.class))).thenReturn(jsSub);
        lenient().when(jsSub.fetch(anyInt(), any())).thenReturn(List.of());

        assertThat(manager.tryStartSubscription(subscription(false), true)).isTrue();

        ArgumentCaptor<PullSubscribeOptions> options = ArgumentCaptor.forClass(PullSubscribeOptions.class);
        verify(jetStream).subscribe(anyString(), options.capture());
        assertThat(options.getValue().getConsumerConfiguration().getMaxDeliver()).isEqualTo(3);
        assertThat(options.getValue().getConsumerConfiguration().getAckWait()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("the handler sees its attempt number; a success is acked")
    void handlerSeesTheAttempt() {
        Message msg = message(2);

        manager.processPulled(subscription(false), msg);

        assertThat(attempts).containsExactly(new EventSubscription.Attempt(2, 3));
        assertThat(attempts.getFirst().isLast()).isFalse();
        verify(msg).ack();
        verify(msg, never()).nakWithDelay(any(Duration.class));
    }

    @Test
    @DisplayName("a failure is redelivered after that attempt's backoff, not the default NAK delay")
    void failureNaksWithTheAttemptsBackoff() {
        Message first = message(1);
        Message second = message(2);

        manager.processPulled(subscription(true), first);
        manager.processPulled(subscription(true), second);

        verify(first).nakWithDelay(Duration.ofSeconds(5));
        verify(second).nakWithDelay(Duration.ofSeconds(30));
        verify(first, never()).ack();
    }

    @Test
    @DisplayName("the last attempt is flagged, so the handler can settle it and return")
    void lastAttemptIsFlagged() {
        Message msg = message(3);

        manager.processPulled(subscription(false), msg);

        assertThat(attempts.getFirst().isLast()).isTrue();
        verify(msg).ack();
    }

    @Test
    @DisplayName("a pulled batch is processed concurrently: a slow delivery does not delay the next")
    void batchIsProcessedConcurrently() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription jsSub = mock(JetStreamSubscription.class);
        when(connectionManager.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(anyString(), any(PullSubscribeOptions.class))).thenReturn(jsSub);
        Message slow = message(1);
        when(slow.getSubject()).thenReturn("slow");
        Message fast = message(1);
        when(fast.getSubject()).thenReturn("fast");
        when(jsSub.fetch(anyInt(), any())).thenReturn(List.of(slow, fast)).thenReturn(List.of());
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        EventSubscription sub = EventSubscription.queueGroupWithRedelivery("worker-module-events",
                "kelta.record.changed.>", "worker-module-events",
                new EventSubscription.Redelivery(BACKOFF, Duration.ofSeconds(60), (subject, body, attempt) -> {
                    if ("slow".equals(subject)) {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }));

        assertThat(manager.tryStartSubscription(sub, true)).isTrue();

        verify(fast, org.mockito.Mockito.timeout(2_000)).ack();
        verify(slow, never()).ack();
        release.countDown();
        verify(slow, org.mockito.Mockito.timeout(2_000)).ack();
    }
}
