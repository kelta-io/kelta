package io.kelta.modules.billing;

import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns the calling member's plan, subscription status, and merged entitlements.
 *
 * <p>Replaces the compiled-in {@code GET /api/billing/me}: a module cannot contribute a
 * controller, so the member-facing read rides {@code execute_flow} instead.
 *
 * <p>Answers for the <b>calling</b> member only. A caller-supplied member id is deliberately not
 * accepted — that would let any member read another's billing state, and there is no legitimate
 * use for it on this path. Staff needing another member's entitlements read the collections
 * directly, where Cerbos still applies.
 *
 * <p>Processor ids are never returned. A member has no use for them and they are useful to an
 * attacker probing the tenant's Stripe account.
 */
public class ResolveEntitlementsActionHandler implements ActionHandler {

    public static final String KEY = "billing:resolve-entitlements";

    private final EntitlementResolver entitlements;

    public ResolveEntitlementsActionHandler(EntitlementResolver entitlements) {
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

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("planCode", resolved.planCode());
        output.put("subscriptionStatus", resolved.subscriptionStatus());
        output.put("entitlements", resolved.values());
        return ActionResult.success(output);
    }
}
