package io.kelta.auth.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UnknownRegistrationCache")
class UnknownRegistrationCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private UnknownRegistrationCache cache(int maxEntries) {
        return new UnknownRegistrationCache(Duration.ofSeconds(30), maxEntries, clock);
    }

    @Test
    @DisplayName("remembers a miss until the TTL passes")
    void expiresAfterTtl() {
        var cache = cache(10);
        cache.recordUnknown("t:p");

        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.isKnownUnknown("t:p")).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.isKnownUnknown("t:p")).isFalse();
        assertThat(cache.size()).as("expired entry dropped on read").isZero();
    }

    @Test
    @DisplayName("forget and clear drop recorded misses")
    void forgetAndClear() {
        var cache = cache(10);
        cache.recordUnknown("t:a");
        cache.recordUnknown("t:b");

        cache.forget(List.of("t:a"));
        assertThat(cache.isKnownUnknown("t:a")).isFalse();
        assertThat(cache.isKnownUnknown("t:b")).isTrue();

        cache.clear();
        assertThat(cache.isKnownUnknown("t:b")).isFalse();
    }

    @Test
    @DisplayName("stays within its bound: purges expired entries, then stops recording")
    void bounded() {
        var cache = cache(2);
        cache.recordUnknown("t:a");
        cache.recordUnknown("t:b");

        cache.recordUnknown("t:c");
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.isKnownUnknown("t:c")).isFalse();

        clock.advance(Duration.ofSeconds(31));
        cache.recordUnknown("t:c");
        assertThat(cache.isKnownUnknown("t:c")).isTrue();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("does not keep oversized ids")
    void skipsOversizedIds() {
        var cache = cache(10);
        String huge = "t:" + "x".repeat(UnknownRegistrationCache.MAX_ID_LENGTH);

        cache.recordUnknown(huge);

        assertThat(cache.isKnownUnknown(huge)).isFalse();
        assertThat(cache.size()).isZero();
    }
}
