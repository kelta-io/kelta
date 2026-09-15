# scripts/dora — DORA metrics

Deployment frequency · lead time for changes · change failure rate · time to restore,
for this repo, computed from sources that already exist. Zero dependencies, Node ≥ 18.

```
GitHub (merged PRs)  ─┐
main first-parent    ─┼─► collect.mjs ─► dora.json
homelab-argo git log ─┤        │
Loki / Mimir alerts  ─┘        ├─► Loki   {job="dora"}            (events + snapshots, 30d)
                               ├─► Mimir  dora_* gauges via Alloy (30d)  → Grafana "EMF DORA"
                               └─► Kelta  dora-* collections      (durable) → Kelta "DORA" dashboard
smoke-test job ──► emit-deploy-event.sh ─► Loki {job="dora", event="deploy_healthy"}
```

Runs nightly from `.github/workflows/dora-metrics.yml` (`.claude/docs/ci-cd.md` has the
workflow-level description). Everything is recomputed from scratch each run and every
sink is idempotent, so re-running is always safe.

## Files

| File | Role |
|------|------|
| `lib.mjs` | Pure logic: ArgoCD log classification, PR→deployment attachment, incident pairing, percentiles, DORA bands, window summaries. `node --test scripts/dora/lib.test.mjs`. |
| `collect.mjs` | I/O: loads the sources, calls `lib.mjs`, writes the report, pushes to the sinks. `--help` lists the flags. |
| `emit-deploy-event.sh` | Called at the end of the smoke-test job: stamps merge→healthy for the SHA that just went live. Exits 0 no matter what. |
| `bootstrap-kelta.mjs` | Creates the four `dora-*` collections, a source report, the **DORA** dashboard (15 widgets) and optionally a nav item. Idempotent — looks everything up by name. |

## Run it locally

```bash
GITHUB_TOKEN=$(gh auth token) node scripts/dora/collect.mjs \
  --argo-dir ~/GitHub/homelab-argo --out /tmp/dora.json
```

Prints the summary table on stderr. Add sinks with port-forwards when you want to see
the dashboards move without waiting for the cron:

```bash
kubectl -n observability port-forward svc/loki 13100:3100 &
kubectl -n observability port-forward svc/mimir 18080:8080 &
kubectl -n observability port-forward svc/alloy-collector 14318:4318 &
GITHUB_TOKEN=$(gh auth token) node scripts/dora/collect.mjs --argo-dir ~/GitHub/homelab-argo \
  --loki-url http://localhost:13100 --mimir-url http://localhost:18080 \
  --push-loki --push-otlp http://localhost:14318 --push-kelta
```

`--push-kelta` uses the kelta CLI's active profile (or `KELTA_URL`/`KELTA_TENANT`/
`KELTA_TOKEN`). First time on a tenant:

```bash
node scripts/dora/bootstrap-kelta.mjs                       # collections + dashboard
node scripts/dora/bootstrap-kelta.mjs --menu "<menu>" --parent "<item>"   # + nav item
node scripts/dora/collect.mjs ... --push-kelta --push-days 90             # backfill
```

A 90-day backfill is ~5 minutes (two CLI calls per row); the nightly 6-day window is
well under a minute.

## Definitions (and the traps)

- **Deployment** = one `chore: update Kelta images to main-<sha>` commit in
  `homelab-argo` by `github-actions[bot]`. That is what ArgoCD rolls, so that is what
  is counted. A `revert: re-apply the main-<sha> image bump` counts as a deployment but
  carries no changes (they were already attributed to the original bump).
- **Change** = one merged PR. It ships in the first bump whose SHA is at/after its
  merge commit on the first-parent history of `main`. The Build-and-Deploy concurrency
  group cancels in-flight builds when the next merge lands, so one bump routinely
  carries several PRs — attributing by history position rather than 1:1 is what makes
  the lead-time numbers honest. PRs whose merge commit is not on first-parent
  (merged into a stacked branch that was then squashed) are reported as `unshipped`
  with the reason.
- **Lead time** = first commit on the PR → bump commit time. `mergeToProd` = merge → bump
  (pure pipeline latency). Both p50 and p90 are kept; the band uses p50.
- **Failure** = a bump followed by a rollback commit before the next bump:
  `revert: roll back image bump (smoke-test failed in run N)` (automatic) or any manual
  `revert…` / `roll back` commit touching `emf/`. False-positive auto-rollbacks (a
  flaky smoke poll reverting a healthy release) count as failures — DORA measures the
  pipeline, and a needless revert is a pipeline failure. `autoRollbacks` is tracked
  separately so you can see the split.
- **Incident** = Alerting→Normal for one alert fingerprint (Grafana state history in
  Loki) or one contiguous run of `ALERTS{alertstate="firing"}` samples (Prometheus
  rules in Mimir). Ongoing incidents get `ttrSec` measured to now and are excluded from
  MTTR until they resolve. Grafana only writes state history to Loki once
  `GF_UNIFIED_ALERTING_STATE_HISTORY_*` is set on the Grafana deployment, so MTTR is
  empty before that ships and reads `0` on the Kelta metric tile (no resolved
  incidents), not "elite".
- **Windows** are trailing 7/30/90 days ending at the run time. **Segments**
  `all|bot|human` filter changes by PR author (`Bot` GraphQL type or `*[bot]` login);
  deployments and incidents are shared across segments.
- **Bands** — Google DORA report thresholds: deploy frequency ≥1/day elite, weekly
  high, monthly medium; lead time <1d / <1w / <1mo; CFR ≤5% / ≤10% / ≤15%; MTTR <1h /
  <1d / <1w.

## Sinks

- **Loki** `{job="dora", event=deploy|rollback|incident|snapshot}` — lines carry the
  event's own timestamp (only the last `--push-days`, default 6, inside Loki's 7-day
  old-sample window) and are serialized with sorted keys, so a re-push is
  byte-identical and Loki dedupes it. `deploy_healthy` comes from CI, not the collector.
- **Mimir** (OTLP JSON → Alloy `:4318/v1/metrics`) gauges, labels `window`, `segment`,
  `repo`, `quantile` where relevant: `dora_deploy_frequency_per_day`,
  `dora_lead_time_seconds`, `dora_merge_to_prod_seconds`, `dora_change_failure_rate`,
  `dora_time_to_restore_seconds`, `dora_{deployments,changes,rollbacks,incidents}_total`,
  `dora_collector_last_run_timestamp_seconds`. No OTLP unit on counts/ratios — the
  translator would append `_ratio`.
- **Kelta** — `dora-deployments` (key `sha`), `dora-changes` (key `prNumber`),
  `dora-incidents` (key `key`), `dora-snapshots` (key `key`; `kind=latest` rows are
  overwritten each run and are what the dashboard tiles read, `kind=history` rows
  accumulate one per day for the trend chart). Plain tenant metadata — any tenant can
  run the bootstrap.
