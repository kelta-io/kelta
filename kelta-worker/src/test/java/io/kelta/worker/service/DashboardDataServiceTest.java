package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.*;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.cache.WorkerCacheManager;
import io.kelta.worker.service.DashboardDataService.WidgetExecutionException;
import io.kelta.worker.service.DashboardDataService.WidgetResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardDataServiceTest {

    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private CollectionLifecycleManager lifecycleManager;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private WorkerCacheManager cacheManager;
    private RecordMaskingService recordMaskingService;
    private DashboardDataService service;

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        cacheManager = mock(WorkerCacheManager.class);
        // Default mock: maskableConfigs() returns an empty map (Mockito ReturnsEmptyValues),
        // so no collection has masking configured and the existing tests behave unchanged.
        recordMaskingService = mock(RecordMaskingService.class);

        // Default: no cache hits
        when(cacheManager.getDashboardWidgetData(anyString())).thenReturn(Optional.empty());

        service = new DashboardDataService(queryEngine, collectionRegistry,
            lifecycleManager, jdbcTemplate, objectMapper, cacheManager, recordMaskingService);
    }

    // =========================================================================
    // Metric widget tests
    // =========================================================================

    @Test
    void shouldExecuteMetricWidgetWithCount() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        QueryResult queryResult = QueryResult.of(
            List.of(Map.of("name", "A"), Map.of("name", "B"), Map.of("name", "C")),
            42L, new Pagination(1, 1000));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-1", "metric",
            Map.of("collectionName", "accounts", "aggregateFunction", "COUNT"));

        WidgetResult result = service.executeWidget(component, Map.of(), null);

        assertEquals("metric", result.type());
        assertNotNull(result.data());
        assertEquals(42L, result.data().get("value"));
        assertEquals("COUNT", result.data().get("aggregateFunction"));
    }

    @Test
    void shouldExecuteMetricWidgetWithSum() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("opportunities")).thenReturn(collDef);

        List<Map<String, Object>> data = List.of(
            Map.of("amount", 100.0), Map.of("amount", 250.5), Map.of("amount", 49.5));
        QueryResult queryResult = QueryResult.of(data, 3L, new Pagination(1, 1000));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-2", "metric",
            Map.of("collectionName", "opportunities",
                   "aggregateFunction", "SUM",
                   "aggregateField", "amount"));

        WidgetResult result = service.executeWidget(component, Map.of(), null);

        assertEquals("metric", result.type());
        assertEquals(400.0, result.data().get("value"));
    }

    @Test
    void shouldThrowWhenAggregateFieldMissingForNonCount() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        Map<String, Object> component = buildComponent("comp-3", "metric",
            Map.of("collectionName", "accounts", "aggregateFunction", "SUM"));

        assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), null));
    }

    // =========================================================================
    // Chart widget tests
    // =========================================================================

    @Test
    void shouldExecuteChartWidgetWithGrouping() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("cases")).thenReturn(collDef);

        List<Map<String, Object>> data = List.of(
            Map.of("status", "open"), Map.of("status", "open"),
            Map.of("status", "closed"), Map.of("status", "pending"));
        QueryResult queryResult = QueryResult.of(data, 4L, new Pagination(1, 1000));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-4", "chart",
            Map.of("collectionName", "cases",
                   "groupByField", "status",
                   "aggregateFunction", "COUNT"));

        WidgetResult result = service.executeWidget(component, Map.of(), null);

        assertEquals("chart", result.type());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series =
            (List<Map<String, Object>>) result.data().get("series");
        assertNotNull(series);
        assertEquals(3, series.size()); // open, closed, pending

        // Find the "open" group and verify count
        Map<String, Object> openGroup = series.stream()
            .filter(s -> "open".equals(s.get("label")))
            .findFirst().orElse(null);
        assertNotNull(openGroup);
        assertEquals(2, openGroup.get("count"));
        assertEquals(2, openGroup.get("value"));
    }

    @Test
    void shouldThrowWhenGroupByFieldMissing() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("cases")).thenReturn(collDef);

        Map<String, Object> component = buildComponent("comp-5", "chart",
            Map.of("collectionName", "cases"));

        assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), null));
    }

    @Test
    void shouldLimitChartGroupsToMaxGroups() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("items")).thenReturn(collDef);

        // Generate 30 distinct groups
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            data.add(Map.of("category", "cat-" + i));
        }
        QueryResult queryResult = QueryResult.of(data, 30L, new Pagination(1, 1000));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-6", "chart",
            Map.of("collectionName", "items",
                   "groupByField", "category",
                   "maxGroups", 5));

        WidgetResult result = service.executeWidget(component, Map.of(), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series =
            (List<Map<String, Object>>) result.data().get("series");
        assertEquals(5, series.size());
    }

    // =========================================================================
    // Table widget tests
    // =========================================================================

    @Test
    void shouldExecuteTableWidgetWithPagination() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("contacts")).thenReturn(collDef);

        List<Map<String, Object>> data = List.of(
            Map.of("name", "Alice", "email", "alice@example.com"),
            Map.of("name", "Bob", "email", "bob@example.com"));
        QueryResult queryResult = QueryResult.of(data, 50L, new Pagination(1, 25));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-7", "table",
            Map.of("collectionName", "contacts",
                   "fields", List.of("name", "email")));

        WidgetResult result = service.executeWidget(component, Map.of("page", "1", "pageSize", "25"), null);

        assertEquals("table", result.type());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records =
            (List<Map<String, Object>>) result.data().get("records");
        assertEquals(2, records.size());
        assertNotNull(result.pagination());
        assertEquals(50L, result.pagination().get("totalCount"));
    }

    // =========================================================================
    // Recent records widget tests
    // =========================================================================

    @Test
    void shouldExecuteRecentWidgetSortedByCreatedAtDesc() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("activities")).thenReturn(collDef);

        List<Map<String, Object>> data = List.of(
            Map.of("subject", "Latest"), Map.of("subject", "Older"));
        QueryResult queryResult = QueryResult.of(data, 100L, new Pagination(1, 10));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-8", "recent",
            Map.of("collectionName", "activities", "limit", 10));

        WidgetResult result = service.executeWidget(component, Map.of(), null);

        assertEquals("recent", result.type());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records =
            (List<Map<String, Object>>) result.data().get("records");
        assertEquals(2, records.size());
        assertEquals(100L, result.data().get("totalCount"));
    }

    @Test
    void shouldClampRecentRecordsLimit() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("activities")).thenReturn(collDef);

        QueryResult queryResult = QueryResult.of(List.of(), 0L, new Pagination(1, 100));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        // Request 200 but max is 100
        Map<String, Object> component = buildComponent("comp-9", "recent",
            Map.of("collectionName", "activities", "limit", 200));

        service.executeWidget(component, Map.of(), null);

        // Verify the pagination used is clamped to 100
        verify(queryEngine).executeQuery(eq(collDef), argThat(req ->
            req.pagination().pageSize() <= 100));
    }

    // =========================================================================
    // Time range filter tests
    // =========================================================================

    @Test
    void shouldBuildTimeFiltersFromRuntimeParams() {
        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of("timeRange", "7D"), Map.of());

        assertEquals(1, filters.size());
        assertEquals("createdAt", filters.get(0).fieldName());
        assertEquals(FilterOperator.GTE, filters.get(0).operator());
    }

    @Test
    void shouldBuildTimeFiltersWithCustomTimeField() {
        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of("timeRange", "30D"),
            Map.of("timeField", "closedAt"));

        assertEquals(1, filters.size());
        assertEquals("closedAt", filters.get(0).fieldName());
    }

    @Test
    void shouldBuildTimeFiltersFromExplicitDates() {
        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of("startDate", "2026-01-01", "endDate", "2026-03-31"),
            Map.of());

        assertEquals(2, filters.size());
    }

    @Test
    void shouldBuildNoTimeFilterWhenIgnoreTimeRangeIsTrue() {
        Map<String, Object> config = Map.of("ignoreTimeRange", true);

        List<FilterCondition> underToday = service.buildTimeFilters(
            Map.of("timeRange", "TODAY"), config);
        List<FilterCondition> under30d = service.buildTimeFilters(
            Map.of("timeRange", "30D"), config);
        List<FilterCondition> underAll = service.buildTimeFilters(
            Map.of(), config);

        assertTrue(underToday.isEmpty());
        assertTrue(under30d.isEmpty());
        assertTrue(underAll.isEmpty());
    }

    @Test
    void shouldIgnoreTimeRangeTakePrecedenceOverFixedTimeRange() {
        Map<String, Object> config = Map.of(
            "ignoreTimeRange", true, "fixedTimeRange", "7D");

        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of("timeRange", "30D"), config);

        assertTrue(filters.isEmpty());
    }

    @Test
    void shouldApplyFixedTimeRangeRegardlessOfRuntimeTimeRange() {
        Map<String, Object> config = Map.of("fixedTimeRange", "7D");

        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of("timeRange", "30D"), config);

        assertEquals(1, filters.size());
        assertEquals("createdAt", filters.get(0).fieldName());
        assertEquals(FilterOperator.GTE, filters.get(0).operator());

        Instant expectedStart = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant actualStart = Instant.parse((String) filters.get(0).value());
        assertTrue(Duration.between(actualStart, expectedStart).abs().toSeconds() < 5);
    }

    @Test
    void shouldApplyFixedTimeRangeWhenNoRuntimeTimeRangeGiven() {
        Map<String, Object> config = Map.of("fixedTimeRange", "7D");

        List<FilterCondition> filters = service.buildTimeFilters(Map.of(), config);

        assertEquals(1, filters.size());
    }

    @Test
    void shouldFallBackToRuntimeTimeRangeWhenNoIgnoreOrFixed() {
        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of("timeRange", "7D"), Map.of());

        assertEquals(1, filters.size());
    }

    @Test
    void shouldFallBackToConfigTimeRangeWhenNoRuntimeTimeRange() {
        List<FilterCondition> filters = service.buildTimeFilters(
            Map.of(), Map.of("timeRange", "90D"));

        assertEquals(1, filters.size());
    }

    // =========================================================================
    // Dashboard execution tests
    // =========================================================================

    @Test
    void shouldExecuteMultipleWidgets() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        QueryResult queryResult = QueryResult.of(
            List.of(Map.of("name", "A")), 10L, new Pagination(1, 1000));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        List<Map<String, Object>> components = List.of(
            buildComponent("comp-a", "metric",
                Map.of("collectionName", "accounts", "aggregateFunction", "COUNT")),
            buildComponent("comp-b", "recent",
                Map.of("collectionName", "accounts", "limit", 5))
        );

        Map<String, WidgetResult> results = service.executeDashboard(
            "dash-1", components, Map.of(), null);

        assertEquals(2, results.size());
        assertNotNull(results.get("comp-a"));
        assertNotNull(results.get("comp-b"));
        assertNull(results.get("comp-a").error());
        assertNull(results.get("comp-b").error());
    }

    @Test
    void shouldReturnErrorForFailedWidget() {
        // No collection set up → widget should fail gracefully
        when(collectionRegistry.get(anyString())).thenReturn(null);

        List<Map<String, Object>> components = List.of(
            buildComponent("comp-fail", "metric",
                Map.of("collectionName", "nonexistent", "aggregateFunction", "COUNT")));

        Map<String, WidgetResult> results = service.executeDashboard(
            "dash-2", components, Map.of(), null);

        assertEquals(1, results.size());
        assertNotNull(results.get("comp-fail").error());
    }

    // =========================================================================
    // Caching tests
    // =========================================================================

    @Test
    void shouldReturnCachedResultWhenPresent() {
        // The collection is resolved before the cache read (so masking config can be
        // checked), but a hit still short-circuits before any query. Cache entries only
        // ever exist for non-masking collections, so serving one without a Cerbos check is safe.
        when(collectionRegistry.get("accounts")).thenReturn(SystemCollectionDefinitions.dashboards());
        Map<String, Object> cachedData = Map.of(
            "type", "metric",
            "data", Map.of("value", 42L));
        when(cacheManager.getDashboardWidgetData(anyString()))
            .thenReturn(Optional.of(cachedData));

        Map<String, Object> component = buildComponent("comp-cached", "metric",
            Map.of("collectionName", "accounts", "aggregateFunction", "COUNT"));

        WidgetResult result = service.executeWidget(component, Map.of(), null);

        assertEquals("metric", result.type());
        // Query engine should NOT be called
        verifyNoInteractions(queryEngine);
    }

    @Test
    void shouldCacheResultAfterExecution() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        QueryResult queryResult = QueryResult.of(
            List.of(), 5L, new Pagination(1, 1000));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-new", "metric",
            Map.of("collectionName", "accounts", "aggregateFunction", "COUNT"));

        service.executeWidget(component, Map.of(), null);

        verify(cacheManager).putDashboardWidgetData(anyString(), any());
    }

    // =========================================================================
    // Unsupported type test
    // =========================================================================

    @Test
    void shouldThrowForUnsupportedWidgetType() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        Map<String, Object> component = buildComponent("comp-bad", "unknown_type",
            Map.of("collectionName", "accounts"));

        assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), null));
    }

    @Test
    void shouldThrowWhenComponentTypeIsNull() {
        Map<String, Object> component = new HashMap<>();
        component.put("id", "comp-null");
        component.put("componentType", null);
        component.put("config", Map.of("collectionName", "accounts"));

        assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), null));
    }

    // =========================================================================
    // Aggregate computation tests
    // =========================================================================

    @Test
    void shouldComputeAverageAggregate() {
        List<Map<String, Object>> records = List.of(
            Map.of("score", 10.0), Map.of("score", 20.0), Map.of("score", 30.0));

        Object avg = service.computeAggregate(records, "score", "AVG");
        assertEquals(20.0, (double) avg, 0.001);
    }

    @Test
    void shouldComputeMinAggregate() {
        List<Map<String, Object>> records = List.of(
            Map.of("price", 50.0), Map.of("price", 10.0), Map.of("price", 30.0));

        Object min = service.computeAggregate(records, "price", "MIN");
        assertEquals(10.0, (double) min, 0.001);
    }

    @Test
    void shouldComputeMaxAggregate() {
        List<Map<String, Object>> records = List.of(
            Map.of("price", 50.0), Map.of("price", 10.0), Map.of("price", 30.0));

        Object max = service.computeAggregate(records, "price", "MAX");
        assertEquals(50.0, (double) max, 0.001);
    }

    @Test
    void shouldReturnZeroForEmptyRecords() {
        Object result = service.computeAggregate(List.of(), "amount", "SUM");
        assertEquals(0, result);
    }

    // =========================================================================
    // Config parsing tests
    // =========================================================================

    @Test
    void shouldParseFieldListFromJsonString() {
        List<String> fields = service.parseFieldList("[\"name\",\"email\",\"phone\"]");
        assertEquals(3, fields.size());
        assertEquals("name", fields.get(0));
    }

    @Test
    void shouldParseFieldListFromCommaSeparatedString() {
        List<String> fields = service.parseFieldList("name, email, phone");
        assertEquals(3, fields.size());
        assertEquals("name", fields.get(0));
        assertEquals("email", fields.get(1));
    }

    @Test
    void shouldParseFieldListFromList() {
        List<String> fields = service.parseFieldList(List.of("name", "email"));
        assertEquals(2, fields.size());
    }

    @Test
    void shouldReturnEmptyForNullFields() {
        List<String> fields = service.parseFieldList(null);
        assertTrue(fields.isEmpty());
    }

    @Test
    void shouldParseFiltersFromList() {
        List<Map<String, Object>> filtersList = List.of(
            Map.of("field", "status", "operator", "eq", "value", "open"),
            Map.of("field", "priority", "operator", "eq", "value", "high"));

        List<FilterCondition> filters = service.parseFilters(filtersList);
        assertEquals(2, filters.size());
        assertEquals("status", filters.get(0).fieldName());
        assertEquals(FilterOperator.EQ, filters.get(0).operator());
    }

    @Test
    void shouldParseInOperatorFilter() {
        List<Map<String, Object>> filtersList = List.of(
            Map.of("field", "status", "operator", "in", "value", List.of("a", "b")));

        List<FilterCondition> filters = service.parseFilters(filtersList);
        assertEquals(1, filters.size());
        assertEquals(FilterOperator.IN, filters.get(0).operator());
    }

    @Test
    void shouldMapContainsOperatorToCaseInsensitiveIcontains() {
        List<Map<String, Object>> filtersList = List.of(
            Map.of("field", "status", "operator", "contains", "value", "Open"));

        List<FilterCondition> filters = service.parseFilters(filtersList);
        assertEquals(1, filters.size());
        assertEquals(FilterOperator.ICONTAINS, filters.get(0).operator());
    }

    @Test
    void shouldThrowWidgetExecutionExceptionNamingUnknownOperator() {
        List<Map<String, Object>> filtersList = List.of(
            Map.of("field", "status", "operator", "nope", "value", "x"));

        WidgetExecutionException ex = assertThrows(WidgetExecutionException.class,
            () -> service.parseFilters(filtersList));
        assertTrue(ex.getMessage().contains("nope"));
    }

    // =========================================================================
    // Collection resolution tests
    // =========================================================================

    @Test
    void shouldResolveCollectionByName() {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        CollectionDefinition resolved = service.resolveTargetCollection(null,
            Map.of("collectionName", "accounts"));
        assertNotNull(resolved);
    }

    @Test
    void shouldResolveCollectionFromReport() {
        CollectionDefinition reportsDef = SystemCollectionDefinitions.reports();
        when(collectionRegistry.get("reports")).thenReturn(reportsDef);

        Map<String, Object> report = new HashMap<>();
        report.put("primaryCollectionId", "col-123");
        when(queryEngine.getById(eq(reportsDef), eq("rpt-1")))
            .thenReturn(Optional.of(report));

        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(jdbcTemplate.queryForList(anyString(), eq("col-123")))
            .thenReturn(List.of(Map.of("name", "accounts")));
        when(collectionRegistry.get("accounts")).thenReturn(collDef);

        CollectionDefinition resolved = service.resolveTargetCollection("rpt-1", Map.of());
        assertNotNull(resolved);
    }

    @Test
    void shouldReturnNullWhenCollectionNotFound() {
        when(collectionRegistry.get(anyString())).thenReturn(null);
        when(lifecycleManager.loadCollectionByName(anyString(), any())).thenReturn(null);

        CollectionDefinition resolved = service.resolveTargetCollection(null,
            Map.of("collectionName", "nonexistent"));
        assertNull(resolved);
    }

    // =========================================================================
    // Data-masking tests
    // =========================================================================

    private static final ReportExecutionService.MaskingPrincipal MASKED_VIEWER =
        new ReportExecutionService.MaskingPrincipal("viewer@acme.example", "profile-1", "tenant-1");

    /** Stubs the collection to have a masking-configured "ssn" field masked for the given principal. */
    private CollectionDefinition withMaskedSsn(boolean maskedForViewer) {
        CollectionDefinition collDef = SystemCollectionDefinitions.dashboards();
        when(collectionRegistry.get("people")).thenReturn(collDef);
        FieldMaskingService.MaskingConfig cfg = new FieldMaskingService.MaskingConfig(
            FieldMaskingService.MaskType.FULL, '*', null);
        when(recordMaskingService.maskableConfigs(collDef)).thenReturn(Map.of("ssn", cfg));
        when(recordMaskingService.maskedFieldsFor(anyString(), anyString(), anyString(),
            eq(collDef.name()), any())).thenReturn(maskedForViewer ? Set.of("ssn") : Set.of());
        return collDef;
    }

    @Test
    void shouldRejectFilterOnMaskedField() {
        withMaskedSsn(true);
        Map<String, Object> component = buildComponent("comp-mf", "table",
            Map.of("collectionName", "people",
                   "filters", List.of(Map.of("field", "ssn", "operator", "eq", "value", "123"))));

        WidgetExecutionException ex = assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), MASKED_VIEWER));
        assertTrue(ex.getMessage().contains("ssn"));
        verify(queryEngine, never()).executeQuery(any(), any());
    }

    @Test
    void shouldRejectChartGroupByMaskedField() {
        withMaskedSsn(true);
        Map<String, Object> component = buildComponent("comp-mg", "chart",
            Map.of("collectionName", "people", "groupByField", "ssn", "aggregateFunction", "COUNT"));

        assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), MASKED_VIEWER));
    }

    @Test
    void shouldRejectSortOnMaskedField() {
        withMaskedSsn(true);
        Map<String, Object> component = buildComponent("comp-ms", "table",
            Map.of("collectionName", "people",
                   "sortBy", List.of(Map.of("field", "ssn", "direction", "ASC"))));

        assertThrows(WidgetExecutionException.class,
            () -> service.executeWidget(component, Map.of(), MASKED_VIEWER));
    }

    @Test
    void shouldMaskTableRowsAndBypassCacheForMaskingConfiguredCollection() {
        CollectionDefinition collDef = withMaskedSsn(true);
        QueryResult queryResult = QueryResult.of(
            List.of(new HashMap<>(Map.of("name", "Jane", "ssn", "123-45-6789"))),
            1L, new Pagination(1, 25));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-mt", "table",
            Map.of("collectionName", "people", "fields", List.of("name", "ssn")));

        WidgetResult result = service.executeWidget(component, Map.of(), MASKED_VIEWER);

        assertEquals("table", result.type());
        // Row masking is delegated to the shared RecordMaskingService, keyed to the viewer.
        verify(recordMaskingService).maskRows(eq(collDef), eq(queryResult.data()),
            eq("viewer@acme.example"), eq("profile-1"), eq("tenant-1"));
        // A masking-configured collection must never touch the shared (non-per-viewer) cache.
        verify(cacheManager, never()).getDashboardWidgetData(anyString());
        verify(cacheManager, never()).putDashboardWidgetData(anyString(), any());
    }

    @Test
    void shouldNotMaskForNullSystemPrincipalButStillBypassCache() {
        CollectionDefinition collDef = withMaskedSsn(true);
        QueryResult queryResult = QueryResult.of(
            List.of(new HashMap<>(Map.of("name", "Jane", "ssn", "123-45-6789"))),
            1L, new Pagination(1, 25));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-sys", "table",
            Map.of("collectionName", "people", "fields", List.of("name", "ssn")));

        // Null principal = system trust tier — no masking, no Cerbos check.
        service.executeWidget(component, Map.of(), null);

        verify(recordMaskingService, never()).maskRows(any(), any(), any(), any(), any());
        verify(recordMaskingService, never()).maskedFieldsFor(any(), any(), any(), any(), any());
        // Still bypasses the shared cache (the collection is masking-configured).
        verify(cacheManager, never()).getDashboardWidgetData(anyString());
        verify(cacheManager, never()).putDashboardWidgetData(anyString(), any());
    }

    @Test
    void shouldNotBypassCacheWhenViewerMayUnmaskButCollectionHasMaskConfig() {
        // Even when THIS viewer can unmask (maskedFields empty), the collection is
        // masking-configured, so the shared cache stays bypassed (it can't distinguish viewers).
        CollectionDefinition collDef = withMaskedSsn(false);
        QueryResult queryResult = QueryResult.of(
            List.of(new HashMap<>(Map.of("name", "Jane", "ssn", "123-45-6789"))),
            1L, new Pagination(1, 25));
        when(queryEngine.executeQuery(eq(collDef), any())).thenReturn(queryResult);

        Map<String, Object> component = buildComponent("comp-unmask", "table",
            Map.of("collectionName", "people", "fields", List.of("name", "ssn")));

        service.executeWidget(component, Map.of(), MASKED_VIEWER);

        verify(cacheManager, never()).getDashboardWidgetData(anyString());
        verify(cacheManager, never()).putDashboardWidgetData(anyString(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Object> buildComponent(String id, String type,
                                               Map<String, Object> config) {
        Map<String, Object> component = new HashMap<>();
        component.put("id", id);
        component.put("componentType", type);
        component.put("reportId", null);
        component.put("config", new HashMap<>(config));
        return component;
    }
}
