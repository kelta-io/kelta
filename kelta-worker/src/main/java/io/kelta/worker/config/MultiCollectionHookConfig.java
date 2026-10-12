package io.kelta.worker.config;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.listener.MenuConfigEventPublisher;
import io.kelta.worker.listener.ProfilePermissionSyncHook;
import io.kelta.worker.service.CerbosPolicySyncCoalescer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Before-save hooks whose class serves more than one collection, so they cannot be a single
 * {@code @Component}: one bean per collection, each taking the collection name.
 *
 * <p>Every other platform hook is a {@code @Component}. Either way the bean is registered with
 * the {@code BeforeSaveHookRegistry} by {@link BeforeSaveHookRegistrar} — nothing here calls
 * {@code register}.
 *
 * @since 1.0.0
 */
@Configuration
public class MultiCollectionHookConfig {

    @Bean
    public ProfilePermissionSyncHook objectPermissionSyncHook(CerbosPolicySyncCoalescer syncCoalescer) {
        return new ProfilePermissionSyncHook("profile-object-permissions", syncCoalescer);
    }

    @Bean
    public ProfilePermissionSyncHook systemPermissionSyncHook(CerbosPolicySyncCoalescer syncCoalescer) {
        return new ProfilePermissionSyncHook("profile-system-permissions", syncCoalescer);
    }

    /**
     * Apps/nav v2: menu + menu-item writes broadcast kelta.config.menu.changed.&lt;tenantId&gt;
     * so every pod evicts its cached navigation config (MenuCacheInvalidationListener).
     */
    @Bean
    public MenuConfigEventPublisher menuConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        return new MenuConfigEventPublisher(eventPublisher, "ui-menus");
    }

    @Bean
    public MenuConfigEventPublisher menuItemConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        return new MenuConfigEventPublisher(eventPublisher, "ui-menu-items");
    }
}
