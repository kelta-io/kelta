package io.kelta.worker.service.telehealth;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.event.VideoSessionPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("LiveKitWebhookService")
class LiveKitWebhookServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PlatformEventPublisher eventPublisher;
    private ArchiveService archiveService;
    private LiveKitWebhookService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        eventPublisher = mock(PlatformEventPublisher.class);
        archiveService = mock(ArchiveService.class);
        service = new LiveKitWebhookService(jdbcTemplate, eventPublisher, archiveService);
        // Idempotency claim succeeds by default. The claim carries the tenant the room
        // resolved to, so it is taken after the session lookup, not before it.
        when(jdbcTemplate.update(contains("livekit_webhook_event"), anyString(), anyString(), anyString()))
                .thenReturn(1);
    }

    private void stubSession() {
        when(jdbcTemplate.queryForList(contains("FROM video_session"), eq("t_t1_room")))
                .thenReturn(List.of(Map.of(
                        "id", "vs-1", "tenant_id", "t1",
                        "appointment_id", "appt-1")));
    }

    @Test
    @DisplayName("room_started marks the session ACTIVE and publishes lifecycle + trigger subjects")
    void roomStarted() {
        stubSession();
        service.process("{\"event\":\"room_started\",\"id\":\"EV_1\",\"room\":{\"name\":\"t_t1_room\"}}");

        verify(jdbcTemplate).update(contains("SET status = 'ACTIVE'"), eq("vs-1"));
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PlatformEvent> event = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventPublisher, times(2)).publish(subject.capture(), event.capture());
        assertThat(subject.getAllValues()).containsExactly(
                "kelta.video.session.t1.vs-1",
                "kelta.trigger.t1.video.session");
        assertThat(((VideoSessionPayload) event.getAllValues().get(0).getPayload()).getStatus())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("room_finished ends the session with a computed duration")
    void roomFinished() {
        stubSession();
        when(jdbcTemplate.queryForObject(contains("SET status = 'ENDED'"), eq(Integer.class),
                eq("vs-1"))).thenReturn(1234);

        service.process("{\"event\":\"room_finished\",\"id\":\"EV_2\",\"room\":{\"name\":\"t_t1_room\"}}");

        ArgumentCaptor<PlatformEvent> event = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventPublisher, times(2)).publish(anyString(), event.capture());
        VideoSessionPayload payload = (VideoSessionPayload) event.getAllValues().get(0).getPayload();
        assertThat(payload.getStatus()).isEqualTo("ENDED");
        assertThat(payload.getDurationSeconds()).isEqualTo(1234);
        // The ended session is auto-archived (telehealth slice 7).
        verify(archiveService).archiveVideoSession("t1", "vs-1");
    }

    @Test
    @DisplayName("lifecycle events bridge onto kelta.trigger.<tenant>.video.session for NATS_TRIGGERED flows")
    void bridgesToFlowTriggerNamespace() {
        stubSession();
        when(jdbcTemplate.queryForObject(contains("SET status = 'ENDED'"), eq(Integer.class),
                eq("vs-1"))).thenReturn(60);

        service.process("{\"event\":\"room_finished\",\"id\":\"EV_6\",\"room\":{\"name\":\"t_t1_room\"}}");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PlatformEvent> event = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventPublisher, times(2)).publish(subject.capture(), event.capture());
        assertThat(subject.getAllValues().get(1)).isEqualTo("kelta.trigger.t1.video.session");
        // Same envelope on both subjects — flows read $.input.payload.*.
        VideoSessionPayload bridged = (VideoSessionPayload) event.getAllValues().get(1).getPayload();
        assertThat(bridged.getStatus()).isEqualTo("ENDED");
        assertThat(bridged.getAppointmentId()).isEqualTo("appt-1");
    }

    @Test
    @DisplayName("a failing auto-archive never breaks the webhook (best-effort)")
    void archiveFailureDoesNotBreakWebhook() {
        stubSession();
        when(jdbcTemplate.queryForObject(contains("SET status = 'ENDED'"), eq(Integer.class),
                eq("vs-1"))).thenReturn(42);
        when(archiveService.archiveVideoSession("t1", "vs-1"))
                .thenThrow(new RuntimeException("s3 down"));

        // Must not throw.
        service.process("{\"event\":\"room_finished\",\"id\":\"EV_5\",\"room\":{\"name\":\"t_t1_room\"}}");

        verify(archiveService).archiveVideoSession("t1", "vs-1");
    }

    @Test
    @DisplayName("duplicate event ids are claimed once and skipped after")
    void idempotent() {
        stubSession();
        when(jdbcTemplate.update(contains("livekit_webhook_event"), eq("EV_DUP"), anyString(), anyString()))
                .thenReturn(0);

        service.process("{\"event\":\"room_started\",\"id\":\"EV_DUP\",\"room\":{\"name\":\"t_t1_room\"}}");

        verify(jdbcTemplate, never()).update(contains("SET status = 'ACTIVE'"), anyString());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("the idempotency claim records the tenant its room belongs to")
    void claimCarriesTheTenant() {
        stubSession();
        service.process("{\"event\":\"room_started\",\"id\":\"EV_T\",\"room\":{\"name\":\"t_t1_room\"}}");

        verify(jdbcTemplate).update(
                contains("INSERT INTO livekit_webhook_event (event_id, event_type, tenant_id, processed_at)"),
                eq("EV_T"), eq("room_started"), eq("t1"));
    }

    @Test
    @DisplayName("egress_ended stores the recording key without a lifecycle publish")
    void egressEnded() {
        stubSession();
        service.process("""
                {"event":"egress_ended","id":"EV_3",
                 "egressInfo":{"roomName":"t_t1_room",
                   "fileResults":[{"filename":"rec.mp4","location":"s3://bucket/t1/rec.mp4"}]}}
                """);

        verify(jdbcTemplate).update(contains("SET recording_key = ?"),
                eq("s3://bucket/t1/rec.mp4"), eq("vs-1"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("unknown rooms and unparseable bodies are ignored safely")
    void ignoresUnknown() {
        when(jdbcTemplate.queryForList(contains("FROM video_session"), eq("ghost-room")))
                .thenReturn(List.of());
        service.process("{\"event\":\"room_started\",\"id\":\"EV_4\",\"room\":{\"name\":\"ghost-room\"}}");
        service.process("not json");
        verifyNoInteractions(eventPublisher);
    }
}
