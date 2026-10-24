package io.kelta.gateway.auth;

import io.kelta.gateway.cache.GatewayCacheManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reactive JWT decoder for the API gateway. Accepts tokens issued by the
 * internal kelta-auth provider on:
 * <ul>
 *   <li>the configured platform issuer URI (e.g. {@code https://auth.kelta.io}), and</li>
 *   <li>any verified tenant custom domain that maps to a known slug
 *       (e.g. {@code https://acme.com} when {@code acme.com} is registered for
 *       tenant {@code acme}).</li>
 * </ul>
 *
 * <p>External IdPs federate through kelta-auth — they never sign tokens directly.
 */
public class DynamicReactiveJwtDecoder implements ReactiveJwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(DynamicReactiveJwtDecoder.class);

    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final String primaryIssuer;
    private final Duration clockSkew;
    private final GatewayCacheManager cacheManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, NimbusReactiveJwtDecoder> delegates = new ConcurrentHashMap<>();

    public DynamicReactiveJwtDecoder(String primaryIssuer, Duration clockSkew, GatewayCacheManager cacheManager) {
        if (primaryIssuer == null || primaryIssuer.isBlank()) {
            throw new IllegalArgumentException("primaryIssuer must be set to the internal kelta-auth issuer");
        }
        this.primaryIssuer = stripTrailingSlash(primaryIssuer);
        this.clockSkew = clockSkew != null ? clockSkew : Duration.ZERO;
        this.cacheManager = cacheManager;
        log.info("DynamicReactiveJwtDecoder initialized: primaryIssuer={}, clockSkew={}s",
                this.primaryIssuer, this.clockSkew.getSeconds());
    }

    @Override
    public Mono<Jwt> decode(String token) throws JwtException {
        return decode(token, null);
    }

    /**
     * Decodes a JWT. The {@code tenantId} parameter is retained for source
     * compatibility with callers and ignored — issuer validation matches against
     * the primary issuer or any verified tenant custom domain.
     */
    public Mono<Jwt> decode(String token, String tenantId) throws JwtException {
        String issuer;
        try {
            issuer = extractIssuer(token);
        } catch (RuntimeException e) {
            return reject("Failed to parse JWT issuer: " + e.getMessage());
        }

        if (issuer == null || issuer.isBlank()) {
            return reject("JWT missing issuer (iss) claim");
        }

        String normalised = stripTrailingSlash(issuer);
        if (!isAcceptedIssuer(normalised)) {
            log.warn("Rejecting JWT: iss={} not platform issuer or verified custom domain", normalised);
            // The issuer is caller-supplied; keep it out of the message, which becomes the
            // WWW-Authenticate error_description.
            return Mono.error(new BadJwtException(
                    "JWT issuer not accepted: not the platform issuer or a verified tenant custom domain"));
        }

        // The Nimbus delegate already raises BadJwtException for signature, expiry and validator
        // failures, and a plain JwtException for a JWKS fetch failure (5xx, not 401).
        return delegateFor(normalised).decode(token);
    }

    /**
     * A token-shape problem is the caller's fault: {@link BadJwtException} is what Spring
     * Security's {@code JwtReactiveAuthenticationManager} maps to a 401 {@code invalid_token}.
     * Any other {@link JwtException} becomes an {@code AuthenticationServiceException} (500).
     */
    private static Mono<Jwt> reject(String reason) {
        log.warn("Rejecting JWT: {}", reason);
        return Mono.error(new BadJwtException(reason));
    }

    private boolean isAcceptedIssuer(String issuer) {
        if (primaryIssuer.equals(issuer)) return true;
        // Anything else: only accept when the issuer's host is a registered
        // custom domain. This stops attackers from supplying tokens signed by
        // arbitrary URLs.
        String host = hostOf(issuer);
        return host != null && cacheManager.resolveCustomDomain(host).isPresent();
    }

    private NimbusReactiveJwtDecoder delegateFor(String issuer) {
        return delegates.computeIfAbsent(issuer, iss -> {
            String jwksUri = iss + "/oauth2/jwks";
            NimbusReactiveJwtDecoder nimbus = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
            nimbus.setJwtValidator(bearerTokenValidator(clockSkew));
            log.debug("Initialized JWKS decoder for {}", jwksUri);
            return nimbus;
        });
    }

    /**
     * Validates a JWT presented as a bearer credential: timestamps, and not a refresh token.
     * kelta-auth's direct-login refresh token ({@code token_type=refresh}) is signed by the
     * same key as access tokens, so without this check it would authenticate as one.
     */
    static OAuth2TokenValidator<Jwt> bearerTokenValidator(Duration clockSkew) {
        return new DelegatingOAuth2TokenValidator<>(
                List.of(new JwtTimestampValidator(clockSkew), DynamicReactiveJwtDecoder::rejectRefreshToken));
    }

    private static OAuth2TokenValidatorResult rejectRefreshToken(Jwt jwt) {
        if (REFRESH_TOKEN_TYPE.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM))) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN, "A refresh token is not a bearer token", null));
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String hostOf(String issuer) {
        try {
            return URI.create(issuer).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractIssuer(String token) {
        if (token == null) {
            throw new IllegalArgumentException("Invalid JWT format");
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid JWT format");
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode issNode = node.get("iss");
            return issNode != null && !issNode.isNull() ? issNode.asText() : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JWT payload");
        }
    }

    /**
     * Drops the cached JWKS decoders so the next decode rebuilds them. Called
     * when key rotation or configuration changes are signalled.
     */
    public void evictAll() {
        delegates.clear();
        log.info("Evicted cached JWT decoders");
    }
}
