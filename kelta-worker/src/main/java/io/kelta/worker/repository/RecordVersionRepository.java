package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes the {@code record_version} log of full-record snapshots for collections with
 * collection-level {@code track_history} enabled.
 *
 * <p>Version numbers are 1-based per record, computed as
 * {@code COALESCE(MAX(version_number), 0) + 1}. Concurrent writers to the same record would
 * both read the same MAX and collide on {@code uq_record_version} (#1578 — a retry loop here
 * only shrank the window, and still dropped versions and logged a Postgres ERROR per
 * collision). So each insert first takes a per-record {@code pg_advisory_xact_lock} inside a
 * transaction: the next writer blocks until the previous one commits, and its INSERT then runs
 * with a fresh READ COMMITTED snapshot that sees the committed MAX. (A caller running this in a
 * REPEATABLE READ / SERIALIZABLE transaction would read a stale MAX — none does today.) Runs under the request's tenant context, so Postgres RLS scopes
 * every row to the tenant. Follows the hand-written-SQL {@code JdbcTemplate} idiom (see
 * {@link FieldHistoryRepository}) — no JPA.
 */
@Repository
public class RecordVersionRepository {

    /**
     * Serializes version inserts per record until the transaction ends. The key is a 64-bit hash
     * of tenant/collection/record; a hash collision only makes two unrelated records wait on each
     * other briefly, never produces a wrong version number.
     */
    static final String LOCK_RECORD =
            "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ? || ':' || ?, 0))";

    static final String INSERT_VERSION = """
            INSERT INTO record_version
                (id, tenant_id, collection_id, record_id, version_number, change_type,
                 snapshot, changed_fields, changed_by, changed_at, change_source)
            SELECT ?, ?, ?, ?, COALESCE(MAX(version_number), 0) + 1, ?,
                   CAST(? AS jsonb), CAST(? AS jsonb), ?, NOW(), ?
            FROM record_version
            WHERE tenant_id = ? AND collection_id = ? AND record_id = ?
            """;

    /** Versions changed before the retention cutoff. */
    static final String DUE_BY_AGE = "changed_at < ?";

    /**
     * Versions changed before the cutoff, or ranked past the per-record cap by
     * {@code version_number DESC}. A record's newest version always ranks 1, so a cap of at least
     * 1 never removes it. A window function cannot share a query level with {@code FOR UPDATE},
     * so the ranking sits in a sub-select and the caller locks the ids it returns.
     */
    static final String DUE_BY_AGE_OR_CAP = """
            id IN (SELECT id FROM (
                       SELECT id, changed_at,
                              row_number() OVER (PARTITION BY tenant_id, collection_id, record_id
                                                 ORDER BY version_number DESC) AS version_rank
                         FROM record_version) ranked
                    WHERE changed_at < ? OR version_rank > ?)
            """;

    static final String COUNT_DUE = "SELECT COUNT(*) FROM record_version WHERE ";

    static final String DELETE_DUE = """
            DELETE FROM record_version
             WHERE id IN (
                   SELECT id FROM record_version
                    WHERE %s
                    ORDER BY changed_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
            """;

    private final JdbcTemplate jdbc;

    public RecordVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the next version for a record. {@code snapshotJson} and {@code changedFieldsJson}
     * are pre-serialized JSON (an object and an array of field names respectively).
     *
     * <p>{@code @Transactional} is load-bearing: {@code pg_advisory_xact_lock} is released at
     * transaction end, so without a surrounding transaction the lock would be dropped before the
     * INSERT runs. Joins the caller's transaction when there is one.
     */
    @Transactional
    public void recordVersion(String tenantId, String collectionId, String recordId,
                              String changeType, String snapshotJson, String changedFieldsJson,
                              String changedBy, String changeSource) {
        jdbc.queryForObject(LOCK_RECORD, Object.class, tenantId, collectionId, recordId);
        jdbc.update(INSERT_VERSION,
                UUID.randomUUID().toString(), tenantId, collectionId, recordId,
                changeType, snapshotJson, changedFieldsJson, changedBy, changeSource,
                tenantId, collectionId, recordId);
    }

    /**
     * Counts versions due for retention: changed before {@code cutoff}, or (when
     * {@code maxVersionsPerRecord > 0}) beyond the newest {@code maxVersionsPerRecord} of their
     * record. With no tenant bound, RLS's {@code admin_bypass} makes this span every tenant.
     */
    public long countDueForRetention(Instant cutoff, int maxVersionsPerRecord) {
        Long count = maxVersionsPerRecord > 0
                ? jdbc.queryForObject(COUNT_DUE + DUE_BY_AGE_OR_CAP, Long.class,
                        Timestamp.from(cutoff), maxVersionsPerRecord)
                : jdbc.queryForObject(COUNT_DUE + DUE_BY_AGE, Long.class, Timestamp.from(cutoff));
        return count == null ? 0L : count;
    }

    /**
     * Deletes up to {@code batchSize} due versions, oldest first; returns rows removed. Rows are
     * claimed with {@code FOR UPDATE SKIP LOCKED}, so concurrent pods delete disjoint batches.
     */
    public int deleteDueForRetentionBatch(Instant cutoff, int maxVersionsPerRecord, int batchSize) {
        return maxVersionsPerRecord > 0
                ? jdbc.update(DELETE_DUE.formatted(DUE_BY_AGE_OR_CAP),
                        Timestamp.from(cutoff), maxVersionsPerRecord, batchSize)
                : jdbc.update(DELETE_DUE.formatted(DUE_BY_AGE), Timestamp.from(cutoff), batchSize);
    }
}
