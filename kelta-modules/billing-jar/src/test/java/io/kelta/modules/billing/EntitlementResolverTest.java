package io.kelta.modules.billing;

import io.kelta.runtime.module.service.MemberEntitlements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entitlement resolution decides what a paying member may do, so each rule here is one a bug would
 * silently under- or over-grant.
 */
@DisplayName("EntitlementResolver")
class EntitlementResolverTest {

    private FakeCollections collections;
    private EntitlementResolver resolver;

    @BeforeEach
    void setUp() {
        collections = new FakeCollections();
        resolver = new EntitlementResolver(collections);
    }

    /**
     * Hand-written rather than a Mockito mock: the inline mock-maker cannot retransform classes
     * under JDK 25 in a standalone module build, and a module others copy as a template should not
     * carry that fragility. The seam is small enough that a fake reads better anyway.
     */
    private static final class FakeCollections extends BillingCollections {
        private final Map<String, Map<String, Object>> plansById = new LinkedHashMap<>();
        private List<Map<String, Object>> active = List.of();
        private Optional<Map<String, Object>> subscription = Optional.empty();
        private List<Map<String, Object>> passes = List.of();

        FakeCollections() {
            super(null, null);
        }

        @Override
        public List<Map<String, Object>> activePlans() {
            return active;
        }

        @Override
        public Optional<Map<String, Object>> findPlanById(String id) {
            return Optional.ofNullable(plansById.get(id));
        }

        @Override
        public Optional<Map<String, Object>> findSubscriptionByUserId(String userId) {
            return subscription;
        }

        @Override
        public List<Map<String, Object>> livePasses(String userId, Instant now) {
            return passes;
        }

        FakeCollections withActivePlans(Map<String, Object>... plans) {
            active = List.of(plans);
            for (Map<String, Object> p : plans) {
                plansById.put(String.valueOf(p.get("id")), p);
            }
            return this;
        }

        FakeCollections withPlan(Map<String, Object> p) {
            plansById.put(String.valueOf(p.get("id")), p);
            return this;
        }

        FakeCollections withSubscription(Map<String, Object> s) {
            subscription = Optional.of(s);
            return this;
        }

        FakeCollections withLivePasses(Map<String, Object>... p) {
            passes = List.of(p);
            return this;
        }
    }

    private static Map<String, Object> plan(String id, String code, String kind,
                                            Map<String, Object> entitlements) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("code", code);
        p.put("kind", kind);
        p.put("active", true);
        p.put("entitlements", entitlements);
        return p;
    }

    private static Map<String, Object> subscription(String planId, String status) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("planId", planId);
        s.put("status", status);
        return s;
    }

    // ------------------------------------------------------------ Base plan selection

    @Test
    @DisplayName("An entitling subscription supplies the base plan")
    void entitlingSubscriptionSuppliesBasePlan() {
        collections.withSubscription(subscription("p1", "active"))
                .withPlan(plan("p1", "PRO", "SUBSCRIPTION", Map.of("watches", 10)));

        MemberEntitlements result = resolver.resolve("u1");

        assertThat(result.planCode()).isEqualTo("PRO");
        assertThat(result.subscriptionStatus()).isEqualTo("active");
        assertThat(result.intValue("watches", 0)).isEqualTo(10);
    }

    @Test
    @DisplayName("A re-subscribed member gets the new active row, not the canceled one stored first")
    void resubscribedMemberResolvesActiveRow() {
        // Real BillingCollections over a store that returns the oldest row first, as an unsorted
        // query does.
        BillingCollectionsTest.StubStore store = new BillingCollectionsTest.StubStore()
                .rows(BillingCollections.SUBSCRIPTIONS, List.of(
                        BillingCollectionsTest.subscription("old", "canceled",
                                "2026-03-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                        BillingCollectionsTest.subscription("new", "active",
                                "2026-11-01T00:00:00Z", "2026-10-01T00:00:00Z")))
                .rows(BillingCollections.PLANS, List.of(
                        plan("p0", "FREE", "DEFAULT", Map.of("watches", 1)),
                        plan("plan-old", "BASIC", "SUBSCRIPTION", Map.of("watches", 5)),
                        plan("plan-new", "PRO", "SUBSCRIPTION", Map.of("watches", 10))));

        MemberEntitlements result = new EntitlementResolver(store.collections()).resolve("u1");

        assertThat(result.planCode()).isEqualTo("PRO");
        assertThat(result.subscriptionStatus()).isEqualTo("active");
        assertThat(result.intValue("watches", 0)).isEqualTo(10);
    }

    @Test
    @DisplayName("past_due keeps entitlements while the processor retries the card")
    void pastDueStaysEntitled() {
        // Cutting a member off mid-retry punishes a recoverable failure.
        collections.withSubscription(subscription("p1", "past_due"))
                .withPlan(plan("p1", "PRO", "SUBSCRIPTION", Map.of("watches", 10)));

        assertThat(resolver.resolve("u1").intValue("watches", 0)).isEqualTo(10);
    }

    @Test
    @DisplayName("A lapsed subscription degrades to the DEFAULT plan, not to nothing")
    void lapsedFallsBackToDefaultPlan() {
        collections.withSubscription(subscription("p1", "canceled"))
                .withActivePlans(plan("p0", "FREE", "DEFAULT", Map.of("watches", 1)));

        MemberEntitlements result = resolver.resolve("u1");

        assertThat(result.planCode()).isEqualTo("FREE");
        assertThat(result.intValue("watches", 0)).isEqualTo(1);
        // Status is only reported when it supplied the baseline — a lapsed member is on DEFAULT.
        assertThat(result.subscriptionStatus()).isNull();
    }

    @Test
    @DisplayName("No subscription and no DEFAULT plan resolves to EMPTY, never an error")
    void noPlansResolvesEmpty() {
        // The caller's fallback must always be the restrictive one.
        assertThat(resolver.resolve("u1").isEmpty()).isTrue();
        assertThat(resolver.resolve(null)).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(resolver.resolve("  ")).isEqualTo(MemberEntitlements.EMPTY);
    }

    // ------------------------------------------------------------ Pass merging

    @Test
    @DisplayName("A live pass adds to the plan rather than replacing it")
    void passesAreAdditive() {
        collections.withActivePlans(plan("p0", "FREE", "DEFAULT", Map.of("watches", 10)))
                .withPlan(plan("pass1", "TENPACK", "ONE_TIME", Map.of("watches", 10)))
                .withLivePasses(Map.of("planId", "pass1"));

        assertThat(resolver.resolve("u1").intValue("watches", 0)).isEqualTo(20);
    }

    @Test
    @DisplayName("A pass grants a capability the plan lacks but cannot revoke one it has")
    void passesCanGrantButNotRevoke() {
        collections.withActivePlans(plan("p0", "FREE", "DEFAULT", Map.of("sms", true, "email", true)))
                .withPlan(plan("pass1", "PUSH", "ONE_TIME", Map.of("push", true, "email", false)))
                .withLivePasses(Map.of("planId", "pass1"));

        MemberEntitlements result = resolver.resolve("u1");

        assertThat(result.boolValue("push", false)).isTrue();
        // Booleans OR — a pass must never take away what the plan granted.
        assertThat(result.boolValue("email", false)).isTrue();
    }

    @Test
    @DisplayName("Lists union without duplicates, preserving order")
    void listsUnion() {
        collections.withActivePlans(
                        plan("p0", "FREE", "DEFAULT", Map.of("channels", List.of("email", "push"))))
                .withPlan(plan("pass1", "SMS", "ONE_TIME", Map.of("channels", List.of("push", "sms"))))
                .withLivePasses(Map.of("planId", "pass1"));

        assertThat(resolver.resolve("u1").listValue("channels"))
                .containsExactly("email", "push", "sms");
    }

    @Test
    @DisplayName("A pass with no plan is ignored rather than failing the resolve")
    void passWithoutPlanIsIgnored() {
        collections.withActivePlans(plan("p0", "FREE", "DEFAULT", Map.of("watches", 1)))
                .withLivePasses(Map.of("stripeCheckoutSessionId", "cs_1"));

        assertThat(resolver.resolve("u1").intValue("watches", 0)).isEqualTo(1);
    }

    // ------------------------------------------------------------ Merge primitives

    @Test
    @DisplayName("Integral sums stay integral; fractional values fall back to BigDecimal")
    void sumsKeepTheirType() {
        Map<String, Object> base = new LinkedHashMap<>(Map.of("a", 2, "b", 1.5));
        EntitlementResolver.mergeInto(base, Map.of("a", 3, "b", 1.25));

        assertThat(base.get("a")).isEqualTo(5L);
        assertThat(((Number) base.get("b")).doubleValue()).isEqualTo(2.75);
    }

    @Test
    @DisplayName("Mismatched types are last-write-wins rather than an error")
    void mismatchedTypesAreLastWriteWins() {
        Map<String, Object> base = new LinkedHashMap<>(Map.of("a", 2));
        EntitlementResolver.mergeInto(base, Map.of("a", "unlimited"));

        assertThat(base.get("a")).isEqualTo("unlimited");
    }

    @Test
    @DisplayName("A plan with unusable entitlements yields an empty map, not an exception")
    void badEntitlementsDoNotBreakResolution() {
        // One bad plan row must not break every entitlement check for the tenant.
        assertThat(EntitlementResolver.entitlementsOf(Map.of("entitlements", "not-a-map"))).isEmpty();
        assertThat(EntitlementResolver.entitlementsOf(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Entitling statuses are matched case-insensitively")
    void entitlingStatusIsCaseInsensitive() {
        assertThat(EntitlementResolver.isEntitling(Map.of("status", "ACTIVE"))).isTrue();
        assertThat(EntitlementResolver.isEntitling(Map.of("status", "Trialing"))).isTrue();
        assertThat(EntitlementResolver.isEntitling(Map.of("status", "canceled"))).isFalse();
        assertThat(EntitlementResolver.isEntitling(Map.of())).isFalse();
    }

    @Test
    @DisplayName("Read-time expiry is the resolver's contract — it merges only what it is given")
    void onlyLivePassesAreMerged() {
        // livePasses already applies read-time expiry; the resolver must not second-guess stored
        // status, so a member is never over-entitled by a sweep that has not run.
        collections.withActivePlans(plan("p0", "FREE", "DEFAULT", Map.of("watches", 1)));

        assertThat(resolver.resolve("u1").intValue("watches", 0)).isEqualTo(1);
    }
}
