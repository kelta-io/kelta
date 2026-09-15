package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardComponentValidatorTest {

    private CollectionRegistry collectionRegistry;
    private CollectionLifecycleManager lifecycleManager;
    private QueryEngine queryEngine;
    private DashboardComponentValidator validator;

    private CollectionDefinition accountsDef;

    @BeforeEach
    void setUp() {
        collectionRegistry = mock(CollectionRegistry.class);
        lifecycleManager = mock(CollectionLifecycleManager.class);
        queryEngine = mock(QueryEngine.class);
        validator = new DashboardComponentValidator(collectionRegistry, lifecycleManager, queryEngine);

        accountsDef = CollectionDefinition.builder()
            .name("accounts")
            .displayName("Accounts")
            .addField(FieldDefinition.string("name"))
            .addField(FieldDefinition.doubleField("amount"))
            .addField(FieldDefinition.date("closedAt"))
            .addField(new FieldDefinition("revenueRollup", FieldType.ROLLUP_SUMMARY,
                true, false, false, null, null, null, null, null))
            .addField(new FieldDefinition("fullName", FieldType.FORMULA,
                true, false, false, null, null, null, null, null))
            .build();
        when(collectionRegistry.get("accounts")).thenReturn(accountsDef);
    }

    private Map<String, Object> component(String componentType, Map<String, Object> config) {
        Map<String, Object> c = new HashMap<>();
        c.put("id", "comp-1");
        c.put("componentType", componentType);
        c.put("config", new HashMap<>(config));
        c.put("columnPosition", 1);
        c.put("columnSpan", 1);
        return c;
    }

    // =========================================================================
    // collectionName
    // =========================================================================

    @Test
    void rejectsUnknownCollectionName() {
        when(collectionRegistry.get("nope")).thenReturn(null);
        when(lifecycleManager.loadCollectionByName(eq("nope"), any())).thenReturn(null);

        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("metric", Map.of("collectionName", "nope")));

        assertEquals(1, errors.size());
        assertEquals("config/collectionName", errors.get(0).field());
    }

    @Test
    void acceptsKnownCollectionNameWithNoOtherConfig() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("metric", Map.of("collectionName", "accounts")));

        assertTrue(errors.isEmpty());
    }

    // =========================================================================
    // groupByField / aggregateField: rollup + formula rejected
    // =========================================================================

    @Test
    void rejectsRollupSummaryAsGroupByField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("chart", Map.of("collectionName", "accounts", "groupByField", "revenueRollup")));

        assertEquals(1, errors.size());
        assertEquals("config/groupByField", errors.get(0).field());
    }

    @Test
    void rejectsFormulaAsAggregateField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("metric", Map.of("collectionName", "accounts", "aggregateField", "fullName")));

        assertEquals(1, errors.size());
        assertEquals("config/aggregateField", errors.get(0).field());
    }

    @Test
    void acceptsPlainFieldAsGroupByField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("chart", Map.of("collectionName", "accounts", "groupByField", "name")));

        assertTrue(errors.isEmpty());
    }

    @Test
    void rejectsUnknownGroupByField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("chart", Map.of("collectionName", "accounts", "groupByField", "missingField")));

        assertEquals(1, errors.size());
        assertEquals("config/groupByField", errors.get(0).field());
        assertTrue(errors.get(0).message().contains("missingField"));
        assertTrue(errors.get(0).message().contains("accounts"));
    }

    // =========================================================================
    // timeField: must be DATE/DATETIME
    // =========================================================================

    @Test
    void rejectsNonDateTimeField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("table", Map.of("collectionName", "accounts", "timeField", "name")));

        assertEquals(1, errors.size());
        assertEquals("config/timeField", errors.get(0).field());
    }

    @Test
    void acceptsDateFieldAsTimeField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("table", Map.of("collectionName", "accounts", "timeField", "closedAt")));

        assertTrue(errors.isEmpty());
    }

    // =========================================================================
    // fields list (table/recent)
    // =========================================================================

    @Test
    void rejectsUnknownFieldInFieldsList() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("table", Map.of("collectionName", "accounts", "fields", List.of("name", "ghost"))));

        assertEquals(1, errors.size());
        assertEquals("config/fields/1", errors.get(0).field());
    }

    // =========================================================================
    // filters
    // =========================================================================

    @Test
    void rejectsUnknownFilterOperator() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("table", Map.of("collectionName", "accounts",
                "filters", List.of(Map.of("field", "name", "operator", "nope", "value", "x")))));

        assertEquals(1, errors.size());
        assertEquals("config/filters/0/operator", errors.get(0).field());
    }

    @Test
    void rejectsUnknownFilterField() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("table", Map.of("collectionName", "accounts",
                "filters", List.of(Map.of("field", "ghost", "operator", "eq", "value", "x")))));

        assertEquals(1, errors.size());
        assertEquals("config/filters/0/field", errors.get(0).field());
    }

    @Test
    void acceptsContainsAliasOperator() {
        List<BeforeSaveResult.ValidationError> errors = validator.validate(
            component("table", Map.of("collectionName", "accounts",
                "filters", List.of(Map.of("field", "name", "operator", "contains", "value", "x")))));

        assertTrue(errors.isEmpty());
    }

    // =========================================================================
    // componentType
    // =========================================================================

    @Test
    void rejectsUnknownComponentType() {
        Map<String, Object> c = component("pie-chart-3000", Map.of("collectionName", "accounts"));

        List<BeforeSaveResult.ValidationError> errors = validator.validate(c);

        assertEquals(1, errors.size());
        assertEquals("componentType", errors.get(0).field());
    }

    // =========================================================================
    // Grid position
    // =========================================================================

    @Test
    void rejectsColumnPositionZero() {
        Map<String, Object> c = component("metric", Map.of("collectionName", "accounts"));
        c.put("columnPosition", 0);

        List<BeforeSaveResult.ValidationError> errors = validator.validate(c);

        assertEquals(1, errors.size());
        assertEquals("columnPosition", errors.get(0).field());
    }

    @Test
    void rejectsColumnPositionBeyondDashboardColumnCount() {
        CollectionDefinition dashboardsDef = CollectionDefinition.builder()
            .name("dashboards")
            .displayName("Dashboards")
            .addField(FieldDefinition.integer("columnCount"))
            .build();
        when(collectionRegistry.get("dashboards")).thenReturn(dashboardsDef);
        when(queryEngine.getById(eq(dashboardsDef), eq("dash-1")))
            .thenReturn(Optional.of(Map.of("columnCount", 4)));

        Map<String, Object> c = component("metric", Map.of("collectionName", "accounts"));
        c.put("dashboardId", "dash-1");
        c.put("columnPosition", 4);
        c.put("columnSpan", 2);

        List<BeforeSaveResult.ValidationError> errors = validator.validate(c);

        assertEquals(1, errors.size());
        assertEquals("columnPosition", errors.get(0).field());
    }

    @Test
    void acceptsColumnPositionWithinDashboardColumnCount() {
        CollectionDefinition dashboardsDef = CollectionDefinition.builder()
            .name("dashboards")
            .displayName("Dashboards")
            .addField(FieldDefinition.integer("columnCount"))
            .build();
        when(collectionRegistry.get("dashboards")).thenReturn(dashboardsDef);
        when(queryEngine.getById(eq(dashboardsDef), eq("dash-1")))
            .thenReturn(Optional.of(Map.of("columnCount", 4)));

        Map<String, Object> c = component("metric", Map.of("collectionName", "accounts"));
        c.put("dashboardId", "dash-1");
        c.put("columnPosition", 3);
        c.put("columnSpan", 2);

        List<BeforeSaveResult.ValidationError> errors = validator.validate(c);

        assertTrue(errors.isEmpty());
    }

    // =========================================================================
    // reportId-only widgets: no collection to validate, no false positives
    // =========================================================================

    @Test
    void doesNotRequireCollectionNameWhenNotProvided() {
        Map<String, Object> c = component("metric", Map.of());
        c.put("reportId", "rpt-1");

        List<BeforeSaveResult.ValidationError> errors = validator.validate(c);

        assertTrue(errors.isEmpty());
    }
}
