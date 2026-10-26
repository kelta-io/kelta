package io.kelta.worker.service.campaign;

import io.kelta.worker.config.CampaignProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrackingTokenService")
class TrackingTokenServiceTest {

    private final TrackingTokenService service = new TrackingTokenService(
            new CampaignProperties(true, 15000, 5, 20, 50000, "http://localhost:8080", "unit-test-secret"));

    private final TrackingTokenService otherSecret = new TrackingTokenService(
            new CampaignProperties(true, 15000, 5, 20, 50000, "http://localhost:8080", "different-secret"));

    @Test
    @DisplayName("round-trips a recipient id through sign/verify")
    void roundTrips() {
        String token = service.sign("recipient-123");
        assertThat(service.verify(token)).contains("recipient-123");
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForeignSecret() {
        String token = otherSecret.sign("recipient-123");
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("rejects a tampered payload")
    void rejectsTamperedPayload() {
        String token = service.sign("recipient-123");
        String tampered = "X" + token.substring(1);
        assertThat(service.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("rejects tampered signature")
    void rejectsTamperedSignature() {
        String token = service.sign("recipient-123");
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThat(service.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("rejects malformed / empty tokens")
    void rejectsMalformed() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("no-dot")).isEmpty();
        assertThat(service.verify(".sig")).isEmpty();
        assertThat(service.verify("payload.")).isEmpty();
        assertThat(service.verify("!!!.!!!")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("round-trips a tenant and lower-cased address through signAddress/verifyAddress")
    void addressRoundTrips() {
        String token = service.signAddress("tenant-a", " Member@Example.COM ");
        assertThat(service.verifyAddress(token))
                .contains(new TrackingTokenService.SignedAddress("tenant-a", "member@example.com"));
    }

    @Test
    @DisplayName("an address token and a campaign recipient token cannot be swapped")
    void addressAndRecipientTokensAreNotInterchangeable() {
        String addressToken = service.signAddress("tenant-a", "member@example.com");
        String recipientToken = service.sign("recipient-123");

        assertThat(service.verify(addressToken)).isEmpty();
        assertThat(service.verifyAddress(recipientToken)).isEmpty();

        // Even a recipient id shaped like an address payload does not verify as an address.
        String shaped = service.sign("tenant-a\nmember@example.com");
        assertThat(service.verifyAddress(shaped)).isEmpty();
    }

    @Test
    @DisplayName("an address token whose email or tenant is altered fails")
    void alteredAddressTokenFails() {
        String token = service.signAddress("tenant-a", "member@example.com");
        String mac = token.substring(token.indexOf('.'));
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();

        String otherEmail = b64.encodeToString("tenant-a\nvictim@example.com".getBytes(StandardCharsets.UTF_8)) + mac;
        String otherTenant = b64.encodeToString("tenant-b\nmember@example.com".getBytes(StandardCharsets.UTF_8)) + mac;

        assertThat(service.verifyAddress(otherEmail)).isEmpty();
        assertThat(service.verifyAddress(otherTenant)).isEmpty();
        assertThat(otherSecret.verifyAddress(token)).isEmpty();
        assertThat(service.verifyAddress(token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A"))).isEmpty();
    }

    @Test
    @DisplayName("signAddress refuses a line break that would forge the tenant/email split")
    void signAddressRejectsSeparator() {
        assertThatThrownBy(() -> service.signAddress("tenant-a", "a@example.com\ntenant-b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.signAddress(null, "a@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
