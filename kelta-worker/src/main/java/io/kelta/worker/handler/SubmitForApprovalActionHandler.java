package io.kelta.worker.handler;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.service.ApprovalService;
import io.kelta.worker.service.ApprovalService.ApprovalActionResult;
import io.kelta.runtime.context.TenantContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Action handler that submits a record for approval within a flow.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "processId": "..." (optional — auto-detects if omitted)
 * }
 * </pre>
 *
 * <p>Uses the record's collection and record ID from the action context.
 * The submitting user is taken from the action context's userId.
 *
 * @since 1.0.0
 */
public class SubmitForApprovalActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SubmitForApprovalActionHandler.class);

    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    public SubmitForApprovalActionHandler(ApprovalService approvalService,
                                          ObjectMapper objectMapper) {
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getActionTypeKey() {
        return "SUBMIT_FOR_APPROVAL";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            String processId = null;

            // Parse optional config
            if (context.actionConfigJson() != null && !context.actionConfigJson().isBlank()) {
                Map<String, Object> config = objectMapper.readValue(
                        context.actionConfigJson(), new TypeReference<>() {});
                processId = (String) config.get("processId");
            }

            // Flows may execute asynchronously with no tenant bound: bind the flow's tenant for
            // the approval call in that case, and leave an existing binding untouched otherwise.
            String configuredProcessId = processId;
            if (TenantContext.get() == null && context.tenantId() != null) {
                return TenantContext.callWithTenant(context.tenantId(),
                        () -> submit(context, configuredProcessId));
            }
            return submit(context, configuredProcessId);
        } catch (Exception e) {
            log.error("SUBMIT_FOR_APPROVAL action failed: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    private ActionResult submit(ActionContext context, String processId) {
        ApprovalActionResult result = approvalService.submitForApproval(
                context.collectionId(),
                context.recordId(),
                context.userId(),
                processId
        );

        if (!result.success()) {
            log.warn("SUBMIT_FOR_APPROVAL failed for record {}: {}",
                    context.recordId(), result.message());
            return ActionResult.failure(result.message());
        }

        log.info("SUBMIT_FOR_APPROVAL succeeded: instanceId={}, recordId={}",
                result.instanceId(), context.recordId());

        return ActionResult.success(Map.of(
                "instanceId", result.instanceId(),
                "status", result.status(),
                "message", result.message()
        ));
    }

    @Override
    public void validate(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return; // Config is optional
        }

        try {
            objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
