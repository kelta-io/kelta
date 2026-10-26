package io.kelta.runtime.module.core.handlers;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Action handler that creates a new record in a target collection.
 *
 * <p>Config format:
 * <pre>
 * {
 *   "targetCollectionName": "orders",
 *   "fieldMappings": [
 *     {"targetField": "name", "value": "New Record"},
 *     {"targetField": "source_id", "sourceField": "id"}
 *   ]
 * }
 * </pre>
 *
 * <p>Supports both {@code targetCollectionName} (preferred) and legacy
 * {@code targetCollectionId} for backward compatibility.
 *
 * @since 1.0.0
 */
public class CreateRecordActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateRecordActionHandler.class);

    private final ObjectMapper objectMapper;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    public CreateRecordActionHandler(ObjectMapper objectMapper,
                                     CollectionRegistry collectionRegistry,
                                     QueryEngine queryEngine) {
        this.objectMapper = objectMapper;
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
    }

    @Override
    public String getActionTypeKey() {
        return "CREATE_RECORD";
    }

    @Override
    public ActionResult execute(ActionContext context) {
        String targetCollectionName = null;
        try {
            Map<String, Object> config = objectMapper.readValue(
                context.actionConfigJson(), new TypeReference<>() {});

            // Resolve target collection (prefer name, fallback to ID)
            targetCollectionName = resolveCollectionName(config);
            if (targetCollectionName == null) {
                return ActionResult.failure("Target collection name or ID is required");
            }

            CollectionDefinition targetCollection = collectionRegistry.get(targetCollectionName);
            if (targetCollection == null) {
                return ActionResult.failure("Target collection not found: " + targetCollectionName);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fieldMappings = (List<Map<String, Object>>) config.get("fieldMappings");

            Map<String, Object> recordData = new HashMap<>();

            if (fieldMappings != null) {
                for (Map<String, Object> mapping : fieldMappings) {
                    String targetField = (String) mapping.get("targetField");
                    if (targetField == null || targetField.isBlank()) {
                        continue;
                    }

                    String sourceField = (String) mapping.get("sourceField");
                    if (sourceField != null && !sourceField.isBlank()) {
                        Object value = context.data() != null ? context.data().get(sourceField) : null;
                        recordData.put(targetField, value);
                    } else {
                        recordData.put(targetField, mapping.get("value"));
                    }
                }
            }

            // Flow writes bypass the HTTP router's audit stamping — apply the
            // execution actor (initiator → run-as user → flow owner) here.
            FlowAuditStamp.stampCreate(recordData, context.userId());

            log.info("Create record action: targetCollection={}, fields={}",
                targetCollectionName, recordData.keySet());

            Map<String, Object> created = queryEngine.create(targetCollection, recordData);
            Object createdId = created != null ? created.get("id") : null;

            Map<String, Object> output = new HashMap<>();
            output.put("targetCollectionName", targetCollectionName);
            output.put("recordData", recordData);
            output.put("record", created);
            output.put("id", createdId);
            output.put("recordId", createdId);
            return ActionResult.success(output);
        } catch (Exception e) {
            String validation = validationSummary(e);
            if (validation != null) {
                log.error("Failed to execute create record action: tenantId={}, flowId={}, executionId={}, "
                        + "targetCollection={}, {}",
                    context.tenantId(), context.workflowRuleId(), context.executionLogId(),
                    targetCollectionName, validation);
            } else {
                log.error("Failed to execute create record action: tenantId={}, flowId={}, executionId={}, "
                        + "targetCollection={}, error={}",
                    context.tenantId(), context.workflowRuleId(), context.executionLogId(),
                    targetCollectionName, e.getMessage(), e);
            }
            return ActionResult.failure(e);
        }
    }

    @Override
    public void validate(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                configJson, new TypeReference<>() {});

            if (config.get("targetCollectionName") == null && config.get("targetCollectionId") == null) {
                throw new IllegalArgumentException("Config must contain 'targetCollectionName' or 'targetCollectionId'");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Summarises a validation failure as field names and constraints only — never the messages,
     * which can embed submitted values (e.g. a reference error names the missing id).
     * Returns {@code null} when {@code e} is not caused by a {@link ValidationException}.
     */
    private static String validationSummary(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ValidationException ve) {
                List<FieldError> errors = ve.getValidationResult() != null
                    ? ve.getValidationResult().errors() : List.of();
                return "fields=" + errors.stream().map(FieldError::fieldName).distinct().toList()
                    + ", constraints=" + errors.stream().map(FieldError::constraint).distinct().toList();
            }
        }
        return null;
    }

    private String resolveCollectionName(Map<String, Object> config) {
        // Prefer targetCollectionName
        String name = (String) config.get("targetCollectionName");
        if (name != null && !name.isBlank()) {
            return name;
        }

        // Legacy: targetCollectionId — not supported in module context, treat as name
        String id = (String) config.get("targetCollectionId");
        if (id != null && !id.isBlank()) {
            return id;
        }

        return null;
    }
}
