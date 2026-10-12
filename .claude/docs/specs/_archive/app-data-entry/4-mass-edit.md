# Slice 4 — Main-List Mass Edit

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR as slice 3). Frontend-only; not security-typed (the `MANAGE_DATA` gate it
> rides is the existing in-controller bulk-jobs gate — no authz change).

## 1. Goal & scope

"Edit field" on the `ObjectListPage` selection bar: pick one editable field, enter a
typed value, apply it to every selected row via the existing bulk-jobs backend. A lift of
the RelatedList flow, not a build — `MassEditDialog` is reused verbatim and the
submit/poll logic is **extracted to a shared `runBulkUpdate` utility** consumed by both
RelatedList and the list page (single source; behavior identical). Gated on
`permissions.canEdit` **and** the `MANAGE_DATA` system permission (the bulk path bypasses
per-record write advice, so the permission stays the boundary). **Not delivered:**
bulk-edit of multiple fields in one pass, cross-page "select all N matching" selection.

## 2. UI samples

Selection bar: `3 records selected  [✎ Edit field] [🗑 Delete] … [Clear selection]`.
Dialog is the existing MassEditDialog (field picker + typed FieldControl editor). Result
toast: `Updated 3 of 3 records` with a **View jobs** action linking to the Bulk Jobs
page; partial failures use the error variant with success/error counts.

## 3. Data & API contracts

- `POST /api/bulk-jobs` `{collectionId, operation: 'UPDATE', records: [{id, <field>: v}]}`
  then `GET /api/bulk-jobs/{id}` every 2s, ≤30 polls, until status ∈
  `COMPLETED|FAILED|ABORTED` — exactly the RelatedList contract, now in
  `utils/bulkUpdate.ts` (`runBulkUpdate(api, collectionId, records)` → `{jobId, status,
  successRecords, errorRecords}`; timeout ⇒ `status: undefined`). No toasts in the util —
  callers own messaging (RelatedList keeps `useToast`, the page uses sonner + i18n).
- On COMPLETED: clear selection, `refetch()` the list (grouping/sums recompute free).
- On non-COMPLETED or partial errors: error toast pointing at the Bulk Jobs page
  (`/{tenant}/bulk-jobs`, itself `MANAGE_DATA`-gated — same audience); dialog stays open
  on thrown errors (MassEditDialog contract).
- `ListViewToolbar` new prop `onMassEdit?: () => void`; the caller passes it only when
  the gate holds, so the toolbar stays permission-ignorant.
- Mass-editable fields = schema fields minus `id`/system audit fields, filtered by
  `getFieldControl(type).editable` (same rule as RelatedList).

## 4. DB migrations

None.

## 5. File-by-file code changes

`utils/bulkUpdate.ts` (new, +`bulkUpdate.test.ts`) · `RelatedList.tsx` (submit/poll block
→ `runBulkUpdate`, toasts unchanged) · `ListViewToolbar.tsx` (+test: `onMassEdit`
button) · `ObjectListPage.tsx` (gate, dialog state, submit handler, toolbar wiring) ·
`en.json` (`massEdit.*`).

## 6. Test plan

Vitest: `runBulkUpdate` — happy path, poll loop (fake timers), FAILED status, timeout,
missing job id; toolbar renders the button only with `onMassEdit` + selection; existing
RelatedList mass-edit suite green against the extracted util. Playwright: post-deploy —
select two rows, mass-edit a text field, assert both rows updated. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md row · memory.

## 8. Risks & open questions

- The 60s poll ceiling mirrors RelatedList; very large pages (200 rows) still finish well
  inside it, but the timeout toast routes users to the Bulk Jobs page rather than lying.
- Selection is page-scoped today; "select all matching" is future work and would need a
  server-side filter→job contract, not more polling.
