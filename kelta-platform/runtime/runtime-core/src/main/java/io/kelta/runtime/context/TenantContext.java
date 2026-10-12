package io.kelta.runtime.context;

/**
 * Holder for the current tenant ID and slug, bound as {@link ScopedValue}s.
 * <p>
 * Use {@link #runWithTenant} or {@link #callWithTenant} to establish a structured tenant
 * scope that works correctly with virtual threads; on a worker request path the scope is
 * already bound by {@code TenantContextFilter}, so just read {@link #get()}.
 * <p>
 * The ThreadLocal setters ({@code set}/{@code setSlug}/{@code clear}) were removed
 * 2026-10-11: {@link #get()} preferred a bound ScopedValue over the ThreadLocal, so on a
 * request path {@code set(...)} was a silent no-op, and on pooled threads a value that was
 * never cleared leaked into the next task. {@link #runWithTenantIfUnbound} keeps the old
 * "an outer binding wins" precedence for the few shared paths that need it.
 * <p>
 * <b>Cross-tenant work:</b> there is no "platform scope" helper, and binding a sentinel
 * tenant id is the one thing that will not work. The RLS bypass is keyed on an
 * <em>empty</em> setting — every table's policy reads
 * {@code USING (current_setting('app.current_tenant_id', true) = '')} — and
 * {@code TenantAwareDataSourceConfig} issues {@code SET app.current_tenant_id = ''}
 * precisely when no tenant is bound. So the two supported idioms are:
 * <ul>
 *   <li><b>Preferred:</b> {@link #callWithTenant}/{@link #runWithTenant} with a concrete
 *       tenant id, once per tenant. {@code SandboxProvisioningService} and
 *       {@code MetadataPromotionService} both do this deliberately.</li>
 *   <li><b>Genuine platform paths</b> (Flyway, bootstrap) simply leave the context
 *       <em>unbound</em>, which is what selects {@code admin_bypass}.</li>
 * </ul>
 * A {@code runAsPlatform}/{@code callAsPlatform} pair used to live here and bound a
 * {@code "__platform__"} sentinel, documented as matching a {@code platform_bypass} policy.
 * That policy was never migrated, and the sentinel matches neither {@code admin_bypass}
 * (which needs the empty string) nor {@code tenant_isolation} (no tenant owns that id), so
 * every RLS-scoped read returned zero rows and every write was silently dropped. Removed
 * 2026-09-09 with no production callers; see {@code concerns.md}. Do not reintroduce a
 * sentinel-binding helper without first migrating a policy that actually matches it.
 */
public final class TenantContext {

    // ScopedValue-based (preferred, virtual-thread safe)
    public static final ScopedValue<String> CURRENT_TENANT = ScopedValue.newInstance();
    public static final ScopedValue<String> CURRENT_TENANT_SLUG = ScopedValue.newInstance();

    private TenantContext() {}

    /**
     * Returns the bound tenant ID, or {@code null} when none is bound.
     */
    public static String get() {
        return CURRENT_TENANT.isBound() ? CURRENT_TENANT.get() : null;
    }

    /**
     * Returns the bound tenant slug, or {@code null} when none is bound.
     */
    public static String getSlug() {
        return CURRENT_TENANT_SLUG.isBound() ? CURRENT_TENANT_SLUG.get() : null;
    }

    // ── Modern ScopedValue API (preferred) ──────────────────────────────

    /**
     * Executes the given operation within a tenant scope (ID only).
     */
    public static void runWithTenant(String tenantId, Runnable operation) {
        ScopedValue.where(CURRENT_TENANT, tenantId).run(operation);
    }

    /**
     * Executes the given operation within a tenant scope with both ID and slug.
     */
    public static void runWithTenant(String tenantId, String tenantSlug, Runnable operation) {
        ScopedValue.where(CURRENT_TENANT, tenantId)
                   .where(CURRENT_TENANT_SLUG, tenantSlug)
                   .run(operation);
    }

    /**
     * Executes the given operation within a tenant scope and returns a result.
     */
    public static <T> T callWithTenant(String tenantId, ScopedValue.CallableOp<T, RuntimeException> operation) {
        return ScopedValue.where(CURRENT_TENANT, tenantId).call(operation);
    }

    /**
     * Executes the given operation within a tenant scope with both ID and slug,
     * and returns a result.
     */
    public static <T> T callWithTenant(String tenantId, String tenantSlug, ScopedValue.CallableOp<T, RuntimeException> operation) {
        return ScopedValue.where(CURRENT_TENANT, tenantId)
                          .where(CURRENT_TENANT_SLUG, tenantSlug)
                          .call(operation);
    }

    // ── Caller-precedence helpers ───────────────────────────────────────

    /**
     * Runs {@code operation} with {@code tenantId}/{@code tenantSlug} bound <em>only where the
     * caller has not already bound a value</em>: an outer binding (the worker's request filter, a
     * NATS listener's scope) wins, and each of the two values is filled independently.
     *
     * <p>This is the precedence the removed ThreadLocal setters had — {@code get()} always
     * preferred a bound {@link ScopedValue} over the ThreadLocal. It exists for shared code that
     * runs both on request paths, where the tenant is pre-bound, and on background paths, where it
     * is not, and must not override the request's binding. Code that is handed an explicit tenant
     * to act for should use {@link #runWithTenant}/{@link #callWithTenant}, which shadow an outer
     * binding. A {@code null} or blank argument binds nothing.
     */
    public static void runWithTenantIfUnbound(String tenantId, String tenantSlug, Runnable operation) {
        callWithTenantIfUnbound(tenantId, tenantSlug, () -> {
            operation.run();
            return null;
        });
    }

    /**
     * Value-returning form of {@link #runWithTenantIfUnbound(String, String, Runnable)}.
     */
    public static <T> T callWithTenantIfUnbound(String tenantId, String tenantSlug,
                                                ScopedValue.CallableOp<T, RuntimeException> operation) {
        boolean bindTenant = !CURRENT_TENANT.isBound() && tenantId != null && !tenantId.isBlank();
        boolean bindSlug = !CURRENT_TENANT_SLUG.isBound() && tenantSlug != null && !tenantSlug.isBlank();
        if (bindTenant && bindSlug) {
            return ScopedValue.where(CURRENT_TENANT, tenantId)
                              .where(CURRENT_TENANT_SLUG, tenantSlug)
                              .call(operation);
        }
        if (bindTenant) {
            return ScopedValue.where(CURRENT_TENANT, tenantId).call(operation);
        }
        if (bindSlug) {
            return ScopedValue.where(CURRENT_TENANT_SLUG, tenantSlug).call(operation);
        }
        return operation.call();
    }
}
