package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.runtime.flow.*;
import io.kelta.worker.repository.FlowRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * REST endpoints for flow execution management.
 * <p>
 * Provides endpoints to execute flows, cancel executions, view execution
 * details, and retrieve step-level logs. Returns JSON:API format.
 * <p>
 * The gateway checks only the writes under {@code /api/flows/**}; every read here leaves it with
 * just {@code API_ACCESS} (a portal member's page action polls its own run), and the worker's
 * record advice does not filter these bodies. So each read authorizes itself. Internal staff
 * holding {@code MANAGE_WORKFLOWS} or {@code VIEW_SETUP} read every execution in full, its steps,
 * the per-flow execution list and the versions. Anyone else — a portal member always — reads
 * only the status of an execution whose {@code started_by} is them (another one is a 404, as if
 * it did not exist) and is refused the rest: {@code started_by} is the run's resolved actor, so
 * it is also the member whose record edit triggered a run, and that run's state was assembled on
 * the internal tier, not from records they may read.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/flows")
@ConditionalOnBean(FlowEngine.class)
public class FlowExecutionController {

    private static final Logger log = LoggerFactory.getLogger(FlowExecutionController.class);

    /**
     * Lets a test run start an inactive flow, start any flow, and pass a pre-built {@code state}.
     * The gateway exempts {@code /execute} from it, so a caller without it may start only a
     * member-launchable flow, with server-built context.
     */
    static final String MANAGE_WORKFLOWS = "MANAGE_WORKFLOWS";

    /** Read-only access to setup; with {@link #MANAGE_WORKFLOWS}, reads every execution and version. */
    static final String VIEW_SETUP = "VIEW_SETUP";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowEngine flowEngine;
    private final FlowActorResolver flowActorResolver;
    private final FlowStore flowStore;
    private final InitialStateBuilder initialStateBuilder;
    private final FlowRepository flowRepository;
    private final AdminPermissionGuard permissionGuard;

    public FlowExecutionController(FlowEngine flowEngine,
                                    FlowStore flowStore,
                                    InitialStateBuilder initialStateBuilder,
                                    FlowRepository flowRepository,
                                    FlowActorResolver flowActorResolver,
                                    AdminPermissionGuard permissionGuard) {
        this.flowEngine = flowEngine;
        this.flowActorResolver = flowActorResolver;
        this.flowStore = flowStore;
        this.initialStateBuilder = initialStateBuilder;
        this.flowRepository = flowRepository;
        this.permissionGuard = permissionGuard;
    }

    /**
     * Executes a flow with optional input payload.
     * Used for AUTOLAUNCHED flows and manual trigger from UI.
     * <p>
     * A caller without MANAGE_WORKFLOWS (a portal member running a page {@code runFlow} action)
     * may start only a {@code SCREEN} flow or one whose {@code trigger_config} has
     * {@code memberLaunchable: true}, and may not send {@code state}: its context always comes
     * from the request headers.
     */
    @PostMapping("/{flowId}/execute")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> executeFlow(
            @PathVariable String flowId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            HttpServletRequest request) {

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

        boolean isTest = body != null && Boolean.TRUE.equals(body.get("test"));
        boolean manager = permissionGuard.holdsAny(request, MANAGE_WORKFLOWS);
        if (!Boolean.TRUE.equals(flow.get("active")) && !(isTest && manager)) {
            log.warn("Execute rejected for inactive flow: flowId={}", flowId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    JsonApiResponseBuilder.error("409", "FLOW_INACTIVE", "Conflict", "Flow is not active"));
        }

        if (!manager && !isMemberLaunchable(flow)) {
            log.warn("Execute rejected for flow not launchable by members: flowId={}, userId={}", flowId, userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "FLOW_NOT_LAUNCHABLE", "Forbidden",
                            "Starting this flow requires the MANAGE_WORKFLOWS permission"));
        }

        if (!manager && body != null && body.containsKey("state")) {
            log.warn("Execute rejected for state override without MANAGE_WORKFLOWS: flowId={}, userId={}",
                    flowId, userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    JsonApiResponseBuilder.error("403", "FLOW_STATE_OVERRIDE_FORBIDDEN", "Forbidden",
                            "Passing 'state' requires the MANAGE_WORKFLOWS permission; send 'input' instead"));
        }

        // If no tenantId from header, use the flow's tenant
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = (String) flow.get("tenant_id");
        }

        // Build initial state
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
     * Gets execution details including state data. A caller without {@link #checkFullExecutionAccess}
     * gets only the status fields of an execution they started ({@link #executionStatusMap}).
     */
    @GetMapping("/executions/{executionId}")
    public ResponseEntity<Map<String, Object>> getExecution(
            @PathVariable String executionId,
            HttpServletRequest request) {

        log.debug("Get execution request: executionId={}", executionId);

        boolean full = checkFullExecutionAccess(request);
        Optional<FlowExecutionData> execution = full
                ? flowStore.loadExecution(executionId)
                : loadOwnExecution(executionId);
        if (execution.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> execMap = full ? executionToMap(execution.get()) : executionStatusMap(execution.get());
        String id = (String) execMap.remove("id");
        return ResponseEntity.ok(JsonApiResponseBuilder.single("flow-executions", id, execMap));
    }

    /**
     * Gets step-level execution logs for an execution. Each step carries the state's input and
     * output snapshots, so a caller without {@link #checkFullExecutionAccess} gets 404 — for their
     * own run too — before anything is loaded.
     */
    @GetMapping("/executions/{executionId}/steps")
    public ResponseEntity<Map<String, Object>> getExecutionSteps(
            @PathVariable String executionId,
            HttpServletRequest request) {

        log.debug("Get execution steps request: executionId={}", executionId);

        if (!checkFullExecutionAccess(request)) {
            return ResponseEntity.notFound().build();
        }
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
     * record activity timeline. Newest first. A caller without {@link #checkFullExecutionAccess}
     * sees only the executions they started, without their duration or error message.
     */
    @GetMapping("/record-executions")
    public ResponseEntity<Map<String, Object>> listExecutionsByRecord(
            @RequestParam String recordId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {

        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        boolean full = checkFullExecutionAccess(request);
        List<Map<String, Object>> rows;
        if (full) {
            rows = flowRepository.findExecutionsByTriggerRecord(recordId, effectiveLimit);
        } else {
            Optional<String> callerId = CallerContext.current().map(CallerContext::userId);
            rows = callerId.isEmpty() ? List.of()
                    : flowRepository.findExecutionsByTriggerRecordStartedBy(recordId, callerId.get(), effectiveLimit);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", row.get("id"));
            record.put("flowId", row.get("flow_id"));
            record.put("flowName", row.get("flow_name"));
            record.put("status", row.get("status"));
            record.put("startedAt", row.get("started_at") != null ? row.get("started_at").toString() : null);
            record.put("completedAt", row.get("completed_at") != null ? row.get("completed_at").toString() : null);
            if (full) {
                record.put("durationMs", row.get("duration_ms"));
                record.put("errorMessage", row.get("error_message"));
            }
            records.add(record);
        }

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-executions", records));
    }

    /**
     * Lists executions for a specific flow. Needs {@link #MANAGE_WORKFLOWS} or {@link #VIEW_SETUP}.
     */
    @GetMapping("/{flowId}/flow-executions")
    public ResponseEntity<Map<String, Object>> listExecutions(
            @PathVariable String flowId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {

        permissionGuard.requireInternalAny(request, MANAGE_WORKFLOWS, VIEW_SETUP);
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
     * Lists all published versions for a flow. Needs {@link #MANAGE_WORKFLOWS} or {@link #VIEW_SETUP}.
     */
    @GetMapping("/{flowId}/versions")
    public ResponseEntity<Map<String, Object>> listVersions(@PathVariable String flowId,
                                                            HttpServletRequest request) {
        permissionGuard.requireInternalAny(request, MANAGE_WORKFLOWS, VIEW_SETUP);
        log.debug("List flow versions request: flowId={}", flowId);

        List<Map<String, Object>> versions = flowRepository.findFlowVersions(flowId);
        Map<String, Object> meta = Map.of("flowId", flowId, "count", versions.size());
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("flow-versions", versions, meta));
    }

    /**
     * Gets a specific version's definition. Needs {@link #MANAGE_WORKFLOWS} or {@link #VIEW_SETUP}.
     */
    @GetMapping("/{flowId}/versions/{versionNumber}")
    public ResponseEntity<Map<String, Object>> getVersion(
            @PathVariable String flowId,
            @PathVariable int versionNumber,
            HttpServletRequest request) {

        permissionGuard.requireInternalAny(request, MANAGE_WORKFLOWS, VIEW_SETUP);
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

    /**
     * Whether the caller reads every execution in full: internal staff whose profile holds
     * {@link #MANAGE_WORKFLOWS} or {@link #VIEW_SETUP}. Never a {@code PORTAL} caller, whatever
     * its profile grants. Decided before any execution is loaded.
     */
    private boolean checkFullExecutionAccess(HttpServletRequest request) {
        return permissionGuard.internalHoldsAny(request, MANAGE_WORKFLOWS, VIEW_SETUP);
    }

    /**
     * The execution when its {@code started_by} is the bound caller; empty otherwise (another
     * user's answers the same 404 as a missing one), and without a lookup when no caller is bound.
     */
    private Optional<FlowExecutionData> loadOwnExecution(String executionId) {
        Optional<String> callerId = CallerContext.current().map(CallerContext::userId);
        if (callerId.isEmpty()) {
            return Optional.empty();
        }
        Optional<FlowExecutionData> execution = flowStore.loadExecution(executionId)
                .filter(exec -> exec.startedBy() != null && exec.startedBy().equalsIgnoreCase(callerId.get()));
        if (execution.isEmpty()) {
            log.debug("Execution read refused: executionId={} is not the caller's", executionId);
        }
        return execution;
    }

    /** A {@code SCREEN} flow, or one whose {@code trigger_config} sets {@code memberLaunchable: true}. */
    static boolean isMemberLaunchable(Map<String, Object> flow) {
        if ("SCREEN".equals(flow.get("flow_type"))) {
            return true;
        }
        if (!(flow.get("trigger_config") instanceof String json) || json.isBlank()) {
            return false;
        }
        try {
            Object config = MAPPER.readValue(json, Object.class);
            if (config instanceof String inner) {
                config = MAPPER.readValue(inner, Object.class);
            }
            if (config instanceof Map<?, ?> map && "jsonb".equals(map.get("type"))
                    && map.get("value") instanceof String inner) {
                config = MAPPER.readValue(inner, Object.class);
            }
            return config instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("memberLaunchable"));
        } catch (Exception e) {
            log.warn("Unreadable trigger_config on flow {}: {}", flow.get("id"), e.getMessage());
            return false;
        }
    }

    /**
     * What a caller without {@link #checkFullExecutionAccess} sees of their own run: enough to poll
     * it to a terminal status ({@code runFlow} with {@code awaitResult}, {@code kelta flows execute
     * --wait}, MCP {@code get_flow_run}), and none of its state, input or error detail.
     */
    static Map<String, Object> executionStatusMap(FlowExecutionData exec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", exec.id());
        map.put("flowId", exec.flowId());
        map.put("status", exec.status());
        map.put("startedAt", exec.startedAt() != null ? exec.startedAt().toString() : null);
        map.put("completedAt", exec.completedAt() != null ? exec.completedAt().toString() : null);
        return map;
    }

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
