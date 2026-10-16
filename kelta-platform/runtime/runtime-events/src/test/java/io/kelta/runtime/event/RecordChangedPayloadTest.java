package io.kelta.runtime.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RecordChangedPayload JSON")
class RecordChangedPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("payloadOmitted=true round-trips through Jackson")
    void payloadOmittedRoundTrips() {
        RecordChangedPayload payload = RecordChangedPayload.updated(
                        "products", "rec-1", null, null, List.of("name"))
                .withPayloadOmitted(true);

        String json = objectMapper.writeValueAsString(payload);
        RecordChangedPayload read = objectMapper.readValue(json, RecordChangedPayload.class);

        assertTrue(json.contains("\"payloadOmitted\":true"));
        assertTrue(read.isPayloadOmitted());
        assertEquals("products", read.getCollectionName());
        assertEquals("rec-1", read.getRecordId());
        assertEquals(ChangeType.UPDATED, read.getChangeType());
        assertEquals(List.of("name"), read.getChangedFields());
        assertNull(read.getData());
    }

    @Test
    @DisplayName("payloadOmitted defaults to false and is left out of the JSON")
    void payloadOmittedDefaultIsNotSerialized() {
        RecordChangedPayload payload = RecordChangedPayload.created(
                "products", "rec-1", Map.of("name", "Widget"));

        String json = objectMapper.writeValueAsString(payload);

        assertFalse(payload.isPayloadOmitted());
        assertFalse(json.contains("payloadOmitted"));
    }

    @Test
    @DisplayName("event from an older pod without the key deserializes as payloadOmitted=false")
    void legacyEventDeserializesAsFalse() {
        String legacy = """
                {"collectionName":"products","recordId":"rec-1","changeType":"CREATED",
                 "data":{"name":"Widget"},"changedFields":[],"containsMaskedFields":false,
                 "ownerScoped":false}
                """;

        RecordChangedPayload read = objectMapper.readValue(legacy, RecordChangedPayload.class);

        assertFalse(read.isPayloadOmitted());
        assertEquals(Map.of("name", "Widget"), read.getData());
    }
}
