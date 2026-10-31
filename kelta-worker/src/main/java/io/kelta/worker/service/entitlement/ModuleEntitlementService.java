package io.kelta.worker.service.entitlement;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.worker.service.TenantSlugResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The only {@link EntitlementService}: asks the tenant's installed module, and otherwise answers
 * {@link MemberEntitlements#EMPTY}.
 *
 * <h2>No module ⇒ no entitlements</h2>
 * Billing is a module ({@code kelta-modules/billing}, or the legacy JAR
 * {@code kelta-modules/billing-jar}); the worker keeps no billing tables or plans of its own. A tenant with no module publishing an {@link EntitlementProvider} — or a
 * worker with runtime modules switched off, where no {@link ModuleServiceRegistry} bean exists —
 * resolves {@code EMPTY} for every member. That is exactly what such a tenant got from the former
 * compiled-in service (no subscription and no DEFAULT plan ⇒ no plan code, no status, no values),
 * so the callers' defaults are unchanged:
 * <ul>
 *   <li>{@code WatchController}: no {@code maxActiveWatches} ⇒ uncapped;</li>
 *   <li>{@code WatchController} / {@code AlertDispatchService}: no {@code channels} ⇒ the tenant is
 *       not gating, except billable channels (SMS), which need an affirmative entitlement and are
 *       therefore never sent.</li>
 * </ul>
 *
 * <h2>Failure is EMPTY, never wider</h2>
 * A module that throws, returns null, or cannot be called safely (no tenant slug) resolves
 * {@code EMPTY} with a loud log. Entitlements gate alert delivery and watch creation: a broken
 * module must neither take those paths down nor grant more than a tenant with no billing at all.
 *
 * <h2>Why the tenant is bound here and not in the module</h2>
 * A module implementation reads through the query engine, which resolves the tenant's schema from
 * {@code TenantContext}'s <b>slug</b>, not its id. A module cannot look a slug up —
 * {@link TenantSlugResolver} is platform-side — and a null slug silently reads the public schema
 * instead of the tenant's. So the platform establishes the context around the call, unless the
 * ambient context already is that tenant with a slug (worker request paths arrive pre-bound, and
 * rebinding could replace a good slug with a failed lookup).
 *
 * <h2>No caching</h2>
 * A module cannot consume NATS, so it could not invalidate a cache held here when a webhook on
 * another pod changed a subscription. A stale entitlement — a cancelled member still served, an
 * upgrade not taking effect — is worse than a slower lookup.
 *
 * @since 1.0.0
 */
@Service
public class ModuleEntitlementService implements EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(ModuleEntitlementService.class);

    private final ModuleServiceRegistry moduleServices;
    private final TenantSlugResolver tenantSlugResolver;

    public ModuleEntitlementService(@Nullable ModuleServiceRegistry moduleServices,
                                    @Nullable TenantSlugResolver tenantSlugResolver) {
        this.moduleServices = moduleServices;
        this.tenantSlugResolver = tenantSlugResolver;
    }

    @Override
    public MemberEntitlements resolve(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            return MemberEntitlements.EMPTY;
        }
        Optional<EntitlementProvider> provider = moduleProvider(tenantId);
        if (provider.isEmpty()) {
            return MemberEntitlements.EMPTY;
        }
        try {
            MemberEntitlements resolved = callInTenant(tenantId, provider.get(), userId);
            return resolved == null ? MemberEntitlements.EMPTY : resolved;
        } catch (RuntimeException e) {
            log.error("Module EntitlementProvider failed for tenant {} member {}; "
                    + "resolving no entitlements", tenantId, userId, e);
            return MemberEntitlements.EMPTY;
        }
    }

    /** Resolved per call: a module can be disabled at runtime, and its ClassLoader closed with it. */
    private Optional<EntitlementProvider> moduleProvider(String tenantId) {
        if (moduleServices == null) {
            return Optional.empty();
        }
        return moduleServices.find(tenantId, EntitlementProvider.class);
    }

    private MemberEntitlements callInTenant(String tenantId, EntitlementProvider provider,
                                            String userId) {
        if (tenantId.equals(TenantContext.get()) && TenantContext.getSlug() != null) {
            return provider.resolve(tenantId, userId);
        }
        String slug = tenantSlugResolver == null
                ? null : tenantSlugResolver.resolveSlug(tenantId).orElse(null);
        if (slug == null) {
            // Without a slug the query engine reads the public schema rather than the tenant's,
            // which would answer from the wrong data instead of failing. Refuse the module path.
            log.error("No tenant slug for {}; cannot call the module EntitlementProvider safely, "
                    + "resolving no entitlements", tenantId);
            return MemberEntitlements.EMPTY;
        }
        return TenantContext.callWithTenant(tenantId, slug, () -> provider.resolve(tenantId, userId));
    }
}
