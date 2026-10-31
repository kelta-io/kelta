package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.ActorSource;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DecisionActionHandler")
class DecisionActionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ActionHandlerRegistry handlerRegistry;
    private DecisionActionHandler handler;

    /** Stub FormulaEvaluator that returns true when expression equals "true" */
    private final FormulaEvaluator formulaEvaluator = new FormulaEvaluator(List.of()) {
        @Override
        public boolean evaluateBoolean(String expression, Map<String, Object> context) {
            if ("THROW".equals(expression)) {
                throw new RuntimeException("eval error");
            }
            return "true".equals(expression) || "status == 'High'".equals(expression);
        }
    };

    @BeforeEach
    void setUp() {
        handlerRegistry = new ActionHandlerRegistry();
        handler = new DecisionActionHandler(objectMapper, formulaEvaluator, handlerRegistry);
    }

    @Test
    @DisplayName("Should have correct action type key")
    void shouldHaveCorrectKey() {
        assertEquals("DECISION", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Should execute true branch when condition is true, as the run's user and actor source")
    void shouldExecuteTrueBranch() {
        List<ActionContext> nested = new java.util.ArrayList<>();
        // Register a handler for nested FIELD_UPDATE action
        handlerRegistry.register(new ActionHandler() {
            @Override public String getActionTypeKey() { return "FIELD_UPDATE"; }
            @Override public ActionResult execute(ActionContext context) {
                nested.add(context);
                return ActionResult.success(Map.of("updated", true));
            }
        });

        String config = """
            {
              "condition": "true",
              "trueActions": [
                {"actionType": "FIELD_UPDATE", "config": {"updates": []}}
              ],
              "falseActions": [
                {"actionType": "LOG_MESSAGE", "config": {"message": "should not execute"}}
              ]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals(true, result.outputData().get("conditionResult"));
        assertEquals("true", result.outputData().get("branch"));
        assertEquals(1, result.outputData().get("actionsExecuted"));
        assertEquals("user-1", nested.getFirst().userId());
        assertEquals(ActorSource.RUN_AS, nested.getFirst().actorSource());
    }

    @Test
    @DisplayName("Should execute false branch when condition is false")
    void shouldExecuteFalseBranch() {
        handlerRegistry.register(new ActionHandler() {
            @Override public String getActionTypeKey() { return "LOG_MESSAGE"; }
            @Override public ActionResult execute(ActionContext context) {
                return ActionResult.success(Map.of("logged", true));
            }
        });

        String config = """
            {
              "condition": "false",
              "trueActions": [
                {"actionType": "FIELD_UPDATE", "config": {}}
              ],
              "falseActions": [
                {"actionType": "LOG_MESSAGE", "config": {"message": "low priority"}}
              ]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals(false, result.outputData().get("conditionResult"));
        assertEquals("false", result.outputData().get("branch"));
        assertEquals(1, result.outputData().get("actionsExecuted"));
    }

    @Test
    @DisplayName("Should succeed with zero actions when branch is empty")
    void shouldSucceedWithEmptyBranch() {
        String config = """
            {
              "condition": "true",
              "trueActions": [],
              "falseActions": [{"actionType": "LOG_MESSAGE", "config": {}}]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals(true, result.outputData().get("conditionResult"));
        assertEquals(0, result.outputData().get("actionsExecuted"));
    }

    @Test
    @DisplayName("Should succeed with zero actions when branch is null")
    void shouldSucceedWithNullBranch() {
        String config = """
            {
              "condition": "true",
              "falseActions": [{"actionType": "LOG_MESSAGE", "config": {}}]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals(0, result.outputData().get("actionsExecuted"));
    }

    @Test
    @DisplayName("Should fail when condition is missing")
    void shouldFailWhenConditionMissing() {
        String config = """
            {
              "trueActions": [{"actionType": "FIELD_UPDATE", "config": {}}]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("condition"));
    }

    @Test
    @DisplayName("Should fail when condition evaluation throws")
    void shouldFailWhenConditionEvaluationThrows() {
        String config = """
            {
              "condition": "THROW",
              "trueActions": [{"actionType": "FIELD_UPDATE", "config": {}}]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("eval error"));
    }

    @Test
    @DisplayName("Should skip nested actions with missing handler")
    void shouldSkipNestedActionsWithMissingHandler() {
        String config = """
            {
              "condition": "true",
              "trueActions": [
                {"actionType": "NONEXISTENT", "config": {}}
              ]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals(0, result.outputData().get("actionsExecuted"));
    }

    @Test
    @DisplayName("Should stop on nested action failure and report it")
    @SuppressWarnings("unchecked")
    void shouldStopOnNestedFailure() {
        handlerRegistry.register(new ActionHandler() {
            @Override public String getActionTypeKey() { return "FAIL_ACTION"; }
            @Override public ActionResult execute(ActionContext context) {
                return ActionResult.failure("nested failure reason");
            }
        });
        handlerRegistry.register(new ActionHandler() {
            @Override public String getActionTypeKey() { return "SECOND_ACTION"; }
            @Override public ActionResult execute(ActionContext context) {
                return ActionResult.success();
            }
        });

        String config = """
            {
              "condition": "true",
              "trueActions": [
                {"actionType": "FAIL_ACTION", "config": {}},
                {"actionType": "SECOND_ACTION", "config": {}}
              ]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        // Decision returns success even when nested action fails, but marks nestedFailure
        assertTrue(result.successful());
        assertEquals(true, result.outputData().get("nestedFailure"));
        assertEquals(1, result.outputData().get("actionsExecuted"));

        List<Map<String, Object>> actionResults =
            (List<Map<String, Object>>) result.outputData().get("actionResults");
        assertNotNull(actionResults);
        assertEquals(1, actionResults.size());
        assertEquals("FAILURE", actionResults.get(0).get("status"));
    }

    @Test
    @DisplayName("Should execute multiple nested actions in order")
    void shouldExecuteMultipleNestedActions() {
        handlerRegistry.register(new ActionHandler() {
            @Override public String getActionTypeKey() { return "ACTION_A"; }
            @Override public ActionResult execute(ActionContext context) {
                return ActionResult.success(Map.of("a", true));
            }
        });
        handlerRegistry.register(new ActionHandler() {
            @Override public String getActionTypeKey() { return "ACTION_B"; }
            @Override public ActionResult execute(ActionContext context) {
                return ActionResult.success(Map.of("b", true));
            }
        });

        String config = """
            {
              "condition": "true",
              "trueActions": [
                {"actionType": "ACTION_A", "config": {}},
                {"actionType": "ACTION_B", "config": {}}
              ]
            }
            """;

        ActionResult result = handler.execute(makeContext(config));
        assertTrue(result.successful());
        assertEquals(2, result.outputData().get("actionsExecuted"));
        assertNull(result.outputData().get("nestedFailure"));
    }

    @Test
    @DisplayName("Should validate config requires condition")
    void shouldValidateRequiresCondition() {
        assertThrows(IllegalArgumentException.class, () ->
            handler.validate("{\"trueActions\": [{\"actionType\": \"FIELD_UPDATE\"}]}"));
    }

    @Test
    @DisplayName("Should validate config requires at least one branch with actions")
    void shouldValidateRequiresAtLeastOneBranch() {
        assertThrows(IllegalArgumentException.class, () ->
            handler.validate("{\"condition\": \"x > 1\", \"trueActions\": [], \"falseActions\": []}"));
    }

    @Test
    @DisplayName("Should pass validation with valid config")
    void shouldPassValidation() {
        assertDoesNotThrow(() ->
            handler.validate("""
                {"condition": "x > 1", "trueActions": [{"actionType": "FIELD_UPDATE", "config": {}}]}
                """));
    }

    private ActionContext makeContext(String config) {
        return ActionContext.builder()
            .tenantId("t1").collectionId("c1").collectionName("orders").recordId("r1")
            .userId("user-1").actorSource(ActorSource.RUN_AS).data(Map.of("status", "High"))
            .actionConfigJson(config).workflowRuleId("wf1").executionLogId("log1").build();
    }
}
