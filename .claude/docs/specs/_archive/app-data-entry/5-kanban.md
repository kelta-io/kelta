# Slice 5 — Kanban View

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR as slices 6+7). Frontend-only; not security-typed. Consumes SavedView v2's
> `viewType`/`typeConfig.kanban` (owned by [slice 2](./2-list-power.md)).

## 1. Goal & scope

`viewType='kanban'` renderer on `ObjectListPage`: lanes from the picklist values of a
chosen lane field, cards rendered via `FieldRenderer`, per-lane counts, drag a card to
another lane → `PATCH {laneField: lane}` with `If-Match` (fresh per-record ETag fetched
at drop time; failure ⇒ snap back + error toast + refetch). Drag is `canEdit`-gated;
filters/search/pagination ride the same query as the table. This slice also introduces
the **view-type switcher** (table/kanban/calendar/gallery) and per-type config pickers
in the toolbar, shared by slices 6+7. `usePicklistOptions` is **promoted** from
`PageBuilderPage/widgets/builtins/inputs/` to `src/hooks/` (page-builder imports
updated; behavior unchanged). **Not delivered:** lane reordering, per-lane WIP limits,
cross-page lanes (page-scoped like grouping), swimlanes.

## 2. UI samples

Toolbar: `[Views ▾] [Columns] [Normal] [Group by ▾] [⊞ Table ▾]` — the last switches
renderer; kanban adds a `[Lane: Status ▾]` picker (picklist fields only). Board: one
column per picklist value (+ an `—` unassigned lane when needed), header `Value (n)`,
cards show the display field + up to 3 visible columns.

## 3. Data & API contracts

- Same records query as the table (sort/filters/page unchanged). No backend change.
- Lanes: `usePicklistOptions(laneField)` (now `@/hooks/usePicklistOptions`) + any
  distinct record values not in the picklist (data wins) + `—` for null.
- Drop: `GET /api/{collection}/{id}` (`getWithMeta` → ETag) then
  `useRecordMutation.patch` with `ifMatch`; any rejection (409/validation) toasts,
  clears the optimistic move, and refetches. Optimistic lane override lives in the
  board (`pendingMoves`), cleared on settle.
- Lane field: `typeConfig.kanban.laneField`, default = first accessible picklist field;
  none ⇒ inline empty-state, no crash. Card fields: `typeConfig.kanban.cardFields ??`
  first 3 visible non-title columns.
- `@dnd-kit/core` is already a dependency; the board is its own component tree
  (concerns.md rule is *don't mix DnD libraries in one tree* — page-builder canvas is a
  separate tree).

## 4. DB migrations

None.

## 5. File-by-file code changes

`components/KanbanBoard/` (new, +tests) · `hooks/usePicklistOptions.ts` (+test, moved;
5 import sites updated) · `ObjectListPage.tsx` (view-type switcher, lane picker,
renderer branch, `handleMoveCard`) · `en.json` (`altViews.*`).

## 6. Test plan

Vitest: lane resolution (picklist order, data-only values appended, unassigned lane),
per-lane counts, card click-through, drag affordance gated on `canEdit`, empty-state
without a picklist field. Playwright post-deploy: drag a card between lanes, assert the
PATCH persists. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md · playbooks/conventions untouched · memory.

## 8. Risks & open questions

- jsdom can't simulate real dnd-kit pointer drags — the drop handler is covered through
  unit-tested pure helpers + the e2e; accepted.
- ETag-at-drop shrinks but doesn't eliminate the lost-update window (list rows carry no
  ETags); acceptable — the server 409s on the true conflict.
