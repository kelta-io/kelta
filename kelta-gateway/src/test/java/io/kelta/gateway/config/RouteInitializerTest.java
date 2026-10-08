package io.kelta.gateway.config;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.health.RouteReadinessHealthIndicator;
import io.kelta.gateway.route.RouteRefresher;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.gateway.service.RouteConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RouteInitializer.
 *
 * <p>Tests verify that on startup:
 * <ul>
 *   <li>Tenant slug cache is primed via GatewayCacheManager</li>
 *   <li>Dynamic routes are fetched from the worker service</li>
 *   <li>The route cache is refreshed</li>
 *   <li>A failed bootstrap fetch is retried in the background until it succeeds</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RouteInitializerTest {

    @Mock
    private RouteRegistry routeRegistry;

    @Mock
    private RouteConfigService routeConfigService;

    @Mock
    private RouteRefresher routeRefresher;

    @Mock
    private GatewayCacheManager cacheManager;

    @Mock
    private ApplicationArguments applicationArguments;

    private RouteReadinessHealthIndicator routeReadiness;
    private RouteInitializer routeInitializer;

    @BeforeEach
    void setUp() {
        // Real indicator, not a mock — the point of these tests is that the
        // readiness flag actually flips.
        routeReadiness = new RouteReadinessHealthIndicator(routeRegistry);
        routeInitializer = new RouteInitializer(
            routeRegistry,
            routeConfigService,
            routeRefresher,
            cacheManager,
            routeReadiness
        );
    }

    @AfterEach
    void tearDown() {
        // Tests that make the bootstrap fail would otherwise leave a retry loop running.
        routeInitializer.shutdown();
    }

    private void useFastRetries() {
        ReflectionTestUtils.setField(routeInitializer, "retryInitialDelayMs", 10L);
        ReflectionTestUtils.setField(routeInitializer, "retryMaxDelayMs", 20L);
    }

    @Test
    void testGatewayIsNotReadyBeforeRoutesLoad() {
        // The web server is already accepting requests at this point; readiness
        // must not claim otherwise.
        assertFalse(routeReadiness.isReady());
        assertEquals(Status.DOWN, routeReadiness.health().getStatus());
    }

    @Test
    void testRun_MarksGatewayReady() {
        routeInitializer.run(applicationArguments);

        assertTrue(routeReadiness.isReady());
        assertEquals(Status.UP, routeReadiness.health().getStatus());
    }

    @Test
    void testRun_MarksReadyEvenWhenBootstrapFetchFails() {
        // Static routes are registered unconditionally, so a worker blip must not
        // leave this pod permanently out of the load balancer.
        doThrow(new IllegalStateException("worker unreachable"))
            .when(routeConfigService).refreshRoutes();

        routeInitializer.run(applicationArguments);

        assertTrue(routeReadiness.isReady());
    }

    @Test
    void testRun_CallsRefreshRoutes() {
        routeInitializer.run(applicationArguments);

        verify(routeConfigService).refreshRoutes();
    }

    @Test
    void testRun_PrimesTenantSlugCache() {
        routeInitializer.run(applicationArguments);

        verify(cacheManager).refreshTenantSlugsFromWorker();
    }

    @Test
    void testRun_RefreshesRoutes() {
        routeInitializer.run(applicationArguments);

        verify(routeRefresher).refresh();
    }

    @Test
    void testRun_ContinuesWhenSlugCacheFails() {
        doThrow(new RuntimeException("Redis down")).when(cacheManager).refreshTenantSlugsFromWorker();

        routeInitializer.run(applicationArguments);

        // Should still attempt to refresh routes
        verify(routeConfigService).refreshRoutes();
        verify(routeRefresher).refresh();
    }

    @Test
    void testRun_ContinuesWhenRouteRefreshFails() {
        doThrow(new RuntimeException("Worker down")).when(routeConfigService).refreshRoutes();

        routeInitializer.run(applicationArguments);

        // Should still publish refresh event
        verify(routeRefresher).refresh();
    }

    @Test
    void testRun_RetriesBootstrapUntilWorkerIsUp() {
        // The 2026-10-07 outage: the gateway started before the worker, the one
        // bootstrap fetch failed, and collection routes never loaded.
        useFastRetries();
        doThrow(new IllegalStateException("Connection refused"))
            .doThrow(new IllegalStateException("Connection refused"))
            .doNothing()
            .when(routeConfigService).refreshRoutes();

        routeInitializer.run(applicationArguments);

        // Startup fetch fails, retry 1 fails, retry 2 succeeds.
        verify(routeConfigService, timeout(2000).times(3)).refreshRoutes();
        // Once at startup, once when the retry finally loads the collection routes.
        verify(routeRefresher, timeout(2000).times(2)).refresh();
        // And it stops retrying after the success.
        verify(routeConfigService, after(200).times(3)).refreshRoutes();
        assertTrue(routeReadiness.isReady());
    }

    @Test
    void testRun_DoesNotRetryWhenBootstrapSucceeds() {
        useFastRetries();

        routeInitializer.run(applicationArguments);

        verify(routeConfigService, after(200).times(1)).refreshRoutes();
        verify(routeRefresher, times(1)).refresh();
    }
}
