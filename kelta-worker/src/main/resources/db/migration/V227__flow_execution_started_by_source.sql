-- How a flow execution's started_by was chosen (io.kelta.runtime.workflow.ActorSource):
-- INITIATOR (the user the trigger names: a manual run's caller, or for a record trigger the user
-- whose write produced the event, which for a flow's own write is the upstream run's user and may
-- be a fallback; see .claude/docs/concerns.md), RUN_AS (the flow's run_as_user_id) or OWNER (the
-- flow's created_by). FlowActorResolver picks it with the
-- actor; the engine keeps it on the execution so a resumed or retried run still knows it, and
-- an external module receives it as the assertion's act.source, so a member-only action can
-- refuse a run-as or owner fallback (specs/module-platform/9-external-modules.md §3.3).
--
-- NULL for rows written before this migration, and for a run with no actor: the source is
-- unknown, and a module action sends no actor for it. The CHECK admits NULL.
-- To reverse: ALTER TABLE flow_execution DROP COLUMN started_by_source;
-- (dropping the column drops chk_flow_execution_started_by_source with it).
ALTER TABLE flow_execution ADD COLUMN IF NOT EXISTS started_by_source VARCHAR(16);
ALTER TABLE flow_execution ADD CONSTRAINT chk_flow_execution_started_by_source
    CHECK (started_by_source IN ('INITIATOR', 'RUN_AS', 'OWNER'));
