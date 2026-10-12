package io.kelta.worker.handler;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.service.ApprovalService;
import io.kelta.worker.service.ApprovalService.ApprovalActionResult;
import io.kelta.worker.util.WithTenant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("SubmitForApprovalActionHandler")
class SubmitForApprovalActionHandlerTest {

    private ApprovalService approvalService;
    private SubmitForApprovalActionHandler handler;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        handler = new SubmitForApprovalActionHandler(approvalService, objectMapper);
    }

    @Test
    @DisplayName("Should return correct action type key")
    void shouldReturnActionTypeKey() {
        assertThat(handler.getActionTypeKey()).isEqualTo("SUBMIT_FOR_APPROVAL");
    }

    @Test
    @DisplayName("Should submit for approval successfully")
    @WithTenant("t1")
    void shouldSubmitSuccessfully() {
        when(approvalService.submitForApproval("col-1", "rec-1", "user-1", null))
                .thenReturn(ApprovalActionResult.success("inst-1", "PENDING", "Submitted"));

        ActionContext context = ActionContext.builder()
                .tenantId("t1")
                .collectionId("col-1")
                .recordId("rec-1")
                .userId("user-1")
                .actionConfigJson(null)
                .build();

        ActionResult result = handler.execute(context);

        assertThat(result.successful()).isTrue();
        assertThat(result.outputData()).containsEntry("instanceId", "inst-1");
        assertThat(result.outputData()).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("Should use processId from config")
    @WithTenant("t1")
    void shouldUseProcessIdFromConfig() {
        when(approvalService.submitForApproval("col-1", "rec-1", "user-1", "proc-1"))
                .thenReturn(ApprovalActionResult.success("inst-1", "PENDING", "Submitted"));

        ActionContext context = ActionContext.builder()
                .tenantId("t1")
                .collectionId("col-1")
                .recordId("rec-1")
                .userId("user-1")
                .actionConfigJson("{\"processId\": \"proc-1\"}")
                .build();

        ActionResult result = handler.execute(context);

        assertThat(result.successful()).isTrue();
        verify(approvalService).submitForApproval("col-1", "rec-1", "user-1", "proc-1");
    }

    @Test
    @DisplayName("Should return failure when service fails")
    @WithTenant("t1")
    void shouldReturnFailureOnServiceError() {
        when(approvalService.submitForApproval(any(), any(), any(), any()))
                .thenReturn(ApprovalActionResult.error("No active process"));

        ActionContext context = ActionContext.builder()
                .tenantId("t1")
                .collectionId("col-1")
                .recordId("rec-1")
                .userId("user-1")
                .build();

        ActionResult result = handler.execute(context);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("No active process");
    }

    @Test
    @DisplayName("Should set tenant context when not present")
    void shouldSetTenantContextFromActionContext() {
        // No tenant context set: the handler binds the action context's tenant for the call
        AtomicReference<String> tenantSeenByService = new AtomicReference<>();
        when(approvalService.submitForApproval("col-1", "rec-1", "user-1", null))
                .thenAnswer(inv -> {
                    tenantSeenByService.set(TenantContext.get());
                    return ApprovalActionResult.success("inst-1", "PENDING", "Submitted");
                });

        ActionContext context = ActionContext.builder()
                .tenantId("t1")
                .collectionId("col-1")
                .recordId("rec-1")
                .userId("user-1")
                .build();

        ActionResult result = handler.execute(context);

        assertThat(result.successful()).isTrue();
        assertThat(tenantSeenByService.get()).isEqualTo("t1");
        // The binding is scoped to the call: nothing is left bound after execution
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Should accept empty config")
    void shouldAcceptEmptyConfig() {
        handler.validate("");
        handler.validate(null);
    }

    @Test
    @DisplayName("Should accept valid config")
    void shouldAcceptValidConfig() {
        handler.validate("{\"processId\": \"proc-1\"}");
    }

    @Test
    @DisplayName("Should reject invalid JSON config")
    void shouldRejectInvalidConfig() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                handler.validate("{invalid json}"));
    }
}
