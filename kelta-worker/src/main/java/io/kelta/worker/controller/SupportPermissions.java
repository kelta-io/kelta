package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * Profile system-permission checks shared by the self-scoped support views
 * ({@link WatchController}, {@link WinController}, {@link AlertLatencyController}).
 *
 * <p>Reads and writes are kept apart: {@link #READ} admits the read-everything
 * {@code VIEW_ALL_DATA} alongside {@code MANAGE_DATA}, so a read-only metrics principal can see
 * tenant-wide views; {@link #WRITE} stays {@code MANAGE_DATA} alone, so nothing that mutates or
 * acts on another member's behalf ever consults {@code VIEW_ALL_DATA}.
 *
 * <p>{@code watches} and {@code wins} are owner-scoped ({@code ownerScope=ALL}), so the generic
 * owner guard and read predicate see the bound {@link CallerContext}. Once a controller has made
 * its support decision, {@link #readAll} and {@link #writeAs} re-bind the caller so those
 * generic checks agree with it.
 */
final class SupportPermissions {

    static final String MANAGE_DATA = "MANAGE_DATA";
    static final String VIEW_ALL_DATA = "VIEW_ALL_DATA";
    /** Tenant-wide read-only views (support-mode list/get, alert latency summary). */
    static final Set<String> READ = Set.of(MANAGE_DATA, VIEW_ALL_DATA);
    /** Acting on a named member's records (create/update/delete on their behalf). */
    static final Set<String> WRITE = Set.of(MANAGE_DATA);

    private SupportPermissions() {
    }

    /** True when the caller is a portal (member) actor, per the gateway-stamped identity. */
    static boolean isPortal(HttpServletRequest request) {
        String userType = request.getHeader("X-User-Type");
        return userType != null && "PORTAL".equalsIgnoreCase(userType);
    }

    /**
     * True when the caller is internal staff whose profile grants any of {@code permissions}.
     * A PORTAL actor short-circuits to false before any lookup — a member is never support
     * staff, whatever their profile happens to grant.
     */
    static boolean internalHoldsAny(HttpServletRequest request,
                                    CerbosPermissionResolver permissionResolver,
                                    BootstrapRepository bootstrapRepository,
                                    Set<String> permissions) {
        if (isPortal(request)) {
            return false;
        }
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> permissions.contains(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }

    /**
     * Runs a tenant-wide support read the controller has already authorized with {@link #READ}.
     * {@code MANAGE_DATA} alone does not lift the owner-scoped read predicate (only
     * {@code VIEW_ALL_DATA} does), so the read runs with the {@code viewAll} bypass set.
     */
    static <T> T readAll(ScopedValue.CallableOp<T, RuntimeException> read) {
        CallerContext caller = CallerContext.current().orElse(null);
        if (caller == null || caller.viewAll()) {
            return read.call();
        }
        return CallerContext.callAs(
                new CallerContext(caller.userId(), caller.userType(), true, caller.modifyAll()), read);
    }

    /**
     * Runs a write on {@code subject}'s rows. When a support actor (already checked for
     * {@link #WRITE}) names another member, the write runs as that member, so the owner guard
     * stamps and compares the member as the owner. The actor's own bypasses are not carried over.
     */
    static <T> T writeAs(String subject, ScopedValue.CallableOp<T, RuntimeException> write) {
        CallerContext caller = CallerContext.current().orElse(null);
        if (caller == null || subject.equals(caller.userId())) {
            return write.call();
        }
        return CallerContext.callAs(new CallerContext(subject, UserType.PORTAL, false, false), write);
    }
}
