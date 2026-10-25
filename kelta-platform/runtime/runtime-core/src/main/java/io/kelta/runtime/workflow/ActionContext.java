package io.kelta.runtime.workflow;

import java.util.List;
import java.util.Map;

/**
 * Context passed to every {@link ActionHandler} during workflow execution.
 * Contains all information needed to execute an action: the triggering record data,
 * the action configuration, resolved data payload, and metadata.
 *
 * <p>{@code triggerDepth} is the number of {@code TRIGGER_FLOW} hops that led to the
 * running flow execution (zero outside a flow, or for a flow not started by
 * {@code TRIGGER_FLOW}); {@code TRIGGER_FLOW} uses it to bound flow-to-flow loops.
 *
 * @since 1.0.0
 */
public record ActionContext(
    String tenantId,
    String collectionId,
    String collectionName,
    String recordId,
    Map<String, Object> data,
    Map<String, Object> previousData,
    List<String> changedFields,
    String userId,
    String actionConfigJson,
    String workflowRuleId,
    String executionLogId,
    Map<String, Object> resolvedData,
    int triggerDepth,
    Map<String, Object> stateData
) {

    /**
     * Builder for creating ActionContext instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tenantId;
        private String collectionId;
        private String collectionName;
        private String recordId;
        private Map<String, Object> data;
        private Map<String, Object> previousData;
        private List<String> changedFields;
        private String userId;
        private String actionConfigJson;
        private String workflowRuleId;
        private String executionLogId;
        private Map<String, Object> resolvedData;
        private int triggerDepth;
        private Map<String, Object> stateData;

        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder collectionId(String collectionId) { this.collectionId = collectionId; return this; }
        public Builder collectionName(String collectionName) { this.collectionName = collectionName; return this; }
        public Builder recordId(String recordId) { this.recordId = recordId; return this; }
        public Builder data(Map<String, Object> data) { this.data = data; return this; }
        public Builder previousData(Map<String, Object> previousData) { this.previousData = previousData; return this; }
        public Builder changedFields(List<String> changedFields) { this.changedFields = changedFields; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder actionConfigJson(String actionConfigJson) { this.actionConfigJson = actionConfigJson; return this; }
        public Builder workflowRuleId(String workflowRuleId) { this.workflowRuleId = workflowRuleId; return this; }
        public Builder executionLogId(String executionLogId) { this.executionLogId = executionLogId; return this; }
        public Builder resolvedData(Map<String, Object> resolvedData) { this.resolvedData = resolvedData; return this; }
        public Builder triggerDepth(int triggerDepth) { this.triggerDepth = triggerDepth; return this; }
        public Builder stateData(Map<String, Object> stateData) { this.stateData = stateData; return this; }

        public ActionContext build() {
            return new ActionContext(
                tenantId, collectionId, collectionName, recordId,
                data, previousData, changedFields, userId,
                actionConfigJson, workflowRuleId, executionLogId, resolvedData,
                triggerDepth, stateData
            );
        }
    }
}
