package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a {@code dashboard-components} record before it is persisted — shared by
 * {@link DashboardComponentConfigHook} (write path) and {@code DashboardDataController}'s
 * {@code POST /api/dashboards/{id}/validate} (dry-run path).
 *
 * <p>{@code config} is free JSON with no schema of its own, so none of this is caught by
 * generic field validation: a wrong {@code collectionName}, a field name that does not
 * exist on the target collection, an operator outside {@link FilterOperator}'s vocabulary,
 * a rollup/formula field used as {@code groupByField}/{@code aggregateField}, or a grid
 * position outside the dashboard's column count would otherwise only surface at render
 * time as a swallowed widget error.
 *
 * @since 1.0.0
 */
@Service
public class DashboardComponentValidator {

    private static final Set<String> COMPONENT_TYPES = Set.of("metric", "chart", "table", "recent");
    private static final Set<FieldType> NON_AGGREGATABLE_TYPES =
            EnumSet.of(FieldType.ROLLUP_SUMMARY, FieldType.FORMULA);
    private static final Set<FieldType> TIME_FIELD_TYPES = EnumSet.of(FieldType.DATE, FieldType.DATETIME);

    private final CollectionRegistry collectionRegistry;
    private final CollectionLifecycleManager lifecycleManager;
    private final QueryEngine queryEngine;

    public DashboardComponentValidator(CollectionRegistry collectionRegistry,
                                        CollectionLifecycleManager lifecycleManager,
                                        QueryEngine queryEngine) {
        this.collectionRegistry = collectionRegistry;
        this.lifecycleManager = lifecycleManager;
        this.queryEngine = queryEngine;
    }

    /**
     * Validates the effective (post-merge, for updates) state of a dashboard-component
     * record. Returns an empty list when the record is valid.
     */
    public List<BeforeSaveResult.ValidationError> validate(Map<String, Object> component) {
        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>();

        String componentType = stringValue(component.get("componentType"));
        if (componentType != null && !COMPONENT_TYPES.contains(componentType.toLowerCase(Locale.ROOT))) {
            errors.add(error("componentType", "Unknown componentType '" + componentType
                    + "'; expected one of metric, chart, table, recent"));
        }

        Map<String, Object> config = asMap(component.get("config"));

        CollectionDefinition collection = null;
        String collectionName = stringValue(config.get("collectionName"));
        if (collectionName != null && !collectionName.isBlank()) {
            collection = resolveCollection(collectionName);
            if (collection == null) {
                errors.add(error("config/collectionName", "Unknown collection '" + collectionName + "'"));
            }
        }

        if (collection != null) {
            validateFieldList(collection, config.get("fields"), "config/fields", errors);
            validateAggregatable(collection, stringValue(config.get("groupByField")),
                    "config/groupByField", errors);
            validateAggregatable(collection, stringValue(config.get("aggregateField")),
                    "config/aggregateField", errors);
            validateTimeField(collection, stringValue(config.get("timeField")),
                    "config/timeField", errors);
            validateFilters(collection, config.get("filters"), errors);
        }

        validateGridPosition(component, errors);

        return errors;
    }

    // =========================================================================
    // Grid position
    // =========================================================================

    private void validateGridPosition(Map<String, Object> component,
                                       List<BeforeSaveResult.ValidationError> errors) {
        Integer columnPosition = intValue(component.get("columnPosition"));
        if (columnPosition == null) {
            return;
        }
        if (columnPosition < 1) {
            errors.add(error("columnPosition", "columnPosition must be at least 1 (1-based grid column)"));
            return;
        }

        int columnSpan = Optional.ofNullable(intValue(component.get("columnSpan"))).orElse(1);
        Integer columnCount = resolveColumnCount(stringValue(component.get("dashboardId")));
        if (columnCount != null && columnPosition + columnSpan - 1 > columnCount) {
            errors.add(error("columnPosition", "columnPosition " + columnPosition + " with columnSpan "
                    + columnSpan + " exceeds the dashboard's columnCount of " + columnCount));
        }
    }

    private Integer resolveColumnCount(String dashboardId) {
        if (dashboardId == null || dashboardId.isBlank()) {
            return null;
        }
        CollectionDefinition dashboardsDef = collectionRegistry.get("dashboards");
        if (dashboardsDef == null) {
            return null;
        }
        return queryEngine.getById(dashboardsDef, dashboardId)
                .map(dashboard -> intValue(dashboard.get("columnCount")))
                .orElse(null);
    }

    // =========================================================================
    // Field references
    // =========================================================================

    private void validateFieldList(CollectionDefinition collection, Object fieldsObj, String pointer,
                                    List<BeforeSaveResult.ValidationError> errors) {
        List<String> fields = toStringList(fieldsObj);
        for (int i = 0; i < fields.size(); i++) {
            validateFieldExists(collection, fields.get(i), pointer + "/" + i, errors);
        }
    }

    private void validateAggregatable(CollectionDefinition collection, String fieldName, String pointer,
                                       List<BeforeSaveResult.ValidationError> errors) {
        if (fieldName == null || fieldName.isBlank()) {
            return;
        }
        FieldDefinition field = collection.getField(fieldName);
        if (field == null) {
            errors.add(error(pointer, unknownFieldMessage(fieldName, collection)));
            return;
        }
        if (NON_AGGREGATABLE_TYPES.contains(field.type())) {
            errors.add(error(pointer, "Field '" + fieldName + "' is a " + field.type()
                    + " field and cannot be used here"));
        }
    }

    private void validateTimeField(CollectionDefinition collection, String fieldName, String pointer,
                                    List<BeforeSaveResult.ValidationError> errors) {
        if (fieldName == null || fieldName.isBlank()) {
            return;
        }
        FieldDefinition field = collection.getField(fieldName);
        if (field == null) {
            errors.add(error(pointer, unknownFieldMessage(fieldName, collection)));
            return;
        }
        if (!TIME_FIELD_TYPES.contains(field.type())) {
            errors.add(error(pointer, "Field '" + fieldName
                    + "' must be a DATE or DATETIME field, got " + field.type()));
        }
    }

    private void validateFieldExists(CollectionDefinition collection, String fieldName, String pointer,
                                      List<BeforeSaveResult.ValidationError> errors) {
        if (fieldName == null || fieldName.isBlank()) {
            return;
        }
        if (collection.getField(fieldName) == null) {
            errors.add(error(pointer, unknownFieldMessage(fieldName, collection)));
        }
    }

    @SuppressWarnings("unchecked")
    private void validateFilters(CollectionDefinition collection, Object filtersObj,
                                  List<BeforeSaveResult.ValidationError> errors) {
        if (!(filtersObj instanceof List<?> list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> filter = (Map<String, Object>) raw;
            String pointer = "config/filters/" + i;

            validateFieldExists(collection, stringValue(filter.get("field")), pointer + "/field", errors);

            String operator = stringValue(filter.get("operator"));
            if (operator != null && !operator.isBlank() && !isValidOperator(operator)) {
                errors.add(error(pointer + "/operator", "Unknown filter operator '" + operator + "'"));
            }
        }
    }

    /** Mirrors {@code DashboardDataService.mapOperator}'s widget-specific "contains" alias. */
    private boolean isValidOperator(String operator) {
        if ("contains".equalsIgnoreCase(operator.trim())) {
            return true;
        }
        try {
            FilterOperator.parse(operator);
            return true;
        } catch (InvalidFilterException e) {
            return false;
        }
    }

    private String unknownFieldMessage(String fieldName, CollectionDefinition collection) {
        return "Unknown field '" + fieldName + "' on collection '" + collection.name() + "'";
    }

    // =========================================================================
    // Collection resolution
    // =========================================================================

    private CollectionDefinition resolveCollection(String name) {
        CollectionDefinition def = collectionRegistry.get(name);
        if (def != null) {
            return def;
        }
        return lifecycleManager.loadCollectionByName(name, null);
    }

    // =========================================================================
    // Parsing helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : Map.of();
    }

    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    result.add(o.toString());
                }
            }
            return result;
        }
        if (obj instanceof String str && !str.isBlank()) {
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String stringValue(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private Integer intValue(Object obj) {
        if (obj instanceof Number num) {
            return num.intValue();
        }
        if (obj instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private BeforeSaveResult.ValidationError error(String pointer, String message) {
        return new BeforeSaveResult.ValidationError(pointer, message);
    }
}
