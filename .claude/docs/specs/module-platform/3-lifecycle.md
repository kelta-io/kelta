# Slice 3 — Lifecycle: Manifest v2, Install Plan, Upgrade, Uninstall

> **Status:** 🔴 spec written (KLT-537), not started in code. Split into three PR-sized parts
> (3a, 3b, 3c in §1) that the planner can file directly, in order.
> Source-verified against main `6488c0c` on 2026-10-11. **Flyway directory head at that base:
> V206** (`kelta-worker/src/main/resources/db/migration/V206__collection_ownership.sql`, the
> output of `ls kelta-worker/src/main/resources/db/migration | sort -V | tail -1`). This slice
> needs **no migration** (§4).

> Child of [`.claude/docs/specs/module-platform/README.md`](README.md). Implements the parent's
> **Idempotent / destructive / consent** contract (the `planHash` install consent, downgrade,
> `purgeData`, and "an upgrade whose plan removes an action key a live flow references") and the
> uninstall half of the **Provenance** contract. Closes Known defect 3 for `minPlatformVersion`
> (permissions stay slice 4), Known defect 5 (v2 over v1 is "already installed"), and the rest of
> Known defect 6 (`ModuleProvenanceStore.deleteForModule` exists, nothing calls it, and uninstall
> removes nothing).

## 1. Goal & scope

Today a module can be installed exactly once and removed only partly:

- `RuntimeModuleManager.installModule` and `installModuleWithJar` both throw
  `IllegalStateException("Module '<id>' is already installed for tenant <t>")` when a
  `tenant_module` row exists for the id, which `ModuleController` maps to `409`. The only way from
  v1 to v2 is uninstall + install, and that drops the module's handlers from every flow in between.
- `ModuleManifest.minPlatformVersion` is parsed by `ModuleManifestParser` and read by nothing.
  `kelta-modules/billing/src/main/resources/kelta-module.json` declares `"minPlatformVersion": "1.0.0"`
  while the platform's first release is `v0.1.0`, so nothing has ever checked the value.
- `RuntimeModuleManager.uninstallModule` unloads, deletes the JAR from S3 and deletes the
  `tenant_module` row (actions cascade). The collections and fields slice 2 recorded in
  `module_provisioned_resource` stay behind. Correctly so, because nothing can yet tell which of
  them the module may remove.

This slice gives a module a version-aware lifecycle: **plan, then apply**. Every
state-changing step is computed first as a plan the admin can read, hashed into a `planHash`, and
applied only when the caller presents that hash. When the world has changed since the plan was
read, the apply refuses and returns the new plan.

### In scope

1. **Manifest v2** (§3.1). A `manifestVersion` field (absent means 1). A v2 manifest may declare
   LOOKUP / MASTER_DETAIL fields (`referenceTo`) and a `metadata` list of further
   `PackageImportService` items from an allowlist (global picklists, picklist values, validation
   rules, UI pages, menus, menu items). Every v1 manifest, including
   `kelta-modules/billing/src/main/resources/kelta-module.json`, parses and installs exactly as
   today.
2. **`minPlatformVersion` enforced** at install and upgrade against the running platform version
   (§3.2), which comes from the `KELTA_VERSION` environment variable through a new
   `kelta.platform.version` property.
3. **Install plan + `planHash`** (§3.3). The plan is a dry run through `ModuleMetadataProvisioner`
   plus the manifest's declared action handlers, routes, services and permissions, diffed against
   the installed version when there is one. A new `POST /api/modules/plan` returns it.
4. **Upgrade** (§3.4): same module id, a strictly higher version, re-plan, apply. The
   `tenant_module` row is updated in place, so its id and status survive, and every pod reloads
   through a new `ModuleChangeType.UPGRADED`.
5. **Uninstall that removes what the module owns** (§3.5). It removes only `CREATED` provenance
   rows whose `content_hash` still matches the resource as it stands, and reports everything it
   keeps and why. A resource holding tenant data is removed only with `purgeData=true`, which needs
   `CUSTOMIZE_APPLICATION` and a plan computed with `purgeData=true`.

### Out of scope

- **Approving permissions and capabilities.** The plan *lists* `manifest.permissions` and
  `manifest.services` for disclosure. Turning them into a catalog with grants (slice 4) or into
  approved capabilities (slice 7) is left to those slices. They also decide when a fresh install
  must carry a `planHash` (§3.3, "fresh install").
- **Module settings** (slice 5), **route permissions** (slice 6), **billing removal** (slice 8).
- **Admin UI for plans.** `ModulesPage` keeps calling `install` / `install-jar` / `DELETE` with no
  `planHash`, and every one of those calls behaves as it does today (§3.6). The plan / consent
  dialog is a follow-up UI task (§8, open question 1).
- **Downgrade.** Refused with a blocker (§3.4). The parent allows it with explicit consent, but it
  is not built here.
- **Manifest `FLOW`, `PAGE_LAYOUT` and `LAYOUT_*` items** (§3.1, and §8 for why).
- **Removing what an upgrade dropped.** A collection v1 declared and v2 does not stays installed
  and is reported in the plan as `orphanedByUpgrade`. Only uninstall removes metadata.

### The three PRs

Each part is one PR, filed in order. Each part's code task gets the acceptance lines below
verbatim. Every part also carries the standing lines: `verify.sh` green; `.claude/CHANGELOG.md`
entry; the §7 docs for that part.

**3a — Manifest v2 + platform version check** (runtime-core + worker; touches
`kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleManifest.java`,
`kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleManifestParser.java`,
`kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleVersion.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/PlatformVersion.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleMetadataProvisioner.java`,
`kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java`,
`kelta-worker/src/main/java/io/kelta/worker/config/ModuleConfig.java`,
`kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java`,
`kelta-worker/src/main/resources/application.yml`,
`kelta-modules/billing/src/main/resources/kelta-module.json`, tests, docs):

- A manifest with no `manifestVersion` parses to `manifestVersion() == 1`, and every existing
  `ModuleManifestParserTest` case passes unchanged. A manifest with `manifestVersion: 3` (or any
  value other than 1 or 2) is rejected with `ModuleManifestException` naming the supported
  versions.
- A manifest that uses a v2-only key (`metadata`, or `referenceTo` on a field) without declaring
  `"manifestVersion": 2` is rejected with a message telling the author to declare it. The keys are
  never silently ignored.
- A v2 field `{"name":"owner","type":"LOOKUP","referenceTo":"users"}` becomes a package `FIELD`
  item carrying `reference_collection_name: "users"`, and the field is created with its
  `referenceCollectionId` resolved by `PackageImportService` (test through
  `ModuleMetadataProvisionerTest` with the importer mocked: assert the package item).
- A v2 `metadata` item of an allowlisted type is provisioned through the same two-pass import as
  collections. It gets a `CREATED` provenance row (a 64-hex `content_hash`, §3.5) when created and
  an `ADOPTED` row when it already existed. A `metadata` item of a type not on the allowlist (e.g.
  `FLOW`) rejects the manifest before `PackageImportService.importPackage` is called.
- `ModuleVersion.parse` accepts SemVer 2.0 (`1.2.3`, `1.2.3-rc.1`, `1.2.3+build`, and a leading
  `v`). It orders by SemVer precedence, so `1.2.3-rc.1` < `1.2.3`, build metadata is ignored, and
  `1.10.0` > `1.9.0`. It rejects anything else.
- `PlatformVersion` reads `kelta.platform.version`, declared in `application.yml` as
  `${KELTA_VERSION:}`. A blank or unparseable value is *unknown*. Unit test: `0.1.0`, `v0.1.0`,
  blank and `main` give known `0.1.0`, known `0.1.0`, unknown and unknown.
- Install of a manifest whose `minPlatformVersion` is greater than a *known* platform version is
  refused on both install paths **before** any JAR upload, row write or provisioning. The
  `IllegalArgumentException` carries the message
  `Module '<id>' requires Kelta >= <min>; this platform is <running>`, and the controller answers
  `422`. When the platform version is unknown, the install proceeds with a WARN log.
- `kelta-modules/billing/src/main/resources/kelta-module.json` declares `"minPlatformVersion": "0.1.0"`.

**3b — Install plan, `planHash` and upgrade** (worker + runtime-events; touches
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleInstallPlan.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleLifecyclePlanner.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/ModulePlanChangedException.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java`,
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleMetadataProvisioner.java`,
`kelta-worker/src/main/java/io/kelta/worker/module/JdbcModuleStore.java`,
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleEventListener.java`,
`kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java`,
`kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleStore.java`,
`kelta-platform/runtime/runtime-events/src/main/java/io/kelta/runtime/event/ModuleChangeType.java`,
tests, docs):

- `POST /api/modules/plan`, as JSON `{manifest}` or as multipart `manifest`+`jar`[+`signature`],
  returns `200` with a `module-install-plans` resource whose attributes match §3.3. It writes
  nothing: no S3 object, no `tenant_module` row, no metadata row and no provenance row (assert
  `ModuleJarService.uploadJar`, `ModuleStore.createModule` and
  `PackageImportService.importPackage` with `dryRun=false` are never called).
- The same manifest + JAR planned twice against unchanged state gives the same `planHash`. Changing
  any of: one byte of the JAR, the manifest, the running platform version, the installed version, or
  the dry-run outcome of one metadata item changes it.
- Installing over an existing module id **without** `planHash` still answers `409`. The message
  now ends `POST /api/modules/plan and send its planHash to upgrade`.
- Installing a strictly higher version **with** the current `planHash` upgrades in place. The
  `tenant_module` row keeps its `id`, `status` and `installed_at`. `version`, `name`, `description`,
  `manifest`, `module_class`, `source_url`, `s3_key`, `jar_checksum`, `jar_size_bytes` and
  `jar_signature` take the new values. `tenant_module_action` rows are replaced by the new
  manifest's. New collections, fields and metadata items are provisioned. A new field on a
  collection whose provenance row is `CREATED` by this module is added. The old JAR's S3 object is
  deleted after the new one is stored. One `ModuleChangeType.UPGRADED` event is published.
- An apply whose `planHash` differs from the freshly computed plan answers `409` with
  `errors[0].code = "PlanChanged"` and the fresh plan in `meta.plan`. Nothing is written.
- A plan with any `blockers` (platform too old, downgrade, same version with a different JAR,
  unparseable version, unsupported `manifestVersion`) cannot be applied: `422`, and nothing is
  written.
- A plan whose `actionHandlers.removed` contains a key that an **active** flow's Task `Resource`
  names lists that flow in `consentRequired` as `REMOVES_REFERENCED_ACTION_KEY`. The apply needs
  `planHash` **and** `acknowledge=REMOVES_REFERENCED_ACTION_KEY`, or it answers `409`.
- `ModuleEventListener` on `UPGRADED` unloads the module by id and reloads it from the current row
  when this pod had it loaded. Test: a pod that had v1 loaded serves v2's action keys afterwards.
  None of v1's removed keys stays registered, and the `loadedModules` "already loaded" early return
  is not hit.
- A fresh install (no row for the id) without `planHash` behaves exactly as today, so `ModulesPage`
  is unaffected. With `planHash` it is checked like an upgrade.

**3c — Uninstall that removes what the module owns** (worker; touches
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleUninstallPlan.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleUninstaller.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleResourceSnapshot.java` (**new**),
`kelta-worker/src/main/java/io/kelta/worker/module/ModuleProvenanceStore.java`,
`kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java`,
`kelta-worker/src/main/java/io/kelta/worker/listener/CollectionDeletionGuardHook.java`,
`kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java`, tests, docs):

- `GET /api/modules/{moduleId}/uninstall-plan?purgeData=<bool>` returns a
  `module-uninstall-plans` resource (§3.5) with one entry per provenance row and a `planHash`. It
  writes nothing.
- An entry is `REMOVE` only when all of these hold: the row is `CREATED`, its stored
  `content_hash` equals `ModuleResourceSnapshot.hash` of the resource as it stands now, and (for
  `purgeData=false`) the resource holds no tenant data and no cascading dependents. Otherwise the
  entry is `KEEP_ADOPTED`, `KEEP_MODIFIED`, `KEEP_UNVERIFIABLE` (CREATED with a null hash),
  `KEEP_HAS_DATA` or `KEEP_HAS_DEPENDENTS`. A resource that no longer exists is `GONE`.
- `DELETE /api/modules/{moduleId}` **without** `planHash` behaves as today: unload, JAR delete, row
  delete, and no metadata touched. The response gains a `retained` list of the provenance rows left
  behind.
- `DELETE /api/modules/{moduleId}?planHash=<h>` removes exactly the plan's `REMOVE` entries through
  `QueryEngine.delete`, so the collection/field NATS broadcast and `CollectionDeletionGuardHook`'s
  table drop fire as for an admin delete. It deletes the provenance row of each removed or `GONE`
  resource and keeps every other row. It calls `ModuleProvenanceStore.deleteForModule` only when no
  row is kept.
- Test (`ModuleUninstallerTest`): a `CREATED` collection whose display name the tenant edited
  after install is `KEEP_MODIFIED` and is not deleted. An `ADOPTED` collection is never passed to
  `QueryEngine.delete` under any parameter combination. An unedited, empty `CREATED` collection is
  deleted with its provenance row.
- A `CREATED` collection that holds records, or a field on one, is `KEEP_HAS_DATA` unless the plan
  was computed with `purgeData=true`. `DELETE …?purgeData=true&planHash=<h>` from a caller without
  `CUSTOMIZE_APPLICATION` answers `403` and changes nothing. With the permission and a matching hash
  it deletes the collection, its table and its records.
- `purgeData=true` with a `planHash` computed for `purgeData=false` (or the reverse) answers `409
  PlanChanged`. `purgeData` is part of the hash.
- `CollectionDeletionGuardHook` treats a delete as forced when its new `FORCE` scoped value is bound
  to `true`, as well as on `?force=true`. `ModuleUninstaller` binds it only around `REMOVE` deletes
  of a plan computed with `purgeData=true`. Every existing `CollectionDeletionGuardHookTest` case
  passes unchanged.

## 2. UI samples

N/A — this slice is backend only. The parent's slice table marks slice 3 as
"backend (manifest v2, install plan, upgrade, uninstall)". The admin UI keeps its current calls,
and §3.6 keeps every one of them working unchanged. The plan/consent dialog belongs with the UI
work that slices 4 and 7 need anyway: an install plan that lists permissions and capabilities to
approve is the same dialog. §8 open question 1 records that choice for the planner.

## 3. Data & API contracts

### 3.1 Manifest v2

A v2 manifest is a v1 manifest plus these keys. Everything else keeps its v1 meaning.

| Key | Type | v1 | v2 |
|---|---|---|---|
| `manifestVersion` | integer | absent (= 1) or `1` | `2`; any other value is rejected |
| `minPlatformVersion` | SemVer string | parsed, **now enforced** (§3.2) | same |
| `collections[].fields[].referenceTo` | collection name | rejected (v2-only) | target of a `LOOKUP` / `MASTER_DETAIL` field; may name a system collection such as `users` (`PackageImportService` has resolved those since #1660) |
| `metadata` | array of `{ "type", "data" }` | rejected (v2-only) | further `PackageImportService` items. `type` ∈ `GLOBAL_PICKLIST`, `PICKLIST_VALUE`, `VALIDATION_RULE`, `UI_PAGE`, `UI_MENU`, `UI_MENU_ITEM`; `data` uses the system tables' column names, exactly as a package export does (`PackageImportService.naturalKeyFor` defines each type's natural key) |

**v1 compatibility.** The parser's `FAIL_ON_UNKNOWN_PROPERTIES` stays disabled, so v1 manifests
carrying unrelated extra keys keep parsing. The two v2-only keys are the exception: a v1 manifest
that contains them is **rejected**, not ignored. A v1 author who added `metadata` and saw it
silently dropped would ship a module that half-provisions.

**Record shape.** `ModuleManifest` gains two trailing components, `int manifestVersion` and
`List<MetadataItem> metadata` (with `record MetadataItem(String type, Map<String, Object> data)`).
`FieldManifest` gains a trailing `String referenceTo`. At `6488c0c` the records are
constructed in `ModuleManifestParser` (which 3a changes anyway) and in
`ModuleMetadataProvisionerTest` only (`grep -rln "new ModuleManifest(\|new FieldManifest(" --include=*.java`).
Give both records a delegating constructor with the current arity so the test compiles unchanged,
as slice 2 did for `ItemResult`.

**Translation.** `ModuleMetadataProvisioner.toPackage` adds `reference_collection_name` to a FIELD
item's data when `referenceTo` is set, and appends each `metadata` item verbatim after the FIELD
items. `PackageImportService` sorts by `TYPE_ORDER` itself. The slice-2 two-pass apply is unchanged
except for two things. The `itemKeyFilter` admits every `metadata` key. Ownership of a `metadata`
item follows its pass-2 action: `CREATED` → `CREATED`, `SKIPPED` → `ADOPTED`.

**Why this allowlist.** These types have natural keys that never involve a source-tenant id, so
they mean the same thing in every tenant. `PAGE_LAYOUT` and `LAYOUT_*` key on sort orders, so an
admin reordering sections changes the identity of the item. `FLOW` is excluded on purpose (§8).

### 3.2 `minPlatformVersion` and the running platform version

**Source (verified at `6488c0c`).** The worker has no runtime notion of the release version today:

- `org.springframework.boot.info.BuildProperties` exists (the `build-info` goal in
  `kelta-worker/pom.xml`, used by `CerbosPolicySeeder`), but its `version` is the Maven project
  version, `1.0.0-SNAPSHOT`, for every build, and no release changes it.
- The release workflow stamps only the OCI label `org.opencontainers.image.version` on the image.
  Nothing inside the JVM reads image labels.
- **`KELTA_VERSION` does reach the worker process** on the documented self-host path.
  `docker-compose.yml` gives the `kelta-worker` service `env_file: .env`, and `.env.example`
  sets `KELTA_VERSION=0.1.0`, the same variable that picks the
  `ghcr.io/kelta-io/kelta-worker:${KELTA_VERSION:-0.1.0}` image tag. The variable that chooses the
  release is therefore present in the container that runs it.

So the source is **the `KELTA_VERSION` environment variable**, bound through a new property
declared in `kelta-worker/src/main/resources/application.yml`:

```yaml
kelta:
  platform:
    # The running Kelta release, e.g. 0.1.0. Compared against a module's minPlatformVersion.
    # Blank (the default) = unknown: the check is skipped with a WARN, never failed.
    version: ${KELTA_VERSION:}
```

`PlatformVersion` (**new**, `@Component`) takes `@Value("${kelta.platform.version:}")`, strips a
leading `v`, and parses it with `ModuleVersion`. It exposes `Optional<ModuleVersion> current()` and
`String describe()` (the version, or `unknown`). The worker is a JVM image (parent, Deploy hazard
1), and this is a plain `@Value` rather than a `@ConditionalOnProperty`, so the native-image
build-time freeze in `CLAUDE.md` → Pitfalls does not apply. The property is still declared in
`application.yml` so it is greppable.

**Check.** `RuntimeModuleManager` (3a), and from 3b `ModuleLifecyclePlanner`, compares
`ModuleVersion.parse(manifest.minPlatformVersion())` with `PlatformVersion.current()`:

| `minPlatformVersion` | platform version | Result |
|---|---|---|
| absent | any | `OK` |
| present | known, ≥ min | `OK` |
| present | known, < min | **blocker** `PLATFORM_TOO_OLD` (3a: `IllegalArgumentException` → `422`) |
| present | unknown | `UNKNOWN`: proceed, WARN `Module '<id>' declares minPlatformVersion <min>; platform version unknown (KELTA_VERSION unset)` |
| unparseable | any | **blocker** `UNPARSEABLE_VERSION` |

The check runs at **install and upgrade only**, never at load. A module that is already installed
keeps loading after a platform rollback. Quarantining a working module because of an operator's
rollback would turn a recoverable situation into an outage. `GET /api/modules/{id}/health` gains
`platformVersion` and `minPlatformVersionSatisfied` (`true` / `false` / `null` when unknown) so the
mismatch is visible.

`kelta-modules/billing/src/main/resources/kelta-module.json` must move from `1.0.0` to `0.1.0` in
3a. Otherwise, the first time the check runs against a known version, it refuses the reference
module on every self-hosted `0.1.0` stack. `kelta-modules/**` is not a deploy trigger (parent,
Deploy hazard 4), so the change only takes effect when billing is next rebuilt and signed.

### 3.3 The install plan and `planHash`

`ModuleLifecyclePlanner.planInstall(tenantId, manifestJson, jarBytesOrNull, userId)` returns a
`ModuleInstallPlan` (**new** record). It writes nothing. Its metadata section is the dry-run pass
of `ModuleMetadataProvisioner` (the same `importPackage(..., dryRun=true)` slice 2 already runs
first), exposed as a new public `ModuleMetadataProvisioner.plan(tenantId, manifest, userId)` that
validates the manifest and returns the dry-run report **without** the apply pass.

```json
{
  "data": {
    "type": "module-install-plans",
    "id": "kelta-billing",
    "attributes": {
      "moduleId": "kelta-billing",
      "mode": "UPGRADE",
      "fromVersion": "1.0.0",
      "toVersion": "1.1.0",
      "manifestVersion": 2,
      "manifestSha256": "…64 hex…",
      "jarSha256": "…64 hex…",
      "platformVersion": "0.1.0",
      "minPlatformVersion": "0.1.0",
      "platformCheck": "OK",
      "metadata": [
        { "type": "COLLECTION", "naturalKey": "billing_plans", "action": "ADOPT" },
        { "type": "FIELD", "naturalKey": "billing_plans.trialDays", "action": "CREATE" },
        { "type": "UI_MENU_ITEM", "naturalKey": "billing:Billing", "action": "CREATE" }
      ],
      "orphanedByUpgrade": [ { "type": "COLLECTION", "naturalKey": "billing_legacy_passes" } ],
      "actionHandlers": { "added": ["billing:refund"], "removed": ["billing:legacy-sync"], "kept": ["billing:resolve-entitlements"] },
      "routes": { "added": [{ "path": "/refunds", "methods": ["POST"], "handlerKey": "billing:refund" }], "removed": [], "kept": [] },
      "services": { "added": [], "removed": [], "kept": ["io.kelta.runtime.module.service.EntitlementProvider"] },
      "permissions": ["MANAGE_BILLING"],
      "blockers": [],
      "consentRequired": [
        { "code": "REMOVES_REFERENCED_ACTION_KEY", "actionKey": "billing:legacy-sync", "flowId": "…", "flowName": "Nightly sync" }
      ],
      "planHash": "…64 hex…"
    }
  }
}
```

- **`mode`**: `INSTALL` (no row for the id); `UPGRADE` (row exists, `toVersion` > `fromVersion`);
  `NO_OP` (same version and same `jarSha256`, or same manifest when there is no JAR; the apply
  returns `200` with the current row and changes nothing); `BLOCKED`.
- **`metadata[].action`**: `CREATE` (the dry run reported `CREATED`) or `ADOPT` (the dry run
  reported `SKIPPED`). For `UPGRADE`, a FIELD on a collection whose provenance row is `CREATED` by
  this module is `CREATE` when absent. That is the slice-2 "fields of an existing collection are
  never written" rule, relaxed only for the module's own collections (§3.4). On an adopted
  collection the field is listed with `action: "SKIP_ADOPTED"`.
- **`orphanedByUpgrade`**: provenance rows of the installed version whose natural key the new
  manifest no longer declares. Informational only. Upgrade never removes them (§1, Out of scope).
- **`actionHandlers` / `routes` / `services`**: the manifest's declarations, diffed against the
  installed row's stored manifest (all `added` for `INSTALL`). Diffs are computed by `key`, by
  `path` + `methods`, and by port name respectively. `BeforeSaveHook`s are code-only (returned by
  `KeltaModule.getBeforeSaveHooks()` after classloading), so the plan cannot list them. Say so in
  the plan's Javadoc rather than imply a complete inventory.
- **`permissions`**: `manifest.permissions`, verbatim, for disclosure. Slice 4 makes them mean
  something.
- **`blockers`** (codes): `PLATFORM_TOO_OLD`, `UNPARSEABLE_VERSION`, `UNSUPPORTED_MANIFEST_VERSION`,
  `DOWNGRADE` (`toVersion` < `fromVersion`; message: uninstall and install the older version),
  `SAME_VERSION_DIFFERENT_JAR` (a publisher must bump the version; anything else defeats
  version-keyed idempotency), and `INVALID_MANIFEST` (a `ModuleMetadataProvisioner` validation
  failure, with its message).
- **`consentRequired`**: `REMOVES_REFERENCED_ACTION_KEY`, one entry per (removed key, active flow).
  Found with
  `SELECT id, name FROM flow WHERE tenant_id = ? AND active = true AND jsonb_path_exists(definition, '$.** ? (@.Resource == $k)', jsonb_build_object('k', ?::text))`,
  run under the tenant like any other flow read.

**`planHash`** is the SHA-256 (64 lowercase hex) of the plan serialised as canonical JSON: keys
sorted at every level, as slice 2's `ModuleMetadataProvisioner.contentHash` already does,
`planHash` itself excluded, every list sorted (by `type` then `naturalKey`, by key, or by path) so
an unordered dry-run report cannot change the hash. Because the plan contains `manifestSha256`,
`jarSha256`, `platformVersion`, `fromVersion` and every per-item action, any change to what would
happen changes the hash. Nothing is stored server-side. The apply re-plans and compares, so a plan
cannot go stale in a table and no migration is needed.

**Fresh install.** The `planHash` is **optional** on `INSTALL` in this slice. Requiring it would
break `ModulesPage` and every script that installs a module, and a fresh install in slice 3 needs
no consent the platform does not already ask for. Blockers are enforced on every path regardless.
Slices 4 and 7, which add things to consent to, make it mandatory when the plan contains
permissions or capabilities. **Upgrade** and **uninstall-with-removal** always require it.

### 3.4 Upgrade

`RuntimeModuleManager.upgradeModule(tenantId, manifestJson, jarBytesOrNull, signature,
planHash, acknowledged, userId)`. `installModule` and `installModuleWithJar` call it when a row
exists and a `planHash` was supplied. Order:

1. Verify the JAR signature (the same `ModuleSignatureVerifier` call, against the tenant's keys)
   **before** anything else, as install does today.
2. Re-plan. Mismatched hash → `ModulePlanChangedException` (**new**, carries the fresh plan) →
   `409 PlanChanged`. Blockers → `422`. Unacknowledged `consentRequired` → `409 ConsentRequired`.
   `NO_OP` → return the row.
3. Upload the new JAR. The S3 key is version- and checksum-keyed
   (`ModuleJarService.uploadJar(tenantId, moduleId, version, bytes)`), so it never collides with
   the old object.
4. `ModuleStore.updateModuleForUpgrade(rowId, …)` (**new**; SQL `UPDATE tenant_module SET …,
   updated_at = now() WHERE id = ?`) and `ModuleStore.replaceActions(rowId, actions)` (**new**;
   `DELETE FROM tenant_module_action WHERE tenant_module_id = ?` then the existing batch insert, in
   one `@Transactional` method on `JdbcModuleStore`). Status, `installed_at` and `installed_by` are
   untouched. A module that was `ACTIVE` stays `ACTIVE` across the upgrade.
5. Provision: `ModuleMetadataProvisioner.provision(tenantId, manifest, userId,
   ownedCollections)`, where `ownedCollections` is the set of this module's `CREATED` `COLLECTION`
   natural keys from `ModuleProvenanceStore.findByModule`. Fields of those collections enter the
   pass-2 `itemKeyFilter` even though the collection already exists. Provenance is recorded as
   today, and ownership still never downgrades. A failure marks the row `FAILED` exactly as
   install does and does not roll back steps 3–4. The admin sees `FAILED` with `last_error`, and
   re-applying the same plan converges, since every step is an idempotent upsert.
6. On this pod: `wasLoaded = isLoaded(tenantId, moduleId)`, `unloadModule(tenantId, moduleId)` (by
   id, the form `ModuleEventListener` already relies on), then `loadModule(tenantId, freshRow)` if
   `wasLoaded`.
7. Publish `ModuleChangeType.UPGRADED` through `ModuleConfigEventPublisher` (Critical Rule 1).
   `ModuleEventListener` handles it with the same unload-by-id + reload-if-was-loaded sequence.
8. Best-effort `ModuleJarService.deleteJar(oldS3Key)` (WARN on failure). It runs last so that a pod
   still loading v1 when the event arrives fails a single load and then reloads v2 from the row.

`ModuleChangeType` gains `UPGRADED`. The enum is already in the worker's native `reflect-config.json`
with `allDeclaredFields`, so a new constant needs no reflection entry. Nothing in `kelta-gateway`
deserializes `ModuleChangedPayload` (its `reflect-config.json` lists the payload class only), so an
unknown constant cannot break the gateway.

### 3.5 Uninstall

`ModuleUninstaller.plan(tenantId, moduleId, purgeData)` returns a `ModuleUninstallPlan` (**new**):

```json
{
  "data": {
    "type": "module-uninstall-plans",
    "id": "kelta-billing",
    "attributes": {
      "moduleId": "kelta-billing",
      "installedVersion": "1.1.0",
      "purgeData": false,
      "entries": [
        { "resourceType": "COLLECTION", "naturalKey": "billing_plans", "ownership": "CREATED", "decision": "KEEP_HAS_DATA", "records": 3 },
        { "resourceType": "FIELD", "naturalKey": "billing_plans.trialDays", "ownership": "CREATED", "decision": "KEEP_HAS_DATA", "records": 3 },
        { "resourceType": "COLLECTION", "naturalKey": "billing_coupons", "ownership": "CREATED", "decision": "REMOVE", "records": 0 },
        { "resourceType": "COLLECTION", "naturalKey": "accounts", "ownership": "ADOPTED", "decision": "KEEP_ADOPTED" },
        { "resourceType": "UI_PAGE", "naturalKey": "/billing", "ownership": "CREATED", "decision": "KEEP_MODIFIED" }
      ],
      "planHash": "…64 hex…"
    }
  }
}
```

The decision for each provenance row is the first rule that matches:

| # | Condition | Decision |
|---|---|---|
| 1 | `ownership = ADOPTED` | `KEEP_ADOPTED`, always, under every parameter |
| 2 | the resource no longer exists (`resource_id` not found) | `GONE`: the provenance row is dropped, nothing deleted |
| 3 | `content_hash` is null | `KEEP_UNVERIFIABLE` (rows written before slice 2 filled hashes) |
| 4 | `ModuleResourceSnapshot.hash(row)` ≠ `content_hash` | `KEEP_MODIFIED` |
| 5 | a FIELD whose collection is not itself `REMOVE` and the collection has ≥1 record | `KEEP_HAS_DATA` unless `purgeData` |
| 6 | a COLLECTION with ≥1 record | `KEEP_HAS_DATA` unless `purgeData` |
| 7 | a COLLECTION with `CollectionDeletionGuardHook` cascading children (layouts, reports, notes …) | `KEEP_HAS_DEPENDENTS` unless `purgeData` |
| 8 | otherwise | `REMOVE` |

A FIELD row whose collection is `REMOVE` is `REMOVE` too: the collection delete cascades its
fields. Its provenance row goes with it, and it is not deleted separately.

**Current-content hash.** `ModuleResourceSnapshot` (**new**) reproduces exactly the bytes the
creation hash was taken over, so an untouched resource matches:

- `COLLECTION` and `FIELD`: the slice-2 hash is over the *package item data* (§3 of
  [`2-provenance-provisioning.md`](2-provenance-provisioning.md)). The snapshot reads the same keys
  back from the row: `SELECT name, display_name, path, active, current_version FROM collection
  WHERE id = ? AND tenant_id = ?`, and for a field `SELECT c.name AS collection_name, f.name,
  f.display_name, f.type, f.required, f.field_order, f.active FROM field f JOIN collection c ON
  c.id = f.collection_id WHERE f.id = ? AND f.tenant_id = ?`. From 3a on it also reads
  `reference_collection_name` (via `f.reference_collection_id`), but only when the item carried it,
  i.e. it is non-null. It then hashes with the same canonical JSON. A 3c test pins that the
  snapshot of a just-provisioned collection and field equals the hash
  `ModuleMetadataProvisioner` recorded, so rows written by slice 2 keep matching.
- `metadata` types (3a on): the creation hash is taken **after** the apply pass. The provisioner
  reads the created row back through `QueryEngine.getById` on
  `PackageImportService.systemCollectionFor(type)` and hashes every attribute except `id`,
  `tenantId`, `createdAt`, `updatedAt`, `createdBy` and `updatedBy`. The snapshot repeats that
  read. Hashing the stored row avoids reverse-mapping the `*_name` reference keys a package item
  uses.

A tenant edit therefore changes the hash and keeps the resource. A false mismatch (a platform
write that touches a hashed column) only ever keeps something. It never removes it.

**Apply.** `ModuleUninstaller.uninstall(tenantId, moduleId, planHash, purgeData)`:

1. With no `planHash`: today's `RuntimeModuleManager.uninstallModule` (unload, JAR delete, row
   delete), unchanged. The response lists `retained` = every provenance row.
2. With a `planHash`: re-plan with the given `purgeData`, compare (`409 PlanChanged` with the fresh
   plan in `meta.plan` on mismatch), and require `CUSTOMIZE_APPLICATION` when `purgeData` is true
   (checked in `ModuleController`, `403` otherwise). This is the permission
   `MigrationController.requireSchemaPermission` uses for destructive schema changes, checked the
   same way, through `CerbosPermissionResolver.getProfileId` +
   `BootstrapRepository.findProfileSystemPermissions`. Then run today's uninstall steps **first**,
   so no handler, hook or route of the module runs while its collections disappear.
3. Delete the `REMOVE` entries through `QueryEngine.delete(systemDef, resourceId)` inside
   `TenantContext.callWithTenant(tenantId, slug, …)`. The slug comes from `TenantSlugResolver`, per
   the parent's Tenant binding contract. Order is the reverse of `PackageImportService`'s
   `TYPE_ORDER` (menu items before menus, picklist values before picklists, validation rules and
   fields before collections). Collection deletes run with `CollectionDeletionGuardHook.FORCE`
   bound to `true` **only** when the plan was computed with `purgeData=true`. Without it, the guard
   rejects a collection that gained dependents between plan and apply, and that entry is reported
   `KEEP_HAS_DEPENDENTS` in the response instead of failing the uninstall.
4. Delete each removed or `GONE` provenance row (**new** `ModuleProvenanceStore.deleteRow(tenantId,
   id)`). When nothing is kept, call the existing `deleteForModule`, its first caller. Kept rows stay,
   so a reinstall still finds its own `CREATED` collection, and
   `GET /api/modules/{moduleId}/provisioned-resources` still answers "what did the module I removed
   leave behind?".
5. A delete that throws is reported as `FAILED` with its message, and the remaining entries
   continue. The module row is already gone at this point, so throwing would strand a half-removed
   module that no one could address. The provenance row of a failed entry is kept.

`CollectionDeletionGuardHook.forceRequested()` today reads only the current HTTP request's `force`
parameter, which `DELETE /api/modules/{id}?purgeData=true` does not carry. The new
`public static final ScopedValue<Boolean> FORCE` is the explicit, scoped way for platform code to
say "forced". A request parameter cannot leak into an unrelated delete. A `ThreadLocal` can, on a
pooled thread, as the hook's own `PENDING_DROP` comment explains.

### 3.6 `ModuleController` API changes (exact)

| Endpoint | Change | Part |
|---|---|---|
| `POST /api/modules/install` | Body gains optional `planHash` (string) and `acknowledge` (string array). With no row for the id and no `planHash`: unchanged. With a row for the id and no `planHash`: `409`, message ends `POST /api/modules/plan and send its planHash to upgrade`. With a `planHash`: plan-checked install or upgrade. New statuses: `422` (blockers, `errors[].code` = blocker code), `409` with `errors[0].code` = `PlanChanged` or `ConsentRequired` and `meta.plan` | 3a (`422` for `PLATFORM_TOO_OLD`), 3b (rest) |
| `POST /api/modules/install-jar` | Optional multipart parts `planHash` and `acknowledge` (comma-separated), same semantics. The signature check stays first (`403`) | 3a, 3b |
| `POST /api/modules/plan` | **New.** `consumes = application/json`, body `{ "manifest": "<json>" }`; and `consumes = multipart/form-data`, parts `manifest`, `jar`, optional `signature` (verified → `403` as install-jar). `200` `module-install-plans`. A plan with blockers is still `200` (the plan is the answer); `400` only for unparseable manifest JSON | 3b |
| `GET /api/modules/{moduleId}/uninstall-plan` | **New.** Query `purgeData` (default `false`). `200` `module-uninstall-plans`; `404` when the module is not installed | 3c |
| `DELETE /api/modules/{moduleId}` | Optional query `planHash` and `purgeData` (default `false`). Without `planHash`: unchanged behaviour; the response attributes gain `retained` (array of `{resourceType, naturalKey, ownership}`). With `planHash`: `403` when `purgeData=true` without `CUSTOMIZE_APPLICATION`, `409 PlanChanged`, else `200` with attributes `removed`, `retained` (with `decision`), `failed` | 3c |
| `GET /api/modules/{moduleId}/health` | Gains `platformVersion` and `minPlatformVersionSatisfied` | 3a |

The new paths sit under `/api/modules/**`, already a static gateway route, so no gateway change is
needed. `ModuleController` adds no `/**` mapping: both new paths are fixed-depth (`/plan`,
`/{moduleId}/uninstall-plan`), the shape of the existing `/{moduleId}/health`. So the
`DynamicCollectionRouter` catch-all pitfall does not apply. `ModuleHttpController`'s
`/{moduleId}/x/**` does not overlap either.

**Authorization.** The new endpoints follow the controller's existing precedent: the gateway's
`API_ACCESS` only, as slice 2 recorded for `provisioned-resources`. Install and upgrade are no more
powerful than the install endpoints already are. The one new power is deleting a tenant's data,
and that is gated in-controller on `CUSTOMIZE_APPLICATION`. Gating the whole controller is
slice 4's job.

## 4. DB migrations

**None.** The Flyway head at base `6488c0c` is **V206**
(`kelta-worker/src/main/resources/db/migration/V206__collection_ownership.sql`). If a part finds it
needs a migration after all, claim the next number at that time. Do not assume V207 is still free.

Why none is needed:

- Plans are recomputed and compared, never stored (§3.3).
- Upgrade updates existing `tenant_module` columns in place. `version` is `varchar(20)` (V1
  baseline), enough for SemVer with a short pre-release tag. `ModuleVersion.parse` rejects longer
  values with a clear message rather than letting Postgres truncate-fail.
- `chk_module_status` (as amended by
  `kelta-worker/src/main/resources/db/migration/V189__module_fail_closed.sql`) needs no
  `UPGRADING` value: the upgrade is synchronous within one request, and a failure is recorded as
  the existing `FAILED`.
- Uninstall reads and deletes `module_provisioned_resource` rows
  (`kelta-worker/src/main/resources/db/migration/V190__module_provisioned_resource.sql`), whose
  columns already carry everything: `ownership`, `content_hash`, `resource_id`.

## 5. File-by-file code changes

All paths exist at main `6488c0c` unless marked **new**.

**3a**

- `kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleVersion.java` —
  **new**. `record ModuleVersion(int major, int minor, int patch, List<String> preRelease)
  implements Comparable<ModuleVersion>`, `static ModuleVersion parse(String)` (leading `v`
  stripped, build metadata dropped, max 20 characters, `IllegalArgumentException` otherwise).
- `kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleManifest.java`
  — `manifestVersion`, `metadata` + `MetadataItem`, `FieldManifest.referenceTo`, delegating
  constructors (§3.1). Javadoc for `minPlatformVersion` states that it is enforced at
  install/upgrade.
- `kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleManifestParser.java`
  — parses `manifestVersion` (absent = 1, only 1 and 2 accepted), `referenceTo`, `metadata` (each
  item needs a textual `type` and an object `data`), and rejects the v2-only keys in a v1 manifest.
  The type allowlist is enforced in the worker (next item), because runtime-core does not know
  `PackageImportService`.
- `kelta-worker/src/main/java/io/kelta/worker/module/PlatformVersion.java` — **new** (§3.2).
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleMetadataProvisioner.java` —
  `reference_collection_name` translation, `metadata` items appended and admitted to the filter,
  the allowlist check inside `validate` (before any import), ownership for metadata items, and the
  post-apply snapshot hash for metadata items (shared with 3c's `ModuleResourceSnapshot`; in 3a a
  package-private method on the provisioner that 3c moves out).
- `kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java` — the
  `minPlatformVersion` check at the top of both install paths (after the signature check, before the
  upload). It takes a `PlatformVersion` through a setter (as with `setProvenanceStore`) so the
  existing public constructors are unchanged. The method stays short: this file is 1,300 lines,
  and the plan/upgrade/uninstall logic goes into the new classes of 3b/3c, not here.
- `kelta-worker/src/main/java/io/kelta/worker/config/ModuleConfig.java` — `setPlatformVersion(...)`
  on the manager.
- `kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java` — catch the
  version refusal as `422`; health gains the two fields.
- `kelta-worker/src/main/resources/application.yml` — `kelta.platform.version: ${KELTA_VERSION:}`.
- `kelta-modules/billing/src/main/resources/kelta-module.json` — `minPlatformVersion` → `0.1.0`.

**3b**

- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleInstallPlan.java` — **new**, the
  record behind §3.3 with a `toAttributes()` for the controller and a `computeHash(ObjectMapper)`.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleLifecyclePlanner.java` — **new**,
  `@Service`: `planInstall(...)`. Depends on `ModuleStore`, `ModuleManifestParser` (via
  `RuntimeModuleManager.parseManifest`), `ModuleMetadataProvisioner`, `ModuleProvenanceStore`,
  `PlatformVersion`, `JdbcTemplate` (the flow-reference query) and `ObjectMapper`.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModulePlanChangedException.java` — **new**.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleMetadataProvisioner.java` — public
  `plan(...)` (validate + dry run, no apply). `provision(...)` gains an `ownedCollections`
  overload, and the existing signature delegates with an empty set.
- `kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java` — `upgradeModule`
  (§3.4). Both install paths branch to it when a row exists and a `planHash` was given, and keep
  the `409` otherwise.
- `kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/module/ModuleStore.java` —
  `updateModuleForUpgrade(...)` and `replaceActions(...)`.
- `kelta-worker/src/main/java/io/kelta/worker/module/JdbcModuleStore.java` — their SQL
  (`replaceActions` transactional).
- `kelta-platform/runtime/runtime-events/src/main/java/io/kelta/runtime/event/ModuleChangeType.java`
  — `UPGRADED`.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleEventListener.java` — the `UPGRADED`
  case (§3.4 step 7).
- `kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java` — `POST /plan`
  (both consumes), `planHash` / `acknowledge` on both install endpoints, error mapping (§3.6). The
  `UPGRADED` publish replaces `INSTALLED` when the manager reports an upgrade (it returns the mode
  alongside the row).

**3c**

- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleResourceSnapshot.java` — **new**
  (§3.5). Takes over the metadata snapshot hash from 3a's provisioner.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleUninstallPlan.java` — **new**.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleUninstaller.java` — **new**, `@Service`:
  `plan(...)`, `uninstall(...)`. Record counts come from `QueryEngine` count on the collection's
  definition. Child counts come from `CollectionDeletionGuardHook.countChildren`, exposed as a
  `public static` helper that takes a `JdbcTemplate`. The guard's `CASCADING_CHILDREN` list stays
  the single source of truth.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleProvenanceStore.java` —
  `deleteRow(tenantId, id)`. `deleteForModule` gains its first caller.
- `kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java` —
  `uninstallModule` returns the deleted row's data so `ModuleUninstaller` can sequence after it.
  No behaviour change.
- `kelta-worker/src/main/java/io/kelta/worker/listener/CollectionDeletionGuardHook.java` —
  `FORCE` scoped value; `forceRequested()` checks it first.
- `kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java` —
  `GET /{moduleId}/uninstall-plan`, `DELETE /{moduleId}` parameters and response, the
  `CUSTOMIZE_APPLICATION` check (constructor gains `CerbosPermissionResolver` and
  `BootstrapRepository` through `ObjectProvider`, matching how it already takes optional beans).

**Harness and E2E.** Nothing in `kelta-test-harness/` or `e2e-tests/` installs a module or calls
`/api/modules`. `grep -rln "api/modules" kelta-test-harness e2e-tests` at `6488c0c` matches only
`e2e-tests/tests/auth/real-sign-in.spec.ts`, which names the path in a comment about a past
redirect loop. So no fixture changes. `kelta-ui/app/src/pages/ModulesPage/ModulesPage.tsx` calls
`POST /api/modules/install`, `POST /api/modules/install-jar` and `DELETE /api/modules/{id}` with no
`planHash`. §3.6 keeps all three unchanged, so its test file needs no change either.

## 6. Test plan

**3a**

- `ModuleVersionTest` (
  `kelta-platform/runtime/runtime-core/src/test/java/io/kelta/runtime/module/ModuleVersionTest.java` **new**):
  parse/ordering table (`1.9.0 < 1.10.0`, `1.2.3-rc.1 < 1.2.3`, `1.2.3-alpha < 1.2.3-alpha.1`,
  `+build` ignored, `v` prefix), and rejection of `main`, `1.2`, `""`, and a 21-character value.
- `ModuleManifestParserTest`
  (`kelta-platform/runtime/runtime-core/src/test/java/io/kelta/runtime/module/ModuleManifestParserTest.java`):
  every existing case unchanged; absent `manifestVersion` → 1; `3` rejected; v2 `referenceTo` and
  `metadata` parsed; `metadata` in a v1 manifest rejected; a `metadata` item without `data`
  rejected; the billing manifest file parses as v1.
- `PlatformVersionTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/PlatformVersionTest.java` **new**): `0.1.0`, `v0.1.0`,
  blank, `main`.
- `ModuleMetadataProvisionerTest`
  (`kelta-worker/src/test/java/io/kelta/worker/module/ModuleMetadataProvisionerTest.java`):
  `referenceTo` → `reference_collection_name`; `metadata` items appended and in the apply filter;
  disallowed type rejected with `importPackage` never called; a `SKIPPED` metadata item is
  `ADOPTED` with a null hash; a `CREATED` one carries the post-apply snapshot hash (`QueryEngine`
  mocked).
- `RuntimeModuleManagerTest`
  (`kelta-worker/src/test/java/io/kelta/worker/module/RuntimeModuleManagerTest.java`): with platform
  `0.1.0`, a `minPlatformVersion: "1.0.0"` manifest is refused on `installModule` and on
  `installModuleWithJar`, with `uploadJar` and `createModule` never called; with the platform
  unknown, the same install succeeds; with `0.1.0` and `"0.1.0"`, it succeeds.
- `ModuleControllerProvenanceTest`
  (`kelta-worker/src/test/java/io/kelta/worker/controller/ModuleControllerProvenanceTest.java`, or a
  new `ModuleControllerLifecycleTest` alongside it): the refusal maps to `422`; health carries
  `platformVersion`.

**3b**

- `ModuleLifecyclePlannerTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/ModuleLifecyclePlannerTest.java` **new**): modes
  `INSTALL` / `UPGRADE` / `NO_OP` / `BLOCKED` with each blocker code; action/route/service diffs;
  `SKIP_ADOPTED` vs `CREATE` for a new field on an adopted vs owned collection;
  `orphanedByUpgrade`; the flow-reference query's SQL text is pinned and its rows become
  `consentRequired`. **Hash stability**: the same inputs with the dry-run items in a different
  order give the same hash. **Hash sensitivity**: each input listed in 3b's acceptance changes it.
  A mocked `JdbcTemplate` cannot prove that `jsonb_path_exists` matches a real flow definition
  (`.claude/docs/concerns.md`, "an assertion that a value was passed to a mock is not an assertion
  that the database accepts it"). Also cover that query in a `kelta-test-harness` scenario against
  real Postgres if the part can. The harness shares one tenant, so give the flow a unique name and
  delete it in `finally`.
- `RuntimeModuleManagerUpgradeTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/RuntimeModuleManagerUpgradeTest.java` **new**): the
  `409` without `planHash`; `PlanChanged` with nothing written; a successful upgrade keeps the row
  id and status, calls `updateModuleForUpgrade` + `replaceActions`, provisions with the module's
  `CREATED` collections as `ownedCollections`, unloads and reloads on this pod only when it was
  loaded, publishes nothing itself (the controller publishes), and deletes the old JAR last; a
  provisioning failure marks the row `FAILED` without throwing; a missing `acknowledge` → consent
  error.
- `JdbcModuleStoreUpgradeTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/JdbcModuleStoreUpgradeTest.java` **new**): the SQL text
  of both new methods; `replaceActions` deletes before inserting.
- `ModuleEventListenerTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/ModuleEventListenerTest.java` **new**): `UPGRADED`
  unloads by id and reloads from the row only when the pod had it loaded; the existing
  `INSTALLED` / `UNINSTALLED` behaviour pinned while the file is open.
- `ModuleControllerLifecycleTest` (
  `kelta-worker/src/test/java/io/kelta/worker/controller/ModuleControllerLifecycleTest.java` **new**,
  standalone MockMvc): `POST /plan` JSON and multipart; `planHash` + `acknowledge` passed through on
  both install endpoints; `422` / `409 PlanChanged` (with `meta.plan`) / `409 ConsentRequired`
  envelopes; `UPGRADED` published on an upgrade and `INSTALLED` on a fresh install.

**3c**

- `ModuleResourceSnapshotTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/ModuleResourceSnapshotTest.java` **new**): for the row
  values `ModuleMetadataProvisioner.toPackage` would write, the snapshot hash **equals** the
  provisioner's recorded `contentHash`, for a collection and for a field with and without
  `referenceTo`. This is the test that keeps slice-2 rows removable. A changed `display_name`
  changes the hash.
- `ModuleUninstallerTest` (
  `kelta-worker/src/test/java/io/kelta/worker/module/ModuleUninstallerTest.java` **new**): every row of the
  §3.5 decision table, including `ADOPTED` never deleted under `purgeData=true`; FIELD rows of a
  removed collection are not deleted separately; reverse-`TYPE_ORDER` delete order (`InOrder`);
  `FORCE` bound only with `purgeData` (assert `CollectionDeletionGuardHook.FORCE.isBound()` inside
  the `QueryEngine.delete` answer); a delete that throws is reported `FAILED` and the rest
  continue; provenance rows deleted for removed/`GONE` entries only; `deleteForModule` called only
  when nothing is kept; slug bound around the deletes.
- `CollectionDeletionGuardHookTest`
  (`kelta-worker/src/test/java/io/kelta/worker/listener/CollectionDeletionGuardHookTest.java`):
  existing cases unchanged; a delete with children passes when `FORCE` is bound to `true` and no
  request is present; still blocked when bound to `false`.
- `ModuleProvenanceStoreTest`
  (`kelta-worker/src/test/java/io/kelta/worker/module/ModuleProvenanceStoreTest.java`):
  `deleteRow` SQL is tenant-scoped.
- `ModuleControllerLifecycleTest`: `GET /uninstall-plan` (`404` for an unknown module);
  `DELETE` without `planHash` keeps today's response plus `retained`; `purgeData=true` without
  `CUSTOMIZE_APPLICATION` → `403` with `ModuleUninstaller.uninstall` never called; `409
  PlanChanged`.
- **Manual, post-merge on a JVM worker** (not runnable in CI: CI installs no module). On a sandbox
  tenant: install `kelta-modules/billing` v1 → bump its version, rebuild, sign → `POST /plan` shows
  `UPGRADE` → apply with the hash → `GET /api/modules/kelta-billing/health` on two pods shows the
  new `version` and `loadedOnThisPod: true` → `GET /uninstall-plan` shows `KEEP_HAS_DATA` for
  populated collections → `DELETE` with the hash removes only the empty ones →
  `/provisioned-resources` lists the kept rows.

## 7. Docs to update

Per part, in the same PR (CLAUDE.md Rule 6):

- `.claude/docs/specs/module-platform/README.md` — slice-3 row state per part. Known defect 3
  (`minPlatformVersion` half, 3a), 5 (3b) and 6 (3c) marked FIXED with the closing part.
- `.claude/docs/status.md` — Extensibility / modules row: slice 3 state per part; remove "reinstall/
  upgrade still 409" at 3b and "uninstall does not drop them" at 3c.
- `.claude/docs/playbooks.md` — "Ship a runtime-installable module": `manifestVersion` 2 keys and
  the allowlist (3a); "bump the version, plan, apply with `planHash`" as the upgrade procedure (3b);
  "uninstall removes only what the module created and nobody edited; data needs `purgeData`" (3c).
- `.claude/docs/architecture.md` — the `/api/modules/plan` and `/uninstall-plan` endpoints and the
  in-controller `CUSTOMIZE_APPLICATION` check under "Authorizing a new endpoint" (3b, 3c).
- `CLAUDE.md` — none: no new subject, module or stack change. `UPGRADED` is a new enum value on
  the existing `kelta.config.module.changed.<tenantId>.<moduleId>` subject that
  `ModuleConfigEventPublisher` already publishes.
- `README.md` — `KELTA_VERSION` is now also read by the worker, not only used as the image tag
  (3a), next to where the README documents it for the quickstart.
- `kelta-modules/billing/README.md` — the `minPlatformVersion` change and how to upgrade the
  installed module with a plan (3a/3b).
- `kelta-marketing/src/content/docs/platform/modules.md` — its example manifest shows
  `"minPlatformVersion": "1.0.0"`; correct it to a version that exists and say that it is enforced
  (3a).

## 8. Risks & open questions

- **The platform version is unknown wherever `KELTA_VERSION` is not in the worker's environment.**
  That includes any deployment whose manifests live outside this repo, such as the homelab Argo
  manifests, and the check then degrades to a WARN. This is deliberate: refusing every module on a
  deployment that never set the variable would be an outage caused by a compatibility check. The
  durable fix bakes the version into the image: a `KELTA_VERSION` build arg →
  `ENV KELTA_VERSION` in `kelta-worker/Dockerfile.jvm`, passed by the release workflow. That
  touches a Dockerfile and `.github/`, which no part of this slice names, so it is a **follow-up**
  that needs `touches` naming those files. Until it lands, `make up-jvm` from a source checkout
  also reports the `.env` value (`0.1.0`) rather than the commit it built. That is acceptable for a
  minimum-version check and wrong for anything stricter.
- **Content-hash false mismatches.** If any platform write changes a hashed column of a module's
  collection after install (the candidate is `collection.current_version`; no code bumping it was
  found at `6488c0c`), every such collection reads `KEEP_MODIFIED` and uninstall removes nothing.
  This is the safe direction, and `ModuleResourceSnapshotTest` plus the manual check in §6 would
  show it. The fix would be to drop the column from the projection, not to loosen the comparison.
- **Plan/apply races.** Re-planning at apply closes most of the window. What remains is the gap
  between the re-plan and the writes in the same request. For upgrade this is the slice-2 race
  (ownership comes from the apply pass). For uninstall, a record inserted into an "empty"
  collection in that gap is dropped with it when the plan said `REMOVE` without `purgeData`. Close
  it by re-counting records immediately before each collection delete and downgrading the entry to
  `KEEP_HAS_DATA` when the count is non-zero. 3c should do this. It is cheap.
- **Upgrade is not transactional across S3, rows, metadata and every pod.** The failure modes are
  ordered so that each leaves a state an admin can see and re-apply (`FAILED` + `last_error`, the
  old JAR deleted last). A pod that misses the `UPGRADED` event keeps serving v1 until restart:
  this is Known defect 9, unchanged by this slice, and `/health` shows it per pod.
- **`acknowledge` is a client-supplied string.** It is consent bookkeeping, not authorization. It
  only proves the caller read the plan. Whoever may upgrade may acknowledge, and slice 4 decides
  who may upgrade.
- **Open question 1: where does the plan UI go?** Recommendation: one follow-up UI task after 3c
  that gives `ModulesPage` a plan dialog for install, upgrade and uninstall (with the `purgeData`
  checkbox), built so slices 4 and 7 can add their approval sections to it. Filing it inside 3b or
  3c would push those parts past one PR and add Playwright coverage that has no signed module to
  install in CI.
- **Open question 2: manifest `FLOW` items.** Useful: billing's pass expiry is a flow an admin
  creates by hand today. But a module-provisioned flow runs with platform powers on a schedule the
  admin did not author, and flows reference action keys that upgrade can remove. Leave it out of the
  v2 allowlist until slice 7 has a capability for it.
- **Open question 3: downgrade.** Blocked here. The parent allows it with consent, but a downgrade
  must also decide what to do with metadata the newer version created, and no tenant has asked for
  it. Revisit if one does.
