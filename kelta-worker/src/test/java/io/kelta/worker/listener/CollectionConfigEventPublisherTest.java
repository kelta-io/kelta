package io.kelta.worker.listener;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("CollectionConfigEventPublisher")
class CollectionConfigEventPublisherTest {

    private PlatformEventPublisher eventPublisher;
    private CollectionLifecycleManager lifecycleManager;
    private CollectionConfigEventPublisher publisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(PlatformEventPublisher.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        publisher = new CollectionConfigEventPublisher(eventPublisher, lifecycleManager);
    }

    @Test
    @DisplayName("Should target 'collections' collection")
    void shouldTargetCollections() {
        assertEquals("collections", publisher.getCollectionName());
    }

    @Test
    @DisplayName("Should have order 100 to run after schema hooks")
    void shouldHaveOrderAfterSchemaHooks() {
        assertEquals(100, publisher.getOrder());
    }

    @Test
    @DisplayName("Should publish CREATED event wrapped in PlatformEvent")
    void shouldPublishCreatedEvent() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-1",
            "name", "orders",
            "displayName", "Orders",
            "active", true,
            "currentVersion", 1
        ));

        publisher.afterCreate(record, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("Should publish UPDATED event wrapped in PlatformEvent")
    void shouldPublishUpdatedEvent() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-1",
            "name", "orders",
            "active", true
        ));
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1",
            "name", "orders"
        ));

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("Should not publish event when record is missing name field")
    void shouldNotPublishEventWhenNameMissing() {
        Map<String, Object> record = new HashMap<>(Map.of(
            "id", "col-no-name",
            "active", true
        ));
        // name is intentionally missing

        publisher.afterCreate(record, "tenant-1");

        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("Should publish DELETED event wrapped in PlatformEvent")
    void shouldPublishDeletedEvent() {
        publisher.afterDelete("col-1", "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("An ownership change publishes and refreshes this pod synchronously")
    void ownershipChangeRefreshesLocally() {
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "ownerScope", "NONE", "ownerScopeReads", true));
        Map<String, Object> record = new HashMap<>(previous);
        record.put("ownerField", "createdBy");
        record.put("ownerScope", "PORTAL");

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
        verify(lifecycleManager).refreshOrInitializeLocally("col-1");
    }

    @Test
    @DisplayName("Turning off owner-scoped reads refreshes this pod synchronously")
    void ownerScopeReadsChangeRefreshesLocally() {
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "ownerField", "createdBy", "ownerScope", "ALL",
            "ownerScopeReads", true));
        Map<String, Object> record = new HashMap<>(previous);
        record.put("ownerScopeReads", false);

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(lifecycleManager).refreshOrInitializeLocally("col-1");
    }

    @Test
    @DisplayName("An update that leaves ownership alone only publishes")
    void nonOwnershipUpdateDoesNotRefreshLocally() {
        Map<String, Object> previous = new HashMap<>(Map.of(
            "id", "col-1", "name", "orders", "displayName", "Orders", "ownerScope", "NONE"));
        Map<String, Object> record = new HashMap<>(previous);
        record.put("displayName", "All orders");

        publisher.afterUpdate("col-1", record, previous, "tenant-1");

        verify(eventPublisher).publish(anyString(), any());
        verifyNoInteractions(lifecycleManager);
    }
}
