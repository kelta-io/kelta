package io.kelta.worker.module;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs every request the platform sends to an external module
 * ({@code specs/module-platform/9-external-modules.md} §3.2): an ES256 JWS carried as
 * {@code Authorization: Bearer <assertion>}, which the module verifies against the public JWKS
 * served at {@code GET /api/modules/.well-known/jwks.json} ({@link #jwks()}).
 *
 * <p>Claims: {@code iss} ({@value #ISSUER_PROPERTY}), {@code aud} (the module id), {@code sub} (the
 * install id), {@code tenant_id}, {@code tenant_slug}, {@code act} (the acting user, RFC 8693, when
 * there is one), {@code htm}/{@code htu} (method and the exact URL sent, as in DPoP),
 * {@code body_sha256} (base64url, unpadded, of the exact body bytes — of the empty string when
 * there is none), {@code iat}, {@code exp = iat + 60 s} and a random {@code jti}. The header names
 * the key in {@code kid}, its RFC 7638 thumbprint.
 *
 * <p><b>Keys.</b> {@value #SIGNING_KEY_PROPERTY} (env {@code KELTA_MODULE_SIGNING_KEY}) holds one or
 * more PKCS#8 PEM P-256 private keys ({@code openssl genpkey -algorithm EC -pkeyopt
 * ec_paramgen_curve:P-256}). The <em>first</em> signs; every key is published. Rotation is: put
 * the new key first and keep the old one second, wait out the 60 s lifetime plus the modules' JWKS
 * cache, then drop the old one. The key is platform-wide, never per tenant: the module learns the
 * tenant from the signed claims, and a public key needs no per-install secret delivered to it.
 *
 * <p>Unset, the signer is unconfigured: the JWKS is empty and {@link #sign} refuses, so an install
 * fails with a clear error rather than sending a module requests it cannot authenticate. There is
 * deliberately no generated fallback key — each pod would generate its own and modules would
 * reject every other pod's assertions.
 */
@Component
public class ModuleAssertionSigner {

    private static final Logger log = LoggerFactory.getLogger(ModuleAssertionSigner.class);

    public static final String SIGNING_KEY_PROPERTY = "kelta.modules.platform-signing-key";
    public static final String ISSUER_PROPERTY = "kelta.modules.issuer";

    /** How long an assertion is valid. Short: every request gets a fresh one. */
    static final Duration LIFETIME = Duration.ofSeconds(60);

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----");

    /** The acting user ({@code act} claim), when a person's action caused the call. */
    public record Actor(String userId, String userType, String profileId) {}

    /**
     * One request to sign.
     *
     * @param moduleId   the manifest id ({@code aud})
     * @param installId  the {@code tenant_module} row id ({@code sub})
     * @param actor      the acting user, or {@code null} (webhooks, schedules, events, lifecycle)
     * @param method     the HTTP method sent
     * @param url        the exact URL sent
     * @param body       the exact body bytes sent, or {@code null} for none
     */
    public record Assertion(String moduleId, String installId, String tenantId, String tenantSlug,
                            Actor actor, String method, String url, byte[] body) {}

    private record SigningKey(String kid, ECPrivateKey privateKey, ECKey publicJwk) {}

    private final List<SigningKey> keys;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public ModuleAssertionSigner(@Value("${" + SIGNING_KEY_PROPERTY + ":}") String signingKeyPem,
                                 @Value("${" + ISSUER_PROPERTY + ":}") String issuer) {
        this(signingKeyPem, issuer, Clock.systemUTC());
    }

    ModuleAssertionSigner(String signingKeyPem, String issuer, Clock clock) {
        this.keys = parseKeys(signingKeyPem);
        this.issuer = issuer == null ? "" : issuer.strip();
        this.clock = Objects.requireNonNull(clock);
        if (keys.isEmpty()) {
            log.warn("{} is not set: external modules cannot be installed or called on this pod "
                    + "(set KELTA_MODULE_SIGNING_KEY)", SIGNING_KEY_PROPERTY);
        } else {
            log.info("External-module request signing ready: {} key(s), signing with kid {}, issuer {}",
                    keys.size(), keys.getFirst().kid(), this.issuer);
        }
    }

    /** True when a signing key is configured. */
    public boolean isConfigured() {
        return !keys.isEmpty() && !issuer.isEmpty();
    }

    /** The {@code iss} every assertion carries; modules fetch {@code {issuer}/.well-known/jwks.json}. */
    public String issuer() {
        return issuer;
    }

    /** The {@code kid} new assertions are signed with, or {@code null} when unconfigured. */
    public String currentKeyId() {
        return keys.isEmpty() ? null : keys.getFirst().kid();
    }

    /** The public JWKS document: every configured key, public parts only. */
    public Map<String, Object> jwks() {
        List<JWK> publicKeys = new ArrayList<>();
        for (SigningKey key : keys) {
            publicKeys.add(key.publicJwk());
        }
        return new JWKSet(publicKeys).toJSONObject(true);
    }

    /**
     * Signs one request.
     *
     * @throws IllegalStateException when no signing key or issuer is configured
     */
    public String sign(Assertion request) {
        if (!isConfigured()) {
            throw new IllegalStateException("External-module request signing is not configured: set "
                    + "KELTA_MODULE_SIGNING_KEY (and " + ISSUER_PROPERTY + ")");
        }
        Objects.requireNonNull(request.moduleId(), "moduleId");
        Objects.requireNonNull(request.method(), "method");
        Objects.requireNonNull(request.url(), "url");
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(request.moduleId())
                .subject(request.installId())
                .claim("tenant_id", request.tenantId())
                .claim("tenant_slug", request.tenantSlug())
                .claim("htm", request.method().toUpperCase(java.util.Locale.ROOT))
                .claim("htu", withoutFragment(request.url()))
                .claim("body_sha256", bodySha256(request.body()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .jwtID(UUID.randomUUID().toString());
        if (request.actor() != null && request.actor().userId() != null) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("sub", request.actor().userId());
            act.put("user_type", request.actor().userType());
            if (request.actor().profileId() != null) {
                act.put("profile_id", request.actor().profileId());
            }
            claims.claim("act", act);
        }
        SigningKey key = keys.getFirst();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(key.kid())
                .type(JOSEObjectType.JWT)
                .build(), claims.build());
        try {
            jwt.sign(new ECDSASigner(key.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a module request: " + e.getMessage(), e);
        }
        return jwt.serialize();
    }

    /** Base64url (unpadded) SHA-256 of {@code body}; of the empty string when {@code null}. */
    public static String bodySha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    // ── key material ──────────────────────────────────────────────────────────────────────

    private static List<SigningKey> parseKeys(String pem) {
        if (pem == null || pem.isBlank()) {
            return List.of();
        }
        // Env files and secret mounts often carry the PEM with literal "\n" sequences.
        String text = pem.replace("\\n", "\n");
        List<SigningKey> parsed = new ArrayList<>();
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            String type = m.group(1);
            if ("EC PRIVATE KEY".equals(type)) {
                throw new IllegalArgumentException(SIGNING_KEY_PROPERTY + ": SEC1 'EC PRIVATE KEY' PEM is not "
                        + "supported; convert it with 'openssl pkcs8 -topk8 -nocrypt'");
            }
            if (!"PRIVATE KEY".equals(type)) {
                continue;
            }
            parsed.add(signingKey(Base64.getMimeDecoder().decode(m.group(2).strip())));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(SIGNING_KEY_PROPERTY
                    + " is set but holds no PKCS#8 'PRIVATE KEY' PEM block");
        }
        return List.copyOf(parsed);
    }

    private static SigningKey signingKey(byte[] pkcs8) {
        try {
            ECPrivateKey privateKey = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            ECParameterSpec params = privateKey.getParams();
            if (Curve.forECParameterSpec(params) != Curve.P_256) {
                throw new IllegalArgumentException(SIGNING_KEY_PROPERTY + ": the key is not a P-256 key");
            }
            ECPoint q = multiply(params.getGenerator(), privateKey.getS(), params);
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(q, params));
            ECKey jwk = new ECKey.Builder(Curve.P_256, publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .keyIDFromThumbprint()
                    .build();
            return new SigningKey(jwk.getKeyID(), privateKey, jwk);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (ClassCastException | java.security.GeneralSecurityException | JOSEException e) {
            // Never echo key material.
            throw new IllegalArgumentException(SIGNING_KEY_PROPERTY + ": not a usable EC private key ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * {@code k·P} on the key's curve, by double-and-add in affine coordinates. Used once per key at
     * startup to derive the public key a PKCS#8 private key does not expose through the JDK API,
     * so the operator configures a single secret. Not constant-time; it never runs per request.
     */
    static ECPoint multiply(ECPoint point, BigInteger k, ECParameterSpec params) {
        BigInteger p = ((ECFieldFp) params.getCurve().getField()).getP();
        BigInteger a = params.getCurve().getA();
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = point;
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) {
                result = add(result, addend, a, p);
            }
            addend = add(addend, addend, a, p);
        }
        return result;
    }

    private static ECPoint add(ECPoint s, ECPoint t, BigInteger a, BigInteger p) {
        if (s.equals(ECPoint.POINT_INFINITY)) {
            return t;
        }
        if (t.equals(ECPoint.POINT_INFINITY)) {
            return s;
        }
        BigInteger lambda;
        if (s.getAffineX().equals(t.getAffineX())) {
            if (s.getAffineY().add(t.getAffineY()).mod(p).signum() == 0) {
                return ECPoint.POINT_INFINITY;
            }
            BigInteger x = s.getAffineX();
            lambda = x.pow(2).multiply(BigInteger.valueOf(3)).add(a)
                    .multiply(s.getAffineY().shiftLeft(1).modInverse(p)).mod(p);
        } else {
            lambda = t.getAffineY().subtract(s.getAffineY())
                    .multiply(t.getAffineX().subtract(s.getAffineX()).modInverse(p)).mod(p);
        }
        BigInteger x3 = lambda.pow(2).subtract(s.getAffineX()).subtract(t.getAffineX()).mod(p);
        BigInteger y3 = lambda.multiply(s.getAffineX().subtract(x3)).subtract(s.getAffineY()).mod(p);
        return new ECPoint(x3, y3);
    }
}
