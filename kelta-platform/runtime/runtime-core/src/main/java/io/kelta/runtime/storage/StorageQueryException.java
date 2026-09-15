package io.kelta.runtime.storage;

import io.kelta.runtime.query.InvalidQueryException;

/**
 * Thrown when a query or write fails because of a client-supplied value the
 * database could not accept — an unparseable UUID/date/number in a filter, an
 * unknown column, or an operator that doesn't exist for the target type
 * (Postgres SQLSTATE class {@code 22} plus {@code 42703}/{@code 42883}).
 *
 * <p>Extends {@link InvalidQueryException} so it maps to HTTP 400 with no
 * extra wiring; {@code io.kelta.runtime.router.GlobalExceptionHandler} additionally
 * surfaces {@link #getSqlState()} in the error {@code meta}. Anything else — a dropped
 * connection, a deadlock, a syntax error in SQL this adapter generated itself
 * — stays a plain {@link StorageException} (500): see
 * {@code PhysicalTableStorageAdapter.classifyDataAccessException}.
 *
 * @since 1.0.0
 */
public class StorageQueryException extends InvalidQueryException {

    private final String sqlState;

    public StorageQueryException(String fieldName, String reason, String sqlState, Throwable cause) {
        super(fieldName, reason);
        initCause(cause);
        this.sqlState = sqlState;
    }

    public StorageQueryException(String reason, String sqlState, Throwable cause) {
        super(reason);
        initCause(cause);
        this.sqlState = sqlState;
    }

    /**
     * The Postgres SQLSTATE that triggered this classification (e.g. {@code "22P02"}).
     */
    public String getSqlState() {
        return sqlState;
    }
}
