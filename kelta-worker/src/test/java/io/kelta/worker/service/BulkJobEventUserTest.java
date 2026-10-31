package io.kelta.worker.service;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.worker.repository.BulkJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A bulk job runs on a poller with no request caller, so nothing but the job itself says who its
 * writes are. A record in the upload naming another user as {@code updatedBy} must not make that
 * user the record event's — and so every triggered run's — initiator: the event names the job's
 * creator. Real poller, bulk service and query engine; storage and the publisher are mocks.
 */
@DisplayName("Bulk job — the user its record events name")
class BulkJobEventUserTest {

    private static final String TENANT = "tenant-1";
    private static final String COLLECTION_ID = "col-1";
    private static final String RECORD_ID = "11111111-0000-4000-8000-000000000001";
    private static final String CREATOR_EMAIL = "admin@example.com";
    private static final String CREATOR = "cccccccc-0000-4000-8000-00000000000c";
    private static final String RECORD_AUTHOR = "aaaaaaaa-0000-4000-8000-00000000000a";
    private static final String SPOOFED_MEMBER = "dddddddd-0000-4000-8000-00000000000d";

    private BulkJobRepository bulkJobRepository;
    private StorageAdapter storageAdapter;
    private RecordEventPublisher publisher;
    private UserIdResolver userIdResolver;
    private BulkJobProcessorService processor;

    @BeforeEach
    void setUp() {
        bulkJobRepository = mock(BulkJobRepository.class);
        storageAdapter = mock(StorageAdapter.class);
        publisher = mock(RecordEventPublisher.class);
        userIdResolver = mock(UserIdResolver.class);
        CollectionRegistry registry = mock(CollectionRegistry.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        CollectionDefinition accounts = new CollectionDefinitionBuilder()
                .name("accounts").displayName("Accounts")
                .addField(FieldDefinition.string("name"))
                .build();
        when(jdbcTemplate.queryForList(anyString(), eq(COLLECTION_ID)))
                .thenReturn(List.of(Map.of("name", "accounts")));
        when(registry.get("accounts")).thenReturn(accounts);

        Map<String, Object> stored = new HashMap<>(Map.of("id", RECORD_ID, "name", "Acme",
                "createdBy", RECORD_AUTHOR, "updatedBy", RECORD_AUTHOR));
        when(storageAdapter.getById(accounts, RECORD_ID)).thenReturn(Optional.of(stored));
        when(storageAdapter.update(eq(accounts), eq(RECORD_ID), anyMap())).thenAnswer(inv -> {
            Map<String, Object> merged = new HashMap<>(stored);
            merged.putAll(inv.<Map<String, Object>>getArgument(2));
            return Optional.of(merged);
        });

        DefaultQueryEngine queryEngine = new DefaultQueryEngine(storageAdapter, null, null, null, null, null,
                null, publisher, null);
        BulkOperationService bulkOperationService = new BulkOperationService(queryEngine, registry,
                mock(CollectionLifecycleManager.class), bulkJobRepository, jdbcTemplate);
        processor = new BulkJobProcessorService(bulkJobRepository, bulkOperationService, new ObjectMapper(),
                userIdResolver);
    }

    private void queueUpdateJob() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", TENANT);
        job.put("collection_id", COLLECTION_ID);
        job.put("operation", "UPDATE");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", "[{\"id\":\"" + RECORD_ID + "\",\"name\":\"Renamed\",\"updatedBy\":\""
                + SPOOFED_MEMBER + "\"}]");
        job.put("created_by", CREATOR_EMAIL);
        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));
    }

    private String publishedUser() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformEvent<RecordChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue().getUserId();
    }

    @Test
    @DisplayName("a bulk UPDATE with a spoofed updatedBy publishes as the job's creator and stores the creator")
    void spoofedUpdatedByPublishesCreator() {
        queueUpdateJob();
        when(userIdResolver.resolve(CREATOR_EMAIL, TENANT)).thenReturn(CREATOR);

        processor.processQueuedJobs();

        assertThat(publishedUser()).isEqualTo(CREATOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(storageAdapter).update(any(), eq(RECORD_ID), patch.capture());
        assertThat(patch.getValue()).containsEntry("updatedBy", CREATOR);
    }

    @Test
    @DisplayName("with a creator that no longer resolves, the event is the platform's — never the upload's user")
    void unresolvedCreatorPublishesSystem() {
        queueUpdateJob();
        when(userIdResolver.resolve(CREATOR_EMAIL, TENANT)).thenReturn(CREATOR_EMAIL);

        processor.processQueuedJobs();

        assertThat(publishedUser()).isEqualTo("system");
    }
}
