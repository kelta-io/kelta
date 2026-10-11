# Slice 2 — Provenance + Unified Provisioning

> **Status:** partial — planned remainder. Shipped:
> `kelta-worker/src/main/resources/db/migration/V190__module_provisioned_resource.sql` and
> `kelta-worker/src/main/java/io/kelta/worker/module/ModuleProvenanceStore.java`, written by
> `RuntimeModuleManager.recordProvenance` as `COLLECTION` rows marked `CREATED` or `ADOPTED`.
> This spec covers the rest: provisioning through `PackageImportService` instead of
> `ModuleCollectionProvisioner`, content hashes, FIELD provenance, ownership that survives a
> reinstall, and a read surface so an admin can find what a module left behind.
> Source-verified against main `ece7925` on 2026-10-11 (Flyway head V206).

> Child of [`.claude/docs/specs/module-platform/README.md`](README.md). Implements the parent's
> **Provenance** shared contract and the Reuse map row "Provision collections, fields, …" →
> `PackageImportService`. Closes the open half of Known defect 6 that does not need slice 3.

## 1. Goal & scope

`ModuleCollectionProvisioner` is a second, weaker metadata writer: it handles two of
`PackageImportService`'s thirteen types (`COLLECTION`, `FIELD`), cannot resolve a LOOKUP target,
has no dry run, and reports nothing per item. Every later slice that provisions more than bare
collections (pages, menus, layouts, picklists, flows in slice 3; billing's five collections in
slice 8) would have to grow it into a copy of `PackageImportService`. This slice removes it.

In scope:

- **One provisioning path.** A module's manifest `collections` are translated into a package
  (`{"items":[{"type":"COLLECTION","data":{…}}, {"type":"FIELD","data":{…}}]}`) and applied by
  `PackageImportService.importPackage`, the importer the sandbox clone
  (`SandboxProvisioningService`), metadata promotion (`MetadataPromotionService`) and package import
  (`PackageService`) already use. Writes still go through `QueryEngine`, so the collection/field
  NATS broadcast and the physical-table DDL fire exactly as they do today.
- **Behaviour preserved.** A collection that already exists is **adopted and left completely alone**,
  including its fields: no field is added to it, none updated. This is the current
  `ModuleCollectionProvisioner` behaviour, and `.claude/docs/playbooks.md` → "Ship a runtime-installable module"
  tells admins to pre-create a module's collections on a tenant with live data, relying on it.
  Manifest validation (collection name `^[a-z][a-z0-9_]*$`, field name `^[a-zA-Z][a-zA-Z0-9_]*$`,
  reserved field names, non-blank type) still rejects a malformed manifest **before anything is
  written**. A provisioning failure still marks the module `FAILED` without throwing out of install.
- **Provenance per the parent contract.** One `module_provisioned_resource` row per `COLLECTION`
  and per `FIELD` the run touched, with `resource_id` (the created or existing row id) and, for
  `CREATED`, a `content_hash`. `ADOPTED` rows carry `content_hash = null`: the module never
  removes them, so there is nothing to compare against.
- **Ownership never downgrades.** Uninstall keeps provenance rows (it already does: nothing calls
  `deleteForModule`), so after an uninstall + reinstall the module's own earlier collection
  "already exists". Re-recording it as `ADOPTED` would make it unremovable forever. A row already
  `CREATED` for the same `(tenant, module, type, natural_key)` stays `CREATED` with its original
  hash.
- **Read surface.** `GET /api/modules/{moduleId}/provisioned-resources` lists the provenance rows
  for the caller's tenant, **including for a module that is no longer installed** — that is the
  defect-6 question ("which collections did the module I removed leave behind?").

Out of scope (later slices of the parent):

- Removing anything on uninstall, `purgeData`, or comparing hashes at uninstall — slice 3
  (lifecycle). This slice only makes the data that decision needs correct.
- Upgrade, adding fields to an adopted collection, and `OVERWRITE` — slice 3; it needs the
  install plan and admin consent the parent requires for `OVERWRITE` on tenant-modified metadata.
- New manifest item types (pages, menus, layouts, picklists, LOOKUP fields) — slice 3 manifest v2.
  This slice changes no manifest shape, so `kelta-modules/billing/src/main/resources/kelta-module.json`
  installs unchanged.
- Any change to `PackageImportService`'s behaviour for its existing callers.

Acceptance (for the code task filed from this spec):

- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleCollectionProvisioner.java`,
  `kelta-worker/src/test/java/io/kelta/worker/module/ModuleCollectionProvisionerTest.java` and
  `kelta-worker/src/test/java/io/kelta/worker/module/ModuleProvisionOwnershipTest.java` are deleted; no main or test code references
  `ModuleCollectionProvisioner`.
- Installing a module whose manifest declares a new collection creates the collection and its
  fields through `PackageImportService.importPackage`, and records one `CREATED` `COLLECTION` row
  and one `CREATED` `FIELD` row per field, each with a non-null `resource_id` and a 64-hex
  `content_hash`.
- Installing over an existing collection of the same name records one `ADOPTED` `COLLECTION` row,
  creates no field on it (a manifest field the existing collection lacks is still not added), and
  records no `FIELD` rows for it.
- A malformed manifest (bad name, reserved field, blank type) is rejected before
  `PackageImportService.importPackage` is called.
- An import item that fails marks the module `FAILED`; install does not throw.
- Re-recording a key already `CREATED` as `ADOPTED` leaves it `CREATED` with its original hash.
- `GET /api/modules/{moduleId}/provisioned-resources` returns the tenant's rows for that module id,
  including after uninstall, and never another tenant's rows.

## 2. UI samples

N/A — backend only. The admin UI does not render provenance in this slice; the read endpoint is
the surface, and a `ModulesPage` panel belongs with slice 3's uninstall dialog, which is where an
admin needs it.

## 3. Data & API contracts

**Manifest → package translation** (no manifest change). Keys are the system tables' column
names, which is what `PackageImportService.mapRowToFields` matches on
(`FieldDefinition.effectiveColumnName()`):

| Manifest | Package item |
|---|---|
| `collections[i]` | `{"type":"COLLECTION","data":{"name", "display_name", "path":"/api/<name>", "active":true, "current_version":1}}` — `display_name` defaults as today (`name` with a capitalised first letter) |
| `collections[i].fields[j]` | `{"type":"FIELD","data":{"collection_name":<collection>, "name", "display_name" (defaults to `name`), "type", "required", "field_order":j, "active":true}}` |

`PackageImportService` already sets `tenantId` and `systemCollection=false` on the collection and
resolves `collection_name` → `collectionId`.

**Two-pass apply**, so adoption is decided per collection, before anything is written:

1. `importPackage(tenantId, pkg, new ImportOptions(SKIP, /*dryRun*/ true, null, null, userId))`.
   A `COLLECTION` item reported `SKIPPED` already exists → **adopted**. (Dry run does not write;
   `upsertViaEngine` registers a placeholder id so the FIELD items still resolve their collection.)
2. `importPackage(tenantId, pkg, new ImportOptions(SKIP, false, null, itemKeyFilter, userId))`,
   where `itemKeyFilter` (`"<TYPE>:<naturalKey>"`, as `PackageImportService.included` expects)
   holds every `COLLECTION` key plus the `FIELD` keys (`<collection>.<field>`) of collections
   **not** adopted. Fields of an adopted collection are therefore never written.

Any `FAILED` item in pass 2 → the module is marked `FAILED` (same handling as a thrown
`ModuleCollectionProvisioner` error today); provenance is still recorded for the items that
succeeded, so a retry adopts its own half-created collection as `CREATED` (ownership never
downgrades, below).

**`PackageImportService.ItemResult` gains `resourceId`** (the created or existing row id), with a
secondary 4-argument constructor delegating to it with `null`, so the existing
`new ItemResult(type, key, action, error)` call sites in `PackageService`, `PackageServiceTest`
and the importer's own failure paths compile unchanged. `upsertViaEngine` fills it on
`CREATED`, `UPDATED` and `SKIPPED`. The package-import HTTP report is built field by field in
`PackageService`, so its JSON shape is unchanged.

**Provenance rows** (`module_provisioned_resource`, V190 columns, unchanged):

| `resource_type` | `natural_key` | `ownership` | `content_hash` |
|---|---|---|---|
| `COLLECTION` | `<name>` | `CREATED` if pass 1 did not skip it, else `ADOPTED` | `CREATED`: `ModuleJarService.sha256(canonicalJson(item.data))`; `ADOPTED`: `null` |
| `FIELD` | `<collection>.<field>` | `CREATED` (fields are only written for created collections) | as above |

`canonicalJson` = the item's `data` map serialised with keys sorted (recursively copy into `TreeMap`s, or Jackson's
`ORDER_MAP_ENTRIES_BY_KEYS`), UTF-8. The hash covers what the module asked for, so slice 3 can
compare it against a hash of the row as it stands.

**`ModuleProvenanceStore.record` upsert** keeps the first `CREATED`:

```sql
ON CONFLICT (tenant_id, module_id, resource_type, natural_key) DO UPDATE SET
  module_version = EXCLUDED.module_version,
  resource_id    = COALESCE(EXCLUDED.resource_id, module_provisioned_resource.resource_id),
  ownership      = CASE WHEN module_provisioned_resource.ownership = 'CREATED'
                        THEN 'CREATED' ELSE EXCLUDED.ownership END,
  content_hash   = CASE WHEN module_provisioned_resource.ownership = 'CREATED'
                        THEN module_provisioned_resource.content_hash
                        ELSE EXCLUDED.content_hash END
```

**Endpoint** `GET /api/modules/{moduleId}/provisioned-resources` (in `ModuleController`, tenant from
`X-Tenant-ID`): `200` JSON:API collection of type `module-provisioned-resources`, one resource per
row with attributes `resourceType`, `naturalKey`, `resourceId`, `ownership`, `contentHash`,
`moduleVersion`, `createdAt` (from `ModuleProvenanceStore.findByModule`, newest first). An unknown
module id returns `200` with an empty `data` array, not `404`: an uninstalled module has no
`tenant_module` row but may still have provenance. `/api/modules/**` is already a static gateway
route, so no gateway change.

**Tenant binding.** The provisioner binds `TenantContext.callWithTenant(tenantId, slug, …)`, the
slug from `TenantSlugResolver.resolveSlug` (the parent's Tenant binding contract). Today
`ModuleCollectionProvisioner` binds only the id and works because the install request path has
already bound the slug; a provisioning run from any other caller would read the public schema.

## 4. DB migrations

None. `module_provisioned_resource` (V190) already has every column this slice writes, and the
upsert change is SQL in `ModuleProvenanceStore`, not DDL. (If a later revision finds one is
needed, the Flyway directory head at 2026-10-11 is V206 — take the head + 1 at that time.)

## 5. File-by-file code changes

All paths exist at main `ece7925` unless marked **new**.

- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleMetadataProvisioner.java` — **new**,
  `@Service`. Constructor: `PackageImportService`, `TenantSlugResolver`, `ObjectMapper`.
  `ProvisionResult provision(String tenantId, ModuleManifest manifest, String executingUserId)`:
  validate (the rules and messages moved verbatim from `ModuleCollectionProvisioner.validate`),
  translate (section 3), dry-run pass, filtered apply pass, and return a
  `ProvisionResult(List<ProvisionedResource> resources, List<ItemResult> failures)` where
  `ProvisionedResource(String type, String naturalKey, String resourceId, String ownership,
  String contentHash)`. Holds no provenance-store dependency: recording stays in
  `RuntimeModuleManager`, next to the module-status bookkeeping.
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleCollectionProvisioner.java` — **delete**.
- `kelta-worker/src/main/java/io/kelta/worker/module/RuntimeModuleManager.java` — the
  `ModuleCollectionProvisioner collectionProvisioner` field and constructor parameter (all three
  public constructors that take it) become `ModuleMetadataProvisioner metadataProvisioner`.
  `provisionCollections` calls `metadataProvisioner.provision(tenantId, manifest, installedBy)`,
  marks the module `FAILED` when `failures` is non-empty or the call throws, and passes the result
  to `recordProvenance`, which writes one row per `ProvisionedResource` (still best-effort,
  logged at WARN on failure, as today).
- `kelta-worker/src/main/java/io/kelta/worker/module/ModuleProvenanceStore.java` — the
  ownership-preserving upsert (section 3). `deleteForModule` stays and stays uncalled (slice 3).
- `kelta-worker/src/main/java/io/kelta/worker/service/PackageImportService.java` — add
  `resourceId` to `ItemResult` plus the delegating 4-argument constructor; `upsertViaEngine` passes
  the id on `CREATED`/`UPDATED`/`SKIPPED`. No other behaviour change.
- `kelta-worker/src/main/java/io/kelta/worker/config/ModuleConfig.java` — inject
  `ModuleMetadataProvisioner` instead of `ModuleCollectionProvisioner` in `runtimeModuleManager(...)`.
- `kelta-worker/src/main/java/io/kelta/worker/controller/ModuleController.java` — add
  `GET /{moduleId}/provisioned-resources`; constructor gains `ModuleProvenanceStore` (resolve via
  `ObjectProvider` like `moduleRouteRegistry`, returning an empty list when absent).

Tests:

- `kelta-worker/src/test/java/io/kelta/worker/module/ModuleCollectionProvisionerTest.java` —
  **delete**; its six cases move to `ModuleMetadataProvisionerTest`.
- `kelta-worker/src/test/java/io/kelta/worker/module/ModuleProvisionOwnershipTest.java` —
  **delete**; its created/adopted cases move to `ModuleMetadataProvisionerTest`.
- `kelta-worker/src/test/java/io/kelta/worker/module/ModuleMetadataProvisionerTest.java` — **new**.
- `kelta-worker/src/test/java/io/kelta/worker/module/ModuleProvenanceStoreTest.java` — **new**.
- `kelta-worker/src/test/java/io/kelta/worker/controller/ModuleControllerProvenanceTest.java` —
  **new**.
- `kelta-worker/src/test/java/io/kelta/worker/module/RuntimeModuleManagerHookTest.java` — the
  `mock(ModuleCollectionProvisioner.class)` at line ~115 and the `provisionWithOwnership` verify
  at ~141 switch to `ModuleMetadataProvisioner.provision`.
- `kelta-worker/src/test/java/io/kelta/worker/service/PackageImportServiceTest.java` — add the
  `resourceId` assertions.

Nothing in `kelta-test-harness/` or `e2e-tests/` installs a module or posts to `/api/modules`
(grep at 2026-10-11: the only hit, `e2e-tests/tests/auth/real-sign-in.spec.ts`, names
`/api/modules` in a comment about a past redirect loop), so no fixture changes.

## 6. Test plan

- `ModuleMetadataProvisionerTest` (Mockito over `PackageImportService` + `TenantSlugResolver`):
  - translation: a two-field manifest produces one `COLLECTION` and two `FIELD` items with the
    exact column keys and defaults in section 3 (`display_name` default, `path`, `field_order`);
  - new collection: dry run reports `CREATED` → apply pass `itemKeyFilter` contains the collection
    and both field keys → result has three `CREATED` resources with ids and 64-hex hashes;
  - existing collection: dry run reports the collection `SKIPPED` → apply pass `itemKeyFilter`
    excludes its field keys → one `ADOPTED` resource, `content_hash` null, no `FIELD` resources;
  - the six `ModuleCollectionProvisionerTest` cases (create, default display name, skip existing,
    malformed manifest rejected **with `importPackage` never called**, reserved field name,
    empty manifest is a no-op with no `importPackage` call);
  - a `FAILED` item lands in `failures` and the succeeded items are still returned;
  - the slug is bound: `importPackage` observes `TenantContext.getSlug()` equal to the
    resolver's answer (assert inside the `thenAnswer`).
  - hash stability: same `data` in a different map insertion order gives the same hash.
- `ModuleProvenanceStoreTest`: the upsert SQL keeps `CREATED` and its hash when re-recorded as
  `ADOPTED`, and upgrades `ADOPTED` → `CREATED`. A mocked `JdbcTemplate` cannot see an `ON CONFLICT`
  clause execute (`.claude/docs/concerns.md`: "an assertion that a value was passed to a mock is not an
  assertion that the database accepts it"), so put this case in a `kelta-test-harness` scenario
  against real Postgres if the code task can, and in any case assert the SQL text in the unit test.
  A harness scenario shares one tenant: use a unique `module_id` and delete its rows in `finally`.
- `RuntimeModuleManagerHookTest`: install with a manifest collection calls
  `ModuleMetadataProvisioner.provision` and records one provenance row per returned resource; a
  provisioner that returns failures marks the module `FAILED` and install returns normally.
- `ModuleControllerProvenanceTest` (standalone MockMvc): rows for the tenant are returned as
  JSON:API; an unknown module id is `200` with empty `data`; the store is called with the
  `X-Tenant-ID` tenant only.
- `PackageImportServiceTest`: `resourceId` is set for `CREATED` and `SKIPPED` items; existing
  assertions unchanged.
- Manual, post-merge on a JVM worker (not runnable in CI): install `kelta-modules/billing` on a
  sandbox tenant with none of its collections → five collections, `CREATED` rows; uninstall,
  reinstall → rows still `CREATED`; `GET /api/modules/kelta-billing/provisioned-resources` after
  uninstall still lists them.

## 7. Docs to update

- `.claude/docs/specs/module-platform/README.md` — slice 2 state → shipped; Known defect 6 →
  states what is left for slice 3 (removal on uninstall).
- `.claude/docs/status.md` — Extensibility / modules row: provisioning runs through
  `PackageImportService`; provenance read endpoint.
- `.claude/docs/playbooks.md` — "Ship a runtime-installable module" (`ModuleCollectionProvisioner`
  is named at the collections step, the install-ordering note, and the tests paragraph): name
  `ModuleMetadataProvisioner` → `PackageImportService`, keep the "existing collections are adopted
  and left alone" rule, and replace `ModuleCollectionProvisionerTest` with
  `ModuleMetadataProvisionerTest`.
- `.claude/docs/concerns.md` — the tenant-stamping paragraph that lists the direct
  `queryEngine.create(fieldsDef, …)` callers names `ModuleCollectionProvisioner`; drop it from that
  list (`PackageImportService` already stamps `tenantId`).
- `kelta-worker/CLAUDE.md` — only if it names `ModuleCollectionProvisioner` at the time (it does
  not at 2026-10-11).

## 8. Risks & open questions

- **Dry run and apply can disagree.** A collection created by someone else between pass 1 and pass 2
  is skipped by pass 2's `SKIP` mode, but pass 1 called it "created", so its fields are in the
  filter and would be added to a collection the module did not make. The window is one install
  request; accept it, but derive ownership from **pass 2's** `COLLECTION` result (`SKIPPED` there →
  `ADOPTED`), not from pass 1, so provenance is never wrong even if a field slips in.
- **`PackageImportService` is shared with sandbox clone and promotion.** The only change is an
  additive record component; keep it that way. Any behavioural change to the importer for modules
  belongs behind an `ImportOptions` flag, not in the shared path.
- **`FIELD` provenance is new.** Rows for fields mean slice 3 can remove a field the module added
  without dropping the collection; it also means a manifest with many fields writes many rows.
  Billing's manifest has 34 fields across five collections — fine.
- **Read endpoint authorization.** `/api/modules/**` receives only the gateway's `API_ACCESS`
  check, and `ModuleController`'s existing endpoints (install, enable, uninstall) enforce nothing
  further in-controller. The new endpoint is read-only metadata, matching the existing
  `GET /api/modules` list, so it follows that precedent; gating the whole controller on a module
  permission is slice 4's job (`.claude/docs/architecture.md` → Authorizing a new endpoint). Do not quietly fix
  it in this slice.
- **Open:** should pass 1's dry-run report be returned from install so the admin sees "will adopt
  `billing_plans`"? Useful, but it is slice 3's install plan; this slice only logs it.
