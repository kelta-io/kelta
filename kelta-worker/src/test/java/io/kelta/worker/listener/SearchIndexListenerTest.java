package io.kelta.worker.listener;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.CollectionLifecycleManager;
import io.kelta.worker.service.SearchIndexService;
import io.kelta.worker.service.TenantSlugResolver;
import io.kelta.worker.service.WorkerBootstrapService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

@DisplayName("SearchIndexListener")
class SearchIndexListenerTest {

    private SearchIndexService searchIndexService;
    private CollectionLifecycleManager lifecycleManager;
    private CollectionRegistry collectionRegistry;
    private ObjectMapper objectMapper;
    private QueryEngine queryEngine;
    private CollectionDefinition userCollection;
    private WorkerBootstrapService bootstrap;
    private SearchIndexListener listener;

    @BeforeEach
    void setUp() {
        searchIndexService = mock(SearchIndexService.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        collectionRegistry = mock(CollectionRegistry.class);
        objectMapper = new ObjectMapper();
        queryEngine = mock(QueryEngine.class);
        TenantSlugResolver tenantSlugResolver = mock(TenantSlugResolver.class);
        when(tenantSlugResolver.resolveSlug("tenant-1")).thenReturn(Optional.of("acme"));
        bootstrap = mock(WorkerBootstrapService.class);
        listener = new SearchIndexListener(searchIndexService, lifecycleManager,
                collectionRegistry, objectMapper, queryEngine, tenantSlugResolver, bootstrap);

        // A tenant's own collection is registered under its tenant, as ConcurrentCollectionRegistry
        // keys it: looked up with no tenant bound (or another one) it is unknown.
        userCollection = mock(CollectionDefinition.class);
        when(userCollection.systemCollection()).thenReturn(false);
        when(collectionRegistry.get("products")).thenAnswer(invocation ->
                "tenant-1".equals(TenantContext.get()) ? userCollection : null);

        // System collections return a system definition
        CollectionDefinition systemCollection = mock(CollectionDefinition.class);
        when(systemCollection.systemCollection()).thenReturn(true);
        when(collectionRegistry.get("fields")).thenReturn(systemCollection);
    }

    @Nested
    @DisplayName("handleRecordChanged")
    class HandleRecordChanged {

        @Test
        @DisplayName("should index record on CREATED event")
        void shouldIndexOnCreated() throws Exception {
            when(lifecycleManager.getCollectionIdByName("products")).thenReturn("col-1");

            String message = buildMessage("tenant-1", "products", "rec-1", ChangeType.CREATED,
                    Map.of("name", "Widget A"));

            listener.handleRecordChanged(message);

            verify(searchIndexService).indexRecord(
                    eq("tenant-1"), eq("col-1"), eq("products"), eq("rec-1"),
                    argThat(data -> "Widget A".equals(data.get("name"))));
        }

        @Test
        @DisplayName("waits for the pod's collections to be registered before looking one up")
        void bootstrapsBeforeTheRegistryLookup() throws Exception {
            when(lifecycleManager.getCollectionIdByName("products")).thenReturn("col-1");

            listener.handleRecordChanged(buildMessage("tenant-1", "products", "rec-1", ChangeType.CREATED,
                    Map.of("name", "Widget A")));

            var order = inOrder(bootstrap, collectionRegistry);
            order.verify(bootstrap).ensureBootstrapped();
            order.verify(collectionRegistry).get("products");
        }

        @Test
        @DisplayName("should index record on UPDATED event")
        void shouldIndexOnUpdated() throws Exception {
            when(lifecycleManager.getCollectionIdByName("products")).thenReturn("col-1");

            String message = buildMessage("tenant-1", "products", "rec-1", ChangeType.UPDATED,
                    Map.of("name", "Widget B"));

            listener.handleRecordChanged(message);

            verify(searchIndexService).indexRecord(
                    eq("tenant-1"), eq("col-1"), eq("products"), eq("rec-1"),
                    argThat(data -> "Widget B".equals(data.get("name"))));
        }

        @Test
        @DisplayName("should remove record on DELETED event")
        void shouldRemoveOnDeleted() throws Exception {
            String message = buildMessage("tenant-1", "products", "rec-1", ChangeType.DELETED, null);

            listener.handleRecordChanged(message);

            verify(searchIndexService).removeRecord("tenant-1", "products", "rec-1");
        }

        @Test
        @DisplayName("should skip system collections")
        void shouldSkipSystemCollections() throws Exception {
            String message = buildMessage("tenant-1", "fields", "field-1", ChangeType.CREATED,
                    Map.of("name", "email"));

            listener.handleRecordChanged(message);

            verifyNoInteractions(searchIndexService);
        }

        @Test
        @DisplayName("should skip events without tenant ID")
        void shouldSkipWithoutTenantId() throws Exception {
            String message = objectMapper.writeValueAsString(Map.of(
                    "payload", Map.of(
                            "collectionName", "products",
                            "recordId", "rec-1",
                            "changeType", "CREATED",
                            "data", Map.of("name", "Test"))));

            listener.handleRecordChanged(message);

            verifyNoInteractions(searchIndexService);
        }

        @Test
        @DisplayName("should index the re-read record when the event payload was omitted")
        void shouldReReadWhenPayloadOmitted() throws Exception {
            when(lifecycleManager.getCollectionIdByName("products")).thenReturn("col-1");
            // The re-read runs with the tenant's slug bound: its tables live in the slug's schema.
            when(queryEngine.getById(userCollection, "rec-1")).thenAnswer(invocation ->
                    "acme".equals(TenantContext.getSlug())
                            ? Optional.of(Map.of("id", "rec-1", "name", "Re-read"))
                            : Optional.empty());

            listener.handleRecordChanged(buildSlimMessage(ChangeType.UPDATED));

            verify(queryEngine).getById(userCollection, "rec-1");
            verify(searchIndexService).indexRecord(
                    eq("tenant-1"), eq("col-1"), eq("products"), eq("rec-1"),
                    argThat(data -> "Re-read".equals(data.get("name"))));
        }

        @Test
        @DisplayName("should skip indexing when an omitted-payload record can no longer be read")
        void shouldSkipWhenOmittedRecordIsGone() throws Exception {
            when(queryEngine.getById(userCollection, "rec-1")).thenReturn(Optional.empty());

            listener.handleRecordChanged(buildSlimMessage(ChangeType.UPDATED));

            verify(searchIndexService, never()).indexRecord(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should remove a deleted record from a slim event without re-reading")
        void shouldRemoveOnSlimDelete() throws Exception {
            listener.handleRecordChanged(buildSlimMessage(ChangeType.DELETED));

            verify(searchIndexService).removeRecord("tenant-1", "products", "rec-1");
            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("should not re-read when the event carries its data")
        void shouldNotReReadWhenDataPresent() throws Exception {
            when(lifecycleManager.getCollectionIdByName("products")).thenReturn("col-1");

            listener.handleRecordChanged(buildMessage("tenant-1", "products", "rec-1",
                    ChangeType.CREATED, Map.of("name", "Widget A")));

            verifyNoInteractions(queryEngine);
        }

        @Test
        @DisplayName("should handle malformed messages gracefully")
        void shouldHandleMalformedMessages() {
            listener.handleRecordChanged("not valid json");

            verifyNoInteractions(searchIndexService);
        }
    }

    private String buildSlimMessage(ChangeType changeType) throws Exception {
        RecordChangedPayload payload = new RecordChangedPayload(
                "products", "rec-1", changeType, null, null, java.util.List.of("name"))
                .withPayloadOmitted(true);

        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("tenantId", "tenant-1");
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    /**
     * Builds a NATS message in the PlatformEvent envelope format.
     */
    private String buildMessage(String tenantId, String collectionName, String recordId,
                                 ChangeType changeType, Map<String, Object> data) throws Exception {
        RecordChangedPayload payload = new RecordChangedPayload(
                collectionName, recordId, changeType, data, null, null);

        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("tenantId", tenantId);
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }
}
