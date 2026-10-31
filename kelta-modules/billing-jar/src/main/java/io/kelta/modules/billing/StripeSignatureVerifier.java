package io.kelta.modules.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the {@code Stripe-Signature} header on inbound billing webhooks.
 *
 * <p>The header looks like {@code t=1699999999,v1=<hex>,v1=<hex>} — more than one
 * {@code v1} appears while a signing secret is being rotated, so <b>any</b> match
 * is accepted. The signed payload is {@code "<t>.<rawBody>"}, which is why the
 * raw request body must be verified before it is parsed: re-serializing JSON
 * changes the bytes and would invalidate a genuine signature.
 *
 * <p>Three properties this class must not lose:
 * <ul>
 *   <li><b>Constant-time comparison</b> ({@link MessageDigest#isEqual}) — a
 *       byte-by-byte early exit leaks the expected signature to a timing attack.</li>
 *   <li><b>Timestamp tolerance</b> — without it, a captured request stays
 *       replayable forever.</li>
 *   <li><b>Fail closed</b> — every malformed, missing, or unparseable input
 *       returns false rather than throwing or defaulting to accept.</li>
 * </ul>
 *
 * <p>This is the only trust anchor for a gateway-unauthenticated endpoint, so
 * failures log at debug here and the caller decides what to surface.
 */
public class StripeSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(StripeSignatureVerifier.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Replay window. Matches Stripe's own recommended default. */
    static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    /**
     * Verifies a signature header against the raw body and signing secret.
     *
     * @param signatureHeader the raw {@code Stripe-Signature} header value
     * @param rawBody         the request body exactly as received
     * @param signingSecret   the endpoint signing secret ({@code whsec_…})
     * @return true only when a {@code v1} signature matches and the timestamp is
     *         inside the tolerance window
     */
    public boolean verify(String signatureHeader, String rawBody, String signingSecret) {
        return verify(signatureHeader, rawBody, signingSecret, DEFAULT_TOLERANCE, Instant.now());
    }

    /** Verification with an explicit tolerance and clock, for tests. */
    boolean verify(String signatureHeader, String rawBody, String signingSecret,
                   Duration tolerance, Instant now) {
        if (signatureHeader == null || signatureHeader.isBlank()
                || rawBody == null
                || signingSecret == null || signingSecret.isBlank()) {
            return false;
        }

        ParsedSignature parsed = parse(signatureHeader);
        if (parsed == null || parsed.signatures().isEmpty()) {
            log.debug("Stripe signature header missing t= or v1= components");
            return false;
        }

        Instant timestamp = Instant.ofEpochSecond(parsed.timestamp());
        Duration drift = Duration.between(timestamp, now).abs();
        if (drift.compareTo(tolerance) > 0) {
            // Reject future-dated headers too: a clock-skewed sender is a
            // misconfiguration, and accepting them widens the replay window.
            log.debug("Stripe signature timestamp outside tolerance ({}s drift)", drift.toSeconds());
            return false;
        }

        byte[] expected = hmac(parsed.timestamp() + "." + rawBody, signingSecret);
        if (expected == null) {
            return false;
        }
        for (String candidate : parsed.signatures()) {
            byte[] provided = decodeHex(candidate);
            if (provided != null && MessageDigest.isEqual(expected, provided)) {
                return true;
            }
        }
        log.debug("No Stripe v1 signature matched the computed HMAC");
        return false;
    }

    /**
     * Parses {@code t=<seconds>,v1=<hex>[,v1=<hex>…]}. Unknown schemes (a future
     * {@code v2}) are ignored rather than treated as failures.
     */
    static ParsedSignature parse(String header) {
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if ("v1".equals(key) && !value.isEmpty()) {
                signatures.add(value);
            }
        }
        if (timestamp == null) {
            return null;
        }
        return new ParsedSignature(timestamp, List.copyOf(signatures));
    }

    private static byte[] hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Failed to compute Stripe HMAC: {}", e.getMessage());
            return null;
        }
    }

    /** Decodes lowercase/uppercase hex; null when the input is not valid hex. */
    static byte[] decodeHex(String hex) {
        int len = hex.length();
        if (len == 0 || len % 2 != 0) {
            return null;
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    /** Timestamp (epoch seconds) plus every {@code v1} signature offered. */
    record ParsedSignature(long timestamp, List<String> signatures) {
    }
}
