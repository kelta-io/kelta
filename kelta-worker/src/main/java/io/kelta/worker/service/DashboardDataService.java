package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.cache.WorkerCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes dashboard widget queries, producing aggregated data for metric cards,
 * bar charts, data tables, and recent-records widgets.
 *
 * <p>Each widget type maps to a specific query strategy:
 * <ul>
 *   <li><b>metric</b> — single aggregate value (COUNT, SUM, AVG, MIN, MAX)</li>
 *   <li><b>chart</b> — grouped aggregates for bar/line/pie charts</li>
 *   <li><b>table</b> — paginated query results</li>
 *   <li><b>recent</b> — latest N records sorted by createdAt DESC</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Service
public class DashboardDataService {

    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);
    private static final int MAX_RECENT_RECORDS = 100;
    private static final int DEFAULT_RECENT_RECORDS = 10;
    private static final int MAX_TABLE_PAGE_SIZE = 500;
    private static final int DEFAULT_TABLE_PAGE_SIZE = 25;

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final CollectionLifecycleManager lifecycleManager;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkerCacheManager cacheManager;
    private final RecordMaskingService recordMaskingService;

    public DashboardDataService(QueryEngine queryEngine,
                                CollectionRegistry collectionRegistry,
                                CollectionLifecycleManager lifecycleManager,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                WorkerCacheManager cacheManager,
                                RecordMaskingService recordMaskingService) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.lifecycleManager = lifecycleManager;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.recordMaskingService = recordMaskingService;
    }

    /**
     * Executes all components of a dashboard and returns a map of componentId → result.
     */
    public Map<String, WidgetResult> executeDashboard(String dashboardId,
                                                      List<Map<String, Object>> components,
                                                      Map<String, String> runtimeParams,
                                                      ReportExecutionService.MaskingPrincipal principal) {
        Map<String, WidgetResult> results = new LinkedHashMap<>();
        for (Map<String, Object> component : components) {
            String componentId = (String) component.get("id");
            try {
                WidgetResult result = executeWidget(component, runtimeParams, principal);
                results.put(componentId, result);
            } catch (WidgetExecutionException e) {
                log.warn("Widget execution failed: componentId={}, error={}", componentId, e.getMessage());
                results.put(componentId, WidgetResult.error(e.getMessage()));
            } catch (Exception e) {
                log.error("Unexpected widget error: componentId={}", componentId, e);
                results.put(componentId, WidgetResult.error("Internal error executing widget"));
            }
        }
        return results;
    }

    /**
     * Executes a single widget/component and returns its data.
     */
    public WidgetResult executeWidget(Map<String, Object> component,
                                      Map<String, String> runtimeParams,
                                      ReportExecutionService.MaskingPrincipal principal) {
        String componentId = (String) component.get("id");
        String componentType = (String) component.get("componentType");
        String reportId = (String) component.get("reportId");

        if (componentType == null || componentType.isBlank()) {
            throw new WidgetExecutionException("Component has no componentType");
        }

        // Parse component config
        @SuppressWarnings("unchecked")
        Map<String, Object> config = component.get("config") instanceof Map
            ? (Map<String, Object>) component.get("config")
            : parseJsonConfig(component.get("config"));

        // Resolve target collection: either from the linked report or direct config
        CollectionDefinition targetCollection = resolveTargetCollection(reportId, config);
        if (targetCollection == null) {
            throw new WidgetExecutionException("Cannot resolve target collection for component " + componentId);
        }

        // Data masking: if the target collection has any masking-configured field, the
        // widget output is per-viewer, so the shared widget cache MUST be bypassed (it is
        // not keyed on the viewer — a user who can unmask would otherwise poison the cache
        // for one who cannot). maskedFields is the subset denied to THIS viewer (empty when
        // the viewer may unmask all, or for a null/system principal — the FLS trust tier).
        Map<String, FieldMaskingService.MaskingConfig> maskable =
            recordMaskingService.maskableConfigs(targetCollection);
        boolean maskingConfigured = !maskable.isEmpty();
        Set<String> maskedFields = (maskingConfigured && principal != null && principal.isPresent())
            ? recordMaskingService.maskedFieldsFor(principal.email(), principal.profileId(),
                principal.tenantId(), targetCollection.name(), maskable.keySet())
            : Set.of();

        // Shared cache is only safe for collections with no masking config.
        String cacheKey = buildCacheKey(componentId, runtimeParams);
        if (!maskingConfigured) {
            Optional<Map<String, Object>> cached = cacheManager.getDashboardWidgetData(cacheKey);
            if (cached.isPresent()) {
                log.debug("Cache hit for widget: componentId={}", componentId);
                return WidgetResult.fromCachedMap(cached.get());
            }
        }

        // Build time-range filters from runtime params
        List<FilterCondition> timeFilters = buildTimeFilters(runtimeParams, config);

        // Build widget-specific filters from config
        List<FilterCondition> configFilters = parseFilters(config.get("filters"));

        // Combine all filters
        List<FilterCondition> allFilters = new ArrayList<>(configFilters);
        allFilters.addAll(timeFilters);

        // A masked field used as a filter would leak values by probing — reject it
        // (the same guard reports apply). group-by / sort rejects live in the executors.
        for (FilterCondition f : allFilters) {
            rejectMaskedField(maskedFields, f.fieldName(), "filter on");
        }

        WidgetResult result = switch (componentType.toLowerCase()) {
            case "metric" -> executeMetricWidget(targetCollection, config, allFilters);
            case "chart" -> executeChartWidget(targetCollection, config, allFilters, maskedFields);
            case "table" -> executeTableWidget(targetCollection, config, allFilters, runtimeParams,
                maskedFields, principal);
            case "recent" -> executeRecentWidget(targetCollection, config, allFilters, principal);
            default -> throw new WidgetExecutionException("Unsupported widget type: " + componentType);
        };

        // Only cache non-masked collections (see above).
        if (!maskingConfigured) {
            cacheManager.putDashboardWidgetData(cacheKey, result.toMap());
        }

        log.info("Widget executed: componentId={}, type={}, collection={}",
            componentId, componentType, targetCollection.name());

        return result;
    }

    /** Rejects using a masked field as a predicate (filter / sort / group-by), mirroring reports. */
    private void rejectMaskedField(Set<String> maskedFields, String field, String usage) {
        if (field != null && maskedFields.contains(field)) {
            throw new WidgetExecutionException("Cannot " + usage + " a masked field: " + field);
        }
    }

    /**
     * Masks masking-configured field values in raw record rows for the viewer.
     * A {@code null}/system principal (the FLS trust tier) is not masked, matching
     * reports. {@link RecordMaskingService#maskRows} short-circuits when the collection
     * has no masking config or the viewer may unmask all.
     */
    private void maskRows(CollectionDefinition collection, List<Map<String, Object>> rows,
                          ReportExecutionService.MaskingPrincipal principal) {
        if (principal == null || !principal.isPresent()) {
            return;
        }
        recordMaskingService.maskRows(collection, rows,
            principal.email(), principal.profileId(), principal.tenantId());
    }

    // =========================================================================
    // Widget executors
    // =========================================================================

    /**
     * Metric widget: returns a single aggregate value (COUNT, SUM, AVG, MIN, MAX).
     */
    WidgetResult executeMetricWidget(CollectionDefinition collection,
                                     Map<String, Object> config,
                                     List<FilterCondition> filters) {
        String aggregateFunction = getConfigString(config, "aggregateFunction", "COUNT");
        String aggregateField = getConfigString(config, "aggregateField", null);

        // For COUNT we don't need a specific field
        if (!"COUNT".equalsIgnoreCase(aggregateFunction) &&
            (aggregateField == null || aggregateField.isBlank())) {
            throw new WidgetExecutionException(
                "aggregateField is required for " + aggregateFunction + " metric");
        }

        // Execute a query to get the data for aggregation
        List<String> fields = aggregateField != null && !aggregateField.isBlank()
            ? List.of(aggregateField) : List.of();

        QueryRequest request = new QueryRequest(
            new Pagination(1, 1000), List.of(), fields, filters);
        QueryResult queryResult = queryEngine.executeQuery(collection, request);

        Object value;
        if ("COUNT".equalsIgnoreCase(aggregateFunction)) {
            value = queryResult.metadata().totalCount();
        } else {
            value = computeAggregate(queryResult.data(), aggregateField, aggregateFunction);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", value);
        data.put("aggregateFunction", aggregateFunction);
        data.put("aggregateField", aggregateField);
        data.put("label", getConfigString(config, "label", aggregateFunction));
        data.put("totalRecords", queryResult.metadata().totalCount());

        return new WidgetResult("metric", data, null, null);
    }

    /**
     * Chart widget: returns grouped aggregates for bar/line/pie charts.
     */
    WidgetResult executeChartWidget(CollectionDefinition collection,
                                    Map<String, Object> config,
                                    List<FilterCondition> filters,
                                    Set<String> maskedFields) {
        String groupByField = getConfigString(config, "groupByField", null);
        if (groupByField == null || groupByField.isBlank()) {
            throw new WidgetExecutionException("groupByField is required for chart widgets");
        }
        // Grouping by a masked field would surface its distinct values as chart labels.
        rejectMaskedField(maskedFields, groupByField, "group by");

        String aggregateFunction = getConfigString(config, "aggregateFunction", "COUNT");
        String aggregateField = getConfigString(config, "aggregateField", null);
        int maxGroups = getConfigInt(config, "maxGroups", 20);

        // Fetch records for grouping
        List<String> fields = new ArrayList<>();
        fields.add(groupByField);
        if (aggregateField != null && !aggregateField.isBlank()) {
            fields.add(aggregateField);
        }

        QueryRequest request = new QueryRequest(
            new Pagination(1, 1000), List.of(), fields, filters);
        QueryResult queryResult = queryEngine.executeQuery(collection, request);

        // Group records by the groupBy field
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> record : queryResult.data()) {
            String key = formatValue(record.get(groupByField));
            if (key.isEmpty()) key = "(empty)";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }

        // Compute aggregates per group
        List<Map<String, Object>> series = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            if (count >= maxGroups) break;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", entry.getKey());
            point.put("count", entry.getValue().size());

            if (!"COUNT".equalsIgnoreCase(aggregateFunction) &&
                aggregateField != null && !aggregateField.isBlank()) {
                point.put("value", computeAggregate(
                    entry.getValue(), aggregateField, aggregateFunction));
            } else {
                point.put("value", entry.getValue().size());
            }

            series.add(point);
            count++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupByField", groupByField);
        data.put("aggregateFunction", aggregateFunction);
        data.put("series", series);
        data.put("totalRecords", queryResult.metadata().totalCount());

        return new WidgetResult("chart", data, null, null);
    }

    /**
     * Table widget: returns paginated query results.
     */
    WidgetResult executeTableWidget(CollectionDefinition collection,
                                    Map<String, Object> config,
                                    List<FilterCondition> filters,
                                    Map<String, String> runtimeParams,
                                    Set<String> maskedFields,
                                    ReportExecutionService.MaskingPrincipal principal) {
        List<String> fields = parseFieldList(config.get("fields"));
        List<SortField> sorting = parseSortFields(config.get("sortBy"));

        // Sorting by a masked field leaks value ordering.
        for (SortField s : sorting) {
            rejectMaskedField(maskedFields, s.fieldName(), "sort on");
        }

        int pageNumber = getIntParam(runtimeParams, "page", 1);
        int pageSize = Math.min(
            getIntParam(runtimeParams, "pageSize", DEFAULT_TABLE_PAGE_SIZE),
            MAX_TABLE_PAGE_SIZE);

        QueryRequest request = new QueryRequest(
            new Pagination(pageNumber, pageSize), sorting, fields, filters);
        QueryResult queryResult = queryEngine.executeQuery(collection, request);

        // Table returns raw record rows — mask masked-field values for this viewer.
        maskRows(collection, queryResult.data(), principal);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", queryResult.data());
        data.put("fields", fields.isEmpty() ? null : fields);

        PaginationMetadata meta = queryResult.metadata();
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("totalCount", meta.totalCount());
        pagination.put("currentPage", meta.currentPage());
        pagination.put("pageSize", meta.pageSize());
        pagination.put("totalPages", meta.totalPages());

        return new WidgetResult("table", data, pagination, null);
    }

    /**
     * Recent records widget: returns the latest N records sorted by createdAt DESC.
     */
    WidgetResult executeRecentWidget(CollectionDefinition collection,
                                     Map<String, Object> config,
                                     List<FilterCondition> filters,
                                     ReportExecutionService.MaskingPrincipal principal) {
        int limit = Math.min(
            getConfigInt(config, "limit", DEFAULT_RECENT_RECORDS),
            MAX_RECENT_RECORDS);

        List<String> fields = parseFieldList(config.get("fields"));
        List<SortField> sorting = List.of(new SortField("createdAt", SortDirection.DESC));

        QueryRequest request = new QueryRequest(
            new Pagination(1, limit), sorting, fields, filters);
        QueryResult queryResult = queryEngine.executeQuery(collection, request);

        // Recent returns raw record rows — mask masked-field values for this viewer.
        maskRows(collection, queryResult.data(), principal);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", queryResult.data());
        data.put("totalCount", queryResult.metadata().totalCount());

        return new WidgetResult("recent", data, null, null);
    }

    // =========================================================================
    // Aggregation helpers
    // =========================================================================

    Object computeAggregate(List<Map<String, Object>> records, String field, String function) {
        List<Double> values = records.stream()
            .map(r -> r.get(field))
            .filter(v -> v instanceof Number)
            .map(v -> ((Number) v).doubleValue())
            .toList();

        if (values.isEmpty()) {
            return 0;
        }

        return switch (function.toUpperCase()) {
            case "SUM" -> values.stream().mapToDouble(Double::doubleValue).sum();
            case "AVG" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            case "MIN" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case "MAX" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            case "COUNT" -> (long) values.size();
            default -> throw new WidgetExecutionException("Unsupported aggregate function: " + function);
        };
    }

    // =========================================================================
    // Collection resolution
    // =========================================================================

    CollectionDefinition resolveTargetCollection(String reportId, Map<String, Object> config) {
        // First try direct collectionId from widget config
        String collectionId = getConfigString(config, "collectionId", null);
        String collectionName = getConfigString(config, "collectionName", null);

        if (collectionName != null && !collectionName.isBlank()) {
            CollectionDefinition def = collectionRegistry.get(collectionName);
            if (def != null) return def;
            return lifecycleManager.loadCollectionByName(collectionName, null);
        }

        if (collectionId != null && !collectionId.isBlank()) {
            return resolveCollectionById(collectionId);
        }

        // Fall back to linked report's primaryCollectionId
        if (reportId != null && !reportId.isBlank()) {
            return resolveCollectionFromReport(reportId);
        }

        return null;
    }

    private CollectionDefinition resolveCollectionById(String collectionId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name FROM collection WHERE id = ? AND active = true LIMIT 1",
                collectionId);
            if (rows.isEmpty()) return null;

            String name = (String) rows.get(0).get("name");
            CollectionDefinition def = collectionRegistry.get(name);
            if (def == null) {
                def = lifecycleManager.loadCollectionByName(name, null);
            }
            return def;
        } catch (Exception e) {
            log.error("Failed to resolve collection by id: {}", collectionId, e);
            return null;
        }
    }

    private CollectionDefinition resolveCollectionFromReport(String reportId) {
        try {
            CollectionDefinition reportsDef = collectionRegistry.get("reports");
            if (reportsDef == null) return null;

            Optional<Map<String, Object>> report = queryEngine.getById(reportsDef, reportId);
            if (report.isEmpty()) return null;

            String primaryCollectionId = (String) report.get().get("primaryCollectionId");
            if (primaryCollectionId == null || primaryCollectionId.isBlank()) return null;

            return resolveCollectionById(primaryCollectionId);
        } catch (Exception e) {
            log.error("Failed to resolve collection from report: {}", reportId, e);
            return null;
        }
    }

    // =========================================================================
    // Filter and config parsing
    // =========================================================================

    List<FilterCondition> buildTimeFilters(Map<String, String> runtimeParams,
                                           Map<String, Object> config) {
        List<FilterCondition> filters = new ArrayList<>();

        // A widget that opts out of the page time range entirely (a "state right now"
        // metric, e.g. tasks currently in progress) gets no time filter at all — not even
        // explicit start/end dates — regardless of the page's selected range.
        if (getConfigBoolean(config, "ignoreTimeRange")) {
            return filters;
        }

        String timeField = getConfigString(config, "timeField", "createdAt");

        // Precedence: config.fixedTimeRange (one of TODAY|7D|30D|90D|1Y, ignoring the
        // page's selected range) > runtime timeRange (the page's selected range) >
        // config.timeRange (the widget's own default when the page sends none).
        String fixedTimeRange = getConfigString(config, "fixedTimeRange", null);
        String timeRange;
        if (fixedTimeRange != null && !fixedTimeRange.isBlank()) {
            timeRange = fixedTimeRange;
        } else {
            timeRange = runtimeParams != null ? runtimeParams.get("timeRange") : null;
            if (timeRange == null) {
                timeRange = getConfigString(config, "timeRange", null);
            }
        }

        if (timeRange != null && !timeRange.isBlank()) {
            Instant now = Instant.now();
            Instant start = switch (timeRange.toUpperCase()) {
                case "TODAY" -> now.truncatedTo(ChronoUnit.DAYS);
                case "7D", "LAST_7_DAYS" -> now.minus(7, ChronoUnit.DAYS);
                case "30D", "LAST_30_DAYS" -> now.minus(30, ChronoUnit.DAYS);
                case "90D", "LAST_90_DAYS" -> now.minus(90, ChronoUnit.DAYS);
                case "1Y", "LAST_YEAR" -> now.minus(365, ChronoUnit.DAYS);
                default -> null;
            };

            if (start != null) {
                filters.add(new FilterCondition(timeField, FilterOperator.GTE,
                    start.toString()));
            }
        }

        // Check for explicit start/end date params
        String startDate = runtimeParams != null ? runtimeParams.get("startDate") : null;
        String endDate = runtimeParams != null ? runtimeParams.get("endDate") : null;

        if (startDate != null && !startDate.isBlank()) {
            filters.add(new FilterCondition(timeField, FilterOperator.GTE, startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            filters.add(new FilterCondition(timeField, FilterOperator.LTE, endDate));
        }

        return filters;
    }

    @SuppressWarnings("unchecked")
    List<FilterCondition> parseFilters(Object filtersObj) {
        if (filtersObj == null) return List.of();

        List<Map<String, Object>> filterList;
        if (filtersObj instanceof String str) {
            if (str.isBlank()) return List.of();
            try {
                filterList = objectMapper.readValue(str, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to parse widget filters: {}", e.getMessage());
                return List.of();
            }
        } else if (filtersObj instanceof List<?>) {
            filterList = (List<Map<String, Object>>) filtersObj;
        } else {
            return List.of();
        }

        List<FilterCondition> filters = new ArrayList<>();
        for (Map<String, Object> f : filterList) {
            String field = (String) f.get("field");
            String operator = (String) f.get("operator");
            Object value = f.get("value");
            if (field == null || field.isBlank()) continue;
            filters.add(new FilterCondition(field, mapOperator(operator), value));
        }
        return filters;
    }

    @SuppressWarnings("unchecked")
    List<String> parseFieldList(Object fieldsObj) {
        if (fieldsObj == null) return List.of();
        if (fieldsObj instanceof String str) {
            if (str.isBlank()) return List.of();
            try {
                return objectMapper.readValue(str, new TypeReference<>() {});
            } catch (Exception e) {
                // Try comma-separated
                return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            }
        }
        if (fieldsObj instanceof List<?>) {
            return ((List<?>) fieldsObj).stream()
                .map(Object::toString)
                .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    List<SortField> parseSortFields(Object sortObj) {
        if (sortObj == null) return List.of();
        if (sortObj instanceof String str) {
            if (str.isBlank()) return List.of();
            return SortField.fromParams(str);
        }
        if (sortObj instanceof List<?> list) {
            List<SortField> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String field = (String) ((Map<String, Object>) m).get("field");
                    String dir = (String) ((Map<String, Object>) m).getOrDefault("direction", "ASC");
                    if (field != null && !field.isBlank()) {
                        result.add(new SortField(field,
                            "DESC".equalsIgnoreCase(dir) ? SortDirection.DESC : SortDirection.ASC));
                    }
                }
            }
            return result;
        }
        return List.of();
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private FilterOperator mapOperator(String operator) {
        if (operator == null || operator.isBlank()) return FilterOperator.EQ;
        // Widget configs historically use "contains" for case-insensitive matching
        // (mirrored in ReportExecutionService); preserve that before falling back to
        // FilterOperator's canonical case-sensitive CONTAINS.
        if ("contains".equalsIgnoreCase(operator.trim())) {
            return FilterOperator.ICONTAINS;
        }
        try {
            return FilterOperator.parse(operator);
        } catch (InvalidFilterException e) {
            throw new WidgetExecutionException("Unsupported filter operator '" + operator + "'");
        }
    }

    String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null) return defaultValue;
        Object val = config.get(key);
        if (val == null) return defaultValue;
        String str = val.toString();
        return str.isBlank() ? defaultValue : str;
    }

    boolean getConfigBoolean(Map<String, Object> config, String key) {
        if (config == null) return false;
        Object val = config.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object val = config.get(key);
        if (val instanceof Number num) return num.intValue();
        if (val instanceof String str) {
            try { return Integer.parseInt(str); }
            catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private int getIntParam(Map<String, String> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        String val = params.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private int getRefreshInterval(Map<String, Object> component) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = component.get("config") instanceof Map
            ? (Map<String, Object>) component.get("config") : Map.of();
        return getConfigInt(config, "refreshIntervalSeconds", 300);
    }

    private String buildCacheKey(String componentId, Map<String, String> runtimeParams) {
        StringBuilder sb = new StringBuilder("widget:");
        sb.append(componentId);
        if (runtimeParams != null && !runtimeParams.isEmpty()) {
            sb.append(":");
            runtimeParams.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append("&"));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonConfig(Object configObj) {
        if (configObj == null) return Map.of();
        if (configObj instanceof String str) {
            if (str.isBlank()) return Map.of();
            try {
                return objectMapper.readValue(str, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to parse widget config JSON: {}", e.getMessage());
                return Map.of();
            }
        }
        return Map.of();
    }

    private String formatValue(Object value) {
        if (value == null) return "";
        return value.toString();
    }

    // =========================================================================
    // Result types
    // =========================================================================

    public record WidgetResult(
        String type,
        Map<String, Object> data,
        Map<String, Object> pagination,
        String error
    ) {
        public static WidgetResult error(String message) {
            return new WidgetResult(null, null, null, message);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("data", data);
            if (pagination != null) map.put("pagination", pagination);
            if (error != null) map.put("error", error);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static WidgetResult fromCachedMap(Map<String, Object> map) {
            return new WidgetResult(
                (String) map.get("type"),
                (Map<String, Object>) map.get("data"),
                (Map<String, Object>) map.get("pagination"),
                (String) map.get("error")
            );
        }
    }

    public static class WidgetExecutionException extends RuntimeException {
        public WidgetExecutionException(String message) {
            super(message);
        }
    }
}
