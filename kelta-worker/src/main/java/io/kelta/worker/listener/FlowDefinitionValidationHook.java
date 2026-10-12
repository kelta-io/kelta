package io.kelta.worker.listener;

import io.kelta.runtime.flow.FlowDefinitionValidator;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rejects a flow save when its {@code definition} JSON is structurally
 * malformed — missing {@code StartAt}, dangling {@code Next}/{@code Default}/
 * choice targets, a {@code Map} state without an iterator, an unknown state
 * type, etc.
 *
 * <p>Without this hook the platform happily persisted any JSON the UI sent
 * and authors only discovered the problem at run-time, when the engine
 * tried to step the flow and threw {@code FlowDefinitionException}. Surfacing
 * the errors here lets the writer see all of them at once in the 400
 * response.
 *
 * <p>Partial updates that don't touch {@code definition} pass through
 * untouched. Empty/blank definitions are rejected — the DB column is
 * {@code NOT NULL} and a flow with no states cannot run.
 *
 * @since 1.0.0
 */
@Component
public class FlowDefinitionValidationHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FlowDefinitionValidationHook.class);

    private final FlowDefinitionValidator validator;
    private final ObjectMapper objectMapper;

    public FlowDefinitionValidationHook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.validator = new FlowDefinitionValidator(objectMapper);
    }

    @Override
    public String getCollectionName() {
        return "flows";
    }

    @Override
    public int getOrder() {
        // Run before any of the flow-related side-effect hooks (FlowConfigEventPublisher
        // = 100, FlowScheduleSyncHook = 150) so we don't publish change events or sync
        // a scheduled_job row for a definition that's about to be rejected.
        return 50;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(record, true);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        // On update the payload may omit `definition` (e.g. a flip of `active`).
        // Only validate when the caller actually sent a new definition.
        return validate(record, false);
    }

    private BeforeSaveResult validate(Map<String, Object> record, boolean required) {
        if (!record.containsKey("definition")) {
            if (required) {
                return BeforeSaveResult.error("definition",
                        "Flow definition is required");
            }
            return BeforeSaveResult.ok();
        }

        Object raw = record.get("definition");
        String json;
        if (raw == null) {
            return BeforeSaveResult.error("definition", "Flow definition is required");
        } else if (raw instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return BeforeSaveResult.error("definition", "Flow definition is required");
            }
            try {
                json = objectMapper.writeValueAsString(m);
            } catch (Exception e) {
                log.warn("Failed to serialize flow definition payload to JSON: {}", e.getMessage());
                return BeforeSaveResult.error("definition",
                        "Flow definition could not be serialized: " + e.getMessage());
            }
        } else if (raw instanceof String s) {
            if (s.isBlank()) {
                return BeforeSaveResult.error("definition", "Flow definition is required");
            }
            json = s;
        } else {
            return BeforeSaveResult.error("definition",
                    "Flow definition must be a JSON object (got " + raw.getClass().getSimpleName() + ")");
        }

        List<String> problems = validator.validate(json);
        if (problems.isEmpty()) {
            return BeforeSaveResult.ok();
        }
        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>(problems.size());
        for (String message : problems) {
            errors.add(new BeforeSaveResult.ValidationError("definition", message));
        }
        return BeforeSaveResult.errors(errors);
    }
}
