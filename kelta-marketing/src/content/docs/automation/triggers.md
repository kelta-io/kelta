---
title: "Triggers: record, schedule, webhook, NATS, manual"
description: The four flow types and how each starts — record change filters, cron schedules and scheduled jobs, inbound webhooks, NATS messages, and explicit API invocation.
section: automation
order: 30
---

A flow's `flowType` decides how it starts; `triggerConfig` holds the type-specific settings. Whatever the trigger,
the run begins with a [state envelope](/docs/automation/flow-data/) whose `trigger.type` records the origin.

| `flowType` | Starts when | `trigger.type` |
|---|---|---|
| `RECORD_TRIGGERED` | a record in a collection is created / updated / deleted | `RECORD_CHANGE` |
| `SCHEDULED` | a cron schedule fires | `SCHEDULED` |
| `NATS_TRIGGERED` | a message arrives on a topic | `NATS_MESSAGE` |
| `AUTOLAUNCHED` | called explicitly — API, CLI, MCP, page action, inbound webhook | `API_INVOCATION` or `WEBHOOK` |

Only **active** flows start. Every start resolves an **actor** — the initiating user for manual runs, else the
flow's `runAsUserId`, else the flow's owner — and the run acts with that user's permissions; records it writes are
stamped with that user.

## Record triggers

```json
{ "collection": "invoices", "events": ["CREATED", "UPDATED"], "triggerFields": ["status"],
  "filterFormula": "status = \"OVERDUE\" AND amount > 0" }
```

- `events` — any of `CREATED`, `UPDATED`, `DELETED` (all when omitted).
- `triggerFields` — for updates, start only when at least one listed field changed.
- `filterFormula` — a [formula](/docs/data-model/formula-fields/#expression-syntax) evaluated against the
  record's new data; the flow starts only when it is `TRUE`.

The run sees the record at `$.record.data`, the previous values at `$.record.previousData` and the list of
`$.record.changedFields`. Record events are delivered through NATS JetStream, so a flow starts once per change
regardless of how many worker pods run.

## Schedules

```json
{ "cron": "0 */4 * * *", "timezone": "Europe/Lisbon", "inputData": { "mode": "full" } }
```

- `cron` accepts the standard 5-field form (minutes first) or the 6-field form with seconds; 5-field input is
  normalised on save, and an unparseable expression is rejected with `400` naming `triggerConfig.cron`.
- `timezone` is an IANA name (default `UTC`).
- `inputData` is placed at `$.input` on every run.

Saving a `SCHEDULED` flow creates or updates its row in **scheduled jobs**; `GET /api/flows/{id}/schedule` shows
`cron`, `timezone`, `active`, `lastRunAt`, `lastStatus`, `nextRunAt` and a derived `scheduleStatus`
(`ACTIVE`, `PAUSED`, `NONE`, or `UNSYNCED` when the executor will never pick the flow up); `GET /api/flows/{id}/runs`
lists recent executions.

### Scheduled jobs

Setup → Automation → Scheduled jobs shows every cron-driven job, not just flows: `jobType` is `FLOW`, `SCRIPT`,
`REPORT_EXPORT` or `DATA_EXPORT`, with `cronExpression`, `timezone`, `config` (for example report recipients) and
`lastStatus`. Actions: `POST /api/scheduled-jobs/{id}/pause`, `/resume`, `/execute` (run now) and
`POST /api/scheduled-jobs/validate-cron` for pre-save validation. Each run writes a `job-execution-logs` row.

## NATS message triggers

```json
{ "topic": "orders.imported" }
```

The flow starts for every message published to `kelta.trigger.<tenantId>.<topic>` on the platform's NATS
JetStream. The message body (any JSON; a non-JSON body arrives as `{ "raw": "…" }`) becomes `$.input`, and
`$.trigger.subject` / `$.trigger.topic` identify the source. Exactly one worker pod handles each message. This is
the integration point for publishers that run inside your own infrastructure next to Kelta.

## Manual and API invocation

```http
POST /api/flows/{flowId}/execute
{ "input": { "customerId": "…" } }
```

Note the wrap: the body's `input` object becomes `$.input` — see [the double-wrap rule](/docs/automation/flow-data/#the-double-wrap-rule).
The response carries the `executionId`. CLI: `kelta flows execute <flowId> --input '{...}'` (the CLI adds the
outer wrap for you). MCP: `execute_flow`. Page-builder `runFlow` actions call the same endpoint.

Two escape hatches: `body.state` supplies a complete pre-built envelope, and `body.test: true` marks the run as a
test.

## Inbound webhooks

An `AUTOLAUNCHED`, active flow can be started by an external system without credentials:

```http
GET  /api/flows/{flowId}/webhook-url      # the public URL to hand out
POST /api/webhooks/{flowId}               # the call the external system makes
```

The JSON body becomes `$.input` and the request headers `$.headers`; `trigger.type` is `WEBHOOK`.

- The endpoint is **unauthenticated by design**: the flow id in the URL is the shared secret. Hand the URL only
  to the system that should call it; rotate by recreating the flow.
- There is **no signature verification**. If the source signs its requests, verify the signature inside the flow
  (`INVOKE_SCRIPT` or `CALL_API` to a verifier) before acting on the body.
- The gateway applies a per-IP rate limit to unauthenticated paths, and the endpoint is exempt from the tenant IP
  allowlist.
- A flow that is not `AUTOLAUNCHED` or not active answers `400`.

For webhooks that carry the record events Kelta itself emits, see [Outbound webhooks](/docs/api/webhooks/).
