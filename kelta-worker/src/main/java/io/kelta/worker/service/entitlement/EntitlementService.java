package io.kelta.worker.service.entitlement;

import io.kelta.runtime.module.service.MemberEntitlements;
import java.util.List;

/**
 * What a portal member is entitled to, as platform code asks it: watch limits, permitted alert
 * channels and other opaque plan keys.
 *
 * <p>The platform holds no billing data of its own. Entitlements come only from a runtime module
 * that publishes an {@link io.kelta.runtime.module.service.EntitlementProvider} for the tenant
 * (the {@code kelta-billing} module is the reference one). A tenant without such a module has no
 * entitlements: every lookup resolves {@link MemberEntitlements#EMPTY}, and each caller's default
 * applies — see {@link ModuleEntitlementService}.
 *
 * @since 1.0.0
 */
public interface EntitlementService {

    /**
     * Effective entitlements for a member. Never null — an unknown member, a tenant with no billing
     * module, or a module that fails all resolve to {@link MemberEntitlements#EMPTY} rather than an
     * error.
     */
    MemberEntitlements resolve(String tenantId, String userId);

    /** Integer limit, or {@code deflt} when the member has no such entitlement. */
    default int intLimit(String tenantId, String userId, String key, int deflt) {
        return resolve(tenantId, userId).intValue(key, deflt);
    }

    /** Boolean flag, or {@code deflt} when the member has no such entitlement. */
    default boolean boolLimit(String tenantId, String userId, String key, boolean deflt) {
        return resolve(tenantId, userId).boolValue(key, deflt);
    }

    /** String list (e.g. permitted alert channels); empty when unset. */
    default List<String> listLimit(String tenantId, String userId, String key) {
        return resolve(tenantId, userId).listValue(key);
    }
}
