-- A flow that had ever run could not be deleted. Deleting a flow is a generic record
-- delete on the `flows` system collection (DynamicCollectionRouter → QueryEngine.delete),
-- and flow_execution_flow_id_fkey (V1__baseline) had no ON DELETE action, so the first
-- execution row restricted the delete forever: Postgres 23503, answered as 409
-- REFERENCED_RECORD. Test and retired flows could only be deactivated.
--
-- An execution is the run history of its flow and has no meaning without it, so it goes
-- with the flow. Its own children already cascade from flow_execution(id) in the baseline
-- (flow_step_log_execution_id_fkey, flow_pending_resume_execution_id_fkey), as does
-- flow_version from flow(id). FlowDeletionGuardHook refuses the delete while the flow has
-- a RUNNING or WAITING execution, so the cascade never removes rows a live engine owns.
ALTER TABLE flow_execution
    DROP CONSTRAINT IF EXISTS flow_execution_flow_id_fkey,
    ADD CONSTRAINT flow_execution_flow_id_fkey
        FOREIGN KEY (flow_id) REFERENCES flow(id) ON DELETE CASCADE;
