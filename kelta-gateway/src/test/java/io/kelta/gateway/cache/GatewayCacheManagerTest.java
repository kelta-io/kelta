package io.kelta.gateway.cache;

import io.kelta.gateway.config.GovernorLimitConfig;
import io.kelta.gateway.route.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayCacheManager.
 *
 * Tests verify both the tenant slug cache and governor limit cache functionality
 * backed by Caffeine caches.
 */
@ExtendWith(MockitoExtension.class)
class GatewayCacheManagerTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private GatewayCacheManager cacheManager;

    private static final String WORKER_SERVICE_URL = "http://localhost:8080";

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);

        cacheManager = new GatewayCacheManager(webClientBuilder, WORKER_SERVICE_URL);
    }

    // ── Tenant Slug Cache Tests ───────────────────────────────────────

    @Nested
    class TenantSlugCacheTests {

        @Test
        void resolveReturnsEmptyWhenCacheIsEmpty() {
            Optional<String> result = cacheManager.resolveTenantSlug("acme");
            assertThat(result).isEmpty();
        }

        @Test
        void isKnownSlugReturnsFalseWhenCacheIsEmpty() {
            assertThat(cacheManager.isKnownSlug("acme")).isFalse();
        }

        @Test
        void tenantSlugCacheSizeReturnsZeroWhenCacheIsEmpty() {
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(0);
        }

        @Test
        void resolveReturnsEmptyForNullSlug() {
            Optional<String> result = cacheManager.resolveTenantSlug(null);
            assertThat(result).isEmpty();
        }

        @Test
        void resolveReturnsEmptyForBlankSlug() {
            Optional<String> result = cacheManager.resolveTenantSlug("   ");
            assertThat(result).isEmpty();
        }

        @Test
        void isKnownSlugReturnsFalseForNullSlug() {
            assertThat(cacheManager.isKnownSlug(null)).isFalse();
        }

        @Test
        void resolveReturnsCorrectTenantIdAfterRefresh() {
            // Given
            Map<String, String> slugMap = Map.of(
                    "acme", "tenant-id-1",
                    "globex", "tenant-id-2"
            );
            stubRefreshResponse(Mono.just(slugMap));

            // When
            cacheManager.refreshTenantSlugsFromWorker();

            // Then
            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");
            assertThat(cacheManager.resolveTenantSlug("globex")).contains("tenant-id-2");
        }

        @Test
        void isKnownSlugReturnsTrueAfterRefresh() {
            // Given
            Map<String, String> slugMap = Map.of("acme", "tenant-id-1");
            stubRefreshResponse(Mono.just(slugMap));

            // When
            cacheManager.refreshTenantSlugsFromWorker();

            // Then
            assertThat(cacheManager.isKnownSlug("acme")).isTrue();
        }

        @Test
        void tenantSlugCacheSizeReflectsCacheContentsAfterRefresh() {
            // Given
            Map<String, String> slugMap = Map.of(
                    "acme", "tenant-id-1",
                    "globex", "tenant-id-2",
                    "initech", "tenant-id-3"
            );
            stubRefreshResponse(Mono.just(slugMap));

            // When
            cacheManager.refreshTenantSlugsFromWorker();

            // Then
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(3);
        }

        @Test
        void unknownSlugReturnsEmptyAfterRefresh() {
            // Given
            Map<String, String> slugMap = Map.of("acme", "tenant-id-1");
            stubRefreshResponse(Mono.just(slugMap));

            // When
            cacheManager.refreshTenantSlugsFromWorker();

            // Then
            assertThat(cacheManager.resolveTenantSlug("unknown")).isEmpty();
            assertThat(cacheManager.isKnownSlug("unknown")).isFalse();
        }

        @Test
        void refreshReplacesExistingCacheEntries() {
            // Given - first refresh with acme
            Map<String, String> firstMap = Map.of("acme", "tenant-id-1");
            stubRefreshResponse(Mono.just(firstMap));
            cacheManager.refreshTenantSlugsFromWorker();

            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");

            // When - second refresh with different data
            Map<String, String> secondMap = Map.of("globex", "tenant-id-2");
            stubRefreshResponse(Mono.just(secondMap));
            cacheManager.refreshTenantSlugsFromWorker();

            // Then - old entry is gone, new entry is present
            assertThat(cacheManager.resolveTenantSlug("acme")).isEmpty();
            assertThat(cacheManager.resolveTenantSlug("globex")).contains("tenant-id-2");
        }

        @Test
        void refreshHandlesErrorGracefully() {
            // Given
            stubRefreshResponse(Mono.error(new RuntimeException("Connection refused")));

            // When - should not throw
            cacheManager.refreshTenantSlugsFromWorker();

            // Then - cache remains empty
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(0);
            assertThat(cacheManager.resolveTenantSlug("acme")).isEmpty();
        }

        @Test
        void refreshKeepsExistingCacheOnError() {
            // Given - populate cache first
            Map<String, String> slugMap = Map.of("acme", "tenant-id-1");
            stubRefreshResponse(Mono.just(slugMap));
            cacheManager.refreshTenantSlugsFromWorker();

            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");

            // When - second refresh fails
            stubRefreshResponse(Mono.error(new RuntimeException("Connection refused")));
            cacheManager.refreshTenantSlugsFromWorker();

            // Then - existing cache is preserved
            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(1);
        }

        @Test
        void refreshKeepsExistingCacheOnNullResponse() {
            // Given - populate cache first
            Map<String, String> slugMap = Map.of("acme", "tenant-id-1");
            stubRefreshResponse(Mono.just(slugMap));
            cacheManager.refreshTenantSlugsFromWorker();

            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");

            // When - second refresh returns null
            stubRefreshResponse(Mono.justOrEmpty(null));
            cacheManager.refreshTenantSlugsFromWorker();

            // Then - existing cache is preserved
            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");
        }

        @Test
        void refreshKeepsExistingCacheOnEmptyMapResponse() {
            // Given - populate cache first
            Map<String, String> slugMap = Map.of("acme", "tenant-id-1");
            stubRefreshResponse(Mono.just(slugMap));
            cacheManager.refreshTenantSlugsFromWorker();

            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");

            // When - second refresh returns empty map
            stubRefreshResponse(Mono.just(Map.of()));
            cacheManager.refreshTenantSlugsFromWorker();

            // Then - existing cache is preserved
            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");
        }

        @Test
        void refreshTenantSlugsBulkLoadsEntries() {
            // Given
            Map<String, String> slugMap = Map.of(
                    "acme", "tenant-id-1",
                    "globex", "tenant-id-2"
            );

            // When
            cacheManager.refreshTenantSlugs(slugMap);

            // Then
            assertThat(cacheManager.resolveTenantSlug("acme")).contains("tenant-id-1");
            assertThat(cacheManager.resolveTenantSlug("globex")).contains("tenant-id-2");
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(2);
        }

        @Test
        void resolveLazilyFetchesFromWorkerOnCacheMiss() {
            // Cold cache (scheduled refresh hasn't run): a valid tenant must still resolve.
            stubRefreshResponse(Mono.just(Map.of("couchpicks", "tenant-cp")));

            assertThat(cacheManager.resolveTenantSlug("couchpicks")).contains("tenant-cp");

            // The lazy fetch warmed the cache — a second resolve hits it, no further worker call.
            reset(webClient);
            assertThat(cacheManager.resolveTenantSlug("couchpicks")).contains("tenant-cp");
            verifyNoInteractions(webClient);
        }

        @Test
        void resolveNegativeCachesUnknownSlugAfterLazyFetch() {
            stubRefreshResponse(Mono.just(Map.of("acme", "tenant-id-1")));

            assertThat(cacheManager.resolveTenantSlug("ghost")).isEmpty();

            // A genuinely-unknown slug is negative-cached — no repeat worker fetch.
            reset(webClient);
            assertThat(cacheManager.resolveTenantSlug("ghost")).isEmpty();
            verifyNoInteractions(webClient);
        }

        @Test
        void resolveDoesNotPinValidTenantToNotFoundOnFetchError() {
            // A transient worker error must NOT poison a valid tenant to "not found".
            stubRefreshResponse(Mono.error(new RuntimeException("worker down")));
            assertThat(cacheManager.resolveTenantSlug("couchpicks")).isEmpty();

            // Worker recovers — the retry resolves.
            stubRefreshResponse(Mono.just(Map.of("couchpicks", "tenant-cp")));
            assertThat(cacheManager.resolveTenantSlug("couchpicks")).contains("tenant-cp");
        }

        @Test
        void isKnownSlugLazilyResolvesOnCacheMiss() {
            stubRefreshResponse(Mono.just(Map.of("acme", "tenant-id-1")));
            assertThat(cacheManager.isKnownSlug("acme")).isTrue();
        }

        @Test
        void refreshNeverLeavesAnUnchangedSlugUnresolvable() throws Exception {
            // #1334: invalidateAll() + putAll() opened a window in which a live tenant 404'd.
            // Deliberately NO worker stub: the lazy fetch must not paper over the window, or this
            // test passes against the very bug it exists to catch.
            Map<String, String> slugMap = Map.of("acme", "tenant-id-1", "globex", "tenant-id-2");
            cacheManager.refreshTenantSlugs(slugMap);

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicInteger misses = new AtomicInteger();
            // A miss here is exactly what becomes a TENANT_NOT_FOUND response.
            Thread reader = new Thread(() -> {
                while (running.get()) {
                    if (cacheManager.resolveTenantSlug("acme").isEmpty()) {
                        misses.incrementAndGet();
                    }
                }
            });
            reader.start();
            try {
                for (int i = 0; i < 2_000; i++) {
                    cacheManager.refreshTenantSlugs(slugMap);
                }
            } finally {
                running.set(false);
                reader.join(5_000);
            }

            assertThat(misses.get())
                    .as("a slug present before and after the refresh must never miss")
                    .isZero();
        }

        @Test
        void refreshStillDropsSlugsThatDisappeared() {
            cacheManager.refreshTenantSlugs(Map.of("acme", "tenant-id-1", "globex", "tenant-id-2"));

            // acme was deleted/renamed upstream — it must stop resolving.
            cacheManager.refreshTenantSlugs(Map.of("globex", "tenant-id-2"));

            stubRefreshResponse(Mono.just(Map.of("globex", "tenant-id-2")));
            assertThat(cacheManager.resolveTenantSlug("acme")).isEmpty();
            assertThat(cacheManager.resolveTenantSlug("globex")).contains("tenant-id-2");
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(2); // globex + negative acme
        }

        @Test
        void resolveOnANonBlockingThreadAnswersFromCacheInsteadOfThrowing() {
            // The blocking variant used to be called from the reactive filter chain, where
            // block() throws — the caught exception became a 404 for a valid tenant (#1334).
            cacheManager.refreshTenantSlugs(Map.of("acme", "tenant-id-1"));

            Optional<String> hit = Mono.fromSupplier(() -> cacheManager.resolveTenantSlug("acme"))
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(5));
            assertThat(hit).contains("tenant-id-1");
        }

        @Test
        void reactiveResolveWorksOnANonBlockingThread() {
            stubRefreshResponse(Mono.just(Map.of("couchpicks", "tenant-cp")));

            Optional<String> resolved = Mono.defer(() -> cacheManager.resolveTenantSlugReactive("couchpicks"))
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(5));

            assertThat(resolved)
                    .as("the lazy lookup must run on a reactive thread, not blow up on block()")
                    .contains("tenant-cp");
        }

        @Test
        void concurrentMissesShareOneWorkerFetch() {
            AtomicInteger fetches = new AtomicInteger();
            stubRefreshResponse(Mono.fromSupplier(() -> {
                fetches.incrementAndGet();
                return Map.of("acme", "tenant-id-1");
            }).delayElement(Duration.ofMillis(50)));

            List<Mono<Optional<String>>> lookups = IntStream.range(0, 8)
                    .mapToObj(i -> cacheManager.resolveTenantSlugReactive("acme")
                            .subscribeOn(Schedulers.parallel()))
                    .toList();
            List<Optional<String>> results = Flux.merge(lookups).collectList().block(Duration.ofSeconds(5));

            assertThat(results).allSatisfy(r -> assertThat(r).contains("tenant-id-1"));
            assertThat(fetches.get())
                    .as("a cold cache must not stampede the worker")
                    .isEqualTo(1);
        }
    }

    // ── Tenant ID → Slug Lookup Tests ─────────────────────────────────

    @Nested
    class TenantIdToSlugTests {

        @Test
        void resolvesIdFromTheCachedSlugMapWithoutCallingTheWorker() {
            cacheManager.refreshTenantSlugs(Map.of("acme", "tenant-id-1", "globex", "tenant-id-2"));

            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-id-2").block())
                    .contains("globex");
            verifyNoInteractions(webClient);
        }

        @Test
        void lazilyFetchesTheSlugMapOnACacheMiss() {
            stubRefreshResponse(Mono.just(Map.of("couchpicks", "tenant-cp")));

            Optional<String> resolved = Mono.defer(() -> cacheManager.resolveTenantIdToSlugReactive("tenant-cp"))
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(5));
            assertThat(resolved).contains("couchpicks");

            // The fetch warmed the shared slug cache for both directions.
            reset(webClient);
            assertThat(cacheManager.resolveTenantSlug("couchpicks")).contains("tenant-cp");
            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-cp").block()).contains("couchpicks");
            verifyNoInteractions(webClient);
        }

        @Test
        void unknownIdResolvesEmptyAndAddsNoSlugEntry() {
            stubRefreshResponse(Mono.just(Map.of("acme", "tenant-id-1")));

            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-ghost").block()).isEmpty();
            assertThat(cacheManager.tenantSlugCacheSize()).isEqualTo(1);

            // Remembered briefly, so a stream of unknown ids is not a worker fetch per request.
            reset(webClient);
            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-ghost").block()).isEmpty();
            verifyNoInteractions(webClient);
        }

        @Test
        void unknownIdResolvesOnceASlugRefreshKnowsIt() {
            stubRefreshResponse(Mono.just(Map.of("acme", "tenant-id-1")));
            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-new").block()).isEmpty();

            cacheManager.refreshTenantSlugs(Map.of("acme", "tenant-id-1", "fresh", "tenant-new"));

            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-new").block()).contains("fresh");
        }

        @Test
        void fetchErrorResolvesEmptyAndRetriesNextTime() {
            stubRefreshResponse(Mono.error(new RuntimeException("worker down")));
            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-cp").block()).isEmpty();

            stubRefreshResponse(Mono.just(Map.of("couchpicks", "tenant-cp")));
            assertThat(cacheManager.resolveTenantIdToSlugReactive("tenant-cp").block()).contains("couchpicks");
        }

        @Test
        void blankIdResolvesEmpty() {
            assertThat(cacheManager.resolveTenantIdToSlugReactive(null).block()).isEmpty();
            assertThat(cacheManager.resolveTenantIdToSlugReactive(" ").block()).isEmpty();
            verifyNoInteractions(webClient);
        }
    }

    // ── Governor Limit Cache Tests ────────────────────────────────────

    @Nested
    class GovernorLimitCacheTests {

        @Test
        void testLoadGovernorLimits() {
            // Given
            Map<String, GovernorLimitConfig> limits = new HashMap<>();
            limits.put("tenant-1", new GovernorLimitConfig(100_000));
            limits.put("tenant-2", new GovernorLimitConfig(50_000));

            // When
            cacheManager.loadGovernorLimits(limits);

            // Then
            assertThat(cacheManager.governorLimitCacheSize()).isEqualTo(2);
            assertEquals(Optional.of(100_000), cacheManager.getGovernorLimit("tenant-1"));
            assertEquals(Optional.of(50_000), cacheManager.getGovernorLimit("tenant-2"));
        }

        @Test
        void testLoadGovernorLimits_NullMap() {
            // When
            cacheManager.loadGovernorLimits(null);

            // Then
            assertThat(cacheManager.governorLimitCacheSize()).isEqualTo(0);
        }

        @Test
        void testLoadGovernorLimits_EmptyMap() {
            // When
            cacheManager.loadGovernorLimits(new HashMap<>());

            // Then
            assertThat(cacheManager.governorLimitCacheSize()).isEqualTo(0);
        }

        @Test
        void testUpdateGovernorLimit() {
            // When
            cacheManager.updateGovernorLimit("tenant-1", 200_000);

            // Then
            assertEquals(Optional.of(200_000), cacheManager.getGovernorLimit("tenant-1"));
        }

        @Test
        void testGetRateLimitForTenant_Known() {
            // Given
            cacheManager.updateGovernorLimit("tenant-1", 144_000); // (144,000 / 288) * 5 = 2500 per 5-min window

            // When
            RateLimitConfig config = cacheManager.getRateLimitForTenant("tenant-1");

            // Then
            assertEquals(2500, config.getRequestsPerWindow());
            assertEquals(Duration.ofMinutes(5), config.getWindowDuration());
        }

        @Test
        void testGetRateLimitForTenant_Unknown_UsesDefault() {
            // When - tenant not in cache, should use default (100,000/day)
            RateLimitConfig config = cacheManager.getRateLimitForTenant("unknown-tenant");

            // Then - (100,000 / 288) * 5 = 1735 per 5-min window
            assertEquals(1735, config.getRequestsPerWindow());
            assertEquals(Duration.ofMinutes(5), config.getWindowDuration());
        }

        @Test
        void testGetRateLimitForTenant_MinimumOnePerWindow() {
            // Given - very low limit: 1 per day
            cacheManager.updateGovernorLimit("low-tenant", 1);

            // When
            RateLimitConfig config = cacheManager.getRateLimitForTenant("low-tenant");

            // Then - minimum 1 request per window
            assertEquals(1, config.getRequestsPerWindow());
        }

        @Test
        void testGetGovernorLimit_NotFound() {
            // When
            Optional<Integer> result = cacheManager.getGovernorLimit("nonexistent");

            // Then
            assertTrue(result.isEmpty());
        }

        @Test
        void testLoadGovernorLimits_ClearsExisting() {
            // Given - pre-populate cache
            cacheManager.updateGovernorLimit("old-tenant", 50_000);
            assertThat(cacheManager.governorLimitCacheSize()).isEqualTo(1);

            // When - load new data
            Map<String, GovernorLimitConfig> limits = new HashMap<>();
            limits.put("new-tenant", new GovernorLimitConfig(100_000));
            cacheManager.loadGovernorLimits(limits);

            // Then - old data cleared, new data loaded
            assertThat(cacheManager.governorLimitCacheSize()).isEqualTo(1);
            assertTrue(cacheManager.getGovernorLimit("old-tenant").isEmpty());
            assertEquals(Optional.of(100_000), cacheManager.getGovernorLimit("new-tenant"));
        }
    }

    // ── Governor Limit Refresh From Worker Tests ────────────────────

    @Nested
    class GovernorLimitRefreshFromWorkerTests {

        @Test
        void refreshGovernorLimitsFromWorkerUpdatesCache() {
            // Given
            Map<String, Integer> limitsMap = Map.of(
                    "tenant-1", 10_000_000,
                    "tenant-2", 50_000
            );
            stubGovernorLimitsRefreshResponse(Mono.just(limitsMap));

            // When
            cacheManager.refreshGovernorLimitsFromWorker();

            // Then
            assertEquals(Optional.of(10_000_000), cacheManager.getGovernorLimit("tenant-1"));
            assertEquals(Optional.of(50_000), cacheManager.getGovernorLimit("tenant-2"));
            assertThat(cacheManager.governorLimitCacheSize()).isEqualTo(2);
        }

        @Test
        void refreshGovernorLimitsFromWorkerReplacesExistingCache() {
            // Given - pre-populate
            cacheManager.updateGovernorLimit("old-tenant", 100_000);
            assertThat(cacheManager.getGovernorLimit("old-tenant")).contains(100_000);

            Map<String, Integer> limitsMap = Map.of("new-tenant", 200_000);
            stubGovernorLimitsRefreshResponse(Mono.just(limitsMap));

            // When
            cacheManager.refreshGovernorLimitsFromWorker();

            // Then
            assertTrue(cacheManager.getGovernorLimit("old-tenant").isEmpty());
            assertEquals(Optional.of(200_000), cacheManager.getGovernorLimit("new-tenant"));
        }

        @Test
        void refreshGovernorLimitsFromWorkerHandlesErrorGracefully() {
            // Given - pre-populate cache
            cacheManager.updateGovernorLimit("tenant-1", 100_000);

            stubGovernorLimitsRefreshResponse(Mono.error(new RuntimeException("Connection refused")));

            // When - should not throw
            cacheManager.refreshGovernorLimitsFromWorker();

            // Then - existing cache is preserved
            assertEquals(Optional.of(100_000), cacheManager.getGovernorLimit("tenant-1"));
        }

        @Test
        void refreshGovernorLimitsFromWorkerKeepsCacheOnEmptyResponse() {
            // Given - pre-populate cache
            cacheManager.updateGovernorLimit("tenant-1", 100_000);

            stubGovernorLimitsRefreshResponse(Mono.just(Map.of()));

            // When
            cacheManager.refreshGovernorLimitsFromWorker();

            // Then - existing cache is preserved
            assertEquals(Optional.of(100_000), cacheManager.getGovernorLimit("tenant-1"));
        }
    }

    // ── Custom Domain Cache Tests ────────────────────────────────────

    @Nested
    class CustomDomainCacheTests {

        @Test
        void resolveCustomDomain_cachesPositiveResult() {
            // Given - stub a successful worker response
            stubCustomDomainResolveResponse("app.acme.com", Mono.just("acme"));

            // When - first lookup hits worker
            Optional<String> first = cacheManager.resolveCustomDomain("app.acme.com");

            // Then
            assertThat(first).contains("acme");
        }

        @Test
        void resolveCustomDomain_cachesNegativeResult() {
            // Given - stub a 404 from worker (WebClient throws on non-2xx)
            stubCustomDomainResolveResponse("unknown.com",
                    Mono.error(new org.springframework.web.reactive.function.client.WebClientResponseException(
                            404, "Not Found", null, null, null)));

            // When - first lookup hits worker, returns empty
            Optional<String> first = cacheManager.resolveCustomDomain("unknown.com");
            assertThat(first).isEmpty();

            // Second lookup should NOT call worker again (cached negative)
            // Reset mock interactions
            reset(webClient);

            Optional<String> second = cacheManager.resolveCustomDomain("unknown.com");
            assertThat(second).isEmpty();

            // Verify no more interactions with webClient
            verifyNoInteractions(webClient);
        }

        @Test
        void resolveCustomDomain_doesNotNegativeCacheOnTransportFailure() {
            // #1334: a timeout/connection failure is not an answer. Negative-caching it black-holed
            // a live custom domain for the 10-minute cache TTL.
            stubCustomDomainResolveResponse("app.acme.com",
                    Mono.error(new RuntimeException("connection reset")));
            assertThat(cacheManager.resolveCustomDomain("app.acme.com")).isEmpty();

            // Worker recovers — the very next request resolves.
            stubCustomDomainResolveResponse("app.acme.com", Mono.just("acme"));
            assertThat(cacheManager.resolveCustomDomain("app.acme.com")).contains("acme");
        }

        @Test
        void resolveCustomDomain_worksOnANonBlockingThread() {
            stubCustomDomainResolveResponse("app.acme.com", Mono.just("acme"));

            Optional<String> resolved = Mono.defer(() -> cacheManager.resolveCustomDomainReactive("app.acme.com"))
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(5));

            assertThat(resolved)
                    .as("the reactive filter chain must be able to resolve a custom domain")
                    .contains("acme");
        }

        @Test
        void registerCustomDomain_addsToCacheDirectly() {
            cacheManager.registerCustomDomain("app.acme.com", "acme");

            Optional<String> result = cacheManager.resolveCustomDomain("app.acme.com");
            assertThat(result).contains("acme");
            verifyNoInteractions(webClient);
        }

        @Test
        void removeCustomDomain_evictsFromCache() {
            cacheManager.registerCustomDomain("app.acme.com", "acme");
            cacheManager.removeCustomDomain("app.acme.com");

            // Next resolve should try the worker again (entry was evicted)
            stubCustomDomainResolveResponse("app.acme.com", Mono.just("acme-new"));

            Optional<String> result = cacheManager.resolveCustomDomain("app.acme.com");
            assertThat(result).contains("acme-new");
        }

        @Test
        void evictAllCustomDomains_clearsAllEntries() {
            cacheManager.registerCustomDomain("app.acme.com", "acme");
            cacheManager.registerCustomDomain("app.beta.com", "beta");
            cacheManager.evictAllCustomDomains();

            // Both should require worker lookup now
            stubCustomDomainResolveResponse("app.acme.com", Mono.just("acme"));
            Optional<String> result = cacheManager.resolveCustomDomain("app.acme.com");
            assertThat(result).contains("acme");
            verify(webClient).get(); // Confirms worker was called
        }

        @SuppressWarnings("unchecked")
        private void stubCustomDomainResolveResponse(String domain, Mono<String> response) {
            when(webClient.get()).thenReturn(requestHeadersUriSpec);
            when(requestHeadersUriSpec.uri("/internal/domains/resolve?domain={domain}", domain))
                    .thenReturn(requestHeadersSpec);
            when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(String.class)).thenReturn(response);
        }
    }

    // ── Guest Profile Cache Tests ────────────────────────────────────

    @Nested
    class GuestProfileCacheTests {

        @Test
        void resolveGuestProfileReactive_cachesPositiveResult() {
            stubGuestProfileResponse("tenant-1", Mono.just("guest-profile-id"));

            Optional<String> resolved = cacheManager.resolveGuestProfileReactive("tenant-1")
                    .block(Duration.ofSeconds(5));

            assertThat(resolved).contains("guest-profile-id");
        }

        @Test
        void resolveGuestProfileReactive_cachesNegativeResultAndDoesNotRefetch() {
            stubGuestProfileResponse("tenant-1",
                    Mono.error(new org.springframework.web.reactive.function.client.WebClientResponseException(
                            404, "Not Found", null, null, null)));

            Optional<String> first = cacheManager.resolveGuestProfileReactive("tenant-1")
                    .block(Duration.ofSeconds(5));
            assertThat(first).isEmpty();

            reset(webClient);
            Optional<String> second = cacheManager.resolveGuestProfileReactive("tenant-1")
                    .block(Duration.ofSeconds(5));
            assertThat(second).isEmpty();
            verifyNoInteractions(webClient);
        }

        @Test
        void resolveGuestProfileReactive_doesNotNegativeCacheOnTransportFailure() {
            stubGuestProfileResponse("tenant-1", Mono.error(new RuntimeException("connection reset")));
            assertThat(cacheManager.resolveGuestProfileReactive("tenant-1").block(Duration.ofSeconds(5)))
                    .isEmpty();

            stubGuestProfileResponse("tenant-1", Mono.just("guest-profile-id"));
            assertThat(cacheManager.resolveGuestProfileReactive("tenant-1").block(Duration.ofSeconds(5)))
                    .contains("guest-profile-id");
        }

        @Test
        void resolveGuestProfileReactive_blankTenantIdIsEmptyWithoutCallingWorker() {
            assertThat(cacheManager.resolveGuestProfileReactive("").block(Duration.ofSeconds(5))).isEmpty();
            assertThat(cacheManager.resolveGuestProfileReactive(null).block(Duration.ofSeconds(5))).isEmpty();
            verifyNoInteractions(webClient);
        }

        @SuppressWarnings("unchecked")
        private void stubGuestProfileResponse(String tenantId, Mono<String> response) {
            when(webClient.get()).thenReturn(requestHeadersUriSpec);
            when(requestHeadersUriSpec.uri("/internal/tenants/{tenantId}/guest-profile", tenantId))
                    .thenReturn(requestHeadersSpec);
            when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(String.class)).thenReturn(response);
        }
    }

    // ── System Collection Response Cache Tests ─────────────────────────

    @Nested
    class SystemCollectionResponseCacheTests {

        @Test
        void getSystemCollectionResponse_returnsEmptyWhenNotCached() {
            assertThat(cacheManager.getSystemCollectionResponse("t1:/api/collections")).isEmpty();
        }

        @Test
        void putAndGetSystemCollectionResponse_returnsCachedValue() {
            byte[] body = "{\"data\":[]}".getBytes();
            cacheManager.putSystemCollectionResponse("t1:/api/collections", body);

            Optional<byte[]> result = cacheManager.getSystemCollectionResponse("t1:/api/collections");
            assertThat(result).isPresent();
            assertThat(new String(result.get())).isEqualTo("{\"data\":[]}");
        }

        @Test
        void evictSystemCollectionResponses_removesMatchingEntries() {
            cacheManager.putSystemCollectionResponse("t1:/api/collections", "{}".getBytes());
            cacheManager.putSystemCollectionResponse("t1:/api/collections?page[number]=2", "{}".getBytes());
            cacheManager.putSystemCollectionResponse("t1:/api/ui-pages", "{}".getBytes());

            cacheManager.evictSystemCollectionResponses("collections");

            assertThat(cacheManager.getSystemCollectionResponse("t1:/api/collections")).isEmpty();
            assertThat(cacheManager.getSystemCollectionResponse("t1:/api/collections?page[number]=2")).isEmpty();
            // ui-pages should not be affected
            assertThat(cacheManager.getSystemCollectionResponse("t1:/api/ui-pages")).isPresent();
        }

        @Test
        void evictAllSystemCollectionResponses_clearsAllEntries() {
            cacheManager.putSystemCollectionResponse("t1:/api/collections", "{}".getBytes());
            cacheManager.putSystemCollectionResponse("t2:/api/ui-pages", "{}".getBytes());

            cacheManager.evictAllSystemCollectionResponses();

            assertThat(cacheManager.getSystemCollectionResponse("t1:/api/collections")).isEmpty();
            assertThat(cacheManager.getSystemCollectionResponse("t2:/api/ui-pages")).isEmpty();
        }

        @Test
        void systemCollectionResponseCacheSize_reflectsEntries() {
            assertThat(cacheManager.systemCollectionResponseCacheSize()).isEqualTo(0);

            cacheManager.putSystemCollectionResponse("t1:/api/collections", "{}".getBytes());
            cacheManager.putSystemCollectionResponse("t1:/api/ui-pages", "{}".getBytes());

            assertThat(cacheManager.systemCollectionResponseCacheSize()).isEqualTo(2);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubRefreshResponse(Mono<Map<String, String>> response) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("/internal/tenants/slug-map")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void stubGovernorLimitsRefreshResponse(Mono<Map<String, Integer>> response) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("/internal/governor-limits")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(response);
    }
}
