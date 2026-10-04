package io.kelta.worker.service.availability;

import io.kelta.worker.repository.AlertRepository;
import io.kelta.worker.repository.AvailabilityStateRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AvailabilityMatchService Tests")
class AvailabilityMatchServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String TARGET_ID = "target-1";
    private static final Instant CLAIMED_AT = Instant.parse("2026-08-02T12:00:00Z");

    private WatchTargetRepository targetRepository;
    private WatchRepository watchRepository;
    private AvailabilityStateRepository stateRepository;
    private AlertRepository alertRepository;
    private AvailabilityMatchService service;

    @BeforeEach
    void setUp() {
        targetRepository = mock(WatchTargetRepository.class);
        watchRepository = mock(WatchRepository.class);
        stateRepository = mock(AvailabilityStateRepository.class);
        alertRepository = mock(AlertRepository.class);
        service = newService(true, 30);

        when(targetRepository.findBySourceAndExternalId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(target()));
        when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(alertRepository.alertedRecently(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false);
        when(alertRepository.claim(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(Optional.of(claim("alert-1")));
    }

    private static AlertRepository.Claim claim(String alertId) {
        return new AlertRepository.Claim(alertId, CLAIMED_AT);
    }

    private AvailabilityMatchService newService(boolean enabled, int suppressionMinutes) {
        return new AvailabilityMatchService(targetRepository, watchRepository, stateRepository,
                alertRepository, new ObjectMapper(), enabled, suppressionMinutes);
    }

    private static WatchTarget target() {
        return new WatchTarget(TARGET_ID, TENANT, "recgov", "site-1", "Site A",
                "campground", "{}", true);
    }

    private static Watch watch(String id, String criteria) {
        return new Watch(id, TENANT, "member-" + id, TARGET_ID, criteria,
                "[\"push\"]", Watch.STATUS_ACTIVE, null);
    }

    private static AvailabilityEvent event(String status, LocalDate day, Integer quantity) {
        Instant start = day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
        return new AvailabilityEvent("recgov", "site-1",
                day == null ? "slot-1" : day.toString(), status,
                start, start, quantity, Map.of(), Instant.now());
    }

    private void withTransition(boolean opened) {
        when(stateRepository.record(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any()))
                .thenReturn(new AvailabilityStateRepository.Transition(
                        opened, opened ? "episode-1" : null, opened ? "OPEN" : "CLOSED"));
    }

    @Nested
    @DisplayName("Short-circuits")
    class ShortCircuits {

        @Test
        @DisplayName("disabled service does no work at all")
        void disabledDoesNothing() {
            assertThat(newService(false, 30).process(TENANT, event("OPEN", null, null))).isEmpty();
            verifyNoInteractions(targetRepository, stateRepository, watchRepository, alertRepository);
        }

        @Test
        @DisplayName("an unusable event is dropped before touching the database")
        void unusableEventDropped() {
            AvailabilityEvent noSlot = new AvailabilityEvent("recgov", "site-1", null,
                    "OPEN", null, null, null, Map.of(), Instant.now());

            assertThat(service.process(TENANT, noSlot)).isEmpty();
            verifyNoInteractions(targetRepository, stateRepository);
        }

        @Test
        @DisplayName("an unknown target is dropped quietly — pollers cover more than we register")
        void unknownTargetDropped() {
            when(targetRepository.findBySourceAndExternalId(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            assertThat(service.process(TENANT, event("OPEN", null, null))).isEmpty();
            verifyNoInteractions(stateRepository, watchRepository);
        }

        @Test
        @DisplayName("no transition stops before the watch query — the volume killer")
        void noTransitionStopsEarly() {
            // Most polls report an already-open or still-closed slot. Everything
            // downstream must be skipped for them.
            withTransition(false);

            assertThat(service.process(TENANT, event("OPEN", null, null))).isEmpty();
            verify(watchRepository, never()).findLiveForTarget(anyString(), anyString(), any());
            verifyNoInteractions(alertRepository);
        }

        @Test
        @DisplayName("a transition with no watches claims nothing")
        void noWatchesClaimsNothing() {
            withTransition(true);

            assertThat(service.process(TENANT, event("OPEN", null, null))).isEmpty();
            verifyNoInteractions(alertRepository);
        }
    }

    @Nested
    @DisplayName("Matching")
    class Matching {

        @Test
        @DisplayName("claims an alert for a watch whose criteria match")
        void claimsMatchingWatch() {
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1",
                            "{\"dateStart\":\"2026-08-14\",\"dateEnd\":\"2026-08-16\"}")));

            List<AvailabilityMatchService.ClaimedAlert> claimed =
                    service.process(TENANT, event("OPEN", LocalDate.of(2026, 8, 15), null));

            assertThat(claimed).hasSize(1);
            assertThat(claimed.get(0).alertId()).isEqualTo("alert-1");
            assertThat(claimed.get(0).createdAt())
                    .as("the claim's created_at is the detection time latency is measured from")
                    .isEqualTo(CLAIMED_AT);
            verify(alertRepository).claim(eq(TENANT), eq("w1"), eq(TARGET_ID),
                    eq("2026-08-15"), eq("episode-1"), any(), any());
        }

        @Test
        @DisplayName("skips a watch whose date range excludes the slot")
        void skipsNonMatchingDates() {
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1",
                            "{\"dateStart\":\"2026-08-14\",\"dateEnd\":\"2026-08-16\"}")));

            assertThat(service.process(TENANT, event("OPEN", LocalDate.of(2026, 9, 1), null)))
                    .isEmpty();
            verify(alertRepository, never()).claim(anyString(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("skips a watch needing more units than are available")
        void skipsInsufficientQuantity() {
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{\"quantity\":4}")));

            assertThat(service.process(TENANT, event("OPEN", null, 2))).isEmpty();
        }

        @Test
        @DisplayName("alerts every matching member on the same target")
        void alertsAllMatchingMembers() {
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{}"), watch("w2", "{}")));
            when(alertRepository.claim(anyString(), eq("w1"), anyString(), anyString(),
                    anyString(), any(), any())).thenReturn(Optional.of(claim("alert-1")));
            when(alertRepository.claim(anyString(), eq("w2"), anyString(), anyString(),
                    anyString(), any(), any())).thenReturn(Optional.of(claim("alert-2")));

            assertThat(service.process(TENANT, event("OPEN", null, null))).hasSize(2);
        }

        @Test
        @DisplayName("invalid criteria alerts rather than silently dropping the member")
        void invalidCriteriaStillAlerts() {
            // A member who mis-saved criteria should get a slightly-too-broad
            // alert, not silence.
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{not json")));

            assertThat(service.process(TENANT, event("OPEN", LocalDate.of(2026, 8, 15), null)))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("Dedupe and suppression")
    class DedupeAndSuppression {

        @Test
        @DisplayName("a lost dedupe claim yields no alert — another pod already sent it")
        void lostClaimYieldsNothing() {
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{}")));
            when(alertRepository.claim(anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(), any())).thenReturn(Optional.empty());

            assertThat(service.process(TENANT, event("OPEN", null, null))).isEmpty();
        }

        @Test
        @DisplayName("suppresses a watch alerted about this slot recently")
        void suppressesRecentlyAlerted() {
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{}")));
            when(alertRepository.alertedRecently(anyString(), anyString(), anyString(),
                    any(), any())).thenReturn(true);

            assertThat(service.process(TENANT, event("OPEN", null, null))).isEmpty();
            verify(alertRepository, never()).claim(anyString(), anyString(), anyString(),
                    anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("passes the configured suppression window through")
        void passesSuppressionWindow() {
            AvailabilityMatchService tenMinutes = newService(true, 10);
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{}")));

            tenMinutes.process(TENANT, event("OPEN", null, null));

            verify(alertRepository).alertedRecently(anyString(), anyString(), anyString(),
                    eq(Duration.ofMinutes(10)), any());
        }

        @Test
        @DisplayName("a zero window disables suppression entirely")
        void zeroWindowDisablesSuppression() {
            AvailabilityMatchService noSuppression = newService(true, 0);
            withTransition(true);
            when(watchRepository.findLiveForTarget(anyString(), anyString(), any()))
                    .thenReturn(List.of(watch("w1", "{}")));

            assertThat(noSuppression.process(TENANT, event("OPEN", null, null))).hasSize(1);
            verify(alertRepository, never()).alertedRecently(anyString(), anyString(),
                    anyString(), any(), any());
        }
    }
}
