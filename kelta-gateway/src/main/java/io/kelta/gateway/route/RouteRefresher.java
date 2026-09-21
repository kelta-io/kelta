package io.kelta.gateway.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The single place that asks Spring Cloud Gateway to rebuild its route cache.
 *
 * <p>{@code CachingRouteLocator} handles a {@link RefreshRoutesEvent} in two steps:
 * it fetches the current routes from the {@link DynamicRouteLocator} and then stores
 * that snapshot under a lock. Only the store is synchronized, so two refreshes on
 * different threads can interleave as a lost update: thread A snapshots the registry
 * <em>before</em> thread B registers a new collection route, B snapshots and stores,
 * then A stores its stale snapshot on top. The new collection is then unroutable
 * (404 "No static resource api/&lt;name&gt;") until the next config event arrives.
 *
 * <p>That is exactly the shape of a collection create: the worker publishes both a
 * {@code kelta.config.collection.changed} event (handled by
 * {@code ConfigEventListener}, which registers the route and refreshes) and a
 * {@code kelta.record.changed} event for the {@code collections} system collection
 * (handled by {@code SystemCollectionRouteListener}, which refreshes). They arrive on
 * two NATS consumer threads and raced in CI.
 *
 * <p>The event multicaster is synchronous and the locator is in-memory, so the whole
 * fetch-and-store runs inside {@code publishEvent} on the calling thread. Serializing
 * the publish here therefore serializes the entire refresh, and every refresh sees a
 * registry at least as new as the one the previous refresh saw. Route it all through
 * {@link #refresh()}; never publish {@link RefreshRoutesEvent} directly.
 */
@Component
public class RouteRefresher {

    private static final Logger logger = LoggerFactory.getLogger(RouteRefresher.class);

    private final ApplicationEventPublisher eventPublisher;

    public RouteRefresher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Rebuilds the gateway's route cache from the current {@link RouteRegistry}.
     * Callers must have finished mutating the registry before calling this.
     */
    public synchronized void refresh() {
        logger.debug("Publishing RefreshRoutesEvent");
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
