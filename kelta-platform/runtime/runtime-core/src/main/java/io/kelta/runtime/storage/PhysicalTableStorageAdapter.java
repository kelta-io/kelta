package io.kelta.runtime.storage;

import static io.kelta.runtime.storage.SqlIdentifiers.castPlaceholder;
import static io.kelta.runtime.storage.SqlIdentifiers.getBaseTableName;
import static io.kelta.runtime.storage.SqlIdentifiers.getColumnName;
import static io.kelta.runtime.storage.SqlIdentifiers.quoteIdentifier;
import static io.kelta.runtime.storage.SqlIdentifiers.resolveColumnName;
import static io.kelta.runtime.storage.SqlQueryBuilder.buildOrderByClause;
import static io.kelta.runtime.storage.SqlQueryBuilder.buildSelectClause;
import static io.kelta.runtime.storage.StorageConstraintErrors.classifyDataAccessException;
import static io.kelta.runtime.storage.StorageConstraintErrors.extractCompositeConstraintName;
import static io.kelta.runtime.storage.StorageConstraintErrors.extractViolatedColumns;
import static io.kelta.runtime.storage.StorageConstraintErrors.isForeignKeyViolation;
import static io.kelta.runtime.storage.TenantScopePolicy.refusesAnonymousRead;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.system.SystemCollectionTenancy;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.storage.TenantScopePolicy.TenantScope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    static final Set<String> SYSTEM_COLUMNS = Set.of(
        "id", "created_at", "updated_at", "created_by", "updated_by", "tenant_id",
        "record_type_id", "created_geo", "updated_geo"
    );

    /**
     * Whether {@code columnName} was already written by the system-field handling in create/update.
     * {@code record_type_id} is a system column only on user-collection tables; a system collection
     * that declares a field mapped to it (for example a record-type reference) writes it as a regular
     * field.
     */
    private static boolean handledAsSystemColumn(CollectionDefinition definition, String columnName) {
        if ("record_type_id".equals(columnName) && definition.systemCollection()) {
            return false;
        }
        return SYSTEM_COLUMNS.contains(columnName);
    }

    private final JdbcTemplate jdbcTemplate;
    private final SchemaMigrationEngine migrationEngine;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final TenantScopePolicy tenantScopePolicy;
    private final SqlQueryBuilder queryBuilder;
    private final CollectionTableDdl tableDdl;

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
        this.tenantScopePolicy = new TenantScopePolicy(migrationEngine);
        this.queryBuilder = new SqlQueryBuilder(this::convertValueForStorage);
        this.tableDdl = new CollectionTableDdl(jdbcTemplate, migrationEngine);
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
        tableDdl.initializeTable(definition, tableRef);
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

        // Build WHERE clause for filters, narrowed to the calling tenant for tenant-scoped
        // system collections. This is the single choke point: every direct executeQuery caller
        // (dashboards, reports, page render, exports, bulk ops, campaigns) is scoped here,
        // whether or not it went through the JSON:API router.
        List<FilterCondition> allFilters = new ArrayList<>();
        if (request.hasFilters()) {
            allFilters.addAll(request.filters());
        }
        TenantScope tenantScope = tenantScopePolicy.readScope(definition, tableRef);

        String whereClause = queryBuilder.buildWhereClause(allFilters, tenantScope, definition, params);
        if (!whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
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

            // Get total count — same predicate, or a metric widget counts every tenant's rows
            long totalCount = getTotalCount(tableRef, allFilters, tenantScope, definition);

            return QueryResult.of(data, totalCount, pagination);
        } catch (DataAccessException e) {
            throw classifyDataAccessException(e, "Failed to query collection: " + definition.name(),
                    filterValues(allFilters), definition);
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
        String semanticWhere = queryBuilder.buildWhereClause(
                filters == null ? List.of() : filters, tenantScopePolicy.readScope(definition, tableRef),
                definition, params);
        if (!semanticWhere.isEmpty()) {
            sql.append(" AND ").append(semanticWhere);
        }
        sql.append(" ORDER BY _distance ASC LIMIT ?");
        params.add(limit);

        try {
            List<Map<String, Object>> data = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            remapColumnNames(definition, data);
            reconstructCompanionColumns(definition, data);
            return data;
        } catch (DataAccessException e) {
            throw classifyDataAccessException(e, "Failed semantic search on collection: " + definition.name(),
                    filterValues(filters), definition);
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

        String whereClause = queryBuilder.buildWhereClause(
                filters == null ? List.of() : filters, tenantScopePolicy.readScope(definition, tableRef),
                definition, params);
        if (!whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
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
            throw classifyDataAccessException(e, "Failed to aggregate collection: " + definition.name(),
                    filterValues(filters), definition);
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
        if (refusesAnonymousRead(definition)) {
            return Optional.empty();
        }
        TableRef tableRef = getTableRef(definition);
        String sql = "SELECT * FROM " + tableRef.toSql() + " WHERE id = ?";
        List<Object> params = new ArrayList<>();
        params.add(id);
        // Only self-scoped collections are narrowed here: for tenant_id-scoped ones get-by-id
        // stays router-side (the router's RecordTenantScope.visibleToTenant), but nothing downstream
        // could narrow a tenants row and a foreign id must read as absent.
        if (SystemCollectionTenancy.isSelfScoped(definition)) {
            TenantScope scope = tenantScopePolicy.tenantScope(definition, tableRef);
            if (scope != null) {
                sql += " AND " + scope.sql();
                params.addAll(scope.params());
            }
        }
        // Owner-scoped callers never see a row they do not own: a foreign id reads as absent (404).
        TenantScope owner = tenantScopePolicy.ownerScope(definition);
        if (owner != null) {
            sql += " AND " + owner.sql();
            params.addAll(owner.params());
        }

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params.toArray());
            if (results.isEmpty()) {
                return Optional.empty();
            }
            // Remap column names to field names for system collections
            remapColumnNames(definition, results);
            // Reconstruct companion column values
            reconstructCompanionColumns(definition, results);
            return Optional.of(results.get(0));
        } catch (DataAccessException e) {
            throw classifyDataAccessException(e, "Failed to get record by ID from collection: " + definition.name(),
                    Map.of("id", id), definition);
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
                if (handledAsSystemColumn(definition, columnName)) {
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
            throw classifyDataAccessException(e, "Failed to create record in collection: " + definition.name(),
                    data, definition);
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
                if (handledAsSystemColumn(definition, columnName)) {
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
            throw classifyDataAccessException(e, "Failed to update record in collection: " + definition.name(),
                    data, definition);
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

    private static Map<String, Object> filterValues(List<FilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (FilterCondition filter : filters) {
            values.put(filter.fieldName(), filter.value());
        }
        return values;
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
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(fieldName, value);
            throw classifyDataAccessException(e, "Failed to check uniqueness for field: " + fieldName,
                    values, definition);
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
     * Converts a camelCase string to snake_case, e.g. {@code "firstName"} → {@code "first_name"}.
     *
     * @param camelCase the camelCase string
     * @return the snake_case equivalent
     */
    public static String toSnakeCase(String camelCase) {
        return SqlIdentifiers.toSnakeCase(camelCase);
    }

    /**
     * Gets the total count of records matching the filter conditions.
     *
     * @param tableRef the table reference
     * @param filters the filter conditions
     * @param tenantScope the tenant predicate to AND in, or {@code null} for none
     * @param definition the collection definition (used for column name mapping)
     * @return the total count
     */
    private long getTotalCount(TableRef tableRef, List<FilterCondition> filters,
                                TenantScope tenantScope, CollectionDefinition definition) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
        sql.append(tableRef.toSql());

        List<Object> params = new ArrayList<>();
        String whereClause = queryBuilder.buildWhereClause(
                filters == null ? List.of() : filters, tenantScope, definition, params);
        if (!whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
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
}
