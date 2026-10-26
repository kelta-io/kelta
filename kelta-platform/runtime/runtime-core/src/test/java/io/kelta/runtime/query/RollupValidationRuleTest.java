package io.kelta.runtime.query;

import io.kelta.runtime.formula.BuiltInFunctions;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.service.RollupSummaryService;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.CustomValidationRuleEngine;
import io.kelta.runtime.validation.RecordValidationException;
import io.kelta.runtime.validation.ValidationError;
import io.kelta.runtime.validation.ValidationRuleDefinition;
import io.kelta.runtime.validation.ValidationRuleEvaluator;
import io.kelta.runtime.validation.ValidationRuleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Custom validation rules that reference a ROLLUP_SUMMARY field see the value computed
 * at write time from the child rows, so a rule can test related records. The child table
 * lives in an in-memory database and the rollup runs the real aggregate query.
 */
class RollupValidationRuleTest {

    private static final String RULE_MESSAGE = "Close or cancel the open tasks first";
    private static final String CLOSE_RULE = "AND(state = \"closed\", openTasks > 0)";
    private static final String PROJECT_ID = "p1";
    private static final String TENANT = "tenant-1";

    private JdbcTemplate jdbcTemplate;
    private StorageAdapter storageAdapter;
    private RollupSummaryService rollupSummaryService;
    private ValidationRuleRegistry ruleRegistry;
    private DefaultQueryEngine engine;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:rollupvalidation" + UUID.randomUUID().toString().replace("-", "")
                        + ";DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        jdbcTemplate.execute("CREATE TABLE tasks (id VARCHAR(36) PRIMARY KEY, "
                + "project_id VARCHAR(36), status VARCHAR(20), hours DOUBLE PRECISION)");

        storageAdapter = mock(StorageAdapter.class);
        rollupSummaryService = spy(new RollupSummaryService(jdbcTemplate));
        CollectionRegistry collectionRegistry = mock(CollectionRegistry.class);
        when(collectionRegistry.get("tasks")).thenReturn(tasks());

        ruleRegistry = new ValidationRuleRegistry();
        CustomValidationRuleEngine ruleEngine = new CustomValidationRuleEngine(ruleRegistry,
                new ValidationRuleEvaluator(new FormulaEvaluator(List.of(
                        new BuiltInFunctions.And(), new BuiltInFunctions.Or()))));

        engine = new DefaultQueryEngine(storageAdapter, null, null, null, null,
                rollupSummaryService, ruleEngine, null, null, collectionRegistry);

        Map<String, Object> stored = new HashMap<>(Map.of("id", PROJECT_ID, "state", "active"));
        when(storageAdapter.getById(any(), eq(PROJECT_ID))).thenReturn(Optional.of(stored));
        when(storageAdapter.update(any(), eq(PROJECT_ID), any()))
                .thenAnswer(invocation -> Optional.of(new HashMap<>(invocation.<Map<String, Object>>getArgument(2))));
        when(storageAdapter.create(any(), any()))
                .thenAnswer(invocation -> new HashMap<>(invocation.<Map<String, Object>>getArgument(1)));
    }

    private static CollectionDefinition tasks() {
        return new CollectionDefinitionBuilder()
                .name("tasks")
                .displayName("Tasks")
                .addField(new FieldDefinitionBuilder().name("projectId").type(FieldType.STRING).build())
                .addField(new FieldDefinitionBuilder().name("status").type(FieldType.STRING).build())
                .addField(new FieldDefinitionBuilder().name("hours").type(FieldType.DOUBLE).build())
                .build();
    }

    private static CollectionDefinition projects() {
        return new CollectionDefinitionBuilder()
                .name("projects")
                .displayName("Projects")
                .tenantId(TENANT)
                .addField(new FieldDefinitionBuilder().name("state").type(FieldType.STRING).build())
                .addField(new FieldDefinitionBuilder()
                        .name("openTasks")
                        .type(FieldType.ROLLUP_SUMMARY)
                        .fieldTypeConfig(Map.of(
                                "childCollection", "tasks",
                                "foreignKeyField", "projectId",
                                "aggregateFunction", "COUNT",
                                "filter", Map.of("status", "open")))
                        .build())
                .addField(new FieldDefinitionBuilder()
                        .name("totalHours")
                        .type(FieldType.ROLLUP_SUMMARY)
                        .fieldTypeConfig(Map.of(
                                "childCollection", "tasks",
                                "foreignKeyField", "projectId",
                                "aggregateFunction", "SUM",
                                "aggregateField", "hours"))
                        .build())
                .addField(new FieldDefinitionBuilder()
                        .name("taskCount")
                        .type(FieldType.ROLLUP_SUMMARY)
                        .fieldTypeConfig(Map.of(
                                "childCollection", "tasks",
                                "foreignKeyField", "projectId",
                                "aggregateFunction", "COUNT"))
                        .build())
                .build();
    }

    private void registerRules(String... formulas) {
        List<ValidationRuleDefinition> rules = new ArrayList<>();
        for (int i = 0; i < formulas.length; i++) {
            rules.add(new ValidationRuleDefinition("rule_" + i, formulas[i], RULE_MESSAGE,
                    "state", "CREATE_AND_UPDATE", true));
        }
        ruleRegistry.register(TENANT, "projects", rules);
    }

    private void insertTask(String status, double hours) {
        jdbcTemplate.update("INSERT INTO tasks (id, project_id, status, hours) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), PROJECT_ID, status, hours);
    }

    private Map<String, Object> patch(Map<String, Object> values) {
        return new HashMap<>(values);
    }

    @Test
    @DisplayName("Closing a parent with one child matching the rollup filter is rejected with the rule's message and field")
    void rejectsUpdateWhenAMatchingChildExists() {
        registerRules(CLOSE_RULE);
        insertTask("open", 1.0);

        RecordValidationException ex = assertThrows(RecordValidationException.class,
                () -> engine.update(projects(), PROJECT_ID, patch(Map.of("state", "closed"))));

        assertEquals(List.of(new ValidationError("rule_0", RULE_MESSAGE, "state")), ex.getErrors());
        verify(storageAdapter, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("Closing a parent with no children is allowed")
    void allowsUpdateWithNoChildren() {
        registerRules(CLOSE_RULE);

        assertDoesNotThrow(() -> engine.update(projects(), PROJECT_ID, patch(Map.of("state", "closed"))));

        verify(storageAdapter).update(any(), eq(PROJECT_ID), any());
    }

    @Test
    @DisplayName("Closing a parent whose children do not match the rollup filter is allowed")
    void allowsUpdateWhenNoChildMatchesTheFilter() {
        registerRules(CLOSE_RULE);
        insertTask("done", 1.0);
        insertTask("cancelled", 2.0);
        jdbcTemplate.update("INSERT INTO tasks (id, project_id, status, hours) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), "another-project", "open", 3.0);

        assertDoesNotThrow(() -> engine.update(projects(), PROJECT_ID, patch(Map.of("state", "closed"))));

        verify(storageAdapter).update(any(), eq(PROJECT_ID), any());
    }

    @Test
    @DisplayName("A client-supplied rollup value in the PATCH body cannot bypass the rule")
    void clientSuppliedRollupValueIsOverridden() {
        registerRules(CLOSE_RULE);
        insertTask("open", 1.0);

        assertThrows(RecordValidationException.class,
                () -> engine.update(projects(), PROJECT_ID, patch(Map.of("state", "closed", "openTasks", 0))));

        verify(storageAdapter, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("Each referenced rollup costs one aggregate query per evaluation, regardless of child count")
    void computesEachReferencedRollupOnce() {
        registerRules(CLOSE_RULE, "totalHours > 100");
        insertTask("done", 1.0);
        insertTask("done", 2.0);
        insertTask("cancelled", 3.0);
        insertTask("done", 4.0);

        engine.update(projects(), PROJECT_ID, patch(Map.of("state", "closed")));

        verify(rollupSummaryService, times(1)).compute(any(), eq("project_id"), eq(PROJECT_ID),
                eq("COUNT"), any(), eq(Map.of("status", "open")));
        verify(rollupSummaryService, times(1)).compute(any(), eq("project_id"), eq(PROJECT_ID),
                eq("SUM"), eq("hours"), any());
        // taskCount is a rollup no rule reads
        verify(rollupSummaryService, times(2)).compute(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Rules that reference no rollup field cost zero rollup queries")
    void noRollupQueriesWhenRulesReferenceNoRollup() {
        registerRules("AND(state = \"closed\", owner = \"nobody\")");
        insertTask("open", 1.0);

        engine.update(projects(), PROJECT_ID, patch(Map.of("state", "closed")));
        engine.create(projects(), patch(Map.of("state", "closed")));

        verify(rollupSummaryService, never()).compute(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("On create the rollup is computed for the new record, overriding a client-supplied value")
    void createSeesComputedRollup() {
        registerRules("openTasks > 0");

        assertDoesNotThrow(() -> engine.create(projects(), patch(Map.of("state", "active", "openTasks", 5))));

        verify(rollupSummaryService, times(1)).compute(any(), any(), any(), any(), any(), any());
        verify(storageAdapter).create(any(), any());
    }
}
