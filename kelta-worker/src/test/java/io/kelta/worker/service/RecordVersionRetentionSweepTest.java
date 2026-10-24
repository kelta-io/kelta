package io.kelta.worker.service;

import io.kelta.worker.repository.RecordVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RecordVersionRetentionSweep Tests")
class RecordVersionRetentionSweepTest {

    private RecordVersionRepository repository;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository = mock(RecordVersionRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        when(repository.countDueForRetention(any(), anyInt())).thenReturn(0L);
    }

    private RecordVersionRetentionSweep sweep(boolean enabled, boolean dryRun) {
        return sweep(enabled, dryRun, 365, 0, 1000, 20);
    }

    private RecordVersionRetentionSweep sweep(boolean enabled, boolean dryRun, int maxAgeDays,
                                              int maxVersionsPerRecord, int batchSize, int maxBatches) {
        return new RecordVersionRetentionSweep(repository, meterRegistry, enabled, dryRun,
                maxAgeDays, maxVersionsPerRecord, batchSize, maxBatches);
    }

    private double purgedCount() {
        return meterRegistry.get("kelta_worker_record_versions_purged").counter().count();
    }

    @Nested
    @DisplayName("Gating")
    class Gating {

        @Test
        @DisplayName("enabled=false touches nothing")
        void disabledTouchesNothing() {
            sweep(false, false).sweep();
            verifyNoInteractions(repository);
            assertThat(purgedCount()).isZero();
        }

        @Test
        @DisplayName("DRY-RUN counts and reports but deletes nothing")
        void dryRunDeletesNothing() {
            when(repository.countDueForRetention(any(), anyInt())).thenReturn(4_200L);

            long reported = sweep(true, true).purge(Instant.now());

            assertThat(reported).isEqualTo(4_200L);
            verify(repository).countDueForRetention(any(), anyInt());
            verify(repository, never()).deleteDueForRetentionBatch(any(), anyInt(), anyInt());
            assertThat(purgedCount()).isZero();
        }

        @Test
        @DisplayName("nothing due short-circuits before any delete")
        void nothingDueShortCircuits() {
            sweep(true, false).sweep();
            verify(repository, never()).deleteDueForRetentionBatch(any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("Armed deletion")
    class ArmedDeletion {

        @Test
        @DisplayName("increments the purged metric by the rows deleted")
        void incrementsMetric() {
            when(repository.countDueForRetention(any(), anyInt())).thenReturn(7L);
            when(repository.deleteDueForRetentionBatch(any(), anyInt(), anyInt())).thenReturn(7);

            sweep(true, false).sweep();

            verify(repository).deleteDueForRetentionBatch(any(), anyInt(), eq(1000));
            assertThat(purgedCount()).isEqualTo(7.0);
        }

        @Test
        @DisplayName("keeps draining while batches come back full")
        void drainsFullBatches() {
            when(repository.countDueForRetention(any(), anyInt())).thenReturn(250L);
            when(repository.deleteDueForRetentionBatch(any(), anyInt(), anyInt())).thenReturn(100, 100, 50);

            long deleted = sweep(true, false, 365, 0, 100, 20).purge(Instant.now());

            verify(repository, times(3)).deleteDueForRetentionBatch(any(), anyInt(), eq(100));
            assertThat(deleted).isEqualTo(250L);
            assertThat(purgedCount()).isEqualTo(250.0);
        }

        @Test
        @DisplayName("stops at max-batches rather than running unbounded")
        void respectsBatchCap() {
            when(repository.countDueForRetention(any(), anyInt())).thenReturn(1_000_000L);
            when(repository.deleteDueForRetentionBatch(any(), anyInt(), anyInt())).thenReturn(100);

            sweep(true, false, 365, 0, 100, 3).purge(Instant.now());

            verify(repository, times(3)).deleteDueForRetentionBatch(any(), anyInt(), anyInt());
            assertThat(purgedCount()).isEqualTo(300.0);
        }

        @Test
        @DisplayName("passes max-age-days as the cutoff and the cap through")
        void passesCutoffAndCap() {
            when(repository.countDueForRetention(any(), anyInt())).thenReturn(1L);
            when(repository.deleteDueForRetentionBatch(any(), anyInt(), anyInt())).thenReturn(1);
            Instant before = Instant.now();

            sweep(true, false, 30, 5, 1000, 20).sweep();

            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(repository).countDueForRetention(cutoff.capture(), eq(5));
            verify(repository).deleteDueForRetentionBatch(cutoff.getValue(), 5, 1000);
            assertThat(Duration.between(before.minus(Duration.ofDays(30)), cutoff.getValue()).abs())
                    .isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("a negative cap is treated as no cap")
        void negativeCapIsNoCap() {
            sweep(true, true, 365, -3, 1000, 20).purge(Instant.now());
            verify(repository).countDueForRetention(any(), eq(0));
        }
    }

    @Test
    @DisplayName("a failure never escapes to the scheduler")
    void failureDoesNotEscape() {
        when(repository.countDueForRetention(any(), anyInt())).thenThrow(new IllegalStateException("db down"));
        // A thrown exception would suppress every future run of a fixedDelay task.
        sweep(true, false).sweep();
    }
}
