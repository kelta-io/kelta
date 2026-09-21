package io.kelta.gateway.route;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RouteRefresher Tests")
class RouteRefresherTest {

    @Test
    @DisplayName("refresh publishes a RefreshRoutesEvent")
    void refreshPublishesEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        new RouteRefresher(publisher).refresh();

        verify(publisher).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    @DisplayName("concurrent refreshes are serialized, never interleaved")
    void concurrentRefreshesAreSerialized() throws Exception {
        // CachingRouteLocator's fetch-then-store runs inside publishEvent; two
        // interleaved refreshes lose the newer snapshot. Model publishEvent as a
        // slow handler and assert it never runs on two threads at once.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ApplicationEventPublisher publisher = event -> {
            assertInstanceOf(RefreshRoutesEvent.class, event);
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            firstEntered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "test released the first refresh");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
        };
        RouteRefresher refresher = new RouteRefresher(publisher);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(refresher::refresh);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "first refresh entered the handler");
            Future<?> second = pool.submit(refresher::refresh);

            // The second refresh must be blocked on the lock, not inside the handler.
            Thread.sleep(100);
            assertFalse(second.isDone());
            assertEquals(1, inFlight.get(), "second refresh must wait for the first");

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, maxInFlight.get(), "refreshes must never overlap");
    }
}
