package io.kelta.worker.listener;

import io.kelta.runtime.flow.FlowExecutionData;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Refuses to delete a flow while one of its executions is still live.
 *
 * <p>V216 made {@code flow_execution.flow_id} cascade on flow delete (before it, any flow
 * that had ever run was undeletable), and step logs and pending resumes already cascade from
 * the execution. A delete therefore takes the flow's whole run history with it — which is
 * the intent for finished runs, but would pull the rows out from under the engine for a
 * {@code RUNNING} execution or a {@code WAITING} one a scheduler is about to resume. Those
 * two are exactly the statuses {@link FlowExecutionData#isTerminal()} does not count as
 * terminal; a flow with only {@code COMPLETED}, {@code FAILED} or {@code CANCELLED} runs is
 * deleted.
 *
 * <p>The count is by {@code flow_id} alone, with no tenant predicate: it must cover exactly
 * the rows the cascade removes, and the cascade is not tenant-scoped either (RLS still
 * applies to the query).
 *
 * @since 1.0.0
 */
@Component
public class FlowDeletionGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FlowDeletionGuardHook.class);

    static final String ERROR_CODE = "FLOW_HAS_ACTIVE_EXECUTIONS";

    /** The non-terminal execution statuses; pinned to the schema by FlowDeletionCascadeSchemaTest. */
    static final List<String> ACTIVE_STATUSES =
            List.of(FlowExecutionData.STATUS_RUNNING, FlowExecutionData.STATUS_WAITING);

    static final String ACTIVE_EXECUTIONS_SQL =
            "SELECT COUNT(*) FROM flow_execution WHERE flow_id = ? AND status IN (?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public FlowDeletionGuardHook(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return "flows";
    }

    /** Before every other flows hook, so a refused delete does none of their side work. */
    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public BeforeSaveResult beforeDelete(String collectionName, String id, String tenantId) {
        if (id == null) {
            return BeforeSaveResult.ok();
        }
        Integer active = jdbcTemplate.queryForObject(ACTIVE_EXECUTIONS_SQL, Integer.class, id,
                ACTIVE_STATUSES.get(0), ACTIVE_STATUSES.get(1));
        if (active != null && active > 0) {
            log.info("Blocked delete of flow {} (tenant {}): {} active execution(s)",
                    id, tenantId, active);
            throw new HookConflictException(ERROR_CODE, null,
                    "This flow has " + active + " running or waiting execution(s). "
                            + "Cancel them or let them finish before deleting the flow.",
                    Map.of("activeExecutions", active));
        }
        return BeforeSaveResult.ok();
    }
}
