package io.kelta.gateway.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration class for Spring Cloud Gateway routing.
 *
 * Routes are provided by {@link io.kelta.gateway.route.DynamicRouteLocator}, which is
 * a {@code @Component} implementing {@code RouteLocator}. Spring Cloud Gateway's
 * {@code GatewayAutoConfiguration} automatically discovers all {@code RouteLocator}
 * beans and includes them in its {@code CompositeRouteLocator}.
 *
 * <p><strong>Note:</strong> Do NOT define a {@code @Bean RouteLocator routeLocator(...)}
 * here — that would override Spring Cloud Gateway's auto-configured
 * {@code CachingRouteLocator} bean (which is also named "routeLocator"),
 * breaking route caching and the {@code RefreshRoutesEvent} mechanism.
 *
 * Validates: Requirements 9.1, 9.2
 */
@Configuration
public class GatewayConfig {

    /**
     * Shared {@link WebClient.Builder} for outbound calls — every worker-bound
     * caller in the gateway injects this builder and customizes (e.g. sets a
     * baseUrl).
     *
     * <p>The builder carries the {@link ExchangeFilterFunction} from
     * {@link InternalServiceAuthConfig}. When the {@code internal-auth} rollout
     * flag is on, it attaches a short-lived bearer token to any request whose URL
     * path starts with {@code /internal/}; when off, it passes requests through.
     * The filter is path-aware, so non-internal traffic (e.g. OIDC discovery,
     * JWKS, health probes against other services) is untouched.
     */
    @Bean
    public WebClient.Builder webClientBuilder(
            @Qualifier("internalBearerExchangeFilter") ExchangeFilterFunction internalAuthFilter) {
        return WebClient.builder().filter(internalAuthFilter);
    }
}
