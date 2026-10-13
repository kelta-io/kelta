package io.kelta.runtime.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.util.MultiValueMap;

/**
 * Represents a query request with pagination, sorting, field selection, and filtering.
 * 
 * <p>This record encapsulates all query parameters that can be specified when
 * querying a collection. It supports:
 * <ul>
 *   <li><b>Pagination</b> - Page number and page size</li>
 *   <li><b>Sorting</b> - Multiple sort fields with ascending/descending order</li>
 *   <li><b>Field Selection</b> - Specific fields to return in the response</li>
 *   <li><b>Filtering</b> - Multiple filter conditions combined with AND logic</li>
 * </ul>
 * 
 * <h2>Query Parameter Formats</h2>
 * <ul>
 *   <li>Pagination: {@code page[number]=1&page[size]=20}</li>
 *   <li>Sorting: {@code sort=field1,-field2} (prefix with - for descending)</li>
 *   <li>Fields: {@code fields=fieldA,fieldB} or {@code fields[type]=fieldA,fieldB}</li>
 *   <li>Filtering: {@code filter[field][op]=value}</li>
 * </ul>
 * 
 * @param pagination pagination settings (page number and size)
 * @param sorting list of sort fields with direction
 * @param fields list of field names to return (empty means all fields)
 * @param filters list of filter conditions
 * 
 * @see Pagination
 * @see SortField
 * @see FilterCondition
 * @since 1.0.0
 */
public record QueryRequest(
    Pagination pagination,
    List<SortField> sorting,
    List<String> fields,
    List<FilterCondition> filters
) {
    /**
     * Compact constructor with validation and defensive copying.
     */
    public QueryRequest {
        Objects.requireNonNull(pagination, "pagination cannot be null");
        sorting = sorting != null ? List.copyOf(sorting) : List.of();
        fields = fields != null ? List.copyOf(fields) : List.of();
        filters = filters != null ? List.copyOf(filters) : List.of();
    }
    
    /**
     * Creates a QueryRequest from HTTP query parameters.
     * 
     * <p>Parses the following parameter formats:
     * <ul>
     *   <li>{@code page[number]} - Page number (default: 1)</li>
     *   <li>{@code page[size]} - Page size (default: 20)</li>
     *   <li>{@code sort} - Comma-separated sort fields (prefix with - for descending)</li>
     *   <li>{@code fields} or {@code fields[type]} - Comma-separated field names</li>
     *   <li>{@code filter[field][op]} - Filter conditions</li>
     * </ul>
     * 
     * @param params the HTTP query parameters
     * @return a new QueryRequest parsed from the parameters
     */
    public static QueryRequest fromParams(Map<String, String> params) {
        Pagination pagination = Pagination.fromParams(params);
        List<SortField> sorting = SortField.fromParams(params.get("sort"));
        List<String> fields = parseFields(resolveFieldsParam(params));
        List<FilterCondition> filters = FilterCondition.fromParams(params);

        return new QueryRequest(pagination, sorting, fields, filters);
    }

    /**
     * Variant of {@link #fromParams(Map)} that preserves repeated query
     * parameters — needed so {@code filter[id][in]=a&filter[id][in]=b} is read
     * as a list rather than collapsing to the last value. Scalar parameters
     * (page, sort, fields, include) read the first value via
     * {@link MultiValueMap#getFirst(Object)}.
     *
     * @param params the HTTP query parameters as a multi-value map
     * @return a new QueryRequest parsed from the parameters
     */
    public static QueryRequest fromParams(MultiValueMap<String, String> params) {
        Map<String, String> single = params != null ? params.toSingleValueMap() : Map.of();
        Pagination pagination = Pagination.fromParams(single);
        List<SortField> sorting = SortField.fromParams(single.get("sort"));
        List<String> fields = parseFields(resolveFieldsParam(single));
        List<FilterCondition> filters = FilterCondition.fromParams(params);

        return new QueryRequest(pagination, sorting, fields, filters);
    }
    
    /**
     * Reads the sparse fieldset ({@code fields=a,b} or {@code fields[type]=a,b}) with the
     * same rules {@link #fromParams(MultiValueMap)} applies, without parsing the rest of
     * the query — for reads such as get-by-id that take no pagination or filters.
     *
     * @param params the HTTP query parameters, may be null
     * @return the requested field names, or an empty list if none were requested
     */
    public static List<String> fieldsFromParams(MultiValueMap<String, String> params) {
        return params != null ? parseFields(resolveFieldsParam(params.toSingleValueMap())) : List.of();
    }

    /**
     * Creates a default QueryRequest with default pagination and no sorting, filtering, or field selection.
     * 
     * @return a new QueryRequest with defaults
     */
    public static QueryRequest defaults() {
        return new QueryRequest(
            Pagination.defaults(),
            List.of(),
            List.of(),
            List.of()
        );
    }
    
    /**
     * Resolves the fields parameter value, supporting both plain {@code fields=...}
     * and JSON:API spec-compliant {@code fields[type]=...} formats.
     *
     * @param params the HTTP query parameters
     * @return the raw fields parameter value, or null if not present
     */
    private static String resolveFieldsParam(Map<String, String> params) {
        // Check plain format first: fields=a,b
        String plain = params.get("fields");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        // Check JSON:API sparse fieldsets format: fields[type]=a,b
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("fields[") && key.endsWith("]")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Parses the fields parameter into a list of field names.
     *
     * @param fieldsParam the fields parameter value (comma-separated)
     * @return list of field names, or empty list if null/blank
     */
    private static List<String> parseFields(String fieldsParam) {
        if (fieldsParam == null || fieldsParam.isBlank()) {
            return List.of();
        }
        return List.of(fieldsParam.split(","))
            .stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
    
    /**
     * Checks if this request has any sorting specified.
     * 
     * @return true if sorting is specified
     */
    public boolean hasSorting() {
        return !sorting.isEmpty();
    }
    
    /**
     * Checks if this request has field selection specified.
     * 
     * @return true if specific fields are requested
     */
    public boolean hasFieldSelection() {
        return !fields.isEmpty();
    }
    
    /**
     * Checks if this request has any filters specified.
     *
     * @return true if filters are specified
     */
    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    /**
     * Creates a new QueryRequest with the specified filters, preserving all
     * other settings (pagination, sorting, fields).
     *
     * @param newFilters the new filter conditions
     * @return a new QueryRequest with the updated filters
     */
    public QueryRequest withFilters(List<FilterCondition> newFilters) {
        return new QueryRequest(pagination, sorting, fields, newFilters);
    }
}
