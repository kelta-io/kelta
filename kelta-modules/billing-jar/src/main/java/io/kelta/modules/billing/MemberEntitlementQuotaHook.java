package io.kelta.modules.billing;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces per-member record quotas from {@code billing_entitlement_rules} rows — "records in
 * collection X are capped by entitlement key Y" — so a tenant caps a new collection by adding a
 * row, not by shipping code.
 *
 * <p><b>This is a wildcard hook: it runs on every record create for the installing tenant.</b> The
 * fast path therefore has to cost nothing — no rules for the collection ends the call before any
 * query, and that is the overwhelmingly common case.
 *
 * <p><b>Counting.</b> The member's existing rows are counted through {@link QueryEngine#aggregate},
 * which validates every filter field against the collection definition before building SQL and
 * binds all values as parameters. That is what makes the tenant-authored {@code countFilter} JSON
 * safe: an unknown field name is rejected rather than interpolated.
 *
 * <p><b>Fail-open, deliberately.</b> A missing collection definition, an unparseable filter, or a
 * failed count allows the write and logs. A billing-infrastructure glitch must not block a
 * tenant's data entry. This is the opposite of the platform's *guard* hooks, which fail closed —
 * those protect other users' data, this one protects revenue.
 */
public class MemberEntitlementQuotaHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(MemberEntitlementQuotaHook.class);

    /** The field identifying who owns a record; stamped by the router from the gateway identity. */
    static final String OWNER_FIELD = "createdBy";

    static final String APPLIES_TO_PORTAL = "PORTAL";

    private static final String COUNT_ALIAS = "memberRecordCount";

    private final BillingCollections collections;
    private final EntitlementResolver entitlements;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;

    /** One warning per process for the PORTAL-scope limitation below, not one per write. */
    private final AtomicBoolean warnedPortalScope = new AtomicBoolean(false);

    public MemberEntitlementQuotaHook(BillingCollections collections,
                                      EntitlementResolver entitlements,
                                      CollectionRegistry collectionRegistry,
                                      QueryEngine queryEngine) {
        this.collections = collections;
        this.entitlements = entitlements;
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
    }

    @Override
    public String getCollectionName() {
        return BeforeSaveHookRegistry.WILDCARD;
    }

    /** Early within the wildcard group, so a rejected write does no later side work. */
    @Override
    public int getOrder() {
        return -90;
    }

    @Override
    public BeforeSaveResult beforeCreate(String collectionName, Map<String, Object> record,
                                         String tenantId) {
        if (collectionName == null || record == null) {
            return BeforeSaveResult.ok();
        }
        // Never let a quota rule cap the module's own bookkeeping.
        if (BillingCollections.isModuleCollection(collectionName)) {
            return BeforeSaveResult.ok();
        }

        List<Map<String, Object>> rules = collections.activeRulesForCollection(collectionName);
        if (rules.isEmpty()) {
            return BeforeSaveResult.ok(); // fast path — no query, no allocation
        }

        Object owner = record.get(OWNER_FIELD);
        if (owner == null || owner.toString().isBlank()) {
            // No actor (flow, scheduler, import). Quotas are a per-member concept, so there is
            // nothing to enforce against.
            return BeforeSaveResult.ok();
        }
        String userId = owner.toString();

        for (Map<String, Object> rule : rules) {
            if (APPLIES_TO_PORTAL.equalsIgnoreCase(str(rule.get("appliesTo")))) {
                // The compiled-in hook distinguishes portal members from internal staff by the
                // gateway's X-User-Type header, read through Spring's RequestContextHolder.
                // Neither jakarta.servlet nor org.springframework is on a module's classpath, so
                // a module CANNOT tell the two apart — and enforcing a member cap against staff
                // would block legitimate internal work. Skipping is the fail-open choice
                // consistent with the rest of this hook. Use appliesTo=ALL for rules that must
                // hold for every actor.
                if (warnedPortalScope.compareAndSet(false, true)) {
                    log.warn("Billing module: skipping PORTAL-scoped quota rules — a module cannot "
                            + "read the actor tier. Use appliesTo=ALL to enforce for every actor.");
                }
                continue;
            }
            BeforeSaveResult result = enforce(rule, userId, collectionName);
            if (!result.isSuccess()) {
                return result;
            }
        }
        return BeforeSaveResult.ok();
    }

    private BeforeSaveResult enforce(Map<String, Object> rule, String userId,
                                     String collectionName) {
        String limitKey = str(rule.get("limitKey"));
        if (limitKey == null) {
            return BeforeSaveResult.ok();
        }

        Object raw = entitlements.resolve(userId).values().get(limitKey);
        if (raw == null) {
            // No such entitlement key for this member ⇒ uncapped. A tenant wanting a hard cap
            // must put the key on its DEFAULT plan.
            return BeforeSaveResult.ok();
        }
        if (raw instanceof Boolean included) {
            // A boolean gate: "this feature is in the plan". Passes OR-merge, so one granting
            // pass lifts a DEFAULT false.
            return included ? BeforeSaveResult.ok() : reject(rule, 0, 0);
        }
        Long parsed = numericLimit(raw);
        if (parsed == null) {
            log.warn("Quota rule {} resolved to unusable value of type {} — allowing",
                    limitKey, raw.getClass().getName());
            return BeforeSaveResult.ok();
        }
        long limit = parsed;
        if (limit < 0) {
            return BeforeSaveResult.ok(); // negative = unlimited, no count query
        }
        if (limit == 0) {
            return reject(rule, 0, 0);
        }

        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            log.warn("Quota rule {} references collection {} not in the registry — allowing",
                    limitKey, collectionName);
            return BeforeSaveResult.ok();
        }
        // hasQueryableField, NOT hasField: createdBy is a system audit field that every record
        // carries and the storage layer can filter on, but it is not in the collection's declared
        // fields. hasField therefore returns false for EVERY collection, which made this guard
        // fail open on every positive limit (issue #1384) — only limit <= 0 ever rejected,
        // because that branch short-circuits above.
        if (!definition.hasQueryableField(OWNER_FIELD)) {
            log.warn("Collection {} has no {} field — cannot scope quota {} to a member, allowing",
                    collectionName, OWNER_FIELD, limitKey);
            return BeforeSaveResult.ok();
        }

        List<FilterCondition> filters = new ArrayList<>();
        filters.add(new FilterCondition(OWNER_FIELD, FilterOperator.EQ, userId));
        if (!appendCountFilter(rule, definition, filters)) {
            return BeforeSaveResult.ok(); // unusable filter — logged, fail open
        }

        long used;
        try {
            Object count = queryEngine
                    .aggregate(definition, filters,
                            List.of(new AggregationSpec("COUNT", null, COUNT_ALIAS)))
                    .get(COUNT_ALIAS);
            used = count instanceof Number n ? n.longValue() : 0L;
        } catch (RuntimeException e) {
            log.warn("Quota count failed for {} on {} — allowing: {}",
                    limitKey, collectionName, e.getMessage());
            return BeforeSaveResult.ok();
        }

        return used >= limit ? reject(rule, used, limit) : BeforeSaveResult.ok();
    }

    /**
     * Appends the rule's {@code countFilter} equality predicates.
     *
     * <p>Field names are checked against the collection definition here <b>and</b> again inside
     * {@code aggregate}; values bind as parameters, never interpolated. Returns false when the
     * filter is unusable so the caller fails open rather than silently enforcing a narrower count
     * than the tenant intended.
     */
    private boolean appendCountFilter(Map<String, Object> rule, CollectionDefinition definition,
                                      List<FilterCondition> filters) {
        Object raw = rule.get("countFilter");
        if (raw == null) {
            return true;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            log.warn("Quota rule {} countFilter is not an object — allowing", rule.get("limitKey"));
            return false;
        }
        if (map.isEmpty()) {
            return true;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String field = String.valueOf(entry.getKey());
            // Same reason as the owner guard above: a countFilter may legitimately name an audit
            // field, and hasField would reject it as unknown.
            if (!definition.hasQueryableField(field)) {
                log.warn("Quota rule {} countFilter names unknown field '{}' on {} — allowing",
                        rule.get("limitKey"), field, definition.name());
                return false;
            }
            filters.add(new FilterCondition(field, FilterOperator.EQ, entry.getValue()));
        }
        return true;
    }

    /** A Number, or a numeric string parsed the way {@code MemberEntitlements.intValue} does. */
    private static Long numericLimit(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return (long) Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BeforeSaveResult reject(Map<String, Object> rule, long used, long limit) {
        log.info("Rejecting create — member at quota {} ({}/{})", rule.get("limitKey"), used, limit);
        String message = str(rule.get("message"));
        if (message == null) {
            message = "You have reached your plan limit (" + used + "/" + limit
                    + "). Upgrade your plan to add more.";
        }
        return BeforeSaveResult.error(null, message);
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
