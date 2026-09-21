---
title: Retries, catch, waits and resume
description: Retry and Catch policies, the error-code catalogue, how Wait states park and resume durably, and what survives a restart.
section: automation
order: 50
---

## Retry

```json
"Retry": [
  { "ErrorEquals": ["Api.Timeout", "Api.HttpServerError"], "IntervalSeconds": 2, "MaxAttempts": 5, "BackoffRate": 2.0 },
  { "ErrorEquals": ["States.ALL"], "MaxAttempts": 1 }
]
```

Policies are tried in order; the first whose `ErrorEquals` matches the error code applies. Defaults:
`IntervalSeconds` 1, `MaxAttempts` 3, `BackoffRate` 2.0 (the interval doubles after each attempt).
`States.ALL` matches every error. Available on `Task`, `Parallel` and `InvokeFlow`.

## Catch

```json
"Catch": [
  { "ErrorEquals": ["Api.HttpClientError"], "ResultPath": "$.error", "Next": "RecordFailure" },
  { "ErrorEquals": ["States.ALL"], "Next": "NotifyOps" }
]
```

When retries are exhausted (or none apply), the first matching `Catch` moves execution to `Next` with
`{ "Error": "…", "Cause": "…" }` merged at `ResultPath` (default `$`). With no matching catch, the run fails.

## Error codes

| Code | Raised when |
|---|---|
| `ActionFailed` | a handler returned a failure without a specific code |
| `ResourceNotFound` | `Resource` names a handler that is not registered |
| `States.NoChoiceMatched` | a `Choice` had no matching rule and no `Default` |
| `States.ItemsNotArray` | a `Map`'s `ItemsPath` did not resolve to an array |
| `Api.HttpClientError`, `Api.HttpServerError`, `Api.Timeout`, `Credential.*`, `Mapper.Failure` | `CALL_API` |
| `<ExceptionName>` | an unexpected exception — its simple class name (e.g. `ValidationException`) |
| `States.ALL` | wildcard used only in `ErrorEquals` |

A `Fail` state raises whatever `Error` / `Cause` it declares.

## Map partial failures

`FailOnPartial: true` fails the whole `Map` when any iteration fails; with `false` (default) the result array
records per-item outcomes and the flow continues.

## Wait states and durable resume

```json
"Cool-off": { "Type": "Wait", "Seconds": 3600, "Next": "Check" }
"Until":    { "Type": "Wait", "TimestampPath": "$.record.data.due_date", "Next": "Remind" }
```

- `Seconds` ≤ 10 sleeps in-process.
- Longer waits, `Timestamp` and `TimestampPath` **park** the run: its status becomes `WAITING`, a resume row is
  written, and a poller (every 10 s by default) claims due rows with `SELECT … FOR UPDATE SKIP LOCKED`, so exactly
  one pod resumes each run. The run continues from the Wait's `Next` with its state intact — pods can restart in
  between.
- `EventName` waits park the run until something calls the resume API with that event name. No built-in event
  source does this today; treat event waits as an integration hook rather than a ready-made feature.
- A `Wait` inside a `Parallel` branch or `Map` iterator is **not** resumable: parking there fails the run with a
  clear error instead of hanging. Put long waits at the top level.
- A parked run is never pruned by log retention; it waits until resumed or cancelled.

## Cancel and retry a run

```http
POST /api/flows/executions/{executionId}/cancel
POST /api/flows/executions/{executionId}/retry
```

Retry works on terminal runs only and starts a **new** execution: `?mode=full` (default) replays the original
initial state; `?mode=from-failure` restarts from the failed step's recorded input snapshot. See
[Runs, versions and retention](/docs/automation/flow-runs/).
