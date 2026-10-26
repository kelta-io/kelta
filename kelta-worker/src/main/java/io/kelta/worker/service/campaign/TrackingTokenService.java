package io.kelta.worker.service.campaign;

import io.kelta.worker.config.CampaignProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Signs and verifies opaque tracking tokens used in campaign open-pixel, click-redirect,
 * and unsubscribe links.
 *
 * <p>These links are public (unauthenticated) endpoints, so the token is the only thing
 * standing between an attacker and forged unsubscribes / poisoned open-and-click stats.
 * Each token is {@code base64url(recipientId) + "." + base64url(HMAC-SHA256(secret, recipientId))}.
 * Verification recomputes the MAC and compares in constant time. The recipient id alone then
 * resolves the tenant, campaign, and email from {@code email_campaign_recipient} — nothing
 * sensitive is encoded in the URL.
 *
 * <p><b>Address tokens</b> ({@link #signAddress}) authenticate the flow-mail unsubscribe link,
 * where there is no recipient row to look up: the tenant id and the lower-cased address travel
 * in the payload, and the MAC covers them behind the {@value #ADDRESS_DOMAIN} prefix. Because
 * campaign tokens MAC the bare payload, neither kind verifies as the other.
 */
@Service
public class TrackingTokenService {

    private static final Logger log = LoggerFactory.getLogger(TrackingTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    static final String ADDRESS_DOMAIN = "email-unsub:v1:";
    private static final char ADDRESS_SEPARATOR = '\n';

    private final byte[] secret;

    public TrackingTokenService(CampaignProperties properties) {
        this.secret = properties.trackingSecret().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Signs a recipient id into a self-verifying tracking token.
     *
     * @param recipientId the {@code email_campaign_recipient} row id
     * @return an opaque URL-safe token
     */
    public String sign(String recipientId) {
        String payload = B64.encodeToString(recipientId.getBytes(StandardCharsets.UTF_8));
        String mac = B64.encodeToString(hmac(recipientId));
        return payload + "." + mac;
    }

    /**
     * Verifies a token and extracts the recipient id.
     *
     * @param token the token from a tracking link
     * @return the recipient id if the signature is valid, else empty
     */
    public Optional<String> verify(String token) {
        return verifyPayload(token, "");
    }

    /**
     * Signs a tenant-scoped email address into an unsubscribe token for flow mail.
     *
     * @param tenantId the tenant whose suppression list the token writes to
     * @param email    the recipient; lower-cased before signing
     * @return an opaque URL-safe token
     */
    public String signAddress(String tenantId, String email) {
        if (tenantId == null || tenantId.isBlank() || email == null || email.isBlank()) {
            throw new IllegalArgumentException("tenantId and email are required");
        }
        if (tenantId.indexOf(ADDRESS_SEPARATOR) >= 0 || email.indexOf(ADDRESS_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("tenantId and email must not contain a line break");
        }
        String payload = tenantId + ADDRESS_SEPARATOR + email.strip().toLowerCase(Locale.ROOT);
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + B64.encodeToString(hmac(ADDRESS_DOMAIN + payload));
    }

    /**
     * Verifies an address token from {@link #signAddress}.
     *
     * @return the tenant and address if the signature is valid, else empty
     */
    public Optional<SignedAddress> verifyAddress(String token) {
        return verifyPayload(token, ADDRESS_DOMAIN).flatMap(payload -> {
            int sep = payload.indexOf(ADDRESS_SEPARATOR);
            if (sep <= 0 || sep == payload.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(new SignedAddress(payload.substring(0, sep), payload.substring(sep + 1)));
        });
    }

    /** A tenant id and lower-cased email address recovered from a verified address token. */
    public record SignedAddress(String tenantId, String email) {}

    private Optional<String> verifyPayload(String token, String domain) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        try {
            String payload = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] presented = B64D.decode(token.substring(dot + 1));
            byte[] expected = hmac(domain + payload);
            if (!MessageDigest.isEqual(expected, presented)) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (IllegalArgumentException e) {
            // Malformed base64 — treat as an invalid token, not a server error.
            log.debug("Rejected malformed tracking token");
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute tracking HMAC", e);
        }
    }
}
