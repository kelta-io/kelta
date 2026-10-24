package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DelayActionHandler")
class DelayActionHandlerTest {

    private final DelayActionHandler handler = new DelayActionHandler();

    @Test
    @DisplayName("Keeps the DELAY key so already-saved flows reach it")
    void shouldHaveCorrectKey() {
        assertEquals("DELAY", handler.getActionTypeKey());
    }

    @Test
    @DisplayName("Fails with a message naming the Wait state instead of faking success")
    void failsNamingReplacement() {
        ActionContext ctx = ActionContext.builder()
            .tenantId("t1").userId("user-1").data(Map.of())
            .actionConfigJson("{\"delayMinutes\": 60}").build();

        ActionResult result = handler.execute(ctx);

        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("Wait state"), result.errorMessage());
    }
}
