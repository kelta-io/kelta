package io.kelta.worker.service;

import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.DataExportRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("DataExportService")
class DataExportServiceTest {

    private DataExportRepository exportRepository;
    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private PlatformEventPublisher eventPublisher;
    private DataExportService service;

    @BeforeEach
    void setUp() {
        exportRepository = mock(DataExportRepository.class);
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = JsonMapper.builder().build();
        eventPublisher = mock(PlatformEventPublisher.class);

        service = new DataExportService(
                exportRepository, queryEngine, collectionRegistry,
                jdbcTemplate, objectMapper, Optional.empty(), eventPublisher,
                mock(RecordMaskingService.class));
    }

    @Test
    @DisplayName("createExport should create FULL export")
    void createExportShouldCreateFullExport() {
        when(exportRepository.create(anyString(), anyString(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn("exp-1");

        String id = service.createExport("t1", "Full Export", "desc", "FULL", null, "CSV", "user@test.com");

        assertThat(id).isEqualTo("exp-1");
        verify(exportRepository).create("t1", "Full Export", "desc", "FULL", null, "CSV", "user@test.com");
    }

    @Test
    @DisplayName("createExport should serialize collection IDs for SELECTIVE export")
    void createExportShouldSerializeCollectionIds() {
        when(exportRepository.create(anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("exp-2");

        String id = service.createExport("t1", "Selective Export", null, "SELECTIVE",
                List.of("col-1", "col-2"), "JSON", "user@test.com");

        assertThat(id).isEqualTo("exp-2");
        verify(exportRepository).create(eq("t1"), eq("Selective Export"), isNull(),
                eq("SELECTIVE"), contains("col-1"), eq("JSON"), eq("user@test.com"));
    }

    @Test
    @DisplayName("executeExport should skip if export not pending")
    void executeExportShouldSkipIfNotPending() {
        when(exportRepository.findPendingExport("exp-1")).thenReturn(Optional.empty());

        service.executeExport("exp-1", "t1");

        verify(exportRepository, never()).markInProgress(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("executeExport should export CSV for FULL scope")
    void executeExportShouldExportCsvForFullScope() {
        // Setup pending export
        Map<String, Object> export = new HashMap<>();
        export.put("id", "exp-1");
        export.put("tenant_id", "t1");
        export.put("name", "Full Export");
        export.put("export_scope", "FULL");
        export.put("collection_ids", null);
        export.put("format", "CSV");
        export.put("created_by", "user@test.com");
        when(exportRepository.findPendingExport("exp-1")).thenReturn(Optional.of(export));

        // Setup collection
        List<Map<String, Object>> collectionRows = List.of(
                Map.of("id", "col-1", "name", "accounts")
        );
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE tenant_id"), eq("t1")))
                .thenReturn(collectionRows);

        CollectionDefinition definition = new CollectionDefinition(
                "accounts", "Accounts", "accounts",
                List.of(
                        FieldDefinition.requiredString("id"),
                        FieldDefinition.string("name")
                ),
                null, null, null, 1L, null, null
        );
        when(collectionRegistry.get("accounts")).thenReturn(definition);

        // Setup query result
        List<Map<String, Object>> records = List.of(
                Map.of("id", "rec-1", "name", "Acme Corp"),
                Map.of("id", "rec-2", "name", "Widget Co")
        );
        PaginationMetadata metadata = new PaginationMetadata(2, 1, 1000, 1);
        QueryResult queryResult = new QueryResult(records, metadata);
        when(queryEngine.executeQuery(eq(definition), any(QueryRequest.class))).thenReturn(queryResult);

        service.executeExport("exp-1", "t1");

        // Verify status transitions
        verify(exportRepository).markInProgress("exp-1");
        verify(exportRepository).markCompleted(eq("exp-1"), eq(2), eq(2), isNull(), anyLong());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("executeExport should export JSON for SELECTIVE scope")
    void executeExportShouldExportJsonForSelectiveScope() {
        // Setup pending export
        Map<String, Object> export = new HashMap<>();
        export.put("id", "exp-2");
        export.put("tenant_id", "t1");
        export.put("name", "Selective Export");
        export.put("export_scope", "SELECTIVE");
        export.put("collection_ids", "[\"col-1\"]");
        export.put("format", "JSON");
        export.put("created_by", "user@test.com");
        when(exportRepository.findPendingExport("exp-2")).thenReturn(Optional.of(export));

        // Setup collection lookup
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("col-1"), eq("t1")))
                .thenReturn(List.of(Map.of("id", "col-1", "name", "contacts")));

        CollectionDefinition definition = new CollectionDefinition(
                "contacts", "Contacts", "contacts",
                List.of(
                        FieldDefinition.requiredString("id"),
                        FieldDefinition.string("email")
                ),
                null, null, null, 1L, null, null
        );
        when(collectionRegistry.get("contacts")).thenReturn(definition);

        // Setup query result
        List<Map<String, Object>> records = List.of(
                Map.of("id", "c-1", "email", "a@test.com")
        );
        PaginationMetadata metadata = new PaginationMetadata(1, 1, 1000, 1);
        QueryResult queryResult = new QueryResult(records, metadata);
        when(queryEngine.executeQuery(eq(definition), any(QueryRequest.class))).thenReturn(queryResult);

        service.executeExport("exp-2", "t1");

        verify(exportRepository).markInProgress("exp-2");
        verify(exportRepository).markCompleted(eq("exp-2"), eq(1), eq(1), isNull(), anyLong());
    }

    @Test
    @DisplayName("executeExport should mark failed when no collections found")
    void executeExportShouldFailWhenNoCollections() {
        Map<String, Object> export = new HashMap<>();
        export.put("id", "exp-3");
        export.put("tenant_id", "t1");
        export.put("name", "Empty Export");
        export.put("export_scope", "FULL");
        export.put("collection_ids", null);
        export.put("format", "CSV");
        export.put("created_by", "user@test.com");
        when(exportRepository.findPendingExport("exp-3")).thenReturn(Optional.of(export));
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE tenant_id"), eq("t1")))
                .thenReturn(List.of());

        service.executeExport("exp-3", "t1");

        verify(exportRepository).markFailed(eq("exp-3"), contains("No collections found"));
    }

    @Test
    @DisplayName("executeExport should mark failed on exception")
    void executeExportShouldMarkFailedOnException() {
        Map<String, Object> export = new HashMap<>();
        export.put("id", "exp-4");
        export.put("tenant_id", "t1");
        export.put("name", "Error Export");
        export.put("export_scope", "FULL");
        export.put("collection_ids", null);
        export.put("format", "CSV");
        export.put("created_by", "user@test.com");
        when(exportRepository.findPendingExport("exp-4")).thenReturn(Optional.of(export));
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE tenant_id"), eq("t1")))
                .thenThrow(new RuntimeException("DB connection lost"));

        service.executeExport("exp-4", "t1");

        verify(exportRepository).markFailed(eq("exp-4"), contains("DB connection lost"));
    }
}
