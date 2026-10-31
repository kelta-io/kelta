package io.kelta.worker.module;

import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.event.ModuleChangedPayload;
import io.kelta.runtime.module.TenantModuleData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes module lifecycle events for cross-pod propagation.
 * <p>
 * When a module is installed, upgraded, enabled, disabled, or uninstalled on one pod,
 * this publisher sends an event so all other pods can update their handler registries.
 *
 * @since 1.0.0
 */
public class ModuleConfigEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ModuleConfigEventPublisher.class);

    private static final String SUBJECT_PREFIX = "kelta.config.module.changed.";

    private final PlatformEventPublisher eventPublisher;

    public ModuleConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes a module lifecycle change event.
     *
     * @param module     the module data
     * @param changeType the type of change
     */
    public void publishEvent(TenantModuleData module, ModuleChangeType changeType) {
        ModuleChangedPayload payload = new ModuleChangedPayload(
            module.id(), module.tenantId(), module.moduleId(), module.name(),
            module.version(), module.manifest(), changeType
        );

        PlatformEvent<ModuleChangedPayload> event =
                EventFactory.createEvent("kelta.config.module.changed", payload);
        String subject = SUBJECT_PREFIX + module.tenantId() + "." + module.moduleId();
        log.info("Publishing module {} event for '{}' v{} (tenant={}) to '{}'",
                changeType, module.moduleId(), module.version(), module.tenantId(), subject);
        eventPublisher.publish(subject, event);
    }
}
