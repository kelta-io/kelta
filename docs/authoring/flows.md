# Authoring Flows

A flow is an automation stored on the `flows` system collection: a
trigger plus a Step-Functions-style JSON state machine. Create one with
`kelta flows create`, the MCP `create_flow` tool, or `POST /api/flows`.
This page is the compact reference for writing one by hand; the full
catalogue is on kelta.io:
[flows and state types](https://kelta.io/docs/automation/flows/),
[flow data](https://kelta.io/docs/automation/flow-data/),
[action handlers](https://kelta.io/docs/automation/action-handlers/),
[triggers](https://kelta.io/docs/automation/triggers/).

## The flow record

- `name` (required), `description`.
- `flowType` (required) — `RECORD_TRIGGERED`, `SCHEDULED`,
  `NATS_TRIGGERED`, `AUTOLAUNCHED` (manual / API / webhook) or `SCREEN`.
- `triggerConfig` — JSON, shape depends on `flowType`:
  - `RECORD_TRIGGERED`: `{"collection", "events", "triggerFields",
    "filterFormula"}`. `events` is any of `CREATED`, `UPDATED`,
    `DELETED` (all when omitted). `triggerFields` applies to `UPDATED`
    only: the flow starts when at least one listed field changed.
    `filterFormula` is a formula over the record's new data that must be
    true.
  - `SCHEDULED`: `{"cron", "timezone", "inputData"}`; `inputData`
    becomes `$.input`.
  - `NATS_TRIGGERED`: `{"topic"}`; the message body becomes `$.input`.
  - `AUTOLAUNCHED`: none.
- `definition` (required) — the state machine below.
- `active` — defaults to `false`. Inactive flows never start; create
  with `active: true` (CLI `--active`) when you mean it to run.
- `runAsUserId` — the user the flow acts as when there is no initiating
  user (record, schedule and NATS starts); falls back to the flow owner.

## Definition skeleton

```json
{
  "Comment": "optional",
  "StartAt": "FirstState",
  "States": {
    "FirstState": { "Type": "Task", "Resource": "CREATE_RECORD", "Parameters": {}, "Next": "Done" },
    "Done": { "Type": "Succeed" }
  }
}
```

Every state has a `Type` and either `Next` or `End: true` (`Choice`,
`Succeed` and `Fail` have neither). Saving checks the structure
(`StartAt` exists, every `Next` resolves, state types are known); it does
**not** check a Task's `Parameters`. A wrong parameter name fails the
step at run time.

State types: `Task` (`Resource`, `Parameters`, `InputPath`, `ResultPath`,
`OutputPath`, `Retry`, `Catch`), `Choice` (`Choices[]`, `Default`),
`Parallel` (`Branches[]`), `Map` (`ItemsPath`, `Iterator`,
`MaxConcurrency`), `Wait` (`Seconds`, `Timestamp`, `TimestampPath`),
`Pass` (`Result`), `InvokeFlow` (`FlowId` or `FlowName`, `Input`),
`Succeed`, `Fail` (`Error`, `Cause`).

A `Choice` rule is `{"Variable": "<JSONPath>", "<Comparison>": <value>,
"Next": "<state>"}` with comparisons such as `StringEquals`,
`NumericGreaterThan`, `BooleanEquals`, `IsPresent`, `IsNull`, and
`And` / `Or` / `Not` for composition. Without a `Default`, a non-match
fails the run.

## The state envelope

Every run carries one JSON object; every path in the definition is
evaluated against it:

```json
{
  "trigger": { "type": "RECORD_CHANGE", "changeType": "UPDATED", "collectionName": "deals" },
  "record":  { "id": "…", "collectionName": "deals", "data": {}, "previousData": {}, "changedFields": [] },
  "input":   {},
  "context": { "tenantId": "…", "userId": "…", "flowId": "…", "executionId": "…" }
}
```

- `$.record.data.<field>` — the triggering record's new values
  (record-triggered flows only); `$.record.previousData.<field>` the old
  ones; `$.record.id` its id.
- `$.input.<key>` — inputs for API, CLI, MCP, scheduled, NATS and
  webhook starts. Never `$.<key>`. `POST /api/flows/{id}/execute` reads
  `body.input`, so the HTTP and MCP callers double-wrap
  (`{"input": {"customerId": "…"}}`); `kelta flows execute --input`
  adds the outer wrap for you.
- `$.context.*` — always present.

**`${…}` substitution.** Inside a Task's `Parameters`, any string
containing `${$.path}` is resolved against the envelope before the
handler runs. A string that is exactly one expression
(`"${$.record.data.amount}"`) keeps the value's type (number, boolean,
object); an expression embedded in text
(`"Deal ${$.record.data.name}"`) is stringified. A missing path resolves
to an empty string.

**`ResultPath`** says where the Task's result lands. The default `$`
**replaces the whole envelope** with the result, so the next state no
longer sees `$.record`. Set `"ResultPath": "$.<name>"` on every Task
whose successors still need the record or inputs.

## Task resources

`Resource` names the handler; `Parameters` is its config.

### `CREATE_RECORD`

```json
{
  "Type": "Task",
  "Resource": "CREATE_RECORD",
  "Parameters": {
    "targetCollectionName": "tasks",
    "fieldMappings": [
      { "targetField": "subject", "value": "Follow up: ${$.record.data.name}" },
      { "targetField": "invoice", "value": "${$.record.data.id}" }
    ]
  },
  "ResultPath": "$.created",
  "Next": "Done"
}
```

- `targetCollectionName` (required).
- `fieldMappings[]` — `{"targetField", "value"}` sets a literal or
  `${…}` value; `{"targetField", "sourceField"}` copies a **top-level**
  key of the Task's input (the envelope after `InputPath`), so it only
  reads a record field when the Task sets
  `"InputPath": "$.record.data"`. Prefer `value` with `${…}`.
- Result: `{"record", "id", "recordId", "targetCollectionName",
  "recordData"}`.

### `UPDATE_RECORD`

```json
{
  "Type": "Task",
  "Resource": "UPDATE_RECORD",
  "Parameters": {
    "targetCollectionName": "invoices",
    "recordId": "${$.record.data.invoice}",
    "updates": [
      { "field": "status", "value": "PAID" },
      { "field": "paidAmount", "sourceField": "record.data.amount" }
    ]
  },
  "ResultPath": "$.updated",
  "Next": "Done"
}
```

- `targetCollectionName` (required) and `updates[]` (required,
  non-empty).
- The target record: `recordId` (a literal or `${…}`), or
  `recordIdField`, the name of a top-level key in the Task's input
  holding the id. In a flow, set one of them: a Task does not inherit
  the triggering record's id. To update the triggering record itself,
  use `"recordId": "${$.record.data.id}"`.
- Each update is `{"field", "value"}` or `{"field", "sourceField"}`;
  `sourceField` is a dot path into the Task's input
  (`"record.data.amount"`).
- Result: `{"record", "targetRecordId", "targetCollectionName",
  "updatedFields"}`.

### `FIELD_UPDATE`

```json
{
  "Type": "Task",
  "Resource": "FIELD_UPDATE",
  "Parameters": {
    "updates": [ { "field": "status", "value": "APPROVED" } ]
  },
  "ResultPath": "$.fieldUpdate",
  "Next": "Done"
}
```

- `updates[]` (required, non-empty), each `{"field", "value"}`.
- Record-scoped: it acts on the record the action is invoked for, which
  a flow Task does not pass in. In a flow it **does not change the
  triggering record**; with no record in scope the step fails. Use
  `UPDATE_RECORD` with `"recordId": "${$.record.data.id}"` instead.

### `QUERY_RECORDS`

```json
{
  "Type": "Task",
  "Resource": "QUERY_RECORDS",
  "Parameters": {
    "targetCollectionName": "orders",
    "filters": [ { "field": "customer", "operator": "eq", "value": "${$.record.data.id}" } ],
    "sort": "-createdAt",
    "pageSize": 50,
    "aggregations": [
      { "function": "SUM", "field": "total", "alias": "totalSpent" },
      { "function": "COUNT", "alias": "orderCount" }
    ]
  },
  "ResultPath": "$.orders",
  "Next": "Done"
}
```

- `targetCollectionName` (required).
- `filters[]` — `{"field", "operator", "value"}`. `operator` is one of
  `eq neq gt gte lt lte contains icontains isnull`; anything else
  (including a missing operator) is treated as `eq`.
- `sort` — comma-separated field names, `-` prefix for descending.
- `pageSize` — default `200`; only the first page is returned.
- `aggregations[]` — `{"function", "field", "alias"}`; `COUNT` needs no
  `field`.
- Result: `{"records", "totalCount", "aggregations"}`, e.g. read
  `$.orders.totalCount` in a following `Choice`.

### `EMAIL_ALERT`

```json
{
  "Type": "Task",
  "Resource": "EMAIL_ALERT",
  "Parameters": {
    "to": "${$.record.data.ownerEmail}",
    "subject": "Deal ${$.record.data.name} closed",
    "body": "<p>Closed at ${$.record.data.closedAt}.</p>"
  },
  "ResultPath": "$.email",
  "Next": "Done"
}
```

- `to` (required), `subject` (required unless `templateId`), `body`
  (HTML), `templateId` (an email template; its subject and body replace
  the config's).
- Result: `{"emailLogId", "to", "subject", "status": "QUEUED"}`. The
  email is queued, not sent synchronously.

### `CALL_API`

Raw mode (the default):

```json
{
  "Type": "Task",
  "Resource": "CALL_API",
  "Parameters": {
    "mode": "raw",
    "method": "POST",
    "url": "https://api.example.com/v1/orders",
    "credentialRef": "example-api",
    "headers": { "X-Source": "kelta" },
    "queryParams": { "dryRun": "false" },
    "body": { "ref": "${$.record.data.id}", "amount": "${$.record.data.amount}" },
    "idempotency": { "enabled": true, "key": "${$.record.data.id}", "ttlSeconds": 86400 }
  },
  "ResultPath": "$.api",
  "Next": "Done"
}
```

- `mode` — `raw` (default) or `operation`.
- Raw: `url` (required), `method` (`GET` default; `GET POST PUT PATCH
  DELETE HEAD OPTIONS`), `headers`, `queryParams`, `body`.
- Operation: `specId` and `operationId` (both required; URL and method
  come from the imported OpenAPI spec), `pathParams`, `queryParams`,
  `headers`, `requestBody`. Note the body key differs by mode: `body` in
  raw, `requestBody` in operation.
- `credentialRef` — a vault credential name; auth is applied for you,
  secrets never appear in the flow.
- `idempotency` — `{"enabled", "key", "ttlSeconds"}`; on by default for
  every method except `GET`, `HEAD` and `OPTIONS`.
- `responseMapping` — optional template applied to the response; its
  output lands at `mapped` in the result. The parsed JSON response is
  always at `responseData`, so a following state can read
  `$.api.responseData.<key>` without it.
- Result: `{"statusCode", "url", "method", "responseBody",
  "responseData", "mapped"}`. Failures carry codes `Api.HttpClientError`,
  `Api.HttpServerError`, `Api.Timeout`, `Credential.*`, `Mapper.Failure`
  for `Catch`.

## Complete example: stamp closed deals

`RECORD_TRIGGERED` on `deals`, started only when `stage` changes. A won
or lost deal gets `closedAt` stamped; a won deal also gets a kick-off
activity. Every Task sets `ResultPath`, so later states still see
`$.record`.

```json
{
  "name": "Stamp closed deals",
  "flowType": "RECORD_TRIGGERED",
  "active": true,
  "triggerConfig": {
    "collection": "deals",
    "events": ["UPDATED"],
    "triggerFields": ["stage"]
  },
  "definition": {
    "StartAt": "IsClosed",
    "States": {
      "IsClosed": {
        "Type": "Choice",
        "Choices": [
          { "Variable": "$.record.data.stage", "StringEquals": "CLOSED_WON", "Next": "StampWon" },
          { "Variable": "$.record.data.stage", "StringEquals": "CLOSED_LOST", "Next": "StampLost" }
        ],
        "Default": "StillOpen"
      },
      "StampWon": {
        "Type": "Task",
        "Resource": "UPDATE_RECORD",
        "Parameters": {
          "targetCollectionName": "deals",
          "recordId": "${$.record.data.id}",
          "updates": [ { "field": "closedAt", "value": "${$.record.data.updatedAt}" } ]
        },
        "ResultPath": "$.stamp",
        "Next": "CreateKickOff"
      },
      "StampLost": {
        "Type": "Task",
        "Resource": "UPDATE_RECORD",
        "Parameters": {
          "targetCollectionName": "deals",
          "recordId": "${$.record.data.id}",
          "updates": [ { "field": "closedAt", "value": "${$.record.data.updatedAt}" } ]
        },
        "ResultPath": "$.stamp",
        "End": true
      },
      "CreateKickOff": {
        "Type": "Task",
        "Resource": "CREATE_RECORD",
        "Parameters": {
          "targetCollectionName": "activities",
          "fieldMappings": [
            { "targetField": "subject", "value": "Kick-off call: ${$.record.data.name}" },
            { "targetField": "deal", "value": "${$.record.data.id}" },
            { "targetField": "completed", "value": false }
          ]
        },
        "ResultPath": "$.kickOff",
        "End": true
      },
      "StillOpen": { "Type": "Succeed" }
    }
  }
}
```

Because `triggerFields` lists only `stage`, the flow's own `closedAt`
write cannot start it again.
