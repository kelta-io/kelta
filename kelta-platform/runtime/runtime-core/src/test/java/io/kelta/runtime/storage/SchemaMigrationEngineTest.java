package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.storage.SchemaMigrationEngine.MigrationRecord;
import io.kelta.runtime.storage.SchemaMigrationEngine.MigrationType;
import io.kelta.runtime.storage.SchemaMigrationEngine.SchemaDiff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for SchemaMigrationEngine.
 * 
 * Tests migration history tracking, schema diff detection, ALTER TABLE generation,
 * column deprecation, and type change validation.
 * 
 * Validates Requirements: 10.1, 10.2, 10.3, 10.4, 10.5
 */
class SchemaMigrationEngineTest {
    
    private JdbcTemplate jdbcTemplate;
    private SchemaMigrationEngine migrationEngine;
    
    @BeforeEach
    void setUp() {
        // Create embedded H2 database
        // DATABASE_TO_LOWER makes H2 fold unquoted identifiers to lower case the way
        // PostgreSQL does. Without it, DDL that quotes a lower-case column name (needed so
        // reserved words like `user` parse) would not match the upper-cased column H2
        // creates for the same name unquoted — a divergence from production, not a real bug.
        DataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:h2:mem:migengine;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        
        jdbcTemplate = new JdbcTemplate(dataSource);
        
        // Drop migration table if exists (for clean test runs)
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS kelta_migrations");
        } catch (Exception e) {
            // Ignore
        }
        
        migrationEngine = new SchemaMigrationEngine(jdbcTemplate);
    }
    
    private CollectionDefinition createCollection(String name, List<FieldDefinition> fields) {
        StorageConfig storageConfig = new StorageConfig(
            name,
            Map.of()
        );
        
        return new CollectionDefinition(
            name,
            name,
            "Test collection",
            fields,
            storageConfig,
            null,
            null,
            1L,
            Instant.now(),
            Instant.now()
        );
    }
    
    private void createTestTable(String tableName, List<FieldDefinition> fields) {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(tableName).append(" (");
        sql.append("id VARCHAR(36) PRIMARY KEY, ");
        sql.append("created_at TIMESTAMP NOT NULL, ");
        sql.append("updated_at TIMESTAMP NOT NULL");
        
        for (FieldDefinition field : fields) {
            sql.append(", ");
            sql.append(field.name()).append(" ");
            sql.append(mapFieldTypeToSql(field.type()));
        }
        
        sql.append(")");
        jdbcTemplate.execute(sql.toString());
    }
    
    private String mapFieldTypeToSql(FieldType type) {
        return switch (type) {
            case STRING, PHONE, EMAIL, URL, TEXT, RICH_TEXT, EXTERNAL_ID, PICKLIST, AUTO_NUMBER -> "TEXT";
            case INTEGER -> "INTEGER";
            case LONG -> "BIGINT";
            case DOUBLE, CURRENCY, PERCENT -> "DOUBLE PRECISION";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case DATETIME -> "TIMESTAMP";
            case JSON, ARRAY, MULTI_PICKLIST, GEOLOCATION -> "CLOB"; // H2 doesn't have JSONB, use CLOB
            case REFERENCE, LOOKUP, MASTER_DETAIL -> "VARCHAR(36)";
            case ENCRYPTED -> "BINARY(256)";
            // H2 has no pgvector; tests that exercise VECTOR DDL run against Postgres
            // (PhysicalTableStorageAdapter's own SQL emission test). Here we stand in
            // with a generic blob column so the helper stays total.
            case VECTOR -> "BINARY(4096)";
            case FORMULA, ROLLUP_SUMMARY -> null;
        };
    }
    
    @Nested
    @DisplayName("Migration History Table Tests")
    class MigrationHistoryTests {
        
        @Test
        @DisplayName("Should create migration history table on initialization")
        void shouldCreateMigrationHistoryTable() {
            // The table should be created in setUp via constructor
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kelta_migrations", Integer.class);
            assertEquals(0, count);
        }
        
        @Test
        @DisplayName("Should record migration with all required fields")
        void shouldRecordMigrationWithAllFields() {
            String sql = "CREATE TABLE test_table (id VARCHAR(36) PRIMARY KEY)";
            
            migrationEngine.recordMigration("test_dataection", MigrationType.CREATE_TABLE, sql);
            
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_dataection");
            
            assertEquals(1, history.size());
            MigrationRecord record = history.get(0);
            assertEquals("test_dataection", record.collectionName());
            assertEquals(MigrationType.CREATE_TABLE, record.migrationType());
            assertEquals(sql, record.sqlStatement());
            assertNotNull(record.executedAt());
            assertTrue(record.id() > 0);
        }
        
        @Test
        @DisplayName("Should record multiple migrations in order")
        void shouldRecordMultipleMigrationsInOrder() {
            migrationEngine.recordMigration("test_data", MigrationType.CREATE_TABLE, "CREATE TABLE...");
            migrationEngine.recordMigration("test_data", MigrationType.ADD_COLUMN, "ALTER TABLE ADD...");
            migrationEngine.recordMigration("test_data", MigrationType.DEPRECATE_COLUMN, "COMMENT ON...");

            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            
            assertEquals(3, history.size());
            assertEquals(MigrationType.CREATE_TABLE, history.get(0).migrationType());
            assertEquals(MigrationType.ADD_COLUMN, history.get(1).migrationType());
            assertEquals(MigrationType.DEPRECATE_COLUMN, history.get(2).migrationType());
        }
        
        @Test
        @DisplayName("Should filter migration history by collection name")
        void shouldFilterMigrationHistoryByCollectionName() {
            migrationEngine.recordMigration("collection_a", MigrationType.CREATE_TABLE, "SQL A");
            migrationEngine.recordMigration("collection_b", MigrationType.CREATE_TABLE, "SQL B");
            migrationEngine.recordMigration("collection_a", MigrationType.ADD_COLUMN, "SQL A2");
            
            List<MigrationRecord> historyA = migrationEngine.getMigrationHistory("collection_a");
            List<MigrationRecord> historyB = migrationEngine.getMigrationHistory("collection_b");
            
            assertEquals(2, historyA.size());
            assertEquals(1, historyB.size());
        }
        
        @Test
        @DisplayName("Should get all migration history")
        void shouldGetAllMigrationHistory() {
            migrationEngine.recordMigration("collection_a", MigrationType.CREATE_TABLE, "SQL A");
            migrationEngine.recordMigration("collection_b", MigrationType.CREATE_TABLE, "SQL B");
            
            List<MigrationRecord> allHistory = migrationEngine.getAllMigrationHistory();
            
            assertEquals(2, allHistory.size());
        }
    }
    
    @Nested
    @DisplayName("Schema Diff Detection Tests")
    class SchemaDiffTests {
        
        @Test
        @DisplayName("Should detect added fields")
        void shouldDetectAddedFields() {
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            List<SchemaDiff> diffs = migrationEngine.detectDifferences(oldDef, newDef);
            
            assertEquals(1, diffs.size());
            assertEquals(SchemaDiff.DiffType.FIELD_ADDED, diffs.get(0).diffType());
            assertEquals("description", diffs.get(0).fieldName());
            assertNull(diffs.get(0).oldType());
            assertEquals(FieldType.STRING, diffs.get(0).newType());
        }
        
        @Test
        @DisplayName("Should detect removed fields")
        void shouldDetectRemovedFields() {
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            List<SchemaDiff> diffs = migrationEngine.detectDifferences(oldDef, newDef);
            
            assertEquals(1, diffs.size());
            assertEquals(SchemaDiff.DiffType.FIELD_REMOVED, diffs.get(0).diffType());
            assertEquals("description", diffs.get(0).fieldName());
            assertEquals(FieldType.STRING, diffs.get(0).oldType());
            assertNull(diffs.get(0).newType());
        }
        
        @Test
        @DisplayName("Should detect type changes")
        void shouldDetectTypeChanges() {
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("count", FieldType.INTEGER, false, false, false, null, null, null, null, null)
            );
            
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("count", FieldType.LONG, false, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            List<SchemaDiff> diffs = migrationEngine.detectDifferences(oldDef, newDef);
            
            assertEquals(1, diffs.size());
            assertEquals(SchemaDiff.DiffType.TYPE_CHANGED, diffs.get(0).diffType());
            assertEquals("count", diffs.get(0).fieldName());
            assertEquals(FieldType.INTEGER, diffs.get(0).oldType());
            assertEquals(FieldType.LONG, diffs.get(0).newType());
        }
        
        @Test
        @DisplayName("Should detect multiple changes")
        void shouldDetectMultipleChanges() {
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("count", FieldType.INTEGER, false, false, false, null, null, null, null, null),
                new FieldDefinition("old_field", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("count", FieldType.LONG, false, false, false, null, null, null, null, null),
                new FieldDefinition("new_field", FieldType.BOOLEAN, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            List<SchemaDiff> diffs = migrationEngine.detectDifferences(oldDef, newDef);
            
            assertEquals(3, diffs.size());
            
            // Verify we have one of each type
            assertTrue(diffs.stream().anyMatch(d -> d.diffType() == SchemaDiff.DiffType.FIELD_ADDED));
            assertTrue(diffs.stream().anyMatch(d -> d.diffType() == SchemaDiff.DiffType.FIELD_REMOVED));
            assertTrue(diffs.stream().anyMatch(d -> d.diffType() == SchemaDiff.DiffType.TYPE_CHANGED));
        }
        
        @Test
        @DisplayName("Should return empty list when no changes")
        void shouldReturnEmptyListWhenNoChanges() {
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", fields);
            CollectionDefinition newDef = createCollection("test_data", fields);
            
            List<SchemaDiff> diffs = migrationEngine.detectDifferences(oldDef, newDef);
            
            assertTrue(diffs.isEmpty());
        }
    }
    
    @Nested
    @DisplayName("Type Change Compatibility Tests")
    class TypeChangeCompatibilityTests {
        
        @Test
        @DisplayName("STRING can change to any type")
        void stringCanChangeToAnyType() {
            for (FieldType targetType : FieldType.values()) {
                assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.STRING, targetType),
                    "STRING should be compatible with " + targetType);
            }
        }
        
        @Test
        @DisplayName("INTEGER can change to LONG, DOUBLE, STRING")
        void integerCanChangeToLongDoubleString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.INTEGER));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.LONG));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.DOUBLE));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.BOOLEAN));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.DATE));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.DATETIME));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.INTEGER, FieldType.JSON));
        }
        
        @Test
        @DisplayName("LONG can change to DOUBLE, STRING")
        void longCanChangeToDoubleString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.LONG, FieldType.LONG));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.LONG, FieldType.DOUBLE));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.LONG, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.LONG, FieldType.INTEGER));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.LONG, FieldType.BOOLEAN));
        }
        
        @Test
        @DisplayName("DOUBLE can change to STRING")
        void doubleCanChangeToString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DOUBLE, FieldType.DOUBLE));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DOUBLE, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.DOUBLE, FieldType.INTEGER));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.DOUBLE, FieldType.LONG));
        }
        
        @Test
        @DisplayName("BOOLEAN can change to STRING")
        void booleanCanChangeToString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.BOOLEAN, FieldType.BOOLEAN));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.BOOLEAN, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.BOOLEAN, FieldType.INTEGER));
        }
        
        @Test
        @DisplayName("DATE can change to DATETIME, STRING")
        void dateCanChangeToDatetimeString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DATE, FieldType.DATE));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DATE, FieldType.DATETIME));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DATE, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.DATE, FieldType.INTEGER));
        }
        
        @Test
        @DisplayName("DATETIME can change to STRING")
        void datetimeCanChangeToString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DATETIME, FieldType.DATETIME));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.DATETIME, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.DATETIME, FieldType.DATE));
        }
        
        @Test
        @DisplayName("JSON can change to STRING")
        void jsonCanChangeToString() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.JSON, FieldType.JSON));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.JSON, FieldType.STRING));
            
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.JSON, FieldType.INTEGER));
        }
        
        @Test
        @DisplayName("Should throw IncompatibleSchemaChangeException for incompatible changes")
        void shouldThrowExceptionForIncompatibleChanges() {
            FieldDefinition oldField = new FieldDefinition("count", FieldType.BOOLEAN,
                false, false, false, null, null, null, null, null);
            FieldDefinition newField = new FieldDefinition("count", FieldType.INTEGER,
                false, false, false, null, null, null, null, null);
            
            IncompatibleSchemaChangeException exception = assertThrows(
                IncompatibleSchemaChangeException.class,
                () -> migrationEngine.validateTypeChange("test_dataection", oldField, newField)
            );
            
            assertEquals("test_dataection", exception.getCollectionName());
            assertEquals("count", exception.getFieldName());
            assertEquals(FieldType.BOOLEAN, exception.getOldType());
            assertEquals(FieldType.INTEGER, exception.getNewType());
        }
        
        @Test
        @DisplayName("Should not throw for compatible type changes")
        void shouldNotThrowForCompatibleChanges() {
            FieldDefinition oldField = new FieldDefinition("count", FieldType.INTEGER,
                false, false, false, null, null, null, null, null);
            FieldDefinition newField = new FieldDefinition("count", FieldType.LONG,
                false, false, false, null, null, null, null, null);

            assertDoesNotThrow(() ->
                migrationEngine.validateTypeChange("test_dataection", oldField, newField));
        }

        @Test
        @DisplayName("TEXT and RICH_TEXT are bidirectionally compatible (shared TEXT column)")
        void textAndRichTextAreCompatible() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.TEXT, FieldType.RICH_TEXT));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.RICH_TEXT, FieldType.TEXT));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.TEXT, FieldType.STRING));
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.RICH_TEXT, FieldType.STRING));
        }

        @Test
        @DisplayName("VECTOR is only compatible with itself")
        void vectorIsOnlyCompatibleWithItself() {
            assertTrue(migrationEngine.isTypeChangeCompatible(FieldType.VECTOR, FieldType.VECTOR));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.VECTOR, FieldType.STRING));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.VECTOR, FieldType.JSON));
            assertFalse(migrationEngine.isTypeChangeCompatible(FieldType.VECTOR, FieldType.ARRAY));
        }
    }

    @Nested
    @DisplayName("SQL Type Mapping Tests")
    class SqlTypeMappingTests {

        @Test
        @DisplayName("FORMULA with returnType=TEXT maps to TEXT column")
        void formulaTextMapsToTextColumn() {
            FieldDefinition field = new FieldDefinition("label", FieldType.FORMULA,
                    true, false, false, null, null, null, null, Map.of("returnType", "TEXT", "expression", "name"));
            assertEquals("TEXT", migrationEngine.mapFieldTypeToSql(FieldType.FORMULA, field));
        }

        @Test
        @DisplayName("FORMULA with returnType=NUMBER maps to NUMERIC column")
        void formulaNumberMapsToNumericColumn() {
            FieldDefinition field = new FieldDefinition("total", FieldType.FORMULA,
                    true, false, false, null, null, null, null, Map.of("returnType", "NUMBER", "expression", "price * qty"));
            assertEquals("NUMERIC", migrationEngine.mapFieldTypeToSql(FieldType.FORMULA, field));
        }

        @Test
        @DisplayName("FORMULA with returnType=BOOLEAN maps to BOOLEAN column")
        void formulaBooleanMapsToBooleanColumn() {
            FieldDefinition field = new FieldDefinition("isLarge", FieldType.FORMULA,
                    true, false, false, null, null, null, null, Map.of("returnType", "BOOLEAN", "expression", "amount > 100"));
            assertEquals("BOOLEAN", migrationEngine.mapFieldTypeToSql(FieldType.FORMULA, field));
        }

        @Test
        @DisplayName("TEXT and RICH_TEXT both emit the Postgres TEXT column type")
        void textAndRichTextEmitText() {
            FieldDefinition text = new FieldDefinition("synopsis", FieldType.TEXT,
                    true, false, false, null, null, null, null, null);
            FieldDefinition richText = new FieldDefinition("description", FieldType.RICH_TEXT,
                    true, false, false, null, null, null, null, null);

            assertEquals("TEXT", migrationEngine.mapFieldTypeToSql(FieldType.TEXT, text));
            assertEquals("TEXT", migrationEngine.mapFieldTypeToSql(FieldType.RICH_TEXT, richText));
        }

        @Test
        @DisplayName("VECTOR emits vector(N) using the dimension from fieldTypeConfig")
        void vectorEmitsConfiguredDimension() {
            FieldDefinition vector = new FieldDefinition("embedding", FieldType.VECTOR,
                    true, false, false, null, null, null, null,
                    Map.of("dimension", 768), null);

            assertEquals("vector(768)",
                    migrationEngine.mapFieldTypeToSql(FieldType.VECTOR, vector));
        }

        @Test
        @DisplayName("VECTOR defaults to dimension 1536 when fieldTypeConfig is empty")
        void vectorDefaultsTo1536() {
            FieldDefinition vector = new FieldDefinition("embedding", FieldType.VECTOR,
                    true, false, false, null, null, null, null, null, null);

            assertEquals("vector(1536)",
                    migrationEngine.mapFieldTypeToSql(FieldType.VECTOR, vector));
        }

        @Test
        @DisplayName("VECTOR rejects dimension out of pgvector's 1..16000 range")
        void vectorRejectsOutOfRangeDimension() {
            FieldDefinition tooBig = new FieldDefinition("embedding", FieldType.VECTOR,
                    true, false, false, null, null, null, null,
                    Map.of("dimension", 20000), null);

            assertThrows(IllegalArgumentException.class,
                    () -> migrationEngine.mapFieldTypeToSql(FieldType.VECTOR, tooBig));
        }

        @Test
        @DisplayName("VECTOR accepts a stringified numeric dimension (JSON deserialization edge case)")
        void vectorAcceptsStringDimension() {
            FieldDefinition vector = new FieldDefinition("embedding", FieldType.VECTOR,
                    true, false, false, null, null, null, null,
                    Map.of("dimension", "1024"), null);

            assertEquals("vector(1024)",
                    migrationEngine.mapFieldTypeToSql(FieldType.VECTOR, vector));
        }
    }
    
    @Nested
    @DisplayName("Schema Migration Execution Tests")
    class SchemaMigrationExecutionTests {
        
        @BeforeEach
        void cleanupTestTable() {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS test_data");
            } catch (Exception e) {
                // Ignore
            }
        }
        
        private void setupTestTable(String tableName, List<FieldDefinition> fields) {
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
            sql.append(tableName).append(" (");
            sql.append("id VARCHAR(36) PRIMARY KEY, ");
            sql.append("created_at TIMESTAMP NOT NULL, ");
            sql.append("updated_at TIMESTAMP NOT NULL");
            
            for (FieldDefinition field : fields) {
                sql.append(", ");
                sql.append(field.name()).append(" ");
                sql.append(mapFieldTypeToSql(field.type()));
            }
            
            sql.append(")");
            jdbcTemplate.execute(sql.toString());
        }
        
        @Test
        @DisplayName("Should add column when field is added")
        void shouldAddColumnWhenFieldIsAdded() {
            // Create initial table
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", oldFields);
            
            // Define new schema with additional field
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            // Execute migration
            migrationEngine.migrateSchema(oldDef, newDef);
            
            // Verify column was added by inserting data
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, name, description) VALUES (?, ?, ?, ?, ?)",
                "1", Instant.now(), Instant.now(), "Test", "Test Description"
            );
            
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_data WHERE description = 'Test Description'", Integer.class);
            assertEquals(1, count);
            
            // Verify migration was recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.ADD_COLUMN));
        }
        
        @Test
        @DisplayName("Should be idempotent when column already exists (concurrent reconcile race)")
        void shouldBeIdempotentWhenColumnAlreadyExists() {
            // Scenario: two worker pods both receive the same collection-changed event
            // (broadcast subscription). Pod A has already run the ALTER TABLE ADD COLUMN.
            // Pod B's definition diff still thinks the column is missing, so it re-issues
            // the same statement. With IF NOT EXISTS this must not throw.

            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", oldFields);

            // Pod A already added the column
            jdbcTemplate.execute("ALTER TABLE test_data ADD COLUMN description TEXT");

            // Pod B computes the diff against the old definition and tries to add it again
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);

            // Must not throw — ADD COLUMN IF NOT EXISTS keeps this idempotent
            assertDoesNotThrow(() -> migrationEngine.migrateSchema(oldDef, newDef));

            // Column should still be usable
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, name, description) VALUES (?, ?, ?, ?, ?)",
                "1", Instant.now(), Instant.now(), "Test", "Test Description"
            );
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_data WHERE description = 'Test Description'", Integer.class);
            assertEquals(1, count);
        }

        @Test
        @DisplayName("Should deprecate column when field is removed")
        void shouldDeprecateColumnWhenFieldIsRemoved() {
            // Create initial table with two fields
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("old_field", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", oldFields);
            
            // Define new schema without old_field
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            // Execute migration
            migrationEngine.migrateSchema(oldDef, newDef);
            
            // Verify column still exists (not dropped)
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, name, old_field) VALUES (?, ?, ?, ?, ?)",
                "1", Instant.now(), Instant.now(), "Test", "Old Value"
            );
            
            // Verify migration was recorded as DEPRECATE_COLUMN
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.DEPRECATE_COLUMN));
        }
        
        @Test
        @DisplayName("Should alter column type for compatible changes")
        void shouldAlterColumnTypeForCompatibleChanges() {
            // Create initial table with INTEGER field
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("count", FieldType.INTEGER, true, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", oldFields);
            
            // Insert some data
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, count) VALUES (?, ?, ?, ?)",
                "1", Instant.now(), Instant.now(), 100
            );
            
            // Define new schema with LONG type
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("count", FieldType.LONG, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            // Execute migration
            migrationEngine.migrateSchema(oldDef, newDef);
            
            // Verify data is preserved and can hold larger values
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, count) VALUES (?, ?, ?, ?)",
                "2", Instant.now(), Instant.now(), 9999999999L
            );
            
            Long count = jdbcTemplate.queryForObject(
                "SELECT count FROM test_data WHERE id = '2'", Long.class);
            assertEquals(9999999999L, count);
            
            // Verify migration was recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.ALTER_COLUMN_TYPE));
        }
        
        @Test
        @DisplayName("Should throw exception for incompatible type changes during migration")
        void shouldThrowExceptionForIncompatibleTypeChanges() {
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("flag", FieldType.BOOLEAN, true, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", oldFields);
            
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("flag", FieldType.INTEGER, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            assertThrows(IncompatibleSchemaChangeException.class,
                () -> migrationEngine.migrateSchema(oldDef, newDef));
        }
        
        @Test
        @DisplayName("Should handle multiple changes in single migration")
        void shouldHandleMultipleChangesInSingleMigration() {
            // Create initial table
            List<FieldDefinition> oldFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("count", FieldType.INTEGER, true, false, false, null, null, null, null, null),
                new FieldDefinition("old_field", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", oldFields);
            
            // Define new schema with multiple changes
            List<FieldDefinition> newFields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("count", FieldType.LONG, true, false, false, null, null, null, null, null),
                new FieldDefinition("new_field", FieldType.BOOLEAN, true, false, false, null, null, null, null, null)
            );
            
            CollectionDefinition oldDef = createCollection("test_data", oldFields);
            CollectionDefinition newDef = createCollection("test_data", newFields);
            
            // Execute migration
            migrationEngine.migrateSchema(oldDef, newDef);
            
            // Verify all migrations were recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertEquals(3, history.size());
            
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.ADD_COLUMN));
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.DEPRECATE_COLUMN));
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.ALTER_COLUMN_TYPE));
        }
        
        @Test
        @DisplayName("Should do nothing when schemas are identical")
        void shouldDoNothingWhenSchemasAreIdentical() {
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            setupTestTable("test_data", fields);
            
            CollectionDefinition oldDef = createCollection("test_data", fields);
            CollectionDefinition newDef = createCollection("test_data", fields);
            
            // Execute migration
            migrationEngine.migrateSchema(oldDef, newDef);
            
            // Verify no migrations were recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.isEmpty());
        }
    }
    
    @Nested
    @DisplayName("Migration Record String Type Tests")
    class MigrationRecordStringTypeTests {
        
        @Test
        @DisplayName("Should accept string migration type")
        void shouldAcceptStringMigrationType() {
            migrationEngine.recordMigration("test_data", "CREATE_TABLE", "CREATE TABLE...");

            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertEquals(1, history.size());
            assertEquals(MigrationType.CREATE_TABLE, history.get(0).migrationType());
        }
        
        @Test
        @DisplayName("Should throw for invalid string migration type")
        void shouldThrowForInvalidStringMigrationType() {
            assertThrows(IllegalArgumentException.class,
                () -> migrationEngine.recordMigration("test", "INVALID_TYPE", "SQL"));
        }
    }

    @Nested
    @DisplayName("Schema Reconciliation Tests")
    class SchemaReconciliationTests {

        @BeforeEach
        void cleanupTestTable() {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS test_data");
            } catch (Exception e) {
                // Ignore
            }
        }

        @Test
        @DisplayName("Should add missing columns when table exists without them")
        void shouldAddMissingColumnsWhenTableExistsWithoutThem() {
            // Create table with only 'name' column
            jdbcTemplate.execute(
                "CREATE TABLE test_data (id VARCHAR(36) PRIMARY KEY, " +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, name TEXT)");

            // Define collection with 'name' and 'description' columns
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            CollectionDefinition def = createCollection("test_data", fields);

            // Reconcile schema
            migrationEngine.reconcileSchema(def);

            // Verify the 'description' column was added
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, name, description) VALUES (?, ?, ?, ?, ?)",
                "1", Instant.now(), Instant.now(), "Test", "Test Description"
            );

            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_data WHERE description = 'Test Description'", Integer.class);
            assertEquals(1, count);

            // Verify migration was recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.stream().anyMatch(r -> r.migrationType() == MigrationType.ADD_COLUMN));
        }

        @Test
        @DisplayName("Should do nothing when table has all columns")
        void shouldDoNothingWhenTableHasAllColumns() {
            // Create table with all columns (including system columns that reconcileSchema auto-adds)
            jdbcTemplate.execute(
                "CREATE TABLE test_data (id VARCHAR(36) PRIMARY KEY, " +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, " +
                "created_by VARCHAR(36), updated_by VARCHAR(36), record_type_id VARCHAR(36), " +
                "name TEXT, description TEXT)");

            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("description", FieldType.STRING, true, false, false, null, null, null, null, null)
            );
            CollectionDefinition def = createCollection("test_data", fields);

            // Reconcile schema
            migrationEngine.reconcileSchema(def);

            // Verify no migrations were recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("Should do nothing when table does not exist")
        void shouldDoNothingWhenTableDoesNotExist() {
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            CollectionDefinition def = createCollection("test_data", fields);

            // Reconcile schema on a non-existent table
            migrationEngine.reconcileSchema(def);

            // No migration should be recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("Should add multiple missing columns")
        void shouldAddMultipleMissingColumns() {
            // Create table with only 'name' column (include system columns to isolate the test)
            jdbcTemplate.execute(
                "CREATE TABLE test_data (id VARCHAR(36) PRIMARY KEY, " +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, " +
                "created_by VARCHAR(36), updated_by VARCHAR(36), record_type_id VARCHAR(36), name TEXT)");

            // Define collection with 'name', 'price', and 'quantity' columns
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null),
                new FieldDefinition("price", FieldType.DOUBLE, true, false, false, null, null, null, null, null),
                new FieldDefinition("quantity", FieldType.INTEGER, true, false, false, null, null, null, null, null)
            );
            CollectionDefinition def = createCollection("test_data", fields);

            // Reconcile schema
            migrationEngine.reconcileSchema(def);

            // Verify both columns were added
            jdbcTemplate.update(
                "INSERT INTO test_data (id, created_at, updated_at, name, price, quantity) VALUES (?, ?, ?, ?, ?, ?)",
                "1", Instant.now(), Instant.now(), "Test", 9.99, 42
            );

            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_data", Integer.class);
            assertEquals(1, count);

            // Verify two ADD_COLUMN migrations were recorded
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            long addColumnCount = history.stream()
                .filter(r -> r.migrationType() == MigrationType.ADD_COLUMN)
                .count();
            assertEquals(2, addColumnCount);
        }

        @Test
        @DisplayName("Should handle case-insensitive column matching")
        void shouldHandleCaseInsensitiveColumnMatching() {
            // Create table with uppercase column name (include system columns to isolate the test)
            jdbcTemplate.execute(
                "CREATE TABLE test_data (id VARCHAR(36) PRIMARY KEY, " +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, " +
                "created_by VARCHAR(36), updated_by VARCHAR(36), record_type_id VARCHAR(36), NAME TEXT)");

            // Define collection with lowercase field name
            List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.STRING, false, false, false, null, null, null, null, null)
            );
            CollectionDefinition def = createCollection("test_data", fields);

            // Reconcile schema — should not add duplicate column
            migrationEngine.reconcileSchema(def);

            // Verify no migrations were recorded (column already exists)
            List<MigrationRecord> history = migrationEngine.getMigrationHistory("test_data");
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("Should return existing columns for a table")
        void shouldReturnExistingColumnsForTable() {
            jdbcTemplate.execute(
                "CREATE TABLE test_data (id VARCHAR(36) PRIMARY KEY, " +
                "created_at TIMESTAMP NOT NULL, name TEXT, price DOUBLE PRECISION)");

            Set<String> columns = migrationEngine.getExistingColumns("test_data");

            assertTrue(columns.contains("id"));
            assertTrue(columns.contains("created_at"));
            assertTrue(columns.contains("name"));
            assertTrue(columns.contains("price"));
        }

        @Test
        @DisplayName("Should return empty set for non-existent table")
        void shouldReturnEmptySetForNonExistentTable() {
            Set<String> columns = migrationEngine.getExistingColumns("non_existent_table");
            assertTrue(columns.isEmpty());
        }
    }

    @Nested
    @DisplayName("Destructive migration (migrateSchemaDestructive)")
    class DestructiveMigrationTests {

        @Test
        @DisplayName("physically DROPs a removed column (not deprecate)")
        void dropsRemovedColumn() {
            List<FieldDefinition> before = List.of(
                    FieldDefinition.string("name"), FieldDefinition.integer("amount"));
            createTestTable("orders", before);
            CollectionDefinition oldDef = createCollection("orders", before);
            CollectionDefinition newDef = createCollection("orders", List.of(FieldDefinition.string("name")));

            List<SchemaMigrationEngine.AppliedChange> applied = migrationEngine.migrateSchemaDestructive(
                    oldDef, newDef, TableRef.publicSchema("orders"), false);

            assertEquals(1, applied.size());
            assertEquals("REMOVE_FIELD", applied.get(0).operation());
            assertEquals(MigrationType.DROP_COLUMN, applied.get(0).type());
            Set<String> columns = migrationEngine.getExistingColumns("orders");
            assertFalse(columns.contains("amount"), "amount column should be physically dropped");
            assertTrue(columns.contains("name"), "name column should survive");
        }

        @Test
        @DisplayName("ADDs a new column and ALTERs a compatible type change")
        void addsAndAlters() {
            List<FieldDefinition> before = List.of(FieldDefinition.integer("amount"));
            createTestTable("things", before);
            CollectionDefinition oldDef = createCollection("things", before);
            // amount INTEGER -> STRING (compatible), plus a new 'label' STRING field.
            CollectionDefinition newDef = createCollection("things",
                    List.of(FieldDefinition.string("amount"), FieldDefinition.string("label")));

            List<SchemaMigrationEngine.AppliedChange> applied = migrationEngine.migrateSchemaDestructive(
                    oldDef, newDef, TableRef.publicSchema("things"), false);

            assertEquals(2, applied.size());
            Set<String> columns = migrationEngine.getExistingColumns("things");
            assertTrue(columns.contains("amount"));
            assertTrue(columns.contains("label"));
        }

        @Test
        @DisplayName("rejects an incompatible type change unless forced")
        void rejectsIncompatibleTypeChange() {
            List<FieldDefinition> before = List.of(FieldDefinition.bool("flag"));
            createTestTable("toggles", before);
            CollectionDefinition oldDef = createCollection("toggles", before);
            // BOOLEAN -> INTEGER is not in the compatibility matrix.
            CollectionDefinition newDef = createCollection("toggles", List.of(FieldDefinition.integer("flag")));

            assertThrows(IncompatibleSchemaChangeException.class, () ->
                    migrationEngine.migrateSchemaDestructive(
                            oldDef, newDef, TableRef.publicSchema("toggles"), false));
        }
    }

    @Nested
    @DisplayName("Unique flag toggle migrations")
    class UniqueFlagToggleTests {

        private static final String TABLE = "unique_toggle_items";

        private CollectionDefinition withUnique(boolean unique) {
            return createCollection(TABLE, List.of(
                new FieldDefinition("slug", FieldType.STRING, true, false, unique,
                    null, null, null, null, null),
                new FieldDefinition("country", FieldType.STRING, true, false, false,
                    null, null, null, null, null)));
        }

        private void insertRow(String id, String country, String slug) {
            jdbcTemplate.update("INSERT INTO " + TABLE
                + " (id, created_at, updated_at, country, slug) VALUES (?, NOW(), NOW(), ?, ?)",
                id, country, slug);
        }

        private int alterUniqueHistoryCount() {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kelta_migrations WHERE collection_name = '" + TABLE
                    + "' AND migration_type = 'ALTER_UNIQUE'", Integer.class);
            return count == null ? 0 : count;
        }

        @BeforeEach
        void createTable() {
            // Column-level UNIQUE mirrors what initializeCollection emits — the
            // database-default-named constraint shape the drop path must resolve
            // from the catalog (it cannot guess the name).
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE);
            jdbcTemplate.execute("CREATE TABLE " + TABLE + " ("
                + "id VARCHAR(36) PRIMARY KEY, "
                + "created_at TIMESTAMP NOT NULL, "
                + "updated_at TIMESTAMP NOT NULL, "
                + "country TEXT, "
                + "slug TEXT UNIQUE)");
            insertRow("a", "nl", "erasmus-plus");
        }

        @Test
        @DisplayName("unique true -> false drops the default-named constraint; duplicates then insert")
        void dropUniqueAllowsDuplicates() {
            assertThrows(DataAccessException.class, () -> insertRow("b", "de", "erasmus-plus"));

            migrationEngine.migrateSchema(withUnique(true), withUnique(false));

            insertRow("b", "de", "erasmus-plus");
            Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE slug = 'erasmus-plus'", Integer.class);
            assertEquals(2, rows);
            assertEquals(1, alterUniqueHistoryCount());
        }

        @Test
        @DisplayName("unique false -> true re-adds a uniq_-named constraint that rejects duplicates")
        void addUniqueRejectsDuplicates() {
            migrationEngine.migrateSchema(withUnique(true), withUnique(false));
            migrationEngine.migrateSchema(withUnique(false), withUnique(true));

            assertThrows(DataAccessException.class, () -> insertRow("b", "de", "erasmus-plus"));
            assertEquals(2, alterUniqueHistoryCount());
        }

        @Test
        @DisplayName("adding when a constraint already covers the column is a no-op (event redelivery)")
        void addIsIdempotent() {
            // The CREATE TABLE constraint is still in place; a false -> true toggle
            // must detect it (whatever its name) and skip the ADD.
            migrationEngine.migrateSchema(withUnique(false), withUnique(true));

            assertEquals(0, alterUniqueHistoryCount());
            assertThrows(DataAccessException.class, () -> insertRow("b", "de", "erasmus-plus"));
        }

        @Test
        @DisplayName("composite uniq_ indexes on the same column survive a single-column drop")
        void compositeIndexUntouched() {
            // The production scenario: single-column unique OFF, composite
            // (country, slug) uniqueness stays enforced by its separate index.
            jdbcTemplate.execute("CREATE UNIQUE INDEX uniq_unique_toggle_items_country_slug ON "
                + TABLE + " (country, slug)");

            migrationEngine.migrateSchema(withUnique(true), withUnique(false));

            // Same slug in another country: now allowed.
            insertRow("b", "de", "erasmus-plus");
            // Same (country, slug) pair: still rejected by the composite index.
            assertThrows(DataAccessException.class, () -> insertRow("c", "de", "erasmus-plus"));
        }
    }

    @Nested
    @DisplayName("pgvector extension on the add-field path")
    class VectorExtensionTests {

        private final FieldDefinition embedding = new FieldDefinition("embedding", FieldType.VECTOR,
            true, false, false, null, null, null, null, Map.of("dimension", 3), null);

        @Test
        @DisplayName("a refused CREATE EXTENSION fails with the create path's message and skips the ALTER")
        void refusedExtensionIsActionable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            doThrow(new BadSqlGrammarException(
                    "create extension", "CREATE EXTENSION IF NOT EXISTS vector",
                    new SQLException("permission denied to create extension \"vector\"")))
                .when(jdbc).execute("CREATE EXTENSION IF NOT EXISTS vector");
            SchemaMigrationEngine engine = new SchemaMigrationEngine(jdbc);
            CollectionDefinition oldDef = createCollection("vec_add", List.of(FieldDefinition.string("title")));
            CollectionDefinition newDef = createCollection("vec_add",
                List.of(FieldDefinition.string("title"), embedding));

            StorageException ex = assertThrows(StorageException.class,
                () -> engine.migrateSchema(oldDef, newDef));

            assertTrue(ex.getMessage().startsWith("Collection 'vec_add' has a VECTOR field but the pgvector "
                + "extension could not be enabled. Install pgvector on the database"), ex.getMessage());
            assertInstanceOf(BadSqlGrammarException.class, ex.getCause());
            verify(jdbc, never())
                .execute(contains("ADD COLUMN"));
        }

        @Test
        @DisplayName("no VECTOR field added → the extension is never requested")
        void noVectorNoExtension() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            SchemaMigrationEngine engine = new SchemaMigrationEngine(jdbc);
            CollectionDefinition oldDef = createCollection("vec_add", List.of(embedding));
            CollectionDefinition newDef = createCollection("vec_add",
                List.of(embedding, FieldDefinition.string("title")));

            engine.migrateSchema(oldDef, newDef);

            verify(jdbc, never())
                .execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
    }

    @Nested
    @DisplayName("Owner-field index")
    class OwnerIndexTests {

        private static final String TABLE = "owned_items";

        private CollectionDefinition owned(String ownerField) {
            return new CollectionDefinitionBuilder().name(TABLE)
                .addField(FieldDefinition.string("title"))
                .addField(FieldDefinition.lookup("owner", "users", "Owner"))
                .ownerField(ownerField)
                .build();
        }

        private int ownerIndexCount() {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.indexes WHERE LOWER(index_name) = 'idx_owner_owned_items_owner'",
                Integer.class);
            return count == null ? 0 : count;
        }

        @BeforeEach
        void createTable() {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE);
            jdbcTemplate.execute("CREATE TABLE " + TABLE + " (id VARCHAR(36) PRIMARY KEY, "
                + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, title TEXT, owner VARCHAR(36))");
        }

        @Test
        @DisplayName("setting ownerField creates the index; redelivery is a no-op")
        void settingOwnerFieldIndexes() {
            migrationEngine.migrateSchema(owned(null), owned("owner"));
            migrationEngine.migrateSchema(owned(null), owned("owner"));

            assertEquals(1, ownerIndexCount());
        }

        @Test
        @DisplayName("unchanged ownerField emits no index DDL")
        void unchangedOwnerFieldIsNoOp() {
            migrationEngine.migrateSchema(owned("owner"), owned("owner"));

            assertEquals(0, ownerIndexCount());
        }
    }
}
