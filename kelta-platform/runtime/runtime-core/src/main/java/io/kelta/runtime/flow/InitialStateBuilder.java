package io.kelta.runtime.flow;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constructs the canonical initial state envelope for flow executions.
 * <p>
 * Every flow execution starts with a state object following a common structure:
 * <pre>
 * {
 *   "trigger": { "type": "...", ...metadata... },
 *   "&lt;source-key&gt;": { ...source data... },
 *   "context": { "tenantId": "...", "flowId": "...", "executionId": "..." }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class InitialStateBuilder {

    /**
     * Builds initial state from a record change event.
     *
     * @param event       the platform event wrapping a record changed payload
     * @param flowId      the flow being executed
     * @param executionId the execution ID
     * @return the initial state envelope
     */
    public Map<String, Object> buildFromRecordEvent(PlatformEvent<RecordChangedPayload> event,
                                                     String flowId, String executionId) {
        Map<String, Object> state = new LinkedHashMap<>();
        RecordChangedPayload payload = event.getPayload();

        // Trigger metadata
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "RECORD_CHANGE");
        trigger.put("changeType", payload.getChangeType().name());
        trigger.put("collectionName", payload.getCollectionName());
        trigger.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : null);
        state.put("trigger", trigger);

        // Record data
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", payload.getRecordId());
        record.put("collectionName", payload.getCollectionName());
        record.put("data", payload.getData() != null ? payload.getData() : Map.of());
        record.put("previousData", payload.getPreviousData() != null ? payload.getPreviousData() : Map.of());
        record.put("changedFields", payload.getChangedFields() != null ? payload.getChangedFields() : java.util.List.of());
        // Slim event: data is the record re-read by id (or empty), previousData is unavailable.
        if (payload.isPayloadOmitted()) {
            record.put("payloadOmitted", true);
        }
        state.put("record", record);

        // Context
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", event.getTenantId());
        context.put("userId", event.getUserId());
        context.put("flowId", flowId);
        context.put("executionId", executionId);
        state.put("context", context);

        return state;
    }

    /**
     * Builds initial state from an API invocation.
     *
     * @param inputPayload the caller-provided input
     * @param tenantId     the tenant ID
     * @param userId       the user ID
     * @param flowId       the flow being executed
     * @param executionId  the execution ID
     * @return the initial state envelope
     */
    public Map<String, Object> buildFromApiInvocation(Map<String, Object> inputPayload,
                                                       String tenantId, String userId,
                                                       String flowId, String executionId) {
        Map<String, Object> state = new LinkedHashMap<>();

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "API_INVOCATION");
        state.put("trigger", trigger);

        state.put("input", inputPayload != null ? inputPayload : Map.of());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", tenantId);
        context.put("userId", userId);
        context.put("flowId", flowId);
        context.put("executionId", executionId);
        state.put("context", context);

        return state;
    }

    /**
     * Builds initial state from a scheduled execution.
     *
     * @param triggerConfig the flow's trigger config (may contain inputData)
     * @param tenantId      the tenant ID
     * @param flowId        the flow being executed
     * @param executionId   the execution ID
     * @return the initial state envelope
     */
    /**
     * Builds initial state from an incoming webhook request.
     *
     * @param payload     the webhook request body
     * @param headers     selected headers from the webhook request
     * @param tenantId    the tenant ID
     * @param flowId      the flow being executed
     * @param executionId the execution ID
     * @return the initial state envelope
     */
    public Map<String, Object> buildFromWebhook(Map<String, Object> payload,
                                                  Map<String, String> headers,
                                                  String tenantId,
                                                  String flowId, String executionId) {
        Map<String, Object> state = new LinkedHashMap<>();

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "WEBHOOK");
        state.put("trigger", trigger);

        state.put("input", payload != null ? payload : Map.of());
        state.put("headers", headers != null ? headers : Map.of());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", tenantId);
        context.put("flowId", flowId);
        context.put("executionId", executionId);
        state.put("context", context);

        return state;
    }

    /**
     * Builds initial state from a scheduled execution.
     *
     * @param triggerConfig the flow's trigger config (may contain inputData)
     * @param tenantId      the tenant ID
     * @param flowId        the flow being executed
     * @param executionId   the execution ID
     * @return the initial state envelope
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildFromSchedule(Map<String, Object> triggerConfig,
                                                  String tenantId,
                                                  String flowId, String executionId) {
        Map<String, Object> state = new LinkedHashMap<>();

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "SCHEDULED");
        state.put("trigger", trigger);

        // Static input data from trigger config
        Map<String, Object> inputData = triggerConfig != null
            ? (Map<String, Object>) triggerConfig.getOrDefault("inputData", Map.of())
            : Map.of();
        state.put("input", inputData);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", tenantId);
        context.put("flowId", flowId);
        context.put("executionId", executionId);
        state.put("context", context);

        return state;
    }

    /**
     * Builds initial state from a NATS trigger message
     * ({@code kelta.trigger.<tenantId>.<topic>}).
     *
     * @param payload     the parsed message body (JSON object; non-JSON raw
     *                    payloads arrive wrapped as {@code {"raw": "<text>"}})
     * @param subject     the full NATS subject the message arrived on
     * @param topic       the flow-facing topic (subject minus the platform prefix)
     * @param tenantId    the tenant ID
     * @param flowId      the flow being executed
     * @param executionId the execution ID
     * @return the initial state envelope
     */
    public Map<String, Object> buildFromNatsMessage(Map<String, Object> payload,
                                                     String subject, String topic,
                                                     String tenantId,
                                                     String flowId, String executionId) {
        Map<String, Object> state = new LinkedHashMap<>();

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "NATS_MESSAGE");
        trigger.put("subject", subject);
        trigger.put("topic", topic);
        state.put("trigger", trigger);

        state.put("input", payload != null ? payload : Map.of());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", tenantId);
        context.put("flowId", flowId);
        context.put("executionId", executionId);
        state.put("context", context);

        return state;
    }
}
