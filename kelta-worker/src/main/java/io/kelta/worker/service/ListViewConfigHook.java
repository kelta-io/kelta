package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.DuplicateDefaultException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Before-save hook for the "list-views" system collection.
 *
 * <p>Until now, a column, sortField or filter field that doesn't exist on the target
 * collection, an operator outside the grammar, an out-of-range rowLimit, or a second
 * default view for the same collection/visibility were all accepted on write — the
 * end-user list page ({@code listViewMapping.ts}) silently repaired the row at read
 * time instead (bad rowLimit becomes 25, a non-string filter value is dropped). This
 * hook rejects those at write time instead.
 *
 * <p>Validates columns/sortField/filter fields against the target collection's
 * queryable fields, parses filter operators via {@link FilterOperator#parse} and
 * rewrites them to their canonical lowercase form (and an {@code IN} filter's array
 * value to the CSV string the FE round-trips), checks rowLimit against the allowed
 * page sizes, and enforces at most one default view per (collectionId, visibility).
 * A second default does NOT get auto-unset here — that's a client-driven PATCH of the
 * old default, not a side effect of this hook.
 *
 * @since 1.0.0
 */
public class ListViewConfigHook implements BeforeSaveHook {

    private static final List<Integer> ALLOWED_ROW_LIMITS = List.of(10, 25, 50, 100);

    private static final String SELECT_COLLECTION_NAME =
            "SELECT name FROM collection WHERE id = ?";

    private static final String SELECT_DEFAULT_VIEW_ID =
            "SELECT id FROM list_view WHERE collection_id = ? AND visibility = ? AND is_default = true";

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
        return validate(record, Map.of(), null);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return validate(record, previous, id);
    }

    private BeforeSaveResult validate(Map<String, Object> patch, Map<String, Object> previous, String id) {
        String collectionId = resolveField(patch, previous, "collectionId");
        CollectionDefinition target = collectionId != null ? resolveCollection(collectionId) : null;

        if (patch.containsKey("columns")) {
            BeforeSaveResult error = validateColumns(patch.get("columns"), target);
            if (error != null) {
                return error;
            }
        }

        if (patch.containsKey("sortField")) {
            BeforeSaveResult error = validateSortField(patch.get("sortField"), target);
            if (error != null) {
                return error;
            }
        }

        if (patch.containsKey("rowLimit")) {
            BeforeSaveResult error = validateRowLimit(patch.get("rowLimit"));
            if (error != null) {
                return error;
            }
        }

        Map<String, Object> fieldUpdates = new LinkedHashMap<>();
        if (patch.containsKey("filters")) {
            BeforeSaveResult error = validateFilters(patch.get("filters"), target);
            if (error != null) {
                return error;
            }
            fieldUpdates.put("filters", canonicalizeFilters((List<?>) patch.get("filters")));
        }

        if (Boolean.TRUE.equals(patch.get("isDefault"))) {
            String visibility = resolveField(patch, previous, "visibility");
            if (collectionId != null && visibility != null) {
                String existingId = findExistingDefaultId(collectionId, visibility, id);
                if (existingId != null) {
                    throw new DuplicateDefaultException("DEFAULT_VIEW_EXISTS",
                            "A default list view already exists for this collection and visibility",
                            existingId);
                }
            }
        }

        return fieldUpdates.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.withFieldUpdates(fieldUpdates);
    }

    private BeforeSaveResult validateColumns(Object columnsObj, CollectionDefinition target) {
        if (!(columnsObj instanceof List<?> columns)) {
            return BeforeSaveResult.error("columns", "columns must be an array of field names");
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!(columns.get(i) instanceof String columnName) || columnName.isBlank()) {
                return BeforeSaveResult.error("columns/" + i, "column name must be a non-blank string");
            }
            if (target != null && !target.hasQueryableField(columnName)) {
                return BeforeSaveResult.error("columns/" + i,
                        "Field '" + columnName + "' does not exist on the target collection");
            }
        }
        return null;
    }

    private BeforeSaveResult validateSortField(Object sortFieldObj, CollectionDefinition target) {
        if (sortFieldObj == null) {
            return null;
        }
        String sortField = sortFieldObj.toString();
        if (sortField.isBlank()) {
            return null;
        }
        if (target != null && !target.hasQueryableField(sortField)) {
            return BeforeSaveResult.error("sortField",
                    "Field '" + sortField + "' does not exist on the target collection");
        }
        return null;
    }

    private BeforeSaveResult validateRowLimit(Object rowLimitObj) {
        if (rowLimitObj == null) {
            return null;
        }
        if (!(rowLimitObj instanceof Number number) || !ALLOWED_ROW_LIMITS.contains(number.intValue())) {
            return BeforeSaveResult.error("rowLimit",
                    "rowLimit must be one of " + ALLOWED_ROW_LIMITS);
        }
        return null;
    }

    private BeforeSaveResult validateFilters(Object filtersObj, CollectionDefinition target) {
        if (!(filtersObj instanceof List<?> filters)) {
            return BeforeSaveResult.error("filters", "filters must be an array");
        }
        for (int i = 0; i < filters.size(); i++) {
            if (!(filters.get(i) instanceof Map<?, ?> filter)) {
                return BeforeSaveResult.error("filters/" + i, "each filter must be an object");
            }
            Object fieldObj = filter.get("field");
            if (!(fieldObj instanceof String fieldName) || fieldName.isBlank()) {
                return BeforeSaveResult.error("filters/" + i + "/field", "filter field is required");
            }
            if (target != null && !target.hasQueryableField(fieldName)) {
                return BeforeSaveResult.error("filters/" + i + "/field",
                        "Field '" + fieldName + "' does not exist on the target collection");
            }
            Object operatorObj = filter.get("operator");
            try {
                FilterOperator.parse(operatorObj == null ? null : operatorObj.toString());
            } catch (InvalidFilterException e) {
                return BeforeSaveResult.error("filters/" + i + "/operator", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Rewrites each filter's operator to its canonical lowercase form and, for {@code IN},
     * an array value to the CSV string the FE's {@code asFilterConditions} round-trips
     * (a non-string filter value is otherwise silently dropped at read time).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> canonicalizeFilters(List<?> filters) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (Object item : filters) {
            Map<String, Object> filter = (Map<String, Object>) item;
            FilterOperator operator = FilterOperator.parse(String.valueOf(filter.get("operator")));

            Map<String, Object> out = new LinkedHashMap<>(filter);
            out.put("operator", operator.name().toLowerCase(Locale.ROOT));
            if (operator == FilterOperator.IN && filter.get("value") instanceof List<?> values) {
                out.put("value", values.stream().map(String::valueOf).collect(Collectors.joining(",")));
            }
            canonical.add(out);
        }
        return canonical;
    }

    private String findExistingDefaultId(String collectionId, String visibility, String excludeId) {
        String sql = excludeId != null
                ? SELECT_DEFAULT_VIEW_ID + " AND id <> ? LIMIT 1"
                : SELECT_DEFAULT_VIEW_ID + " LIMIT 1";
        List<String> ids = excludeId != null
                ? jdbcTemplate.queryForList(sql, String.class, collectionId, visibility, excludeId)
                : jdbcTemplate.queryForList(sql, String.class, collectionId, visibility);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private CollectionDefinition resolveCollection(String collectionId) {
        List<String> names = jdbcTemplate.queryForList(SELECT_COLLECTION_NAME, String.class, collectionId);
        return names.isEmpty() ? null : collectionRegistry.get(names.get(0));
    }

    private static String resolveField(Map<String, Object> patch, Map<String, Object> previous, String key) {
        Object value = patch.containsKey(key) ? patch.get(key) : previous.get(key);
        return value == null ? null : value.toString();
    }
}
