package io.kelta.modules.billing;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These two handlers replace the compiled-in {@code GET /api/billing/plans} and {@code /me}.
 *
 * <p>The response shapes are asserted literally, because the consumer frontend is not in this repo
 * and will not fail this build: it moves to the module route by changing a path, and a field that
 * quietly changed name or nesting would surface as a broken billing page rather than a test failure.
 *
 * <p>Also asserted: no processor id ever reaches a member. A pricing page has no use for a price id,
 * and those ids are useful to an attacker probing the processor account.
 */
@DisplayName("Portal surface handlers")
class PortalSurfaceHandlerTest {

    private static final String MEMBER = "user-1";

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** Minimal stand-in; Mockito cannot mock on this JDK standalone, so the fake is hand-written. */
    private static final class Fake extends BillingCollections {
        List<Map<String, Object>> plans = List.of();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        Optional<Map<String, Object>> subscription = Optional.empty();
        List<Map<String, Object>> passes = List.of();

        Fake() {
            super(null, null);
        }

        @Override public List<Map<String, Object>> activePlans() {
            return plans;
        }

        @Override public Optional<Map<String, Object>> findPlanById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override public Optional<Map<String, Object>> findPlanByCode(String code) {
            return Optional.ofNullable(byCode.get(code));
        }

        @Override public Optional<Map<String, Object>> findSubscriptionByUserId(String userId) {
            return subscription;
        }

        @Override public List<Map<String, Object>> livePasses(String userId, Instant now) {
            return passes;
        }
    }

    private static ActionContext ctx(String userId) {
        return ActionContext.builder().tenantId("t1").userId(userId).build();
    }

    @Test
    @DisplayName("list-plans returns {data:[{code,name,kind,passDurationDays}]} and no price ids")
    void listPlansMatchesTheCompiledInShape() {
        Fake fake = new Fake();
        fake.plans = List.of(map(
            "id", "p1", "code", "PRO", "name", "Pro", "kind", "SUBSCRIPTION",
            "passDurationDays", null,
            "stripePriceId", "price_secret", "stripeProductId", "prod_secret",
            "entitlements", map("watches", 25)));

        ActionResult result = new ListPlansActionHandler(fake).execute(ctx(MEMBER));

        assertThat(result.successful()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data =
                (List<Map<String, Object>>) result.outputData().get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsOnlyKeys("code", "name", "kind", "passDurationDays");
        assertThat(data.get(0)).containsEntry("code", "PRO").containsEntry("kind", "SUBSCRIPTION");
        // Never leaked, even though the row carries them.
        assertThat(data.get(0).toString()).doesNotContain("price_").doesNotContain("prod_");
    }

    @Test
    @DisplayName("me returns {plan, subscription, passes, entitlements}, without processor ids")
    void myBillingMatchesTheCompiledInShape() {
        Fake fake = new Fake();
        Map<String, Object> pro = map("id", "p1", "code", "PRO", "name", "Pro",
            "kind", "SUBSCRIPTION", "entitlements", map("watches", 25));
        fake.byCode.put("PRO", pro);
        fake.byId.put("p1", pro);
        fake.plans = List.of(pro);
        fake.subscription = Optional.of(map(
            "status", "active", "currentPeriodEnd", "2026-09-30T00:00:00Z",
            "cancelAtPeriodEnd", false,
            "stripeSubscriptionId", "sub_secret", "stripeCustomerId", "cus_secret"));
        fake.passes = List.of(map("planId", "p1", "expiresAt", "2026-09-05T00:00:00Z"));

        ActionResult result =
                new MyBillingActionHandler(fake, new EntitlementResolver(fake)).execute(ctx(MEMBER));

        assertThat(result.successful()).isTrue();
        Map<String, Object> body = result.outputData();
        assertThat(body).containsOnlyKeys("plan", "subscription", "passes", "entitlements");

        @SuppressWarnings("unchecked")
        Map<String, Object> subscription = (Map<String, Object>) body.get("subscription");
        assertThat(subscription).containsOnlyKeys("status", "currentPeriodEnd", "cancelAtPeriodEnd");
        assertThat(body.toString()).doesNotContain("sub_secret").doesNotContain("cus_secret");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> passes = (List<Map<String, Object>>) body.get("passes");
        assertThat(passes).hasSize(1);
        assertThat(passes.get(0)).containsOnlyKeys("planCode", "expiresAt");
        assertThat(passes.get(0)).containsEntry("planCode", "PRO");
    }

    @Test
    @DisplayName("me refuses without a calling member rather than answering for nobody")
    void myBillingNeedsAMember() {
        Fake fake = new Fake();

        ActionResult result =
                new MyBillingActionHandler(fake, new EntitlementResolver(fake)).execute(ctx(null));

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("No calling member");
    }
}
