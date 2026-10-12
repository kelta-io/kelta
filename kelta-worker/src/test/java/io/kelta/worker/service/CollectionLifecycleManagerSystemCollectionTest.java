package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.system.CoreSystemCollections;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CollectionLifecycleManager's DB-direct collection loading.
 *
 * <p>Verifies that the manager correctly:
 * <ul>
 *   <li>Uses canonical SystemCollectionDefinitions for system collections</li>
 *   <li>Builds definitions from DB data for user-defined collections</li>
 *   <li>Reverse-maps field types from DB format to FieldType enum</li>
 *   <li>Loads validation rules from the database</li>
 * </ul>
 */
class CollectionLifecycleManagerSystemCollectionTest {

    private CollectionRegistry collectionRegistry;
    private StorageAdapter storageAdapter;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private CollectionLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        collectionRegistry = mock(CollectionRegistry.class);
        storageAdapter = mock(StorageAdapter.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();

        lifecycleManager = new CollectionLifecycleManager(
                collectionRegistry, storageAdapter, jdbcTemplate, objectMapper);
    }

    // ==================== Helper Methods ====================

    /**
     * Builds a mock DB row for a system collection.
     */
    private Map<String, Object> buildSystemCollectionRow(String name) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "coll-123");
        row.put("name", name);
        row.put("display_name", "System " + name);
        row.put("description", "A system collection");
        row.put("storage_mode", "PHYSICAL_TABLES");
        row.put("active", true);
        row.put("current_version", 1);
        row.put("system_collection", true);
        row.put("path", "/api/" + name);
        row.put("tenant_id", "00000000-0000-0000-0000-000000000001");
        return row;
    }

    /**
     * Builds a mock DB row for a user-defined collection.
     */
    private Map<String, Object> buildUserCollectionRow(String name) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "coll-456");
        row.put("name", name);
        row.put("display_name", name);
        row.put("description", null);
        row.put("storage_mode", "PHYSICAL_TABLES");
        row.put("active", true);
        row.put("current_version", 1);
        row.put("system_collection", false);
        row.put("path", "/api/" + name);
        row.put("tenant_id", "tenant-abc");
        return row;
    }

    /**
     * Builds mock field DB rows.
     */
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

        Map<String, Object> f2 = new HashMap<>();
        f2.put("name", "price");
        f2.put("type", "number");
        f2.put("required", false);
        f2.put("unique_constraint", false);
        f2.put("indexed", false);
        f2.put("default_value", null);
        f2.put("constraints", null);
        f2.put("field_type_config", null);
        f2.put("reference_target", null);
        f2.put("relationship_type", null);
        f2.put("relationship_name", null);
        f2.put("cascade_delete", false);
        f2.put("field_order", 1);
        f2.put("column_name", null);
        f2.put("immutable", false);

        return List.of(f1, f2);
    }

    /**
     * Sets up DB mocks for collection + fields + validation rules.
     */
    private void mockDbForCollection(String collectionId, Map<String, Object> collectionRow,
                                       List<Map<String, Object>> fieldRows) {
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq(collectionId)))
                .thenReturn(List.of(collectionRow));
        when(jdbcTemplate.queryForList(contains("FROM field WHERE"), eq(collectionId)))
                .thenReturn(fieldRows);
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq(collectionId)))
                .thenReturn(List.of());
    }

    // ==================== System Collection Tests ====================

    @Nested
    @DisplayName("System Collection Loading")
    class SystemCollectionTests {

        @Test
        @DisplayName("Should use canonical definition for system collections")
        void usesCanonicalDefinitionForSystemCollections() {
            // Given: a system collection "users" exists in the DB
            Map<String, Object> row = buildSystemCollectionRow("users");
            mockDbForCollection("coll-123", row, List.of());

            // When
            lifecycleManager.initializeCollection("coll-123");

            // Then: should register the canonical SystemCollectionDefinition
            ArgumentCaptor<CollectionDefinition> defCaptor =
                    ArgumentCaptor.forClass(CollectionDefinition.class);
            verify(collectionRegistry).register(defCaptor.capture());

            CollectionDefinition registered = defCaptor.getValue();
            CollectionDefinition canonical = CoreSystemCollections.users();

            assertThat(registered.name()).isEqualTo("users");
            assertThat(registered.systemCollection()).isTrue();
            assertThat(registered.tenantScoped()).isEqualTo(canonical.tenantScoped());
            assertThat(registered.fields()).hasSameSizeAs(canonical.fields());
            assertThat(registered.storageConfig().tableName())
                    .isEqualTo(canonical.storageConfig().tableName());
        }

        @Test
        @DisplayName("Should use canonical definition for profiles")
        void usesCanonicalDefinitionForProfiles() {
            Map<String, Object> row = buildSystemCollectionRow("profiles");
            mockDbForCollection("coll-123", row, List.of());

            lifecycleManager.initializeCollection("coll-123");

            ArgumentCaptor<CollectionDefinition> defCaptor =
                    ArgumentCaptor.forClass(CollectionDefinition.class);
            verify(collectionRegistry).register(defCaptor.capture());

            CollectionDefinition registered = defCaptor.getValue();
            assertThat(registered.name()).isEqualTo("profiles");
            assertThat(registered.systemCollection()).isTrue();
            assertThat(registered.fields()).hasSize(
                    CoreSystemCollections.profiles().fields().size());
        }

    }

    // ==================== User Collection Tests ====================

    @Nested
    @DisplayName("User Collection Loading")
    class UserCollectionTests {

        @Test
        @DisplayName("Should build definition from DB for user-defined collections")
        void buildsFromDbForUserCollections() {
            Map<String, Object> row = buildUserCollectionRow("products");
            mockDbForCollection("coll-456", row, buildFieldRows());

            lifecycleManager.initializeCollection("coll-456");

            ArgumentCaptor<CollectionDefinition> defCaptor =
                    ArgumentCaptor.forClass(CollectionDefinition.class);
            verify(collectionRegistry).register(defCaptor.capture());

            CollectionDefinition registered = defCaptor.getValue();
            assertThat(registered.name()).isEqualTo("products");
            assertThat(registered.systemCollection()).isFalse();
            // 2 user-defined fields + 4 injected system audit fields
            assertThat(registered.fields()).hasSize(6);
        }

        @Test
        @DisplayName("Should reverse-map field types from DB format")
        void reverseMapFieldTypes() {
            Map<String, Object> row = buildUserCollectionRow("orders");
            mockDbForCollection("coll-456", row, buildFieldRows());

            lifecycleManager.initializeCollection("coll-456");

            ArgumentCaptor<CollectionDefinition> defCaptor =
                    ArgumentCaptor.forClass(CollectionDefinition.class);
            verify(collectionRegistry).register(defCaptor.capture());

            CollectionDefinition registered = defCaptor.getValue();
            // "string" → STRING, "number" → DOUBLE
            assertThat(registered.fields().get(0).type()).isEqualTo(FieldType.STRING);
            assertThat(registered.fields().get(1).type()).isEqualTo(FieldType.DOUBLE);
        }

        @Test
        @DisplayName("Should parse reference fields from DB")
        void parsesReferenceFields() {
            Map<String, Object> row = buildUserCollectionRow("invoices");
            Map<String, Object> refField = new HashMap<>();
            refField.put("name", "customerId");
            refField.put("type", "string");
            refField.put("required", true);
            refField.put("unique_constraint", false);
            refField.put("indexed", false);
            refField.put("default_value", null);
            refField.put("constraints", null);
            refField.put("field_type_config", null);
            refField.put("reference_target", "customers");
            refField.put("relationship_type", "LOOKUP");
            refField.put("relationship_name", "Customer");
            refField.put("cascade_delete", false);
            refField.put("field_order", 0);
            refField.put("column_name", "customer_id");
            refField.put("immutable", false);

            mockDbForCollection("coll-456", row, List.of(refField));

            lifecycleManager.initializeCollection("coll-456");

            ArgumentCaptor<CollectionDefinition> defCaptor =
                    ArgumentCaptor.forClass(CollectionDefinition.class);
            verify(collectionRegistry).register(defCaptor.capture());

            CollectionDefinition registered = defCaptor.getValue();
            assertThat(registered.fields().get(0).referenceConfig()).isNotNull();
            assertThat(registered.fields().get(0).referenceConfig().targetCollection())
                    .isEqualTo("customers");
            assertThat(registered.fields().get(0).referenceConfig().relationshipType())
                    .isEqualTo("LOOKUP");
            assertThat(registered.fields().get(0).columnName()).isEqualTo("customer_id");
        }

        @Test
        @DisplayName("Should not call storageAdapter for missing collection")
        void skipsStorageForMissingCollection() {
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq("missing")))
                    .thenReturn(List.of());

            lifecycleManager.initializeCollection("missing");

            verify(collectionRegistry, never()).register(any());
            verify(storageAdapter, never()).initializeCollection(any());
        }
    }

    // ==================== reverseMapFieldType Tests ====================

    @Nested
    @DisplayName("reverseMapFieldType")
    class ReverseMapFieldTypeTests {

        @Test
        void shouldMapString() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("string"))
                    .isEqualTo(FieldType.STRING);
        }

        @Test
        void shouldMapNumber() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("number"))
                    .isEqualTo(FieldType.DOUBLE);
        }

        @Test
        void shouldMapBoolean() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("boolean"))
                    .isEqualTo(FieldType.BOOLEAN);
        }

        @Test
        void shouldMapDate() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("date"))
                    .isEqualTo(FieldType.DATE);
        }

        @Test
        void shouldMapDatetime() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("datetime"))
                    .isEqualTo(FieldType.DATETIME);
        }

        @Test
        void shouldMapObject() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("object"))
                    .isEqualTo(FieldType.JSON);
        }

        @Test
        void shouldMapArray() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("array"))
                    .isEqualTo(FieldType.ARRAY);
        }

        @Test
        void shouldHandleNull() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType(null))
                    .isEqualTo(FieldType.STRING);
        }

        @Test
        void shouldHandleUnknownType() {
            assertThat(CollectionLifecycleManager.reverseMapFieldType("xyz_unknown"))
                    .isEqualTo(FieldType.STRING);
        }

        @Test
        void shouldHandleDirectEnumMatch() {
            // If someone stores "STRING" (uppercase enum name) in DB
            assertThat(CollectionLifecycleManager.reverseMapFieldType("STRING"))
                    .isEqualTo(FieldType.STRING);
        }
    }

    // ==================== Load Collection By Name Tests ====================

    @Nested
    @DisplayName("loadCollectionByName")
    class LoadCollectionByNameTests {

        @Test
        @DisplayName("Should return existing definition if already loaded")
        void returnsExistingDefinition() {
            CollectionDefinition existingDef = CoreSystemCollections.tenants();
            when(collectionRegistry.get("tenants")).thenReturn(existingDef);

            CollectionDefinition result = lifecycleManager.loadCollectionByName("tenants", null);

            assertThat(result).isSameAs(existingDef);
            // Should not query DB
            verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("Should return null for nonexistent collection")
        void returnsNullForNonexistent() {
            when(collectionRegistry.get("nonexistent")).thenReturn(null);
            when(jdbcTemplate.queryForList(contains("FROM collection WHERE name"), eq("nonexistent")))
                    .thenReturn(List.of());

            CollectionDefinition result = lifecycleManager.loadCollectionByName("nonexistent", null);

            assertThat(result).isNull();
        }
    }

    // ==================== Teardown Tests ====================

    @Nested
    @DisplayName("teardownCollection")
    class TeardownTests {

        @Test
        @DisplayName("Should unregister collection on teardown")
        void unregistersOnTeardown() {
            // First initialize a collection
            Map<String, Object> row = buildUserCollectionRow("products");
            mockDbForCollection("coll-456", row, buildFieldRows());
            lifecycleManager.initializeCollection("coll-456");

            // Then tear it down
            lifecycleManager.teardownCollection("coll-456");

            verify(collectionRegistry).unregister("products");
        }
    }
}
