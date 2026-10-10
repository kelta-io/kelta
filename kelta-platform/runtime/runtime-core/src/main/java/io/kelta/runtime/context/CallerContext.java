package io.kelta.runtime.context;

import io.kelta.runtime.model.OwnerScope;

import java.util.Optional;

/**
 * The resolved identity of the caller behind the current request: their canonical
 * {@code platform_user.id} UUID, their user type, and whether their profile grants the
 * {@code VIEW_ALL_DATA} / {@code MODIFY_ALL_DATA} admin bypasses.
 *
 * <p>Held in a {@link ScopedValue} the way {@link TenantContext} is, so it is virtual-thread
 * safe and bounded by the request. The worker's {@code CallerContextFilter} binds it once per
 * request from the gateway-stamped {@code X-User-Id} / {@code X-User-Type} /
 * {@code X-User-Profile-Id} headers; everything downstream reads {@link #current()} instead of
 * re-resolving {@code X-User-Id}.
 *
 * <p><b>Empty means internal tier.</b> Flows, NATS listeners, schedulers and provisioning run
 * with no caller bound, and are never owner-scoped.
 *
 * @param userId    the caller's {@code platform_user.id} (a UUID)
 * @param userType  {@link UserType#INTERNAL} staff or {@link UserType#PORTAL} member
 * @param viewAll   the caller's profile grants {@code VIEW_ALL_DATA} (always false for PORTAL)
 * @param modifyAll the caller's profile grants {@code MODIFY_ALL_DATA} (always false for PORTAL)
 */
public record CallerContext(String userId, UserType userType, boolean viewAll, boolean modifyAll) {

    public static final ScopedValue<CallerContext> CURRENT = ScopedValue.newInstance();

    public enum UserType {
        INTERNAL, PORTAL;

        /** {@code PORTAL} (case-insensitive) is a member; anything else, including null, is staff. */
        public static UserType parse(String value) {
            return value != null && PORTAL.name().equalsIgnoreCase(value.trim()) ? PORTAL : INTERNAL;
        }
    }

    /** Whether the owner check is for reading rows or for changing them. */
    public enum Access { READ, WRITE }

    public CallerContext {
        if (userType == null) {
            userType = UserType.INTERNAL;
        }
    }

    public boolean isPortal() {
        return userType == UserType.PORTAL;
    }

    /** The bound caller, or empty on the internal tier. */
    public static Optional<CallerContext> current() {
        return CURRENT.isBound() ? Optional.ofNullable(CURRENT.get()) : Optional.empty();
    }

    /** Runs {@code work} as {@code caller} — for tests and deliberate internal impersonation. */
    public static <T> T callAs(CallerContext caller, ScopedValue.CallableOp<T, RuntimeException> work) {
        return ScopedValue.where(CURRENT, caller).call(work);
    }

    /** Runs {@code work} as {@code caller} — for tests and deliberate internal impersonation. */
    public static void runAs(CallerContext caller, Runnable work) {
        ScopedValue.where(CURRENT, caller).run(work);
    }

    /**
     * Whether this caller is limited to their own rows on a collection with the given owner
     * scope. PORTAL callers never bypass; under {@link OwnerScope#ALL} an INTERNAL caller
     * bypasses with {@code VIEW_ALL_DATA} for reads and {@code MODIFY_ALL_DATA} for writes.
     */
    public boolean ownerScoped(OwnerScope scope, Access access) {
        if (scope == null) {
            return false;
        }
        return switch (scope) {
            case NONE -> false;
            case PORTAL -> isPortal();
            case ALL -> isPortal() || !(access == Access.WRITE ? modifyAll : viewAll);
        };
    }
}
