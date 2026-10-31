package io.kelta.worker.module;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModuleAssertionSigner")
public class ModuleAssertionSignerTest {

    /** A throwaway P-256 key generated per test run; never a committed secret. */
    public static final String KEY_PEM = pem(generate());
    private static final String ISSUER = "https://api.example.test/api/modules";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private static final ModuleAssertionSigner.Assertion REQUEST = new ModuleAssertionSigner.Assertion(
            "acme-billing", "install-1", "tenant-1", "acme",
            new ModuleAssertionSigner.Actor("user-1", "INTERNAL", "profile-1"),
            "post", "https://billing.example.test/kelta/v1/lifecycle?x=1#frag",
            "{\"event\":\"installed\"}".getBytes(StandardCharsets.UTF_8));

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static ModuleAssertionSigner signer(String pem) {
        return new ModuleAssertionSigner(pem, ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("signs ES256 with the claims of spec §3.2 and verifies against the published JWKS")
    void signsTheSpecClaims() throws Exception {
        ModuleAssertionSigner signer = signer(KEY_PEM);

        SignedJWT jwt = SignedJWT.parse(signer.sign(REQUEST));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(signer.currentKeyId());
        ECKey published = (ECKey) JWKSet.parse(signer.jwks()).getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(published).isNotNull();
        assertThat(published.isPrivate()).isFalse();
        assertThat(jwt.verify(new ECDSAVerifier(published))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).containsExactly("acme-billing");
        assertThat(claims.getSubject()).isEqualTo("install-1");
        assertThat(claims.getStringClaim("tenant_id")).isEqualTo("tenant-1");
        assertThat(claims.getStringClaim("tenant_slug")).isEqualTo("acme");
        assertThat(claims.getStringClaim("htm")).isEqualTo("POST");
        assertThat(claims.getStringClaim("htu")).isEqualTo("https://billing.example.test/kelta/v1/lifecycle?x=1");
        assertThat(claims.getJSONObjectClaim("act"))
                .containsEntry("sub", "user-1")
                .containsEntry("user_type", "INTERNAL")
                .containsEntry("profile_id", "profile-1");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(60));
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    @DisplayName("body_sha256 is the unpadded base64url SHA-256 of the exact body, of \"\" when there is none")
    void bodySha256() throws Exception {
        byte[] body = REQUEST.body();
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        String claim = SignedJWT.parse(signer(KEY_PEM).sign(REQUEST)).getJWTClaimsSet()
                .getStringClaim("body_sha256");
        assertThat(claim).isEqualTo(expected).doesNotContain("=", "+", "/");

        // The well-known SHA-256 of the empty string.
        assertThat(ModuleAssertionSigner.bodySha256(null))
                .isEqualTo("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU")
                .isEqualTo(ModuleAssertionSigner.bodySha256(new byte[0]));
    }

    @Test
    @DisplayName("no act claim without an actor, and a fresh jti per request")
    void noActorAndUniqueJti() throws Exception {
        ModuleAssertionSigner signer = signer(KEY_PEM);
        var request = new ModuleAssertionSigner.Assertion("acme-billing", "install-1", "tenant-1", "acme",
                null, "GET", "https://billing.example.test/kelta/v1/health", null);

        var first = SignedJWT.parse(signer.sign(request)).getJWTClaimsSet();
        var second = SignedJWT.parse(signer.sign(request)).getJWTClaimsSet();

        assertThat(first.getClaim("act")).isNull();
        assertThat(first.getJWTID()).isNotEqualTo(second.getJWTID());
    }

    @Test
    @DisplayName("the first key signs and every key is published, for rotation")
    void rotation() throws Exception {
        KeyPair next = generate();
        ModuleAssertionSigner rotated = signer(pem(next) + KEY_PEM);
        ModuleAssertionSigner old = signer(KEY_PEM);

        Map<String, Object> jwks = rotated.jwks();
        assertThat(JWKSet.parse(jwks).getKeys()).hasSize(2);
        assertThat(JWKSet.parse(jwks).getKeyByKeyId(old.currentKeyId())).isNotNull();

        SignedJWT jwt = SignedJWT.parse(rotated.sign(REQUEST));
        assertThat(jwt.getHeader().getKeyID()).isNotEqualTo(old.currentKeyId());
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) next.getPublic()))).isTrue();
    }

    @Test
    @DisplayName("the derived public key is the key pair's own public key")
    void derivesThePublicKey() throws Exception {
        KeyPair pair = generate();
        ECKey published = (ECKey) JWKSet.parse(signer(pem(pair)).jwks()).getKeys().getFirst();
        assertThat(published.toECPublicKey().getW()).isEqualTo(((ECPublicKey) pair.getPublic()).getW());
    }

    @Test
    @DisplayName("PEM with literal \\n sequences, as env files carry it, is accepted")
    void escapedNewlines() {
        String escaped = KEY_PEM.replace("\n", "\\n");
        assertThat(signer(escaped).currentKeyId()).isEqualTo(signer(KEY_PEM).currentKeyId());
    }

    @Test
    @DisplayName("unconfigured: empty JWKS, and signing refuses")
    void unconfigured() {
        ModuleAssertionSigner signer = signer("");
        assertThat(signer.isConfigured()).isFalse();
        assertThat(signer.jwks()).containsEntry("keys", java.util.List.of());
        assertThatThrownBy(() -> signer.sign(REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KELTA_MODULE_SIGNING_KEY");
        assertThat(new ModuleAssertionSigner(KEY_PEM, "", Clock.systemUTC()).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("refuses SEC1 PEM, a non-P-256 key and text without a key, naming the property")
    void refusesBadKeys() throws Exception {
        assertThatThrownBy(() -> signer("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----"))
                .hasMessageContaining("pkcs8");
        assertThatThrownBy(() -> signer("not a key"))
                .hasMessageContaining(ModuleAssertionSigner.SIGNING_KEY_PROPERTY);

        KeyPairGenerator p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        assertThatThrownBy(() -> signer(pem(p384.generateKeyPair())))
                .hasMessageContaining("P-256");
    }
}
