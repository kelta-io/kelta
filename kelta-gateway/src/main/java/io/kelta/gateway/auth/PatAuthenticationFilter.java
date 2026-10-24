package io.kelta.gateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;

/**
 * Global filter for Personal Access Token (PAT) authentication.
 *
 * <p>Handles Bearer tokens with the {@code klt_} prefix. The JWT filter
 * skips these tokens, delegating to this filter.
 *
 * <p>Validation strategy: Redis first, worker API fallback.
 * Token metadata is cached in Redis by the worker on creation.
 * If Redis misses, the gateway calls the worker's validation endpoint
 * which re-caches the data in Redis.
 *
 * <p>Worker-outage resilience: the JSON of every PAT this filter accepts is stored
 * in a bounded, short-TTL in-memory grace cache (default 10,000 entries, 5 min
 * after write). When the worker is unreachable and Redis misses, the grace cache
 * serves the last known-good token data so callers ride out rolling restarts
 * without spurious 401s. Revocation is always checked first — a revoked token is
 * rejected and its grace entry evicted, even when the grace cache holds its data.
 *
 * <p>Last-used tracking: after a successful authentication the filter tells the worker
 * ({@code POST /internal/tokens/{hash}/last-used}) to stamp {@code user_api_token.last_used_at},
 * fire-and-forget, at most once per token per window on this replica (a bounded Caffeine cache
 * keyed by token hash). The worker's UPDATE is itself conditional on the window, so replicas
 * cannot stamp the same token twice in one window either.
 *
 * @since 1.0.0
 */
@Component
public class PatAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PatAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PAT_PREFIX = "klt_";
    private static final String PRINCIPAL_ATTRIBUTE = "gateway.principal";
    private static final String PAT_KEY_PREFIX = "pat:";
    private static final String REVOCATION_KEY_PREFIX = "pat:revoked:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration LAST_USED_TIMEOUT = Duration.ofSeconds(5);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient workerClient;
    private final GatewayMetrics metrics;
    private final RedisRateLimiter redisRateLimiter;
    private final long lastUsedWindowSeconds;
    private final Cache<String, Boolean> lastUsedThrottle;

    // In-memory fallback keyed by token hash, holding the PAT JSON. Written only once
    // authenticateWithPat has accepted the token, so expired, unparseable and cross-tenant
    // tokens never enter it. Bounded in size and expiring a fixed time after the write,
    // so neither a flood of distinct tokens nor a long worker outage keeps data alive.
    // Revocation is always checked before this cache is consulted, and a revoked hit
    // evicts the entry.
    private final Cache<String, String> graceCache;

    /** PAT JSON plus whether it came from the grace cache (which must not re-arm its TTL). */
    private record PatLookup(String json, boolean fromGraceCache) {}

    public PatAuthenticationFilter(
            ReactiveStringRedisTemplate redisTemplate,
            WebClient.Builder webClientBuilder,
            @Value("${kelta.gateway.worker-service-url:http://kelta-worker:80}") String workerServiceUrl,
            @Value("${kelta.gateway.pat-grace-ttl-seconds:300}") int graceTtlSeconds,
            @Value("${kelta.gateway.pat-grace-max-entries:10000}") long graceMaxEntries,
            GatewayMetrics metrics,
            RedisRateLimiter redisRateLimiter,
            @Value("${kelta.gateway.pat-last-used.window-seconds:300}") long lastUsedWindowSeconds,
            @Value("${kelta.gateway.pat-last-used.max-tokens:10000}") long lastUsedMaxTokens) {
        this.redisTemplate = redisTemplate;
        this.workerClient = webClientBuilder.baseUrl(workerServiceUrl).build();
        this.graceCache = Caffeine.newBuilder()
                .maximumSize(graceMaxEntries)
                .expireAfterWrite(Duration.ofSeconds(graceTtlSeconds))
                .build();
        this.metrics = metrics;
        this.redisRateLimiter = redisRateLimiter;
        this.lastUsedWindowSeconds = lastUsedWindowSeconds;
        this.lastUsedThrottle = Caffeine.newBuilder()
                .maximumSize(lastUsedMaxTokens)
                .expireAfterWrite(Duration.ofSeconds(lastUsedWindowSeconds))
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Only handle PAT tokens — skip if already authenticated or not a PAT
        GatewayPrincipal existing = exchange.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (existing != null) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        if (!token.startsWith(PAT_PREFIX)) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
        String tokenHash = sha256(token);

        // Check revocation first — a revoked token is always rejected, even when
        // the grace cache holds its PAT data.
        return redisTemplate.opsForValue().get(REVOCATION_KEY_PREFIX + tokenHash)
                .hasElement()
                .flatMap(isRevoked -> {
                    if (Boolean.TRUE.equals(isRevoked)) {
                        graceCache.invalidate(tokenHash);
                        log.warn("Revoked PAT used for path: {}", path);
                        metrics.recordAuthFailure(tenantSlug, "revoked_pat");
                        return unauthorized(exchange, "Token has been revoked");
                    }
                    // Try Redis first, fall back to worker (with grace cache on worker error).
                    //
                    // The "unknown token" branch keys off whether the *lookup* produced JSON,
                    // never off whether the downstream Mono emitted. authenticateWithPat ends in
                    // chain.filter(...), a Mono<Void> that always completes empty — so hanging a
                    // switchIfEmpty off it fired on every successful request, logging "Unknown
                    // PAT" ~10k times a day and recording an unknown_pat auth failure for each,
                    // which made the metric useless for spotting real ones. (The bogus 401 that
                    // followed was swallowed only because the response was already committed.)
                    return redisTemplate.opsForValue().get(PAT_KEY_PREFIX + tokenHash)
                            .map(patJson -> new PatLookup(patJson, false))
                            .switchIfEmpty(fetchFromWorker(tokenHash))
                            .flatMap(lookup ->
                                    authenticateWithPat(lookup, exchange, chain, path, tenantSlug, tokenHash)
                                            .thenReturn(Boolean.TRUE))
                            .defaultIfEmpty(Boolean.FALSE)
                            .flatMap(recognized -> {
                                if (Boolean.TRUE.equals(recognized)) {
                                    return Mono.empty();
                                }
                                log.warn("Unknown PAT used for path: {}", path);
                                metrics.recordAuthFailure(tenantSlug, "unknown_pat");
                                return unauthorized(exchange, "Invalid or expired token");
                            });
                });
    }

    /**
     * Fallback: call the worker's PAT validation endpoint.
     *
     * <p>A 404 is the worker's normal answer for a token it doesn't know, so it stays at debug.
     * For any other error (worker down, timeout, 5xx — exactly the connection-refused errors
     * seen during rolling restarts) the grace cache is consulted before giving up. This lets
     * callers ride out a brief worker outage without receiving spurious 401s for valid tokens
     * that were successfully validated within the grace window.
     */
    private Mono<PatLookup> fetchFromWorker(String tokenHash) {
        return workerClient.get()
                .uri("/api/me/tokens/validate/{hash}", tokenHash)
                .retrieve()
                .bodyToMono(String.class)
                .map(patJson -> new PatLookup(patJson, false))
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException.NotFound) {
                        log.debug("Worker does not recognise this PAT hash");
                    } else {
                        log.warn("Worker PAT validation is failing — PATs missing from the Redis "
                                + "cache will be rejected as invalid until it recovers: {}",
                                e.toString());
                        String cached = graceCache.getIfPresent(tokenHash);
                        if (cached != null) {
                            log.debug("Serving PAT from grace cache during worker outage");
                            return Mono.just(new PatLookup(cached, true));
                        }
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> authenticateWithPat(PatLookup lookup, ServerWebExchange exchange,
                                            GatewayFilterChain chain, String path, String tenantSlug,
                                            String tokenHash) {
        String patJson = lookup.json();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> patData = OBJECT_MAPPER.readValue(patJson, Map.class);

            // Validate expiry
            String expiresAt = (String) patData.get("expiresAt");
            if (expiresAt != null && !expiresAt.isEmpty()) {
                try {
                    java.time.Instant expiry = java.time.Instant.parse(expiresAt);
                    if (expiry.isBefore(java.time.Instant.now())) {
                        log.warn("Expired PAT used for path: {}", path);
                        metrics.recordAuthFailure(tenantSlug, "expired_pat");
                        return unauthorized(exchange, "Token has expired");
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse PAT expiry: {}", expiresAt);
                }
            }

            String userId = (String) patData.get("userId");
            String tenantId = (String) patData.get("tenantId");
            String email = (String) patData.get("email");
            String scopes = patData.get("scopes") != null
                    ? patData.get("scopes").toString() : "[\"api\"]";

            // Same rule as JwtAuthenticationFilter: a token issued in tenant A is never accepted
            // on tenant B's URL. Identity is resolved in the URL's tenant downstream, so without
            // this check the token's own tenant would not bound what it can reach.
            // A tenant claimed only by the X-Tenant-ID / X-Tenant-Slug header must be the token's
            // own, so a token that records no tenant cannot use one either (PLT-355).
            String requestTenantId = TenantResolutionFilter.getTenantId(exchange);
            boolean hasTokenTenant = tenantId != null && !tenantId.isEmpty();
            if ((hasTokenTenant && requestTenantId != null && !requestTenantId.isEmpty()
                    && !tenantId.equals(requestTenantId))
                    || (!hasTokenTenant && TenantResolutionFilter.isHeaderSourced(exchange))) {
                log.warn("Cross-tenant PAT use rejected: token tenant={} request tenant={} path={}",
                        tenantId, requestTenantId, path);
                metrics.recordAuthFailure(tenantSlug, "tenant_mismatch");
                return unauthorized(exchange, "Token is not valid for this tenant");
            }

            GatewayPrincipal principal = new GatewayPrincipal(
                    email, Collections.emptyList(), Map.of(
                    "sub", userId,
                    "pat", "true",
                    "pat_scopes", scopes
            )).withTenantId(tenantId).withUserId(userId);

            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(r -> r.header("X-User-Id", userId))
                    .build();
            mutatedExchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);

            if (!lookup.fromGraceCache()) {
                graceCache.put(tokenHash, patJson);
            }
            log.debug("PAT authenticated user {} for path: {}", email, path);
            // Fire-and-forget, mirroring RateLimitFilter's incrementDailyCounter — must
            // not delay or fail the request if Redis is briefly unavailable.
            redisRateLimiter.incrementPatUsageCounter(tokenHash).subscribe();
            recordLastUsed(tokenHash);
            return chain.filter(mutatedExchange);
        } catch (JacksonException e) {
            log.error("Failed to parse PAT data", e);
            metrics.recordAuthFailure(tenantSlug, "pat_parse_error");
            return unauthorized(exchange, "Authentication failed");
        }
    }

    /**
     * Fire-and-forget: subscribed off the request chain, so a slow or failing worker never
     * delays or fails the request. A failed call is not retried until the window expires.
     */
    private void recordLastUsed(String tokenHash) {
        if (lastUsedThrottle.asMap().putIfAbsent(tokenHash, Boolean.TRUE) != null) {
            return;
        }
        workerClient.post()
                .uri(uri -> uri.path("/internal/tokens/{hash}/last-used")
                        .queryParam("windowSeconds", lastUsedWindowSeconds)
                        .build(tokenHash))
                .retrieve()
                .toBodilessEntity()
                .timeout(LAST_USED_TIMEOUT)
                .subscribe(
                        response -> { },
                        e -> log.warn("Failed to record PAT last-used time: {}", e.toString()));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.UNAUTHORIZED)) {
            return Mono.empty();
        }

        String path = exchange.getRequest().getPath().value();
        String errorJson;
        try {
            errorJson = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "error", Map.of(
                            "status", 401,
                            "code", "UNAUTHORIZED",
                            "message", message,
                            "path", path
                    )
            ));
        } catch (JacksonException e) {
            log.error("Failed to serialize error response", e);
            errorJson = "{\"error\":{\"status\":401,\"code\":\"UNAUTHORIZED\"}}";
        }

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(errorJson.getBytes()))
        );
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    Cache<String, String> graceCache() {
        return graceCache;
    }

    @Override
    public int getOrder() {
        return -99; // Run after JwtAuthenticationFilter (-100)
    }
}
