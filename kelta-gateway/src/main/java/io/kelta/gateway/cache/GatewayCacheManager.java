package io.kelta.gateway.cache;

import io.kelta.gateway.config.GovernorLimitConfig;
import io.kelta.gateway.config.TenantIpConfig;
import io.kelta.gateway.route.RateLimitConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized cache manager for the gateway, backed by Caffeine caches.
 *
 * <p>Replaces the previous {@code TenantSlugCache} and {@code TenantGovernorLimitCache}
 * with a unified caching strategy using Caffeine's high-performance caches with
 * time-based expiration, maximum size bounds, and statistics recording.
 *
 * <p>Manages two caches:
 * <ul>
 *   <li><strong>Tenant slug cache</strong> — maps tenant slugs to tenant IDs.
 *       Populated on startup and refreshed periodically from the worker's
 *       {@code /internal/tenants/slug-map} endpoint. Expires after 10 minutes.</li>
 *   <li><strong>Governor limit cache</strong> — maps tenant IDs to daily API call limits.
 *       Populated from bootstrap configuration on startup and updated via NATS when
 *       tenant governor limits change. No time-based expiration — entries persist until
 *       explicitly invalidated.</li>
 * </ul>
 */
@Component
public class GatewayCacheManager {

    private static final Logger log = LoggerFactory.getLogger(GatewayCacheManager.class);

    /**
     * Window duration for rate limiting. We use 5-minute windows to allow
     * short bursts (e.g., AI creating a collection + multiple fields)
     * without hitting the per-window limit.
     */
    private static final Duration WINDOW_DURATION = Duration.ofMinutes(5);

    /**
     * Number of windows per day (288 five-minute windows).
     */
    private static final int WINDOWS_PER_DAY = 288;

    /**
     * Default apiCallsPerDay when a tenant is not found in the cache.
     * Matches GovernorLimits.defaults().apiCallsPerDay().
     */
    private static final int DEFAULT_API_CALLS_PER_DAY = 100_000;

    /** Bound on a lazy worker lookup made while a request waits on it. */
    private static final Duration SLUG_MAP_TIMEOUT = Duration.ofSeconds(2);

    /** In-flight lazy slug-map fetch, shared so concurrent misses issue one worker call. */
    private final AtomicReference<Mono<Map<String, String>>> inFlightSlugMapFetch = new AtomicReference<>();

    private final Cache<String, String> tenantSlugCache;
    private final Cache<String, Boolean> unknownTenantIdCache; // tenantId the slug map lacks
    private final Cache<String, Integer> governorLimitCache;
    private final Cache<String, TenantIpConfig> tenantIpConfigCache; // tenantId → IP allowlist config
    private final Cache<String, String> customDomainCache; // domain → tenantSlug
    private final Cache<String, String> guestProfileCache; // tenantId → guest profileId
    private final Cache<String, byte[]> systemCollectionResponseCache; // tenantId:path → response body
    private final WebClient webClient;

    public GatewayCacheManager(
            WebClient.Builder webClientBuilder,
            @Value("${kelta.gateway.worker-service-url:http://emf-worker:80}") String workerServiceUrl) {

        this.webClient = webClientBuilder.baseUrl(workerServiceUrl).build();

        this.tenantSlugCache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .recordStats()
                .build();

        this.unknownTenantIdCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

        this.governorLimitCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .recordStats()
                .build();

        this.tenantIpConfigCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .recordStats()
                .build();

        this.customDomainCache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

        this.guestProfileCache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

        this.systemCollectionResponseCache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .recordStats()
                .build();
    }

    // ── Tenant Slug Cache ─────────────────────────────────────────────────

    /**
     * Resolves a tenant slug to a tenant ID.
     *
     * @param slug the tenant slug from the URL path
     * @return the tenant ID if the slug is known, empty otherwise
     */
    public Optional<String> resolveTenantSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        Optional<String> cached = cachedTenantSlug(slug);
        if (cached != null) {
            return cached;
        }
        if (Schedulers.isInNonBlockingThread()) {
            // A reactive caller must use resolveTenantSlugReactive — blocking here throws, and the
            // resulting "not found" would 404 a valid tenant. Answer from cache only.
            log.warn("resolveTenantSlug('{}') missed the cache on a non-blocking thread; "
                    + "use resolveTenantSlugReactive so the lazy lookup can run", slug);
            return Optional.empty();
        }
        return resolveTenantSlugReactive(slug).block(SLUG_MAP_TIMEOUT.plusSeconds(1));
    }

    /**
     * Resolves a tenant slug to a tenant ID without blocking — the variant reactive callers
     * (the gateway filter chain) must use.
     *
     * <p>On a cache miss the slug→tenant map is pulled from the worker and merged. The scheduled
     * refresh may not have run yet (cold start, or a refresh that failed while the worker was
     * briefly unreachable), or this may be a brand-new tenant; without the lazy lookup a VALID
     * tenant 404s for up to one refresh interval and the UI cannot even load. Concurrent misses
     * share one in-flight fetch.
     *
     * @param slug the tenant slug from the URL path
     * @return the tenant ID if the slug is known, empty otherwise
     */
    public Mono<Optional<String>> resolveTenantSlugReactive(String slug) {
        if (slug == null || slug.isBlank()) {
            return Mono.just(Optional.empty());
        }
        Optional<String> cached = cachedTenantSlug(slug);
        if (cached != null) {
            return Mono.just(cached);
        }
        return sharedTenantSlugMapFetch()
                .map(mapping -> {
                    if (mapping.isEmpty()) {
                        return Optional.<String>empty();
                    }
                    // Merge only — never clear, or a concurrent request misses a slug we hold.
                    tenantSlugCache.putAll(mapping);
                    unknownTenantIdCache.invalidateAll(mapping.values());
                    String resolved = mapping.get(slug);
                    if (resolved != null) {
                        return Optional.of(resolved);
                    }
                    // Fetched successfully but the slug genuinely isn't a tenant — negative-cache so
                    // an unknown slug doesn't re-fetch on every request (cleared by the next refresh).
                    tenantSlugCache.put(slug, SLUG_NOT_FOUND);
                    return Optional.<String>empty();
                })
                // On a fetch error we intentionally do NOT negative-cache: a transient worker blip
                // must not pin a valid tenant to "not found" — the next request retries.
                .onErrorResume(e -> {
                    log.warn("Lazy tenant slug-map fetch failed: {}", e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * Resolves a tenant ID to its slug without blocking — the inverse of
     * {@link #resolveTenantSlugReactive}. Used when a client names its tenant by
     * {@code X-Tenant-ID} alone, so the worker still receives the slug that selects the tenant's
     * schema.
     *
     * <p>On a cache miss the same shared slug-map fetch runs. An id the fetched map does not hold
     * is remembered briefly in a separate set (never as a slug entry), so a stream of unknown ids
     * cannot turn into a worker fetch per request; the next slug refresh forgets it.
     *
     * @param tenantId the tenant ID claimed by the client
     * @return the tenant slug if the ID is known, empty otherwise
     */
    public Mono<Optional<String>> resolveTenantIdToSlugReactive(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Mono.just(Optional.empty());
        }
        Optional<String> cached = slugForTenantId(tenantSlugCache.asMap(), tenantId);
        if (cached.isPresent()) {
            return Mono.just(cached);
        }
        if (unknownTenantIdCache.getIfPresent(tenantId) != null) {
            return Mono.just(Optional.empty());
        }
        return sharedTenantSlugMapFetch()
                .map(mapping -> {
                    if (mapping.isEmpty()) {
                        return Optional.<String>empty();
                    }
                    tenantSlugCache.putAll(mapping);
                    unknownTenantIdCache.invalidateAll(mapping.values());
                    Optional<String> resolved = slugForTenantId(mapping, tenantId);
                    if (resolved.isEmpty()) {
                        unknownTenantIdCache.put(tenantId, Boolean.TRUE);
                    }
                    return resolved;
                })
                .onErrorResume(e -> {
                    log.warn("Lazy tenant slug-map fetch failed: {}", e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    private static Optional<String> slugForTenantId(Map<String, String> slugMap, String tenantId) {
        return slugMap.entrySet().stream()
                .filter(e -> tenantId.equals(e.getValue()) && !SLUG_NOT_FOUND.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Reads the slug straight from the cache.
     *
     * @return the resolution if the cache holds one (possibly {@link Optional#empty()} for a
     *         negative entry), or {@code null} on a cache miss
     */
    private Optional<String> cachedTenantSlug(String slug) {
        String cached = tenantSlugCache.getIfPresent(slug);
        if (cached == null) {
            return null;
        }
        return SLUG_NOT_FOUND.equals(cached) ? Optional.empty() : Optional.of(cached);
    }

    /**
     * Fetches the slug→tenantId map from the worker (bounded), collapsing concurrent callers onto a
     * single in-flight request so a cold cache cannot stampede the worker. Never emits null.
     */
    private Mono<Map<String, String>> sharedTenantSlugMapFetch() {
        while (true) {
            Mono<Map<String, String>> existing = inFlightSlugMapFetch.get();
            if (existing != null) {
                return existing;
            }
            // defer so an assembly-time failure surfaces as onError rather than being thrown at the
            // caller — the resolve path treats any fetch failure as "unknown, retry next request".
            Mono<Map<String, String>> fetch = Mono.defer(() -> webClient.get()
                            .uri("/internal/tenants/slug-map")
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {}))
                    .timeout(SLUG_MAP_TIMEOUT)
                    .defaultIfEmpty(Map.of())
                    .doFinally(signal -> inFlightSlugMapFetch.set(null))
                    .cache();
            if (inFlightSlugMapFetch.compareAndSet(null, fetch)) {
                return fetch;
            }
        }
    }

    /**
     * Checks whether the given string is a known tenant slug (lazily resolving on a cache miss).
     */
    public boolean isKnownSlug(String slug) {
        return resolveTenantSlug(slug).isPresent();
    }

    /**
     * Returns the number of cached slug mappings.
     */
    public long tenantSlugCacheSize() {
        return tenantSlugCache.estimatedSize();
    }

    /**
     * Bulk-loads tenant slug mappings, replacing existing entries.
     *
     * @param slugMap map of slug to tenantId
     */
    public void refreshTenantSlugs(Map<String, String> slugMap) {
        replaceTenantSlugs(slugMap);
        log.info("Refreshed tenant slug cache: {} entries", slugMap.size());
    }

    /**
     * Swaps the slug cache over to {@code slugMap} without ever leaving it empty.
     *
     * <p>{@code invalidateAll()} followed by {@code putAll(...)} opens a window — sub-millisecond,
     * but every refresh tick — in which a request for a perfectly valid tenant misses the cache and
     * 404s with {@code TENANT_NOT_FOUND} (#1334). Writing the new entries first and only then
     * dropping the ones that are gone means a slug present in both the old and new map is never
     * absent. Stale entries (including negative {@code SLUG_NOT_FOUND} markers) still disappear, so
     * a renamed or deleted tenant stops resolving as before.
     */
    private void replaceTenantSlugs(Map<String, String> slugMap) {
        tenantSlugCache.putAll(slugMap);
        tenantSlugCache.asMap().keySet().removeIf(slug -> !slugMap.containsKey(slug));
        unknownTenantIdCache.invalidateAll();
    }

    /**
     * Refreshes the tenant slug cache from the worker service.
     * Called on startup by {@link io.kelta.gateway.config.RouteInitializer}
     * and periodically via {@code @Scheduled}.
     */
    @Scheduled(fixedDelayString = "${kelta.gateway.tenant-slug.cache-refresh-ms:60000}")
    public void refreshTenantSlugsFromWorker() {
        try {
            Map<String, String> mapping = webClient.get()
                    .uri("/internal/tenants/slug-map")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                    .block();

            if (mapping != null && !mapping.isEmpty()) {
                replaceTenantSlugs(mapping);
                log.info("Refreshed tenant slug cache: {} entries", mapping.size());
            } else {
                log.warn("Tenant slug-map returned empty; keeping existing cache ({} entries)",
                        tenantSlugCache.estimatedSize());
            }
        } catch (Exception e) {
            log.warn("Failed to refresh tenant slug cache (will retry): {}", e.getMessage());
        }
    }

    // ── Governor Limit Cache ──────────────────────────────────────────────

    /**
     * Loads governor limits from the bootstrap configuration.
     *
     * @param governorLimits map of tenantId to GovernorLimitConfig
     */
    public void loadGovernorLimits(Map<String, GovernorLimitConfig> governorLimits) {
        if (governorLimits == null || governorLimits.isEmpty()) {
            log.warn("No governor limits received from bootstrap; using defaults for all tenants");
            return;
        }

        governorLimitCache.invalidateAll();
        for (Map.Entry<String, GovernorLimitConfig> entry : governorLimits.entrySet()) {
            governorLimitCache.put(entry.getKey(), entry.getValue().getApiCallsPerDay());
        }

        log.info("Loaded governor limits for {} tenants from bootstrap", governorLimits.size());
    }

    /**
     * Updates the governor limit for a specific tenant.
     *
     * @param tenantId       the tenant ID
     * @param apiCallsPerDay the tenant's daily API call limit
     */
    public void updateGovernorLimit(String tenantId, int apiCallsPerDay) {
        governorLimitCache.put(tenantId, apiCallsPerDay);
        log.info("Updated governor limit for tenant {}: {} API calls/day", tenantId, apiCallsPerDay);
    }

    /**
     * Gets the rate limit configuration for a tenant.
     *
     * <p>Converts the daily API call limit into a per-minute rate limit
     * by dividing by 1440 (minutes per day). Ensures a minimum of 1 request
     * per window to avoid blocking all requests.
     *
     * @param tenantId the tenant ID
     * @return the rate limit config for the tenant
     */
    public RateLimitConfig getRateLimitForTenant(String tenantId) {
        Integer dailyLimit = governorLimitCache.getIfPresent(tenantId);
        int limit = (dailyLimit != null) ? dailyLimit : DEFAULT_API_CALLS_PER_DAY;
        int requestsPerWindow = Math.max(1, (limit / WINDOWS_PER_DAY) * 5);
        return new RateLimitConfig(requestsPerWindow, WINDOW_DURATION);
    }

    /**
     * Gets the raw daily API call limit for a tenant.
     *
     * @param tenantId the tenant ID
     * @return optional containing the daily limit, or empty if tenant not in cache
     */
    public Optional<Integer> getGovernorLimit(String tenantId) {
        return Optional.ofNullable(governorLimitCache.getIfPresent(tenantId));
    }

    /**
     * Returns the number of cached governor limits.
     */
    public long governorLimitCacheSize() {
        return governorLimitCache.estimatedSize();
    }

    /**
     * Refreshes the governor limit cache from the worker service.
     *
     * <p>Called when a tenant record change event is received via NATS
     * (e.g., governor limits updated via the admin UI). Fetches the
     * lightweight governor-limits map from the worker's
     * {@code /internal/governor-limits} endpoint and updates the cache.
     */
    public void refreshGovernorLimitsFromWorker() {
        try {
            Map<String, Integer> limitsMap = webClient.get()
                    .uri("/internal/governor-limits")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Integer>>() {})
                    .block();

            if (limitsMap != null && !limitsMap.isEmpty()) {
                governorLimitCache.invalidateAll();
                governorLimitCache.putAll(limitsMap);
                log.info("Refreshed governor limit cache from worker: {} entries", limitsMap.size());
            } else {
                log.warn("Governor limits map returned empty; keeping existing cache ({} entries)",
                        governorLimitCache.estimatedSize());
            }
        } catch (Exception e) {
            log.warn("Failed to refresh governor limit cache from worker: {}", e.getMessage());
        }
    }

    // ── Tenant IP Allowlist Cache ─────────────────────────────────────────

    /**
     * Loads per-tenant IP allowlist configs from the bootstrap configuration,
     * replacing any existing entries.
     *
     * @param ipAllowlists map of tenantId to {@link TenantIpConfig}
     */
    public void loadTenantIpConfigs(Map<String, TenantIpConfig> ipAllowlists) {
        if (ipAllowlists == null || ipAllowlists.isEmpty()) {
            log.info("No tenant IP allowlists in bootstrap; no tenants restrict network access");
            tenantIpConfigCache.invalidateAll();
            return;
        }
        tenantIpConfigCache.invalidateAll();
        tenantIpConfigCache.putAll(ipAllowlists);
        log.info("Loaded IP allowlist config for {} tenants from bootstrap", ipAllowlists.size());
    }

    /**
     * Returns the IP allowlist config for a tenant, or empty if the tenant is unknown
     * to the cache (in which case the caller should fail open — allow the request).
     *
     * @param tenantId the tenant ID
     * @return the tenant's IP config, or empty if not cached
     */
    public Optional<TenantIpConfig> getTenantIpConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantIpConfigCache.getIfPresent(tenantId));
    }

    /**
     * Returns the number of cached tenant IP configs.
     */
    public long tenantIpConfigCacheSize() {
        return tenantIpConfigCache.estimatedSize();
    }

    /**
     * Refreshes the tenant IP allowlist cache from the worker service.
     *
     * <p>Called by {@link io.kelta.gateway.listener.IpAllowlistCacheInvalidationListener}
     * when a {@code kelta.config.tenant.ip-allowlist.changed.*} event is received. Fetches
     * the map from the worker's {@code /internal/ip-allowlists} endpoint and replaces the
     * cache. On any failure the existing cache is kept so a transient worker blip does not
     * drop restrictions.
     */
    public void refreshIpAllowlistsFromWorker() {
        try {
            Map<String, TenantIpConfig> allowlists = webClient.get()
                    .uri("/internal/ip-allowlists")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, TenantIpConfig>>() {})
                    .block(Duration.ofSeconds(2));

            if (allowlists != null) {
                tenantIpConfigCache.invalidateAll();
                tenantIpConfigCache.putAll(allowlists);
                log.info("Refreshed tenant IP allowlist cache from worker: {} entries", allowlists.size());
            } else {
                log.warn("IP allowlists map returned null; keeping existing cache ({} entries)",
                        tenantIpConfigCache.estimatedSize());
            }
        } catch (Exception e) {
            log.warn("Failed to refresh tenant IP allowlist cache from worker: {}", e.getMessage());
        }
    }

    // ── Custom Domain Cache ───────────────────────────────────────────────

    /**
     * Sentinel value indicating a domain was looked up and is not a custom domain.
     * Cached to avoid repeated worker API calls for regular subdomain-based tenants.
     */
    private static final String DOMAIN_NOT_FOUND = "__NOT_FOUND__";

    /**
     * Sentinel value indicating a slug was looked up against the worker and is not a known tenant.
     * Negative-cached so an unknown slug doesn't trigger a worker fetch on every request.
     */
    private static final String SLUG_NOT_FOUND = "__NOT_FOUND__";

    /**
     * Resolves a custom domain to a tenant slug.
     *
     * <p>Uses a three-tier lookup: local cache → worker API → not found.
     * Both positive results (domain → slug) and negative results (domain not found)
     * are cached to avoid repeated worker calls.
     *
     * @param domain the custom domain (e.g., "app.acme.com")
     * @return the tenant slug if the domain is registered, empty otherwise
     */
    public Optional<String> resolveCustomDomain(String domain) {
        Optional<String> cached = cachedCustomDomain(domain);
        if (cached != null) {
            return cached;
        }
        if (Schedulers.isInNonBlockingThread()) {
            // See resolveTenantSlug: a reactive caller must use resolveCustomDomainReactive.
            log.warn("resolveCustomDomain('{}') missed the cache on a non-blocking thread; "
                    + "use resolveCustomDomainReactive so the lookup can run", domain);
            return Optional.empty();
        }
        return resolveCustomDomainReactive(domain).block(SLUG_MAP_TIMEOUT.plusSeconds(1));
    }

    /**
     * Resolves a custom domain to a tenant slug without blocking — the variant reactive callers
     * (the gateway filter chain) must use.
     *
     * <p>Three-tier lookup: local cache → worker API → not found. A definitive "no such domain"
     * from the worker is negative-cached so an unregistered host does not call the worker on every
     * request; a lookup that *failed* is not, because pinning a valid domain to "not found" for the
     * cache TTL would black-hole a live tenant (#1334).
     *
     * @param domain the custom domain (e.g., "app.acme.com")
     * @return the tenant slug if the domain is registered, empty otherwise
     */
    public Mono<Optional<String>> resolveCustomDomainReactive(String domain) {
        if (domain == null || domain.isBlank()) {
            return Mono.just(Optional.empty());
        }
        Optional<String> cached = cachedCustomDomain(domain);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.defer(() -> webClient.get()
                        .uri("/internal/domains/resolve?domain={domain}", domain)
                        .retrieve()
                        .bodyToMono(String.class))
                .timeout(SLUG_MAP_TIMEOUT)
                .defaultIfEmpty("")
                .map(resolved -> {
                    if (!resolved.isBlank()) {
                        customDomainCache.put(domain, resolved);
                        return Optional.of(resolved);
                    }
                    // The worker answered, and the answer is "not a custom domain".
                    customDomainCache.put(domain, DOMAIN_NOT_FOUND);
                    return Optional.<String>empty();
                })
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException response
                            && response.getStatusCode().value() == 404) {
                        // The worker answered, and the answer is "no such domain" — cache it.
                        customDomainCache.put(domain, DOMAIN_NOT_FOUND);
                        return Mono.just(Optional.<String>empty());
                    }
                    // Timeout, connection failure, 5xx: no answer at all. Do NOT negative-cache, or
                    // a live tenant is black-holed for the cache TTL.
                    log.debug("Custom domain lookup failed for {}: {}", domain, e.getMessage());
                    return Mono.just(Optional.<String>empty());
                });
    }

    /**
     * Reads the domain straight from the cache.
     *
     * @return the resolution if the cache holds one (possibly {@link Optional#empty()} for a
     *         negative entry), or {@code null} on a cache miss
     */
    private Optional<String> cachedCustomDomain(String domain) {
        String cached = customDomainCache.getIfPresent(domain);
        if (cached == null) {
            return null;
        }
        return DOMAIN_NOT_FOUND.equals(cached) ? Optional.empty() : Optional.of(cached);
    }

    // ── Guest Profile Cache ───────────────────────────────────────────────

    /**
     * Sentinel value indicating a tenant was looked up and has no Guest profile configured.
     */
    private static final String GUEST_PROFILE_NOT_FOUND = "__NOT_FOUND__";

    /**
     * Resolves a tenant's Guest profile id without blocking — the variant the gateway filter
     * chain must use, since {@link JwtAuthenticationFilter} runs on a non-blocking thread.
     *
     * <p>Three-tier lookup: local cache → worker {@code /internal/tenants/{id}/guest-profile}
     * → not found. A definitive "no Guest profile" is negative-cached (most tenants never
     * configure one, so this keeps every anonymous request from hitting the worker); a lookup
     * that *failed* is not, for the same reason {@link #resolveCustomDomainReactive} doesn't —
     * a transient worker blip must not pin a tenant that actually has a Guest profile to "none"
     * for the cache TTL.
     *
     * @param tenantId the tenant id (not slug — the JWT filter already resolves this)
     * @return the Guest profile id if the tenant has one configured, empty otherwise
     */
    public Mono<Optional<String>> resolveGuestProfileReactive(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Mono.just(Optional.empty());
        }
        String cached = guestProfileCache.getIfPresent(tenantId);
        if (cached != null) {
            return Mono.just(GUEST_PROFILE_NOT_FOUND.equals(cached) ? Optional.empty() : Optional.of(cached));
        }
        return Mono.defer(() -> webClient.get()
                        .uri("/internal/tenants/{tenantId}/guest-profile", tenantId)
                        .retrieve()
                        .bodyToMono(String.class))
                .timeout(SLUG_MAP_TIMEOUT)
                .defaultIfEmpty("")
                .map(resolved -> {
                    if (!resolved.isBlank()) {
                        guestProfileCache.put(tenantId, resolved);
                        return Optional.of(resolved);
                    }
                    guestProfileCache.put(tenantId, GUEST_PROFILE_NOT_FOUND);
                    return Optional.<String>empty();
                })
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException response
                            && response.getStatusCode().value() == 404) {
                        guestProfileCache.put(tenantId, GUEST_PROFILE_NOT_FOUND);
                        return Mono.just(Optional.<String>empty());
                    }
                    log.debug("Guest profile lookup failed for tenant {}: {}", tenantId, e.getMessage());
                    return Mono.just(Optional.<String>empty());
                });
    }

    /**
     * Registers a custom domain mapping in the local cache.
     */
    public void registerCustomDomain(String domain, String tenantSlug) {
        customDomainCache.put(domain, tenantSlug);
    }

    /**
     * Removes a custom domain mapping from the local cache.
     * Evicts the entry entirely so the next lookup will re-fetch from the worker.
     */
    public void removeCustomDomain(String domain) {
        customDomainCache.invalidate(domain);
    }

    /**
     * Evicts all custom domain cache entries (positive and negative).
     * Used as a fallback when a domain change event lacks the specific domain name.
     */
    public void evictAllCustomDomains() {
        customDomainCache.invalidateAll();
        log.info("Evicted all custom domain cache entries");
    }

    // ── System Collection Response Cache ─────────────────────────────────

    /**
     * Returns a cached system collection response for the given cache key.
     *
     * @param cacheKey the composite key (tenantId:path with query string)
     * @return the cached response body bytes, or empty if not cached
     */
    public Optional<byte[]> getSystemCollectionResponse(String cacheKey) {
        return Optional.ofNullable(systemCollectionResponseCache.getIfPresent(cacheKey));
    }

    /**
     * Caches a system collection response.
     *
     * @param cacheKey     the composite key
     * @param responseBody the response body bytes to cache
     */
    public void putSystemCollectionResponse(String cacheKey, byte[] responseBody) {
        systemCollectionResponseCache.put(cacheKey, responseBody);
    }

    /**
     * Evicts cached system collection responses for a specific collection.
     *
     * <p>Since cache keys include the collection name in the path segment,
     * this method removes all entries whose key contains {@code /api/<collectionName>}.
     *
     * @param collectionName the collection name whose cached responses should be evicted
     */
    public void evictSystemCollectionResponses(String collectionName) {
        String pathSegment = "/api/" + collectionName;
        systemCollectionResponseCache.asMap().keySet()
                .removeIf(key -> key.contains(pathSegment));
        log.info("Evicted system collection response cache entries for: {}", collectionName);
    }

    /**
     * Evicts all cached system collection responses.
     */
    public void evictAllSystemCollectionResponses() {
        systemCollectionResponseCache.invalidateAll();
        log.info("Evicted all system collection response cache entries");
    }

    /**
     * Returns the number of cached system collection responses.
     */
    public long systemCollectionResponseCacheSize() {
        return systemCollectionResponseCache.estimatedSize();
    }
}
