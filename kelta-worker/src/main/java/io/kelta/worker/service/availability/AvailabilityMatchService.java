package io.kelta.worker.service.availability;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.AlertRepository;
import io.kelta.worker.repository.AvailabilityStateRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns an availability observation into alerts.
 *
 * <p>The pipeline, in the order it short-circuits:
 * <ol>
 *   <li><b>Resolve the target.</b> An unknown {@code (source, externalId)} is
 *       dropped quietly — a poller may legitimately cover targets no tenant has
 *       registered, and that is not an error.</li>
 *   <li><b>Record the observation and read the transition.</b> If the slot did
 *       not just open, stop. <b>This is the volume killer</b>: the overwhelming
 *       majority of polls report no change, and everything below it is skipped
 *       for them.</li>
 *   <li><b>Match live watches</b> on the target (one indexed query), then filter
 *       on criteria in Java.</li>
 *   <li><b>Claim an alert</b> per surviving watch — the insert is the dedupe.</li>
 * </ol>
 *
 * <p><b>Sending is not done here.</b> This service decides *who* should be
 * alerted and records that decision durably; {@link AlertDispatchService} does
 * the I/O afterwards, outside any transaction, so a provider timeout can never
 * roll back the dedupe row and cause a duplicate alert on redelivery.
 *
 * <p>Runs off a NATS queue-group subscription with no ambient tenant, so every
 * repository call passes {@code tenantId} explicitly and DB reads are wrapped in
 * an explicit tenant scope for RLS.
 */
@Service
public class AvailabilityMatchService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityMatchService.class);

    private final WatchTargetRepository targetRepository;
    private final WatchRepository watchRepository;
    private final AvailabilityStateRepository stateRepository;
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration suppressionWindow;

    public AvailabilityMatchService(
            WatchTargetRepository targetRepository,
            WatchRepository watchRepository,
            AvailabilityStateRepository stateRepository,
            AlertRepository alertRepository,
            ObjectMapper objectMapper,
            @Value("${kelta.availability.enabled:true}") boolean enabled,
            @Value("${kelta.availability.suppression-minutes:30}") int suppressionMinutes) {
        this.targetRepository = targetRepository;
        this.watchRepository = watchRepository;
        this.stateRepository = stateRepository;
        this.alertRepository = alertRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.suppressionWindow = Duration.ofMinutes(Math.max(0, suppressionMinutes));
    }

    /**
     * An alert that was claimed and is owed a notification. {@code createdAt} is the alert row's
     * {@code created_at} — the detection time delivery latency is measured from.
     */
    public record ClaimedAlert(String alertId, Watch watch, WatchTarget target,
                               String slotKey, Instant windowStart, Instant windowEnd,
                               Instant createdAt) {
    }

    /**
     * Processes one observation and returns the alerts newly claimed by it —
     * empty for the common "nothing changed" case.
     */
    public List<ClaimedAlert> process(String tenantId, AvailabilityEvent event) {
        if (!enabled || tenantId == null || tenantId.isBlank() || event == null) {
            return List.of();
        }
        if (!event.isUsable()) {
            log.debug("Dropping unusable availability event for tenant {}: {}", tenantId, event);
            return List.of();
        }

        return TenantContext.callWithTenant(tenantId, () -> {
            Optional<WatchTarget> target = targetRepository
                    .findBySourceAndExternalId(tenantId, event.source(), event.targetExternalId());
            if (target.isEmpty()) {
                // Expected: pollers cover more than any one tenant registers.
                log.debug("No target for {}/{} in tenant {} — dropping",
                        event.source(), event.targetExternalId(), tenantId);
                return List.of();
            }
            WatchTarget resolved = target.get();

            AvailabilityStateRepository.Transition transition = stateRepository.record(
                    tenantId, resolved.id(), event.slotKey(),
                    event.isOpen() ? AvailabilityEvent.STATUS_OPEN : AvailabilityEvent.STATUS_CLOSED,
                    event.windowStart(), event.windowEnd(), event.quantity(), event.polledAt());

            if (!transition.isAlertable()) {
                // Either a close, or the slot was already open. Most polls land
                // here, which is exactly why this check precedes the watch query.
                return List.of();
            }

            log.info("Slot {} on target {} ({}) opened — episode {}",
                    event.slotKey(), resolved.name(), resolved.id(), transition.episodeId());
            return claimAlerts(tenantId, resolved, event, transition.episodeId());
        });
    }

    private List<ClaimedAlert> claimAlerts(String tenantId, WatchTarget target,
                                           AvailabilityEvent event, String episodeId) {
        Instant now = Instant.now();
        List<Watch> candidates = watchRepository.findLiveForTarget(tenantId, target.id(), now);
        if (candidates.isEmpty()) {
            return List.of();
        }

        LocalDate slotStart = toLocalDate(event.windowStart());
        LocalDate slotEnd = toLocalDate(event.windowEnd());

        List<ClaimedAlert> claimed = new ArrayList<>();
        for (Watch watch : candidates) {
            if (!watch.isLive(now)) {
                continue; // defence in depth; the query already filtered
            }
            if (!matches(watch, slotStart, slotEnd, event.quantity())) {
                continue;
            }
            // Guard against a slot flapping open/closed: distinct episodes are
            // legitimately alertable, but not within minutes of each other.
            if (!suppressionWindow.isZero() && alertRepository.alertedRecently(
                    tenantId, watch.id(), event.slotKey(), suppressionWindow, now)) {
                log.debug("Suppressing alert for watch {} slot {} — alerted within {}",
                        watch.id(), event.slotKey(), suppressionWindow);
                continue;
            }

            alertRepository.claim(tenantId, watch.id(), target.id(), event.slotKey(), episodeId,
                            event.windowStart(), event.windowEnd())
                    .ifPresent(claim -> claimed.add(new ClaimedAlert(
                            claim.alertId(), watch, target, event.slotKey(),
                            event.windowStart(), event.windowEnd(), claim.createdAt())));
        }
        return claimed;
    }

    /**
     * Evaluates a watch's criteria against the slot.
     *
     * <p>Deliberately in Java rather than pushed into SQL: criteria is opaque
     * member-authored JSONB, and coupling the query plan to a shape members can
     * change is how a schema tweak silently stops matching. The candidate set is
     * already bounded by the indexed target+status query.
     *
     * <p>Unparseable criteria matches everything rather than nothing — a member
     * receiving a slightly-too-broad alert is recoverable; silently never
     * alerting them is not.
     */
    private boolean matches(Watch watch, LocalDate slotStart, LocalDate slotEnd, Integer quantity) {
        WatchCriteria.ParseResult parsed = WatchCriteria.parse(watch.criteria(), objectMapper);
        if (!parsed.isValid()) {
            log.warn("Watch {} has invalid criteria {} — alerting anyway rather than "
                    + "silently dropping the member", watch.id(), parsed.errors());
            return true;
        }
        WatchCriteria criteria = parsed.criteria();
        return criteria.overlaps(slotStart, slotEnd) && criteria.satisfiesQuantity(quantity);
    }

    /**
     * Slot windows are compared as calendar dates: a member watching "August
     * 14–16" means those days at the target, and comparing instants would shift
     * the window by a timezone offset. UTC is the reference because that is what
     * pollers report in.
     */
    private static LocalDate toLocalDate(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
