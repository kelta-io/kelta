package io.kelta.runtime.context;

import java.util.Optional;

/**
 * The user a record write is done by — the {@code userId} of the record-changed event the write
 * publishes, and so the initiator of every record-triggered flow it starts
 * ({@code flow_execution.started_by}, the {@code act} claim sent to external modules,
 * {@code $.context.userId}). Never the record's stored {@code createdBy} or last
 * {@code updatedBy}: an update or delete by user B on a record user A created is B's.
 *
 * <p>{@link #current()} resolves, innermost first:
 * <ol>
 *   <li>a {@link #CURRENT} binding — a flow step binds its execution actor here
 *       ({@code FlowEngine}), a background job its owner ({@code BulkJobProcessorService}), and a
 *       write made on someone's behalf under a re-bound {@link CallerContext} binds the real actor
 *       here so the re-binding does not move the attribution;</li>
 *   <li>the request {@link CallerContext} — the authenticated caller.</li>
 * </ol>
 * Empty when neither is bound: a platform path (bootstrap, package import, a NATS listener), whose
 * writes are {@code "system"}'s. Nothing a written record carries stands in — an unbound writer's
 * {@code createdBy}/{@code updatedBy} may come from its own input. A platform path that writes
 * for a user binds it here.
 *
 * <p>Kept apart from {@link CallerContext} on purpose. Binding a flow actor as the caller would
 * owner-scope the flow's reads and writes and subject them to the caller-only write rules;
 * this binding changes attribution only, never authorization.
 */
public final class ActingUser {

    /** The acting user's {@code platform_user.id}, when bound explicitly. */
    public static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    private ActingUser() {
    }

    /** The acting user: the explicit binding, else the request caller, else empty. */
    public static Optional<String> current() {
        if (CURRENT.isBound()) {
            String bound = CURRENT.get();
            if (bound != null && !bound.isBlank()) {
                return Optional.of(bound);
            }
        }
        return CallerContext.current().map(CallerContext::userId).filter(id -> !id.isBlank());
    }

    /**
     * Runs {@code work} with {@code userId} as the acting user. A null or blank {@code userId}
     * binds nothing, so the enclosing attribution (if any) still applies.
     */
    public static <T> T callAs(String userId, ScopedValue.CallableOp<T, RuntimeException> work) {
        if (userId == null || userId.isBlank()) {
            return work.call();
        }
        return ScopedValue.where(CURRENT, userId).call(work);
    }

    /**
     * Runs {@code work} with {@code userId} as the acting user. A null or blank {@code userId}
     * binds nothing, so the enclosing attribution (if any) still applies.
     */
    public static void runAs(String userId, Runnable work) {
        if (userId == null || userId.isBlank()) {
            work.run();
            return;
        }
        ScopedValue.where(CURRENT, userId).run(work);
    }
}
