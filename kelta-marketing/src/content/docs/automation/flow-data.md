---
title: "Flow data: the state envelope and $.input"
description: The JSON state every run carries, what each trigger puts in it, the $.input rule and the double-wrap for HTTP and MCP callers, and how InputPath, Parameters, ResultPath and OutputPath shape data between states.
section: automation
order: 40
---

## The state envelope

Every execution starts with this envelope and every JSONPath in the definition is evaluated against it:

```json
{
  "trigger": { "type": "API_INVOCATION" },
  "input":   { "customerId": "…" },
  "record":  { "id": "…", "collectionName": "invoices", "data": { }, "previousData": { }, "changedFields": [ ] },
  "headers": { },
  "context": { "tenantId": "…", "flowId": "…", "executionId": "…", "userId": "…" }
}
```

| Trigger | `trigger` | Present |
|---|---|---|
| Record change | `type: RECORD_CHANGE`, `changeType`, `collectionName`, `timestamp` | `record` |
| API / manual | `type: API_INVOCATION` | `input` |
| Scheduled | `type: SCHEDULED` | `input` (= `triggerConfig.inputData`) |
| Webhook | `type: WEBHOOK` | `input`, `headers` |
| NATS message | `type: NATS_MESSAGE`, `subject`, `topic` | `input` |

`context` is always present.

## Always read `$.input.<key>`

Inputs live under `input`. A path such as `$.customerId` skips the envelope, JSONPath returns nothing, and the
downstream task fails with whatever generic error its own validation produces — a confusing "required parameter
missing" rather than "you read the wrong path". Record data is at `$.record.data.<field>`, never `$.<field>`.

## The double-wrap rule

`POST /api/flows/{id}/execute` reads the input from `body.input`. So the HTTP body must already contain an `input`
key — the outer wrap is the request shape, the inner object is what lands at `$.input`:

```bash
curl -X POST https://api.example.com/acme/api/flows/$FLOW_ID/execute \
  -H 'Authorization: Bearer klt_...' -H 'Content-Type: application/json' \
  -d '{ "input": { "customerId": "9d2c…" } }'
# ⇒ $.input.customerId == "9d2c…"
```

The MCP `execute_flow` tool passes its `input` argument through as the HTTP body, so the same applies there —
double-wrap:

```json
{ "flowId": "…", "input": { "input": { "customerId": "9d2c…" } } }
```

The CLI adds the outer wrap for you: `kelta flows execute <id> --input '{"customerId": "9d2c…"}'`.

A single wrap sends `{ "customerId": … }` as the body; the controller finds no `body.input`, `$.input` is `{}`, and
every read of `$.input.customerId` is empty.

## Shaping data between states

| Key | Applies to | Effect |
|---|---|---|
| `InputPath` | Task, Parallel, Map, Pass, InvokeFlow | JSONPath selecting the portion of state the state works on (default `$`) |
| `Parameters` | Task | Object passed to the handler; string values of the form `"${$.path}"` are substituted from the state |
| `Input` | InvokeFlow | Same substitution; becomes the sub-flow's `$.input` |
| `ItemsPath` | Map | JSONPath to the array to iterate |
| `ResultPath` | Task, Parallel, Map, Pass, InvokeFlow | Where the result is merged: `$` replaces the state; `$.lookup` nests it under `lookup`; `null` discards it |
| `OutputPath` | same | JSONPath selecting what is passed to the next state (default `$`) |
| `Result` | Pass | Static value to inject at `ResultPath` |

Example — query, then use the count:

```json
"CountOpen": {
  "Type": "Task", "Resource": "QUERY_RECORDS",
  "Parameters": { "targetCollectionName": "invoices",
                  "filters": [ { "field": "customer", "operator": "eq", "value": "${$.record.data.id}" },
                               { "field": "status", "operator": "eq", "value": "OPEN" } ] },
  "ResultPath": "$.open",
  "Next": "Decide"
},
"Decide": {
  "Type": "Choice",
  "Choices": [ { "Variable": "$.open.totalCount", "NumericGreaterThan": 3, "Next": "Escalate" } ],
  "Default": "Done"
}
```

## Errors in the state

When a `Catch` matches, the error is placed at the catch's `ResultPath` (default `$`) as
`{ "Error": "<code>", "Cause": "<message>" }`, so the next state can inspect `$.Error` — see
[Retries, catch, waits and resume](/docs/automation/error-handling/).
