package io.kelta.gateway.listener;

import io.kelta.gateway.route.RouteDefinition;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.CollectionChangedPayload;
import io.kelta.runtime.event.PlatformEvent;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConfigEventListener.
 *
 * Tests the NATS event listener that updates gateway configuration in real-time.
 * The listener now accepts raw JSON strings and manually deserializes them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigEventListener Tests")
class ConfigEventListenerTest {

    @Mock
    private RouteRegistry routeRegistry;

    private ObjectMapper objectMapper;
    private ConfigEventListener listener;

    private static final String WORKER_SERVICE_URL = "http://emf-worker:80";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new ConfigEventListener(routeRegistry, objectMapper, event -> {}, WORKER_SERVICE_URL);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("Collection Changed Event Tests")
    class CollectionChangedTests {

        @Test
        @DisplayName("Should add route when collection is created")
        void shouldAddRouteWhenCollectionCreated() throws Exception {
            // Arrange
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("collection-1");
            payload.setName("users");
            payload.setChangeType(ChangeType.CREATED);

            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "config.collection.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleCollectionChanged(toJson(event));

            // Assert
            ArgumentCaptor<RouteDefinition> routeCaptor = ArgumentCaptor.forClass(RouteDefinition.class);
            verify(routeRegistry).updateRoute(routeCaptor.capture());

            RouteDefinition capturedRoute = routeCaptor.getValue();
            assertEquals("collection-1", capturedRoute.getId());
            assertEquals("users", capturedRoute.getCollectionName());
            assertEquals("/api/users/**", capturedRoute.getPath());
            assertEquals(WORKER_SERVICE_URL, capturedRoute.getBackendUrl());
        }

        @Test
        @DisplayName("Route carries the tenant from the event envelope")
        void shouldTagRouteWithEnvelopeTenant() throws Exception {
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("collection-t1");
            payload.setName("tasks");
            payload.setActive(true);
            payload.setChangeType(ChangeType.CREATED);
            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(), "config.collection.changed", "tenant-1",
                UUID.randomUUID().toString(), null, Instant.now(), payload);

            listener.handleCollectionChanged(toJson(event));

            ArgumentCaptor<RouteDefinition> routeCaptor = ArgumentCaptor.forClass(RouteDefinition.class);
            verify(routeRegistry).updateRoute(routeCaptor.capture());
            assertEquals("tenant-1", routeCaptor.getValue().getTenantId());
            assertEquals("/api/tasks/**", routeCaptor.getValue().getPath());
        }

        @Test
        @DisplayName("An UPDATED event without `active` (field/rule hooks send none) keeps the route")
        void shouldKeepRouteWhenUpdatedEventCarriesNoActiveFlag() throws Exception {
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("collection-1");
            payload.setName("customers");
            payload.setChangeType(ChangeType.UPDATED);   // active left at its primitive default
            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(), "config.collection.changed", "tenant-1",
                UUID.randomUUID().toString(), null, Instant.now(), payload);

            listener.handleCollectionChanged(toJson(event));

            verify(routeRegistry).updateRoute(any());
            verify(routeRegistry, never()).removeRoute(any());
        }

        @Test
        @DisplayName("Should update route when collection is updated")
        void shouldUpdateRouteWhenCollectionUpdated() throws Exception {
            // Arrange
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("collection-1");
            payload.setName("users");
            payload.setActive(true);
            payload.setChangeType(ChangeType.UPDATED);

            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "config.collection.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleCollectionChanged(toJson(event));

            // Assert
            verify(routeRegistry).updateRoute(any(RouteDefinition.class));
        }

        @Test
        @DisplayName("Should remove route when collection is deleted")
        void shouldRemoveRouteWhenCollectionDeleted() throws Exception {
            // Arrange
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("collection-1");
            payload.setName("users");
            payload.setChangeType(ChangeType.DELETED);

            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "config.collection.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleCollectionChanged(toJson(event));

            // Assert
            verify(routeRegistry).removeRoute("collection-1");
            verify(routeRegistry, never()).updateRoute(any());
        }

        @Test
        @DisplayName("Should handle null payload gracefully")
        void shouldHandleNullPayloadGracefully() throws Exception {
            // Arrange - JSON with null payload
            String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"payload\":null}";

            // Act & Assert - should not throw exception
            assertDoesNotThrow(() -> listener.handleCollectionChanged(json));
            verify(routeRegistry, never()).updateRoute(any());
            verify(routeRegistry, never()).removeRoute(any());
        }

        @Test
        @DisplayName("Should handle malformed JSON gracefully")
        void shouldHandleMalformedJsonGracefully() {
            // Act & Assert - should not throw exception
            assertDoesNotThrow(() -> listener.handleCollectionChanged("not-valid-json"));
            verify(routeRegistry, never()).updateRoute(any());
            verify(routeRegistry, never()).removeRoute(any());
        }

        @Test
        @DisplayName("Should handle flat payload format (without ConfigEvent wrapper)")
        void shouldHandleFlatPayloadFormat() {
            // Arrange - flat JSON without ConfigEvent wrapper
            String json = "{\"id\":\"collection-flat\",\"name\":\"flat-test\",\"changeType\":\"CREATED\"}";

            // Act
            listener.handleCollectionChanged(json);

            // Assert
            ArgumentCaptor<RouteDefinition> routeCaptor = ArgumentCaptor.forClass(RouteDefinition.class);
            verify(routeRegistry).updateRoute(routeCaptor.capture());

            RouteDefinition capturedRoute = routeCaptor.getValue();
            assertEquals("collection-flat", capturedRoute.getId());
            assertEquals("flat-test", capturedRoute.getCollectionName());
        }

        @Test
        @DisplayName("Should not add route when collection name is null")
        void shouldNotAddRouteWhenCollectionNameIsNull() throws Exception {
            // Arrange - payload with null name (e.g., from a record missing the name field)
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("collection-no-name");
            payload.setChangeType(ChangeType.CREATED);
            // name is intentionally not set (null)

            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "config.collection.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleCollectionChanged(toJson(event));

            // Assert - should not add route when name is missing
            verify(routeRegistry, never()).updateRoute(any());
            verify(routeRegistry, never()).removeRoute(any());
        }

        @Test
        @DisplayName("Should route system collections like users normally")
        void shouldRouteSystemCollectionsNormally() throws Exception {
            // Arrange — system collections (users, profiles, etc.) should be routed to worker
            CollectionChangedPayload payload = new CollectionChangedPayload();
            payload.setId("sys-users-id");
            payload.setName("users");
            payload.setChangeType(ChangeType.CREATED);

            PlatformEvent<CollectionChangedPayload> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "config.collection.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleCollectionChanged(toJson(event));

            // Assert — system collections ARE routed
            ArgumentCaptor<RouteDefinition> routeCaptor = ArgumentCaptor.forClass(RouteDefinition.class);
            verify(routeRegistry).updateRoute(routeCaptor.capture());

            RouteDefinition route = routeCaptor.getValue();
            assertEquals("sys-users-id", route.getId());
            assertEquals("users", route.getCollectionName());
            assertEquals("/api/users/**", route.getPath());
        }
    }

    @Nested
    @DisplayName("Worker Assignment Changed Event Tests")
    class WorkerAssignmentChangedTests {

        @Test
        @DisplayName("Should add route using configured service URL, not pod IP from event")
        void shouldAddRouteUsingConfiguredServiceUrl() throws Exception {
            // Arrange - event contains pod-specific URL, but gateway should ignore it
            Map<String, Object> payload = new HashMap<>();
            payload.put("workerId", "worker-1");
            payload.put("collectionId", "collection-1");
            payload.put("workerBaseUrl", "http://10.1.150.150:8080");
            payload.put("collectionName", "accounts");
            payload.put("changeType", "CREATED");

            PlatformEvent<Object> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "kelta.worker.assignment.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleWorkerAssignmentChanged(toJson(event));

            // Assert - should use configured worker service URL, not pod IP
            ArgumentCaptor<RouteDefinition> routeCaptor = ArgumentCaptor.forClass(RouteDefinition.class);
            verify(routeRegistry).updateRoute(routeCaptor.capture());

            RouteDefinition capturedRoute = routeCaptor.getValue();
            assertEquals("collection-1", capturedRoute.getId());
            assertEquals("/api/accounts/**", capturedRoute.getPath());
            assertEquals(WORKER_SERVICE_URL, capturedRoute.getBackendUrl());
            assertEquals("accounts", capturedRoute.getCollectionName());
        }

        @Test
        @DisplayName("Should remove route when collection is unassigned from worker")
        void shouldRemoveRouteWhenCollectionUnassigned() throws Exception {
            // Arrange
            Map<String, Object> payload = new HashMap<>();
            payload.put("workerId", "worker-1");
            payload.put("collectionId", "collection-1");
            payload.put("collectionName", "accounts");
            payload.put("changeType", "DELETED");

            PlatformEvent<Object> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "kelta.worker.assignment.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleWorkerAssignmentChanged(toJson(event));

            // Assert
            verify(routeRegistry).removeRoute("collection-1");
            verify(routeRegistry, never()).updateRoute(any());
        }

        @Test
        @DisplayName("Should handle null payload gracefully")
        void shouldHandleNullPayloadGracefully() {
            // Arrange - JSON with null payload
            String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"payload\":null}";

            // Act & Assert - should not throw exception
            assertDoesNotThrow(() -> listener.handleWorkerAssignmentChanged(json));
            verify(routeRegistry, never()).updateRoute(any());
            verify(routeRegistry, never()).removeRoute(any());
        }

        @Test
        @DisplayName("Should handle missing required fields gracefully")
        void shouldHandleMissingRequiredFieldsGracefully() throws Exception {
            // Arrange - missing collectionName
            Map<String, Object> payload = new HashMap<>();
            payload.put("workerId", "worker-1");
            payload.put("collectionId", "collection-1");
            payload.put("changeType", "CREATED");
            // collectionName is missing

            PlatformEvent<Object> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "kelta.worker.assignment.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleWorkerAssignmentChanged(toJson(event));

            // Assert - should not add route when required fields are missing
            verify(routeRegistry, never()).updateRoute(any());
        }

        @Test
        @DisplayName("Should route system collection worker assignment normally")
        void shouldRouteSystemCollectionWorkerAssignmentNormally() throws Exception {
            // Arrange — system collections (profiles, etc.) assigned to worker should be routed
            Map<String, Object> payload = new HashMap<>();
            payload.put("workerId", "worker-1");
            payload.put("collectionId", "sys-profiles-id");
            payload.put("workerBaseUrl", "http://10.1.150.150:8080");
            payload.put("collectionName", "profiles");
            payload.put("changeType", "CREATED");

            PlatformEvent<Object> event = new PlatformEvent<>(
                UUID.randomUUID().toString(),
                "kelta.worker.assignment.changed",
                null,
                UUID.randomUUID().toString(),
                null,
                Instant.now(),
                payload
            );

            // Act
            listener.handleWorkerAssignmentChanged(toJson(event));

            // Assert — system collections ARE routed
            ArgumentCaptor<RouteDefinition> routeCaptor = ArgumentCaptor.forClass(RouteDefinition.class);
            verify(routeRegistry).updateRoute(routeCaptor.capture());

            RouteDefinition route = routeCaptor.getValue();
            assertEquals("sys-profiles-id", route.getId());
            assertEquals("profiles", route.getCollectionName());
            assertEquals(WORKER_SERVICE_URL, route.getBackendUrl());
        }

    }
}
