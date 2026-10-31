package io.kelta.worker.module;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleRegistry.Endpoint;
import io.kelta.worker.service.FieldMaskingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One manifest {@code hooks[]} registration of an external module
 * ({@code specs/module-platform/9-external-modules.md} §3.6), registered per tenant in
 * {@code BeforeSaveHookRegistry} under its {@code collection} ({@code *} for every collection) and
 * {@code order}, and run as {@code POST {baseUrl}/kelta/v1/hooks}.
 *
 * <p><b>Admission-webhook shape.</b> The module answers {@code {allowed, fieldUpdates, errors[],
 * conflict}}:
 * <ul>
 *   <li>{@code allowed: true} → the write goes ahead, with {@code fieldUpdates} merged in
 *       (create/update only). An update naming a field the principal may not write is a module
 *       error (below), never a silent drop.</li>
 *   <li>{@code allowed: false} with {@code conflict} → {@link HookConflictException} (409).</li>
 *   <li>{@code allowed: false} with {@code errors} → a validation failure (400), one error per
 *       entry; none given → one record-level error.</li>
 * </ul>
 * On {@code delete} only {@code allowed} is honored.
 *
 * <p><b>Module errors and {@code failurePolicy}.</b> A timeout, an open breaker, an unreachable
 * module, a non-2xx, an unreadable body or a refused field update is a module error. With
 * {@code failurePolicy: fail} (the default) it fails the write with
 * {@code ModuleUnavailableException}/{@code ModuleTimeoutException} (503); with {@code ignore} the
 * write proceeds as if the hook had allowed it.
 *
 * <p><b>Runs inside the write.</b> The write path may hold a database transaction (and its pooled
 * connection) while the module answers, so the manifest caps {@code timeoutMs} at 5 s (default
 * 2 s) — see {@code concerns.md}. Records are filtered for the principal (§3.4) before they leave.
 */
final class RemoteBeforeSaveHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(RemoteBeforeSaveHook.class);

    static final String FAILURE_POLICY_IGNORE = "ignore";
    static final String EVENT_CREATE = "beforeCreate";
    static final String EVENT_UPDATE = "beforeUpdate";
    static final String EVENT_DELETE = "beforeDelete";

    private final String tenantId;
    private final String moduleId;
    private final ExternalModuleManifest.Hook declaration;
    private final ExternalModuleRegistry registry;
    private final ExternalModuleInvoker invoker;
    private final CollectionRegistry collectionRegistry;
    private final FieldMaskingService masking;
    private final ObjectMapper objectMapper;

    RemoteBeforeSaveHook(String tenantId, String moduleId, ExternalModuleManifest.Hook declaration,
                         ExternalModuleRegistry registry, ExternalModuleInvoker invoker,
                         CollectionRegistry collectionRegistry, FieldMaskingService masking, ObjectMapper objectMapper) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.moduleId = Objects.requireNonNull(moduleId);
        this.declaration = Objects.requireNonNull(declaration);
        this.registry = Objects.requireNonNull(registry);
        this.invoker = Objects.requireNonNull(invoker);
        this.collectionRegistry = collectionRegistry;
        this.masking = masking;
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    String moduleId() {
        return moduleId;
    }

    @Override
    public String getCollectionName() {
        return declaration.collection();
    }

    @Override
    public int getOrder() {
        return declaration.order();
    }

    @Override
    public BeforeSaveResult beforeCreate(String collectionName, Map<String, Object> record, String tenantId) {
        if (!declaration.events().contains(EVENT_CREATE)) {
            return BeforeSaveResult.ok();
        }
        return evaluate("create", collectionName, null, record, null);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String collectionName, String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        if (!declaration.events().contains(EVENT_UPDATE)) {
            return BeforeSaveResult.ok();
        }
        return evaluate("update", collectionName, id, record, previous);
    }

    @Override
    public BeforeSaveResult beforeDelete(String collectionName, String id, String tenantId) {
        if (!declaration.events().contains(EVENT_DELETE)) {
            return BeforeSaveResult.ok();
        }
        return evaluate("delete", collectionName, id, null, null);
    }

    private BeforeSaveResult evaluate(String operation, String collection, String recordId,
                                      Map<String, Object> record, Map<String, Object> previous) {
        Endpoint endpoint = registry.endpoint(tenantId, moduleId).orElse(null);
        try {
            if (endpoint == null) {
                throw ModuleUnavailableException.of(moduleId, "Module '" + moduleId + "' is not installed");
            }
            CollectionDefinition definition = collectionRegistry == null ? null : collectionRegistry.get(collection);
            byte[] body = objectMapper.writeValueAsBytes(request(operation, collection, recordId, record, previous,
                    definition, endpoint));
            ModuleHttpClient.Call call = ModuleHttpClient.Call.postJson(ModuleHttpClient.HOOKS_PATH, body,
                    Duration.ofMillis(declaration.timeoutMs()), ExternalModuleRegistry.currentActor(null));
            Decision decision = invoker.call(endpoint.row(), ExternalModuleInvoker.Kind.HOOK, call, this::read);
            return apply(decision, operation, collection, definition, endpoint);
        } catch (ModuleUnavailableException e) {
            if (FAILURE_POLICY_IGNORE.equals(declaration.failurePolicy())) {
                log.warn("Module '{}' hook on '{}' failed and its failurePolicy is ignore; allowing the {}: {}",
                        moduleId, collection, operation, e.getMessage());
                return BeforeSaveResult.ok();
            }
            throw e;
        }
    }

    private Map<String, Object> request(String operation, String collection, String recordId,
                                        Map<String, Object> record, Map<String, Object> previous,
                                        CollectionDefinition definition, Endpoint endpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("collection", collection);
        body.put("operation", operation);
        body.put("recordId", recordId);
        if (record != null) {
            body.put("record", endpoint.access().outbound(collection, definition, record, masking));
            body.put("previousRecord", endpoint.access().outbound(collection, definition, previous, masking));
            body.put("changedFields", changedFields(record, previous));
        }
        return body;
    }

    /** The fields of {@code record} whose value differs from {@code previous} (all of them on create). */
    private static List<String> changedFields(Map<String, Object> record, Map<String, Object> previous) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (previous == null || !Objects.equals(entry.getValue(), previous.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    /** A parsed {@code HookResponse}. */
    private record Decision(boolean allowed, Map<String, Object> fieldUpdates,
                            List<BeforeSaveResult.ValidationError> errors, BeforeSaveResult.ValidationError conflict) {}

    private Decision read(OutboundHttpClient.Response response) {
        JsonNode json = ExternalModuleRegistry.readObject(objectMapper, response);
        JsonNode allowed = json.get("allowed");
        if (allowed == null || !allowed.isBoolean()) {
            throw new ExternalModuleInvoker.InvalidResponseException("HookResponse.allowed must be a boolean");
        }
        Map<String, Object> fieldUpdates = Map.of();
        JsonNode updates = json.get("fieldUpdates");
        if (updates != null && !updates.isNull()) {
            if (!updates.isObject()) {
                throw new ExternalModuleInvoker.InvalidResponseException("HookResponse.fieldUpdates must be an object");
            }
            fieldUpdates = objectMapper.convertValue(updates, ExternalModuleRegistry.MAP_TYPE);
        }
        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>();
        JsonNode list = json.get("errors");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw new ExternalModuleInvoker.InvalidResponseException("HookResponse.errors must be an array");
            }
            for (JsonNode error : list) {
                errors.add(validationError(error));
            }
        }
        JsonNode conflict = json.get("conflict");
        BeforeSaveResult.ValidationError conflictError = conflict == null || conflict.isNull() ? null
                : validationError(conflict);
        return new Decision(allowed.booleanValue(), fieldUpdates, errors, conflictError);
    }

    private static BeforeSaveResult.ValidationError validationError(JsonNode node) {
        if (node == null || !node.isObject() || node.get("message") == null || !node.get("message").isString()) {
            throw new ExternalModuleInvoker.InvalidResponseException(
                    "a HookResponse ValidationError needs a string message");
        }
        JsonNode field = node.get("field");
        JsonNode code = node.get("code");
        return new BeforeSaveResult.ValidationError(
                field != null && field.isString() ? field.stringValue() : null,
                node.get("message").stringValue(),
                code != null && code.isString() ? code.stringValue() : null);
    }

    private BeforeSaveResult apply(Decision decision, String operation, String collection,
                                   CollectionDefinition definition, Endpoint endpoint) {
        if (decision.allowed()) {
            if ("delete".equals(operation) || decision.fieldUpdates().isEmpty()) {
                return BeforeSaveResult.ok();
            }
            List<String> refused = endpoint.access().unwritable(collection, definition, decision.fieldUpdates(),
                    "create".equals(operation));
            if (!refused.isEmpty()) {
                throw ModuleUnavailableException.of(moduleId, "Module '" + moduleId + "' hook on '" + collection
                        + "' tried to set field(s) its permissions do not allow: " + refused);
            }
            return BeforeSaveResult.withFieldUpdates(decision.fieldUpdates());
        }
        if (decision.conflict() != null) {
            BeforeSaveResult.ValidationError conflict = decision.conflict();
            throw new HookConflictException(conflict.code() == null ? "ModuleConflict" : conflict.code(),
                    conflict.field(), conflict.message(), Map.of("moduleId", moduleId));
        }
        if (decision.errors().isEmpty()) {
            return BeforeSaveResult.error(null, "Rejected by module '" + moduleId + "'");
        }
        return BeforeSaveResult.errors(decision.errors());
    }
}
