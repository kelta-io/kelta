package io.kelta.worker.module;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.ModuleChangeType;
import io.kelta.runtime.event.ModuleChangedPayload;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * NATS listener for module lifecycle change events.
 * <p>
 * When a module is installed/enabled/disabled/uninstalled on any pod,
 * this listener receives the event and updates the local handler registry.
 * <p>
 * Uses a unique consumer group per pod so every pod receives every event.
 *
 * @since 1.0.0
 */
@Component
public class ModuleEventListener {

    private static final Logger log = LoggerFactory.getLogger(ModuleEventListener.class);

    private final RuntimeModuleManager runtimeModuleManager;
    private final ModuleStore moduleStore;
    private final ObjectMapper objectMapper;

    public ModuleEventListener(RuntimeModuleManager runtimeModuleManager,
                                ModuleStore moduleStore,
                                ObjectMapper objectMapper) {
        this.runtimeModuleManager = runtimeModuleManager;
        this.moduleStore = moduleStore;
        this.objectMapper = objectMapper;
    }

    public void handleModuleChanged(String message) {
        log.debug("Received module changed event: {}", message);

        try {
            ModuleChangedPayload payload = parsePayload(message);
            if (payload == null) {
                log.warn("Could not parse module changed event from message");
                return;
            }

            String tenantId = payload.getTenantId();
            String moduleId = payload.getModuleId();
            ModuleChangeType changeType = payload.getChangeType();

            log.info("Processing module {} event for '{}' (tenant={})",
                changeType, moduleId, tenantId);

            if (tenantId == null || tenantId.isBlank()) {
                log.warn("Dropping module change event with no tenantId: module={}", moduleId);
                return;
            }

            TenantContext.runWithTenant(tenantId, () -> {
                switch (changeType) {
                    case INSTALLED, ENABLED -> {
                        Optional<TenantModuleData> module =
                            moduleStore.findByTenantAndModuleId(tenantId, moduleId);
                        if (module.isPresent() && changeType == ModuleChangeType.ENABLED) {
                            // An enable is an explicit request to try again: re-run a load that
                            // failed on this pod instead of hitting the already-loaded no-op.
                            runtimeModuleManager.ensureLoaded(tenantId, module.get());
                        } else if (module.isPresent()) {
                            runtimeModuleManager.loadModule(tenantId, module.get());
                        } else {
                            log.warn("Module '{}' not found in DB for tenant {}", moduleId, tenantId);
                        }
                    }
                    // Unload by id, never by row. Uninstall deletes the row and only then
                    // publishes UNINSTALLED, so every pod except the one that served the request
                    // gets here after the row is gone. Looking it up found nothing, skipped the
                    // unload, and left the id in loadedModules — after which a reinstall hit the
                    // "already loaded" early return and registered no handlers at all, on every
                    // pod but one, while /api/modules still reported ACTIVE.
                    case DISABLED, UNINSTALLED ->
                        runtimeModuleManager.unloadModule(tenantId, moduleId);
                }
            });
        } catch (Exception e) {
            log.error("Error processing module changed event: {}", e.getMessage(), e);
        }
    }

    private ModuleChangedPayload parsePayload(String message) {
        try {
            var tree = objectMapper.readTree(message);
            if (tree.has("payload")) {
                var payloadNode = tree.get("payload");
                return objectMapper.treeToValue(payloadNode, ModuleChangedPayload.class);
            }
            return objectMapper.readValue(message, ModuleChangedPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse module changed event: {}", e.getMessage());
            return null;
        }
    }
}
