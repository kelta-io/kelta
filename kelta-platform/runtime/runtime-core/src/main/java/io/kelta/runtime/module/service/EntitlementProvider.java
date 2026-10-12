package io.kelta.runtime.module.service;

/**
 * Resolves what a member is entitled to — the platform-defined port a billing module implements so
 * platform code can stop depending on a compiled-in billing service.
 *
 * <p>Published by a module through {@code KeltaModule.getServices()} and resolved per call from
 * {@link ModuleServiceRegistry}. It is the platform's only source of entitlements: when no
 * installed module publishes one, the platform resolves {@link MemberEntitlements#EMPTY} for every
 * member, and each caller applies its own default for an absent key.
 *
 * <p><b>The caller binds the tenant.</b> {@code tenantId} is passed explicitly and the platform
 * establishes {@code TenantContext} (id <i>and</i> slug) around the call, because an implementation
 * that reads its data through the query engine resolves the tenant's schema from the
 * <b>slug</b> — a module has no way to look one up, and a null slug silently reads the public
 * schema instead of the tenant's.
 *
 * <p>Implementations must be safe to call concurrently and must not cache across tenants.
 *
 * @since 1.0.0
 */
public interface EntitlementProvider {

    /**
     * Effective entitlements for a member.
     *
     * <p>Must never return null and must never throw for an unknown member: an unrecognised user, a
     * tenant with no plans, or a lapsed subscription all resolve to {@link MemberEntitlements#EMPTY}
     * so the caller's fallback is always the restrictive one.
     */
    MemberEntitlements resolve(String tenantId, String userId);
}
