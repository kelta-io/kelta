package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.formula.BuiltInFunctions;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.DefaultQueryEngine;
import io.kelta.runtime.registry.ConcurrentCollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.CustomValidationRuleEngine;
import io.kelta.runtime.validation.RecordValidationException;
import io.kelta.runtime.validation.ValidationError;
import io.kelta.runtime.validation.ValidationRuleEvaluator;
import io.kelta.runtime.validation.ValidationRuleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two tenants each own a collection named {@code orders}, each with its own validation rule. The
 * worker's {@link ValidationRuleRegistry} was keyed by the bare collection name, so whichever
 * tenant's {@code orders} was initialized last owned the rules of both: tenant B's writes were
 * validated against tenant A's rules, and tearing tenant A's {@code orders} down removed tenant
 * B's rules as well.
 *
 * <p>Wires the real collection registry, rule registry, rule engine and query engine to the
 * lifecycle manager (database reads mocked) and writes through {@link DefaultQueryEngine#create}
 * as each tenant, the same path a record POST takes.
 */
@DisplayName("Validation rules of same-named collections in two tenants")
class CollectionLifecycleManagerValidationRuleTenantScopingTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    /** Tenant A caps an order's total at 100. */
    private static final String A_RULE = "a_total_cap";
    /** Tenant B rejects negative totals. */
    private static final String B_RULE = "b_no_negative_total";

    private ConcurrentCollectionRegistry collectionRegistry;
    private JdbcTemplate jdbcTemplate;
    private CollectionLifecycleManager manager;
    private DefaultQueryEngine queryEngine;

    @BeforeEach
    void setUp() {
        collectionRegistry = new ConcurrentCollectionRegistry();
        ValidationRuleRegistry ruleRegistry = new ValidationRuleRegistry();
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString())).thenReturn(null);

        stubCollection("col-a", TENANT_A, "orders", false);
        stubCollection("col-b", TENANT_B, "orders", false);
        stubRules("col-a", TENANT_A, List.of(ruleRow(TENANT_A, A_RULE, "total > 100", "Tenant A caps orders at 100")));
        stubRules("col-b", TENANT_B, List.of(ruleRow(TENANT_B, B_RULE, "total < 0", "Tenant B rejects negative totals")));

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
    }

    @ParameterizedTest(name = "{0} initialized last")
    @ValueSource(strings = {"tenant A", "tenant B"})
    @DisplayName("each tenant's write to `orders` is checked against its own rule only")
    void eachTenantIsCheckedAgainstItsOwnRuleOnly(String lastInitialized) {
        if (lastInitialized.equals("tenant A")) {
            manager.initializeCollection("col-b", false);
            manager.initializeCollection("col-a", false);
        } else {
            manager.initializeCollection("col-a", false);
            manager.initializeCollection("col-b", false);
        }

        assertThatCode(() -> createOrder(TENANT_B, 500))
                .as("tenant A's cap does not apply to tenant B's order")
                .doesNotThrowAnyException();
        assertThat(rejectedRules(() -> createOrder(TENANT_B, -1)))
                .as("tenant B's own rule still applies to tenant B")
                .containsExactly(B_RULE);

        assertThatCode(() -> createOrder(TENANT_A, -1))
                .as("tenant B's rule does not apply to tenant A's order")
                .doesNotThrowAnyException();
        assertThat(rejectedRules(() -> createOrder(TENANT_A, 500)))
                .as("tenant A's own rule still applies to tenant A")
                .containsExactly(A_RULE);
    }

    @Test
    @DisplayName("tearing down tenant A's `orders` leaves tenant B's rule enforced")
    void teardownOfOneTenantLeavesTheOthersRules() {
        manager.initializeCollection("col-b", false);
        manager.initializeCollection("col-a", false);

        manager.teardownCollection("col-a");

        assertThat(rejectedRules(() -> createOrder(TENANT_B, -1)))
                .as("tenant B's rule survives tenant A's teardown")
                .containsExactly(B_RULE);
    }

    /**
     * A system collection is one definition every tenant writes through, but each rule on it
     * belongs to the tenant that wrote it and governs only that tenant's writes.
     */
    @Test
    @DisplayName("a tenant's rule on a system collection applies only to that tenant's writes")
    void systemCollectionRulesArePerWritingTenant() {
        stubCollection("col-users", SystemCollectionDefinitions.SYSTEM_TENANT_ID, "users", true);
        Map<String, Object> aUsersRule = ruleRow(TENANT_A, "a_no_blocked_email",
                "email = \"blocked@example.com\"", "Tenant A blocks this address");
        Map<String, Object> bUsersRule = ruleRow(TENANT_B, "b_no_test_email",
                "email = \"test@example.com\"", "Tenant B blocks this address");
        // Startup bootstrap reads unbound, so RLS shows every tenant's rows on the collection.
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users")))
                .thenReturn(List.of(aUsersRule, bUsersRule));
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users"), eq(TENANT_A)))
                .thenReturn(List.of(aUsersRule));
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq("col-users"), eq(TENANT_B)))
                .thenReturn(List.of(bUsersRule));

        manager.initializeCollection("col-users", false);

        assertThatCode(() -> createUser(TENANT_B, "blocked@example.com"))
                .as("tenant A's rule on `users` does not apply to tenant B")
                .doesNotThrowAnyException();
        assertThat(rejectedRules(() -> createUser(TENANT_A, "blocked@example.com"))).containsExactly("a_no_blocked_email");
        assertThat(rejectedRules(() -> createUser(TENANT_B, "test@example.com"))).containsExactly("b_no_test_email");

        // A refresh for tenant B's rule change runs bound to tenant B and sees only B's rows: it
        // must replace B's set without dropping tenant A's.
        TenantContext.runWithTenant(TENANT_B, () -> manager.refreshCollection("col-users"));

        assertThat(rejectedRules(() -> createUser(TENANT_A, "blocked@example.com"))).containsExactly("a_no_blocked_email");
        assertThatCode(() -> createUser(TENANT_A, "test@example.com")).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ helpers

    private void createOrder(String tenantId, double total) {
        TenantContext.runWithTenant(tenantId, () -> {
            CollectionDefinition orders = collectionRegistry.get("orders");
            assertThat(orders.tenantId()).as("the registry resolves the caller's own `orders`").isEqualTo(tenantId);
            queryEngine.create(orders, new HashMap<>(Map.of("name", "order", "total", total)));
        });
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

    /**
     * The collection's rule rows, whether the worker reads them by collection alone (the
     * pre-fix query) or by collection and owning tenant.
     */
    private void stubRules(String collectionId, String tenantId, List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq(collectionId))).thenReturn(rows);
        when(jdbcTemplate.queryForList(contains("FROM validation_rule"), eq(collectionId), eq(tenantId)))
                .thenReturn(rows);
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
