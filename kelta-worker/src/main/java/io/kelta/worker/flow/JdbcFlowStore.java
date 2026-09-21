package io.kelta.worker.flow;

import io.kelta.runtime.flow.FlowExecutionData;
import io.kelta.runtime.flow.FlowStepLogData;
import io.kelta.runtime.flow.FlowStore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-backed implementation of {@link FlowStore}.
 * <p>
 * Uses direct SQL queries via {@link JdbcTemplate} for performance and simplicity.
 * JSON columns are serialized/deserialized via {@link ObjectMapper}.
 *
 * @since 1.0.0
 */
public class JdbcFlowStore implements FlowStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFlowStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcFlowStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    // -------------------------------------------------------------------------
    // Execution Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public String createExecution(String tenantId, String flowId, String startedBy,
                                  String triggerRecordId, Map<String, Object> initialInput,
                                  boolean isTest) {
        String id = UUID.randomUUID().toString();
        String initialInputJson = toJson(initialInput);
        String stateDataJson = toJson(initialInput != null ? initialInput : Map.of());

        jdbcTemplate.update("""
            INSERT INTO flow_execution
                (id, tenant_id, flow_id, status, started_by, trigger_record_id,
                 state_data, initial_input, is_test, step_count, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 0, NOW())
            """,
            id, tenantId, flowId, FlowExecutionData.STATUS_RUNNING,
            startedBy, triggerRecordId, stateDataJson, initialInputJson, isTest);

        return id;
    }

    @Override
    public Optional<FlowExecutionData> loadExecution(String executionId) {
        List<FlowExecutionData> results = jdbcTemplate.query("""
            SELECT id, tenant_id, flow_id, status, started_by, trigger_record_id,
                   state_data, current_node_id, error_message, step_count, duration_ms,
                   initial_input, is_test, started_at, completed_at
            FROM flow_execution
            WHERE id = ?
            """,
            (rs, rowNum) -> mapExecution(rs),
            executionId);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void updateExecutionState(String executionId, String currentNodeId,
                                     Map<String, Object> stateData, String status, int stepCount) {
        jdbcTemplate.update("""
            UPDATE flow_execution
            SET current_node_id = ?, state_data = ?::jsonb, status = ?,
                step_count = ?, updated_at = NOW()
            WHERE id = ?
            """,
            currentNodeId, toJson(stateData), status, stepCount, executionId);
    }

    @Override
    public void completeExecution(String executionId, String status, Map<String, Object> stateData,
                                  String errorMessage, int durationMs, int stepCount) {
        jdbcTemplate.update("""
            UPDATE flow_execution
            SET status = ?, state_data = ?::jsonb, error_message = ?,
                duration_ms = ?, step_count = ?, completed_at = NOW(), updated_at = NOW()
            WHERE id = ?
            """,
            status, toJson(stateData), errorMessage, durationMs, stepCount, executionId);
    }

    @Override
    public void cancelExecution(String executionId) {
        jdbcTemplate.update("""
            UPDATE flow_execution
            SET status = ?, completed_at = NOW(), updated_at = NOW()
            WHERE id = ?
            """,
            FlowExecutionData.STATUS_CANCELLED, executionId);
    }

    // -------------------------------------------------------------------------
    // Step Logging
    // -------------------------------------------------------------------------

    @Override
    public String logStepExecution(String executionId, String stateId, String stateName,
                                   String stateType, Map<String, Object> inputSnapshot,
                                   Map<String, Object> outputSnapshot, String status,
                                   String errorMessage, String errorCode,
                                   Integer durationMs, int attemptNumber) {
        String id = UUID.randomUUID().toString();

        jdbcTemplate.update("""
            INSERT INTO flow_step_log
                (id, execution_id, state_id, state_name, state_type, status,
                 input_snapshot, output_snapshot, error_message, error_code,
                 attempt_number, duration_ms, started_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?,
                    NOW(), CASE WHEN ? IN ('SUCCEEDED', 'FAILED', 'SKIPPED') THEN NOW() ELSE NULL END)
            """,
            id, executionId, stateId, stateName, stateType, status,
            toJson(inputSnapshot), toJson(outputSnapshot),
            errorMessage, errorCode, attemptNumber, durationMs, status);

        return id;
    }

    @Override
    public void updateStepLog(String stepLogId, Map<String, Object> outputSnapshot,
                              String status, String errorMessage, String errorCode, int durationMs) {
        jdbcTemplate.update("""
            UPDATE flow_step_log
            SET output_snapshot = ?::jsonb, status = ?, error_message = ?,
                error_code = ?, duration_ms = ?, completed_at = NOW(), updated_at = NOW()
            WHERE id = ?
            """,
            toJson(outputSnapshot), status, errorMessage, errorCode, durationMs, stepLogId);
    }

    @Override
    public List<FlowStepLogData> loadStepLogs(String executionId) {
        return jdbcTemplate.query("""
            SELECT id, execution_id, state_id, state_name, state_type, status,
                   input_snapshot, output_snapshot, error_message, error_code,
                   attempt_number, duration_ms, started_at, completed_at
            FROM flow_step_log
            WHERE execution_id = ?
            ORDER BY started_at ASC
            """,
            (rs, rowNum) -> mapStepLog(rs),
            executionId);
    }

    // -------------------------------------------------------------------------
    // Flow Definition Lookup
    // -------------------------------------------------------------------------

    @Override
    public Optional<String> findFlowDefinitionById(String tenantId, String flowId) {
        return findDefinition("WHERE tenant_id = ? AND id = ?", tenantId, flowId);
    }

    @Override
    public Optional<String> findFlowDefinitionByName(String tenantId, String flowName) {
        return findDefinition("WHERE tenant_id = ? AND name = ?", tenantId, flowName);
    }

    @Override
    public Optional<String> findTenantSlug(String tenantId) {
        List<String> results = jdbcTemplate.query(
            "SELECT slug FROM tenant WHERE id = ? LIMIT 1",
            (rs, rowNum) -> rs.getString("slug"),
            tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    private Optional<String> findDefinition(String whereClause, Object... args) {
        List<String> results = jdbcTemplate.query(
            "SELECT definition FROM flow " + whereClause + " LIMIT 1",
            (rs, rowNum) -> {
                Object def = rs.getObject("definition");
                return def != null ? def.toString() : null;
            },
            args);
        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
    }

    // -------------------------------------------------------------------------
    // Execution Queries
    // -------------------------------------------------------------------------

    @Override
    public List<FlowExecutionData> findExecutionsByFlow(String flowId, int limit, int offset) {
        return jdbcTemplate.query("""
            SELECT id, tenant_id, flow_id, status, started_by, trigger_record_id,
                   state_data, current_node_id, error_message, step_count, duration_ms,
                   initial_input, is_test, started_at, completed_at
            FROM flow_execution
            WHERE flow_id = ?
            ORDER BY started_at DESC
            LIMIT ? OFFSET ?
            """,
            (rs, rowNum) -> mapExecution(rs),
            flowId, limit, offset);
    }

    @Override
    public List<FlowExecutionData> findWaitingExecutions() {
        return jdbcTemplate.query("""
            SELECT id, tenant_id, flow_id, status, started_by, trigger_record_id,
                   state_data, current_node_id, error_message, step_count, duration_ms,
                   initial_input, is_test, started_at, completed_at
            FROM flow_execution
            WHERE status = ?
            """,
            (rs, rowNum) -> mapExecution(rs),
            FlowExecutionData.STATUS_WAITING);
    }

    // -------------------------------------------------------------------------
    // Pending Resume
    // -------------------------------------------------------------------------

    @Override
    public String createPendingResume(String executionId, String tenantId,
                                      Instant resumeAt, String resumeEvent) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO flow_pending_resume (id, execution_id, tenant_id, resume_at, resume_event)
            VALUES (?, ?, ?, ?, ?)
            """,
            id, executionId, tenantId,
            resumeAt != null ? Timestamp.from(resumeAt) : null,
            resumeEvent);
        return id;
    }

    @Override
    public List<String> claimPendingResumes(String claimedBy, int limit) {
        // Use UPDATE ... RETURNING with optimistic locking
        return jdbcTemplate.queryForList("""
            UPDATE flow_pending_resume
            SET claimed_by = ?
            WHERE id IN (
                SELECT id FROM flow_pending_resume
                WHERE claimed_by IS NULL AND resume_at <= NOW()
                ORDER BY resume_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING execution_id
            """,
            String.class,
            claimedBy, limit);
    }

    @Override
    public Optional<String> claimPendingResumeByEvent(String resumeEvent, String claimedBy) {
        List<String> results = jdbcTemplate.queryForList("""
            UPDATE flow_pending_resume
            SET claimed_by = ?
            WHERE id = (
                SELECT id FROM flow_pending_resume
                WHERE claimed_by IS NULL AND resume_event = ?
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING execution_id
            """,
            String.class,
            claimedBy, resumeEvent);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void deletePendingResume(String executionId) {
        jdbcTemplate.update("""
            DELETE FROM flow_pending_resume WHERE execution_id = ?
            """,
            executionId);
    }

    // -------------------------------------------------------------------------
    // Audit Trail
    // -------------------------------------------------------------------------

    @Override
    public void logAuditEvent(String tenantId, String flowId, String action,
                              String userId, Map<String, Object> details) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO flow_audit_log (id, tenant_id, flow_id, action, user_id, details)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """,
            id, tenantId, flowId, action, userId, toJson(details));
    }

    // -------------------------------------------------------------------------
    // ResultSet Mapping
    // -------------------------------------------------------------------------

    private FlowExecutionData mapExecution(ResultSet rs) throws SQLException {
        return new FlowExecutionData(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("flow_id"),
            rs.getString("status"),
            rs.getString("started_by"),
            rs.getString("trigger_record_id"),
            fromJson(rs.getString("state_data")),
            rs.getString("current_node_id"),
            rs.getString("error_message"),
            rs.getInt("step_count"),
            rs.getObject("duration_ms") != null ? rs.getInt("duration_ms") : null,
            fromJson(rs.getString("initial_input")),
            rs.getBoolean("is_test"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at"))
        );
    }

    private FlowStepLogData mapStepLog(ResultSet rs) throws SQLException {
        return new FlowStepLogData(
            rs.getString("id"),
            rs.getString("execution_id"),
            rs.getString("state_id"),
            rs.getString("state_name"),
            rs.getString("state_type"),
            rs.getString("status"),
            fromJson(rs.getString("input_snapshot")),
            fromJson(rs.getString("output_snapshot")),
            rs.getString("error_message"),
            rs.getString("error_code"),
            rs.getInt("attempt_number"),
            rs.getObject("duration_ms") != null ? rs.getInt("duration_ms") : null,
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at"))
        );
    }

    // -------------------------------------------------------------------------
    // JSON Helpers
    // -------------------------------------------------------------------------

    private String toJson(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize map to JSON", e);
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON to map: {}", json, e);
            return Map.of();
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
