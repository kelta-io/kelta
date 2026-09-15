package io.kelta.runtime.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Represents a filter condition for query filtering.
 *
 * <p>Filter conditions are specified in the query using the format:
 * {@code filter[field][operator]=value}
 *
 * <h2>Supported Operators</h2>
 * <ul>
 *   <li>{@code eq} - equals</li>
 *   <li>{@code neq} - not equals</li>
 *   <li>{@code gt} - greater than</li>
 *   <li>{@code lt} - less than</li>
 *   <li>{@code gte} - greater than or equal</li>
 *   <li>{@code lte} - less than or equal</li>
 *   <li>{@code isnull} - is null (value should be "true" or "false")</li>
 *   <li>{@code contains} - contains substring (case-sensitive)</li>
 *   <li>{@code starts} - starts with (case-sensitive)</li>
 *   <li>{@code ends} - ends with (case-sensitive)</li>
 *   <li>{@code icontains} - contains substring (case-insensitive)</li>
 *   <li>{@code istarts} - starts with (case-insensitive)</li>
 *   <li>{@code iends} - ends with (case-insensitive)</li>
 *   <li>{@code ieq} - equals (case-insensitive)</li>
 *   <li>{@code in} (alias {@code any}) - match any of a set of values</li>
 * </ul>
 *
 * <h2>Multi-value lookups</h2>
 * <p>Use {@code in} (or its alias {@code any}) to match a record against a set
 * of values in a single query. Two equivalent wire formats are accepted:
 * <ul>
 *   <li>Comma-separated: {@code filter[id][in]=a,b,c}</li>
 *   <li>Repeated key: {@code filter[id][in]=a&filter[id][in]=b&filter[id][in]=c}</li>
 * </ul>
 * Both forms produce a single {@code id IN (?, ?, ?)} SQL clause with the
 * values bound as parameters. The combined list is capped at
 * {@link #MAX_IN_LIST_SIZE} per condition; over-cap requests fail with
 * {@link InvalidFilterException} (HTTP 400).
 *
 * <p><b>{@code eq} treats commas as literal characters.</b> A request like
 * {@code filter[name][eq]=Smith,John} matches the single literal string
 * {@code "Smith,John"} — not two values. Use {@code in} for multi-value
 * lookups. This behavior is preserved deliberately so legitimate values
 * containing commas (display names, descriptions) continue to round-trip.
 *
 * <h2>Examples</h2>
 * <ul>
 *   <li>{@code filter[status][eq]=active} - status equals "active"</li>
 *   <li>{@code filter[price][gte]=100} - price >= 100</li>
 *   <li>{@code filter[name][icontains]=john} - name contains "john" (case-insensitive)</li>
 *   <li>{@code filter[id][in]=u1,u2,u3} - id is one of u1, u2, u3</li>
 * </ul>
 *
 * @param fieldName the name of the field to filter on
 * @param operator the filter operator
 * @param value the value to compare against (a {@link Collection} for {@code in})
 *
 * @see FilterOperator
 * @since 1.0.0
 */
public record FilterCondition(
    String fieldName,
    FilterOperator operator,
    Object value
) {
    /**
     * Pattern to match filter parameters: filter[fieldName][operator]
     */
    private static final Pattern FILTER_PATTERN = Pattern.compile("filter\\[([^\\]]+)\\]\\[([^\\]]+)\\]");

    /**
     * Pattern to match the EQ shorthand: filter[fieldName] (no operator segment).
     */
    private static final Pattern FILTER_SHORTHAND_PATTERN = Pattern.compile("filter\\[([^\\]]+)\\]");

    /**
     * Grammar shown to callers whose {@code filter...} key matches neither
     * {@link #FILTER_PATTERN} nor {@link #FILTER_SHORTHAND_PATTERN}.
     */
    private static final String FILTER_GRAMMAR = "filter[field][op]=value | filter[field]=value";

    /**
     * Maximum number of values accepted in a single {@code IN}/{@code ANY}
     * list. Exceeding this throws {@link InvalidFilterException}, which the
     * gateway maps to HTTP 400. The cap exists so an untrusted caller can't
     * synthesize an arbitrarily large {@code IN (?, ?, …)} clause and exhaust
     * the JDBC parameter budget or planner.
     */
    public static final int MAX_IN_LIST_SIZE = 200;

    /**
     * Compact constructor with validation.
     */
    public FilterCondition {
        Objects.requireNonNull(fieldName, "fieldName cannot be null");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be blank");
        }
        Objects.requireNonNull(operator, "operator cannot be null");
        // value can be null for ISNULL operator
    }

    /**
     * Creates a filter condition for equality.
     *
     * @param fieldName the field name
     * @param value the value to match
     * @return a new FilterCondition with EQ operator
     */
    public static FilterCondition eq(String fieldName, Object value) {
        return new FilterCondition(fieldName, FilterOperator.EQ, value);
    }

    /**
     * Creates a filter condition for not equals.
     *
     * @param fieldName the field name
     * @param value the value to not match
     * @return a new FilterCondition with NEQ operator
     */
    public static FilterCondition neq(String fieldName, Object value) {
        return new FilterCondition(fieldName, FilterOperator.NEQ, value);
    }

    /**
     * Creates a filter condition for greater than.
     *
     * @param fieldName the field name
     * @param value the value to compare against
     * @return a new FilterCondition with GT operator
     */
    public static FilterCondition gt(String fieldName, Object value) {
        return new FilterCondition(fieldName, FilterOperator.GT, value);
    }

    /**
     * Creates a filter condition for less than.
     *
     * @param fieldName the field name
     * @param value the value to compare against
     * @return a new FilterCondition with LT operator
     */
    public static FilterCondition lt(String fieldName, Object value) {
        return new FilterCondition(fieldName, FilterOperator.LT, value);
    }

    /**
     * Creates a filter condition for is null check.
     *
     * @param fieldName the field name
     * @param isNull true to check for null, false to check for not null
     * @return a new FilterCondition with ISNULL operator
     */
    public static FilterCondition isNull(String fieldName, boolean isNull) {
        return new FilterCondition(fieldName, FilterOperator.ISNULL, isNull);
    }

    /**
     * Creates a filter condition for contains (case-sensitive).
     *
     * @param fieldName the field name
     * @param value the substring to search for
     * @return a new FilterCondition with CONTAINS operator
     */
    public static FilterCondition contains(String fieldName, String value) {
        return new FilterCondition(fieldName, FilterOperator.CONTAINS, value);
    }

    /**
     * Creates a filter condition for contains (case-insensitive).
     *
     * @param fieldName the field name
     * @param value the substring to search for
     * @return a new FilterCondition with ICONTAINS operator
     */
    public static FilterCondition icontains(String fieldName, String value) {
        return new FilterCondition(fieldName, FilterOperator.ICONTAINS, value);
    }

    /**
     * Creates a filter condition for IN matching against a set of values.
     *
     * @param fieldName the field name
     * @param values the candidate values
     * @return a new FilterCondition with IN operator
     */
    public static FilterCondition in(String fieldName, Collection<?> values) {
        return new FilterCondition(fieldName, FilterOperator.IN, List.copyOf(values));
    }

    /**
     * Parses filter conditions from HTTP query parameters that may contain
     * repeated keys (e.g. {@code filter[id][in]=a&filter[id][in]=b}).
     *
     * <p>Behavior:
     * <ul>
     *   <li>{@code filter[field]=value} (no operator segment) is shorthand for
     *       {@code eq}.</li>
     *   <li>{@code in}/{@code any}: all repeated values are merged, each value
     *       is additionally split on {@code ,}, blanks are trimmed and dropped,
     *       and the result is de-duplicated. Yields a single
     *       {@link FilterCondition} whose value is a {@link List}.</li>
     *   <li>All other operators: the last value wins (matches Spring's
     *       single-value {@code @RequestParam Map} semantics) and the raw
     *       string is passed through.</li>
     * </ul>
     *
     * <p>Non-filter keys (e.g. {@code sort}, {@code page[size]}) are ignored.
     * Any key that starts with {@code filter[} but matches neither shape above
     * (e.g. {@code filter[field][in][]=value}) is rejected rather than
     * silently dropped.
     *
     * @param params the HTTP query parameters
     * @return list of filter conditions, or empty list if none found
     * @throws InvalidFilterException if a {@code filter[...]} key doesn't match
     *         {@code filter[field][op]=value} or {@code filter[field]=value},
     *         an operator is unrecognized, an {@code in} list is blank, or the
     *         {@code in} list exceeds {@link #MAX_IN_LIST_SIZE}
     */
    public static List<FilterCondition> fromParams(MultiValueMap<String, String> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }

        List<FilterCondition> filters = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("filter[")) {
                continue;
            }

            Matcher opMatcher = FILTER_PATTERN.matcher(key);
            if (opMatcher.matches()) {
                String fieldName = opMatcher.group(1);
                String operatorToken = opMatcher.group(2).toUpperCase();

                FilterOperator operator = resolveOperator(fieldName, operatorToken);
                List<String> rawValues = entry.getValue();

                if (operator == FilterOperator.IN) {
                    List<String> values = collectInValues(fieldName, rawValues);
                    filters.add(new FilterCondition(fieldName, operator, values));
                } else {
                    String value = (rawValues == null || rawValues.isEmpty())
                            ? null
                            : rawValues.get(rawValues.size() - 1);
                    Object parsedValue = parseValue(value, operator);
                    filters.add(new FilterCondition(fieldName, operator, parsedValue));
                }
                continue;
            }

            Matcher shorthandMatcher = FILTER_SHORTHAND_PATTERN.matcher(key);
            if (shorthandMatcher.matches()) {
                String fieldName = shorthandMatcher.group(1);
                List<String> rawValues = entry.getValue();
                String value = (rawValues == null || rawValues.isEmpty())
                        ? null
                        : rawValues.get(rawValues.size() - 1);
                filters.add(new FilterCondition(fieldName, FilterOperator.EQ, value));
                continue;
            }

            throw new InvalidFilterException(key, "malformed filter parameter; expected " + FILTER_GRAMMAR);
        }

        return List.copyOf(filters);
    }

    /**
     * Backwards-compatible adapter for callers that only have a single-value
     * map (no repeated keys). Internal services that don't go through the
     * gateway use this — e.g. {@code BulkOperationService}.
     */
    public static List<FilterCondition> fromParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        MultiValueMap<String, String> multi = new LinkedMultiValueMap<>(params.size());
        for (Map.Entry<String, String> e : params.entrySet()) {
            multi.add(e.getKey(), e.getValue());
        }
        return fromParams(multi);
    }

    private static FilterOperator resolveOperator(String fieldName, String operatorToken) {
        try {
            return FilterOperator.parse(operatorToken);
        } catch (InvalidFilterException e) {
            throw new InvalidFilterException(fieldName, e.getReason());
        }
    }

    private static List<String> collectInValues(String fieldName, List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            throw new InvalidFilterException(fieldName, "'in' filter requires at least one value");
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String raw : rawValues) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    deduped.add(trimmed);
                }
            }
        }
        if (deduped.isEmpty()) {
            throw new InvalidFilterException(fieldName, "'in' filter requires at least one non-blank value");
        }
        if (deduped.size() > MAX_IN_LIST_SIZE) {
            throw new InvalidFilterException(
                    fieldName,
                    "'in' filter accepts at most " + MAX_IN_LIST_SIZE
                            + " values, got " + deduped.size());
        }
        return List.copyOf(deduped);
    }

    /**
     * Parses the filter value based on the operator.
     *
     * @param value the string value
     * @param operator the filter operator
     * @return the parsed value
     */
    private static Object parseValue(String value, FilterOperator operator) {
        if (operator == FilterOperator.ISNULL) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }
}
