---
title: Action handler reference
description: Every action a Task state can run — parameters, outputs and error codes — grouped by data, communication, integration and control, with the retired ones called out.
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
deployment). Prefer `QUERY_RECORDS` when it can express the query.

`${...}` placeholders in `sql` are bound as query parameters, never pasted into the statement, so a
value from the record or input cannot change the SQL. Write a placeholder where a value goes, either bare
(`WHERE total > ${$.input.min}`) or as a whole quoted literal (`'${$.record.data.id}'`). A placeholder cannot
name a table or column, sit inside a longer literal (write `'%' || ${$.input.q} || '%'` instead of
`'%${$.input.q}%'`), or be the whole statement; such a step fails with a message saying so.

## Communication

### `EMAIL_ALERT`

```json
{ "to": "${$.record.data.email}", "subject": "Invoice ${$.record.data.number}", "body": "…" }
```

or `{ "to": "…", "templateId": "<email template id>" }` to use a tenant email template. Sent through the tenant's
SMTP configuration ([Messaging](/docs/platform/messaging/)).

Two optional keys change the message headers:

- `transactional` (boolean, default `true`): mail the recipient must receive, such as a receipt or a password
  notice. It gets no unsubscribe headers and is sent even to an address on the tenant's suppression list. With
  `false` the step skips a suppressed recipient (output `status` is `SUPPRESSED`, which is not a failure) and adds
  the RFC 8058 one-click headers `List-Unsubscribe` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
  Any value other than a boolean fails the step.
- `replyTo`: one plain address (`support@example.com`, no display name or list), sent as `Reply-To`. Placeholders
  are allowed; a value that does not resolve to a single address fails the step.

The step has no key for the sender. `From` is the tenant's `fromAddress` / `fromName`
([Messaging](/docs/platform/messaging/)), else the platform's `EMAIL_FROM_ADDRESS` / `EMAIL_FROM_NAME`. When a
tenant `fromAddress` is relayed through the platform SMTP server (the tenant set a From but no SMTP host of its
own), the message also carries `Sender: <platform from-address>`. `Message-ID` is generated on the domain of
the `From` address. Every flow email carries `Auto-Submitted: auto-generated`.

A non-transactional email whose tenant sets `fromAddress` but relays through the platform SMTP server:

```text
From: Example Alerts <alerts@mail.example.com>
Sender: noreply@platform.example
Reply-To: support@example.com
Auto-Submitted: auto-generated
List-Unsubscribe: <https://<platform host>/api/track/email-unsubscribe?t=<signed token>>
List-Unsubscribe-Post: List-Unsubscribe=One-Click
```

The unsubscribe URL is the worker's tracking base URL (`CAMPAIGN_TRACKING_BASE_URL`, else `EXTERNAL_BASE_URL`)
followed by `/api/track/email-unsubscribe?t=` and a signed token naming the tenant and the recipient address.
Only this HTTPS form is sent, never a `mailto:` one. The route needs no login:
`POST /api/track/email-unsubscribe?t=…` is the one-click unsubscribe (repeating it changes nothing), and
`GET /api/track/email-unsubscribe?t=…` shows a confirmation page whose button sends that `POST`. A `GET` on its own
never unsubscribes, because mail scanners fetch every link in a message. An unsubscribe adds the address to the
tenant's suppression list (reason `UNSUBSCRIBE`), so later non-transactional emails to it are skipped. To handle
unsubscribes in your own flow instead, for example to update a preference field, see the one-click unsubscribe
webhook recipe in [Triggers](/docs/automation/triggers/).

### `OUTBOUND_MESSAGE`

```json
{ "url": "https://hooks.example.com/kelta", "method": "POST",
  "headers": { "X-Source": "kelta" }, "bodyTemplate": "{\"id\": \"{{id}}\", \"status\": \"{{status}}\"}" }
```

Fire-and-forget webhook; without `bodyTemplate` the record is sent as JSON.

The URL must be public: a host that resolves to a loopback, private or link-local address is
refused unless the operator allow-lists that range (`KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS`).
`GET` redirects are followed up to three hops, each checked the same way; other methods do not
follow redirects. The call times out after 60 seconds, and a 4xx/5xx response fails the step.

### `SEND_NOTIFICATION`

```json
{ "userId": "${$.record.data.ownerId}", "title": "Order approved",
  "message": "Order ${$.record.data.number} was approved.", "link": "/app/orders/${$.record.id}", "level": "INFO" }
```

Writes an in-app notification for one user of the tenant. `title` (up to 255 characters) and `message` are
required. `userId` takes a user id or email and defaults to the user who triggered the flow; a recipient who is not a
user of the tenant fails the step. `link` is optional (up to 2048 characters). `level` is `INFO` (default),
`WARNING` or `ERROR`; anything else fails the step.

Output: `{ "notificationId", "userId", "title", "message", "link", "level" }` (`link` only when set).

The recipient reads their own notifications at `GET /api/me/notifications` (newest first, `page[number]` /
`page[size]`, with `meta.unreadCount`) and marks them read with `POST /api/me/notifications/{id}/read` or
`POST /api/me/notifications/read-all`. Nobody else can list or mark them, admins included.

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
- Error codes: `Api.HttpClientError` (4xx), `Api.HttpServerError` (5xx), `Api.Timeout`, `Api.Refused`,
  `Credential.*`, `Mapper.Failure` — catch them individually.
- The URL — the raw `url` or the imported spec's base URL — must be public: a host that resolves to
  a loopback, private or link-local address is refused (`Api.Refused`) unless the operator
  allow-lists that range (`KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS`). `GET`/`HEAD` redirects are
  followed up to three hops, each checked the same way. The call times out after 60 seconds
  (`Api.Timeout`) and reads at most 1 MB; a larger response keeps a truncated `responseBody` and
  no `responseData`.

### `HTTP_CALLOUT` (legacy)

```json
{ "url": "https://api.example.com/endpoint", "method": "GET", "headers": {}, "body": "", "responseVariable": "apiResponse" }
```

Captures up to 50 KB of response body under `responseVariable`. Prefer `CALL_API`.

The URL must be public: a host that resolves to a loopback, private or link-local address is
refused unless the operator allow-lists that range (`KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS`).
`GET` redirects are followed up to three hops, each checked the same way. The call times out
after 60 seconds, reads at most 1 MB, and a 4xx/5xx response fails the step.

### `PUBLISH_EVENT`

```json
{ "topic": "order.approved", "eventType": "order.approved",
  "dataPayload": { "orderId": "${$.input.orderId}", "total": "${$.input.total}" } }
```

Publishes a JSON event to `kelta.event.<tenantId>.<topic>` on the platform's NATS JetStream (stream `KELTA_EVENTS`)
for systems outside Kelta. The tenant id in the subject is inserted by the platform; `topic` is dot-separated
tokens of letters, digits, `-` and `_` — wildcards and empty tokens fail the step. Returns
`{ "topic", "subject", "eventType", "messageKey" }`. The step fails if no NATS publisher is configured or the publish
is not acknowledged. Subject, envelope fields and consuming: [External events](/docs/api/external-events/).

### `INVOKE_SCRIPT`

```json
{ "scriptId": "<script id>", "inputPayload": { "threshold": 10 }, "timeoutSeconds": 30 }
```

or inline `"scriptSource": "record.total > input.threshold"`. Scripts run in a sandboxed JavaScript engine with
`record`, `previousRecord`, `input` and `context` bindings and return the last expression. Scripts are managed
under Setup → Integration → Scripts.

An active script of type `API_ENDPOINT` or `EVENT_HANDLER` can also be run over HTTP — the app's *run script* quick
action does this: `POST /api/scripts/{id}/execute` with `{ "input": { … }, "context"?: { … } }` runs it
synchronously and returns its result. When the script sets `requiredPermission`, the caller's profile must hold
that system permission (`403` otherwise); without it any API user may run the script.

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

### `TRIGGER_FLOW`

```json
{ "flowId": "<flow id>", "input": { "orderId": "${$.input.orderId}" } }
```

Starts another saved flow as a **new, asynchronous** execution and returns `{ "flowId", "executionId" }` without
waiting for it; the run appears under the target flow's runs. The target reads `input` as `$.input.<key>`, and
`$.trigger.type` is `TRIGGER_FLOW`. An unknown `flowId` fails the step. To wait for another flow's result, use an
`InvokeFlow` state instead.

Chains are bounded: each `TRIGGER_FLOW` hop counts one level (carried in the started run's initial input as
`_triggerDepth`, so it also survives a `Wait`), and a step at depth 10 fails with
`… the trigger chain reached MAX_TRIGGER_DEPTH=10` instead of starting another run. A flow that triggers itself,
directly or through other flows, therefore stops after ten hops.

### `SUBMIT_FOR_APPROVAL` *(record-scoped)*

```jsonc
{ "processId": "<approval process id>" }   // optional — auto-detects the collection's process
```

Submits the triggering record; see [Approval processes](/docs/automation/approvals/).

### `ANONYMIZE_USER`

```jsonc
{ "userId": "${$.input.userId}" }   // required
```

Erases a user's personal data and keeps the user's id; the step fails unless the flow's user holds `MANAGE_USERS`.
Result: `{"userId", "alreadyAnonymized"}`. What is erased and kept: [Anonymizing a user](/docs/security/users-and-groups/#anonymizing-a-user).

## Rejected at save

These handlers never did their work. Saving a flow (create or update) with a Task whose `Resource` is one of them —
at any level, including `Parallel` branches and `Map` iterators — returns `400` naming the replacement. A flow saved
before this check still runs, but the step fails with the same message instead of reporting success.

| Handler | Use instead |
|---|---|
| `DELAY` | a `Wait` state (`Seconds`, `Timestamp`, `TimestampPath`) |
| `CREATE_TASK` | `CREATE_RECORD` on your tasks collection |

## Handlers from modules

[Runtime modules](/docs/platform/modules/) can register additional handlers; they appear in the designer's palette
under the module's category and are referenced by their `key` in `Resource`.
