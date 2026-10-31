package io.kelta.worker.listener;

import io.kelta.runtime.context.ActingUser;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.FieldHistoryRepository;
import io.kelta.worker.repository.FieldHistoryRepository.Change;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FieldHistoryHook")
class FieldHistoryHookTest {

    @Mock
    private FieldHistoryRepository historyRepository;
    @Mock
    private CollectionRegistry collectionRegistry;
    @Mock
    private CollectionLifecycleManager lifecycleManager;
    @Mock
    private QueryEngine queryEngine;

    private FieldHistoryHook hook;

    private static final FieldDefinition TRACKED =
            FieldDefinition.string("status").withTrackHistory(true);
    private static final FieldDefinition UNTRACKED = FieldDefinition.string("name");

    @BeforeEach
    void setUp() {
        hook = new FieldHistoryHook(historyRepository, collectionRegistry, lifecycleManager,
                queryEngine, new ObjectMapper());
    }

    private CollectionDefinition userCollectionWithTrackedField() {
        CollectionDefinition def = mock(CollectionDefinition.class);
        when(def.systemCollection()).thenReturn(false);
        when(def.fields()).thenReturn(List.of(TRACKED, UNTRACKED));
        when(collectionRegistry.get("orders")).thenReturn(def);
        when(lifecycleManager.getCollectionIdByName("orders")).thenReturn("col-1");
        return def;
    }

    @SuppressWarnings("unchecked")
    private List<Change> captureChanges() {
        ArgumentCaptor<List<Change>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).recordChanges(eq("tenant-1"), eq("col-1"), anyString(),
                anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("update records only tracked fields whose value changed")
    void updateRecordsChangedTrackedField() {
        userCollectionWithTrackedField();
        Map<String, Object> previous = new HashMap<>(Map.of("status", "NEW", "name", "A"));
        Map<String, Object> updated = new HashMap<>(Map.of("status", "DONE", "name", "A", "updatedBy", "user-1"));

        hook.afterUpdate("orders", "rec-1", updated, previous, "tenant-1");

        List<Change> changes = captureChanges();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).fieldName()).isEqualTo("status");
        assertThat(changes.get(0).oldValue()).isEqualTo("\"NEW\"");
        assertThat(changes.get(0).newValue()).isEqualTo("\"DONE\"");
    }

    @Test
    @DisplayName("update with no tracked-field change writes nothing")
    void updateNoTrackedChangeNoWrite() {
        userCollectionWithTrackedField();
        Map<String, Object> previous = new HashMap<>(Map.of("status", "NEW", "name", "A"));
        Map<String, Object> updated = new HashMap<>(Map.of("status", "NEW", "name", "B"));

        hook.afterUpdate("orders", "rec-1", updated, previous, "tenant-1");

        verify(historyRepository, never()).recordChanges(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create records tracked fields with a non-null initial value (old=null)")
    void createRecordsInitialValues() {
        userCollectionWithTrackedField();
        Map<String, Object> record = new HashMap<>(Map.of(
                "id", "rec-1", "status", "NEW", "name", "A", "createdBy", "user-1"));

        hook.afterCreate("orders", record, "tenant-1");

        List<Change> changes = captureChanges();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).fieldName()).isEqualTo("status");
        assertThat(changes.get(0).oldValue()).isNull();
        assertThat(changes.get(0).newValue()).isEqualTo("\"NEW\"");
    }

    @Test
    @DisplayName("a delete is credited to the user doing it, not the record's last updater")
    void deleteCreditedToActingUser() {
        CollectionDefinition def = userCollectionWithTrackedField();
        when(queryEngine.getById(def, "rec-1"))
                .thenReturn(Optional.of(new HashMap<>(Map.of("status", "DONE", "updatedBy", "user-1"))));

        ActingUser.runAs("user-2", () -> hook.beforeDelete("orders", "rec-1", "tenant-1"));

        verify(historyRepository).recordChanges(eq("tenant-1"), eq("col-1"), eq("rec-1"),
                eq("user-2"), eq("UI"), any());
    }

    @Test
    @DisplayName("delete re-reads the record and records old=value, new=null; proceeds")
    void deleteRecordsFinalValues() {
        CollectionDefinition def = userCollectionWithTrackedField();
        when(queryEngine.getById(def, "rec-1"))
                .thenReturn(Optional.of(new HashMap<>(Map.of("status", "DONE", "updatedBy", "user-1"))));

        var result = hook.beforeDelete("orders", "rec-1", "tenant-1");

        assertThat(result.isSuccess()).isTrue();
        List<Change> changes = captureChanges();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).fieldName()).isEqualTo("status");
        assertThat(changes.get(0).oldValue()).isEqualTo("\"DONE\"");
        assertThat(changes.get(0).newValue()).isNull();
    }

    @Test
    @DisplayName("skips collections with collection-level trackHistory (RecordVersionHook supersedes)")
    void skipsCollectionLevelTracking() {
        CollectionDefinition def = mock(CollectionDefinition.class);
        when(def.systemCollection()).thenReturn(false);
        when(def.trackHistory()).thenReturn(true);
        when(collectionRegistry.get("orders")).thenReturn(def);

        hook.afterUpdate("orders", "rec-1", new HashMap<>(Map.of("status", "DONE")),
                new HashMap<>(Map.of("status", "NEW")), "tenant-1");

        verify(historyRepository, never()).recordChanges(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("skips system collections")
    void skipsSystemCollection() {
        CollectionDefinition def = mock(CollectionDefinition.class);
        when(def.systemCollection()).thenReturn(true);
        when(collectionRegistry.get("flows")).thenReturn(def);

        hook.afterUpdate("flows", "f1", new HashMap<>(Map.of("status", "X")),
                new HashMap<>(Map.of("status", "Y")), "tenant-1");

        verify(historyRepository, never()).recordChanges(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("is a wildcard hook running late (after veto hooks)")
    void wildcardAndOrder() {
        assertThat(hook.getCollectionName()).isEqualTo("*");
        assertThat(hook.getOrder()).isEqualTo(900);
    }
}
