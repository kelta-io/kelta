package io.kelta.gateway.listener;

import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Listener for configuration change events.
 *
 * <p>Handles:
 * <ul>
 *   <li>Collection changed events: Updates route registry when collections are created/updated/deleted</li>
 *   <li>Worker assignment changed events: Updates routes when collections are assigned to workers</li>
 * </ul>
 *
 * <p>All event processing handles malformed events gracefully by logging errors
 * and continuing to process subsequent events. Messages are manually deserialized
 * using {@link ObjectMapper}.
 */
@Component
public class ConfigEventListener {

    private static final Logger logger = LoggerFactory.getLogger(ConfigEventListener.class);

    private final RouteRegistry routeRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final String workerServiceUrl;

    public ConfigEventListener(RouteRegistry routeRegistry,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher applicationEventPublisher,
                              @org.springframework.beans.factory.annotation.Value("${kelta.gateway.worker-service-url:http://emf-worker:80}") String workerServiceUrl) {
        this.routeRegistry = routeRegistry;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.workerServiceUrl = workerServiceUrl;
    }

    /**
     * Handles collection changed events.
     *
     * <p>Accepts raw JSON strings and manually deserializes the payload.
     */
    public void handleCollectionChanged(String message) {
        try {
            logger.debug("Received collection changed event: {}", message);

            CollectionChangedPayload payload = parseCollectionPayload(message);

            if (payload == null) {
                logger.warn("Could not parse collection changed event from message");
                return;
            }
            String tenantId = parseEnvelopeTenantId(message);

            logger.info("Processing collection change: id={}, name={}, changeType={}, tenantId={}",
                        payload.getId(), payload.getName(), payload.getChangeType(), tenantId);

            if (payload.getChangeType() == ChangeType.DELETED) {
                routeRegistry.removeRoute(payload.getId());
                logger.info("Removed route for deleted collection: id={}, name={}",
                           payload.getId(), payload.getName());
            } else {
                // Note: an UPDATED event does not reliably carry `active` — several publishers
                // (field, validation-rule and approval hooks) send UPDATED with the primitive
                // default of false — so a collection that turns inactive keeps its route until
                // the next bootstrap. Do not treat active=false as a removal here.
                RouteDefinition route = buildRouteFromCollection(payload, tenantId);

                if (route != null) {
                    routeRegistry.updateRoute(route);
                    logger.info("Updated route for collection: id={}, name={}, path={}",
                               payload.getId(), payload.getName(), route.getPath());
                } else {
                    logger.error("Failed to build route from collection: id={}, name={}",
                                payload.getId(), payload.getName());
                }
            }

            applicationEventPublisher.publishEvent(new RefreshRoutesEvent(this));

        } catch (Exception e) {
            logger.error("Error processing collection changed event: {}", e.getMessage(), e);
        }
    }

    /**
     * Parses the CollectionChangedPayload from the raw NATS message.
     * Handles both PlatformEvent wrapper format and flat JSON format.
     */
    private CollectionChangedPayload parseCollectionPayload(String message) {
        try {
            var tree = objectMapper.readTree(message);

            if (tree.has("payload")) {
                return objectMapper.treeToValue(tree.get("payload"), CollectionChangedPayload.class);
            }

            return objectMapper.readValue(message, CollectionChangedPayload.class);

        } catch (Exception e) {
            logger.error("Failed to parse collection changed event: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The owning tenant travels on the event envelope ({@code PlatformEvent.tenantId}),
     * not in the collection payload. Null when the message is a bare payload.
     */
    private String parseEnvelopeTenantId(String message) {
        try {
            var tree = objectMapper.readTree(message);
            if (tree.hasNonNull("tenantId")) {
                return tree.get("tenantId").asText();
            }
        } catch (Exception e) {
            logger.debug("No tenantId on collection event envelope: {}", e.getMessage());
        }
        return null;
    }

    private RouteDefinition buildRouteFromCollection(CollectionChangedPayload payload, String tenantId) {
        try {
            String collectionId = payload.getId();
            String collectionName = payload.getName();

            if (collectionId == null || collectionName == null) {
                logger.error("Missing required fields in collection payload: id={}, name={}",
                            collectionId, collectionName);
                return null;
            }

            String path = "/api/" + collectionName + "/**";

            return new RouteDefinition(
                collectionId,
                path,
                workerServiceUrl,
                collectionName,
                null,
                0,
                tenantId
            );

        } catch (Exception e) {
            logger.error("Error building route from collection: {}", payload, e);
            return null;
        }
    }

    /**
     * Handles worker assignment changed events.
     *
     * <p>Accepts raw JSON strings and manually deserializes the payload.
     */
    public void handleWorkerAssignmentChanged(String message) {
        try {
            logger.debug("Received worker assignment event: {}", message);

            Map<String, Object> payload = parseWorkerAssignmentPayload(message);

            if (payload == null) {
                logger.warn("Could not parse worker assignment event from message");
                return;
            }

            String workerId = (String) payload.get("workerId");
            String collectionId = (String) payload.get("collectionId");
            String collectionName = (String) payload.get("collectionName");
            String changeType = (String) payload.get("changeType");

            logger.info("Processing worker assignment: workerId={}, collectionId={}, collectionName={}, changeType={}",
                        workerId, collectionId, collectionName, changeType);

            if ("DELETED".equals(changeType)) {
                routeRegistry.removeRoute(collectionId);
                logger.info("Removed route for unassigned collection: {}", collectionName);
            } else {
                if (collectionName == null || collectionId == null) {
                    logger.error("Missing required fields in worker assignment event: " +
                                "collectionId={}, collectionName={}",
                                collectionId, collectionName);
                    return;
                }

                // Always use the configured worker service URL (K8s Service DNS) instead
                // of the pod-specific IP from the event. Pod IPs are ephemeral and become
                // stale when pods restart, causing routing failures.
                String path = "/api/" + collectionName + "/**";
                String tenantId = payload.get("tenantId") instanceof String t && !t.isBlank()
                        ? t : parseEnvelopeTenantId(message);
                RouteDefinition route = new RouteDefinition(
                    collectionId,
                    path,
                    workerServiceUrl,
                    collectionName,
                    null,
                    0,
                    tenantId
                );

                routeRegistry.updateRoute(route);
                logger.info("Added/updated route for worker-assigned collection: path={}, workerUrl={}",
                            path, workerServiceUrl);
            }

            applicationEventPublisher.publishEvent(new RefreshRoutesEvent(this));

        } catch (Exception e) {
            logger.error("Error processing worker assignment event: {}", e.getMessage(), e);
        }
    }

    /**
     * Parses the worker assignment payload from the raw NATS message.
     * Handles both PlatformEvent wrapper format and flat JSON format.
     */
    private Map<String, Object> parseWorkerAssignmentPayload(String message) {
        try {
            var tree = objectMapper.readTree(message);

            if (tree.has("payload")) {
                return objectMapper.convertValue(tree.get("payload"),
                        new TypeReference<Map<String, Object>>() {});
            }

            return objectMapper.readValue(message,
                    new TypeReference<Map<String, Object>>() {});

        } catch (Exception e) {
            logger.error("Failed to parse worker assignment event: {}", e.getMessage());
            return null;
        }
    }
}
