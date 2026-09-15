package io.kelta.runtime.query;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Filter operators for query filtering.
 *
 * <p>These operators are used in filter conditions to specify how field values
 * should be compared against filter values.
 *
 * @since 1.0.0
 */
public enum FilterOperator {
    /**
     * Equals (case-sensitive for strings).
     * <p>Usage: {@code filter[field][eq]=value}
     */
    EQ,
    
    /**
     * Not equals (case-sensitive for strings).
     * <p>Usage: {@code filter[field][neq]=value}
     */
    NEQ,
    
    /**
     * Greater than.
     * <p>Usage: {@code filter[field][gt]=value}
     */
    GT,
    
    /**
     * Less than.
     * <p>Usage: {@code filter[field][lt]=value}
     */
    LT,
    
    /**
     * Greater than or equal.
     * <p>Usage: {@code filter[field][gte]=value}
     */
    GTE,
    
    /**
     * Less than or equal.
     * <p>Usage: {@code filter[field][lte]=value}
     */
    LTE,
    
    /**
     * Is null check.
     * <p>Usage: {@code filter[field][isnull]=true} or {@code filter[field][isnull]=false}
     */
    ISNULL,
    
    /**
     * Contains substring (case-sensitive).
     * <p>Usage: {@code filter[field][contains]=value}
     */
    CONTAINS,
    
    /**
     * Starts with (case-sensitive).
     * <p>Usage: {@code filter[field][starts]=value}
     */
    STARTS,
    
    /**
     * Ends with (case-sensitive).
     * <p>Usage: {@code filter[field][ends]=value}
     */
    ENDS,
    
    /**
     * Contains substring (case-insensitive).
     * <p>Usage: {@code filter[field][icontains]=value}
     */
    ICONTAINS,
    
    /**
     * Starts with (case-insensitive).
     * <p>Usage: {@code filter[field][istarts]=value}
     */
    ISTARTS,
    
    /**
     * Ends with (case-insensitive).
     * <p>Usage: {@code filter[field][iends]=value}
     */
    IENDS,
    
    /**
     * Equals (case-insensitive).
     * <p>Usage: {@code filter[field][ieq]=value}
     */
    IEQ,

    /**
     * In a set of values.
     * <p>The filter value should be a {@link java.util.Collection} of values.
     * <p>Usage (internal): created programmatically via
     * {@code new FilterCondition("field", FilterOperator.IN, listOfValues)}
     */
    IN;

    /**
     * Canonical enum names plus the UI's aliases (e.g. {@code equals}, {@code any}),
     * keyed uppercase for case-insensitive lookup.
     */
    private static final Map<String, FilterOperator> ALIASES = buildAliases();

    private static Map<String, FilterOperator> buildAliases() {
        Map<String, FilterOperator> map = new LinkedHashMap<>();
        for (FilterOperator op : values()) {
            map.put(op.name(), op);
        }
        map.put("ANY", IN);
        map.put("EQUALS", EQ);
        map.put("NOT_EQUALS", NEQ);
        map.put("GREATER_THAN", GT);
        map.put("LESS_THAN", LT);
        map.put("GREATER_THAN_OR_EQUAL", GTE);
        map.put("LESS_THAN_OR_EQUAL", LTE);
        map.put("STARTS_WITH", STARTS);
        map.put("ENDS_WITH", ENDS);
        return Map.copyOf(map);
    }

    /**
     * Resolves a filter operator token — a canonical name (case-insensitive,
     * e.g. {@code eq}, {@code in}), the {@code any} alias for {@link #IN}, or
     * an end-user-UI alias ({@code equals}, {@code not_equals},
     * {@code greater_than}, {@code less_than}, {@code greater_than_or_equal},
     * {@code less_than_or_equal}, {@code starts_with}, {@code ends_with}).
     *
     * @param token the operator token
     * @return the resolved operator
     * @throws InvalidFilterException if the token is blank or unrecognized;
     *         the message lists the accepted canonical names
     */
    public static FilterOperator parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidFilterException(
                    "filter operator is required; accepted: " + acceptedNames());
        }
        FilterOperator operator = ALIASES.get(token.trim().toUpperCase(Locale.ROOT));
        if (operator == null) {
            throw new InvalidFilterException(
                    "unknown filter operator '" + token.toLowerCase(Locale.ROOT)
                            + "'; accepted: " + acceptedNames());
        }
        return operator;
    }

    private static String acceptedNames() {
        StringBuilder sb = new StringBuilder();
        for (FilterOperator op : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(op.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
