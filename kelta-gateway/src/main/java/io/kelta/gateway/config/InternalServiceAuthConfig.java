package io.kelta.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Wires the gateway's service-to-service OAuth2 client for
 * {@code /internal/**} calls into the worker.
 *
 * <p>Registers the {@code gateway-internal} OAuth2 client against kelta-auth's
 * Spring Authorization Server token endpoint, caches authorized tokens via the
 * standard Spring Security reactive machinery, and exposes an
 * {@link ExchangeFilterFunction} that attaches a short-lived
 * {@code Authorization: Bearer …} header only on requests whose URL path
 * starts with {@code /internal/}. Non-internal requests pass through
 * untouched, so {@code GatewayConfig.webClientBuilder} can install the filter
 * globally without affecting other outbound traffic.
 *
 * <p><b>Rollout flag.</b> Everything here is gated behind
 * {@code kelta.gateway.internal-auth.enabled=true}. When the flag is off (the
 * default during rollout) no OAuth2 beans are created and the shared WebClient
 * builder degrades to its pre-change behavior; callers keep hitting worker's
 * {@code /internal/**} with no bearer token. Flip the flag once worker has
 * {@code kelta.worker.internal-auth.enabled=true} and the
 * {@code gateway-internal} client is registered in kelta-auth.
 */
@Configuration
public class InternalServiceAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthConfig.class);

    /** Registration id used to look up the client in Spring Security. */
    public static final String CLIENT_REGISTRATION_ID = "gateway-internal";

    /** Path prefix that triggers bearer-token attachment. */
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    /**
     * Registers the {@code gateway-internal} OAuth2 client so the reactive
     * client manager can fetch access tokens from kelta-auth via the
     * {@code client_credentials} grant.
     */
    @Bean
    @ConditionalOnProperty(name = "kelta.gateway.internal-auth.enabled", havingValue = "true")
    public ReactiveClientRegistrationRepository internalClientRegistrations(
            @Value("${kelta.gateway.internal-auth.client-id:gateway-internal}") String clientId,
            @Value("${kelta.gateway.internal-auth.client-secret:}") String clientSecret,
            @Value("${kelta.auth.issuer-uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri:}}") String issuerUri,
            @Value("${kelta.gateway.auth-service-url:}") String authServiceUrl) {

        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "kelta.gateway.internal-auth.enabled=true requires kelta.gateway.internal-auth.client-secret "
                            + "to be configured. Register the matching client in kelta-auth via "
                            + "kelta.auth.internal-clients." + clientId + ".secret.");
        }
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "kelta.gateway.internal-auth.enabled=true requires an OAuth2 issuer URI "
                            + "(kelta.auth.issuer-uri or spring.security.oauth2.resourceserver.jwt.issuer-uri).");
        }

        // kelta-auth's token endpoint, reached at the auth-service URL when one is set (the same
        // network path the JWT decoder fetches keys over), else at the issuer.
        String authBase = authServiceUrl == null || authServiceUrl.isBlank() ? issuerUri : authServiceUrl.trim();
        String tokenUri = authBase.endsWith("/") ? authBase + "oauth2/token" : authBase + "/oauth2/token";
        ClientRegistration registration = ClientRegistration.withRegistrationId(CLIENT_REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .scope("internal")
                .build();

        log.info("Registered OAuth2 client '{}' for worker /internal/** calls (token URI: {})",
                clientId, tokenUri);
        return new InMemoryReactiveClientRegistrationRepository(registration);
    }

    @Bean
    @ConditionalOnProperty(name = "kelta.gateway.internal-auth.enabled", havingValue = "true")
    public ReactiveOAuth2AuthorizedClientService internalAuthorizedClientService(
            ReactiveClientRegistrationRepository clients) {
        return new InMemoryReactiveOAuth2AuthorizedClientService(clients);
    }

    @Bean
    @ConditionalOnProperty(name = "kelta.gateway.internal-auth.enabled", havingValue = "true")
    public AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager internalAuthorizedClientManager(
            ReactiveClientRegistrationRepository clients,
            ReactiveOAuth2AuthorizedClientService service) {
        AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(clients, service);
        manager.setAuthorizedClientProvider(
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build());
        return manager;
    }

    /**
     * Path-aware {@link ExchangeFilterFunction}. Applied globally via the shared
     * WebClient.Builder; only requests to {@code /internal/**} pay the cost of
     * token acquisition.
     */
    @Bean
    @ConditionalOnProperty(name = "kelta.gateway.internal-auth.enabled", havingValue = "true")
    public ExchangeFilterFunction internalBearerExchangeFilter(
            AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager clientManager) {

        // client_credentials has no end-user principal; provide a stable
        // anonymous identity so the authorized-client cache keys on it.
        Authentication servicePrincipal = new AnonymousAuthenticationToken(
                CLIENT_REGISTRATION_ID,
                CLIENT_REGISTRATION_ID,
                List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));

        return (request, next) -> {
            if (request.url().getPath() == null
                    || !request.url().getPath().startsWith(INTERNAL_PATH_PREFIX)) {
                return next.exchange(request);
            }
            return clientManager
                    .authorize(OAuth2AuthorizeRequest
                            .withClientRegistrationId(CLIENT_REGISTRATION_ID)
                            .principal(servicePrincipal)
                            .build())
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "client_credentials exchange returned no token for " + CLIENT_REGISTRATION_ID)))
                    .flatMap(client -> {
                        ClientRequest authorized = ClientRequest.from(request)
                                .headers(h -> h.setBearerAuth(client.getAccessToken().getTokenValue()))
                                .build();
                        return next.exchange(authorized);
                    });
        };
    }
}
