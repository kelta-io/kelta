package io.kelta.auth.service;

import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches OIDC Discovery metadata from a provider's
 * {@code .well-known/openid-configuration} endpoint.
 *
 * <p>When overrides are present in the OIDC provider configuration,
 * they take precedence over discovered values.
 *
 * <p>The issuer is tenant-supplied, so the fetch goes through {@link OutboundHttpClient} with the
 * service's {@link OutboundUrlGuard} (non-public addresses refused unless listed in
 * {@code KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS}), follows no redirects, and is bounded by
 * {@link #CONNECT_TIMEOUT}, {@link #REQUEST_TIMEOUT} overall and {@link #MAX_DOCUMENT_BYTES} —
 * the same limits the worker applies when it discovers an issuer on save. A refused or failed
 * discovery is cached like any other failure, so the endpoints fall back to the overrides.
 * The document is read as a JSON tree (no reflective binding, nothing to register for the native
 * image).
 */
@Service
public class OidcDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_DOCUMENT_BYTES = 256 * 1024;

    private final OutboundHttpClient httpClient;
    private final ObjectMapper jsonMapper = JsonMapper.builder().build();
    private final Map<String, CachedDiscovery> cache = new ConcurrentHashMap<>();

    public OidcDiscoveryService(OutboundUrlGuard outboundUrlGuard) {
        this.httpClient = new OutboundHttpClient(outboundUrlGuard, new OutboundHttpClient.Limits(
                CONNECT_TIMEOUT, REQUEST_TIMEOUT, MAX_DOCUMENT_BYTES, 0));
    }

    /**
     * Resolved OIDC Discovery metadata.
     */
    public record DiscoveryMetadata(
            String authorizationEndpoint,
            String tokenEndpoint,
            String userinfoEndpoint,
            String jwksUri,
            String endSessionEndpoint,
            String issuer
    ) {}

    /**
     * Discovers OIDC endpoints from the provider's issuer URI.
     *
     * @param issuerUri the OIDC issuer URI (e.g., {@code https://accounts.google.com})
     * @return discovered metadata, or empty if Discovery fails
     */
    public Optional<DiscoveryMetadata> discover(String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            return Optional.empty();
        }

        // Check cache
        CachedDiscovery cached = cache.get(issuerUri);
        if (cached != null && !cached.isExpired()) {
            return cached.metadata;
        }

        try {
            String discoveryUrl = buildDiscoveryUrl(issuerUri);
            log.debug("Fetching OIDC Discovery from: {}", discoveryUrl);

            JsonNode response = fetch(discoveryUrl);

            if (response == null || !response.isObject()) {
                log.warn("Discovery response from {} is not a JSON object", discoveryUrl);
                cacheResult(issuerUri, Optional.empty());
                return Optional.empty();
            }

            DiscoveryMetadata metadata = new DiscoveryMetadata(
                    textOrNull(response, "authorization_endpoint"),
                    textOrNull(response, "token_endpoint"),
                    textOrNull(response, "userinfo_endpoint"),
                    textOrNull(response, "jwks_uri"),
                    textOrNull(response, "end_session_endpoint"),
                    textOrNull(response, "issuer")
            );

            log.info("OIDC Discovery succeeded for issuer={}", issuerUri);
            Optional<DiscoveryMetadata> result = Optional.of(metadata);
            cacheResult(issuerUri, result);
            return result;

        } catch (OutboundUrlGuard.RejectedUrlException e) {
            log.warn("OIDC Discovery not attempted for issuer={}: {}", issuerUri, e.getMessage());
            cacheResult(issuerUri, Optional.empty());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("OIDC Discovery failed for issuer={}: {}", issuerUri, e.getMessage());
            cacheResult(issuerUri, Optional.empty());
            return Optional.empty();
        }
    }

    private JsonNode fetch(String discoveryUrl) {
        OutboundHttpClient.Response response = httpClient.get(discoveryUrl, Map.of("Accept", "application/json"));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        if (response.truncated()) {
            throw new IllegalStateException("the document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        return jsonMapper.readTree(response.body());
    }

    /**
     * Resolves endpoints for a provider, applying overrides over discovered values.
     * Override values (non-null, non-blank) take precedence over discovered values.
     *
     * @param issuerUri the OIDC issuer URI
     * @param overrides provider configuration with optional endpoint overrides
     * @return resolved metadata with overrides applied
     */
    public ResolvedEndpoints resolve(String issuerUri, EndpointOverrides overrides) {
        Optional<DiscoveryMetadata> discovered = discover(issuerUri);

        String authorizationUri = coalesce(
                overrides.authorizationUri(),
                discovered.map(DiscoveryMetadata::authorizationEndpoint).orElse(null));
        String tokenUri = coalesce(
                overrides.tokenUri(),
                discovered.map(DiscoveryMetadata::tokenEndpoint).orElse(null));
        String userinfoUri = coalesce(
                overrides.userinfoUri(),
                discovered.map(DiscoveryMetadata::userinfoEndpoint).orElse(null));
        String jwksUri = coalesce(
                overrides.jwksUri(),
                discovered.map(DiscoveryMetadata::jwksUri).orElse(null));
        String endSessionUri = coalesce(
                overrides.endSessionUri(),
                discovered.map(DiscoveryMetadata::endSessionEndpoint).orElse(null));

        String status = discovered.isPresent() ? "discovered" : "manual";
        if (overrides.hasAnyOverride()) {
            status = discovered.isPresent() ? "discovered" : "manual";
        }

        return new ResolvedEndpoints(
                authorizationUri, tokenUri, userinfoUri, jwksUri, endSessionUri, status);
    }

    /**
     * Evicts the cached Discovery result for an issuer.
     */
    public void evict(String issuerUri) {
        cache.remove(issuerUri);
    }

    /**
     * Evicts all cached Discovery results.
     */
    public void evictAll() {
        cache.clear();
    }

    /**
     * Endpoint overrides from the OIDC provider configuration.
     */
    public record EndpointOverrides(
            String authorizationUri,
            String tokenUri,
            String userinfoUri,
            String jwksUri,
            String endSessionUri
    ) {
        public boolean hasAnyOverride() {
            return isNotBlank(authorizationUri) || isNotBlank(tokenUri)
                    || isNotBlank(userinfoUri) || isNotBlank(jwksUri)
                    || isNotBlank(endSessionUri);
        }

        private static boolean isNotBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * Resolved endpoints with Discovery status.
     */
    public record ResolvedEndpoints(
            String authorizationUri,
            String tokenUri,
            String userinfoUri,
            String jwksUri,
            String endSessionUri,
            String discoveryStatus
    ) {}

    private String buildDiscoveryUrl(String issuerUri) {
        String base = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        return base + "/.well-known/openid-configuration";
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String text = child.asText();
        return text.isBlank() ? null : text;
    }

    private String coalesce(String override, String discovered) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return discovered;
    }

    private void cacheResult(String issuerUri, Optional<DiscoveryMetadata> metadata) {
        cache.put(issuerUri, new CachedDiscovery(metadata, Instant.now()));
    }

    private record CachedDiscovery(Optional<DiscoveryMetadata> metadata, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(CACHE_TTL));
        }
    }
}
