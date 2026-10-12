package io.kelta.worker.module;

import java.time.Duration;
import java.util.Objects;

/**
 * Backoff for re-attempting a module load that failed for an infrastructure reason.
 *
 * <p>Exponential from {@code initialBackoff}, doubling per failed attempt, capped at
 * {@code maxBackoff}. There is no give-up: a retry loop that eventually stops would recreate the
 * terminal state that took every module down for five days in the 2026-10-06 incident. Instead,
 * once {@code escalateAfterAttempts} consecutive attempts have failed the failure is logged at ERROR
 * once more (the "still down" signal), and retries continue at the cap.
 *
 * @param initialBackoff        delay before the first retry
 * @param maxBackoff            the longest delay between retries
 * @param escalateAfterAttempts consecutive failed attempts after which the failure is escalated
 */
public record ModuleLoadRetryPolicy(Duration initialBackoff, Duration maxBackoff,
                                    int escalateAfterAttempts) {

    /** 30 s, doubling, capped at 10 min; escalate after 8 attempts (roughly 45 minutes down). */
    public static final ModuleLoadRetryPolicy DEFAULTS =
            new ModuleLoadRetryPolicy(Duration.ofSeconds(30), Duration.ofMinutes(10), 8);

    public ModuleLoadRetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be at least initialBackoff");
        }
        if (escalateAfterAttempts < 1) {
            throw new IllegalArgumentException("escalateAfterAttempts must be at least 1");
        }
    }

    /** Builds a policy from millisecond settings, as {@code application.yml} supplies them. */
    public static ModuleLoadRetryPolicy ofMillis(long initialBackoffMs, long maxBackoffMs,
                                                 int escalateAfterAttempts) {
        return new ModuleLoadRetryPolicy(Duration.ofMillis(initialBackoffMs),
                Duration.ofMillis(maxBackoffMs), escalateAfterAttempts);
    }

    /**
     * The delay before the next attempt, after {@code failedAttempts} consecutive failures.
     *
     * @param failedAttempts how many attempts have failed so far (1 after the first failure)
     */
    public Duration backoffAfter(int failedAttempts) {
        int doublings = Math.max(0, failedAttempts - 1);
        // Stop doubling well before the multiplication could overflow; the cap applies anyway.
        if (doublings >= 30) {
            return maxBackoff;
        }
        Duration delay = initialBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
