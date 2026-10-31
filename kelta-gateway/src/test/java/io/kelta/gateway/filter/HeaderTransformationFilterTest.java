package io.kelta.gateway.filter;

import io.kelta.gateway.auth.GatewayPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for HeaderTransformationFilter.
 *
 * Tests verify that:
 * - Authorization header is preserved for downstream JWT validation
 * - X-Forwarded-User header is added with principal username
 * - X-Forwarded-Groups header is added with comma-separated groups
 * - X-Forwarded-Roles header is added for backward compatibility (same value as groups)
 * - Other headers are preserved
 * - Filter handles unauthenticated requests gracefully
 */
class HeaderTransformationFilterTest {
    
    private HeaderTransformationFilter filter;
    private GatewayFilterChain chain;
    
    @BeforeEach
    void setUp() {
        filter = new HeaderTransformationFilter();
        chain = mock(GatewayFilterChain.class);
        
        // Mock chain to capture the mutated exchange
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange exchange = invocation.getArgument(0);
            return Mono.empty();
        });
    }
    
    @Test
    void shouldPreserveAuthorizationHeader() {
        // Given: A request with Authorization header and authenticated principal
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Custom-Header", "custom-value")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "testuser",
                Arrays.asList("USER", "ADMIN"),
                Collections.emptyMap()
        );
        exchange.getAttributes().put("gateway.principal", principal);

        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Then: Authorization header should be preserved for downstream JWT validation
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-token");
    }
    
    @Test
    void shouldAddForwardedUserHeader() {
        // Given: A request with authenticated principal
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();
        
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        
        GatewayPrincipal principal = new GatewayPrincipal(
                "john.doe",
                Arrays.asList("USER"),
                Collections.emptyMap()
        );
        exchange.getAttributes().put("gateway.principal", principal);
        
        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });
        
        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
        
        // Then: X-Forwarded-User header should be present
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Forwarded-User")).isEqualTo("john.doe");
    }
    
    @Test
    void shouldAddForwardedGroupsHeaderWithCommaSeparatedGroups() {
        // Given: A request with authenticated principal having multiple groups
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "admin.user",
                Arrays.asList("USER", "ADMIN", "MODERATOR"),
                Collections.emptyMap()
        );
        exchange.getAttributes().put("gateway.principal", principal);

        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Then: X-Forwarded-Groups header should contain comma-separated groups
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Forwarded-Groups")).isEqualTo("USER,ADMIN,MODERATOR");
        // Backward compatibility: X-Forwarded-Roles should also be set with the same value
        assertThat(headers.getFirst("X-Forwarded-Roles")).isEqualTo("USER,ADMIN,MODERATOR");
    }
    
    @Test
    void shouldPreserveOtherHeaders() {
        // Given: A request with multiple headers
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Custom-Header", "custom-value")
                .header("X-Request-ID", "12345")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
        
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        
        GatewayPrincipal principal = new GatewayPrincipal(
                "testuser",
                Arrays.asList("USER"),
                Collections.emptyMap()
        );
        exchange.getAttributes().put("gateway.principal", principal);
        
        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });
        
        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
        
        // Then: Other headers should be preserved (including Authorization)
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Custom-Header")).isEqualTo("custom-value");
        assertThat(headers.getFirst("X-Request-ID")).isEqualTo("12345");
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-token");
    }
    
    @Test
    void shouldHandleEmptyGroupsList() {
        // Given: A request with principal having no groups
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "testuser",
                Collections.emptyList(),
                Collections.emptyMap()
        );
        exchange.getAttributes().put("gateway.principal", principal);

        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Then: Both group headers should be empty string
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Forwarded-Groups")).isEqualTo("");
        assertThat(headers.getFirst("X-Forwarded-Roles")).isEqualTo("");
    }
    
    @Test
    void shouldSkipTransformationWhenNoPrincipal() {
        // Given: A request without authenticated principal (e.g., bootstrap endpoint)
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/control/bootstrap")
                .header("X-Custom-Header", "custom-value")
                .build();
        
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        // No principal in attributes
        
        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });
        
        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
        
        // Then: Headers should remain unchanged
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.containsHeader("X-Forwarded-User")).isFalse();
        assertThat(headers.containsHeader("X-Forwarded-Groups")).isFalse();
        assertThat(headers.containsHeader("X-Forwarded-Roles")).isFalse();
        assertThat(headers.getFirst("X-Custom-Header")).isEqualTo("custom-value");
    }
    
    @Test
    void shouldHaveCorrectOrder() {
        // Then: Filter should run after authentication and authorization
        assertThat(filter.getOrder()).isEqualTo(50);
    }
    
    @Test
    void shouldStripForgedInternalHeaders() {
        // Given: A request with client-supplied internal headers (forgery attempt)
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Forwarded-User", "forged-admin")
                .header("X-User-Id", "forged-user-id")
                .header("X-Forwarded-Groups", "SUPER_ADMIN")
                .header("X-Forwarded-Roles", "SUPER_ADMIN")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "real.user",
                Arrays.asList("USER"),
                Map.of("sub", "real-user-id", "email", "real.user@example.com")
        );
        exchange.getAttributes().put("gateway.principal", principal);

        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Then: Forged headers should be replaced with gateway-verified values
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Forwarded-User")).isEqualTo("real.user");
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("real.user@example.com");
        assertThat(headers.getFirst("X-Forwarded-Groups")).isEqualTo("USER");
        assertThat(headers.getFirst("X-Forwarded-Roles")).isEqualTo("USER");
    }

    @Test
    void shouldUseEmailClaimForUserIdHeader() {
        // Given: JWT with both sub (UUID) and email claims
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/data").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "craig@example.com",
                List.of("USER"),
                Map.of("sub", "277d1874-8e12-4101-b22e-37f6c31f8d69",
                       "email", "craig@example.com",
                       "preferred_username", "craig@example.com")
        );
        exchange.getAttributes().put("gateway.principal", principal);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Then: X-User-Id should be the email, NOT the sub UUID
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("craig@example.com");
    }

    @Test
    void shouldFallBackToPreferredUsernameWhenNoEmailClaim() {
        // Given: JWT with sub and preferred_username but no email claim
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/data").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "craig",
                List.of("USER"),
                Map.of("sub", "277d1874-8e12-4101-b22e-37f6c31f8d69",
                       "preferred_username", "craig@example.com")
        );
        exchange.getAttributes().put("gateway.principal", principal);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("craig@example.com");
    }

    @Test
    void shouldFallBackToUsernameWhenNoEmailOrPreferredUsername() {
        // Given: JWT with only sub claim (no email, no preferred_username)
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/data").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "craig@example.com",
                List.of("USER"),
                Map.of("sub", "277d1874-8e12-4101-b22e-37f6c31f8d69")
        );
        exchange.getAttributes().put("gateway.principal", principal);

        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Then: Falls back to principal.getUsername() (which PrincipalExtractor sets to email)
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("craig@example.com");
    }

    @Test
    void shouldHandleSingleGroup() {
        // Given: A request with principal having single group
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/users")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayPrincipal principal = new GatewayPrincipal(
                "testuser",
                Arrays.asList("ADMIN"),
                Collections.emptyMap()
        );
        exchange.getAttributes().put("gateway.principal", principal);

        // Capture the mutated exchange
        final ServerWebExchange[] capturedExchange = new ServerWebExchange[1];
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            capturedExchange[0] = invocation.getArgument(0);
            return Mono.empty();
        });

        // When: Filter is applied
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Then: Both group headers should contain single group without comma
        assertThat(capturedExchange[0]).isNotNull();
        HttpHeaders headers = capturedExchange[0].getRequest().getHeaders();
        assertThat(headers.getFirst("X-Forwarded-Groups")).isEqualTo("ADMIN");
        assertThat(headers.getFirst("X-Forwarded-Roles")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("a connected-app token forwards its connected_app_id; a user token forwards none, even when forged")
    void forwardsConnectedAppIdOnlyForConnectedAppTokens() {
        // A module service principal: run-as connected-app token (external modules 9b).
        MockServerWebExchange appExchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/modules/self/credentials/stripe")
                .header("X-Connected-App-Id", "forged-app")
                .build());
        GatewayPrincipal servicePrincipal = new GatewayPrincipal("kmod_client", List.of(),
                Map.of("email", "svc@service.kelta.invalid", "user_type", "SERVICE"),
                "profile-1", "Module profile", "tenant-1", "app-1", "api");
        appExchange.getAttributes().put("gateway.principal", servicePrincipal);

        // A signed-in user: no connected_app_id claim.
        MockServerWebExchange userExchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/modules/self/credentials/stripe")
                .header("X-Connected-App-Id", "app-1")
                .build());
        userExchange.getAttributes().put("gateway.principal", new GatewayPrincipal("user@example.com",
                List.of(), Map.of("email", "user@example.com")));

        final List<ServerWebExchange> captured = new ArrayList<>();
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(appExchange, chain)).verifyComplete();
        StepVerifier.create(filter.filter(userExchange, chain)).verifyComplete();

        assertThat(captured.get(0).getRequest().getHeaders().get("X-Connected-App-Id"))
                .as("the token's own app id replaces a forged one").containsExactly("app-1");
        assertThat(captured.get(1).getRequest().getHeaders().getFirst("X-Connected-App-Id"))
                .as("a user token carries no app id, and a client-sent one is dropped").isNull();
    }
}
