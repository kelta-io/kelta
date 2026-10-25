package io.kelta.worker.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserAccessRevocationMarker")
class UserAccessRevocationMarkerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T18:30:00Z");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final UserAccessRevocationMarker marker =
            new UserAccessRevocationMarker(redis, Duration.ofHours(2), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("writes user:revoked:<tenantId>:<userId> = epoch seconds with the configured TTL")
    void writesTheMarker() {
        when(redis.opsForValue()).thenReturn(valueOps);

        marker.markAfterCommit("t-1", "u-1");

        verify(valueOps).set("user:revoked:t-1:u-1", Long.toString(NOW.getEpochSecond()), Duration.ofHours(2));
    }

    @Test
    @DisplayName("inside a transaction the marker is written only after commit")
    void waitsForCommit() {
        when(redis.opsForValue()).thenReturn(valueOps);
        TransactionSynchronizationManager.initSynchronization();

        marker.markAfterCommit("t-1", "u-1");
        verifyNoInteractions(redis);

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(valueOps).set("user:revoked:t-1:u-1", Long.toString(NOW.getEpochSecond()), Duration.ofHours(2));
    }

    @Test
    @DisplayName("a Redis failure is logged, never thrown")
    void redisFailureIsSwallowed() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> marker.markAfterCommit("t-1", "u-1")).doesNotThrowAnyException();
        assertThat(marker.mark("t-1", "u-1")).isFalse();
    }

    @Test
    @DisplayName("a blank tenant or user id writes nothing")
    void blankIds() {
        marker.markAfterCommit("", "u-1");
        marker.markAfterCommit("t-1", null);

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("the default TTL is at least two hours")
    void defaultTtl() throws Exception {
        String expression = UserAccessRevocationMarker.class
                .getConstructor(StringRedisTemplate.class, Duration.class)
                .getParameters()[1]
                .getAnnotation(org.springframework.beans.factory.annotation.Value.class)
                .value();

        assertThat(expression).isEqualTo("${kelta.security.user-revocation-ttl:PT2H}");
        assertThat(Duration.parse("PT2H")).isGreaterThanOrEqualTo(Duration.ofHours(2));
    }
}
