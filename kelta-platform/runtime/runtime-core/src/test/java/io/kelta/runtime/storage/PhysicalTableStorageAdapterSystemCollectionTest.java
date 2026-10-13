package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.SortField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for system collection support in PhysicalTableStorageAdapter.
 *
 * <p>Verifies that system collections (those with systemCollection=true) correctly:
 * <ul>
 *   <li>Skip table creation during initialization</li>
 *   <li>Use column name mapping (field-level and collection-level)</li>
 *   <li>Inject tenant_id for tenant-scoped system collections</li>
 *   <li>Remap result column names from snake_case to camelCase</li>
 * </ul>
 */
class PhysicalTableStorageAdapterSystemCollectionTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaMigrationEngine migrationEngine;
    private PhysicalTableStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        migrationEngine = mock(SchemaMigrationEngine.class);
        adapter = new PhysicalTableStorageAdapter(jdbcTemplate, migrationEngine, new tools.jackson.databind.ObjectMapper());
        // Before writing a FK the adapter locates the target table (it may live in the tenant
        // schema or, for a system collection, in public). Default to "found" so FK tests
        // exercise the ADD CONSTRAINT path rather than the skip path.
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);
    }

    // ==================== Helper Methods ====================

    /**
     * Builds a system collection definition for "users" with column mappings.
     * Table name: "users" (managed by Flyway, not prefixed with tbl_).
     */
    private CollectionDefinition buildSystemCollection() {
        return new CollectionDefinitionBuilder()
                .name("users")
                .displayName("Users")
                .storageConfig(new StorageConfig("users", null))
                .systemCollection(true)
                .tenantScoped(true)
                .addField(new FieldDefinitionBuilder()
                        .name("firstName")
                        .type(FieldType.STRING)
                        .columnName("first_name")
                        .build())
                .addField(new FieldDefinitionBuilder()
                        .name("lastName")
                        .type(FieldType.STRING)
                        .columnName("last_name")
                        .build())
                .addField(new FieldDefinitionBuilder()
                        .name("email")
                        .type(FieldType.STRING)
                        .build())
                .addColumnMapping("email", "email_address")
                .build();
    }

    /**
     * Builds a system collection with collection-level column mapping only
     * (no field-level columnName overrides).
     */
    private CollectionDefinition buildSystemCollectionWithCollectionMapping() {
        return new CollectionDefinitionBuilder()
                .name("audit-logs")
                .displayName("Audit Logs")
                .storageConfig(new StorageConfig("audit_logs", null))
                .systemCollection(true)
                .tenantScoped(false)
                .addField(new FieldDefinitionBuilder()
                        .name("actionType")
                        .type(FieldType.STRING)
                        .build())
                .addColumnMapping("actionType", "action_type")
                .build();
    }

    /**
     * Builds a system collection that declares a field stored in {@code record_type_id}
     * (the shape of a per-record-type settings collection).
     */
    private CollectionDefinition buildSystemCollectionWithRecordTypeField() {
        return new CollectionDefinitionBuilder()
                .name("record-type-settings")
                .displayName("Record Type Settings")
                .storageConfig(new StorageConfig("record_type_setting", null))
                .systemCollection(true)
                .tenantScoped(false)
                .addField(new FieldDefinitionBuilder()
                        .name("recordTypeId")
                        .type(FieldType.STRING)
                        .columnName("record_type_id")
                        .build())
                .addField(new FieldDefinitionBuilder()
                        .name("fieldId")
                        .type(FieldType.STRING)
                        .columnName("field_id")
                        .build())
                .build();
    }

    /**
     * Builds a standard (non-system) collection definition.
     */
    private CollectionDefinition buildNonSystemCollection() {
        return new CollectionDefinitionBuilder()
                .name("products")
                .displayName("Products")
                .addField(FieldDefinition.requiredString("name"))
                .addField(FieldDefinition.doubleField("price"))
                .build();
    }

    // ==================== Initialization Tests ====================

    @Nested
    @DisplayName("initializeCollection Tests")
    class InitializationTests {

        @Test
        @DisplayName("Should skip table creation for system collections")
        void initializeCollection_skipsTableCreation_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            adapter.initializeCollection(systemDef);

            // Verify that no SQL execute was called (no CREATE TABLE)
            verify(jdbcTemplate, never()).execute(anyString());
            // Verify that migration engine was not invoked
            verify(migrationEngine, never()).recordMigration(
                    anyString(), any(SchemaMigrationEngine.MigrationType.class), anyString());
            verify(migrationEngine, never()).reconcileSchema(any(CollectionDefinition.class));
            verify(migrationEngine, never()).reconcileSchema(any(CollectionDefinition.class), any(TableRef.class));
        }

        @Test
        @DisplayName("Should create table for non-system collections")
        void initializeCollection_createsTable_forNonSystemCollections() {
            CollectionDefinition nonSystemDef = buildNonSystemCollection();

            adapter.initializeCollection(nonSystemDef);

            // Verify that SQL execute was called (CREATE TABLE)
            verify(jdbcTemplate, atLeastOnce()).execute(argThat((String sql) ->
                    sql.contains("CREATE TABLE IF NOT EXISTS")));
            // Verify migration tracking
            ArgumentCaptor<SchemaMigrationEngine.MigrationType> typeCaptor =
                    ArgumentCaptor.forClass(SchemaMigrationEngine.MigrationType.class);
            verify(migrationEngine).recordMigration(
                    eq("products"), typeCaptor.capture(), anyString());
            assertEquals(SchemaMigrationEngine.MigrationType.CREATE_TABLE, typeCaptor.getValue());
            verify(migrationEngine).reconcileSchema(eq(nonSystemDef), any(TableRef.class));
        }

        @Test
        @DisplayName("Should swallow DuplicateKeyException from concurrent CREATE TABLE race")
        void initializeCollection_swallowsDuplicateKey_fromConcurrentCreateRace() {
            // PostgreSQL's CREATE TABLE IF NOT EXISTS is not atomic against
            // concurrent CREATEs: two transactions can both pass the existence
            // check and then both try to INSERT into pg_type, leaving one with
            // SQLSTATE 23505 on pg_type_typname_nsp_index. Multi-pod workers
            // consuming the same NATS CollectionChanged event hit this race.
            // The losing pod must continue (the table now exists, committed by
            // the winner) so reconcileSchema can fill in any missing columns.
            CollectionDefinition nonSystemDef = buildNonSystemCollection();
            doThrow(new DuplicateKeyException(
                    "duplicate key value violates unique constraint \"pg_type_typname_nsp_index\""))
                    .when(jdbcTemplate).execute(argThat((String sql) ->
                            sql != null && sql.contains("CREATE TABLE IF NOT EXISTS")));

            assertDoesNotThrow(() -> adapter.initializeCollection(nonSystemDef));

            // Even though CREATE failed, reconcileSchema and migration recording
            // must still run — the table exists from the winning concurrent
            // transaction and we still need to bring this pod's view in line.
            verify(migrationEngine).reconcileSchema(eq(nonSystemDef), any(TableRef.class));
            verify(migrationEngine).recordMigration(
                    eq("products"), eq(SchemaMigrationEngine.MigrationType.CREATE_TABLE), anyString());
        }

        @Test
        @DisplayName("Should still propagate DuplicateKeyException from post-create statements")
        void initializeCollection_propagatesDuplicateKey_fromPostCreateStatements() {
            // The race-recovery path only applies to the CREATE TABLE statement
            // itself. A duplicate-key failure from a FK or index statement is a
            // real error (e.g. a constraint name collision) and must surface as
            // a StorageException so the caller can retry or log it.
            CollectionDefinition childDef = new CollectionDefinitionBuilder()
                    .name("order_lines")
                    .displayName("Order Lines")
                    .addField(FieldDefinition.requiredString("amount"))
                    .addField(new FieldDefinitionBuilder()
                            .name("parentRef")
                            .type(FieldType.MASTER_DETAIL)
                            .referenceConfig(ReferenceConfig.masterDetail("orders", "parent"))
                            .build())
                    .build();
            doThrow(new DuplicateKeyException("constraint name collision"))
                    .when(jdbcTemplate).execute(argThat((String sql) ->
                            sql != null && sql.contains("FOREIGN KEY")));

            assertThrows(StorageException.class,
                    () -> adapter.initializeCollection(childDef));
        }

        @Test
        @DisplayName("Should reconcile schema before issuing FK constraint statements")
        void initializeCollection_reconcilesSchema_beforeForeignKeyStatements() {
            // A collection with a MASTER_DETAIL field generates a FK constraint
            // statement in the post-create batch. When the underlying table was
            // created earlier without this column (e.g. the field was added later
            // and a NATS UPDATED event lands on a worker pod that fell into the
            // initialize path), CREATE TABLE IF NOT EXISTS is a no-op and the FK
            // statement references a column that doesn't yet exist. reconcileSchema
            // must run first to add the missing columns; otherwise the ADD
            // CONSTRAINT FOREIGN KEY fails with "column does not exist".
            CollectionDefinition childDef = new CollectionDefinitionBuilder()
                    .name("order_lines")
                    .displayName("Order Lines")
                    .addField(FieldDefinition.requiredString("amount"))
                    .addField(new FieldDefinitionBuilder()
                            .name("parentRef")
                            .type(FieldType.MASTER_DETAIL)
                            .referenceConfig(ReferenceConfig.masterDetail("orders", "parent"))
                            .build())
                    .build();

            adapter.initializeCollection(childDef);

            InOrder inOrder = inOrder(migrationEngine, jdbcTemplate);
            inOrder.verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("CREATE TABLE IF NOT EXISTS")));
            inOrder.verify(migrationEngine).reconcileSchema(eq(childDef), any(TableRef.class));
            inOrder.verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("FOREIGN KEY") && sql.contains("parent_ref")));
        }

        @Test
        @DisplayName("Should quote column names so reserved words like `user` are legal fields")
        void initializeCollection_quotesReservedWordColumns() {
            // `user` is a PostgreSQL reserved word. Emitted bare, the whole CREATE TABLE dies
            // with `syntax error at or near "user"` — so the collection never gets a table and
            // never reconciles, however many times the worker restarts.
            CollectionDefinition withReservedWord = new CollectionDefinitionBuilder()
                    .name("watchlists")
                    .displayName("Watchlists")
                    .addField(FieldDefinition.requiredString("user"))
                    .addField(FieldDefinition.requiredString("notes"))
                    .build();

            adapter.initializeCollection(withReservedWord);

            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("CREATE TABLE IF NOT EXISTS")
                            && sql.contains("\"user\" TEXT")
                            && !sql.contains(", user TEXT")));
        }

        @Test
        @DisplayName("Should fall back to the public schema when the target is not in the tenant's")
        void initializeCollection_resolvesForeignKeyTarget_inPublicSchema() {
            // A tenant collection may point a LOOKUP at a system collection, whose table is
            // Flyway-managed and lives in public — not alongside the source. Assuming
            // co-location produced `relation "<tenant>.users" does not exist` and aborted
            // initialization for the entire collection.
            reset(jdbcTemplate);
            // The tenant-schema existence pre-check, then the two FK target probes:
            // absent from the tenant schema, present in public.
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                    .thenReturn(false)
                    .thenReturn(true);

            // A non-system target, so the raw name is the table name in both schemas.
            io.kelta.runtime.context.TenantContext.runWithTenant("tenant-1", "acme", () ->
                    adapter.initializeCollection(buildCollectionWithLookupTo("archived_orders")));

            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("FOREIGN KEY") && sql.contains("REFERENCES archived_orders")));
        }

        @Test
        @DisplayName("Should map a system-collection FK target to its real table (users → platform_user)")
        void initializeCollection_resolvesSystemCollectionTarget_toItsPhysicalTable() {
            // ReferenceConfig names the target COLLECTION, not its table. Tenant collections make
            // those identical, but a system collection maps onto its Flyway-managed table, so
            // `users` is stored in `platform_user` — a table named `users` exists nowhere. Taking
            // the collection name as the table name left every LOOKUP at a system collection
            // pointing at a table that never existed.
            reset(jdbcTemplate);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                    .thenReturn(false)   // no tenant-local `users` collection…
                    .thenReturn(true);   // …so the system mapping in public wins

            io.kelta.runtime.context.TenantContext.runWithTenant("tenant-1", "acme", () ->
                    adapter.initializeCollection(buildCollectionWithLookupTo("users")));

            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("FOREIGN KEY") && sql.contains("REFERENCES platform_user")));
        }

        @Test
        @DisplayName("Should prefer a tenant's own table over the system mapping of the same name")
        void initializeCollection_prefersTenantTable_overSystemMapping() {
            // A tenant collection is allowed to be called `users`; its own table must win in its
            // own schema rather than being redirected to the platform's user table.
            reset(jdbcTemplate);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(1);
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                    .thenReturn(true);   // first candidate = tenant schema, raw name

            io.kelta.runtime.context.TenantContext.runWithTenant("tenant-1", "acme", () ->
                    adapter.initializeCollection(buildCollectionWithLookupTo("users")));

            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("FOREIGN KEY")
                            && sql.contains("REFERENCES \"acme\".\"users\"")
                            && !sql.contains("platform_user")));
        }

        @Test
        @DisplayName("Should skip — not fail — a FK whose target table exists nowhere")
        void initializeCollection_skipsForeignKey_whenTargetMissing() {
            reset(jdbcTemplate);
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                    .thenReturn(false);

            assertDoesNotThrow(() ->
                    adapter.initializeCollection(buildCollectionWithLookupTo("ghosts")));

            // The table itself is still created; only the dangling constraint is skipped.
            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("CREATE TABLE IF NOT EXISTS")));
            verify(jdbcTemplate, never()).execute(argThat((String sql) ->
                    sql.contains("FOREIGN KEY")));
        }

        @Test
        @DisplayName("Should add FKs NOT VALID, then validate, so legacy orphans can't block the deploy")
        void initializeCollection_addsForeignKeyNotValid_thenValidates() {
            // One orphaned row otherwise fails ADD CONSTRAINT on every single boot, forever,
            // leaving the column with no referential integrity at all. NOT VALID enforces the
            // FK for every subsequent write immediately; VALIDATE then promotes it when the
            // existing rows turn out to be clean.
            adapter.initializeCollection(buildCollectionWithLookupTo("users"));

            InOrder inOrder = inOrder(jdbcTemplate);
            inOrder.verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("ADD CONSTRAINT") && sql.contains("NOT VALID")));
            inOrder.verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("VALIDATE CONSTRAINT")));
        }

        @Test
        @DisplayName("Should scope the FK existence check to this table, not the whole database")
        void initializeCollection_scopesForeignKeyExistenceCheck_toTable() {
            // conname is unique per table, not per database, and generated FK names derive from
            // collection + field names — which repeat across tenants. An unqualified
            // `WHERE conname = ...` matched another tenant's constraint and skipped creating
            // this one, so only the first tenant to initialize ever got its foreign key.
            adapter.initializeCollection(buildCollectionWithLookupTo("users"));

            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("ADD CONSTRAINT")
                            && sql.contains("conname = 'fk_watchlists_owner'")
                            && sql.contains("conrelid = to_regclass(")));
        }

        @Test
        @DisplayName("Should keep the FK enforced when validation fails on existing rows")
        void initializeCollection_toleratesValidateFailure_onOrphanedRows() {
            doThrow(new org.springframework.dao.DataIntegrityViolationException(
                    "insert or update on table violates foreign key constraint"))
                    .when(jdbcTemplate).execute(argThat((String sql) ->
                            sql != null && sql.contains("VALIDATE CONSTRAINT")));

            assertDoesNotThrow(() ->
                    adapter.initializeCollection(buildCollectionWithLookupTo("users")));

            verify(jdbcTemplate).execute(argThat((String sql) ->
                    sql.contains("ADD CONSTRAINT") && sql.contains("NOT VALID")));
        }
    }

    /** A non-system collection with a single LOOKUP field pointing at {@code target}. */
    private CollectionDefinition buildCollectionWithLookupTo(String target) {
        return new CollectionDefinitionBuilder()
                .name("watchlists")
                .displayName("Watchlists")
                .addField(new FieldDefinitionBuilder()
                        .name("owner")
                        .type(FieldType.LOOKUP)
                        .referenceConfig(ReferenceConfig.lookup(target, "Owner"))
                        .build())
                .build();
    }

    // ==================== Column Name Mapping Tests (via create) ====================

    @Nested
    @DisplayName("Column Name Mapping Tests via create()")
    class ColumnNameMappingViaCreateTests {

        @Test
        @DisplayName("Should write a declared record_type_id field on a system collection")
        void create_writesDeclaredRecordTypeIdField_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollectionWithRecordTypeField();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "row-1");
            data.put("recordTypeId", "rt-1");
            data.put("fieldId", "field-1");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(systemDef, data);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

            String sql = sqlCaptor.getValue();
            assertEquals(1, sql.split("record_type_id", -1).length - 1,
                    "INSERT should write record_type_id exactly once, got: " + sql);
            assertTrue(java.util.Arrays.asList(paramsCaptor.getValue()).contains("rt-1"),
                    "INSERT params should carry the record type id");
        }

        @Test
        @DisplayName("Should write record_type_id once as a system column on a user collection")
        void create_writesRecordTypeIdOnce_forNonSystemCollections() {
            CollectionDefinition def = buildNonSystemCollection();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "p-1");
            data.put("name", "Widget");
            data.put("recordTypeId", "rt-1");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(def, data);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));
            String sql = sqlCaptor.getValue();
            assertEquals(1, sql.split("record_type_id", -1).length - 1,
                    "INSERT should write record_type_id exactly once, got: " + sql);
        }

        @Test
        @DisplayName("Should use field-level columnName for system collections in INSERT SQL")
        void create_usesFieldLevelColumnName_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "user-1");
            data.put("firstName", "John");
            data.put("lastName", "Doe");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(systemDef, data);

            // Capture the SQL and verify it uses "first_name" and "last_name" columns
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("first_name"),
                    "SQL should use field-level column name 'first_name', got: " + sql);
            assertTrue(sql.contains("last_name"),
                    "SQL should use field-level column name 'last_name', got: " + sql);
        }

        @Test
        @DisplayName("Should use collection-level mapping for system collections in INSERT SQL")
        void create_usesCollectionLevelMapping_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "user-1");
            data.put("email", "john@example.com");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(systemDef, data);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("email_address"),
                    "SQL should use collection-level column mapping 'email_address', got: " + sql);
        }

        @Test
        @DisplayName("Should use field name as column name for non-system collections")
        void create_usesFieldName_forNonSystemCollections() {
            CollectionDefinition nonSystemDef = buildNonSystemCollection();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "prod-1");
            data.put("name", "Widget");
            data.put("price", 9.99);
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(nonSystemDef, data);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));

            String sql = sqlCaptor.getValue();
            // For non-system collections, column names match field names
            assertTrue(sql.contains("name"),
                    "SQL should use field name 'name' for non-system collection, got: " + sql);
            assertTrue(sql.contains("price"),
                    "SQL should use field name 'price' for non-system collection, got: " + sql);
        }

        @Test
        @DisplayName("Should inject tenant_id for tenant-scoped system collections")
        void create_injectsTenantId_forTenantScopedSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "user-1");
            data.put("tenantId", "tenant-abc");
            data.put("firstName", "Jane");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(systemDef, data);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("tenant_id"),
                    "SQL should include 'tenant_id' column for tenant-scoped system collection, got: " + sql);

            // Verify tenant_id value is in the params
            Object[] params = paramsCaptor.getValue();
            boolean hasTenantId = false;
            for (Object param : params) {
                if ("tenant-abc".equals(param)) {
                    hasTenantId = true;
                    break;
                }
            }
            assertTrue(hasTenantId, "Parameters should contain the tenant ID value 'tenant-abc'");
        }

        @Test
        @DisplayName("Should not inject tenant_id for non-system collections")
        void create_skipsTenantId_forNonSystemCollections() {
            CollectionDefinition nonSystemDef = buildNonSystemCollection();

            Map<String, Object> data = new HashMap<>();
            data.put("id", "prod-1");
            data.put("tenantId", "tenant-abc");
            data.put("name", "Widget");
            data.put("createdAt", Instant.now());
            data.put("updatedAt", Instant.now());

            adapter.create(nonSystemDef, data);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertFalse(sql.contains("tenant_id"),
                    "SQL should NOT include 'tenant_id' column for non-system collection, got: " + sql);
        }
    }

    // ==================== Column Name Mapping Tests (via update) ====================

    @Nested
    @DisplayName("Column Name Mapping Tests via update()")
    class ColumnNameMappingViaUpdateTests {

        @Test
        @DisplayName("Should update a declared record_type_id field on a system collection")
        void update_writesDeclaredRecordTypeIdField_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollectionWithRecordTypeField();

            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", "row-1");
            existingRecord.put("record_type_id", "rt-1");
            when(jdbcTemplate.queryForList(contains("SELECT * FROM"), eq("row-1")))
                    .thenReturn(List.of(existingRecord));
            when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(1);

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("recordTypeId", "rt-2");
            updateData.put("updatedAt", Instant.now());

            adapter.update(systemDef, "row-1", updateData);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());
            String sql = sqlCaptor.getValue();
            assertEquals(1, sql.split("record_type_id", -1).length - 1,
                    "UPDATE should set record_type_id exactly once, got: " + sql);
            assertTrue(java.util.Arrays.asList(paramsCaptor.getValue()).contains("rt-2"),
                    "UPDATE params should carry the new record type id");
        }

        @Test
        @DisplayName("Should use field-level columnName for system collections in UPDATE SQL")
        void update_usesFieldLevelColumnName_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            // Mock getById to return existing record
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", "user-1");
            existingRecord.put("first_name", "John");
            when(jdbcTemplate.queryForList(contains("SELECT * FROM"), eq("user-1")))
                    .thenReturn(List.of(existingRecord));

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("firstName", "Jane");
            updateData.put("updatedAt", Instant.now());

            // Mock the update call
            when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(1);
            // Mock the second getById call for the return value
            when(jdbcTemplate.queryForList(contains("SELECT * FROM"), eq("user-1")))
                    .thenReturn(List.of(existingRecord));

            adapter.update(systemDef, "user-1", updateData);

            // Capture the UPDATE SQL
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("first_name"),
                    "UPDATE SQL should use field-level column name 'first_name', got: " + sql);
        }
    }

    // ==================== Column Name Remapping in Query Results ====================

    @Nested
    @DisplayName("Column Name Remapping Tests via query()")
    class ColumnNameRemappingTests {

        @Test
        @DisplayName("Should remap snake_case columns to camelCase for system collections")
        void query_remapsSnakeCaseToCamelCase_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            // Mock queryForList to return results with snake_case column names
            Map<String, Object> row = new HashMap<>();
            row.put("id", "user-1");
            row.put("first_name", "John");
            row.put("last_name", "Doe");
            row.put("email_address", "john@example.com");
            row.put("created_at", Instant.now());
            row.put("updated_at", Instant.now());
            row.put("created_by", "admin");
            row.put("updated_by", "admin");
            row.put("tenant_id", "tenant-1");

            when(jdbcTemplate.queryForList(contains("SELECT"), any(Object[].class)))
                    .thenReturn(new ArrayList<>(List.of(new HashMap<>(row))));
            when(jdbcTemplate.queryForObject(contains("SELECT COUNT"), eq(Long.class), any(Object[].class)))
                    .thenReturn(1L);

            QueryRequest request = QueryRequest.defaults();
            QueryResult result = adapter.query(systemDef, request);

            assertFalse(result.data().isEmpty(), "Query should return data");
            Map<String, Object> record = result.data().get(0);

            // Verify system audit fields are remapped
            assertTrue(record.containsKey("createdAt"),
                    "Result should have 'createdAt' (remapped from 'created_at')");
            assertTrue(record.containsKey("updatedAt"),
                    "Result should have 'updatedAt' (remapped from 'updated_at')");
            assertTrue(record.containsKey("createdBy"),
                    "Result should have 'createdBy' (remapped from 'created_by')");
            assertTrue(record.containsKey("updatedBy"),
                    "Result should have 'updatedBy' (remapped from 'updated_by')");
            assertTrue(record.containsKey("tenantId"),
                    "Result should have 'tenantId' (remapped from 'tenant_id')");

            // Verify field-level column names are remapped
            assertTrue(record.containsKey("firstName"),
                    "Result should have 'firstName' (remapped from 'first_name')");
            assertTrue(record.containsKey("lastName"),
                    "Result should have 'lastName' (remapped from 'last_name')");

            // Verify collection-level column mapping is remapped
            assertTrue(record.containsKey("email"),
                    "Result should have 'email' (remapped from 'email_address')");
        }

        @Test
        @DisplayName("Should remap audit columns for non-system collections")
        void query_remapsAuditColumns_forNonSystemCollections() {
            CollectionDefinition nonSystemDef = buildNonSystemCollection();

            Map<String, Object> row = new HashMap<>();
            row.put("id", "prod-1");
            row.put("name", "Widget");
            row.put("price", 9.99);
            row.put("created_at", Instant.now());
            row.put("updated_at", Instant.now());

            when(jdbcTemplate.queryForList(contains("SELECT"), any(Object[].class)))
                    .thenReturn(new ArrayList<>(List.of(new HashMap<>(row))));
            when(jdbcTemplate.queryForObject(contains("SELECT COUNT"), eq(Long.class), any(Object[].class)))
                    .thenReturn(1L);

            QueryRequest request = QueryRequest.defaults();
            QueryResult result = adapter.query(nonSystemDef, request);

            assertFalse(result.data().isEmpty(), "Query should return data");
            Map<String, Object> record = result.data().get(0);

            // For non-system collections, single-word fields keep their names
            assertTrue(record.containsKey("name"),
                    "Non-system collection should preserve 'name' as-is");
            assertTrue(record.containsKey("price"),
                    "Non-system collection should preserve 'price' as-is");

            // Audit columns should be remapped to camelCase
            assertTrue(record.containsKey("createdAt"),
                    "Audit column 'created_at' should be remapped to 'createdAt'");
            assertTrue(record.containsKey("updatedAt"),
                    "Audit column 'updated_at' should be remapped to 'updatedAt'");
        }
    }

    // ==================== Column Name Resolution in getById ====================

    @Nested
    @DisplayName("Column Name Remapping Tests via getById()")
    class GetByIdRemappingTests {

        @Test
        @DisplayName("Should remap column names in getById results for system collections")
        void getById_remapsColumnNames_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            Map<String, Object> row = new HashMap<>();
            row.put("id", "user-1");
            row.put("first_name", "John");
            row.put("last_name", "Doe");
            row.put("email_address", "john@example.com");
            row.put("created_at", Instant.now());
            row.put("updated_at", Instant.now());

            when(jdbcTemplate.queryForList(contains("SELECT * FROM"), eq("user-1")))
                    .thenReturn(new ArrayList<>(List.of(new HashMap<>(row))));

            Optional<Map<String, Object>> result = adapter.getById(systemDef, "user-1");

            assertTrue(result.isPresent());
            Map<String, Object> record = result.get();
            assertTrue(record.containsKey("firstName"),
                    "getById should remap 'first_name' to 'firstName'");
            assertTrue(record.containsKey("lastName"),
                    "getById should remap 'last_name' to 'lastName'");
            assertTrue(record.containsKey("email"),
                    "getById should remap 'email_address' to 'email'");
        }
    }

    // ==================== Column Name Resolution in Query Filters/Sorting ====================

    @Nested
    @DisplayName("Column Name Resolution in WHERE/ORDER BY")
    class ColumnNameResolutionInQueryTests {

        @Test
        @DisplayName("Should resolve system audit field names in WHERE clause")
        void query_resolvesSysFieldsInWhereClause() {
            CollectionDefinition systemDef = buildSystemCollection();

            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(new ArrayList<>());
            when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                    .thenReturn(0L);

            QueryRequest request = new QueryRequest(
                    Pagination.defaults(),
                    List.of(),
                    List.of(),
                    List.of(FilterCondition.eq("createdAt", "2024-01-01"))
            );

            adapter.query(systemDef, request);

            // Capture the SELECT SQL and verify it contains "created_at" in WHERE
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("created_at"),
                    "WHERE clause should use 'created_at' for 'createdAt', got: " + sql);
        }

        @Test
        @DisplayName("Should resolve mapped field names in ORDER BY clause")
        void query_resolvesMappedFieldsInOrderByClause() {
            CollectionDefinition systemDef = buildSystemCollection();

            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(new ArrayList<>());
            when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                    .thenReturn(0L);

            QueryRequest request = new QueryRequest(
                    Pagination.defaults(),
                    List.of(SortField.asc("firstName")),
                    List.of(),
                    List.of()
            );

            adapter.query(systemDef, request);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("first_name"),
                    "ORDER BY clause should use 'first_name' for 'firstName', got: " + sql);
        }
    }

    // ==================== isUnique Column Name Resolution ====================

    @Nested
    @DisplayName("isUnique Column Name Resolution Tests")
    class IsUniqueColumnNameTests {

        @Test
        @DisplayName("Should resolve column name for system collection in isUnique query")
        void isUnique_resolvesColumnName_forSystemCollections() {
            CollectionDefinition systemDef = buildSystemCollection();

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(0);

            adapter.isUnique(systemDef, "firstName", "John", null);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Integer.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("first_name"),
                    "isUnique SQL should use resolved column name 'first_name', got: " + sql);
        }

        @Test
        @DisplayName("Should use field name as column name for non-system collection in isUnique")
        void isUnique_usesFieldName_forNonSystemCollections() {
            CollectionDefinition nonSystemDef = buildNonSystemCollection();

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                    .thenReturn(0);

            adapter.isUnique(nonSystemDef, "name", "Widget", null);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Integer.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("name"),
                    "isUnique SQL should use field name 'name', got: " + sql);
        }
    }
}
