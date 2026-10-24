package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Action handler that starts another saved flow as a new, asynchronous execution.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "flowId": "target-flow-uuid",
 *   "input": { "key": "value" }
 * }
 * </pre>
 *
 * <p>The started execution gets the usual state envelope: {@code input} (optional) is readable
 * as {@code $.input.<key>}, {@code $.trigger.type} is {@code TRIGGER_FLOW} and {@code $.context}
 * carries the tenant, user and target flow ids. The step
 * succeeds once the execution is created and returns its {@code executionId}; it does not wait
 * for the target flow to finish (use an {@code InvokeFlow} state for that).
 *
 * <p>Each hop increments the trigger depth the engine carries in the started execution's
 * initial input; a step running at {@link FlowEngine#MAX_TRIGGER_DEPTH} fails instead of
 * starting another execution, so a flow that triggers itself (directly or through other flows)
 * stops rather than looping forever.
 *
 * @since 1.0.0
 */
public class TriggerFlowActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(TriggerFlowActionHandler.class);

    private final ObjectMapper objectMapper;
    private final FlowEngine flowEngine;

    /**
     * Creates a TriggerFlowActionHandler.
     *
     * @param objectMapper the JSON mapper
     * @param flowEngine   the engine that starts the target flow; {@code null} makes every
     *                     execution fail
     */
    public TriggerFlowActionHandler(ObjectMapper objectMapper, FlowEngine flowEngine) {
        this.objectMapper = objectMapper;
        this.flowEngine = flowEngine;
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

            if (!(config.get("flowId") instanceof String flowId) || flowId.isBlank()) {
                return ActionResult.failure("TRIGGER_FLOW: 'flowId' is required in config");
            }
            if (flowEngine == null) {
                return ActionResult.failure("TRIGGER_FLOW: no flow engine is available to start flow '"
                    + flowId + "'");
            }
            if (context.triggerDepth() >= FlowEngine.MAX_TRIGGER_DEPTH) {
                return ActionResult.failure("TRIGGER_FLOW: flow '" + flowId
                    + "' not started — the trigger chain reached MAX_TRIGGER_DEPTH="
                    + FlowEngine.MAX_TRIGGER_DEPTH);
            }

            Object rawInput = config.get("input");
            if (rawInput != null && !(rawInput instanceof Map)) {
                return ActionResult.failure("TRIGGER_FLOW: 'input' must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> input = rawInput != null ? (Map<String, Object>) rawInput : Map.of();

            Map<String, Object> state = new LinkedHashMap<>();
            state.put("trigger", Map.of("type", "TRIGGER_FLOW"));
            state.put("input", input);
            Map<String, Object> flowContext = new LinkedHashMap<>();
            flowContext.put("tenantId", context.tenantId());
            flowContext.put("userId", context.userId());
            flowContext.put("flowId", flowId);
            state.put("context", flowContext);

            int depth = context.triggerDepth() + 1;
            Optional<String> executionId = flowEngine.startTriggeredExecution(
                context.tenantId(), flowId, state, context.userId(), depth);
            if (executionId.isEmpty()) {
                return ActionResult.failure("TRIGGER_FLOW: flow '" + flowId + "' not found");
            }

            log.info("TRIGGER_FLOW: started execution {} of flow '{}' at trigger depth {}",
                executionId.get(), flowId, depth);

            return ActionResult.success(Map.of(
                "flowId", flowId,
                "executionId", executionId.get()
            ));
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
