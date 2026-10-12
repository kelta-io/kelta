# Slice 8 — Admin convergence + cleanup + cross-cutting e2e

> Child of [README.md](./README.md). Depends on Slices 2–4 (and consumes 5–7 where shipped). Last.

## 1. Goal & scope
**Delivers:** `/resources` (admin) becomes a thin `variant="admin"` wrapper over the same unified
core; deletes the now-dead duplicate components; owns the single cross-cutting Playwright e2e that
proves both route trees render one behavior. **Does NOT:** add new capability — pure convergence +
cleanup + the parity test. Conforms to parent "end-user runtime is canonical; `/resources` is a
thin admin variant".

## 2. UI samples
`/resources/:collection/:id` and `/app/o/:collection/:id` render the SAME `RecordShell`; admin adds
show-system-fields, raw-JSON drawer, schema deep-link. No end-user-visible change.

## 3. Data & API contracts
None new — both trees call the same components + endpoints.

## 4. DB migrations
None.

## 5. File-by-file code changes
- **Delete** the losing duplicates once wrappers soak: `LayoutFieldSections`, local `RecordHeader`,
  kelta-web `LayoutRenderer` record path, standalone `ObjectDataTable`, `kelta-ui` `RelatedList`,
  old `FieldRenderer`/`formFieldRenderers` (superseded by the registry) — verify no other importers
  first (`grep`).
- **Simplify** `ResourceDetailPage`/`ResourceListPage`/`ObjectDetailPage`/`ObjectListPage` to thin
  wrappers.
- **Update** `concerns.md` large-file table (removed/added components).

## 6. Test plan
- Vitest: dead-code deletion leaves no dangling imports; both wrappers still render.
- **Playwright e2e (post-deploy, owned here):** open a record → inline-edit each field-type family
  → add/edit a child row inline → trigger an `onChange` compute + an `onBeforeSubmit` block → hit a
  validation rule (per-field error) → concurrent-edit 409 prompt → save. **Assert persisted DB
  changes.** Run the SAME spec against `/resources` and `/app/o` to prove one behavior.

## 7. Docs to update
- `status.md` (record stacks unified; duplicates removed), `architecture.md` (single record path),
  `concerns.md`, `project_outsystems_roadmap.md` memory (slices done).

## 8. Risks & open questions
- Deletion ordering — only remove a component after every importer migrated; the e2e is the safety
  net. Keep deletions in this final slice so earlier slices stay revertible.
- Admin-only affordances must not leak to end-user variant (gate on `variant`).
