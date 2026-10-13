-- Per-flow execution-history retention (PLT-603). NULL keeps the global
-- kelta.flow.retention.max-age-days window; a value replaces it for that flow's
-- terminal executions, shorter or longer. Capped at ten years so the sweep's
-- cutoff arithmetic can never overflow a timestamp.
ALTER TABLE flow ADD COLUMN IF NOT EXISTS retention_days INTEGER;

ALTER TABLE flow DROP CONSTRAINT IF EXISTS chk_flow_retention_days;
ALTER TABLE flow ADD CONSTRAINT chk_flow_retention_days
    CHECK (retention_days IS NULL OR retention_days BETWEEN 1 AND 3650);

-- Machine-readable failure code on the execution row. The stale-RUNNING reaper
-- writes 'Abandoned'; step-level codes stay on flow_step_log.error_code.
ALTER TABLE flow_execution ADD COLUMN IF NOT EXISTS error_code VARCHAR(100);
