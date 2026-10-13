package io.kelta.worker.service;

import io.kelta.worker.repository.FlowLogRetentionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("FlowLogRetentionSweep Tests")
class FlowLogRetentionSweepTest {

    private FlowLogRetentionRepository repository;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository = mock(FlowLogRetentionRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        when(repository.countDueExecutions(any(), anyInt())).thenReturn(0L);
        when(repository.countDueJobLogs(any())).thenReturn(0L);
        when(repository.countStaleRunning(any())).thenReturn(0L);
    }

    private FlowLogRetentionSweep sweep(boolean enabled, boolean dryRun) {
        return sweep(enabled, dryRun, 60, 1000, 20);
    }

    private FlowLogRetentionSweep sweep(boolean enabled, boolean dryRun,
                                        int maxAgeDays, int batchSize, int maxBatches) {
        return sweep(enabled, dryRun, maxAgeDays, batchSize, maxBatches, 24);
    }

    private FlowLogRetentionSweep sweep(boolean enabled, boolean dryRun, int maxAgeDays,
                                        int batchSize, int maxBatches, int staleRunningHours) {
        return new FlowLogRetentionSweep(repository, meterRegistry,
                enabled, dryRun, maxAgeDays, batchSize, maxBatches, staleRunningHours);
    }

    private double purgedCount() {
        return meterRegistry.get("kelta_worker_flowlog_purged").counter().count();
    }

    @Nested
    @DisplayName("Gating")
    class Gating {

        @Test
        @DisplayName("disabled sweep touches nothing")
        void disabledTouchesNothing() {
            sweep(false, false).sweep();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("DRY-RUN is the default posture and deletes nothing")
        void dryRunDeletesNothing() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(12_431L);
            when(repository.countDueJobLogs(any())).thenReturn(3_010L);

            sweep(true, true).sweep();

            // Counted, reported, and NOT deleted.
            verify(repository).countDueExecutions(any(), anyInt());
            verify(repository).countDueJobLogs(any());
            verify(repository, never()).deleteExecutionBatch(any(), anyInt(), anyInt());
            verify(repository, never()).deleteJobLogBatch(any(), anyInt());
            assertThat(purgedCount()).isZero();
        }

        @Test
        @DisplayName("nothing due short-circuits before any delete")
        void nothingDueShortCircuits() {
            sweep(true, false).sweep();

            verify(repository, never()).deleteExecutionBatch(any(), anyInt(), anyInt());
            verify(repository, never()).deleteJobLogBatch(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("Armed deletion")
    class ArmedDeletion {

        @Test
        @DisplayName("deletes both tables and counts the rows")
        void deletesBothTables() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(5L);
            when(repository.countDueJobLogs(any())).thenReturn(3L);
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(5);
            when(repository.deleteJobLogBatch(any(), anyInt())).thenReturn(3);

            sweep(true, false, 60, 1000, 20).sweep();

            verify(repository).deleteExecutionBatch(any(), anyInt(), anyInt());
            verify(repository).deleteJobLogBatch(any(), anyInt());
            assertThat(purgedCount()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("keeps draining while batches come back full")
        void drainsFullBatches() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(250L);
            // Two full batches, then a short one ends the drain.
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt()))
                    .thenReturn(100, 100, 50);

            sweep(true, false, 60, 100, 20).purgeExecutions(Instant.now());

            verify(repository, times(3)).deleteExecutionBatch(any(), anyInt(), anyInt());
            assertThat(purgedCount()).isEqualTo(250.0);
        }

        @Test
        @DisplayName("stops at the per-cycle batch cap rather than running unbounded")
        void respectsBatchCap() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(1_000_000L);
            // Always a full batch: without the cap this would never terminate.
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(100);

            sweep(true, false, 60, 100, 3).purgeExecutions(Instant.now());

            verify(repository, times(3)).deleteExecutionBatch(any(), anyInt(), anyInt());
            assertThat(purgedCount()).isEqualTo(300.0);
        }

        @Test
        @DisplayName("passes the configured batch size through")
        void passesBatchSize() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(10L);
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(10);

            sweep(true, false, 60, 500, 20).purgeExecutions(Instant.now());

            verify(repository).deleteExecutionBatch(any(), anyInt(), eq(500));
        }
    }

    @Nested
    @DisplayName("Cutoff")
    class Cutoff {

        @Test
        @DisplayName("executions get the cycle's now and the global days; per-flow windows resolve in SQL")
        void executionsGetNowAndGlobalDays() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(1L);
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(1);
            Instant before = Instant.now();

            sweep(true, false, 30, 1000, 20).sweep();

            ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
            verify(repository).countDueExecutions(now.capture(), eq(30));
            verify(repository).deleteExecutionBatch(now.getValue(), 30, 1000);
            assertThat(Duration.between(before, now.getValue()).abs())
                    .isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("job logs have no flow and are cut at max-age-days")
        void jobLogsUseGlobalCutoff() {
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(1L);
            when(repository.countDueJobLogs(any())).thenReturn(1L);
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(1);
            when(repository.deleteJobLogBatch(any(), anyInt())).thenReturn(1);

            sweep(true, false).sweep();

            ArgumentCaptor<Instant> execNow = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> jobCutoff = ArgumentCaptor.forClass(Instant.class);
            verify(repository).countDueExecutions(execNow.capture(), eq(60));
            verify(repository).countDueJobLogs(jobCutoff.capture());
            assertThat(jobCutoff.getValue()).isEqualTo(execNow.getValue().minus(Duration.ofDays(60)));
        }
    }

    @Nested
    @DisplayName("Stale RUNNING reaper")
    class Reaper {

        @Test
        @DisplayName("marks stale RUNNING executions Abandoned with a message naming the threshold")
        void reapsWithThreshold() {
            when(repository.countStaleRunning(any())).thenReturn(2L);
            when(repository.reapStaleRunningBatch(any(), anyString(), anyInt())).thenReturn(2);
            Instant now = Instant.parse("2026-10-13T12:00:00Z");

            sweep(true, false, 60, 1000, 20, 24).reapStaleRunning(now);

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            Instant staleBefore = Instant.parse("2026-10-12T12:00:00Z");
            verify(repository).countStaleRunning(staleBefore);
            verify(repository).reapStaleRunningBatch(eq(staleBefore), message.capture(), eq(1000));
            assertThat(message.getValue()).contains("24 hours").contains("kelta.flow.stale-running-hours");
            assertThat(meterRegistry.get("kelta_worker_flow_executions_abandoned").counter().count())
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("reaps before purging, in the same cycle")
        void reapsBeforePurge() {
            when(repository.countStaleRunning(any())).thenReturn(1L);
            when(repository.reapStaleRunningBatch(any(), anyString(), anyInt())).thenReturn(1);
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(1L);
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(1);

            sweep(true, false).sweep();

            InOrder order = inOrder(repository);
            order.verify(repository).reapStaleRunningBatch(any(), anyString(), anyInt());
            order.verify(repository).deleteExecutionBatch(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("stays bounded by batch-size x max-batches")
        void respectsBatchCap() {
            when(repository.countStaleRunning(any())).thenReturn(1_000L);
            when(repository.reapStaleRunningBatch(any(), anyString(), anyInt())).thenReturn(10);

            sweep(true, false, 60, 10, 3, 24).reapStaleRunning(Instant.now());

            verify(repository, times(3)).reapStaleRunningBatch(any(), anyString(), eq(10));
        }

        @Test
        @DisplayName("dry-run counts but does not update")
        void dryRunDoesNotUpdate() {
            when(repository.countStaleRunning(any())).thenReturn(5L);

            sweep(true, true).reapStaleRunning(Instant.now());

            verify(repository).countStaleRunning(any());
            verify(repository, never()).reapStaleRunningBatch(any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("stale-running-hours 0 disables the reaper")
        void zeroDisables() {
            sweep(true, false, 60, 1000, 20, 0).reapStaleRunning(Instant.now());

            verify(repository, never()).countStaleRunning(any());
            verify(repository, never()).reapStaleRunningBatch(any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("a reaper failure still lets executions be purged")
        void reaperFailureDoesNotBlockPurge() {
            when(repository.countStaleRunning(any())).thenThrow(new IllegalStateException("lock timeout"));
            when(repository.countDueExecutions(any(), anyInt())).thenReturn(1L);
            when(repository.deleteExecutionBatch(any(), anyInt(), anyInt())).thenReturn(1);

            sweep(true, false).sweep();

            verify(repository).deleteExecutionBatch(any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("Failure isolation")
    class FailureIsolation {

        @Test
        @DisplayName("an execution-phase failure still lets job logs be pruned")
        void executionFailureDoesNotBlockJobLogs() {
            when(repository.countDueExecutions(any(), anyInt()))
                    .thenThrow(new IllegalStateException("lock timeout"));
            when(repository.countDueJobLogs(any())).thenReturn(2L);
            when(repository.deleteJobLogBatch(any(), anyInt())).thenReturn(2);

            sweep(true, false).sweep();

            verify(repository).deleteJobLogBatch(any(), anyInt());
        }

        @Test
        @DisplayName("a failure never escapes to the scheduler")
        void failureDoesNotEscape() {
            when(repository.countDueExecutions(any(), anyInt()))
                    .thenThrow(new IllegalStateException("db down"));
            when(repository.countDueJobLogs(any()))
                    .thenThrow(new IllegalStateException("db down"));

            // A thrown exception would suppress every future run of a fixedDelay task.
            sweep(true, false).sweep();
        }
    }
}
