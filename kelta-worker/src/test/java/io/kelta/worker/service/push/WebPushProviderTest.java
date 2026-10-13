package io.kelta.worker.service.push;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("WebPushProvider Tests")
class WebPushProviderTest {

    private static final String VAPID_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String VAPID_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";

    private WebPushProvider provider(String privateKey) {
        return new WebPushProvider(new ObjectMapper(), new OutboundUrlGuard(""), VAPID_PUBLIC, privateKey,
                "mailto:ops@example.com");
    }

    private static PushMessage message(String subscription) {
        return new PushMessage("token", "web", "Now available", "A slot opened.",
                Map.of("watchId", "w1"), subscription);
    }

    @Nested
    @DisplayName("Platform routing")
    class PlatformRouting {

        @Test
        @DisplayName("claims only the web platform, so it runs beside the mobile provider")
        void supportsOnlyWeb() {
            WebPushProvider p = provider(VAPID_PRIVATE);

            assertThat(p.supports("web")).isTrue();
            assertThat(p.supports("WEB")).isTrue();
            assertThat(p.supports("ios")).isFalse();
            assertThat(p.supports("android")).isFalse();
        }

        @Test
        @DisplayName("exposes the public key a frontend needs for pushManager.subscribe")
        void exposesPublicKey() {
            assertThat(provider(VAPID_PRIVATE).publicKey()).isEqualTo(VAPID_PUBLIC);
        }
    }

    @Nested
    @DisplayName("Subscription validation")
    class SubscriptionValidation {

        @Test
        @DisplayName("a device with no stored subscription is pruned, not retried")
        void missingSubscriptionPrunes() {
            // The token is only a hash of the endpoint, so there is nothing to
            // send to — retrying forever would never succeed.
            PushDeliveryException e = catchThrowableOfType(
                    () -> provider(VAPID_PRIVATE).send(message(null), null),
                    PushDeliveryException.class);

            assertThat(e).isNotNull();
            assertThat(e.isInvalidToken()).isTrue();
        }

        @Test
        @DisplayName("an unreadable or incomplete subscription is pruned")
        void malformedSubscriptionPrunes() {
            for (String bad : new String[]{
                    "{not json",
                    "{\"endpoint\":\"https://push.test/x\"}",                        // no keys
                    "{\"keys\":{\"p256dh\":\"a\",\"auth\":\"b\"}}",                  // no endpoint
                    "{\"endpoint\":\"https://push.test/x\",\"keys\":{\"auth\":\"b\"}}"}) {
                PushDeliveryException e = catchThrowableOfType(
                        () -> provider(VAPID_PRIVATE).send(message(bad), null),
                        PushDeliveryException.class);

                assertThat(e).as("subscription: %s", bad).isNotNull();
                assertThat(e.isInvalidToken()).as("subscription: %s", bad).isTrue();
            }
        }

        @Test
        @DisplayName("a missing VAPID private key fails WITHOUT pruning the device")
        void missingPrivateKeyDoesNotPrune() {
            // An operator configuration gap must not silently delete every
            // browser subscription in the tenant.
            PushDeliveryException e = catchThrowableOfType(
                    () -> provider(null).send(message("{}"), null),
                    PushDeliveryException.class);

            assertThat(e).isNotNull();
            assertThat(e.isInvalidToken()).isFalse();
        }

        @Test
        @DisplayName("an unparseable VAPID key disables sending rather than crashing startup")
        void badPrivateKeyDisablesSending() {
            WebPushProvider p = provider("!!!not-base64!!!");

            PushDeliveryException e = catchThrowableOfType(
                    () -> p.send(message("{}"), null), PushDeliveryException.class);
            assertThat(e).isNotNull();
            assertThat(e.isInvalidToken()).isFalse();
        }
    }

    @Nested
    @DisplayName("Endpoint check")
    class EndpointCheck {

        private String subscription(String endpoint) {
            var keys = generatedPublicPoint();
            return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\""
                    + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(keys)
                    + "\",\"auth\":\"BTBZMqHH6r4Tts7J_aSIgg\"}}";
        }

        private byte[] generatedPublicPoint() {
            try {
                return WebPushCrypto.generateEphemeralKeys().publicPoint();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        @Test
        @DisplayName("an endpoint that is not https is pruned without sending")
        void nonHttpsPrunes() {
            PushDeliveryException e = catchThrowableOfType(
                    () -> provider(VAPID_PRIVATE).send(message(subscription("http://push.example.com/x")), null),
                    PushDeliveryException.class);

            assertThat(e).isNotNull();
            assertThat(e.isInvalidToken()).isTrue();
            assertThat(e.getMessage()).contains("https");
        }

        @Test
        @DisplayName("an endpoint on a non-public address is pruned without sending")
        void nonPublicPrunes() {
            for (String endpoint : new String[]{"https://127.0.0.1:9/x", "https://10.1.2.3/x",
                    "https://169.254.169.254/x", "https://[::1]/x"}) {
                PushDeliveryException e = catchThrowableOfType(
                        () -> provider(VAPID_PRIVATE).send(message(subscription(endpoint)), null),
                        PushDeliveryException.class);

                assertThat(e).as(endpoint).isNotNull();
                assertThat(e.isInvalidToken()).as(endpoint).isTrue();
                assertThat(e.getMessage()).as(endpoint).contains("non-public");
            }
        }

        @Test
        @DisplayName("an allow-listed address is attempted, and a failed connection keeps the device")
        void allowListedAttempted() {
            WebPushProvider allowed = new WebPushProvider(new ObjectMapper(),
                    new OutboundUrlGuard("127.0.0.0/8"), VAPID_PUBLIC, VAPID_PRIVATE, "mailto:ops@example.com");

            // Nothing listens on the discard port, so the attempt fails as a transport error.
            PushDeliveryException e = catchThrowableOfType(
                    () -> allowed.send(message(subscription("https://127.0.0.1:9/x")), null),
                    PushDeliveryException.class);

            assertThat(e).isNotNull();
            assertThat(e.isInvalidToken()).isFalse();
            assertThat(e.getMessage()).contains("delivery failed");
        }

        @Test
        @DisplayName("a host that does not resolve keeps the device")
        void unresolvableKeepsDevice() {
            PushDeliveryException e = catchThrowableOfType(
                    () -> provider(VAPID_PRIVATE).send(message(subscription("https://push.invalid/x")), null),
                    PushDeliveryException.class);

            assertThat(e).isNotNull();
            assertThat(e.isInvalidToken()).isFalse();
        }
    }

    @Nested
    @DisplayName("VAPID token (RFC 8292)")
    class VapidToken {

        /**
         * A bad VAPID token is rejected by the push service with a 401 that looks
         * exactly like any other transient failure, so verifying the signature
         * against the advertised public key is the only cheap way to know the
         * configured key pair actually matches.
         */
        @Test
        @DisplayName("signs ES256 verifiably with the advertised public key")
        void signatureVerifies() throws Exception {
            String token = provider(VAPID_PRIVATE).vapidToken("https://fcm.googleapis.com/fcm/send/abc");

            SignedJWT jwt = SignedJWT.parse(token);
            var publicKey = (java.security.interfaces.ECPublicKey)
                    WebPushCrypto.decodePoint(WebPushProvider.decode(VAPID_PUBLIC));

            assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
            assertThat(jwt.verify(new ECDSAVerifier(publicKey))).isTrue();
        }

        @Test
        @DisplayName("audience is the push service ORIGIN, not the endpoint")
        void audienceIsOrigin() throws Exception {
            // The endpoint path IS the subscription; putting it in the token would
            // hand it to anyone who sees the Authorization header.
            String token = provider(VAPID_PRIVATE)
                    .vapidToken("https://fcm.googleapis.com/fcm/send/secret-subscription-id");

            var claims = SignedJWT.parse(token).getJWTClaimsSet();
            assertThat(claims.getAudience()).containsExactly("https://fcm.googleapis.com");
            assertThat(token).doesNotContain("secret-subscription-id");
            assertThat(claims.getSubject()).isEqualTo("mailto:ops@example.com");
            assertThat(claims.getExpirationTime()).isAfter(new java.util.Date());
        }

        @Test
        @DisplayName("keeps a non-default port in the audience")
        void keepsPort() throws Exception {
            String token = provider(VAPID_PRIVATE).vapidToken("https://push.test:8443/x");

            assertThat(SignedJWT.parse(token).getJWTClaimsSet().getAudience())
                    .containsExactly("https://push.test:8443");
        }

        @Test
        @DisplayName("accepts a freshly generated P-256 pair in the documented encoding")
        void acceptsGeneratedPair() throws Exception {
            // Guards the `make gen-vapid` recipe: raw base64url scalar for the
            // private key, uncompressed point for the public key. A mismatch here
            // means every operator who follows the README ships a 401.
            var keys = WebPushCrypto.generateEphemeralKeys();
            var ecPrivate = (java.security.interfaces.ECPrivateKey) keys.keyPair().getPrivate();
            byte[] scalar = new byte[32];
            byte[] raw = ecPrivate.getS().toByteArray();
            int len = Math.min(raw.length, 32);
            System.arraycopy(raw, raw.length - len, scalar, 32 - len, len);

            var p = new WebPushProvider(new ObjectMapper(), new OutboundUrlGuard(""),
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(keys.publicPoint()),
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(scalar),
                    "mailto:ops@example.com");

            SignedJWT jwt = SignedJWT.parse(p.vapidToken("https://push.test/x"));
            assertThat(jwt.verify(new ECDSAVerifier(
                    (java.security.interfaces.ECPublicKey) keys.keyPair().getPublic()))).isTrue();
        }

        @Test
        @DisplayName("falls back to a valid mailto subject when none is configured")
        void defaultsSubject() throws Exception {
            // Safari rejects a token whose sub is absent or not a mailto:/https: URI.
            var p = new WebPushProvider(new ObjectMapper(), new OutboundUrlGuard(""),
                    VAPID_PUBLIC, VAPID_PRIVATE, "  ");

            assertThat(SignedJWT.parse(p.vapidToken("https://push.test/x"))
                    .getJWTClaimsSet().getSubject()).startsWith("mailto:");
        }
    }

    @Nested
    @DisplayName("Base64url handling")
    class Base64Handling {

        @Test
        @DisplayName("accepts both padded and unpadded base64url from clients")
        void acceptsPaddedAndUnpadded() {
            // Browsers differ on whether they pad; rejecting one half would drop
            // those subscribers with an opaque decode error.
            assertThat(WebPushProvider.decode("BTBZMqHH6r4Tts7J_aSIgg")).hasSize(16);
            assertThat(WebPushProvider.decode("BTBZMqHH6r4Tts7J_aSIgg==")).hasSize(16);
        }
    }

    @Nested
    @DisplayName("Device token derivation")
    class DeviceTokenDerivation {

        @Test
        @DisplayName("hashes the endpoint so it fits the column and unique constraint")
        void hashesEndpoint() {
            String longEndpoint = "https://fcm.googleapis.com/fcm/send/" + "x".repeat(600);
            String token = DefaultPushService.webDeviceToken(
                    "{\"endpoint\":\"" + longEndpoint + "\"}");

            // 64 hex chars — well inside the 500-char column the raw endpoint
            // would have overflowed.
            assertThat(token).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("is stable for one subscription and distinct across subscriptions")
        void stableAndDistinct() {
            String a = DefaultPushService.webDeviceToken("{\"endpoint\":\"https://push.test/a\"}");
            String again = DefaultPushService.webDeviceToken("{\"endpoint\":\"https://push.test/a\"}");
            String b = DefaultPushService.webDeviceToken("{\"endpoint\":\"https://push.test/b\"}");

            assertThat(a).isEqualTo(again);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("rejects a subscription with no endpoint")
        void rejectsMissingEndpoint() {
            assertThatThrownBy(() -> DefaultPushService.webDeviceToken("{}"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DefaultPushService.webDeviceToken("{not json"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
