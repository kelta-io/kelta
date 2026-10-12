package io.kelta.worker.controller;

import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.service.ApprovalService;
import io.kelta.worker.service.ApprovalService.ApprovalActionResult;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ApprovalController")
@WithTenant("t1")
class ApprovalControllerTest {

    /** UUID the mocked resolver returns for the gateway-stamped identifier. */
    private static final String USER_UUID = "11111111-1111-1111-1111-111111111111";

    private ApprovalService approvalService;
    private UserIdResolver userIdResolver;
    private ApprovalController controller;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        userIdResolver = mock(UserIdResolver.class);
        // Default: the header identifier resolves to the platform_user UUID; unknown
        // identifiers fall through to the resolver's pass-through contract.
        when(userIdResolver.resolve(anyString(), any()))
                .thenAnswer(inv -> "user-1".equals(inv.getArgument(0)) ? USER_UUID : inv.getArgument(0));
        controller = new ApprovalController(approvalService, userIdResolver);
    }

    @Nested
    @DisplayName("submitForApproval")
    class Submit {

        @Test
        @DisplayName("Should submit successfully with the header-resolved UUID")
        void shouldSubmitSuccessfully() {
            when(approvalService.submitForApproval("col-1", "rec-1", USER_UUID, null))
                    .thenReturn(ApprovalActionResult.success("inst-1", "PENDING", "Submitted"));

            var body = new HashMap<String, Object>();
            body.put("collectionId", "col-1");
            body.put("recordId", "rec-1");

            var response = controller.submitForApproval(body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("success", true);
            assertThat(response.getBody()).containsEntry("instanceId", "inst-1");
            verify(userIdResolver).resolve("user-1", "t1");
        }

        @Test
        @DisplayName("Should return 400 for missing collectionId")
        void shouldReturn400ForMissingCollectionId() {
            var body = new HashMap<String, Object>();
            body.put("recordId", "rec-1");

            var response = controller.submitForApproval(body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("Should reject a caller without the identity header (403)")
        void shouldRejectMissingIdentity() {
            var body = new HashMap<String, Object>();
            body.put("collectionId", "col-1");
            body.put("recordId", "rec-1");

            assertThatThrownBy(() -> controller.submitForApproval(body, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(approvalService);
        }

        @Test
        @DisplayName("Should ignore a body submittedBy — header identity always wins")
        void shouldIgnoreBodySubmittedBy() {
            when(approvalService.submitForApproval("col-1", "rec-1", USER_UUID, null))
                    .thenReturn(ApprovalActionResult.success("inst-1", "PENDING", "Submitted"));

            var body = new HashMap<String, Object>();
            body.put("collectionId", "col-1");
            body.put("recordId", "rec-1");
            body.put("submittedBy", "spoofed-user");

            var response = controller.submitForApproval(body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(approvalService).submitForApproval("col-1", "rec-1", USER_UUID, null);
            verify(approvalService, never()).submitForApproval(any(), any(), eq("spoofed-user"), any());
        }

        @Test
        @DisplayName("Should reject a body submittedBy with no header (spoof path dead, 403)")
        void shouldRejectBodyOnlyIdentity() {
            var body = new HashMap<String, Object>();
            body.put("collectionId", "col-1");
            body.put("recordId", "rec-1");
            body.put("submittedBy", "spoofed-user");

            assertThatThrownBy(() -> controller.submitForApproval(body, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(approvalService);
        }

        @Test
        @DisplayName("Should reject an identifier the resolver cannot map to a UUID (403)")
        void shouldRejectUnresolvableIdentity() {
            var body = new HashMap<String, Object>();
            body.put("collectionId", "col-1");
            body.put("recordId", "rec-1");

            assertThatThrownBy(() -> controller.submitForApproval(body, "ghost@example.com"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(approvalService);
        }

        @Test
        @DisplayName("Should return 400 without tenant context")
        @WithTenant(none = true)
        void shouldReturn400WithoutTenant() {
            var body = new HashMap<String, Object>();
            body.put("collectionId", "col-1");
            body.put("recordId", "rec-1");

            var response = controller.submitForApproval(body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("Should approve successfully")
        void shouldApproveSuccessfully() {
            when(approvalService.approve("inst-1", USER_UUID, "LGTM"))
                    .thenReturn(ApprovalActionResult.success("inst-1", "APPROVED", "Approved"));

            var body = Map.<String, Object>of("comments", "LGTM");
            var response = controller.approve("inst-1", body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "APPROVED");
        }

        @Test
        @DisplayName("Should return 400 when service returns error")
        void shouldReturn400OnServiceError() {
            when(approvalService.approve("inst-1", USER_UUID, null))
                    .thenReturn(ApprovalActionResult.error("No pending step"));

            var response = controller.approve("inst-1", null, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("Should ignore a body userId — header identity always wins")
        void shouldIgnoreBodyUserId() {
            when(approvalService.approve("inst-1", USER_UUID, null))
                    .thenReturn(ApprovalActionResult.success("inst-1", "APPROVED", "Approved"));

            var body = Map.<String, Object>of("userId", "spoofed-assignee-uuid");
            var response = controller.approve("inst-1", body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(approvalService).approve("inst-1", USER_UUID, null);
            verify(approvalService, never()).approve(any(), eq("spoofed-assignee-uuid"), any());
        }

        @Test
        @DisplayName("Should reject approve without identity header even when body carries userId (403)")
        void shouldRejectApproveWithoutHeader() {
            var body = Map.<String, Object>of("userId", "spoofed-assignee-uuid");

            assertThatThrownBy(() -> controller.approve("inst-1", body, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(approvalService);
        }
    }

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("Should reject successfully")
        void shouldRejectSuccessfully() {
            when(approvalService.reject("inst-1", USER_UUID, "Needs revision"))
                    .thenReturn(ApprovalActionResult.success("inst-1", "REJECTED", "Rejected"));

            var body = Map.<String, Object>of("comments", "Needs revision");
            var response = controller.reject("inst-1", body, "user-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "REJECTED");
        }
    }

    @Nested
    @DisplayName("recall")
    class Recall {

        @Test
        @DisplayName("Should recall successfully")
        void shouldRecallSuccessfully() {
            when(approvalService.recall("inst-1", USER_UUID))
                    .thenReturn(ApprovalActionResult.success("inst-1", "RECALLED", "Recalled"));

            var response = controller.recall("inst-1", "user-1", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "RECALLED");
        }

        @Test
        @DisplayName("Should ignore a body userId on recall — header identity always wins")
        void shouldIgnoreBodyUserIdOnRecall() {
            when(approvalService.recall("inst-1", USER_UUID))
                    .thenReturn(ApprovalActionResult.success("inst-1", "RECALLED", "Recalled"));

            var response = controller.recall("inst-1", "user-1",
                    Map.of("userId", "spoofed-submitter-uuid"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(approvalService).recall("inst-1", USER_UUID);
            verify(approvalService, never()).recall(any(), eq("spoofed-submitter-uuid"));
        }
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("Should return active approval status")
        void shouldReturnActiveStatus() {
            when(approvalService.getCurrentApprovalStatus("col-1", "rec-1"))
                    .thenReturn(Optional.of(new HashMap<>(Map.of("id", "inst-1", "status", "PENDING"))));

            var response = controller.getStatus("col-1", "rec-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("hasActiveApproval", true);
        }

        @Test
        @DisplayName("Should return no active approval")
        void shouldReturnNoActiveApproval() {
            when(approvalService.getCurrentApprovalStatus("col-1", "rec-1"))
                    .thenReturn(Optional.empty());

            var response = controller.getStatus("col-1", "rec-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("hasActiveApproval", false);
        }
    }

    @Nested
    @DisplayName("getHistory")
    class GetHistory {

        @Test
        @DisplayName("Should return approval history")
        void shouldReturnHistory() {
            when(approvalService.getApprovalHistory("col-1", "rec-1"))
                    .thenReturn(List.of(Map.of("id", "inst-1", "status", "APPROVED")));

            var response = controller.getHistory("col-1", "rec-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getLockStatus")
    class GetLockStatus {

        @Test
        @DisplayName("Should return locked status")
        void shouldReturnLockedStatus() {
            when(approvalService.isRecordLocked("col-1", "rec-1")).thenReturn(true);

            var response = controller.getLockStatus("col-1", "rec-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("locked", true);
        }
    }
}
