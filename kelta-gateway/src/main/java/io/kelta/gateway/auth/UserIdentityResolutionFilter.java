package io.kelta.gateway.auth;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.kelta.gateway.filter.TenantResolutionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the user's identity (profileId, profileName) from the worker
 * and enriches the {@link GatewayPrincipal}.
 *
 * <p>Order: -50 (after JWT authentication at -100, before route authorization at 0).
 */
@Component
public class UserIdentityResolutionFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityResolutionFilter.class);
    private static final String CACHE_KEY_PREFIX = "user-identity:";
    private static final String PRINCIPAL_ATTRIBUTE = "gateway.principal";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final PublicPathMatcher publicPathMatcher;
    private final Duration cacheTtl;

    public UserIdentityResolutionFilter(
            WebClient.Builder webClientBuilder,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            PublicPathMatcher publicPathMatcher,
            @Value("${kelta.gateway.worker-service-url:http://emf-worker:80}") String workerServiceUrl,
            @Value("${kelta.gateway.security.identity-cache-ttl-minutes:5}") int cacheTtlMinutes) {
        this.webClient = webClientBuilder.baseUrl(workerServiceUrl).build();
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.publicPathMatcher = publicPathMatcher;
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (publicPathMatcher.isPublicRequest(exchange)) {
            return chain.filter(exchange);
        }

        GatewayPrincipal principal = JwtAuthenticationFilter.getPrincipal(exchange);
        if (principal == null) {
            return chain.filter(exchange);
        }

        String tenantId = TenantResolutionFilter.getTenantId(exchange);
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }

        // Set tenantId on principal from URL resolution (if not already set from JWT claims)
        if (principal.getTenantId() == null) {
            principal = principal.withTenantId(tenantId);
            exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
        }

        // Skip worker lookup when the profile is already in the JWT claims (kelta-auth tokens)
        // and the caller's platform_user UUID is known too. This eliminates the synchronous
        // worker call per request for kelta-auth-issued tokens. A kelta-auth `sub` is the UUID on
        // the direct-login and portal paths but the email on the authorization-code path, so
        // those tokens still take the (Redis-cached) lookup for the UUID alone.
        if (principal.getProfileId() != null && !principal.getProfileId().isEmpty()) {
            if (principal.getUserId() == null && isUuid(principal.getClaims().get("sub"))) {
                principal = principal.withUserId((String) principal.getClaims().get("sub"));
                exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
            }
            if (principal.getUserId() != null) {
                log.debug("Profile already resolved from JWT claims for user: {}", principal.getUsername());
                return chain.filter(exchange);
            }
        }

        String email = principal.getUsername();
        String cacheKey = CACHE_KEY_PREFIX + tenantId + ":" + email;

        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> {
                    GatewayPrincipal current = JwtAuthenticationFilter.getPrincipal(exchange);
                    GatewayPrincipal enriched = enrichFromIdentityJson(json, current);
                    if (enriched != current) {
                        exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, enriched);
                    }
                    return Mono.just(json);
                })
                .switchIfEmpty(fetchFromWorker(tenantId, email, exchange, cacheKey))
                .then(chain.filter(exchange));
    }

    /**
     * Applies the worker's identity lookup ({@code userId}, {@code profileId},
     * {@code profileName}, {@code userType}) to the principal.
     *
     * <p>A profile the token already carries is kept. A PAT's principal is built by the
     * gateway, not from a token, so its {@code user_type} claim comes from here — the PAT
     * owner's {@code platform_user.user_type} — and {@code HeaderTransformationFilter} stamps it
     * as {@code X-User-Type}. Without it every PAT, a portal member's included, read as INTERNAL.
     */
    static GatewayPrincipal enrichFromIdentity(Map<String, String> identity, GatewayPrincipal principal) {
        GatewayPrincipal enriched = principal;
        if (enriched.getProfileId() == null || enriched.getProfileId().isEmpty()) {
            enriched = enriched
                    .withProfileId(identity.get("profileId"))
                    .withProfileName(identity.get("profileName"));
        }
        if (enriched.getUserId() == null && isUuid(identity.get("userId"))) {
            enriched = enriched.withUserId(identity.get("userId"));
        }
        String userType = identity.get("userType");
        if ("true".equals(enriched.getClaims().get("pat")) && userType != null && !userType.isBlank()) {
            enriched = enriched.withClaim("user_type", userType);
        }
        return enriched.equals(principal) ? principal : enriched;
    }

    private GatewayPrincipal enrichFromIdentityJson(String json, GatewayPrincipal principal) {
        try {
            Map<String, String> identity = objectMapper.readValue(json,
                    new TypeReference<Map<String, String>>() {});
            return enrichFromIdentity(identity, principal);
        } catch (Exception e) {
            log.warn("Failed to parse cached user identity: {}", e.getMessage());
            return principal;
        }
    }

    private static boolean isUuid(Object value) {
        return value instanceof String s && UUID_PATTERN.matcher(s).matches();
    }

    private Mono<String> fetchFromWorker(String tenantId, String email,
                                         ServerWebExchange exchange, String cacheKey) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/user-identity")
                        .queryParam("email", email)
                        .queryParam("tenantId", tenantId)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(json -> {
                    GatewayPrincipal current = JwtAuthenticationFilter.getPrincipal(exchange);
                    GatewayPrincipal enriched = enrichFromIdentityJson(json, current);
                    if (enriched != current) {
                        exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, enriched);
                    }
                    return redisTemplate.opsForValue().set(cacheKey, json, cacheTtl)
                            .thenReturn(json);
                })
                .onErrorResume(e -> {
                    log.warn("Failed to fetch user identity for {}/{}: {}",
                            tenantId, email, e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
