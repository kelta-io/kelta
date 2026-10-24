package io.kelta.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration that enforces OAuth2 bearer-token authentication
 * on the worker's {@code /internal/**} endpoints and leaves every other path
 * untouched so the existing gateway-trusts-headers model keeps working.
 *
 * <p>The platform's gateway, ai, and auth services call into {@code /internal/**}
 * for bootstrap data, slug-maps, OIDC lookups, and JIT user provisioning. Those
 * calls now carry a short-lived JWT obtained from kelta-auth via the
 * {@code client_credentials} grant; this filter chain validates the JWT via
 * kelta-auth's JWKS and requires the {@code SCOPE_internal} authority on the
 * bearer token.
 *
 * <p>If {@code kelta.auth.issuer-uri} (or the Spring Boot standard
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}) is blank — for
 * example in a local dev container started before kelta-auth — the filter chain
 * is still registered, but requests will fail closed with 401. Configure the
 * issuer explicitly to enable internal calls.
 *
 * <p><b>Rollout flag.</b> The JWT requirement is <em>off by default</em>. Flip
 * {@code kelta.worker.internal-auth.enabled=true} (or the env
 * {@code KELTA_WORKER_INTERNAL_AUTH_ENABLED=true}) once every caller — gateway,
 * ai, auth — has been updated to acquire a bearer token and attach it. Until
 * then {@code /internal/**} is permit-all so the acceptance side can ship ahead
 * of the caller side without breaking production. The flag
 * ({@link InternalAuthProperties}) is read when the {@code /internal/**} chain is
 * built rather than through {@code @ConditionalOnProperty}, so a native build
 * (as {@code make up} produces) honours a deploy-time env flip too.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalEndpointSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalEndpointSecurityConfig.class);

    private static final String INTERNAL_PATH = "/internal/**";
    private static final String INTERNAL_SCOPE = "SCOPE_internal";

    /**
     * Security chain for {@code /internal/**}: JWT with scope {@code internal}
     * when the rollout flag is on, permit-all when it is off.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalEndpointFilterChain(HttpSecurity http,
            InternalAuthProperties internalAuth,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:${kelta.auth.issuer-uri:}}") String issuerUri)
            throws Exception {

        http
                .securityMatcher(INTERNAL_PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!internalAuth.enabled()) {
            log.info("Worker /internal/** is permit-all (kelta.worker.internal-auth.enabled=false). "
                    + "Flip the flag once every caller acquires a client_credentials JWT.");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        if (issuerUri == null || issuerUri.isBlank()) {
            log.warn("OAuth2 issuer URI is not configured — /internal/** endpoints will reject every request. "
                    + "Set spring.security.oauth2.resourceserver.jwt.issuer-uri to enable service-to-service calls.");
        } else {
            log.info("Worker /internal/** protected by OAuth2 JWT (issuer={}, required scope=internal)", issuerUri);
        }
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasAuthority(INTERNAL_SCOPE))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Catch-all chain that preserves the worker's existing posture: the gateway
     * authenticates users upstream and forwards trusted tenant/user headers, so
     * the worker does not re-authenticate user traffic. Without this chain
     * Spring Security auto-config would lock every endpoint down.
     */
    @Bean
    @Order(100)
    public SecurityFilterChain defaultPermitAllFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
