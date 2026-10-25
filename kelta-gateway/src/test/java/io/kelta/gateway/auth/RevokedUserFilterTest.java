package io.kelta.gateway.auth;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RevokedUserFilter")
class RevokedUserFilterTest {

    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String MARKER_KEY = "user:revoked:" + TENANT + ":" + USER_ID;
    private static final Instant REVOKED_AT = Instant.parse("2026-10-25T18:00:00Z");

    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveValueOperations<String, String> valueOps;
    private PublicPathMatcher publicPaths;
    private GatewayMetrics metrics;
    private RevokedUserFilter filter;
    private final AtomicBoolean forwarded = new AtomicBoolean(false);
    private final GatewayFilterChain chain = e -> {
        forwarded.set(true);
        return Mono.empty();
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        publicPaths = mock(PublicPathMatcher.class);
        metrics = mock(GatewayMetrics.class);
        filter = new RevokedUserFilter(redis, publicPaths, metrics);
    }

    private static GatewayPrincipal jwtPrincipal(Object iat) {
        return new GatewayPrincipal("member@example.com", List.of(),
                Map.of("sub", USER_ID, "tenant_id", TENANT, "iat", iat),
                "profile-1", "Portal User", TENANT, null, null, null, USER_ID);
    }

    private MockServerWebExchange exchange(GatewayPrincipal principal) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/acme/api/watches").build());
        if (principal != null) {
            exchange.getAttributes().put("gateway.principal", principal);
        }
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, TENANT);
        return exchange;
    }

    private void markerIs(Instant revokedAt) {
        when(valueOps.get(MARKER_KEY)).thenReturn(Mono.just(Long.toString(revokedAt.getEpochSecond())));
    }

    private MockServerWebExchange run(GatewayPrincipal principal) {
        MockServerWebExchange exchange = exchange(principal);
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return exchange;
    }

    @Test
    @DisplayName("runs after user identity resolution (-50) and before route authorization (0)")
    void order() {
        assertThat(filter.getOrder()).isGreaterThan(-50).isLessThan(0);
    }

    @Test
    @DisplayName("a token issued before the marker gets 401 UNAUTHORIZED")
    void tokenIssuedBeforeRevocationIsRejected() {
        markerIs(REVOKED_AT);

        MockServerWebExchange exchange = run(jwtPrincipal(REVOKED_AT.minusSeconds(600)));

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"UNAUTHORIZED\"");
        verify(metrics).recordAuthFailure(any(), eq("user_revoked"));
    }

    @Test
    @DisplayName("a token issued in the same second as the marker is rejected")
    void tokenIssuedAtRevocationIsRejected() {
        markerIs(REVOKED_AT);

        MockServerWebExchange exchange = run(jwtPrincipal(REVOKED_AT));

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("iat as a number (epoch seconds) is compared the same way")
    void numericIat() {
        markerIs(REVOKED_AT);

        MockServerWebExchange exchange = run(jwtPrincipal(REVOKED_AT.minusSeconds(1).getEpochSecond()));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token issued after the marker (reactivated and signed in again) passes")
    void tokenIssuedAfterRevocationPasses() {
        markerIs(REVOKED_AT);

        MockServerWebExchange exchange = run(jwtPrincipal(REVOKED_AT.plusSeconds(1)));

        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("no marker passes")
    void noMarkerPasses() {
        when(valueOps.get(MARKER_KEY)).thenReturn(Mono.empty());

        run(jwtPrincipal(REVOKED_AT));

        assertThat(forwarded).isTrue();
    }

    @Test
    @DisplayName("a PAT principal skips the check (PATs have pat:revoked:)")
    void patSkips() {
        GatewayPrincipal pat = new GatewayPrincipal("member@example.com", List.of(),
                Map.of("sub", USER_ID, "pat", "true"), "profile-1", "Portal User", TENANT,
                null, null, null, USER_ID);

        run(pat);

        assertThat(forwarded).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("a Guest principal (no userId) skips the check")
    void guestSkips() {
        GatewayPrincipal guest = new GatewayPrincipal(JwtAuthenticationFilter.GUEST_USER_ID, List.of(), Map.of(),
                "guest-profile", JwtAuthenticationFilter.GUEST_PROFILE_NAME, TENANT, null, null);

        run(guest);

        assertThat(forwarded).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("an unauthenticated request skips the check")
    void noPrincipalSkips() {
        run(null);

        assertThat(forwarded).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("public paths skip the check")
    void publicPathSkips() {
        when(publicPaths.isPublicRequest(any(ServerWebExchange.class))).thenReturn(true);

        run(jwtPrincipal(REVOKED_AT.minusSeconds(600)));

        assertThat(forwarded).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("Redis unreachable fails open: the request passes and the miss is counted")
    void redisDownFailsOpen() {
        when(valueOps.get(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("redis down")));

        MockServerWebExchange exchange = run(jwtPrincipal(REVOKED_AT.minusSeconds(600)));

        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(metrics).recordAuthFailure(any(), eq("user_revocation_unchecked"));
    }

    @Test
    @DisplayName("a downstream error is not swallowed as a Redis failure")
    void downstreamErrorPropagates() {
        when(valueOps.get(MARKER_KEY)).thenReturn(Mono.empty());
        GatewayFilterChain failing = e -> Mono.error(new IllegalStateException("downstream"));

        StepVerifier.create(filter.filter(exchange(jwtPrincipal(REVOKED_AT)), failing))
                .verifyErrorMessage("downstream");
    }

    @Test
    @DisplayName("a token without iat, or an unreadable marker, counts as revoked")
    void unreadableValues() {
        assertThat(RevokedUserFilter.isRevoked("1761400000", null)).isTrue();
        assertThat(RevokedUserFilter.isRevoked("not-a-number", Instant.now())).isTrue();
        assertThat(RevokedUserFilter.isRevoked("1761400000", "1761400001")).isFalse();
    }

    @Test
    @DisplayName("the 401 body is the one JwtAuthenticationFilter answers for an invalid token")
    void sameBodyAsJwtAuthenticationFilter() {
        markerIs(REVOKED_AT);
        String revokedBody = run(jwtPrincipal(REVOKED_AT.minusSeconds(600))).getResponse().getBodyAsString().block();

        DynamicReactiveJwtDecoder decoder = mock(DynamicReactiveJwtDecoder.class);
        when(decoder.decode(anyString(), anyString())).thenReturn(Mono.error(new JwtException("bad")));
        when(publicPaths.isPublicRequest(any(ServerWebExchange.class))).thenReturn(false);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(decoder, mock(PrincipalExtractor.class),
                publicPaths, metrics, mock(GatewayCacheManager.class));
        MockServerWebExchange invalid = MockServerWebExchange.from(MockServerHttpRequest.get("/acme/api/watches")
                .header("Authorization", "Bearer not-a-jwt").build());
        invalid.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, TENANT);
        StepVerifier.create(jwtFilter.filter(invalid, chain)).verifyComplete();

        assertThat(invalid.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokedBody).isEqualTo(invalid.getResponse().getBodyAsString().block());
    }
}
