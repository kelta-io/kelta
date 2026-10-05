package io.kelta.gateway.authz.cerbos;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CerbosException;
import dev.cerbos.sdk.CheckResult;
import dev.cerbos.sdk.builders.AttributeValue;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import io.grpc.Status;
import io.kelta.gateway.auth.GatewayPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps Cerbos SDK calls with caching, hard timeouts, thread interruption,
 * and a circuit breaker to prevent thread-pool exhaustion.
 *
 * <p>Authorization results are cached in-memory (Caffeine) and invalidated
 * via NATS events when Cerbos policies change. A safety-net TTL of 10 minutes
 * handles edge cases like missed events.
 *
 * <h3>Fail-closed design</h3>
 * <p>All failures (timeouts, errors, circuit breaker open) result in deny.
 * Only successful Cerbos responses that return ALLOW are cached as {@code true}.
 *
 * <h3>Transient-failure retry</h3>
 * <p>A check that fails with {@code DEADLINE_EXCEEDED} / {@code UNAVAILABLE} (or the local
 * timeout) resets the channel's transport and is retried once — but only when the previous
 * check succeeded, so during an outage at most the first request of a failure streak pays
 * the second attempt. A second failure still denies.
 */
@Service
public class CerbosAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(CerbosAuthorizationService.class);

    /** Maximum time (seconds) to wait for a single Cerbos gRPC call. */
    private static final long CERBOS_TIMEOUT_SECONDS = 2;

    /** Consecutive failures before the circuit breaker opens. */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;

    /** Seconds to keep the circuit open (deny all) before retrying. */
    private static final long CIRCUIT_BREAKER_COOLDOWN_SECONDS = 10;

    /** Safety-net TTL for cache entries (primary invalidation is event-driven). */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final int CACHE_MAX_SIZE = 10_000;

    private final CerbosBlockingClient cerbosClient;
    private final CerbosChannel cerbosChannel;
    private final ExecutorService cerbosExecutor;

    // Cache: key = "tenantId:profileId:permission" or "tenantId:profileId:collectionId:action"
    private final Cache<String, Boolean> permissionCache;

    // Metrics
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheEvictions;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntil = new AtomicLong(0);

    public CerbosAuthorizationService(CerbosBlockingClient cerbosClient, CerbosChannel cerbosChannel,
                                      MeterRegistry meterRegistry) {
        this.cerbosClient = cerbosClient;
        this.cerbosChannel = cerbosChannel;
        this.cerbosExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cerbos-check");
            t.setDaemon(true);
            return t;
        });

        this.permissionCache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_TTL)
                .recordStats()
                .build();

        this.cacheHits = Counter.builder("cerbos.cache.hits")
                .tag("service", "gateway")
                .register(meterRegistry);
        this.cacheMisses = Counter.builder("cerbos.cache.misses")
                .tag("service", "gateway")
                .register(meterRegistry);
        this.cacheEvictions = Counter.builder("cerbos.cache.evictions")
                .tag("service", "gateway")
                .register(meterRegistry);

        meterRegistry.gauge("cerbos.cache.size", permissionCache, Cache::estimatedSize);
    }

    public Mono<Boolean> checkSystemPermission(GatewayPrincipal principal, String permissionName) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying system check (fail-closed): user={} permission={}",
                    principal.getUsername(), permissionName);
            return Mono.just(false);
        }

        String cacheKey = systemCacheKey(principal, permissionName);
        Boolean cached = permissionCache.getIfPresent(cacheKey);
        if (cached != null) {
            cacheHits.increment();
            log.debug("Cerbos system check (cached): user={} permission={} allowed={}",
                    principal.getUsername(), permissionName, cached);
            return Mono.just(cached);
        }
        cacheMisses.increment();

        return Mono.fromCallable(() -> {
            Callable<Boolean> check = () -> {
                Principal cerbosPrincipal = CerbosPrincipalBuilder.build(principal);
                Resource resource = Resource.newInstance("system_feature", permissionName)
                        .withAttribute("featureName", AttributeValue.stringValue(permissionName))
                        .withScope(principal.getTenantId());

                CheckResult result = cerbosClient.check(cerbosPrincipal, resource, permissionName);
                return result.isAllowed(permissionName);
            };

            try {
                boolean allowed = checkWithRetry(check);
                recordSuccess();
                permissionCache.put(cacheKey, allowed);
                log.debug("Cerbos system check: user={} permission={} allowed={}",
                        principal.getUsername(), permissionName, allowed);
                return allowed;
            } catch (TimeoutException e) {
                recordFailure();
                log.error("Cerbos system check timed out (fail-closed): user={} permission={}",
                        principal.getUsername(), permissionName);
                return false;
            } catch (Exception e) {
                recordFailure();
                log.error("Cerbos system check failed (fail-closed): user={} permission={} error={}",
                        principal.getUsername(), permissionName, e.getMessage());
                return false;
            }
        })
        .onErrorResume(e -> {
            log.error("Cerbos system check error (fail-closed): user={} permission={} error={}",
                    principal.getUsername(), permissionName, e.getMessage());
            return Mono.just(false);
        });
    }

    public Mono<Boolean> checkObjectPermission(GatewayPrincipal principal,
                                                String collectionId,
                                                String action) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying object check (fail-closed): user={} collection={} action={}",
                    principal.getUsername(), collectionId, action);
            return Mono.just(false);
        }

        String cacheKey = objectCacheKey(principal, collectionId, action);
        Boolean cached = permissionCache.getIfPresent(cacheKey);
        if (cached != null) {
            cacheHits.increment();
            log.debug("Cerbos object check (cached): user={} collection={} action={} allowed={}",
                    principal.getUsername(), collectionId, action, cached);
            return Mono.just(cached);
        }
        cacheMisses.increment();

        return Mono.fromCallable(() -> {
            Callable<Boolean> check = () -> {
                Principal cerbosPrincipal = CerbosPrincipalBuilder.build(principal);
                Resource resource = Resource.newInstance("collection", collectionId)
                        .withAttribute("collectionId", AttributeValue.stringValue(collectionId))
                        .withScope(principal.getTenantId());

                CheckResult result = cerbosClient.check(cerbosPrincipal, resource, action);
                return result.isAllowed(action);
            };

            try {
                boolean allowed = checkWithRetry(check);
                recordSuccess();
                permissionCache.put(cacheKey, allowed);
                log.debug("Cerbos object check: user={} collection={} action={} allowed={}",
                        principal.getUsername(), collectionId, action, allowed);
                return allowed;
            } catch (TimeoutException e) {
                recordFailure();
                log.error("Cerbos object check timed out (fail-closed): user={} collection={} action={}",
                        principal.getUsername(), collectionId, action);
                return false;
            } catch (Exception e) {
                recordFailure();
                log.error("Cerbos object check failed (fail-closed): user={} collection={} action={} error={}",
                        principal.getUsername(), collectionId, action, e.getMessage());
                return false;
            }
        })
        .onErrorResume(e -> {
            log.error("Cerbos object check error (fail-closed): user={} collection={} action={} error={}",
                    principal.getUsername(), collectionId, action, e.getMessage());
            return Mono.just(false);
        });
    }

    /**
     * Evicts all cached permission entries for a tenant.
     * Called by {@link io.kelta.gateway.listener.CerbosCacheInvalidationListener}
     * when Cerbos policies are re-synced for a tenant.
     */
    public void evictForTenant(String tenantId) {
        long evicted = permissionCache.asMap().keySet().stream()
                .filter(key -> key.startsWith(tenantId + ":"))
                .peek(permissionCache::invalidate)
                .count();
        if (evicted > 0) {
            cacheEvictions.increment(evicted);
            log.info("Evicted {} cached permission entries for tenant {}", evicted, tenantId);
        }
    }

    // ── Cerbos call + transient retry ───────────────────────────────────

    private boolean checkWithRetry(Callable<Boolean> check) throws Exception {
        boolean retryAllowed = consecutiveFailures.get() == 0;
        try {
            return callOnce(check);
        } catch (Exception e) {
            if (!retryAllowed || !isTransient(e)) {
                throw e;
            }
            log.warn("Cerbos check failed transiently ({}) — resetting connection and retrying once",
                    e.getMessage());
            cerbosChannel.resetConnection();
            return callOnce(check);
        }
    }

    private boolean callOnce(Callable<Boolean> check) throws Exception {
        Future<Boolean> future = cerbosExecutor.submit(check);
        try {
            return future.get(CERBOS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }

    static boolean isTransient(Throwable e) {
        if (e instanceof TimeoutException) {
            return true;
        }
        Status.Code code = statusCode(e instanceof ExecutionException ? e.getCause() : e);
        return code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE;
    }

    private static Status.Code statusCode(Throwable e) {
        if (e instanceof CerbosException ce) {
            return Status.fromCodeValue(ce.getStatusCode()).getCode();
        }
        return Status.fromThrowable(e).getCode();
    }

    // ── Cache key builders ──────────────────────────────────────────────

    // Keys carry the request-origin country because the Cerbos principal does: a decision
    // cached for one origin must never answer for another once a policy references
    // P.attr.geoCountry. Tenant prefix stays first so evictForTenant() keeps matching.
    static String systemCacheKey(GatewayPrincipal principal, String permission) {
        return principal.getTenantId() + ":" + principal.getProfileId() + ":system:" + permission
                + ":geo:" + geoOrEmpty(principal);
    }

    static String objectCacheKey(GatewayPrincipal principal, String collectionId, String action) {
        return principal.getTenantId() + ":" + principal.getProfileId() + ":object:" + collectionId
                + ":" + action + ":geo:" + geoOrEmpty(principal);
    }

    private static String geoOrEmpty(GatewayPrincipal principal) {
        return principal.getGeoCountry() != null ? principal.getGeoCountry() : "";
    }

    // ── Circuit breaker helpers ──────────────────────────────────────────

    private boolean isCircuitOpen() {
        long openUntil = circuitOpenUntil.get();
        if (openUntil == 0) return false;
        if (System.currentTimeMillis() < openUntil) return true;
        circuitOpenUntil.set(0);
        consecutiveFailures.set(0);
        log.info("Cerbos circuit breaker closed — resuming checks");
        return false;
    }

    private void recordSuccess() {
        if (consecutiveFailures.get() > 0) {
            consecutiveFailures.set(0);
        }
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            long until = System.currentTimeMillis() + (CIRCUIT_BREAKER_COOLDOWN_SECONDS * 1000);
            circuitOpenUntil.set(until);
            log.error("Cerbos circuit breaker OPEN — denying all checks for {}s after {} consecutive failures",
                    CIRCUIT_BREAKER_COOLDOWN_SECONDS, failures);
        }
    }
}
