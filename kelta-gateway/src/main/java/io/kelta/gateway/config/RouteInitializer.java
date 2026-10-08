package io.kelta.gateway.config;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.health.RouteReadinessHealthIndicator;
import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRefresher;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.gateway.service.RouteConfigService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Initializes routes on application startup.
 *
 * <p>This component:
 * <ol>
 *   <li>Primes the tenant slug cache from the worker service</li>
 *   <li>Fetches dynamic routes from the worker's internal bootstrap endpoint</li>
 *   <li>Refreshes the Spring Cloud Gateway route cache via {@link RouteRefresher}</li>
 *   <li>If the bootstrap fetch failed, keeps retrying it in the background with
 *       exponential backoff until it succeeds</li>
 * </ol>
 *
 * <p><b>Why the retry.</b> Collection routes arrive only from the bootstrap fetch;
 * NATS events carry changes, not the full table. When the gateway started before
 * the worker (2026-10-07, after a node reboot) the one fetch got "connection
 * refused", the gateway went READY with only its static routes, and every tenant
 * collection API answered 404 for ~7 hours until the pod was restarted by hand.
 *
 * <p>All collections are routed to the worker service.
 */
@Component
public class RouteInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(RouteInitializer.class);

    private final RouteRegistry routeRegistry;
    private final RouteConfigService routeConfigService;
    private final RouteRefresher routeRefresher;
    private final GatewayCacheManager cacheManager;
    private final RouteReadinessHealthIndicator routeReadiness;

    @Value("${kelta.gateway.ai-service-url:}")
    private String aiServiceUrl;

    @Value("${OTEL_COLLECTOR_URL:http://alloy-collector.observability.svc.cluster.local:4318}")
    private String otelCollectorUrl;

    @Value("${kelta.gateway.bootstrap-retry.initial-delay-ms:2000}")
    private long retryInitialDelayMs = 2000;

    @Value("${kelta.gateway.bootstrap-retry.max-delay-ms:30000}")
    private long retryMaxDelayMs = 30000;

    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "route-bootstrap-retry");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Creates a new RouteInitializer.
     *
     * @param routeRegistry      The route registry to populate
     * @param routeConfigService Service for fetching routes from the worker
     * @param routeRefresher     Serialized route-cache refresh
     * @param cacheManager       Gateway cache manager to prime on startup
     * @param routeReadiness     Readiness gate flipped once routes are loaded
     */
    public RouteInitializer(
            RouteRegistry routeRegistry,
            RouteConfigService routeConfigService,
            RouteRefresher routeRefresher,
            GatewayCacheManager cacheManager,
            RouteReadinessHealthIndicator routeReadiness) {
        this.routeRegistry = routeRegistry;
        this.routeConfigService = routeConfigService;
        this.routeRefresher = routeRefresher;
        this.cacheManager = cacheManager;
        this.routeReadiness = routeReadiness;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("Initializing gateway routes");

        // Prime the tenant slug cache before route loading
        try {
            cacheManager.refreshTenantSlugsFromWorker();
        } catch (Exception e) {
            logger.warn("Failed to prime tenant slug cache on startup; will retry on next cache refresh: {}", e.getMessage());
        }

        // Register static routes for auxiliary services
        registerStaticRoutes();

        // Fetch and add dynamic routes from the worker service
        boolean bootstrapLoaded = true;
        try {
            routeConfigService.refreshRoutes();
        } catch (Exception e) {
            bootstrapLoaded = false;
            logger.error("Failed to load routes from worker on startup: {}", e.getMessage(), e);
        }

        // Trigger Spring Cloud Gateway to refresh its route cache
        logger.info("Refreshing Gateway route cache");
        routeRefresher.refresh();

        // Only now is the gateway able to route /api/** — the web server has been
        // accepting requests since before this runner started. Flip readiness last,
        // and flip it even if the bootstrap fetch failed above: static routes are
        // registered unconditionally, so the gateway is still useful, and staying
        // unready over a transient worker blip would turn it into an outage. The
        // collection routes are filled in by scheduleBootstrapRetry below.
        routeReadiness.markRoutesInitialized();

        logger.info("Route initialization completed with {} routes; gateway is now READY",
                routeRegistry.size());

        if (!bootstrapLoaded) {
            scheduleBootstrapRetry(1, retryInitialDelayMs);
        }
    }

    /**
     * Retries the bootstrap fetch until it succeeds, doubling the delay each time up
     * to {@code kelta.gateway.bootstrap-retry.max-delay-ms}. Readiness is untouched:
     * the pod is already serving its static routes, and the collection routes join
     * the table the moment the worker answers.
     */
    private void scheduleBootstrapRetry(int attempt, long delayMs) {
        logger.warn("Collection routes not loaded; retrying bootstrap fetch in {} ms (attempt {})",
                delayMs, attempt);
        retryScheduler.schedule(() -> {
            try {
                routeConfigService.refreshRoutes();
            } catch (Exception e) {
                logger.error("Bootstrap retry {} failed: {}", attempt, e.getMessage());
                scheduleBootstrapRetry(attempt + 1, Math.min(delayMs * 2, retryMaxDelayMs));
                return;
            }
            routeRefresher.refresh();
            logger.info("Bootstrap retry {} succeeded; gateway now has {} routes",
                    attempt, routeRegistry.size());
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        retryScheduler.shutdownNow();
    }

    /**
     * Registers static routes for auxiliary services (e.g., AI service).
     * These routes are not fetched from the worker's bootstrap endpoint.
     */
    private void registerStaticRoutes() {
        if (aiServiceUrl != null && !aiServiceUrl.isBlank()) {
            routeRegistry.addRoute(new RouteDefinition(
                    "static-ai", "/api/ai/**", aiServiceUrl, "ai"));
            logger.info("Registered AI service route: /api/ai/** -> {}", aiServiceUrl);
        }

        // OTEL traces route: strips /otel prefix before forwarding to the collector
        routeRegistry.addRoute(new RouteDefinition(
                "otel-traces", "/otel/v1/traces", otelCollectorUrl, "otel",
                null, 1));
        logger.info("Registered OTEL traces route: /otel/v1/traces -> {}", otelCollectorUrl);
    }
}
