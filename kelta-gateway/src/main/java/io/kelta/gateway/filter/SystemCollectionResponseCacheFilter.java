package io.kelta.gateway.filter;

import io.kelta.gateway.auth.GatewayPrincipal;
import io.kelta.gateway.auth.JwtAuthenticationFilter;
import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.error.ResponseHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gateway-level response cache for system collection GET requests.
 *
 * <p>This filter provides a second caching layer (in addition to the worker-side
 * Caffeine cache) that eliminates network round-trips to the worker for
 * frequently-read, rarely-changing system collections.
 *
 * <p>Behavior:
 * <ol>
 *   <li><strong>Cache hit:</strong> Returns the cached response body directly,
 *       bypassing the worker.</li>
 *   <li><strong>Cache miss:</strong> Records the cache key on the exchange and forwards the
 *       request; {@link SystemCollectionResponseCaptureFilter} stores the worker's 200 response
 *       under that key as it is written to the client.</li>
 * </ol>
 *
 * <p>Two properties keep a cached answer equal to the one the worker would give:
 * <ul>
 *   <li>The filter runs <em>after</em> {@code RouteAuthorizationFilter} (order 0), so every
 *       request — hit or miss — is authorized for its caller first, and the key is built from the
 *       request as authorization forwards it (e.g. the narrowed {@code /api/tenants} view).</li>
 *   <li>The key carries the caller ({@link #callerKey}): the worker filters these responses per
 *       caller (record-level authorization including {@code included[]}, field-level security,
 *       manual record shares), so one caller's response is only ever served to the same caller.</li>
 * </ul>
 *
 * <p>Only caches GET requests to known system collection API paths that return
 * a 200 OK response with JSON content.
 *
 * <p>Cache invalidation is driven by NATS events consumed by
 * {@link io.kelta.gateway.listener.SystemCollectionRouteListener}, and by a write to a
 * cacheable collection through this gateway.
 *
 * @since 1.0.0
 */
@Component
public class SystemCollectionResponseCacheFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SystemCollectionResponseCacheFilter.class);

    /**
     * Runs after route authorization (0) so a cached response is only served to a caller the
     * route allows, and before the worker header transformation (50).
     */
    static final int ORDER = 10;

    /**
     * Exchange attribute holding the key a cache miss is stored under. Set only on a miss;
     * {@link SystemCollectionResponseCaptureFilter} caches the response only when it is present.
     */
    static final String CACHE_KEY_ATTR = "gateway.systemCollectionResponseCacheKey";

    private static final Set<HttpMethod> MUTATING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    /** Caller segment of the key for a request without a principal. */
    static final String ANONYMOUS_CALLER = "anonymous";

    /**
     * System collections whose responses should be cached at the gateway layer.
     * These are high-traffic, low-mutation collections read on every page load.
     */
    public static final Set<String> CACHEABLE_COLLECTIONS = Set.of(
            "ui-pages", "ui-menus", "ui-menu-items",
            "collections", "fields",
            "tenants",
            "oidc-providers",
            "global-picklists", "picklist-values",
            "page-layouts", "layout-sections", "layout-fields",
            "layout-related-lists", "layout-assignments",
            "list-views",
            "profiles"
    );

    /**
     * Pattern to extract collection name from API path.
     * Matches: /api/{collectionName} or /api/{collectionName}/{id}
     * Also matches: /api/{collectionName}?query=params
     */
    private static final Pattern API_PATH_PATTERN = Pattern.compile("^/api/([a-zA-Z0-9_-]+)(?:/.*)?$");

    private final GatewayCacheManager cacheManager;

    public SystemCollectionResponseCacheFilter(GatewayCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String collectionName = extractCollectionName(path);
        if (collectionName == null || !CACHEABLE_COLLECTIONS.contains(collectionName)) {
            return chain.filter(exchange);
        }

        // For mutating requests (POST, PATCH, PUT, DELETE) on cacheable collections,
        // evict the gateway cache eagerly so the next GET returns fresh data.
        // This avoids the race condition where the UI refetches before the async
        // NATS cache-eviction event is consumed.
        HttpMethod method = exchange.getRequest().getMethod();
        if (MUTATING_METHODS.contains(method)) {
            log.debug("Mutating request on cacheable collection '{}', evicting gateway cache", collectionName);
            cacheManager.evictSystemCollectionResponses(collectionName);
            return chain.filter(exchange);
        }
        if (method != HttpMethod.GET) {
            return chain.filter(exchange);
        }

        String cacheKey = buildCacheKey(
                TenantResolutionFilter.getTenantId(exchange),
                callerKey(JwtAuthenticationFilter.getPrincipal(exchange)),
                path,
                exchange.getRequest().getURI().getRawQuery());

        Optional<byte[]> cached = cacheManager.getSystemCollectionResponse(cacheKey);
        if (cached.isPresent()) {
            log.debug("Gateway cache hit for system collection: {}", collectionName);
            return writeCachedResponse(exchange, cached.get());
        }

        exchange.getAttributes().put(CACHE_KEY_ATTR, cacheKey);
        return chain.filter(exchange);
    }

    /**
     * Extracts the collection name from an API path.
     *
     * @param path the request path (e.g., "/api/collections" or "/api/ui-pages/123")
     * @return the collection name, or null if the path doesn't match
     */
    static String extractCollectionName(String path) {
        Matcher matcher = API_PATH_PATTERN.matcher(path);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * {@code <tenant>:<caller>:<path>[?<query>]}. The path stays a plain substring of the key, which
     * is what {@link GatewayCacheManager#evictSystemCollectionResponses} matches on; the caller
     * segment is hex, so it can never contain {@code /api/}.
     */
    static String buildCacheKey(String tenantId, String callerKey, String path, String query) {
        String key = (tenantId != null ? tenantId : "_") + ":" + callerKey + ":" + path;
        if (query != null && !query.isEmpty()) {
            key += "?" + query;
        }
        return key;
    }

    /**
     * Identifies the caller as the worker sees it: every principal attribute the gateway forwards
     * and authorization can depend on (user, profile, tenant, groups, user type, connected-app
     * scopes, request-origin country). Hashed so the key stays short and free of path characters.
     */
    static String callerKey(GatewayPrincipal principal) {
        if (principal == null) {
            return ANONYMOUS_CALLER;
        }
        Object userType = principal.getClaims().get("user_type");
        String identity = String.join("\u0000",
                nullToEmpty(principal.getTenantId()),
                nullToEmpty(principal.getUserId()),
                principal.getUsername(),
                nullToEmpty(principal.getProfileId()),
                nullToEmpty(principal.getProfileName()),
                String.join(",", principal.getGroups().stream().sorted().toList()),
                userType != null ? userType.toString() : "",
                nullToEmpty(principal.getConnectedAppId()),
                nullToEmpty(principal.getAppScopes()),
                nullToEmpty(principal.getGeoCountry()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private Mono<Void> writeCachedResponse(ServerWebExchange exchange, byte[] body) {
        ServerHttpResponse response = exchange.getResponse();
        if (!ResponseHelpers.prepareJsonResponse(response, HttpStatus.OK)) {
            return Mono.empty();
        }
        ResponseHelpers.applyHeaderIfWritable(response, () -> response.getHeaders().setContentLength(body.length));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
