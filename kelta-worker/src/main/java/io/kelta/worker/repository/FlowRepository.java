package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository for flow definition and version database queries.
 *
 * <p>Encapsulates SQL access for loading flow definitions and managing
 * flow versions. Flow execution persistence is handled separately
 * by {@link io.kelta.runtime.flow.FlowStore}.
 */
@Repository
public class FlowRepository {

    private static final String SELECT_FLOW_BY_ID = """
            SELECT id, tenant_id, name, definition, trigger_config, flow_type, active
            FROM flow WHERE id = ?
            """;

    private static final String SELECT_FLOW_NAME_BY_ID = """
            SELECT name FROM flow WHERE id = ?
            """;

    private static final String SELECT_MAX_VERSION_NUMBER = """
            SELECT COALESCE(MAX(version_number), 0) FROM flow_version WHERE flow_id = ?
            """;

    private static final String INSERT_FLOW_VERSION = """
            INSERT INTO flow_version (id, flow_id, version_number, definition, change_summary, created_by)
            VALUES (?, ?, ?, ?::jsonb, ?, ?)
            """;

    private static final String UPDATE_FLOW_TRIGGER_CONFIG = """
            UPDATE flow SET trigger_config = ?::jsonb WHERE id = ?
            """;

    private static final String UPDATE_FLOW_PUBLISHED_VERSION = """
            UPDATE flow SET published_version = ?, version = ? WHERE id = ?
            """;

    private static final String SELECT_FLOW_VERSIONS = """
            SELECT id, version_number, change_summary, created_by, created_at
            FROM flow_version WHERE flow_id = ? ORDER BY version_number DESC
            """;

    private static final String SELECT_FLOW_VERSION = """
            SELECT id, version_number, definition, change_summary, created_by, created_at
            FROM flow_version WHERE flow_id = ? AND version_number = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public FlowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads a flow definition by ID, converting JSONB PGobject values to Strings.
     */
    public Optional<Map<String, Object>> findFlowById(String flowId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_FLOW_BY_ID, flowId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.computeIfPresent("definition", (k, v) -> v != null ? v.toString() : null);
        row.computeIfPresent("trigger_config", (k, v) -> v != null ? v.toString() : null);
        return Optional.of(row);
    }

    /**
     * Returns a flow's display name. RLS scopes the lookup to the current tenant.
     */
    public Optional<String> findFlowNameById(String flowId) {
        return jdbcTemplate.queryForList(SELECT_FLOW_NAME_BY_ID, String.class, flowId).stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * Lists executions triggered by a specific record (newest first), joined
     * to the flow for its display name. Backs the record activity timeline.
     * RLS scopes rows to the current tenant.
     */
    public List<Map<String, Object>> findExecutionsByTriggerRecord(String recordId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT e.id, e.flow_id, f.name AS flow_name, e.status,
                       e.started_at, e.completed_at, e.duration_ms, e.error_message
                FROM flow_execution e
                LEFT JOIN flow f ON f.id = e.flow_id
                WHERE e.trigger_record_id = ?
                ORDER BY e.started_at DESC
                LIMIT ?
                """, recordId, limit);
    }

    /** {@link #findExecutionsByTriggerRecord}, limited to the executions {@code startedBy} started. */
    public List<Map<String, Object>> findExecutionsByTriggerRecordStartedBy(String recordId, String startedBy,
                                                                            int limit) {
        return jdbcTemplate.queryForList("""
                SELECT e.id, e.flow_id, f.name AS flow_name, e.status,
                       e.started_at, e.completed_at, e.duration_ms, e.error_message
                FROM flow_execution e
                LEFT JOIN flow f ON f.id = e.flow_id
                WHERE e.trigger_record_id = ?
                  AND lower(e.started_by) = lower(?)
                ORDER BY e.started_at DESC
                LIMIT ?
                """, recordId, startedBy, limit);
    }

    public int getMaxVersionNumber(String flowId) {
        Integer version = jdbcTemplate.queryForObject(SELECT_MAX_VERSION_NUMBER, Integer.class, flowId);
        return version != null ? version : 0;
    }

    public void insertFlowVersion(String versionId, String flowId, int versionNumber,
                                   String definition, String changeSummary, String createdBy) {
        jdbcTemplate.update(INSERT_FLOW_VERSION,
                versionId, flowId, versionNumber, definition, changeSummary, createdBy);
    }

    public void updateTriggerConfig(String flowId, String triggerConfigJson) {
        jdbcTemplate.update(UPDATE_FLOW_TRIGGER_CONFIG, triggerConfigJson, flowId);
    }

    public void updateFlowPublishedVersion(String flowId, int version) {
        jdbcTemplate.update(UPDATE_FLOW_PUBLISHED_VERSION, version, version, flowId);
    }

    public List<Map<String, Object>> findFlowVersions(String flowId) {
        return jdbcTemplate.queryForList(SELECT_FLOW_VERSIONS, flowId);
    }

    public Optional<Map<String, Object>> findFlowVersion(String flowId, int versionNumber) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                SELECT_FLOW_VERSION, flowId, versionNumber);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> version = new LinkedHashMap<>(rows.get(0));
        version.computeIfPresent("definition", (k, v) -> v != null ? v.toString() : null);
        return Optional.of(version);
    }
}
