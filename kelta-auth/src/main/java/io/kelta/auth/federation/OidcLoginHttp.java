package io.kelta.auth.federation;

import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP side of federated OIDC login: the clients Spring Security uses to call a tenant's
 * identity provider for the token exchange, the user-info lookup and the JWKS fetch.
 *
 * <p>Those endpoint URLs come from tenant configuration (or that provider's discovery
 * document), so every request goes through one {@link ClientHttpRequestFactory} that
 * <ul>
 *   <li>checks the URL with the service's {@link OutboundUrlGuard} right before it is sent,
 *       refusing non-public addresses unless allow-listed (the refusal surfaces as an I/O
 *       error, which Spring turns into a failed login);</li>
 *   <li>never follows redirects; and</li>
 *   <li>bounds the connect ({@link #CONNECT_TIMEOUT}) and the wait for a response
 *       ({@link #READ_TIMEOUT}).</li>
 * </ul>
 * The token and user-info clients keep Spring's default message converters and OAuth 2.0
 * error handling; the ID-token decoder mirrors {@link OidcIdTokenDecoderFactory}'s defaults
 * (RS256 via the provider's JWKS, the default OIDC validators and claim conversion).
 */
public final class OidcLoginHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private OidcLoginHttp() {
    }

    /** Guarded, redirect-free, timeout-bounded request factory for identity-provider calls. */
    public static ClientHttpRequestFactory requestFactory(OutboundUrlGuard urlGuard) {
        return requestFactory(urlGuard, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /** With explicit timeouts — tests use it to shorten them. */
    static ClientHttpRequestFactory requestFactory(OutboundUrlGuard urlGuard, Duration connectTimeout,
                                                   Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory delegate = new JdkClientHttpRequestFactory(httpClient);
        delegate.setReadTimeout(readTimeout);
        return (uri, method) -> {
            try {
                urlGuard.check(uri.toString());
            } catch (OutboundUrlGuard.RejectedUrlException e) {
                throw new IOException("identity provider URL refused: " + e.getMessage());
            }
            return delegate.createRequest(uri, method);
        };
    }

    /** Authorization-code token exchange over {@code requestFactory}. */
    public static OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient(
            ClientHttpRequestFactory requestFactory) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return client;
    }

    /** User-info lookup over {@code requestFactory}. */
    public static OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService(
            ClientHttpRequestFactory requestFactory) {
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultOAuth2UserService service = new DefaultOAuth2UserService();
        service.setRestOperations(restTemplate);
        return service;
    }

    /** OIDC user service whose user-info call goes over {@code requestFactory}. */
    public static OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
            ClientHttpRequestFactory requestFactory) {
        OidcUserService service = new OidcUserService();
        service.setOauth2UserService(oauth2UserService(requestFactory));
        return service;
    }

    /** ID-token decoders whose JWKS fetch goes over {@code requestFactory}. */
    public static JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(
            ClientHttpRequestFactory requestFactory) {
        return new IdTokenDecoderFactory(new RestTemplate(requestFactory));
    }

    /**
     * One decoder per registration, JWKS URL, client id and issuer, so a provider whose
     * configuration changes gets a new decoder (and validator) instead of the cached one.
     */
    private static final class IdTokenDecoderFactory implements JwtDecoderFactory<ClientRegistration> {

        private final RestTemplate restTemplate;
        private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

        IdTokenDecoderFactory(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
        }

        @Override
        public JwtDecoder createDecoder(ClientRegistration registration) {
            String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
            if (!StringUtils.hasText(jwkSetUri)) {
                OAuth2Error error = new OAuth2Error("missing_signature_verifier",
                        "Failed to find a Signature Verifier for Client Registration: '"
                                + registration.getRegistrationId()
                                + "'. Check to ensure you have configured the JwkSet URI.", null);
                throw new OAuth2AuthenticationException(error, error.toString());
            }
            String key = String.join("|", registration.getRegistrationId(), jwkSetUri,
                    registration.getClientId(), String.valueOf(registration.getProviderDetails().getIssuerUri()));
            return decoders.computeIfAbsent(key, ignored -> {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .restOperations(restTemplate)
                        .build();
                decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                        new OidcIdTokenValidator(registration)));
                decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
                return decoder;
            });
        }
    }
}
