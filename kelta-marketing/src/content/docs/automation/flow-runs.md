---
title: Runs, step logs, versions and retention
description: Inspect executions and their step logs, cancel and retry, publish flow versions, and understand how long run history is kept.
section: automation
order: 60
---

## Run lifecycle

A run (`flow-executions`) moves through `RUNNING` → `COMPLETED` | `FAILED` | `CANCELLED`, or parks in `WAITING`
while a long `Wait` is pending. Each carries `flowId`, `startedBy` (the resolved actor), `triggerRecordId` for
record-triggered runs, the `initialInput`, the current `stateData`, `currentNodeId`, and `errorMessage` on
failure. Runs are durable: a worker restart does not lose a `RUNNING` or `WAITING` execution.

## Inspecting runs

| Need | Endpoint / command |
|---|---|
| One run | `GET /api/flows/executions/{executionId}` · `kelta flows run <executionId>` · MCP `get_flow_run` |
| Its steps | `GET /api/flows/executions/{executionId}/steps` — one row per state: status, input snapshot, output, error, duration (what is kept depends on the flow's [step logging](#step-logging)) |
| Runs of a flow | `GET /api/flows/{flowId}/flow-executions` · `kelta flows runs <flowId>` |
| Runs that touched a record | `GET /api/flows/record-executions?recordId=…` — shown on the record's activity timeline |
| All runs as a collection | `GET /api/flow-executions?filter[status][eq]=FAILED&sort=-startedAt` |

The console shows the same under Setup → Automation → Flows → *Runs*, with the state machine highlighted per
step; Setup → Platform → Monitoring links each run to its trace.

**Who can read runs.** Staff holding `MANAGE_WORKFLOWS` or `VIEW_SETUP` read every run in full: its state,
input, error and steps, the runs of a flow, its versions and its schedule. Anyone else — every portal member
— reads only the status (`flowId`, `status`, `startedAt`, `completedAt`) of a run they started, such as the
one a page action launched for them, which is enough to wait for it to finish. Another user's run answers
404, steps answer 404, and the per-flow lists answer 403. `record-executions` lists only the caller's own
runs on that record. Return what a member should see through records the flow writes, not through the run's
state.

## Step logging

Each flow has a `stepLogging` level. The run row above is written at every level; the level decides which step
rows are kept:

| Level | Successful step | Failed step |
|---|---|---|
| `FULL` (default) | row with input and output snapshots | row with input and output snapshots |
| `ERRORS_ONLY` | row with state, type, status and duration — no snapshots | row with input and output snapshots |
| `NONE` | no row | row with input and output snapshots |

`FULL` stores the whole state envelope twice per step, so for **high-frequency or high-volume flows** — short
schedules, triggers on busy collections or NATS topics, `Map` over many items, large payloads — use
`ERRORS_ONLY`: you keep the step path and timings of every run and full detail where a run failed. A failed step
keeps its input snapshot at every level, so `retry?mode=from-failure` keeps working.

Set it in the flow designer (*Trigger* → *Step logging*), with `kelta flows update <flowId> --step-logging
ERRORS_ONLY`, MCP `update_flow`, or `PATCH /api/flows/{flowId}` with `{"stepLogging": "ERRORS_ONLY"}`. It travels
with the flow in `kelta metadata export` / `apply`.

## Cancel and retry

```http
POST /api/flows/executions/{executionId}/cancel
POST /api/flows/executions/{executionId}/retry?mode=full          # replay the original initial state
POST /api/flows/executions/{executionId}/retry?mode=from-failure  # restart from the failed step's input snapshot
```

Retry is allowed on terminal runs only and always creates a new execution. CLI: `kelta flows cancel|retry`.

## Versions

Saving a flow edits the working definition. **Publishing** snapshots it as the next version:

```http
POST /api/flows/{flowId}/publish
GET  /api/flows/{flowId}/versions
GET  /api/flows/{flowId}/versions/{n}
```

(`kelta flows publish|versions`.) The `version` attribute on the flow increments; runs record the definition they
started with, so a later edit never changes a run in flight.

## Retention

Terminal runs (`COMPLETED`, `FAILED`, `CANCELLED`) older than the retention window — 60 days by default — are
eligible for pruning, together with their step logs and scheduled-job logs. `WAITING` runs are never pruned.

A flow can set its own window with `retentionDays` (1-3650), shorter or longer than the platform's: a schedule that
fires every few minutes rarely needs two months of history.

```bash
kelta flows update <flowId> --retention-days 7        # keep a week
kelta flows update <flowId> --retention-days default  # back to the platform window
```

(`PATCH /api/flows/{id}` with `{"retentionDays": 7}`, or *Keep run history (days)* in the designer's trigger sheet;
zero, negative or fractional values are a 400.)

A run left `RUNNING` with no step activity for 24 hours — its worker died mid-run — is marked `FAILED` with error
code `Abandoned` and a completion time, and then ages out like any other failed run.

The pruning sweep ships **dry-run by default**: it logs what it would delete until the operator arms it
(`FLOW_RETENTION_DRY_RUN=false`). Ask your operator which mode your deployment runs; until it is armed, history is
kept indefinitely. See [Data retention](/docs/platform/data-retention/).
