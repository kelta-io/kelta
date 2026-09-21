package io.kelta.runtime.flow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage abstraction for flow definitions and executions.
 * <p>
 * Separates the flow engine from the underlying database implementation.
 * The engine uses this interface to persist execution state and step logs.
 *
 * @since 1.0.0
 */
public interface FlowStore {

    // -------------------------------------------------------------------------
    // Execution Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates a new flow execution record.
     *
     * @param tenantId        the tenant ID
     * @param flowId          the flow being executed
     * @param startedBy       the user ID who started the execution (null for trigger-started)
     * @param triggerRecordId the record that triggered the execution (null for non-record triggers)
     * @param initialInput    the initial input data
     * @param isTest          whether this is a test execution
     * @return the generated execution ID
     */
    String createExecution(String tenantId, String flowId, String startedBy,
                           String triggerRecordId, Map<String, Object> initialInput, boolean isTest);

    /**
     * Loads a flow execution by ID.
     *
     * @param executionId the execution ID
     * @return the execution data, or empty if not found
     */
    Optional<FlowExecutionData> loadExecution(String executionId);

    /**
     * Updates the execution state during transitions.
     *
     * @param executionId   the execution ID
     * @param currentNodeId the current state ID being executed
     * @param stateData     the current state data
     * @param status        the execution status
     * @param stepCount     the total step count
     */
    void updateExecutionState(String executionId, String currentNodeId,
                              Map<String, Object> stateData, String status, int stepCount);

    /**
     * Marks an execution as completed (either success or failure).
     *
     * @param executionId  the execution ID
     * @param status       the terminal status (COMPLETED, FAILED, CANCELLED)
     * @param stateData    the final state data
     * @param errorMessage error message if failed (null otherwise)
     * @param durationMs   total execution duration in milliseconds
     * @param stepCount    total number of steps executed
     */
    void completeExecution(String executionId, String status, Map<String, Object> stateData,
                           String errorMessage, int durationMs, int stepCount);

    /**
     * Marks an execution as cancelled.
     *
     * @param executionId the execution ID
     */
    void cancelExecution(String executionId);

    // -------------------------------------------------------------------------
    // Step Logging
    // -------------------------------------------------------------------------

    /**
     * Logs a step execution entry.
     *
     * @param executionId   the parent execution ID
     * @param stateId       the state ID within the flow definition
     * @param stateName     the human-readable state name
     * @param stateType     the state type (Task, Choice, etc.)
     * @param inputSnapshot state data at step entry
     * @param outputSnapshot state data at step exit (null if not yet complete)
     * @param status        step status (RUNNING, SUCCEEDED, FAILED, SKIPPED)
     * @param errorMessage  error message if failed
     * @param errorCode     error code if failed
     * @param durationMs    step duration in milliseconds (null if not yet complete)
     * @param attemptNumber retry attempt number (1-based)
     * @return the generated step log ID
     */
    String logStepExecution(String executionId, String stateId, String stateName,
                            String stateType, Map<String, Object> inputSnapshot,
                            Map<String, Object> outputSnapshot, String status,
                            String errorMessage, String errorCode,
                            Integer durationMs, int attemptNumber);

    /**
     * Updates a step log entry after completion.
     *
     * @param stepLogId      the step log ID
     * @param outputSnapshot state data at step exit
     * @param status         final step status
     * @param errorMessage   error message if failed
     * @param errorCode      error code if failed
     * @param durationMs     step duration in milliseconds
     */
    void updateStepLog(String stepLogId, Map<String, Object> outputSnapshot,
                       String status, String errorMessage, String errorCode, int durationMs);

    /**
     * Loads step logs for an execution.
     *
     * @param executionId the execution ID
     * @return the step log entries ordered by start time
     */
    List<FlowStepLogData> loadStepLogs(String executionId);

    // -------------------------------------------------------------------------
    // Flow Definition Lookup
    // -------------------------------------------------------------------------

    /**
     * Loads a flow's published definition JSON by flow ID, scoped to the tenant.
     * <p>
     * Used by the {@code InvokeFlow} state to fetch a target flow at runtime.
     * Returns the raw definition JSON string (the same format that
     * {@link FlowDefinitionParser} consumes). Tenant scoping prevents one
     * tenant's flow from invoking another tenant's flow.
     *
     * @param tenantId the tenant ID to scope the lookup to
     * @param flowId   the flow ID
     * @return the definition JSON, or empty if the flow does not exist in this tenant
     */
    Optional<String> findFlowDefinitionById(String tenantId, String flowId);

    /**
     * Loads a flow's published definition JSON by name, scoped to the tenant.
     * <p>
     * Flow names are unique within a tenant (enforced by {@code uq_flow}
     * UNIQUE constraint), so this returns at most one definition. Use this
     * variant when the invoking flow references the target by human-readable
     * name rather than UUID.
     *
     * @param tenantId the tenant ID to scope the lookup to
     * @param flowName the flow name
     * @return the definition JSON, or empty if no flow with this name exists in the tenant
     */
    Optional<String> findFlowDefinitionByName(String tenantId, String flowName);

    /**
     * Resolves a tenant's URL slug from its ID.
     * <p>
     * Needed by code paths that enter a tenant scope from outside any request or
     * event context (Wait-state resume): schema-per-tenant table resolution in the
     * storage adapter keys off {@code TenantContext.getSlug()}, and a scope bound
     * with the ID alone silently falls back to the {@code public} schema.
     *
     * @param tenantId the tenant ID
     * @return the tenant slug, or empty if the tenant does not exist
     */
    Optional<String> findTenantSlug(String tenantId);

    // -------------------------------------------------------------------------
    // Execution Queries
    // -------------------------------------------------------------------------

    /**
     * Finds executions for a specific flow.
     *
     * @param flowId the flow ID
     * @param limit  max number of results
     * @param offset pagination offset
     * @return the executions ordered by start time descending
     */
    List<FlowExecutionData> findExecutionsByFlow(String flowId, int limit, int offset);

    /**
     * Finds executions in WAITING status that are due for resume.
     *
     * @return executions that need to be resumed
     */
    List<FlowExecutionData> findWaitingExecutions();

    // -------------------------------------------------------------------------
    // Pending Resume (for Wait states)
    // -------------------------------------------------------------------------

    /**
     * Creates a pending resume entry for a Wait state.
     *
     * @param executionId the execution ID
     * @param tenantId    the tenant ID
     * @param resumeAt    when to resume (null if waiting for event)
     * @param resumeEvent the event name to wait for (null if time-based)
     * @return the pending resume ID
     */
    String createPendingResume(String executionId, String tenantId,
                               Instant resumeAt, String resumeEvent);

    /**
     * Claims pending resumes that are due for processing.
     * Uses optimistic locking to prevent duplicate claims across pods.
     *
     * @param claimedBy identifier of the claiming pod
     * @param limit     max number to claim
     * @return the claimed execution IDs
     */
    List<String> claimPendingResumes(String claimedBy, int limit);

    /**
     * Claims a pending resume by event name.
     *
     * @param resumeEvent the event name
     * @param claimedBy   identifier of the claiming pod
     * @return the execution ID if found and claimed, empty otherwise
     */
    Optional<String> claimPendingResumeByEvent(String resumeEvent, String claimedBy);

    /**
     * Deletes a pending resume entry after the execution has resumed.
     *
     * @param executionId the execution ID
     */
    void deletePendingResume(String executionId);

    // -------------------------------------------------------------------------
    // Audit Trail
    // -------------------------------------------------------------------------

    /**
     * Logs a flow audit event.
     *
     * @param tenantId the tenant ID
     * @param flowId   the flow ID
     * @param action   the action (CREATED, UPDATED, ACTIVATED, DEACTIVATED, DELETED, EXECUTED, CANCELLED)
     * @param userId   the user who performed the action
     * @param details  additional details (JSON)
     */
    void logAuditEvent(String tenantId, String flowId, String action,
                       String userId, Map<String, Object> details);
}
