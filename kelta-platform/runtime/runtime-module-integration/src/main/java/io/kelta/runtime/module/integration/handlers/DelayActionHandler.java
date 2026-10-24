package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;

/**
 * Retired action: the {@code DELAY} Task never paused anything — it recorded a pending action
 * nobody resumed and the flow carried straight on. Flow saves now reject it
 * ({@code FlowDefinitionValidator}); this handler stays registered so a flow saved before that
 * fails with a readable message instead of "no handler found".
 *
 * <p>Replacement: a {@code Wait} state ({@code Seconds}, {@code Timestamp} or
 * {@code TimestampPath}), which parks the execution and resumes it when due.
 *
 * @since 1.0.0
 */
public class DelayActionHandler implements ActionHandler {

    public static final String RETIRED_MESSAGE =
        "DELAY is no longer supported and did not pause the flow — use a Wait state "
            + "(Seconds, Timestamp or TimestampPath) instead";

    @Override
    public String getActionTypeKey() {
        return "DELAY";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        return ActionResult.failure(RETIRED_MESSAGE);
    }
}
