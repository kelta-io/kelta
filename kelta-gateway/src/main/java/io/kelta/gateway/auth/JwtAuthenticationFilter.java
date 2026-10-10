package io.kelta.gateway.auth;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.error.ResponseHelpers;
import io.kelta.gateway.filter.TenantResolutionFilter;
import io.kelta.gateway.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global filter that validates JWT tokens and extracts authenticated principal information.
 * Runs early in the filter chain (order -100) before routing decisions are made.
 * 
 * This filter:
 * - Extracts the Authorization header from incoming requests
 * - Validates JWT tokens using Spring Security's ReactiveJwtDecoder
 * - Stores the extracted GatewayPrincipal in ServerWebExchange attributes
 * - Returns 401 Unauthorized for missing, invalid, or expired tokens
 * - Allows unauthenticated access to actuator and platform paths
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {
    
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PRINCIPAL_ATTRIBUTE = "gateway.principal";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Cerbos principal id + X-User-Id fallback (HeaderTransformationFilter.resolveUserId) for
     *  every anonymous request admitted as Guest. UUID-SHAPED ON PURPOSE, not the readable
     *  string "guest" the first version of this used -- LoginTrackingFilter runs on every
     *  request, treats any non-UUID X-User-Id as a login-worthy email, and auto-provisions a
     *  real platform_user + login_history + security_audit_log rows (and attempts a welcome
     *  email) for the first request that presents one. "guest" tripped that: every anonymous
     *  visitor silently got a persistent fake "Standard User" member account in the tenant, one
     *  per tenant, found live in production logs
     *  ("Auto-provisioned platform_user for 'guest' ... with profile 'Standard User'").
     *  {@code LoginTrackingFilter.isUuid(email)} skips tracking entirely for anything
     *  UUID-shaped, so this sentinel avoids the whole mechanism instead of fighting it.
     *  The canonical nil UUID is used because it can never collide with a real
     *  {@code UUID.randomUUID()} value and reads unambiguously as "no identity" in logs/data.
     *
     *  <p>This also means {@code JdbcUserIdResolver.resolve} short-circuits on it (UUID-shaped
     *  input returns unchanged, no DB lookup), so both {@code DynamicCollectionRouter}'s
     *  createdBy stamping and the worker's {@code CallerContextFilter} resolve it to the exact
     *  same value with no special case needed on either side, so the generic owner guard
     *  ({@code OwnerScopeGuardHook}) stamps and compares the guest's createdBy like any other
     *  caller's. */
    static final String GUEST_USER_ID = "00000000-0000-0000-0000-000000000000";
    static final String GUEST_PROFILE_NAME = "Guest";

    private final DynamicReactiveJwtDecoder jwtDecoder;
    private final PrincipalExtractor principalExtractor;
    private final PublicPathMatcher publicPathMatcher;
    private final GatewayMetrics metrics;
    private final GatewayCacheManager cacheManager;

    /**
     * Creates a new JwtAuthenticationFilter.
     *
     * @param jwtDecoder the JWT decoder for validating tokens (tenant-aware)
     * @param principalExtractor the extractor for creating GatewayPrincipal from JWT
     * @param publicPathMatcher the matcher for public (unauthenticated) paths
     * @param metrics the gateway metrics service
     * @param cacheManager resolves a tenant's Guest profile, if it has configured one
     */
    public JwtAuthenticationFilter(DynamicReactiveJwtDecoder jwtDecoder, PrincipalExtractor principalExtractor,
                                   PublicPathMatcher publicPathMatcher, GatewayMetrics metrics,
                                   GatewayCacheManager cacheManager) {
        this.jwtDecoder = jwtDecoder;
        this.principalExtractor = principalExtractor;
        this.publicPathMatcher = publicPathMatcher;
        this.metrics = metrics;
        this.cacheManager = cacheManager;
    }
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        // Allow CORS preflight requests through — browsers send OPTIONS without credentials
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            log.debug("Allowing CORS preflight request for path: {}", path);
            return chain.filter(exchange);
        }

        // Allow unauthenticated access to public bootstrap paths (GET/HEAD only)
        if (publicPathMatcher.isPublicRequest(exchange)) {
            log.debug("Allowing unauthenticated access to public path: {}", path);
            // No credential is checked here, so nothing proves a header-claimed tenant: a public
            // path is served for the URL's tenant or for none (PLT-355).
            TenantResolutionFilter.clearHeaderSourcedTenant(exchange);
            return chain.filter(exchange);
        }

        // Extract Authorization header
        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);

        if (authHeader == null || authHeader.isEmpty()) {
            return admitAsGuestOrReject(exchange, chain, path);
        }

        if (!authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("Invalid Authorization header format for path: {}", path);
            metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "invalid_format");
            return unauthorized(exchange, "Invalid Authorization header format. Expected 'Bearer <token>'");
        }

        // Extract token from header
        String token = authHeader.substring(BEARER_PREFIX.length());

        // Skip PAT tokens — they are handled by PatAuthenticationFilter
        if (token.startsWith("klt_")) {
            return chain.filter(exchange);
        }

        // Get tenant context (set by TenantSlugExtractionFilter / TenantResolutionFilter)
        // to scope JWT issuer validation to the correct tenant
        String tenantId = TenantResolutionFilter.getTenantId(exchange);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("No tenant context available for JWT validation on path: {}", path);
            metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "missing_tenant_context");
            return unauthorized(exchange, "Tenant context required for authentication");
        }

        // Validate JWT with tenant-scoped issuer verification.
        // Error handlers are scoped to the decode+extract phase only — errors
        // from downstream filters (e.g. Cerbos connection issues) must NOT be
        // swallowed as 401 "Authentication failed".
        return jwtDecoder.decode(token, tenantId)
            .map(jwt -> {
                GatewayPrincipal principal = principalExtractor.extractPrincipal(jwt);
                log.debug("Successfully authenticated user: {} for path: {}", principal.getUsername(), path);

                // Enforce that the JWT's tenant_id matches the slug-resolved tenant.
                // This prevents cross-tenant access: a token issued for tenant A must not
                // be accepted on tenant B's slug.
                // A tenant claimed only by the X-Tenant-ID / X-Tenant-Slug header must be proven
                // by the token itself, so a token without a tenant_id claim cannot use it either.
                String jwtTenantId = principal.getTenantId();
                boolean hasJwtTenant = jwtTenantId != null && !jwtTenantId.isEmpty();
                if ((hasJwtTenant && !jwtTenantId.equals(tenantId))
                        || (!hasJwtTenant && TenantResolutionFilter.isHeaderSourced(exchange))) {
                    log.warn("Cross-tenant access attempt: JWT tenant={} request tenant={} path={}",
                            jwtTenantId, tenantId, path);
                    metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "tenant_mismatch");
                    throw new JwtException("JWT tenant_id does not match request tenant");
                }

                // Store principal in exchange attributes for downstream filters
                ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(exchange.getRequest())
                    .build();
                mutatedExchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
                return mutatedExchange;
            })
            .onErrorResume(JwtException.class, e -> {
                log.warn("JWT validation failed for path: {}: {}", path, e.getMessage());
                metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "invalid_token");
                return unauthorized(exchange, "Invalid or expired JWT token")
                        .then(Mono.empty());
            })
            .onErrorResume(IllegalArgumentException.class, e -> {
                log.error("Failed to extract principal from JWT for path: {}", path, e);
                metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "invalid_claims");
                return unauthorized(exchange, "Invalid JWT claims: " + e.getMessage())
                        .then(Mono.empty());
            })
            .flatMap(chain::filter);
    }
    
    /**
     * A request with no Authorization header at all is rejected as before, UNLESS the
     * resolved tenant has configured a {@code profiles} row named "Guest" -- in which case
     * the request proceeds as that profile instead of 401ing.
     *
     * <p>This grants nothing by itself: the synthetic principal still has to clear
     * {@code RouteAuthorizationFilter}'s normal Cerbos checks (API_ACCESS, then the
     * per-collection action) exactly like any authenticated principal, and Cerbos denies by
     * default until the tenant explicitly grants the Guest profile a permission. A tenant
     * that never creates a Guest profile sees byte-for-byte the same 401 this filter always
     * returned -- {@link GatewayCacheManager#resolveGuestProfileReactive} resolves to empty
     * for them and this falls through to the exact same {@code unauthorized(...)} call.
     */
    private Mono<Void> admitAsGuestOrReject(ServerWebExchange exchange, GatewayFilterChain chain, String path) {
        // Guest access is granted to the URL's tenant only. A tenant named by the X-Tenant-ID /
        // X-Tenant-Slug header is a claim with no credential behind it (PLT-355).
        if (TenantResolutionFilter.isHeaderSourced(exchange)) {
            log.warn("Anonymous request tried to select tenant {} by header on path: {}",
                    TenantResolutionFilter.getTenantId(exchange), path);
            metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "header_tenant_anonymous");
            return unauthorized(exchange, "Missing Authorization header");
        }
        String tenantId = TenantResolutionFilter.getTenantId(exchange);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Missing Authorization header for path: {}", path);
            metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "missing_token");
            return unauthorized(exchange, "Missing Authorization header");
        }

        return cacheManager.resolveGuestProfileReactive(tenantId)
                .flatMap(guestProfileId -> {
                    if (guestProfileId.isEmpty()) {
                        log.warn("Missing Authorization header for path: {}", path);
                        metrics.recordAuthFailure(TenantResolutionFilter.getTenantSlug(exchange), "missing_token");
                        return unauthorized(exchange, "Missing Authorization header");
                    }

                    log.debug("Admitting anonymous request as Guest profile for tenant {} on path: {}",
                            tenantId, path);
                    GatewayPrincipal guest = new GatewayPrincipal(
                            GUEST_USER_ID, List.of(), Map.of(),
                            guestProfileId.get(), GUEST_PROFILE_NAME, tenantId, null, null);

                    ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(exchange.getRequest())
                            .build();
                    mutatedExchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, guest);
                    return chain.filter(mutatedExchange);
                });
    }

    /**
     * Returns an unauthorized response with the given error message.
     *
     * @param exchange the server web exchange
     * @param message the error message
     * @return a Mono that completes the response
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        if (!ResponseHelpers.prepareJsonResponse(exchange.getResponse(), HttpStatus.UNAUTHORIZED)) {
            return Mono.empty();
        }

        String path = exchange.getRequest().getPath().value();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "401");
        error.put("code", "UNAUTHORIZED");
        error.put("title", "Unauthorized");
        error.put("detail", message != null ? message : "Authentication failed");
        error.put("meta", Map.of("path", path));

        String errorJson;
        try {
            errorJson = OBJECT_MAPPER.writeValueAsString(Map.of("errors", List.of(error)));
        } catch (JacksonException e) {
            log.error("Failed to serialize error response", e);
            errorJson = "{\"errors\":[{\"status\":\"401\",\"code\":\"UNAUTHORIZED\",\"title\":\"Unauthorized\",\"detail\":\"Authentication failed\"}]}";
        }

        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(errorJson.getBytes()))
        );
    }
    
    @Override
    public int getOrder() {
        return -100; // Run early in the filter chain
    }
    
    /**
     * Retrieves the GatewayPrincipal from the ServerWebExchange attributes.
     * This is a utility method for downstream filters to access the authenticated principal.
     *
     * @param exchange the server web exchange
     * @return the GatewayPrincipal, or null if not authenticated
     */
    public static GatewayPrincipal getPrincipal(ServerWebExchange exchange) {
        return exchange.getAttribute(PRINCIPAL_ATTRIBUTE);
    }
}
