package io.kelta.runtime.context;

/**
 * Holder for the current tenant ID and slug.
 * <p>
 * Supports both the modern {@link ScopedValue}-based API (preferred) and the
 * legacy {@link ThreadLocal}-based API for backward compatibility during migration.
 * <p>
 * <b>Preferred usage:</b> Use {@link #runWithTenant} or {@link #callWithTenant} to
 * establish a structured tenant scope that works correctly with virtual threads.
 * <p>
 * <b>Legacy usage:</b> {@link #set}/{@link #clear} still work via ThreadLocal
 * but are deprecated and will be removed in a future release.
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

    // ThreadLocal fallback (deprecated, for backward compatibility)
    private static final ThreadLocal<String> LEGACY_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> LEGACY_TENANT_SLUG = new ThreadLocal<>();

    private TenantContext() {}

    /**
     * Returns the current tenant ID from either ScopedValue or ThreadLocal.
     */
    public static String get() {
        return CURRENT_TENANT.isBound() ? CURRENT_TENANT.get() : LEGACY_TENANT.get();
    }

    /**
     * Returns the current tenant slug from either ScopedValue or ThreadLocal.
     */
    public static String getSlug() {
        return CURRENT_TENANT_SLUG.isBound() ? CURRENT_TENANT_SLUG.get() : LEGACY_TENANT_SLUG.get();
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

    // ── Legacy ThreadLocal API (deprecated) ─────────────────────────────

    /**
     * @deprecated Use {@link #runWithTenant(String, Runnable)} instead.
     */
    @Deprecated(forRemoval = true)
    public static void set(String tenantId) {
        LEGACY_TENANT.set(tenantId);
    }

    /**
     * @deprecated Use {@link #runWithTenant(String, String, Runnable)} instead.
     */
    @Deprecated(forRemoval = true)
    public static void setSlug(String slug) {
        LEGACY_TENANT_SLUG.set(slug);
    }

    /**
     * @deprecated No longer needed with ScopedValue — scope is automatically bounded.
     */
    @Deprecated(forRemoval = true)
    public static void clear() {
        LEGACY_TENANT.remove();
        LEGACY_TENANT_SLUG.remove();
    }
}
