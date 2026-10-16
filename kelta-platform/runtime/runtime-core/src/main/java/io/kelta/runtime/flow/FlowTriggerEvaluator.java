package io.kelta.runtime.flow;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.formula.FormulaEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Evaluates whether a trigger event matches a flow's trigger configuration.
 * <p>
 * Used by {@code FlowEventListener} to determine which flows should be
 * started in response to a record change event.
 *
 * @since 1.0.0
 */
public class FlowTriggerEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FlowTriggerEvaluator.class);

    private final FormulaEvaluator formulaEvaluator;

    public FlowTriggerEvaluator(FormulaEvaluator formulaEvaluator) {
        this.formulaEvaluator = formulaEvaluator;
    }

    /**
     * Evaluates whether a record change event matches a RECORD_TRIGGERED flow's trigger config.
     *
     * @param event         the platform event wrapping a record changed payload
     * @param triggerConfig the flow's trigger_config JSONB as a map
     * @return true if the event matches the trigger conditions
     */
    @SuppressWarnings("unchecked")
    public boolean matchesRecordTrigger(PlatformEvent<RecordChangedPayload> event,
                                         Map<String, Object> triggerConfig) {
        if (triggerConfig == null) {
            return false;
        }

        RecordChangedPayload payload = event.getPayload();

        // Check collection
        String collection = (String) triggerConfig.get("collection");
        if (collection != null && !collection.equals(payload.getCollectionName())) {
            return false;
        }

        // Check events (CREATED, UPDATED, DELETED)
        List<String> events = (List<String>) triggerConfig.get("events");
        if (events != null && !events.isEmpty()) {
            String changeType = payload.getChangeType().name();
            if (!events.contains(changeType)) {
                return false;
            }
        }

        // Check trigger fields (for UPDATE events only)
        if (payload.getChangeType() == ChangeType.UPDATED) {
            List<String> triggerFields = (List<String>) triggerConfig.get("triggerFields");
            if (triggerFields != null && !triggerFields.isEmpty()) {
                List<String> changedFields = payload.getChangedFields();
                if (changedFields == null || changedFields.stream().noneMatch(triggerFields::contains)) {
                    return false;
                }
            }
        }

        // Check filter formula
        String filterFormula = (String) triggerConfig.get("filterFormula");
        if (filterFormula != null && !filterFormula.isBlank()) {
            // A slim event whose record could not be re-read has no field values to test.
            if (payload.isPayloadOmitted() && payload.getData() == null) {
                log.warn("Skipping filter formula for {}/{}: payload omitted and record not re-read",
                        payload.getCollectionName(), payload.getRecordId());
                return false;
            }
            try {
                boolean matches = formulaEvaluator.evaluateBoolean(filterFormula, payload.getData());
                if (!matches) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("Filter formula evaluation failed for flow trigger: {}", e.getMessage());
                return false;
            }
        }

        return true;
    }
}
