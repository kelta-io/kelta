package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FlowDefinitionValidationHook")
class FlowDefinitionValidationHookTest {

    private FlowDefinitionValidationHook hook;

    @BeforeEach
    void setUp() {
        hook = new FlowDefinitionValidationHook(new ObjectMapper());
    }

    @Test
    @DisplayName("Targets the flows collection at order 50")
    void targetsFlowsBeforeSideEffectHooks() {
        assertEquals("flows", hook.getCollectionName());
        // FlowConfigEventPublisher is 100 and FlowScheduleSyncHook is 150; we must run before both
        // so a bad definition never publishes a config event or syncs a scheduled_job row.
        assertEquals(50, hook.getOrder());
    }

    @Test
    @DisplayName("Accepts a valid definition supplied as a Map")
    void acceptsValidMapDefinition() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("StartAt", "Done");
        def.put("States", Map.of("Done", Map.of("Type", "Succeed")));
        Map<String, Object> record = new HashMap<>(Map.of(
                "name", "ok-flow",
                "flowType", "AUTOLAUNCHED",
                "definition", def));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertTrue(result.isSuccess(), "valid definition must pass");
        assertFalse(result.hasErrors());
    }

    @Test
    @DisplayName("Accepts a valid definition supplied as a JSON string")
    void acceptsValidStringDefinition() {
        Map<String, Object> record = new HashMap<>(Map.of(
                "flowType", "AUTOLAUNCHED",
                "definition", """
                    {"StartAt": "Done", "States": {"Done": {"Type": "Succeed"}}}
                    """));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("Rejects create with missing definition")
    void rejectsCreateMissingDefinition() {
        Map<String, Object> record = new HashMap<>(Map.of(
                "name", "no-def-flow",
                "flowType", "AUTOLAUNCHED"));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess(), "create without a definition is invalid");
        assertEquals("definition", result.getErrors().get(0).field());
    }

    @Test
    @DisplayName("Rejects create with null definition")
    void rejectsCreateNullDefinition() {
        Map<String, Object> record = new HashMap<>();
        record.put("flowType", "AUTOLAUNCHED");
        record.put("definition", null);

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertEquals("definition", result.getErrors().get(0).field());
    }

    @Test
    @DisplayName("Rejects create with empty Map definition")
    void rejectsCreateEmptyMapDefinition() {
        Map<String, Object> record = new HashMap<>(Map.of(
                "flowType", "AUTOLAUNCHED",
                "definition", Map.of()));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertEquals("definition", result.getErrors().get(0).field());
    }

    @Test
    @DisplayName("Rejects definition missing StartAt with a 400-style error on 'definition' field")
    void rejectsMissingStartAt() {
        Map<String, Object> def = Map.of(
                "States", Map.of("Done", Map.of("Type", "Succeed")));
        Map<String, Object> record = new HashMap<>(Map.of(
                "flowType", "AUTOLAUNCHED",
                "definition", def));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertTrue(result.hasErrors());
        assertEquals("definition", result.getErrors().get(0).field());
        assertTrue(result.getErrors().get(0).message().contains("StartAt"),
                "error must mention StartAt: " + result.getErrors().get(0).message());
    }

    @Test
    @DisplayName("Rejects definition with a dangling Next target")
    void rejectsDanglingNext() {
        Map<String, Object> def = Map.of(
                "StartAt", "Step1",
                "States", Map.of(
                        "Step1", Map.of("Type", "Task", "Resource", "LOG_MESSAGE", "Next", "Ghost")));
        Map<String, Object> record = new HashMap<>(Map.of(
                "flowType", "AUTOLAUNCHED",
                "definition", def));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().get(0).message().contains("Ghost"),
                "error should quote the bad target: " + result.getErrors().get(0).message());
    }

    @Test
    @DisplayName("Rejects a Map state whose iterator has no StartAt")
    void rejectsMapIteratorWithoutStartAt() {
        Map<String, Object> iterator = Map.of(
                "States", Map.of("Inner", Map.of("Type", "Succeed")));
        Map<String, Object> def = Map.of(
                "StartAt", "Iter",
                "States", Map.of(
                        "Iter", Map.of("Type", "Map", "ItemsPath", "$.items",
                                "End", true, "Iterator", iterator)));
        Map<String, Object> record = new HashMap<>(Map.of(
                "flowType", "AUTOLAUNCHED",
                "definition", def));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().get(0).message().contains("StartAt"));
    }

    @Test
    @DisplayName("Returns every problem at once so authors fix the whole flow in one round-trip")
    void returnsAllProblems() {
        Map<String, Object> def = Map.of(
                "StartAt", "Step1",
                "States", Map.of(
                        "Step1", Map.of("Type", "Task", "Resource", "LOG_MESSAGE", "Next", "GhostA"),
                        "Decide", Map.of(
                                "Type", "Choice",
                                "Choices", List.of(Map.of(
                                        "Variable", "$.x",
                                        "StringEquals", "a",
                                        "Next", "GhostB")),
                                "Default", "GhostC")));
        Map<String, Object> record = new HashMap<>(Map.of(
                "flowType", "AUTOLAUNCHED",
                "definition", def));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().size() >= 3,
                "expected the hook to report each dangling target separately");
    }

    @Test
    @DisplayName("Updates that omit `definition` are passed through (partial update)")
    void partialUpdateWithoutDefinitionPassesThrough() {
        Map<String, Object> record = new HashMap<>(Map.of("active", false));

        BeforeSaveResult result = hook.beforeUpdate("flow-1", record, Map.of(), "tenant-1");

        assertTrue(result.isSuccess(),
                "a partial update that doesn't touch `definition` must not be rejected");
    }

    @Test
    @DisplayName("Updates that include `definition` are validated")
    void updateWithDefinitionValidates() {
        Map<String, Object> def = Map.of(
                "States", Map.of("Done", Map.of("Type", "Succeed"))); // no StartAt
        Map<String, Object> record = new HashMap<>(Map.of("definition", def));

        BeforeSaveResult result = hook.beforeUpdate("flow-1", record, Map.of(), "tenant-1");

        assertFalse(result.isSuccess());
        assertEquals("definition", result.getErrors().get(0).field());
        assertTrue(result.getErrors().get(0).message().contains("StartAt"));
    }

    @Test
    @DisplayName("Rejects a definition that is neither Map nor String")
    void rejectsWrongType() {
        Map<String, Object> record = new HashMap<>();
        record.put("flowType", "AUTOLAUNCHED");
        record.put("definition", List.of("not", "a", "map"));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().get(0).message().toLowerCase().contains("json object"));
    }

    private static Map<String, Object> flowWithTask(String resource) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("StartAt", "Step");
        def.put("States", Map.of("Step", Map.of("Type", "Task", "Resource", resource, "End", true)));
        return new HashMap<>(Map.of("name", "retired-flow", "flowType", "AUTOLAUNCHED", "definition", def));
    }

    @Test
    @DisplayName("Rejects create and update of a flow with a DELAY Task, naming the Wait state")
    void rejectsDelayTask() {
        for (BeforeSaveResult result : List.of(
                hook.beforeCreate(flowWithTask("DELAY"), "tenant-1"),
                hook.beforeUpdate("flow-1", flowWithTask("DELAY"), Map.of(), "tenant-1"))) {
            assertFalse(result.isSuccess());
            assertEquals("definition", result.getErrors().get(0).field());
            assertTrue(result.getErrors().get(0).message().contains("Wait state"),
                    result.getErrors().get(0).message());
        }
    }

    @Test
    @DisplayName("Rejects create and update of a flow with a CREATE_TASK Task, naming CREATE_RECORD")
    void rejectsCreateTaskTask() {
        for (BeforeSaveResult result : List.of(
                hook.beforeCreate(flowWithTask("CREATE_TASK"), "tenant-1"),
                hook.beforeUpdate("flow-1", flowWithTask("CREATE_TASK"), Map.of(), "tenant-1"))) {
            assertFalse(result.isSuccess());
            assertEquals("definition", result.getErrors().get(0).field());
            assertTrue(result.getErrors().get(0).message().contains("CREATE_RECORD"),
                    result.getErrors().get(0).message());
        }
    }
}
