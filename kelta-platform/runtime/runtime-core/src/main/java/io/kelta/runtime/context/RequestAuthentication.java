package io.kelta.runtime.context;

/**
 * The authentication state of the HTTP request the current code runs under, mirroring
 * {@link TenantContext}. Bound per request by the worker's {@code TenantContextFilter} (and
 * narrowed to {@link #PLATFORM_SCOPED} by {@code TenantManagementScopeFilter}); unbound on
 * scheduler, flow, NATS-consumer, bootstrap and Flyway paths, which have no HTTP origin.
 *
 * <p>{@code PhysicalTableStorageAdapter} reads it when a tenant-scoped system collection is read
 * with no tenant bound: an {@link #ANONYMOUS} request fails closed (no rows), a
 * {@link #PLATFORM_SCOPED} one reads across tenants on purpose, and an unbound context keeps the
 * platform-path behaviour. Binding it is the explicit opt-in to fail-closed reads.
 */
public enum RequestAuthentication {

    /** No authenticated principal — the gateway forwarded no {@code X-User-Id}. */
    ANONYMOUS,

    /** An authenticated principal, scoped to whatever tenant the request resolved. */
    AUTHENTICATED,

    /** An authenticated principal whose request was deliberately unbound from any tenant. */
    PLATFORM_SCOPED;

    public static final ScopedValue<RequestAuthentication> CURRENT = ScopedValue.newInstance();

    /** The current request's authentication state, or {@code null} off a request thread. */
    public static RequestAuthentication current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }
}
