package io.kelta.runtime.storage;

import static io.kelta.runtime.storage.PhysicalTableStorageAdapter.SYSTEM_COLUMNS;
import static io.kelta.runtime.storage.SqlIdentifiers.buildBoundedIdentifier;
import static io.kelta.runtime.storage.SqlIdentifiers.buildHnswIndexStatement;
import static io.kelta.runtime.storage.SqlIdentifiers.buildOwnerIndexStatement;
import static io.kelta.runtime.storage.SqlIdentifiers.getBaseTableName;
import static io.kelta.runtime.storage.SqlIdentifiers.getColumnName;
import static io.kelta.runtime.storage.SqlIdentifiers.identifierPart;
import static io.kelta.runtime.storage.SqlIdentifiers.quoteIdentifier;
import static io.kelta.runtime.storage.SqlIdentifiers.sanitizeIdentifier;
import static io.kelta.runtime.storage.StorageConstraintErrors.isConcurrentDdlDuplicate;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Table DDL for {@link PhysicalTableStorageAdapter#initializeCollection}: the {@code CREATE
 * TABLE}, its indexes, and LOOKUP/MASTER_DETAIL foreign keys (with target-schema resolution and
 * concurrent-creator tolerance), plus the field-type to column-type mapping.
 *
 * <p>Extracted from {@code PhysicalTableStorageAdapter} unchanged.
 */
final class CollectionTableDdl {

    private static final Logger log = LoggerFactory.getLogger(CollectionTableDdl.class);

    /**
     * Physical table name for each system collection, keyed by collection name.
     *
     * <p>Only system collections need this: a tenant collection's storage config always names the
     * table after the collection, whereas a system collection maps onto its Flyway-managed table
     * ({@code users} → {@code platform_user}). Used to resolve foreign keys whose target is a
     * system collection — see {@link #resolveTargetTable}.
     */
    private static final Map<String, String> SYSTEM_COLLECTION_TABLES =
            io.kelta.runtime.model.system.SystemCollectionDefinitions.byName().entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, e -> getBaseTableName(e.getValue())));

    private final JdbcTemplate jdbcTemplate;
    private final SchemaMigrationEngine migrationEngine;

    CollectionTableDdl(JdbcTemplate jdbcTemplate, SchemaMigrationEngine migrationEngine) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrationEngine = migrationEngine;
    }

    /**
     * Creates (or reconciles) the physical table for a non-system collection: tenant schema,
     * {@code CREATE TABLE IF NOT EXISTS}, schema reconcile, indexes and foreign keys. The body of
     * {@link PhysicalTableStorageAdapter#initializeCollection} after its system-collection skip.
     *
     * @param definition the collection to initialize
     * @param tableRef its resolved table
     */
    void initializeTable(CollectionDefinition definition, TableRef tableRef) {
        String qualifiedName = tableRef.toSql();

        // Ensure the tenant schema exists before creating the table. Use
        // information_schema to verify the CREATE actually took effect — a
        // permission error on CREATE would bubble out of jdbcTemplate.execute,
        // but a post-condition check turns a silently-missing schema (e.g.
        // CREATE raced against a concurrent DROP) into an explicit, actionable
        // StorageException instead of a confusing "relation does not exist"
        // failure later during the CREATE TABLE itself.
        if (!tableRef.isPublicSchema()) {
            String schemaName = tableRef.schema();
            try {
                jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS \""
                        + schemaName.replace("\"", "\"\"") + "\"");
            } catch (DataAccessException e) {
                throw new StorageException(
                        "Failed to create tenant schema '" + schemaName
                                + "' (check the worker's DB role has CREATE on the target database): "
                                + e.getMessage(), e);
            }
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, schemaName);
            if (exists == null) {
                throw new StorageException(
                        "Tenant schema '" + schemaName + "' was not created and is not visible "
                                + "to the worker's DB role — refusing to create table '"
                                + tableRef.tableName() + "'");
            }
        }

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(qualifiedName).append(" (");
        sql.append("id VARCHAR(36) PRIMARY KEY, ");
        sql.append("created_by VARCHAR(36), ");
        sql.append("updated_by VARCHAR(36), ");
        sql.append("created_at TIMESTAMP NOT NULL, ");
        sql.append("updated_at TIMESTAMP NOT NULL, ");
        sql.append("record_type_id VARCHAR(36)");

        List<String> postCreateStatements = new ArrayList<>();
        List<PendingForeignKey> pendingForeignKeys = new ArrayList<>();

        for (FieldDefinition field : definition.fields()) {
            if (!field.type().hasPhysicalColumn()) {
                continue; // Skip FORMULA, ROLLUP_SUMMARY
            }

            String columnName = getColumnName(definition, field);

            // Skip system columns already defined above (id, created_at, etc.)
            if (SYSTEM_COLUMNS.contains(columnName)) {
                continue;
            }

            String sqlType = mapFieldTypeToSql(field.type(), field);
            sql.append(", ");
            sql.append(quoteIdentifier(columnName)).append(" ").append(sqlType);

            if (!field.nullable()) {
                sql.append(" NOT NULL");
            }

            if (field.unique()) {
                sql.append(" UNIQUE");
            }

            // Companion columns (use resolved column name for consistency)
            if (field.type() == FieldType.CURRENCY) {
                sql.append(", ");
                sql.append(quoteIdentifier(columnName + "_currency_code")).append(" VARCHAR(3)");
            }
            if (field.type() == FieldType.GEOLOCATION) {
                sql.append(", ");
                sql.append(quoteIdentifier(columnName + "_longitude")).append(" DOUBLE PRECISION");
            }

            // Unique index for EXTERNAL_ID
            if (field.type() == FieldType.EXTERNAL_ID) {
                String idxName = buildBoundedIdentifier(
                        "idx_", identifierPart(getBaseTableName(definition)),
                        sanitizeIdentifier(columnName));
                postCreateStatements.add(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " + idxName
                    + " ON " + qualifiedName + "(" + quoteIdentifier(columnName) + ")"
                );
            }

            // HNSW index for VECTOR fields — turns the cosine-distance (<=>) similarity
            // search in semanticSearch() from a flat scan into an approximate-nearest-neighbour
            // lookup. Idempotent (IF NOT EXISTS), so re-initialization is safe.
            if (field.type() == FieldType.VECTOR) {
                String idxName = buildBoundedIdentifier(
                        "hnsw_", identifierPart(getBaseTableName(definition)),
                        sanitizeIdentifier(columnName));
                postCreateStatements.add(buildHnswIndexStatement(idxName, qualifiedName, columnName));
            }

            // FK constraints for LOOKUP and MASTER_DETAIL
            if ((field.type() == FieldType.LOOKUP || field.type() == FieldType.MASTER_DETAIL)
                    && field.referenceConfig() != null) {
                String baseName = getBaseTableName(definition);
                // Raw name: kebab-case targets are legal — TableRef validates and
                // quotes the reference. Only the generated fk NAME must be strict.
                String targetTableName = field.referenceConfig().targetCollection();
                String targetCol = quoteIdentifier(field.referenceConfig().targetField());
                String fkName = buildBoundedIdentifier(
                        "fk_", identifierPart(baseName), sanitizeIdentifier(columnName));
                String onDelete = field.type() == FieldType.MASTER_DETAIL
                        ? "ON DELETE CASCADE" : "ON DELETE SET NULL";

                pendingForeignKeys.add(new PendingForeignKey(
                        fkName, qualifiedName, quoteIdentifier(columnName),
                        tableRef, targetTableName, targetCol, onDelete));
            }
        }

        sql.append(")");

        // The pgvector extension must exist before a vector(N) column can be created. Emit it
        // lazily — only when the collection actually has a VECTOR field — so collections without
        // vectors never require pgvector.
        if (definition.fields().stream().anyMatch(f -> f.type() == FieldType.VECTOR)) {
            SchemaMigrationEngine.ensureVectorExtension(jdbcTemplate, definition.name());
        }

        // Owner-scoped reads filter on the owner column; index it so they don't scan.
        if (definition.ownerField() != null) {
            postCreateStatements.add(buildOwnerIndexStatement(definition, qualifiedName));
        }

        try {
            try {
                jdbcTemplate.execute(sql.toString());
            } catch (DataAccessException e) {
                // PostgreSQL's CREATE TABLE IF NOT EXISTS is not atomic against
                // concurrent CREATEs: two transactions can both pass the existence
                // check, and the loser then fails on the catalog — a unique-violation
                // on pg_type_typname_nsp_index (23505) or "type ... already exists"
                // (42710), depending on where it collides. When multiple worker pods
                // consume the same NATS CollectionChanged event in parallel they hit
                // exactly this race. The losing transaction's CREATE rolls back, but
                // the winner's table is committed and visible, so we can safely
                // continue to reconcileSchema which will fill in any missing columns.
                if (!isConcurrentDdlDuplicate(e)) {
                    throw e;
                }
                log.warn("Concurrent CREATE TABLE race for '{}' (likely another worker pod created it "
                        + "first); treating as success and reconciling schema. Cause: {}",
                        qualifiedName, e.getMostSpecificCause().getMessage());
            }

            // Reconcile schema BEFORE running post-create statements. If the table
            // already existed, CREATE TABLE IF NOT EXISTS was a no-op and columns
            // for any later-added fields are missing. The FK constraint statements
            // in postCreateStatements reference those columns and would fail with
            // "column does not exist" until reconcileSchema fills them in. This
            // happens whenever a NATS UPDATED event reaches a worker pod that
            // hadn't yet registered the collection and falls into the initialize
            // path instead of the migrate path.
            migrationEngine.reconcileSchema(definition, tableRef);

            // Collections born with captureGeo on (imports, promotions) get their geo
            // system columns here; the flag-flip path lives in migrateSchema.
            if (definition.captureGeo() && !definition.systemCollection()) {
                migrationEngine.ensureGeoColumns(definition.name(), qualifiedName);
            }

            for (String stmt : postCreateStatements) {
                // CREATE INDEX IF NOT EXISTS races the same way CREATE TABLE does.
                executeToleratingConcurrentDuplicate(stmt, qualifiedName);
            }

            for (PendingForeignKey fk : pendingForeignKeys) {
                applyForeignKey(fk, definition.name());
            }

            // Record the migration in history
            migrationEngine.recordMigration(definition.name(),
                SchemaMigrationEngine.MigrationType.CREATE_TABLE, sql.toString());

            log.info("Initialized table '{}' for collection '{}'", qualifiedName, definition.name());
        } catch (DataAccessException e) {
            throw new StorageException("Failed to initialize table for collection: " + definition.name(), e);
        }
    }

    /**
     * A LOOKUP/MASTER_DETAIL foreign key whose target table must be located before the
     * constraint can be written. Held until after {@code CREATE TABLE} + schema reconcile,
     * because resolving the target needs a DB round-trip and the referencing column has to
     * exist first.
     *
     * @param fkName the generated (length-bounded, unquoted) constraint name
     * @param sourceTable the schema-qualified table the constraint is added to
     * @param sourceColumn the quoted referencing column
     * @param sourceRef the source table's ref, used to derive the candidate target schema
     * @param targetTableName the target collection's raw (possibly kebab-case) table name
     * @param targetColumn the quoted referenced column, typically {@code "id"}
     * @param onDelete the {@code ON DELETE …} clause
     */
    private record PendingForeignKey(
            String fkName,
            String sourceTable,
            String sourceColumn,
            TableRef sourceRef,
            String targetTableName,
            String targetColumn,
            String onDelete) {
    }

    /**
     * Adds one foreign key, resolving the target table's schema first.
     *
     * <p><b>Schema resolution.</b> The target is <em>not</em> necessarily co-located with the
     * source: a tenant collection may hold a LOOKUP to a <em>system</em> collection, whose
     * Flyway-managed table lives in {@code public}. Assuming the source's schema produced
     * {@code relation "<tenant>.users" does not exist} and aborted initialization for the whole
     * collection. So try the source schema first, then fall back to {@code public}, and skip
     * the constraint with an actionable warning when the target exists in neither — a dangling
     * reference is a metadata problem to fix, not a reason to leave the collection tableless.
     *
     * <p><b>{@code NOT VALID}.</b> The constraint is added without validating rows already in
     * the table. A single orphaned legacy row would otherwise fail the {@code ALTER TABLE} on
     * every worker boot, forever, leaving the column with <em>no</em> referential integrity at
     * all. {@code NOT VALID} enforces the FK for all subsequent inserts and updates
     * immediately; the follow-up {@code VALIDATE CONSTRAINT} then promotes it to fully valid
     * when the existing data is clean, and only warns (naming the table) when it is not.
     */
    private void applyForeignKey(PendingForeignKey fk, String collectionName) {
        TableRef targetRef = resolveTargetTable(fk);
        if (targetRef == null) {
            log.warn("Skipping foreign key '{}' on collection '{}': target table '{}' does not exist "
                    + "in schema '{}' or 'public'. The reference field points at a collection with no "
                    + "physical table — fix or remove the reference; the rest of the collection is "
                    + "initialized normally.",
                    fk.fkName(), collectionName, fk.targetTableName(), fk.sourceRef().schema());
            return;
        }

        // The existence check MUST be scoped to this table. `conname` is unique per table, not
        // per database, and generated FK names are derived from collection + field names — which
        // repeat across tenants (every tenant with an `orders` collection and a `customer` field
        // generates fk_orders_customer). An unqualified `WHERE conname = ...` matched some other
        // tenant's constraint and skipped creating this one, so only the first tenant to
        // initialize ever got its foreign key; every tenant after it silently ran with no
        // referential integrity on that column.
        // The existence check and the ADD are not atomic: a worker pod initializing the same
        // collection concurrently can add the constraint in between, and this one then fails
        // with "constraint ... already exists" (42710). The constraint exists either way.
        executeToleratingConcurrentDuplicate(
                "DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '" + fk.fkName() + "'"
                + " AND conrelid = to_regclass('" + fk.sourceTable() + "')) THEN "
                + "ALTER TABLE " + fk.sourceTable()
                + " ADD CONSTRAINT " + fk.fkName()
                + " FOREIGN KEY (" + fk.sourceColumn() + ")"
                + " REFERENCES " + targetRef.toSql() + "(" + fk.targetColumn() + ") "
                + fk.onDelete() + " NOT VALID; "
                + "END IF; END $$",
                fk.sourceTable());

        try {
            jdbcTemplate.execute("ALTER TABLE " + fk.sourceTable()
                    + " VALIDATE CONSTRAINT " + fk.fkName());
        } catch (DataAccessException e) {
            // Only an integrity violation means orphaned rows; anything else (a missing
            // constraint, a lock timeout) would be misdiagnosed by that advice.
            String cause = e.getMostSpecificCause().getMessage();
            if (e instanceof DataIntegrityViolationException) {
                log.warn("Foreign key '{}' on '{}' is enforced for new writes but could not be "
                        + "validated against existing rows — the table holds orphaned references "
                        + "to '{}'. Clean them up (or null them out), then run: "
                        + "ALTER TABLE {} VALIDATE CONSTRAINT {}. Cause: {}",
                        fk.fkName(), fk.sourceTable(), fk.targetTableName(),
                        fk.sourceTable(), fk.fkName(), cause);
            } else {
                log.warn("Could not validate foreign key '{}' on '{}': {}",
                        fk.fkName(), fk.sourceTable(), cause);
            }
        }
    }

    /**
     * Runs an idempotent DDL statement, treating a lost race against a concurrent creator of the
     * same object as success.
     */
    private void executeToleratingConcurrentDuplicate(String ddl, String table) {
        try {
            jdbcTemplate.execute(ddl);
        } catch (DataAccessException e) {
            if (!isConcurrentDdlDuplicate(e)) {
                throw e;
            }
            log.warn("Concurrent DDL race on '{}' (likely another worker pod created the object "
                    + "first); treating as success. Cause: {}",
                    table, e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * Locates the table a foreign key should reference.
     *
     * <p>A {@code ReferenceConfig} names the target <em>collection</em>, not its table. For tenant
     * collections those are always the same string, but a **system** collection stores in a
     * Flyway-managed table whose name usually differs — {@code users} lives in
     * {@code platform_user}, {@code page-layouts} in {@code page_layout}, and so on for 40-odd
     * others. Taking the collection name as the table name meant any LOOKUP pointing at a system
     * collection resolved to a table that has never existed.
     *
     * <p>Candidates are tried in order:
     * <ol>
     *   <li>the source's own schema under the raw target name — a tenant collection that happens
     *       to share a name with a system one must still win in its own schema;</li>
     *   <li>{@code public} under the system collection's real table name, when the target is a
     *       known system collection;</li>
     *   <li>{@code public} under the raw name, covering system collections whose name already
     *       matches their table and sources that live in public themselves.</li>
     * </ol>
     *
     * @return the first candidate that exists, or {@code null} when none do
     */
    private TableRef resolveTargetTable(PendingForeignKey fk) {
        List<TableRef> candidates = new ArrayList<>();
        if (!fk.sourceRef().isPublicSchema()) {
            candidates.add(TableRef.tenantSchema(fk.sourceRef().schema(), fk.targetTableName()));
        }
        String systemTable = SYSTEM_COLLECTION_TABLES.get(fk.targetTableName());
        if (systemTable != null && !systemTable.equals(fk.targetTableName())) {
            candidates.add(TableRef.publicSchema(systemTable));
        }
        candidates.add(TableRef.publicSchema(fk.targetTableName()));

        for (TableRef candidate : candidates) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL", Boolean.class, candidate.toSql());
            if (Boolean.TRUE.equals(exists)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Maps a FieldType to the corresponding PostgreSQL SQL type.
     *
     * @param type the field type
     * @return the SQL type string
     */
    /**
     * Maps a field's type to its PostgreSQL column type. VECTOR reads its
     * {@code dimension} (default 1536) from {@code fieldTypeConfig}; all other
     * types ignore the {@code field} arg.
     */
    private String mapFieldTypeToSql(FieldType type, FieldDefinition field) {
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
}
