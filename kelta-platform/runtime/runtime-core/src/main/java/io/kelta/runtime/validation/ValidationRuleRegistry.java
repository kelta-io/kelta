package io.kelta.runtime.validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of custom validation rules, scoped by tenant.
 *
 * <p>Tenants share collection names ({@code orders} in two tenants), so rules are never keyed by
 * a collection name alone: a name-keyed registry validated one tenant's writes against whichever
 * tenant registered that name last, and tearing one tenant's collection down dropped the other's
 * rules. Every validation rule row belongs to a tenant ({@code validation_rule.tenant_id}), so
 * every rule set here has one too:
 *
 * <ul>
 *   <li><b>Tenant-owned collections</b> are keyed by the owning tenant and the collection name —
 *       the same {@code tenantId:name} key {@code CollectionDefinition#registryKey()} gives the
 *       collection registry.</li>
 *   <li><b>System collections</b> have one definition that every tenant writes through, while each
 *       rule on one belongs to the tenant that wrote it. They are held per collection, per rule
 *       tenant, and govern only that tenant's writes.</li>
 * </ul>
 *
 * <p>There is no bare-name lookup and no fallback from one tenant (or kind) to another: a lookup
 * without a tenant returns no rules.
 *
 * <p>The worker populates this registry when initializing or refreshing collections. The
 * {@link CustomValidationRuleEngine} reads from it during record create/update operations.
 */
public class ValidationRuleRegistry {

    /** Tenant-owned collection rules, keyed by {@code tenantId:collectionName}. */
    private final ConcurrentHashMap<String, List<ValidationRuleDefinition>> tenantCollectionRules =
            new ConcurrentHashMap<>();

    /**
     * System collection name to (rule tenant ID to that tenant's rules). Each inner map is
     * immutable and replaced whole, so a reader never sees a half-applied reload.
     */
    private final ConcurrentHashMap<String, Map<String, List<ValidationRuleDefinition>>> systemCollectionRules =
            new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- tenant-owned collections

    /**
     * Registers (or replaces) the validation rules of a tenant-owned collection.
     *
     * @param tenantId       the tenant that owns the collection (required)
     * @param collectionName the collection name
     * @param rules          the collection's validation rule definitions
     * @throws IllegalArgumentException if {@code tenantId} is null or blank
     */
    public void register(String tenantId, String collectionName, List<ValidationRuleDefinition> rules) {
        tenantCollectionRules.put(key(requireTenant(tenantId), requireName(collectionName)), List.copyOf(rules));
    }

    /**
     * Removes the validation rules of one tenant's collection. Another tenant's same-named
     * collection keeps its rules.
     *
     * @param tenantId       the tenant that owns the collection (required)
     * @param collectionName the collection name
     * @throws IllegalArgumentException if {@code tenantId} is null or blank
     */
    public void unregister(String tenantId, String collectionName) {
        tenantCollectionRules.remove(key(requireTenant(tenantId), requireName(collectionName)));
    }

    /**
     * Returns the active validation rules of a tenant-owned collection.
     *
     * @param tenantId       the tenant that owns the collection
     * @param collectionName the collection name
     * @return unmodifiable list of active rules; empty when none are registered for this tenant's
     *         collection or when {@code tenantId} is null or blank
     */
    public List<ValidationRuleDefinition> getActiveRules(String tenantId, String collectionName) {
        if (isBlank(tenantId) || collectionName == null) {
            return List.of();
        }
        return active(tenantCollectionRules.get(key(tenantId, collectionName)));
    }

    // ---------------------------------------------------------------- system collections

    /**
     * Replaces every tenant's validation rules on a system collection. A tenant absent from
     * {@code rulesByTenant} is left with no rules on it.
     *
     * @param collectionName the system collection name
     * @param rulesByTenant  rule tenant ID to the rules that tenant defined on the collection
     * @throws IllegalArgumentException if a tenant ID in {@code rulesByTenant} is null or blank
     */
    public void registerSystemCollectionRules(String collectionName,
                                              Map<String, List<ValidationRuleDefinition>> rulesByTenant) {
        requireName(collectionName);
        Map<String, List<ValidationRuleDefinition>> next = new HashMap<>();
        rulesByTenant.forEach((tenantId, rules) -> {
            if (!rules.isEmpty()) {
                next.put(requireTenant(tenantId), List.copyOf(rules));
            }
        });
        if (next.isEmpty()) {
            systemCollectionRules.remove(collectionName);
        } else {
            systemCollectionRules.put(collectionName, Map.copyOf(next));
        }
    }

    /**
     * Replaces one tenant's validation rules on a system collection; other tenants' rules on it
     * are untouched.
     *
     * @param collectionName the system collection name
     * @param tenantId       the tenant that defined the rules (required)
     * @param rules          that tenant's rules on the collection (empty removes them)
     * @throws IllegalArgumentException if {@code tenantId} is null or blank
     */
    public void registerSystemCollectionRules(String collectionName, String tenantId,
                                              List<ValidationRuleDefinition> rules) {
        requireName(collectionName);
        requireTenant(tenantId);
        List<ValidationRuleDefinition> copy = List.copyOf(rules);
        systemCollectionRules.compute(collectionName, (name, current) -> {
            Map<String, List<ValidationRuleDefinition>> next =
                    current == null ? new HashMap<>() : new HashMap<>(current);
            if (copy.isEmpty()) {
                next.remove(tenantId);
            } else {
                next.put(tenantId, copy);
            }
            return next.isEmpty() ? null : Map.copyOf(next);
        });
    }

    /**
     * Removes every tenant's validation rules on a system collection.
     *
     * @param collectionName the system collection name
     */
    public void unregisterSystemCollection(String collectionName) {
        systemCollectionRules.remove(requireName(collectionName));
    }

    /**
     * Returns the active validation rules a tenant defined on a system collection — the rules
     * that govern that tenant's writes to it, and no other tenant's.
     *
     * @param tenantId       the writing tenant
     * @param collectionName the system collection name
     * @return unmodifiable list of active rules; empty when the tenant has none on the
     *         collection or when {@code tenantId} is null or blank
     */
    public List<ValidationRuleDefinition> getActiveSystemCollectionRules(String tenantId, String collectionName) {
        if (isBlank(tenantId) || collectionName == null) {
            return List.of();
        }
        Map<String, List<ValidationRuleDefinition>> byTenant = systemCollectionRules.get(collectionName);
        return active(byTenant == null ? null : byTenant.get(tenantId));
    }

    // ---------------------------------------------------------------- shared

    /**
     * Returns the number of registered rule sets: one per tenant-owned collection, plus one per
     * (system collection, tenant) pair.
     */
    public int size() {
        int system = 0;
        for (Map<String, List<ValidationRuleDefinition>> byTenant : systemCollectionRules.values()) {
            system += byTenant.size();
        }
        return tenantCollectionRules.size() + system;
    }

    /** Same shape as {@code CollectionDefinition#registryKey()} for a tenant-owned collection. */
    private static String key(String tenantId, String collectionName) {
        return tenantId + ":" + collectionName;
    }

    private static List<ValidationRuleDefinition> active(List<ValidationRuleDefinition> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream().filter(ValidationRuleDefinition::active).toList();
    }

    private static String requireTenant(String tenantId) {
        if (isBlank(tenantId)) {
            throw new IllegalArgumentException(
                    "Validation rules are always tenant-scoped; a tenant ID is required");
        }
        return tenantId;
    }

    private static String requireName(String collectionName) {
        return Objects.requireNonNull(collectionName, "collectionName cannot be null");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
