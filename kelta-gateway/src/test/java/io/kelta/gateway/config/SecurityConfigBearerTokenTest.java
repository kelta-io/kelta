package io.kelta.gateway.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.kelta.gateway.auth.DynamicReactiveJwtDecoder;
import io.kelta.gateway.cache.GatewayCacheManager;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real {@link SecurityConfig#securityWebFilterChain} with a real
 * {@link DynamicReactiveJwtDecoder}: a bad bearer token is the caller's fault (401
 * {@code invalid_token}), an unreachable JWKS is ours (5xx).
 */
@DisplayName("SecurityConfig bearer-token handling (KLT-773)")
class SecurityConfigBearerTokenTest {

    private static final String API_PATH = "/rzware/api/tasks";

    private MockWebServer issuerServer;
    private String issuer;
    private RSAKey signingKey;
    private NimbusJwtEncoder encoder;
    private GatewayCacheManager cacheManager;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate()).keyID("k1").build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));

        cacheManager = Mockito.mock(GatewayCacheManager.class);
        Mockito.lenient().when(cacheManager.resolveCustomDomain(Mockito.anyString()))
                .thenReturn(Optional.empty());

        issuerServer = new MockWebServer();
        issuerServer.start();
        issuer = stripTrailingSlash(issuerServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        issuerServer.shutdown();
    }

    @Test
    @DisplayName("a token that is not a JWT at all gets 401 invalid_token")
    void garbageToken() {
        expectInvalidToken(clientFor(issuer), "abc");
    }

    @Test
    @DisplayName("an unsigned alg:none token for the trusted issuer gets 401 invalid_token")
    void algNoneToken() {
        serveJwks();
        String token = b64("{\"alg\":\"none\"}") + "." + b64(claimsJson(issuer)) + ".";
        expectInvalidToken(clientFor(issuer), token);
    }

    @Test
    @DisplayName("an RS256-shaped token with no iss gets 401 invalid_token")
    void tokenWithoutIssuer() {
        String token = b64("{\"alg\":\"RS256\",\"kid\":\"k1\"}") + "." + b64("{\"sub\":\"u\"}") + ".sig";
        expectInvalidToken(clientFor(issuer), token);
    }

    @Test
    @DisplayName("a token from an untrusted issuer gets 401 invalid_token")
    void untrustedIssuer() {
        String token = b64("{\"alg\":\"RS256\",\"kid\":\"k1\"}") + "."
                + b64(claimsJson("https://idp.attacker.example")) + ".sig";
        expectInvalidToken(clientFor(issuer), token);
    }

    @Test
    @DisplayName("an expired token signed by the trusted issuer's JWKS gets 401 invalid_token")
    void expiredSignedToken() {
        serveJwks();
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = sign(issuer, past, past.plus(1, ChronoUnit.HOURS));
        expectInvalidToken(clientFor(issuer), expired);
        assertThat(issuerServer.getRequestCount()).isPositive();
    }

    @Test
    @DisplayName("a valid signed token for the trusted issuer passes the chain")
    void validSignedToken() {
        serveJwks();
        Instant now = Instant.now();
        clientFor(issuer).get().uri(API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(issuer, now, now.plus(1, ChronoUnit.HOURS)))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("a well-formed token while the issuer's JWKS returns 500 gets a 5xx, not 401")
    void jwksReturns500() {
        issuerServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500);
            }
        });
        Instant now = Instant.now();
        String token = sign(issuer, now, now.plus(1, ChronoUnit.HOURS));

        clientFor(issuer).get().uri(API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().is5xxServerError();
        assertThat(issuerServer.getRequestCount()).isPositive();
    }

    @Test
    @DisplayName("a well-formed token while the issuer's JWKS is unreachable gets a 5xx, not 401")
    void jwksUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String unreachableIssuer = "http://127.0.0.1:" + closedPort;
        Instant now = Instant.now();
        String token = sign(unreachableIssuer, now, now.plus(1, ChronoUnit.HOURS));

        clientFor(unreachableIssuer).get().uri(API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    private WebTestClient clientFor(String trustedIssuer) {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowedOriginPattern", "https://app.kelta.io");
        DynamicReactiveJwtDecoder decoder = new DynamicReactiveJwtDecoder(trustedIssuer, Duration.ZERO, cacheManager);
        var chain = config.securityWebFilterChain(ServerHttpSecurity.http(), decoder);
        return WebTestClient
                .bindToWebHandler(exchange -> exchange.getResponse().setComplete())
                .webFilter(new WebFilterChainProxy(chain))
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static void expectInvalidToken(WebTestClient client, String token) {
        client.get().uri(API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertThat(value).contains("error=\"invalid_token\""));
    }

    private void serveJwks() {
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        issuerServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/oauth2/jwks".equals(request.getPath())) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(jwks);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    private String sign(String iss, Instant issuedAt, Instant expiresAt) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("k1").build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(iss)
                .subject("user-1")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String claimsJson(String iss) {
        long exp = Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond();
        return "{\"iss\":\"" + iss + "\",\"sub\":\"u\",\"exp\":" + exp + "}";
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
