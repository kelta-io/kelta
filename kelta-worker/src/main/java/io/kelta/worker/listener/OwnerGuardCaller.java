package io.kelta.worker.listener;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.router.UserIdResolver;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

/**
 * The caller identity the owner-guard hooks ({@link WatchGuardHook}, {@link WinGuardHook},
 * {@link NoteGuardHook}, {@link FieldReportGuardHook}, {@link PhotoGuardHook},
 * {@link CommentGuardHook}) compare row owners against.
 *
 * <p>Reads the request's {@link CallerContext}, bound once by {@code CallerContextFilter}. The
 * {@code X-User-Id} re-resolution below runs only when no filter bound one — a request the filter
 * deliberately leaves unbound (a connected app's machine identity) or a hook invoked outside the
 * servlet filter chain — and keeps exactly the contract the hooks always had there.
 */
final class OwnerGuardCaller {

    /** Sentinel for "identity present but unresolvable" — deliberately not a valid UUID. */
    static final String REJECTED = " rejected";

    private static final String USER_ID_HEADER = "X-User-Id";

    private OwnerGuardCaller() {
    }

    /**
     * The caller's {@code platform_user} UUID, {@code null} when there is no HTTP request
     * identity (internal tier), or {@link #REJECTED} when an identity is present but does not
     * resolve to a UUID.
     */
    static String resolve(UserIdResolver userIdResolver, String tenantId) {
        Optional<CallerContext> caller = CallerContext.current();
        if (caller.isPresent()) {
            return caller.get().userId();
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String identifier = attrs.getRequest().getHeader(USER_ID_HEADER);
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        // UserIdResolver returns the original identifier on failure rather than null, so the
        // result is re-validated as a UUID — otherwise an unresolvable email would be compared
        // against the owner column as-is.
        String resolved = userIdResolver.resolve(identifier, tenantId);
        try {
            UUID.fromString(resolved);
            return resolved;
        } catch (IllegalArgumentException | NullPointerException e) {
            return REJECTED;
        }
    }
}
