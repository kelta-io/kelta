package io.kelta.gateway.auth;

import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rejects the access tokens of a user who has been deactivated or deleted. Access tokens are
 * self-contained JWTs validated by signature only, so they would otherwise stay valid until they
 * expire. The worker's {@code UserAccessRevocationMarker} writes
 * {@code user:revoked:<tenantId>:<userId>} = the revocation time in epoch seconds; a JWT of that
 * user whose {@code iat} is at or before it gets the same 401 {@code JwtAuthenticationFilter}
 * answers. A token issued after it — the user was reactivated and signed in again — passes.
 *
 * <p>Skipped for public paths, unauthenticated requests, principals without a resolved
 * {@code userId} (Guest, connected-app machine tokens) and PATs, which have their own
 * {@code pat:revoked:} revocation.
 *
 * <p>Fails open when Redis is unreachable (WARN log + {@code user_revocation_unchecked} metric),
 * like {@code UserIdentityResolutionFilter}: kelta-auth has already revoked the user's refresh
 * tokens and sessions, so exposure stays bounded by the access-token TTL, whereas failing closed
 * would turn a Redis outage into a 401 for every signed-in user.
 *
 * <p>Order: -47 (after {@link UserIdentityResolutionFilter} at -50 has put the {@code userId}
 * on the principal, before route authorization at 0).
 */
@Component
public class RevokedUserFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RevokedUserFilter.class);

    static final String KEY_PREFIX = "user:revoked:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final PublicPathMatcher publicPathMatcher;
    private final GatewayMetrics metrics;

    public RevokedUserFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                             PublicPathMatcher publicPathMatcher,
                             GatewayMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.publicPathMatcher = publicPathMatcher;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (publicPathMatcher.isPublicRequest(exchange)) {
            return chain.filter(exchange);
        }
        GatewayPrincipal principal = JwtAuthenticationFilter.getPrincipal(exchange);
        if (principal == null || principal.getUserId() == null
                || "true".equals(principal.getClaims().get("pat"))) {
            return chain.filter(exchange);
        }
        String tenantId = principal.getTenantId() != null
                ? principal.getTenantId()
                : TenantResolutionFilter.getTenantId(exchange);
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }
        String userId = principal.getUserId();

        return redisTemplate.opsForValue().get(KEY_PREFIX + tenantId + ":" + userId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("Could not check access revocation of user {} (tenant {}); allowing: {}",
                            userId, tenantId, e.getMessage());
                    metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange),
                            "user_revocation_unchecked");
                    return Mono.just(Optional.empty());
                })
                .flatMap(marker -> {
                    if (marker.isPresent() && isRevoked(marker.get(), principal.getClaims().get("iat"))) {
                        log.info("Rejected an access token of revoked user {} (tenant {})", userId, tenantId);
                        metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "user_revoked");
                        return unauthorized(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    /**
     * {@code true} when the token was issued at or before the revocation. A token without a
     * readable {@code iat}, or a marker that is not a number, counts as revoked: the marker says
     * the user was revoked and nothing shows this token came after it.
     */
    static boolean isRevoked(String marker, Object iat) {
        long revokedAt;
        try {
            revokedAt = Long.parseLong(marker.trim());
        } catch (NumberFormatException e) {
            return true;
        }
        Long issuedAt = epochSeconds(iat);
        return issuedAt == null || issuedAt <= revokedAt;
    }

    private static Long epochSeconds(Object iat) {
        if (iat instanceof Instant instant) {
            return instant.getEpochSecond();
        }
        if (iat instanceof Number number) {
            return number.longValue();
        }
        if (iat instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Same body as {@code JwtAuthenticationFilter.unauthorized} for an invalid token. */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.UNAUTHORIZED)) {
            return Mono.empty();
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "401");
        error.put("code", "UNAUTHORIZED");
        error.put("title", "Unauthorized");
        error.put("detail", "Invalid or expired JWT token");
        error.put("meta", Map.of("path", exchange.getRequest().getPath().value()));
        byte[] body = OBJECT_MAPPER.writeValueAsString(Map.of("errors", List.of(error)))
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -47;
    }
}
