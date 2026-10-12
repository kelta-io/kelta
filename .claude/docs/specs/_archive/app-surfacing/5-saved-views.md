# Slice 5 — Saved Views in the App

> Child spec of [App Surfacing (Phase 1)](./README.md), authored with the implementation
> (same PR). Conforms to the parent's
> [Saved view contract](./README.md#saved-view-existing-model--slice-5-wires-shared-views-added).
> Frontend-only; not security-typed.

## 1. Goal & scope

Wires the existing `ViewSelector` + `useSavedViews` (localStorage, previously admin-only)
into the end-user `ObjectListPage`, adds **shared views** (admin-authored PUBLIC
`list-views` rows, read-only), auto-applies a default view on a clean first visit (explicit
URL state always wins), and finally lets a view's `visibleColumns` drive the column set —
replacing the hardcoded first-6 rule (which remains the fallback). **Not delivered:**
server-persisted personal views (cross-device — parent deferral), a column-chooser UI
(Phase 2), editing shared views from the app (Setup → List Views owns them).

## 2. UI samples

`ViewSelector` renders under the list toolbar. Selecting a view applies its
filters/sort/pageSize onto the URL (deep links unchanged) and its columns onto the grid;
"Save view" captures the current state; delete/rename/set-default on a `shared:` view is
rejected with a toast ("Shared views are managed in Setup → List Views").

## 3. Data & API contracts

- Personal views: existing `useSavedViews(collectionName)` (localStorage
  `kelta_views_<collection>`), unchanged.
- Shared views: `GET /api/list-views?filter[collectionId][eq]=<schema.id>&filter[visibility][eq]=PUBLIC`
  mapped by `mapSharedListView` into the `SavedView` shape with id `shared:<rowId>`:
  `columns` (JSON array or JSON string) → `visibleColumns`; `filters` entries are accepted
  **only** when already in the FE `FilterCondition` shape (the admin builder's server-side
  operator grammar is a superset — non-conforming entries are dropped, columns/sort still
  apply); `rowLimit` must be a valid page size (10/25/50/100) else 25; `sortDirection`
  `DESC`→`desc`.
- Applying a view writes the canonical list URL state (`?filter/sort/pageSize`) via the
  page's existing `updateParams`; clearing the view clears those params.
- Default resolution on a clean URL: personal default wins over shared default; applied
  once per mount (`appliedDefaultRef`).
- Columns: `orderFieldsByView(accessibleFields, view.visibleColumns)` (view order wins,
  FLS-filtered fields only); null → first-6 fallback.

## 4. DB migrations

None.

## 5. File-by-file code changes

`pages/app/ObjectListPage/listViewMapping.ts` (+tests, new) — mapping + ordering helpers;
`hooks/useSharedListViews.ts` (new); `ObjectListPage.tsx` — ViewSelector in the toolbar
slot, view state/apply/save/guard handlers, default auto-apply, `visibleFields` honors the
active view; `en.json` `savedViews.sharedReadOnly`.

## 6. Test plan

Vitest: `mapSharedListView` (shape, JSON-string columns, filter-shape guard, rowLimit
fallback), `orderFieldsByView` (order/filter/null-fallback). Page-level behavior rides the
URL-state machinery already covered by ObjectListPage's suite. Post-deploy Playwright:
covered by extending the existing list e2e once deployed (save a view → reload → applied).

## 7. Docs to update (same PR)

`status.md` (saved views in the end-user list), parent README slice table.

## 8. Risks & open questions

- Shared-view filters using server-grammar operators are silently dropped (columns/sort
  still apply) — surfaced in the spec, revisit when the admin builder emits FE-shape
  conditions.
- Personal views remain per-browser (localStorage) — cross-device sync deferred.
- ViewSelector shows rename/delete affordances on shared views (shared component,
  unmodified); the handlers reject with a toast. A `readOnly` affordance flag on
  ViewSelector is a nice-to-have follow-up.
