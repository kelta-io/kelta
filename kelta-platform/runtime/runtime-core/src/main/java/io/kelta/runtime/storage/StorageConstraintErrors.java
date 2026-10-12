package io.kelta.runtime.storage;

import static io.kelta.runtime.storage.SqlIdentifiers.resolveColumnName;

import io.kelta.runtime.model.CollectionDefinition;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Classifies database failures raised by {@link PhysicalTableStorageAdapter}: SQLSTATE
 * extraction, client-vs-storage fault classification (400 vs 500), foreign-key and
 * concurrent-DDL detection, and parsing of Postgres duplicate-key messages.
 *
 * <p>Pure functions with no state. Extracted from {@code PhysicalTableStorageAdapter}
 * unchanged.
 */
final class StorageConstraintErrors {

    private StorageConstraintErrors() {
    }

    /**
     * True when a DDL failure only means another worker pod got there first — the object this
     * idempotent statement ({@code CREATE ... IF NOT EXISTS}, or a guarded {@code ADD
     * CONSTRAINT}) would have created was committed by a concurrent transaction between its
     * existence check and its write. Postgres reports that as {@code duplicate_table} (42P07),
     * {@code duplicate_object} — "type/constraint ... already exists" (42710) — or a unique
     * violation (23505) on a system catalog's name index ({@code pg_type_typname_nsp_index},
     * {@code pg_class_relname_nsp_index}). Any other 23505 is real: {@code CREATE UNIQUE INDEX}
     * over rows that already hold duplicates fails with it too, and must not be swallowed.
     */
    static boolean isConcurrentDdlDuplicate(DataAccessException e) {
        String sqlState = extractSqlState(e);
        if ("42P07".equals(sqlState) || "42710".equals(sqlState)) {
            return true;
        }
        // A DuplicateKeyException can arrive without its driver cause; its message still names
        // the index.
        if ("23505".equals(sqlState) || (sqlState == null && e instanceof DuplicateKeyException)) {
            String message = e.getMostSpecificCause().getMessage();
            return message != null
                    && (message.contains("pg_type_typname_nsp_index")
                            || message.contains("pg_class_relname_nsp_index"));
        }
        return false;
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

    /**
     * Postgres SQLSTATE codes that mean "the client sent something this statement can't
     * evaluate" rather than a storage fault: the whole data-exception class 22 (bad
     * literal for the target type, out-of-range, truncation — {@code 22P02}, {@code 22007},
     * {@code 22003}, {@code 22001} among them) plus the two 42xxx codes for a query that
     * names something that doesn't exist for this schema ({@code 42703} undefined column,
     * {@code 42883} no operator for the type). Everything else — connection loss, deadlock,
     * a syntax error in SQL this adapter generated itself — is a genuine 500.
     */
    private static boolean isClientQuerySqlState(String sqlState) {
        return sqlState != null
                && (sqlState.startsWith("22") || "42703".equals(sqlState) || "42883".equals(sqlState));
    }

    /**
     * Finds the innermost {@link java.sql.SQLException}'s SQLSTATE in the cause chain, or
     * {@code null} when the failure never reached the driver (e.g. a Spring-side wrapper
     * with no {@code SQLException} cause).
     */
    private static String extractSqlState(Throwable e) {
        String sqlState = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
            }
        }
        return sqlState;
    }

    /**
     * The innermost cause's message — the actual Postgres error text (e.g.
     * {@code invalid input syntax for type uuid: "not-a-uuid"}), which is what makes the
     * 400 actionable instead of the generic wrapper message.
     */
    private static String rootCauseMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : e.getMessage();
    }

    /**
     * Best-effort match of which field's value (or column) triggered the failure, by
     * checking whether the driver's error message names the field's resolved column or
     * echoes its value — Postgres embeds both for the SQLSTATEs this handles (e.g.
     * {@code column "..." does not exist}, {@code invalid input syntax ...: "value"}).
     * Returns {@code null} when the field can't be identified; the caller still reports
     * the SQLSTATE and root-cause message, just without a {@code source.pointer}.
     */
    private static String detectOffendingField(Map<String, Object> valuesByField, CollectionDefinition definition,
                                         Throwable cause) {
        if (valuesByField == null || valuesByField.isEmpty()) {
            return null;
        }
        String message = rootCauseMessage(cause);
        if (message == null) {
            return null;
        }
        // Check exact value matches first — precise, since Postgres quotes the literal
        // verbatim (e.g. `invalid input syntax for type uuid: "not-a-uuid"`). Column-name
        // matching runs as a fallback pass, not inline, because a short column name like
        // "id" can appear inside an unrelated word (e.g. "uuid") and would otherwise beat
        // a genuine value match found later in iteration order.
        for (Map.Entry<String, Object> entry : valuesByField.entrySet()) {
            if (valueAppearsIn(message, entry.getValue())) {
                return entry.getKey();
            }
        }
        for (Map.Entry<String, Object> entry : valuesByField.entrySet()) {
            String fieldName = entry.getKey();
            String columnName = definition != null ? resolveColumnName(definition, fieldName) : fieldName;
            // Match the quoted form only (e.g. `column "colname" does not exist`) — an
            // unquoted contains() would false-positive on short names like "id" showing up
            // inside unrelated words (e.g. "uuid").
            if (columnName != null && message.contains("\"" + columnName + "\"")) {
                return fieldName;
            }
        }
        return null;
    }

    private static boolean valueAppearsIn(String message, Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?> coll) {
            return coll.stream().anyMatch(el -> el != null && message.contains(String.valueOf(el)));
        }
        return message.contains(String.valueOf(value));
    }

    /**
     * Classifies a {@link DataAccessException} into either a 400 {@link StorageQueryException}
     * (client-caused, per {@link #isClientQuerySqlState}) or a 500 {@link StorageException} —
     * extends the {@link #isForeignKeyViolation} pattern from a single SQLSTATE to the general
     * case. {@code valuesByField} (the filter conditions, or the record data on a write) is used
     * only to attempt a {@code source.pointer}; pass {@code null} when there's nothing to check.
     */
    static RuntimeException classifyDataAccessException(DataAccessException e, String contextMessage,
                                                           Map<String, Object> valuesByField,
                                                           CollectionDefinition definition) {
        String sqlState = extractSqlState(e);
        if (!isClientQuerySqlState(sqlState)) {
            return new StorageException(contextMessage, e);
        }
        String fieldName = detectOffendingField(valuesByField, definition, e);
        String reason = rootCauseMessage(e);
        return fieldName != null
                ? new StorageQueryException(fieldName, reason, sqlState, e)
                : new StorageQueryException(reason, sqlState, e);
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
