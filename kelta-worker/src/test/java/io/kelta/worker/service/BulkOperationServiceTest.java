package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.BulkJobRepository;
import io.kelta.worker.repository.BulkJobRepository.BulkJobResultRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkOperationServiceTest {

    @Mock
    private QueryEngine queryEngine;

    @Mock
    private CollectionRegistry collectionRegistry;

    @Mock
    private CollectionLifecycleManager lifecycleManager;

    @Mock
    private BulkJobRepository bulkJobRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CollectionDefinition collectionDefinition;

    private BulkOperationService service;

    private static final String JOB_ID = "job-1";
    private static final String COLLECTION_ID = "col-1";
    private static final String COLLECTION_NAME = "accounts";

    @BeforeEach
    void setUp() {
        service = new BulkOperationService(queryEngine, collectionRegistry,
                lifecycleManager, bulkJobRepository, jdbcTemplate);
    }

    private void mockCollectionResolution() {
        when(jdbcTemplate.queryForList(anyString(), eq(COLLECTION_ID)))
                .thenReturn(List.of(Map.of("name", COLLECTION_NAME)));
        when(collectionRegistry.get(COLLECTION_NAME)).thenReturn(collectionDefinition);
    }

    // ==================== INSERT Tests ====================

    @Nested
    @DisplayName("INSERT operations")
    class InsertTests {

        @Test
        @DisplayName("Should insert all records successfully")
        void insertAll_success() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("name", "Record 1"),
                    Map.of("name", "Record 2"),
                    Map.of("name", "Record 3")
            );

            when(queryEngine.create(eq(collectionDefinition), any()))
                    .thenReturn(Map.of("id", "new-1"))
                    .thenReturn(Map.of("id", "new-2"))
                    .thenReturn(Map.of("id", "new-3"));

            service.processJob(JOB_ID, COLLECTION_ID, "INSERT", records, 200, null, null);

            verify(bulkJobRepository).markProcessing(JOB_ID);
            verify(bulkJobRepository).markCompleted(JOB_ID, 3, 3, 0);
            verify(queryEngine, times(3)).create(eq(collectionDefinition), any());
        }

        @Test
        @DisplayName("Should handle partial failures")
        @SuppressWarnings("unchecked")
        void insertPartialFailure() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("name", "Record 1"),
                    Map.of("name", "Record 2")
            );

            when(queryEngine.create(eq(collectionDefinition), any()))
                    .thenReturn(Map.of("id", "new-1"))
                    .thenThrow(new RuntimeException("Validation failed"));

            service.processJob(JOB_ID, COLLECTION_ID, "INSERT", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 2, 1, 1);

            ArgumentCaptor<List<BulkJobResultRecord>> resultsCaptor = ArgumentCaptor.forClass(List.class);
            verify(bulkJobRepository).insertResults(eq(JOB_ID), resultsCaptor.capture());
            List<BulkJobResultRecord> results = resultsCaptor.getValue();
            assertThat(results).hasSize(2);
            assertThat(results.get(0).status()).isEqualTo("SUCCESS");
            assertThat(results.get(1).status()).isEqualTo("FAILURE");
            assertThat(results.get(1).errorMessage()).isEqualTo("Validation failed");
        }
    }

    // ==================== Audit fields ====================

    @Nested
    @DisplayName("audit fields")
    class AuditFields {

        private static final String CREATOR = "cccccccc-0000-4000-8000-00000000000c";
        private static final String MEMBER = "aaaaaaaa-0000-4000-8000-00000000000a";

        @Test
        @DisplayName("an INSERT replaces uploaded createdBy/updatedBy with the job creator")
        @SuppressWarnings("unchecked")
        void insertStampsCreator() {
            mockCollectionResolution();
            when(queryEngine.create(eq(collectionDefinition), any())).thenReturn(Map.of("id", "new-1"));

            service.processJob(JOB_ID, COLLECTION_ID, "INSERT",
                    List.of(Map.of("name", "R", "createdBy", MEMBER, "updatedBy", MEMBER)), 200, null, CREATOR);

            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(collectionDefinition), data.capture());
            assertThat(data.getValue()).containsEntry("createdBy", CREATOR).containsEntry("updatedBy", CREATOR)
                    .containsEntry("name", "R");
        }

        @Test
        @DisplayName("an UPDATE replaces an uploaded updatedBy with the job creator")
        @SuppressWarnings("unchecked")
        void updateStampsCreator() {
            mockCollectionResolution();
            when(queryEngine.update(eq(collectionDefinition), eq("rec-1"), any()))
                    .thenReturn(Optional.of(Map.of("id", "rec-1")));

            service.processJob(JOB_ID, COLLECTION_ID, "UPDATE",
                    List.of(Map.of("id", "rec-1", "name", "R", "updatedBy", MEMBER, "createdBy", MEMBER)),
                    200, null, CREATOR);

            ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(eq(collectionDefinition), eq("rec-1"), patch.capture());
            assertThat(patch.getValue()).containsEntry("updatedBy", CREATOR).doesNotContainKeys("createdBy", "id");
        }

        @Test
        @DisplayName("with no resolvable creator, uploaded audit fields are dropped, not stored")
        @SuppressWarnings("unchecked")
        void noCreatorDropsAuditFields() {
            mockCollectionResolution();
            when(queryEngine.create(eq(collectionDefinition), any())).thenReturn(Map.of("id", "new-1"));

            service.processJob(JOB_ID, COLLECTION_ID, "INSERT",
                    List.of(Map.of("name", "R", "createdBy", MEMBER, "updatedBy", MEMBER)), 200, null, null);

            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(collectionDefinition), data.capture());
            assertThat(data.getValue()).doesNotContainKeys("createdBy", "updatedBy");
        }
    }

    // ==================== UPDATE Tests ====================

    @Nested
    @DisplayName("UPDATE operations")
    class UpdateTests {

        @Test
        @DisplayName("Should update records by ID")
        void updateAll_success() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    new LinkedHashMap<>(Map.of("id", "rec-1", "name", "Updated 1")),
                    new LinkedHashMap<>(Map.of("id", "rec-2", "name", "Updated 2"))
            );

            when(queryEngine.update(eq(collectionDefinition), anyString(), any()))
                    .thenReturn(Optional.of(Map.of("id", "rec-1")))
                    .thenReturn(Optional.of(Map.of("id", "rec-2")));

            service.processJob(JOB_ID, COLLECTION_ID, "UPDATE", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 2, 2, 0);
            verify(queryEngine).update(eq(collectionDefinition), eq("rec-1"), any());
            verify(queryEngine).update(eq(collectionDefinition), eq("rec-2"), any());
        }

        @Test
        @DisplayName("Should fail when record ID is missing")
        @SuppressWarnings("unchecked")
        void update_missingId() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("name", "No ID record")
            );

            service.processJob(JOB_ID, COLLECTION_ID, "UPDATE", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 1, 0, 1);

            ArgumentCaptor<List<BulkJobResultRecord>> resultsCaptor = ArgumentCaptor.forClass(List.class);
            verify(bulkJobRepository).insertResults(eq(JOB_ID), resultsCaptor.capture());
            assertThat(resultsCaptor.getValue().get(0).errorMessage())
                    .contains("id");
        }
    }

    // ==================== DELETE Tests ====================

    @Nested
    @DisplayName("DELETE operations")
    class DeleteTests {

        @Test
        @DisplayName("Should delete records by ID")
        void deleteAll_success() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("id", "rec-1"),
                    Map.of("id", "rec-2")
            );

            when(queryEngine.delete(collectionDefinition, "rec-1")).thenReturn(true);
            when(queryEngine.delete(collectionDefinition, "rec-2")).thenReturn(true);

            service.processJob(JOB_ID, COLLECTION_ID, "DELETE", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 2, 2, 0);
        }

        @Test
        @DisplayName("Should fail for non-existent records")
        void delete_notFound() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("id", "non-existent")
            );

            when(queryEngine.delete(collectionDefinition, "non-existent")).thenReturn(false);

            service.processJob(JOB_ID, COLLECTION_ID, "DELETE", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 1, 0, 1);
        }
    }

    // ==================== UPSERT Tests ====================

    @Nested
    @DisplayName("UPSERT operations")
    class UpsertTests {

        @Test
        @DisplayName("Should insert when record does not exist (by ID)")
        void upsert_insertNew() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("id", "new-rec", "name", "New Record")
            );

            when(queryEngine.getById(collectionDefinition, "new-rec")).thenReturn(Optional.empty());
            when(queryEngine.create(eq(collectionDefinition), any()))
                    .thenReturn(Map.of("id", "new-rec"));

            service.processJob(JOB_ID, COLLECTION_ID, "UPSERT", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 1, 1, 0);
            verify(queryEngine).create(eq(collectionDefinition), any());
        }

        @Test
        @DisplayName("Should update when record exists (by ID)")
        void upsert_updateExisting() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    new LinkedHashMap<>(Map.of("id", "existing-rec", "name", "Updated"))
            );

            when(queryEngine.getById(collectionDefinition, "existing-rec"))
                    .thenReturn(Optional.of(Map.of("id", "existing-rec", "name", "Old")));
            when(queryEngine.update(eq(collectionDefinition), eq("existing-rec"), any()))
                    .thenReturn(Optional.of(Map.of("id", "existing-rec")));

            service.processJob(JOB_ID, COLLECTION_ID, "UPSERT", records, 200, null, null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 1, 1, 0);
            verify(queryEngine).update(eq(collectionDefinition), eq("existing-rec"), any());
        }

        @Test
        @DisplayName("Should upsert by external ID field")
        void upsert_byExternalId() {
            mockCollectionResolution();
            List<Map<String, Object>> records = List.of(
                    Map.of("externalCode", "EXT-001", "name", "Record")
            );

            QueryResult emptyResult = new QueryResult(List.of(), new PaginationMetadata(0, 1, 1, 0));
            when(queryEngine.executeQuery(eq(collectionDefinition), any()))
                    .thenReturn(emptyResult);
            when(queryEngine.create(eq(collectionDefinition), any()))
                    .thenReturn(Map.of("id", "created-1"));

            service.processJob(JOB_ID, COLLECTION_ID, "UPSERT", records, 200, "externalCode", null);

            verify(bulkJobRepository).markCompleted(JOB_ID, 1, 1, 0);
            verify(queryEngine).create(eq(collectionDefinition), any());
        }
    }

    // ==================== Batch Processing Tests ====================

    @Nested
    @DisplayName("Batch processing")
    class BatchProcessingTests {

        @Test
        @DisplayName("Should process records in batches")
        void processBatches() {
            mockCollectionResolution();
            List<Map<String, Object>> records = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                records.add(Map.of("name", "Record " + i));
            }

            when(queryEngine.create(eq(collectionDefinition), any()))
                    .thenReturn(Map.of("id", "new-id"));

            service.processJob(JOB_ID, COLLECTION_ID, "INSERT", records, 2, null, null);

            // 5 records / batch size 2 = 3 batches
            verify(bulkJobRepository, times(3)).insertResults(eq(JOB_ID), any());
            verify(bulkJobRepository, times(3)).updateProgress(eq(JOB_ID), anyInt(), anyInt(), anyInt());
            verify(bulkJobRepository).markCompleted(JOB_ID, 5, 5, 0);
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should fail job when collection not found")
        void collectionNotFound() {
            when(jdbcTemplate.queryForList(anyString(), eq(COLLECTION_ID)))
                    .thenReturn(List.of());

            service.processJob(JOB_ID, COLLECTION_ID, "INSERT", List.of(Map.of("name", "test")), 200, null, null);

            verify(bulkJobRepository).markFailed(eq(JOB_ID), contains("Collection not found"));
            verify(bulkJobRepository, never()).markProcessing(anyString());
        }
    }
}
