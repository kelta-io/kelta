package io.kelta.worker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CheckResourcesResult;
import dev.cerbos.sdk.CheckResult;
import dev.cerbos.sdk.builders.AttributeValue;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.GeoContext;
import io.kelta.worker.config.WorkerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Wraps Cerbos SDK calls with caching, hard timeouts, thread interruption,
 * and a circuit breaker to prevent thread-pool exhaustion.
 *
 * <p>Field access results are cached in-memory (Caffeine) and invalidated
 * via NATS events when Cerbos policies change. Record access checks are
 * NOT cached because they depend on record attributes (ABAC/CEL rules).
 *
 * <h3>Fail-closed design</h3>
 * <p>When Cerbos is slow or unreachable, all checks return <b>deny</b>.
 * Security first: no request should be allowed without explicit authorization.</p>
 */
@Service
public class CerbosAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(CerbosAuthorizationService.class);

    /** Maximum time (seconds) to wait for a single Cerbos gRPC call. */
    private static final long CERBOS_TIMEOUT_SECONDS = 2;

    /**
     * Maximum resources per CheckResources call. The Cerbos server rejects larger
     * batches with INVALID_ARGUMENT ("number of resources in batch (N) exceeds
     * configured limit (50)" — its default {@code requestLimits.maxActionsPerResource}
     * companion limit). Larger inputs are transparently split into sequential calls;
     * without this, any list page over 50 rows came back fully stripped (fail-closed)
     * and three such pages opened the circuit breaker for the whole pod.
     */
    private static final int MAX_RESOURCES_PER_CHECK = 50;

    // Circuit breaker thresholds — configurable via kelta.worker.cerbos-cb-threshold
    // and kelta.worker.cerbos-cb-cooldown-seconds (see WorkerProperties).

    /** Safety-net TTL for cache entries (primary invalidation is event-driven). */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final int CACHE_MAX_SIZE = 5_000;

    private final CerbosBlockingClient cerbosClient;
    private final ExecutorService cerbosExecutor;
    private final int circuitBreakerThreshold;
    private final long circuitBreakerCooldownSeconds;

    /**
     * Cached batch-Cerbos result for a single (tenant, profile, collection).
     *
     * <p>{@code asked} is the set of field IDs we have previously sent to
     * Cerbos for this key; {@code allowed} is the subset Cerbos approved.
     * Storing the asked set (not just the allow-list) lets us distinguish
     * "field is denied" from "field has never been queried" — critical when
     * a newly-added field (e.g. rollup_summary) shows up in a subsequent
     * request: without {@code asked} we would silently strip it as if it
     * had been denied. See issue #910.
     */
    private record FieldAccessEntry(Set<String> asked, Set<String> allowed) {}

    // Cache for field access: key = "tenantId:profileId:collectionId:action" → asked+allowed
    // sets. The action is part of the key: a cached "read" allow-list must never satisfy a
    // "write" (or "unmask") check — READ_ONLY denies write but allows read, MASKED denies
    // unmask but allows read.
    private final Cache<String, FieldAccessEntry> fieldAccessCache;

    // Collection-wide record decisions: key = "tenantId:profileId:collectionRef:action".
    // Only consulted for collections WITHOUT record-variant custom rules (RecordRuleIndex),
    // where the record policy's outcome is identical for every record. ALLOW only — a deny
    // is never cached because it may be a fail-closed artifact (timeout/breaker).
    private final Cache<String, Boolean> collectionRecordAccessCache;

    // Metrics
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheEvictions;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntil = new AtomicLong(0);

    public CerbosAuthorizationService(CerbosBlockingClient cerbosClient, MeterRegistry meterRegistry,
                                       WorkerProperties workerProperties) {
        this.cerbosClient = cerbosClient;
        this.circuitBreakerThreshold = workerProperties.getCerbosCbThreshold();
        this.circuitBreakerCooldownSeconds = workerProperties.getCerbosCbCooldownSeconds();
        this.cerbosExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cerbos-worker-check");
            t.setDaemon(true);
            return t;
        });

        this.fieldAccessCache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_TTL)
                .recordStats()
                .build();

        this.collectionRecordAccessCache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_TTL)
                .build();

        this.cacheHits = Counter.builder("cerbos.cache.hits")
                .tag("service", "worker")
                .register(meterRegistry);
        this.cacheMisses = Counter.builder("cerbos.cache.misses")
                .tag("service", "worker")
                .register(meterRegistry);
        this.cacheEvictions = Counter.builder("cerbos.cache.evictions")
                .tag("service", "worker")
                .register(meterRegistry);

        meterRegistry.gauge("cerbos.cache.size", fieldAccessCache, Cache::estimatedSize);
    }

    /**
     * Object-level (collection) permission check — the worker-side mirror of the
     * gateway's {@code RouteAuthorizationFilter} per-collection verb check
     * (`create`/`edit`/`delete`/`read` on the {@code collection} resource). The
     * gateway applies this to every dynamic collection route but <b>not</b> to the
     * static {@code /api/operations} route, so the atomic-operations controller
     * calls this to enforce the same authorization on batched writes.
     *
     * <p><b>Keying:</b> the {@code collection} policy CEL is keyed on the bootstrap
     * {@code collection.id} (a UUID) — the same identifier the gateway passes
     * ({@code route.getId()}) — <i>not</i> the collection name the record/field
     * policies use. Pass the collection's UUID here.
     *
     * <p>Fail-closed: a Cerbos circuit-open / timeout / error denies.
     *
     * @param collectionId the collection's UUID (bootstrap {@code collection.id})
     * @param action       {@code create} | {@code edit} | {@code delete} | {@code read}
     */
    public boolean checkCollectionAccess(String email, String profileId, String tenantId,
                                          String collectionId, String action) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying collection check (fail-closed): user={} collection={} action={}",
                    email, collectionId, action);
            return false;
        }

        Principal principal = buildPrincipal(email, profileId, tenantId);

        Future<Boolean> future = cerbosExecutor.submit(() -> {
            Resource resource = Resource.newInstance("collection", collectionId)
                    .withAttribute("collectionId", AttributeValue.stringValue(collectionId))
                    .withScope(tenantId);
            CheckResult result = cerbosClient.check(principal, resource, action);
            return result.isAllowed(action);
        });

        try {
            boolean allowed = future.get(CERBOS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            recordSuccess();
            log.debug("Cerbos collection check: user={} collection={} action={} allowed={}",
                    email, collectionId, action, allowed);
            return allowed;
        } catch (TimeoutException e) {
            future.cancel(true);
            recordFailure();
            log.error("Cerbos collection check timed out (fail-closed): user={} collection={}", email, collectionId);
            return false;
        } catch (Exception e) {
            future.cancel(true);
            recordFailure();
            log.error("Cerbos collection check failed (fail-closed): user={} collection={} error={}",
                    email, collectionId, e.getMessage());
            return false;
        }
    }

    public boolean checkRecordAccess(String email, String profileId, String tenantId,
                                      String collectionId, String recordId,
                                      Map<String, Object> recordAttributes, String action) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying record check (fail-closed): user={} record={}", email, recordId);
            return false;
        }

        Principal principal = buildPrincipal(email, profileId, tenantId);

        Future<Boolean> future = cerbosExecutor.submit(() -> {
            Resource resource = Resource.newInstance("record", recordId)
                    .withAttribute("collectionId", AttributeValue.stringValue(collectionId))
                    .withAttribute("tenantId", stringAttr(tenantId));

            if (recordAttributes != null) {
                for (Map.Entry<String, Object> entry : recordAttributes.entrySet()) {
                    if (entry.getValue() != null) {
                        resource = resource.withAttribute(entry.getKey(), toAttributeValue(entry.getValue()));
                    }
                }
            }

            resource = resource.withScope(tenantId);

            CheckResult result = cerbosClient.check(principal, resource, action);
            return result.isAllowed(action);
        });

        try {
            boolean allowed = future.get(CERBOS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            recordSuccess();
            log.debug("Cerbos record check: user={} collection={} record={} action={} allowed={}",
                    email, collectionId, recordId, action, allowed);
            return allowed;
        } catch (TimeoutException e) {
            future.cancel(true);
            recordFailure();
            log.error("Cerbos record check timed out (fail-closed): user={} record={}", email, recordId);
            return false;
        } catch (Exception e) {
            future.cancel(true);
            recordFailure();
            log.error("Cerbos record check failed (fail-closed): user={} record={} error={}", email, recordId, e.getMessage());
            return false;
        }
    }

    public boolean checkFieldAccess(String email, String profileId, String tenantId,
                                     String collectionId, String fieldId, String action) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying field check (fail-closed): user={} field={}", email, fieldId);
            return false;
        }

        Principal principal = buildPrincipal(email, profileId, tenantId);

        Future<Boolean> future = cerbosExecutor.submit(() -> {
            Resource resource = Resource.newInstance("field", fieldId)
                    .withAttribute("collectionId", AttributeValue.stringValue(collectionId))
                    .withAttribute("fieldId", AttributeValue.stringValue(fieldId))
                    .withScope(tenantId);

            CheckResult result = cerbosClient.check(principal, resource, action);
            return result.isAllowed(action);
        });

        try {
            boolean allowed = future.get(CERBOS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            recordSuccess();
            log.debug("Cerbos field check: user={} collection={} field={} action={} allowed={}",
                    email, collectionId, fieldId, action, allowed);
            return allowed;
        } catch (TimeoutException e) {
            future.cancel(true);
            recordFailure();
            log.error("Cerbos field check timed out (fail-closed): user={} field={}", email, fieldId);
            return false;
        } catch (Exception e) {
            future.cancel(true);
            recordFailure();
            log.error("Cerbos field check failed (fail-closed): user={} field={} error={}", email, fieldId, e.getMessage());
            return false;
        }
    }

    /**
     * Batch-checks field access for multiple fields in a single Cerbos gRPC call.
     * Results are cached per (tenant, profile, collection, action) since field
     * permissions don't depend on record data.
     *
     * @return the list of fieldIds that are ALLOWED
     */
    public List<String> batchCheckFieldAccess(String email, String profileId, String tenantId,
                                               String collectionId, List<String> fieldIds, String action) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying all field access (fail-closed): user={} collection={}", email, collectionId);
            return List.of();
        }
        if (fieldIds.isEmpty()) {
            return fieldIds;
        }

        // Cache hit only when every requested field has already been queried
        // for this (tenant, profile, collection) — otherwise a new field
        // (e.g. just-added rollup_summary) would be silently stripped because
        // "absent from allow-list" is indistinguishable from "denied".
        String cacheKey = fieldCacheKey(tenantId, profileId, collectionId, action);
        FieldAccessEntry cached = fieldAccessCache.getIfPresent(cacheKey);
        Set<String> requestedSet = Set.copyOf(fieldIds);
        if (cached != null && cached.asked().containsAll(requestedSet)) {
            cacheHits.increment();
            List<String> result = fieldIds.stream()
                    .filter(cached.allowed()::contains)
                    .toList();
            log.debug("Cerbos batch field check (cached): user={} collection={} fields={} allowed={}",
                    email, collectionId, fieldIds.size(), result.size());
            return result;
        }
        cacheMisses.increment();

        // Re-query the UNION of (previously asked) ∪ (requested) so we retain
        // deny knowledge for fields the cache already knew about and pick up
        // decisions for any newly-requested fields in a single batch.
        Set<String> toQuery = new HashSet<>(requestedSet);
        if (cached != null) {
            toQuery.addAll(cached.asked());
        }

        // All chunks must succeed before the cache is written: a partial allow-set
        // stored under the full asked-set would persist denials for fields Cerbos
        // never answered about. Any chunk failure → deny everything, no cache write.
        Set<String> allowed = new HashSet<>();
        for (List<String> chunk : partition(List.copyOf(toQuery), MAX_RESOURCES_PER_CHECK)) {
            if (isCircuitOpen()) {
                log.warn("Cerbos circuit open — denying all field access (fail-closed): user={} collection={}",
                        email, collectionId);
                return List.of();
            }
            Principal principal = buildPrincipal(email, profileId, tenantId);
            Future<Set<String>> future = cerbosExecutor.submit(() -> {
                var batchRequest = cerbosClient.batch(principal);
                for (String fieldId : chunk) {
                    Resource resource = Resource.newInstance("field", fieldId)
                            .withAttribute("collectionId", AttributeValue.stringValue(collectionId))
                            .withAttribute("fieldId", AttributeValue.stringValue(fieldId))
                            .withScope(tenantId);
                    batchRequest.addResourceAndActions(resource, action);
                }

                CheckResourcesResult result = batchRequest.check();
                Set<String> chunkAllowed = new HashSet<>();
                for (String fieldId : chunk) {
                    result.find(fieldId).ifPresentOrElse(
                            checkResult -> {
                                if (checkResult.isAllowed(action)) {
                                    chunkAllowed.add(fieldId);
                                }
                            },
                            () -> log.warn("Cerbos batch field check: field not found in result (fail-closed): field={}", fieldId)
                    );
                }
                return chunkAllowed;
            });

            try {
                allowed.addAll(future.get(CERBOS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                recordSuccess();
            } catch (TimeoutException e) {
                future.cancel(true);
                recordFailure();
                log.error("Cerbos batch field check timed out (fail-closed): user={} collection={} fields={}",
                        email, collectionId, fieldIds.size());
                return List.of();
            } catch (Exception e) {
                future.cancel(true);
                recordFailure();
                log.error("Cerbos batch field check failed (fail-closed): user={} collection={} error={}",
                        email, collectionId, e.getMessage());
                return List.of();
            }
        }

        fieldAccessCache.put(cacheKey,
                new FieldAccessEntry(Set.copyOf(toQuery), Set.copyOf(allowed)));
        List<String> result = fieldIds.stream()
                .filter(allowed::contains)
                .toList();
        log.debug("Cerbos batch field check: user={} collection={} fields={} allowed={}",
                email, collectionId, fieldIds.size(), result.size());
        return result;
    }

    /**
     * Collection-wide record decision for collections whose record policy is
     * record-invariant (no enabled custom rules — see {@link RecordRuleIndex}).
     * One sentinel Cerbos check stands in for the whole page; ALLOW results are
     * cached per (tenant, profile, collection, action) until the policy-changed
     * event evicts them. DENY is returned but never cached — it may be a
     * fail-closed artifact of a timeout or an open circuit breaker, and a real
     * deny re-checks in one cheap call per page.
     */
    public boolean checkCollectionWideRecordAccess(String email, String profileId, String tenantId,
                                                    String collectionRef, String action) {
        String cacheKey = tenantId + ":" + profileId + ":" + collectionRef + ":" + action
                + ":geo:" + GeoContext.currentCountry();
        Boolean cached = collectionRecordAccessCache.getIfPresent(cacheKey);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }
        cacheMisses.increment();
        boolean allowed = checkRecordAccess(email, profileId, tenantId,
                collectionRef, "__collection_wide__", Map.of(), action);
        if (allowed) {
            collectionRecordAccessCache.put(cacheKey, Boolean.TRUE);
        }
        return allowed;
    }

    /**
     * Batch-checks record access for multiple records in a single Cerbos gRPC call.
     * NOT cached because record checks depend on record attributes (ABAC/CEL rules).
     *
     * @return the set of recordIds that are ALLOWED
     */
    public Set<String> batchCheckRecordAccess(String email, String profileId, String tenantId,
                                               String collectionId, List<Map<String, Object>> records, String action) {
        if (isCircuitOpen()) {
            log.warn("Cerbos circuit open — denying all record access (fail-closed): user={} collection={}", email, collectionId);
            return Set.of();
        }
        if (records.isEmpty()) {
            return Set.of();
        }

        // Chunks are independent: a failed chunk denies only its own records
        // (fail-closed) while the rest of the page stays authorized, so one bad
        // gRPC call degrades a page instead of blanking it.
        Set<String> allowed = new HashSet<>();
        for (List<Map<String, Object>> chunk : partition(records, MAX_RESOURCES_PER_CHECK)) {
            if (isCircuitOpen()) {
                log.warn("Cerbos circuit open — denying remaining record access (fail-closed): user={} collection={}",
                        email, collectionId);
                break;
            }
            Principal principal = buildPrincipal(email, profileId, tenantId);
            Future<Set<String>> future = cerbosExecutor.submit(() -> {
                var batchRequest = cerbosClient.batch(principal);
                for (Map<String, Object> record : chunk) {
                    String recordId = (String) record.get("id");
                    if (recordId == null) continue;

                    Resource resource = Resource.newInstance("record", recordId)
                            .withAttribute("collectionId", AttributeValue.stringValue(collectionId))
                            .withAttribute("tenantId", stringAttr(tenantId));

                    @SuppressWarnings("unchecked")
                    Map<String, Object> attrs = (Map<String, Object>) record.get("attributes");
                    if (attrs != null) {
                        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                            if (entry.getValue() != null) {
                                resource = resource.withAttribute(entry.getKey(), toAttributeValue(entry.getValue()));
                            }
                        }
                    }
                    resource = resource.withScope(tenantId);
                    batchRequest.addResourceAndActions(resource, action);
                }

                CheckResourcesResult result = batchRequest.check();
                Set<String> chunkAllowed = new HashSet<>();
                for (Map<String, Object> record : chunk) {
                    String recordId = (String) record.get("id");
                    if (recordId == null) {
                        continue;
                    }
                    result.find(recordId).ifPresentOrElse(
                            checkResult -> {
                                if (checkResult.isAllowed(action)) {
                                    chunkAllowed.add(recordId);
                                }
                            },
                            () -> log.warn("Cerbos batch record check: record not found in result (fail-closed): record={}", recordId)
                    );
                }
                return chunkAllowed;
            });

            try {
                allowed.addAll(future.get(CERBOS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                recordSuccess();
            } catch (TimeoutException e) {
                future.cancel(true);
                recordFailure();
                log.error("Cerbos batch record check timed out (chunk denied, fail-closed): user={} collection={} chunk={}",
                        email, collectionId, chunk.size());
            } catch (Exception e) {
                future.cancel(true);
                recordFailure();
                log.error("Cerbos batch record check failed (chunk denied, fail-closed): user={} collection={} error={}",
                        email, collectionId, e.getMessage());
            }
        }

        log.debug("Cerbos batch record check: user={} collection={} records={} allowed={}",
                email, collectionId, records.size(), allowed.size());
        return allowed;
    }

    /** Splits {@code items} into consecutive sublists of at most {@code size} elements. */
    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }

    /**
     * Evicts all cached field access entries for a tenant.
     * Called when Cerbos policies are re-synced for a tenant.
     */
    public void evictForTenant(String tenantId) {
        long evicted = fieldAccessCache.asMap().keySet().stream()
                .filter(key -> key.startsWith(tenantId + ":"))
                .peek(fieldAccessCache::invalidate)
                .count();
        long recordEvicted = collectionRecordAccessCache.asMap().keySet().stream()
                .filter(key -> key.startsWith(tenantId + ":"))
                .peek(collectionRecordAccessCache::invalidate)
                .count();
        if (evicted + recordEvicted > 0) {
            cacheEvictions.increment(evicted + recordEvicted);
            log.info("Evicted {} field + {} collection-wide record cache entries for tenant {}",
                    evicted, recordEvicted, tenantId);
        }
    }

    /**
     * Evicts cached field access entries for a single (tenant, collection) across
     * all profiles. Called when a field is added/updated/removed on the collection
     * so subsequent reads re-query Cerbos for the new field set.
     *
     * <p>Without this, {@link #batchCheckFieldAccess} would keep returning the
     * pre-change allow-list and silently strip the newly-added field (e.g. a
     * rollup_summary attribute) from responses until the {@link #CACHE_TTL}
     * expires — see issue #910.
     */
    public void evictForCollection(String tenantId, String collectionId) {
        if (tenantId == null || collectionId == null) {
            return;
        }
        String prefix = tenantId + ":";
        java.util.function.Predicate<String> matchesCollection = key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            // key = tenantId:profileId:collectionRef:action — match the collection
            // segment positionally so every action's entry is evicted.
            String[] parts = key.split(":", -1);
            return parts.length >= 4 && parts[2].equals(collectionId);
        };
        long evicted = fieldAccessCache.asMap().keySet().stream()
                .filter(matchesCollection)
                .peek(fieldAccessCache::invalidate)
                .count();
        long recordEvicted = collectionRecordAccessCache.asMap().keySet().stream()
                .filter(matchesCollection)
                .peek(collectionRecordAccessCache::invalidate)
                .count();
        if (evicted + recordEvicted > 0) {
            cacheEvictions.increment(evicted + recordEvicted);
            log.info("Evicted {} field + {} collection-wide record cache entries for tenant={} collection={}",
                    evicted, recordEvicted, tenantId, collectionId);
        }
    }

    // ── Cache key builders ──────────────────────────────────────────────

    // Both caches key on the request-origin country because the principal now carries it:
    // once a policy references P.attr.geoCountry, a decision cached for one origin must
    // never answer for another. Tenant prefix stays first for the eviction prefix match.
    private static String fieldCacheKey(String tenantId, String profileId, String collectionId,
                                        String action) {
        return tenantId + ":" + profileId + ":" + collectionId + ":" + action
                + ":geo:" + GeoContext.currentCountry();
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

    /**
     * Builds the principal from the request's scoped values, so it must run on the request
     * thread — never inside a {@code cerbosExecutor} task, where neither {@link GeoContext} nor
     * {@link CallerContext} is bound.
     *
     * <p>{@code P.id} stays the email (existing tenant rules compare it with email fields);
     * {@code P.attr.userId} is the caller's {@code platform_user.id} UUID, which is what
     * own-record rules ({@code R.attr.createdBy == P.attr.userId}) compare against.
     */
    private Principal buildPrincipal(String email, String profileId, String tenantId) {
        return Principal.newInstance(email, "user")
                .withAttribute("profileId", stringAttr(profileId))
                .withAttribute("tenantId", stringAttr(tenantId))
                // "" on the internal tier (flows, schedulers) — no caller is bound there.
                .withAttribute("userId", stringAttr(CallerContext.current()
                        .map(CallerContext::userId).orElse(null)))
                // Request-origin country from GeoContext (bound by TenantContextFilter);
                // "" on flow/system paths and un-geolocated origins — policies must handle it.
                .withAttribute("geoCountry", stringAttr(GeoContext.currentCountry()));
    }

    private void recordSuccess() {
        if (consecutiveFailures.get() > 0) {
            consecutiveFailures.set(0);
        }
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitBreakerThreshold) {
            long until = System.currentTimeMillis() + (circuitBreakerCooldownSeconds * 1000);
            circuitOpenUntil.set(until);
            log.error("Cerbos circuit breaker OPEN — denying all checks for {}s after {} consecutive failures",
                    circuitBreakerCooldownSeconds, failures);
        }
    }

    private static AttributeValue stringAttr(String value) {
        return AttributeValue.stringValue(value != null ? value : "");
    }

    private static AttributeValue toAttributeValue(Object value) {
        if (value instanceof String s) return AttributeValue.stringValue(s);
        if (value instanceof Number n) return AttributeValue.doubleValue(n.doubleValue());
        if (value instanceof Boolean b) return AttributeValue.boolValue(b);
        return AttributeValue.stringValue(String.valueOf(value));
    }
}
