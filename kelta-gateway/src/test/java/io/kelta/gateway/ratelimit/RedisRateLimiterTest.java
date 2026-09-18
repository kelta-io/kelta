package io.kelta.gateway.ratelimit;

import io.kelta.gateway.route.RateLimitConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisRateLimiter Tests")
class RedisRateLimiterTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private org.springframework.data.redis.core.ReactiveValueOperations<String, String> valueOps;

    private RedisRateLimiter newLimiter() {
        return new RedisRateLimiter(redisTemplate);
    }

    @SuppressWarnings("unchecked")
    private void givenWindowCount(long count) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
            .thenReturn(Flux.just(count));
    }

    @Test
    void testFirstRequestInWindow() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(1L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 9)
            .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testWindowKeyAndTtlPassedToScript() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(5));
        givenWindowCount(1L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(RateLimitResult::isAllowed)
            .verifyComplete();

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly("ratelimit:users-collection:user@example.com");
        assertThat(argsCaptor.getValue()).containsExactly("300");
    }

    @Test
    void testSubsequentRequestDoesNotRefreshTtl() {
        // The window TTL is managed inside the atomic script (set only when the
        // window is new or its TTL was lost). Refreshing it per request would
        // turn the fixed window into an idle-expiry that never resets under
        // continuous traffic — the root cause of a tenant-wide 429 lockout.
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(5L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 5)
            .verifyComplete();

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void testRateLimitExceededUsesRemainingWindowTtlAsRetryAfter() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(5));
        givenWindowCount(11L);
        when(redisTemplate.getExpire("ratelimit:users-collection:user@example.com"))
            .thenReturn(Mono.just(Duration.ofSeconds(37)));

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result ->
                !result.isAllowed() &&
                result.getRemainingRequests() == 0 &&
                result.getRetryAfter().equals(Duration.ofSeconds(37)))
            .verifyComplete();
    }

    @Test
    void testRateLimitExceededFallsBackToWindowDurationWhenTtlMissing() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(11L);
        when(redisTemplate.getExpire("ratelimit:users-collection:user@example.com"))
            .thenReturn(Mono.empty());

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result ->
                !result.isAllowed() && result.getRetryAfter().equals(Duration.ofMinutes(1)))
            .verifyComplete();
    }

    @Test
    void testRateLimitExceededFallsBackToWindowDurationWhenTtlNonPositive() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(11L);
        when(redisTemplate.getExpire("ratelimit:users-collection:user@example.com"))
            .thenReturn(Mono.just(Duration.ofSeconds(-1)));

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result ->
                !result.isAllowed() && result.getRetryAfter().equals(Duration.ofMinutes(1)))
            .verifyComplete();
    }

    @Test
    void testExactlyAtLimit() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(10L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 0)
            .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRedisUnavailable() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
            .thenReturn(Flux.error(new RuntimeException("Redis connection failed")));

        // Should allow request and not throw exception
        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 10)
            .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDifferentPrincipalsHaveSeparateKeys() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(1L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user1@example.com", config))
            .expectNextMatches(RateLimitResult::isAllowed)
            .verifyComplete();
        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user2@example.com", config))
            .expectNextMatches(RateLimitResult::isAllowed)
            .verifyComplete();

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), keysCaptor.capture(), anyList());
        assertThat(keysCaptor.getAllValues()).containsExactly(
            List.of("ratelimit:users-collection:user1@example.com"),
            List.of("ratelimit:users-collection:user2@example.com"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDifferentRoutesHaveSeparateKeys() {
        RateLimitConfig config = new RateLimitConfig(10, Duration.ofMinutes(1));
        givenWindowCount(1L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(RateLimitResult::isAllowed)
            .verifyComplete();
        StepVerifier.create(newLimiter().checkRateLimit("posts-collection", "user@example.com", config))
            .expectNextMatches(RateLimitResult::isAllowed)
            .verifyComplete();

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), keysCaptor.capture(), anyList());
        assertThat(keysCaptor.getAllValues()).containsExactly(
            List.of("ratelimit:users-collection:user@example.com"),
            List.of("ratelimit:posts-collection:user@example.com"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCustomWindowDurationPassedAsSeconds() {
        RateLimitConfig config = new RateLimitConfig(100, Duration.ofSeconds(30));
        givenWindowCount(1L);

        StepVerifier.create(newLimiter().checkRateLimit("users-collection", "user@example.com", config))
            .expectNextMatches(RateLimitResult::isAllowed)
            .verifyComplete();

        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("30");
    }

    @Nested
    @DisplayName("incrementPatUsageCounter — per-PAT request metering")
    class IncrementPatUsageCounter {

        @Test
        @DisplayName("first increment sets the TTL")
        void firstIncrementSetsTtl() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("pat-usage:abc123")).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(eq("pat-usage:abc123"), any(Duration.class))).thenReturn(Mono.just(true));

            StepVerifier.create(newLimiter().incrementPatUsageCounter("abc123")).verifyComplete();

            verify(redisTemplate).expire(eq("pat-usage:abc123"), any(Duration.class));
        }

        @Test
        @DisplayName("subsequent increments do not refresh the TTL")
        void subsequentIncrementsSkipTtl() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("pat-usage:abc123")).thenReturn(Mono.just(2L));

            StepVerifier.create(newLimiter().incrementPatUsageCounter("abc123")).verifyComplete();

            verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("fails open (completes without error) when Redis is unavailable")
        void failsOpenOnRedisError() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("pat-usage:abc123"))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection failed")));

            StepVerifier.create(newLimiter().incrementPatUsageCounter("abc123")).verifyComplete();
        }
    }

    @Nested
    @DisplayName("checkWindow — the shared window primitive")
    class CheckWindow {

        private static final String KEY = "ratelimit:ip:/api/modules/webhooks:203.0.113.9";

        @Test
        @DisplayName("should allow and report the remaining budget when under the limit")
        void allowsWhenUnderTheLimit() {
            givenWindowCount(3L);

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofSeconds(60)))
                .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 2)
                .verifyComplete();
        }

        @Test
        @DisplayName("should fail open when the script completes without emitting")
        @SuppressWarnings("unchecked")
        void allowsWhenScriptEmitsNothing() {
            // An empty Flux is not an error, so it slips past onErrorResume. Left
            // unhandled the Mono completes empty, the calling filter never reaches
            // chain.filter(), and the request hangs with no status and no body —
            // strictly worse than the documented fail-open, and on a public path
            // that is a hung client rather than a retried API call.
            when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofSeconds(60)))
                .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 5)
                .verifyComplete();
        }

        @Test
        @DisplayName("should reject with the window's remaining TTL once over the limit")
        void rejectsWhenOverTheLimit() {
            givenWindowCount(6L);
            when(redisTemplate.getExpire(KEY)).thenReturn(Mono.just(Duration.ofSeconds(12)));

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofSeconds(60)))
                .expectNextMatches(result ->
                    !result.isAllowed() && result.getRetryAfter().equals(Duration.ofSeconds(12)))
                .verifyComplete();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("should pass the caller's key through verbatim with the window as TTL")
        void passesKeyAndTtlThrough() {
            // Callers namespace their own keys (the IP limiter uses "ratelimit:ip:"),
            // so this method must not re-prefix them or the two limiters collide.
            givenWindowCount(1L);

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofSeconds(90)))
                .expectNextMatches(RateLimitResult::isAllowed)
                .verifyComplete();

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
            verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
            assertThat(keysCaptor.getValue()).containsExactly(KEY);
            assertThat(argsCaptor.getValue()).containsExactly("90");
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("should clamp a sub-second window to a one-second TTL")
        void clampsSubSecondWindow() {
            // EXPIRE 0 deletes the key outright, which would reset the counter on
            // every request and silently disable the limit.
            givenWindowCount(1L);

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofMillis(400)))
                .expectNextMatches(RateLimitResult::isAllowed)
                .verifyComplete();

            ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
            verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
            assertThat(argsCaptor.getValue()).containsExactly("1");
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("should fail open when Redis is unreachable")
        void failsOpenOnRedisError() {
            // A cache outage must not take the platform down; it does mean limiting
            // is off for the duration, which is the accepted trade-off.
            when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("Redis connection failed")));

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofSeconds(60)))
                .expectNextMatches(result -> result.isAllowed() && result.getRemainingRequests() == 5)
                .verifyComplete();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("should keep the TTL write guarded inside the Lua script")
        void luaScriptSetsTtlOnlyConditionally() {
            // Regression guard for the 2026-07-11 production incident: an
            // unconditional EXPIRE refreshed the window on every request, turning
            // the fixed window into an idle-expiry. Under continuous traffic the
            // counter never reset, so once a tenant crossed its limit every
            // rejected request re-extended the TTL and sustained a tenant-wide 429
            // lockout indefinitely. The TTL may only be written when the window is
            // new (count == 1) or a previous EXPIRE was lost (TTL < 0).
            givenWindowCount(1L);

            StepVerifier.create(newLimiter().checkWindow(KEY, 5, Duration.ofSeconds(60)))
                .expectNextMatches(RateLimitResult::isAllowed)
                .verifyComplete();

            ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
            verify(redisTemplate).execute(scriptCaptor.capture(), anyList(), anyList());
            String source = scriptCaptor.getValue().getScriptAsString();

            assertThat(source).contains("count == 1");
            assertThat(source).contains("TTL");
            // Strip the guarded block; nothing that sets an expiry may survive it.
            String outsideTheGuard = source.replaceAll("(?s)if\\s+count\\s*==\\s*1.*?\\bend\\b", "");
            assertThat(outsideTheGuard).doesNotContainIgnoringCase("expire");
        }
    }
}
