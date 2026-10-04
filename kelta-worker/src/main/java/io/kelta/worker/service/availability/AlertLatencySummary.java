package io.kelta.worker.service.availability;

import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.repository.AlertDeliveryRepository.DeliveryLatencyRow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alert delivery latency: the one definition shared by the
 * {@code kelta_alert_delivery_latency_seconds} timer and {@code GET /api/alerts/latency}.
 *
 * <p><b>Latency is {@code alert_delivery.sent_at - alert.created_at}.</b> {@code alert.created_at}
 * stands in for detection time because {@code AlertRepository.claim} writes it with {@code NOW()}
 * in the same matcher pass that detects the CLOSED → OPEN transition. There is no other durable
 * detection timestamp: {@code availability_state.last_change_at} is overwritten in place on the
 * next transition, and the poller's {@code polledAt} is never persisted. Poller → platform lag is
 * therefore not included.
 *
 * <p>Percentiles are linearly interpolated between closest ranks — the same result as Postgres
 * {@code percentile_cont}.
 *
 * @param count      samples summarised
 * @param p50Seconds median latency, or null when there are no samples
 * @param p90Seconds 90th percentile latency, or null when there are no samples
 */
public record AlertLatencySummary(int count, Double p50Seconds, Double p90Seconds) {

    /**
     * Latency of one delivery. Clamped at zero: {@code sent_at} is stamped by the worker's clock
     * and {@code created_at} by the database's, so a few milliseconds of skew must not produce a
     * negative sample.
     */
    public static Duration latency(Instant alertCreatedAt, Instant sentAt) {
        Duration latency = Duration.between(alertCreatedAt, sentAt);
        return latency.isNegative() ? Duration.ZERO : latency;
    }

    /**
     * Summarises delivery rows. With a {@code channel}, every SENT delivery on that channel is a
     * sample. Without one, each alert is one sample — its earliest SENT delivery — so an alert
     * whose push failed and whose email later went out counts once, at the email's latency.
     * PENDING and FAILED deliveries never contribute.
     */
    public static AlertLatencySummary summarize(List<DeliveryLatencyRow> rows, String channel) {
        List<Duration> samples = new ArrayList<>();
        if (channel != null) {
            for (DeliveryLatencyRow row : rows) {
                if (isSent(row) && channel.equals(row.channel())) {
                    samples.add(latency(row.alertCreatedAt(), row.sentAt()));
                }
            }
        } else {
            Map<String, DeliveryLatencyRow> earliest = new LinkedHashMap<>();
            for (DeliveryLatencyRow row : rows) {
                if (isSent(row)) {
                    earliest.merge(row.alertId(), row,
                            (a, b) -> b.sentAt().isBefore(a.sentAt()) ? b : a);
                }
            }
            for (DeliveryLatencyRow row : earliest.values()) {
                samples.add(latency(row.alertCreatedAt(), row.sentAt()));
            }
        }

        if (samples.isEmpty()) {
            return new AlertLatencySummary(0, null, null);
        }
        double[] seconds = samples.stream().mapToDouble(d -> d.toMillis() / 1000.0).toArray();
        Arrays.sort(seconds);
        return new AlertLatencySummary(seconds.length,
                percentile(seconds, 0.5), percentile(seconds, 0.9));
    }

    private static boolean isSent(DeliveryLatencyRow row) {
        return AlertDeliveryRepository.STATUS_SENT.equals(row.status())
                && row.sentAt() != null && row.alertCreatedAt() != null;
    }

    /** Linear interpolation between closest ranks over sorted values (percentile_cont). */
    static double percentile(double[] sorted, double fraction) {
        double rank = fraction * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        return sorted[lower] + (rank - lower) * (sorted[upper] - sorted[lower]);
    }
}
