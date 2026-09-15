package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.validation.DuplicateDefaultException;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates {@code list-views} rows on write.
 *
 * <p>Every {@code columns} entry, the {@code sortField}, and every filter's {@code field}
 * must exist on the view's target collection (named by {@code collectionId}); filter
 * operators must parse via {@link FilterOperator#parse} and are rewritten in canonical
 * lowercase form (e.g. {@code equals} -> {@code eq}); {@code rowLimit} must be one of the
 * page sizes the UI actually offers. {@code visibility} and {@code sortDirection} are enum
 * fields already enforced generically by the platform's field-level validation before any
 * hook runs, so they are not re-checked here.
 *
 * <p>A second {@code isDefault=true} row for the same collection + visibility is rejected
 * with a {@link DuplicateDefaultException} (409, not the 400 a {@link BeforeSaveResult}
 * error would produce) naming the existing default — the hook never unsets the sibling
 * itself; the caller must PATCH it.
 *
 * @since 1.0.0
 */
public class ListViewConfigHook implements BeforeSaveHook {

    private static final Set<Integer> ALLOWED_ROW_LIMITS = Set.of(10, 25, 50, 100);

    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;

    public ListViewConfigHook(CollectionRegistry collectionRegistry, JdbcTemplate jdbcTemplate) {
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "list-views";
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(null, record, Map.of());
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return validate(id, record, previous);
    }

    private BeforeSaveResult validate(String id, Map<String, Object> record, Map<String, Object> previous) {
        String collectionId = effectiveString("collectionId", record, previous);
        CollectionDefinition target = collectionId != null ? resolveTargetCollection(collectionId) : null;

        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>();
        Map<String, Object> fieldUpdates = new LinkedHashMap<>();

        validateColumns(record, target, errors);
        validateSortField(record, target, errors);
        validateFilters(record, target, errors, fieldUpdates);
        validateRowLimit(record, errors);

        if (!errors.isEmpty()) {
            return BeforeSaveResult.errors(errors);
        }

        if (isEffectivelyDefault(record, previous)) {
            // Not a BeforeSaveResult error: those always answer 400, and a sibling
            // default is a conflict with existing state, not a malformed request.
            enforceSingleDefault(id, collectionId, effectiveString("visibility", record, previous));
        }

        return fieldUpdates.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.withFieldUpdates(fieldUpdates);
    }

    private void validateColumns(Map<String, Object> record, CollectionDefinition target,
                                  List<BeforeSaveResult.ValidationError> errors) {
        if (target == null || !(record.get("columns") instanceof List<?> columns)) {
            return;
        }
        for (int i = 0; i < columns.size(); i++) {
            String column = String.valueOf(columns.get(i));
            if (!target.hasQueryableField(column)) {
                errors.add(new BeforeSaveResult.ValidationError("columns/" + i,
                        "Unknown field '" + column + "' on collection '" + target.name() + "'"));
            }
        }
    }

    private void validateSortField(Map<String, Object> record, CollectionDefinition target,
                                    List<BeforeSaveResult.ValidationError> errors) {
        Object raw = record.get("sortField");
        if (target == null || raw == null) {
            return;
        }
        String sortField = String.valueOf(raw);
        if (!sortField.isBlank() && !target.hasQueryableField(sortField)) {
            errors.add(new BeforeSaveResult.ValidationError("sortField",
                    "Unknown field '" + sortField + "' on collection '" + target.name() + "'"));
        }
    }

    private void validateFilters(Map<String, Object> record, CollectionDefinition target,
                                  List<BeforeSaveResult.ValidationError> errors,
                                  Map<String, Object> fieldUpdates) {
        if (!(record.get("filters") instanceof List<?> filters)) {
            return;
        }

        List<Map<String, Object>> canonical = new ArrayList<>(filters.size());
        boolean changed = false;
        for (int i = 0; i < filters.size(); i++) {
            if (!(filters.get(i) instanceof Map<?, ?> rawFilter)) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i,
                        "Each filter must be an object with field/operator/value"));
                continue;
            }
            Map<String, Object> filter = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawFilter.entrySet()) {
                filter.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            Object fieldObj = filter.get("field");
            String field = fieldObj == null ? null : String.valueOf(fieldObj);
            if (field == null || field.isBlank()) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i + "/field",
                        "Filter field is required"));
                continue;
            }
            if (target != null && !target.hasQueryableField(field)) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i + "/field",
                        "Unknown field '" + field + "' on collection '" + target.name() + "'"));
                continue;
            }

            Object operatorObj = filter.get("operator");
            FilterOperator operator;
            try {
                operator = FilterOperator.parse(operatorObj == null ? null : String.valueOf(operatorObj));
            } catch (InvalidFilterException e) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i + "/operator", e.getMessage()));
                continue;
            }

            String canonicalOperator = operator.name().toLowerCase(Locale.ROOT);
            if (!canonicalOperator.equals(operatorObj)) {
                changed = true;
            }
            filter.put("operator", canonicalOperator);
            canonical.add(filter);
        }

        if (errors.isEmpty() && changed) {
            fieldUpdates.put("filters", canonical);
        }
    }

    private void validateRowLimit(Map<String, Object> record, List<BeforeSaveResult.ValidationError> errors) {
        if (!record.containsKey("rowLimit")) {
            return;
        }
        Integer rowLimit = asInteger(record.get("rowLimit"));
        if (rowLimit == null || !ALLOWED_ROW_LIMITS.contains(rowLimit)) {
            errors.add(new BeforeSaveResult.ValidationError("rowLimit",
                    "rowLimit must be one of " + ALLOWED_ROW_LIMITS));
        }
    }

    private boolean isEffectivelyDefault(Map<String, Object> record, Map<String, Object> previous) {
        Object value = record.containsKey("isDefault") ? record.get("isDefault") : previous.get("isDefault");
        return Boolean.TRUE.equals(value);
    }

    /**
     * Rejects a second default for the same collection + visibility, excluding this
     * row itself (id) so re-saving the current default without changing isDefault stays
     * a 200 — see UpdateListViewToolTest / the PATCH-the-default acceptance case.
     */
    private void enforceSingleDefault(String id, String collectionId, String visibility) {
        if (collectionId == null) {
            return;
        }
        String effectiveVisibility = visibility != null ? visibility : "PRIVATE";
        String sql = "SELECT id FROM list_view WHERE collection_id = ?::uuid AND visibility = ? "
                + "AND is_default = true";
        List<Object> params = new ArrayList<>(List.of(collectionId, effectiveVisibility));
        if (id != null) {
            sql += " AND id <> ?::uuid";
            params.add(id);
        }
        List<String> existing = jdbcTemplate.queryForList(sql, String.class, params.toArray());
        if (!existing.isEmpty()) {
            throw new DuplicateDefaultException("DEFAULT_VIEW_EXISTS", "isDefault", existing.get(0),
                    "A default list view already exists for this collection and visibility");
        }
    }

    private CollectionDefinition resolveTargetCollection(String collectionId) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM collection WHERE id = ?::uuid", String.class, collectionId);
        return names.isEmpty() ? null : collectionRegistry.get(names.get(0));
    }

    private static String effectiveString(String key, Map<String, Object> record, Map<String, Object> previous) {
        Object value = record.containsKey(key) ? record.get(key) : previous.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
