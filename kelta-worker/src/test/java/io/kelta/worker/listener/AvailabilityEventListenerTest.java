package io.kelta.worker.listener;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.service.availability.AlertDispatchService;
import io.kelta.worker.service.availability.AvailabilityEvent;
import io.kelta.worker.service.availability.AvailabilityMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AvailabilityEventListener Tests")
class AvailabilityEventListenerTest {

    private static final String SUBJECT = "kelta.availability.event.tenant-1.recgov";

    private AvailabilityMatchService matchService;
    private AlertDispatchService dispatchService;
    private PlatformEventPublisher eventPublisher;
    private AvailabilityEventListener listener;

    @BeforeEach
    void setUp() {
        matchService = mock(AvailabilityMatchService.class);
        dispatchService = mock(AlertDispatchService.class);
        eventPublisher = mock(PlatformEventPublisher.class);
        listener = new AvailabilityEventListener(matchService, dispatchService,
                eventPublisher, new ObjectMapper());

        when(matchService.process(anyString(), any())).thenReturn(List.of());
    }

    private static String body() {
        return """
                {"source":"recgov","targetExternalId":"site-1","slotKey":"2026-08-14",
                 "status":"OPEN","window":{"start":"2026-08-14T00:00:00Z",
                 "end":"2026-08-16T00:00:00Z"},"quantity":2,"meta":{"loop":"A"},
                 "polledAt":"2026-08-02T12:00:00Z"}
                """;
    }

    private static AvailabilityMatchService.ClaimedAlert claimed() {
        Watch watch = new Watch("w1", "tenant-1", "m1", "target-1", "{}", "[\"push\"]",
                Watch.STATUS_ACTIVE, null);
        WatchTarget target = new WatchTarget("target-1", "tenant-1", "recgov", "site-1",
                "Site A", null, "{}", true);
        return new AvailabilityMatchService.ClaimedAlert("alert-1", watch, target,
                "2026-08-14", null, null, null);
    }

    @Nested
    @DisplayName("Subject parsing")
    class SubjectParsing {

        @Test
        @DisplayName("extracts tenant and source from the subject")
        void extractsTenantAndSource() {
            assertThat(AvailabilityEventListener.tenantFromSubject(SUBJECT)).isEqualTo("tenant-1");
            assertThat(AvailabilityEventListener.sourceFromSubject(SUBJECT)).isEqualTo("recgov");
        }

        @Test
        @DisplayName("returns null for a malformed or truncated subject")
        void nullForMalformedSubject() {
            assertThat(AvailabilityEventListener.tenantFromSubject("kelta.availability.event"))
                    .isNull();
            assertThat(AvailabilityEventListener.tenantFromSubject(null)).isNull();
            assertThat(AvailabilityEventListener.sourceFromSubject(
                    "kelta.availability.event.tenant-1")).isNull();
        }

        @Test
        @DisplayName("drops an event whose subject carries no tenant")
        void dropsTenantlessSubject() {
            listener.handleAvailabilityEvent("kelta.availability.event", body());

            verifyNoInteractions(matchService, dispatchService);
        }
    }

    @Nested
    @DisplayName("Body parsing")
    class BodyParsing {

        @Test
        @DisplayName("parses the full poller contract")
        void parsesFullContract() {
            AvailabilityEvent event = listener.parse(body(), "recgov");

            assertThat(event).isNotNull();
            assertThat(event.source()).isEqualTo("recgov");
            assertThat(event.targetExternalId()).isEqualTo("site-1");
            assertThat(event.slotKey()).isEqualTo("2026-08-14");
            assertThat(event.isOpen()).isTrue();
            assertThat(event.windowStart()).isEqualTo(Instant.parse("2026-08-14T00:00:00Z"));
            assertThat(event.windowEnd()).isEqualTo(Instant.parse("2026-08-16T00:00:00Z"));
            assertThat(event.quantity()).isEqualTo(2);
            assertThat(event.meta()).containsEntry("loop", "A");
            assertThat(event.isUsable()).isTrue();
        }

        @Test
        @DisplayName("falls back to the subject's source when the body omits it")
        void fallsBackToSubjectSource() {
            AvailabilityEvent event = listener.parse(
                    "{\"targetExternalId\":\"site-1\",\"slotKey\":\"s\",\"status\":\"OPEN\"}",
                    "recgov");

            assertThat(event.source()).isEqualTo("recgov");
        }

        @Test
        @DisplayName("tolerates a missing window rather than dropping the event")
        void tolerapesMissingWindow() {
            AvailabilityEvent event = listener.parse(
                    "{\"targetExternalId\":\"site-1\",\"slotKey\":\"s\",\"status\":\"OPEN\"}",
                    "recgov");

            assertThat(event.windowStart()).isNull();
            // "This slot opened" is still actionable without a window.
            assertThat(event.isUsable()).isTrue();
        }

        @Test
        @DisplayName("ignores an unparseable timestamp instead of failing the event")
        void ignoresBadTimestamp() {
            AvailabilityEvent event = listener.parse(
                    "{\"targetExternalId\":\"s\",\"slotKey\":\"k\",\"status\":\"OPEN\","
                            + "\"polledAt\":\"yesterday\"}", "recgov");

            assertThat(event).isNotNull();
            assertThat(event.polledAt()).isNull();
        }

        @Test
        @DisplayName("returns null for a non-object or unparseable body")
        void nullForBadBody() {
            assertThat(listener.parse("{not json", "recgov")).isNull();
            assertThat(listener.parse("[1,2,3]", "recgov")).isNull();
        }
    }

    @Nested
    @DisplayName("Fanout and bridge")
    class FanoutAndBridge {

        @Test
        @DisplayName("dispatches every claimed alert under the subject's tenant")
        void dispatchesClaimedAlerts() {
            var first = claimed();
            when(matchService.process(anyString(), any())).thenReturn(List.of(first, first));

            listener.handleAvailabilityEvent(SUBJECT, body());

            ArgumentCaptor<AvailabilityMatchService.ClaimedAlert> dispatched =
                    ArgumentCaptor.forClass(AvailabilityMatchService.ClaimedAlert.class);
            verify(dispatchService, org.mockito.Mockito.times(2))
                    .dispatch(org.mockito.ArgumentMatchers.eq("tenant-1"), dispatched.capture());
            assertThat(dispatched.getAllValues())
                    .allMatch(a -> "alert-1".equals(a.alertId()));
        }

        @Test
        @DisplayName("publishes a compact summary to the flow-trigger namespace")
        void publishesTriggerBridge() {
            when(matchService.process(anyString(), any())).thenReturn(List.of(claimed()));

            listener.handleAvailabilityEvent(SUBJECT, body());

            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PlatformEvent<?>> event = ArgumentCaptor.forClass(PlatformEvent.class);
            verify(eventPublisher).publish(subject.capture(), event.capture());

            assertThat(subject.getValue()).isEqualTo("kelta.trigger.tenant-1.availability");
            // Ids and counts only — no member identities on the flow bus.
            assertThat(event.getValue().getPayload().toString())
                    .contains("targetId").contains("matchedWatches")
                    .doesNotContain("m1");
        }

        @Test
        @DisplayName("no claimed alerts means no dispatch and no bridge")
        void noAlertsNoBridge() {
            listener.handleAvailabilityEvent(SUBJECT, body());

            verifyNoInteractions(dispatchService);
            verify(eventPublisher, never()).publish(anyString(), any());
        }

        @Test
        @DisplayName("a poison message is swallowed so the subscription is not wedged")
        void poisonMessageSwallowed() {
            when(matchService.process(anyString(), any()))
                    .thenThrow(new IllegalStateException("boom"));

            // Must not propagate: an exception here would keep redelivering.
            listener.handleAvailabilityEvent(SUBJECT, body());
        }

        @Test
        @DisplayName("a bridge failure does not undo an alert that already went out")
        void bridgeFailureIsSwallowed() {
            when(matchService.process(anyString(), any())).thenReturn(List.of(claimed()));
            org.mockito.Mockito.doThrow(new IllegalStateException("nats down"))
                    .when(eventPublisher).publish(anyString(), any());

            listener.handleAvailabilityEvent(SUBJECT, body());

            verify(dispatchService).dispatch(anyString(), any());
        }
    }
}
