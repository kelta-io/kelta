package io.kelta.runtime.workflow.module;

import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.BeforeSaveHook;

import java.util.List;

/**
 * Interface for Kelta platform modules that extend workflow capabilities.
 *
 * <p>A module packages a set of {@link ActionHandler}s and {@link BeforeSaveHook}s
 * that are registered with the runtime when the module starts up. Modules are
 * discovered via Spring classpath scanning and initialized by the {@link ModuleRegistry}.
 *
 * <p>To create a new module:
 * <ol>
 *   <li>Create a class that implements this interface</li>
 *   <li>Annotate it with {@code @Component}</li>
 *   <li>Return action handlers and hooks from the getter methods</li>
 *   <li>Optionally implement {@link #onStartup(ModuleContext)} for initialization</li>
 * </ol>
 *
 * @since 1.0.0
 */
public interface KeltaModule {

    /**
     * Returns the unique identifier for this module.
     *
     * @return the module ID (e.g., "kelta-core", "kelta-communication")
     */
    String getId();

    /**
     * Returns the display name for this module.
     *
     * @return the module name (e.g., "Core Module", "Communication Module")
     */
    String getName();

    /**
     * Returns the version of this module.
     *
     * @return the version string (e.g., "1.0.0")
     */
    String getVersion();

    /**
     * Returns the action handlers provided by this module.
     * These will be registered in the {@link io.kelta.runtime.workflow.ActionHandlerRegistry}.
     *
     * @return the list of action handlers (may be empty, must not be null)
     */
    default List<ActionHandler> getActionHandlers() {
        return List.of();
    }

    /**
     * Returns the before-save hooks provided by this module.
     * These will be registered in the {@link io.kelta.runtime.workflow.BeforeSaveHookRegistry}.
     *
     * @return the list of before-save hooks (may be empty, must not be null)
     */
    default List<BeforeSaveHook> getBeforeSaveHooks() {
        return List.of();
    }

    /**
     * Called when the module is started up. Use this for initialization logic
     * that requires access to runtime services.
     *
     * @param context the module context with references to core services
     */
    default void onStartup(ModuleContext context) {
        // No-op by default
    }
}
