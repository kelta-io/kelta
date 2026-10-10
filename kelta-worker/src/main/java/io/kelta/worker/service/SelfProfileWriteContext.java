package io.kelta.worker.service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Marks the current thread as executing a member's write to <em>their own</em> {@code users}
 * row, already validated by {@code MyProfileController} ({@code PATCH /api/me/profile}).
 *
 * <p>The {@code IdentityCollectionGuardHook} rejects {@code users} writes from request threads
 * whose profile lacks {@code MANAGE_USERS}. This scoped value is a deliberately narrow exception:
 * the guard admits an <b>update</b> only when its target id equals {@link Grant#userId()} and
 * every changed field is in {@link Grant#allowedFields()}. Unlike {@code DelegatedWriteContext}
 * it is not a blanket pass — a write to another id or another field is still blocked.
 *
 * <p>{@link ScopedValue}-based (virtual-thread safe), mirroring {@code TenantContext}.
 */
public final class SelfProfileWriteContext {

    /** The caller's own user id and the fields they may change on it. */
    public record Grant(String userId, Set<String> allowedFields) {
        public Grant {
            Objects.requireNonNull(userId, "userId");
            allowedFields = Set.copyOf(allowedFields);
        }
    }

    private static final ScopedValue<Grant> GRANT = ScopedValue.newInstance();

    private SelfProfileWriteContext() {
    }

    /** The bound self-profile grant, or empty outside a self-profile write. */
    public static Optional<Grant> current() {
        return GRANT.isBound() ? Optional.of(GRANT.get()) : Optional.empty();
    }

    /** Calls {@code operation} with a self-profile grant for {@code userId} bound. */
    public static <T> T callAuthorized(String userId, Set<String> allowedFields,
                                       Callable<T> operation) {
        try {
            return ScopedValue.where(GRANT, new Grant(userId, allowedFields)).call(operation::call);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Self-profile write failed", e);
        }
    }
}
