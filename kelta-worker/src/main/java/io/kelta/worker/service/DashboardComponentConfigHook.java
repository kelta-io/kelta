package io.kelta.worker.service;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rejects a {@code dashboard-components} write whose {@code config} references a
 * collection, field, or operator that does not exist, uses a rollup/formula field where
 * an aggregatable field is required, or places the widget outside the dashboard's grid —
 * see {@link DashboardComponentValidator} for the checks. Without this hook a bad config
 * was only discovered at render time, where {@code DashboardDataService} used to collapse
 * every such failure into "Internal error executing widget".
 *
 * @since 1.0.0
 */
public class DashboardComponentConfigHook implements BeforeSaveHook {

    private final DashboardComponentValidator validator;

    public DashboardComponentConfigHook(DashboardComponentValidator validator) {
        this.validator = validator;
    }

    @Override
    public String getCollectionName() {
        return "dashboard-components";
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return toResult(validator.validate(record));
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        // Partial updates may omit unchanged keys (e.g. a title-only edit) — validate the
        // effective record the update would produce, not just the delta.
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        merged.putAll(record);
        return toResult(validator.validate(merged));
    }

    private BeforeSaveResult toResult(List<BeforeSaveResult.ValidationError> errors) {
        return errors.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.errors(errors);
    }
}
