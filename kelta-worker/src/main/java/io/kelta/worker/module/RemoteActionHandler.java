package io.kelta.worker.module;

import io.kelta.runtime.flow.ActionHandlerDescriptor;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleRegistry.Endpoint;
import io.kelta.worker.service.FieldMaskingService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An external module's flow action ({@code specs/module-platform/9-external-modules.md} §3.6):
 * registered per tenant in {@code ActionHandlerRegistry} under {@code <moduleId>:<key>}, so a flow
 * Task names it exactly as it would a JAR module's action, and run as
 * {@code POST {baseUrl}/kelta/v1/actions/{key}}.
 *
 * <p>Stateless apart from its key: each call reads the install's current snapshot from the
 * {@link ExternalModuleRegistry}, so an install disabled since registration refuses at once, and
 * enforces the manifest's {@code timeoutMs} as the HTTP deadline (the flow's
 * {@code TaskState.timeoutSeconds} is not enforced by the engine).
 *
 * <p>The run's user rides as the signed {@code act} claim ({@link ExternalModuleRegistry#flowActor}),
 * so an action that serves "the calling member" (billing's checkout) works from a flow as it did
 * in a JAR module.
 *
 * <p>Outcomes: {@code success: true} → its {@code output}; {@code success: false} →
 * {@link ActionResult#failure} with {@code <error.code>: <error.message>} (flow error
 * {@code ActionFailed}); a timeout → {@code ModuleTimeoutException}; disabled, breaker open,
 * unreachable, non-2xx or an unreadable body → {@code ModuleUnavailableException}.
 */
final class RemoteActionHandler implements ActionHandler {

    /** {@code ActionResponse.output}: at most this many top-level keys. */
    static final int MAX_OUTPUT_KEYS = 1000;
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final String tenantId;
    private final String moduleId;
    private final TenantModuleData.TenantModuleActionData action;
    private final String key;
    private final ExternalModuleRegistry registry;
    private final ExternalModuleInvoker invoker;
    private final CollectionRegistry collectionRegistry;
    private final FieldMaskingService masking;
    private final ObjectMapper objectMapper;

    RemoteActionHandler(String tenantId, String moduleId, TenantModuleData.TenantModuleActionData action,
                        ExternalModuleRegistry registry, ExternalModuleInvoker invoker,
                        CollectionRegistry collectionRegistry, FieldMaskingService masking, ObjectMapper objectMapper) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.moduleId = Objects.requireNonNull(moduleId);
        this.action = Objects.requireNonNull(action);
        String prefix = moduleId + ":";
        this.key = action.actionKey().startsWith(prefix) ? action.actionKey().substring(prefix.length())
                : action.actionKey();
        this.registry = Objects.requireNonNull(registry);
        this.invoker = Objects.requireNonNull(invoker);
        this.collectionRegistry = collectionRegistry;
        this.masking = masking;
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public String getActionTypeKey() {
        return action.actionKey();
    }

    @Override
    public ActionResult execute(ActionContext context) {
        // Stable across the flow's retries of one step, so the module can deduplicate them.
        String idempotencyKey = context.executionLogId() != null && context.stepName() != null
                ? context.executionLogId() + ":" + context.stepName()
                : UUID.randomUUID().toString();
        return run(context, idempotencyKey);
    }

    /** Runs the action with an explicit {@code Idempotency-Key} (a schedule's {@code <jobId>:<fireTime>}). */
    ActionResult run(ActionContext context, String idempotencyKey) {
        Endpoint endpoint = registry.endpoint(tenantId, moduleId).orElseThrow(() ->
                ModuleUnavailableException.of(moduleId, "Module '" + moduleId + "' is not installed for this tenant"));
        ExternalModuleManifest.Action declared = endpoint.manifest().actions().stream()
                .filter(a -> a.key().equals(key)).findFirst()
                .orElseThrow(() -> ModuleUnavailableException.of(moduleId, "Module '" + moduleId
                        + "' v" + endpoint.row().version() + " no longer declares action '" + key + "'"));

        byte[] body = objectMapper.writeValueAsBytes(requestBody(context, endpoint));
        ModuleHttpClient.Call call = new ModuleHttpClient.Call("POST",
                ModuleHttpClient.ACTIONS_PATH + URLEncoder.encode(key, StandardCharsets.UTF_8), null, body,
                "application/json", Map.of(IDEMPOTENCY_KEY_HEADER, idempotencyKey),
                Duration.ofMillis(declared.timeoutMs()), registry.flowActor(tenantId, context.userId()));
        return invoker.call(endpoint.row(), ExternalModuleInvoker.Kind.ACTION, call, this::readResult);
    }

    private Map<String, Object> requestBody(ActionContext context, Endpoint endpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("input", input(context.resolvedData()));
        body.put("config", config(context.actionConfigJson()));
        String collection = context.collectionName();
        body.put("collection", collection);
        body.put("recordId", context.recordId());
        if (collection != null) {
            // A record handed to the module is filtered for its principal (§3.4), like a hook's.
            var definition = collectionRegistry == null ? null : collectionRegistry.get(collection);
            body.put("record", endpoint.access().outbound(collection, definition, context.data(), masking));
            body.put("previousRecord", endpoint.access().outbound(collection, definition, context.previousData(),
                    masking));
        } else {
            body.put("record", null);
            body.put("previousRecord", null);
        }
        body.put("changedFields", context.changedFields() == null ? List.of() : context.changedFields());
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", context.executionLogId());
        execution.put("flowId", context.workflowRuleId());
        execution.put("stepName", context.stepName());
        execution.put("attempt", Math.max(1, context.attempt()));
        execution.put("triggerDepth", context.triggerDepth());
        body.put("execution", execution);
        return body;
    }

    /**
     * The step's input: the flow state envelope's {@code input} when there is one (the documented
     * {@code $.input.<key>} rule), else the resolved data itself.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(Map<String, Object> resolved) {
        if (resolved == null) {
            return Map.of();
        }
        if (resolved.get("input") instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        return resolved;
    }

    /** The step's resolved {@code Parameters} (its configuration) as an object; {@code {}} otherwise. */
    private Map<String, Object> config(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(configJson);
            if (node != null && node.isObject()) {
                return objectMapper.convertValue(node, ExternalModuleRegistry.MAP_TYPE);
            }
        } catch (RuntimeException e) {
            // A config that is not an object is not something a module can be given.
        }
        return Map.of();
    }

    private ActionResult readResult(OutboundHttpClient.Response response) {
        JsonNode json = ExternalModuleRegistry.readObject(objectMapper, response);
        JsonNode success = json.get("success");
        if (success == null || !success.isBoolean()) {
            throw new ExternalModuleInvoker.InvalidResponseException("ActionResponse.success must be a boolean");
        }
        if (success.booleanValue()) {
            JsonNode output = json.get("output");
            if (output == null || output.isNull()) {
                return ActionResult.success(Map.of());
            }
            if (!output.isObject()) {
                throw new ExternalModuleInvoker.InvalidResponseException("ActionResponse.output must be an object");
            }
            if (output.size() > MAX_OUTPUT_KEYS) {
                throw new ExternalModuleInvoker.InvalidResponseException("ActionResponse.output has "
                        + output.size() + " keys; at most " + MAX_OUTPUT_KEYS);
            }
            return ActionResult.success(objectMapper.convertValue(output, ExternalModuleRegistry.MAP_TYPE));
        }
        JsonNode error = json.get("error");
        String code = text(error, "code").orElse("ModuleError");
        String message = text(error, "message").orElse("the module reported a failure");
        return ActionResult.failure(code + ": " + message);
    }

    private static Optional<String> text(JsonNode node, String field) {
        if (node == null || !node.isObject() || node.get(field) == null || !node.get(field).isString()) {
            return Optional.empty();
        }
        return Optional.of(node.get(field).stringValue());
    }

    @Override
    public ActionHandlerDescriptor getDescriptor() {
        return new ActionHandlerDescriptor() {
            @Override public String getDisplayName() { return action.name(); }
            @Override public String getCategory() { return action.category(); }
            @Override public String getDescription() { return action.description(); }
            @Override public String getConfigSchema() { return action.configSchema(); }
            @Override public String getInputSchema() { return action.inputSchema(); }
            @Override public String getOutputSchema() { return action.outputSchema(); }
        };
    }
}
