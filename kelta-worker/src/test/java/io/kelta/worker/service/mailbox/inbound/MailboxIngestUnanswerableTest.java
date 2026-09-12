package io.kelta.worker.service.mailbox.inbound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which inbound mail gets an SLA clock.
 *
 * <p>The rule mirrors {@code MailboxReplyService}'s refusals exactly, and must keep doing so: a
 * clock on a thread the reply service will not answer breaches on schedule and pages someone
 * about mail that needed no reply. Ten days of DMARC reports produced twenty escalations that way.
 */
@DisplayName("MailboxIngestService.unanswerable")
class MailboxIngestUnanswerableTest {

    private static NormalizedInboundMail mail(String from, String autoSubmitted,
                                              boolean bulk, boolean bounce) {
        return new NormalizedInboundMail(
                "<id@example.com>", null, null, from, null, "support@spotopened.com", null, null,
                "Subject", "body", null, Map.of(),
                new NormalizedInboundMail.Verdicts("PASS", "PASS", "PASS", null, "PASS", "PASS"),
                autoSubmitted, null, bulk, bounce, List.of(), Instant.now(), 100L);
    }

    @Test
    @DisplayName("A customer writing in gets a clock")
    void ordinaryMailIsAnswerable() {
        assertThat(MailboxIngestService.unanswerable(
                mail("alex@example.com", null, false, false))).isFalse();
        // Auto-Submitted: no is the explicit "a human sent this".
        assertThat(MailboxIngestService.unanswerable(
                mail("alex@example.com", "no", false, false))).isFalse();
    }

    @Test
    @DisplayName("Google's DMARC reporter does not")
    void dmarcReportsAreUnanswerable() {
        // The address that opened ten breaching threads in production.
        assertThat(MailboxIngestService.unanswerable(
                mail("noreply-dmarc-support@google.com", null, false, false))).isTrue();
    }

    @Test
    @DisplayName("Auto-submitted, bounce and bulk mail do not")
    void automatedMailIsUnanswerable() {
        assertThat(MailboxIngestService.unanswerable(
                mail("alex@example.com", "auto-replied", false, false))).isTrue();
        assertThat(MailboxIngestService.unanswerable(
                mail("alex@example.com", "auto-generated", false, false))).isTrue();
        assertThat(MailboxIngestService.unanswerable(
                mail("alex@example.com", null, false, true))).isTrue();
        assertThat(MailboxIngestService.unanswerable(
                mail("alex@example.com", null, true, false))).isTrue();
    }
}
