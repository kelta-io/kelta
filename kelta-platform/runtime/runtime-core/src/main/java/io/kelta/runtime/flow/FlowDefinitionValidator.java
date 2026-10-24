package io.kelta.runtime.flow;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a flow definition at save time by running the {@link FlowDefinitionParser}
 * and then performing a graph check: every {@code Next}/{@code Default} target and
 * every nested {@code StartAt} must resolve to a state defined within its own
 * scope (top-level for the root flow, the branch's own States map for
 * {@code Parallel} branches, the iterator's own States map for {@code Map}).
 *
 * <p>The parser already rejects missing top-level {@code StartAt}, missing
 * {@code States}, malformed JSON, unknown state types, and Choice/Parallel state
 * structural problems with a {@link FlowDefinitionException}. This class
 * also rejects Tasks whose {@code Resource} is a retired action that only ever
 * faked success ({@link #RETIRED_RESOURCES}), at any nesting level. This class
 * surfaces that as a single-entry error list so the caller can report multiple
 * dangling-target problems in one round-trip instead of one-at-a-time.
 *
 * @since 1.0.0
 */
public class FlowDefinitionValidator {

    /**
     * Task resources that are rejected at save, with the replacement each message names.
     * Their handlers stay registered and fail at run time for flows saved before this check.
     */
    static final Map<String, String> RETIRED_RESOURCES = Map.of(
            "DELAY", "use a Wait state (Seconds, Timestamp or TimestampPath) to pause the flow",
            "CREATE_TASK", "use a CREATE_RECORD Task that writes to your task collection");

    private final FlowDefinitionParser parser;

    /** The {@code Resource} keys rejected at save; unmodifiable. */
    public static Set<String> retiredResources() {
        return RETIRED_RESOURCES.keySet();
    }

    public FlowDefinitionValidator(ObjectMapper objectMapper) {
        this.parser = new FlowDefinitionParser(objectMapper);
    }

    public FlowDefinitionValidator(FlowDefinitionParser parser) {
        this.parser = parser;
    }

    /**
     * Validates a flow definition JSON string and returns the list of problems.
     * An empty list means the definition is valid.
     */
    public List<String> validate(String json) {
        List<String> errors = new ArrayList<>();
        FlowDefinition definition;
        try {
            definition = parser.parse(json);
        } catch (FlowDefinitionException e) {
            errors.add(e.getMessage());
            return errors;
        }
        validateGraph(definition, "", errors);
        return errors;
    }

    private void validateGraph(FlowDefinition definition, String scopePrefix, List<String> errors) {
        if (definition.states() == null || definition.states().isEmpty()) {
            errors.add(prefixed(scopePrefix, "States map is empty"));
            return;
        }

        String startAt = definition.startAt();
        if (startAt != null && !definition.hasState(startAt)) {
            errors.add(prefixed(scopePrefix,
                    "StartAt '" + startAt + "' does not match any defined state"));
        }

        for (var entry : definition.states().entrySet()) {
            String stateId = entry.getKey();
            StateDefinition state = entry.getValue();
            String stateScope = scopePrefix.isEmpty() ? stateId : scopePrefix + "." + stateId;
            validateState(stateId, scopePrefix, state, definition, stateScope, errors);
        }
    }

    private void validateState(String stateId, String scopePrefix, StateDefinition state,
                                FlowDefinition parent, String stateScope, List<String> errors) {
        switch (state) {
            case StateDefinition.TaskState task -> {
                String replacement = task.resource() != null ? RETIRED_RESOURCES.get(task.resource()) : null;
                if (replacement != null) {
                    errors.add(prefixed(scopePrefix, "State '" + stateId + "' uses Resource '"
                            + task.resource() + "', which is no longer supported — " + replacement));
                }
                checkTransition(stateId, scopePrefix, task.next(), task.end(), parent, errors);
                for (CatchPolicy catchPolicy : task.catchPolicies()) {
                    checkTarget(stateId, scopePrefix, "Catch.Next", catchPolicy.next(), parent, errors);
                }
            }
            case StateDefinition.ChoiceState choice -> {
                for (ChoiceRule rule : choice.choices()) {
                    checkChoiceRuleTargets(stateId, scopePrefix, rule, parent, errors);
                }
                if (choice.defaultState() != null) {
                    checkTarget(stateId, scopePrefix, "Default", choice.defaultState(), parent, errors);
                }
            }
            case StateDefinition.ParallelState parallel -> {
                checkTransition(stateId, scopePrefix, parallel.next(), parallel.end(), parent, errors);
                for (CatchPolicy catchPolicy : parallel.catchPolicies()) {
                    checkTarget(stateId, scopePrefix, "Catch.Next", catchPolicy.next(), parent, errors);
                }
                int idx = 0;
                for (FlowDefinition branch : parallel.branches()) {
                    validateGraph(branch, stateScope + ".Branches[" + idx + "]", errors);
                    idx++;
                }
            }
            case StateDefinition.MapState map -> {
                checkTransition(stateId, scopePrefix, map.next(), map.end(), parent, errors);
                if (map.iterator() == null) {
                    errors.add(prefixed(scopePrefix, "State '" + stateId
                            + "' (Map) is missing an Iterator with StartAt and States"));
                } else {
                    validateGraph(map.iterator(), stateScope + ".Iterator", errors);
                }
            }
            case StateDefinition.WaitState wait ->
                    checkTransition(stateId, scopePrefix, wait.next(), wait.end(), parent, errors);
            case StateDefinition.PassState pass ->
                    checkTransition(stateId, scopePrefix, pass.next(), pass.end(), parent, errors);
            case StateDefinition.InvokeFlowState invoke -> {
                checkTransition(stateId, scopePrefix, invoke.next(), invoke.end(), parent, errors);
                for (CatchPolicy catchPolicy : invoke.catchPolicies()) {
                    checkTarget(stateId, scopePrefix, "Catch.Next", catchPolicy.next(), parent, errors);
                }
            }
            case StateDefinition.SucceedState ignored -> {}
            case StateDefinition.FailState ignored -> {}
        }
    }

    private void checkChoiceRuleTargets(String stateId, String scopePrefix, ChoiceRule rule,
                                         FlowDefinition parent, List<String> errors) {
        if (rule.next() != null) {
            checkTarget(stateId, scopePrefix, "Choice.Next", rule.next(), parent, errors);
        }
        switch (rule) {
            case ChoiceRule.And and -> and.rules()
                    .forEach(r -> checkChoiceRuleTargets(stateId, scopePrefix, r, parent, errors));
            case ChoiceRule.Or or -> or.rules()
                    .forEach(r -> checkChoiceRuleTargets(stateId, scopePrefix, r, parent, errors));
            case ChoiceRule.Not not -> checkChoiceRuleTargets(stateId, scopePrefix, not.rule(), parent, errors);
            default -> {}
        }
    }

    private void checkTransition(String stateId, String scopePrefix, String next, boolean end,
                                  FlowDefinition parent, List<String> errors) {
        if (next == null) {
            if (!end) {
                errors.add(prefixed(scopePrefix,
                        "State '" + stateId + "' has neither Next nor End — it cannot terminate"));
            }
            return;
        }
        if (end) {
            errors.add(prefixed(scopePrefix,
                    "State '" + stateId + "' declares both Next='" + next + "' and End=true"));
        }
        checkTarget(stateId, scopePrefix, "Next", next, parent, errors);
    }

    private void checkTarget(String stateId, String scopePrefix, String field, String target,
                              FlowDefinition parent, List<String> errors) {
        if (target == null || target.isBlank()) {
            errors.add(prefixed(scopePrefix,
                    "State '" + stateId + "' has empty " + field + " target"));
            return;
        }
        if (!parent.hasState(target)) {
            errors.add(prefixed(scopePrefix,
                    "State '" + stateId + "' " + field + " points to '" + target
                            + "', which is not a defined state"));
        }
    }

    private String prefixed(String scopePrefix, String message) {
        return scopePrefix.isEmpty() ? message : scopePrefix + ": " + message;
    }
}
