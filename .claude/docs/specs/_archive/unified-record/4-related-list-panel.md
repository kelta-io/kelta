# Slice 4 — `RelatedListPanel` (inline CRUD + mass-edit)

> Child of [README.md](./README.md). Depends on Slices 1–3 (uses `RecordDataGrid` + `FieldControl`).

## 1. Goal & scope
**Delivers:** one related-list panel built on the real `RelatedList` include-resolution, extended
with **inline add / inline edit / delete** of child rows and optional **mass-edit**, each cell
using `FieldControl.InlineEdit`. Retires the kelta-web `LayoutRenderer` related-list placeholder
stub. **Does NOT:** change relationship semantics or the includes contract. Conforms to parent
unified-core `RelatedListPanel`.

## 2. UI samples
```
▸ Line Items (3)                         [+ New]  [Mass edit ▾]
  Product      Qty   Unit $    Total
  Widget A     [2]   $10       $20      ✎  🗑   ← inline edit qty; delete row
  Widget B      1    $40       $40      ✎  🗑
  [ + add row: Product ▼  Qty[ ] … ]              ← inline create, parent FK pre-filled
```

## 3. Data & API contracts
- Child create: JSON:API `POST /api/{childCollection}` with parent FK attribute pre-set
  (from the relationship's `foreignKeyField`). Edit: `PATCH`. Delete: `DELETE`.
- Mass-edit: N sequential PATCHes (bounded, progress-reported) — reuse `useRecordMutation`; no new
  bulk endpoint in this slice (bulk backend is a separate 🔴 gap).
```ts
interface RelatedListPanelProps {
  parentCollection: string; parentId: string
  relatedList: LayoutRelatedListDto; schema: CollectionSchema; tenantSlug: string
  canCreate: boolean; canEdit: boolean; canDelete: boolean
}
```

## 4. DB migrations
None.

## 5. File-by-file code changes
- **Create** `kelta-web/packages/components/src/record/RelatedListPanel.tsx` (wraps
  `RecordDataGrid` + inline-create row + delete + mass-edit toolbar).
- **Modify** `RecordShell` Related tab → render `RelatedListPanel` per layout related-list.
- **Delete (Slice 8):** kelta-web `LayoutRenderer` `RelatedListRenderer` placeholder;
  `kelta-ui` `RelatedList` once `RecordShell` fully drives related lists.

## 6. Test plan
- Vitest: inline create posts with parent FK; edit PATCHes; delete removes; mass-edit issues N
  PATCHes with progress; permission gates hide create/edit/delete.
- **kelta-test-harness** real-DB scenario: create child under parent → FK persists → RLS-scoped
  (per the DB-constraint-test-gap memory — a mock can't catch a NOT-NULL FK drop).
- e2e owned by Slice 8.

## 7. Docs to update
- `status.md` (related-list CRUD real; stub retired), `concerns.md` (mass-edit = N calls until
  bulk backend exists).

## 8. Risks & open questions
- Mass-edit as N PATCHes consumes governor quota — document; real bulk is out of scope.
- master_detail cascade delete semantics already enforced server-side (FK CASCADE) — deleting a
  child is a normal delete; deleting the parent cascades. Confirm UI messaging.
