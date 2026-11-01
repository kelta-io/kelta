package io.kelta.worker.service;

import io.kelta.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerBootstrapService")
class WorkerBootstrapServiceTest {

    @Mock
    private WorkerProperties workerProperties;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CollectionLifecycleManager lifecycleManager;

    private WorkerBootstrapService service;

    @BeforeEach
    void setUp() {
        when(workerProperties.getId()).thenReturn("worker-1");
        // Production default: pods register collections but do not apply DDL — the migrate
        // Job owns that, once, before the rollout.
        service = new WorkerBootstrapService(workerProperties, jdbcTemplate, lifecycleManager, false);
    }

    @Test
    @DisplayName("initializes all active collections on startup")
    void initializesAllActiveCollections() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", "col-1", "name", "accounts"),
                Map.of("id", "col-2", "name", "contacts")
        ));

        service.onApplicationReady();

        verify(lifecycleManager).initializeCollection("col-1", false);
        verify(lifecycleManager).initializeCollection("col-2", false);
    }

    @Test
    @DisplayName("handles empty collection list gracefully")
    void handlesEmptyCollectionList() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        service.onApplicationReady();

        verify(lifecycleManager, never()).initializeCollection(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("continues initializing other collections when one fails")
    void continuesOnFailure() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", "col-1", "name", "accounts"),
                Map.of("id", "col-2", "name", "contacts")
        ));
        doThrow(new RuntimeException("init failed")).when(lifecycleManager).initializeCollection("col-1", false);

        service.onApplicationReady();

        verify(lifecycleManager).initializeCollection("col-1", false);
        verify(lifecycleManager).initializeCollection("col-2", false);
    }

    @Test
    @DisplayName("skips collections with null id")
    void skipsNullId() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", null);
        row.put("name", "broken");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        service.onApplicationReady();

        verify(lifecycleManager, never()).initializeCollection(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("applies schema at startup only when schema-bootstrap is explicitly enabled")
    void appliesSchemaWhenEnabled() {
        // Escape hatch for deployments with no migrate Job (single pod, local runs).
        WorkerBootstrapService schemaApplying =
                new WorkerBootstrapService(workerProperties, jdbcTemplate, lifecycleManager, true);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", "col-1", "name", "accounts")
        ));

        schemaApplying.onApplicationReady();

        verify(lifecycleManager).initializeCollection("col-1", true);
    }

    @Test
    @DisplayName("handles database query failure gracefully")
    void handlesDatabaseFailure() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        service.onApplicationReady();

        verify(lifecycleManager, never()).initializeCollection(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a consumer ahead of the ready event bootstraps once; the event then skips it")
    void ensureBootstrappedRunsOnce() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", "col-1", "name", "accounts")
        ));

        service.ensureBootstrapped();
        service.ensureBootstrapped();
        service.onApplicationReady();

        verify(jdbcTemplate, times(1)).queryForList(anyString());
        verify(lifecycleManager, times(1)).initializeCollection("col-1", false);
    }

    @Test
    @DisplayName("a failed bootstrap is tried again by the next consumer")
    void failedBootstrapIsRetried() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenThrow(new RuntimeException("Connection refused"))
                .thenReturn(List.of(Map.of("id", "col-1", "name", "accounts")));

        service.onApplicationReady();
        service.ensureBootstrapped();

        verify(lifecycleManager).initializeCollection("col-1", false);
    }
}
