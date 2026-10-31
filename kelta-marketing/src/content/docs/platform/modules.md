---
title: Runtime modules
description: Install a module into a tenant by URL — an HTTPS service in any language. The manifest, plan and consent, what the platform calls, credentials, long-running actions, lifecycle, and limits.
section: platform
order: 60
---

A **module** is an HTTPS service, written in any language, that a tenant installs **by URL** without a platform
redeploy. Its code never runs inside Kelta: the platform calls the module over HTTP, and the module calls the
platform back through the public API with its own service user. A module can contribute flow actions, before-save
hooks, collections and fields, record-event subscriptions, scheduled actions, an entitlements service, a webhook
receiver, authenticated HTTP routes, and an admin UI bundle.

## The manifest

The module serves its manifest, unauthenticated, at `{baseUrl}/.well-known/kelta-module.json`. Only
`"manifestVersion": 2` is accepted.

```jsonc
{
  "manifestVersion": 2,
  "protocolVersion": 1,
  "id": "acme-erp-sync",                 // stable forever
  "name": "ERP sync",
  "version": "1.2.0",                    // an upgrade needs a strictly higher version
  "description": "…",
  "author": "Acme",
  "minPlatformVersion": "0.4.0",
  "permissions": {
    "objects": [ { "collection": "orders", "read": true } ],
    "fields":  [],
    "system":  [],
    "credentials": [ "erp" ]
  },
  "metadata": { "formatVersion": 2, "items": [ /* collections and fields it provisions */ ] },
  "actions":  [ { "key": "push-order", "name": "Push order to ERP", "timeoutMs": 15000 } ],
  "hooks":    [ { "collection": "orders", "events": ["beforeUpdate"], "timeoutMs": 2000, "failurePolicy": "fail" } ],
  "events":   [ { "collection": "orders", "types": ["created", "updated"] } ],
  "routes":   [ { "path": "status", "methods": ["GET"], "permission": null } ],
  "schedules":[ { "action": "push-order", "cron": "0 0 * * * *" } ],
  "webhook":  { "enabled": true },
  "ui":       { "bundlePath": "/kelta/v1/ui-bundle.js", "sha256": "…" }
}
```

The contract for every field and endpoint is
[`docs/module-protocol/openapi.yaml`](https://github.com/kelta-io/kelta/blob/main/docs/module-protocol/openapi.yaml).

## Installing

Setup → Integration → Modules (`MANAGE_CONNECTED_APPS`), or:

```http
POST   /api/modules/plan                      { "baseUrl": "https://…" }   (the plan and its planHash; writes nothing)
POST   /api/modules/install                   { "baseUrl": "https://…", "planHash": "…" }
POST   /api/modules/{id}/upgrade              { "planHash": "…" }
POST   /api/modules/{id}/enable | /disable
GET    /api/modules/{id}/uninstall-plan?purgeData=false
DELETE /api/modules/{id}?planHash=…&purgeData=false
GET    /api/modules, /api/modules/{id}/actions, /api/modules/{id}/health
GET    /api/modules/{id}/provisioned-resources   (collections and fields it created or adopted)
```

Plan, install, upgrade, enable, disable and uninstall check `CUSTOMIZE_APPLICATION` in the API. Install needs a
`baseUrl`; a request without one is `400 baseUrl is required`.

A successful install is **active at once** on every worker. If the module answers something the platform
already answers (an entitlements service, for example), create and fill its collections **before** installing:
install adopts a collection that already exists and leaves it and its fields alone.

The operator must configure the platform's module signing key (`KELTA_MODULE_SIGNING_KEY`, see
[Configuration](/docs/deploy/configuration/)). Without it every plan is blocked with `SIGNING_NOT_CONFIGURED`.

## External modules (installed by URL)

Every module is an external service. Installing one takes two steps: review a plan, then approve it by its hash.
On the **Modules** page, choose **Install Module**, enter the base URL, and select **Review**. The page shows
what the module will be granted and what it adds, then **Approve and install**. On a module's card,
**Upgrade** shows the same review as a diff and warns when the upgrade asks for more access. The uninstall
(trash) button shows what will be removed and what is kept.

- The plan lists what the module asks for and anything that blocks it:
  - object, field and system permissions, and the credentials it reads;
  - the collections and fields its metadata creates or adopts;
  - its actions, hooks, events, routes, schedules, services and UI bundle.

  The status codes are:
  - `200`: the plan itself, even when it has blockers.
  - `409 PlanChanged`: install, upgrade or uninstall with a `planHash` that no longer matches. Nothing is written, and the fresh plan is in `meta.plan`.
  - `422`: a plan with blockers, one error per blocker.
  - `502 ModuleUnreachable`: the module could not be reached.
- Install provisions the metadata and creates the module's **service user**. It is a `SERVICE` user whose profile
  holds exactly the granted permissions, and it cannot sign in. Install then calls the module's `lifecycle` endpoint.
  If that call fails, the install is `FAILED`, and `POST /api/modules/{id}/enable` retries it.
- Every request the platform sends a module carries:
  - an ES256-signed assertion, as `Authorization: Bearer …`;
  - the service user's access token, as `Kelta-Access-Token`.

  The module verifies the assertion against the public keys at `GET /api/modules/.well-known/jwks.json`.
- Upgrade needs a strictly higher `version` and regenerates the service user's profile.
- Uninstall removes only what the module created and nobody has changed since. Collections that hold data are kept
  unless `purgeData=true`. The service user is deactivated.
- A module host must be public `https`, unless the operator lists it in `KELTA_MODULE_ALLOWED_HOSTS`. Redirects
  are never followed.

### What the platform calls

The contract is
[`docs/module-protocol/openapi.yaml`](https://github.com/kelta-io/kelta/blob/main/docs/module-protocol/openapi.yaml).
A module is called only while its install is `ACTIVE` or `DEGRADED`:

- **Flow actions.** A flow Task whose `Resource` is `<moduleId>:<key>` calls
  `POST /kelta/v1/actions/{key}`, with the manifest's `timeoutMs`. The request has
  `Idempotency-Key: <executionId>:<stepName>`, the same on every retry of one step (a call that carries a
  [task](#long-running-actions) adds `:<attempt>`). A timeout fails the step
  with the error code `ModuleTimeoutException`. Any other failure, or a disabled module, gives
  `ModuleUnavailableException`. A `Catch` can match either code. Work that takes longer than one call is a
  [long-running action](#long-running-actions).

  The signed assertion's `act` names the run's user, with `act.source` saying how the platform chose it:
  `initiator` (the user the run's trigger names), `run_as` (the flow's run-as user) or `owner` (the
  flow's owner). The last two are fallbacks for a run whose trigger names no user. An action that acts
  for the calling member should accept only `initiator` (the Rust SDK's `Actor::is_initiator()`) and
  refuse anything else, including an actor with no `source`. More values may be added, so treat an
  unknown one as a fallback. A route's or hook's `act` is the caller and has no `source`.

  For a record-triggered run, `initiator` is the record's **creator**, not necessarily the user who
  changed the record, and a flow's own record write can set the creator. A retry runs as the original
  run's initiator, whoever asked for it. A run that was still waiting when the platform began recording
  the source sends no `act` when it resumes, so a member-only action refuses it.
- **Before-save hooks.** `POST /kelta/v1/hooks` answers `{ allowed, fieldUpdates, errors, conflict }`:
  - `errors` reject the write with `400`;
  - `conflict` rejects it with `409`;
  - `fieldUpdates` change the record before it is saved.

  A hook's timeout is 100–5000 ms (default 2000). If the module fails or times out, `failurePolicy` decides:
  - `fail` (the default): the write is refused with `503 ModuleUnavailable` / `ModuleTimeout`;
  - `ignore`: the write goes ahead.

  A disabled module's hooks do not run at all.
- **Entitlements.** `POST /kelta/v1/services/entitlements/resolve` answers a member's plan, cached for
  `cacheTtlSeconds`. Any failure means no entitlements, which is the restrictive answer.
- **Routes.** `/api/modules/{moduleId}/x/{path}` is proxied to `/kelta/v1/routes/{path}`, for the declared
  methods only, after the route's `permission` is checked. The answer is your status and JSON:
  - a `5xx` or a timeout is `502 ModuleUnreachable`;
  - a module that cannot be reached is `503`.
- **Webhooks.** `POST /api/modules/webhooks/{tenantId}/{moduleId}` forwards the raw body and the provider's
  signature headers to `/kelta/v1/webhooks`. The module verifies the signature. Its status goes back to the
  sender.
- **Schedules.** Each manifest `schedules[]` entry runs its action on a 6-field cron, in UTC. The request has
  `Idempotency-Key: <jobId>:<fireTime>` and no calling user. A schedule can fire twice, so deduplicate on the
  key.
- **Events.** Each manifest `events[]` entry subscribes to `created`, `updated` or `deleted` changes on one
  collection. After the write, `POST /kelta/v1/events` carries
  `{ eventId, type, collection, recordId, timestamp, data, previousData, changedFields }`. Delivery never slows
  or blocks the write. Any `2xx` acknowledges. Anything else is retried after 5 s, 30 s, 2 min, 10 min and 1 h,
  and then recorded as undelivered. Events arrive at least once and in no set order, so deduplicate on
  `eventId`. A change too large to carry arrives with `payloadOmitted: true` and no `data`; read the record by
  id.
- **Admin UI bundle.** The manifest's `ui` names a bundle path and its SHA-256. Kelta fetches the bundle at
  install and upgrade, checks the hash, and stores the approved copy. It serves only that copy, from
  `GET /api/modules/{id}/ui-bundle.js`. Changing the file on your host has no effect until you ship a new
  version with the new hash. The upgrade review shows that the bundle changes.

Records the module receives are what its service user may read: server-only and hidden fields are left out,
and masked fields arrive masked. After 5 failures in a row, the platform stops calling the module for 30 s
and marks the install `DEGRADED`. The first successful call marks it `ACTIVE` again.

### Reading tenant credentials

A module that needs a tenant secret, such as a payment provider's API key, declares the credential's name in
its manifest's `permissions.credentials`. The admin sees the name on the install review. The tenant creates the
credential under that name; the module never stores a secret of its own. With the `Kelta-Access-Token` of the
current request, the module reads it:

```http
GET /api/modules/self/credentials/{name}?purpose=STRIPE_API:checkout
Authorization: Bearer <Kelta-Access-Token>
```

```json
{ "name": "stripe", "type": "stripe",
  "secrets": { "secretKey": "…", "webhookSecret": "…" },
  "metadata": { "allowedReturnOrigins": "https://app.example.com" } }
```

- `secrets` holds the decrypted secret fields. `metadata` holds the credential's other fields as the admin
  entered them. A list-like value can be a list or a comma-separated string, so accept both.
- Only the module's own service-user token can read, and only names its installed manifest declares:
  - `403 NOT_MODULE_PRINCIPAL`: any other token, including an admin's, a personal access token, or another
    module's token;
  - `403 MODULE_NOT_ACTIVE`: the module is not `ACTIVE` or `DEGRADED`. It is still installing during the
    `installed` lifecycle call, so read credentials on later calls;
  - `403 CREDENTIAL_NOT_DECLARED`: the name is not declared. Names are matched exactly; a credential id is
    never accepted;
  - `404 CREDENTIAL_NOT_FOUND`: declared, but the tenant has no active credential of that name.
- Every read is recorded in the tenant's setup audit trail, with the module's service user as the actor and
  `MODULE:<moduleId>` as the purpose. The optional `purpose` (up to 64 letters, digits and `_ . : -`) is added
  after it, so you can say why you read the secret.
- The answer is `Cache-Control: no-store`. Keep the secret in memory for the request that needs it, and never
  log it or return it to a caller.

### Long-running actions

An action that takes minutes or hours, such as a data import, declares how long it may take:

```json
{ "actions": [ { "key": "ingest", "name": "Ingest", "timeoutMs": 10000, "maxDurationSeconds": 7200 } ] }
```

The install review shows the duration. When a flow step calls the action, the request carries a task:

```json
{ "task": { "token": "kmt_…", "expiresAt": "2026-11-01T12:00:00Z" } }
```

1. Answer `202 Accepted` within `timeoutMs` to take the work on. The flow step waits; the run shows as
   waiting. (Answering `200` with a result finishes the step at once, as for any action.)
2. When the work is done, send the result, the same shape as a synchronous answer plus the task's
   `token`, with the module's service-user token:

   ```http
   POST /api/modules/self/tasks/complete
   Authorization: Bearer <Kelta-Access-Token>
   Content-Type: application/json

   { "token": "kmt_…", "success": true, "output": { "rows": 42 } }
   ```

   The answer is `202`, and the step continues with `output` under its `ResultPath`. A
   `"success": false` result fails the step with `ActionFailed`; its `Retry` and `Catch` apply, and a retry
   calls the action again with a new task. Each attempt has its own `Idempotency-Key`
   (`<executionId>:<stepName>:<attempt>`), so a retry is never answered from a stored `202`.
3. If no result arrives by `expiresAt`, the step fails with `ModuleTimeoutException` and the token stops
   working. `expiresAt` is `maxDurationSeconds` from the call, at most one day unless the operator sets
   another limit. Cancelling the flow run ends the task the same way.

The `Kelta-Access-Token` lasts 15 minutes. While the work runs, renew it:

```http
POST /api/modules/self/tasks/heartbeat
Authorization: Bearer <Kelta-Access-Token>
Content-Type: application/json

{ "token": "kmt_…" }
```

The answer `{ "accessToken", "accessTokenExpiresAt", "taskExpiresAt" }` holds a token valid for at least
10 more minutes. A heartbeat does not move `expiresAt`.

- The task token is a secret and works once. It goes in the request body, never in a URL, because paths
  are recorded in traces and access logs. Keep it only until the task is complete, and never log it.
- Only the module that received the task can complete it, with its own service-user token:
  - `403 NOT_MODULE_PRINCIPAL` or `403 MODULE_NOT_ACTIVE`: as for credential reads;
  - `400 INVALID_TASK_RESULT`: the body is not a valid result (not a JSON object, over 1 MiB, or `output`
    over 1000 keys). Nothing is recorded, so correct it and send it again;
  - `404 TASK_NOT_FOUND`: the token is missing, unknown, belongs to another module, was already used, or
    expired. All of these get this one answer.
- If the answer to a completion is lost (a timeout or a dropped connection), send it again. A
  `404 TASK_NOT_FOUND` on that retry can mean the first attempt was recorded, so treat it as possibly
  delivered and check the flow run, not as a failure to report.
- Only a top-level flow step can wait. Inside a parallel or map branch, a called sub-flow or a manifest
  schedule, the request has no `task`, and a `202` fails the step with `ModuleUnavailableException`.

## Lifecycle and failure

An install moves through these states:

| Status | Meaning |
|---|---|
| `INSTALLING` | the plan is being applied |
| `ACTIVE` | installed and called |
| `DEGRADED` | calls kept failing, so a worker opened its circuit breaker; the module is still called, its health is probed every minute, and the first success makes it `ACTIVE` again |
| `DISABLED` | not called; its hooks and entitlements service are off. Enable it again from its card or `POST /api/modules/{id}/enable` |
| `FAILED` | an install step failed (the reason is in `lastError`); `POST /api/modules/{id}/enable` retries it |
| `UNINSTALLING` | removal in progress |

A failing module never looks like a working one: a flow step that calls it fails with `ModuleUnavailableException`
or `ModuleTimeoutException`. `GET /api/modules/{id}/health` reports the stored status, the last error, the
module's own answer to its health endpoint, and the circuit breaker of the worker that answered (named in
`podName`).

## What a module can contribute

| Contribution | Mechanism |
|---|---|
| Flow actions | the manifest's `actions`; they appear in the designer palette and are referenced by `<moduleId>:<key>` |
| Before-save hooks | the manifest's `hooks`, for the installing tenant only, each with a timeout and a `failurePolicy` |
| Collections and fields | the manifest's `metadata`, applied at install through the standard metadata import (so they broadcast, get tables and appear in the schema); an existing collection is adopted, never changed |
| Record events | the manifest's `events`, delivered after the write with retries |
| Scheduled actions | the manifest's `schedules`, run by the platform scheduler |
| Entitlements | the manifest's `services` entry for the `entitlements` port, asked when the platform gates on a member's plan |
| Inbound webhook | `POST /api/modules/webhooks/{tenantId}/{moduleId}` forwards the raw body and signature headers to the module. **Unauthenticated; the module verifies the signature itself**. An unknown tenant, a module that is not installed or not callable, and a module without a webhook all answer a uniform 404 |
| Authenticated HTTP routes | served under `/api/modules/{moduleId}/x/**` (`API_ACCESS` enforced by the gateway, plus the route's optional `permission`); an undeclared route or a module that is not callable answers 404 |
| UI bundle | `GET /api/modules/{id}/ui-bundle.js` — a browser bundle that registers page-builder components and field renderers. It runs same-origin with the admin's session, so it is fully trusted code: the admin's approval of its pinned SHA-256 is the only protection |

## What a module cannot do

A module reaches tenant data only through the public API with its service user, so it gets exactly the
permissions the admin approved and no database access of its own. It cannot add credential types or field types,
run DDL beyond its manifest's metadata, open gateway paths outside `/api/modules/{moduleId}/x/**`, or subscribe to
platform messaging (record events come over HTTP instead). A hook cannot hold the write's transaction: it answers
within its timeout, and a hook that writes the same record back through the API deadlocks or fails.

## Building one

A module is any HTTPS service that implements the protocol. In Rust, the
[`kelta-module`](https://github.com/kelta-io/kelta/tree/main/kelta-modules/sdk-rust) SDK crate verifies the
platform's assertion, serves the protocol endpoints and calls the platform API. The
[`kelta-modules/billing`](https://github.com/kelta-io/kelta/tree/main/kelta-modules/billing) service is built
on it and is the reference implementation. In another language, implement the same checks by hand: verify the
assertion's signature against the JWKS, its audience, expiry, method, URL and body hash, and reject a replayed
`jti`.
