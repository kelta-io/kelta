-- Per-flow step logging level (PLT-602). FULL keeps today's behaviour: one
-- flow_step_log row per step with input/output snapshots. ERRORS_ONLY keeps a
-- row per step but drops the snapshots of successful steps; NONE writes only
-- the failing step's row. Existing flows default to FULL.
ALTER TABLE flow ADD COLUMN IF NOT EXISTS step_logging VARCHAR(20) NOT NULL DEFAULT 'FULL';

ALTER TABLE flow DROP CONSTRAINT IF EXISTS chk_flow_step_logging;
ALTER TABLE flow ADD CONSTRAINT chk_flow_step_logging
    CHECK (step_logging IN ('FULL', 'ERRORS_ONLY', 'NONE'));
