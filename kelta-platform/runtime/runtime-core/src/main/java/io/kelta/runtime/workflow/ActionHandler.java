package io.kelta.runtime.workflow;

import io.kelta.runtime.flow.ActionHandlerDescriptor;

import java.util.Set;

/**
 * Service Provider Interface (SPI) for workflow action handlers.
 * <p>
 * Each action type (FIELD_UPDATE, EMAIL_ALERT, etc.) is implemented by a class
 * that implements this interface. Handlers are discovered via Spring classpath
 * scanning and registered in the {@link ActionHandlerRegistry}.
 * <p>
 * To add a new action type:
 * <ol>
 *   <li>Implement this interface as a {@code @Component}</li>
 *   <li>Register the action type in the {@code workflow_action_type} database table</li>
 *   <li>The handler will be auto-discovered and available for workflow execution</li>
 * </ol>
 *
 * @since 1.0.0
 */
public interface ActionHandler {

    /**
     * Returns the action type key that this handler processes.
     * Must match the {@code key} column in the {@code workflow_action_type} table.
     *
     * @return the action type key (e.g., "FIELD_UPDATE", "EMAIL_ALERT")
     */
    String getActionTypeKey();

    /**
     * Executes the action with the given context.
     *
     * @param context the execution context containing record data, config, and resolved data
     * @return the result of the action execution
     */
    ActionResult execute(ActionContext context);

    /**
     * Validates the action configuration JSON at save time.
     * Called when a workflow rule is created or updated to catch config errors early.
     *
     * @param configJson the action configuration as a JSON string
     * @throws IllegalArgumentException if the config is invalid
     */
    default void validate(String configJson) {
        // Default: no validation — override for strict config checking
    }

    /**
     * Top-level {@code Parameters} keys a flow Task passes to this handler with their
     * {@code ${...}} placeholders left unresolved. The handler resolves them itself from
     * {@link ActionContext#stateData()}, e.g. {@code SQL_QUERY} binds each one as a JDBC
     * parameter instead of splicing text into its statement.
     *
     * @return the keys to leave unresolved; empty (the default) resolves every key
     */
    default Set<String> unresolvedParameterKeys() {
        return Set.of();
    }

    /**
     * Returns the UI descriptor for this handler.
     * <p>
     * Override to provide rich UI integration in the visual flow builder.
     * Handlers that return {@code null} get a generic raw JSON config editor.
     *
     * @return the handler descriptor, or null for default UI
     */
    default ActionHandlerDescriptor getDescriptor() {
        return null;
    }
}
