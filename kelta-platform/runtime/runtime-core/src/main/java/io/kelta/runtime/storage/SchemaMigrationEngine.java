package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Engine for managing schema migrations in Mode A (Physical Tables) storage.
 * 
 * <p>This engine handles:
 * <ul>
 *   <li>Migration history tracking in the {@code kelta_migrations} table</li>
 *   <li>Schema diff detection (added fields, removed fields, type changes)</li>
 *   <li>ALTER TABLE generation for adding columns</li>
 *   <li>Column deprecation (mark but don't drop)</li>
 *   <li>Type change validation and execution</li>
 * </ul>
 * 
 * <h2>Migration History Table</h2>
 * <p>The {@code kelta_migrations} table tracks all schema changes with columns:
 * <ul>
 *   <li>id - auto-increment primary key</li>
 *   <li>collection_name - the collection being migrated</li>
 *   <li>migration_type - CREATE_TABLE, ADD_COLUMN, DEPRECATE_COLUMN, ALTER_COLUMN_TYPE</li>
 *   <li>sql_statement - the executed SQL</li>
 *   <li>executed_at - timestamp of execution</li>
 * </ul>
 * 
 * <h2>Type Change Compatibility</h2>
 * <p>The following type changes are allowed:
 * <ul>
 *   <li>STRING → any type (with data loss risk)</li>
 *   <li>INTEGER → LONG, DOUBLE, STRING</li>
 *   <li>LONG → DOUBLE, STRING</li>
 *   <li>DOUBLE → STRING</li>
 *   <li>BOOLEAN → STRING</li>
 *   <li>DATE → DATETIME, STRING</li>
 *   <li>DATETIME → STRING</li>
 *   <li>JSON → STRING</li>
 * </ul>
 * 
 * @see StorageAdapter
 * @see PhysicalTableStorageAdapter
 * @since 1.0.0
 */
@Service
public class SchemaMigrationEngine {
    
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationEngine.class);
    
    /**
     * Migration types for tracking in the history table.
     */
    public enum MigrationType {
        CREATE_TABLE,
        ADD_COLUMN,
        DEPRECATE_COLUMN,
        ALTER_COLUMN_TYPE,
        DROP_COLUMN,
        ALTER_UNIQUE
    }
    
    private static final String MIGRATIONS_TABLE = "kelta_migrations";
    
    /**
     * Type compatibility map: for each source type, defines which target types are allowed.
     */
    private static final Map<FieldType, Set<FieldType>> TYPE_COMPATIBILITY = new EnumMap<>(FieldType.class);
    
    static {
        // STRING can change to any type (with data loss risk)
        TYPE_COMPATIBILITY.put(FieldType.STRING, EnumSet.allOf(FieldType.class));

        // INTEGER can change to LONG, DOUBLE, STRING
        TYPE_COMPATIBILITY.put(FieldType.INTEGER, EnumSet.of(
            FieldType.INTEGER, FieldType.LONG, FieldType.DOUBLE, FieldType.STRING));

        // LONG can change to DOUBLE, STRING
        TYPE_COMPATIBILITY.put(FieldType.LONG, EnumSet.of(
            FieldType.LONG, FieldType.DOUBLE, FieldType.STRING));

        // DOUBLE can change to STRING
        TYPE_COMPATIBILITY.put(FieldType.DOUBLE, EnumSet.of(
            FieldType.DOUBLE, FieldType.STRING));

        // BOOLEAN can change to STRING
        TYPE_COMPATIBILITY.put(FieldType.BOOLEAN, EnumSet.of(
            FieldType.BOOLEAN, FieldType.STRING));

        // DATE can change to DATETIME, STRING
        TYPE_COMPATIBILITY.put(FieldType.DATE, EnumSet.of(
            FieldType.DATE, FieldType.DATETIME, FieldType.STRING));

        // DATETIME can change to STRING
        TYPE_COMPATIBILITY.put(FieldType.DATETIME, EnumSet.of(
            FieldType.DATETIME, FieldType.STRING));

        // JSON can change to STRING
        TYPE_COMPATIBILITY.put(FieldType.JSON, EnumSet.of(
            FieldType.JSON, FieldType.STRING));

        // --- Phase 2 type compatibility ---
        TYPE_COMPATIBILITY.put(FieldType.REFERENCE, EnumSet.of(
            FieldType.REFERENCE, FieldType.LOOKUP, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.ARRAY, EnumSet.of(
            FieldType.ARRAY, FieldType.JSON, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.PICKLIST, EnumSet.of(
            FieldType.PICKLIST, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.MULTI_PICKLIST, EnumSet.of(
            FieldType.MULTI_PICKLIST, FieldType.JSON, FieldType.ARRAY));
        TYPE_COMPATIBILITY.put(FieldType.CURRENCY, EnumSet.of(
            FieldType.CURRENCY, FieldType.DOUBLE, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.PERCENT, EnumSet.of(
            FieldType.PERCENT, FieldType.DOUBLE, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.AUTO_NUMBER, EnumSet.of(
            FieldType.AUTO_NUMBER, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.PHONE, EnumSet.of(
            FieldType.PHONE, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.EMAIL, EnumSet.of(
            FieldType.EMAIL, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.URL, EnumSet.of(
            FieldType.URL, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.TEXT, EnumSet.of(
            FieldType.TEXT, FieldType.RICH_TEXT, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.RICH_TEXT, EnumSet.of(
            FieldType.RICH_TEXT, FieldType.TEXT, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.VECTOR, EnumSet.of(
            FieldType.VECTOR));
        TYPE_COMPATIBILITY.put(FieldType.ENCRYPTED, EnumSet.of(
            FieldType.ENCRYPTED, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.EXTERNAL_ID, EnumSet.of(
            FieldType.EXTERNAL_ID, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.GEOLOCATION, EnumSet.of(
            FieldType.GEOLOCATION));
        TYPE_COMPATIBILITY.put(FieldType.LOOKUP, EnumSet.of(
            FieldType.LOOKUP, FieldType.REFERENCE, FieldType.STRING));
        TYPE_COMPATIBILITY.put(FieldType.MASTER_DETAIL, EnumSet.of(
            FieldType.MASTER_DETAIL, FieldType.LOOKUP));
        TYPE_COMPATIBILITY.put(FieldType.FORMULA, EnumSet.of(
            FieldType.FORMULA));
        TYPE_COMPATIBILITY.put(FieldType.ROLLUP_SUMMARY, EnumSet.of(
            FieldType.ROLLUP_SUMMARY));
    }
    
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * Creates a new SchemaMigrationEngine.
     * 
     * @param jdbcTemplate the JdbcTemplate for database operations
     */
    public SchemaMigrationEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initializeMigrationTable();
    }
    
    /**
     * Initializes the migration history table if it doesn't exist.
     *
     * <p>Deliberately without the {@code tenant_id} column the table carries in Postgres:
     * that column and its {@code app.current_tenant_id} default are owned by Flyway
     * (V202, which also puts RLS on the table), and Flyway runs before any pod does DDL.
     * Declaring the default here too would break the embedded-H2 tests, which have no such
     * setting; omitting the column entirely lets the insert below fall through to the
     * default on Postgres and to NULL everywhere else.
     */
    private void initializeMigrationTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS kelta_migrations (
                id SERIAL PRIMARY KEY,
                collection_name VARCHAR(255) NOT NULL,
                migration_type VARCHAR(50) NOT NULL,
                sql_statement TEXT NOT NULL,
                executed_at TIMESTAMP NOT NULL
            )
            """;
        
        try {
            jdbcTemplate.execute(sql);
            log.debug("Migration history table initialized");
        } catch (Exception e) {
            log.warn("Could not initialize migration table (may already exist): {}", e.getMessage());
        }
    }
    
    /**
     * Records a migration in the history table.
     * 
     * @param collectionName the collection being migrated
     * @param migrationType the type of migration
     * @param sqlStatement the SQL statement that was executed
     */
    public void recordMigration(String collectionName, MigrationType migrationType, String sqlStatement) {
        String sql = """
            INSERT INTO kelta_migrations (collection_name, migration_type, sql_statement, executed_at)
            VALUES (?, ?, ?, ?)
            """;
        
        jdbcTemplate.update(sql, collectionName, migrationType.name(), sqlStatement, 
            Timestamp.from(Instant.now()));
        
        log.info("Recorded migration for collection '{}': {}", collectionName, migrationType);
    }
    
    /**
     * Records a migration in the history table using a string migration type.
     * 
     * @param collectionName the collection being migrated
     * @param migrationType the type of migration as a string
     * @param sqlStatement the SQL statement that was executed
     */
    public void recordMigration(String collectionName, String migrationType, String sqlStatement) {
        recordMigration(collectionName, MigrationType.valueOf(migrationType), sqlStatement);
    }
    
    /**
     * Migrates the schema from an old collection definition to a new one.
     * 
     * <p>This method:
     * <ol>
     *   <li>Detects added fields and generates ALTER TABLE ADD COLUMN statements</li>
     *   <li>Detects removed fields and marks them as deprecated (adds comment)</li>
     *   <li>Detects type changes, validates compatibility, and generates ALTER TABLE ALTER COLUMN TYPE</li>
     * </ol>
     * 
     * @param oldDefinition the previous collection definition
     * @param newDefinition the new collection definition
     * @throws IncompatibleSchemaChangeException if a type change is incompatible
     * @throws StorageException if migration fails
     */
    public void migrateSchema(CollectionDefinition oldDefinition, CollectionDefinition newDefinition) {
        migrateSchema(oldDefinition, newDefinition, TableRef.publicSchema(getTableName(newDefinition)));
    }

    /**
     * Migrates the schema from an old collection definition to a new one,
     * using a schema-qualified table reference.
     *
     * @param oldDefinition the previous collection definition
     * @param newDefinition the new collection definition
     * @param tableRef the schema-qualified table reference
     * @throws IncompatibleSchemaChangeException if a type change is incompatible
     * @throws StorageException if migration fails
     */
    public void migrateSchema(CollectionDefinition oldDefinition, CollectionDefinition newDefinition,
                               TableRef tableRef) {
        String qualifiedName = tableRef.toSql();
        List<MigrationAction> migrations = new ArrayList<>();

        // Detect added fields
        for (FieldDefinition newField : newDefinition.fields()) {
            if (oldDefinition.getField(newField.name()) == null) {
                migrations.add(createAddColumnMigration(qualifiedName, newDefinition, newField));
            }
        }

        // Detect removed fields (mark as deprecated, don't drop)
        for (FieldDefinition oldField : oldDefinition.fields()) {
            if (newDefinition.getField(oldField.name()) == null) {
                migrations.add(createDeprecateColumnMigration(qualifiedName, newDefinition, oldField));
            }
        }

        // Detect type changes
        for (FieldDefinition newField : newDefinition.fields()) {
            FieldDefinition oldField = oldDefinition.getField(newField.name());
            if (oldField != null && !oldField.type().equals(newField.type())) {
                validateTypeChange(newDefinition.name(), oldField, newField);
                migrations.add(createAlterColumnTypeMigration(qualifiedName, newDefinition, oldField, newField));
            }
        }

        // Execute all migrations
        for (MigrationAction migration : migrations) {
            executeMigration(migration);
        }

        // captureGeo turned on → ensure the geo system columns exist. Tables created
        // before the geo feature lack them; ADD COLUMN IF NOT EXISTS is idempotent, so
        // NATS event redelivery and multi-pod refresh races are harmless.
        if (newDefinition.captureGeo() && !oldDefinition.captureGeo()
                && !newDefinition.systemCollection()) {
            ensureGeoColumns(newDefinition.name(), qualifiedName);
        }

        // Detect unique-flag toggles. These need a live constraint-catalog lookup
        // (legacy constraints carry Postgres default names like <table>_<column>_key),
        // so they are applied directly rather than as precomputed MigrationActions —
        // and after the loop above, so a just-added or just-retyped column exists.
        int uniqueToggles = 0;
        for (FieldDefinition newField : newDefinition.fields()) {
            FieldDefinition oldField = oldDefinition.getField(newField.name());
            if (oldField == null || !newField.type().hasPhysicalColumn()) {
                continue;
            }
            if (oldField.unique() != newField.unique()) {
                applyUniqueToggle(newDefinition, newField, tableRef, newField.unique());
                uniqueToggles++;
            }
        }

        log.info("Schema migration completed for collection '{}': {} changes applied",
            newDefinition.name(), migrations.size() + uniqueToggles);
    }

    /**
     * Ensures the {@code created_geo}/{@code updated_geo} JSONB system columns exist on a
     * captureGeo collection's table. Postgres-only DDL (JSONB) — deliberately NOT part of
     * the base CREATE TABLE so H2-backed unit suites and non-geo tables never see the type.
     * Idempotent under event redelivery and multi-pod races.
     */
    public void ensureGeoColumns(String collectionName, String qualifiedTableName) {
        String geoSql = "ALTER TABLE " + qualifiedTableName
                + " ADD COLUMN IF NOT EXISTS created_geo JSONB,"
                + " ADD COLUMN IF NOT EXISTS updated_geo JSONB";
        jdbcTemplate.execute(geoSql);
        recordMigration(collectionName, MigrationType.ADD_COLUMN, geoSql);
        log.info("Ensured geo system columns on '{}' (captureGeo enabled)", qualifiedTableName);
    }

    /**
     * Adds or drops the single-column UNIQUE constraint backing a field's
     * {@code unique} flag.
     *
     * <p>Dropping resolves the actual constraint name(s) from the catalog instead of
     * guessing: constraints created inline by {@code CREATE TABLE ... UNIQUE} carry the
     * Postgres default name ({@code <table>_<column>_key}), while ones added by this
     * method are named {@code uniq_<table>_<column>}. Only single-column UNIQUE
     * constraints on exactly this column are touched — composite uniques (which are
     * separate {@code uniq_*} <em>indexes</em> managed by
     * {@code CompositeUniqueConstraintService}) and the {@code EXTERNAL_ID} unique
     * index are unaffected. Adding is idempotent: if any single-column UNIQUE
     * constraint already covers the column (including a legacy-named one), nothing
     * is done — NATS collection-changed events may reach a pod more than once.
     */
    private void applyUniqueToggle(CollectionDefinition definition, FieldDefinition field,
                                    TableRef tableRef, boolean makeUnique) {
        String columnName = sanitizeIdentifier(resolvePhysicalColumnName(definition, field));
        List<String> existing = findSingleColumnUniqueConstraints(tableRef, columnName);

        if (makeUnique) {
            if (!existing.isEmpty()) {
                log.info("Unique constraint already present for '{}.{}' ({}), skipping add",
                    definition.name(), field.name(), existing);
                return;
            }
            String sql = "ALTER TABLE " + tableRef.toSql()
                + " ADD CONSTRAINT " + uniqueConstraintName(tableRef.tableName(), columnName)
                + " UNIQUE (" + quoteIdentifier(columnName) + ")";
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException e) {
                throw new StorageException("Failed to add unique constraint for field '"
                    + field.name() + "' on collection '" + definition.name()
                    + "' (existing duplicate values block the constraint?)", e);
            }
            recordMigration(definition.name(), MigrationType.ALTER_UNIQUE, sql);
            log.info("Added unique constraint on '{}.{}'", definition.name(), field.name());
            return;
        }

        if (existing.isEmpty()) {
            log.info("No single-column unique constraint found for '{}.{}', nothing to drop",
                definition.name(), field.name());
            return;
        }
        for (String constraintName : existing) {
            String sql = "ALTER TABLE " + tableRef.toSql()
                + " DROP CONSTRAINT \"" + constraintName + "\"";
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException e) {
                throw new StorageException("Failed to drop unique constraint '" + constraintName
                    + "' for field '" + field.name() + "' on collection '" + definition.name() + "'", e);
            }
            recordMigration(definition.name(), MigrationType.ALTER_UNIQUE, sql);
        }
        log.info("Dropped unique constraint(s) {} on '{}.{}'", existing, definition.name(), field.name());
    }

    /**
     * Returns the names of UNIQUE constraints that cover exactly (and only) the given
     * column. {@code LOWER(...)} on both sides keeps the lookup portable between
     * Postgres (unquoted identifiers fold to lowercase) and H2 (fold to uppercase).
     */
    private List<String> findSingleColumnUniqueConstraints(TableRef tableRef, String columnName) {
        String sql = """
                SELECT tc.constraint_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                 AND ccu.constraint_schema = tc.constraint_schema
                WHERE tc.constraint_type = 'UNIQUE'
                  AND LOWER(tc.table_schema) = LOWER(?)
                  AND LOWER(tc.table_name) = LOWER(?)
                GROUP BY tc.constraint_name
                HAVING COUNT(*) = 1 AND LOWER(MIN(ccu.column_name)) = LOWER(?)
                """;
        return jdbcTemplate.queryForList(sql, String.class,
            tableRef.schema(), tableRef.tableName(), columnName);
    }

    /** {@code uniq_<table>_<column>}, hyphen-safe and bounded to Postgres's 63-char limit. */
    private String uniqueConstraintName(String tableName, String columnName) {
        String name = "uniq_" + PhysicalTableStorageAdapter.identifierPart(tableName) + "_" + columnName;
        return name.length() > 63 ? name.substring(0, 63) : name;
    }
    
    /**
     * Applies a <em>destructive</em> schema migration transforming {@code oldDefinition} into
     * {@code newDefinition} on live tenant data. Unlike {@link #migrateSchema}, removed fields are
     * physically <strong>dropped</strong> (real {@code ALTER TABLE ... DROP COLUMN}) rather than
     * merely deprecated. This is the highest-stakes DDL path in the codebase and is reachable only
     * from the guarded schema-migration execute endpoint.
     *
     * <p>Each change is applied in order (ADD, then DROP, then ALTER) and recorded in
     * {@code kelta_migrations}. Every {@link SchemaDiff.DiffType#TYPE_CHANGED type change} is gated
     * by {@link #validateTypeChange}: an incompatible change throws
     * {@link IncompatibleSchemaChangeException} unless {@code force} is set, in which case it is
     * attempted anyway (with a warning) — the caller is trusting a {@code USING} cast to succeed.
     *
     * @param oldDefinition the current (live) collection definition
     * @param newDefinition the target collection definition
     * @param tableRef      the schema-qualified table reference for the live table
     * @param force         when true, apply type changes even if {@link #isTypeChangeCompatible}
     *                      reports them incompatible (still fails if Postgres rejects the cast)
     * @return the list of changes actually applied (operation + field + SQL), in apply order
     * @throws IncompatibleSchemaChangeException if a type change is incompatible and {@code force} is false
     * @throws StorageException                  if a DDL statement fails
     */
    public List<AppliedChange> migrateSchemaDestructive(CollectionDefinition oldDefinition,
            CollectionDefinition newDefinition, TableRef tableRef, boolean force) {
        String qualifiedName = tableRef.toSql();
        List<AppliedChange> planned = new ArrayList<>();

        // Added fields → ADD COLUMN (idempotent via IF NOT EXISTS).
        for (FieldDefinition newField : newDefinition.fields()) {
            if (oldDefinition.getField(newField.name()) == null) {
                MigrationAction a = createAddColumnMigration(qualifiedName, newDefinition, newField);
                planned.add(new AppliedChange("ADD_FIELD", newField.name(), a.type(), a.sql()));
            }
        }

        // Removed fields → DROP COLUMN (the destructive step migrateSchema will not do).
        for (FieldDefinition oldField : oldDefinition.fields()) {
            if (newDefinition.getField(oldField.name()) == null) {
                MigrationAction a = createDropColumnMigration(qualifiedName, oldDefinition, oldField);
                planned.add(new AppliedChange("REMOVE_FIELD", oldField.name(), a.type(), a.sql()));
            }
        }

        // Type changes → ALTER COLUMN TYPE, gated by compatibility unless forced.
        for (FieldDefinition newField : newDefinition.fields()) {
            FieldDefinition oldField = oldDefinition.getField(newField.name());
            if (oldField != null && !oldField.type().equals(newField.type())) {
                if (force) {
                    if (!isTypeChangeCompatible(oldField.type(), newField.type())) {
                        log.warn("Forcing INCOMPATIBLE type change on '{}.{}': {} -> {} (relying on USING cast)",
                                newDefinition.name(), oldField.name(), oldField.type(), newField.type());
                    }
                } else {
                    validateTypeChange(newDefinition.name(), oldField, newField);
                }
                MigrationAction a = createAlterColumnTypeMigration(qualifiedName, newDefinition, oldField, newField);
                planned.add(new AppliedChange("MODIFY_FIELD", newField.name(), a.type(), a.sql()));
            }
        }

        for (AppliedChange change : planned) {
            try {
                jdbcTemplate.execute(change.sql());
                recordMigration(newDefinition.name(), change.type(), change.sql());
            } catch (RuntimeException e) {
                throw new StorageException(String.format(
                        "Failed to apply %s on field '%s' of collection '%s': %s",
                        change.operation(), change.field(), newDefinition.name(), e.getMessage()), e);
            }
        }

        log.info("Destructive schema migration completed for collection '{}': {} changes applied",
                newDefinition.name(), planned.size());
        return planned;
    }

    /**
     * Reconciles the physical database table schema with the collection definition.
     *
     * <p>This method introspects the actual database columns using {@code information_schema.columns}
     * and adds any columns that exist in the definition but not in the physical table.
     *
     * <p>This is called during collection initialization to handle the case where a table
     * was created before new fields were added to the definition. {@code CREATE TABLE IF NOT EXISTS}
     * will silently succeed even if the table is missing columns, so this method fills the gap.
     *
     * @param definition the current collection definition
     */
    public void reconcileSchema(CollectionDefinition definition) {
        reconcileSchema(definition, TableRef.publicSchema(getTableName(definition)));
    }

    /**
     * Reconciles the physical database table schema with the collection definition,
     * using a schema-qualified table reference.
     *
     * @param definition the current collection definition
     * @param tableRef the schema-qualified table reference
     */
    public void reconcileSchema(CollectionDefinition definition, TableRef tableRef) {
        String qualifiedName = tableRef.toSql();

        // Query actual columns in the database table
        Set<String> existingColumns;
        try {
            existingColumns = getExistingColumns(tableRef.schema(), tableRef.tableName());
        } catch (Exception e) {
            log.warn("Could not introspect columns for table '{}': {}", qualifiedName, e.getMessage());
            return;
        }

        if (existingColumns.isEmpty()) {
            // Table doesn't exist yet — nothing to reconcile
            return;
        }

        // Reconcile audit columns (owner_id -> created_by/updated_by migration)
        reconcileAuditColumns(qualifiedName, existingColumns);

        // Reconcile record_type_id system column (added for record type enforcement)
        if (!definition.systemCollection() && !existingColumns.contains("record_type_id")) {
            String addCol = String.format(
                "ALTER TABLE %s ADD COLUMN IF NOT EXISTS record_type_id VARCHAR(36)", qualifiedName);
            try {
                jdbcTemplate.execute(addCol);
                recordMigration(definition.name(), MigrationType.ADD_COLUMN, addCol);
                log.info("Reconciliation: added record_type_id column to '{}'", qualifiedName);
            } catch (Exception e) {
                log.warn("Could not add record_type_id to '{}': {}", qualifiedName, e.getMessage());
            }
        }

        // Find fields in the definition that don't have a corresponding column
        List<MigrationAction> migrations = new ArrayList<>();
        for (FieldDefinition field : definition.fields()) {
            if (!field.type().hasPhysicalColumn()) {
                continue; // Skip FORMULA, ROLLUP_SUMMARY
            }

            String expectedColumn = resolvePhysicalColumnName(definition, field).toLowerCase();
            if (!existingColumns.contains(expectedColumn)) {
                migrations.add(createAddColumnMigration(qualifiedName, definition, field));
                log.info("Reconciliation: column '{}' missing from table '{}', will add",
                    expectedColumn, qualifiedName);
            }

            // Check companion columns
            if (field.type() == FieldType.CURRENCY
                    && !existingColumns.contains(expectedColumn + "_currency_code")) {
                // The companion column is included in the ADD COLUMN migration
                // No separate action needed
            }
            if (field.type() == FieldType.GEOLOCATION
                    && !existingColumns.contains(expectedColumn + "_longitude")) {
                // The companion column is included in the ADD COLUMN migration
                // No separate action needed
            }
        }

        // Execute any needed migrations
        for (MigrationAction migration : migrations) {
            executeMigration(migration);
        }

        if (!migrations.isEmpty()) {
            log.info("Schema reconciliation completed for collection '{}': {} columns added",
                definition.name(), migrations.size());
        } else {
            log.debug("Schema reconciliation for collection '{}': table is up to date",
                definition.name());
        }
    }

    /**
     * Reconciles audit columns for a table, migrating from the legacy {@code owner_id}
     * column to the new {@code created_by} and {@code updated_by} columns.
     *
     * <p>This method:
     * <ul>
     *   <li>Adds {@code created_by VARCHAR(36)} if it does not exist</li>
     *   <li>Adds {@code updated_by VARCHAR(36)} if it does not exist</li>
     *   <li>If {@code owner_id} exists and {@code created_by} was just added, copies
     *       {@code owner_id} values into {@code created_by} for existing rows</li>
     * </ul>
     *
     * @param tableName the table name to reconcile
     * @param existingColumns the set of existing lowercase column names
     */
    /**
     * Reconciles audit columns for a table.
     * The tableIdentifier parameter may be a bare table name or a schema-qualified SQL expression.
     */
    void reconcileAuditColumns(String tableIdentifier, Set<String> existingColumns) {
        boolean hasOwnerIdColumn = existingColumns.contains("owner_id");
        boolean createdByAdded = false;

        if (!existingColumns.contains("created_by")) {
            String sql = "ALTER TABLE " + tableIdentifier + " ADD COLUMN IF NOT EXISTS created_by VARCHAR(36)";
            try {
                jdbcTemplate.execute(sql);
                log.info("Added audit column 'created_by' to table '{}'", tableIdentifier);
                createdByAdded = true;
            } catch (Exception e) {
                log.warn("Could not add 'created_by' column to table '{}': {}", tableIdentifier, e.getMessage());
            }
        }

        if (!existingColumns.contains("updated_by")) {
            String sql = "ALTER TABLE " + tableIdentifier + " ADD COLUMN IF NOT EXISTS updated_by VARCHAR(36)";
            try {
                jdbcTemplate.execute(sql);
                log.info("Added audit column 'updated_by' to table '{}'", tableIdentifier);
            } catch (Exception e) {
                log.warn("Could not add 'updated_by' column to table '{}': {}", tableIdentifier, e.getMessage());
            }
        }

        // Migrate owner_id data into created_by for existing rows
        if (hasOwnerIdColumn && createdByAdded) {
            String migrateSql = "UPDATE " + tableIdentifier
                    + " SET created_by = owner_id WHERE created_by IS NULL";
            try {
                int updated = jdbcTemplate.update(migrateSql);
                log.info("Migrated {} rows: copied owner_id to created_by in table '{}'", updated, tableIdentifier);
            } catch (Exception e) {
                log.warn("Could not migrate owner_id to created_by in table '{}': {}", tableIdentifier, e.getMessage());
            }
        }
    }

    /**
     * Gets the set of existing column names in a database table.
     * Uses {@code information_schema.columns} which is supported by PostgreSQL and H2.
     *
     * @param tableName the table name to introspect
     * @return set of lowercase column names, or empty set if table doesn't exist
     */
    Set<String> getExistingColumns(String tableName) {
        return getExistingColumns("public", tableName);
    }

    /**
     * Gets the set of existing column names in a database table within a specific schema.
     * Uses {@code information_schema.columns} which is supported by PostgreSQL and H2.
     *
     * @param schemaName the schema name to look in
     * @param tableName the table name to introspect
     * @return set of lowercase column names, or empty set if table doesn't exist
     */
    Set<String> getExistingColumns(String schemaName, String tableName) {
        String sql = """
            SELECT column_name FROM information_schema.columns
            WHERE LOWER(table_schema) = LOWER(?)
            AND LOWER(table_name) = LOWER(?)
            """;

        List<String> columns = jdbcTemplate.query(sql,
            (rs, rowNum) -> rs.getString("column_name").toLowerCase(), schemaName, tableName);

        return new HashSet<>(columns);
    }

    /**
     * Detects schema differences between two collection definitions.
     * 
     * @param oldDefinition the previous collection definition
     * @param newDefinition the new collection definition
     * @return a list of detected schema differences
     */
    public List<SchemaDiff> detectDifferences(CollectionDefinition oldDefinition, 
            CollectionDefinition newDefinition) {
        List<SchemaDiff> diffs = new ArrayList<>();
        
        // Detect added fields
        for (FieldDefinition newField : newDefinition.fields()) {
            if (oldDefinition.getField(newField.name()) == null) {
                diffs.add(new SchemaDiff(SchemaDiff.DiffType.FIELD_ADDED, newField.name(), 
                    null, newField.type()));
            }
        }
        
        // Detect removed fields
        for (FieldDefinition oldField : oldDefinition.fields()) {
            if (newDefinition.getField(oldField.name()) == null) {
                diffs.add(new SchemaDiff(SchemaDiff.DiffType.FIELD_REMOVED, oldField.name(), 
                    oldField.type(), null));
            }
        }
        
        // Detect type changes
        for (FieldDefinition newField : newDefinition.fields()) {
            FieldDefinition oldField = oldDefinition.getField(newField.name());
            if (oldField != null && !oldField.type().equals(newField.type())) {
                diffs.add(new SchemaDiff(SchemaDiff.DiffType.TYPE_CHANGED, newField.name(), 
                    oldField.type(), newField.type()));
            }
        }
        
        return diffs;
    }
    
    /**
     * Validates that a type change is compatible.
     * 
     * @param collectionName the collection name
     * @param oldField the old field definition
     * @param newField the new field definition
     * @throws IncompatibleSchemaChangeException if the type change is not allowed
     */
    public void validateTypeChange(String collectionName, FieldDefinition oldField, 
            FieldDefinition newField) {
        FieldType oldType = oldField.type();
        FieldType newType = newField.type();
        
        Set<FieldType> allowedTypes = TYPE_COMPATIBILITY.get(oldType);
        if (allowedTypes == null || !allowedTypes.contains(newType)) {
            throw new IncompatibleSchemaChangeException(collectionName, oldField.name(), 
                oldType, newType);
        }
        
        log.debug("Type change validated for field '{}': {} -> {}", 
            oldField.name(), oldType, newType);
    }
    
    /**
     * Checks if a type change is compatible without throwing an exception.
     * 
     * @param oldType the current field type
     * @param newType the requested new field type
     * @return true if the type change is allowed, false otherwise
     */
    public boolean isTypeChangeCompatible(FieldType oldType, FieldType newType) {
        if (oldType == newType) {
            return true;
        }
        Set<FieldType> allowedTypes = TYPE_COMPATIBILITY.get(oldType);
        return allowedTypes != null && allowedTypes.contains(newType);
    }
    
    /**
     * Gets the migration history for a collection.
     * 
     * @param collectionName the collection name
     * @return list of migration records
     */
    public List<MigrationRecord> getMigrationHistory(String collectionName) {
        String sql = """
            SELECT id, collection_name, migration_type, sql_statement, executed_at
            FROM kelta_migrations
            WHERE collection_name = ?
            ORDER BY executed_at ASC
            """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> new MigrationRecord(
            rs.getLong("id"),
            rs.getString("collection_name"),
            MigrationType.valueOf(rs.getString("migration_type")),
            rs.getString("sql_statement"),
            rs.getTimestamp("executed_at").toInstant()
        ), collectionName);
    }
    
    /**
     * Gets all migration history.
     * 
     * @return list of all migration records
     */
    public List<MigrationRecord> getAllMigrationHistory() {
        String sql = """
            SELECT id, collection_name, migration_type, sql_statement, executed_at
            FROM kelta_migrations
            ORDER BY executed_at ASC
            """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> new MigrationRecord(
            rs.getLong("id"),
            rs.getString("collection_name"),
            MigrationType.valueOf(rs.getString("migration_type")),
            rs.getString("sql_statement"),
            rs.getTimestamp("executed_at").toInstant()
        ));
    }
    
    // ==================== Private Helper Methods ====================
    
    private String getTableName(CollectionDefinition definition) {
        if (definition.storageConfig() != null && definition.storageConfig().tableName() != null) {
            return definition.storageConfig().tableName();
        }
        return definition.name();
    }
    
    /**
     * Resolves the physical column name for a field, applying snake_case conversion
     * for non-system collections.
     */
    private String resolvePhysicalColumnName(CollectionDefinition definition, FieldDefinition field) {
        if (definition.systemCollection()) {
            return field.name();
        }
        return PhysicalTableStorageAdapter.toSnakeCase(field.name());
    }

    private String sanitizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Identifier cannot be null or blank");
        }
        if (!identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid identifier: " + identifier);
        }
        return identifier;
    }

    /**
     * Sanitizes and double-quotes a column name for use as a column <em>reference</em> in DDL.
     * Mirrors {@link PhysicalTableStorageAdapter#quoteIdentifier} — without it, an
     * {@code ALTER TABLE … ADD COLUMN user …} is a syntax error, so a collection that
     * initialized fine would fail to reconcile the moment such a field is added.
     * Constraint/index <em>names</em> and values compared against catalog strings keep using
     * {@link #sanitizeIdentifier}.
     */
    private String quoteIdentifier(String identifier) {
        return PhysicalTableStorageAdapter.quoteIdentifier(identifier);
    }
    
    /**
     * Maps a field's type to its PostgreSQL column type. The {@code field} argument
     * is used for parameterized types — currently {@link FieldType#VECTOR}, which
     * reads its {@code dimension} from {@code fieldTypeConfig} (default 1536).
     */
    String mapFieldTypeToSql(FieldType type, FieldDefinition field) {
        return switch (type) {
            case STRING -> "TEXT";
            case INTEGER -> "INTEGER";
            case LONG -> "BIGINT";
            case DOUBLE -> "DOUBLE PRECISION";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case DATETIME -> "TIMESTAMP";
            case JSON -> "JSONB";
            case REFERENCE -> "VARCHAR(36)";
            case ARRAY -> "JSONB";
            case PICKLIST -> "VARCHAR(255)";
            case MULTI_PICKLIST -> "TEXT[]";
            case CURRENCY -> "NUMERIC(18,2)";
            case PERCENT -> "NUMERIC(8,4)";
            case AUTO_NUMBER -> "VARCHAR(100)";
            case PHONE -> "VARCHAR(40)";
            case EMAIL -> "VARCHAR(320)";
            case URL -> "VARCHAR(2048)";
            case TEXT, RICH_TEXT -> "TEXT";
            case VECTOR -> "vector(" + vectorDimension(field) + ")";
            case ENCRYPTED -> "BYTEA";
            case EXTERNAL_ID -> "VARCHAR(255)";
            case GEOLOCATION -> "DOUBLE PRECISION";
            case LOOKUP -> "VARCHAR(36)";
            case MASTER_DETAIL -> "VARCHAR(36)";
            case ROLLUP_SUMMARY -> null;
            case FORMULA -> {
                Map<String, Object> cfg = field.fieldTypeConfig();
                String returnType = cfg != null ? (String) cfg.get("returnType") : null;
                yield switch (returnType != null ? returnType.toUpperCase() : "TEXT") {
                    case "NUMBER" -> "NUMERIC";
                    case "BOOLEAN" -> "BOOLEAN";
                    default -> "TEXT";
                };
            }
        };
    }

    /** pgvector caps each row at 16000 dimensions; default to 1536 (OpenAI small). */
    private static int vectorDimension(FieldDefinition field) {
        Object configured = field == null ? null : field.getConfigValue("dimension");
        int dim = switch (configured) {
            case Number n -> n.intValue();
            case String s -> {
                try {
                    yield Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "VECTOR field '" + field.name()
                                    + "' has non-numeric dimension: " + s);
                }
            }
            case null -> 1536;
            default -> throw new IllegalArgumentException(
                    "VECTOR field '" + field.name()
                            + "' has unsupported dimension type: " + configured.getClass().getName());
        };
        if (dim < 1 || dim > 16000) {
            throw new IllegalArgumentException(
                    "VECTOR dimension must be between 1 and 16000 (got " + dim + ")");
        }
        return dim;
    }
    
    /**
     * Creates an ADD COLUMN migration action.
     * The tableIdentifier may be a bare name or schema-qualified SQL expression.
     */
    private MigrationAction createAddColumnMigration(String tableIdentifier, CollectionDefinition definition,
            FieldDefinition field) {
        if (!field.type().hasPhysicalColumn()) {
            // FORMULA and ROLLUP_SUMMARY have no physical column
            return new MigrationAction(definition.name(), MigrationType.ADD_COLUMN,
                "-- No physical column for computed field: " + field.name());
        }

        String columnName = resolvePhysicalColumnName(definition, field);

        // IF NOT EXISTS keeps ADD COLUMN idempotent across concurrent reconciles
        // (broadcast collection-changed events reach every worker pod; NATS JetStream
        // may also redeliver a message if the ack is delayed).
        StringBuilder sql = new StringBuilder("ALTER TABLE ");
        sql.append(tableIdentifier);
        sql.append(" ADD COLUMN IF NOT EXISTS ");
        sql.append(quoteIdentifier(columnName));
        sql.append(" ");
        sql.append(mapFieldTypeToSql(field.type(), field));

        // Note: We don't add NOT NULL for new columns as existing rows would fail
        // The application layer handles nullability validation

        if (field.unique()) {
            sql.append(" UNIQUE");
        }

        // Companion columns
        if (field.type() == FieldType.CURRENCY) {
            sql.append("; ALTER TABLE ").append(tableIdentifier);
            sql.append(" ADD COLUMN IF NOT EXISTS ").append(quoteIdentifier(columnName + "_currency_code"));
            sql.append(" VARCHAR(3)");
        }
        if (field.type() == FieldType.GEOLOCATION) {
            sql.append("; ALTER TABLE ").append(tableIdentifier);
            sql.append(" ADD COLUMN IF NOT EXISTS ").append(quoteIdentifier(columnName + "_longitude"));
            sql.append(" DOUBLE PRECISION");
        }

        return new MigrationAction(definition.name(), MigrationType.ADD_COLUMN, sql.toString());
    }

    /**
     * Creates a DEPRECATE COLUMN migration action.
     * The tableIdentifier may be a bare name or schema-qualified SQL expression.
     */
    private MigrationAction createDeprecateColumnMigration(String tableIdentifier, CollectionDefinition definition,
            FieldDefinition field) {
        String columnName = resolvePhysicalColumnName(definition, field);
        // Mark column as deprecated by adding a comment
        // We don't drop the column to preserve data
        String sql = String.format(
            "COMMENT ON COLUMN %s.%s IS 'DEPRECATED: This column is no longer in use as of %s'",
            tableIdentifier,
            quoteIdentifier(columnName),
            Instant.now().toString()
        );

        return new MigrationAction(definition.name(), MigrationType.DEPRECATE_COLUMN, sql);
    }

    /**
     * Creates a destructive DROP COLUMN migration action, including any companion columns
     * (currency code, geolocation longitude). Uses {@code IF EXISTS} so a re-run — or a column
     * already deprecated/dropped on another pod — is a no-op rather than an error.
     * The tableIdentifier may be a bare name or schema-qualified SQL expression.
     */
    private MigrationAction createDropColumnMigration(String tableIdentifier, CollectionDefinition definition,
            FieldDefinition field) {
        if (!field.type().hasPhysicalColumn()) {
            // FORMULA / ROLLUP_SUMMARY have no physical column to drop.
            return new MigrationAction(definition.name(), MigrationType.DROP_COLUMN,
                "-- No physical column for computed field: " + field.name());
        }

        String columnName = resolvePhysicalColumnName(definition, field);
        StringBuilder sql = new StringBuilder("ALTER TABLE ");
        sql.append(tableIdentifier);
        sql.append(" DROP COLUMN IF EXISTS ");
        sql.append(quoteIdentifier(columnName));

        // Companion columns mirror createAddColumnMigration so no orphan column survives a drop.
        if (field.type() == FieldType.CURRENCY) {
            sql.append("; ALTER TABLE ").append(tableIdentifier);
            sql.append(" DROP COLUMN IF EXISTS ").append(quoteIdentifier(columnName + "_currency_code"));
        }
        if (field.type() == FieldType.GEOLOCATION) {
            sql.append("; ALTER TABLE ").append(tableIdentifier);
            sql.append(" DROP COLUMN IF EXISTS ").append(quoteIdentifier(columnName + "_longitude"));
        }

        return new MigrationAction(definition.name(), MigrationType.DROP_COLUMN, sql.toString());
    }

    /**
     * Creates an ALTER COLUMN TYPE migration action.
     * The tableIdentifier may be a bare name or schema-qualified SQL expression.
     */
    private MigrationAction createAlterColumnTypeMigration(String tableIdentifier, CollectionDefinition definition,
            FieldDefinition oldField, FieldDefinition newField) {
        String columnName = resolvePhysicalColumnName(definition, newField);
        String newSqlType = mapFieldTypeToSql(newField.type(), newField);

        // PostgreSQL syntax for changing column type with USING clause for type conversion
        String sql = String.format(
            "ALTER TABLE %s ALTER COLUMN %s TYPE %s USING %s::%s",
            tableIdentifier,
            quoteIdentifier(columnName),
            newSqlType,
            quoteIdentifier(columnName),
            newSqlType
        );

        return new MigrationAction(definition.name(), MigrationType.ALTER_COLUMN_TYPE, sql);
    }
    
    private void executeMigration(MigrationAction migration) {
        try {
            jdbcTemplate.execute(migration.sql());
            recordMigration(migration.collectionName(), migration.type(), migration.sql());
            log.debug("Executed migration: {} - {}", migration.type(), migration.sql());
        } catch (Exception e) {
            throw new StorageException(
                String.format("Failed to execute migration for collection '%s': %s", 
                    migration.collectionName(), e.getMessage()), e);
        }
    }
    
    // ==================== Inner Classes ====================
    
    /**
     * Represents a migration action to be executed.
     */
    private record MigrationAction(
        String collectionName,
        MigrationType type,
        String sql
    ) {}

    /**
     * A single change applied by {@link #migrateSchemaDestructive}, returned so callers can record
     * per-step progress (e.g. {@code migration_step} rows).
     *
     * @param operation coarse operation label — {@code ADD_FIELD}, {@code REMOVE_FIELD}, or {@code MODIFY_FIELD}
     * @param field     the field name the change targets
     * @param type      the {@link MigrationType} of the underlying DDL
     * @param sql       the executed SQL statement
     */
    public record AppliedChange(
        String operation,
        String field,
        MigrationType type,
        String sql
    ) {}
    
    /**
     * Represents a schema difference between two collection definitions.
     */
    public record SchemaDiff(
        DiffType diffType,
        String fieldName,
        FieldType oldType,
        FieldType newType
    ) {
        public enum DiffType {
            FIELD_ADDED,
            FIELD_REMOVED,
            TYPE_CHANGED
        }
    }
    
    /**
     * Represents a migration record from the history table.
     */
    public record MigrationRecord(
        long id,
        String collectionName,
        MigrationType migrationType,
        String sqlStatement,
        Instant executedAt
    ) {}
}
