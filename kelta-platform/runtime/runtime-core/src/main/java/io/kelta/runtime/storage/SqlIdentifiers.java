package io.kelta.runtime.storage;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * SQL identifier helpers for {@link PhysicalTableStorageAdapter}: table and column name
 * resolution, identifier sanitizing and quoting, and length-bounded generated names.
 *
 * <p>Pure functions with no state. Extracted from {@code PhysicalTableStorageAdapter}
 * unchanged; that class keeps a public {@code toSnakeCase} delegate for callers outside
 * this package.
 */
final class SqlIdentifiers {

    private SqlIdentifiers() {
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
     * class so the index serves the {@code <=>} distance used by {@link PhysicalTableStorageAdapter#semanticSearch}.
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
     * Builds the index DDL for an owner-scoped collection's owner column, which every
     * owner-scoped read filters on ({@link TenantScopePolicy#ownerScope}).
     *
     * @param definition    the collection; its {@code ownerField} must be set
     * @param qualifiedName the schema-qualified table name
     * @return a {@code CREATE INDEX IF NOT EXISTS idx_owner_<table>_<column> ...} statement
     */
    static String buildOwnerIndexStatement(CollectionDefinition definition, String qualifiedName) {
        String column = resolveColumnName(definition, definition.ownerField());
        String idxName = buildBoundedIdentifier(
                "idx_owner_", identifierPart(getBaseTableName(definition)), sanitizeIdentifier(column));
        return "CREATE INDEX IF NOT EXISTS " + idxName
                + " ON " + qualifiedName + "(" + quoteIdentifier(column) + ")";
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
    static String toSnakeCase(String camelCase) {
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
     * Gets the effective column name for a field within a collection.
     * For system collections, fields may have a different physical column name
     * (e.g., API field "firstName" → DB column "first_name").
     *
     * @param definition the collection definition
     * @param field the field definition
     * @return the effective column name
     */
    static String getColumnName(CollectionDefinition definition, FieldDefinition field) {
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
    static String resolveColumnName(CollectionDefinition definition, String fieldName) {
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
}
