package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CollectionLifecycleManager.refreshOrInitializeLocally (issue #910)")
class CollectionLifecycleManagerTest {

    private CollectionLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = spy(new CollectionLifecycleManager(
                mock(CollectionRegistry.class),
                mock(StorageAdapter.class),
                mock(JdbcTemplate.class),
                mock(ObjectMapper.class)));
    }

    @Test
    @DisplayName("Refreshes when the collection is already active on this pod")
    void refreshesWhenActive() {
        doReturn(Set.of("col-1")).when(manager).getActiveCollections();
        doNothing().when(manager).refreshCollection("col-1");

        manager.refreshOrInitializeLocally("col-1");

        verify(manager).refreshCollection("col-1");
        verify(manager, never()).initializeCollection("col-1");
    }

    @Test
    @DisplayName("Initializes when the collection is not yet active (freshly created)")
    void initializesWhenNotActive() {
        doReturn(Set.of()).when(manager).getActiveCollections();
        doNothing().when(manager).initializeCollection("col-2");

        manager.refreshOrInitializeLocally("col-2");

        verify(manager).initializeCollection("col-2");
        verify(manager, never()).refreshCollection("col-2");
    }

    @Test
    @DisplayName("Swallows refresh failure so the originating request never breaks")
    void swallowsFailure() {
        doReturn(Set.of("col-3")).when(manager).getActiveCollections();
        doThrow(new RuntimeException("db down")).when(manager).refreshCollection("col-3");

        assertDoesNotThrow(() -> manager.refreshOrInitializeLocally("col-3"));
    }

    @Nested
    @DisplayName("field.system_maintained → FieldDefinition.systemMaintained (KLT-609)")
    class SystemMaintainedMapping {

        private static Map<String, Object> fieldRow(String name, Object systemMaintained) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", name);
            row.put("type", "number");
            row.put("system_maintained", systemMaintained);
            return row;
        }

        @Test
        @DisplayName("reads system_maintained into the field definition, defaulting to false")
        void mapsSystemMaintainedColumn() {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            CollectionLifecycleManager loader = new CollectionLifecycleManager(
                    mock(CollectionRegistry.class), mock(StorageAdapter.class), jdbcTemplate, new ObjectMapper());
            when(jdbcTemplate.queryForList(contains("system_maintained"), eq("col-1"))).thenReturn(List.of(
                    fieldRow("openCount", true),
                    fieldRow("score", false),
                    fieldRow("legacy", null)));

            CollectionDefinition def = loader.buildDefinitionFromDb("col-1", "inspections", Map.of());

            assertThat(def.getField("openCount").systemMaintained()).isTrue();
            assertThat(def.getField("score").systemMaintained()).isFalse();
            assertThat(def.getField("legacy").systemMaintained()).isFalse();
            assertThat(def.systemMaintainedFieldNames()).containsExactly("openCount");
        }
    }
}
