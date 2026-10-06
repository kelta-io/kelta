# Slice 2 — Owner-Scoped Collections

> Parent: [`README.md`](README.md). Depends on slice 1. **Security — never auto-merged.**

## 1. Goal & scope

**Delivers** the `ownerField` / `ownerScope` / `ownerScopeReads` collection settings and their
enforcement:
- **Reads:** an owner predicate in SQL for every QueryEngine read of an owner-scoped
  collection (list, get-by-id, aggregate, `/latest`, includes, exports, reports, search).
- **Writes:** a generic wildcard `OwnerScopeGuardHook` — stamp owner on create, owner-only
  update/delete, owner field immutable, foreign/missing rows → 404.
- Admin UI to set both on a collection; validation; NATS propagation.

**Does not** retire the seven bespoke hooks (slice 4) or touch `users` (slice 3).

## 2. UI samples

Collection settings (`CollectionForm.tsx`), new "Ownership" group:

```
Ownership
  Owner field   [ member ▾ ]      (LOOKUP→users fields + "Created by")
  Restrict to   ( ) Nobody (default)
                (•) Portal members — members see and change only their own records
                ( ) Everyone except admins with View/Modify All Data
  [x] Also hide other people's records from reads
      (untick for public-read, author-edit collections)
```

## 3. Data & API contracts

Collection attributes (`collections` system collection, JSON:API):
`ownerField: string | null`, `ownerScope: "NONE" | "PORTAL" | "ALL"` (default `NONE`),
`ownerScopeReads: boolean` (default `true`).

Runtime:
```java
// CollectionDefinition — new components (with the usual backward-compatible constructors)
String ownerField;      // null = not owned
OwnerScope ownerScope;  // NONE | PORTAL | ALL
boolean ownerScopeReads; // false = writes only
```

Enforcement contract (owner-scoped caller = `CallerContext.current()` present **and**
`caller.ownerScoped(definition.ownerScope())`):

| Operation | Behaviour |
|---|---|
| list / aggregate / latest / export / report | `WHERE ... AND <owner column> = :callerId` added in SQL (only when `ownerScopeReads`) |
| get by id | same predicate → foreign row = not found (404) (only when `ownerScopeReads`) |
| include (`?include=`) | included rows of an owner-scoped collection are filtered the same way (only when `ownerScopeReads`) |
| create | owner field set to caller; a client-supplied different value → 400 `OWNER_MISMATCH` (creating *for* someone else is a client bug, not a probe) |
| update / delete | stored owner must equal caller, else **404** |
| update owner field | rejected (immutable) |

## 4. DB migrations

`V205__collection_ownership.sql` (re-check head):
```sql
ALTER TABLE collection ADD COLUMN owner_field varchar(100);
ALTER TABLE collection ADD COLUMN owner_scope varchar(10) NOT NULL DEFAULT 'NONE'
  CHECK (owner_scope IN ('NONE','PORTAL','ALL'));
ALTER TABLE collection ADD COLUMN owner_scope_reads boolean NOT NULL DEFAULT true;
```
No index migration is generic; the adoption guide (slice 5) tells tenants to index the owner
field (`FieldDefinition` `indexed`), and slice 4 adds indexes for the system collections it
converts.

## 5. File-by-file code changes

| File | Change |
|---|---|
| `runtime-core/.../model/CollectionDefinition.java`, `CollectionDefinitionBuilder.java`, new `OwnerScope.java` | components + builder |
| `runtime-core/.../model/system/SystemCollectionDefinitions.java` | `collections()` gains `ownerField`, `ownerScope` fields (`withColumnName`) |
| `kelta-worker/.../lifecycle/CollectionLifecycleManager.java` | `SELECT_COLLECTION_*` column lists + `buildDefinitionFromDb` (capture_geo precedent) |
| `runtime-core/.../storage/PhysicalTableStorageAdapter.java` | `ownerScope(...)` predicate beside `tenantScope(...)` in `query`, `aggregate`, `getById` |
| `kelta-worker/.../listener/OwnerScopeGuardHook.java` (new) | wildcard `BeforeSaveHook`, order −100; reads `CallerContext` + definition |
| `kelta-worker/.../config/FlowConfig.java` | register the hook |
| `kelta-worker/.../listener/CollectionOwnershipValidationHook.java` (new) or existing collection-save validation | `ownerScope != NONE` ⇒ valid `ownerField` (LOOKUP→`users` or `createdBy`) |
| `kelta-worker/.../router/DynamicCollectionRouter.java` | map "not owned" to 404 on get/update/delete |
| `kelta-worker/.../realtime` / `RealtimeBridge` (gateway) | owner-scoped collections publish **invalidation-only** events (no record data) |
| `kelta-ui/app/src/components/CollectionForm/CollectionForm.tsx`, `types/collections.ts`, i18n ×6 | Ownership group |
| `CollectionConfigEventPublisher` | unchanged — `collection.changed` already refreshes every pod |

## 6. Test plan

- runtime-core unit: adapter emits the owner predicate only for owner-scoped callers; INTERNAL
  under `PORTAL` scope unaffected; `ALL` + `viewAll` bypasses reads only.
- Worker unit: `OwnerScopeGuardHook` — stamp on create, reject foreign update/delete (404),
  owner field immutable, internal tier (no context) admitted.
- **Real-DB harness scenario** (`kelta-test-harness`, per testing.md "Real-DB guard"): two portal
  users with CRUD on an owner-scoped collection; each sees only their rows on list (correct
  `totalCount`), get, aggregate, `/latest`, include; cross-owner PATCH/DELETE → 404; a staff
  user with `VIEW_ALL_DATA` under `ALL` sees everything.
- Playwright: set Ownership in the collection form; a portal session sees only its own rows.

## 7. Docs to update

`architecture.md` (Authorizing a new endpoint → owner scoping), `playbooks.md` ("Make a
collection member-owned"), `status.md`, `concerns.md` (close the "row-level read policy" debt
for owner-scoped collections; residual realtime note).

## 8. Risks & open questions

- **Paths that bypass `PhysicalTableStorageAdapter`** (raw JDBC repositories, e.g.
  `WatchRepository`, `PushDeviceController`) are not covered — list them in the PR and keep them
  self-scoped by code until slice 4.
- **`CerbosRecordAuthorizationAdvice` still runs** after the query; owner scoping composes with it
  (it can only remove more rows). Confirm no double-404 confusion on get.
- **Performance:** owner predicate needs an index on the owner column for large collections.
- **Open:** should `ALL` honour record shares (`record_share`) to widen access? Proposed: yes —
  `RecordShareAccessService.widen` runs after, as today, so a shared foreign row is still visible.
