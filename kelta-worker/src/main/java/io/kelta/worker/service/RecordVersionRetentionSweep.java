package io.kelta.worker.service;

import io.kelta.worker.repository.RecordVersionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes {@code record_version} snapshots older than a retention window or beyond a per-record
 * version cap — <b>DESTRUCTIVE</b>.
 *
 * <p>{@code RecordVersionHook} writes one full-record snapshot per create/update/delete of a
 * collection with {@code track_history} on, and nothing else deletes them. This sweep bounds the
 * table with the same posture and knobs as {@link FlowLogRetentionSweep}.
 *
 * <p><b>Dry-run is the DEFAULT</b> ({@code kelta.record-version.retention.dry-run:true}): the
 * sweep only LOGS what it WOULD delete until an operator explicitly arms it. Runs unscoped on the
 * scheduler thread (no tenant bound → {@code app.current_tenant_id = ''} → {@code admin_bypass}),
 * so one cycle prunes across every tenant. Work is bounded per cycle by {@code batch-size} rows
 * per statement and {@code max-batches} statements, and each batch claims rows with
 * {@code FOR UPDATE SKIP LOCKED} so concurrent pods take disjoint slices.
 *
 * <p>A version is due when it is older than {@code max-age-days}, or when
 * {@code max-versions-per-record > 0} and it is not among its record's newest that many versions
 * (by {@code version_number}). The cap never removes a record's newest version; the age window
 * can remove every version of a record unchanged for longer than the window.
 */
@Service
public class RecordVersionRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RecordVersionRetentionSweep.class);

    private final RecordVersionRepository repository;
    private final boolean enabled;
    private final boolean dryRun;
    private final int maxAgeDays;
    private final int maxVersionsPerRecord;
    private final int batchSize;
    private final int maxBatches;
    private final Counter purgedCounter;

    public RecordVersionRetentionSweep(
            RecordVersionRepository repository,
            MeterRegistry meterRegistry,
            @Value("${kelta.record-version.retention.enabled:true}") boolean enabled,
            @Value("${kelta.record-version.retention.dry-run:true}") boolean dryRun,
            @Value("${kelta.record-version.retention.max-age-days:365}") int maxAgeDays,
            @Value("${kelta.record-version.retention.max-versions-per-record:0}") int maxVersionsPerRecord,
            @Value("${kelta.record-version.retention.batch-size:1000}") int batchSize,
            @Value("${kelta.record-version.retention.max-batches:20}") int maxBatches) {
        this.repository = repository;
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.maxAgeDays = maxAgeDays;
        this.maxVersionsPerRecord = Math.max(0, maxVersionsPerRecord);
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.purgedCounter = Counter.builder("kelta_worker_record_versions_purged")
                .description("record_version rows deleted by the retention sweep")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${kelta.record-version.retention.poll-interval-ms:3600000}")
    public void sweep() {
        runCycle();
    }

    /**
     * One sweep cycle: returns the rows that would be deleted in dry-run, the rows deleted when
     * armed, and 0 when disabled or on failure. Never throws — a thrown exception would suppress
     * every future run of a fixedDelay task.
     */
    public long runCycle() {
        if (!enabled) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeDays));
        try {
            return purge(cutoff);
        } catch (Exception e) {
            log.error("Record-version retention sweep failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Returns the rows that would be deleted in dry-run, or the rows deleted when armed.
     * Package-private so tests can drive the sweep without the scheduler.
     */
    long purge(Instant cutoff) {
        long due = repository.countDueForRetention(cutoff, maxVersionsPerRecord);
        if (due == 0) {
            return 0;
        }
        if (dryRun) {
            log.info("RecordVersionRetentionSweep DRY-RUN: WOULD delete {} record_version row(s) "
                    + "older than {} or beyond {} version(s) per record (0 = no cap); "
                    + "set kelta.record-version.retention.dry-run=false to arm",
                    due, cutoff, maxVersionsPerRecord);
            return due;
        }
        int deleted = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int removed = repository.deleteDueForRetentionBatch(cutoff, maxVersionsPerRecord, batchSize);
            deleted += removed;
            if (removed < batchSize) {
                break;
            }
            if (batch == maxBatches - 1) {
                log.info("Record-version retention hit the per-cycle batch cap ({} x {}); "
                        + "the remainder will drain on the next cycle", maxBatches, batchSize);
            }
        }
        purgedCounter.increment(deleted);
        log.info("Record-version retention: deleted {} of {} due record_version row(s) older than {} "
                + "or beyond {} version(s) per record", deleted, due, cutoff, maxVersionsPerRecord);
        return deleted;
    }
}
