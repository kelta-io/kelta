package io.kelta.runtime.validation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <p><b>Fail closed.</b> A failed rule load never registers an empty set in place of the rules:
 * the loader calls {@link #markLoadFailed} / {@link #markSystemCollectionLoadFailed}, which keep
 * whatever was registered before. A collection whose rules have <em>never</em> loaded is marked
 * unavailable instead, and every rule lookup for it throws {@link RulesUnavailableException}
 * (HTTP 503) until a load succeeds — a write is refused rather than saved unvalidated.
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

    /** System collections whose every-tenant rule load has succeeded at least once. */
    private final Set<String> loadedSystemCollections = ConcurrentHashMap.newKeySet();

    /** Tenant-owned collection keys whose rules failed to load and were never loaded. */
    private final Set<String> unavailableTenantCollections = ConcurrentHashMap.newKeySet();

    /** System collections whose every-tenant rule load failed and never succeeded. */
    private final Set<String> unavailableSystemCollections = ConcurrentHashMap.newKeySet();

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
        String key = key(requireTenant(tenantId), requireName(collectionName));
        tenantCollectionRules.put(key, List.copyOf(rules));
        unavailableTenantCollections.remove(key);
    }

    /**
     * Records that loading a tenant-owned collection's rules failed. Rules registered by an
     * earlier load stay in force; a collection with none ever registered becomes unavailable, so
     * {@link #getActiveRules} throws for it until {@link #register} succeeds.
     *
     * @param tenantId       the tenant that owns the collection (required)
     * @param collectionName the collection name
     * @return {@code true} if earlier rules were kept, {@code false} if the collection is now
     *         unavailable
     * @throws IllegalArgumentException if {@code tenantId} is null or blank
     */
    public boolean markLoadFailed(String tenantId, String collectionName) {
        String key = key(requireTenant(tenantId), requireName(collectionName));
        if (tenantCollectionRules.containsKey(key)) {
            return true;
        }
        unavailableTenantCollections.add(key);
        return false;
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
        String key = key(requireTenant(tenantId), requireName(collectionName));
        tenantCollectionRules.remove(key);
        unavailableTenantCollections.remove(key);
    }

    /**
     * Returns the active validation rules of a tenant-owned collection.
     *
     * @param tenantId       the tenant that owns the collection
     * @param collectionName the collection name
     * @return unmodifiable list of active rules; empty when none are registered for this tenant's
     *         collection or when {@code tenantId} is null or blank
     * @throws RulesUnavailableException if the collection's rules failed to load and never loaded
     */
    public List<ValidationRuleDefinition> getActiveRules(String tenantId, String collectionName) {
        if (isBlank(tenantId) || collectionName == null) {
            return List.of();
        }
        String key = key(tenantId, collectionName);
        if (unavailableTenantCollections.contains(key)) {
            throw new RulesUnavailableException(collectionName);
        }
        return active(tenantCollectionRules.get(key));
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
        loadedSystemCollections.add(collectionName);
        unavailableSystemCollections.remove(collectionName);
    }

    /**
     * Whether every tenant's rules on a system collection have been loaded at least once
     * ({@link #registerSystemCollectionRules(String, Map)}). Until then a one-tenant reload
     * cannot stand in for the others.
     *
     * @param collectionName the system collection name
     */
    public boolean isSystemCollectionLoaded(String collectionName) {
        return collectionName != null && loadedSystemCollections.contains(collectionName);
    }

    /**
     * Records that loading a system collection's rules failed. Every tenant's rules registered
     * by an earlier load stay in force; a collection never loaded for every tenant becomes
     * unavailable, so {@link #getActiveSystemCollectionRules} throws for it until a full
     * {@link #registerSystemCollectionRules(String, Map)} succeeds.
     *
     * @param collectionName the system collection name
     * @return {@code true} if earlier rules were kept, {@code false} if the collection is now
     *         unavailable
     */
    public boolean markSystemCollectionLoadFailed(String collectionName) {
        requireName(collectionName);
        if (loadedSystemCollections.contains(collectionName)) {
            return true;
        }
        unavailableSystemCollections.add(collectionName);
        return false;
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
        loadedSystemCollections.remove(collectionName);
        unavailableSystemCollections.remove(collectionName);
    }

    /**
     * Returns the active validation rules a tenant defined on a system collection — the rules
     * that govern that tenant's writes to it, and no other tenant's.
     *
     * @param tenantId       the writing tenant
     * @param collectionName the system collection name
     * @return unmodifiable list of active rules; empty when the tenant has none on the
     *         collection or when {@code tenantId} is null or blank
     * @throws RulesUnavailableException if the collection's rules failed to load and never loaded
     */
    public List<ValidationRuleDefinition> getActiveSystemCollectionRules(String tenantId, String collectionName) {
        if (isBlank(tenantId) || collectionName == null) {
            return List.of();
        }
        if (unavailableSystemCollections.contains(collectionName)) {
            throw new RulesUnavailableException(collectionName);
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

    /**
     * A collection's validation rules could not be loaded and none were ever loaded, so a write
     * to it cannot be validated. Served as HTTP 503: the write may succeed once the rules load.
     */
    public static class RulesUnavailableException extends ResponseStatusException {

        public RulesUnavailableException(String collectionName) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "Validation rules for collection '" + collectionName
                    + "' could not be loaded; writes to it are refused until they load");
        }
    }
}
