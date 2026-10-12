package io.kelta.runtime.storage;

import static io.kelta.runtime.storage.SqlIdentifiers.quoteIdentifier;
import static io.kelta.runtime.storage.SqlIdentifiers.resolveColumnName;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.InvalidFilterException;
import io.kelta.runtime.query.SortField;
import io.kelta.runtime.storage.TenantScopePolicy.TenantScope;
import io.kelta.runtime.validation.TypeCoercionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Renders the SELECT list, WHERE clause (filters plus the row-scope predicate) and ORDER BY
 * clause for {@link PhysicalTableStorageAdapter} reads, binding filter values in clause order.
 *
 * <p>Extracted from {@code PhysicalTableStorageAdapter} unchanged. Temporal filter values bind
 * through the adapter's write-path conversion, supplied as {@code valueConverter}, so reads and
 * writes bind identically.
 */
final class SqlQueryBuilder {

    /** The adapter's {@code convertValueForStorage(value, type)}. */
    private final BiFunction<Object, FieldType, Object> valueConverter;

    SqlQueryBuilder(BiFunction<Object, FieldType, Object> valueConverter) {
        this.valueConverter = valueConverter;
    }

    /**
     * Builds the SELECT clause for a query.
     *
     * @param fields the requested fields (empty means all fields)
     * @param definition the collection definition
     * @return the SELECT clause
     */
    static String buildSelectClause(List<String> fields, CollectionDefinition definition) {
        if (fields == null || fields.isEmpty()) {
            return "*";
        }

        // Always include id, created_at, updated_at, created_by, updated_by
        List<String> selectFields = new ArrayList<>();
        selectFields.add("id");
        selectFields.add("created_at");
        selectFields.add("updated_at");
        selectFields.add("created_by");
        selectFields.add("updated_by");
        // Geo columns exist only on captureGeo collections (base DDL for new tables,
        // lazy ALTER for existing ones) — selecting them elsewhere would 42703.
        if (definition.captureGeo()) {
            selectFields.add("created_geo");
            selectFields.add("updated_geo");
        }

        for (String field : fields) {
            // FORMULA/ROLLUP_SUMMARY fields have no physical column (FieldType#hasPhysicalColumn);
            // their values are filled in after retrieval by DefaultQueryEngine.computeVirtualFields,
            // so a sparse fields[] naming one must not put it in the SELECT list. Fields the
            // collection doesn't declare fall through unchanged — same behavior as before.
            FieldDefinition fieldDef = definition.getField(field);
            if (fieldDef != null && !fieldDef.type().hasPhysicalColumn()) {
                continue;
            }
            String columnName = resolveColumnName(definition, field);
            // Dedupe against the bare system-column names added above, but emit the
            // quoted form so reserved-word columns (user, order, …) parse.
            if (!selectFields.contains(columnName)) {
                selectFields.add(quoteIdentifier(columnName));
            }
        }

        return String.join(", ", selectFields);
    }

    /**
     * Builds the WHERE clause from filter conditions with {@code tenantScope} ANDed in.
     *
     * <p>The tenant predicate is appended, never substituted: a caller that supplies its own
     * {@code tenantId} filter cannot widen the scope, only narrow it to the empty set.
     *
     * @param filters the filter conditions (may be empty)
     * @param tenantScope the tenant predicate, or {@code null} when none applies
     * @param definition the collection definition (used for column name mapping)
     * @param params the parameter list to populate, in clause order
     * @return the WHERE clause (without the WHERE keyword), empty when there is nothing to filter
     */
    String buildWhereClause(List<FilterCondition> filters, TenantScope tenantScope,
                                     CollectionDefinition definition, List<Object> params) {
        List<String> predicates = new ArrayList<>();
        for (FilterCondition filter : filters) {
            predicates.add(buildFilterCondition(filter, definition, params));
        }
        if (tenantScope != null) {
            predicates.add(tenantScope.sql());
            params.addAll(tenantScope.params());
        }
        return String.join(" AND ", predicates);
    }

    /**
     * Builds a single filter condition SQL fragment.
     *
     * @param filter the filter condition
     * @param definition the collection definition (used for column name mapping)
     * @param params the parameter list to populate
     * @return the SQL fragment for this filter
     */
    private String buildFilterCondition(FilterCondition filter, CollectionDefinition definition,
                                         List<Object> params) {
        String fieldName = quoteIdentifier(resolveColumnName(definition, filter.fieldName()));
        FilterOperator operator = filter.operator();
        Object value = filter.value();

        // Coerce string filter values to match the field's database type (e.g., "false" → Boolean.FALSE).
        // For IN/ANY the value is a Collection<String>; coerce each element so UUID columns receive
        // java.util.UUID rather than the raw string.
        FieldDefinition fieldDef = definition.getField(filter.fieldName());
        if (fieldDef != null) {
            if (value instanceof String) {
                value = TypeCoercionService.coerceValue(value, fieldDef.type());
            } else if (value instanceof Collection<?> coll) {
                List<Object> coerced = new ArrayList<>(coll.size());
                for (Object element : coll) {
                    coerced.add(element instanceof String s
                            ? TypeCoercionService.coerceValue(s, fieldDef.type())
                            : element);
                }
                value = coerced;
            }
        }

        // Temporal columns must bind as java.sql.Timestamp / java.sql.Date: pgjdbc cannot
        // infer a SQL type for the java.time.Instant that coercion produces for DATETIME
        // fields, and the system audit columns (createdAt/updatedAt) have no FieldDefinition,
        // so their ISO-8601 strings bound as varchar — which Postgres refuses to compare with
        // a timestamp column. Both surfaced as 500s on every dashboard time range.
        FieldType temporal = temporalType(fieldDef, filter.fieldName());
        if (temporal != null && COMPARISON_OPERATORS.contains(operator)) {
            if (value instanceof Collection<?> coll) {
                List<Object> bound = new ArrayList<>(coll.size());
                for (Object element : coll) {
                    bound.add(toTemporalParam(element, temporal, filter.fieldName()));
                }
                value = bound;
            } else {
                value = toTemporalParam(value, temporal, filter.fieldName());
            }
        }

        // Detect array-backed columns (Postgres TEXT[]) so we emit ANY(...) /
        // <> ALL(...) / unnest+ILIKE instead of scalar operators that 500 the
        // gateway on JSONB-coerced arrays. Currently only MULTI_PICKLIST maps
        // to TEXT[]; the legacy FieldType.ARRAY value lands in JSONB so it is
        // intentionally NOT included here.
        boolean isArrayColumn = fieldDef != null
            && fieldDef.type() == FieldType.MULTI_PICKLIST;

        return switch (operator) {
            case EQ -> {
                params.add(value);
                yield isArrayColumn
                    ? "? = ANY(" + fieldName + ")"
                    : fieldName + " = ?";
            }
            case NEQ -> {
                params.add(value);
                yield isArrayColumn
                    ? "? <> ALL(" + fieldName + ")"
                    : fieldName + " != ?";
            }
            case GT -> {
                params.add(value);
                yield fieldName + " > ?";
            }
            case LT -> {
                params.add(value);
                yield fieldName + " < ?";
            }
            case GTE -> {
                params.add(value);
                yield fieldName + " >= ?";
            }
            case LTE -> {
                params.add(value);
                yield fieldName + " <= ?";
            }
            case ISNULL -> {
                // value is a boolean indicating whether to check for null or not null
                boolean isNull = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(value.toString());
                yield isNull ? fieldName + " IS NULL" : fieldName + " IS NOT NULL";
            }
            case CONTAINS -> {
                if (isArrayColumn) {
                    // Array-contains: exact element match. Use ANY() so the
                    // index on the array column can be considered. For
                    // substring search across elements, use ICONTAINS.
                    params.add(value);
                    yield "? = ANY(" + fieldName + ")";
                }
                params.add("%" + value + "%");
                yield fieldName + " LIKE ?";
            }
            case STARTS -> {
                params.add(value + "%");
                yield fieldName + " LIKE ?";
            }
            case ENDS -> {
                params.add("%" + value);
                yield fieldName + " LIKE ?";
            }
            case ICONTAINS -> {
                if (isArrayColumn) {
                    // Case-insensitive element match across the array.
                    params.add(value.toString().toLowerCase());
                    yield "EXISTS (SELECT 1 FROM unnest(" + fieldName
                        + ") AS elt WHERE LOWER(elt) = ?)";
                }
                params.add("%" + value.toString().toLowerCase() + "%");
                yield "LOWER(" + fieldName + ") LIKE ?";
            }
            case ISTARTS -> {
                params.add(value.toString().toLowerCase() + "%");
                yield "LOWER(" + fieldName + ") LIKE ?";
            }
            case IENDS -> {
                params.add("%" + value.toString().toLowerCase());
                yield "LOWER(" + fieldName + ") LIKE ?";
            }
            case IEQ -> {
                params.add(value.toString().toLowerCase());
                yield "LOWER(" + fieldName + ") = ?";
            }
            case IN -> {
                if (value instanceof Collection<?> coll) {
                    if (coll.isEmpty()) {
                        yield "1 = 0"; // always false for empty IN list
                    }
                    String ph = coll.stream()
                            .map(v -> {
                                params.add(v);
                                return "?";
                            })
                            .collect(Collectors.joining(", "));
                    yield fieldName + " IN (" + ph + ")";
                } else {
                    // Single value fallback
                    params.add(value);
                    yield fieldName + " = ?";
                }
            }
        };
    }

    /** Operators whose value is compared against the column and therefore must bind with its type. */
    private static final java.util.Set<FilterOperator> COMPARISON_OPERATORS = java.util.EnumSet.of(
        FilterOperator.EQ, FilterOperator.NEQ, FilterOperator.GT, FilterOperator.GTE,
        FilterOperator.LT, FilterOperator.LTE, FilterOperator.IN);

    /**
     * The temporal type a filter must bind with: the field's own DATE/DATETIME type, or DATETIME
     * for the system audit timestamps, which have no FieldDefinition on user collections.
     *
     * @return DATE, DATETIME, or null when the column is not temporal
     */
    private static FieldType temporalType(FieldDefinition fieldDef, String fieldName) {
        if (fieldDef != null) {
            return fieldDef.type() == FieldType.DATE || fieldDef.type() == FieldType.DATETIME
                ? fieldDef.type() : null;
        }
        return switch (fieldName) {
            case "createdAt", "updatedAt" -> FieldType.DATETIME;
            default -> null;
        };
    }

    /**
     * Converts one filter value for a temporal column into the JDBC type the write path uses
     * ({@link PhysicalTableStorageAdapter#convertValueForStorage}), so reads and writes bind identically.
     *
     * @throws InvalidFilterException when the value is not an ISO-8601 date / date-time (→ 400,
     *         instead of the database rejecting the comparison with a 500)
     */
    private Object toTemporalParam(Object value, FieldType temporal, String fieldName) {
        if (value == null) {
            return null;
        }
        Object bound = valueConverter.apply(value, temporal);
        if (bound instanceof java.sql.Timestamp || bound instanceof java.sql.Date) {
            return bound;
        }
        throw new InvalidFilterException(fieldName, "expected an ISO-8601 "
            + (temporal == FieldType.DATE ? "date (yyyy-MM-dd)" : "date-time (e.g. 2026-01-01T00:00:00Z)")
            + ", got '" + value + "'");
    }

    /**
     * Builds the ORDER BY clause from sort fields.
     *
     * @param sorting the sort fields
     * @param definition the collection definition (used for column name mapping)
     * @return the ORDER BY clause (without the ORDER BY keyword)
     */
    static String buildOrderByClause(List<SortField> sorting, CollectionDefinition definition) {
        return sorting.stream()
            .map(sort -> quoteIdentifier(resolveColumnName(definition, sort.fieldName()))
                    + " " + sort.direction().name())
            .collect(Collectors.joining(", "));
    }
}
