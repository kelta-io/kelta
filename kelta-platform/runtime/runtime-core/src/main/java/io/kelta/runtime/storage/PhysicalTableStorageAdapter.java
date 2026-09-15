package io.kelta.runtime.storage;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.SortField;
import io.kelta.runtime.validation.TypeCoercionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * Storage adapter that maps each collection to a physical PostgreSQL table
 * with columns matching field definitions.
 *
 * <p>When schema-per-tenant isolation is enabled, tenant collections are stored in
 * separate PostgreSQL schemas named by the tenant's slug. System collections remain
 * in the public schema. Schema-qualified table names are used to ensure queries
 * target the correct schema explicitly.
 *
 * <h2>SQL Type Mapping</h2>
 * <ul>
 *   <li>STRING → TEXT</li>
 *   <li>INTEGER → INTEGER</li>
 *   <li>LONG → BIGINT</li>
 *   <li>DOUBLE → DOUBLE PRECISION</li>
 *   <li>BOOLEAN → BOOLEAN</li>
 *   <li>DATE → DATE</li>
 *   <li>DATETIME → TIMESTAMP</li>
 *   <li>JSON → JSONB</li>
 * </ul>
 *
 * <h2>Filter Operators</h2>
 * Supports all filter operators including eq, neq, gt, lt, gte, lte, isnull,
 * contains, starts, ends, icontains, istarts, iends, and ieq.
 *
 * @see StorageAdapter
 * @see SchemaMigrationEngine
 * @since 1.0.0
 */
@Service
public class PhysicalTableStorageAdapter implements StorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(PhysicalTableStorageAdapter.class);

    /** Column names handled as system fields in create/update — skipped in the user-defined field loop. */
    private static final Set<String> SYSTEM_COLUMNS = Set.of(
        "id", "created_at", "updated_at", "created_by", "updated_by", "tenant_id",
        "record_type_id", "created_geo", "updated_geo"
    );

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
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * Creates a new PhysicalTableStorageAdapter.
     *
     * @param jdbcTemplate the JdbcTemplate for database operations
     * @param migrationEngine the schema migration engine for handling schema changes
     * @param objectMapper Jackson ObjectMapper for JSONB value deserialization
     */
    public PhysicalTableStorageAdapter(
            JdbcTemplate jdbcTemplate,
            SchemaMigrationEngine migrationEngine,
            tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrationEngine = migrationEngine;
        this.objectMapper = objectMapper;
    }

    @Override
    public void initializeCollection(CollectionDefinition definition) {
        // System collections use Flyway-managed tables — skip table creation
        if (definition.systemCollection()) {
            log.info("Skipping table creation for system collection '{}' (table '{}' managed by Flyway)",
                    definition.name(), getBaseTableName(definition));
            return;
        }

        TableRef tableRef = getTableRef(definition);
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
        // vectors never require pgvector. A failure here is surfaced with actionable guidance
        // rather than a cryptic "type vector does not exist" on the CREATE TABLE below.
        boolean hasVector = definition.fields().stream()
                .anyMatch(f -> f.type() == FieldType.VECTOR);
        if (hasVector) {
            try {
                jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            } catch (DataAccessException e) {
                throw new StorageException(
                        "Collection '" + definition.name() + "' has a VECTOR field but the pgvector "
                                + "extension could not be enabled. Install pgvector on the database "
                                + "(a pgvector-enabled image, or `CREATE EXTENSION vector` by an admin) "
                                + "and ensure the worker's DB role may use it. Cause: " + e.getMessage(), e);
            }
        }

        try {
            try {
                jdbcTemplate.execute(sql.toString());
            } catch (DuplicateKeyException e) {
                // PostgreSQL's CREATE TABLE IF NOT EXISTS is not atomic against
                // concurrent CREATEs: two transactions can both pass the existence
                // check and then both try to INSERT into pg_type, leaving one
                // with a unique-violation on pg_type_typname_nsp_index (SQLSTATE
                // 23505). When multiple worker pods consume the same NATS
                // CollectionChanged event in parallel they hit exactly this race.
                // The losing transaction's CREATE rolls back, but the winner's
                // table is committed and visible, so we can safely continue to
                // reconcileSchema which will fill in any missing columns.
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
                jdbcTemplate.execute(stmt);
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
        jdbcTemplate.execute(
                "DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '" + fk.fkName() + "'"
                + " AND conrelid = to_regclass('" + fk.sourceTable() + "')) THEN "
                + "ALTER TABLE " + fk.sourceTable()
                + " ADD CONSTRAINT " + fk.fkName()
                + " FOREIGN KEY (" + fk.sourceColumn() + ")"
                + " REFERENCES " + targetRef.toSql() + "(" + fk.targetColumn() + ") "
                + fk.onDelete() + " NOT VALID; "
                + "END IF; END $$");

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
     * Drops the collection's physical table.
     *
     * <p>Mirrors {@link #initializeCollection}: system collections are skipped because Flyway
     * owns those tables, and dropping one would take out platform metadata for every tenant.
     *
     * <p>Deliberately <b>not</b> {@code CASCADE}. A master-detail child table holds a real FK
     * to its parent's table, so a plain {@code DROP} of the parent fails loudly while
     * {@code CASCADE} would silently destroy the child's constraint. Callers should delete
     * child collections first; a blocked drop is logged and leaks the table, which is the
     * pre-existing behaviour and strictly better than unannounced data loss.
     *
     * @param definition the collection whose table should be dropped
     */
    @Override
    public void dropCollection(CollectionDefinition definition) {
        if (definition == null) {
            return;
        }
        if (definition.systemCollection()) {
            log.warn("Refusing to drop table for system collection '{}' — Flyway owns it",
                    definition.name());
            return;
        }

        String qualifiedName = getTableRef(definition).toSql();
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + qualifiedName);
            log.info("Dropped table {} for deleted collection '{}'", qualifiedName, definition.name());
        } catch (RuntimeException e) {
            // The metadata delete has already committed — never throw from here. A dependent
            // object (typically a master-detail child's FK) leaves the table behind; surface
            // it loudly so the leak is actionable rather than invisible.
            log.error("Failed to drop table {} for deleted collection '{}' — the table is now "
                            + "orphaned and must be dropped manually: {}",
                    qualifiedName, definition.name(), e.getMessage());
        }
    }

    @Override
    public void updateCollectionSchema(CollectionDefinition oldDefinition, CollectionDefinition newDefinition) {
        // Delegate schema migration to the migration engine
        TableRef tableRef = getTableRef(newDefinition);
        migrationEngine.migrateSchema(oldDefinition, newDefinition, tableRef);
        log.info("Schema update completed for collection '{}'", newDefinition.name());
    }

    @Override
    public QueryResult query(CollectionDefinition definition, QueryRequest request) {
        TableRef tableRef = getTableRef(definition);
        List<Object> params = new ArrayList<>();

        // Build SELECT clause
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(buildSelectClause(request.fields(), definition));
        sql.append(" FROM ").append(tableRef.toSql());

        // Build WHERE clause for filters
        List<FilterCondition> allFilters = new ArrayList<>();
        if (request.hasFilters()) {
            allFilters.addAll(request.filters());
        }

        if (!allFilters.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(buildWhereClause(allFilters, definition, params));
        }

        // Build ORDER BY clause. Unsorted queries get a deterministic creation-order
        // fallback: without it Postgres returns rows in arbitrary heap order, which
        // (a) makes LIMIT/OFFSET pagination skip or duplicate rows across pages and
        // (b) leaks nondeterministic ordering into JSON:API include resolution
        // (e.g. ?include=fields powering field lists in the layout editor).
        if (request.hasSorting()) {
            sql.append(" ORDER BY ");
            sql.append(buildOrderByClause(request.sorting(), definition));
        } else {
            sql.append(" ORDER BY created_at, id");
        }

        // Build LIMIT and OFFSET for pagination
        Pagination pagination = request.pagination();
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pagination.pageSize());
        params.add(pagination.offset());

        try {
            // Execute query
            List<Map<String, Object>> data = jdbcTemplate.queryForList(sql.toString(), params.toArray());

            // Remap column names to field names for system collections
            remapColumnNames(definition, data);

            // Reconstruct companion column values into structured fields
            reconstructCompanionColumns(definition, data);

            // Get total count
            long totalCount = getTotalCount(tableRef, allFilters, definition);

            return QueryResult.of(data, totalCount, pagination);
        } catch (DataAccessException e) {
            throw new StorageException("Failed to query collection: " + definition.name(), e);
        }
    }

    @Override
    public List<Map<String, Object>> semanticSearch(CollectionDefinition definition,
                                                     String vectorColumn,
                                                     String queryVectorLiteral,
                                                     int limit,
                                                     List<FilterCondition> filters) {
        TableRef tableRef = getTableRef(definition);
        String vectorIdent = quoteIdentifier(vectorColumn);
        List<Object> params = new ArrayList<>();

        // Cosine distance (<=>) to the query vector; bound first so its '?' precedes any filter '?'.
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(buildSelectClause(null, definition));
        sql.append(", (").append(vectorIdent).append(" <=> CAST(? AS vector)) AS _distance");
        params.add(queryVectorLiteral);
        sql.append(" FROM ").append(tableRef.toSql());
        sql.append(" WHERE ").append(vectorIdent).append(" IS NOT NULL");
        if (filters != null && !filters.isEmpty()) {
            sql.append(" AND ").append(buildWhereClause(filters, definition, params));
        }
        sql.append(" ORDER BY _distance ASC LIMIT ?");
        params.add(limit);

        try {
            List<Map<String, Object>> data = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            remapColumnNames(definition, data);
            reconstructCompanionColumns(definition, data);
            return data;
        } catch (DataAccessException e) {
            throw new StorageException("Failed semantic search on collection: " + definition.name(), e);
        }
    }

    @Override
    public Map<String, Object> aggregate(CollectionDefinition definition,
                                          List<FilterCondition> filters,
                                          List<AggregationSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return Map.of();
        }

        TableRef tableRef = getTableRef(definition);
        List<Object> params = new ArrayList<>();

        StringBuilder selectList = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            AggregationSpec spec = specs.get(i);
            if (i > 0) selectList.append(", ");
            String aliasIdent = quoteIdentifier(spec.alias());
            if ("COUNT".equals(spec.function())) {
                selectList.append("COUNT(*) AS ").append(aliasIdent);
            } else {
                String columnName = quoteIdentifier(resolveColumnName(definition, spec.field()));
                selectList.append(spec.function()).append("(").append(columnName).append(") AS ").append(aliasIdent);
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(selectList);
        sql.append(" FROM ").append(tableRef.toSql());

        if (filters != null && !filters.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(buildWhereClause(filters, definition, params));
        }

        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(sql.toString(), params.toArray());
            Map<String, Object> caseInsensitive = new HashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                caseInsensitive.put(e.getKey().toLowerCase(), e.getValue());
            }
            Map<String, Object> result = new HashMap<>();
            for (AggregationSpec spec : specs) {
                Object value = caseInsensitive.get(spec.alias().toLowerCase());
                result.put(spec.alias(), normalizeAggregateValue(spec.function(), value));
            }
            return result;
        } catch (DataAccessException e) {
            throw new StorageException("Failed to aggregate collection: " + definition.name(), e);
        }
    }

    private Object normalizeAggregateValue(String function, Object raw) {
        if ("COUNT".equals(function)) {
            if (raw == null) return 0L;
            return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
        }
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        return raw;
    }

    @Override
    public Optional<Map<String, Object>> getById(CollectionDefinition definition, String id) {
        TableRef tableRef = getTableRef(definition);
        String sql = "SELECT * FROM " + tableRef.toSql() + " WHERE id = ?";

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, id);
            if (results.isEmpty()) {
                return Optional.empty();
            }
            // Remap column names to field names for system collections
            remapColumnNames(definition, results);
            // Reconstruct companion column values
            reconstructCompanionColumns(definition, results);
            return Optional.of(results.get(0));
        } catch (DataAccessException e) {
            throw new StorageException("Failed to get record by ID from collection: " + definition.name(), e);
        }
    }

    @Override
    public Map<String, Object> create(CollectionDefinition definition, Map<String, Object> data) {
        TableRef tableRef = getTableRef(definition);

        // Surface payload keys that don't map to a known field — these are
        // silently dropped by the INSERT loop below. The most common cause is
        // an in-flight schema propagation race where this pod's in-memory
        // CollectionDefinition has not yet been refreshed with the latest
        // field set (rollup_summary tests have caught this). A WARN log keeps
        // the drop visible in CI artifacts so the race is debuggable.
        warnOnUnknownPayloadKeys(definition, data);

        // Build column names, placeholders, and values.
        // JSONB columns need ?::jsonb placeholders so PostgreSQL accepts the
        // value as JSONB instead of VARCHAR.
        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        // Add system fields
        columns.add("id");
        placeholders.add("?");
        values.add(data.get("id"));
        columns.add("created_by");
        placeholders.add("?");
        values.add(data.get("createdBy"));
        columns.add("updated_by");
        placeholders.add("?");
        values.add(data.get("updatedBy"));
        // Request-origin geo stamps (captureGeo collections only — the router puts
        // these keys in the map; absent keys leave the columns out of the INSERT
        // entirely, which also keeps Flyway-managed system tables untouched).
        if (data.containsKey("createdGeo")) {
            columns.add("created_geo");
            placeholders.add("?::jsonb");
            values.add(convertValueForStorage(data.get("createdGeo"), FieldType.JSON));
        }
        if (data.containsKey("updatedGeo")) {
            columns.add("updated_geo");
            placeholders.add("?::jsonb");
            values.add(convertValueForStorage(data.get("updatedGeo"), FieldType.JSON));
        }
        columns.add("created_at");
        placeholders.add("?");
        values.add(convertValueForStorage(data.get("createdAt"), FieldType.DATETIME));
        columns.add("updated_at");
        placeholders.add("?");
        values.add(convertValueForStorage(data.get("updatedAt"), FieldType.DATETIME));

        // For tenant-scoped system collections, add tenant_id
        if (definition.systemCollection() && definition.tenantScoped() && data.containsKey("tenantId")) {
            columns.add("tenant_id");
            placeholders.add("?");
            values.add(data.get("tenantId"));
        }

        // Record type ID (system column on user-defined collection tables)
        if (!definition.systemCollection() && data.containsKey("recordTypeId")) {
            columns.add("record_type_id");
            placeholders.add("?");
            values.add(data.get("recordTypeId"));
        }

        // Add user-defined fields
        for (FieldDefinition field : definition.fields()) {
            if (!field.type().hasPhysicalColumn()) {
                continue; // Skip FORMULA, ROLLUP_SUMMARY
            }
            if (data.containsKey(field.name())) {
                String columnName = getColumnName(definition, field);
                if (SYSTEM_COLUMNS.contains(columnName)) {
                    continue; // Already handled as a system field above
                }
                columns.add(quoteIdentifier(columnName));
                placeholders.add(castPlaceholder(field.type()));
                values.add(convertValueForStorage(data.get(field.name()), field.type()));

                // Handle companion columns
                if (field.type() == FieldType.CURRENCY && data.containsKey(field.name() + "_currency_code")) {
                    columns.add(quoteIdentifier(columnName + "_currency_code"));
                    placeholders.add("?");
                    values.add(data.get(field.name() + "_currency_code"));
                }
                if (field.type() == FieldType.GEOLOCATION && data.get(field.name()) instanceof Map<?,?> geo) {
                    columns.add(quoteIdentifier(columnName + "_longitude"));
                    placeholders.add("?");
                    values.add(((Number) geo.get("longitude")).doubleValue());
                }
            }
        }

        String columnList = String.join(", ", columns);
        String placeholderList = String.join(", ", placeholders);

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
            tableRef.toSql(), columnList, placeholderList);

        try {
            jdbcTemplate.update(sql, values.toArray());
            log.debug("Created record with ID '{}' in collection '{}'", data.get("id"), definition.name());
            // Read back from DB to capture DB-level defaults (e.g., active = true),
            // then merge into the original data so callers see the complete record.
            getById(definition, (String) data.get("id")).ifPresent(readBack -> {
                for (Map.Entry<String, Object> entry : readBack.entrySet()) {
                    data.putIfAbsent(entry.getKey(), entry.getValue());
                }
            });
            return data;
        } catch (DuplicateKeyException e) {
            // Determine which field caused the violation
            String fieldName = detectUniqueViolationField(definition, data, e);
            throw new UniqueConstraintViolationException(
                definition.name(), fieldName, data.get(fieldName), e);
        } catch (DataAccessException e) {
            throw new StorageException("Failed to create record in collection: " + definition.name(), e);
        }
    }

    private static final Set<String> PAYLOAD_SYSTEM_KEYS = Set.of(
        "id", "createdAt", "updatedAt", "createdBy", "updatedBy",
        "tenantId", "recordTypeId", "createdGeo", "updatedGeo"
    );

    private void warnOnUnknownPayloadKeys(CollectionDefinition definition, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        Set<String> knownFieldNames = new HashSet<>();
        for (FieldDefinition field : definition.fields()) {
            knownFieldNames.add(field.name());
            if (field.type() == FieldType.CURRENCY) {
                knownFieldNames.add(field.name() + "_currency_code");
            }
            if (field.type() == FieldType.GEOLOCATION) {
                knownFieldNames.add(field.name() + "_longitude");
                knownFieldNames.add(field.name() + "_latitude");
            }
        }
        for (String key : data.keySet()) {
            if (PAYLOAD_SYSTEM_KEYS.contains(key) || knownFieldNames.contains(key)) {
                continue;
            }
            log.warn("Dropping unknown payload key '{}' on INSERT into collection '{}' — "
                    + "field not present in in-memory CollectionDefinition (likely a "
                    + "schema-propagation race). Known field count: {}.",
                    key, definition.name(), knownFieldNames.size());
        }
    }

    @Override
    public Optional<Map<String, Object>> update(CollectionDefinition definition, String id, Map<String, Object> data) {
        TableRef tableRef = getTableRef(definition);

        // Check if record exists
        Optional<Map<String, Object>> existing = getById(definition, id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        // Build SET clause
        List<String> setClauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        // Always update updated_at
        setClauses.add("updated_at = ?");
        values.add(convertValueForStorage(data.get("updatedAt"), FieldType.DATETIME));

        // Update audit field
        if (data.containsKey("updatedBy")) {
            setClauses.add("updated_by = ?");
            values.add(data.get("updatedBy"));
        }

        // Request-origin geo stamp (captureGeo collections only)
        if (data.containsKey("updatedGeo")) {
            setClauses.add("updated_geo = ?::jsonb");
            values.add(convertValueForStorage(data.get("updatedGeo"), FieldType.JSON));
        }

        // Update record type ID
        if (!definition.systemCollection() && data.containsKey("recordTypeId")) {
            setClauses.add("record_type_id = ?");
            values.add(data.get("recordTypeId"));
        }

        // Update user-defined fields
        for (FieldDefinition field : definition.fields()) {
            if (data.containsKey(field.name())) {
                String columnName = getColumnName(definition, field);
                if (SYSTEM_COLUMNS.contains(columnName)) {
                    continue; // Already handled as a system field above
                }
                setClauses.add(quoteIdentifier(columnName) + " = " + castPlaceholder(field.type()));
                values.add(convertValueForStorage(data.get(field.name()), field.type()));
            }
        }

        // Add ID for WHERE clause
        values.add(id);

        String sql = String.format("UPDATE %s SET %s WHERE id = ?",
            tableRef.toSql(), String.join(", ", setClauses));

        try {
            int rowsAffected = jdbcTemplate.update(sql, values.toArray());
            if (rowsAffected == 0) {
                return Optional.empty();
            }

            log.debug("Updated record with ID '{}' in collection '{}'", id, definition.name());

            // Return the updated record
            return getById(definition, id);
        } catch (DuplicateKeyException e) {
            String fieldName = detectUniqueViolationField(definition, data, e);
            throw new UniqueConstraintViolationException(
                definition.name(), fieldName, data.get(fieldName), e);
        } catch (DataAccessException e) {
            throw new StorageException("Failed to update record in collection: " + definition.name(), e);
        }
    }

    @Override
    public boolean delete(CollectionDefinition definition, String id) {
        TableRef tableRef = getTableRef(definition);

        // Log cascade-affected MASTER_DETAIL child records before delete
        for (FieldDefinition field : definition.fields()) {
            if (field.type() == FieldType.MASTER_DETAIL && field.referenceConfig() != null
                    && field.referenceConfig().isMasterDetail()) {
                log.info("Cascade delete: record '{}' in '{}' will cascade via MASTER_DETAIL field '{}'",
                        id, definition.name(), field.name());
            }
        }

        String sql = "DELETE FROM " + tableRef.toSql() + " WHERE id = ?";

        try {
            int rowsAffected = jdbcTemplate.update(sql, id);
            if (rowsAffected > 0) {
                log.debug("Deleted record with ID '{}' from collection '{}'", id, definition.name());
            }
            return rowsAffected > 0;
        } catch (DataAccessException e) {
            if (isForeignKeyViolation(e)) {
                throw new ReferencedRecordConflictException(definition.name(), id, e);
            }
            throw new StorageException("Failed to delete record from collection: " + definition.name(), e);
        }
    }

    /**
     * True when the failure is a Postgres foreign-key violation (SQL state
     * 23503) — a restricting reference, not a storage fault. Callers translate
     * it to 409 instead of 500.
     */
    static boolean isForeignKeyViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isUnique(CollectionDefinition definition, String fieldName, Object value, String excludeId) {
        TableRef tableRef = getTableRef(definition);
        String columnName = resolveColumnName(definition, fieldName);

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
        sql.append(tableRef.toSql());
        sql.append(" WHERE ").append(quoteIdentifier(columnName)).append(" = ?");

        List<Object> params = new ArrayList<>();
        params.add(value);

        // For tenant-scoped system collections, scope the uniqueness check to the current tenant.
        // Non-system collections use schema-per-tenant isolation and have no tenant_id column.
        if (definition.systemCollection() && definition.tenantScoped()) {
            String tenantId = io.kelta.runtime.context.TenantContext.get();
            if (tenantId != null && !tenantId.isBlank()) {
                sql.append(" AND tenant_id = ?");
                params.add(tenantId);
            }
        }

        if (excludeId != null) {
            sql.append(" AND id != ?");
            params.add(excludeId);
        }

        try {
            Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
            return count == null || count == 0;
        } catch (DataAccessException e) {
            throw new StorageException("Failed to check uniqueness for field: " + fieldName, e);
        }
    }

    @Override
    public int clearVectorColumn(CollectionDefinition definition, String fieldName) {
        FieldDefinition field = definition.fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst().orElse(null);
        if (field == null || field.type() != FieldType.VECTOR) {
            return 0;
        }
        TableRef tableRef = getTableRef(definition);
        String col = quoteIdentifier(resolveColumnName(definition, fieldName));

        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(tableRef.toSql());
        sql.append(" SET ").append(col).append(" = NULL WHERE ").append(col).append(" IS NOT NULL");

        List<Object> params = new ArrayList<>();
        // User collections use schema-per-tenant isolation (no tenant_id column); the rare
        // tenant-scoped system collection needs an explicit tenant filter (mirrors isUnique).
        if (definition.systemCollection() && definition.tenantScoped()) {
            String tenantId = io.kelta.runtime.context.TenantContext.get();
            if (tenantId != null && !tenantId.isBlank()) {
                sql.append(" AND tenant_id = ?");
                params.add(tenantId);
            }
        }

        try {
            int cleared = jdbcTemplate.update(sql.toString(), params.toArray());
            if (cleared > 0) {
                log.info("Cleared {} stale vector value(s) in {}.{}", cleared, definition.name(), fieldName);
            }
            return cleared;
        } catch (DataAccessException e) {
            throw new StorageException("Failed to clear vector column " + fieldName
                    + " in collection: " + definition.name(), e);
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Resolves the table reference for a collection, applying schema-per-tenant
     * isolation when enabled.
     *
     * <p>System collections always resolve to the public schema. Tenant collections
     * resolve to the tenant's schema (named by slug) when schema-per-tenant is enabled,
     * or to the public schema when disabled.
     *
     * @param definition the collection definition
     * @return the resolved table reference
     */
    TableRef getTableRef(CollectionDefinition definition) {
        String tableName = getBaseTableName(definition);

        // System collections always in public schema
        if (definition.systemCollection()) {
            return TableRef.publicSchema(tableName);
        }

        // Non-system (tenant) collections use the tenant's schema
        String tenantSlug = TenantContext.getSlug();
        if (tenantSlug != null && !tenantSlug.isBlank()) {
            return TableRef.tenantSchema(tenantSlug, tableName);
        }

        // Fallback to public schema (e.g., internal operations without tenant context)
        return TableRef.publicSchema(tableName);
    }

    /**
     * Gets the base table name for a collection (without schema qualification).
     *
     * @param definition the collection definition
     * @return the base table name
     */
    static String getBaseTableName(CollectionDefinition definition) {
        if (definition.storageConfig() != null && definition.storageConfig().tableName() != null) {
            return definition.storageConfig().tableName();
        }
        return definition.name();
    }

    /**
     * Builds the pgvector HNSW index DDL for a {@code VECTOR} column, using the cosine operator
     * class so the index serves the {@code <=>} distance used by {@link #semanticSearch}.
     *
     * @param idxName       the (already length-bounded) index name
     * @param qualifiedName the schema-qualified table name
     * @param column        the vector column name (sanitized here)
     * @return a {@code CREATE INDEX IF NOT EXISTS ... USING hnsw (...)} statement
     */
    static String buildHnswIndexStatement(String idxName, String qualifiedName, String column) {
        return "CREATE INDEX IF NOT EXISTS " + idxName
                + " ON " + qualifiedName
                + " USING hnsw (" + quoteIdentifier(column) + " vector_cosine_ops)";
    }

    /**
     * The bind placeholder for a field's column, casting where PostgreSQL needs it: {@code ?::jsonb}
     * for JSON/ARRAY (JSONB columns), {@code ?::vector} for VECTOR (pgvector parses the text
     * literal), plain {@code ?} otherwise.
     */
    static String castPlaceholder(FieldType type) {
        return switch (type) {
            case JSON, ARRAY -> "?::jsonb";
            case VECTOR -> "?::vector";
            default -> "?";
        };
    }

    /**
     * Converts a camelCase string to snake_case.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "firstName"} → {@code "first_name"}</li>
     *   <li>{@code "emailAddress"} → {@code "email_address"}</li>
     *   <li>{@code "XMLParser"} → {@code "xml_parser"}</li>
     *   <li>{@code "name"} → {@code "name"}</li>
     * </ul>
     *
     * @param camelCase the camelCase string
     * @return the snake_case equivalent
     */
    public static String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isBlank()) {
            return camelCase;
        }
        return camelCase
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .toLowerCase();
    }

    /**
     * Sanitizes an identifier (table name or column name) to prevent SQL injection.
     * Only allows alphanumeric characters and underscores.
     *
     * @param identifier the identifier to sanitize
     * @return the sanitized identifier
     */
    static String sanitizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Identifier cannot be null or blank");
        }
        // Only allow alphanumeric characters and underscores
        if (!identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid identifier: " + identifier);
        }
        return identifier;
    }

    /**
     * Sanitizes a column identifier and wraps it in double quotes for use as a column
     * <em>reference</em> in DDL or DML.
     *
     * <p>Without the quotes, a field whose name collides with a PostgreSQL reserved word
     * ({@code user}, {@code order}, {@code group}, {@code default}, …) produces
     * {@code syntax error at or near "user"} — which aborts {@code CREATE TABLE} for the
     * whole collection, so it never gets a table and never reconciles. Column names always
     * arrive lower-cased (via {@link #toSnakeCase} or an explicit {@code columnName}
     * mapping), so quoting is semantically identical to the previous bare rendering for
     * every non-reserved name — it only stops the parser from claiming the reserved ones.
     *
     * <p>Use {@link #sanitizeIdentifier} instead for generated constraint/index
     * <em>names</em>: those are matched against {@code pg_constraint.conname} as string
     * literals and must stay unquoted.
     *
     * @param identifier the column name to sanitize and quote
     * @return the double-quoted identifier, e.g. {@code "user"}
     */
    static String quoteIdentifier(String identifier) {
        return "\"" + sanitizeIdentifier(identifier) + "\"";
    }

    /**
     * Builds an identifier <em>part</em> for a generated index/constraint name from a
     * logical name that may legally contain hyphens (kebab-case collection names like
     * {@code program-translations}). Hyphens become underscores before strict
     * sanitization — generated names are embedded unquoted in DDL, so they must be
     * strictly alphanumeric, while the table itself keeps its hyphenated name via
     * {@link TableRef} quoting. Never use this for a table or column <em>reference</em>.
     */
    static String identifierPart(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Identifier cannot be null or blank");
        }
        return sanitizeIdentifier(name.replace('-', '_'));
    }

    /** PostgreSQL's identifier length limit. Longer names are silently truncated. */
    private static final int PG_IDENT_MAX_LEN = 63;

    /**
     * Builds a deterministic identifier of the form
     * {@code prefix + baseName + "_" + suffix}, rewriting to
     * {@code prefix + trunc + "_" + crc} when the plain join would exceed
     * PostgreSQL's 63-char identifier limit.
     *
     * <p>Without this, two FK constraints on long collection+field pairs would
     * truncate to identical server-side names — the first {@code IF NOT EXISTS}
     * check would match the wrong constraint and the second FK would silently
     * never be created. Using CRC32 of the full joined name as a stable 8-hex
     * suffix guarantees uniqueness while keeping the result recognisable.
     */
    static String buildBoundedIdentifier(String prefix, String baseName, String suffix) {
        String full = prefix + baseName + "_" + suffix;
        if (full.length() <= PG_IDENT_MAX_LEN) {
            return full;
        }
        CRC32 crc = new CRC32();
        crc.update((baseName + "_" + suffix).getBytes(StandardCharsets.UTF_8));
        String hashSuffix = String.format("_%08x", crc.getValue());
        // Budget: prefix + truncated-baseName + hashSuffix, exactly 63 chars.
        int budget = PG_IDENT_MAX_LEN - prefix.length() - hashSuffix.length();
        String trimmedBase = baseName.length() <= budget
                ? baseName
                : baseName.substring(0, Math.max(0, budget));
        return prefix + trimmedBase + hashSuffix;
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

    /**
     * Builds the SELECT clause for a query.
     *
     * @param fields the requested fields (empty means all fields)
     * @param definition the collection definition
     * @return the SELECT clause
     */
    private String buildSelectClause(List<String> fields, CollectionDefinition definition) {
        if (fields == null || fields.isEmpty()) {
            return "*";
        }

        // Always include id, created_at, updated_at, created_by, updated_by
        List<String> selectFields = new ArrayList<>();
        selectFields.add("id");
        selectFields.add("created_at");
        selectFields.add("updated_at");
        selectFields.add("created_by");
        selectFields.add("updated_by");
        // Geo columns exist only on captureGeo collections (base DDL for new tables,
        // lazy ALTER for existing ones) — selecting them elsewhere would 42703.
        if (definition.captureGeo()) {
            selectFields.add("created_geo");
            selectFields.add("updated_geo");
        }

        for (String field : fields) {
            String columnName = resolveColumnName(definition, field);
            // Dedupe against the bare system-column names added above, but emit the
            // quoted form so reserved-word columns (user, order, …) parse.
            if (!selectFields.contains(columnName)) {
                selectFields.add(quoteIdentifier(columnName));
            }
        }

        return String.join(", ", selectFields);
    }

    /**
     * Builds the WHERE clause from filter conditions.
     *
     * @param filters the filter conditions
     * @param definition the collection definition (used for column name mapping)
     * @param params the parameter list to populate
     * @return the WHERE clause (without the WHERE keyword)
     */
    private String buildWhereClause(List<FilterCondition> filters, CollectionDefinition definition,
                                     List<Object> params) {
        return filters.stream()
            .map(filter -> buildFilterCondition(filter, definition, params))
            .collect(Collectors.joining(" AND "));
    }

    /**
     * Builds a single filter condition SQL fragment.
     *
     * @param filter the filter condition
     * @param definition the collection definition (used for column name mapping)
     * @param params the parameter list to populate
     * @return the SQL fragment for this filter
     */
    private String buildFilterCondition(FilterCondition filter, CollectionDefinition definition,
                                         List<Object> params) {
        String fieldName = quoteIdentifier(resolveColumnName(definition, filter.fieldName()));
        FilterOperator operator = filter.operator();
        Object value = filter.value();

        // Coerce string filter values to match the field's database type (e.g., "false" → Boolean.FALSE).
        // For IN/ANY the value is a Collection<String>; coerce each element so UUID columns receive
        // java.util.UUID rather than the raw string.
        FieldDefinition fieldDef = definition.getField(filter.fieldName());
        if (fieldDef != null) {
            if (value instanceof String) {
                value = TypeCoercionService.coerceValue(value, fieldDef.type());
            } else if (value instanceof Collection<?> coll) {
                List<Object> coerced = new ArrayList<>(coll.size());
                for (Object element : coll) {
                    coerced.add(element instanceof String s
                            ? TypeCoercionService.coerceValue(s, fieldDef.type())
                            : element);
                }
                value = coerced;
            }
        }

        // Temporal columns must bind as java.sql.Timestamp / java.sql.Date: pgjdbc cannot
        // infer a SQL type for the java.time.Instant that coercion produces for DATETIME
        // fields, and the system audit columns (createdAt/updatedAt) have no FieldDefinition,
        // so their ISO-8601 strings bound as varchar — which Postgres refuses to compare with
        // a timestamp column. Both surfaced as 500s on every dashboard time range.
        FieldType temporal = temporalType(fieldDef, filter.fieldName());
        if (temporal != null && COMPARISON_OPERATORS.contains(operator)) {
            if (value instanceof Collection<?> coll) {
                List<Object> bound = new ArrayList<>(coll.size());
                for (Object element : coll) {
                    bound.add(toTemporalParam(element, temporal, filter.fieldName()));
                }
                value = bound;
            } else {
                value = toTemporalParam(value, temporal, filter.fieldName());
            }
        }

        // Detect array-backed columns (Postgres TEXT[]) so we emit ANY(...) /
        // <> ALL(...) / unnest+ILIKE instead of scalar operators that 500 the
        // gateway on JSONB-coerced arrays. Currently only MULTI_PICKLIST maps
        // to TEXT[]; the legacy FieldType.ARRAY value lands in JSONB so it is
        // intentionally NOT included here.
        boolean isArrayColumn = fieldDef != null
            && fieldDef.type() == FieldType.MULTI_PICKLIST;

        return switch (operator) {
            case EQ -> {
                params.add(value);
                yield isArrayColumn
                    ? "? = ANY(" + fieldName + ")"
                    : fieldName + " = ?";
            }
            case NEQ -> {
                params.add(value);
                yield isArrayColumn
                    ? "? <> ALL(" + fieldName + ")"
                    : fieldName + " != ?";
            }
            case GT -> {
                params.add(value);
                yield fieldName + " > ?";
            }
            case LT -> {
                params.add(value);
                yield fieldName + " < ?";
            }
            case GTE -> {
                params.add(value);
                yield fieldName + " >= ?";
            }
            case LTE -> {
                params.add(value);
                yield fieldName + " <= ?";
            }
            case ISNULL -> {
                // value is a boolean indicating whether to check for null or not null
                boolean isNull = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(value.toString());
                yield isNull ? fieldName + " IS NULL" : fieldName + " IS NOT NULL";
            }
            case CONTAINS -> {
                if (isArrayColumn) {
                    // Array-contains: exact element match. Use ANY() so the
                    // index on the array column can be considered. For
                    // substring search across elements, use ICONTAINS.
                    params.add(value);
                    yield "? = ANY(" + fieldName + ")";
                }
                params.add("%" + value + "%");
                yield fieldName + " LIKE ?";
            }
            case STARTS -> {
                params.add(value + "%");
                yield fieldName + " LIKE ?";
            }
            case ENDS -> {
                params.add("%" + value);
                yield fieldName + " LIKE ?";
            }
            case ICONTAINS -> {
                if (isArrayColumn) {
                    // Case-insensitive element match across the array.
                    params.add(value.toString().toLowerCase());
                    yield "EXISTS (SELECT 1 FROM unnest(" + fieldName
                        + ") AS elt WHERE LOWER(elt) = ?)";
                }
                params.add("%" + value.toString().toLowerCase() + "%");
                yield "LOWER(" + fieldName + ") LIKE ?";
            }
            case ISTARTS -> {
                params.add(value.toString().toLowerCase() + "%");
                yield "LOWER(" + fieldName + ") LIKE ?";
            }
            case IENDS -> {
                params.add("%" + value.toString().toLowerCase());
                yield "LOWER(" + fieldName + ") LIKE ?";
            }
            case IEQ -> {
                params.add(value.toString().toLowerCase());
                yield "LOWER(" + fieldName + ") = ?";
            }
            case IN -> {
                if (value instanceof Collection<?> coll) {
                    if (coll.isEmpty()) {
                        yield "1 = 0"; // always false for empty IN list
                    }
                    String ph = coll.stream()
                            .map(v -> {
                                params.add(v);
                                return "?";
                            })
                            .collect(Collectors.joining(", "));
                    yield fieldName + " IN (" + ph + ")";
                } else {
                    // Single value fallback
                    params.add(value);
                    yield fieldName + " = ?";
                }
            }
        };
    }

    /** Operators whose value is compared against the column and therefore must bind with its type. */
    private static final java.util.Set<FilterOperator> COMPARISON_OPERATORS = java.util.EnumSet.of(
        FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.GT, FilterOperator.GTE,
        FilterOperator.LT, FilterOperator.LTE, FilterOperator.IN);

    /**
     * The temporal type a filter must bind with: the field's own DATE/DATETIME type, or DATETIME
     * for the system audit timestamps, which have no FieldDefinition on user collections.
     *
     * @return DATE, DATETIME, or null when the column is not temporal
     */
    private static FieldType temporalType(FieldDefinition fieldDef, String fieldName) {
        if (fieldDef != null) {
            return fieldDef.type() == FieldType.DATE || fieldDef.type() == FieldType.DATETIME
                ? fieldDef.type() : null;
        }
        return switch (fieldName) {
            case "createdAt", "updatedAt" -> FieldType.DATETIME;
            default -> null;
        };
    }

    /**
     * Converts one filter value for a temporal column into the JDBC type the write path uses
     * ({@link #convertValueForStorage}), so reads and writes bind identically.
     *
     * @throws InvalidFilterException when the value is not an ISO-8601 date / date-time (→ 400,
     *         instead of the database rejecting the comparison with a 500)
     */
    private Object toTemporalParam(Object value, FieldType temporal, String fieldName) {
        if (value == null) {
            return null;
        }
        Object bound = convertValueForStorage(value, temporal);
        if (bound instanceof java.sql.Timestamp || bound instanceof java.sql.Date) {
            return bound;
        }
        throw new InvalidFilterException(fieldName, "expected an ISO-8601 "
            + (temporal == FieldType.DATE ? "date (yyyy-MM-dd)" : "date-time (e.g. 2026-01-01T00:00:00Z)")
            + ", got '" + value + "'");
    }

    /**
     * Builds the ORDER BY clause from sort fields.
     *
     * @param sorting the sort fields
     * @param definition the collection definition (used for column name mapping)
     * @return the ORDER BY clause (without the ORDER BY keyword)
     */
    private String buildOrderByClause(List<SortField> sorting, CollectionDefinition definition) {
        return sorting.stream()
            .map(sort -> quoteIdentifier(resolveColumnName(definition, sort.fieldName()))
                    + " " + sort.direction().name())
            .collect(Collectors.joining(", "));
    }

    /**
     * Gets the total count of records matching the filter conditions.
     *
     * @param tableRef the table reference
     * @param filters the filter conditions
     * @param definition the collection definition (used for column name mapping)
     * @return the total count
     */
    private long getTotalCount(TableRef tableRef, List<FilterCondition> filters,
                                CollectionDefinition definition) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
        sql.append(tableRef.toSql());

        List<Object> params = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(buildWhereClause(filters, definition, params));
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    /**
     * Converts a value for storage based on the field type.
     *
     * @param value the value to convert
     * @param type the field type
     * @return the converted value
     */
    Object convertValueForStorage(Object value, FieldType type) { // package-private for tests
        if (value == null) {
            return null;
        }

        return switch (type) {
            case JSON, ARRAY -> {
                // Render the value as JSON text for the ?::jsonb bind. This must
                // cover scalars too: a bare string like "en" is not valid JSONB
                // input, but "\"en\"" is.
                try {
                    tools.jackson.databind.ObjectMapper mapper =
                        new tools.jackson.databind.ObjectMapper();
                    yield mapper.writeValueAsString(value);
                } catch (Exception e) {
                    throw new StorageException("Failed to convert value to JSON", e);
                }
            }
            case DATE, DATETIME -> {
                // Handle Instant conversion
                if (value instanceof java.time.Instant instant) {
                    yield java.sql.Timestamp.from(instant);
                }
                // Handle LocalDate conversion (for DATE fields)
                if (value instanceof java.time.LocalDate localDate) {
                    yield java.sql.Date.valueOf(localDate);
                }
                // Handle LocalDateTime conversion
                if (value instanceof java.time.LocalDateTime localDateTime) {
                    yield java.sql.Timestamp.valueOf(localDateTime);
                }
                // Safety net: parse String if coercion was bypassed
                if (value instanceof String str) {
                    String trimmed = str.trim();
                    try {
                        yield java.sql.Timestamp.from(java.time.Instant.parse(trimmed));
                    } catch (java.time.format.DateTimeParseException e) {
                        // Try LocalDateTime
                    }
                    try {
                        java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(trimmed);
                        yield java.sql.Timestamp.from(ldt.toInstant(java.time.ZoneOffset.UTC));
                    } catch (java.time.format.DateTimeParseException e) {
                        // Try LocalDate
                    }
                    try {
                        java.time.LocalDate ld = java.time.LocalDate.parse(trimmed);
                        if (type == FieldType.DATE) {
                            yield java.sql.Date.valueOf(ld);
                        }
                        yield java.sql.Timestamp.from(ld.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
                    } catch (java.time.format.DateTimeParseException e) {
                        // Cannot parse — fall through and let JDBC handle it
                    }
                }
                yield value;
            }
            case MULTI_PICKLIST -> {
                if (value instanceof List<?> list) {
                    yield list.toArray(new String[0]);
                }
                yield value;
            }
            case GEOLOCATION -> {
                // Value is Map with latitude/longitude; store latitude in primary column
                if (value instanceof Map<?,?> geo) {
                    yield ((Number) geo.get("latitude")).doubleValue();
                }
                yield value;
            }
            case VECTOR -> {
                // pgvector accepts the text literal "[v1,v2,...]" (the column placeholder is
                // bound with ?::vector). Accept a pre-rendered string, a numeric list (JSON:API
                // array), or a float[] (e.g. from an EmbeddingService).
                if (value instanceof String) {
                    yield value;
                }
                if (value instanceof float[] floats) {
                    yield io.kelta.runtime.embedding.EmbeddingService.toVectorLiteral(floats);
                }
                if (value instanceof List<?> list) {
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(((Number) list.get(i)).floatValue());
                    }
                    yield sb.append(']').toString();
                }
                yield value;
            }
            default -> value;
        };
    }

    /**
     * Gets the effective column name for a field within a collection.
     * For system collections, fields may have a different physical column name
     * (e.g., API field "firstName" → DB column "first_name").
     *
     * @param definition the collection definition
     * @param field the field definition
     * @return the effective column name
     */
    private String getColumnName(CollectionDefinition definition, FieldDefinition field) {
        if (definition.systemCollection()) {
            // Check field-level column name first
            if (field.columnName() != null) {
                return field.columnName();
            }
            // Then check collection-level column mapping
            String mapped = definition.columnMapping().get(field.name());
            if (mapped != null) {
                return mapped;
            }
        }
        return toSnakeCase(field.name());
    }

    /**
     * Resolves an API field name to its physical database column name.
     * Handles system field names (createdAt → created_at, etc.) and
     * collection-level column mappings.
     *
     * @param definition the collection definition
     * @param fieldName the API field name
     * @return the physical column name
     */
    private String resolveColumnName(CollectionDefinition definition, String fieldName) {
        // System audit fields always map to snake_case columns
        return switch (fieldName) {
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            case "createdBy" -> "created_by";
            case "updatedBy" -> "updated_by";
            case "createdGeo" -> "created_geo";
            case "updatedGeo" -> "updated_geo";
            case "tenantId" -> "tenant_id";
            case "recordTypeId" -> "record_type_id";
            default -> {
                if (definition.systemCollection()) {
                    yield definition.getEffectiveColumnName(fieldName);
                }
                yield toSnakeCase(fieldName);
            }
        };
    }

    /**
     * Remaps column names in query results from physical database column names
     * back to API field names. This is necessary for system collections where
     * the column names (snake_case) differ from API field names (camelCase).
     *
     * <p>For non-system collections, this is a no-op since column names
     * match field names.
     *
     * @param definition the collection definition
     * @param records the query result records to remap in place
     */
    private void remapColumnNames(CollectionDefinition definition, List<Map<String, Object>> records) {
        // Build reverse mapping: column name → field name
        Map<String, String> reverseMap = new HashMap<>();

        // System audit fields (for all collections)
        reverseMap.put("created_at", "createdAt");
        reverseMap.put("updated_at", "updatedAt");
        reverseMap.put("created_by", "createdBy");
        reverseMap.put("updated_by", "updatedBy");
        reverseMap.put("created_geo", "createdGeo");
        reverseMap.put("updated_geo", "updatedGeo");
        reverseMap.put("tenant_id", "tenantId");
        reverseMap.put("record_type_id", "recordTypeId");

        if (definition.systemCollection()) {
            // Collection-level column mappings
            for (Map.Entry<String, String> entry : definition.columnMapping().entrySet()) {
                reverseMap.put(entry.getValue(), entry.getKey());
            }

            // Field-level column names
            for (FieldDefinition field : definition.fields()) {
                if (field.columnName() != null) {
                    reverseMap.put(field.columnName(), field.name());
                }
            }
        } else {
            // For non-system collections, reverse the snake_case → camelCase mapping
            for (FieldDefinition field : definition.fields()) {
                String snakeColumn = toSnakeCase(field.name());
                if (!snakeColumn.equals(field.name())) {
                    reverseMap.put(snakeColumn, field.name());
                }
                // Also map companion columns
                if (field.type() == FieldType.CURRENCY) {
                    reverseMap.put(snakeColumn + "_currency_code", field.name() + "_currency_code");
                }
                if (field.type() == FieldType.GEOLOCATION) {
                    reverseMap.put(snakeColumn + "_longitude", field.name() + "_longitude");
                }
            }
        }

        // Apply remapping to each record and normalize JDBC types
        for (Map<String, Object> record : records) {
            Map<String, Object> remapped = new HashMap<>();
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String columnName = entry.getKey();
                String fName = reverseMap.getOrDefault(columnName, columnName);
                Object value = entry.getValue();
                // Convert java.sql.Timestamp to java.time.Instant so downstream
                // validation (isValidDateTime) and serialization work correctly.
                if (value instanceof java.sql.Timestamp ts) {
                    value = ts.toInstant();
                }
                // Unwrap PostgreSQL PGobject (JSONB columns) so Jackson serializes
                // the actual JSON value instead of {type:"jsonb", value:"...", null:false}.
                if (value != null && "org.postgresql.util.PGobject".equals(value.getClass().getName())) {
                    String jsonStr = value.toString();
                    if (jsonStr != null && !jsonStr.isEmpty()) {
                        try {
                            value = objectMapper.readValue(jsonStr, Object.class);
                        } catch (Exception e) {
                            // Fall back to raw string if not valid JSON
                            value = jsonStr;
                        }
                    }
                }
                remapped.put(fName, value);
            }
            record.clear();
            record.putAll(remapped);
        }
    }

    /**
     * Post-processes query results to convert JDBC-specific types into
     * JSON-serializable Java types and reconstructs structured values
     * from companion columns.
     *
     * <ul>
     *   <li>MULTI_PICKLIST: converts {@code java.sql.Array} (PgArray) to {@code List<String>}</li>
     *   <li>CURRENCY: combines primary column (amount) with _currency_code companion</li>
     *   <li>GEOLOCATION: combines primary column (latitude) with _longitude companion into a Map</li>
     * </ul>
     */
    private void reconstructCompanionColumns(CollectionDefinition definition, List<Map<String, Object>> records) {
        for (FieldDefinition field : definition.fields()) {
            if (field.type() == FieldType.MULTI_PICKLIST) {
                for (Map<String, Object> record : records) {
                    Object value = record.get(field.name());
                    if (value instanceof java.sql.Array sqlArray) {
                        try {
                            Object array = sqlArray.getArray();
                            if (array instanceof String[] strings) {
                                record.put(field.name(), Arrays.asList(strings));
                            } else {
                                record.put(field.name(), List.of());
                            }
                        } catch (SQLException e) {
                            log.warn("Failed to convert SQL array for field '{}': {}", field.name(), e.getMessage());
                            record.put(field.name(), List.of());
                        }
                    }
                }
            } else if (field.type() == FieldType.CURRENCY) {
                String codeKey = field.name() + "_currency_code";
                for (Map<String, Object> record : records) {
                    // Ensure currency_code is accessible via the companion key name
                    // The raw column name from JDBC may already be present
                    if (!record.containsKey(codeKey)) {
                        // Nothing to reconstruct
                        continue;
                    }
                }
            } else if (field.type() == FieldType.GEOLOCATION) {
                String lngKey = field.name() + "_longitude";
                for (Map<String, Object> record : records) {
                    Object lat = record.get(field.name());
                    Object lng = record.get(lngKey);
                    if (lat instanceof Number && lng instanceof Number) {
                        Map<String, Object> geo = new HashMap<>();
                        geo.put("latitude", ((Number) lat).doubleValue());
                        geo.put("longitude", ((Number) lng).doubleValue());
                        record.put(field.name(), geo);
                        record.remove(lngKey);
                    }
                }
            }
        }
    }

    /**
     * Attempts to detect which field caused a unique constraint violation.
     *
     * @param definition the collection definition
     * @param data the data that was being inserted/updated
     * @param e the exception
     * @return the field name that likely caused the violation, or "unknown"
     */
    private String detectUniqueViolationField(CollectionDefinition definition, Map<String, Object> data,
            DuplicateKeyException e) {
        // Best source: the Postgres error detail names the violated columns exactly —
        // "Key (country, slug)=(..., erasmus-plus) already exists." Map the physical
        // columns back to field names so the client learns the real field set, for
        // composite constraints and for single columns alike (including stale indexes
        // whose field no longer carries unique=true in metadata).
        List<String> violatedColumns = extractViolatedColumns(e);
        if (!violatedColumns.isEmpty()) {
            Map<String, String> fieldByColumn = new LinkedHashMap<>();
            for (FieldDefinition field : definition.fields()) {
                fieldByColumn.put(getColumnName(definition, field), field.name());
            }
            fieldByColumn.putIfAbsent("id", "id");
            return violatedColumns.stream()
                    .map(column -> fieldByColumn.getOrDefault(column, column))
                    .collect(Collectors.joining(", "));
        }

        // Composite unique indexes created by CompositeUniqueConstraintService use the
        // "uniq_<table>_<col1>_<col2>" naming convention — surface that instead of
        // pretending the duplicate belongs to a single field.
        String compositeName = extractCompositeConstraintName(e);
        if (compositeName != null) {
            return compositeName;
        }

        // Check each unique field to see which one has a duplicate
        for (FieldDefinition field : definition.fields()) {
            if (field.unique() && data.containsKey(field.name())) {
                if (!isUnique(definition, field.name(), data.get(field.name()), (String) data.get("id"))) {
                    return field.name();
                }
            }
        }

        // Check if it's the primary key
        if (data.containsKey("id")) {
            return "id";
        }

        return "unknown";
    }

    private static final java.util.regex.Pattern DUPLICATE_KEY_DETAIL =
            java.util.regex.Pattern.compile("Key \\((.+?)\\)=");

    /**
     * Pulls the violated column list out of a Postgres duplicate-key error detail
     * ({@code Key (col1, col2)=(v1, v2) already exists.}). Returns an empty list when
     * the driver message carries no such detail (e.g. H2's message format), in which
     * case callers fall back to name- and probe-based detection.
     */
    static List<String> extractViolatedColumns(DuplicateKeyException e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null) {
                var matcher = DUPLICATE_KEY_DETAIL.matcher(msg);
                if (matcher.find()) {
                    List<String> columns = new ArrayList<>();
                    for (String part : matcher.group(1).split(",")) {
                        String column = part.trim();
                        if (column.length() > 1 && column.startsWith("\"") && column.endsWith("\"")) {
                            column = column.substring(1, column.length() - 1);
                        }
                        if (!column.isEmpty()) {
                            columns.add(column);
                        }
                    }
                    if (!columns.isEmpty()) {
                        return columns;
                    }
                }
            }
            cause = cause.getCause();
        }
        return List.of();
    }

    /**
     * Pulls a Kelta-managed composite-unique index name out of a Postgres
     * duplicate-key error message. Returns null when the violation is not on a
     * {@code uniq_*} index (e.g. it's a single-column {@code UNIQUE} column).
     */
    static String extractCompositeConstraintName(DuplicateKeyException e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null) {
                int idx = msg.indexOf("unique constraint \"");
                if (idx >= 0) {
                    int start = idx + "unique constraint \"".length();
                    int end = msg.indexOf('"', start);
                    if (end > start) {
                        String name = msg.substring(start, end);
                        if (name.startsWith(CompositeUniqueConstraintService.INDEX_PREFIX)) {
                            return name;
                        }
                    }
                }
            }
            cause = cause.getCause();
        }
        return null;
    }
}
