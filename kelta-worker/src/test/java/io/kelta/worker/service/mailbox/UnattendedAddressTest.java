package io.kelta.worker.service.mailbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address that broke this was {@code noreply-dmarc-support@google.com}: exact-match on the
 * local part saw {@code noreply-dmarc-support}, compared it to {@code noreply}, and let ten daily
 * DMARC reports open SLA-clocked support threads that each paged a human twice.
 */
@DisplayName("UnattendedAddress")
class UnattendedAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "noreply-dmarc-support@google.com",
            "noreply@spotopened.com",
            "no-reply@example.com",
            "no-reply-notifications@github.com",
            "NoReply@Example.COM",
            "bounces+abc123@mailer.example.com",
            "bounce.12345@lists.example.com",
            "mailer-daemon@googlemail.com",
            "postmaster@example.com",
            "notifications@github.com",
            "donotreply_billing@example.com"})
    @DisplayName("Recognises unattended senders by leading token, not exact local part")
    void recognisesUnattended(String address) {
        assertThat(UnattendedAddress.isUnattended(address)).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "craig@example.com",
            "cklinker@rzware.com",
            // A token followed by a letter is a different word, not a prefix.
            "noreplyjones@example.com",
            "bouncer@example.com",
            "postmasterson@example.com",
            // Token in the domain is irrelevant; only the local part is read.
            "alice@noreply.example.com",
            "support+t123.abcd@spotopened.com"})
    @DisplayName("Leaves ordinary senders alone")
    void leavesOrdinaryAlone(String address) {
        assertThat(UnattendedAddress.isUnattended(address)).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "@example.com", "no-at-sign"})
    @DisplayName("Malformed input is not unattended — it is simply not an address")
    void malformedIsFalse(String address) {
        assertThat(UnattendedAddress.isUnattended(address)).isFalse();
    }
}
