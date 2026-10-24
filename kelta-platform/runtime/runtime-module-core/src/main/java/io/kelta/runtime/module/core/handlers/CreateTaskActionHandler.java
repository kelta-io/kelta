package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;

/**
 * Retired action: {@code CREATE_TASK} never wrote anything — it returned the task data it
 * would have created and reported success. Flow saves now reject it
 * ({@code FlowDefinitionValidator}); this handler stays registered so a flow saved before that
 * fails with a readable message instead of "no handler found".
 *
 * <p>Replacement: a {@code CREATE_RECORD} Task targeting the tenant's task collection.
 *
 * @since 1.0.0
 */
public class CreateTaskActionHandler implements ActionHandler {

    public static final String RETIRED_MESSAGE =
        "CREATE_TASK is no longer supported and created nothing — use a CREATE_RECORD Task "
            + "that writes to your task collection instead";

    @Override
    public String getActionTypeKey() {
        return "CREATE_TASK";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        return ActionResult.failure(RETIRED_MESSAGE);
    }
}
