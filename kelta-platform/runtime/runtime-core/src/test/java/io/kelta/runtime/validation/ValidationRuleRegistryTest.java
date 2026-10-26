package io.kelta.runtime.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ValidationRuleRegistry.
 */
class ValidationRuleRegistryTest {

    private static final String TENANT = "tenant-1";
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    private ValidationRuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ValidationRuleRegistry();
    }

    private static ValidationRuleDefinition rule(String name) {
        return new ValidationRuleDefinition(name, "x > 0", "Error " + name, null, "CREATE_AND_UPDATE", true);
    }

    private static List<String> names(List<ValidationRuleDefinition> rules) {
        return rules.stream().map(ValidationRuleDefinition::name).toList();
    }

    @Test
    @DisplayName("Should return empty list for unregistered collection")
    void shouldReturnEmptyForUnregisteredCollection() {
        List<ValidationRuleDefinition> rules = registry.getActiveRules(TENANT, "unknown");
        assertNotNull(rules);
        assertTrue(rules.isEmpty());
    }

    @Test
    @DisplayName("Should register and retrieve active rules")
    void shouldRegisterAndRetrieveActiveRules() {
        var activeRule = new ValidationRuleDefinition(
                "rule1", "x > 0", "Error", null, "CREATE", true);
        var inactiveRule = new ValidationRuleDefinition(
                "rule2", "y < 0", "Error2", null, "UPDATE", false);

        registry.register(TENANT, "products", List.of(activeRule, inactiveRule));

        List<ValidationRuleDefinition> activeRules = registry.getActiveRules(TENANT, "products");
        assertEquals(1, activeRules.size());
        assertEquals("rule1", activeRules.get(0).name());
    }

    @Test
    @DisplayName("Should replace rules on re-registration")
    void shouldReplaceRulesOnReRegistration() {
        var rule1 = new ValidationRuleDefinition(
                "rule1", "x > 0", "Error", null, "CREATE", true);
        var rule2 = new ValidationRuleDefinition(
                "rule2", "y < 0", "Error2", null, "UPDATE", true);

        registry.register(TENANT, "products", List.of(rule1));
        assertEquals(1, registry.getActiveRules(TENANT, "products").size());

        registry.register(TENANT, "products", List.of(rule1, rule2));
        assertEquals(2, registry.getActiveRules(TENANT, "products").size());
    }

    @Test
    @DisplayName("Should unregister collection rules")
    void shouldUnregisterCollectionRules() {
        var rule = new ValidationRuleDefinition(
                "rule1", "x > 0", "Error", null, "CREATE", true);
        registry.register(TENANT, "products", List.of(rule));
        assertEquals(1, registry.size());

        registry.unregister(TENANT, "products");
        assertEquals(0, registry.size());
        assertTrue(registry.getActiveRules(TENANT, "products").isEmpty());
    }

    @Test
    @DisplayName("Should track size correctly")
    void shouldTrackSizeCorrectly() {
        assertEquals(0, registry.size());

        var rule = new ValidationRuleDefinition(
                "rule1", "x > 0", "Error", null, "CREATE", true);
        registry.register(TENANT, "products", List.of(rule));
        registry.register(TENANT, "orders", List.of(rule));

        assertEquals(2, registry.size());
    }

    @Test
    @DisplayName("Should return only active rules when mixed active/inactive")
    void shouldReturnOnlyActiveRules() {
        List<ValidationRuleDefinition> rules = List.of(
                new ValidationRuleDefinition("r1", "a > 0", "E1", null, "CREATE", true),
                new ValidationRuleDefinition("r2", "b > 0", "E2", null, "CREATE", false),
                new ValidationRuleDefinition("r3", "c > 0", "E3", null, "CREATE", true),
                new ValidationRuleDefinition("r4", "d > 0", "E4", null, "CREATE", false));

        registry.register(TENANT, "test", rules);
        List<ValidationRuleDefinition> active = registry.getActiveRules(TENANT, "test");
        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(ValidationRuleDefinition::active));
    }

    /**
     * Tenants share collection names. Keyed by name alone, tenant B's registration replaced
     * tenant A's rules (so A's writes were checked against B's rules and vice versa) and
     * unregistering A's {@code orders} dropped B's.
     */
    @Nested
    @DisplayName("Same-named collections in two tenants")
    class TenantScoping {

        @Test
        @DisplayName("each tenant's `orders` keeps its own rules, whichever registered last")
        void sameNameInTwoTenantsKeepsSeparateRules() {
            registry.register(TENANT_A, "orders", List.of(rule("a_rule")));
            registry.register(TENANT_B, "orders", List.of(rule("b_rule")));

            assertEquals(List.of("a_rule"), names(registry.getActiveRules(TENANT_A, "orders")));
            assertEquals(List.of("b_rule"), names(registry.getActiveRules(TENANT_B, "orders")));
            assertEquals(2, registry.size());
        }

        @Test
        @DisplayName("a tenant without rules on `orders` gets none, even when another tenant has some")
        void tenantWithoutRulesGetsNone() {
            registry.register(TENANT_A, "orders", List.of(rule("a_rule")));

            assertTrue(registry.getActiveRules(TENANT_B, "orders").isEmpty());
        }

        @Test
        @DisplayName("unregistering tenant A's `orders` leaves tenant B's rules intact")
        void unregisteringOneTenantLeavesTheOther() {
            registry.register(TENANT_B, "orders", List.of(rule("b_rule")));
            registry.register(TENANT_A, "orders", List.of(rule("a_rule")));

            registry.unregister(TENANT_A, "orders");

            assertTrue(registry.getActiveRules(TENANT_A, "orders").isEmpty());
            assertEquals(List.of("b_rule"), names(registry.getActiveRules(TENANT_B, "orders")));
            assertEquals(1, registry.size());
        }

        @Test
        @DisplayName("a lookup without a tenant finds nothing, and rules cannot be registered without one")
        void noTenantNoRules() {
            registry.register(TENANT_A, "orders", List.of(rule("a_rule")));

            assertTrue(registry.getActiveRules(null, "orders").isEmpty());
            assertTrue(registry.getActiveRules("", "orders").isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(null, "orders", List.of(rule("x"))));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(" ", "orders", List.of(rule("x"))));
            assertThrows(IllegalArgumentException.class, () -> registry.unregister(null, "orders"));
        }
    }

    /**
     * A system collection is one definition every tenant writes through, but each rule on it
     * belongs to the tenant that wrote it.
     */
    @Nested
    @DisplayName("System collections")
    class SystemCollections {

        @Test
        @DisplayName("each tenant sees only the rules it defined on a system collection")
        void rulesArePerTenant() {
            registry.registerSystemCollectionRules("users", Map.of(
                    TENANT_A, List.of(rule("a_rule")),
                    TENANT_B, List.of(rule("b_rule"))));

            assertEquals(List.of("a_rule"), names(registry.getActiveSystemCollectionRules(TENANT_A, "users")));
            assertEquals(List.of("b_rule"), names(registry.getActiveSystemCollectionRules(TENANT_B, "users")));
            assertTrue(registry.getActiveSystemCollectionRules("tenant-c", "users").isEmpty());
            assertTrue(registry.getActiveSystemCollectionRules(null, "users").isEmpty());
        }

        @Test
        @DisplayName("replacing one tenant's rules leaves the other tenants' rules in place")
        void replaceOneTenant() {
            registry.registerSystemCollectionRules("users", Map.of(
                    TENANT_A, List.of(rule("a_rule")),
                    TENANT_B, List.of(rule("b_rule"))));

            registry.registerSystemCollectionRules("users", TENANT_B, List.of(rule("b_rule_2")));
            assertEquals(List.of("a_rule"), names(registry.getActiveSystemCollectionRules(TENANT_A, "users")));
            assertEquals(List.of("b_rule_2"), names(registry.getActiveSystemCollectionRules(TENANT_B, "users")));

            registry.registerSystemCollectionRules("users", TENANT_B, List.of());
            assertEquals(List.of("a_rule"), names(registry.getActiveSystemCollectionRules(TENANT_A, "users")));
            assertTrue(registry.getActiveSystemCollectionRules(TENANT_B, "users").isEmpty());
        }

        @Test
        @DisplayName("a full reload replaces every tenant's rules; unregistering clears them all")
        void replaceAllAndUnregister() {
            registry.registerSystemCollectionRules("users", Map.of(
                    TENANT_A, List.of(rule("a_rule")),
                    TENANT_B, List.of(rule("b_rule"))));

            registry.registerSystemCollectionRules("users", Map.of(TENANT_B, List.of(rule("b_rule"))));
            assertTrue(registry.getActiveSystemCollectionRules(TENANT_A, "users").isEmpty());
            assertEquals(1, registry.size());

            registry.unregisterSystemCollection("users");
            assertTrue(registry.getActiveSystemCollectionRules(TENANT_B, "users").isEmpty());
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("system-collection rules and a tenant collection of the same name never mix")
        void systemAndTenantRulesAreSeparate() {
            registry.registerSystemCollectionRules("users", TENANT_A, List.of(rule("system_rule")));
            registry.register(TENANT_A, "users", List.of(rule("tenant_rule")));

            assertEquals(List.of("system_rule"), names(registry.getActiveSystemCollectionRules(TENANT_A, "users")));
            assertEquals(List.of("tenant_rule"), names(registry.getActiveRules(TENANT_A, "users")));

            registry.unregister(TENANT_A, "users");
            assertEquals(List.of("system_rule"), names(registry.getActiveSystemCollectionRules(TENANT_A, "users")));
        }
    }

    @Nested
    @DisplayName("Failed loads (fail closed)")
    class FailedLoads {

        @Test
        @DisplayName("a failed load keeps a tenant collection's previously registered rules")
        void failedLoadKeepsPreviousTenantRules() {
            registry.register(TENANT, "orders", List.of(rule("cap")));

            assertTrue(registry.markLoadFailed(TENANT, "orders"));
            assertEquals(List.of("cap"), names(registry.getActiveRules(TENANT, "orders")));
        }

        @Test
        @DisplayName("a previously loaded empty rule set is kept, not marked unavailable")
        void failedLoadKeepsPreviousEmptySet() {
            registry.register(TENANT, "orders", List.of());

            assertTrue(registry.markLoadFailed(TENANT, "orders"));
            assertTrue(registry.getActiveRules(TENANT, "orders").isEmpty());
        }

        @Test
        @DisplayName("a tenant collection that never loaded is unavailable until a load succeeds")
        void neverLoadedTenantCollectionIsUnavailable() {
            assertFalse(registry.markLoadFailed(TENANT, "orders"));

            var ex = assertThrows(ValidationRuleRegistry.RulesUnavailableException.class,
                    () -> registry.getActiveRules(TENANT, "orders"));
            assertEquals(503, ex.getStatusCode().value());
            assertTrue(registry.getActiveRules(TENANT_B, "orders").isEmpty(),
                    "another tenant's same-named collection is unaffected");

            registry.register(TENANT, "orders", List.of(rule("cap")));
            assertEquals(List.of("cap"), names(registry.getActiveRules(TENANT, "orders")));
        }

        @Test
        @DisplayName("unregistering clears the unavailable state")
        void unregisterClearsUnavailable() {
            registry.markLoadFailed(TENANT, "orders");
            registry.unregister(TENANT, "orders");

            assertTrue(registry.getActiveRules(TENANT, "orders").isEmpty());
        }

        @Test
        @DisplayName("a failed load keeps every tenant's rules on a loaded system collection")
        void failedLoadKeepsSystemCollectionRules() {
            registry.registerSystemCollectionRules("users", Map.of(TENANT_A, List.of(rule("a")), TENANT_B, List.of(rule("b"))));

            assertTrue(registry.markSystemCollectionLoadFailed("users"));
            assertEquals(List.of("a"), names(registry.getActiveSystemCollectionRules(TENANT_A, "users")));
            assertEquals(List.of("b"), names(registry.getActiveSystemCollectionRules(TENANT_B, "users")));
        }

        @Test
        @DisplayName("a system collection loaded with no rules counts as loaded")
        void emptySystemLoadCountsAsLoaded() {
            registry.registerSystemCollectionRules("users", Map.of());

            assertTrue(registry.isSystemCollectionLoaded("users"));
            assertTrue(registry.markSystemCollectionLoadFailed("users"));
            assertTrue(registry.getActiveSystemCollectionRules(TENANT_A, "users").isEmpty());
        }

        @Test
        @DisplayName("a system collection that never loaded is unavailable to every tenant until a full load")
        void neverLoadedSystemCollectionIsUnavailable() {
            assertFalse(registry.isSystemCollectionLoaded("users"));
            assertFalse(registry.markSystemCollectionLoadFailed("users"));

            assertThrows(ValidationRuleRegistry.RulesUnavailableException.class,
                    () -> registry.getActiveSystemCollectionRules(TENANT_A, "users"));
            registry.registerSystemCollectionRules("users", TENANT_A, List.of(rule("a")));
            assertThrows(ValidationRuleRegistry.RulesUnavailableException.class,
                    () -> registry.getActiveSystemCollectionRules(TENANT_B, "users"),
                    "one tenant's reload does not stand in for the others");

            registry.registerSystemCollectionRules("users", Map.of(TENANT_B, List.of(rule("b"))));
            assertTrue(registry.isSystemCollectionLoaded("users"));
            assertEquals(List.of("b"), names(registry.getActiveSystemCollectionRules(TENANT_B, "users")));
        }

        @Test
        @DisplayName("unregistering a system collection clears its loaded and unavailable state")
        void unregisterSystemCollectionClearsState() {
            registry.registerSystemCollectionRules("users", Map.of());
            registry.unregisterSystemCollection("users");
            assertFalse(registry.isSystemCollectionLoaded("users"));

            registry.markSystemCollectionLoadFailed("users");
            registry.unregisterSystemCollection("users");
            assertTrue(registry.getActiveSystemCollectionRules(TENANT_A, "users").isEmpty());
        }
    }
}
