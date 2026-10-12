package io.kelta.worker.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModuleLoadRetryPolicy")
class ModuleLoadRetryPolicyTest {

    @Test
    @DisplayName("defaults double from 30s and cap at 10 minutes")
    void defaultsDoubleAndCap() {
        ModuleLoadRetryPolicy policy = ModuleLoadRetryPolicy.DEFAULTS;
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(policy.backoffAfter(5)).isEqualTo(Duration.ofSeconds(480));
        assertThat(policy.backoffAfter(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffAfter(300)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("builds from the millisecond settings application.yml supplies")
    void ofMillis() {
        ModuleLoadRetryPolicy policy = ModuleLoadRetryPolicy.ofMillis(1_000, 4_000, 3);
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.escalateAfterAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects settings that would retry in a tight loop or never escalate")
    void rejectsNonsense() {
        assertThatThrownBy(() -> ModuleLoadRetryPolicy.ofMillis(0, 1_000, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleLoadRetryPolicy.ofMillis(2_000, 1_000, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleLoadRetryPolicy.ofMillis(1_000, 2_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
