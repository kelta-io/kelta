# Slice 2 — List Power Pack

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR). Owns the [SavedView v2 model](./README.md#savedview-v2-the-shared-model).
> Frontend-only; not security-typed.
>
> **Parent correction:** the parent claimed multi-sort "needs a small additive backend
> extension" — WRONG on re-verification. `SortField.fromParams` already splits the comma
> grammar, `QueryRequest.sorting` is already a `List<SortField>`, every storage adapter
> already joins multi-field ORDER BY (identifier-sanitized), and `validateSortFields`
> iterates. **Zero backend change in this slice**; the FE simply stops truncating to one
> level.

## 1. Goal & scope

Delivers on `ObjectListPage`: **multi-sort** (shift-click headers appends/toggles levels;
URL `sort=a,-b`; numbered sort badges), **column chooser + reorder** (popover; never below
one column; feeds the view's `visibleColumns`), **density toggle**
(compact/normal/comfortable), **sticky first column** (opt-in CSS, selection checkbox +
first data column), **SavedView v2** (`sorts[]`, `viewType`, `density`, `groupBy`,
`typeConfig` — all optional, absent = today's behavior; `viewSorts()` helper resolves v2
over v1 fields), and **`view=<id>` deep links**. Admin `ResourceListPage` untouched (all
`ObjectDataTable` additions are opt-in props). **Not delivered:** grouping rendering
(slice 3 consumes `groupBy`), alt-view renderers (5–7), drag-reorder in the chooser
(up/down buttons v1).

## 2. UI samples

Toolbar row: `[Views ▾] [Columns] [Normal]` — Columns opens a popover (checkbox +
up/down per visible column); the density button cycles. Header shift-click adds a sort
level; multi-sort shows `↑1 ↓2` badges. Saving a view captures columns/sorts/density.

## 3. Data & API contracts

- Server: unchanged — `sort=a,-b` verified end-to-end (`SortField.fromParams` →
  `validateSortFields` → `buildOrderByClause`, sanitized identifiers).
- `listUrlState.ts`: `parseSortParam`/`buildSortParam`; `parseListViewParams` adds
  `sorts: SortState[]` + `viewId` (keeps `sort` = first level for single-sort consumers).
- `useCollectionRecords`: `sort` option widened to `SortState | SortState[]` (comma
  serialization; offline replica sorts by all levels).
- `ObjectDataTable` opt-in props: `sorts?`, `density?`, `stickyFirstColumn?`;
  `onSortChange(field, additive?)` (additive = shift).
- SavedView v2 per the parent model; `viewSorts(view)` = `sorts` else legacy
  `sortField/sortDirection`. Shared `list-views` rows map their single sort into `sorts`.
- `view=<id>` selects the view on load (its state params ride the URL as before);
  precedence: chooser override > active view columns > first-6.

## 4. DB migrations

None.

## 5. File-by-file code changes

`listUrlState.ts` (+tests) · `useCollectionRecords.ts` (param build + offline multi-sort)
· `useSavedViews.ts` (v2 fields, `viewSorts`, saveView passthrough) ·
`ObjectDataTable.tsx` (props, SortIcon levels, shift-click, density paddings incl.
DataRow plumbing, sticky cells) · `components/ColumnChooser/` (new, +tests) ·
`ObjectListPage.tsx` (multi-sort handler, chooser + density in toolbar, v2 capture on
save, `view=` deep link) · `listViewMapping.ts` (sorts) · `en.json`
(`columnChooser.*`, `listPower.*`).

## 6. Test plan

Vitest: sort grammar round-trip + viewId parse; ColumnChooser toggle/floor/reorder;
existing ObjectListPage/saved-views/records suites green (18 files / 113 tests in the
affected areas). Playwright: post-deploy — extend the list e2e (shift-click two levels →
URL `sort=a,-b`; chooser hides a column; save/reload view). `/verify` green before PR.

## 7. Docs to update (same PR)

Parent README (slice row SHIPPED + the multi-sort correction), conventions.md (SavedView
v2 + `view=` param contract), status.md row, memory.

## 8. Risks & open questions

- Sticky-column background rides `bg-inherit` over the zebra rows — verify visually on
  deploy (jsdom can't).
- `sort` prop (single) kept for the admin grid — retire after slice 3+ settles.
- Density affects the virtualizer's fixed 40px row estimate only cosmetically
  (estimate stays; measurement corrects) — revisit if compact lists >100 rows jitter.
