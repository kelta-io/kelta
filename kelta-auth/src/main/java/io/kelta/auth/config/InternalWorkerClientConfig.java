package io.kelta.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Wires kelta-auth's service-to-service OAuth2 client for {@code /internal/**}
 * calls into the worker.
 *
 * <p>kelta-auth is the token issuer, so this client asks itself for tokens via
 * {@code client_credentials}. The matching client is registered by
 * {@link InternalClientRegistrar} at startup from
 * {@code kelta.auth.internal-clients.auth-internal.secret}.
 *
 * <p>Exposes {@link #internalWorkerRestClient} — the {@link RestClient}
 * {@link io.kelta.auth.service.WorkerClient} uses for every worker call. With the
 * rollout flag on it carries a synchronous request-phase interceptor that
 * attaches {@code Authorization: Bearer …} to every request whose path starts
 * with {@code /internal/}. Non-internal calls pass through untouched, so callers
 * that hit {@code /api/internal/email/send} (still on the legacy
 * {@code X-Internal-Token} path) are unaffected.
 *
 * <p><b>Rollout flag.</b> {@link InternalAuthProperties#enabled()}
 * (env {@code KELTA_AUTH_INTERNAL_AUTH_ENABLED}, default off) is read when the bean is built,
 * not through {@code @ConditionalOnProperty}: kelta-auth ships as a native image,
 * which freezes conditions at build time and would ignore a deploy-time env flip.
 * Off, the client is a plain worker client and no OAuth2 machinery or secret is
 * needed. On, a missing secret or issuer fails startup.
 *
 * <p>The {@code auth-internal} registration lives in a private
 * {@link InMemoryClientRegistrationRepository}, never a bean, so it cannot become
 * a second {@code ClientRegistrationRepository} candidate next to the federation
 * {@code DynamicClientRegistrationRepository}.
 */
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
@RegisterReflectionForBinding(InternalAuthProperties.class)
public class InternalWorkerClientConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalWorkerClientConfig.class);

    /** Registration id used to look up the client in Spring Security. */
    public static final String CLIENT_REGISTRATION_ID = "auth-internal";

    /** Path prefix that triggers bearer-token attachment. */
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    /**
     * {@link RestClient} for worker calls, based at {@code kelta.auth.worker-url}.
     * Built from the Spring-managed {@link RestClient.Builder} so outbound calls
     * carry trace context.
     */
    @Bean
    public RestClient internalWorkerRestClient(InternalAuthProperties internalAuth,
                                               AuthProperties authProperties,
                                               RestClient.Builder restClientBuilder) {
        RestClient.Builder builder = restClientBuilder.baseUrl(authProperties.getWorkerUrl());
        if (!internalAuth.enabled()) {
            return builder.build();
        }
        OAuth2AuthorizedClientManager clientManager = authorizedClientManager(internalAuth, authProperties);
        return builder.requestInterceptor(bearerInterceptor(clientManager)).build();
    }

    private static OAuth2AuthorizedClientManager authorizedClientManager(InternalAuthProperties internalAuth,
                                                                         AuthProperties authProperties) {
        String clientId = internalAuth.clientId();
        String clientSecret = internalAuth.clientSecret();
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Internal auth is enabled (KELTA_AUTH_INTERNAL_AUTH_ENABLED) but kelta.auth.internal-auth.client-secret "
                            + "is not configured. It must match kelta.auth.internal-clients." + clientId + ".secret.");
        }
        String issuerUri = authProperties.getIssuerUri();
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "kelta.auth.issuer-uri must be configured when internal-auth is enabled.");
        }

        // kelta-auth is its own token issuer, so the token URI points at the local authorization server.
        String tokenUri = issuerUri.endsWith("/") ? issuerUri + "oauth2/token" : issuerUri + "/oauth2/token";
        ClientRegistration registration = ClientRegistration.withRegistrationId(CLIENT_REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .scope("internal")
                .build();

        var clients = new InMemoryClientRegistrationRepository(registration);
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clients, new InMemoryOAuth2AuthorizedClientService(clients));
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build());

        log.info("WorkerClient using OAuth2 client '{}' for worker /internal/** calls (token URI: {})",
                clientId, tokenUri);
        return manager;
    }

    private static ClientHttpRequestInterceptor bearerInterceptor(OAuth2AuthorizedClientManager clientManager) {
        // client_credentials has no end-user principal; a stable service identity
        // lets the cached authorized-client lookup hit on every request.
        Authentication servicePrincipal = new AnonymousAuthenticationToken(
                CLIENT_REGISTRATION_ID,
                CLIENT_REGISTRATION_ID,
                List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));

        return (request, body, execution) -> {
            String path = request.getURI().getPath();
            if (path != null && path.startsWith(INTERNAL_PATH_PREFIX)) {
                var client = clientManager.authorize(
                        OAuth2AuthorizeRequest
                                .withClientRegistrationId(CLIENT_REGISTRATION_ID)
                                .principal(servicePrincipal)
                                .build());
                if (client == null || client.getAccessToken() == null) {
                    throw new IllegalStateException(
                            "client_credentials exchange returned no token for " + CLIENT_REGISTRATION_ID);
                }
                request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
            }
            return execution.execute(request, body);
        };
    }
}
