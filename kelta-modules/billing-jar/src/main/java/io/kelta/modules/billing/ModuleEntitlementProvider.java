package io.kelta.modules.billing;

import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.module.service.MemberEntitlements;

/**
 * Publishes this module's entitlement resolution as the platform's {@link EntitlementProvider}, so
 * compiled-in platform code — alert fanout, watch limits, the quota hook — can ask a module-owned
 * question it previously could only ask a compiled-in service.
 *
 * <p>{@link EntitlementResolver} takes no tenant: it reads through the query engine, which resolves
 * the tenant's schema from {@code TenantContext}. The platform binds that context around the call
 * (it owns the slug lookup a module cannot do), so the {@code tenantId} argument is deliberately
 * not re-applied here — using it to second-guess the ambient context would be the wrong layer.
 */
public class ModuleEntitlementProvider implements EntitlementProvider {

    private final EntitlementResolver resolver;

    public ModuleEntitlementProvider(EntitlementResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public MemberEntitlements resolve(String tenantId, String userId) {
        return resolver.resolve(userId);
    }
}
