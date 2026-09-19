package io.kelta.gateway.auth;

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
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Worker-outage resilience: every successfully resolved PAT JSON is stored
 * in a short-TTL in-memory grace cache (default 5 min). When the worker is
 * unreachable and Redis misses, the grace cache serves the last known-good
 * token data so callers ride out rolling restarts without spurious 401s.
 * Revocation is always checked first — a revoked token is rejected even when
 * the grace cache holds its data.
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

    private final ReactiveStringRedisTemplate redisTemplate;
    private final WebClient workerClient;
    private final GatewayMetrics metrics;
    private final Duration graceTtl;
    private final RedisRateLimiter redisRateLimiter;

    // Short-TTL in-memory fallback keyed by token hash. Populated on every
    // successful PAT resolution (Redis hit or worker call) so requests can
    // authenticate during worker outages without hitting Redis-only callers
    // with spurious 401s. Revocation is always checked before this cache is
    // ever consulted, so a revoked token cannot ride through stale fallback data.
    private final ConcurrentHashMap<String, CachedPat> graceCache = new ConcurrentHashMap<>();

    private record CachedPat(String json, Instant cachedAt) {
        boolean isExpired(Duration ttl) {
            return cachedAt.plus(ttl).isBefore(Instant.now());
        }
    }

    public PatAuthenticationFilter(
            ReactiveStringRedisTemplate redisTemplate,
            WebClient.Builder webClientBuilder,
            @Value("${kelta.gateway.worker-service-url:http://kelta-worker:80}") String workerServiceUrl,
            @Value("${kelta.gateway.pat-grace-ttl-seconds:300}") int graceTtlSeconds,
            GatewayMetrics metrics,
            RedisRateLimiter redisRateLimiter) {
        this.redisTemplate = redisTemplate;
        this.workerClient = webClientBuilder.baseUrl(workerServiceUrl).build();
        this.graceTtl = Duration.ofSeconds(graceTtlSeconds);
        this.metrics = metrics;
        this.redisRateLimiter = redisRateLimiter;
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
                            .switchIfEmpty(fetchFromWorker(tokenHash))
                            .doOnNext(patJson ->
                                    graceCache.put(tokenHash, new CachedPat(patJson, Instant.now())))
                            .flatMap(patJson ->
                                    authenticateWithPat(patJson, exchange, chain, path, tenantSlug, tokenHash)
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
    private Mono<String> fetchFromWorker(String tokenHash) {
        return workerClient.get()
                .uri("/api/me/tokens/validate/{hash}", tokenHash)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException.NotFound) {
                        log.debug("Worker does not recognise this PAT hash");
                    } else {
                        log.warn("Worker PAT validation is failing — PATs missing from the Redis "
                                + "cache will be rejected as invalid until it recovers: {}",
                                e.toString());
                        CachedPat cached = graceCache.get(tokenHash);
                        if (cached != null && !cached.isExpired(graceTtl)) {
                            log.debug("Serving PAT from grace cache during worker outage");
                            return Mono.just(cached.json());
                        }
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> authenticateWithPat(String patJson, ServerWebExchange exchange,
                                            GatewayFilterChain chain, String path, String tenantSlug,
                                            String tokenHash) {
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

            GatewayPrincipal principal = new GatewayPrincipal(
                    email, Collections.emptyList(), Map.of(
                    "sub", userId,
                    "pat", "true",
                    "pat_scopes", scopes
            )).withTenantId(tenantId);

            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(r -> r.header("X-User-Id", userId))
                    .build();
            mutatedExchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);

            log.debug("PAT authenticated user {} for path: {}", email, path);
            // Fire-and-forget, mirroring RateLimitFilter's incrementDailyCounter — must
            // not delay or fail the request if Redis is briefly unavailable.
            redisRateLimiter.incrementPatUsageCounter(tokenHash).subscribe();
            return chain.filter(mutatedExchange);
        } catch (JacksonException e) {
            log.error("Failed to parse PAT data", e);
            metrics.recordAuthFailure(tenantSlug, "pat_parse_error");
            return unauthorized(exchange, "Authentication failed");
        }
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

    @Override
    public int getOrder() {
        return -99; // Run after JwtAuthenticationFilter (-100)
    }
}
