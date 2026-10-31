# Module Platform — Runtime Add-Ons as a First-Class Extension Surface (Parent Spec)

> **Status:** parent planning spec. Authoritative shared contract for turning runtime-installable
> modules from "a JAR that can register flow handlers" into an add-on surface a third party can
> build a real product on: declare what you contribute, declare what you need, get admin approval,
> fail loudly, and be upgradable and removable without orphaning a tenant's data.
>
> The forcing function is the **complete removal of compiled-in billing** from the platform
> (slice 8). When this spec was written, billing already ran as a runtime module on a prod
> tenant — a compiled-in Spring controller resolving a member's entitlements out of a signed,
> uploaded JAR — so the mechanism was proven. What was not proven was everything around it. (Since
> slice 9 billing is an external HTTP service, and part 9g deleted the JAR path.)
>
> Source-verified against the codebase on **2026-10-11** at main `ece7925` (Flyway directory head
> **V206** (`V206__collection_ownership.sql`) — next new migration is **V207**; deployed
> `flyway_schema_history` keeps pre-flatten numbering, so always check the directory *and* deployed
> history before numbering). Originally written 2026-08-31 against head V188; the per-slice state
> and the Known defects below were re-checked against code on 2026-10-11. If code and this doc
> disagree, trust the code and fix this doc.

## How to use this document

This parent defines the cross-cutting architecture once. Child specs each cover one PR-sized slice
with acceptance criteria, exact contracts, DB migrations (or "none"), file-by-file changes, and a
test plan — per the child-spec template in `specs/_archive/app-surfacing/README.md` (sections 1–8; sections
that don't apply state "N/A — reason"). Read this parent first; every child references it.

> **Direction change (2026-10-30): modules move out of process.** Slice 9,
> [`9-external-modules.md`](9-external-modules.md), replaces the in-process JAR path with modules
> that run as separate HTTP services in any language, called over the protocol in
> [`docs/module-protocol/openapi.yaml`](../../../../docs/module-protocol/openapi.yaml). It
> reverses this spec's security model (module code no longer runs in the worker JVM, and
> permissions become enforceable), supersedes slice 3's manifest v2 (3a) while reusing its plan /
> `planHash` / uninstall contracts (3b, 3c), meets slice 4's goal through a generated service
> profile, and folds the minimal form of slice 5 into the manifest. **Part 9g deleted the JAR path
> (built 2026-10-31, pending merge; V225).** The security model, reuse map, module status and deploy
> hazards below are rewritten for external modules; the slice table and known defects keep their
> 2026-10-11 JAR-era record, marked as such.

---

## Security model — read this before designing anything

**Module code never runs in a platform process.** Since slice 9 (part 9g deleted the in-process
JAR path and its sandboxed classloader) a module is a separate HTTPS service. The trust boundary is
the **network plus the module's service principal**:

- **Platform → module:** every request carries a short-lived ES256 assertion
  (`ModuleAssertionSigner`, `KELTA_MODULE_SIGNING_KEY`; public keys at
  `GET /api/modules/.well-known/jwks.json`) bound to the method, URL and body, naming the tenant,
  the install and the acting user. Traffic goes only to the install's `base_url`, through
  `ModuleHostGuard` (`KELTA_MODULE_ALLOWED_HOSTS` for in-cluster `http` hosts), with no redirects.
- **Module → platform:** the module calls the public JSON:API with a token for the install's
  `SERVICE` user, whose generated profile holds exactly the manifest's `permissions` plus the
  collections the module created. Cerbos object/field permissions, RLS and rate limits apply, so
  `permissions` are **enforced**, not disclosed. A tenant secret reaches a module only through
  `GET /api/modules/self/credentials/{name}`, for a name the manifest declared, audited as the
  module.
- **Consent:** an admin installs from a plan (`planHash`) that lists every grant, credential,
  collection and capability; an upgrade that widens access needs re-consent.

What is still trust, not containment:

- **The admin's approval is the trust decision.** The manifest is not signed; whoever controls the
  module host controls what it declares, and a module that was granted a credential or a
  collection can misuse it. Approving a module is like giving a SaaS vendor an API key.
- **The UI bundle is full-trust code.** `moduleUiBundles.ts` evaluates it same-origin with the
  admin session's DOM and cookie access. Pinning by SHA-256 at install stops later swaps, not a
  malicious first version.
- **An in-cluster module must not reach the worker.** The worker trusts the gateway's identity
  headers, so an in-cluster module needs a NetworkPolicy that denies it the worker pods
  (`concerns.md` → External modules).

---

## Reuse map — what already exists

The single most important finding behind this spec: **the platform already has a metadata
provisioning engine, and modules are not using it.**

| Need | Existing thing to reuse | Location |
|---|---|---|
| Provision collections, fields, picklists, validation rules, layouts, flows, UI pages, menus | `PackageImportService` — upserts **13 types by natural key** (`TYPE_ORDER`), SKIP/OVERWRITE, dry-run, per-item report, and (since #1660) resolves a FIELD's LOOKUP target to a system collection such as `users`, routed through `QueryEngine` so NATS broadcast + table DDL fire normally | `kelta-worker/.../service/PackageImportService.java` (831 lines at 2026-10-11) |
| Module HTTP surface | `/api/modules/**` is **already a static gateway route**; `RouteAuthorizationFilter` enforces `API_ACCESS` on static routes and leaves finer checks to the worker. `/api/modules/{id}/x/**` is proxied to the module's `/kelta/v1/routes/{path}`, with an optional per-route `permission` | `RouteConfigService.registerStaticRoutes()`, `ModuleHttpController` → `ExternalModuleProxy.route` |
| Unauthenticated inbound | Generic platform-owned webhook route, forwarded raw to the module's `/kelta/v1/webhooks`; the module verifies the sender | `kelta-worker/.../controller/ModuleWebhookController.java` → `ExternalModuleProxy.webhook` |
| Module → platform data | The public JSON:API under the install's `SERVICE` principal (generated profile, Cerbos, RLS) | `ModuleServicePrincipalService`, `ModuleAccessTokenService` |
| Permission enforcement | Cerbos policies are **generated from `profile_system_permission` rows** and pushed at runtime — one Cerbos action per distinct permission name. **No Cerbos change is needed to add a permission** | `CerbosPolicyGenerator`, `CerbosPolicySyncService` |
| Secrets | The credential vault: encryption, rotation, `MANAGE_CREDENTIALS` gating, and resolve auditing (V188); a module reads a declared credential over HTTP, audited as the module | `ModuleCredentialService` → `CredentialResolverPort` |
| Periodic work | Manifest `schedules` → `scheduled_job` rows of type `MODULE_ACTION` | `ModuleScheduleSync`, `ScheduledJobExecutorService` |
| Record events | `kelta.record.changed` → queue-group delivery to `/kelta/v1/events` with backoff + dead letter | `ModuleEventDeliveryListener` |
| Platform → module services | `ModuleServiceRegistry`, tenant-scoped, identity-based removal; the external entitlements port registers `RemoteEntitlementProvider` | `runtime-core/.../module/service/ModuleServiceRegistry.java`, `ExternalModuleRegistry` |
| i18n | `ui-translations` rows, overlaid client-side over bundled strings | `SystemCollectionDefinitions`, `bootstrapCache.ts` |

`ModuleCollectionProvisioner` was a weaker re-implementation of two of `PackageImportService`'s
thirteen types. Slice 2 retired it: module metadata is now provisioned by `ModuleMetadataProvisioner`
through `PackageImportService` — see [`2-provenance-provisioning.md`](../_archive/module-platform/2-provenance-provisioning.md).

---

## What is closed, and stays closed

| Extension point | State | Decision |
|---|---|---|
| Credential types | `CredentialTypeRegistry` built once from Spring `List<CredentialType>`; no `register()` | **Not opening.** A module *uses* credentials via `credential-ref` settings. A module needing a typed credential leaves a compiled-in residue — a recorded platform limitation, not per-feature debt |
| `FieldType` | Java enum carrying Postgres column-type mappings that drive DDL | **Not opening.** Compose existing types; `JSON` covers structured cases |
| MCP tools | `kelta-mcp` is a separate Spring service — stateless transport, `List<AdminTool>` fixed at construction | **Not opening.** If needed, generate tools from `tenant_module_action` rows over HTTP |
| NATS streams / subscriptions | `JetStreamInitializer` + `NatsSubscriptionConfig` are startup-bound | **Not opening.** `kelta.trigger.<tenantId>.<topic>` is already an open runtime subject space |
| Arbitrary gateway paths | `RouteConfigService.registerStaticRoutes()` is a hardcoded array | **Not opening.** The `/api/modules/{moduleId}/...` prefix gives the same capability with no collision surface |

---

## Shared contracts

### Module status

`INSTALLING` · `INSTALLED` · `ACTIVE` · `DEGRADED` · `DISABLED` · `FAILED` · `UNINSTALLING`
(`chk_module_status`, V225)

- `ACTIVE` — installed and callable; a successful install or upgrade lands here at once.
- `DEGRADED` — a pod's circuit breaker opened on repeated failures; still called through that
  breaker, and the 60 s reconcile probes `GET /kelta/v1/health` and writes `ACTIVE` again on
  success.
- `FAILED` — an install step failed (`last_error`); `POST /api/modules/{moduleId}/enable` retries
  from the stored manifest.
- `DISABLED` — no invocations; hooks and the entitlements port are unregistered.
- `QUARANTINED` and `STUB` belonged to the in-process JAR loader and were removed in part 9g
  (V225 refuses to run while a row still holds one).

**The DB status column is not pod reality.** The circuit breaker is per pod;
`GET /api/modules/{id}/health` reports the stored status, the module's own health answer and the
answering pod's breaker, and names the pod.

### Unavailable module

A module that cannot be called fails the caller with a specific, attributable error:
`RemoteActionHandler` is registered for every declared action whatever the status and refuses at
call time, so a flow step fails with `ModuleUnavailableException` (or `ModuleTimeoutException`, its
subclass) — never `ResourceNotFound`, which reads to an admin as a mistyped action key, and never a
success.

### Provenance

Every resource a module provisions is recorded in `module_provisioned_resource` with:

- `ownership` — `CREATED` (the module made it) vs `ADOPTED` (it already existed and the module
  reused it). **Uninstall may only ever remove `CREATED`.**
- `content_hash` — the content at provisioning time. A differing hash means the tenant has edited
  it since, so uninstall leaves it and reports it. This is what stops uninstall silently reverting
  an admin's work.

### Tenant binding

Any platform code that calls into a module **must** bind `TenantContext` with **id and slug**. A
module cannot resolve a slug (`TenantSlugResolver` is platform-side), and a null slug does not fail
— the query engine reads the *public* schema instead of the tenant's, answering from the wrong data.
This is the exact defect fixed in #1390; it is a contract, not a preference.

### Idempotent / destructive / consent

- **Idempotent:** install/upgrade by URL (bound to a `planHash`), package import (natural-key
  upsert), permission upsert, provenance upsert, schedule sync, registry refresh.
- **Destructive, never automatic:** dropping collections or fields, deleting records, deleting a
  tenant-modified metadata item, revoking a permission grant an admin made.
- **Explicit admin consent:** install (capability + permission approval via `planHash`), downgrade,
  `purgeData`, `OVERWRITE` on tenant-modified metadata, an upgrade whose plan removes an action key
  a live flow references.

---

## Slice plan

> **JAR-era record.** Rows 0–8 record the state verified on 2026-10-11, when modules were
> in-process JARs. Part 9g deleted that code (`RuntimeModuleManager`, the classloader, quarantine,
> stub mode, load retry, `install-jar`, `ModuleRouteRegistry`, `getServices()`), so where a row
> names it, read it as history. The external equivalents are in slice 9: routes carry an optional
> `permission`, the manifest declares `services` and the plan shows them, `DEGRADED` is written by
> the breaker, and lifecycle (plan, upgrade, uninstall plan) is built.

| Slice | Child spec | Axis | State (verified 2026-10-11) |
|---|---|---|---|
| 0 — Fail closed | not needed — slice shipped | **backend, security** — kill the silent stub fallback | ✅ **Shipped.** CI job `worker-image-is-jvm` in `.github/workflows/ci.yml` asserts `build-and-publish-containers.yml` builds the worker from `kelta-worker/Dockerfile.jvm`, and is in `quality-gate`'s `needs`. `V189__module_fail_closed.sql` adds `QUARANTINED`/`DEGRADED`/`STUB` to `chk_module_status` and `tenant_module.last_error`, `last_error_at`, `last_loaded_at`, `load_attempts`. `RuntimeModuleManager` quarantines on signature, checksum or classload failure (`quarantine(...)` registers `createQuarantinedHandler`, which throws `ModuleUnavailableException` rather than returning a failure, because `TaskStateExecutor` would relabel any failed `ActionResult` as `ActionFailed`). Stub handlers only under the explicit `kelta.modules.stub-mode` opt-in (`application.yml` `${KELTA_MODULES_STUB_MODE:false}`, wired in `ModuleConfig`). `GET /api/modules/{id}/health` (`ModuleController`) reports `loadedOnThisPod` + `podName`. `ModulesPage` installs a signed JAR through `/api/modules/install-jar`. Test: `RuntimeModuleManagerQuarantineTest`. **Follow-up (2026-10-11, module-load-transient-retry):** quarantine is now reserved for permanent failures; a transient one (storage/DB unreachable) fails closed on the pod, records `last_error` via `ModuleStore.recordLoadError` without touching the status, and is retried per pod by `ModuleLoadRetryScheduler` (`kelta.modules.load-retry.*`); re-enabling a quarantined module reloads it on running pods (`ensureLoaded`); health reports `loadStateOnThisPod`. Test: `RuntimeModuleManagerLoadRetryTest`. Not done: `STATUS_DEGRADED` exists in `TenantModuleData` but nothing writes it |
| 1 — Billable-channel guard | not needed — slice shipped | backend (money risk, ~20 lines) | ✅ **Shipped in code.** `AlertDispatchService.java` declares `BILLABLE_CHANNELS = Set.of(CHANNEL_SMS)` and, when no `channels` entitlement resolves, delivers only the non-billable requested channels. `WatchController.java` applies the same filter when it narrows a watch's requested channels. SMS therefore goes out only on an affirmative entitlement. Gap: no unit test pins the "no entitlement → SMS dropped" case (`AlertDispatchServiceTest.noEntitlementMeansNoGating` covers `push` only); worth adding with the next change to either file |
| 2 — Provenance + unified provisioning | [`2-provenance-provisioning.md`](../_archive/module-platform/2-provenance-provisioning.md) | backend (DB, reuse `PackageImportService`) | ✅ **Shipped** (KLT-504). `ModuleMetadataProvisioner` translates the manifest's `collections` into a package and applies it through `PackageImportService.importPackage` (dry run, then a filtered apply pass so an existing collection is adopted with none of its fields written); `ModuleCollectionProvisioner` is deleted. `module_provisioned_resource` (V190) gets one row per `COLLECTION` and `FIELD`, with `resource_id` and a SHA-256 `content_hash` for `CREATED` rows (null for `ADOPTED`); `ModuleProvenanceStore.record` never downgrades a `CREATED` row. `GET /api/modules/{moduleId}/provisioned-resources` lists the caller tenant's rows, including after uninstall. Left for slice 3: nothing calls `deleteForModule` and uninstall removes nothing |
| 3 — Lifecycle | [`3-lifecycle.md`](3-lifecycle.md) | backend (manifest v2, install plan, upgrade, uninstall) | 🔴 **Not started — spec written** (KLT-537), split into three PRs: 3a manifest v2 + `minPlatformVersion` check against `KELTA_VERSION`, 3b install plan + `planHash` + upgrade, 3c uninstall of unedited `CREATED` resources with `purgeData` consent; no migration. In code, installing over an existing module id still throws "already installed" (`RuntimeModuleManager.installModule` / `installModuleWithJar`); no manifest v2, install plan or `planHash` |
| 4 — Permission catalog | `4-permission-catalog.md` (not written) | **backend + UI, security** | 🔴 **Not started.** `manifest.permissions` is parsed by `ModuleManifestParser` and read by no other main code; no `system_permission` catalog table |
| 5 — Module settings | `5-module-settings.md` (not written) | backend + UI (credential-ref) | 🔴 **Not started.** No settings in `ModuleManifest`, DB, SPI, API or UI |
| 6 — Module HTTP routes | `6-module-routes.md` (not written) | **backend, security** | 🟡 **Partial, built ahead of its spec.** `ModuleManifest.RouteManifest` (`path`, `methods`, `handlerKey`), `ModuleRouteRegistry` (registered on load, after the code loaded) and `ModuleHttpController` (`/api/modules/{moduleId}/x/**`, depth-1..3 patterns) dispatch module routes; the health endpoint reports `routesRegisteredOnThisPod`. Not done: a route declares no permission, so the only check is the gateway's `API_ACCESS` on the static `/api/modules/**` route |
| 7 — Capability gating | `7-capability-gating.md` (not written) | **security** (closes an open hole) | 🟡 **Partial.** `RuntimeModuleManager.registerTenantServices` refuses any port in `getServices()` the manifest's `services` list does not declare, and withdraws the module's already-accepted ports. Not done: no capability list and no admin approval of declared ports at install |
| 8 — Billing removal | `8-billing-removal.md` (not written) | **the proving slice** — ends with a Flyway drop | ✅ **Done 2026-10-12.** Code removed 2026-10-11; Deleted from the worker: `service/billing/` (`EntitlementServiceImpl`, `ModuleAwareEntitlementService`, `BillingPassExpirySweep`, `BillingEntitlementRuleCache`), every `repository/Billing*` class, `MemberEntitlementQuotaHook`, `BillingPlanRefreshHook`, `BillingEntitlementRuleRefreshHook`, `BillingEntitlementCacheListener` and its NATS subscription; from runtime: `BillingEntitlementChangedPayload` and the `KELTA_BILLING` stream; from the gateway: the `/api/billing/**` static route and its authoritative-path entry. `V207__drop_billing_webhook_event.sql` drops the orphan dedupe table. The worker's only `EntitlementService` is `service/entitlement/ModuleEntitlementService`: the tenant's module `EntitlementProvider`, else `MemberEntitlements.EMPTY` — what a tenant without billing always got (watches uncapped, push/email ungated, SMS never sent). `V209__drop_legacy_billing_tables.sql` drops the five V178 `billing_*` tables and their `billing-*` system collection rows, and the definitions are gone from `SystemCollectionDefinitions` |
| 9 — External modules | [`9-external-modules.md`](9-external-modules.md) | **backend + UI, security** — modules as out-of-process HTTP services, any language | ✅ **Built** (verified 2026-10-31) — parts 9a–9h: contract, service principal, install by URL, invocation layer, events + UI pin, credential read, Rust SDK + billing module (every billing tenant cut over 2026-10-31), long-running actions, and 9g JAR-path removal (pending merge, V225). Not built: the settings form, a dead-letter list or replay |

Slice 8 exercises every earlier slice: routes (checkout/portal), webhooks (Stripe), settings
(`credential-ref` to the Stripe key), permissions (`MANAGE_BILLING`), provisioning (five
collections), schedules (pass expiry), a published service port (`EntitlementProvider`), and
fail-closed (billing must **never** stub-succeed).

---

## Known defects this spec exists to fix

Each was source-verified as of 2026-08-31 and **re-checked against code on 2026-10-11**. Each
entry leads with its state at that date.

> **After slice 9 (2026-10-31).** The JAR path these entries describe was deleted in part 9g, so
> 1, 2, 4, 5, 8 and 9 are moot as written. For external modules: `permissions` are enforced through
> the service principal's generated profile and `minPlatformVersion` is checked at install and
> upgrade (3); declared `services` are shown in the plan an admin approves (4); upgrade is
> `POST /api/modules/{id}/upgrade` with re-consent (5); uninstall removes unedited `CREATED`
> resources (6); settings have a column but no form (7); calls and breaker transitions have
> metrics and the breaker writes `DEGRADED` (8); a 60 s reconcile repairs a pod that missed an
> event (9). `concerns.md` tracks defects 1 and 2 (one combined entry,
"FIXED — a rejected module reported success"); it has no entry for 3–9, so this list is their
record until a slice closes them.

1. **FIXED (2026-08-31; `concerns.md` → "FIXED — a rejected module reported success") — the stub
   fallback reported success.** `RuntimeModuleManager.loadFromJar` caught every exception and
   called `loadWithStubs`; `createStubHandler` returned
   `ActionResult.success({"status":"EXECUTED","mode":"stub"})` while the module reported `ACTIVE`.
   Fixed in `RuntimeModuleManager.java`: load failure calls `quarantine(...)`, whose handlers throw
   `ModuleUnavailableException`, records `QUARANTINED` with the reason in `tenant_module.last_error`
   (V189), and stubs remain only behind `kelta.modules.stub-mode`. Test:
   `RuntimeModuleManagerQuarantineTest`.
2. **FIXED (2026-08-31; same `concerns.md` entry) — the admin UI could not install a signed JAR.**
   `ModulesPage` only called `/api/modules/install` (manifest JSON). Fixed in
   `kelta-ui/app/src/pages/ModulesPage/ModulesPage.tsx`: with a JAR selected it posts multipart to
   `/api/modules/install-jar`.
3. **OPEN — `manifest.permissions` and `minPlatformVersion` are dead.** Still parsed by
   `ModuleManifestParser`, stored, and read by no other main code. A module author would
   reasonably believe both work. Slice 4 (permissions) and slice 3 (`minPlatformVersion`).
4. **PARTIAL — `getServices()` was ungated.** `RuntimeModuleManager.registerTenantServices` now
   refuses any port the manifest's `services` list does not declare and withdraws the module's
   already-accepted ports, so a module can no longer publish an undeclared port such as
   `EntitlementProvider`. Still open: nothing shows the declared ports to an admin or asks them to
   approve them at install (slice 7).
5. **PARTIAL — no upgrade path.** The orphan-JAR half is FIXED: `installModuleWithJar` now runs the
   duplicate check before `jarService.uploadJar` (`RuntimeModuleManager.java`, commented "Duplicate
   check BEFORE the upload"). Still open: v2 over v1 is rejected as "already installed" on both
   install paths (slice 3).
6. **PARTIAL — uninstall leaves data behind.** The provenance half is FIXED (slice 2):
   `module_provisioned_resource` records every `COLLECTION` and `FIELD` a module created or adopted,
   with a content hash for `CREATED` rows, ownership survives uninstall + reinstall, and
   `GET /api/modules/{moduleId}/provisioned-resources` lists a removed module's leftovers. Still
   open: uninstall removes nothing — removing `CREATED` resources whose hash still matches is
   slice 3.
7. **OPEN — no module settings** anywhere: manifest, DB, SPI, API or UI (slice 5).
8. **PARTIAL — observability.** Done: `GET /api/modules/{id}/health` returns `isLoaded()` as
   `loadedOnThisPod` with `podName`, `routesRegisteredOnThisPod` and the V189 load diagnostics
   (`last_error`, `last_error_at`, `last_loaded_at`, `load_attempts`); `FAILED` is reachable again
   (a provisioning failure sets it). Load-failure metrics now exist (2026-10-11):
   `kelta_worker_module_load_failures{kind,phase}`, `kelta_worker_module_load_recovered` and the
   alertable gauge `kelta_worker_module_load_retry_pending`, and the health endpoint adds
   `loadStateOnThisPod` (`LOADED`/`RETRYING`/`FAILED`/`NOT_LOADED`). Still missing: no metrics for
   handler invocations or errors per module, no per-module attribution of handler failures beyond
   the log line, and `DEGRADED` is defined but never written.
9. **OPEN — cross-pod divergence is silent.** Install/enable still rely on a fire-and-forget NATS
   event (`ModuleEventListener`) with no ack and no periodic reconcile. The health endpoint makes
   divergence *observable*, one pod per request; nothing detects or repairs it.

---

## Deploy hazards that apply to every slice

1. **Retired (part 9g).** The worker had to stay JVM because a native image cannot classload an
   uploaded JAR. 9g deleted the JAR loader, so modules no longer constrain the worker's image.
   Production still runs the JVM worker and the `worker-image-is-jvm` CI job still pins
   `Dockerfile.jvm`: going native again is a separate decision with its own validation.
2. **The merge-train trap.** The deploy workflow sets `cancel-in-progress: true` and filters paths
   against the previous push, so merging a second PR before the first's build completes can leave
   merged code unbuilt with green CI. In slice 8 that means a migration deploying without its code,
   or the reverse. **Merge one, wait for green, verify the ArgoCD tag moved.**
3. **The migrate Job is an ArgoCD PreSync hook** — it runs to completion while the *old* pods still
   serve traffic. A drop migration therefore removes tables underneath running readers, which is
   why 8f ships at least one release after 8e has soaked.
4. **A module ships on its own pipeline.** A change under the `kelta-modules` Rust workspace builds
   and pushes that module's image and bumps its homelab-argo overlay (`build-and-push-modules`,
   `rust_modules` filter) — it is not part of the platform deploy, and a new version reaches a
   tenant only through an upgrade (plan + re-consent).
5. **Install activates immediately.** A successful install is `ACTIVE` at once on every pod: its
   actions, hooks and entitlements port register as soon as the change event lands. On a tenant
   with existing data, populate the collections first (`playbooks.md` → Ship a runtime-installable
   module).

---

## Docs to update (per CLAUDE.md Rule 6)

- `.claude/docs/status.md` — Extensibility/modules row per slice; the portal-billing row shrinks to
  nothing at slice 8.
- `.claude/docs/concerns.md` — close the silent-stub and ungated-`getServices()` items; add the
  entitlement-caching regression (slice 8d).
- `.claude/docs/playbooks.md` — "Ship a runtime-installable module" gains routes, settings,
  permissions, upgrade; the install-ordering note already lands there.
- `.claude/docs/architecture.md` — module HTTP dispatch; `/api/billing/**` row removed at 8b.
- `CLAUDE.md` — Module Map, the `specs/` reference row (this parent), messaging table at 8c.
- `kelta-modules/billing/README.md` + `VERIFICATION.md` — the module becomes the reference add-on.
