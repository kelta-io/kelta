package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.ValidationRuleRegistry;
import io.kelta.worker.config.WorkerMetricsConfig;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CollectionLifecycleManager methods not covered by
 * CollectionLifecycleManagerSystemCollectionTest: refresh, teardown
 * with validation rules, searchable fields, display field, metrics,
 * and active collection tracking.
 */
@DisplayName("CollectionLifecycleManager — refresh, search, metrics")
class CollectionLifecycleManagerRefreshTest {

    private CollectionRegistry collectionRegistry;
    private StorageAdapter storageAdapter;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private ValidationRuleRegistry validationRuleRegistry;
    private CollectionLifecycleManager manager;

    @BeforeEach
    void setUp() {
        collectionRegistry = mock(CollectionRegistry.class);
        storageAdapter = mock(StorageAdapter.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        validationRuleRegistry = mock(ValidationRuleRegistry.class);

        manager = new CollectionLifecycleManager(
                collectionRegistry, storageAdapter, jdbcTemplate, objectMapper);
        manager.setValidationRuleRegistry(validationRuleRegistry);
    }

    private Map<String, Object> buildUserRow(String name) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "coll-456");
        row.put("name", name);
        row.put("display_name", name);
        row.put("description", null);
        row.put("active", true);
        row.put("current_version", 1);
        row.put("system_collection", false);
        row.put("path", "/api/" + name);
        row.put("tenant_id", "tenant-abc");
        row.put("display_field_id", null);
        return row;
    }

    private List<Map<String, Object>> buildFieldRows() {
        Map<String, Object> f1 = new HashMap<>();
        f1.put("name", "title");
        f1.put("type", "string");
        f1.put("required", true);
        f1.put("unique_constraint", false);
        f1.put("indexed", false);
        f1.put("default_value", null);
        f1.put("constraints", null);
        f1.put("field_type_config", null);
        f1.put("reference_target", null);
        f1.put("relationship_type", null);
        f1.put("relationship_name", null);
        f1.put("cascade_delete", false);
        f1.put("field_order", 0);
        f1.put("column_name", null);
        f1.put("immutable", false);
        f1.put("searchable", false);
        return List.of(f1);
    }

    private void mockDbForCollection(String collectionId, Map<String, Object> row,
                                       List<Map<String, Object>> fieldRows) {
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq(collectionId)))
                .thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(contains("FROM field WHERE"), eq(collectionId)))
                .thenReturn(fieldRows);
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq(collectionId),
                eq(row.get("tenant_id"))))
                .thenReturn(List.of());
    }

    private void initializeCollection(String id, String name) {
        Map<String, Object> row = buildUserRow(name);
        mockDbForCollection(id, row, buildFieldRows());
        manager.initializeCollection(id);
    }

    @Nested
    @DisplayName("refreshCollection")
    class RefreshTests {

        @Test
        @DisplayName("Should re-register and migrate schema on refresh")
        void reRegistersAndMigratesSchema() {
            // Initialize first
            initializeCollection("coll-456", "orders");

            CollectionDefinition oldDef = mock(CollectionDefinition.class);
            when(collectionRegistry.get("orders")).thenReturn(oldDef);

            // Refresh
            manager.refreshCollection("coll-456");

            // Should register updated definition
            verify(collectionRegistry, atLeast(2)).register(any(CollectionDefinition.class));
            // Should call updateCollectionSchema
            verify(storageAdapter).updateCollectionSchema(eq(oldDef), any(CollectionDefinition.class));
        }

        @Test
        @DisplayName("Should initialize storage when no old definition exists")
        void initializesStorageWhenNoOldDef() {
            initializeCollection("coll-456", "orders");

            when(collectionRegistry.get("orders")).thenReturn(null);

            manager.refreshCollection("coll-456");

            // Should call initializeCollection on storage instead of update
            verify(storageAdapter, atLeast(2)).initializeCollection(any(CollectionDefinition.class));
        }

        @Test
        @DisplayName("Should skip refresh for unknown collection")
        void skipsRefreshForUnknown() {
            manager.refreshCollection("unknown-id");

            verify(collectionRegistry, never()).register(any());
        }

        @Test
        @DisplayName("Should handle DB error during refresh gracefully")
        void handlesDbErrorGracefully() {
            initializeCollection("coll-456", "orders");

            // Make the second DB call fail
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("coll-456")))
                    .thenReturn(List.of()); // empty = not found

            manager.refreshCollection("coll-456");

            // Should not crash, collection is still active
            assertThat(manager.getActiveCollections()).contains("coll-456");
        }
    }

    @Nested
    @DisplayName("teardownCollection with validation rules")
    class TeardownWithValidationTests {

        @Test
        @DisplayName("Should unregister validation rules on teardown")
        void unregistersValidationRules() {
            initializeCollection("coll-456", "orders");

            manager.teardownCollection("coll-456");

            verify(validationRuleRegistry).unregister("tenant-abc", "orders");
            verify(collectionRegistry).unregister("tenant-abc:orders");
        }

        @Test
        @DisplayName("Should handle teardown of unknown collection gracefully")
        void handlesTeardownOfUnknown() {
            manager.teardownCollection("unknown-id");

            verify(collectionRegistry, never()).unregister(anyString());
            verify(validationRuleRegistry, never()).unregister(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("getSearchableFieldNames")
    class SearchableFieldTests {

        @Test
        @DisplayName("Should return searchable field names from DB")
        void returnsSearchableFields() {
            when(jdbcTemplate.queryForList(contains("searchable = true"), eq("orders")))
                    .thenReturn(List.of(
                            Map.of("name", "title"),
                            Map.of("name", "description")
                    ));

            Set<String> result = manager.getSearchableFieldNames("orders");

            assertThat(result).containsExactlyInAnyOrder("title", "description");
        }

        @Test
        @DisplayName("Should return empty set on DB error")
        void returnsEmptyOnError() {
            when(jdbcTemplate.queryForList(contains("searchable = true"), eq("orders")))
                    .thenThrow(new RuntimeException("DB error"));

            Set<String> result = manager.getSearchableFieldNames("orders");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getDisplayFieldName")
    class DisplayFieldTests {

        @Test
        @DisplayName("Should return display field name from DB")
        void returnsDisplayFieldName() {
            when(jdbcTemplate.queryForList(contains("display_field_id"), eq("orders")))
                    .thenReturn(List.of(Map.of("name", "order_number")));

            String result = manager.getDisplayFieldName("orders");

            assertThat(result).isEqualTo("order_number");
        }

        @Test
        @DisplayName("Should return null when no display field configured")
        void returnsNullWhenNotConfigured() {
            when(jdbcTemplate.queryForList(contains("display_field_id"), eq("orders")))
                    .thenReturn(List.of());

            String result = manager.getDisplayFieldName("orders");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getCollectionIdByName")
    class CollectionIdByNameTests {

        @Test
        @DisplayName("Should return collection ID for known name")
        void returnsIdForKnownName() {
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE name"), eq("orders")))
                    .thenReturn(List.of(Map.of("id", "coll-789")));

            String result = manager.getCollectionIdByName("orders");

            assertThat(result).isEqualTo("coll-789");
        }

        @Test
        @DisplayName("Should return null for unknown name")
        void returnsNullForUnknown() {
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE name"), eq("unknown")))
                    .thenReturn(List.of());

            String result = manager.getCollectionIdByName("unknown");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Metrics integration")
    class MetricsTests {

        @Test
        @DisplayName("Should increment and decrement initializing count during init")
        void incrementsAndDecrementsCount() {
            WorkerMetricsConfig metricsConfig = mock(WorkerMetricsConfig.class);
            AtomicInteger counter = new AtomicInteger(0);
            when(metricsConfig.getInitializingCount()).thenReturn(counter);
            manager.setMetricsConfig(metricsConfig);

            // Force early return with empty result
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("missing")))
                    .thenReturn(List.of());

            manager.initializeCollection("missing");

            // Should be back to 0 after finally block
            assertThat(counter.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should decrement even on exception")
        void decrementsOnException() {
            WorkerMetricsConfig metricsConfig = mock(WorkerMetricsConfig.class);
            AtomicInteger counter = new AtomicInteger(0);
            when(metricsConfig.getInitializingCount()).thenReturn(counter);
            manager.setMetricsConfig(metricsConfig);

            when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("err")))
                    .thenThrow(new RuntimeException("DB down"));

            manager.initializeCollection("err");

            assertThat(counter.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Active collection tracking")
    class ActiveCollectionTests {

        @Test
        @DisplayName("Should return defensive copy of active collections")
        void returnsDefensiveCopy() {
            Set<String> first = manager.getActiveCollections();
            Set<String> second = manager.getActiveCollections();
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("Should track multiple collections")
        void tracksMultipleCollections() {
            initializeCollection("coll-1", "products");

            Map<String, Object> row2 = buildUserRow("orders");
            row2.put("id", "coll-2");
            mockDbForCollection("coll-2", row2, buildFieldRows());
            manager.initializeCollection("coll-2");

            assertThat(manager.getActiveCollectionCount()).isEqualTo(2);
            assertThat(manager.getActiveCollections()).containsExactlyInAnyOrder("coll-1", "coll-2");
        }
    }

    @Nested
    @DisplayName("Validation rule loading")
    class ValidationRuleTests {

        @Test
        @DisplayName("Should load and register validation rules from DB")
        void loadsAndRegistersRules() {
            Map<String, Object> ruleRow = new HashMap<>();
            ruleRow.put("name", "RequireName");
            ruleRow.put("error_condition_formula", "ISBLANK(name)");
            ruleRow.put("error_message", "Name is required");
            ruleRow.put("error_field", "name");
            ruleRow.put("evaluate_on", "CREATE_AND_UPDATE");
            ruleRow.put("active", true);

            Map<String, Object> collRow = buildUserRow("products");
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("coll-456")))
                    .thenReturn(List.of(collRow));
            when(jdbcTemplate.queryForList(contains("FROM field WHERE"), eq("coll-456")))
                    .thenReturn(buildFieldRows());
            when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("coll-456"),
                    eq("tenant-abc")))
                    .thenReturn(List.of(ruleRow));

            manager.initializeCollection("coll-456");

            verify(validationRuleRegistry).register(eq("tenant-abc"), eq("products"), argThat(rules ->
                    rules.size() == 1 && "RequireName".equals(rules.get(0).name())));
        }

        @Test
        @DisplayName("Should mark the load failed, never register an empty set, on DB error")
        void marksLoadFailedOnError() {
            Map<String, Object> collRow = buildUserRow("products");
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("coll-456")))
                    .thenReturn(List.of(collRow));
            when(jdbcTemplate.queryForList(contains("FROM field WHERE"), eq("coll-456")))
                    .thenReturn(buildFieldRows());
            when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("coll-456"),
                    eq("tenant-abc")))
                    .thenThrow(new RuntimeException("DB error"));

            manager.initializeCollection("coll-456");

            verify(validationRuleRegistry).markLoadFailed("tenant-abc", "products");
            verify(validationRuleRegistry, never()).register(anyString(), anyString(), anyList());
        }
    }
}
