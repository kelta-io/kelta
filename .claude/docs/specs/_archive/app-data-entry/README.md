# App Data-Entry Excellence (Phase 2) — Parent Spec

> **Status:** parent planning spec. Authoritative shared contract for the data-entry phase of
> the app-UX roadmap: list power (columns, multi-sort, density, grouping, frozen column),
> main-list mass edit, alternate views (kanban / calendar / gallery), and server-persisted
> per-user preferences. Each slice in the [Slice plan](#slice-plan) is expanded into its own
> child spec in this directory and **extends, never contradicts** the
> [Reuse Map](#reuse-map), [SavedView v2 contract](#savedview-v2-the-shared-model), and
> [Security](#security) sections below.
>
> Source-verified 2026-07-08, immediately after Phase 1
> ([`specs/_archive/app-surfacing/`](../app-surfacing/README.md)) fully merged (#1194–#1199). Flyway
> head file is V1__baseline + `V162__seed_view_analytics_permission.sql`; **next new
> migration is V163** (deployed history keeps pre-flatten numbering — never number below
> V162). If code and this doc disagree, trust the code and fix this doc.

## How to use this document

Child specs each cover one PR-sized slice per the [Child-spec template](#child-spec-template).
Read this parent first; every child references it.

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — `user-ui-preferences` + server-side views | `1-user-preferences.md` — **SHIPPED 2026-07-08**; favorites server-backed 2026-10-11 (KLT-481), recents stay localStorage by decision (§8) | **backend+FE, security** |
| 2 — List power pack | `2-list-power.md` — **SHIPPED 2026-07-08** (FE-only: the parent's "multi-sort needs a backend extension" claim was wrong — the server already accepts `sort=a,-b` end-to-end) | **columns/sort/density (FE)** |
| 3 — Page grouping + aggregates | `3-grouping.md` — **SHIPPED 2026-07-08** (client-side page grouping, collapsible headers, count + numeric sums; group field prepended to server sort) | **list UX (FE)** |
| 4 — Main-list mass edit | `4-mass-edit.md` — **SHIPPED 2026-07-08** (bulk-job submit/poll extracted to shared `utils/bulkUpdate.ts`; RelatedList refactored onto it) | **bulk UX (FE)** |
| 5 — Kanban view | `5-kanban.md` — **SHIPPED 2026-07-08** (lanes from picklist values, dnd-kit drag → PATCH w/ fresh If-Match; `usePicklistOptions` promoted to `src/hooks/`) | **alt views (FE)** |
| 6 — Calendar view | `6-calendar.md` — **SHIPPED 2026-07-08** (dependency-free month grid; visible-range gte/lte merged into the standard query) | **alt views (FE)** |
| 7 — Gallery view | `7-gallery.md` — **SHIPPED 2026-07-08** (responsive card grid, url-field image w/ placeholder fallback) | **alt views (FE)** |

**Dependency order (hard edges): 1 → 2 → {3, 5, 6, 7}; 4 independent** (any time).
Slice 2 owns the SavedView v2 model everything else hangs off.

## Context

Phase 1 surfaced the built backends; Phase 2 makes the list view — where data-entry users
live — genuinely powerful. Verified current state:

1. **Column set is hardcoded** — first 6 accessible fields unless a saved view supplies
   `visibleColumns` (Phase 1 slice 5); there is **no chooser UI** to author that list.
2. **Sort is single-field end-to-end** — `SortState {field, direction}`, URL `sort=[-]f`,
   and the server's `QueryRequest` grammar accepts one field. Multi-sort needs a small
   additive backend extension.
3. **No grouping, no density control, no frozen columns** — `ObjectDataTable` exposes none
   of these (virtualized >100 rows at a fixed 40px estimate; plain `<TableCell>`s).
4. **Mass edit exists but only on related lists** — `RelatedList` + `MassEditDialog` submit
   ONE bulk job (`POST /api/bulk-jobs` `{collectionId, operation:'UPDATE', records:[{id,
   <field>: value}]}`, poll `GET /api/bulk-jobs/{id}` 2s×30 to
   `COMPLETED|FAILED|ABORTED`), gated on `MANAGE_DATA`. The main list still lacks it.
5. **No kanban/calendar/gallery anywhere** — no components, no libraries. `@dnd-kit` is in
   the bundle (page-builder canvas only).
6. **Every user preference is localStorage** — saved views (`kelta_views_<collection>`),
   favorites/recents (`AppContext`). Nothing survives a browser change.

## Key architecture decisions (verified against the code)

- **SavedView v2 is the keystone (slice 2).** One extended model carries everything:
  `viewType` (`table|kanban|calendar|gallery`), `density`, ordered `sorts[]`, `groupBy`,
  and per-view-type config. Back-compat is absolute: absent fields read as today's behavior
  (`table`, `normal`, single sort). Alternate views are *renderers over the same data
  layer* (`useCollectionRecords` + the canonical URL state in `listUrlState.ts`) selected
  by the active view — not new pages.
- **Preferences move server-side once, centrally (slice 1).** New `user-ui-preferences`
  system collection (generic dynamic route — **no gateway static route needed**, verified):
  one row per `(userId, prefType, prefKey)` with a JSON `value` — saved views
  (`prefType='list-view'`, key = collection), favorites, recents. FE reads/writes through a
  `usePreferenceStore` seam that falls back to (and one-time-migrates from) localStorage,
  so offline and logged-out behavior degrade gracefully. **Write guard is mandatory**: a
  `UserPreferenceGuardHook` (BeforeSaveHook) rejects any write whose `userId` ≠ the
  caller's canonical UUID (`X-User-Id` → `UserIdResolver`, the slice-2/Phase-1 pattern) —
  without it any user could edit anyone's preferences via the generic route.
- **Multi-sort is a small additive server change (slice 2).** Extend the `sort` param to
  the JSON:API comma form (`sort=stage,-amount`) in `QueryRequest.fromParams` + the
  storage-adapter ORDER BY; single-field callers are untouched. FE: `SortState` →
  `SortState[]` at the list layer (URL `sort=a,-b`; `listUrlState` owns parsing), with
  shift-click column headers appending a sort level.
- **Grouping is client-side over the fetched page (slice 3).** Real cross-page aggregates
  are the report engine's job (already shipped); the list groups the *current page* by one
  field with per-group count + numeric sums and an explicit "on this page" label. No
  backend change; no silent pretense of totals.
- **Kanban uses `@dnd-kit`** — already a dependency; the concerns.md rule is *don't mix DnD
  libraries in one component tree*, and the kanban board is its own tree. Lanes come from
  the picklist values of the chosen field (`usePicklistOptions` — **promoted from
  `PageBuilderPage/widgets/builtins/inputs/` to `src/hooks/` in slice 5**, page-builder
  imports updated); a card drag PATCHes the lane field through the normal JSON:API path
  with `If-Match` (optimistic-locking ETag already ships) and rides `permissions.canEdit`.
- **Calendar adds no dependency.** A month grid is CSS grid + `Intl`/native `Date`; the
  view binds one date/datetime field and queries the visible range via existing
  `filter[<f>][gte]/[lte]` operators (verified server grammar). A child spec wanting a
  calendar library must justify the dependency explicitly.
- **Main-list mass edit is a lift, not a build (slice 4).** Reuse `MassEditDialog` +
  the exact RelatedList bulk-job flow on `ObjectListPage`'s existing selection state; same
  `MANAGE_DATA` gate (consistency beats convenience — the bulk path bypasses per-record
  advice, so the permission stays the boundary).
- **`ObjectDataTable` grows opt-in props, never behavior changes** — `density?`,
  `stickyFirstColumn?`, `groupBy?`, `sorts?` all default to current rendering; the admin
  `ResourceListPage` (same grid) is untouched until it opts in.

## Reuse Map

Use these — do not rebuild.

| Need | Reuse | Path |
|------|-------|------|
| Data layer (fetch/sort/filter/paginate) | `useCollectionRecords` (+ `FILTER_OPERATOR_MAP` FE→server) | `kelta-ui/app/src/hooks/useCollectionRecords.ts` |
| Canonical list URL state + deep links | `listUrlState.ts` (`parseListViewParams`, `buildListUrl`) | `kelta-ui/app/src/pages/app/ObjectListPage/` |
| Grid (virtualized, selection, keyboard, inline edit) | `ObjectDataTable` + `DataTablePagination` | `kelta-ui/app/src/components/ObjectDataTable/` |
| Saved views UI + personal store | `ViewSelector`, `useSavedViews`, `useSharedListViews`, `listViewMapping.ts` | Phase 1 slice 5 files |
| Typed field editor for one field | `MassEditDialog` (FieldControl registry `Edit`) | `kelta-ui/app/src/components/RelatedList/MassEditDialog.tsx` |
| Bulk update job + poll | RelatedList flow: `POST /api/bulk-jobs` → poll to terminal | `RelatedList.tsx` (`POLL_INTERVAL_MS`, `TERMINAL_JOB_STATUSES`) |
| Field display in cards/cells | `FieldRenderer`, `InlineFieldValue` | `kelta-ui/app/src/components/` |
| Picklist lane values | `usePicklistOptions` (FIELD/GLOBAL resolution) | promoted to `src/hooks/` in slice 5 |
| DnD | `@dnd-kit/{core,sortable}` (already a dep) | package.json |
| Record CRUD + ETag | `useRecordMutation`, `apiClient` `If-Match` support | `kelta-ui/app/src/hooks/`, `services/apiClient.ts` |
| Realtime refresh | Phase 1 slice 4 invalidation (`['collection-records', c]` prefix) — alt views inherit it for free by keeping the same query keys | `src/realtime/` |
| System-collection recipe | playbooks.md §5 (definitions + Flyway table + RLS + hook) | `.claude/docs/playbooks.md` |
| Identity (canonical user UUID) | `useMyIdentity` / `GET /api/me/identity`; BE `UserIdResolver` | Phase 1 slice 2 |

## SavedView v2 (the shared model)

Slice 2 owns this; slices 3/5/6/7 extend `typeConfig` only.

```ts
interface SavedViewV2 {
  // v1 fields unchanged (id, name, collectionName, filters, visibleColumns, pageSize, isDefault, createdAt)
  sorts?: Array<{ field: string; direction: 'asc' | 'desc' }>  // supersedes sortField/sortDirection (kept for back-compat reads)
  viewType?: 'table' | 'kanban' | 'calendar' | 'gallery'       // absent = 'table'
  density?: 'compact' | 'normal' | 'comfortable'               // absent = 'normal'
  groupBy?: string | null                                       // table view, this-page grouping
  typeConfig?: {
    kanban?: { laneField: string; cardFields?: string[] }
    calendar?: { dateField: string; endDateField?: string }
    gallery?: { imageField?: string; titleField?: string; cardFields?: string[] }
  }
}
```

- **URL remains the source of truth for filters/sort/page**; `viewType`/`density`/`groupBy`
  /`typeConfig` live on the view (and a `view=<id>` URL param makes alternate views
  deep-linkable — slice 2 adds it to `listUrlState`).
- Shared `list-views` rows map into v2 with `viewType='table'` (admin builder doesn't
  author alt views yet; that's a Phase 3 concern).
- Server-side storage (slice 1) serializes the same object into
  `user-ui-preferences.value`.

## `user-ui-preferences` (slice 1 contract)

```
collection: user-ui-preferences (table user_ui_preference, V163, RLS tenant policy)
fields: userId (string 36, required) · prefType (string 30, required: list-view|favorites|recents)
        · prefKey (string 200: collection name or '-') · value (JSON, required)
unique: (tenant, userId, prefType, prefKey)
```

- Served by the generic dynamic route (`/api/user-ui-preferences`) — no static route.
- **`UserPreferenceGuardHook`** (wildcard-free, this collection only): on create/update/
  delete via HTTP, resolve the caller (`X-User-Id` → `UserIdResolver` → UUID, fail-closed)
  and reject when `userId` ≠ caller. Registered per playbooks §5; no NATS broadcast (rows
  are read per-request, nothing caches them in-registry — re-verify at implementation).
- FE `usePreferenceStore`: read-through query per `(prefType, prefKey)` filtered
  `filter[userId][eq]=<me>`; write-through mutation; localStorage fallback when offline or
  unauthenticated; one-time migration of existing `kelta_views_*` / favorites on first
  server write.

## Security

- **Slice 1 is security-typed — never auto-merged** (the guard hook is an authorization
  control on a user-writable collection; shipping the collection without it lets any tenant
  user overwrite anyone's preferences).
- **Read exposure**: `user-ui-preferences` rows are tenant-readable like every system
  collection (the approvals-visibility gap class, already documented in concerns.md).
  Preferences are low-sensitivity, but saved-view filters can embed data values — add this
  collection to the same concerns.md entry; row-level read policy remains the shared v2 fix.
- **Kanban drag = a write**: rides the normal JSON:API PATCH (Cerbos + write-FLS + record
  locking + `If-Match`) — a 409/403 must visually snap the card back and toast; never
  retry-loop a denied write.
- **Mass edit** keeps the `MANAGE_DATA` gate (bulk path bypasses per-record advice — the
  in-controller permission is the boundary; same rationale as bulk-jobs hardening).
- Multi-sort/grouping/density are read-path UI — no new authz surface; grouping runs
  client-side on rows the caller already received.

## Slice plan

- **Slice 0 — Parent spec + doc wiring** (this file). CLAUDE.md specs row; memory.
- **Slice 1 — `user-ui-preferences` + server-side views** (backend+FE, **security**).
  V163 migration + system collection + `UserPreferenceGuardHook` (+ worker unit tests +
  harness scenario: self-write 2xx, cross-user write rejected, guard survives body spoof) +
  `usePreferenceStore` with localStorage migration; `useSavedViews`/favorites/recents
  switch to it behind the same APIs.
- **Slice 2 — List power pack** (BE+FE). Server comma multi-sort (additive;
  `QueryRequest.fromParams` + adapter ORDER BY + unit tests incl. injection-safe field
  validation reuse); FE multi-sort (`sorts[]`, shift-click headers, URL `sort=a,-b`);
  **column chooser + reorder** (popover over accessible fields → `visibleColumns`);
  density toggle (`ObjectDataTable.density` row-height + padding, incl. virtual-row
  estimate); sticky first column (`stickyFirstColumn` opt-in CSS); SavedView v2 model +
  `view=` URL param. Owns extending `ViewSelector` minimally (view captures the new state).
- **Slice 3 — Page grouping + aggregates** (FE). `groupBy` on the table view: group headers
  with count + numeric-field sums over the fetched page, collapsible groups, explicit
  "groups reflect this page" caption; sorts prepend the group field server-side so groups
  are contiguous.
- **Slice 4 — Main-list mass edit** (FE). `MassEditDialog` + bulk-job submit/poll on
  `ObjectListPage` selection; results toast (success/error counts, link to the bulk job);
  `MANAGE_DATA`-gated; invalidates the list on terminal status.
- **Slice 5 — Kanban** (FE). `KanbanBoard` renderer for `viewType='kanban'`: lanes from
  `usePicklistOptions(laneField)` (hook promoted to `src/hooks/`), cards via
  `FieldRenderer` over `typeConfig.kanban.cardFields ?? visibleColumns`, per-lane counts,
  `@dnd-kit` drag → PATCH `{[laneField]: lane}` with `If-Match` (409 → snap back + reload
  toast), `canEdit`-gated drag, filters/search still apply (same query).
- **Slice 6 — Calendar** (FE). `CalendarMonthView` for `viewType='calendar'`: no new dep;
  month grid over `typeConfig.calendar.dateField`, visible-range `gte/lte` filters merged
  into the standard query, day cells list records (overflow "+N"), record click-through,
  month pager; date-field picker limited to date/datetime fields.
- **Slice 7 — Gallery** (FE). `GalleryGrid` for `viewType='gallery'`: responsive card grid,
  optional `imageField` (attachment/URL) with `FieldRenderer` fallback header, title +
  cardFields, same pagination.

Every FE slice: Vitest in-PR; a Playwright spec under `e2e-tests/tests/end-user/` that runs
in the PR's E2E job, never skip-gated (precedent: `approvals-inbox.spec.ts`; `testing.md` →
End-to-end); i18n keys for all new
strings; DESIGN.md rules (11px labels, table recipe, no new status colors).

## Child-spec template

Identical to the app-surfacing template — every section present or "N/A — <reason>":
Goal & scope · UI samples · Data & API contracts · DB migrations (verify head; next is
**V163**) · File-by-file changes · Test plan (Vitest / worker unit / harness when
DB-sensitive / post-deploy Playwright) · Docs to update · Risks & open questions.

## Docs to update (per CLAUDE.md Rule 6)

- `.claude/docs/status.md` — new "App data-entry (Phase 2)" row; move per-slice capabilities
  as they ship; saved-views row gains server persistence at slice 1.
- `.claude/docs/conventions.md` — SavedView v2 contract + `view=` URL param (slice 2);
  preference-store seam rule (slice 1).
- `.claude/docs/concerns.md` — add `user-ui-preferences` to the tenant-readable
  system-collection entry (slice 1); `SystemCollectionDefinitions` size note if it grows.
- `.claude/docs/architecture.md` — preference guard hook row (slice 1).
- `CLAUDE.md` reference-doc `specs/` row — add this parent.
- Memory `project_app_ux_roadmap.md` — Phase 2 progress per slice.
