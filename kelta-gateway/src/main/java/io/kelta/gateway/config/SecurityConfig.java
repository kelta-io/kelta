package io.kelta.gateway.config;

import io.kelta.gateway.auth.DynamicReactiveJwtDecoder;
import io.kelta.gateway.auth.PatAwareBearerTokenConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
// Note: Bean returns DynamicReactiveJwtDecoder (which implements ReactiveJwtDecoder)
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for the API Gateway.
 * Configures JWT validation, authentication with multi-provider support,
 * and CORS at the security layer.
 * <p>
 * CORS is configured here (rather than relying solely on Spring Cloud Gateway's
 * globalcors) so that CORS headers are added to ALL responses, including error
 * responses (e.g. 404 from tenant slug resolution) that occur before gateway
 * route matching.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /** Where the gateway reaches kelta-auth for the issuer's keys; blank = the issuer URI. */
    @Value("${kelta.gateway.auth-service-url:}")
    private String authServiceUrl;

    @Value("${CORS_ALLOWED_ORIGIN_PATTERN:}")
    private String corsAllowedOriginPattern;

    @Value("${kelta.gateway.security.jwt-clock-skew-seconds:30}")
    private long jwtClockSkewSeconds;

    /**
     * Configures CORS at the WebFlux security layer.
     * This ensures CORS headers are present on all responses, including
     * error responses that short-circuit before gateway route matching.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        if (corsAllowedOriginPattern == null || corsAllowedOriginPattern.isBlank()) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGIN_PATTERN must be configured. " +
                    "Set it to your UI domain (e.g., 'https://app.example.com'). " +
                    "Wildcard '*' is not permitted with allowCredentials=true.");
        }
        // Comma-separated so several product frontends (e.g. app.kelta.io +
        // app.example.com) can each call the gateway from the browser. Each
        // trimmed entry is a Spring allowedOriginPattern.
        List<String> originPatterns = Arrays.stream(corsAllowedOriginPattern.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (originPatterns.contains("*")) {
            log.warn("CORS_ALLOWED_ORIGIN_PATTERN includes '*' which allows any origin " +
                    "to make credentialed requests. This is insecure for production.");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(originPatterns);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // If-Match / ETag: record optimistic locking (unified record slice 5). The SPA calls the
        // gateway cross-origin, so without these the browser can neither read a record's ETag nor
        // send it back — the preflight would reject an If-Match write outright.
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Correlation-ID",
                "X-Tenant-ID", "X-Tenant-Slug", "X-Requested-With", "If-Match",
                "traceparent", "tracestate", "baggage",
                "b3", "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId", "X-B3-Sampled"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        configuration.setExposedHeaders(Arrays.asList(
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After",
                "ETag"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Configures the security filter chain.
     *
     * <p>API auth is still handled by the custom {@code GlobalFilter}s
     * ({@code JwtAuthenticationFilter}, {@code CerbosAuthorizationFilter}, …)
     * rather than by Spring Security, so {@code anyExchange().permitAll()}
     * stays the default — a regression that bypasses those filters still gets
     * caught downstream, but Spring Security itself does not re-authenticate
     * API traffic.
     *
     * <p>The exception is Spring Boot Actuator. {@code /actuator/health}
     * (Kubernetes liveness/readiness probes) and {@code /actuator/info} stay
     * public; everything else under {@code /actuator/**} — {@code metrics},
     * {@code env}, {@code loggers}, etc. — now requires an OAuth2 bearer
     * token, validated by the platform's existing {@link ReactiveJwtDecoder}
     * via the {@code oauth2ResourceServer} DSL. Closes the
     * "SecurityConfig permits all exchanges" defense-in-depth gap flagged in
     * {@code concerns.md}: even if actuator exposure was accidentally widened
     * in the management config, it can no longer be scraped anonymously.
     *
     * <p>CORS is enabled and delegates to the {@link CorsConfigurationSource}
     * bean so error responses that short-circuit before gateway routing still
     * carry CORS headers.
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                          ReactiveJwtDecoder reactiveJwtDecoder) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .pathMatchers("/actuator/**").authenticated()
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(oauth -> oauth
                    // PATs (klt_*) are validated by PatAuthenticationFilter as a
                    // GlobalFilter ordered before this chain. Filter them out at
                    // the converter so AuthenticationWebFilter doesn't try to
                    // authenticate them as JWTs (which surfaces 5xx).
                    .bearerTokenConverter(new PatAwareBearerTokenConverter())
                    .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder)))
            .build();
    }

    /**
     * Creates the JWT decoder bean. The gateway accepts only tokens issued by
     * the internal kelta-auth provider; external IdPs federate through kelta-auth,
     * which mints platform JWTs.
     */
    @Bean
    public DynamicReactiveJwtDecoder jwtDecoder(io.kelta.gateway.cache.GatewayCacheManager cacheManager) {
        log.info("Gateway JWT decoder: primary issuer {} + verified tenant custom domains", issuerUri);
        return new DynamicReactiveJwtDecoder(
                issuerUri, authServiceUrl, Duration.ofSeconds(jwtClockSkewSeconds), cacheManager);
    }
}
