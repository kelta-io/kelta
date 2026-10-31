package io.kelta.worker.config;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Schedules the wait-state resume poller.
 *
 * <p>Long ({@code seconds} &gt; 10) and timestamp-based Wait states park their
 * execution WAITING and record a {@code flow_pending_resume} row with a wake-up
 * time. This poller claims due rows on a configurable interval (default 10s)
 * via {@code SELECT FOR UPDATE SKIP LOCKED} — so exactly one pod resumes each
 * execution — and hands them to {@link FlowEngine#resumeExecution(String, String)} under that claim.
 *
 * <p>Event-based waits ({@code resume_event} set, {@code resume_at} null) are
 * never time-claimed; they resume via {@code claimPendingResumeByEvent} when
 * the event arrives.
 *
 * <p><b>Claim lease.</b> A claim that no resume consumed within
 * {@code kelta.flow.resume.claim-lease-seconds} (default 300) is taken over here, whatever the
 * row's {@code resume_at}: its claimer died, its queued resume was dropped at shutdown, or it
 * failed without releasing. Each cycle claims under its own identifier, and only the current
 * holder of a row can consume it ({@link FlowEngine#resumeExecution(String, String)}), so a
 * takeover racing a slow resume runs the step once.
 *
 * <p>Enabled by default. Disable with {@code kelta.flow.resume.enabled=false}.
 *
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(name = "kelta.flow.resume.enabled", havingValue = "true", matchIfMissing = true)
public class FlowResumePollerConfig {

    private static final Logger log = LoggerFactory.getLogger(FlowResumePollerConfig.class);

    private static final int CLAIM_BATCH_SIZE = 25;

    private final FlowStore flowStore;
    private final FlowEngine flowEngine;
    private final Duration claimLease;
    private final String instanceId = "worker-" + UUID.randomUUID();

    public FlowResumePollerConfig(FlowStore flowStore, FlowEngine flowEngine,
                                  @Value("${kelta.flow.resume.claim-lease-seconds:300}") long claimLeaseSeconds) {
        if (claimLeaseSeconds < 1) {
            throw new IllegalArgumentException("kelta.flow.resume.claim-lease-seconds must be at least 1");
        }
        this.flowStore = flowStore;
        this.flowEngine = flowEngine;
        this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
        log.info("Flow resume poller enabled — claiming due flow_pending_resume rows as {} (claim lease {})",
            instanceId, claimLease);
    }

    @Scheduled(fixedDelayString = "${kelta.flow.resume.poll-interval-ms:10000}")
    public void pollAndResume() {
        // Unique per cycle: a later cycle's takeover of a row is then a different claim.
        String claim = instanceId + ":" + UUID.randomUUID();
        List<String> executionIds;
        try {
            executionIds = flowStore.claimPendingResumes(claim, CLAIM_BATCH_SIZE, claimLease);
        } catch (Exception e) {
            log.error("Flow resume poll cycle failed: {}", e.getMessage(), e);
            return;
        }
        for (String executionId : executionIds) {
            try {
                log.info("Resuming waiting flow execution {}", executionId);
                flowEngine.resumeExecution(executionId, claim);
            } catch (Exception e) {
                // The engine released the claim, so a later cycle retries this one; go on with the rest.
                log.error("Could not resume flow execution {}: {}", executionId, e.getMessage(), e);
            }
        }
    }
}
