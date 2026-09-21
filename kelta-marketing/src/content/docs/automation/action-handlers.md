---
title: Action handler reference
description: Every action a Task state can run — parameters, outputs and error codes — grouped by data, communication, integration and control, with the not-yet-functional ones called out.
section: automation
order: 20
---

A `Task` state names its handler in `Resource` and passes configuration in `Parameters`. Values in `Parameters`
may use `${$.path}` substitution against the [state envelope](/docs/automation/flow-data/). A handler returns a
result (merged at `ResultPath`) or a failure with an error code that `Retry`/`Catch` can match; a generic failure is
`ActionFailed`.

Record-triggered flows have a **triggering record** (`$.record`); handlers marked *record-scoped* act on it.

## Data

### `CREATE_RECORD`

```json
{ "targetCollectionName": "tasks",
  "fieldMappings": [ { "targetField": "subject", "value": "Follow up" },
                     { "targetField": "invoice", "sourceField": "id" } ] }
```

`value` is a literal; `sourceField` copies a field from the triggering record. Returns the created record.
`createdBy`/`updatedBy` are stamped with the flow's actor.

### `UPDATE_RECORD`

```json
{ "targetCollectionName": "invoices", "recordIdField": "invoice",
  "updates": [ { "field": "status", "value": "PAID" }, { "field": "paid_by", "sourceField": "userId" } ] }
```

`recordIdField` names the field on the triggering record that holds the target's id.

### `DELETE_RECORD`

```json
{ "targetCollectionName": "drafts", "recordIdField": "draft" }
```

### `FIELD_UPDATE` *(record-scoped)*

```json
{ "updates": [ { "field": "status", "value": "APPROVED" } ] }
```

### `QUERY_RECORDS`

```json
{ "targetCollectionName": "orders",
  "filters": [ { "field": "customer", "operator": "eq", "value": "${$.record.data.id}" } ],
  "sort": "-createdAt", "pageSize": 200,
  "aggregations": [ { "function": "SUM", "field": "total", "alias": "total_spent" }, { "function": "COUNT", "alias": "orders" } ] }
```

Returns `{ "records": [...], "totalCount": n, "aggregations": { ... } }`. Operators are the
[filter grammar](/docs/api/querying/#filtering) operators.

### `SQL_QUERY`

```json
{ "sql": "SELECT id, total FROM orders WHERE customer_id = '${$.record.data.id}'", "maxRows": 1000 }
```

Runs inside the tenant's schema (`search_path` is pinned, so unqualified names cannot reach other tenants).
`maxRows` defaults to 1000 and is capped at 10 000. SELECT returns `{ records, rowCount, columns }`; DML/DDL
returns `{ rowsAffected, success }`. Available when the worker has a datasource wired (always, in a normal
deployment). Treat substituted values as untrusted — prefer `QUERY_RECORDS` when it can express the query.

## Communication

### `EMAIL_ALERT`

```json
{ "to": "${$.record.data.email}", "subject": "Invoice ${$.record.data.number}", "body": "…" }
```

or `{ "to": "…", "templateId": "<email template id>" }` to use a tenant email template. Sent through the tenant's
SMTP configuration ([Messaging](/docs/platform/messaging/)).

### `OUTBOUND_MESSAGE`

```json
{ "url": "https://hooks.example.com/kelta", "method": "POST",
  "headers": { "X-Source": "kelta" }, "bodyTemplate": "{\"id\": \"{{id}}\", \"status\": \"{{status}}\"}" }
```

Fire-and-forget webhook; without `bodyTemplate` the record is sent as JSON.

## Integration

### `CALL_API`

The preferred way to call an external API. Two modes:

```json
{ "mode": "operation", "specId": "<api-spec id>", "operationId": "getCustomer",
  "credentialRef": "crm", "path": { "id": "${$.record.data.crm_id}" }, "query": {}, "headers": {},
  "responseMapping": { "name": "$.data.name" } }
```

```json
{ "mode": "raw", "method": "POST", "url": "https://api.example.com/v1/orders",
  "credentialRef": "example-api", "requestBody": { "ref": "${$.record.data.id}" },
  "idempotency": { "enabled": true, "key": "${$.record.data.id}", "ttlSeconds": 86400 } }
```

- `operation` mode resolves URL and method from an imported OpenAPI spec.
- `credentialRef` names a vault credential; the handler applies bearer, basic, API-key or OAuth 2 client
  credentials — secrets never appear in the flow.
- Payload fields accept `${$.path}` placeholders or `=<JSONata expression>`.
- Error codes: `Api.HttpClientError` (4xx), `Api.HttpServerError` (5xx), `Api.Timeout`, `Credential.*`,
  `Mapper.Failure` — catch them individually.

### `HTTP_CALLOUT` (legacy)

```json
{ "url": "https://api.example.com/endpoint", "method": "GET", "headers": {}, "body": "", "responseVariable": "apiResponse" }
```

Captures up to 50 KB of response body under `responseVariable`. Prefer `CALL_API`.

### `INVOKE_SCRIPT`

```json
{ "scriptId": "<script id>", "inputPayload": { "threshold": 10 }, "timeoutSeconds": 30 }
```

or inline `"scriptSource": "record.total > input.threshold"`. Scripts run in a sandboxed JavaScript engine with
`record`, `previousRecord`, `input` and `context` bindings and return the last expression. Scripts are managed
under Setup → Integration → Scripts.

## Control and utility

### `DECISION`

```json
{ "condition": "priority = \"High\" AND amount > 1000",
  "trueActions": [ { "actionType": "FIELD_UPDATE", "config": { "updates": [ { "field": "sla", "value": "4h" } ] } } ],
  "falseActions": [] }
```

Inline if/else: `condition` is a [formula](/docs/data-model/formula-fields/#expression-syntax) evaluated against
the state data, and each branch is a list of nested actions. For branching between states a `Choice` state is
usually clearer.

### `LOG_MESSAGE`

```json
{ "level": "INFO", "message": "Reached step 3 for ${$.record.data.id}" }
```

Writes to the run's step log.

### `SUBMIT_FOR_APPROVAL` *(record-scoped)*

```json
{ "processId": "<approval process id>" }   // optional — auto-detects the collection's process
```

Submits the triggering record; see [Approval processes](/docs/automation/approvals/).

## Registered but not yet functional

These handlers exist in the catalogue and accept their configuration, but the platform side they depend on is not
wired in the current release. They return a success result and write to the step log without doing the work.
Do not build on them yet.

| Handler | Config accepted | Use instead |
|---|---|---|
| `TRIGGER_FLOW` | `{ "flowId" }` — returns `{ "status": "QUEUED" }` | an `InvokeFlow` state |
| `DELAY` | `{ "delayMinutes" \| "delayUntilField" \| "delayUntilTime" }` — returns immediately | a `Wait` state (`Seconds`, `Timestamp`, `TimestampPath`) |
| `SEND_NOTIFICATION` | `{ "userId", "title", "message", "level" }` — logged only | `EMAIL_ALERT`, or `CREATE_RECORD` on a notifications collection |
| `PUBLISH_EVENT` | `{ "topic", "eventType", "dataPayload" }` — logged only | `CALL_API` / `OUTBOUND_MESSAGE` to notify an external system |
| `CREATE_TASK` | `{ "subject", "description", "assignTo", "dueDate", "priority", "status" }` — returns the payload, writes nothing | `CREATE_RECORD` on your tasks collection |

## Handlers from modules

[Runtime modules](/docs/platform/modules/) can register additional handlers; they appear in the designer's palette
under the module's category and are referenced by their `key` in `Resource`.
