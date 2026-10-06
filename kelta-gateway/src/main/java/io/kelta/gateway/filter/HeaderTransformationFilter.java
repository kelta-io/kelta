package io.kelta.gateway.filter;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.JwtAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

/**
 * Global filter that transforms request headers before forwarding to backend services.
 * Runs with order 50 (after authentication and authorization, before forwarding).
 *
 * This filter:
 * - Preserves the Authorization header so downstream services can validate JWT tokens
 * - Adds X-Forwarded-User header with the authenticated principal's username
 * - Adds X-User-Id header with the user's email (resolved from JWT claims)
 * - Adds X-User-Type header (INTERNAL|PORTAL) from the principal's user_type claim. For JWTs the
 *   claim is the token's own; a PAT's principal is gateway-built, and UserIdentityResolutionFilter
 *   adds the PAT owner's platform_user.user_type to it, so a portal member's PAT is PORTAL
 * - Adds X-Forwarded-Groups header with comma-separated list of principal's groups
 * - Adds X-Forwarded-Roles header (backward compatibility, same value as groups)
 * - Preserves all other request headers
 *
 * The backend services can use the X-Forwarded-User, X-User-Id, and X-Forwarded-Groups
 * headers for lightweight identity extraction, or validate the JWT themselves using the
 * preserved Authorization header.
 *
 * Validates: Requirements 9.4, 9.5, 9.6
 */
@Component
public class HeaderTransformationFilter implements GlobalFilter, Ordered {
    
    private static final Logger log = LoggerFactory.getLogger(HeaderTransformationFilter.class);
    
    private static final String X_FORWARDED_USER_HEADER = "X-Forwarded-User";
    private static final String X_USER_ID_HEADER = "X-User-Id";
    private static final String X_USER_TYPE_HEADER = "X-User-Type";
    private static final String X_FORWARDED_ROLES_HEADER = "X-Forwarded-Roles";
    private static final String X_FORWARDED_GROUPS_HEADER = "X-Forwarded-Groups";
    private static final String X_TENANT_ID_HEADER = "X-Tenant-ID";
    private static final String X_TENANT_SLUG_HEADER = "X-Tenant-Slug";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Retrieve the authenticated principal from exchange attributes
        GatewayPrincipal principal = JwtAuthenticationFilter.getPrincipal(exchange);

        // If no principal is present (unauthenticated request like bootstrap endpoint),
        // still propagate tenant headers if available
        if (principal == null) {
            log.debug("No principal found, skipping user header transformation for path: {}",
                    exchange.getRequest().getPath().value());
            String tenantId = TenantResolutionFilter.getTenantId(exchange);
            String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
            if (tenantId != null || tenantSlug != null) {
                ServerHttpRequest req = exchange.getRequest().mutate()
                        .headers(headers -> {
                            if (tenantId != null) headers.set(X_TENANT_ID_HEADER, tenantId);
                            if (tenantSlug != null) headers.set(X_TENANT_SLUG_HEADER, tenantSlug);
                        })
                        .build();
                return chain.filter(exchange.mutate().request(req).build());
            }
            return chain.filter(exchange);
        }

        // Build the mutated request with transformed headers
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    // Strip any client-supplied internal headers to prevent forgery.
                    // These headers are gateway-verified and must not be set by clients.
                    // (IdentityHeaderStripFilter already removed them at the chain head;
                    // kept here as defense in depth.)
                    headers.remove(X_FORWARDED_USER_HEADER);
                    headers.remove(X_USER_ID_HEADER);
                    headers.remove(X_USER_TYPE_HEADER);
                    headers.remove(X_FORWARDED_GROUPS_HEADER);
                    headers.remove(X_FORWARDED_ROLES_HEADER);

                    // Add X-Forwarded-User header
                    headers.set(X_FORWARDED_USER_HEADER, principal.getUsername());

                    // Add X-User-Id header (email for platform_user resolution)
                    String userId = resolveUserId(principal);
                    headers.set(X_USER_ID_HEADER, userId);

                    // Add X-User-Type header (INTERNAL|PORTAL) from the user_type
                    // claim — the JWT's own, or the PAT owner's (added by
                    // UserIdentityResolutionFilter). Tokens minted before the
                    // claim existed read as INTERNAL.
                    Object userType = principal.getClaims().get("user_type");
                    headers.set(X_USER_TYPE_HEADER,
                            userType instanceof String s && !s.isEmpty() ? s : "INTERNAL");

                    // Add X-Forwarded-Groups header with comma-separated groups
                    String groups = principal.getGroups().stream()
                            .collect(Collectors.joining(","));
                    headers.set(X_FORWARDED_GROUPS_HEADER, groups);
                    // Backward compatibility: also forward as X-Forwarded-Roles
                    headers.set(X_FORWARDED_ROLES_HEADER, groups);

                    // Add tenant headers from exchange attributes (set by TenantResolutionFilter)
                    String tenantId = TenantResolutionFilter.getTenantId(exchange);
                    String tenantSlug = TenantResolutionFilter.getTenantSlug(exchange);
                    if (tenantId != null) {
                        headers.set(X_TENANT_ID_HEADER, tenantId);
                    }
                    if (tenantSlug != null) {
                        headers.set(X_TENANT_SLUG_HEADER, tenantSlug);
                    }
                    if (tenantId == null && tenantSlug == null) {
                        log.warn("No tenant context for authenticated user {} on path {}. " +
                                "Request will proceed without tenant isolation.",
                                principal.getUsername(), exchange.getRequest().getPath().value());
                    }

                    log.debug("Added forwarding headers for user: {}, groups: {}, tenantId: {}",
                            principal.getUsername(), groups, tenantId);
                })
                .build();
        
        // Create mutated exchange with the new request
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();
        
        return chain.filter(mutatedExchange);
    }
    
    /**
     * Resolves the user's identifier from JWT claims for the X-User-Id header.
     *
     * <p>The worker's {@code JdbcUserIdResolver} translates this value to a
     * {@code platform_user.id} UUID.  It performs a lookup by email, so this
     * method prefers the email-style claims ({@code email}, {@code preferred_username})
     * over the OIDC {@code sub} claim (which is typically an IdP-internal UUID
     * that does not match any {@code platform_user.id}).
     *
     * @param principal the authenticated gateway principal
     * @return the resolved user identifier (ideally an email address)
     */
    private String resolveUserId(GatewayPrincipal principal) {
        // Prefer email claim — matches platform_user lookup in JdbcUserIdResolver
        Object email = principal.getClaims().get("email");
        if (email instanceof String s && !s.isEmpty()) {
            return s;
        }
        // Fall back to preferred_username (often the email in OIDC providers)
        Object preferredUsername = principal.getClaims().get("preferred_username");
        if (preferredUsername instanceof String s && !s.isEmpty()) {
            return s;
        }
        // Last resort: use the principal username (extracted by PrincipalExtractor)
        return principal.getUsername();
    }

    @Override
    public int getOrder() {
        return 50; // Run after authentication (-100) and authorization (0), before forwarding
    }
}
