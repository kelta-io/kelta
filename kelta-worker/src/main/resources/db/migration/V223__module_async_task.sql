-- External modules part 9h (specs/module-platform/9-external-modules.md §3.6.2): long-running
-- (asynchronous) module actions.
--
-- When a top-level flow Task calls a module action whose manifest declares maxDurationSeconds,
-- the worker writes one row here before the call and sends the module a task token. The module
-- may answer 202; the flow step then parks WAITING (flow_pending_resume, resume_event =
-- 'module-task:<id>', resume_at = expires_at) until the module completes the task through
-- POST /api/modules/self/tasks/{token}, or the resume poller fails the step at expires_at.
--
-- token_hash is the SHA-256 (hex) of the token; the token itself is never stored. A row is bound
-- to its tenant, install, execution, step and attempt. status moves once, PENDING to COMPLETED
-- (outcome recorded) or PENDING to EXPIRED (the step resumed without one), and that one
-- conditional UPDATE is what makes a token single use. A token the module never needed (it
-- answered synchronously, or the call failed) is deleted.
--
-- Additive only: a new table, its indexes and its RLS policies. Rows go with their install and
-- with their execution (ON DELETE CASCADE), so flow-log retention removes them too.
-- To reverse:
--   DROP TABLE IF EXISTS module_async_task;

CREATE TABLE IF NOT EXISTS module_async_task (
    id character varying(36) NOT NULL,
    tenant_id character varying(36) NOT NULL,
    tenant_module_id character varying(36) NOT NULL,
    execution_id character varying(36) NOT NULL,
    step_name text NOT NULL,
    attempt integer NOT NULL,
    action_key character varying(100) NOT NULL,
    token_hash character varying(64) NOT NULL,
    status character varying(20) DEFAULT 'PENDING' NOT NULL,
    outcome jsonb,
    expires_at timestamp with time zone NOT NULL,
    last_heartbeat_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT module_async_task_pkey PRIMARY KEY (id),
    CONSTRAINT uq_module_async_task_token UNIQUE (token_hash),
    CONSTRAINT chk_module_async_task_status CHECK (status IN ('PENDING', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT fk_module_async_task_module FOREIGN KEY (tenant_module_id)
        REFERENCES tenant_module (id) ON DELETE CASCADE,
    CONSTRAINT fk_module_async_task_execution FOREIGN KEY (execution_id)
        REFERENCES flow_execution (id) ON DELETE CASCADE
);

-- An execution's tasks (the FK cascade from flow_execution, and an operator's look-up).
CREATE INDEX IF NOT EXISTS idx_module_async_task_execution
    ON module_async_task (tenant_id, execution_id);

-- An install's tasks (the FK cascade from tenant_module).
CREATE INDEX IF NOT EXISTS idx_module_async_task_module
    ON module_async_task (tenant_module_id);

-- RLS — tenant_isolation (explicit WITH CHECK, as V200) + admin_bypass.
ALTER TABLE module_async_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY module_async_task FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON module_async_task;
CREATE POLICY tenant_isolation ON module_async_task
    FOR ALL
    USING ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true))
    WITH CHECK ((tenant_id)::text = current_setting('app.current_tenant_id'::text, true));

DROP POLICY IF EXISTS admin_bypass ON module_async_task;
CREATE POLICY admin_bypass ON module_async_task
    USING ((current_setting('app.current_tenant_id'::text, true) = ''::text));
