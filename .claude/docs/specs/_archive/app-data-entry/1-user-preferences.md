# Slice 1 — `user-ui-preferences` + Server-Side Views

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR). Conforms to the parent's
> [`user-ui-preferences` contract](./README.md#user-ui-preferences-slice-1-contract) and
> [Security](./README.md#security) sections.
>
> **Security-typed — never auto-merged** (the guard hook is the authorization control on a
> user-writable collection).

> **Superseded guard (2026-10-10, member-data-ownership slice 4 / KLT-380).** The bespoke
> `UserPreferenceGuardHook` described below is deleted. `user-ui-preferences` now declares
> ownership metadata in `SystemCollectionDefinitions.userUiPreferences()` — `ownerField=userId`,
> `ownerScope=ALL`, `ownerScopeReads=true` — enforced by the generic `OwnerScopeGuardHook`
> (writes) and the `PhysicalTableStorageAdapter` owner predicate (reads). Two behaviour changes:
> reads are now **owner-filtered** (closing the tenant-readable gap noted in §1), and a foreign
> update/delete is a **404** (was a 400). See `specs/member-data-ownership/` and architecture.md →
> Owner-scoped collections.

## 1. Goal & scope

Server-persisted per-user UI preferences: the `user-ui-preferences` system collection
(V163), a mandatory owner guard (`UserPreferenceGuardHook`), and the FE
`usePreferenceValue` store seam. `useSavedViews` becomes server-backed (prefType
`list-view`, prefKey = collection) with the legacy localStorage key as warm cache, offline
fallback, and one-time migration source — saved views finally follow the user across
browsers. **Favorites delivered later (2026-10-11, KLT-481):** `AppContext` backs favorites
with `usePreferenceValue('favorites', 'default', { localKey: favoritesStorageKey(scope) })`
— server wins once loaded, one-time push of local favorites when the row is absent, invalid
server entries dropped, per-tenant localStorage as warm cache and offline fallback. Recents
stay local by decision (see §8). **Not delivered:** SavedView v2 fields (slice 2), row-level read policy
(documented tenant-readable gap — closed by the ownership metadata above).

## 2. UI samples

None — no visible UI change. Observable: save a view in browser A, open browser B as the
same user → the view is there.

## 3. Data & API contracts

Table + collection per the parent contract (V163, unique `(tenant, user, prefType,
prefKey)`, generic route `/api/user-ui-preferences`, no static route, **no NATS hook** —
rows are read per-request, nothing caches them in a registry; re-verified).

**Guard (`UserPreferenceGuardHook`, order −100, this collection only — retired, see the note
at the top):** caller =
`X-User-Id` header → `UserIdResolver` → UUID (the Phase-1 approval-hardening chain).
Create: `record.userId` must equal caller. Update: `previous.userId` must equal caller and
the row cannot be re-owned. Delete: owner looked up by id (the `beforeDelete` SPI carries
no row snapshot). Present-but-unresolvable identity → rejected (fail-closed). No HTTP
request context, or request without the identity header → admitted (internal tier — flows,
schedulers, SCIM; identical contract to `IdentityCollectionGuardHook`).

**FE store:** `usePreferenceValue<T>(prefType, prefKey, {localKey?})` →
`{value, isLoaded, save}`. Query filtered `userId+prefType+prefKey`; `save` upserts
(PATCH by cached row id, else POST) with optimistic cache write + localStorage mirror;
no resolvable identity → localStorage-only mode. `useSavedViews` keeps its exact public
API; server value wins on load; empty server + local views → one-time migration push.

## 4. DB migrations

`V163__create_user_ui_preference.sql` — table + unique + owner index + RLS
(`tenant_isolation` + `admin_bypass`, baseline pattern).

## 5. File-by-file code changes

BE: `V163__…​.sql` (new) · `SystemCollectionDefinitions.userUiPreferences()` + `all()` ·
`listener/UserPreferenceGuardHook.java` (new) · `FlowConfig` bean registration.
FE: `hooks/usePreferenceStore.ts` (new) · `hooks/useSavedViews.ts` (server sync layered
over the unchanged mutation logic).
Tests: `UserPreferenceGuardHookTest` (6) · `UserPreferenceScenarioTest` (harness, real
RLS: self CRUD 2xx; cross-user create/update/delete rejected + row untouched) ·
`usePreferenceStore.test.ts` (4) · `useSavedViews.test.ts` (4).

## 6. Test plan

As listed in §5; `/verify` green before PR. Playwright: N/A — no UI change (cross-browser
persistence is by nature not a single-session e2e; the harness scenario is the guard).

## 7. Docs to update (same PR)

status.md (saved views row: server persistence; Phase-2 row), concerns.md (add
`user-ui-preferences` to the tenant-readable system-collection entry), architecture.md
(guard hook row), conventions.md (preference-store seam rule), parent README slice table,
memory.

## 8. Risks & open questions

- **Favorites shipped separately (KLT-481); recents stay local by decision.** Favorites
  moved to the store in their own PR (prefType `favorites`, prefKey `default`, one row per
  user holding the array). The sync only mounts for a signed-in user: `AppContextProvider`
  also wraps the login route, and resolving `/api/me/identity` there would 401 and
  redirect the login page to itself. Server writes start only after the row has loaded, so
  an early toggle cannot create a second row; until then it writes localStorage and the
  server value wins on load. **Recents are not migrated**: `addRecentItem` runs on every
  record visit, so server persistence would cost a preference write per navigation for a
  list that is a convenience cache, not user-authored data. They remain per-tenant
  localStorage (`kelta_recent_items:<scope>`) and do not follow the user across browsers.
- **Rows are tenant-readable** (generic-route read gap class) — preferences are
  low-sensitivity but saved-view filters can embed data values; added to the existing
  concerns entry; row-level read policy is the shared v2 fix.
- **StrictMode double-invoke** can fire `prefSave` twice from a mutation updater — the
  save is an idempotent upsert; harmless. `AppContext.toggleFavorite` avoids it outright:
  it computes the next array and calls `setFavorites` and `save` outside any updater.
- **Concurrent browsers last-write-wins** on the whole view array (single row per
  collection) — acceptable for preferences; no ETag plumbing in v1.
