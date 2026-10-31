package io.kelta.runtime.workflow;

/**
 * How a flow run's user ({@link ActionContext#userId()}) was chosen, in the precedence the worker's
 * {@code FlowActorResolver} applies.
 *
 * <p>Only {@link #INITIATOR} is a person who acted: they started the run, or made the record change
 * that triggered it. {@link #RUN_AS} and {@link #OWNER} are fallback identities for a run nobody
 * started (a schedule, a webhook, a NATS trigger, a record change with no acting user). Code that
 * acts <em>for</em> the run's user, rather than only attributing work to them, must tell the two
 * apart: an external module receives the source as {@code act.source} and refuses a member-only
 * action for a fallback identity.
 *
 * <p>Recorded with the execution ({@code flow_execution.started_by_source}), so a resumed or
 * retried run keeps it. {@code null} where it is unknown: a run started before it was recorded, or
 * an action that is not a flow step.
 *
 * @since 1.0.0
 */
public enum ActorSource {

    /** The user who started the run: a manual run's caller, or the record change's acting user. */
    INITIATOR,

    /** The flow's configured run-as user ({@code flow.run_as_user_id}): no person started the run. */
    RUN_AS,

    /** The flow's owner ({@code flow.created_by}): no person started the run and no run-as user is set. */
    OWNER;

    /** True for an identity the run fell back to, not a person who acted ({@link #RUN_AS}, {@link #OWNER}). */
    public boolean isFallback() {
        return this != INITIATOR;
    }

    /** The source stored as {@code name}, or {@code null} for a blank or unknown name. */
    public static ActorSource fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ActorSource source : values()) {
            if (source.name().equals(name)) {
                return source;
            }
        }
        return null;
    }
}
