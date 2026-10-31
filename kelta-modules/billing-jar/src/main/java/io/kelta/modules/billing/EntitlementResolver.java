package io.kelta.modules.billing;

import io.kelta.runtime.module.service.MemberEntitlements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves what a portal member is currently entitled to.
 *
 * <p><b>Resolution order.</b> The base plan is the member's subscription plan when its status is
 * entitling ({@code active}, {@code trialing}, {@code past_due}), otherwise the tenant's DEFAULT
 * plan. Every live pass is then merged on top. A lapsed subscription therefore degrades to the
 * free baseline rather than to nothing, and {@code past_due} keeps its entitlements while the
 * processor retries the card — cutting a member off mid-retry punishes a recoverable failure.
 *
 * <p><b>Merge rules.</b> Numbers SUM, booleans OR, arrays union (order-preserving, de-duplicated),
 * anything else last-write-wins. Passes are additive by construction: a ten-watch pass on top of a
 * ten-watch plan gives twenty, and a pass can grant a capability the plan lacks but never revoke
 * one it has.
 *
 * <p><b>Expiry is read-time.</b> A pass counts only while it is live, regardless of its stored
 * status, so a member is never over-entitled by an expiry sweep that has not run.
 *
 * <p><b>Deliberately uncached, unlike the compiled-in implementation.</b> That one keeps a
 * Caffeine cache invalidated fleet-wide over NATS — neither is available here: a module cannot
 * subscribe to NATS, so a cache it held could not be invalidated when a webhook on another pod
 * changed a subscription, and a stale entitlement is worse than a slower one. Every resolve reads
 * the collections. Revisit only with a measured latency problem and a real invalidation path.
 */
public class EntitlementResolver {

    private static final Logger log = LoggerFactory.getLogger(EntitlementResolver.class);

    /** Subscription statuses that confer their plan's entitlements. */
    static final Set<String> ENTITLING_STATUSES = Set.of("active", "trialing", "past_due");

    static final String KIND_DEFAULT = "DEFAULT";

    private final BillingCollections collections;

    public EntitlementResolver(BillingCollections collections) {
        this.collections = collections;
    }

    /**
     * Effective entitlements for a member. Never null — an unknown member, a tenant with no plans,
     * or a lapsed subscription with no DEFAULT plan all resolve to
     * {@link MemberEntitlements#EMPTY} rather than an error, so a caller's fallback is always the
     * restrictive one.
     */
    public MemberEntitlements resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            return MemberEntitlements.EMPTY;
        }

        Optional<Map<String, Object>> subscription = collections.findSubscriptionByUserId(userId);
        boolean entitling = subscription.map(EntitlementResolver::isEntitling).orElse(false);

        Optional<Map<String, Object>> basePlan = Optional.empty();
        if (entitling) {
            Object planId = subscription.get().get("planId");
            if (planId != null && !planId.toString().isBlank()) {
                basePlan = collections.findPlanById(planId.toString());
            }
        }
        if (basePlan.isEmpty()) {
            basePlan = defaultPlan();
        }

        Map<String, Object> merged = new LinkedHashMap<>(
                basePlan.map(EntitlementResolver::entitlementsOf).orElseGet(Map::of));

        for (Map<String, Object> pass : collections.livePasses(userId, Instant.now())) {
            Object planId = pass.get("planId");
            if (planId == null || planId.toString().isBlank()) {
                continue;
            }
            collections.findPlanById(planId.toString())
                    .map(EntitlementResolver::entitlementsOf)
                    .ifPresent(passValues -> mergeInto(merged, passValues));
        }

        String status = subscription.map(s -> asString(s.get("status"))).orElse(null);
        return new MemberEntitlements(
                basePlan.map(p -> asString(p.get("code"))).orElse(null),
                // Only report a status when it is the one that actually supplied the baseline;
                // a lapsed member is on DEFAULT.
                entitling ? status : null,
                merged);
    }

    /** Integer limit, or {@code deflt} when the member has no such entitlement. */
    public int intLimit(String userId, String key, int deflt) {
        return resolve(userId).intValue(key, deflt);
    }

    /** Boolean flag, or {@code deflt} when the member has no such entitlement. */
    public boolean boolLimit(String userId, String key, boolean deflt) {
        return resolve(userId).boolValue(key, deflt);
    }

    /** String list (e.g. permitted alert channels); empty when unset. */
    public List<String> listLimit(String userId, String key) {
        return resolve(userId).listValue(key);
    }

    // ------------------------------------------------------------- Internals

    private Optional<Map<String, Object>> defaultPlan() {
        return collections.activePlans().stream()
                .filter(p -> KIND_DEFAULT.equalsIgnoreCase(asString(p.get("kind"))))
                .findFirst();
    }

    static boolean isEntitling(Map<String, Object> subscription) {
        String status = asString(subscription.get("status"));
        return status != null && ENTITLING_STATUSES.contains(status.toLowerCase());
    }

    /**
     * A plan's opaque entitlement map. The platform never interprets the keys, so a tenant invents
     * limits without a schema change. A malformed value yields an empty map rather than an
     * exception: one bad plan row must not break every entitlement check for the tenant.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> entitlementsOf(Map<String, Object> plan) {
        Object raw = plan.get("entitlements");
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (raw != null && !raw.toString().isBlank()) {
            log.warn("Ignoring plan entitlements of unexpected type {}", raw.getClass().getName());
        }
        return Map.of();
    }

    /**
     * Merges {@code addition} into {@code base}: numbers SUM, booleans OR, arrays union,
     * everything else last-write-wins.
     */
    @SuppressWarnings("unchecked")
    static void mergeInto(Map<String, Object> base, Map<String, Object> addition) {
        addition.forEach((key, value) -> base.merge(key, value, (existing, incoming) -> {
            if (existing instanceof Number a && incoming instanceof Number b) {
                return sum(a, b);
            }
            if (existing instanceof Boolean a && incoming instanceof Boolean b) {
                return a || b;
            }
            if (existing instanceof List<?> a && incoming instanceof List<?> b) {
                LinkedHashSet<Object> union = new LinkedHashSet<>((List<Object>) a);
                union.addAll((List<Object>) b);
                return new ArrayList<>(union);
            }
            return incoming;
        }));
    }

    /** Keeps integral sums integral; falls back to BigDecimal for fractional values. */
    private static Number sum(Number a, Number b) {
        if (isIntegral(a) && isIntegral(b)) {
            return a.longValue() + b.longValue();
        }
        return new BigDecimal(a.toString()).add(new BigDecimal(b.toString()));
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
