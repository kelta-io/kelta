package io.kelta.worker.service;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.context.ActingUser;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BulkJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkJobProcessorServiceTest {

    @Mock
    private BulkJobRepository bulkJobRepository;

    @Mock
    private BulkOperationService bulkOperationService;

    @Mock
    private UserIdResolver userIdResolver;

    private BulkJobProcessorService processorService;

    @BeforeEach
    void setUp() {
        processorService = new BulkJobProcessorService(bulkJobRepository,
                bulkOperationService, new ObjectMapper(), userIdResolver);
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
                argThat(records -> records.size() == 2), eq(200), isNull(), isNull());
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
                .processJob(anyString(), anyString(), anyString(), any(), anyInt(), any(), any());

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
                any(), eq(100), eq("externalCode"), isNull());
    }

    @Test
    @DisplayName("runs the job as its creator: the resolved UUID is bound as acting user and stamped")
    void runsJobAsResolvedCreator() {
        String creator = "cccccccc-0000-4000-8000-00000000000c";
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-1");
        job.put("collection_id", "col-1");
        job.put("operation", "UPDATE");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", "[{\"id\":\"rec-1\",\"name\":\"x\"}]");
        job.put("created_by", "admin@example.com");
        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));
        when(userIdResolver.resolve("admin@example.com", "tenant-1")).thenReturn(creator);
        List<String> actingDuringJob = new ArrayList<>();
        doAnswer(inv -> {
            actingDuringJob.add(ActingUser.current().orElse("none"));
            return null;
        }).when(bulkOperationService).processJob(anyString(), anyString(), anyString(), any(), anyInt(), any(), any());

        processorService.processQueuedJobs();

        verify(bulkOperationService).processJob(eq("job-1"), eq("col-1"), eq("UPDATE"),
                any(), eq(200), isNull(), eq(creator));
        assertThat(actingDuringJob).containsExactly(creator);
    }

    @Test
    @DisplayName("a creator that does not resolve to a user runs the job as nobody")
    void unresolvedCreatorRunsAsNobody() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job-1");
        job.put("tenant_id", "tenant-1");
        job.put("collection_id", "col-1");
        job.put("operation", "INSERT");
        job.put("batch_size", 200);
        job.put("external_id_field", null);
        job.put("data_payload", "[{\"name\":\"x\"}]");
        job.put("created_by", "gone@example.com");
        when(bulkJobRepository.findQueuedJobs(10)).thenReturn(List.of(job));
        // The resolver hands a miss back raw.
        when(userIdResolver.resolve("gone@example.com", "tenant-1")).thenReturn("gone@example.com");

        processorService.processQueuedJobs();

        verify(bulkOperationService).processJob(eq("job-1"), eq("col-1"), eq("INSERT"),
                any(), eq(200), isNull(), isNull());
    }
}
