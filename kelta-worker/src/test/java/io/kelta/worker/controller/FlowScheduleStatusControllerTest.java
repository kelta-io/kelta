package io.kelta.worker.controller;

import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("FlowScheduleStatusController")
@WithTenant("t1")
class FlowScheduleStatusControllerTest {

    private static final MockHttpServletRequest REQUEST = AdminPermissionGuardFixture.request();

    private ScheduledJobRepository repository;
    private FlowScheduleStatusController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledJobRepository.class);
        controller = new FlowScheduleStatusController(repository,
                AdminPermissionGuardFixture.granting("MANAGE_WORKFLOWS"));
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("a caller holding only API_ACCESS is refused both reads, before any lookup")
        void apiAccessOnlyIsForbidden() {
            FlowScheduleStatusController portal = new FlowScheduleStatusController(repository,
                    AdminPermissionGuardFixture.apiAccessOnly());

            AdminPermissionGuardFixture.assertForbidden(() -> portal.getFlowSchedule("flow-1", REQUEST),
                    "MANAGE_WORKFLOWS or VIEW_SETUP");
            AdminPermissionGuardFixture.assertForbidden(() -> portal.getFlowRuns("flow-1", 20, REQUEST),
                    "MANAGE_WORKFLOWS or VIEW_SETUP");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a portal caller is refused both reads whatever its profile grants")
        void portalRefused() {
            FlowScheduleStatusController misconfigured = new FlowScheduleStatusController(repository,
                    AdminPermissionGuardFixture.granting("VIEW_SETUP"));
            MockHttpServletRequest portal = AdminPermissionGuardFixture.request();
            portal.addHeader("X-User-Type", "PORTAL");

            AdminPermissionGuardFixture.assertForbidden(() -> misconfigured.getFlowSchedule("flow-1", portal),
                    "MANAGE_WORKFLOWS or VIEW_SETUP");
            AdminPermissionGuardFixture.assertForbidden(() -> misconfigured.getFlowRuns("flow-1", 20, portal),
                    "MANAGE_WORKFLOWS or VIEW_SETUP");
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("VIEW_SETUP alone admits both reads")
        void viewSetupAdmits() {
            FlowScheduleStatusController viewer = new FlowScheduleStatusController(repository,
                    AdminPermissionGuardFixture.granting("VIEW_SETUP"));
            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("AUTOLAUNCHED"));
            when(repository.findScheduleByFlowId("flow-1", "t1")).thenReturn(Optional.empty());
            when(repository.findRecentRunsForFlow("flow-1", "t1", 20)).thenReturn(List.of());

            assertThat(viewer.getFlowSchedule("flow-1", REQUEST).getStatusCode().value()).isEqualTo(200);
            assertThat(viewer.getFlowRuns("flow-1", 20, REQUEST).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("getFlowSchedule")
    class GetFlowSchedule {

        @Test
        @DisplayName("Returns ACTIVE schedule for synced SCHEDULED flow")
        void returnsActiveForSyncedScheduledFlow() {
            Instant now = Instant.parse("2026-06-12T10:00:00Z");
            Instant next = Instant.parse("2026-06-12T11:00:00Z");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "job-1");
            row.put("tenant_id", "t1");
            row.put("name", "flow-1");
            row.put("cron_expression", "0 0 * * * *");
            row.put("timezone", "UTC");
            row.put("active", true);
            row.put("last_run_at", Timestamp.from(now));
            row.put("last_status", "SUCCESS");
            row.put("next_run_at", Timestamp.from(next));

            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("SCHEDULED"));
            when(repository.findScheduleByFlowId("flow-1", "t1")).thenReturn(Optional.of(row));

            var response = controller.getFlowSchedule("flow-1", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("flowId", "flow-1");
            assertThat(body).containsEntry("flowType", "SCHEDULED");
            assertThat(body).containsEntry("cron", "0 0 * * * *");
            assertThat(body).containsEntry("timezone", "UTC");
            assertThat(body).containsEntry("active", true);
            assertThat(body).containsEntry("lastStatus", "SUCCESS");
            assertThat(body).containsEntry("lastRunAt", now.toString());
            assertThat(body).containsEntry("nextRunAt", next.toString());
            assertThat(body).containsEntry("scheduleStatus", "ACTIVE");
            assertThat(body).doesNotContainKey("reason");
        }

        @Test
        @DisplayName("Returns PAUSED when active=false")
        void returnsPausedWhenInactive() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cron_expression", "0 0 * * * *");
            row.put("timezone", "UTC");
            row.put("active", false);
            row.put("last_run_at", null);
            row.put("last_status", null);
            row.put("next_run_at", null);

            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("SCHEDULED"));
            when(repository.findScheduleByFlowId("flow-1", "t1")).thenReturn(Optional.of(row));

            var response = controller.getFlowSchedule("flow-1", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("scheduleStatus", "PAUSED");
            assertThat(response.getBody()).containsEntry("active", false);
        }

        @Test
        @DisplayName("Returns UNSYNCED with reason when SCHEDULED flow has no scheduled_job row")
        void returnsUnsyncedWhenNoRow() {
            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("SCHEDULED"));
            when(repository.findScheduleByFlowId("flow-1", "t1")).thenReturn(Optional.empty());

            var response = controller.getFlowSchedule("flow-1", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("flowId", "flow-1");
            assertThat(body).containsEntry("flowType", "SCHEDULED");
            assertThat(body).containsEntry("scheduleStatus", "UNSYNCED");
            assertThat(body).containsEntry("reason", "No scheduled_job row registered for this flow");
            assertThat(body).doesNotContainKey("cron");
        }

        @Test
        @DisplayName("Returns UNSYNCED when active job has null next_run_at")
        void returnsUnsyncedWhenNextRunNull() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cron_expression", "0 0 * * * *");
            row.put("timezone", "UTC");
            row.put("active", true);
            row.put("last_run_at", null);
            row.put("last_status", null);
            row.put("next_run_at", null);

            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("SCHEDULED"));
            when(repository.findScheduleByFlowId("flow-1", "t1")).thenReturn(Optional.of(row));

            var response = controller.getFlowSchedule("flow-1", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Map<String, Object> body = response.getBody();
            assertThat(body).containsEntry("scheduleStatus", "UNSYNCED");
            assertThat((String) body.get("reason")).contains("next_run_at");
        }

        @Test
        @DisplayName("Returns NONE for non-SCHEDULED flow with no scheduled_job row")
        void returnsNoneForNonScheduledFlow() {
            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("AUTOLAUNCHED"));
            when(repository.findScheduleByFlowId("flow-1", "t1")).thenReturn(Optional.empty());

            var response = controller.getFlowSchedule("flow-1", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Map<String, Object> body = response.getBody();
            assertThat(body).containsEntry("flowType", "AUTOLAUNCHED");
            assertThat(body).containsEntry("scheduleStatus", "NONE");
            assertThat(body).doesNotContainKey("reason");
        }

        @Test
        @DisplayName("Returns 404 when flow does not exist for tenant")
        void returns404WhenFlowMissing() {
            when(repository.findFlowType("flow-other", "t1")).thenReturn(Optional.empty());

            var response = controller.getFlowSchedule("flow-other", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("Returns 400 when no tenant context")
        @WithTenant(none = true)
        void returns400WithoutTenant() {
            var response = controller.getFlowSchedule("flow-1", REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("getFlowRuns")
    class GetFlowRuns {

        @Test
        @DisplayName("Returns recent runs newest first")
        void returnsRecentRuns() {
            Instant t1 = Instant.parse("2026-06-12T09:00:00Z");
            Instant t2 = Instant.parse("2026-06-12T09:01:00Z");

            Map<String, Object> r1 = new LinkedHashMap<>();
            r1.put("id", "run-1");
            r1.put("job_id", "job-1");
            r1.put("status", "SUCCESS");
            r1.put("started_at", Timestamp.from(t1));
            r1.put("completed_at", Timestamp.from(t2));
            r1.put("duration_ms", 60000);
            r1.put("error_message", null);

            Map<String, Object> r2 = new LinkedHashMap<>();
            r2.put("id", "run-2");
            r2.put("job_id", "job-1");
            r2.put("status", "FAILED");
            r2.put("started_at", Timestamp.from(t1));
            r2.put("completed_at", Timestamp.from(t2));
            r2.put("duration_ms", 500);
            r2.put("error_message", "boom");

            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("SCHEDULED"));
            when(repository.findRecentRunsForFlow("flow-1", "t1", 20)).thenReturn(List.of(r1, r2));

            var response = controller.getFlowRuns("flow-1", 20, REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body).containsEntry("flowId", "flow-1");
            assertThat(body).containsEntry("count", 2);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) body.get("runs");
            assertThat(runs).hasSize(2);
            assertThat(runs.get(0)).containsEntry("id", "run-1");
            assertThat(runs.get(0)).containsEntry("status", "SUCCESS");
            assertThat(runs.get(0)).containsEntry("durationMs", 60000);
            assertThat(runs.get(0)).containsEntry("startedAt", t1.toString());
            assertThat(runs.get(0)).containsEntry("completedAt", t2.toString());
            assertThat(runs.get(1)).containsEntry("status", "FAILED");
            assertThat(runs.get(1)).containsEntry("errorMessage", "boom");
        }

        @Test
        @DisplayName("Returns empty list when flow has no run history")
        void returnsEmptyListWhenNoRuns() {
            when(repository.findFlowType("flow-1", "t1")).thenReturn(Optional.of("SCHEDULED"));
            when(repository.findRecentRunsForFlow("flow-1", "t1", 20)).thenReturn(List.of());

            var response = controller.getFlowRuns("flow-1", 20, REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("count", 0);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) response.getBody().get("runs");
            assertThat(runs).isEmpty();
        }

        @Test
        @DisplayName("Returns 404 when flow does not exist for tenant")
        void returns404WhenFlowMissing() {
            when(repository.findFlowType("flow-other", "t1")).thenReturn(Optional.empty());
            var response = controller.getFlowRuns("flow-other", 20, REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("Returns 400 when no tenant context")
        @WithTenant(none = true)
        void returns400WithoutTenant() {
            var response = controller.getFlowRuns("flow-1", 20, REQUEST);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }
}
