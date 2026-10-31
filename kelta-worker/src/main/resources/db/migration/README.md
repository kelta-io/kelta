# Flyway migrations — flattened baseline (2026-07-06)

`V1..V161` were consolidated into a single **`V1__baseline.sql`**. The original
scripts are retained (off the Flyway classpath) under
[`../migration-archive/`](../migration-archive/) for history/reference. New
migrations continue at **`V162`**.

## What the baseline is

`V1__baseline.sql` is the kelta-worker control-plane `public` schema + bootstrap
seed, generated from a fresh migrate of the full V1..V161 chain (demo seed **V50
excluded**) and captured with `pg_dump`, made Flyway/JDBC-safe (psql `\` meta +
session `SET`s stripped; `CREATE EXTENSION pg_trgm` + `CREATE SCHEMA` guarded).
Fresh-install parity was verified against Flyway 11.14.1: byte-equivalent schema
+ seed vs the incremental chain, plus 10 FK/audit indexes that lived on
production but were never in a migration (now codified, `IF NOT EXISTS`).

Out of scope (unchanged by the flatten):
- **kelta-ai** owns the `ai_*` tables via its own Flyway history
  (`ai_flyway_schema_history`) — not touched here.
- **System-collection metadata** (collections/fields/layouts) is seeded
  idempotently at worker startup by `SystemCollectionSeeder`, so it is not in
  the baseline.
- **Per-tenant business-data schemas** are created at runtime.

## Fresh installs

Nothing special — Flyway runs `V1__baseline` (and any later `V162+`). The demo
tenant is no longer auto-seeded. Verified: `migrate` on an empty DB produces the
106-table control-plane schema, applied as version `v1`.

## Existing databases — one-time reconciliation (REQUIRED, deploy-coordinated)

Production is **already baselined at V92** (`<< Flyway Baseline >>`) and its
`flyway_schema_history` lists `V93..V161`. After the flatten those scripts no
longer exist on the classpath, so Flyway reports them as *missing* and **fails
`migrate`**. The reconciliation is a one-line history cleanup:

```sql
-- Remove the now-superseded V93..V161 history rows; KEEP the BASELINE@92 marker.
DELETE FROM flyway_schema_history
 WHERE version ~ '^[0-9]+$' AND version::int > 92;
```

After this, `flyway_schema_history` holds only the `BASELINE@92` row.
`V1__baseline` (version 1) is below the baseline → ignored (never re-run); there
are no missing rows → `migrate` is a clean **no-op**; `V162+` apply normally.
All three verified on a clone of prod with Flyway 11.14.1.

### Timing (critical)

The DELETE and the flatten deploy must be paired, because the **currently
deployed** worker still has the V93..V161 files: if it ran a migration between
the DELETE and the flatten going live, it would re-apply V93..V161 and corrupt
the schema. Serving pods do not migrate (`flyway.enabled=false`); migrations run
only via the K8s **PreSync job**. Safe sequence for the cut-over:

1. **Back up** `flyway_schema_history` (`pg_dump --table=public.flyway_schema_history`).
2. Ensure the **flatten is the next thing deployed** (no interim old-code sync).
3. Run the DELETE above on the target DB.
4. Deploy the flatten → the PreSync `flyway migrate` is a no-op.

For full automation this can instead be a Flyway `beforeMigrate` SQL callback
(the DELETE is idempotent), removing the manual coordination — deferred here.

> `spring.flyway.ignore-migration-patterns: "*:missing,*:future"` is also set in
> `application.yml`. `*:missing` is a secondary guard for the flatten, but it is
> **not** a substitute for the DELETE — it did not reliably suppress the pure
> no-op failure in testing. `*:future` restores Flyway's default (setting the
> property replaces it), so an older image's migrate Job can still validate
> after a rollback.
