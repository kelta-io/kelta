package io.kelta.runtime.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionContext")
class ActionContextTest {

    @Test
    @DisplayName("Should build context with all fields")
    void shouldBuildContext() {
        ActionContext context = ActionContext.builder()
            .tenantId("t1")
            .collectionId("c1")
            .collectionName("users")
            .recordId("r1")
            .data(Map.of("name", "Test"))
            .previousData(Map.of("name", "Old"))
            .changedFields(List.of("name"))
            .userId("u1")
            .actorSource(ActorSource.RUN_AS)
            .actionConfigJson("{}")
            .workflowRuleId("w1")
            .executionLogId("e1")
            .resolvedData(Map.of("resolved", true))
            .build();

        assertEquals("t1", context.tenantId());
        assertEquals("c1", context.collectionId());
        assertEquals("users", context.collectionName());
        assertEquals("r1", context.recordId());
        assertEquals("Test", context.data().get("name"));
        assertEquals("Old", context.previousData().get("name"));
        assertEquals(List.of("name"), context.changedFields());
        assertEquals("u1", context.userId());
        assertEquals(ActorSource.RUN_AS, context.actorSource());
        assertEquals("{}", context.actionConfigJson());
        assertEquals("w1", context.workflowRuleId());
        assertEquals("e1", context.executionLogId());
        assertEquals(true, context.resolvedData().get("resolved"));
    }

    @Test
    @DisplayName("Should allow null fields in builder")
    void shouldAllowNullFields() {
        ActionContext context = ActionContext.builder()
            .tenantId("t1")
            .build();

        assertEquals("t1", context.tenantId());
        assertNull(context.collectionId());
        assertNull(context.recordId());
        assertNull(context.actorSource());
    }
}
