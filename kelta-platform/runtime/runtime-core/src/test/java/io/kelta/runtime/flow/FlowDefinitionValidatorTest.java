package io.kelta.runtime.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FlowDefinitionValidator")
class FlowDefinitionValidatorTest {

    private FlowDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FlowDefinitionValidator(new ObjectMapper());
    }

    @Test
    @DisplayName("returns no errors for a minimal valid flow")
    void validMinimalFlow() {
        String json = """
            {
                "StartAt": "Done",
                "States": { "Done": { "Type": "Succeed" } }
            }
            """;
        assertEquals(List.of(), validator.validate(json));
    }

    @Test
    @DisplayName("returns no errors for a multi-state flow with Task → Succeed")
    void validTaskFlow() {
        String json = """
            {
                "StartAt": "Step1",
                "States": {
                    "Step1": { "Type": "Task", "Resource": "LOG_MESSAGE", "Next": "Done" },
                    "Done":  { "Type": "Succeed" }
                }
            }
            """;
        assertEquals(List.of(), validator.validate(json));
    }

    @Test
    @DisplayName("reports missing top-level StartAt with the parser's message")
    void missingStartAt() {
        String json = """
            {
                "States": { "Done": { "Type": "Succeed" } }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("StartAt"),
                "error should mention StartAt: " + errors.get(0));
    }

    @Test
    @DisplayName("reports StartAt that does not match any defined state")
    void startAtUnknownState() {
        String json = """
            {
                "StartAt": "GhostState",
                "States": { "Done": { "Type": "Succeed" } }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("GhostState"),
                "error should quote the bad target: " + errors.get(0));
        assertTrue(errors.get(0).contains("StartAt"));
    }

    @Test
    @DisplayName("reports a Task Next pointing at a state that does not exist")
    void danglingNext() {
        String json = """
            {
                "StartAt": "Step1",
                "States": {
                    "Step1": { "Type": "Task", "Resource": "LOG_MESSAGE", "Next": "Missing" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Step1"));
        assertTrue(errors.get(0).contains("Missing"));
        assertTrue(errors.get(0).contains("Next"));
    }

    @Test
    @DisplayName("reports a Choice Default pointing at an unknown state")
    void danglingChoiceDefault() {
        String json = """
            {
                "StartAt": "Decide",
                "States": {
                    "Decide": {
                        "Type": "Choice",
                        "Choices": [
                            { "Variable": "$.x", "StringEquals": "a", "Next": "Done" }
                        ],
                        "Default": "Nowhere"
                    },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Nowhere"));
        assertTrue(errors.get(0).contains("Default"));
    }

    @Test
    @DisplayName("reports a Choice rule Next pointing at an unknown state")
    void danglingChoiceRule() {
        String json = """
            {
                "StartAt": "Decide",
                "States": {
                    "Decide": {
                        "Type": "Choice",
                        "Choices": [
                            { "Variable": "$.x", "StringEquals": "a", "Next": "Vanished" }
                        ],
                        "Default": "Done"
                    },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Vanished"));
    }

    @Test
    @DisplayName("recurses into compound And/Or/Not rules and reports their bad targets")
    void danglingCompoundChoiceRule() {
        String json = """
            {
                "StartAt": "Decide",
                "States": {
                    "Decide": {
                        "Type": "Choice",
                        "Choices": [
                            {
                                "And": [
                                    { "Variable": "$.x", "StringEquals": "a" }
                                ],
                                "Next": "Ghost"
                            }
                        ],
                        "Default": "Done"
                    },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Ghost"));
    }

    @Test
    @DisplayName("reports a Task with neither Next nor End")
    void taskWithoutNextOrEnd() {
        String json = """
            {
                "StartAt": "Step1",
                "States": {
                    "Step1": { "Type": "Task", "Resource": "LOG_MESSAGE" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Step1"));
        assertTrue(errors.get(0).contains("Next") && errors.get(0).contains("End"));
    }

    @Test
    @DisplayName("reports a Task with both Next and End=true")
    void taskWithBothNextAndEnd() {
        String json = """
            {
                "StartAt": "Step1",
                "States": {
                    "Step1": { "Type": "Task", "Resource": "LOG_MESSAGE",
                               "Next": "Done", "End": true },
                    "Done":  { "Type": "Succeed" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("both Next") && errors.get(0).contains("End"));
    }

    @Test
    @DisplayName("reports a Catch.Next pointing at an unknown state")
    void danglingCatchNext() {
        String json = """
            {
                "StartAt": "Risky",
                "States": {
                    "Risky": {
                        "Type": "Task", "Resource": "HTTP_CALLOUT", "End": true,
                        "Catch": [
                            { "ErrorEquals": ["States.ALL"], "Next": "NopeHandler" }
                        ]
                    }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("NopeHandler"));
        assertTrue(errors.get(0).contains("Catch"));
    }

    @Test
    @DisplayName("reports a Map state missing its iterator block")
    void mapWithoutIterator() {
        String json = """
            {
                "StartAt": "Iter",
                "States": {
                    "Iter": { "Type": "Map", "ItemsPath": "$.items", "End": true }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Iter"));
        assertTrue(errors.get(0).contains("Iterator"));
    }

    @Test
    @DisplayName("reports a Map iterator with a missing StartAt")
    void mapIteratorMissingStartAt() {
        String json = """
            {
                "StartAt": "Iter",
                "States": {
                    "Iter": {
                        "Type": "Map", "ItemsPath": "$.items", "End": true,
                        "Iterator": {
                            "States": { "Inner": { "Type": "Succeed" } }
                        }
                    }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("StartAt"),
                "iterator-scoped StartAt error expected: " + errors.get(0));
    }

    @Test
    @DisplayName("reports a Map iterator whose inner StartAt points at an unknown state")
    void mapIteratorDanglingStartAt() {
        String json = """
            {
                "StartAt": "Iter",
                "States": {
                    "Iter": {
                        "Type": "Map", "ItemsPath": "$.items", "End": true,
                        "Iterator": {
                            "StartAt": "MissingInner",
                            "States": { "Inner": { "Type": "Succeed" } }
                        }
                    }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("MissingInner"));
        assertTrue(errors.get(0).contains("Iter.Iterator"),
                "scope prefix should locate the failure inside the Map: " + errors.get(0));
    }

    @Test
    @DisplayName("reports a Parallel branch with a dangling Next")
    void parallelBranchDanglingNext() {
        String json = """
            {
                "StartAt": "Fan",
                "States": {
                    "Fan": {
                        "Type": "Parallel", "End": true,
                        "Branches": [
                            {
                                "StartAt": "B1",
                                "States": {
                                    "B1": { "Type": "Task", "Resource": "LOG_MESSAGE", "Next": "B1Missing" }
                                }
                            }
                        ]
                    }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("B1Missing"));
        assertTrue(errors.get(0).contains("Fan.Branches[0]"),
                "branch scope prefix should appear: " + errors.get(0));
    }

    @Test
    @DisplayName("accumulates multiple errors in a single pass")
    void multipleErrors() {
        String json = """
            {
                "StartAt": "Step1",
                "States": {
                    "Step1": { "Type": "Task", "Resource": "LOG_MESSAGE", "Next": "GhostA" },
                    "Decide": {
                        "Type": "Choice",
                        "Choices": [
                            { "Variable": "$.x", "StringEquals": "a", "Next": "GhostB" }
                        ],
                        "Default": "GhostC"
                    }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertTrue(errors.size() >= 3,
                "expected at least 3 dangling targets, got " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("GhostA")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("GhostB")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("GhostC")));
    }

    @Test
    @DisplayName("returns the parser exception as the only error when JSON is malformed")
    void malformedJson() {
        List<String> errors = validator.validate("not even json");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).toLowerCase().contains("flow") ||
                   errors.get(0).toLowerCase().contains("parse"));
    }

    @Test
    @DisplayName("rejects a DELAY Task, naming the Wait state")
    void rejectsDelayTask() {
        String json = """
            {
                "StartAt": "Pause",
                "States": {
                    "Pause": { "Type": "Task", "Resource": "DELAY", "Parameters": { "delayMinutes": 5 }, "Next": "Done" },
                    "Done": { "Type": "Succeed" }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("'Pause'") && errors.get(0).contains("DELAY")
                && errors.get(0).contains("Wait state"), errors.get(0));
    }

    @Test
    @DisplayName("rejects a CREATE_TASK Task, naming CREATE_RECORD")
    void rejectsCreateTaskTask() {
        String json = """
            {
                "StartAt": "Task1",
                "States": {
                    "Task1": { "Type": "Task", "Resource": "CREATE_TASK", "End": true }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("CREATE_TASK") && errors.get(0).contains("CREATE_RECORD"),
                errors.get(0));
    }

    @Test
    @DisplayName("rejects retired Tasks nested in Parallel branches and Map iterators")
    void rejectsNestedRetiredTasks() {
        String json = """
            {
                "StartAt": "Fan",
                "States": {
                    "Fan": {
                        "Type": "Parallel",
                        "Branches": [
                            { "StartAt": "B1", "States": { "B1": { "Type": "Task", "Resource": "DELAY", "End": true } } }
                        ],
                        "Next": "Each"
                    },
                    "Each": {
                        "Type": "Map",
                        "ItemsPath": "$.items",
                        "Iterator": { "StartAt": "I1", "States": { "I1": { "Type": "Task", "Resource": "CREATE_TASK", "End": true } } },
                        "End": true
                    }
                }
            }
            """;
        List<String> errors = validator.validate(json);
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Fan.Branches[0]:") && e.contains("Wait state")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Each.Iterator:") && e.contains("CREATE_RECORD")),
                errors.toString());
    }
}
