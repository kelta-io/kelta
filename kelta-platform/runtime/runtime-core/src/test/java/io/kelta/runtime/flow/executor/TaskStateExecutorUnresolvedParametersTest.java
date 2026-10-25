package io.kelta.runtime.flow.executor;

import io.kelta.runtime.flow.FlowDefinition;
import io.kelta.runtime.flow.FlowExecutionContext;
import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.flow.StateDefinition;
import io.kelta.runtime.flow.StateExecutionResult;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TaskStateExecutor — handler-declared unresolved parameter keys")
class TaskStateExecutorUnresolvedParametersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("a declared key keeps its ${...} text, other keys resolve, and the full state reaches the handler")
    void declaredKeyStaysRaw() {
        AtomicReference<ActionContext> seen = new AtomicReference<>();
        Map<String, Object> config = run(capturing("RAW_KEYS", Set.of("sql"), seen),
                "$.input");

        assertThat(config.get("sql")).isEqualTo("SELECT 1 WHERE id = '${$.input.id}'");
        assertThat(config.get("label")).isEqualTo("for o-1");
        assertThat(seen.get().stateData())
                .containsEntry("input", Map.of("id", "o-1"))
                .containsKey("context");
        assertThat(seen.get().resolvedData()).isEqualTo(Map.of("id", "o-1"));
    }

    @Test
    @DisplayName("a handler declaring no keys gets every parameter resolved, as before")
    void defaultResolvesEverything() {
        AtomicReference<ActionContext> seen = new AtomicReference<>();
        Map<String, Object> config = run(capturing("ALL_KEYS", Set.of(), seen), null);

        assertThat(config.get("sql")).isEqualTo("SELECT 1 WHERE id = 'o-1'");
        assertThat(config.get("label")).isEqualTo("for o-1");
    }

    private Map<String, Object> run(ActionHandler handler, String inputPath) {
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(handler));
        TaskStateExecutor executor = new TaskStateExecutor(registry, new StateDataResolver(objectMapper), objectMapper);

        StateDefinition.TaskState task = new StateDefinition.TaskState("Step", null, handler.getActionTypeKey(),
                Map.of("sql", "SELECT 1 WHERE id = '${$.input.id}'", "label", "for ${$.input.id}"),
                inputPath, null, "$.result", null, null, true, List.of(), List.of());
        FlowDefinition definition = new FlowDefinition(null, "Step", Map.of("Step", task), Map.of());
        FlowExecutionContext context = new FlowExecutionContext("exec-1", "t1", "flow-1", "u1", definition,
                Map.of("input", Map.of("id", "o-1"), "context", Map.of("userId", "u1")));

        StateExecutionResult result = executor.execute(task, context);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.updatedData().get("result");
        return output;
    }

    private ActionHandler capturing(String key, Set<String> rawKeys, AtomicReference<ActionContext> seen) {
        return new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return key;
            }

            @Override
            public Set<String> unresolvedParameterKeys() {
                return rawKeys;
            }

            @Override
            public ActionResult execute(ActionContext context) {
                seen.set(context);
                return ActionResult.success(objectMapper.readValue(context.actionConfigJson(),
                        new TypeReference<Map<String, Object>>() {}));
            }
        };
    }
}
