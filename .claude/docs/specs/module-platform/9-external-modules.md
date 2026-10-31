# Slice 9 — External Modules: Out-of-Process Add-Ons over HTTP

> **Status:** 🟡 **In progress** — 9a (spec + contract) merged 2026-10-30; 9b (service principal)
> built 2026-10-30, not yet called by install. Planning spec for replacing the
> in-process JAR module path with modules that run as **separate HTTP services, in any language**.
> The machine-readable protocol contract is
> [`docs/module-protocol/openapi.yaml`](../../../../docs/module-protocol/openapi.yaml); this
> document is the design and the slice plan around it.
>
> Source-verified against the codebase on **2026-10-30** at main `0d93c0467` (Flyway directory
> head **V219** (`V219__watch_target_synthetic.sql`); deployed `flyway_schema_history` keeps
> pre-flatten numbering, so always check the directory *and* deployed history before numbering).
> If code and this doc disagree, trust the code and fix this doc.
>
> Conforms to the parent [`README.md`](README.md): **Module status**, **Provenance**, **Tenant
> binding** and **Idempotent / destructive / consent**. It **changes** the parent's security model
> (§1, "What this slice reverses") and supersedes slice 3's manifest v2 (§3.1 there).

## 1. Goal & scope

### Why

Today a module is a signed JAR loaded into the worker JVM by `SandboxedModuleClassLoader`. The
parent spec states the consequence plainly: **the JAR publisher signature is the entire trust
boundary**, capabilities are disclosure rather than containment, and `data:read` / `data:write`
cannot be enforced because a module holds `QueryEngine` directly. Every module must be written in
Java against platform classes, and the worker can never be a native image (deploy hazard 1).

The direction decided on 2026-10-30: **all module code runs out of process.** A module is any
HTTPS service. It publishes a manifest; a tenant admin installs it by URL; the platform calls the
module over HTTP for every piece of logic, and the module calls the platform back through the
public JSON:API under a service principal whose permissions the admin approved.

### What this slice reverses in the parent spec

| Parent spec says (JAR world) | External world |
|---|---|
| The JAR signature is the whole trust boundary | The boundary is the **network + the module's service principal**. Module code never runs in a platform process. |
| `data:read` / `data:write` will not be shipped — a module holds `QueryEngine` | The module reaches data **only** through the public API with its own token, so Cerbos object/field permissions, RLS and rate limits apply. Permissions become real (slice 4's goal, met by a generated profile). |
| A module cannot subscribe to NATS, has no scheduler, cannot read the actor tier | Events are delivered over HTTP (§3.6); the manifest declares schedules (§3.7); the actor rides in the signed request (§3.3). |
| The worker must stay JVM (deploy hazard 1) | No JAR classloading after part 9g. Whether the worker goes native again is a separate decision. |

`moduleUiBundles.ts` still evaluates a module's UI bundle same-origin in the admin session. That
part of the parent's warning **does not change**: the UI bundle is trusted code. This slice pins it
by hash at install so a running module cannot swap it later (§3.9).

### Delivers

1. **Module protocol v1**: HTTPS + JSON, contract-first in `docs/module-protocol/openapi.yaml`.
   Endpoints under `{baseUrl}/kelta/v1/…` for actions, hooks, events, services, routes, webhooks,
   lifecycle and health, plus the manifest at `{baseUrl}/.well-known/kelta-module.json`.
2. **Manifest v2** (§3.1), fetched from the module, embedding a metadata package for everything
   the module provisions.
3. **Trust model** (§3.2–3.4): platform→module requests signed as short-lived JWTs (ES256, JWKS
   published by the worker); module→platform calls with a per-install service principal token.
4. **Install by URL with plan + consent**, reusing slice 3's `planHash` design (§3.5).
5. **Every capability of today's in-process SPI** mapped to an HTTP endpoint (§3.6), so the
   billing module can move without losing behavior.
6. **A Rust reference module**: billing, rewritten as an external service, plus a Rust SDK crate
   (part 9f). Billing also serves as the trial for Rust in this codebase (see §8).
7. **Removal of the JAR path** once billing runs externally (part 9g).

### Does not

- Deploy modules. Kelta stores a base URL; who runs the service (homelab-argo, a third party's
  cloud) is outside the platform.
- Open the closed extension points in the parent's **What is closed** table (credential types,
  `FieldType`, MCP tools, NATS subjects, arbitrary gateway paths).
- Give modules direct database or NATS access.
- Add module-defined system permissions. A route may require an **existing** permission; a
  module minting new permission names stays slice 4.
- Async (long-running) actions. v1 actions are synchronous with an enforced timeout. A task-token
  callback is part 9h, later (§8).
- Module settings UI beyond what the manifest's `settings` schema renders (§3.8).

### The parts (one PR each, in order)

| Part | PR | Security | Depends on |
|---|---|---|---|
| 9a | Protocol contract + this spec (docs only) | — | — |
| 9b | Module service principal: `SERVICE` user type, manifest-generated profile, client_credentials token resolves to it — **built** (V220, `ModuleServicePrincipalService`; install does not call it until 9c) | **yes, no auto-merge** | 9a |
| 9c | Registry + install by URL: manifest v2 parser, plan/consent, metadata provisioning, worker signing key + JWKS, `KELTA_MODULE_ALLOWED_HOSTS` | **yes, no auto-merge** | 9b |
| 9d | Invocation layer: remote actions, before-save hooks, service ports, route + webhook proxies, schedules, timeouts, circuit breaker, `DEGRADED`, metrics | **yes, no auto-merge** | 9c |
| 9e | Event delivery (NATS → HTTP) + UI bundle pinning + Modules page install-by-URL and consent screen | — | 9d |
| 9f | Rust billing module + `kelta-module` Rust SDK; cut the prod tenant over | — | 9e |
| 9g | Delete the JAR path | — | 9f soaked one release |
| 9h | (later) Async actions with task tokens | — | 9d |

Standing acceptance lines for every code part: `/verify` green; a `.claude/changelog.d/` fragment;
the docs rows in §7 that the part touches; tests from §6 for what it builds.

## 2. UI samples

N/A for 9a–9d (backend). Part 9e changes the Modules admin page:

```
┌ Install module ────────────────────────────────────────────────────┐
│ Module URL  [ https://billing.modules.example.com            ]    │
│                                              [ Fetch manifest ]    │
├────────────────────────────────────────────────────────────────────┤
│ Billing · kelta-billing 2.0.0 · by Example Ltd                     │
│ Host: billing.modules.example.com (allowed)                        │
│                                                                    │
│ This module will be able to:                                       │
│   ✔ Read, create, edit billing_plans, billing_customers, … (own)   │
│   ✔ Read users (name, email)                                       │
│   ✔ Read the tenant credential "stripe"                            │
│   ✔ Run on every create of any collection (before-save hook)       │
│   ✔ Receive created/updated events for billing_subscriptions       │
│   ✔ Answer the "entitlements" service for this tenant              │
│   ✔ Run "expire-passes" every hour                                 │
│   ✔ Add a UI bundle to the admin app (sha256 3f9a…c21)             │
│                                                                    │
│ It will create: 5 collections, 23 fields, 2 picklists              │
│ It will adopt (already exist, left unchanged): none                │
│                                                                    │
│                               [ Cancel ]  [ Approve and install ]  │
└────────────────────────────────────────────────────────────────────┘
```

Upgrade shows the same panel as a diff: added/removed grants, created/kept metadata.

## 3. Data & API contracts

The OpenAPI document is authoritative for wire shapes. This section explains the design and the
platform-side behavior the OpenAPI document cannot express.

### 3.1 Manifest v2

Served by the module at `GET {baseUrl}/.well-known/kelta-module.json`, unauthenticated (it holds no
secrets). Schema: `ModuleManifest` in the OpenAPI components.

```jsonc
{
  "manifestVersion": 2,
  "protocolVersion": 1,
  "id": "kelta-billing",                // ^[a-z][a-z0-9-]{1,62}$, stable forever
  "name": "Billing",
  "version": "2.0.0",                   // semver; upgrade requires strictly higher
  "description": "Stripe-backed portal billing",
  "author": "Example Ltd",
  "minPlatformVersion": "0.4.0",
  "permissions": {
    "objects": [ { "collection": "users", "read": true } ],
    "fields":  [ { "collection": "users", "field": "email", "visibility": "READ_ONLY" } ],
    "system":  [],
    "credentials": [ "stripe" ]
  },
  "metadata": { "formatVersion": 2, "items": [ /* PackageImportService items */ ] },
  "settings": { "type": "object", "properties": { "allowedReturnOrigins": { "type": "array" } } },
  "actions":  [ { "key": "create-checkout-session", "name": "Create checkout session",
                  "timeoutMs": 15000, "inputSchema": {}, "outputSchema": {} } ],
  "hooks":    [ { "collection": "*", "events": ["beforeCreate"], "order": 500,
                  "timeoutMs": 2000, "failurePolicy": "fail" } ],
  "events":   [ { "collection": "billing_subscriptions", "types": ["created", "updated"] } ],
  "services": [ { "port": "entitlements", "cacheTtlSeconds": 60 } ],
  "routes":   [ { "path": "me", "methods": ["GET"], "permission": null } ],
  "schedules":[ { "action": "expire-passes", "cron": "0 0 * * * *" } ],
  "webhook":  { "enabled": true },
  "ui":       { "bundlePath": "/kelta/v1/ui-bundle.js", "sha256": "3f9a…c21" }
}
```

Rules the parser enforces (part 9c):

- `manifestVersion: 2` is the external manifest. A manifest without it is the JAR manifest (v1),
  accepted only by the JAR install endpoints until part 9g deletes them. **Slice 3's §3.1
  "manifest v2" (an in-JAR v2) is superseded by this one and will not be built.**
- `protocolVersion` must be one the platform speaks (`1`). The platform calls only the endpoints
  of that version.
- `minPlatformVersion` is checked against the running platform version as slice 3 §3.2 designs it
  (`KELTA_VERSION` → `kelta.platform.version`).
- `metadata` is a **metadata package** (`formatVersion: 2`, the shape `PackageService` exports).
  Item types are limited to **slice 3's allowlist**: `COLLECTION`, `FIELD`, `GLOBAL_PICKLIST`,
  `PICKLIST_VALUE`, `VALIDATION_RULE`, `UI_PAGE`, `UI_MENU`, `UI_MENU_ITEM`. The reasons carry
  over unchanged: these types have natural keys that never involve a source-tenant id; layouts key
  on sort orders; `FLOW` runs with platform powers on a schedule the admin did not author.
  `schedules` (§3.7) replaces the one flow billing needed. Widening the allowlist is §8 open
  question 2.
- Every `actions[].key`, `routes[].path`, `events[].collection` and `hooks[].collection` must be
  unique within its list. A hook or event on a collection the module neither provisions nor has
  `read` on is rejected at plan time.
- `timeoutMs` bounds: actions 1–120000 (default 30000); hooks 100–5000 (default 2000).
- No wildcards in `permissions`. Collections the module provisions through `metadata` get full
  object access implicitly and are listed as "(own)" on the consent screen.

### 3.2 Platform → module: signed requests

Every request the platform sends to a module carries `Authorization: Bearer <assertion>`, a JWS
compact token:

| Claim | Value |
|---|---|
| `iss` | `kelta.modules.issuer`, e.g. `https://api.kelta.io/api/modules` |
| `aud` | the module `id` |
| `sub` | the module install id (`tenant_module.id`) |
| `tenant_id`, `tenant_slug` | the tenant being served |
| `act` | the acting user, when there is one: `{ "sub": userId, "user_type": "INTERNAL"\|"PORTAL", "profile_id": … }` (RFC 8693 actor claim). Absent for webhooks, schedules and events |
| `htm`, `htu` | HTTP method and full target URL (as in DPoP, RFC 9449) |
| `body_sha256` | base64url SHA-256 of the exact request body bytes (of the empty string for GET) |
| `iat`, `exp` | `exp = iat + 60 s` |
| `jti` | random, unique per request |

- **Algorithm ES256.** It is supported by every mainstream JWT library; EdDSA is not yet universal.
- **Keys**: one platform-wide P-256 key pair, configured as a secret
  (`KELTA_MODULE_SIGNING_KEY`, PEM) and declared in `application.yml` as
  `${KELTA_MODULE_SIGNING_KEY:}` (never env-only; see CLAUDE.md pitfalls). The public JWKS is served
  unauthenticated at `GET /api/modules/.well-known/jwks.json` with a `kid` per key, so rotation
  is publish-new-key, sign with it, drop the old one after `exp` plus cache time.
- **The module verifies**: signature against the JWKS for the configured issuer, `aud` equals its
  id, `exp`, `htm`/`htu` match the request, and `body_sha256` matches the body it received. The
  Rust and future SDKs do this in one call. A module that skips verification is accepting
  unauthenticated calls; the protocol doc says so in the security section.
- **Why not HMAC (Standard Webhooks)**: a shared secret per install would have to be delivered to
  the module and stored by it, per tenant. A public-key assertion needs nothing but the issuer URL.
  Google Pub/Sub push authenticates with an OIDC JWT the same way.

Trace context: every request also carries `traceparent` (W3C), so module spans join the platform's
Tempo trace.

### 3.3 Module → platform: the service principal

Each install gets a **service principal**, created by the install plan's apply (part 9b):

- A `platform_user` row with **`user_type = 'SERVICE'`** (new value in
  `platform_user_user_type_check`, which today allows `INTERNAL` and `PORTAL`, V167). A service
  user cannot log in interactively, never receives mail, is not shown in the Users list by
  default, and does not count against the `users` governor limit.
- A **profile generated from `manifest.permissions`** plus full object access on collections the
  module provisions, written to `profile_object_permission` (`can_create/can_read/can_edit/
  can_delete`), `profile_field_permission` (`visibility`) and `profile_system_permission`.
  `CerbosPolicyGenerator` compiles it like any other profile; **no Cerbos change**.
- A **`connected_app`** with `grant_types = ["client_credentials"]` bound to that user. Today
  `connected_app` has no run-as column (V1 baseline) and a client_credentials token carries
  `connected_app_id`, `auth_method=api_key` and `app_scopes` but no profile
  (`KeltaTokenCustomizer`), so the gateway's `RouteAuthorizationFilter` answers
  `403 User identity not resolved`. Part 9b adds `connected_app.run_as_user_id` (V220) and resolves a
  token with one to that user's identity and profile in `UserIdentityResolutionFilter`.
  As built: kelta-auth puts the user's `run_as_user_id`, `user_type=SERVICE`, `profile_id`,
  `profile_name`, `email` and `preferred_username` on the token (`sub` stays the client id),
  refuses the token unless the user is an `ACTIVE` `SERVICE` user of the app's tenant with a
  profile, builds a run-as app as a `client_credentials`-only confidential client whatever its
  other columns say, and gives it a **15-minute** access token. The gateway sets the principal's
  user id from `run_as_user_id`.
- The connected app's **plaintext client secret** is kept by the worker, encrypted with the
  existing `EncryptionService`, because `connected_app` stores only `client_secret_hash` and the
  worker must request tokens with it. As built it lives on the app itself,
  `connected_app.client_secret_enc` (V220), JDBC-only like `run_as_user_id` — neither is a field of
  the `connected-apps` collection, so no generic read path can serve them — and
  `ModuleServicePrincipalService.clientCredentials` decrypts it. Deactivating the principal clears it.
- `ModuleServicePrincipalService` (worker) creates the three rows in one transaction under the
  tenant, validates every collection, field, visibility and system permission against the
  tenant's metadata (`API_ACCESS` is always granted), marks the profile `is_system`, gives the user
  an email under the reserved `.invalid` TLD, then requests a Cerbos sync and publishes
  `kelta.record.changed` for the profile and connected-app collections.

**Token delivery.** The worker requests a client_credentials access token from kelta-auth per
install, caches it until `exp − 60 s`, and sends it on every module request as
**`Kelta-Access-Token`**. The module uses it as a bearer token against the public API
(`/api/…`, JSON:API) for that request's work. Modules therefore **never store a Kelta
credential**: every invocation brings what it needs, and background work is a platform-driven
schedule (§3.7) or event delivery (§3.6), each of which carries a token.

**Credentials.** A module that declared `permissions.credentials: ["stripe"]` reads that tenant
credential with `GET /api/modules/self/credentials/{name}` using its access token. The endpoint
resolves the module from the token's `connected_app_id`, refuses any name not declared in the
installed manifest, and resolves through `CredentialResolverPort` (which already audits resolves,
V188). Security-sensitive: it returns secret material to an external service by design.

### 3.4 What a module may see of a record

Hook and event payloads are built from the query engine's internal record, which can contain
**server-only fields** (encrypted secrets, credential hashes) and fields the module's profile may
not read. The platform therefore filters every outbound record:

1. **Server-only fields are always removed** (`FieldDefinition.serverOnly`).
2. Fields whose `visibility` for the module's profile is `HIDDEN` are removed; `MASKED` fields are
   masked with the existing masking shape.
3. Hook `fieldUpdates` naming a field the profile cannot write are refused, and the hook result is
   treated as a module error (§3.6, failure handling).

This mirrors `CerbosFieldSecurityAdvice` / `CerbosFieldWriteSecurityAdvice`, evaluated for the
module's principal instead of the caller.

### 3.5 Install, upgrade, uninstall

All on the existing `ModuleController`, gated on `CUSTOMIZE_APPLICATION` as today:

| Endpoint | Behavior |
|---|---|
| `POST /api/modules/plan` `{ "baseUrl": "…", "purgeData": false }` | Fetches the manifest (through the module-host guard, §3.10), validates it, dry-runs `metadata` through `ModuleMetadataProvisioner`, diffs against the installed version if any, and returns the plan: grants (objects/fields/system/credentials), capabilities (hooks/events/services/routes/schedules/UI), metadata (create/adopt/keep), blockers, and `planHash` |
| `POST /api/modules/install` `{ "baseUrl": "…", "planHash": "…" }` | Recomputes the plan; refuses with `409` and the new plan when `planHash` differs. Otherwise: creates the service principal (§3.3), provisions metadata (provenance rows as slice 2), fetches and pins the UI bundle (§3.9), registers schedules, writes `tenant_module` with `base_url`, then calls the module's `lifecycle` (`installed`). A non-2xx from `lifecycle` marks the install `FAILED` with `last_error`; metadata stays (provisioning is idempotent) and `POST /{id}/enable` retries |
| `POST /api/modules/{id}/upgrade` `{ "planHash": "…" }` | Same, from the stored `base_url`; requires a strictly higher `version`; regenerates the profile; `lifecycle` (`upgraded`, with `fromVersion`) |
| `POST /api/modules/{id}/enable` / `disable` | Status change + `lifecycle`; disable stops all invocations |
| `DELETE /api/modules/{id}?purgeData=…&planHash=…` | `lifecycle` (`uninstalled`, best effort), removes schedules, deactivates the connected app and the service user, removes `CREATED` resources whose `content_hash` still matches (slice 3 §3.5 contract) |

Every state change publishes `kelta.config.module.changed.<tenantId>.<moduleId>`
(`ModuleConfigEventPublisher`, already exists), and every pod refreshes its **endpoint registry**
from the database. No code is loaded on a pod, so the "install activates immediately" and
"cross-pod divergence" hazards of the JAR path do not apply: a pod that missed an event reads
current rows on its next refresh (part 9d adds a periodic reconcile, every 60 s).

### 3.6 Capability map (nothing lost from the in-process SPI)

| In-process today | External form | Platform side |
|---|---|---|
| `ActionHandler` (`ActionContext` → `ActionResult`) | `POST /kelta/v1/actions/{key}` → `{ success, output, error }` | `RemoteActionHandler`, registered per tenant in `ActionHandlerRegistry` under the module's key, so flows reference it exactly as today. Body carries `input` (`resolvedData`), `config`, record context, and `execution { executionId, flowId, stepName, attempt }`. Header `Idempotency-Key` = `executionId:stepName` (stable across retries of one step) |
| `BeforeSaveHook.beforeCreate/Update/Delete` → `BeforeSaveResult` | `POST /kelta/v1/hooks` → `{ allowed, fieldUpdates, errors[], conflict }` | `RemoteBeforeSaveHook`, registered in `BeforeSaveHookRegistry` with the manifest `order`. `errors[]` map to `ValidationError(field, message, code)`; `conflict` maps to `HookConflictException` (409). Delete hooks may only veto. Shape follows Kubernetes admission webhooks |
| `afterCreate/Update/Delete` | `POST /kelta/v1/events` (async) | Delivered from `kelta.record.changed` (§3.6.1). **Never blocks a write**, which matches today's swallowed after-hook exceptions |
| `getServices()` → `EntitlementProvider.resolve(tenantId, userId)` | `POST /kelta/v1/services/entitlements/resolve` → `MemberEntitlements { planCode, subscriptionStatus, values }` | `ModuleEntitlementService` keeps its contract: any failure or timeout → `MemberEntitlements.EMPTY` (restrictive). Responses cached per `(tenant, user)` for `cacheTtlSeconds` (0–300) |
| Routes `/api/modules/{id}/x/**` | Proxied to `{baseUrl}/kelta/v1/routes/{path}` | Real HTTP pass-through: method, query, body, `Content-Type`; response status and JSON body back to the caller, 1 MiB cap. Module 5xx or timeout → `502`. Optional `permission` checked against the caller before proxying |
| Webhook `/api/modules/webhooks/{tenantId}/{moduleId}` | Proxied to `{baseUrl}/kelta/v1/webhooks` | Raw body and today's lower-cased header allowlist (`x-`, `stripe-`, `signature`, `digest`, `content-type`, `user-agent`) pass through; the module verifies the provider's signature; its status passes back so the provider retries on non-2xx |
| `uiBundlePath` | Fetched and pinned at install (§3.9) | Served at `GET /api/modules/{id}/ui-bundle.js` as today |
| `scheduled_jobs` FLOW → flow → module action (billing's workaround) | Manifest `schedules` (§3.7) | New job type `MODULE_ACTION` |
| (none) | `POST /kelta/v1/lifecycle`, `GET /kelta/v1/health` | Lifecycle on install/upgrade/enable/disable/uninstall; health on demand from `GET /api/modules/{id}/health` |

**Failure handling.** Every call has a hard timeout (the manifest's `timeoutMs`; connect timeout
2 s). The flow `TaskState.timeoutSeconds` field is parsed but not enforced today
(`FlowDefinitionParser` reads it, `TaskStateExecutor` does not); for remote actions the HTTP
timeout is the enforcement.

| Outcome | Action (flow error code) | Hook | Service port | Route |
|---|---|---|---|---|
| 2xx, `success`/`allowed` | output | apply `fieldUpdates` | cache + use | pass through |
| 2xx, `success: false` | `ActionFailed` (message `<error.code>: <error.message>`) | validation errors → `400` | — | pass through |
| Timeout | `ModuleTimeoutException` | `failurePolicy`: `fail` → `503`, `ignore` → proceed | `EMPTY` | `502` |
| Connect error / 5xx / invalid body | `ModuleUnavailableException` | as timeout | `EMPTY` | `502` |
| Module disabled or breaker open | `ModuleUnavailableException` | as timeout | `EMPTY` | `503` |

`TaskStateExecutor` uses a thrown exception's simple class name as the flow error code, so the
remote handler throws `ModuleUnavailableException` (the class today's quarantined handlers throw)
and a new `ModuleTimeoutException`. Existing flow `Catch` clauses on `ModuleUnavailableException`
keep matching.

**Circuit breaker.** Per pod, per install: open after 5 consecutive failures, half-open after
30 s. Opening writes `DEGRADED` with `last_error` (the status exists in `chk_module_status` and has
never been written); the first success after half-open writes `ACTIVE` again.

**Hooks and transactions.** The query engine opens no transaction itself, but hooks run inside one
when the caller has one (`AtomicOperationsController`, `PackageImportService`). A remote hook there
holds a pooled connection (`DB_POOL_MAX` defaults to 10) for up to its timeout. That is why hook
timeouts cap at 5 s, and it is listed in §8.

#### 3.6.1 Event delivery

A queue-group JetStream consumer `worker-module-events` on `kelta.record.changed.>` (one pod
processes each event) looks up installs subscribed to the event's tenant + collection + type and
POSTs `{ eventId, type, collection, recordId, timestamp, data, previousData, changedFields }`,
filtered per §3.4.

- **At least once.** Ack after a 2xx; on failure `nak` with backoff (5 s, 30 s, 2 min, 10 min,
  1 h); after the last attempt write a `module_event_dead_letter` row and ack. Modules dedupe on
  `eventId`.
- **No ordering guarantee** across records. Per record, `timestamp` lets a module discard stale
  updates.
- **`payloadOmitted: true`** events (over `kelta.nats.max-event-payload-bytes`) are forwarded
  without `data`; the module re-reads by id with its token.
- No new NATS subject or stream: the consumer reads `KELTA_RECORDS`, which exists.

### 3.7 Schedules

`schedules[]` become `scheduled_jobs` rows with a new `job_type = 'MODULE_ACTION'` and
`job_reference_id = '<moduleId>:<actionKey>'`, executed by `ScheduledJobExecutorService` (today it
handles `FLOW`, `SCRIPT`, `REPORT_EXPORT`, `DATA_EXPORT`). The action is invoked as in §3.6 with no
`act` claim. Rows are owned by the install and removed on uninstall. This replaces billing's
"a module has no scheduler; create a scheduled flow by hand" workaround.

### 3.8 Settings

`manifest.settings` is a JSON Schema for per-install configuration (billing's
`allowedReturnOrigins`). Values are stored on `tenant_module.settings` (jsonb), edited on the
Modules page through the schema, and sent on every invocation in the `settings` field of the
request body. Secrets never go in settings; they are credentials (§3.3). This is the minimal form
of slice 5; slice 5's `credential-ref` setting type folds into `permissions.credentials`.

### 3.9 UI bundle

At install and upgrade the platform fetches `ui.bundlePath` from the module, checks its SHA-256
against `ui.sha256` (refusing the install on mismatch or absence), stores it in object storage under
`modules/{tenantId}/{moduleId}/{version}/ui-bundle.{sha256}.js`, and serves it from the Kelta
origin at the existing `GET /api/modules/{id}/ui-bundle.js`. It is **never proxied live**: the
bundle runs same-origin in admin sessions, so a compromised or replaced module service must not be
able to change it after the admin approved it.

### 3.10 Module hosts and SSRF

Module base URLs are typed by tenant admins, and in-cluster modules resolve to private addresses,
which `OutboundUrlGuard` refuses unless `KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS` admits them. That
variable also governs `HTTP_CALLOUT`, `CALL_API` and outbound messages, so widening it to the
cluster's pod/service ranges would let any flow reach internal services.

So module traffic gets its **own** allow-list, `KELTA_MODULE_ALLOWED_HOSTS` (declared in
`application.yml` as `${KELTA_MODULE_ALLOWED_HOSTS:}`): a comma-separated list of hostnames or
`*.suffix` patterns that may resolve to private addresses for module calls only. Public hosts need
no entry. `https` is required except for hosts on that list. The guard re-checks every request (DNS
can change after install) and follows no redirects.

### 3.11 Data model (part 9c / 9e migrations)

`tenant_module` gains: `base_url text`, `protocol_version int`, `connected_app_id varchar(36)`,
`service_user_id varchar(36)`, `settings jsonb`,
`ui_bundle_sha256 varchar(64)`, `ui_bundle_key text`. `s3_key`, `jar_checksum`, `jar_signature`,
`module_class` become nullable in 9c and are dropped in 9g.

New: `module_event_dead_letter (id, tenant_id, tenant_module_id, event_id, payload jsonb,
last_status int, last_error text, attempts int, created_at)` with the standard tenant RLS policy.

## 4. DB migrations

None in 9a. Later parts, numbered at their own head+1 when written (head is **V219** today):

- 9b (V220, built): `platform_user_user_type_check` gains `SERVICE`; `connected_app.run_as_user_id`
  (FK `platform_user`) and `connected_app.client_secret_enc` (the kept secret, moved here from
  `tenant_module`).
- 9c: the `tenant_module` columns in §3.11; `scheduled_jobs` accepts `MODULE_ACTION` if a check
  constraint limits `job_type`.
- 9e: `module_event_dead_letter`.
- 9g: drop the JAR columns and `tenant_module_signing_key` (V186), and remove `QUARANTINED`/`STUB`
  from `chk_module_status` once no row holds them.

## 5. File-by-file code changes

9a (this PR): this file; `docs/module-protocol/openapi.yaml`; parent `README.md` slice row +
superseded notes; `3-lifecycle.md` status note; `status.md` modules row; changelog fragment.

Later parts, representative files (each part's PR lists all):

| Part | Files |
|---|---|
| 9b | `kelta-auth/.../service/ConnectedAppRegisteredClientRepository.java`, `KeltaTokenCustomizer.java`; `kelta-gateway/.../UserIdentityResolutionFilter.java`, `authz/RouteAuthorizationFilter.java`; worker `CerbosPolicyGenerator.java`, new `ModuleServicePrincipalService`; user listing / governor-limit counting to exclude `SERVICE` |
| 9c | `runtime-core/.../module/ModuleManifest.java` + `ModuleManifestParser.java` (v2), `ModuleController.java`, `ModuleInstaller.java`, `ModuleMetadataProvisioner.java`, new `ModuleAssertionSigner` + JWKS endpoint, `runtime-core/.../outbound/OutboundUrlGuard.java` (module-host mode), gateway `application.yml` `unauthenticated-paths` += `/api/modules/.well-known` |
| 9d | new `RemoteActionHandler`, `RemoteBeforeSaveHook`, `ModuleHttpClient` (assertion, token, timeouts, breaker, metrics); `ModuleRegistrar.java`, `ModuleHttpController.java`, `ModuleWebhookController.java`, `ModuleEntitlementService.java`, `ScheduledJobExecutorService.java` |
| 9e | new `ModuleEventDeliveryListener` (beside `RecordWebhookPublisher.java`), `NatsSubscriptionConfig.java`; `ModuleController.java` (bundle pin); `kelta-ui/app/src/pages/ModulesPage/` (install by URL, consent, settings form) |
| 9f | `kelta-modules/billing/` (Rust, replaces the Java module), new `kelta-modules/sdk-rust/`; homelab-argo manifests (outside this repo) |
| 9g | delete `SandboxedModuleClassLoader`, `ModuleLoader`, `ModuleJarService`, `ModuleSignatureVerifier`, `ModuleLoadRetryScheduler`, `ModuleLoadFailureClassifier`, signing-key endpoints, `install-jar`; JAR branches in `RuntimeModuleManager`. Compiled-in `KeltaModule`s (`runtime-module-core`, `runtime-module-integration`) stay |

Pitfalls that apply (CLAUDE.md): map the JWKS endpoint as a literal path, never `/**` under `/api`
(`DynamicCollectionRouter`); declare every gating property in `application.yml`; register
`RemoteBeforeSaveHook` instances through the registry API used for tenant hooks today, not as
beans.

## 6. Test plan

- **Unit (worker, runtime-core):** assertion signing — claims, `body_sha256`, `exp`, `kid`;
  manifest v2 parser — every rule in §3.1; plan hashing is stable and changes on any grant or
  metadata change; outcome table in §3.6 for actions, hooks, services, routes (stub HTTP with
  `MockWebServer`/WireMock, both already test dependencies); §3.4 filtering removes server-only and
  hidden fields and refuses unwritable `fieldUpdates`; breaker opens, writes `DEGRADED`, recovers.
- **Unit (auth, gateway):** a client_credentials token for a connected app with `run_as_user_id`
  resolves to that user and profile; one without keeps today's behavior. (9b: `KeltaTokenCustomizerTest`,
  `ConnectedAppRegisteredClientRepositoryTest`, `UserIdentityResolutionFilterTest`.)
- **`kelta-test-harness` scenario** with a WireMock module container: plan → install with
  `planHash` → collections exist → flow step calls the action → hook vetoes a create and rewrites a
  field → route proxy → webhook proxy → event delivered and retried after a 500 → entitlements
  resolve → disable → `ModuleUnavailableException` → uninstall removes unedited `CREATED` resources. Also: a
  module token cannot read a collection it was not granted (Cerbos), across RLS. 9b ships
  `ModuleServicePrincipalScenarioTest` (token claims, granted read/write audited as the user, a
  profile-permission refusal, tenant confinement, plain-app token unchanged, inactive user, seat
  count, no sign-in). The stack's Cerbos is allow-all and its gateway runs with
  `PERMISSIONS_ENABLED=false`, so the object-permission deny is proven on a real PDP by
  `CerbosGeneratedPolicyIT.moduleServicePrincipalReadsOnlyItsGrant` and the plain-app 403 by
  `RouteAuthorizationFilterTest.connectedAppTokenShapes`.
- **Playwright:** Modules page install by URL against the harness's stub module; the consent
  screen lists grants; approve; uninstall.
- **9f:** `cargo test`, `cargo clippy -- -D warnings`, `cargo fmt --check`; the harness scenario
  against the real billing image; on a staging tenant, Stripe test-mode checkout → webhook →
  subscription row → entitlements, compared with the Java module's answers before cutover.

## 7. Docs to update

| Doc | Change | Part |
|---|---|---|
| `.claude/docs/status.md` | Extensibility/modules row: slice 9 state | each part |
| parent `README.md` | slice table row 9; security model + reuse map rewritten for external modules; deploy hazard 1 retired | 9a (notes), 9g (rewrite) |
| `.claude/docs/playbooks.md` | "Ship a runtime-installable module" rewritten for external modules | 9e |
| `.claude/docs/architecture.md` | module invocation path, JWKS + credentials endpoints and their authorization | 9c, 9d |
| `.claude/docs/integrations.md` | module protocol, event delivery semantics | 9d, 9e |
| `.claude/docs/concerns.md` | close the classloader-sandbox and same-JVM items; add the module-host allow-list and hook-in-transaction notes | 9d, 9g |
| `CLAUDE.md` | Module Map: `kelta-modules/` holds external services | 9f |
| `README.md` | `KELTA_MODULE_SIGNING_KEY`, `KELTA_MODULE_ALLOWED_HOSTS` | 9c |
| `kelta-modules/billing/README.md`, `VERIFICATION.md` | rewritten for the Rust service | 9f |

## 8. Risks & open questions

**Risks**

- **Hook latency on the write path.** A remote hook adds a network round trip to every matching
  write and, inside a transaction, holds a pooled connection. Mitigation: 5 s cap, per-collection
  registration, `failurePolicy`, breaker. A wildcard (`*`) hook should be rare; the consent screen
  says "every create of any collection" in words.
- **Secret exposure by design.** `GET /api/modules/self/credentials/{name}` hands a tenant secret
  to an external host. It is limited to declared names, audited, and shown on the consent screen,
  but an admin approving a malicious module gives it the secret. Same trust decision as giving a
  SaaS vendor an API key.
- **UI bundle is still full-trust code** in the admin origin (unchanged from the JAR world).
  Pinning prevents later swaps, not a malicious first version. Sandboxing it (iframe + postMessage
  bridge) is a separate effort.
- **Event delivery load.** One consumer pod handles all module deliveries per event; a slow module
  delays others only through its own retries, because deliveries run concurrently with a per-install
  in-flight cap (default 8).
- **Cutover (9f).** Billing must never stub-succeed. Order: install the external billing module
  on a staging tenant, compare entitlements, then on prod disable the JAR install and install the
  external one in one maintenance step; the merge-train and PreSync hazards in the parent apply.

**Open questions**

1. **`SERVICE` user type vs a separate principal table.** Recommended: the user type, so
   `profile`, Cerbos and audit (`created_by`) work unchanged. Cost: every place that lists users
   or counts seats must exclude it (9b enumerates them).
2. **Metadata allowlist.** `PAGE_LAYOUT`, `LIST_VIEW`, `DASHBOARD`, `REPORT` would let a module
   ship its whole admin UX. The MCP `apply_layout` / `apply_listview` / `apply_dashboard` tools now
   key on `collection + name` / `name`; whether `PackageImportService` matches those types on the
   same natural keys needs checking before they are admitted.
3. **On-behalf-of tokens.** v1 modules act as their own principal and receive the actor as a
   signed claim. Acting *as* the user (RFC 8693 token exchange) would let a route read exactly what
   the caller can. Deferred until a module needs it.
4. **Async actions (9h).** `202` + task token, completed via `POST /api/modules/self/tasks/{token}`.
   Needs a flow-engine state that waits for a callback and resumes with a payload.
   `JdbcFlowStore.claimPendingResumeByEvent` exists but nothing calls it and its query does not
   filter by tenant; fix both there.
5. **Rust trial gate (9f).** Billing in Rust is also the measurement for any later Rust work in the
   platform: clean and incremental build time, image size, RSS, p50/p99 of
   `entitlements.resolve`, CI failures and agent iterations per PR, bugs after deploy, each
   compared with the Java module. Record the numbers in the 9f PR.
