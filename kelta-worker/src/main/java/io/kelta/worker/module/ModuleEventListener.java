package io.kelta.worker.module;

import io.kelta.runtime.event.ModuleChangedPayload;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * NATS listener for module lifecycle change events.
 * <p>
 * When a module is installed, upgraded, enabled, disabled or uninstalled on any pod, this
 * listener receives the event and has {@link ExternalModuleRegistry} re-read the install row and
 * re-register (or drop) the module's handlers, hooks and service port on this pod.
 * <p>
 * Uses a unique consumer group per pod so every pod receives every event.
 *
 * @since 1.0.0
 */
@Component
public class ModuleEventListener {

    private static final Logger log = LoggerFactory.getLogger(ModuleEventListener.class);

    private final ObjectMapper objectMapper;

    /** Absent when module support is switched off ({@code kelta.modules.runtime.enabled=false}). */
    private ExternalModuleRegistry externalModules;

    public ModuleEventListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setExternalModuleRegistry(ExternalModuleRegistry externalModules) {
        this.externalModules = externalModules;
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

            log.info("Processing module {} event for '{}' (tenant={})",
                payload.getChangeType(), moduleId, tenantId);

            if (tenantId == null || tenantId.isBlank()) {
                log.warn("Dropping module change event with no tenantId: module={}", moduleId);
                return;
            }

            // Whatever the change, re-read the row and re-register from it. By id, never by a
            // row looked up here: uninstall deletes the row and only then publishes UNINSTALLED,
            // so every pod but the one that served the request sees no row, and refresh() must
            // then drop the registrations rather than skip them.
            if (externalModules != null) {
                externalModules.refresh(tenantId, moduleId); // binds the tenant itself
            }
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
