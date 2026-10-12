package io.kelta.worker.config;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Registers every {@link BeforeSaveHook} bean with the {@link BeforeSaveHookRegistry} as the bean
 * is initialized — so a platform hook is a {@code @Component} (or a {@code @Bean}, when one class
 * serves several collections) and needs no hand-written {@code hookRegistry.register(...)}.
 *
 * <p>Why a post-processor and not {@code new BeforeSaveHookRegistry(List<BeforeSaveHook>)}:
 * the registry cannot depend on the hooks, because hooks depend (through {@code QueryEngine},
 * {@code CollectionLifecycleManager}, ...) on the registry — {@code DefaultQueryEngine} runs
 * them. Registering at initialization also keeps the timing the hand-wired {@code @Bean}
 * methods had: a hook is in the registry as soon as it exists.
 *
 * <p>Registration order is irrelevant to execution order: the registry sorts each collection's
 * hooks by {@link BeforeSaveHook#getOrder()}, and no two hooks on one collection share an order
 * ({@code BeforeSaveHookWiringTest} enforces it), since bean discovery order is unspecified.
 *
 * <p>Only global (platform) hooks come through here. Compile-time module hooks are registered by
 * {@code ModuleRegistry}, and tenant-scoped runtime-module hooks by {@code RuntimeModuleManager}
 * ({@link BeforeSaveHookRegistry#registerTenantHook}); neither kind is a Spring bean.
 *
 * <p>The registry is looked up lazily so this post-processor, which Spring creates before
 * ordinary beans, does not force {@code FlowConfig} into existence early. With
 * {@code kelta.flow.enabled=false} there is no registry and nothing is registered.
 *
 * @since 1.0.0
 */
@Component
public class BeforeSaveHookRegistrar implements BeanPostProcessor {

    private final ObjectProvider<BeforeSaveHookRegistry> registry;

    public BeforeSaveHookRegistrar(ObjectProvider<BeforeSaveHookRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BeforeSaveHook hook) {
            BeforeSaveHookRegistry hookRegistry = registry.getIfAvailable();
            if (hookRegistry != null) {
                hookRegistry.register(hook);
            }
        }
        return bean;
    }
}
