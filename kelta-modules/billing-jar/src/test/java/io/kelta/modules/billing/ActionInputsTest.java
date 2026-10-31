package io.kelta.modules.billing;

import io.kelta.runtime.workflow.ActionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handlers are invoked on two paths that shape their inputs differently, and reading the wrong one
 * fails at runtime with a misleading "required" error rather than at compile time.
 */
@DisplayName("ActionInputs")
class ActionInputsTest {

    private static ActionContext contextWith(Map<String, Object> resolvedData) {
        return ActionContext.builder().tenantId("t1").resolvedData(resolvedData).build();
    }

    @Test
    @DisplayName("Unwraps the flow engine's state envelope")
    void unwrapsFlowEnvelope() {
        // TaskStateExecutor passes the whole envelope as resolvedData. Reading planCode from the
        // top level found nothing and the handler reported "planCode ... required" against a
        // request that supplied it — the platform's documented $.input.<key> rule.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("input", Map.of("planCode", "PRO", "successUrl", "https://a.example/ok"));
        envelope.put("context", Map.of("flowId", "f1"));
        envelope.put("trigger", Map.of("type", "API_INVOCATION"));

        Map<String, Object> inputs = ActionInputs.of(contextWith(envelope));

        assertThat(ActionInputs.string(inputs, "planCode")).isEqualTo("PRO");
        assertThat(ActionInputs.string(inputs, "successUrl")).isEqualTo("https://a.example/ok");
    }

    @Test
    @DisplayName("Reads top-level values, as the platform's webhook dispatch supplies them")
    void readsTopLevelValues() {
        Map<String, Object> resolved = Map.of("rawBody", "{}", "moduleId", "kelta-billing");

        Map<String, Object> inputs = ActionInputs.of(contextWith(resolved));

        assertThat(ActionInputs.string(inputs, "moduleId")).isEqualTo("kelta-billing");
    }

    @Test
    @DisplayName("Empty or absent resolvedData yields no inputs rather than throwing")
    void toleratesEmptyInput() {
        assertThat(ActionInputs.of(contextWith(null))).isEmpty();
        assertThat(ActionInputs.of(contextWith(Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("Blank and missing values read as null so required-checks fire")
    void blankIsNull() {
        Map<String, Object> inputs = Map.of("planCode", "   ", "other", "x");

        assertThat(ActionInputs.string(inputs, "planCode")).isNull();
        assertThat(ActionInputs.string(inputs, "missing")).isNull();
        assertThat(ActionInputs.string(inputs, "other")).isEqualTo("x");
    }

    @Test
    @DisplayName("A non-map 'input' value is treated as top-level data, not unwrapped")
    void nonMapInputIsNotUnwrapped() {
        // Guards a collection whose own field happens to be called "input".
        Map<String, Object> resolved = Map.of("input", "not-a-map", "planCode", "PRO");

        assertThat(ActionInputs.string(ActionInputs.of(contextWith(resolved)), "planCode"))
                .isEqualTo("PRO");
    }
}
