# Slice 2 — Flow-Log Retention Sweep

> Child of `specs/consumer-alerting/README.md`. Independent of every other slice; **must land
> before high-frequency polling flows turn on** (slice 4 / poller go-live).

> **Landed.** `FlowLogRetentionSweep` + `FlowLogRetentionRepository`, config block, 11 unit
> tests, and `FlowLogRetentionScenarioTest` on real Postgres. Three notes against the design
> below:
> - **Age is `COALESCE(completed_at, started_at)`, not `completed_at`.** `completed_at` is
>   nullable, so a terminal row that never got one would be invisible to a plain
>   `completed_at < cutoff` predicate — it would leak forever, which is exactly the bug this
>   sweep exists to fix. The harness test covers that path explicitly.
> - **Added `max-batches` (default 20)**, not in the original config block. `batch-size` alone
>   bounds a single statement but not a cycle; without a batch cap, a first arming against a
>   large backlog would loop until the whole backlog was gone in one pass against a shared
>   Postgres. Work per cycle is now `batch-size × max-batches` per table and the remainder
>   drains on the next cycle.
> - **No index was added.** The delete claims by `status` + age with a `LIMIT`, and the
>   verified-cascade FKs already carry the dependent deletes; add
>   `flow_execution (status, completed_at)` only if an armed environment shows the scan in
>   `pg_stat_activity`. Deliberately not speculating a migration.
>
> Verified: only two tables reference `flow_execution` (`flow_step_log`,
> `flow_pending_resume`) and **both cascade**, so no delete can fail on a dependent constraint
> and no orphans are left. `job_execution_log`'s FK is to `scheduled_job` and does not cascade,
> so it is pruned directly.

## 1. Goal & scope

Delivers: age-based pruning of `flow_execution` (cascading to `flow_step_log` +
`flow_pending_resume` via existing `ON DELETE CASCADE`) and `job_execution_log`, dry-run
gated, batched, multi-pod safe. Closes the unbounded-growth item in the parent Context — the
`record_version` retention concern applies verbatim to flow logs, and today nothing deletes
them (`JdbcFlowStore`'s only DELETE is `deletePendingResume`).

Does not deliver: `record_version` retention (separate concern, tracked in concerns.md),
per-tenant retention overrides beyond config (v1 is platform-level config; per-tenant override
only if cheap).

## 2. UI samples

N/A — backend ops. Log line sample (dry-run):
`FlowLogRetentionSweep DRY-RUN: would delete 12,431 flow_execution rows (< 2026-06-03), 3,010 job_execution_log rows`.

## 3. Data & API contracts

Config (worker `application.yml`):

```yaml
kelta:
  flow:
    retention:
      enabled: true
      dry-run: true            # default TRUE — operator must arm it
      max-age-days: 60         # terminal executions older than this are purged
      batch-size: 1000
      poll-interval-ms: 3600000  # hourly
```

Rules: only terminal executions (`status IN ('COMPLETED','FAILED','CANCELLED')`) are
eligible; WAITING/RUNNING rows are never touched regardless of age. `job_execution_log` is
purged by the same age. Deletes run in `LIMIT batch-size` loops with `FOR UPDATE SKIP LOCKED`
claims (multi-pod safe), with a max batch count per cycle to bound runtime.

## 4. DB migrations

None (tables exist; cascade FKs already present in V1__baseline). Optional: an index on
`flow_execution (status, completed_at)` if the delete scan needs it — verify with EXPLAIN
during implementation; if added, take the current head+1.

## 5. File-by-file code changes

- `kelta-worker/.../service/FlowLogRetentionSweep.java` — NEW; copy the
  `RetentionPurgeSweep`/`AutoArchiveSweep` shape (@Scheduled fixedDelay, dry-run gate,
  SKIP LOCKED batch claim, per-batch logging, metrics counter `kelta_worker_flowlog_purged`).
- `kelta-worker/.../repository/FlowLogRetentionRepository.java` — NEW; JdbcTemplate deletes
  (records + raw SQL; no JPA).
- `kelta-worker/src/main/resources/application.yml` — the config block above.

## 6. Test plan

Unit: sweep respects dry-run (no delete calls), batch loop bounds, terminal-status filter.
Harness: `FlowLogRetentionScenarioTest` — seed old terminal + old WAITING + fresh executions
with step logs on real Postgres; run the sweep armed; assert old terminal rows + cascaded step
logs are gone while WAITING and fresh rows survive; run again → idempotent no-op.

## 7. Docs to update

`concerns.md` — add then close the "flow logs unbounded" row (documenting the dry-run default
+ arming procedure, like the existing purge entry) · `status.md` operational row ·
`README.md` (env vars) if the config block is operator-facing.

## 8. Risks & open questions

Purged executions disappear from the flow-run observability UI (`FlowExecutionController`) —
the 60-day default keeps recent history; note it in docs. Deletion load on shared Postgres —
batches + hourly cadence bound it; watch `pg_stat_activity` on first arm. The dry-run default
mirrors the existing purge posture: leave dry-run on for a cycle, review the "would purge"
logs, then arm per environment.
