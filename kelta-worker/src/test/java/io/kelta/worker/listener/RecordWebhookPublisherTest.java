package io.kelta.worker.listener;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("RecordWebhookPublisher")
class RecordWebhookPublisherTest {

    private MockRestServiceServer server;
    private ObjectMapper objectMapper;
    private RecordWebhookPublisher publisher;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder()
                .baseUrl("http://svix.test")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token");
        server = MockRestServiceServer.bindTo(builder).build();
        objectMapper = new ObjectMapper();
        publisher = new RecordWebhookPublisher(builder.build(), objectMapper);
    }

    private String buildEventJson(String tenantId, String collectionName, String recordId, ChangeType changeType) {
        RecordChangedPayload payload = switch (changeType) {
            case CREATED -> RecordChangedPayload.created(collectionName, recordId, Map.of("name", "Acme"));
            case UPDATED -> RecordChangedPayload.updated(collectionName, recordId,
                    Map.of("name", "Acme Updated"), Map.of("name", "Acme"), List.of("name"));
            case DELETED -> RecordChangedPayload.deleted(collectionName, recordId, Map.of("name", "Acme"));
        };

        var event = new PlatformEvent<RecordChangedPayload>();
        event.setEventId(UUID.randomUUID().toString());
        event.setTenantId(tenantId);
        event.setTimestamp(Instant.now());
        event.setPayload(payload);

        return objectMapper.writeValueAsString(event);
    }

    @Test
    @DisplayName("publishes record.created event with collection as channel")
    void publishesCreatedEvent() {
        String json = buildEventJson("tenant-1", "orders", "rec-123", ChangeType.CREATED);

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-1/msg/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(jsonPath("$.eventType").value("record.created"))
                .andExpect(jsonPath("$.channels[0]").value("orders"))
                .andExpect(jsonPath("$.payload.recordId").value("rec-123"))
                .andExpect(jsonPath("$.payload.collectionName").value("orders"))
                .andExpect(jsonPath("$.payload.changeType").value("CREATED"))
                .andRespond(withSuccess());

        publisher.onRecordChanged(json);

        server.verify();
    }

    @Test
    @DisplayName("publishes record.updated event with previousData and changedFields")
    void publishesUpdatedEventWithDiff() {
        String json = buildEventJson("tenant-1", "orders", "rec-123", ChangeType.UPDATED);

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-1/msg/"))
                .andExpect(jsonPath("$.eventType").value("record.updated"))
                .andExpect(jsonPath("$.payload.previousData.name").value("Acme"))
                .andExpect(jsonPath("$.payload.changedFields[0]").value("name"))
                .andRespond(withSuccess());

        publisher.onRecordChanged(json);

        server.verify();
    }

    @Test
    @DisplayName("publishes record.deleted event")
    void publishesDeletedEvent() {
        String json = buildEventJson("tenant-2", "contacts", "rec-456", ChangeType.DELETED);

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-2/msg/"))
                .andExpect(jsonPath("$.eventType").value("record.deleted"))
                .andExpect(jsonPath("$.payload.recordId").value("rec-456"))
                .andRespond(withSuccess());

        publisher.onRecordChanged(json);

        server.verify();
    }

    @Test
    @DisplayName("forwards a slim event without data, flagged payloadOmitted")
    void forwardsSlimEvent() {
        var event = new PlatformEvent<RecordChangedPayload>();
        event.setEventId("evt-slim");
        event.setTenantId("tenant-1");
        event.setTimestamp(Instant.now());
        event.setPayload(new RecordChangedPayload("orders", "rec-123", ChangeType.UPDATED,
                null, null, List.of("name")).withPayloadOmitted(true));

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-1/msg/"))
                .andExpect(jsonPath("$.eventType").value("record.updated"))
                .andExpect(jsonPath("$.eventId").value("evt-slim"))
                .andExpect(jsonPath("$.payload.recordId").value("rec-123"))
                .andExpect(jsonPath("$.payload.payloadOmitted").value(true))
                .andExpect(jsonPath("$.payload.changedFields[0]").value("name"))
                .andExpect(jsonPath("$.payload.data").doesNotExist())
                .andExpect(jsonPath("$.payload.previousData").doesNotExist())
                .andRespond(withSuccess());

        publisher.onRecordChanged(objectMapper.writeValueAsString(event));

        server.verify();
    }

    @Test
    @DisplayName("full event carries no payloadOmitted key")
    void fullEventHasNoPayloadOmittedKey() {
        String json = buildEventJson("tenant-1", "orders", "rec-123", ChangeType.CREATED);

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-1/msg/"))
                .andExpect(jsonPath("$.payload.data.name").value("Acme"))
                .andExpect(jsonPath("$.payload.payloadOmitted").doesNotExist())
                .andRespond(withSuccess());

        publisher.onRecordChanged(json);

        server.verify();
    }

    @Test
    @DisplayName("does not publish when tenant ID is missing")
    void skipsMissingTenantId() {
        String json = buildEventJson(null, "orders", "rec-123", ChangeType.CREATED);

        publisher.onRecordChanged(json);

        server.verify();
    }

    @Test
    @DisplayName("payload contains record data")
    void payloadContainsRecordData() {
        String json = buildEventJson("tenant-1", "orders", "rec-789", ChangeType.CREATED);

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-1/msg/"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.payload.data.name").value("Acme"))
                .andRespond(withSuccess());

        publisher.onRecordChanged(json);

        server.verify();
    }

    @Test
    @DisplayName("swallows Svix errors and does not throw")
    void swallowsErrors() {
        String json = buildEventJson("tenant-1", "orders", "rec-123", ChangeType.CREATED);

        server.expect(requestTo("http://svix.test/api/v1/app/tenant-1/msg/"))
                .andRespond(withServerError());

        publisher.onRecordChanged(json);

        server.verify();
    }
}
