---
title: Runtime modules
description: Install signed JARs into a tenant at runtime — the manifest, install and signing, lifecycle and quarantine, what a module can contribute, and its limits.
section: platform
order: 60
---

A **module** is one JAR plus a `kelta-module.json` manifest, uploaded into a tenant. It runs inside the worker
without a platform redeploy and can contribute flow action handlers, before-save hooks, collections, a webhook
handler, authenticated HTTP routes, a browser UI bundle, and implementations of platform service ports.

## The manifest

```json
{
  "id": "acme-erp-sync",
  "name": "ERP sync",
  "version": "1.2.0",
  "description": "…",
  "author": "Acme",
  "moduleClass": "com.acme.kelta.ErpSyncModule",
  "minPlatformVersion": "1.0.0",
  "permissions": ["MANAGE_DATA"],
  "webhookHandlerKey": "erp:webhook",
  "uiBundlePath": "static/ui-bundle.js",
  "actionHandlers": [
    { "key": "erp:push-order", "name": "Push order to ERP", "category": "ERP", "description": "…", "icon": "upload" }
  ],
  "collections": [
    { "name": "erp-sync-log", "displayName": "ERP sync log", "fields": [ { "name": "order", "type": "LOOKUP", "referenceTarget": "orders" } ] }
  ]
}
```

## Installing

Setup → Integration → Modules (`MANAGE_CONNECTED_APPS`), or:

```http
POST /api/modules/install-jar   (multipart: jar, manifest, signature?)
POST /api/modules/install       (manifest only, no JAR)
POST /api/modules/{id}/enable | /disable
DELETE /api/modules/{id}
GET  /api/modules, /api/modules/{id}/actions, /api/modules/{id}/health
GET  /api/modules/{id}/provisioned-resources   (collections and fields it created or adopted)
```

Install, enable, disable and uninstall check `CUSTOMIZE_APPLICATION` in the API.

Installing **loads** the module immediately on every pod; enable/disable are bookkeeping around that. The JAR is
stored in object storage with its SHA-256 checksum.

## Signing

Set `KELTA_MODULES_SIGNING_REQUIRED=true` in production: a module runs arbitrary Java inside the worker, so the
signature is the trust boundary.

- Trust anchors are **per tenant**: `POST /api/modules/signing-keys` (`MANAGE_CREDENTIALS`) registers a public key;
  several keys may be active so rotation is additive; keys can be retired and reactivated
  (`POST /api/modules/signing-keys/{id}/retire`, `/activate`), and a retired key deleted
  (`DELETE /api/modules/signing-keys/{id}`). `GET /api/modules/signing-keys` lists them.
- Algorithms: Ed25519 (default), SHA256withRSA, ECDSA.
- The detached signature over the JAR bytes is verified at install and **re-verified on every load**, together
  with the checksum.
- With signing required, a tenant with no active key cannot install any JAR.

A legacy platform-wide key (`KELTA_MODULES_SIGNING_PUBLIC_KEY`) is still honoured but logs a warning; prefer
per-tenant keys.

## Lifecycle and failure

A JAR that fails signature, checksum or class loading is **quarantined**: the module records `QUARANTINED` with the
reason, and its handlers throw a clear "module unavailable" error so a flow step fails attributably instead of
silently succeeding. Re-enabling a quarantined module (from the Modules page or `POST /api/modules/{id}/enable`)
reloads it on every worker without a restart, re-verifying it first. If object storage or the database is briefly
unreachable, the module is not quarantined: its actions refuse on that worker while the load is retried
automatically with backoff. `GET /api/modules/{id}/health` reports whether the module is loaded on the answering pod.
Stub mode (`KELTA_MODULES_STUB_MODE`) exists for development only.

Uninstall unloads the module but keeps the collections and data it created; reinstalling skips collections that
already exist.

## External modules (installed by URL)

An **external module** is an HTTPS service instead of a JAR. It publishes a v2 manifest at
`{baseUrl}/.well-known/kelta-module.json` and is installed by URL, in two steps: review a plan, then approve it by
its hash. On the **Modules** page, choose **Install Module → From URL**, enter the base URL, and select **Review**.
The page shows what the module will be granted and what it adds, then **Approve and install**. On a module's card,
**Upgrade** shows the same review as a diff and warns when the upgrade asks for more access. The uninstall
(trash) button shows what will be removed and what is kept.

```http
POST   /api/modules/plan                      { "baseUrl": "https://…" }   (the plan and its planHash; writes nothing)
POST   /api/modules/install                   { "baseUrl": "https://…", "planHash": "…" }
POST   /api/modules/{id}/upgrade              { "planHash": "…" }
GET    /api/modules/{id}/uninstall-plan?purgeData=false
DELETE /api/modules/{id}?planHash=…&purgeData=false
```

- The plan lists what the module asks for and anything that blocks it:
  - object, field and system permissions;
  - the collections and fields its metadata creates or adopts.

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
An external module is called only while its install is `ACTIVE` or `DEGRADED`:

- **Flow actions.** A flow Task whose `Resource` is `<moduleId>:<key>` calls
  `POST /kelta/v1/actions/{key}`, with the manifest's `timeoutMs`. The request has
  `Idempotency-Key: <executionId>:<stepName>`, the same on every retry of one step. A timeout fails the step
  with the error code `ModuleTimeoutException`. Any other failure, or a disabled module, gives
  `ModuleUnavailableException`. A `Catch` can match either code. Work that takes longer than one call is a
  [long-running action](#long-running-actions).
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
"actions": [ { "key": "ingest", "name": "Ingest", "timeoutMs": 10000, "maxDurationSeconds": 7200 } ]
```

The install review shows the duration. When a flow step calls the action, the request carries a task:

```json
"task": { "token": "kmt_…", "expiresAt": "2026-11-01T12:00:00Z" }
```

1. Answer `202 Accepted` within `timeoutMs` to take the work on. The flow step waits; the run shows as
   waiting. (Answering `200` with a result finishes the step at once, as for any action.)
2. When the work is done, send the result, the same shape as a synchronous answer, with the module's
   service-user token:

   ```http
   POST /api/modules/self/tasks/{token}
   Authorization: Bearer <Kelta-Access-Token>
   Content-Type: application/json

   { "success": true, "output": { "rows": 42 } }
   ```

   The answer is `202`, and the step continues with `output` under its `ResultPath`. A
   `"success": false` result fails the step with `ActionFailed`; its `Retry` and `Catch` apply, and a retry
   calls the action again with a new task.
3. If no result arrives by `expiresAt`, the step fails with `ModuleTimeoutException` and the token stops
   working. `expiresAt` is `maxDurationSeconds` from the call, at most one day unless the operator sets
   another limit.

The `Kelta-Access-Token` lasts 15 minutes. While the work runs, renew it:

```http
POST /api/modules/self/tasks/{token}/heartbeat
Authorization: Bearer <Kelta-Access-Token>
```

The answer `{ "accessToken", "accessTokenExpiresAt", "taskExpiresAt" }` holds a token valid for at least
10 more minutes. A heartbeat does not move `expiresAt`.

- The task token is a secret and works once. Keep it only until the task is complete, and never log it.
- Only the module that received the task can complete it, with its own service-user token:
  - `403 NOT_MODULE_PRINCIPAL` or `403 MODULE_NOT_ACTIVE`: as for credential reads;
  - `400 INVALID_TASK_RESULT`: the body is not a valid result (over 1 MiB, or `output` over 1000 keys). Nothing
    is recorded, so correct it and send it again;
  - `404 TASK_NOT_FOUND`: the token is unknown, belongs to another module, was already used, or expired. All
    four get this one answer.
- Only a top-level flow step can wait. Inside a parallel or map branch, a called sub-flow or a manifest
  schedule, the request has no `task`, and a `202` fails the step with `ModuleUnavailableException`.

## What a module can contribute

| Contribution | Mechanism |
|---|---|
| Flow action handlers | listed in the manifest; appear in the designer palette under the module's category and are referenced by `key` |
| Before-save hooks | registered per installing tenant, ahead of platform hooks |
| Collections and fields | created at install through the standard collection API (so they broadcast, get tables and appear in the schema) |
| Inbound webhook | `POST /api/modules/webhooks/{tenantId}/{moduleId}` dispatches the raw body and headers to the manifest's `webhookHandlerKey`. **Unauthenticated; the module verifies the signature itself** |
| Authenticated HTTP routes | served under `/api/modules/{moduleId}/x/**` (`API_ACCESS` enforced by the gateway); an inactive module or unknown route answers a uniform 404 |
| UI bundle | `GET /api/modules/{id}/ui-bundle.js` — a browser bundle that registers page-builder components and field renderers; runs same-origin, protected only by the JAR signature |
| Platform services | `getServices()` returns implementations of platform-defined ports (for example an entitlement provider), registered per tenant |

## What a module cannot do

No Spring beans, no `@RestController`, no scheduler (use a scheduled flow), no NATS subscriptions, no
transactions of its own (writes go through the query engine), no DDL beyond the manifest's collections, no
request context (a hook cannot read the caller's user type), and no access to platform internals outside the
allow-listed packages. Populate collections **before** installing a module that answers platform queries.

## Building one

**An external module** is any HTTPS service that implements the protocol. In Rust, the
[`kelta-module`](https://github.com/kelta-io/kelta/tree/main/kelta-modules/sdk-rust) SDK crate verifies the
platform's assertion, serves the protocol endpoints and calls the platform API. The
[`kelta-modules/billing`](https://github.com/kelta-io/kelta/tree/main/kelta-modules/billing) service is built
on it and is the reference implementation.

**A JAR module** is a Maven project outside the platform build with `runtime-core` and
`runtime-module-integration` as `provided` dependencies: implement `KeltaModule`, package the JAR, sign it,
and install. The legacy
[`kelta-modules/billing-jar`](https://github.com/kelta-io/kelta/tree/main/kelta-modules/billing-jar) build of
billing is the example, including its `README.md` on the trade-offs of the module boundary.
