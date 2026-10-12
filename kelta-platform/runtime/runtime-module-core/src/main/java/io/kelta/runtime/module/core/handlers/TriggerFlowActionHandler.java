package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Action handler that triggers another flow as a sub-invocation.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "flowId": "target-flow-uuid"
 * }
 * </pre>
 *
 * <p>This handler currently logs the sub-flow invocation request.
 * Full flow-to-flow execution will be wired when the flow engine
 * is available via the module extension mechanism.
 *
 * @since 1.0.0
 */
public class TriggerFlowActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(TriggerFlowActionHandler.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates a TriggerFlowActionHandler.
     *
     * @param objectMapper the JSON mapper
     */
    public TriggerFlowActionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getActionTypeKey() {
        return "TRIGGER_FLOW";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String flowId = (String) config.get("flowId");

            if (flowId == null || flowId.isBlank()) {
                return ActionResult.failure("TRIGGER_FLOW: 'flowId' is required in config");
            }

            log.info("TRIGGER_FLOW: requesting execution of flow '{}' from record '{}' in collection '{}'",
                flowId, context.recordId(), context.collectionName());

            // Sub-flow invocation: the flow engine will be wired via extensions in a future phase.
            // For now, log the request and return success with the target flow info.
            Map<String, Object> output = new HashMap<>();
            output.put("flowId", flowId);
            output.put("status", "QUEUED");

            log.info("TRIGGER_FLOW: queued flow '{}' for execution", flowId);

            return ActionResult.success(output);
        } catch (Exception e) {
            log.error("Failed to execute trigger flow action: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("flowId") == null) {
                throw new IllegalArgumentException("Config must contain 'flowId'");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
