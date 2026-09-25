package io.kelta.worker.listener;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.RecordVersionRepository;
import io.kelta.worker.service.CollectionLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordVersionHook")
class RecordVersionHookTest {

    @Mock
    private RecordVersionRepository versionRepository;
    @Mock
    private CollectionRegistry collectionRegistry;
    @Mock
    private CollectionLifecycleManager lifecycleManager;
    @Mock
    private QueryEngine queryEngine;

    private RecordVersionHook hook;

    private static final FieldDefinition STATUS = FieldDefinition.string("status");
    private static final FieldDefinition NAME = FieldDefinition.string("name");
    private static final FieldDefinition CREATED_AT = FieldDefinition.datetime("created_at");

    @BeforeEach
    void setUp() {
        hook = new RecordVersionHook(versionRepository, collectionRegistry, lifecycleManager,
                queryEngine, new ObjectMapper());
    }

    private CollectionDefinition trackedCollection() {
        CollectionDefinition def = mock(CollectionDefinition.class);
        when(def.systemCollection()).thenReturn(false);
        when(def.trackHistory()).thenReturn(true);
        // lenient: the delete path never enumerates fields (changedFields is empty)
        lenient().when(def.fields()).thenReturn(List.of(STATUS, NAME, CREATED_AT));
        when(collectionRegistry.get("orders")).thenReturn(def);
        when(lifecycleManager.getCollectionIdByName("orders")).thenReturn("col-1");
        return def;
    }

    private record CapturedVersion(String changeType, String snapshotJson,
                                   String changedFieldsJson, String changedBy, String changeSource) {
    }

    private CapturedVersion captureVersion() {
        ArgumentCaptor<String> changeType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> changedFields = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> changedBy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> changeSource = ArgumentCaptor.forClass(String.class);
        verify(versionRepository).recordVersion(eq("tenant-1"), eq("col-1"), anyString(),
                changeType.capture(), snapshot.capture(), changedFields.capture(),
                changedBy.capture(), changeSource.capture());
        return new CapturedVersion(changeType.getValue(), snapshot.getValue(),
                changedFields.getValue(), changedBy.getValue(), changeSource.getValue());
    }

    @Test
    @DisplayName("create writes a CREATED version with all non-null user fields as changed")
    void createWritesVersion() {
        trackedCollection();
        Map<String, Object> record = new HashMap<>(Map.of(
                "id", "rec-1", "status", "NEW", "createdBy", "user-1", "created_at", "2026-01-01"));

        hook.afterCreate("orders", record, "tenant-1");

        CapturedVersion version = captureVersion();
        assertThat(version.changeType()).isEqualTo("CREATED");
        assertThat(version.changedFieldsJson()).isEqualTo("[\"status\"]");
        assertThat(version.snapshotJson()).contains("\"status\":\"NEW\"").contains("\"id\":\"rec-1\"");
        assertThat(version.changedBy()).isEqualTo("user-1");
        assertThat(version.changeSource()).isEqualTo("UI");
    }

    @Test
    @DisplayName("update writes an UPDATED version listing only changed user fields")
    void updateWritesChangedFields() {
        trackedCollection();
        Map<String, Object> previous = new HashMap<>(Map.of("status", "NEW", "name", "A"));
        Map<String, Object> updated = new HashMap<>(Map.of(
                "status", "DONE", "name", "A", "updatedBy", "user-2", "updated_at", "changed"));

        hook.afterUpdate("orders", "rec-1", updated, previous, "tenant-1");

        CapturedVersion version = captureVersion();
        assertThat(version.changeType()).isEqualTo("UPDATED");
        assertThat(version.changedFieldsJson()).isEqualTo("[\"status\"]");
        assertThat(version.changedBy()).isEqualTo("user-2");
    }

    @Test
    @DisplayName("no-op update (only audit fields differ) writes no version")
    void noOpUpdateSkipsVersion() {
        trackedCollection();
        Map<String, Object> previous = new HashMap<>(Map.of("status", "NEW", "name", "A"));
        Map<String, Object> updated = new HashMap<>(Map.of(
                "status", "NEW", "name", "A", "updated_at", "different"));

        hook.afterUpdate("orders", "rec-1", updated, previous, "tenant-1");

        verify(versionRepository, never()).recordVersion(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("delete snapshots the record before, and writes the DELETED version only after, the delete")
    void deleteWritesFinalSnapshot() {
        CollectionDefinition def = trackedCollection();
        when(queryEngine.getById(def, "rec-1")).thenReturn(Optional.of(new HashMap<>(Map.of(
                "id", "rec-1", "status", "DONE", "updated_by", "user-3"))));

        var result = hook.beforeDelete("orders", "rec-1", "tenant-1");

        assertThat(result.isSuccess()).isTrue();
        // Nothing written yet: the version (and its advisory lock) must come after the row lock
        // the delete takes, the same order as create/update — see RecordVersionHook#pendingDeletes.
        verify(versionRepository, never()).recordVersion(any(), any(), any(), any(), any(), any(), any(), any());

        hook.afterDelete("orders", "rec-1", "tenant-1");

        CapturedVersion version = captureVersion();
        assertThat(version.changeType()).isEqualTo("DELETED");
        assertThat(version.changedFieldsJson()).isEqualTo("[]");
        assertThat(version.snapshotJson()).contains("\"status\":\"DONE\"");
        assertThat(version.changedBy()).isEqualTo("user-3");
    }

    @Test
    @DisplayName("a vetoed or failed delete (no afterDelete) leaves nothing pending for the next one")
    void abandonedDeleteIsDropped() {
        CollectionDefinition def = trackedCollection();
        when(queryEngine.getById(def, "rec-1")).thenReturn(Optional.of(new HashMap<>(Map.of("id", "rec-1"))));
        when(queryEngine.getById(def, "rec-2")).thenReturn(Optional.of(new HashMap<>(Map.of("id", "rec-2"))));

        hook.beforeDelete("orders", "rec-1", "tenant-1");   // rec-1's delete never completes
        hook.beforeDelete("orders", "rec-2", "tenant-1");
        hook.afterDelete("orders", "rec-2", "tenant-1");
        hook.afterDelete("orders", "rec-1", "tenant-1");    // stray: must not write a version

        verify(versionRepository, times(1)).recordVersion(any(), any(), eq("rec-2"), eq("DELETED"),
                any(), any(), any(), any());
        verify(versionRepository, never()).recordVersion(any(), any(), eq("rec-1"), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("afterDelete without a captured snapshot writes nothing")
    void afterDeleteWithoutSnapshot() {
        hook.afterDelete("orders", "rec-9", "tenant-1");

        verify(versionRepository, never()).recordVersion(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("author falls back to system with SYSTEM change source")
    void systemAuthorFallback() {
        trackedCollection();
        Map<String, Object> record = new HashMap<>(Map.of("id", "rec-1", "status", "NEW"));

        hook.afterCreate("orders", record, "tenant-1");

        CapturedVersion version = captureVersion();
        assertThat(version.changedBy()).isEqualTo("system");
        assertThat(version.changeSource()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("skips collections without trackHistory")
    void skipsUntrackedCollection() {
        CollectionDefinition def = mock(CollectionDefinition.class);
        when(def.systemCollection()).thenReturn(false);
        when(def.trackHistory()).thenReturn(false);
        when(collectionRegistry.get("orders")).thenReturn(def);

        hook.afterCreate("orders", new HashMap<>(Map.of("id", "rec-1", "status", "NEW")), "tenant-1");

        verify(versionRepository, never()).recordVersion(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("skips system collections")
    void skipsSystemCollection() {
        CollectionDefinition def = mock(CollectionDefinition.class);
        when(def.systemCollection()).thenReturn(true);
        when(collectionRegistry.get("flows")).thenReturn(def);

        hook.afterCreate("flows", new HashMap<>(Map.of("id", "f-1")), "tenant-1");

        verify(versionRepository, never()).recordVersion(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("write failures are swallowed (best-effort, CRUD must not break)")
    void writeFailureSwallowed() {
        trackedCollection();
        doThrow(new RuntimeException("boom")).when(versionRepository)
                .recordVersion(any(), any(), any(), any(), any(), any(), any(), any());

        hook.afterCreate("orders", new HashMap<>(Map.of("id", "rec-1", "status", "NEW")), "tenant-1");
        // no exception propagated
    }

    @Test
    @DisplayName("is a wildcard hook running after FieldHistoryHook")
    void wildcardAndOrder() {
        assertThat(hook.getCollectionName()).isEqualTo("*");
        assertThat(hook.getOrder()).isEqualTo(910);
    }
}
