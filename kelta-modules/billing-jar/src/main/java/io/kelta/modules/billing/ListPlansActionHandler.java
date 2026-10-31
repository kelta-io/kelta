package io.kelta.modules.billing;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant's purchasable plans — what a pricing page renders.
 *
 * <p>Deliberately the same shape the compiled-in {@code GET /api/billing/plans} returns
 * ({@code {"data":[{code,name,kind,passDurationDays}]}}), so the consumer frontend moves to the
 * module route by changing a path rather than its parsing.
 *
 * <p>Unauthenticated in effect: plans are public product information, and this returns no member
 * data. Processor price ids are <b>not</b> included — a pricing page has no use for them, and they
 * are useful to an attacker probing the processor account.
 */
public class ListPlansActionHandler implements ActionHandler {

    public static final String KEY = "billing:list-plans";

    private static final List<String> PUBLIC_FIELDS =
            List.of("code", "name", "kind", "passDurationDays");

    private final BillingCollections collections;

    public ListPlansActionHandler(BillingCollections collections) {
        this.collections = collections;
    }

    @Override
    public String getActionTypeKey() {
        return KEY;
    }

    @Override
    public ActionResult execute(ActionContext context) {
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Map<String, Object> row : collections.activePlans()) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String field : PUBLIC_FIELDS) {
                item.put(field, row.get(field));
            }
            plans.add(item);
        }
        return ActionResult.success(Map.of("data", plans));
    }
}
