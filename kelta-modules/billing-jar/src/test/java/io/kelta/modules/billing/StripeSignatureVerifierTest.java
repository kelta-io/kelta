package io.kelta.modules.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature check is the module's entire trust anchor: the platform route it sits behind is
 * unauthenticated and verifies nothing. Every case here is a way that anchor could silently stop
 * holding.
 */
@DisplayName("StripeSignatureVerifier")
class StripeSignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\"}";

    private final StripeSignatureVerifier verifier = new StripeSignatureVerifier();

    @Test
    @DisplayName("Accepts a correctly signed payload")
    void acceptsValidSignature() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(verifier.verify(header(now, BODY, SECRET), BODY, SECRET,
                Duration.ofMinutes(5), now)).isTrue();
    }

    @Test
    @DisplayName("Accepts when any one of several v1 signatures matches")
    void acceptsDuringSecretRotation() {
        // Two v1 values appear while a signing secret is being rotated; rejecting unless the
        // first matches would drop real events mid-rotation.
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        long ts = now.getEpochSecond();
        String header = "t=" + ts + ",v1=" + hmac(ts + "." + BODY, "whsec_old")
                + ",v1=" + hmac(ts + "." + BODY, SECRET);

        assertThat(verifier.verify(header, BODY, SECRET, Duration.ofMinutes(5), now)).isTrue();
    }

    @Test
    @DisplayName("Rejects a body that changed after signing")
    void rejectsTamperedBody() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String header = header(now, BODY, SECRET);

        assertThat(verifier.verify(header, BODY + " ", SECRET, Duration.ofMinutes(5), now))
                .isFalse();
    }

    @Test
    @DisplayName("Rejects a signature made with a different secret")
    void rejectsWrongSecret() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(verifier.verify(header(now, BODY, "whsec_other"), BODY, SECRET,
                Duration.ofMinutes(5), now)).isFalse();
    }

    @Test
    @DisplayName("Rejects a replay outside the tolerance window")
    void rejectsStaleTimestamp() {
        // Without this, a captured request stays replayable forever.
        Instant signedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = signedAt.plus(Duration.ofMinutes(10));

        assertThat(verifier.verify(header(signedAt, BODY, SECRET), BODY, SECRET,
                Duration.ofMinutes(5), now)).isFalse();
    }

    @Test
    @DisplayName("Rejects a future-dated timestamp too")
    void rejectsFutureTimestamp() {
        // A clock-skewed sender is a misconfiguration; accepting it widens the replay window.
        Instant signedAt = Instant.parse("2026-01-01T00:10:00Z");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(verifier.verify(header(signedAt, BODY, SECRET), BODY, SECRET,
                Duration.ofMinutes(5), now)).isFalse();
    }

    @Test
    @DisplayName("Fails closed on missing, malformed, or unparseable input")
    void failsClosed() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Duration tolerance = Duration.ofMinutes(5);

        assertThat(verifier.verify(null, BODY, SECRET, tolerance, now)).isFalse();
        assertThat(verifier.verify("", BODY, SECRET, tolerance, now)).isFalse();
        assertThat(verifier.verify(header(now, BODY, SECRET), null, SECRET, tolerance, now))
                .isFalse();
        assertThat(verifier.verify(header(now, BODY, SECRET), BODY, null, tolerance, now))
                .isFalse();
        // No t= component
        assertThat(verifier.verify("v1=abcd", BODY, SECRET, tolerance, now)).isFalse();
        // No v1= component
        assertThat(verifier.verify("t=" + now.getEpochSecond(), BODY, SECRET, tolerance, now))
                .isFalse();
        // Non-numeric timestamp
        assertThat(verifier.verify("t=soon,v1=abcd", BODY, SECRET, tolerance, now)).isFalse();
        // Odd-length / non-hex signature
        assertThat(verifier.verify("t=" + now.getEpochSecond() + ",v1=xyz", BODY, SECRET,
                tolerance, now)).isFalse();
    }

    @Test
    @DisplayName("Ignores an unknown signature scheme rather than failing on it")
    void ignoresUnknownScheme() {
        // A future v2 alongside a valid v1 must not break verification.
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        long ts = now.getEpochSecond();
        String header = "t=" + ts + ",v2=whatever,v1=" + hmac(ts + "." + BODY, SECRET);

        assertThat(verifier.verify(header, BODY, SECRET, Duration.ofMinutes(5), now)).isTrue();
    }

    private static String header(Instant at, String body, String secret) {
        long ts = at.getEpochSecond();
        return "t=" + ts + ",v1=" + hmac(ts + "." + body, secret);
    }

    private static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
