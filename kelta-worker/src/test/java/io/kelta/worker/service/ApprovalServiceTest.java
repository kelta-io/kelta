package io.kelta.worker.service;

import io.kelta.worker.repository.ApprovalRepository;
import io.kelta.worker.util.WithTenant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ApprovalService")
@WithTenant("t1")
class ApprovalServiceTest {

    private ApprovalRepository approvalRepository;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        approvalRepository = mock(ApprovalRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = JsonMapper.builder().build();
        service = new ApprovalService(approvalRepository, jdbcTemplate, objectMapper);
    }

    @Nested
    @DisplayName("submitForApproval")
    class SubmitForApproval {

        @Test
        @DisplayName("Should submit record for approval successfully")
        void shouldSubmitSuccessfully() {
            when(approvalRepository.findPendingInstanceForRecord("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.empty());
            when(approvalRepository.findActiveProcessesForCollection("col-1", "t1"))
                    .thenReturn(List.of(Map.of(
                            "id", "proc-1",
                            "on_submit_field_updates", "[]",
                            "execution_order", 0
                    )));
            when(approvalRepository.findStepsByProcessId("proc-1"))
                    .thenReturn(List.of(Map.of(
                            "id", "step-1",
                            "step_number", 1,
                            "approver_type", "USER",
                            "approver_id", "approver-1"
                    )));
            when(approvalRepository.createInstance("t1", "proc-1", "col-1", "rec-1", "user-1"))
                    .thenReturn("inst-1");

            var result = service.submitForApproval("col-1", "rec-1", "user-1", null);

            assertThat(result.success()).isTrue();
            assertThat(result.instanceId()).isEqualTo("inst-1");
            assertThat(result.status()).isEqualTo("PENDING");
            verify(approvalRepository).createStepInstance("inst-1", "step-1", "approver-1");
        }

        @Test
        @DisplayName("Should reject if record already has pending approval")
        void shouldRejectDuplicate() {
            when(approvalRepository.findPendingInstanceForRecord("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "existing-inst")));

            var result = service.submitForApproval("col-1", "rec-1", "user-1", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("already has a pending approval");
        }

        @Test
        @DisplayName("Should reject if no active approval process exists")
        void shouldRejectNoProcess() {
            when(approvalRepository.findPendingInstanceForRecord("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.empty());
            when(approvalRepository.findActiveProcessesForCollection("col-1", "t1"))
                    .thenReturn(List.of());

            var result = service.submitForApproval("col-1", "rec-1", "user-1", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No active approval process");
        }

        @Test
        @DisplayName("Should reject if process has no steps")
        void shouldRejectNoSteps() {
            when(approvalRepository.findPendingInstanceForRecord("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.empty());
            when(approvalRepository.findActiveProcessesForCollection("col-1", "t1"))
                    .thenReturn(List.of(Map.of("id", "proc-1", "on_submit_field_updates", "[]")));
            when(approvalRepository.findStepsByProcessId("proc-1"))
                    .thenReturn(List.of());

            var result = service.submitForApproval("col-1", "rec-1", "user-1", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("no steps configured");
        }

        @Test
        @DisplayName("Should use specific process ID when provided")
        void shouldUseSpecificProcess() {
            when(approvalRepository.findPendingInstanceForRecord("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.empty());
            when(approvalRepository.findProcessById("specific-proc", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "specific-proc",
                            "on_submit_field_updates", "[]"
                    )));
            when(approvalRepository.findStepsByProcessId("specific-proc"))
                    .thenReturn(List.of(Map.of(
                            "id", "step-1",
                            "step_number", 1,
                            "approver_type", "USER",
                            "approver_id", "approver-1"
                    )));
            when(approvalRepository.createInstance("t1", "specific-proc", "col-1", "rec-1", "user-1"))
                    .thenReturn("inst-1");

            var result = service.submitForApproval("col-1", "rec-1", "user-1", "specific-proc");

            assertThat(result.success()).isTrue();
            verify(approvalRepository).findProcessById("specific-proc", "t1");
            verify(approvalRepository, never()).findActiveProcessesForCollection(any(), any());
        }

        @Test
        @DisplayName("Should fail without tenant context")
        @WithTenant(none = true)
        void shouldFailWithoutTenant() {
            var result = service.submitForApproval("col-1", "rec-1", "user-1", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No tenant context");
        }
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("Should approve step and complete approval when last step")
        void shouldApproveAndComplete() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "approval_process_id", "proc-1",
                            "collection_id", "col-1",
                            "record_id", "rec-1",
                            "current_step_number", 1
                    )));
            when(approvalRepository.findStepInstanceForApprover("inst-1", "approver-1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "si-1",
                            "step_id", "step-1",
                            "unanimity_required", false,
                            "on_approve_action", "NEXT_STEP",
                            "step_number", 1
                    )));
            when(approvalRepository.findStepsByProcessId("proc-1"))
                    .thenReturn(List.of(Map.of(
                            "id", "step-1",
                            "step_number", 1
                    )));
            when(approvalRepository.findProcessById("proc-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "proc-1",
                            "on_approval_field_updates", "[]"
                    )));

            var result = service.approve("inst-1", "approver-1", "Looks good");

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("APPROVED");
            verify(approvalRepository).updateStepInstanceStatus("si-1", "APPROVED", "Looks good");
            verify(approvalRepository).updateInstanceStatus("inst-1", "APPROVED", null);
        }

        @Test
        @DisplayName("Should advance to next step when more steps exist")
        void shouldAdvanceToNextStep() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "approval_process_id", "proc-1",
                            "collection_id", "col-1",
                            "record_id", "rec-1",
                            "current_step_number", 1
                    )));
            when(approvalRepository.findStepInstanceForApprover("inst-1", "approver-1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "si-1",
                            "step_id", "step-1",
                            "unanimity_required", false,
                            "on_approve_action", "NEXT_STEP",
                            "step_number", 1
                    )));
            when(approvalRepository.findStepsByProcessId("proc-1"))
                    .thenReturn(List.of(
                            Map.of("id", "step-1", "step_number", 1),
                            Map.of("id", "step-2", "step_number", 2,
                                    "approver_type", "USER", "approver_id", "approver-2")
                    ));

            var result = service.approve("inst-1", "approver-1", null);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("PENDING");
            verify(approvalRepository).advanceStep("inst-1", 2);
            verify(approvalRepository).createStepInstance("inst-1", "step-2", "approver-2");
        }

        @Test
        @DisplayName("Should wait for remaining approvers when unanimity required")
        void shouldWaitForUnanimity() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "approval_process_id", "proc-1",
                            "collection_id", "col-1",
                            "record_id", "rec-1",
                            "current_step_number", 1
                    )));
            when(approvalRepository.findStepInstanceForApprover("inst-1", "approver-1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "si-1",
                            "step_id", "step-1",
                            "unanimity_required", true,
                            "on_approve_action", "NEXT_STEP",
                            "step_number", 1
                    )));
            when(approvalRepository.findPendingStepInstances("inst-1", "step-1"))
                    .thenReturn(List.of(Map.of("id", "si-2", "assigned_to", "approver-2")));

            var result = service.approve("inst-1", "approver-1", null);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("PENDING");
            assertThat(result.message()).contains("Waiting for remaining approvers");
        }

        @Test
        @DisplayName("Should reject if approval is not pending")
        void shouldRejectIfNotPending() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "APPROVED"
                    )));

            var result = service.approve("inst-1", "approver-1", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("not pending");
        }

        @Test
        @DisplayName("Should reject if user has no pending step")
        void shouldRejectIfNoStepForUser() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "inst-1", "status", "PENDING")));
            when(approvalRepository.findStepInstanceForApprover("inst-1", "wrong-user"))
                    .thenReturn(Optional.empty());

            var result = service.approve("inst-1", "wrong-user", null);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No pending approval step");
        }
    }

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("Should reject approval with final rejection")
        void shouldRejectFinal() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "approval_process_id", "proc-1",
                            "collection_id", "col-1",
                            "record_id", "rec-1",
                            "current_step_number", 1
                    )));
            when(approvalRepository.findStepInstanceForApprover("inst-1", "approver-1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "si-1",
                            "step_id", "step-1",
                            "on_reject_action", "REJECT_FINAL",
                            "step_number", 1
                    )));
            when(approvalRepository.findProcessById("proc-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "proc-1",
                            "on_rejection_field_updates", "[]"
                    )));

            var result = service.reject("inst-1", "approver-1", "Not acceptable");

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("REJECTED");
            verify(approvalRepository).updateStepInstanceStatus("si-1", "REJECTED", "Not acceptable");
            verify(approvalRepository).cancelPendingStepInstances("inst-1");
            verify(approvalRepository).updateInstanceStatus("inst-1", "REJECTED", null);
        }
    }

    @Nested
    @DisplayName("recall")
    class Recall {

        @Test
        @DisplayName("Should recall approval by submitter")
        void shouldRecallBySubmitter() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "submitted_by", "user-1",
                            "approval_process_id", "proc-1",
                            "collection_id", "col-1",
                            "record_id", "rec-1"
                    )));
            when(approvalRepository.findProcessById("proc-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "proc-1",
                            "allow_recall", true,
                            "on_recall_field_updates", "[]"
                    )));

            var result = service.recall("inst-1", "user-1");

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("RECALLED");
            verify(approvalRepository).cancelPendingStepInstances("inst-1");
            verify(approvalRepository).updateInstanceStatus("inst-1", "RECALLED", null);
        }

        @Test
        @DisplayName("Should reject recall by non-submitter")
        void shouldRejectRecallByNonSubmitter() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "submitted_by", "user-1",
                            "approval_process_id", "proc-1"
                    )));

            var result = service.recall("inst-1", "other-user");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Only the submitter");
        }

        @Test
        @DisplayName("Should reject recall when not allowed")
        void shouldRejectRecallWhenNotAllowed() {
            when(approvalRepository.findInstanceById("inst-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "inst-1",
                            "status", "PENDING",
                            "submitted_by", "user-1",
                            "approval_process_id", "proc-1"
                    )));
            when(approvalRepository.findProcessById("proc-1", "t1"))
                    .thenReturn(Optional.of(Map.of(
                            "id", "proc-1",
                            "allow_recall", false
                    )));

            var result = service.recall("inst-1", "user-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Recall is not allowed");
        }
    }

    @Nested
    @DisplayName("isRecordLocked")
    class IsRecordLocked {

        @Test
        @DisplayName("Should return true when record has LOCKED approval")
        void shouldReturnTrueWhenLocked() {
            when(approvalRepository.getRecordEditability("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.of("LOCKED"));

            assertThat(service.isRecordLocked("col-1", "rec-1")).isTrue();
        }

        @Test
        @DisplayName("Should return false when no active approval")
        void shouldReturnFalseWhenNoApproval() {
            when(approvalRepository.getRecordEditability("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.empty());

            assertThat(service.isRecordLocked("col-1", "rec-1")).isFalse();
        }

        @Test
        @DisplayName("Should return false when record editability is UNLOCKED")
        void shouldReturnFalseWhenUnlocked() {
            when(approvalRepository.getRecordEditability("col-1", "rec-1", "t1"))
                    .thenReturn(Optional.of("UNLOCKED"));

            assertThat(service.isRecordLocked("col-1", "rec-1")).isFalse();
        }
    }
}
