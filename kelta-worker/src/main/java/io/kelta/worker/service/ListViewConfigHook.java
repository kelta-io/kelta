package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Write-side validation for the {@code list-views} system collection: without
 * this, a column, {@code sortField} or filter field that doesn't exist on the
 * target collection, an operator outside the grammar, an arbitrary
 * {@code rowLimit}, or a second default view for the same collection +
 * visibility were all silently accepted and the UI repaired them at read time.
 *
 * <p>Every check here is generic — driven by the target collection's own
 * schema (resolved from {@code collectionId} via the {@code collections}
 * system table) and the {@link FilterOperator} grammar, not by anything
 * tenant- or collection-specific.
 *
 * <p>{@code visibility} and {@code sortDirection} are NOT re-validated here:
 * both are declared with {@code withEnumValues} in
 * {@code SystemCollectionDefinitions}, so {@code DefaultValidationEngine}
 * already rejects an out-of-set value before any hook runs.
 *
 * <p>The one-default-per-scope rule is enforced as a 409 ({@link
 * HookConflictException}, code {@code DEFAULT_VIEW_EXISTS}) naming the
 * existing default's id in {@code meta.existingId} — this hook never writes
 * a sibling row to unset the old default; that is a client-driven PATCH.
 */
public class ListViewConfigHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(ListViewConfigHook.class);

    static final String COLLECTION = "list-views";
    private static final Set<Integer> ALLOWED_ROW_LIMITS = Set.of(10, 25, 50, 100);

    private final CollectionRegistry collectionRegistry;
    private final JdbcTemplate jdbcTemplate;

    public ListViewConfigHook(CollectionRegistry collectionRegistry, JdbcTemplate jdbcTemplate) {
        this.collectionRegistry = collectionRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(null, record, null);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return validate(id, record, previous);
    }

    private BeforeSaveResult validate(String id, Map<String, Object> record, Map<String, Object> previous) {
        CollectionDefinition target = resolveTargetDefinition(asString(effective("collectionId", record, previous)));

        List<BeforeSaveResult.ValidationError> errors = new ArrayList<>();
        validateColumns(record, target, errors);
        validateSortField(record, target, errors);
        validateRowLimit(record, errors);
        Map<String, Object> fieldUpdates = validateFilters(record, target, errors);

        if (!errors.isEmpty()) {
            return BeforeSaveResult.errors(errors);
        }

        checkDefaultUniqueness(id, record, previous);

        return fieldUpdates.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.withFieldUpdates(fieldUpdates);
    }

    private void validateColumns(Map<String, Object> record, CollectionDefinition target,
                                  List<BeforeSaveResult.ValidationError> errors) {
        if (target == null || !(record.get("columns") instanceof List<?> columns)) {
            return;
        }
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i) == null ? null : columns.get(i).toString();
            if (column == null || column.isBlank() || !target.hasQueryableField(column)) {
                errors.add(new BeforeSaveResult.ValidationError("columns/" + i,
                        "Unknown field '" + column + "' on collection '" + target.name() + "'"));
            }
        }
    }

    private void validateSortField(Map<String, Object> record, CollectionDefinition target,
                                    List<BeforeSaveResult.ValidationError> errors) {
        if (target == null || !record.containsKey("sortField")) {
            return;
        }
        Object value = record.get("sortField");
        String sortField = value == null ? null : value.toString();
        if (sortField != null && !sortField.isBlank() && !target.hasQueryableField(sortField)) {
            errors.add(new BeforeSaveResult.ValidationError("sortField",
                    "Unknown field '" + sortField + "' on collection '" + target.name() + "'"));
        }
    }

    private void validateRowLimit(Map<String, Object> record, List<BeforeSaveResult.ValidationError> errors) {
        if (!record.containsKey("rowLimit")) {
            return;
        }
        Integer rowLimit = asInteger(record.get("rowLimit"));
        if (rowLimit != null && !ALLOWED_ROW_LIMITS.contains(rowLimit)) {
            errors.add(new BeforeSaveResult.ValidationError("rowLimit",
                    "rowLimit must be one of: 10, 25, 50, 100"));
        }
    }

    /**
     * Validates each filter's {@code field} against the target collection and its
     * {@code operator} via {@link FilterOperator#parse}, rewriting the operator to
     * canonical lowercase form (e.g. {@code equals} -> {@code eq}). {@code value} is
     * left untouched — an {@code in} filter accepts either a CSV string or an array,
     * both pass through as-is.
     *
     * @return field updates carrying the canonicalized {@code filters} array, or an
     *         empty map when nothing needed rewriting
     */
    private Map<String, Object> validateFilters(Map<String, Object> record, CollectionDefinition target,
                                                  List<BeforeSaveResult.ValidationError> errors) {
        if (!(record.get("filters") instanceof List<?> filters)) {
            return Map.of();
        }
        List<Object> canonical = new ArrayList<>(filters.size());
        boolean changed = false;
        for (int i = 0; i < filters.size(); i++) {
            Object entry = filters.get(i);
            if (!(entry instanceof Map<?, ?> filterMap)) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i, "Filter must be an object"));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) filterMap);

            Object fieldValue = mutable.get("field");
            String field = fieldValue == null ? null : fieldValue.toString();
            if (target != null && (field == null || field.isBlank() || !target.hasQueryableField(field))) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i + "/field",
                        "Unknown field '" + field + "' on collection '" + target.name() + "'"));
            }

            Object operatorValue = mutable.get("operator");
            String operatorToken = operatorValue == null ? null : operatorValue.toString();
            try {
                String canonicalOperator = FilterOperator.parse(operatorToken).name().toLowerCase(Locale.ROOT);
                if (!canonicalOperator.equals(operatorToken)) {
                    mutable.put("operator", canonicalOperator);
                    changed = true;
                }
            } catch (InvalidFilterException e) {
                errors.add(new BeforeSaveResult.ValidationError("filters/" + i + "/operator", e.getMessage()));
            }
            canonical.add(mutable);
        }
        if (!errors.isEmpty() || !changed) {
            return Map.of();
        }
        return Map.of("filters", canonical);
    }

    /**
     * Enforces one default view per (collection, visibility) scope. Only a client-driven
     * PATCH may hand the default to another row — this hook never writes a sibling row to
     * unset the old default, it only rejects the write that would create a second one.
     */
    private void checkDefaultUniqueness(String id, Map<String, Object> record, Map<String, Object> previous) {
        Object isDefaultValue = effective("isDefault", record, previous);
        if (!Boolean.TRUE.equals(isDefaultValue)) {
            return;
        }
        String collectionId = asString(effective("collectionId", record, previous));
        String visibility = asString(effective("visibility", record, previous));
        if (collectionId == null || visibility == null) {
            return;
        }
        findExistingDefaultId(collectionId, visibility, id).ifPresent(existingId -> {
            throw new HookConflictException("DEFAULT_VIEW_EXISTS", "isDefault",
                    "A default list view already exists for this collection and visibility",
                    Map.of("existingId", existingId));
        });
    }

    private Optional<String> findExistingDefaultId(String collectionId, String visibility, String excludeId) {
        List<String> ids = excludeId != null
                ? jdbcTemplate.queryForList(
                        "SELECT id FROM list_view WHERE collection_id = ?::uuid AND visibility = ? "
                                + "AND is_default = true AND id <> ?::uuid LIMIT 1",
                        String.class, collectionId, visibility, excludeId)
                : jdbcTemplate.queryForList(
                        "SELECT id FROM list_view WHERE collection_id = ?::uuid AND visibility = ? "
                                + "AND is_default = true LIMIT 1",
                        String.class, collectionId, visibility);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private CollectionDefinition resolveTargetDefinition(String collectionId) {
        if (collectionId == null) {
            return null;
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM collection WHERE id = ?::uuid", String.class, collectionId);
        if (names.isEmpty()) {
            log.warn("List view targets unknown collection '{}' -- skipping field validation", collectionId);
            return null;
        }
        CollectionDefinition definition = collectionRegistry.get(names.get(0));
        if (definition == null) {
            log.warn("Collection '{}' not in registry -- skipping list view field validation", names.get(0));
        }
        return definition;
    }

    /** Value from the patch if present, else the previous stored value (create has no previous). */
    private static Object effective(String key, Map<String, Object> record, Map<String, Object> previous) {
        if (record.containsKey(key)) {
            return record.get(key);
        }
        return previous != null ? previous.get(key) : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
