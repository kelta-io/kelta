package io.kelta.worker.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModuleCircuitBreaker")
class ModuleCircuitBreakerTest {

    private final ExternalModuleFixtures.MutableClock clock = new ExternalModuleFixtures.MutableClock();
    private final ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(clock);

    @Test
    @DisplayName("opens on the 5th consecutive failure, not before; a success in between resets the count")
    void opensAfterFiveConsecutiveFailures() {
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.onFailure("boom")).isEqualTo(ModuleCircuitBreaker.Transition.NONE);
        }
        assertThat(breaker.onSuccess()).isEqualTo(ModuleCircuitBreaker.Transition.NONE);
        assertThat(breaker.consecutiveFailures()).isZero();

        for (int i = 0; i < 4; i++) {
            breaker.onFailure("boom");
        }
        assertThat(breaker.state()).isEqualTo(ModuleCircuitBreaker.State.CLOSED);
        assertThat(breaker.onFailure("fifth")).isEqualTo(ModuleCircuitBreaker.Transition.OPENED);
        assertThat(breaker.state()).isEqualTo(ModuleCircuitBreaker.State.OPEN);
        assertThat(breaker.lastFailure()).isEqualTo("fifth");
        assertThat(breaker.retryAt()).isEqualTo(clock.instant().plus(ModuleCircuitBreaker.OPEN_DURATION));
    }

    @Test
    @DisplayName("refuses while open; after 30 s admits exactly one probe, which closes it on success")
    void halfOpenProbeRecovers() {
        open();
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).as("the probe").isTrue();
        assertThat(breaker.state()).isEqualTo(ModuleCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("while the probe is in flight").isFalse();

        assertThat(breaker.onSuccess()).isEqualTo(ModuleCircuitBreaker.Transition.RECOVERED);
        assertThat(breaker.state()).isEqualTo(ModuleCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.retryAt()).isNull();
    }

    @Test
    @DisplayName("a failed probe opens it for another 30 s without reporting a second OPENED")
    void failedProbeReopens() {
        open();
        clock.advance(ModuleCircuitBreaker.OPEN_DURATION);
        assertThat(breaker.tryAcquire()).isTrue();

        assertThat(breaker.onFailure("still down")).isEqualTo(ModuleCircuitBreaker.Transition.NONE);
        assertThat(breaker.state()).isEqualTo(ModuleCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(ModuleCircuitBreaker.OPEN_DURATION);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    private void open() {
        for (int i = 0; i < ModuleCircuitBreaker.FAILURE_THRESHOLD; i++) {
            breaker.onFailure("boom");
        }
        assertThat(breaker.state()).isEqualTo(ModuleCircuitBreaker.State.OPEN);
    }
}
