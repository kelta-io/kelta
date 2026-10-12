package io.kelta.worker.service.mailbox;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.MailboxEscalationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fires SLA warnings and breach escalations.
 *
 * <p>A {@code @Scheduled} platform bean rather than a {@code scheduled_job} row: that table is
 * tenant-scoped with a {@code job_type} CHECK constraint, so using it would mean a migration plus
 * a row per tenant to do something that is inherently cross-tenant.
 *
 * <p><b>This sweep is a gate, not a tidier.</b> Unlike a pass-expiry sweep (the billing module's
 * {@code billing:expire-passes}), whose work the read path re-derives anyway, nothing else notices a breach — if this stops running, nobody
 * is paged and the only symptom is silence. That is why it publishes a liveness timestamp, and why
 * an operator alert on that timestamp is worth more than one on any counter here.
 *
 * <p>Claim-then-send, deliberately: a pod crash between the two drops one notification rather than
 * sending it twice. The next level still fires on schedule, so a dropped WARN self-heals into a
 * BREACH rather than vanishing.
 *
 * @since 1.0.0
 */
@Service
public class MailboxSlaSweep {

    private static final Logger log = LoggerFactory.getLogger(MailboxSlaSweep.class);

    private static final String CLOCK_FIRST_RESPONSE = "FIRST_RESPONSE";
    private static final String CLOCK_RESOLUTION = "RESOLUTION";

    /**
     * The chain. Offsets are minutes relative to the thread's due time, so a negative value is a
     * warning ahead of the deadline and positive values are escalating lateness.
     */
    private record Step(String level, int offsetMinutes) {
    }

    private final MailboxEscalationRepository escalationRepository;
    private final MailboxEscalationDispatchService dispatchService;
    private final boolean enabled;
    private final int batchSize;
    private final List<Step> chain;

    private volatile long lastRunEpochSeconds;

    public MailboxSlaSweep(MailboxEscalationRepository escalationRepository,
                           MailboxEscalationDispatchService dispatchService,
                           @Value("${kelta.mailbox.sla.enabled:true}") boolean enabled,
                           @Value("${kelta.mailbox.sla.batch-size:200}") int batchSize,
                           @Value("${kelta.mailbox.sla.warn-offset-minutes:-30}") int warnOffset,
                           @Value("${kelta.mailbox.sla.breach-2-offset-minutes:60}") int breach2Offset,
                           @Value("${kelta.mailbox.sla.breach-3-offset-minutes:240}") int breach3Offset) {
        this.escalationRepository = escalationRepository;
        this.dispatchService = dispatchService;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.chain = List.of(
                new Step("WARN", warnOffset),
                new Step("BREACH", 0),
                new Step("BREACH_2", breach2Offset),
                new Step("BREACH_3", breach3Offset));
    }

    @Scheduled(fixedDelayString = "${kelta.mailbox.sla.poll-interval-ms:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            // Claim BEFORE settling, never after. settleStates() moves a newly-late thread from
            // PENDING to BREACHED, and claimDue reads that same column; settling first meant a
            // thread crossed its due time and lost its claim eligibility within one tick, so the
            // BREACH notification was dropped in the same pass that noticed the breach. claimDue
            // now also accepts BREACHED, which covers the later levels and any thread that was
            // already settled by an older build — this ordering just avoids a one-tick delay.
            int fired = 0;
            for (String clock : List.of(CLOCK_FIRST_RESPONSE, CLOCK_RESOLUTION)) {
                for (Step step : chain) {
                    fired += fire(clock, step);
                }
            }

            escalationRepository.settleStates();
            lastRunEpochSeconds = System.currentTimeMillis() / 1000;
            if (fired > 0) {
                log.info("Fired {} support SLA escalation(s)", fired);
            }
        } catch (Exception e) {
            // Never let one bad batch kill the scheduler thread.
            log.error("Support SLA sweep failed: {}", e.getMessage(), e);
        }
    }

    private int fire(String clock, Step step) {
        List<MailboxEscalationRepository.Claimed> claimed =
                escalationRepository.claimDue(clock, step.level(), step.offsetMinutes(), batchSize);

        for (MailboxEscalationRepository.Claimed escalation : claimed) {
            try {
                // The claim ran unbound under admin_bypass; dispatch reads tenant-scoped rows and
                // sends as the tenant, so it needs the tenant bound.
                TenantContext.runWithTenant(escalation.tenantId(),
                        () -> dispatchService.dispatch(escalation));
            } catch (Exception e) {
                log.warn("Escalation {} for thread {} could not be dispatched: {}",
                        escalation.id(), escalation.threadId(), e.getMessage());
            }
        }
        return claimed.size();
    }

    /**
     * Seconds since this sweep last completed, or -1 if it never has.
     *
     * <p>Exposed because a silently dead gate is the failure this design cannot otherwise detect:
     * every counter reads zero both when nothing is breaching and when nothing is running.
     */
    public long secondsSinceLastRun() {
        return lastRunEpochSeconds == 0 ? -1 : (System.currentTimeMillis() / 1000) - lastRunEpochSeconds;
    }
}
