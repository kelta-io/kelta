# Slice 3 — `RecordDataGrid` + inline edit

> Child of [README.md](./README.md). Consumes Slice 1. Parallel to Slice 2 (both depend only on 1).

## 1. Goal & scope
**Delivers:** one grid in `@kelta/components` folding `ObjectDataTable` (virtual scroll, keyboard
nav, selection) with `InlineEditCell` wired in for editable columns via `FieldControl.InlineEdit`.
Drives the list page, the related-list body (Slice 4), and the lookup picker. **Does NOT:** add
related-list CRUD affordances (Slice 4) or optimistic locking (Slice 5 — grid consumes it once it
lands). Conforms to parent unified-core `RecordDataGrid`.

## 2. UI samples
```
│ ☐ Name        Stage        Amount     Owner    │
│ ☐ Acme        [Won ▼]      $1.2M      J. Doe   │  ← Stage cell clicked → picklist inline edit
│ ☐ Globex      Prospect     $0.4M ✎    A. Lin   │  ← ✎ = editable column
```
Non-editable types show the same "edit in detail" affordance as today; boolean toggles in place.

## 3. Data & API contracts
```ts
interface RecordDataGridProps {
  collectionName: string; schema: CollectionSchema; rows: Record<string,unknown>[]
  columns: string[]; editableColumns?: string[]; tenantSlug: string
  onCellCommit(rowId: string, field: string, value: unknown): Promise<void>  // → PATCH
  onRowOpen?(rowId: string): void; selection?: SelectionState
}
```
Cell commit reuses `useRecordMutation.patch` (single attribute). No new endpoint.

## 4. DB migrations
None.

## 5. File-by-file code changes
- **Create** `kelta-web/packages/components/src/record/RecordDataGrid.tsx` (folds `ObjectDataTable`
  + `InlineEditCell`).
- **Modify** `kelta-ui/app/src/pages/app/ObjectListPage/ObjectListPage.tsx` and
  `kelta-ui/app/src/pages/ResourceListPage/ResourceListPage.tsx` → render `RecordDataGrid`.
- **Delete (Slice 8):** standalone `ObjectDataTable` once both lists migrate.

## 6. Test plan
- Vitest: editable cell commits coerced value on Enter/blur, cancels on Esc; non-editable shows
  hint; boolean toggles immediately; virtual scroll + keyboard nav preserved; selection intact.
- e2e owned by Slice 8.

## 7. Docs to update
- `status.md` (inline-edit in grids now real), `concerns.md` (grid perf notes).

## 8. Risks & open questions
- `InlineEditCell` currently supports 12 types; Slice 1 fills the rest — ensure the grid uses the
  registry, not the old cell's hardcoded type list.
- Without Slice 5, concurrent cell edits are last-write-wins — acceptable interim; note it.
