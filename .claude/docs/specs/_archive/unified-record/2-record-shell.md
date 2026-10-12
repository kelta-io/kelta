# Slice 2 — `RecordShell` + `RecordDetailBody`

> Child of [README.md](./README.md). Consumes Slice 1's `FieldControl` registry. Depends on
> Slice 1.

## 1. Goal & scope

**Delivers:** one record-detail experience in `@kelta/components` (`RecordShell` frame +
`RecordDetailBody` section renderer) rendered by BOTH `ResourceDetailPage` (admin) and
`ObjectDetailPage` (end user) via thin wrappers passing `variant`. Folds the three section
renderers (`LayoutFormSections`, `LayoutFieldSections`, kelta-web `LayoutRenderer`) into one that
does view **and** in-place inline edit per placement. Promotes the Slice-1 registry into
`@kelta/components`.

**Does NOT:** touch the grid (Slice 3) or related-list CRUD (Slice 4 — Slice 2 renders related
lists read-only via existing `RelatedList`); no backend change.

**Conforms to:** parent unified-core `RecordShell`/`RecordDetailBody`; layout-driven from
`PageLayoutDto`; FLS preserved (edits go through authorized JSON:API).

## 2. UI samples

```
┌ RecordShell ───────────────────────────────────────────────┐
│ [avatar] Acme Corp                        [Edit][•••][★]     │  ← single RecordHeader
│ Owner: J. Doe · Updated 2h ago · ID: 3f2… ⧉                 │
├─ Details ─┬─ Related ─┬─ Activity ───────────────────────────┤
│ ▸ Section: Overview (2 cols)                                 │
│   Name      [ Acme Corp        ]  ← click → inline edit      │
│   Revenue   $1,240,000                                       │
│   Owner     J. Doe ▼             ← reference inline edit     │
└─────────────────────────────────────────────────────────────┘
```
`variant="admin"` adds: show-system-fields toggle, raw-JSON drawer, schema deep-link.

## 3. Data & API contracts

```ts
// @kelta/components
interface RecordShellProps {
  collectionName: string; recordId: string; layout: PageLayoutDto | null
  schema: CollectionSchema; record: Record<string, unknown>
  variant: 'admin' | 'enduser'
  tenantSlug: string
  onFieldCommit(field: string, value: unknown): Promise<void>  // → PATCH via useRecordMutation
}
```
Inline field commit issues a partial JSON:API PATCH (single attribute), reusing
`useRecordMutation.patch`. No new endpoint.

## 4. DB migrations
None.

## 5. File-by-file code changes

- **Create** `kelta-web/packages/components/src/record/{RecordShell,RecordDetailBody,RecordSectionRenderer,RecordHeader}.tsx`.
- **Promote** `fieldControl/` from `kelta-ui/app` into `@kelta/components` (per Slice 1 §5).
- **Modify** `kelta-ui/app/src/pages/ResourceDetailPage/ResourceDetailPage.tsx` and
  `kelta-ui/app/src/pages/app/ObjectDetailPage/ObjectDetailPage.tsx` → thin wrappers over
  `RecordShell` (fetch schema/record/layout, pass `variant`).
- **Delete (in Slice 8, not here):** `LayoutFieldSections`, the local `RecordHeader`, kelta-web
  `LayoutRenderer` detail path — kept until wrappers soak.

## 6. Test plan
- Vitest: section renderer honors `readOnlyOnLayout`/`requiredOnLayout`/visibility rules; inline
  commit calls `onFieldCommit` with coerced value; `variant="admin"` shows system fields.
- Both wrapper pages render identical body given the same layout (parity test).
- e2e owned by Slice 8.

## 7. Docs to update
- `status.md` (detail unified), `architecture.md` (one record-detail path), `concerns.md`
  (`RecordShell` size watch).

## 8. Risks & open questions
- Cross-package promotion of the registry is the main churn — do it here, once.
- `ObjectDetailPage` reverse-relationship discovery logic must be preserved into `RecordShell`'s
  Related tab (read-only until Slice 4).
