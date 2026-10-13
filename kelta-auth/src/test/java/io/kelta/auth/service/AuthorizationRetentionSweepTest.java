package io.kelta.auth.service;

import io.kelta.auth.config.AuthProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthorizationRetentionSweep")
class AuthorizationRetentionSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuthProperties.AuthorizationRetention settings = new AuthProperties.AuthorizationRetention();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AuthorizationRetentionSweep sweep;

    @BeforeEach
    void setUp() {
        settings.setBatchSize(100);
        settings.setMaxBatches(5);
        sweep = new AuthorizationRetentionSweep(jdbc, settings, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void batches(Integer first, Integer... rest) {
        when(jdbc.update(eq(AuthorizationRetentionSweep.DELETE_BATCH), any(Object[].class))).thenReturn(first, rest);
    }

    @Test
    @DisplayName("deletes in batches until a short batch, and counts what it deleted")
    void drainsUntilShortBatch() {
        batches(100, 100, 7);

        assertThat(sweep.deleteExpired()).isEqualTo(207);
        verify(jdbc, times(3)).update(eq(AuthorizationRetentionSweep.DELETE_BATCH), any(Object[].class));
        assertThat(meters.counter("kelta_auth_authorizations_deleted").count()).isEqualTo(207);
    }

    @Test
    @DisplayName("stops after max-batches statements, leaving the rest of a backlog for the next sweep")
    void boundedPerSweep() {
        batches(100);

        assertThat(sweep.deleteExpired()).isEqualTo(500);
        verify(jdbc, times(5)).update(eq(AuthorizationRetentionSweep.DELETE_BATCH), any(Object[].class));
    }

    @Test
    @DisplayName("every token expiry is compared with now minus the grace period, and the batch size is the limit")
    void bindsCutoffAndLimit() {
        settings.setGrace(Duration.ofDays(1));
        batches(0);

        sweep.deleteExpired();

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq(AuthorizationRetentionSweep.DELETE_BATCH), args.capture());
        Timestamp cutoff = Timestamp.from(NOW.minus(Duration.ofDays(1)));
        assertThat(args.getValue()).containsExactly(cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, 100);
    }

    @Test
    @DisplayName("the statement names every token of the table and locks only rows no other sweep holds")
    void statementCoversEveryToken() {
        for (String token : new String[] {"authorization_code", "access_token", "oidc_id_token", "refresh_token",
                "device_code", "user_code"}) {
            assertThat(AuthorizationRetentionSweep.DELETE_BATCH)
                    .contains(token + "_value IS NULL OR " + token + "_expires_at < ?")
                    .contains(token + "_value IS NOT NULL");
        }
        assertThat(AuthorizationRetentionSweep.DELETE_BATCH).contains("LIMIT ?").contains("FOR UPDATE SKIP LOCKED");
    }

    @Test
    @DisplayName("disabled: the scheduled run touches nothing")
    void disabled() {
        settings.setEnabled(false);

        sweep.sweep();

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a database failure is logged, never thrown into the scheduler")
    void failureIsContained() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatCode(sweep::sweep).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a negative grace period counts as none")
    void negativeGrace() {
        settings.setGrace(Duration.ofHours(-1));

        assertThat(sweep.cutoff()).isEqualTo(NOW);
    }
}
