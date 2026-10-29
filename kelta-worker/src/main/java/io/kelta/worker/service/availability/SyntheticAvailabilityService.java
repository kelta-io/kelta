package io.kelta.worker.service.availability;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.listener.AvailabilityEventListener;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Injects a synthetic availability observation, so an end-to-end test or a canary exercises the
 * real match → dispatch → delivery path without any provider poll.
 *
 * <p>Only a {@code synthetic} watch target can be driven this way, and only this path can drive
 * one: pollers never list synthetic targets, and the matcher drops a poller's report on one.
 *
 * <p>The observation runs through {@link AvailabilityEventListener#process} — the exact code the
 * NATS listener runs — so the alerts, delivery rows, metrics and the flow-trigger bridge are the
 * production ones.
 *
 * <p><b>Episodes.</b> Alerts are deduped per {@code (watch, slot, episode)}, and a new episode is
 * minted only on a CLOSED→OPEN edge. With no {@code slotKey}, each injection uses a fresh
 * {@value #SLOT_PREFIX}&lt;uuid&gt; slot, so every injection is a first sighting that alerts again
 * (and no per-slot suppression window applies). With an explicit {@code slotKey}, the slot follows
 * the normal rules: re-injecting while it is still OPEN keeps its episode and does not re-alert.
 */
@Service
public class SyntheticAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(SyntheticAvailabilityService.class);

    /** Prefix of the fresh slot key minted when the caller names none. */
    public static final String SLOT_PREFIX = "synthetic-";

    /** Outcome of an injection attempt. */
    public enum Status { INJECTED, TARGET_NOT_FOUND, TARGET_NOT_SYNTHETIC }

    /**
     * @param alerts alerts newly claimed and dispatched — empty when every matching watch was
     *               deduped, nothing matched, or the injection was refused
     */
    public record Injection(Status status, WatchTarget target, String slotKey,
                            List<AvailabilityMatchService.ClaimedAlert> alerts) {
        static Injection refused(Status status, WatchTarget target) {
            return new Injection(status, target, null, List.of());
        }
    }

    private final WatchTargetRepository targetRepository;
    private final AvailabilityEventListener availabilityEventListener;

    public SyntheticAvailabilityService(WatchTargetRepository targetRepository,
                                        AvailabilityEventListener availabilityEventListener) {
        this.targetRepository = targetRepository;
        this.availabilityEventListener = availabilityEventListener;
    }

    /**
     * Records one OPEN observation for a synthetic target and delivers whatever it alerts.
     *
     * @param slotKey optional; blank mints a fresh {@value #SLOT_PREFIX}&lt;uuid&gt; slot
     */
    public Injection inject(String tenantId, String targetId, String slotKey,
                            Instant windowStart, Instant windowEnd, Integer quantity) {
        Optional<WatchTarget> found = TenantContext.callWithTenant(tenantId,
                () -> targetRepository.findById(tenantId, targetId));
        if (found.isEmpty()) {
            return Injection.refused(Status.TARGET_NOT_FOUND, null);
        }
        WatchTarget target = found.get();
        if (!target.synthetic()) {
            return Injection.refused(Status.TARGET_NOT_SYNTHETIC, target);
        }

        String slot = slotKey == null || slotKey.isBlank()
                ? SLOT_PREFIX + UUID.randomUUID()
                : slotKey;
        AvailabilityEvent event = new AvailabilityEvent(target.source(), target.externalId(),
                slot, AvailabilityEvent.STATUS_OPEN, windowStart, windowEnd, quantity,
                Map.of("synthetic", true), Instant.now());

        List<AvailabilityMatchService.ClaimedAlert> alerts = availabilityEventListener.process(
                tenantId, event, AvailabilityMatchService.Origin.INJECTOR);
        log.info("Synthetic OPEN on target {} slot {} in tenant {} claimed {} alert(s)",
                target.id(), slot, tenantId, alerts.size());
        return new Injection(Status.INJECTED, target, slot, alerts);
    }
}
