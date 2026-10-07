package io.kelta.gateway.auth;

import io.kelta.gateway.filter.HeaderTransformationFilter;
import io.kelta.gateway.filter.TenantResolutionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A PAT's principal is built by the gateway, so it carries no {@code user_type} claim of its own:
 * the PAT owner's type comes from the worker identity lookup. Before this, every PAT — a portal
 * member's included — was stamped {@code X-User-Type: INTERNAL}.
 */
@DisplayName("UserIdentityResolutionFilter")
class UserIdentityResolutionFilterTest {

    private static final String TENANT = "tenant-1";
    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";

    private ReactiveValueOperations<String, String> valueOps;
    private UserIdentityResolutionFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        PublicPathMatcher publicPaths = mock(PublicPathMatcher.class);
        filter = new UserIdentityResolutionFilter(WebClient.builder(), redis, new ObjectMapper(),
                publicPaths, "http://worker", 5);
    }

    private static GatewayPrincipal patPrincipal() {
        return new GatewayPrincipal("member@example.com", List.of(), Map.of(
                "sub", OWNER_ID, "pat", "true", "pat_scopes", "[\"api\"]")).withTenantId(TENANT);
    }

    private void identityIs(String userType) {
        when(valueOps.get(anyString())).thenReturn(Mono.just("""
                {"userId":"%s","profileId":"profile-1","profileName":"Portal User","userType":"%s"}
                """.formatted(OWNER_ID, userType)));
    }

    /** Runs identity resolution then header transformation, returning the forwarded request headers. */
    private ServerWebExchange forward(GatewayPrincipal principal) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/acme/api/watches").build());
        exchange.getAttributes().put("gateway.principal", principal);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, TENANT);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain downstream = e -> {
            forwarded.set(e);
            return Mono.empty();
        };
        HeaderTransformationFilter headers = new HeaderTransformationFilter();
        // Deferred like Spring's DefaultGatewayFilterChain: the next filter runs only once
        // identity resolution has completed.
        GatewayFilterChain chain = e -> Mono.defer(() -> headers.filter(e, downstream));
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return forwarded.get();
    }

    @Test
    @DisplayName("a PAT whose owner is PORTAL is forwarded with X-User-Type: PORTAL")
    void portalOwnerPatIsPortal() {
        identityIs("PORTAL");

        ServerWebExchange forwarded = forward(patPrincipal());

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Type")).isEqualTo("PORTAL");
        GatewayPrincipal resolved = JwtAuthenticationFilter.getPrincipal(forwarded);
        assertThat(resolved.getUserId()).isEqualTo(OWNER_ID);
        assertThat(resolved.getProfileId()).isEqualTo("profile-1");
    }

    @Test
    @DisplayName("a PAT whose owner is INTERNAL is forwarded with X-User-Type: INTERNAL")
    void internalOwnerPatIsInternal() {
        identityIs("INTERNAL");

        ServerWebExchange forwarded = forward(patPrincipal());

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Type")).isEqualTo("INTERNAL");
    }

    @Test
    @DisplayName("a JWT's own user_type claim is never overridden by the lookup")
    void jwtUserTypeIsTheTokens() {
        GatewayPrincipal jwt = new GatewayPrincipal("staff@example.com", List.of(),
                Map.of("sub", "staff@example.com", "user_type", "INTERNAL"));

        GatewayPrincipal enriched = UserIdentityResolutionFilter.enrichFromIdentity(
                Map.of("userId", OWNER_ID, "userType", "PORTAL"), jwt);

        assertThat(enriched.getClaims().get("user_type")).isEqualTo("INTERNAL");
        assertThat(enriched.getUserId()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("an anonymous Guest principal (profile, no sub) never triggers a lookup")
    void guestSkipsLookup() {
        GatewayPrincipal guest = new GatewayPrincipal("00000000-0000-0000-0000-000000000000", List.of(),
                Map.of(), "guest-profile", "Guest", TENANT, null, null);

        forward(guest);

        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("a token that already carries its profile keeps it; a UUID sub is the user id with no lookup")
    void profileClaimsSkipLookupWhenSubIsUuid() {
        GatewayPrincipal kelta = new GatewayPrincipal("staff@example.com", List.of(),
                Map.of("sub", OWNER_ID), "profile-jwt", "Staff", TENANT, null, null);

        ServerWebExchange forwarded = forward(kelta);

        GatewayPrincipal resolved = JwtAuthenticationFilter.getPrincipal(forwarded);
        assertThat(resolved.getUserId()).isEqualTo(OWNER_ID);
        assertThat(resolved.getProfileId()).isEqualTo("profile-jwt");
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("a token with its profile but an email sub looks up only the user id, keeping its profile")
    void emailSubLooksUpUserId() {
        identityIs("INTERNAL");
        GatewayPrincipal authCode = new GatewayPrincipal("staff@example.com", List.of(),
                Map.of("sub", "staff@example.com"), "profile-jwt", "Staff", TENANT, null, null);

        GatewayPrincipal resolved = JwtAuthenticationFilter.getPrincipal(forward(authCode));

        assertThat(resolved.getUserId()).isEqualTo(OWNER_ID);
        assertThat(resolved.getProfileId()).isEqualTo("profile-jwt");
    }
}
