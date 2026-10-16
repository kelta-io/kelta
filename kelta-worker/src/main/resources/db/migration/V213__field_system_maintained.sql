-- KLT-609: system-maintained fields. A field flagged system_maintained may be set or changed only
-- by flows, hooks and other internal paths; DefaultQueryEngine rejects an API caller's write that
-- sets or changes it (400). Additive and defaulted, so existing fields are unaffected.
-- Rollback: ALTER TABLE field DROP COLUMN system_maintained;
ALTER TABLE field ADD COLUMN IF NOT EXISTS system_maintained boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN field.system_maintained IS
    'When true, API callers may not set or change the value; only flows and internal paths may write it (KLT-609).';
