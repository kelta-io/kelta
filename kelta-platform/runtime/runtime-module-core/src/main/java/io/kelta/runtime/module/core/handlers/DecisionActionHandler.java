package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Action handler that implements conditional branching (if/else) in workflows.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "condition": "status == 'High'",
 *   "trueActions": [
 *     {"actionType": "FIELD_UPDATE", "config": {"updates": [{"field": "priority", "value": "Urgent"}]}}
 *   ],
 *   "falseActions": [
 *     {"actionType": "LOG_MESSAGE", "config": {"message": "Not high priority", "level": "INFO"}}
 *   ]
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class DecisionActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(DecisionActionHandler.class);

    private final ObjectMapper objectMapper;
    private final FormulaEvaluator formulaEvaluator;
    private final ActionHandlerRegistry handlerRegistry;

    public DecisionActionHandler(ObjectMapper objectMapper,
                                  FormulaEvaluator formulaEvaluator,
                                  ActionHandlerRegistry handlerRegistry) {
        this.objectMapper = objectMapper;
        this.formulaEvaluator = formulaEvaluator;
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public String getActionTypeKey() {
        return "DECISION";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            String condition = (String) config.get("condition");
            if (condition == null || condition.isBlank()) {
                return ActionResult.failure("Decision condition is required");
            }

            boolean conditionResult;
            try {
                conditionResult = formulaEvaluator.evaluateBoolean(condition, context.data());
            } catch (Exception e) {
                log.warn("Decision condition evaluation failed: {}", e.getMessage());
                return ActionResult.failure("Condition evaluation error: " + e.getMessage());
            }

            log.info("Decision action: condition='{}' evaluated to {} for record={}",
                condition, conditionResult, context.recordId());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> branchActions = conditionResult
                ? (List<Map<String, Object>>) config.get("trueActions")
                : (List<Map<String, Object>>) config.get("falseActions");

            if (branchActions == null || branchActions.isEmpty()) {
                log.debug("No actions defined for {} branch of decision", conditionResult ? "true" : "false");
                return ActionResult.success(Map.of(
                    "conditionResult", conditionResult,
                    "branch", conditionResult ? "true" : "false",
                    "actionsExecuted", 0
                ));
            }

            int actionsExecuted = 0;
            List<Map<String, Object>> actionResults = new ArrayList<>();

            for (Map<String, Object> actionDef : branchActions) {
                String actionType = (String) actionDef.get("actionType");
                if (actionType == null || actionType.isBlank()) {
                    continue;
                }

                // Tenant-scoped first, then global — same reason as
                // TaskStateExecutor: a nested action supplied by a runtime-loaded
                // module lives only in the tenant map, so a global-only lookup
                // silently SKIPPED it.
                Optional<ActionHandler> handlerOpt =
                    handlerRegistry.getHandler(context.tenantId(), actionType);
                if (handlerOpt.isEmpty()) {
                    log.warn("No handler for nested action type '{}' in decision", actionType);
                    actionResults.add(Map.of("actionType", actionType, "status", "SKIPPED",
                        "error", "No handler registered"));
                    continue;
                }

                Object nestedConfig = actionDef.get("config");
                String nestedConfigJson = nestedConfig != null
                    ? objectMapper.writeValueAsString(nestedConfig) : "{}";

                ActionContext childContext = ActionContext.builder()
                    .tenantId(context.tenantId())
                    .collectionId(context.collectionId())
                    .collectionName(context.collectionName())
                    .recordId(context.recordId())
                    .data(context.data())
                    .previousData(context.previousData())
                    .changedFields(context.changedFields())
                    .userId(context.userId())
                    .actorSource(context.actorSource())
                    .actionConfigJson(nestedConfigJson)
                    .workflowRuleId(context.workflowRuleId())
                    .executionLogId(context.executionLogId())
                    .resolvedData(context.resolvedData())
                    .build();

                ActionResult nestedResult = handlerOpt.get().execute(childContext);
                actionsExecuted++;

                Map<String, Object> resultEntry = new HashMap<>();
                resultEntry.put("actionType", actionType);
                resultEntry.put("status", nestedResult.successful() ? "SUCCESS" : "FAILURE");
                if (nestedResult.errorMessage() != null) {
                    resultEntry.put("error", nestedResult.errorMessage());
                }
                actionResults.add(resultEntry);

                if (!nestedResult.successful()) {
                    log.warn("Nested action '{}' failed in decision branch: {}",
                        actionType, nestedResult.errorMessage());
                    return ActionResult.success(Map.of(
                        "conditionResult", conditionResult,
                        "branch", conditionResult ? "true" : "false",
                        "actionsExecuted", actionsExecuted,
                        "actionResults", actionResults,
                        "nestedFailure", true
                    ));
                }
            }

            return ActionResult.success(Map.of(
                "conditionResult", conditionResult,
                "branch", conditionResult ? "true" : "false",
                "actionsExecuted", actionsExecuted,
                "actionResults", actionResults
            ));
        } catch (Exception e) {
            log.error("Failed to execute decision action: {}", e.getMessage(), e);
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("condition") == null) {
                throw new IllegalArgumentException("Config must contain 'condition' expression");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trueActions = (List<Map<String, Object>>) config.get("trueActions");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> falseActions = (List<Map<String, Object>>) config.get("falseActions");

            if ((trueActions == null || trueActions.isEmpty())
                && (falseActions == null || falseActions.isEmpty())) {
                throw new IllegalArgumentException(
                    "At least one of 'trueActions' or 'falseActions' must have actions defined");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }
}
