package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CreateTaskActionHandler")
class CreateTaskActionHandlerTest {

    private final CreateTaskActionHandler handler = new CreateTaskActionHandler();

    @Test
    @DisplayName("Keeps the CREATE_TASK key so already-saved flows reach it")
    void shouldHaveCorrectKey() {
        assertEquals("CREATE_TASK", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Fails with a message naming CREATE_RECORD instead of faking success")
    void failsNamingReplacement() {
        ActionContext ctx = ActionContext.builder()
            .tenantId("t1").userId("user-1").data(Map.of())
            .actionConfigJson("{\"subject\": \"Follow up\"}").build();

        ActionResult result = handler.execute(ctx);

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("CREATE_RECORD"), result.errorMessage());
    }
}
