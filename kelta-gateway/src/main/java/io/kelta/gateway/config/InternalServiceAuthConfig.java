package io.kelta.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
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
 * <p>Exposes {@link #internalBearerExchangeFilter}, which
 * {@code GatewayConfig.webClientBuilder} installs on the shared WebClient
 * builder. With the rollout flag on it fetches a {@code gateway-internal}
 * {@code client_credentials} token from kelta-auth's Spring Authorization Server
 * token endpoint, caches it via the standard Spring Security reactive machinery,
 * and attaches a short-lived {@code Authorization: Bearer …} header only on
 * requests whose URL path starts with {@code /internal/}. Non-internal requests
 * pass through untouched.
 *
 * <p><b>Rollout flag.</b> {@link InternalAuthProperties#enabled()}
 * (env {@code KELTA_GATEWAY_INTERNAL_AUTH_ENABLED}, default off) is read when the filter bean is
 * built, not through {@code @ConditionalOnProperty}: the gateway ships as a
 * native image, which freezes conditions at build time and would ignore a
 * deploy-time env flip. Off, the filter passes every request through unchanged
 * and needs no secret. On, a missing secret or issuer fails startup. Flip it once
 * worker has {@code KELTA_WORKER_INTERNAL_AUTH_ENABLED=true} and the
 * {@code gateway-internal} client is registered in kelta-auth.
 *
 * <p>The client registration is held privately rather than exposed as a
 * {@link ReactiveClientRegistrationRepository} bean, so it never feeds Spring
 * Boot's reactive OAuth2 client auto-configuration.
 */
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalServiceAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthConfig.class);

    /** Registration id used to look up the client in Spring Security. */
    public static final String CLIENT_REGISTRATION_ID = "gateway-internal";

    /** Path prefix that triggers bearer-token attachment. */
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    /**
     * Path-aware {@link ExchangeFilterFunction}. Applied globally via the shared
     * WebClient.Builder; only requests to {@code /internal/**} pay the cost of
     * token acquisition, and only with the rollout flag on.
     */
    @Bean
    public ExchangeFilterFunction internalBearerExchangeFilter(
            InternalAuthProperties internalAuth,
            @Value("${kelta.auth.issuer-uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri:}}") String issuerUri) {
        if (!internalAuth.enabled()) {
            return (request, next) -> next.exchange(request);
        }
        ReactiveOAuth2AuthorizedClientManager clientManager = authorizedClientManager(internalAuth, issuerUri);

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

    /**
     * Registers the {@code gateway-internal} OAuth2 client so the reactive
     * client manager can fetch access tokens from kelta-auth via the
     * {@code client_credentials} grant.
     */
    private static ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            InternalAuthProperties internalAuth, String issuerUri) {
        String clientId = internalAuth.clientId();
        String clientSecret = internalAuth.clientSecret();
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Internal auth is enabled (KELTA_GATEWAY_INTERNAL_AUTH_ENABLED) but kelta.gateway.internal-auth.client-secret "
                            + "is not configured. Register the matching client in kelta-auth via "
                            + "kelta.auth.internal-clients." + clientId + ".secret.");
        }
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "Internal auth is enabled (KELTA_GATEWAY_INTERNAL_AUTH_ENABLED) but no OAuth2 issuer URI is configured "
                            + "(kelta.auth.issuer-uri or spring.security.oauth2.resourceserver.jwt.issuer-uri).");
        }

        String tokenUri = issuerUri.endsWith("/") ? issuerUri + "oauth2/token" : issuerUri + "/oauth2/token";
        ClientRegistration registration = ClientRegistration.withRegistrationId(CLIENT_REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .scope("internal")
                .build();

        var clients = new InMemoryReactiveClientRegistrationRepository(registration);
        var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                clients, new InMemoryReactiveOAuth2AuthorizedClientService(clients));
        manager.setAuthorizedClientProvider(
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build());

        log.info("Registered OAuth2 client '{}' for worker /internal/** calls (token URI: {})",
                clientId, tokenUri);
        return manager;
    }
}
