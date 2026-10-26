package io.kelta.runtime.validation;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CustomValidationRuleEngine.
 */
class CustomValidationRuleEngineTest {

    private static final String TENANT = "tenant-1";
    private static final CollectionDefinition PRODUCTS = tenantCollection(TENANT, "products");
    private static final CollectionDefinition PROJECTS = tenantCollection(TENANT, "projects");

    static CollectionDefinition tenantCollection(String tenantId, String name) {
        return new CollectionDefinitionBuilder()
                .name(name)
                .addField(FieldDefinition.string("name"))
                .tenantId(tenantId)
                .build();
    }

    private ValidationRuleRegistry ruleRegistry;
    private ValidationRuleEvaluator ruleEvaluator;
    private CustomValidationRuleEngine engine;

    @BeforeEach
    void setUp() {
        ruleRegistry = new ValidationRuleRegistry();
        ruleEvaluator = mock(ValidationRuleEvaluator.class);
        engine = new CustomValidationRuleEngine(ruleRegistry, ruleEvaluator);
    }

    @Nested
    @DisplayName("Evaluate with no rules")
    class NoRulesTests {

        @Test
        @DisplayName("Should not throw when no rules registered")
        void shouldNotThrowWhenNoRules() {
            Map<String, Object> data = Map.of("name", "test");
            assertDoesNotThrow(() ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));
        }

        @Test
        @DisplayName("Should not call evaluator when no rules registered")
        void shouldNotCallEvaluatorWhenNoRules() {
            Map<String, Object> data = Map.of("name", "test");
            engine.evaluate(PRODUCTS, data, OperationType.CREATE);
            verifyNoInteractions(ruleEvaluator);
        }
    }

    @Nested
    @DisplayName("Evaluate with passing rules")
    class PassingRulesTests {

        @Test
        @DisplayName("Should not throw when all rules pass")
        void shouldNotThrowWhenAllRulesPass() {
            var rule = new ValidationRuleDefinition(
                    "positive_price", "price < 0", "Price must be positive",
                    "price", "CREATE_AND_UPDATE", true);
            ruleRegistry.register(TENANT, "products", List.of(rule));

            Map<String, Object> data = Map.of("price", 10.0);
            when(ruleEvaluator.isInvalid("price < 0", data)).thenReturn(false);

            assertDoesNotThrow(() ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));
        }
    }

    @Nested
    @DisplayName("Evaluate with failing rules")
    class FailingRulesTests {

        @Test
        @DisplayName("Should throw RecordValidationException when rule fails")
        void shouldThrowWhenRuleFails() {
            var rule = new ValidationRuleDefinition(
                    "positive_price", "price < 0", "Price must be positive",
                    "price", "CREATE_AND_UPDATE", true);
            ruleRegistry.register(TENANT, "products", List.of(rule));

            Map<String, Object> data = Map.of("price", -5.0);
            when(ruleEvaluator.isInvalid("price < 0", data)).thenReturn(true);

            RecordValidationException ex = assertThrows(RecordValidationException.class, () ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));

            assertEquals(1, ex.getErrors().size());
            assertEquals("positive_price", ex.getErrors().get(0).ruleName());
            assertEquals("Price must be positive", ex.getErrors().get(0).errorMessage());
            assertEquals("price", ex.getErrors().get(0).errorField());
        }

        @Test
        @DisplayName("Should collect multiple rule violations")
        void shouldCollectMultipleViolations() {
            var rule1 = new ValidationRuleDefinition(
                    "positive_price", "price < 0", "Price must be positive",
                    "price", "CREATE_AND_UPDATE", true);
            var rule2 = new ValidationRuleDefinition(
                    "name_required", "name == null", "Name is required",
                    "name", "CREATE_AND_UPDATE", true);
            ruleRegistry.register(TENANT, "products", List.of(rule1, rule2));

            Map<String, Object> data = Map.of("price", -5.0);
            when(ruleEvaluator.isInvalid("price < 0", data)).thenReturn(true);
            when(ruleEvaluator.isInvalid("name == null", data)).thenReturn(true);

            RecordValidationException ex = assertThrows(RecordValidationException.class, () ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));

            assertEquals(2, ex.getErrors().size());
        }
    }

    @Nested
    @DisplayName("Operation type filtering")
    class OperationTypeFilteringTests {

        @Test
        @DisplayName("Should skip rules not applicable to CREATE")
        void shouldSkipRulesNotApplicableToCreate() {
            var updateOnlyRule = new ValidationRuleDefinition(
                    "update_check", "x > 0", "Error", null, "UPDATE", true);
            ruleRegistry.register(TENANT, "products", List.of(updateOnlyRule));

            Map<String, Object> data = Map.of("x", 100);
            engine.evaluate(PRODUCTS, data, OperationType.CREATE);

            verifyNoInteractions(ruleEvaluator);
        }

        @Test
        @DisplayName("Should skip rules not applicable to UPDATE")
        void shouldSkipRulesNotApplicableToUpdate() {
            var createOnlyRule = new ValidationRuleDefinition(
                    "create_check", "x > 0", "Error", null, "CREATE", true);
            ruleRegistry.register(TENANT, "products", List.of(createOnlyRule));

            Map<String, Object> data = Map.of("x", 100);
            engine.evaluate(PRODUCTS, data, OperationType.UPDATE);

            verifyNoInteractions(ruleEvaluator);
        }

        @Test
        @DisplayName("Should evaluate CREATE_AND_UPDATE rules for both operations")
        void shouldEvaluateCreateAndUpdateRulesForBoth() {
            var rule = new ValidationRuleDefinition(
                    "always_check", "x > 100", "Too high", "x",
                    "CREATE_AND_UPDATE", true);
            ruleRegistry.register(TENANT, "products", List.of(rule));

            Map<String, Object> data = Map.of("x", 50);
            when(ruleEvaluator.isInvalid("x > 100", data)).thenReturn(false);

            engine.evaluate(PRODUCTS, data, OperationType.CREATE);
            engine.evaluate(PRODUCTS, data, OperationType.UPDATE);

            verify(ruleEvaluator, times(2)).isInvalid("x > 100", data);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should not throw when evaluator throws exception")
        void shouldNotThrowWhenEvaluatorThrows() {
            var rule = new ValidationRuleDefinition(
                    "broken_rule", "invalid formula", "Error", null,
                    "CREATE_AND_UPDATE", true);
            ruleRegistry.register(TENANT, "products", List.of(rule));

            Map<String, Object> data = Map.of("x", 10);
            when(ruleEvaluator.isInvalid("invalid formula", data))
                    .thenThrow(new RuntimeException("Parse error"));

            assertDoesNotThrow(() ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));
        }

        @Test
        @DisplayName("Should skip inactive rules")
        void shouldSkipInactiveRules() {
            var inactiveRule = new ValidationRuleDefinition(
                    "disabled_rule", "x > 0", "Error", null,
                    "CREATE_AND_UPDATE", false);
            ruleRegistry.register(TENANT, "products", List.of(inactiveRule));

            Map<String, Object> data = Map.of("x", 100);
            engine.evaluate(PRODUCTS, data, OperationType.CREATE);

            verifyNoInteractions(ruleEvaluator);
        }
    }

    @Nested
    @DisplayName("Severity")
    class SeverityTests {

        @Test
        @DisplayName("WARNING-severity rule that matches does not throw")
        void warningRuleDoesNotBlock() {
            var rule = new ValidationRuleDefinition(
                    "warn_rule", "x > 100", "Too high", "x",
                    "CREATE_AND_UPDATE", true, "WARNING");
            ruleRegistry.register(TENANT, "products", List.of(rule));

            Map<String, Object> data = Map.of("x", 150);
            when(ruleEvaluator.isInvalid("x > 100", data)).thenReturn(true);

            assertDoesNotThrow(() ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));
        }

        @Test
        @DisplayName("WARNING and ERROR rules together — only ERROR blocks")
        void warningAndErrorOnlyErrorBlocks() {
            var warn = new ValidationRuleDefinition(
                    "warn_rule", "x > 100", "warn", "x",
                    "CREATE_AND_UPDATE", true, "WARNING");
            var err = new ValidationRuleDefinition(
                    "err_rule", "y > 100", "err", "y",
                    "CREATE_AND_UPDATE", true, "ERROR");
            ruleRegistry.register(TENANT, "products", List.of(warn, err));

            Map<String, Object> data = Map.of("x", 150, "y", 200);
            when(ruleEvaluator.isInvalid("x > 100", data)).thenReturn(true);
            when(ruleEvaluator.isInvalid("y > 100", data)).thenReturn(true);

            RecordValidationException ex = assertThrows(RecordValidationException.class, () ->
                    engine.evaluate(PRODUCTS, data, OperationType.CREATE));

            // Only the ERROR rule contributes to the response
            assertEquals(1, ex.getErrors().size());
            assertEquals("err_rule", ex.getErrors().get(0).ruleName());
        }
    }

    @Nested
    @DisplayName("Referenced fields")
    class ReferencedFieldsTests {

        @Test
        @DisplayName("Collects field references from parsed formulas, not string literals or function names")
        void collectsFieldReferences() {
            ruleRegistry.register(TENANT, "projects", List.of(
                    new ValidationRuleDefinition("close", "AND(state = \"openTasks\", openTasks > 0)",
                            "msg", "state", "CREATE_AND_UPDATE", true),
                    new ValidationRuleDefinition("hours", "-totalHours < 0 || !ISBLANK(owner)",
                            "msg", null, "UPDATE", true)));

            assertEquals(Set.of("state", "openTasks", "totalHours", "owner"),
                    engine.referencedFields(PROJECTS, OperationType.UPDATE));
        }

        @Test
        @DisplayName("Skips rules that are inactive or do not apply to the operation")
        void skipsInactiveAndNonApplicableRules() {
            ruleRegistry.register(TENANT, "projects", List.of(
                    new ValidationRuleDefinition("update_only", "openTasks > 0", "msg", null, "UPDATE", true),
                    new ValidationRuleDefinition("inactive", "totalHours > 0", "msg", null, "CREATE", false),
                    new ValidationRuleDefinition("create", "state = \"x\"", "msg", null, "CREATE", true)));

            assertEquals(Set.of("state"), engine.referencedFields(PROJECTS, OperationType.CREATE));
        }

        @Test
        @DisplayName("An unparseable formula contributes no references")
        void unparseableFormulaContributesNothing() {
            ruleRegistry.register(TENANT, "projects", List.of(
                    new ValidationRuleDefinition("broken", "openTasks > (", "msg", null, "CREATE_AND_UPDATE", true)));

            assertEquals(Set.of(), engine.referencedFields(PROJECTS, OperationType.UPDATE));
        }

        @Test
        @DisplayName("Returns empty for a collection without rules")
        void emptyWithoutRules() {
            assertEquals(Set.of(), engine.referencedFields(PROJECTS, OperationType.UPDATE));
        }
    }

    /**
     * Two tenants each own an {@code orders} collection. A write is checked only against the
     * rules of the tenant it belongs to — with the registry keyed by collection name, tenant B's
     * write was rejected by tenant A's rule.
     */
    @Nested
    @DisplayName("Tenant scoping")
    class TenantScopingTests {

        private final CollectionDefinition ordersA = tenantCollection("tenant-a", "orders");
        private final CollectionDefinition ordersB = tenantCollection("tenant-b", "orders");
        private final ValidationRuleDefinition aMaxTotal = new ValidationRuleDefinition(
                "a_max_total", "total > 100", "Tenant A caps orders at 100", "total",
                "CREATE_AND_UPDATE", true);

        @Test
        @DisplayName("tenant B's write to its `orders` is not rejected by tenant A's rule")
        void otherTenantsRuleDoesNotApply() {
            ruleRegistry.register("tenant-a", "orders", List.of(aMaxTotal));
            Map<String, Object> data = Map.of("total", 500);
            when(ruleEvaluator.isInvalid("total > 100", data)).thenReturn(true);

            assertDoesNotThrow(() -> engine.evaluate(ordersB, data, OperationType.CREATE));
            assertEquals(Set.of(), engine.referencedFields(ordersB, OperationType.CREATE));

            RecordValidationException ex = assertThrows(RecordValidationException.class,
                    () -> engine.evaluate(ordersA, data, OperationType.CREATE));
            assertEquals("a_max_total", ex.getErrors().get(0).ruleName());
        }

        @Test
        @DisplayName("a tenant-owned definition's rules come from its tenantId, not the bound tenant")
        void ownerTenantGovernsTenantCollections() {
            ruleRegistry.register("tenant-a", "orders", List.of(aMaxTotal));
            Map<String, Object> data = Map.of("total", 500);
            when(ruleEvaluator.isInvalid("total > 100", data)).thenReturn(true);

            TenantContext.runWithTenant("tenant-a",
                    () -> assertDoesNotThrow(() -> engine.evaluate(ordersB, data, OperationType.CREATE)));
        }

        @Test
        @DisplayName("a tenant-collection definition without a tenant gets no rules")
        void definitionWithoutTenantGetsNoRules() {
            ruleRegistry.register("tenant-a", "orders", List.of(aMaxTotal));

            assertDoesNotThrow(() -> engine.evaluate(tenantCollection(null, "orders"),
                    Map.of("total", 500), OperationType.CREATE));
            verifyNoInteractions(ruleEvaluator);
        }

        @Test
        @DisplayName("on a system collection, only the writing tenant's rules apply")
        void systemCollectionUsesTheWritingTenantsRules() {
            CollectionDefinition users = new CollectionDefinitionBuilder()
                    .name("users")
                    .addField(FieldDefinition.string("email"))
                    .systemCollection(true)
                    .build();
            ValidationRuleDefinition aRule = new ValidationRuleDefinition(
                    "a_email", "ISBLANK(email)", "Tenant A requires an email", "email",
                    "CREATE_AND_UPDATE", true);
            ruleRegistry.registerSystemCollectionRules("users", "tenant-a", List.of(aRule));
            Map<String, Object> data = Map.of("name", "x");
            when(ruleEvaluator.isInvalid("ISBLANK(email)", data)).thenReturn(true);

            TenantContext.runWithTenant("tenant-b",
                    () -> assertDoesNotThrow(() -> engine.evaluate(users, data, OperationType.CREATE)));
            assertDoesNotThrow(() -> engine.evaluate(users, data, OperationType.CREATE),
                    "no tenant bound: no tenant's rules apply");
            TenantContext.runWithTenant("tenant-a",
                    () -> assertThrows(RecordValidationException.class,
                            () -> engine.evaluate(users, data, OperationType.CREATE)));
        }
    }
}
