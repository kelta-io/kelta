package io.kelta.worker.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.formula.BuiltInFunctions;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.registry.ConcurrentCollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.CustomValidationRuleEngine;
import io.kelta.runtime.validation.RecordValidationException;
import io.kelta.runtime.validation.ValidationError;
import io.kelta.runtime.validation.ValidationRuleDefinition;
import io.kelta.runtime.validation.ValidationRuleEvaluator;
import io.kelta.runtime.validation.ValidationRuleRegistry;
import io.kelta.runtime.validation.ValidationRuleRegistry.RulesUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A database error while loading validation rules used to register an empty rule set, silently
 * switching validation off until the next reload; and a system collection first loaded inside
 * one tenant's request registered that tenant's rules only. Wires the real registries, rule
 * engine and query engine to the lifecycle manager with the database reads mocked.
 */
@DisplayName("Validation rule loads fail closed and a system collection's first load reads every tenant")
class CollectionLifecycleManagerValidationRuleLoadFailureTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    private ConcurrentCollectionRegistry collectionRegistry;
    private ValidationRuleRegistry ruleRegistry;
    private JdbcTemplate jdbcTemplate;
    private CollectionLifecycleManager manager;
    private DefaultQueryEngine queryEngine;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        collectionRegistry = new ConcurrentCollectionRegistry();
        ruleRegistry = new ValidationRuleRegistry();
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString())).thenReturn(null);

        StorageAdapter storageAdapter = mock(StorageAdapter.class);
        when(storageAdapter.create(any(), any()))
                .thenAnswer(invocation -> new HashMap<>(invocation.<Map<String, Object>>getArgument(1)));

        manager = new CollectionLifecycleManager(collectionRegistry, storageAdapter, jdbcTemplate,
                new ObjectMapper());
        manager.setValidationRuleRegistry(ruleRegistry);

        CustomValidationRuleEngine ruleEngine = new CustomValidationRuleEngine(ruleRegistry,
                new ValidationRuleEvaluator(new FormulaEvaluator(List.of(
                        new BuiltInFunctions.And(), new BuiltInFunctions.Or()))));
        queryEngine = new DefaultQueryEngine(storageAdapter, null, null, null, null, null,
                ruleEngine, null, null, collectionRegistry);

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(CollectionLifecycleManager.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(CollectionLifecycleManager.class)).detachAppender(logs);
    }

    // ------------------------------------------------------------------ tenant-owned collection

    @Test
    @DisplayName("a failed refresh of a tenant collection keeps its previously loaded rules")
    void failedTenantRefreshKeepsPreviousRules() {
        stubCollection("col-a", TENANT_A, "orders", false);
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-a"), eq(TENANT_A)))
                .thenReturn(List.of(ruleRow(TENANT_A, "cap", "total > 100", "Orders are capped at 100")))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        manager.initializeCollection("col-a", false);
        TenantContext.runWithTenant(TENANT_A, () -> manager.refreshCollection("col-a"));

        assertThat(ruleRegistry.getActiveRules(TENANT_A, "orders"))
                .extracting(ValidationRuleDefinition::name)
                .containsExactly("cap");
        assertThat(rejectedRules(() -> createOrder(TENANT_A, 500))).containsExactly("cap");
        assertThat(errorLog()).contains("orders", TENANT_A, "keeping the previously loaded rules");
    }

    @Test
    @DisplayName("a tenant collection whose rules never loaded refuses writes until they load")
    void neverLoadedTenantCollectionRefusesWrites() {
        stubCollection("col-a", TENANT_A, "orders", false);
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-a"), eq(TENANT_A)))
                .thenThrow(new DataAccessResourceFailureException("connection reset"))
                .thenReturn(List.of(ruleRow(TENANT_A, "cap", "total > 100", "Orders are capped at 100")));

        manager.initializeCollection("col-a", false);

        RulesUnavailableException refused =
                catchThrowableOfType(RulesUnavailableException.class, () -> createOrder(TENANT_A, 5));
        assertThat(refused).as("the write is refused, not saved unvalidated").isNotNull();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getReason()).contains("orders");
        assertThat(errorLog()).contains("orders", TENANT_A, "writes to it are refused");

        TenantContext.runWithTenant(TENANT_A, () -> manager.refreshCollection("col-a"));

        assertThatCode(() -> createOrder(TENANT_A, 5)).doesNotThrowAnyException();
        assertThat(rejectedRules(() -> createOrder(TENANT_A, 500))).containsExactly("cap");
    }

    @Test
    @DisplayName("a tenant collection loaded while another tenant is bound still registers its owner's rules")
    void tenantCollectionLoadedUnderAnotherTenantRegistersOwnersRules() {
        stubCollection("col-a", TENANT_A, "orders", false);
        Map<String, Object> aRule = ruleRow(TENANT_A, "cap", "total > 100", "Orders are capped at 100");
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-a"), eq(TENANT_A)))
                .thenAnswer(invocation -> rowsVisibleTo(TenantContext.get(), aRule));

        TenantContext.runWithTenant(TENANT_B, () -> manager.initializeCollection("col-a", false));

        assertThat(ruleRegistry.getActiveRules(TENANT_A, "orders"))
                .extracting(ValidationRuleDefinition::name).containsExactly("cap");
    }

    // ------------------------------------------------------------------ system collection

    @Test
    @DisplayName("a failed reload of a system collection keeps every tenant's previously loaded rules")
    void failedSystemReloadKeepsEveryTenantsRules() {
        stubCollection("col-users", SystemCollectionDefinitions.SYSTEM_TENANT_ID, "users", true);
        Map<String, Object> aRule = ruleRow(TENANT_A, "a_rule", "email = \"a@example.com\"", "A blocks it");
        Map<String, Object> bRule = ruleRow(TENANT_B, "b_rule", "email = \"b@example.com\"", "B blocks it");
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users")))
                .thenReturn(List.of(aRule, bRule))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users"), eq(TENANT_B)))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        manager.initializeCollection("col-users", false);
        // A tenant-bound refresh (B's rule changed) and an unbound reload both fail.
        TenantContext.runWithTenant(TENANT_B, () -> manager.refreshCollection("col-users"));
        manager.refreshCollection("col-users");

        assertThat(ruleRegistry.getActiveSystemCollectionRules(TENANT_A, "users"))
                .extracting(ValidationRuleDefinition::name).containsExactly("a_rule");
        assertThat(ruleRegistry.getActiveSystemCollectionRules(TENANT_B, "users"))
                .extracting(ValidationRuleDefinition::name).containsExactly("b_rule");
        assertThat(rejectedRules(() -> createUser(TENANT_B, "b@example.com"))).containsExactly("b_rule");
        assertThat(errorLog()).contains("users", TENANT_B, "keeping the previously loaded rules");
    }

    @Test
    @DisplayName("a system collection whose rules never loaded refuses every tenant's writes")
    void neverLoadedSystemCollectionRefusesWrites() {
        stubCollection("col-users", SystemCollectionDefinitions.SYSTEM_TENANT_ID, "users", true);
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users")))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        manager.initializeCollection("col-users", false);

        assertThatThrownBy(() -> createUser(TENANT_A, "a@example.com"))
                .isInstanceOf(RulesUnavailableException.class);
        assertThatThrownBy(() -> createUser(TENANT_B, "b@example.com"))
                .isInstanceOf(RulesUnavailableException.class);
        assertThat(errorLog()).contains("users", "writes to it are refused");
    }

    @Test
    @DisplayName("a system collection first loaded inside tenant A's request registers tenant B's rules too")
    void firstSystemLoadUnderBoundTenantReadsEveryTenant() {
        stubCollection("col-users", SystemCollectionDefinitions.SYSTEM_TENANT_ID, "users", true);
        Map<String, Object> aRule = ruleRow(TENANT_A, "a_rule", "email = \"a@example.com\"", "A blocks it");
        Map<String, Object> bRule = ruleRow(TENANT_B, "b_rule", "email = \"b@example.com\"", "B blocks it");
        // RLS: a connection bound to a tenant sees only that tenant's rows; only an unbound one
        // (admin_bypass) sees every tenant's.
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users")))
                .thenAnswer(invocation -> rowsVisibleTo(TenantContext.get(), aRule, bRule));
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users"), anyString()))
                .thenAnswer(invocation -> rowsVisibleTo(TenantContext.get(), aRule, bRule));

        TenantContext.runWithTenant(TENANT_A, () -> manager.initializeCollection("col-users", false));

        assertThat(ruleRegistry.getActiveSystemCollectionRules(TENANT_B, "users"))
                .extracting(ValidationRuleDefinition::name).containsExactly("b_rule");
        assertThat(ruleRegistry.getActiveSystemCollectionRules(TENANT_A, "users"))
                .extracting(ValidationRuleDefinition::name).containsExactly("a_rule");
        assertThat(rejectedRules(() -> createUser(TENANT_B, "b@example.com"))).containsExactly("b_rule");
    }

    // ------------------------------------------------------------------ helpers

    @SafeVarargs
    private static List<Map<String, Object>> rowsVisibleTo(String boundTenant, Map<String, Object>... rows) {
        if (boundTenant == null) {
            return List.of(rows);
        }
        return Arrays.stream(rows).filter(row -> boundTenant.equals(row.get("tenant_id"))).toList();
    }

    private String errorLog() {
        return String.join("\n", logs.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList());
    }

    private void createOrder(String tenantId, double total) {
        TenantContext.runWithTenant(tenantId, () -> queryEngine.create(collectionRegistry.get("orders"),
                new HashMap<>(Map.of("name", "order", "total", total))));
    }

    private void createUser(String tenantId, String email) {
        TenantContext.runWithTenant(tenantId, () -> queryEngine.create(collectionRegistry.get("users"),
                new HashMap<>(Map.of("email", email, "userType", "INTERNAL"))));
    }

    private static List<String> rejectedRules(Runnable write) {
        RecordValidationException ex = catchThrowableOfType(RecordValidationException.class, write::run);
        assertThat(ex).as("the write is rejected by a validation rule").isNotNull();
        return ex.getErrors().stream().map(ValidationError::ruleName).toList();
    }

    private void stubCollection(String id, String tenantId, String name, boolean system) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("active", true);
        row.put("system_collection", system);
        row.put("tenant_id", tenantId);
        when(jdbcTemplate.queryForList(contains("FROM collection WHERE id"), eq(id))).thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(contains("FROM field WHERE"), eq(id)))
                .thenReturn(List.of(fieldRow("name", "string", 0), fieldRow("total", "number", 1)));
    }

    private static Map<String, Object> ruleRow(String tenantId, String name, String formula, String message) {
        Map<String, Object> row = new HashMap<>();
        row.put("tenant_id", tenantId);
        row.put("name", name);
        row.put("error_condition_formula", formula);
        row.put("error_message", message);
        row.put("error_field", null);
        row.put("evaluate_on", "CREATE_AND_UPDATE");
        row.put("active", true);
        row.put("severity", "ERROR");
        return row;
    }

    private static Map<String, Object> fieldRow(String name, String type, int order) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", name);
        row.put("type", type);
        row.put("required", false);
        row.put("unique_constraint", false);
        row.put("indexed", false);
        row.put("cascade_delete", false);
        row.put("field_order", order);
        row.put("immutable", false);
        row.put("searchable", false);
        return row;
    }
}
