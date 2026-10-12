package io.kelta.worker.module;

import io.kelta.runtime.flow.ActionHandlerDescriptor;
import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.runtime.workflow.module.KeltaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a module into platform registry entries for one tenant: its before-save hooks and
 * published services, and the stub or refusing action handlers that stand in when its real code
 * is not running.
 *
 * <p>Stateless. What each load registered — and therefore what unload must remove — is tracked
 * by {@link ModuleLoader}, which owns all per-load state.
 */
final class ModuleRegistrar {

    /**
     * Logs under {@link RuntimeModuleManager}'s name rather than this class's: this code was split
     * out of that class, and log queries and alerts select on the logger name.
     */
    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);

    private final ActionHandlerRegistry actionHandlerRegistry;
    private final BeforeSaveHookRegistry beforeSaveHookRegistry;
    private final ModuleServiceRegistry moduleServiceRegistry;

    ModuleRegistrar(ActionHandlerRegistry actionHandlerRegistry,
                    BeforeSaveHookRegistry beforeSaveHookRegistry,
                    ModuleServiceRegistry moduleServiceRegistry) {
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.beforeSaveHookRegistry = beforeSaveHookRegistry;
        this.moduleServiceRegistry = moduleServiceRegistry;
    }

    /**
     * Registers handlers that refuse to run for every action the manifest declares.
     *
     * <p>Registering rather than leaving the keys absent is deliberate: an absent key surfaces to a
     * flow as {@code ResourceNotFound}, which an admin reads as a typo in the step. A refusing
     * handler names the module and the reason.
     */
    void registerRefusingHandlers(String tenantId, TenantModuleData module, String reason) {
        for (var action : module.actions()) {
            actionHandlerRegistry.registerTenantHandler(tenantId,
                createRefusingHandler(action, module, reason));
        }
    }

    /**
     * Registers a module's before-save hooks against the installing tenant, returning the exact
     * instances registered so unload can remove them by identity. Returns empty when no hook
     * registry is wired (stub-only test setups) — hooks are then simply not active.
     */
    /**
     * Publishes the module's platform-callable services for this tenant.
     *
     * <p>Unwinds itself: if one port is rejected -- a duplicate for the tenant, or an interface the
     * module bundled rather than the platform's -- the ports already accepted from this same module
     * are removed before the exception propagates, so the caller's fallback-to-stubs path never
     * leaves a partially published module answering platform calls.
     *
     * @return the exact instances registered, for unload to remove by identity
     */
    Map<Class<?>, Object> registerTenantServices(String tenantId, KeltaModule keltaModule,
                                                 ModuleManifest manifest) {
        if (moduleServiceRegistry == null) {
            return Map.of();
        }
        Map<Class<?>, Object> declared = keltaModule.getServices();
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Set<String> allowed = manifest == null || manifest.services() == null
                ? Set.of() : Set.copyOf(manifest.services());
        Map<Class<?>, Object> registered = new java.util.LinkedHashMap<>();
        try {
            declared.forEach((port, service) -> {
                // A module may only publish ports its manifest declares. Without this, a module an
                // admin installed for one purpose can publish any platform port and silently become
                // the tenant's authority for something unrelated -- the code decides, and nobody is
                // ever shown the claim. The manifest entry is what an admin can be told about and
                // approve.
                if (!allowed.contains(port.getName())) {
                    throw new IllegalStateException("Module '"
                        + (manifest == null ? "?" : manifest.id())
                        + "' tried to publish " + port.getName()
                        + ", which its manifest does not declare in \"services\"");
                }
                moduleServiceRegistry.register(tenantId, port, service);
                registered.put(port, service);
            });
        } catch (RuntimeException e) {
            moduleServiceRegistry.remove(tenantId, registered);
            throw e;
        }
        return Map.copyOf(registered);
    }

    List<BeforeSaveHook> registerTenantHooks(String tenantId, KeltaModule keltaModule) {
        if (beforeSaveHookRegistry == null) {
            return List.of();
        }
        List<BeforeSaveHook> hooks = List.copyOf(keltaModule.getBeforeSaveHooks());
        for (BeforeSaveHook hook : hooks) {
            beforeSaveHookRegistry.registerTenantHook(tenantId, hook);
        }
        return hooks;
    }

    /**
     * Loads stub handlers from manifest metadata (no JAR available).
     */
    void loadWithStubs(String tenantId, TenantModuleData module) {
        for (var action : module.actions()) {
            ActionHandler stubHandler = createStubHandler(action, module);
            actionHandlerRegistry.registerTenantHandler(tenantId, stubHandler);
        }
        log.debug("Loaded module '{}' with stub handlers for tenant {}", module.moduleId(), tenantId);
    }

    /**
     * A handler that refuses to run, naming the module and why.
     *
     * <p>The descriptor is still manifest-derived so the flow designer renders the step normally
     * rather than showing a blank, and the failure carries a stable {@code ModuleUnavailable} code
     * a flow's Catch clause can be written against.
     */
    private ActionHandler createRefusingHandler(TenantModuleData.TenantModuleActionData action,
                                                 TenantModuleData module, String reason) {
        return new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return action.actionKey();
            }

            @Override
            public ActionResult execute(ActionContext context) {
                // Null-safe on purpose: the refusal itself must be what surfaces, not an NPE
                // from the log line.
                log.error("Refused action '{}': module '{}' v{} is not running for tenant {} ({})",
                    action.actionKey(), module.moduleId(), module.version(),
                    context == null ? module.tenantId() : context.tenantId(), reason);
                throw new ModuleUnavailableException(
                    module.moduleId(), module.version(), reason);
            }

            @Override
            public ActionHandlerDescriptor getDescriptor() {
                return new ActionHandlerDescriptor() {
                    @Override public String getDisplayName() { return action.name(); }
                    @Override public String getCategory() { return action.category(); }
                    @Override public String getDescription() { return action.description(); }
                    @Override public String getConfigSchema() { return action.configSchema(); }
                    @Override public String getInputSchema() { return action.inputSchema(); }
                    @Override public String getOutputSchema() { return action.outputSchema(); }
                };
            }
        };
    }

    /**
     * Creates a stub action handler from manifest metadata.
     * Used when no JAR is available for real ClassLoader-based loading.
     */
    private ActionHandler createStubHandler(TenantModuleData.TenantModuleActionData action,
                                             TenantModuleData module) {
        return new ActionHandler() {
            @Override
            public String getActionTypeKey() {
                return action.actionKey();
            }

            @Override
            public ActionResult execute(ActionContext context) {
                log.info("Executing runtime module handler '{}' from module '{}' v{} (stub mode)",
                    action.actionKey(), module.name(), module.version());
                return ActionResult.success(Map.of(
                    "handler", action.actionKey(),
                    "module", module.moduleId(),
                    "status", "EXECUTED",
                    "mode", "stub"
                ));
            }

            @Override
            public ActionHandlerDescriptor getDescriptor() {
                return new ActionHandlerDescriptor() {
                    @Override public String getConfigSchema() { return action.configSchema(); }
                    @Override public String getInputSchema() { return action.inputSchema(); }
                    @Override public String getOutputSchema() { return action.outputSchema(); }
                    @Override public String getDisplayName() { return action.name(); }
                    @Override public String getCategory() { return action.category(); }
                    @Override public String getDescription() { return action.description(); }
                };
            }
        };
    }
}
