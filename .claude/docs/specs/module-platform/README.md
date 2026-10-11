# Module Platform — Runtime Add-Ons as a First-Class Extension Surface (Parent Spec)

> **Status:** parent planning spec. Authoritative shared contract for turning runtime-installable
> modules from "a JAR that can register flow handlers" into an add-on surface a third party can
> build a real product on: declare what you contribute, declare what you need, get admin approval,
> fail loudly, and be upgradable and removable without orphaning a tenant's data.
>
> The forcing function is the **complete removal of compiled-in billing** from the platform
> (slice 8). Billing already runs as a runtime module on the `<tenant-slug>` prod tenant — a
> compiled-in Spring controller resolving a member's entitlements out of a signed, uploaded JAR —
> so the mechanism is proven. What is not proven is everything around it.
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
test plan — per the child-spec template in `specs/app-surfacing/README.md` (sections 1–8; sections
that don't apply state "N/A — reason"). Read this parent first; every child references it.

---

## Security model — read this before designing anything

`SandboxedModuleClassLoader.ALLOWED_PARENT_PREFIXES` includes **`java.` and `javax.` wholesale**. A
loaded module can open sockets, read the filesystem, spawn threads, and call `System.exit` — inside
the worker JVM, with the worker's database connection. On the browser side, `moduleUiBundles.ts`
evaluates publisher JavaScript from a blob URL, same-origin, with the admin session's full DOM and
cookie access.

**The JAR publisher signature is the entire trust boundary.**

Everything this spec calls a *capability* is **disclosure and coarse gating at platform chokepoints
— not containment**. A capability tells an admin what a module intends to reach and lets the
platform refuse at a few specific call sites. It does not confine the module.

This must stay stated plainly. The risk of leaving it implicit is that `capabilities: [...]` in a
manifest reads like a sandbox to a future maintainer, who then relaxes the signature gate on that
assumption. `RouteAuthorizationFilter` already gets this right: designating a module signing key is
gated on `MANAGE_CREDENTIALS` precisely because it is a grant of code-execution authority.

Corollaries that follow from this and are **not** open questions:

- Per-module resource quotas, thread isolation, and `SecurityManager`-style sandboxing are not
  buildable on a supported JVM mechanism. Do not design as if they exist.
- `data:read` / `data:write` capabilities will not be shipped. A module holds `QueryEngine`
  directly; the platform would not be enforcing them, and a capability the platform does not
  enforce is worse than no capability at all.

---

## Reuse map — what already exists

The single most important finding behind this spec: **the platform already has a metadata
provisioning engine, and modules are not using it.**

| Need | Existing thing to reuse | Location |
|---|---|---|
| Provision collections, fields, picklists, validation rules, layouts, flows, UI pages, menus | `PackageImportService` — upserts **13 types by natural key** (`TYPE_ORDER`), SKIP/OVERWRITE, dry-run, per-item report, and (since #1660) resolves a FIELD's LOOKUP target to a system collection such as `users`, routed through `QueryEngine` so NATS broadcast + table DDL fire normally | `kelta-worker/.../service/PackageImportService.java` (831 lines at 2026-10-11) |
| Module HTTP surface | `/api/modules/**` is **already a static gateway route**; `RouteAuthorizationFilter` enforces `API_ACCESS` on static routes and leaves finer checks to the worker | `kelta-gateway/.../service/RouteConfigService.java:190` |
| Unauthenticated inbound | Generic platform-owned webhook route + tenant binding | `kelta-worker/.../controller/ModuleWebhookController.java` |
| Permission enforcement | Cerbos policies are **generated from `profile_system_permission` rows** and pushed at runtime — one Cerbos action per distinct permission name. **No Cerbos change is needed to add a permission** | `CerbosPolicyGenerator`, `CerbosPolicySyncService` |
| Secrets | The credential vault: encryption, rotation, `MANAGE_CREDENTIALS` gating, and resolve auditing (V188) | `CredentialResolverPort` via `ModuleContext.getExtension(...)` |
| Periodic work | `scheduled_jobs` rows of type `FLOW` → a flow whose Task calls a module action key | `ScheduledJobExecutorService` |
| Module→platform services | `ModuleServiceRegistry`, tenant-scoped, identity-based removal | `runtime-core/.../module/service/ModuleServiceRegistry.java` |
| i18n | `ui-translations` rows, overlaid client-side over bundled strings | `SystemCollectionDefinitions`, `bootstrapCache.ts` |

`ModuleCollectionProvisioner` is a weaker re-implementation of two of `PackageImportService`'s
thirteen types. Slice 2 retires it — see [`2-provenance-provisioning.md`](2-provenance-provisioning.md).

---

## What is closed, and stays closed

| Extension point | State | Decision |
|---|---|---|
| Credential types | `CredentialTypeRegistry` built once from Spring `List<CredentialType>`; no `register()` | **Not opening.** A module *uses* credentials via `credential-ref` settings. A module needing a typed credential leaves a compiled-in residue — a recorded platform limitation, not per-feature debt |
| `FieldType` | Java enum carrying Postgres column-type mappings that drive DDL | **Not opening.** Compose existing types; `JSON` covers structured cases |
| MCP tools | `kelta-mcp` is a separate Spring service — no module classloader, stateless transport, `List<AdminTool>` fixed at construction | **Not opening.** If needed, generate tools from `tenant_module_action` rows over HTTP; never load module code into kelta-mcp |
| NATS streams / subscriptions | `JetStreamInitializer` + `NatsSubscriptionConfig` are startup-bound | **Not opening.** `kelta.trigger.<tenantId>.<topic>` is already an open runtime subject space |
| Arbitrary gateway paths | `RouteConfigService.registerStaticRoutes()` is a hardcoded array | **Not opening.** The `/api/modules/{moduleId}/...` prefix gives the same capability with no collision surface |

---

## Shared contracts

### Module status

`INSTALLED` · `ACTIVE` · `DEGRADED` · `QUARANTINED` · `DISABLED` · `FAILED` · `STUB`

- `DEGRADED` — loaded, but something declared is unavailable (required setting unset, declared port
  refused, UI bundle missing). Real handlers for what loaded; quarantined for the rest.
- `QUARANTINED` — load failed (signature, checksum, classload, `onStartup` threw). Quarantined
  handlers only.
- `STUB` — **explicit dev opt-in** (`kelta.modules.stub-mode=true`), never reached by falling back
  from an error, and reported as such by the API and UI.

**The DB status column is not pod reality.** `isLoaded()` is per-pod; `GET /api/modules/{id}/health`
reports both and names the pod.

### Quarantined handler

A module whose load failed registers handlers that return
`ActionResult.failure("ModuleUnavailable", "<moduleId> v<version> is quarantined: <reason>")` —
registered rather than absent, so a flow step fails with a specific attributable error instead of
`ResourceNotFound`, which reads to an admin as a mistyped action key.

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

- **Idempotent:** JAR upload (version-keyed), package import (natural-key upsert), permission
  upsert, provenance upsert, route/schedule registration, load/unload.
- **Destructive, never automatic:** dropping collections or fields, deleting records, deleting a
  tenant-modified metadata item, revoking a permission grant an admin made.
- **Explicit admin consent:** install (capability + permission approval via `planHash`), downgrade,
  `purgeData`, `OVERWRITE` on tenant-modified metadata, an upgrade whose plan removes an action key
  a live flow references.

---

## Slice plan

| Slice | Child spec | Axis | State (verified 2026-10-11) |
|---|---|---|---|
| 0 — Fail closed | not needed — slice shipped | **backend, security** — kill the silent stub fallback | ✅ **Shipped.** CI job `worker-image-is-jvm` in `.github/workflows/ci.yml` asserts `build-and-publish-containers.yml` builds the worker from `kelta-worker/Dockerfile.jvm`, and is in `quality-gate`'s `needs`. `V189__module_fail_closed.sql` adds `QUARANTINED`/`DEGRADED`/`STUB` to `chk_module_status` and `tenant_module.last_error`, `last_error_at`, `last_loaded_at`, `load_attempts`. `RuntimeModuleManager` quarantines on signature, checksum or classload failure (`quarantine(...)` registers `createQuarantinedHandler`, which throws `ModuleUnavailableException` rather than returning a failure, because `TaskStateExecutor` would relabel any failed `ActionResult` as `ActionFailed`). Stub handlers only under the explicit `kelta.modules.stub-mode` opt-in (`application.yml` `${KELTA_MODULES_STUB_MODE:false}`, wired in `ModuleConfig`). `GET /api/modules/{id}/health` (`ModuleController`) reports `loadedOnThisPod` + `podName`. `ModulesPage` installs a signed JAR through `/api/modules/install-jar`. Test: `RuntimeModuleManagerQuarantineTest`. Not done: `STATUS_DEGRADED` exists in `TenantModuleData` but nothing writes it |
| 1 — Billable-channel guard | not needed — slice shipped | backend (money risk, ~20 lines) | ✅ **Shipped in code.** `AlertDispatchService.java` declares `BILLABLE_CHANNELS = Set.of(CHANNEL_SMS)` and, when no `channels` entitlement resolves, delivers only the non-billable requested channels. `WatchController.java` applies the same filter when it narrows a watch's requested channels. SMS therefore goes out only on an affirmative entitlement. Gap: no unit test pins the "no entitlement → SMS dropped" case (`AlertDispatchServiceTest.noEntitlementMeansNoGating` covers `push` only); worth adding with the next change to either file |
| 2 — Provenance + unified provisioning | [`2-provenance-provisioning.md`](2-provenance-provisioning.md) | backend (DB, reuse `PackageImportService`) | 🟡 **Partial.** Done: `V190__module_provisioned_resource.sql` (table + RLS) and `ModuleProvenanceStore` (`record` / `findByModule` / `deleteForModule`); `RuntimeModuleManager.recordProvenance` writes `COLLECTION` rows as `CREATED` or `ADOPTED`. Not done: provisioning still runs through `ModuleCollectionProvisioner` (wired in `ModuleConfig`), not `PackageImportService`; `content_hash` is always written `null`; nothing reads `findByModule` and nothing calls `deleteForModule` (uninstall leaves the provenance rows) |
| 3 — Lifecycle | `3-lifecycle.md` (not written) | backend (manifest v2, install plan, upgrade, uninstall) | 🔴 **Not started.** Installing over an existing module id still throws "already installed" (`RuntimeModuleManager.installModule` / `installModuleWithJar`); no manifest v2, install plan or `planHash` |
| 4 — Permission catalog | `4-permission-catalog.md` (not written) | **backend + UI, security** | 🔴 **Not started.** `manifest.permissions` is parsed by `ModuleManifestParser` and read by no other main code; no `system_permission` catalog table |
| 5 — Module settings | `5-module-settings.md` (not written) | backend + UI (credential-ref) | 🔴 **Not started.** No settings in `ModuleManifest`, DB, SPI, API or UI |
| 6 — Module HTTP routes | `6-module-routes.md` (not written) | **backend, security** | 🟡 **Partial, built ahead of its spec.** `ModuleManifest.RouteManifest` (`path`, `methods`, `handlerKey`), `ModuleRouteRegistry` (registered on load, after the code loaded) and `ModuleHttpController` (`/api/modules/{moduleId}/x/**`, depth-1..3 patterns) dispatch module routes; the health endpoint reports `routesRegisteredOnThisPod`. Not done: a route declares no permission, so the only check is the gateway's `API_ACCESS` on the static `/api/modules/**` route |
| 7 — Capability gating | `7-capability-gating.md` (not written) | **security** (closes an open hole) | 🟡 **Partial.** `RuntimeModuleManager.registerTenantServices` refuses any port in `getServices()` the manifest's `services` list does not declare, and withdraws the module's already-accepted ports. Not done: no capability list and no admin approval of declared ports at install |
| 8 — Billing removal | `8-billing-removal.md` (not written) | **the proving slice** — 6 PRs, ends with a Flyway drop | 🟡 **Partial, out of scope here.** No `/api/billing` controller or Stripe client remains in `kelta-worker/src/main`; `kelta-modules/billing` serves checkout/portal/webhooks. Still compiled in: `service/billing/` (`EntitlementServiceImpl`, `ModuleAwareEntitlementService`, `BillingPassExpirySweep`, `BillingEntitlementRuleCache`), the `repository/Billing*` classes, the billing tables, and the gateway's `/api/billing/**` static route in `RouteConfigService` |

Slice 8 exercises every earlier slice: routes (checkout/portal), webhooks (Stripe), settings
(`credential-ref` to the Stripe key), permissions (`MANAGE_BILLING`), provisioning (five
collections), schedules (pass expiry), a published service port (`EntitlementProvider`), and
fail-closed (billing must **never** stub-succeed).

---

## Known defects this spec exists to fix

Each was source-verified as of 2026-08-31 and **re-checked against code on 2026-10-11**. Each
entry leads with its current state. `concerns.md` tracks defects 1 and 2 (one combined entry,
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
6. **PARTIAL — uninstall leaves data with no provenance.** V190 `module_provisioned_resource` and
   `ModuleProvenanceStore` now record which collections a module created or adopted. Still open:
   only `COLLECTION` rows are recorded, `content_hash` is always `null`, and `uninstallModule`
   neither reads nor clears the records, so an admin still has no surface that lists a removed
   module's leftovers (slice 2, then slice 3 for uninstall).
7. **OPEN — no module settings** anywhere: manifest, DB, SPI, API or UI (slice 5).
8. **PARTIAL — observability.** Done: `GET /api/modules/{id}/health` returns `isLoaded()` as
   `loadedOnThisPod` with `podName`, `routesRegisteredOnThisPod` and the V189 load diagnostics
   (`last_error`, `last_error_at`, `last_loaded_at`, `load_attempts`); `FAILED` is reachable again
   (a provisioning failure sets it). Still missing: no Micrometer metrics in
   `kelta-worker/.../module/` (load outcomes, quarantines, handler invocations or errors per module),
   no per-module attribution of handler failures beyond the log line, and `DEGRADED` is defined but
   never written.
9. **OPEN — cross-pod divergence is silent.** Install/enable still rely on a fire-and-forget NATS
   event (`ModuleEventListener`) with no ack and no periodic reconcile. The health endpoint makes
   divergence *observable*, one pod per request; nothing detects or repairs it.

---

## Deploy hazards that apply to every slice

1. **The worker must stay JVM.** `build-and-publish-containers.yml` pins `Dockerfile.jvm` because a
   native image cannot classload an uploaded JAR — a native worker quarantines **every** module
   (before slice 0 it silently turned them into success-reporting stubs). Before slice 8d that is degraded-but-covered; after it, it silently ungates every tenant.
   Slice 0 added that CI assertion: the `worker-image-is-jvm` job in `ci.yml`, a
   `quality-gate` dependency.
2. **The merge-train trap.** The deploy workflow sets `cancel-in-progress: true` and filters paths
   against the previous push, so merging a second PR before the first's build completes can leave
   merged code unbuilt with green CI. In slice 8 that means a migration deploying without its code,
   or the reverse. **Merge one, wait for green, verify the ArgoCD tag moved.**
3. **The migrate Job is an ArgoCD PreSync hook** — it runs to completion while the *old* pods still
   serve traffic. A drop migration therefore removes tables underneath running readers, which is
   why 8f ships at least one release after 8e has soaked.
4. **`kelta-modules/**` is not a deploy trigger.** Every module-side change is a manual build, sign,
   install, verify, and appears in no deploy.
5. **`install-jar` activates immediately.** Handlers, hooks and services register at *install*;
   `status` stays `INSTALLED` until `/enable` — that flag is bookkeeping, not a gate. On a tenant
   with existing data, populate collections first.

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
