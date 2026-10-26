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
The response is a JSON:API `flow-executions` resource; the execution id is `data.id` (`attributes.status` is
`RUNNING`). CLI: `kelta flows execute <flowId> --input '{...}'` (the CLI adds the outer wrap for you). MCP:
`execute_flow`. Page-builder `runFlow` actions call the same endpoint.

Two escape hatches: `body.state` supplies a complete pre-built envelope, and `body.test: true` marks the run as a
test.

An inactive flow does not start: the call answers **409** with error code `FLOW_INACTIVE`. The one exception is a
`test: true` run by a caller whose profile holds `MANAGE_WORKFLOWS` — that is how the flow designer's **Test**
button runs a flow before it is activated. A test run by anyone else gets the same 409.

## Inbound webhooks

An `AUTOLAUNCHED`, active flow can be started by an external system without Kelta credentials:

```http
GET  /api/flows/{flowId}/webhook-url      # the public URL to hand out
POST /api/webhooks/{flowId}               # the call the external system makes
GET  /api/webhooks/{flowId}               # only when the flow opts in (below)
```

The JSON body becomes `$.input`, the query parameters `$.query`, and the request's `content-type` and
`user-agent` headers `$.headers`; `trigger.type` is `WEBHOOK`. A parameter that appears once is a string, a
repeated one a list: `?a=1&tag=x&tag=y` gives `$.query.a = "1"` and `$.query.tag = ["x", "y"]`.

- Without further configuration the endpoint is **unauthenticated by design**: the flow id in the URL is the
  shared secret. Hand the URL only to the system that should call it; rotate by recreating the flow, or set a
  `secret` (below).
- The gateway rate-limits the endpoint per client IP: by default 300 requests per minute per IP, shared by **all**
  flow webhooks called from that IP (the bucket is the `/api/webhooks` prefix plus the IP). Over budget the call
  gets `429` with a `Retry-After` header. The budget is configurable per deployment via `RATE_LIMIT_IP_PATHS`
  (see [Configuration](/docs/deploy/configuration/)). The endpoint is exempt from the tenant IP allowlist.
- An unknown flow answers `404`; a flow that is not `AUTOLAUNCHED`, not active or has no definition answers `400`.

### Webhook options

A `webhook` block in the flow's `triggerConfig` widens what the endpoint accepts. Every key is optional, and a
flow without the block keeps the contract above exactly (POST, JSON body, no secret, the JSON:API reply).

```json
{
  "webhook": {
    "methods": ["POST", "GET"],
    "bodies": ["json", "form"],
    "secret": "a-long-random-value",
    "response": { "status": 200, "body": "Unsubscribed", "contentType": "text/plain" }
  }
}
```

| Key | Values | Default |
|---|---|---|
| `methods` | `GET`, `POST` | `["POST"]` |
| `bodies` | `json` (`application/json`, `application/*+json`), `form` (`application/x-www-form-urlencoded`) | `["json"]` |
| `secret` | a non-empty string | none |
| `response` | `{status, body, contentType}` or `{redirect}` | the JSON:API `flow-executions` reply |

- A **form** body becomes `$.input` as a map of field names to strings (a repeated field becomes a list), so
  `List-Unsubscribe=One-Click` gives `$.input["List-Unsubscribe"] = "One-Click"`. Query parameters stay in
  `$.query`, never in `$.input`.
- A method not in `methods` answers `405` with an `Allow` header; a body type not in `bodies` answers `415`.
- **`GET` and `form` require a `secret`.** A link in an email, a browser or a mail client can send them on a
  user's behalf, so a flow that lists either without a `secret` refuses every call with `403`
  ("Webhook configuration is invalid: GET and form bodies require triggerConfig.webhook.secret to be set"),
  as does any other invalid `webhook` block (an unknown method or body type, a blank secret).

### Authenticating with the secret

With a `secret` set, every call to the flow's webhook (POST JSON included) must carry it in one of two ways:

- the header `X-Kelta-Webhook-Secret: <secret>` — for a server-to-server caller; or
- a `sig` query parameter: the lowercase (any case is accepted) hex **HMAC-SHA256**, keyed by the secret, of the
  other query parameters, URL-decoded, written `name=value`, sorted by name (then by value) and joined with `&`.
  Use this for links, where no header can be added. The body is not signed.

A missing or wrong secret or signature answers `401` and starts no run. Both are compared in constant time, and
neither is logged; `sig` is removed from `$.query`.

Worked example: secret `example-secret`, a link carrying `u=42` and `list=news`. The string to sign is
`list=news&u=42`:

```bash
printf 'list=news&u=42' | openssl dgst -sha256 -hmac 'example-secret'
# a9e75d97ecf1257b5afa05ae2fef47ae2a5590cc00f79bca49e0b85f1e075c8f
```

so the link is `https://<tenant host>/api/webhooks/{flowId}?u=42&list=news&sig=a9e75d97ecf1257b5afa05ae2fef47ae2a5590cc00f79bca49e0b85f1e075c8f`.
Kelta's formula and flow language has no HMAC function yet, so compute signed links in the system that builds them
(the code that renders the email, for instance).

The secret is stored in `triggerConfig` in plain text: anyone who can read the flow's metadata can read it. Treat
read access to flows accordingly, and rotate by changing the value. A signed link does not expire; put an
expiry timestamp among the signed parameters and check it in the flow if you need one.

### Custom response

By default the call answers `200` with a JSON:API `flow-executions` document. `response` replaces it once the run
has started:

- `{"status": 202, "body": "Thanks!", "contentType": "text/plain"}` — `status` is any `2xx` (default `200`);
  `body` is at most 4 KB (UTF-8) and optional; `contentType` is `text/plain` (default), `text/html` or
  `application/json`. `204` and `205` cannot carry a body.
- `{"redirect": "https://example.com/unsubscribed"}` — answers `303 See Other` with that `Location`; it must be an
  absolute `http` or `https` URL.

An invalid `response` (a non-2xx status, an oversize body, another content type, a relative redirect) is ignored
and the default reply is sent; the flow still runs. Failures before the run starts (`401`, `403`, `404`, `405`,
`415`) always use the JSON:API error envelope.

### Recipe: one-click unsubscribe (RFC 8058)

Mail providers show an **Unsubscribe** button when a message carries both headers below; pressing it makes the
provider `POST` `List-Unsubscribe=One-Click` as a form body to the URL, with no cookies or credentials. Give a flow
this `triggerConfig`:

```json
{
  "webhook": {
    "methods": ["POST"],
    "bodies": ["form"],
    "secret": "a-long-random-value",
    "response": { "status": 200, "body": "You have been unsubscribed.", "contentType": "text/plain" }
  }
}
```

and send each message with a signed URL naming the recipient:

```text
List-Unsubscribe: <https://<tenant host>/api/webhooks/{flowId}?u=42&list=news&sig=a9e75d97…>
List-Unsubscribe-Post: List-Unsubscribe=One-Click
```

The flow then sees `$.input["List-Unsubscribe"] = "One-Click"` and `$.query.u = "42"`; a Choice on the first and an
`UPDATE_RECORD` keyed by the second does the unsubscribe. Leave `GET` out of `methods` for this URL: link scanners
fetch every `GET` URL in a message, and RFC 8058 requires that a `GET` never unsubscribe on its own. For an
"unsubscribe" link in the message body, point it at a page that asks the reader to confirm, or add `GET` to a
separate flow that only records the request and answers with a `redirect` to that page.

For webhooks that carry the record events Kelta itself emits, see [Outbound webhooks](/docs/api/webhooks/).
