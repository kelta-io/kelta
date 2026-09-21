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
POST /api/modules/{id}/enable | /disable
DELETE /api/modules/{id}
GET  /api/modules, /api/modules/{id}/actions, /api/modules/{id}/health
```

Installing **loads** the module immediately on every pod; enable/disable are bookkeeping around that. The JAR is
stored in object storage with its SHA-256 checksum.

## Signing

Set `KELTA_MODULES_SIGNING_REQUIRED=true` in production: a module runs arbitrary Java inside the worker, so the
signature is the trust boundary.

- Trust anchors are **per tenant**: `POST /api/modules/signing-keys` (`MANAGE_CREDENTIALS`) registers a public key;
  several keys may be active so rotation is additive; keys can be retired and reactivated.
- Algorithms: Ed25519 (default), SHA256withRSA, ECDSA.
- The detached signature over the JAR bytes is verified at install and **re-verified on every load**, together
  with the checksum.
- With signing required, a tenant with no active key cannot install any JAR.

A legacy platform-wide key (`KELTA_MODULES_SIGNING_PUBLIC_KEY`) is still honoured but logs a warning; prefer
per-tenant keys.

## Lifecycle and failure

A JAR that fails signature, checksum or class loading is **quarantined**: the module records `QUARANTINED` with the
reason, and its handlers throw a clear "module unavailable" error so a flow step fails attributably instead of
silently succeeding. `GET /api/modules/{id}/health` reports whether the module is loaded on the answering pod.
Stub mode (`KELTA_MODULES_STUB_MODE`) exists for development only.

Uninstall unloads the module but keeps the collections and data it created; reinstalling skips collections that
already exist.

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

Create a Maven project outside the platform build with `runtime-core` and `runtime-module-integration` as
`provided` dependencies, implement `KeltaModule`, package the JAR, sign it, and install. The
[`kelta-modules/billing`](https://github.com/kelta-io/kelta/tree/main/kelta-modules/billing) module in the
repository is the reference implementation, including its `README.md` on the trade-offs of the module boundary.
