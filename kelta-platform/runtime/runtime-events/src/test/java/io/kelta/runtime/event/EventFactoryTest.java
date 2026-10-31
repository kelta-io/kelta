package io.kelta.runtime.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventFactory Tests")
class EventFactoryTest {

    @Nested
    @DisplayName("createEvent with correlationId")
    class CreateEventWithCorrelation {

        @Test
        void shouldCreateEventWithCorrelationId() {
            Instant before = Instant.now();
            PlatformEvent<String> event = EventFactory.createEvent("test.event", "corr-123", "payload");
            Instant after = Instant.now();

            assertNotNull(event.getEventId());
            assertEquals("test.event", event.getEventType());
            assertEquals("corr-123", event.getCorrelationId());
            assertEquals("payload", event.getPayload());
            assertNull(event.getTenantId());
            assertNull(event.getUserId());
            assertNotNull(event.getTimestamp());
            assertFalse(event.getTimestamp().isBefore(before));
            assertFalse(event.getTimestamp().isAfter(after));
        }

        @Test
        void shouldGenerateUniqueIds() {
            PlatformEvent<String> e1 = EventFactory.createEvent("test", "c1", "p1");
            PlatformEvent<String> e2 = EventFactory.createEvent("test", "c1", "p1");
            assertNotEquals(e1.getEventId(), e2.getEventId());
        }
    }

    @Nested
    @DisplayName("createEvent without correlationId")
    class CreateEventWithoutCorrelation {

        @Test
        void shouldCreateEventWithNullCorrelationId() {
            PlatformEvent<String> event = EventFactory.createEvent("test.event", "payload");

            assertNotNull(event.getEventId());
            assertEquals("test.event", event.getEventType());
            assertNull(event.getCorrelationId());
            assertEquals("payload", event.getPayload());
        }
    }

    @Nested
    @DisplayName("createRecordEvent")
    class CreateRecordEvent {

        @Test
        void shouldCreateRecordEventWithTenantAndUser() {
            var payload = RecordChangedPayload.created("products", "rec-1", Map.of("name", "Test"));
            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.created", "tenant-1", "user-1", payload);

            assertNotNull(event.getEventId());
            assertEquals("record.created", event.getEventType());
            assertEquals("tenant-1", event.getTenantId());
            assertEquals("user-1", event.getUserId());
            assertNull(event.getCorrelationId());
            assertNotNull(event.getTimestamp());
            assertEquals(payload, event.getPayload());
        }

        @Test
        void shouldAcceptNullUserId() {
            var payload = RecordChangedPayload.deleted("col", "r1", Map.of());
            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.deleted", "tenant-1", null, payload);

            assertNull(event.getUserId());
            assertEquals("tenant-1", event.getTenantId());
        }
    }

    @Nested
    @DisplayName("Generic payload types")
    class GenericPayloadTypes {

        @Test
        void shouldWorkWithCollectionChangedPayload() {
            var payload = new CollectionChangedPayload();
            payload.setName("contacts");
            payload.setChangeType(ChangeType.CREATED);

            PlatformEvent<CollectionChangedPayload> event = EventFactory.createEvent(
                    "collection.changed", "corr-1", payload);

            assertEquals("contacts", event.getPayload().getName());
            assertEquals(ChangeType.CREATED, event.getPayload().getChangeType());
        }

        @Test
        void shouldWorkWithModuleChangedPayload() {
            var payload = new ModuleChangedPayload(
                    "id-1", "t-1", "m-1", "Mod", "1.0", "{}", ModuleChangeType.INSTALLED);

            PlatformEvent<ModuleChangedPayload> event = EventFactory.createEvent(
                    "module.changed", payload);

            assertEquals("Mod", event.getPayload().getName());
            assertEquals(ModuleChangeType.INSTALLED, event.getPayload().getChangeType());
        }
    }
}
