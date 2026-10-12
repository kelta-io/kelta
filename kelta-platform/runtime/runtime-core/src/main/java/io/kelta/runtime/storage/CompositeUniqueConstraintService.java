package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages composite unique constraints on collection tables.
 *
 * <p>Issues {@code CREATE UNIQUE INDEX} / {@code DROP INDEX} statements against
 * the physical PostgreSQL table that backs a collection, with constraint
 * metadata read back from {@code pg_indexes}. Constraints live at the database
 * level — no separate registry is required for enforcement, because Postgres
 * rejects duplicate inserts directly.
 *
 * <p>Index naming follows the convention {@code uniq_<table>_<col1>_<col2>...}
 * (clamped to PostgreSQL's 63-character identifier limit via
 * {@link SqlIdentifiers#buildBoundedIdentifier(String, String, String)}).
 * Listing is filtered by the {@code uniq_<table>_} prefix so unique indexes
 * Kelta did not create (Flyway-managed primary keys, etc.) are not surfaced.
 */
@Service
public class CompositeUniqueConstraintService {

    private static final Logger log = LoggerFactory.getLogger(CompositeUniqueConstraintService.class);
    static final String INDEX_PREFIX = "uniq_";

    private final JdbcTemplate jdbcTemplate;
    private final PhysicalTableStorageAdapter storageAdapter;

    public CompositeUniqueConstraintService(JdbcTemplate jdbcTemplate,
                                            PhysicalTableStorageAdapter storageAdapter) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageAdapter = storageAdapter;
    }

    /**
     * Creates a composite unique index on the collection's physical table.
     *
     * @param definition the collection definition (used to resolve table + columns)
     * @param fieldNames the field names to constrain (must be 2+ to make sense as composite;
     *                   single-field is allowed but redundant with {@code field.unique()})
     * @return metadata describing the created index
     * @throws IllegalArgumentException if fieldNames is null/empty or references an unknown field
     * @throws UniqueConstraintViolationException if existing rows violate the proposed constraint
     */
    public ConstraintInfo create(CollectionDefinition definition, List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            throw new IllegalArgumentException("fieldNames cannot be empty");
        }

        List<String> columns = resolveColumns(definition, fieldNames);
        TableRef tableRef = storageAdapter.getTableRef(definition);
        String baseTable = SqlIdentifiers.getBaseTableName(definition);
        String indexName = buildIndexName(baseTable, columns);

        // Quote the column refs (reserved words like `user` are legal field names);
        // the index NAME stays unquoted so catalog lookups by name still match.
        String columnList = columns.stream()
                .map(SqlIdentifiers::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();

        String sql = "CREATE UNIQUE INDEX IF NOT EXISTS "
                + SqlIdentifiers.sanitizeIdentifier(indexName)
                + " ON " + tableRef.toSql() + " (" + columnList + ")";

        try {
            jdbcTemplate.execute(sql);
            log.info("Created composite unique constraint '{}' on '{}' ({})",
                    indexName, tableRef.toSql(), columns);
            return new ConstraintInfo(indexName, fieldNames, columns);
        } catch (DuplicateKeyException e) {
            throw new UniqueConstraintViolationException(definition.name(),
                    String.join(",", fieldNames), null, e);
        } catch (DataAccessException e) {
            throw new StorageException(
                    "Failed to create composite unique constraint on collection '"
                            + definition.name() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Lists composite unique constraints defined on the collection's table.
     * Reads from {@code pg_indexes} filtered by the Kelta {@code uniq_} prefix
     * so framework-managed indexes (primary keys, Flyway constraints) are excluded.
     */
    public List<ConstraintInfo> list(CollectionDefinition definition) {
        TableRef tableRef = storageAdapter.getTableRef(definition);
        String baseTable = SqlIdentifiers.getBaseTableName(definition);

        String sql = "SELECT indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname = ? AND tablename = ? AND indexname LIKE ?";
        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        String name = rs.getString("indexname");
                        String def = rs.getString("indexdef");
                        List<String> columns = parseColumnsFromIndexDef(def);
                        List<String> fieldNames = columnsToFieldNames(definition, columns);
                        return new ConstraintInfo(name, fieldNames, columns);
                    },
                    tableRef.schema(), baseTable, INDEX_PREFIX + "%");
        } catch (DataAccessException e) {
            throw new StorageException(
                    "Failed to list composite unique constraints for collection '"
                            + definition.name() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Drops a composite unique constraint by index name. Must belong to the
     * collection's table (verified by querying {@code pg_indexes} first).
     *
     * @return true if the index was found and dropped; false if no matching index exists
     */
    public boolean drop(CollectionDefinition definition, String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName cannot be blank");
        }
        TableRef tableRef = storageAdapter.getTableRef(definition);
        String baseTable = SqlIdentifiers.getBaseTableName(definition);

        Integer match = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = ? AND tablename = ? AND indexname = ?",
                Integer.class, tableRef.schema(), baseTable, indexName);
        if (match == null || match == 0) {
            return false;
        }

        String dropSql = "DROP INDEX " + (tableRef.isPublicSchema()
                ? SqlIdentifiers.sanitizeIdentifier(indexName)
                : "\"" + tableRef.schema() + "\"."
                        + SqlIdentifiers.sanitizeIdentifier(indexName));
        try {
            jdbcTemplate.execute(dropSql);
            log.info("Dropped composite unique constraint '{}' on '{}'", indexName, tableRef.toSql());
            return true;
        } catch (DataAccessException e) {
            throw new StorageException(
                    "Failed to drop composite unique constraint '" + indexName + "': " + e.getMessage(), e);
        }
    }

    private List<String> resolveColumns(CollectionDefinition definition, List<String> fieldNames) {
        List<String> columns = new ArrayList<>(fieldNames.size());
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) {
                throw new IllegalArgumentException("fieldNames contains a blank entry");
            }
            FieldDefinition field = definition.getField(fieldName);
            if (field == null) {
                throw new IllegalArgumentException(
                        "Unknown field '" + fieldName + "' on collection '" + definition.name() + "'");
            }
            if (!field.type().hasPhysicalColumn()) {
                throw new IllegalArgumentException(
                        "Field '" + fieldName + "' has no physical column and cannot participate in a unique constraint");
            }
            String column = definition.systemCollection()
                    ? definition.getEffectiveColumnName(fieldName)
                    : SqlIdentifiers.toSnakeCase(fieldName);
            columns.add(column);
        }
        return columns;
    }

    static String buildIndexName(String baseTable, List<String> columns) {
        String suffix = String.join("_", columns);
        return SqlIdentifiers.buildBoundedIdentifier(
                INDEX_PREFIX,
                SqlIdentifiers.identifierPart(baseTable),
                suffix);
    }

    static List<String> parseColumnsFromIndexDef(String indexDef) {
        if (indexDef == null) return List.of();
        int open = indexDef.lastIndexOf('(');
        int close = indexDef.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return List.of();
        String inside = indexDef.substring(open + 1, close);
        List<String> cols = new ArrayList<>();
        for (String piece : inside.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            cols.add(trimmed);
        }
        return cols;
    }

    private List<String> columnsToFieldNames(CollectionDefinition definition, List<String> columns) {
        Map<String, String> columnToField = new LinkedHashMap<>();
        for (FieldDefinition f : definition.fields()) {
            String col = definition.systemCollection()
                    ? definition.getEffectiveColumnName(f.name())
                    : SqlIdentifiers.toSnakeCase(f.name());
            columnToField.put(col, f.name());
        }
        List<String> result = new ArrayList<>(columns.size());
        for (String col : columns) {
            result.add(columnToField.getOrDefault(col, col));
        }
        return result;
    }

    /**
     * Describes a composite unique constraint.
     *
     * @param indexName the physical Postgres index name
     * @param fieldNames the API field names participating in the constraint
     * @param columns the physical column names participating in the constraint
     */
    public record ConstraintInfo(String indexName, List<String> fieldNames, List<String> columns) {
        public ConstraintInfo {
            fieldNames = List.copyOf(fieldNames);
            columns = List.copyOf(columns);
        }
    }
}
