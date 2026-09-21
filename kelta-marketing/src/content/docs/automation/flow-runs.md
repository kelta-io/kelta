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
| Its steps | `GET /api/flows/executions/{executionId}/steps` — one row per state: status, input snapshot, output, error, duration |
| Runs of a flow | `GET /api/flows/{flowId}/flow-executions` · `kelta flows runs <flowId>` |
| Runs that touched a record | `GET /api/flows/record-executions?recordId=…` — shown on the record's activity timeline |
| All runs as a collection | `GET /api/flow-executions?filter[status][eq]=FAILED&sort=-startedAt` |

The console shows the same under Setup → Automation → Flows → *Runs*, with the state machine highlighted per
step; Setup → Platform → Monitoring links each run to its trace.

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

The pruning sweep ships **dry-run by default**: it logs what it would delete until the operator arms it
(`FLOW_RETENTION_DRY_RUN=false`). Ask your operator which mode your deployment runs; until it is armed, history is
kept indefinitely. See [Data retention](/docs/platform/data-retention/).
