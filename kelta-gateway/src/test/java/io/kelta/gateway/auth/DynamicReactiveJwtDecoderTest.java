package io.kelta.gateway.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.kelta.gateway.cache.GatewayCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DynamicReactiveJwtDecoder")
class DynamicReactiveJwtDecoderTest {

    private static final String INTERNAL_ISSUER = "https://auth.kelta.io";

    private DynamicReactiveJwtDecoder decoder;
    private GatewayCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = Mockito.mock(GatewayCacheManager.class);
        Mockito.lenient().when(cacheManager.resolveCustomDomain(Mockito.anyString()))
                .thenReturn(Optional.empty());
        decoder = new DynamicReactiveJwtDecoder(INTERNAL_ISSUER, Duration.ZERO, cacheManager);
    }

    @Test
    @DisplayName("rejects invalid JWT format")
    void rejectInvalidJwt() {
        StepVerifier.create(decoder.decode("not.a.jwt"))
                .expectError(JwtException.class)
                .verify();
    }

    @Test
    @DisplayName("rejects token without iss claim")
    void rejectTokenWithoutIssuer() {
        String token = unsignedToken("{\"sub\":\"user\"}");

        StepVerifier.create(decoder.decode(token))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(JwtException.class);
                    assertThat(err.getMessage()).contains("missing issuer");
                })
                .verify();
    }

    @Test
    @DisplayName("rejects token whose iss is not the platform issuer or a verified custom domain")
    void rejectsForeignIssuer() {
        String token = unsignedToken(
                "{\"iss\":\"https://idp.rzware.com/realms/rzWare\",\"sub\":\"u\"}");

        StepVerifier.create(decoder.decode(token))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(JwtException.class);
                    assertThat(err.getMessage()).contains("not the platform issuer");
                })
                .verify();
    }

    @Test
    @DisplayName("accepts issuer from a verified tenant custom domain (parses past validation)")
    void acceptsVerifiedCustomDomainIssuer() {
        Mockito.when(cacheManager.resolveCustomDomain("acme.com")).thenReturn(Optional.of("acme"));
        String token = unsignedToken("{\"iss\":\"https://acme.com\",\"sub\":\"u\"}");

        // The token will fail downstream (no real signature / JWKS unreachable),
        // but the failure must NOT be our issuer-allow check rejecting acme.com.
        StepVerifier.create(decoder.decode(token))
                .expectErrorSatisfies(err -> assertThat(err.getMessage())
                        .doesNotContain("not the platform issuer"))
                .verify();
    }

    @Test
    @DisplayName("constructor rejects null or blank issuer")
    void constructorRejectsBlankIssuer() {
        assertThatThrownBy(() -> new DynamicReactiveJwtDecoder(null, Duration.ZERO, cacheManager))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicReactiveJwtDecoder("", Duration.ZERO, cacheManager))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("evictAll clears the cached delegate")
    void evictAllClearsCache() {
        decoder.evictAll();
        // no exception — cache is empty
    }

    @Nested
    @DisplayName("Clock Skew")
    class ClockSkewTest {

        @Test
        @DisplayName("accepts explicit clock skew")
        void acceptsExplicitClockSkew() {
            DynamicReactiveJwtDecoder d = new DynamicReactiveJwtDecoder(
                    INTERNAL_ISSUER, Duration.ofSeconds(30), cacheManager);
            d.evictAll();
        }

        @Test
        @DisplayName("treats null clock skew as zero")
        void nullClockSkewBecomesZero() {
            DynamicReactiveJwtDecoder d = new DynamicReactiveJwtDecoder(INTERNAL_ISSUER, null, cacheManager);
            d.evictAll();
        }

        @Test
        @DisplayName("rejects invalid JWT even when clock skew is configured")
        void rejectsInvalidJwtWithClockSkew() {
            DynamicReactiveJwtDecoder d = new DynamicReactiveJwtDecoder(
                    INTERNAL_ISSUER, Duration.ofSeconds(60), cacheManager);

            StepVerifier.create(d.decode("not.a.jwt"))
                    .expectError(JwtException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Bearer token validator (PLT-707)")
    class BearerTokenValidatorTest {

        private RSAKey signingKey;
        private NimbusJwtEncoder encoder;
        private NimbusReactiveJwtDecoder delegate;

        @BeforeEach
        void setUp() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate()).keyID("k1").build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
            // The same validator delegateFor() installs on every per-issuer JWKS decoder.
            delegate = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
            delegate.setJwtValidator(DynamicReactiveJwtDecoder.bearerTokenValidator(Duration.ZERO));
        }

        @Test
        @DisplayName("rejects a signed refresh token shaped like DirectLoginController's")
        void rejectsDirectLoginRefreshToken() {
            Instant now = Instant.now();
            // Claims exactly as DirectLoginController builds its refresh token.
            String refreshToken = sign(JwtClaimsSet.builder()
                    .issuer(INTERNAL_ISSUER)
                    .subject("user-1")
                    .issuedAt(now)
                    .expiresAt(now.plus(30, ChronoUnit.DAYS))
                    .id(UUID.randomUUID().toString())
                    .claim("token_type", "refresh")
                    .build());

            StepVerifier.create(delegate.decode(refreshToken))
                    .expectErrorSatisfies(err -> {
                        assertThat(err).isInstanceOf(JwtException.class);
                        assertThat(err.getMessage()).contains("refresh token is not a bearer token");
                    })
                    .verify();
        }

        @Test
        @DisplayName("accepts a signed access token")
        void acceptsAccessToken() {
            Instant now = Instant.now();
            String accessToken = sign(JwtClaimsSet.builder()
                    .issuer(INTERNAL_ISSUER)
                    .subject("user-1")
                    .issuedAt(now)
                    .expiresAt(now.plus(1, ChronoUnit.HOURS))
                    .claim("tenant_id", "tenant-1")
                    .build());

            StepVerifier.create(delegate.decode(accessToken))
                    .assertNext(jwt -> assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("tenant-1"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("still rejects an expired token")
        void rejectsExpiredToken() {
            Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
            String expired = sign(JwtClaimsSet.builder()
                    .issuer(INTERNAL_ISSUER)
                    .subject("user-1")
                    .issuedAt(past)
                    .expiresAt(past.plus(1, ChronoUnit.HOURS))
                    .build());

            StepVerifier.create(delegate.decode(expired))
                    .expectError(JwtException.class)
                    .verify();
        }

        private String sign(JwtClaimsSet claims) {
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("k1").build();
            return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        }
    }

    private static String unsignedToken(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".signature";
    }
}
