package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.SortField;
import io.kelta.runtime.query.SortDirection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhysicalTableStorageAdapter.
 * 
 * Uses H2 in-memory database for testing.
 */
class PhysicalTableStorageAdapterTest {
    
    private JdbcTemplate jdbcTemplate;
    private SchemaMigrationEngine migrationEngine;
    private PhysicalTableStorageAdapter adapter;
    private CollectionDefinition testCollection;
    
    @BeforeEach
    void setUp() {
        // Create embedded H2 database
        // DATABASE_TO_LOWER makes H2 fold unquoted identifiers to lower case the way
        // PostgreSQL does. Without it, DDL that quotes a lower-case column name (needed so
        // reserved words like `user` parse) would not match the upper-cased column H2
        // creates for the same name unquoted — a divergence from production, not a real bug.
        DataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:h2:mem:adapter;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        
        jdbcTemplate = new JdbcTemplate(dataSource);
        
        // Drop migration table if exists (for clean test runs)
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS kelta_migrations");
        } catch (Exception e) {
            // Ignore
        }
        
        migrationEngine = new SchemaMigrationEngine(jdbcTemplate);
        adapter = new PhysicalTableStorageAdapter(jdbcTemplate, migrationEngine, new tools.jackson.databind.ObjectMapper());
        
        // Create a test collection definition
        testCollection = createTestCollection("test_products");
        
        // Drop table if exists (for clean test runs)
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_products");
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private CollectionDefinition createTestCollection(String name) {
        List<FieldDefinition> fields = List.of(
            new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
            new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null),
            new FieldDefinition("price", FieldType.DOUBLE, false, false, false, null, null, null, null, null),
            new FieldDefinition("quantity", FieldType.INTEGER, true, false, false, null, null, null, null, null),
            new FieldDefinition("active", FieldType.BOOLEAN, true, false, false, null, null, null, null, null),
            new FieldDefinition("sku", FieldType.STRING, false, false, true, null, null, null, null, null)
        );
        
        StorageConfig storageConfig = new StorageConfig(
            name,
            Map.of()
        );
        
        return new CollectionDefinition(
            name,
            "Test Products",
            "A test collection for products",
            fields,
            storageConfig,
            null,
            null,
            1L,
            Instant.now(),
            Instant.now()
        );
    }
    
    @Nested
    @DisplayName("dropCollection Tests")
    class DropCollectionTests {

        private boolean tableExists(String table) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE upper(table_name) = upper(?)",
                    Integer.class, table);
            return n != null && n > 0;
        }

        @Test
        @DisplayName("drops a user collection's table")
        void dropsUserTable() {
            adapter.initializeCollection(testCollection);
            assertTrue(tableExists("test_products"), "table should exist before the drop");

            adapter.dropCollection(testCollection);

            assertFalse(tableExists("test_products"),
                    "collection delete must not leak the physical table");
        }

        @Test
        @DisplayName("REFUSES to drop a system collection's Flyway-owned table")
        void refusesToDropSystemTable() {
            // Dropping one of these would destroy platform metadata for EVERY tenant, so this
            // is the single most important property of dropCollection. Mirrors the same
            // systemCollection() guard initializeCollection uses to skip creation.
            adapter.initializeCollection(testCollection);
            assertTrue(tableExists("test_products"));

            adapter.dropCollection(asSystemCollection(testCollection));

            assertTrue(tableExists("test_products"),
                    "a system collection's table is Flyway-owned and must survive");
        }

        @Test
        @DisplayName("a missing table is a no-op, not an error")
        void missingTableIsNoOp() {
            // Runs after the metadata delete has committed, so it must never throw.
            assertFalse(tableExists("test_products"));
            adapter.dropCollection(testCollection);
        }

        @Test
        @DisplayName("a null definition is a no-op")
        void nullDefinitionIsNoOp() {
            adapter.dropCollection(null);
        }

        private CollectionDefinition asSystemCollection(CollectionDefinition source) {
            return new CollectionDefinition(
                    source.name(), source.displayName(), source.description(), source.fields(),
                    source.storageConfig(), source.apiConfig(), source.authzConfig(),
                    source.version(), source.createdAt(), source.updatedAt(),
                    true, source.tenantScoped(), source.readOnly(), source.immutableFields(),
                    source.columnMapping(), source.displayFieldName(), source.tenantId(),
                    source.trackHistory(), source.captureGeo());
        }
    }

    @Nested
    @DisplayName("JSON Storage Conversion Tests")
    class JsonStorageConversionTests {

        @Test
        @DisplayName("Should render JSON scalars as JSON text for the jsonb bind")
        void rendersJsonScalarsAsJsonText() {
            assertEquals("\"en\"", adapter.convertValueForStorage("en", FieldType.JSON));
            assertEquals("true", adapter.convertValueForStorage(true, FieldType.JSON));
            assertEquals("30", adapter.convertValueForStorage(30, FieldType.JSON));
        }

        @Test
        @DisplayName("Should render JSON objects and arrays as JSON text")
        void rendersJsonContainersAsJsonText() {
            assertEquals("{\"a\":1}", adapter.convertValueForStorage(Map.of("a", 1), FieldType.JSON));
            assertEquals("[1,2]", adapter.convertValueForStorage(List.of(1, 2), FieldType.JSON));
        }
    }

    @Nested
    @DisplayName("Table Initialization Tests")
    class InitializationTests {
        
        @Test
        @DisplayName("Should create table with correct columns")
        void shouldCreateTableWithCorrectColumns() {
            adapter.initializeCollection(testCollection);
            
            // Verify table exists by querying it
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_products", Integer.class);
            assertEquals(0, count);
        }
        
        @Test
        @DisplayName("Should be idempotent - calling twice should not fail")
        void shouldBeIdempotent() {
            adapter.initializeCollection(testCollection);
            assertDoesNotThrow(() -> adapter.initializeCollection(testCollection));
        }
        
        @Test
        @DisplayName("Should reconcile missing columns when table already exists with fewer columns")
        void shouldReconcileMissingColumns() {
            // Pre-create the table with only 'name' and 'sku' columns (simulating older schema)
            jdbcTemplate.execute(
                "CREATE TABLE test_products (id VARCHAR(36) PRIMARY KEY, " +
                "owner_id VARCHAR(36), " +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, " +
                "name TEXT NOT NULL, sku TEXT UNIQUE)");

            // Initialize with full definition (which includes description, price, quantity, active)
            // CREATE TABLE IF NOT EXISTS will be a no-op, but reconcileSchema should add missing columns
            adapter.initializeCollection(testCollection);

            // Verify we can insert data using all columns (including the newly added ones)
            Map<String, Object> data = new HashMap<>();
            data.put("id", "reconcile-1");
            data.put("name", "Reconciled Product");
            data.put("description", "Added by reconciliation");
            data.put("price", 29.99);
            data.put("quantity", 100);
            data.put("active", true);
            data.put("sku", "SKU-RECONCILE");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            Map<String, Object> created = adapter.create(testCollection, data);
            assertNotNull(created);
            assertEquals("Reconciled Product", created.get("name"));
        }

        @Test
        @DisplayName("Should create table with all field types")
        void shouldCreateTableWithAllFieldTypes() {
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("str_field", FieldType.STRING, true, false, false, null, null, null, null, null),
                new FieldDefinition("int_field", FieldType.INTEGER, true, false, false, null, null, null, null, null),
                new FieldDefinition("long_field", FieldType.LONG, true, false, false, null, null, null, null, null),
                new FieldDefinition("double_field", FieldType.DOUBLE, true, false, false, null, null, null, null, null),
                new FieldDefinition("bool_field", FieldType.BOOLEAN, true, false, false, null, null, null, null, null),
                new FieldDefinition("date_field", FieldType.DATE, true, false, false, null, null, null, null, null),
                new FieldDefinition("datetime_field", FieldType.DATETIME, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition allTypesCollection = new CollectionDefinition(
                "all_types",
                "All Types",
                "Collection with all field types",
                fields,
                StorageConfig.physicalTable("all_types"),
                null, null,
                1L, Instant.now(), Instant.now()
            );
            
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS all_types");
            } catch (Exception e) {
                // Ignore
            }
            
            assertDoesNotThrow(() -> adapter.initializeCollection(allTypesCollection));
        }
    }
    
    @Nested
    @DisplayName("CRUD Operations Tests")
    class CrudTests {
        
        @BeforeEach
        void initTable() {
            adapter.initializeCollection(testCollection);
        }
        
        @Test
        @DisplayName("Should create a record")
        void shouldCreateRecord() {
            Map<String, Object> data = createTestRecord("1", "Product A", 99.99, "SKU001");
            
            Map<String, Object> created = adapter.create(testCollection, data);
            
            assertNotNull(created);
            assertEquals("1", created.get("id"));
            assertEquals("Product A", created.get("name"));
        }
        
        @Test
        @DisplayName("Should get record by ID")
        void shouldGetRecordById() {
            Map<String, Object> data = createTestRecord("1", "Product A", 99.99, "SKU001");
            adapter.create(testCollection, data);
            
            Optional<Map<String, Object>> result = adapter.getById(testCollection, "1");
            
            assertTrue(result.isPresent());
            assertEquals("Product A", result.get().get("NAME"));
        }
        
        @Test
        @DisplayName("Should return empty for non-existent ID")
        void shouldReturnEmptyForNonExistentId() {
            Optional<Map<String, Object>> result = adapter.getById(testCollection, "non-existent");
            
            assertTrue(result.isEmpty());
        }
        
        @Test
        @DisplayName("Should update a record")
        void shouldUpdateRecord() {
            Map<String, Object> data = createTestRecord("1", "Product A", 99.99, "SKU001");
            adapter.create(testCollection, data);
            
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "Updated Product");
            updateData.put("price", 149.99);
            updateData.put("updatedAt", Instant.now());
            
            Optional<Map<String, Object>> updated = adapter.update(testCollection, "1", updateData);
            
            assertTrue(updated.isPresent());
            assertEquals("Updated Product", updated.get().get("NAME"));
        }
        
        @Test
        @DisplayName("Should return empty when updating non-existent record")
        void shouldReturnEmptyWhenUpdatingNonExistent() {
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "Updated Product");
            updateData.put("updatedAt", Instant.now());
            
            Optional<Map<String, Object>> result = adapter.update(testCollection, "non-existent", updateData);
            
            assertTrue(result.isEmpty());
        }
        
        @Test
        @DisplayName("Should delete a record")
        void shouldDeleteRecord() {
            Map<String, Object> data = createTestRecord("1", "Product A", 99.99, "SKU001");
            adapter.create(testCollection, data);
            
            boolean deleted = adapter.delete(testCollection, "1");
            
            assertTrue(deleted);
            assertTrue(adapter.getById(testCollection, "1").isEmpty());
        }
        
        @Test
        @DisplayName("Should return false when deleting non-existent record")
        void shouldReturnFalseWhenDeletingNonExistent() {
            boolean deleted = adapter.delete(testCollection, "non-existent");
            
            assertFalse(deleted);
        }
        
        private Map<String, Object> createTestRecord(String id, String name, double price, String sku) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("price", price);
            data.put("sku", sku);
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            return data;
        }
    }
    
    @Nested
    @DisplayName("Default Ordering Tests")
    class DefaultOrderingTests {

        @Test
        @DisplayName("Unsorted queries fall back to deterministic creation order")
        void unsortedQueriesUseCreationOrder() {
            adapter.initializeCollection(testCollection);

            // Insert out of creation-time order: ids chosen so neither insert order
            // nor id order matches created_at order.
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            insertAt("z-third", "Third", base.plusSeconds(30));
            insertAt("a-first", "First", base.plusSeconds(10));
            insertAt("m-second", "Second", base.plusSeconds(20));

            QueryRequest request = new QueryRequest(
                new Pagination(1, 10), List.of(), List.of(), List.of());
            QueryResult result = adapter.query(testCollection, request);

            List<String> names = result.data().stream()
                .map(r -> (String) r.get("name"))
                .toList();
            assertEquals(List.of("First", "Second", "Third"), names);
        }

        private void insertAt(String id, String name, Instant createdAt) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("price", 1.0);
            data.put("sku", "SKU-" + id);
            data.put("createdAt", createdAt);
            data.put("updatedAt", createdAt);
            adapter.create(testCollection, data);
        }
    }

    @Nested
    @DisplayName("Query Tests")
    class QueryTests {
        
        @BeforeEach
        void initTableAndData() {
            adapter.initializeCollection(testCollection);
            
            // Insert test data
            insertTestData("1", "Apple", 1.99, 100, true, "SKU001");
            insertTestData("2", "Banana", 0.99, 200, true, "SKU002");
            insertTestData("3", "Cherry", 3.99, 50, false, "SKU003");
            insertTestData("4", "Date", 5.99, 30, true, "SKU004");
            insertTestData("5", "Elderberry", 7.99, 10, false, "SKU005");
        }
        
        private void insertTestData(String id, String name, double price, int quantity, 
                boolean active, String sku) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("price", price);
            data.put("quantity", quantity);
            data.put("active", active);
            data.put("sku", sku);
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
        }
        
        @Test
        @DisplayName("Should query all records with default pagination")
        void shouldQueryAllRecordsWithDefaultPagination() {
            QueryRequest request = QueryRequest.defaults();
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(5, result.data().size());
            assertEquals(5, result.metadata().totalCount());
        }
        
        @Test
        @DisplayName("Should apply pagination correctly")
        void shouldApplyPaginationCorrectly() {
            QueryRequest request = new QueryRequest(
                new Pagination(1, 2),
                List.of(),
                List.of(),
                List.of()
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size());
            assertEquals(5, result.metadata().totalCount());
            assertEquals(3, result.metadata().totalPages());
        }
        
        @Test
        @DisplayName("Should apply sorting ascending")
        void shouldApplySortingAscending() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(SortField.asc("price")),
                List.of(),
                List.of()
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(5, result.data().size());
            // H2 returns column names in uppercase
            assertEquals("Banana", result.data().get(0).get("NAME")); // 0.99
            assertEquals("Apple", result.data().get(1).get("NAME"));  // 1.99
        }
        
        @Test
        @DisplayName("Should apply sorting descending")
        void shouldApplySortingDescending() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(SortField.desc("price")),
                List.of(),
                List.of()
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(5, result.data().size());
            assertEquals("Elderberry", result.data().get(0).get("NAME")); // 7.99
            assertEquals("Date", result.data().get(1).get("NAME"));       // 5.99
        }
    }
    
    @Nested
    @DisplayName("Filter Operator Tests")
    class FilterOperatorTests {
        
        @BeforeEach
        void initTableAndData() {
            adapter.initializeCollection(testCollection);
            
            // Insert test data
            insertTestData("1", "Apple", 1.99, 100, true, "SKU001");
            insertTestData("2", "Banana", 0.99, 200, true, "SKU002");
            insertTestData("3", "Cherry", 3.99, 50, false, "SKU003");
            insertTestData("4", "Date", 5.99, 30, true, "SKU004");
            insertTestData("5", "Elderberry", 7.99, 10, false, "SKU005");
        }
        
        private void insertTestData(String id, String name, double price, int quantity, 
                boolean active, String sku) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("price", price);
            data.put("quantity", quantity);
            data.put("active", active);
            data.put("sku", sku);
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
        }
        
        @Test
        @DisplayName("Should filter with EQ operator")
        void shouldFilterWithEqOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.eq("name", "Apple"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(1, result.data().size());
            assertEquals("Apple", result.data().get(0).get("NAME"));
        }
        
        @Test
        @DisplayName("Should filter with NEQ operator")
        void shouldFilterWithNeqOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.neq("name", "Apple"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(4, result.data().size());
        }
        
        @Test
        @DisplayName("Should filter with GT operator")
        void shouldFilterWithGtOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.gt("price", "5.00"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Date (5.99) and Elderberry (7.99)
        }
        
        @Test
        @DisplayName("Should filter with LT operator")
        void shouldFilterWithLtOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.lt("price", "2.00"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Apple (1.99) and Banana (0.99)
        }
        
        @Test
        @DisplayName("Should filter with GTE operator")
        void shouldFilterWithGteOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("price", FilterOperator.GTE, "5.99"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Date (5.99) and Elderberry (7.99)
        }
        
        @Test
        @DisplayName("Should filter with LTE operator")
        void shouldFilterWithLteOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("price", FilterOperator.LTE, "1.99"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Apple (1.99) and Banana (0.99)
        }
        
        @Test
        @DisplayName("Should filter with CONTAINS operator")
        void shouldFilterWithContainsOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.contains("name", "err"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Cherry and Elderberry
        }
        
        @Test
        @DisplayName("Should filter with STARTS operator")
        void shouldFilterWithStartsOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.STARTS, "A"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(1, result.data().size());
            assertEquals("Apple", result.data().get(0).get("NAME"));
        }
        
        @Test
        @DisplayName("Should filter with ENDS operator")
        void shouldFilterWithEndsOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.ENDS, "e"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Apple and Date
        }
        
        @Test
        @DisplayName("Should filter with ICONTAINS operator (case-insensitive)")
        void shouldFilterWithIcontainsOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.icontains("name", "APPLE"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(1, result.data().size());
            assertEquals("Apple", result.data().get(0).get("NAME"));
        }
        
        @Test
        @DisplayName("Should filter with ISTARTS operator (case-insensitive)")
        void shouldFilterWithIstartsOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.ISTARTS, "a"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(1, result.data().size());
            assertEquals("Apple", result.data().get(0).get("NAME"));
        }
        
        @Test
        @DisplayName("Should filter with IENDS operator (case-insensitive)")
        void shouldFilterWithIendsOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.IENDS, "E"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Apple and Date
        }
        
        @Test
        @DisplayName("Should filter with IEQ operator (case-insensitive equals)")
        void shouldFilterWithIeqOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.IEQ, "APPLE"))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(1, result.data().size());
            assertEquals("Apple", result.data().get(0).get("NAME"));
        }
        
        @Test
        @DisplayName("Should filter boolean field with string value 'false'")
        void shouldFilterBooleanFieldWithStringValueFalse() {
            // Simulates filter from URL: filter[active][eq]=false (value is String "false")
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.eq("active", "false"))
            );

            QueryResult result = adapter.query(testCollection, request);

            // Cherry (active=false) and Elderberry (active=false)
            assertEquals(2, result.data().size());
        }

        @Test
        @DisplayName("Should filter boolean field with string value 'true'")
        void shouldFilterBooleanFieldWithStringValueTrue() {
            // Simulates filter from URL: filter[active][eq]=true (value is String "true")
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.eq("active", "true"))
            );

            QueryResult result = adapter.query(testCollection, request);

            // Apple (active=true), Banana (active=true), Date (active=true)
            assertEquals(3, result.data().size());
        }

        @Test
        @DisplayName("Should filter numeric field with string value")
        void shouldFilterNumericFieldWithStringValue() {
            // Simulates filter from URL: filter[quantity][gt]=50 (value is String "50")
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("quantity", FilterOperator.GT, "50"))
            );

            QueryResult result = adapter.query(testCollection, request);

            // Apple (100) and Banana (200)
            assertEquals(2, result.data().size());
        }

        @Test
        @DisplayName("Should combine multiple filters with AND logic")
        void shouldCombineMultipleFiltersWithAndLogic() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(
                    new FilterCondition("price", FilterOperator.GT, "1.00"),
                    new FilterCondition("active", FilterOperator.EQ, true)
                )
            );
            
            QueryResult result = adapter.query(testCollection, request);

            // Apple (1.99, active), Date (5.99, active)
            assertEquals(2, result.data().size());
        }

        @Test
        @DisplayName("Should filter with IN operator returning all matches in one query")
        void shouldFilterWithInOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.IN,
                        List.of("Apple", "Cherry", "Elderberry")))
            );

            QueryResult result = adapter.query(testCollection, request);

            assertEquals(3, result.data().size());
            assertEquals(3L, result.metadata().totalCount());
        }

        @Test
        @DisplayName("IN with a single value matches one row")
        void inSingleValue() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.IN, List.of("Banana")))
            );

            QueryResult result = adapter.query(testCollection, request);
            assertEquals(1, result.data().size());
            assertEquals("Banana", result.data().get(0).get("NAME"));
        }

        @Test
        @DisplayName("IN with an empty list yields zero rows (1=0 short-circuit)")
        void inEmptyList() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("name", FilterOperator.IN, List.of()))
            );

            QueryResult result = adapter.query(testCollection, request);
            assertEquals(0, result.data().size());
            assertEquals(0L, result.metadata().totalCount());
        }

        @Test
        @DisplayName("IN on integer field coerces string elements to integers")
        void inCoercesNumericElements() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("quantity", FilterOperator.IN,
                        List.of("100", "30")))
            );

            QueryResult result = adapter.query(testCollection, request);
            // Apple (100) and Date (30)
            assertEquals(2, result.data().size());
        }
    }

    @Nested
    @DisplayName("Uniqueness Tests")
    class UniquenessTests {
        
        @BeforeEach
        void initTable() {
            adapter.initializeCollection(testCollection);
        }
        
        @Test
        @DisplayName("Should return true for unique value")
        void shouldReturnTrueForUniqueValue() {
            Map<String, Object> data = new HashMap<>();
            data.put("id", "1");
            data.put("name", "Product A");
            data.put("price", 99.99);
            data.put("sku", "SKU001");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
            
            boolean isUnique = adapter.isUnique(testCollection, "sku", "SKU002", null);
            
            assertTrue(isUnique);
        }
        
        @Test
        @DisplayName("Should return false for duplicate value")
        void shouldReturnFalseForDuplicateValue() {
            Map<String, Object> data = new HashMap<>();
            data.put("id", "1");
            data.put("name", "Product A");
            data.put("price", 99.99);
            data.put("sku", "SKU001");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
            
            boolean isUnique = adapter.isUnique(testCollection, "sku", "SKU001", null);
            
            assertFalse(isUnique);
        }
        
        @Test
        @DisplayName("Should exclude specified ID when checking uniqueness")
        void shouldExcludeSpecifiedIdWhenCheckingUniqueness() {
            Map<String, Object> data = new HashMap<>();
            data.put("id", "1");
            data.put("name", "Product A");
            data.put("price", 99.99);
            data.put("sku", "SKU001");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
            
            // Should be unique when excluding the record's own ID
            boolean isUnique = adapter.isUnique(testCollection, "sku", "SKU001", "1");
            
            assertTrue(isUnique);
        }
    }
    
    @Nested
    @DisplayName("ISNULL Filter Tests")
    class IsNullFilterTests {
        
        @BeforeEach
        void initTableAndData() {
            adapter.initializeCollection(testCollection);
            
            // Insert test data with some null descriptions
            insertTestDataWithNullDescription("1", "Apple", 1.99, null, "SKU001");
            insertTestDataWithNullDescription("2", "Banana", 0.99, "Yellow fruit", "SKU002");
            insertTestDataWithNullDescription("3", "Cherry", 3.99, null, "SKU003");
        }
        
        private void insertTestDataWithNullDescription(String id, String name, double price, 
                String description, String sku) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("price", price);
            data.put("description", description);
            data.put("sku", sku);
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
        }
        
        @Test
        @DisplayName("Should filter with ISNULL=true operator")
        void shouldFilterWithIsNullTrueOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.isNull("description", true))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(2, result.data().size()); // Apple and Cherry have null descriptions
        }
        
        @Test
        @DisplayName("Should filter with ISNULL=false operator (IS NOT NULL)")
        void shouldFilterWithIsNullFalseOperator() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(FilterCondition.isNull("description", false))
            );
            
            QueryResult result = adapter.query(testCollection, request);
            
            assertEquals(1, result.data().size()); // Only Banana has a description
            assertEquals("Banana", result.data().get(0).get("NAME"));
        }
    }

    @Nested
    @DisplayName("Sparse Fieldset with Computed Fields Tests")
    class SparseFieldsetComputedFieldsTests {

        private CollectionDefinition collectionWithComputedFields() {
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("code", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("taskCount", FieldType.ROLLUP_SUMMARY, true, false, false, null, null, null, null, null),
                new FieldDefinition("total", FieldType.FORMULA, true, false, false, null, null, null, null, null)
            );
            StorageConfig storageConfig = new StorageConfig("test_epics", Map.of());
            return new CollectionDefinition(
                "test_epics", "Test Epics", "A test collection with computed fields",
                fields, storageConfig, null, null, 1L, Instant.now(), Instant.now());
        }

        @BeforeEach
        void dropTable() {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS test_epics");
            } catch (Exception ignored) {
                // ignore
            }
        }

        @Test
        @DisplayName("a sparse fields[] naming a ROLLUP_SUMMARY field emits no column reference for it")
        void sparseFieldsetSkipsRollupSummaryColumn() {
            CollectionDefinition collection = collectionWithComputedFields();
            adapter.initializeCollection(collection);

            Map<String, Object> data = new HashMap<>();
            data.put("id", "epic-1");
            data.put("code", "EPIC-1");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(collection, data);

            QueryRequest request = new QueryRequest(
                Pagination.defaults(), List.of(), List.of("code", "taskCount"), List.of());

            QueryResult result = assertDoesNotThrow(() -> adapter.query(collection, request),
                "a sparse fieldset naming a ROLLUP_SUMMARY field must not 500 with a missing-column error");

            assertEquals(1, result.data().size());
            assertFalse(result.data().get(0).containsKey("taskCount"),
                "there is no physical column for a ROLLUP_SUMMARY field — post-retrieval "
                    + "computation fills it in, not the SELECT");
        }

        @Test
        @DisplayName("a sparse fields[] naming a FORMULA field emits no column reference for it")
        void sparseFieldsetSkipsFormulaColumn() {
            CollectionDefinition collection = collectionWithComputedFields();
            adapter.initializeCollection(collection);

            Map<String, Object> data = new HashMap<>();
            data.put("id", "epic-2");
            data.put("code", "EPIC-2");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(collection, data);

            QueryRequest request = new QueryRequest(
                Pagination.defaults(), List.of(), List.of("code", "total"), List.of());

            QueryResult result = assertDoesNotThrow(() -> adapter.query(collection, request),
                "a sparse fieldset naming a FORMULA field must not 500 with a missing-column error");

            assertEquals(1, result.data().size());
            assertFalse(result.data().get(0).containsKey("total"),
                "there is no physical column for a FORMULA field — post-retrieval computation "
                    + "fills it in, not the SELECT");
        }

        @Test
        @DisplayName("an undeclared field name in fields[] keeps today's resolveColumnName behavior (still attempted)")
        void undeclaredFieldNameStillAttemptsColumnLookup() {
            CollectionDefinition collection = collectionWithComputedFields();
            adapter.initializeCollection(collection);

            QueryRequest request = new QueryRequest(
                Pagination.defaults(), List.of(), List.of("code", "doesNotExist"), List.of());

            // Unlike a real computed field, an undeclared name has no FieldDefinition to consult,
            // so buildSelectClause falls through to resolveColumnName's snake_case guess exactly
            // as it did before this change — it is not silently skipped.
            StorageException ex = assertThrows(StorageException.class, () -> adapter.query(collection, request));
            assertNotNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("Aggregate Tests")
    class AggregateTests {

        @BeforeEach
        void initTableAndData() {
            adapter.initializeCollection(testCollection);
            insertRow("1", "Apple",      1.99, 100, true,  "SKU001");
            insertRow("2", "Banana",     0.99, 200, true,  "SKU002");
            insertRow("3", "Cherry",     3.99,  50, false, "SKU003");
            insertRow("4", "Date",       5.99,  30, true,  "SKU004");
            insertRow("5", "Elderberry", 7.99,  10, false, "SKU005");
        }

        private void insertRow(String id, String name, double price, int quantity,
                               boolean active, String sku) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("price", price);
            data.put("quantity", quantity);
            data.put("active", active);
            data.put("sku", sku);
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());
            adapter.create(testCollection, data);
        }

        @Test
        @DisplayName("SUM + COUNT with no filters covers all rows")
        void sumAndCountAll() {
            Map<String, Object> result = adapter.aggregate(
                testCollection,
                List.of(),
                List.of(
                    new AggregationSpec("SUM", "price", "total_price"),
                    new AggregationSpec("COUNT", null, "total_count")
                )
            );

            assertEquals(20.95, ((Number) result.get("total_price")).doubleValue(), 0.001);
            assertEquals(5L, result.get("total_count"));
        }

        @Test
        @DisplayName("Aggregations honour filter conditions")
        void aggregationsRespectFilters() {
            Map<String, Object> result = adapter.aggregate(
                testCollection,
                List.of(new FilterCondition("active", FilterOperator.EQ, true)),
                List.of(
                    new AggregationSpec("SUM", "quantity", "total_qty"),
                    new AggregationSpec("COUNT", null, "active_count")
                )
            );

            assertEquals(330.0, ((Number) result.get("total_qty")).doubleValue(), 0.001);
            assertEquals(3L, result.get("active_count"));
        }

        @Test
        @DisplayName("Empty match returns zero COUNT and null SUM normalised")
        void emptyMatchYieldsZeros() {
            Map<String, Object> result = adapter.aggregate(
                testCollection,
                List.of(new FilterCondition("name", FilterOperator.EQ, "no-such-fruit")),
                List.of(
                    new AggregationSpec("SUM", "price", "total"),
                    new AggregationSpec("COUNT", null, "count")
                )
            );

            assertEquals(0L, result.get("count"));
            assertNull(result.get("total"));
        }

        @Test
        @DisplayName("MIN, MAX, AVG produce expected scalars")
        void minMaxAvg() {
            Map<String, Object> result = adapter.aggregate(
                testCollection,
                List.of(),
                List.of(
                    new AggregationSpec("MIN", "price", "min_price"),
                    new AggregationSpec("MAX", "price", "max_price"),
                    new AggregationSpec("AVG", "price", "avg_price")
                )
            );

            assertEquals(0.99, ((Number) result.get("min_price")).doubleValue(), 0.001);
            assertEquals(7.99, ((Number) result.get("max_price")).doubleValue(), 0.001);
            assertEquals(4.19, ((Number) result.get("avg_price")).doubleValue(), 0.001);
        }
    }

    @Nested
    @DisplayName("Kebab-case collection names")
    class KebabCaseCollectionNames {

        @Test
        @DisplayName("identifierPart maps hyphens to underscores and stays strict otherwise")
        void identifierPartMapsHyphens() {
            assertEquals("program_translations",
                    PhysicalTableStorageAdapter.identifierPart("program-translations"));
            assertEquals("plain", PhysicalTableStorageAdapter.identifierPart("plain"));
            assertThrows(IllegalArgumentException.class,
                    () -> PhysicalTableStorageAdapter.identifierPart("bad;name"));
            assertThrows(IllegalArgumentException.class,
                    () -> PhysicalTableStorageAdapter.identifierPart("  "));
        }

        @Test
        @DisplayName("initializeCollection handles a kebab-case tenant collection with a generated index name")
        void kebabCollectionInitializes() {
            // Regression: generated index/constraint NAMES used to feed the raw
            // kebab-case collection name into sanitizeIdentifier and threw
            // "Invalid identifier: program-translations" on every NATS-triggered
            // refresh. The EXTERNAL_ID field forces an idx_ name through
            // identifierPart; the FK path shares the same helper (its DO $$
            // post-create block is Postgres-only, so it is not executed on H2).
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("externalRef", FieldType.EXTERNAL_ID, true, false, false, null, null, null, null, null)
            );
            CollectionDefinition kebab = new CollectionDefinition(
                "program-translations",
                "Program Translations",
                "kebab-case regression collection",
                fields,
                new StorageConfig("program-translations", Map.of()),
                null,
                null,
                1L,
                Instant.now(),
                Instant.now()
            );

            io.kelta.runtime.context.TenantContext.runWithTenant("tenant-1", "kebab-tenant", () -> {
                assertDoesNotThrow(() -> adapter.initializeCollection(kebab));
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM \"kebab-tenant\".\"program-translations\"", Integer.class);
                assertEquals(0, count);
            });
        }
    }

    @Nested
    @DisplayName("Unique-violation column extraction")
    class UniqueViolationColumnExtraction {

        private org.springframework.dao.DuplicateKeyException pgStyle(String detail) {
            // The Postgres driver surfaces the detail inside the nested SQLException
            // message; Spring wraps it in DuplicateKeyException.
            return new org.springframework.dao.DuplicateKeyException("duplicate key",
                new java.sql.SQLException("ERROR: duplicate key value violates unique constraint "
                    + "\"uniq_incentive_programs_country_slug\"\n  Detail: " + detail));
        }

        @Test
        @DisplayName("parses a composite key detail into its column list")
        void parsesCompositeColumns() {
            List<String> columns = PhysicalTableStorageAdapter.extractViolatedColumns(
                pgStyle("Key (country, slug)=(25c26833, erasmus-plus) already exists."));
            assertEquals(List.of("country", "slug"), columns);
        }

        @Test
        @DisplayName("parses a single-column key detail")
        void parsesSingleColumn() {
            List<String> columns = PhysicalTableStorageAdapter.extractViolatedColumns(
                pgStyle("Key (slug)=(erasmus-plus) already exists."));
            assertEquals(List.of("slug"), columns);
        }

        @Test
        @DisplayName("unquotes quoted column identifiers")
        void unquotesQuotedColumns() {
            List<String> columns = PhysicalTableStorageAdapter.extractViolatedColumns(
                pgStyle("Key (\"userId\", \"tenantId\")=(u1, t1) already exists."));
            assertEquals(List.of("userId", "tenantId"), columns);
        }

        @Test
        @DisplayName("returns empty for messages without a Key detail (H2 format)")
        void emptyWithoutDetail() {
            org.springframework.dao.DuplicateKeyException h2Style =
                new org.springframework.dao.DuplicateKeyException("duplicate key",
                    new java.sql.SQLException("Unique index or primary key violation: "
                        + "\"PUBLIC.CONSTRAINT_INDEX_4 ON PUBLIC.T(SLUG) VALUES ('x')\""));
            assertTrue(PhysicalTableStorageAdapter.extractViolatedColumns(h2Style).isEmpty());
        }
    }

    @Nested
    @DisplayName("Delete blocked by a restricting foreign key (SQL state 23503)")
    class DeleteForeignKeyConflict {

        private PhysicalTableStorageAdapter adapterWithFailingJdbc(RuntimeException toThrow) {
            JdbcTemplate failing = org.mockito.Mockito.mock(JdbcTemplate.class);
            org.mockito.Mockito.when(failing.update(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.<Object>any())).thenThrow(toThrow);
            return new PhysicalTableStorageAdapter(failing, migrationEngine,
                    new tools.jackson.databind.ObjectMapper());
        }

        @Test
        @DisplayName("maps a Postgres 23503 to ReferencedRecordConflictException (409), not a 500 StorageException")
        void fkViolationBecomesConflict() {
            // Regression: DELETE /api/fields/{id} 500'd when layout_field rows still
            // referenced the field — the 23503 was swallowed into StorageException.
            RuntimeException fk = new org.springframework.dao.DataIntegrityViolationException(
                    "delete violates foreign key",
                    new java.sql.SQLException(
                            "update or delete on table \"field\" violates foreign key constraint "
                            + "\"layout_field_field_id_fkey\" on table \"layout_field\"", "23503"));

            ReferencedRecordConflictException ex = assertThrows(
                    ReferencedRecordConflictException.class,
                    () -> adapterWithFailingJdbc(fk).delete(testCollection, "rec-1"));
            assertEquals("test_products", ex.getCollectionName());
            assertEquals("rec-1", ex.getRecordId());
        }

        @Test
        @DisplayName("other data-access failures still surface as StorageException")
        void nonFkFailuresStayStorageException() {
            RuntimeException down = new org.springframework.dao.DataAccessResourceFailureException(
                    "connection refused");
            StorageException ex = assertThrows(StorageException.class,
                    () -> adapterWithFailingJdbc(down).delete(testCollection, "rec-1"));
            assertFalse(ex instanceof ReferencedRecordConflictException);
        }

        @Test
        @DisplayName("detection walks the cause chain and matches only SQL state 23503")
        void detectionMatrix() {
            assertTrue(PhysicalTableStorageAdapter.isForeignKeyViolation(
                    new RuntimeException(new RuntimeException(
                            new java.sql.SQLException("fk", "23503")))));
            assertFalse(PhysicalTableStorageAdapter.isForeignKeyViolation(
                    new java.sql.SQLException("unique", "23505")));
            assertFalse(PhysicalTableStorageAdapter.isForeignKeyViolation(
                    new RuntimeException("no sql cause")));
        }
    }

    @Nested
    @DisplayName("Temporal filter binding (DATE / DATETIME / audit timestamps)")
    class TemporalFilterTests {

        private CollectionDefinition events;

        @BeforeEach
        void initTableAndData() {
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                FieldDefinition.datetime("dueAt"),
                FieldDefinition.date("dueOn")
            );
            events = new CollectionDefinition("test_events", "Test Events", "temporal filters", fields,
                new StorageConfig("test_events", Map.of()), null, null, 1L, Instant.now(), Instant.now());
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS test_events");
            } catch (Exception e) {
                // Ignore
            }
            adapter.initializeCollection(events);
            insert("1", "January", "2026-01-01T10:00:00Z", "2026-01-01", "2026-01-01T00:00:00Z");
            insert("2", "February", "2026-02-01T10:00:00Z", "2026-02-01", "2026-02-01T00:00:00Z");
            insert("3", "March", "2026-03-01T10:00:00Z", "2026-03-01", "2026-03-01T00:00:00Z");
        }

        private void insert(String id, String name, String dueAt, String dueOn, String createdAt) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("name", name);
            data.put("dueAt", Instant.parse(dueAt));
            data.put("dueOn", java.time.LocalDate.parse(dueOn));
            data.put("createdAt", Instant.parse(createdAt));
            data.put("updatedAt", Instant.parse(createdAt));
            adapter.create(events, data);
        }

        private List<String> names(FilterCondition... filters) {
            QueryRequest request = new QueryRequest(Pagination.defaults(), List.of(), List.of(), List.of(filters));
            return adapter.query(events, request).data().stream().map(r -> (String) r.get("name")).sorted().toList();
        }

        @Test
        @DisplayName("createdAt (no FieldDefinition) filters with an ISO-8601 string — the dashboard time range")
        void shouldFilterAuditTimestampFromIsoString() {
            assertEquals(List.of("February", "March"),
                names(new FilterCondition("createdAt", FilterOperator.GTE, "2026-02-01T00:00:00Z")));
            assertEquals(List.of("January"),
                names(new FilterCondition("updatedAt", FilterOperator.LT, "2026-02-01T00:00:00Z")));
        }

        @Test
        @DisplayName("a DATETIME field filters with every comparison operator")
        void shouldFilterDatetimeField() {
            assertEquals(List.of("February"), names(new FilterCondition("dueAt", FilterOperator.EQ, "2026-02-01T10:00:00Z")));
            assertEquals(List.of("January", "March"), names(new FilterCondition("dueAt", FilterOperator.NEQ, "2026-02-01T10:00:00Z")));
            assertEquals(List.of("March"), names(new FilterCondition("dueAt", FilterOperator.GT, "2026-02-01T10:00:00Z")));
            assertEquals(List.of("February", "January"), names(new FilterCondition("dueAt", FilterOperator.LTE, "2026-02-01T10:00:00Z")));
            assertEquals(List.of("January", "March"), names(new FilterCondition("dueAt", FilterOperator.IN,
                List.of("2026-01-01T10:00:00Z", "2026-03-01T10:00:00Z"))));
        }

        @Test
        @DisplayName("a DATE field filters with yyyy-MM-dd values, scalar and IN")
        void shouldFilterDateField() {
            assertEquals(List.of("February"), names(new FilterCondition("dueOn", FilterOperator.EQ, "2026-02-01")));
            assertEquals(List.of("February", "March"), names(new FilterCondition("dueOn", FilterOperator.GTE, "2026-02-01")));
            assertEquals(List.of("January", "March"), names(new FilterCondition("dueOn", FilterOperator.IN,
                List.of("2026-01-01", "2026-03-01"))));
        }

        @Test
        @DisplayName("an unparseable temporal value is a 400-class InvalidFilterException, not a database error")
        void shouldRejectUnparseableTemporalValue() {
            io.kelta.runtime.query.InvalidFilterException ex = assertThrows(io.kelta.runtime.query.InvalidFilterException.class,
                () -> names(new FilterCondition("createdAt", FilterOperator.GTE, "yesterday")));
            assertTrue(ex.getMessage().contains("createdAt"), ex.getMessage());
            assertThrows(io.kelta.runtime.query.InvalidFilterException.class,
                () -> names(new FilterCondition("dueOn", FilterOperator.EQ, "next week")));
        }

        @Test
        @DisplayName("ISNULL on a temporal column is untouched")
        void shouldLeaveIsNullAlone() {
            assertEquals(List.of(), names(new FilterCondition("dueAt", FilterOperator.ISNULL, true)));
            assertEquals(List.of("February", "January", "March"),
                names(new FilterCondition("dueAt", FilterOperator.ISNULL, false)));
        }

        @Test
        @DisplayName("the write-path converter is what binds: strings become java.sql types")
        void shouldConvertTemporalValuesLikeTheWritePath() {
            assertInstanceOf(java.sql.Timestamp.class, adapter.convertValueForStorage("2026-02-01T00:00:00Z", FieldType.DATETIME));
            assertInstanceOf(java.sql.Date.class, adapter.convertValueForStorage("2026-02-01", FieldType.DATE));
            assertInstanceOf(java.sql.Timestamp.class, adapter.convertValueForStorage(Instant.parse("2026-02-01T00:00:00Z"), FieldType.DATETIME));
        }
    }

}
