package io.kelta.worker.module;

import io.kelta.runtime.event.EventSubscription;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.kelta.worker.module.ExternalModuleFixtures.TENANT;
import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.manifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModuleEventDeliveryListener")
class ModuleEventDeliveryListenerTest {

    private static final String COLLECTION = "orders";
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30));
    private static final EventSubscription.Attempt FIRST = new EventSubscription.Attempt(1, 3);
    private static final EventSubscription.Attempt LAST = new EventSubscription.Attempt(3, 3);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ExternalModuleRegistry registry = mock(ExternalModuleRegistry.class);
    private final CollectionRegistry collections = mock(CollectionRegistry.class);
    private final ModuleEventDeadLetterStore deadLetters = mock(ModuleEventDeadLetterStore.class);
    private ModuleEventDeliveryListener listener;

    /** Reads orders; sees {@code cost} masked and never {@code internalNote} (HIDDEN) or {@code secret}. */
    private final ModulePrincipalAccess access = new ModulePrincipalAccess(
            Map.of(COLLECTION, new ModulePrincipalAccess.ObjectAccess(false, true, false, false)),
            Map.of(COLLECTION, Map.of("internalNote", "HIDDEN", "cost", "MASKED")), false, false);

    @BeforeEach
    void setUp() {
        ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("token");
        ExternalModuleInvoker invoker = new ExternalModuleInvoker(mock(ModuleStore.class), http, tokens,
                mock(TenantSlugResolver.class), null, new ExternalModuleFixtures.MutableClock(), Runnable::run);
        CollectionDefinition orders = new CollectionDefinitionBuilder().name(COLLECTION).displayName("Orders")
                .addField(FieldDefinition.string("status"))
                .addField(FieldDefinition.string("internalNote"))
                .addField(FieldDefinition.string("cost"))
                .addField(FieldDefinition.string("secret").withServerOnly(true))
                .build();
        when(collections.get(COLLECTION)).thenReturn(orders);
        when(deadLetters.record(anyString(), anyString(), anyString(), anyString(), anyInt(), any(), anyLong()))
                .thenReturn(true);
        listener = new ModuleEventDeliveryListener(registry, invoker, deadLetters, collections, null, mapper, null,
                BACKOFF);
    }

    private static ExternalModuleRegistry.Endpoint endpoint(String installId, String moduleId,
                                                            ModulePrincipalAccess access) {
        TenantModuleData row = new TenantModuleData(installId, TENANT, moduleId, moduleId, "1.0.0", null,
                "https://" + moduleId + ".example.test", null, null, null, "{}", TenantModuleData.STATUS_ACTIVE,
                "admin", Instant.EPOCH, Instant.EPOCH, null, List.of(), "https://" + moduleId + ".example.test", 1,
                "app-" + moduleId, "svc-" + moduleId);
        return new ExternalModuleRegistry.Endpoint(row, manifest(), access);
    }

    private void subscribers(ExternalModuleRegistry.Endpoint... endpoints) {
        when(registry.eventSubscribers(TENANT, COLLECTION, "updated")).thenReturn(List.of(endpoints));
    }

    private static String updatedEvent() {
        return """
                {"eventId":"ev-1","eventType":"record.changed","tenantId":"tenant-1",
                 "timestamp":"2026-10-30T12:00:00Z",
                 "payload":{"collectionName":"orders","recordId":"rec-1","changeType":"UPDATED",
                   "data":{"status":"PAID","internalNote":"call back","cost":"1234","secret":"s3cr3t"},
                   "previousData":{"status":"NEW","internalNote":"","cost":"1234","secret":"s3cr3t"},
                   "changedFields":["status","internalNote","secret"]}}
                """;
    }

    private JsonNode sentBody() {
        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        assertThat(sent.getValue().path()).isEqualTo("/kelta/v1/events");
        assertThat(sent.getValue().method()).isEqualTo("POST");
        assertThat(sent.getValue().timeout()).isEqualTo(ModuleEventDeliveryListener.DELIVERY_TIMEOUT);
        assertThat(sent.getValue().actor()).as("events carry no act claim").isNull();
        return mapper.readTree(sent.getValue().body());
    }

    @Test
    @DisplayName("POSTs the RecordEvent filtered for the principal: server-only and HIDDEN gone, MASKED masked")
    void deliversAFilteredEvent() {
        subscribers(endpoint("install-1", "stub", access));
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(204, ""));

        listener.handle("kelta.record.changed.tenant-1.orders", updatedEvent(), FIRST);

        JsonNode body = sentBody();
        assertThat(body.get("eventId").stringValue()).isEqualTo("ev-1");
        assertThat(body.get("type").stringValue()).isEqualTo("updated");
        assertThat(body.get("tenantId").stringValue()).isEqualTo(TENANT);
        assertThat(body.get("collection").stringValue()).isEqualTo(COLLECTION);
        assertThat(body.get("recordId").stringValue()).isEqualTo("rec-1");
        assertThat(body.get("timestamp").stringValue()).isEqualTo("2026-10-30T12:00:00Z");
        JsonNode data = body.get("data");
        assertThat(data.get("status").stringValue()).isEqualTo("PAID");
        assertThat(data.has("secret")).as("server-only").isFalse();
        assertThat(data.has("internalNote")).as("HIDDEN").isFalse();
        assertThat(data.get("cost").stringValue()).as("MASKED").isEqualTo("******");
        assertThat(body.get("previousData").get("status").stringValue()).isEqualTo("NEW");
        assertThat(body.get("previousData").has("secret")).isFalse();
        assertThat(mapper.convertValue(body.get("changedFields"), List.class)).containsExactly("status");
        verify(registry).ensureInitialized();
        verify(deadLetters, never()).record(any(), any(), any(), any(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("a module without read on the collection gets ids only: no attributes, no changed field names")
    void unreadableCollectionSendsIdsOnly() {
        subscribers(endpoint("install-1", "stub", ModulePrincipalAccess.NONE));
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(204, ""));

        listener.handle("s", updatedEvent(), FIRST);

        JsonNode body = sentBody();
        assertThat(body.get("recordId").stringValue()).isEqualTo("rec-1");
        assertThat(body.get("data").size()).isZero();
        assertThat(body.get("changedFields").size()).isZero();
    }

    @Test
    @DisplayName("no subscriber for the collection and type: nothing is sent")
    void noSubscribers() {
        when(registry.eventSubscribers(any(), any(), any())).thenReturn(List.of());

        listener.handle("s", updatedEvent(), FIRST);

        verify(http, never()).invoke(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an event without a tenant is ignored")
    void noTenant() {
        listener.handle("s", "{\"eventId\":\"ev-1\",\"payload\":{\"collectionName\":\"orders\","
                + "\"changeType\":\"CREATED\"}}", FIRST);

        verify(registry, never()).eventSubscribers(any(), any(), any());
    }

    @Test
    @DisplayName("a non-2xx before the last attempt fails the delivery so it is redelivered; no dead letter")
    void failureBeforeTheLastAttemptIsRetried() {
        subscribers(endpoint("install-1", "stub", access));
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(500, "{\"error\":\"down\"}"));

        assertThatThrownBy(() -> listener.handle("s", updatedEvent(), FIRST))
                .isInstanceOf(ModuleEventDeliveryListener.EventDeliveryException.class)
                .hasMessageContaining("stub").hasMessageContaining("attempt 1/3").hasMessageContaining("500");
        verify(deadLetters, never()).record(any(), any(), any(), any(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("a failure on the last attempt is dead-lettered with the body sent and the module's status; no throw")
    void lastAttemptIsDeadLettered() {
        subscribers(endpoint("install-1", "stub", access));
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(503, "unavailable"));

        listener.handle("s", updatedEvent(), LAST);

        ArgumentCaptor<String> payload = ArgumentCaptor.captor();
        verify(deadLetters).record(eq(TENANT), eq("install-1"), eq("ev-1"), payload.capture(), eq(503),
                argThat(error -> error.contains("503")), eq(3L));
        JsonNode stored = mapper.readTree(payload.getValue());
        assertThat(stored.get("data").has("secret")).as("the dead letter holds only what the module may see")
                .isFalse();
        assertThat(stored.get("eventId").stringValue()).isEqualTo("ev-1");
    }

    @Test
    @DisplayName("a timeout on the last attempt is dead-lettered with status 0")
    void timeoutIsDeadLetteredWithStatusZero() {
        subscribers(endpoint("install-1", "stub", access));
        when(http.invoke(any(), any(), any(), any()))
                .thenThrow(new ModuleHttpClient.ModuleCallException(0, "timed out", true));

        listener.handle("s", updatedEvent(), LAST);

        verify(deadLetters).record(eq(TENANT), eq("install-1"), eq("ev-1"), anyString(), eq(0),
                argThat(error -> error.contains("did not answer")), eq(3L));
    }

    @Test
    @DisplayName("of two subscribers only the one that failed is named, and only it is dead-lettered")
    void onlyTheFailingInstallIsRetriedOrDeadLettered() {
        ExternalModuleRegistry.Endpoint healthy = endpoint("install-ok", "healthy", access);
        ExternalModuleRegistry.Endpoint broken = endpoint("install-bad", "broken", access);
        subscribers(healthy, broken);
        when(http.invoke(argThat(row -> row != null && "healthy".equals(row.moduleId())), any(), any(), any()))
                .thenReturn(json(204, ""));
        when(http.invoke(argThat(row -> row != null && "broken".equals(row.moduleId())), any(), any(), any()))
                .thenReturn(json(500, ""));

        assertThatThrownBy(() -> listener.handle("s", updatedEvent(), FIRST))
                .hasMessageContaining("broken").hasMessageNotContaining("healthy");

        listener.handle("s", updatedEvent(), LAST);

        verify(deadLetters).record(eq(TENANT), eq("install-bad"), eq("ev-1"), anyString(), eq(500), any(), eq(3L));
        verify(deadLetters, never()).record(any(), eq("install-ok"), any(), any(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("an event whose data was omitted for size is forwarded without data and flagged")
    void omittedPayloadIsFlagged() {
        when(registry.eventSubscribers(TENANT, COLLECTION, "created")).thenReturn(
                List.of(endpoint("install-1", "stub", access)));
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(204, ""));

        listener.handle("s", """
                {"eventId":"ev-2","tenantId":"tenant-1","timestamp":1793361600,
                 "payload":{"collectionName":"orders","recordId":"rec-2","changeType":"CREATED",
                            "payloadOmitted":true}}
                """, FIRST);

        JsonNode body = sentBody();
        assertThat(body.get("payloadOmitted").booleanValue()).isTrue();
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.get("type").stringValue()).isEqualTo("created");
        assertThat(body.get("timestamp").stringValue()).as("epoch seconds become ISO-8601")
                .isEqualTo(Instant.ofEpochSecond(1793361600L).toString());
    }

    @Test
    @DisplayName("the subscription is the worker-module-events queue group with the configured schedule")
    void subscription() {
        EventSubscription sub = listener.subscription();

        assertThat(sub.name()).isEqualTo("worker-module-events");
        assertThat(sub.subject()).isEqualTo("kelta.record.changed.>");
        assertThat(sub.queueGroup()).isEqualTo("worker-module-events");
        assertThat(sub.redelivery().backoff()).isEqualTo(BACKOFF);
        assertThat(sub.redelivery().ackWait()).isEqualTo(ModuleEventDeliveryListener.ACK_WAIT);
    }

    @Test
    @DisplayName("the backoff property reads 5s/2m/1h and ISO-8601; anything else fails startup")
    void parseBackoff() {
        assertThat(ModuleEventDeliveryListener.parseBackoff("5s, 30s,2m,10m,1h")).containsExactly(
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
                Duration.ofHours(1));
        assertThat(ModuleEventDeliveryListener.parseBackoff("PT1S")).containsExactly(Duration.ofSeconds(1));
        assertThatThrownBy(() -> ModuleEventDeliveryListener.parseBackoff("soon"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleEventDeliveryListener.parseBackoff(" , "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
