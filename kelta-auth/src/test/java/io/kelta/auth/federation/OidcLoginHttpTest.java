package io.kelta.auth.federation;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The clients federated login uses to call an identity provider, against a provider on
 * loopback: token exchange, user info and the JWKS fetch work when the address is allow-listed,
 * and are refused before anything is sent when it is not.
 */
@DisplayName("OidcLoginHttp")
class OidcLoginHttpTest {

    private static final String CLIENT_ID = "kelta-client";

    private HttpServer server;
    private String base;
    private RSAKey signingKey;
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private volatile long delayMillis;
    private volatile String lastAuthorization;

    private record Response(int status, String body, String location) {}

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Response response = responses.getOrDefault(exchange.getRequestURI().getPath(),
                    new Response(404, "{}", null));
            if (response.location() != null) {
                exchange.getResponseHeaders().add("Location", response.location());
            }
            byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        responses.put("/jwks", new Response(200, new JWKSet(signingKey.toPublicJWK()).toString(), null));
        responses.put("/token", new Response(200,
                "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"openid\"}", null));
        responses.put("/userinfo", new Response(200, "{\"sub\":\"user-1\",\"email\":\"user@example.com\"}", null));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static ClientHttpRequestFactory allowingLoopback() {
        return OidcLoginHttp.requestFactory(new OutboundUrlGuard("127.0.0.0/8"));
    }

    private static ClientHttpRequestFactory defaultGuard() {
        return OidcLoginHttp.requestFactory(new OutboundUrlGuard(""));
    }

    private ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("tenant1:prov-1")
                .clientId(CLIENT_ID)
                .clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://auth.example.test/login/oauth2/code/tenant1:prov-1")
                .scope("openid", "email", "profile")
                .authorizationUri(base + "/authorize")
                .tokenUri(base + "/token")
                .userInfoUri(base + "/userinfo")
                .jwkSetUri(base + "/jwks")
                .userNameAttributeName("sub")
                .issuerUri(base)
                .build();
    }

    private OAuth2AuthorizationCodeGrantRequest grantRequest() {
        ClientRegistration registration = registration();
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(CLIENT_ID)
                .redirectUri(registration.getRedirectUri())
                .state("state-1")
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("code-1")
                .redirectUri(registration.getRedirectUri())
                .state("state-1")
                .build();
        return new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(request, response));
    }

    private String idToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(base)
                .subject("user-1")
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    @DisplayName("token exchange succeeds against an allow-listed provider")
    void tokenExchange() {
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> client =
                OidcLoginHttp.tokenResponseClient(allowingLoopback());

        OAuth2AccessTokenResponse response = client.getTokenResponse(grantRequest());

        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("at-1");
        assertThat(lastAuthorization).startsWith("Basic ");
    }

    @Test
    @DisplayName("token exchange is refused for a loopback provider by default, and nothing is sent")
    void tokenExchangeRefusedByDefault() {
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> client =
                OidcLoginHttp.tokenResponseClient(defaultGuard());

        assertThatThrownBy(() -> client.getTokenResponse(grantRequest()))
                .isInstanceOf(OAuth2AuthorizationException.class)
                .hasStackTraceContaining("refused");
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("user info is fetched from an allow-listed provider")
    void userInfo() {
        OAuth2User user = OidcLoginHttp.oauth2UserService(allowingLoopback()).loadUser(new OAuth2UserRequest(
                registration(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at-1",
                        Instant.now(), Instant.now().plusSeconds(300))));

        assertThat(user.getName()).isEqualTo("user-1");
        assertThat(lastAuthorization).isEqualTo("Bearer at-1");
    }

    @Test
    @DisplayName("an ID token is verified with the provider's JWKS when allow-listed")
    void idTokenVerified() throws Exception {
        JwtDecoder decoder = OidcLoginHttp.idTokenDecoderFactory(allowingLoopback()).createDecoder(registration());

        Jwt jwt = decoder.decode(idToken());

        assertThat(jwt.getSubject()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("the JWKS fetch is refused for a loopback provider by default")
    void jwksRefusedByDefault() throws Exception {
        JwtDecoder decoder = OidcLoginHttp.idTokenDecoderFactory(defaultGuard()).createDecoder(registration());
        String token = idToken();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("an ID token for another client is rejected")
    void idTokenAudienceChecked() throws Exception {
        ClientRegistration other = ClientRegistration.withClientRegistration(registration()).clientId("someone-else").build();
        JwtDecoder decoder = OidcLoginHttp.idTokenDecoderFactory(allowingLoopback()).createDecoder(other);
        String token = idToken();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("redirects are not followed")
    void redirectNotFollowed() {
        responses.put("/moved", new Response(302, null, "http://10.0.0.1/jwks"));

        ResponseEntity<String> response = new RestTemplate(allowingLoopback())
                .getForEntity(base + "/moved", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a slow provider times out")
    void readTimeout() {
        delayMillis = 2_000;
        ClientHttpRequestFactory quick = OidcLoginHttp.requestFactory(new OutboundUrlGuard("127.0.0.0/8"),
                Duration.ofSeconds(1), Duration.ofMillis(300));

        assertThatThrownBy(() -> new RestTemplate(quick).getForEntity(base + "/userinfo", String.class))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("the request factory refuses a private address before connecting")
    void requestFactoryRefusesPrivate() {
        assertThatThrownBy(() -> new RestTemplate(allowingLoopback()).getForEntity("http://10.0.0.1/x", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("refused");
    }
}
