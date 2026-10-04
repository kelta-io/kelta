package io.kelta.worker.service.availability;

import io.kelta.worker.repository.AlertDeliveryRepository.DeliveryLatencyRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

@DisplayName("AlertLatencySummary")
class AlertLatencySummaryTest {

    private static final Instant T0 = Instant.parse("2026-08-01T12:00:00Z");

    private static DeliveryLatencyRow row(String alertId, String channel, String status,
                                          Integer sentAfterSeconds) {
        return new DeliveryLatencyRow(alertId, channel, status, T0,
                sentAfterSeconds == null ? null : T0.plusSeconds(sentAfterSeconds));
    }

    @Test
    @DisplayName("failed push then sent email contributes one sample: the email's latency")
    void failedPushThenSentEmail() {
        AlertLatencySummary summary = AlertLatencySummary.summarize(List.of(
                row("a1", "push", "FAILED", null),
                row("a1", "email", "SENT", 42)), null);

        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.p50Seconds()).isEqualTo(42.0);
        assertThat(summary.p90Seconds()).isEqualTo(42.0);
    }

    @Test
    @DisplayName("without a channel each alert counts once, at its earliest SENT delivery")
    void earliestSentPerAlert() {
        AlertLatencySummary summary = AlertLatencySummary.summarize(List.of(
                row("a1", "email", "SENT", 30),
                row("a1", "push", "SENT", 5),
                row("a1", "sms", "SENT", 60)), null);

        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.p50Seconds()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("with a channel every SENT delivery on that channel is a sample")
    void channelFilter() {
        AlertLatencySummary summary = AlertLatencySummary.summarize(List.of(
                row("a1", "email", "SENT", 30),
                row("a1", "push", "SENT", 5),
                row("a2", "email", "SENT", 10)), "email");

        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.p50Seconds()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("PENDING and FAILED-only alerts contribute nothing")
    void pendingAndFailedContributeNothing() {
        AlertLatencySummary summary = AlertLatencySummary.summarize(List.of(
                row("a1", "push", "PENDING", null),
                row("a2", "push", "FAILED", null),
                row("a2", "email", "FAILED", null),
                row("a3", "push", "SENT", 7)), null);

        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.p50Seconds()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("zero rows is count 0 with null percentiles, not an error")
    void emptyIsZero() {
        AlertLatencySummary summary = AlertLatencySummary.summarize(List.of(), null);

        assertThat(summary.count()).isZero();
        assertThat(summary.p50Seconds()).isNull();
        assertThat(summary.p90Seconds()).isNull();
    }

    @Test
    @DisplayName("percentiles interpolate between closest ranks, like percentile_cont")
    void interpolatedPercentiles() {
        AlertLatencySummary summary = AlertLatencySummary.summarize(List.of(
                row("a1", "push", "SENT", 1),
                row("a2", "push", "SENT", 2),
                row("a3", "push", "SENT", 3),
                row("a4", "push", "SENT", 4),
                row("a5", "push", "SENT", 10)), null);

        assertThat(summary.count()).isEqualTo(5);
        assertThat(summary.p50Seconds()).isEqualTo(3.0);
        // rank 0.9 * 4 = 3.6 -> 4 + 0.6 * (10 - 4)
        assertThat(summary.p90Seconds()).isCloseTo(7.6, offset(1e-9));
    }

    @Test
    @DisplayName("clock skew never yields a negative latency")
    void negativeClampedToZero() {
        assertThat(AlertLatencySummary.latency(T0, T0.minusMillis(5))).isEqualTo(Duration.ZERO);
        assertThat(AlertLatencySummary.latency(T0, T0.plusSeconds(3)))
                .isEqualTo(Duration.ofSeconds(3));
    }
}
