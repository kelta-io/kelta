package io.kelta.worker.controller;

import io.kelta.worker.repository.ScheduledJobRepository;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ScheduledJobActionsController")
@WithTenant("t1")
class ScheduledJobActionsControllerTest {

    private ScheduledJobRepository repository;
    private ScheduledJobActionsController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledJobRepository.class);
        controller = new ScheduledJobActionsController(repository);
    }

    @Nested
    @DisplayName("pause")
    class Pause {

        @Test
        @DisplayName("Should pause job for current tenant")
        void shouldPauseJob() {
            when(repository.pause("job-1", "t1")).thenReturn(1);

            var response = controller.pause("job-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "paused");
            verify(repository).pause("job-1", "t1");
        }

        @Test
        @DisplayName("Should return 404 for other tenant's job")
        void shouldReturn404ForOtherTenant() {
            when(repository.pause("job-other", "t1")).thenReturn(0);

            var response = controller.pause("job-other");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("resume")
    class Resume {

        @Test
        @DisplayName("Should resume job and calculate next run from NOW")
        void shouldResumeJob() {
            when(repository.findByIdAndTenant("job-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "job-1", "cron_expression", "0 0 9 * * *", "timezone", "UTC", "active", false)));
            when(repository.resume(eq("job-1"), eq("t1"), any())).thenReturn(1);

            var response = controller.resume("job-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "resumed");
            verify(repository).resume(eq("job-1"), eq("t1"), any());
        }

        @Test
        @DisplayName("Should return 404 for other tenant's job")
        void shouldReturn404ForOtherTenant() {
            when(repository.findByIdAndTenant("job-other", "t1")).thenReturn(Optional.empty());

            var response = controller.resume("job-other");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("validateCron")
    class ValidateCron {

        @Test
        @DisplayName("Should validate correct cron expression")
        void shouldValidateCorrectCron() {
            var response = controller.validateCron(Map.of("expression", "0 0 9 * * *"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("valid", true);
        }

        @Test
        @DisplayName("Should reject invalid cron expression")
        void shouldRejectInvalidCron() {
            var response = controller.validateCron(Map.of("expression", "not-a-cron"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("Should reject invalid timezone")
        void shouldRejectInvalidTimezone() {
            var response = controller.validateCron(Map.of("expression", "0 0 9 * * *", "timezone", "Invalid/Zone"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().get("error").toString()).contains("Invalid timezone");
        }

        @Test
        @DisplayName("Should reject empty cron expression")
        void shouldRejectEmptyCron() {
            var response = controller.validateCron(Map.of("expression", ""));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }
}
