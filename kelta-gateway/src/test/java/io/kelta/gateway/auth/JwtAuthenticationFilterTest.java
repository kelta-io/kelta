package io.kelta.gateway.auth;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import io.kelta.gateway.filter.TenantResolutionFilter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtAuthenticationFilter.
 * Tests authentication flows, edge cases, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {
    
    @Mock
    private DynamicReactiveJwtDecoder jwtDecoder;

    @Mock
    private PrincipalExtractor principalExtractor;

    @Mock
    private PublicPathMatcher publicPathMatcher;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain filterChain;

    @Mock
    private GatewayCacheManager cacheManager;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtDecoder, principalExtractor, publicPathMatcher, metrics, cacheManager);

        // Mock filter chain to return completed Mono (lenient to avoid unnecessary stubbing errors)
        lenient().when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        // Default: no public paths match (most tests require auth)
        lenient().when(publicPathMatcher.isPublicRequest(any(ServerWebExchange.class))).thenReturn(false);
    }
    
    @Test
    void shouldHaveOrderMinus100() {
        assertThat(filter.getOrder()).isEqualTo(-100);
    }
    
    @Test
    void shouldAllowCorsPreflightRequests() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest
            .options("/api/users")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        verify(filterChain).filter(exchange);
        verify(jwtDecoder, never()).decode(anyString());
    }
    
    @Test
    void shouldAllowPublicPathRequestsWithoutAuth() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/ui-pages")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(publicPathMatcher.isPublicRequest(exchange)).thenReturn(true);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        verify(filterChain).filter(exchange);
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void shouldReturn401WhenAuthorizationHeaderIsMissing() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        
        // When
        Mono<Void> result = filter.filter(exchange, filterChain);
        
        // Then
        StepVerifier.create(result)
            .verifyComplete();
        
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
        verify(jwtDecoder, never()).decode(anyString());
    }
    
    @Test
    void shouldReturn401WhenAuthorizationHeaderIsEmpty() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        
        // When
        Mono<Void> result = filter.filter(exchange, filterChain);
        
        // Then
        StepVerifier.create(result)
            .verifyComplete();
        
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
    }
    
    @Test
    void shouldReturn401WhenAuthorizationHeaderDoesNotStartWithBearer() {
        // Given
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        
        // When
        Mono<Void> result = filter.filter(exchange, filterChain);
        
        // Then
        StepVerifier.create(result)
            .verifyComplete();
        
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
    }
    
    @Test
    void shouldAdmitAsGuestWhenTenantHasAGuestProfileAndNoAuthHeader() {
        // Given — no Authorization header, but the tenant has configured a Guest profile
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/listings").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        when(cacheManager.resolveGuestProfileReactive("tenant-1"))
            .thenReturn(Mono.just(java.util.Optional.of("guest-profile-id")));

        when(filterChain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange capturedExchange = invocation.getArgument(0);
            GatewayPrincipal stored = JwtAuthenticationFilter.getPrincipal(capturedExchange);
            assertThat(stored).isNotNull();
            assertThat(stored.getUsername()).isEqualTo("00000000-0000-0000-0000-000000000000");
            assertThat(stored.getProfileId()).isEqualTo("guest-profile-id");
            assertThat(stored.getProfileName()).isEqualTo("Guest");
            assertThat(stored.getTenantId()).isEqualTo("tenant-1");
            return Mono.empty();
        });

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        verify(jwtDecoder, never()).decode(anyString(), anyString());
    }

    @Test
    void shouldReturn401WhenTenantHasNoGuestProfileAndNoAuthHeader() {
        // Given — no Authorization header, tenant has NOT configured a Guest profile
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        when(cacheManager.resolveGuestProfileReactive("tenant-1"))
            .thenReturn(Mono.just(java.util.Optional.empty()));

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then — byte-for-byte the same rejection as before this existed
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
    }

    @Test
    void shouldReturn401WhenTenantContextIsMissing() {
        // Given — Bearer token present but no tenant context set
        String token = "valid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        // No tenant context set on exchange

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
        verify(jwtDecoder, never()).decode(anyString(), anyString());
    }

    @Test
    void shouldReturn401WhenJwtIsInvalid() {
        // Given
        String token = "invalid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        when(jwtDecoder.decode(token, "tenant-1")).thenReturn(Mono.error(new JwtException("Invalid token")));

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
    }

    @Test
    void shouldReturn401WhenJwtIsExpired() {
        // Given
        String token = "expired.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        when(jwtDecoder.decode(token, "tenant-1")).thenReturn(Mono.error(new JwtException("Token expired")));

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
    }

    @Test
    void shouldReturn401WhenPrincipalExtractionFails() {
        // Given
        String token = "valid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        Jwt jwt = createMockJwt("user123", List.of("USER"));
        when(jwtDecoder.decode(token, "tenant-1")).thenReturn(Mono.just(jwt));
        when(principalExtractor.extractPrincipal(jwt))
            .thenThrow(new IllegalArgumentException("Missing required claim"));

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain, never()).filter(any());
    }

    @Test
    void shouldAuthenticateValidJwtAndStorePrincipal() {
        // Given
        String token = "valid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        Jwt jwt = createMockJwt("user123", List.of("USER", "ADMIN"));
        GatewayPrincipal principal = new GatewayPrincipal(
            "user123",
            List.of("USER", "ADMIN"),
            Map.of("sub", "user123", "roles", List.of("USER", "ADMIN"))
        ).withTenantId("tenant-1");

        when(jwtDecoder.decode(token, "tenant-1")).thenReturn(Mono.just(jwt));
        when(principalExtractor.extractPrincipal(jwt)).thenReturn(principal);

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();

        verify(filterChain).filter(any(ServerWebExchange.class));
        verify(jwtDecoder).decode(token, "tenant-1");
        verify(principalExtractor).extractPrincipal(jwt);
    }

    @Test
    void shouldStorePrincipalInExchangeAttributes() {
        // Given
        String token = "valid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        Jwt jwt = createMockJwt("user123", List.of("USER"));
        GatewayPrincipal principal = new GatewayPrincipal(
            "user123",
            List.of("USER"),
            Map.of("sub", "user123")
        ).withTenantId("tenant-1");

        when(jwtDecoder.decode(token, "tenant-1")).thenReturn(Mono.just(jwt));
        when(principalExtractor.extractPrincipal(jwt)).thenReturn(principal);

        // Capture the exchange passed to filter chain
        when(filterChain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange capturedExchange = invocation.getArgument(0);
            GatewayPrincipal storedPrincipal = JwtAuthenticationFilter.getPrincipal(capturedExchange);
            assertThat(storedPrincipal).isNotNull();
            assertThat(storedPrincipal.getUsername()).isEqualTo("user123");
            assertThat(storedPrincipal.getGroups()).containsExactly("USER");
            return Mono.empty();
        });

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then
        StepVerifier.create(result)
            .verifyComplete();
    }

    @Test
    void shouldPropagateUnexpectedExceptionsDuringValidation() {
        // Given
        String token = "valid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");

        when(jwtDecoder.decode(token, "tenant-1")).thenReturn(Mono.error(new RuntimeException("Unexpected error")));

        // When
        Mono<Void> result = filter.filter(exchange, filterChain);

        // Then — unexpected errors propagate so downstream error handlers can
        // respond appropriately, rather than being swallowed as 401
        StepVerifier.create(result)
            .verifyError(RuntimeException.class);

        verify(filterChain, never()).filter(any());
    }
    
    @Test
    void shouldIncludePathInErrorResponse() {
        // Given
        String path = "/api/users/123";
        MockServerHttpRequest request = MockServerHttpRequest
            .get(path)
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        
        // When
        Mono<Void> result = filter.filter(exchange, filterChain);
        
        // Then
        StepVerifier.create(result)
            .verifyComplete();
        
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Note: In a real test, we would verify the response body contains the path
        // but that requires more complex setup with DataBuffer handling
    }
    
    @Test
    void unauthorized_returnsJsonApiErrorsEnvelope() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/widgets").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = filter.filter(exchange, filterChain);

        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isNotNull();
        // JSON:API spec: errors[] array, not singular error object
        assertThat(body).contains("\"errors\"");
        assertThat(body).doesNotContain("\"error\":{");
        assertThat(body).contains("\"status\":\"401\"");
        assertThat(body).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(body).contains("\"title\":\"Unauthorized\"");
        assertThat(body).contains("\"detail\":");
        assertThat(body).contains("/api/widgets");
    }

    @Nested
    @DisplayName("tenant_id claim on a tenant route (PLT-707)")
    class TenantClaim {

        private static final String TOKEN = "header.payload.sig";

        @BeforeEach
        void useRealPrincipalExtractor() {
            PrincipalExtractor real = new PrincipalExtractor();
            lenient().when(principalExtractor.extractPrincipal(any(Jwt.class)))
                .thenAnswer(inv -> real.extractPrincipal(inv.getArgument(0)));
        }

        @Test
        @DisplayName("a JWT without tenant_id on a URL-slug tenant is rejected with 401")
        void tenantlessJwtOnUrlSlugTenantRejected() {
            MockServerWebExchange exchange = bearerExchange();
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_SLUG_ATTR, "acme");
            decodesTo(jwtClaims(null));

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain, never()).filter(any());
            verify(metrics).recordAuthFailure("acme", "missing_tenant_claim");
        }

        @Test
        @DisplayName("a JWT with an empty tenant_id on a custom-domain tenant is rejected with 401")
        void emptyTenantJwtOnCustomDomainTenantRejected() {
            // CustomDomainFilter leaves the tenant on the exchange the same way a URL slug does.
            MockServerWebExchange exchange = bearerExchange();
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            decodesTo(jwtClaims(""));

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain, never()).filter(any());
        }

        @Test
        @DisplayName("a JWT without tenant_id on a header-claimed tenant is rejected with 401")
        void tenantlessJwtOnHeaderTenantRejected() {
            MockServerWebExchange exchange = bearerExchange();
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_SOURCE_ATTR,
                TenantResolutionFilter.TENANT_SOURCE_HEADER);
            decodesTo(jwtClaims(null));

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain, never()).filter(any());
            verify(metrics).recordAuthFailure(any(), eq("missing_tenant_claim"));
        }

        @Test
        @DisplayName("a JWT whose tenant_id matches the request tenant passes to the chain")
        void matchingTenantPasses() {
            MockServerWebExchange exchange = bearerExchange();
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            decodesTo(jwtClaims("tenant-1"));
            when(filterChain.filter(any(ServerWebExchange.class))).thenAnswer(inv -> {
                GatewayPrincipal stored = JwtAuthenticationFilter.getPrincipal(inv.getArgument(0));
                assertThat(stored.getTenantId()).isEqualTo("tenant-1");
                return Mono.empty();
            });

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain).filter(any(ServerWebExchange.class));
        }

        @Test
        @DisplayName("a JWT whose tenant_id names another tenant is rejected with 401")
        void mismatchedTenantRejected() {
            MockServerWebExchange exchange = bearerExchange();
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            decodesTo(jwtClaims("tenant-2"));

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain, never()).filter(any());
            verify(metrics).recordAuthFailure(any(), eq("tenant_mismatch"));
        }

        @Test
        @DisplayName("a direct-login refresh token presented as a bearer is rejected with 401")
        void directLoginRefreshTokenRejected() {
            // Claims exactly as DirectLoginController builds its refresh token: no tenant_id.
            MockServerWebExchange exchange = bearerExchange();
            exchange.getAttributes().put(TenantResolutionFilter.TENANT_ID_ATTR, "tenant-1");
            decodesTo(Jwt.withTokenValue(TOKEN).header("alg", "RS256")
                .issuer("https://auth.kelta.io").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .jti("refresh-jti").claim("token_type", "refresh")
                .build());

            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(filterChain, never()).filter(any());
        }

        private MockServerWebExchange bearerExchange() {
            return MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .build());
        }

        private void decodesTo(Jwt jwt) {
            when(jwtDecoder.decode(TOKEN, "tenant-1")).thenReturn(Mono.just(jwt));
        }

        private Jwt jwtClaims(String tenantId) {
            Jwt.Builder builder = Jwt.withTokenValue(TOKEN).header("alg", "RS256")
                .issuer("https://auth.kelta.io").subject("user-1").claim("preferred_username", "u@x.io")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
            if (tenantId != null) {
                builder.claim("tenant_id", tenantId);
            }
            return builder.build();
        }
    }

    /**
     * Helper method to create a mock JWT for testing.
     */
    private Jwt createMockJwt(String subject, List<String> groups) {
        return new Jwt(
            "token-value",
            Instant.now(),
            Instant.now().plusSeconds(3600),
            Map.of("alg", "RS256"),
            Map.of(
                "sub", subject,
                "roles", groups,
                "iss", "http://localhost:9000/realms/emf"
            )
        );
    }
}
