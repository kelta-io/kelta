package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.runtime.flow.*;
import io.kelta.worker.repository.FlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for flow execution management.
 * <p>
 * Provides endpoints to execute flows, cancel executions, view execution
 * details, and retrieve step-level logs. Returns JSON:API format.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/flows")
@ConditionalOnBean(FlowEngine.class)
public class FlowExecutionController {

    private static final Logger log = LoggerFactory.getLogger(FlowExecutionController.class);

    private final FlowEngine flowEngine;
    private final FlowActorResolver flowActorResolver;
    private final FlowStore flowStore;
    private final InitialStateBuilder initialStateBuilder;
    private final FlowRepository flowRepository;

    public FlowExecutionController(FlowEngine flowEngine,
                                    FlowStore flowStore,
                                    InitialStateBuilder initialStateBuilder,
                                    FlowRepository flowRepository,
                                    FlowActorResolver flowActorResolver) {
        this.flowEngine = flowEngine;
        this.flowActorResolver = flowActorResolver;
        this.flowStore = flowStore;
        this.initialStateBuilder = initialStateBuilder;
        this.flowRepository = flowRepository;
    }

    /**
     * Executes a flow with optional input payload.
     * Used for AUTOLAUNCHED flows and manual trigger from UI.
     */
    @PostMapping("/{flowId}/execute")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> executeFlow(
            @PathVariable String flowId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {

        log.debug("Execute flow request: flowId={}, tenantId={}", flowId, tenantId);

        // Load the flow definition
        Optional<Map<String, Object>> flowOpt = flowRepository.findFlowById(flowId);
        if (flowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> flow = flowOpt.get();
        String definitionJson = (String) flow.get("definition");
        if (definitionJson == null || definitionJson.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Flow has no definition"));
        }

        // If no tenantId from header, use the flow's tenant
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = (String) flow.get("tenant_id");
        }

        // Build initial state
        boolean isTest = body != null && Boolean.TRUE.equals(body.get("test"));
        String executionId = UUID.randomUUID().toString();

        Map<String, Object> initialState;

        // Support a "state" key for providing a pre-built initial state
        if (body != null && body.containsKey("state") && body.get("state") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> customState = new LinkedHashMap<>((Map<String, Object>) body.get("state"));
            if (!customState.containsKey("context")) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("tenantId", tenantId);
                ctx.put("userId", userId);
                ctx.put("flowId", flowId);
                ctx.put("executionId", executionId);
                customState.put("context", ctx);
            }
            initialState = customState;
        } else {
            Map<String, Object> inputPayload = Map.of();
            if (body != null && body.containsKey("input")) {
                Object input = body.get("input");
                if (input instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inputMap = (Map<String, Object>) input;
                    inputPayload = inputMap;
                }
            }
            initialState = initialStateBuilder.buildFromApiInvocation(
                    inputPayload, tenantId, userId, flowId, executionId);
        }

        String resultExecutionId = flowEngine.startExecution(
                tenantId, flowId, definitionJson, initialState,
                flowActorResolver.resolve(tenantId, flowId, userId), null, isTest);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("flowId", flowId);
        attrs.put("status", "RUNNING");

        log.info("Started flow execution: flowId={}, executionId={}", flowId, resultExecutionId);
        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("flow-executions", resultExecutionId, attrs));
    }

    /**
     * Cancels a running flow execution.
     */
    @PostMapping("/executions/{executionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelExecution(
            @PathVariable String executionId) {

        log.debug("Cancel execution request: executionId={}", executionId);

        Optional<FlowExecutionData> execution = flowStore.loadExecution(executionId);
        if (execution.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FlowExecutionData exec = execution.get();
        if (exec.isTerminal()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request",
                            "Execution is already in terminal state: " + exec.status()));
        }

        flowEngine.cancelExecution(executionId);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("status", "CANCELLED");

        log.info("Cancelled flow execution: executionId={}", executionId);
        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("flow-executions", executionId, attrs));
    }

    /**
     * Gets execution details including state data.
     */
    @GetMapping("/executions/{executionId}")
    public ResponseEntity<Map<String, Object>> getExecution(
            @PathVariable String executionId) {

        log.debug("Get execution request: executionId={}", executionId);

        Optional<FlowExecutionData> execution = flowStore.loadExecution(executionId);
        if (execution.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> execMap = executionToMap(execution.get());
        String id = (String) execMap.remove("id");
        return ResponseEntity.ok(JsonApiResponseBuilder.single("flow-executions", id, execMap));
    }

    /**
     * Gets step-level execution logs for an execution.
     */
    @GetMapping("/executions/{executionId}/steps")
    public ResponseEntity<Map<String, Object>> getExecutionSteps(
            @PathVariable String executionId) {

        log.debug("Get execution steps request: executionId={}", executionId);

        Optional<FlowExecutionData> execution = flowStore.loadExecution(executionId);
        if (execution.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<FlowStepLogData> steps = flowStore.loadStepLogs(executionId);

        List<Map<String, Object>> stepList = new ArrayList<>();
        for (FlowStepLogData step : steps) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("id", step.id());
            stepMap.put("stateId", step.stateId());
            stepMap.put("stateName", step.stateName());
            stepMap.put("stateType", step.stateType());
            stepMap.put("status", step.status());
            stepMap.put("inputSnapshot", step.inputSnapshot());
            stepMap.put("outputSnapshot", step.outputSnapshot());
            stepMap.put("errorMessage", step.errorMessage());
            stepMap.put("errorCode", step.errorCode());
            stepMap.put("attemptNumber", step.attemptNumber());
            stepMap.put("durationMs", step.durationMs());
            stepMap.put("startedAt", step.startedAt() != null ? step.startedAt().toString() : null);
            stepMap.put("completedAt", step.completedAt() != null ? step.completedAt().toString() : null);
            stepList.add(stepMap);
        }

        Map<String, Object> meta = Map.of("executionId", executionId, "totalSteps", stepList.size());
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-steps", stepList, meta));
    }

    /**
     * Retries a failed or cancelled execution.
     */
    @PostMapping("/executions/{executionId}/retry")
    public ResponseEntity<Map<String, Object>> retryExecution(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "full") String mode) {

        log.debug("Retry execution request: executionId={}, mode={}", executionId, mode);

        Optional<FlowExecutionData> execution = flowStore.loadExecution(executionId);
        if (execution.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FlowExecutionData exec = execution.get();
        if (!exec.isTerminal()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request",
                            "Can only retry terminal executions. Current status: " + exec.status()));
        }

        // Load the flow definition
        Optional<Map<String, Object>> flowOpt = flowRepository.findFlowById(exec.flowId());
        if (flowOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Flow not found: " + exec.flowId()));
        }

        String definitionJson = (String) flowOpt.get().get("definition");
        if (definitionJson == null || definitionJson.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Flow has no definition"));
        }

        Map<String, Object> initialState;
        if ("from-failure".equals(mode)) {
            List<FlowStepLogData> steps = flowStore.loadStepLogs(executionId);
            FlowStepLogData failedStep = null;
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (FlowStepLogData.STATUS_FAILED.equals(steps.get(i).status())) {
                    failedStep = steps.get(i);
                    break;
                }
            }
            if (failedStep == null || failedStep.inputSnapshot() == null) {
                return ResponseEntity.badRequest().body(
                        JsonApiResponseBuilder.error("400", "Bad Request",
                                "No failed step with input snapshot found. Use mode=full instead."));
            }
            initialState = failedStep.inputSnapshot();
        } else {
            initialState = exec.initialInput() != null ? exec.initialInput() : Map.of();
        }

        String newExecutionId = flowEngine.startExecution(
                exec.tenantId(), exec.flowId(), definitionJson, initialState,
                flowActorResolver.resolve(exec.tenantId(), exec.flowId(), exec.startedBy()),
                exec.triggerRecordId(), exec.isTest());

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("originalExecutionId", executionId);
        attrs.put("flowId", exec.flowId());
        attrs.put("mode", mode);
        attrs.put("status", "RUNNING");

        log.info("Retried execution: original={}, new={}, mode={}", executionId, newExecutionId, mode);
        return ResponseEntity.ok(
                JsonApiResponseBuilder.single("flow-executions", newExecutionId, attrs));
    }

    /**
     * Lists executions triggered by a specific record — consumed by the
     * record activity timeline. Newest first.
     */
    @GetMapping("/record-executions")
    public ResponseEntity<Map<String, Object>> listExecutionsByRecord(
            @RequestParam String recordId,
            @RequestParam(defaultValue = "20") int limit) {

        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> rows = flowRepository.findExecutionsByTriggerRecord(recordId, effectiveLimit);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", row.get("id"));
            record.put("flowId", row.get("flow_id"));
            record.put("flowName", row.get("flow_name"));
            record.put("status", row.get("status"));
            record.put("startedAt", row.get("started_at") != null ? row.get("started_at").toString() : null);
            record.put("completedAt", row.get("completed_at") != null ? row.get("completed_at").toString() : null);
            record.put("durationMs", row.get("duration_ms"));
            record.put("errorMessage", row.get("error_message"));
            records.add(record);
        }

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-executions", records));
    }

    /**
     * Lists executions for a specific flow.
     */
    @GetMapping("/{flowId}/flow-executions")
    public ResponseEntity<Map<String, Object>> listExecutions(
            @PathVariable String flowId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        log.debug("List executions request: flowId={}, limit={}, offset={}", flowId, limit, offset);

        List<FlowExecutionData> executions = flowStore.findExecutionsByFlow(flowId, limit, offset);

        List<Map<String, Object>> executionList = new ArrayList<>();
        for (FlowExecutionData exec : executions) {
            executionList.add(executionToMap(exec));
        }

        Map<String, Object> meta = Map.of("flowId", flowId, "count", executionList.size());
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-executions", executionList, meta));
    }

    /**
     * Returns all available flow task resources (built-in + runtime modules).
     */
    @GetMapping("/resources")
    public ResponseEntity<Map<String, Object>> getResources() {
        log.debug("Get flow resources request");

        List<Map<String, Object>> resources = new ArrayList<>();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-resources", resources));
    }

    // -------------------------------------------------------------------------
    // Flow Versioning
    // -------------------------------------------------------------------------

    /**
     * Publishes the current flow definition as a new version.
     */
    @PostMapping("/{flowId}/publish")
    public ResponseEntity<Map<String, Object>> publishVersion(
            @PathVariable String flowId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {

        log.debug("Publish flow version request: flowId={}", flowId);

        Optional<Map<String, Object>> flowOpt = flowRepository.findFlowById(flowId);
        if (flowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String definition = (String) flowOpt.get().get("definition");
        if (definition == null || definition.isBlank()) {
            return ResponseEntity.badRequest().body(
                    JsonApiResponseBuilder.error("400", "Bad Request", "Flow has no definition to publish"));
        }

        String changeSummary = body != null ? (String) body.get("changeSummary") : null;

        int nextVersion = flowRepository.getMaxVersionNumber(flowId) + 1;
        String versionId = UUID.randomUUID().toString();

        flowRepository.insertFlowVersion(versionId, flowId, nextVersion, definition,
                changeSummary, userId != null ? userId : "system");
        flowRepository.updateFlowPublishedVersion(flowId, nextVersion);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("flowId", flowId);
        attrs.put("versionNumber", nextVersion);
        attrs.put("changeSummary", changeSummary);

        log.info("Published flow version: flowId={}, version={}", flowId, nextVersion);
        return ResponseEntity.ok(JsonApiResponseBuilder.single("flow-versions", versionId, attrs));
    }

    /**
     * Lists all published versions for a flow.
     */
    @GetMapping("/{flowId}/versions")
    public ResponseEntity<Map<String, Object>> listVersions(@PathVariable String flowId) {
        log.debug("List flow versions request: flowId={}", flowId);

        List<Map<String, Object>> versions = flowRepository.findFlowVersions(flowId);
        Map<String, Object> meta = Map.of("flowId", flowId, "count", versions.size());
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-versions", versions, meta));
    }

    /**
     * Gets a specific version's definition.
     */
    @GetMapping("/{flowId}/versions/{versionNumber}")
    public ResponseEntity<Map<String, Object>> getVersion(
            @PathVariable String flowId,
            @PathVariable int versionNumber) {

        log.debug("Get flow version request: flowId={}, version={}", flowId, versionNumber);

        Optional<Map<String, Object>> versionOpt = flowRepository.findFlowVersion(flowId, versionNumber);
        if (versionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> attrs = new LinkedHashMap<>(versionOpt.get());
        Object idObj = attrs.remove("id");
        String versionId = idObj != null ? idObj.toString() : flowId + "-v" + versionNumber;
        return ResponseEntity.ok(JsonApiResponseBuilder.single("flow-versions", versionId, attrs));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> executionToMap(FlowExecutionData exec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", exec.id());
        map.put("flowId", exec.flowId());
        map.put("tenantId", exec.tenantId());
        map.put("status", exec.status());
        map.put("startedBy", exec.startedBy());
        map.put("triggerRecordId", exec.triggerRecordId());
        map.put("currentNodeId", exec.currentNodeId());
        map.put("stepCount", exec.stepCount());
        map.put("durationMs", exec.durationMs());
        map.put("errorMessage", exec.errorMessage());
        map.put("errorCode", exec.errorCode());
        map.put("isTest", exec.isTest());
        map.put("stateData", exec.stateData());
        map.put("initialInput", exec.initialInput());
        map.put("startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null);
        map.put("completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null);
        return map;
    }
}
