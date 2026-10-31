package io.kelta.modules.billing;

import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Flips due one-time passes from ACTIVE to EXPIRED.
 *
 * <p><b>An action handler, not a scheduled job — a module has no scheduler.</b> The compiled-in
 * version is a Spring {@code @Scheduled} bean, but a runtime-loaded module is not a Spring bean and
 * cannot register one. Exposing the work as an action means a tenant schedules it the platform's
 * own way: a scheduled flow calling {@code billing:expire-passes}. That is strictly more
 * configurable than a hardcoded cron, and it needs no new platform capability.
 *
 * <p><b>This is a tidier, not a gate.</b> {@link EntitlementResolver} already ignores an expired
 * pass at read time regardless of its stored status, so a member is never over-entitled by a sweep
 * that is late, paused, or never scheduled. What it buys is an accurate stored status for admin
 * screens and reports.
 *
 * <p>Not destructive: it only advances a status, so unlike the platform's retention purge it needs
 * no dry-run gate.
 */
public class ExpirePassesActionHandler implements ActionHandler {

    public static final String KEY = "billing:expire-passes";

    private static final Logger log = LoggerFactory.getLogger(ExpirePassesActionHandler.class);

    /** Bounds one invocation so a large backlog cannot hold a flow step open indefinitely. */
    static final int DEFAULT_BATCH_LIMIT = 500;

    private final BillingCollections collections;

    public ExpirePassesActionHandler(BillingCollections collections) {
        this.collections = collections;
    }

    @Override
    public String getActionTypeKey() {
        return KEY;
    }

    @Override
    public ActionResult execute(ActionContext context) {
        Map<String, Object> input = ActionInputs.of(context);
        int limit = positiveInt(input.get("limit"), DEFAULT_BATCH_LIMIT);

        int expired;
        try {
            expired = collections.expireDuePasses(Instant.now(), limit).size();
        } catch (RuntimeException e) {
            log.error("Pass expiry sweep failed for tenant {}: {}",
                    context.tenantId(), e.getMessage(), e);
            return ActionResult.failure("Pass expiry sweep failed");
        }

        if (expired > 0) {
            log.info("Expired {} billing pass(es) for tenant {}", expired, context.tenantId());
        }
        // `expired` lets a flow branch on whether there may be more to do — a full batch means the
        // caller should run again rather than wait for the next schedule.
        return ActionResult.success(Map.of(
                "expired", expired,
                "batchFull", expired >= limit));
    }

    private static int positiveInt(Object raw, int deflt) {
        if (raw instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                int parsed = Integer.parseInt(s.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return deflt;
    }
}
