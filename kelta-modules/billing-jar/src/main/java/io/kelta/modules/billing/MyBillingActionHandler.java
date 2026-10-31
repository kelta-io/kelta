package io.kelta.modules.billing;

import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calling member's plan, subscription, live passes and entitlements.
 *
 * <p>Deliberately the same shape the compiled-in {@code GET /api/billing/me} returns
 * ({@code {plan, subscription, passes, entitlements}}), so the consumer frontend moves to the module
 * route by changing a path rather than its parsing. {@code billing:resolve-entitlements} is a
 * narrower answer ({@code planCode}/{@code entitlements}/{@code subscriptionStatus}) kept for flows
 * and for the {@code EntitlementProvider} port.
 *
 * <p>Processor ids — customer, subscription, price — are never returned. A member has no use for
 * them and they are useful to an attacker probing the processor account.
 *
 * <p>Passes are filtered at read time by the same rule the resolver applies, so an expired pass
 * stops counting the moment it lapses rather than when a sweep next runs.
 */
public class MyBillingActionHandler implements ActionHandler {

    public static final String KEY = "billing:me";

    private final BillingCollections collections;
    private final EntitlementResolver entitlements;

    public MyBillingActionHandler(BillingCollections collections, EntitlementResolver entitlements) {
        this.collections = collections;
        this.entitlements = entitlements;
    }

    @Override
    public String getActionTypeKey() {
        return KEY;
    }

    @Override
    public ActionResult execute(ActionContext context) {
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ActionResult.failure("No calling member");
        }

        MemberEntitlements resolved = entitlements.resolve(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", planSummary(resolved));
        body.put("subscription", collections.findSubscriptionByUserId(userId)
                .map(MyBillingActionHandler::subscriptionSummary)
                .orElse(null));
        body.put("passes", livePassSummaries(userId));
        body.put("entitlements", resolved.values());
        return ActionResult.success(body);
    }

    private Map<String, Object> planSummary(MemberEntitlements resolved) {
        if (resolved.planCode() == null) {
            return null;
        }
        var plan = collections.findPlanByCode(resolved.planCode());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("code", resolved.planCode());
        // Falls back to the code so a plan row deleted out from under a live subscription still
        // renders something the member recognises rather than a blank.
        summary.put("name", plan.map(p -> asString(p.get("name"))).orElse(resolved.planCode()));
        summary.put("kind", plan.map(p -> asString(p.get("kind"))).orElse(null));
        return summary;
    }

    private static Map<String, Object> subscriptionSummary(Map<String, Object> subscription) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", subscription.get("status"));
        summary.put("currentPeriodEnd", subscription.get("currentPeriodEnd"));
        summary.put("cancelAtPeriodEnd", subscription.get("cancelAtPeriodEnd"));
        return summary;
    }

    private List<Map<String, Object>> livePassSummaries(String userId) {
        List<Map<String, Object>> passes = new ArrayList<>();
        for (Map<String, Object> pass : collections.livePasses(userId, Instant.now())) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object planId = pass.get("planId");
            item.put("planCode", planId == null ? null
                    : collections.findPlanById(asString(planId))
                            .map(p -> asString(p.get("code"))).orElse(null));
            item.put("expiresAt", pass.get("expiresAt"));
            passes.add(item);
        }
        return passes;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
