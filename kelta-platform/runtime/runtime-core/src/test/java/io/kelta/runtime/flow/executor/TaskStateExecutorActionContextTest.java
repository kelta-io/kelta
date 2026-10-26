package io.kelta.runtime.flow.executor;

import io.kelta.runtime.flow.FlowDefinition;
import io.kelta.runtime.flow.FlowExecutionContext;
import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.flow.StateDefinition;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TaskStateExecutor — ActionContext flow coordinates")
class TaskStateExecutorActionContextTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("the handler receives the flow id as workflowRuleId and the execution id as executionLogId")
    void handlerReceivesFlowAndExecutionIds() {
        AtomicReference<ActionContext> seen = new AtomicReference<>();
        ActionHandler handler = new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return "CAPTURE";
            }

            @Override
            public ActionResult execute(ActionContext context) {
                seen.set(context);
                return ActionResult.success(Map.of());
            }
        };
        TaskStateExecutor executor = new TaskStateExecutor(new ActionHandlerRegistry(List.of(handler)),
                new StateDataResolver(objectMapper), objectMapper);

        StateDefinition.TaskState task = new StateDefinition.TaskState("Step", null, "CAPTURE",
                Map.of(), null, null, "$.result", null, null, true, List.of(), List.of());
        FlowDefinition definition = new FlowDefinition(null, "Step", Map.of("Step", task), Map.of());
        FlowExecutionContext context = new FlowExecutionContext("exec-42", "tenant-1", "flow-7", "u1",
                definition, Map.of("input", Map.of()));

        executor.execute(task, context);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().tenantId()).isEqualTo("tenant-1");
        assertThat(seen.get().workflowRuleId()).isEqualTo("flow-7");
        assertThat(seen.get().executionLogId()).isEqualTo("exec-42");
    }
}
