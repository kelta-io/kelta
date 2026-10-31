package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.workflow.ActorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the audit identity ("actor") a flow execution runs as.
 *
 * <p>Precedence: the initiating user when one exists (manual runs,
 * record-change events carry the acting user) → the flow's configured
 * {@code run_as_user_id} → the flow owner ({@code created_by}). The resolved
 * actor is passed to {@link io.kelta.runtime.flow.FlowEngine#startExecution}
 * as the execution user, so action handlers stamp it into
 * {@code createdBy}/{@code updatedBy} on records the flow writes and it is
 * recorded as the execution's {@code started_by}.
 *
 * <p>Each branch also names its {@link ActorSource} ({@code INITIATOR}, {@code RUN_AS},
 * {@code OWNER}), passed along with the actor and recorded as {@code started_by_source}. Only an
 * initiator is a person who acted; an external module receives the source as {@code act.source}
 * and refuses a member-only action for a run-as or owner fallback.
 *
 * <p>Non-UUID initiators (the webhook path historically passed the literal
 * {@code "webhook"}) are treated as absent so the fallback chain applies and
 * no non-UUID value reaches a UUID audit column.
 */
@Service
public class FlowActorResolver {

    private static final Logger log = LoggerFactory.getLogger(FlowActorResolver.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final String SELECT_FLOW_ACTOR = """
            SELECT run_as_user_id, created_by FROM flow WHERE id = ?
            """;

    /**
     * A resolved flow actor: the user id and how it was chosen. Both {@code null} when nothing
     * resolves ({@link #NONE}).
     */
    public record Resolved(String userId, ActorSource source) {
        /** No actor: no initiator, and the flow has neither a run-as user nor an owner. */
        public static final Resolved NONE = new Resolved(null, null);
    }

    private final JdbcTemplate jdbcTemplate;
    private final JdbcUserIdResolver userIdResolver;

    public FlowActorResolver(JdbcTemplate jdbcTemplate, JdbcUserIdResolver userIdResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.userIdResolver = userIdResolver;
    }

    /**
     * Resolves the execution actor for a flow start.
     *
     * @param tenantId          tenant the flow belongs to
     * @param flowId            the flow being started
     * @param initiatingUserId  the user who initiated the start, when known
     * @return the actor and its source; {@link Resolved#NONE} when nothing resolves
     */
    public Resolved resolve(String tenantId, String flowId, String initiatingUserId) {
        if (initiatingUserId != null && UUID_PATTERN.matcher(initiatingUserId).matches()) {
            return new Resolved(initiatingUserId, ActorSource.INITIATOR);
        }
        try {
            // Manual runs may carry an email (gateway auth-code JWTs) — translate
            // it the same way the record router does before falling back.
            if (initiatingUserId != null && !initiatingUserId.isBlank()) {
                String translated = TenantContext.callWithTenant(tenantId,
                    () -> userIdResolver.resolve(initiatingUserId, tenantId));
                if (translated != null && UUID_PATTERN.matcher(translated).matches()) {
                    return new Resolved(translated, ActorSource.INITIATOR);
                }
            }
            return TenantContext.callWithTenant(tenantId, () -> {
                List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList(SELECT_FLOW_ACTOR, flowId);
                if (rows.isEmpty()) {
                    return Resolved.NONE;
                }
                Map<String, Object> row = rows.get(0);
                Object runAs = row.get("run_as_user_id");
                if (runAs != null) {
                    return new Resolved(runAs.toString(), ActorSource.RUN_AS);
                }
                Object owner = row.get("created_by");
                return owner != null ? new Resolved(owner.toString(), ActorSource.OWNER) : Resolved.NONE;
            });
        } catch (Exception e) {
            // Actor resolution must never block a flow start — fall back to no actor.
            log.warn("Could not resolve flow actor for flow {} (tenant {}): {}",
                flowId, tenantId, e.getMessage());
            return Resolved.NONE;
        }
    }

    /**
     * The actor of a retry of an execution: the original run's user and source, so a run-as or
     * owner fallback stays one and a retry never becomes an initiator's run. An execution started
     * before the source was recorded keeps the user it resolved to then, with an unknown
     * ({@code null}) source; one with no user resolves afresh, as a run with no initiator.
     *
     * @param startedBy       the original execution's {@code started_by}
     * @param startedBySource the original execution's {@code started_by_source}
     */
    public Resolved resolveRetry(String tenantId, String flowId, String startedBy, ActorSource startedBySource) {
        if (startedBySource != null) {
            return new Resolved(startedBy, startedBySource);
        }
        if (startedBy == null) {
            return resolve(tenantId, flowId, null);
        }
        return new Resolved(resolve(tenantId, flowId, startedBy).userId(), null);
    }
}
