package io.kelta.worker.service;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BulkJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkJobProcessorServiceTest {

    @Mock
    private BulkJobRepository bulkJobRepository;

    @Mock
    private BulkOperationService bulkOperationService;

    private BulkJobProcessorService processorService;

    @BeforeEach
    void setUp() {
        processorService = new BulkJobProcessorService(bulkJobRepository,
                bulkOperationService, new ObjectMapper());
    }

    @Test
    @DisplayName("Should do nothing when no queued jobs")
    void noQueuedJobs() {
        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of());

        processorService.processQueuedJobs();

        verifyNoInteractions(bulkOperationService);
    }

    @Test
    @DisplayName("Should process a queued job with JSON data payload")
    void processJobWithJsonPayload() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-1");
        job.put("collection_id", "col-1");
        job.put("operation", "INSERT");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", "[{\"name\":\"Record 1\"},{\"name\":\"Record 2\"}]");
        job.put("file_storage_key", null);

        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));

        processorService.processQueuedJobs();

        verify(bulkOperationService).processJob(eq("job-1"), eq("col-1"), eq("INSERT"),
                argThat(records -> records.size() == 2), eq(200), isNull());
    }

    @Test
    @DisplayName("Should fail job when data payload is null")
    void failJobWhenNoPayload() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-1");
        job.put("collection_id", "col-1");
        job.put("operation", "INSERT");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", null);
        job.put("file_storage_key", null);

        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));

        processorService.processQueuedJobs();

        verify(bulkJobRepository).markFailed(eq("job-1"), contains("No records"));
        verifyNoInteractions(bulkOperationService);
    }

    @Test
    @DisplayName("Should set and clear tenant context for each job")
    void setsTenantContext() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-abc");
        job.put("collection_id", "col-1");
        job.put("operation", "INSERT");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", "[{\"name\":\"test\"}]");
        job.put("file_storage_key", null);

        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));

        processorService.processQueuedJobs();

        // After processing, tenant context should be cleared
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Should handle processing exception gracefully")
    void handleProcessingException() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-1");
        job.put("collection_id", "col-1");
        job.put("operation", "INSERT");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", "[{\"name\":\"test\"}]");
        job.put("file_storage_key", null);

        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));
        doThrow(new RuntimeException("Unexpected error")).when(bulkOperationService)
                .processJob(anyString(), anyString(), anyString(), any(), anyInt(), any());

        processorService.processQueuedJobs();

        verify(bulkJobRepository).markFailed(eq("job-1"), eq("Unexpected error"));
    }

    @Test
    @DisplayName("Should pass external ID field to operation service")
    void passesExternalIdField() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-1");
        job.put("collection_id", "col-1");
        job.put("operation", "UPSERT");
        job.put("batch_size", 100);
        job.put("external_id_field", "externalCode");
        job.put("data_payload", "[{\"externalCode\":\"EXT-001\",\"name\":\"test\"}]");
        job.put("file_storage_key", null);

        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));

        processorService.processQueuedJobs();

        verify(bulkOperationService).processJob(eq("job-1"), eq("col-1"), eq("UPSERT"),
                any(), eq(100), eq("externalCode"));
    }
}
