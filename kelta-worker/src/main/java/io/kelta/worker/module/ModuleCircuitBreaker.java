package io.kelta.worker.module;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The circuit breaker in front of one external module install, on one pod
 * ({@code specs/module-platform/9-external-modules.md} §3.6): it opens after
 * {@value #FAILURE_THRESHOLD} consecutive failures, refuses calls while open, and after
 * {@link #OPEN_DURATION} lets exactly one call through as a probe (half-open). A successful probe
 * closes it; a failed one opens it for another 30 s.
 *
 * <p>Per pod on purpose: whether a pod can reach a module is a fact about that pod's network.
 * The shared signal is the {@code tenant_module} status, which {@link ExternalModuleInvoker}
 * writes {@code DEGRADED} when a breaker opens and {@code ACTIVE} when one recovers.
 *
 * <p>Thread-safe; every method is short and synchronized.
 */
final class ModuleCircuitBreaker {

    static final int FAILURE_THRESHOLD = 5;
    static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** What a recorded outcome did to the breaker. */
    enum Transition {
        /** No change of state. */
        NONE,
        /** It just opened: the module is now refused on this pod. */
        OPENED,
        /** It just closed again after being open: the module recovered. */
        RECOVERED
    }

    private final Clock clock;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;
    private String lastFailure;

    ModuleCircuitBreaker(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Whether a call may go out now. While open this is false until {@link #OPEN_DURATION} has
     * passed; then the breaker turns half-open and admits one probe, and refuses everything else
     * until that probe's outcome is recorded.
     */
    synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (clock.instant().isBefore(openedAt.plus(OPEN_DURATION))) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            }
            default -> {
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
            }
        }
    }

    synchronized Transition onSuccess() {
        State before = state;
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
        openedAt = null;
        return before == State.CLOSED ? Transition.NONE : Transition.RECOVERED;
    }

    synchronized Transition onFailure(String reason) {
        lastFailure = reason;
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = clock.instant();
            return Transition.NONE;
        }
        if (state == State.OPEN) {
            return Transition.NONE;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            openedAt = clock.instant();
            return Transition.OPENED;
        }
        return Transition.NONE;
    }

    synchronized State state() {
        return state;
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** When an open breaker admits its next probe, or {@code null} when it is not open. */
    synchronized Instant retryAt() {
        return state == State.OPEN && openedAt != null ? openedAt.plus(OPEN_DURATION) : null;
    }

    synchronized String lastFailure() {
        return lastFailure;
    }
}
