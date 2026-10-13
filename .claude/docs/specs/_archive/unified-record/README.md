# Unified Record Experience — Parent Spec

> **Status:** parent planning spec. Authoritative shared contract for collapsing the two drifted
> record stacks (admin `/resources`, end-user `/app/o`) into ONE reusable Record Experience,
> rendered identically for admin and end user. Each slice in the [Slice plan](#slice-plan) is
> expanded into its own child spec under [`./`](.) and **extends, never contradicts** the
> [Reuse Map](#reuse-map), [unified core](#the-unified-core), and [event/validation
> contract](#event--validation-model) below.
>
> Source-verified against the codebase on 2026-07-01 (Flyway head **V148**, next **V149**). If
> code and this doc disagree, trust the code and fix this doc.
>
> **Shipped — archived.** Every slice landed, and a follow-up record-stack convergence put the
> create/edit form (`RecordForm`) and the list (`RecordList`) on shared cores as well, with every
> record URL built by `recordRoutes`. The live contract is `conventions.md` → Component reuse and
> `architecture.md` → Record detail path; read this spec as history.

## How to use this document

This parent spec defines the cross-cutting architecture once. The child specs
(`<slice>.md` in this dir) each cover one PR-sized slice with acceptance criteria, UI samples,
exact contracts, DB migrations (or "none"), file-by-file changes, and a test plan — per the
[Child-spec template](#child-spec-template). Read this parent first; every child references it.

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — `FieldControl` registry (keystone) | [1-field-control-registry.md](./1-field-control-registry.md) | **Field controls** |
| 2 — `RecordShell` + `RecordDetailBody` | [2-record-shell.md](./2-record-shell.md) | **Detail view** |
| 3 — `RecordDataGrid` + inline edit | [3-record-data-grid.md](./3-record-data-grid.md) | **List/grid** |
| 4 — `RelatedListPanel` (inline CRUD) | [4-related-list-panel.md](./4-related-list-panel.md) | **Child lists** |
| 5 — Optimistic locking | [5-optimistic-locking.md](./5-optimistic-locking.md) | **Concurrency (BE+FE)** |
| 6 — Client event rules (`SCRIPT`) | [6-client-event-rules.md](./6-client-event-rules.md) | **Logic (FE)** |
| 7 — Server record-event scripts | [7-server-event-scripts.md](./7-server-event-scripts.md) | **Logic (BE)** |
| 8 — Admin convergence + cleanup | [8-admin-convergence.md](./8-admin-convergence.md) | **Dedup** |

## Context

Tenants "run their business" from the record UI: view a record, edit any field, see child lists,
get validation + event logic before save. Today that experience is **split across two drifted
stacks** and is inconsistent + incomplete, so it doesn't feel trustworthy.

- **Admin "Resource Browser"** — routes `/:tenant/resources/:collection/...`
  (`ResourceBrowserPage`, `ResourceListPage`, `ResourceDetailPage`, `ResourceFormPage`) under
  `AdminPageRoute` (`App.tsx` ~613-651).
- **End-user runtime** — routes `/:tenant/app/o/:collection/...`
  (`EndUserObjectListPage`→`ObjectListPage`, `EndUserObjectDetailPage`→`ObjectDetailPage`,
  `EndUserObjectFormPage`→`ObjectFormPage`) under `EndUserShell` (`App.tsx` ~1164-1195).

Both implement the *same concept* twice with different components, so a fix in one doesn't land
in the other. Forms are already partly shared (both form pages use `LayoutFormSections` +
`useLayoutRules`); detail, list, related-list, and header are duplicated and drifted.

**Intended outcome:** ONE unified, reusable Record Experience — "best of both" — where every
field is editable through one control map, event-based scripts + validations run before submit,
concurrent edits are safe, and every relationship/field type is handled. The end-user runtime is
the canonical UX; `/resources` becomes a thin admin variant (system-field toggle, raw JSON,
schema deep-link) over the same core.

**Most primitives already exist** — this is mostly *unification + wiring*, not green-field.

## Key architecture decisions (verified against the code)

- **The keystone is a `FieldControl` registry.** Today display lives in `FieldRenderer`
  (view-only, 21 types) and edit lives in a separate `formFieldRenderers` map. Merge them into
  ONE `FieldType`-keyed registry, each entry `{ View, Edit, InlineEdit, coerce, validate }`.
  "Every field editable" becomes a registry guarantee instead of per-page code. Plugin overrides
  via `componentRegistry` stay.
- **One component core in `@kelta/components`**, rendered by both route trees. `/resources` and
  `/app/o` become thin wrappers passing `variant="admin" | "enduser"`.
- **Backend stays authoritative for validation + scripts.** Client rules/scripts are UX sugar;
  the enforced gate is the existing `BeforeSaveHook` + validation pipeline in `DefaultQueryEngine`.
- **Reuse-first** — see the [Reuse Map](#reuse-map); do not rebuild renderers, the rule engine,
  includes resolution, or the grid.
- **Optimistic locking is additive** — an ETag/`If-Match` seam on the existing JSON:API paths,
  no schema change (row version = `updated_at`).

## Current-state evaluation (source-verified)

### What already works (reuse, don't rebuild) → see [Reuse Map](#reuse-map)

### Gaps this effort closes
1. **Duplication / drift** — two detail pages, two list pages, three layout section renderers
   (`LayoutFormSections`, `LayoutFieldSections`, kelta-web `LayoutRenderer` whose related-list is
   a **placeholder stub**), two `RecordHeader`s.
2. **Inline edit not wired** into `ObjectDataTable` or related lists; and
   `reference`/`lookup`/`master_detail`/`multi_picklist`/`json`/`rich_text`/`geolocation` have
   **no inline editor** — so "every field editable in place" is false today.
3. **Related lists read-only** — navigate or "+ New" (FK pre-fill) only; no inline
   add/edit/delete of child rows, no mass-edit.
4. **No tenant-facing record-event script surface** — `GraalVmScriptExecutor` runs only inside a
   flow action (`InvokeScriptActionHandler`); no declarative `onLoad`/`onChange`/`onBeforeSubmit`
   (client) or before/after-save script binding (server).
5. **No optimistic locking** — record PATCH has no version/ETag; concurrent edits silently
   last-write-wins (lost updates).

## The unified core

New/consolidated components in `kelta-web/packages/components/src/record/` (exported from
`@kelta/components`):

- **`RecordShell`** — one page frame: single `RecordHeader`, action bar, tabbed body
  (Details / Related / Activity), side rail. Driven entirely by `PageLayoutDto` (sections,
  relatedLists, headerConfig, railBlocks, rules). Replaces the `ResourceDetailPage` +
  `ObjectDetailPage` bodies.
- **`FieldControl` registry** (keystone, Slice 1) — `FieldType` → `{ View, Edit, InlineEdit,
  coerce, validate }`. Real Edit + InlineEdit for **every** type, including
  `reference`/`lookup`/`master_detail` (→ `LookupSelect`), `multi_picklist`
  (→ `MultiPicklistSelect`), `rich_text` (→ `RichTextEditor`), `json` (→ JSON editor),
  `geolocation` (lat/lng inputs). Server-computed types (`formula`, `rollup_summary`,
  `auto_number`, `encrypted`) expose `View` + a read-only `Edit`.
- **`RecordDetailBody`** (Slice 2) — one section renderer (folds
  `LayoutFieldSections`/`LayoutFormSections`/`LayoutRenderer`) doing view + in-place inline edit
  per placement, honoring `readOnlyOnLayout`/`requiredOnLayout`/visibility rules. Click a value →
  `FieldControl.InlineEdit` → PATCH, no page nav.
- **`RelatedListPanel`** (Slice 4) — the real `RelatedList` include-resolution, extended with
  inline add / inline edit / delete of child rows + optional mass-edit, each cell using
  `FieldControl.InlineEdit`. Retires the kelta-web stub.
- **`RecordDataGrid`** (Slice 3) — folds `ObjectDataTable` (virtual scroll, keyboard nav,
  selection) + `InlineEditCell`; editable columns via `FieldControl.InlineEdit`; drives list,
  related, and lookup-picker.
- **`RecordFormEngine`** (Slice 2/6) — keep `LayoutFormSections` + `useLayoutRules`; route field
  rendering through `FieldControl.Edit` so form and detail-inline share controls + validation.

## Event & validation model

One contract, two enforcement points (client = UX, server = gate):

- **Client rules** (Slice 6) — extend the existing layout `RuleEngine` (`layoutRules.ts`,
  `useLayoutRules`) kinds (`COMPUTE`/`VALIDATE`/`DEFAULT`/`TRANSFORM`) with a **`SCRIPT`** kind on
  events `onLoad`/`onChange`/`onBlur`/`onBeforeSubmit`, evaluating a sandboxed expression over
  `@kelta/formula`'s AST evaluator (no `eval`/`window`/`globalThis` reach) with scope
  `{ record, previous, field, user, context }`. `onBeforeSubmit` can block or mutate the payload
  before PATCH/POST.
- **Server binding** (Slice 7) — a new declarative system-collection binding (record-event →
  GraalVm script) invoked from a `BeforeSaveHook`: before-create/update validates + transforms +
  can block; after-* runs side effects. Reuses the existing hook order (`FormulaComputeHook`
  order 250 → custom validation → module hooks) + the NATS config-refresh pattern
  (`kelta.config.*.changed`). Confirm the active `ScriptExecutor` bean (`GraalVmScriptExecutor`
  vs the no-op `LoggingScriptExecutor`) and document it — `status.md` currently says no-op but
  the GraalVM impl + `org.graalvm.polyglot` dep are present.
- **Validation surfacing** — keep the JSON:API `/data/attributes/<field>` pointer →
  `parseAxiosError` → `fieldErrors` path; `RecordFormEngine` + `RecordDetailBody` render per-field
  errors + `WARNING`-severity toasts consistently.

## Concurrency safety (Slice 5)

Optimistic locking, additive (no migration): worker emits a strong `ETag` (hash of `updated_at`)
on record GET; `useRecordMutation` echoes `If-Match` on PATCH/PUT/DELETE; worker returns **409
Conflict** on mismatch; UI shows a reload/merge prompt. Covers both inline and form edits.

## Reuse Map

Use these — do not rebuild.

| Need | Reuse | Path |
|------|-------|------|
| Display of 21 field types | `FieldRenderer` (plugin-overridable) | `kelta-ui/app/src/components/FieldRenderer/` |
| Edit controls (typed inputs, lookup, multi, rich text) | `formFieldRenderers` + `LookupSelect`/`MultiPicklistSelect`/`RichTextEditor` | `kelta-ui/app/src/pages/PageBuilderPage/widgets/builtins/`, `kelta-ui/app/src/components/` |
| Inline cell edit (12 types) | `InlineEditCell` | `kelta-ui/app/src/components/InlineEditCell/` |
| Layout-driven form sections | `LayoutFormSections` | `kelta-ui/app/src/components/LayoutFormSections/` |
| Client rule engine (COMPUTE/VALIDATE/DEFAULT/TRANSFORM) | `useLayoutRules` + `dtosToLayoutRules` | `@kelta/components`, `kelta-ui/app/src/utils/layoutRules.ts` |
| Related records + includes resolution | `RelatedList`, `useRelatedRecords`, `listIncludes.ts` | `kelta-ui/app/src/components/RelatedList/`, `kelta-ui/app/src/hooks/`, `kelta-ui/app/src/pages/app/ObjectListPage/` |
| Grid (virtual scroll, keyboard nav, selection) | `ObjectDataTable` | `kelta-ui/app/src/components/ObjectDataTable/` |
| Layout metadata (sections/placements/related/rules/header/rail) | `usePageLayout`, `PageLayoutDto` | `kelta-ui/app/src/hooks/usePageLayout.ts` |
| Record CRUD mutations + JSON:API wrap | `useRecordMutation`, `jsonapi.ts` | `kelta-ui/app/src/hooks/`, `kelta-ui/app/src/utils/` |
| Server validation (constraints + formula rules) | `DefaultValidationEngine`, `CustomValidationRuleEngine`, `ValidationRuleRegistry` | `runtime-core .../validation/` |
| Server lifecycle hooks + write path | `BeforeSaveHook(Registry)`, `DefaultQueryEngine`, `FormulaComputeHook` | `runtime-core .../workflow/`, `.../query/`, `kelta-worker .../listener/` |
| Server JS engine | `GraalVmScriptExecutor`, `InvokeScriptActionHandler` | `runtime-module-integration .../spi/graalvm/`, `.../handlers/` |
| Error → field mapping | `parseAxiosError` (`/data/attributes/<f>` → `fieldErrors`) | `kelta-ui/app/src/services/apiClient.ts` |
| Plugin field/component override | `componentRegistry.getFieldRenderer` | `kelta-ui/app/src/services/componentRegistry.ts` |

## Security

- **Field controls** ride existing FLS — `FieldControl.Edit`/`InlineEdit` submit via the
  authorized JSON:API path; write-side `CerbosFieldWriteSecurityAdvice` + read-side
  `CerbosFieldSecurityAdvice` still enforce. Denied fields never render an editor.
- **Client scripts** use the `@kelta/formula` AST evaluator only (no `eval`/`Function`/`window`);
  arbitrary JS is server-only.
- **Server scripts** run under `GraalVmScriptExecutor` with per-script timeout + per-tenant
  governor quota; they run inside the tenant + user Cerbos context of the write (no privilege
  escalation).
- **Optimistic locking** prevents lost updates but is not an authz control — Cerbos/RLS still
  gate the write.

## Slice plan

Foundation (unified components) first — everything hangs off the `FieldControl` registry.

- **Slice 0 — Parent spec + doc wiring** (this file). Author parent + per-slice child specs;
  update `CLAUDE.md` reference-doc table + `status.md` rows. No code.
- **Slice 1 — `FieldControl` registry** (keystone). Merge display+edit+inline; add missing
  editors; parity golden-snapshot of 21 `View`s. Per-type Vitest.
- **Slice 2 — `RecordShell` + `RecordDetailBody`**. One section renderer (view + in-place inline
  edit); point both detail pages at it (`variant`); delete losing duplicates.
- **Slice 3 — `RecordDataGrid` + inline edit**. Fold `ObjectDataTable` + `InlineEditCell`; drive
  list/related/picker.
- **Slice 4 — `RelatedListPanel`**. Inline add/edit/delete + mass-edit; retire kelta-web stub.
- **Slice 5 — Optimistic locking**. ETag/`If-Match`/409 + UI conflict prompt +
  kelta-test-harness concurrent-write scenario.
- **Slice 6 — Client event rules** (`SCRIPT` kind). Extend `RuleEngine` +
  `PageLayoutsPage/RulesEditor.tsx`.
- **Slice 7 — Server record-event scripts**. System-collection binding → `BeforeSaveHook` →
  `GraalVmScriptExecutor`; NATS-refreshed; worker unit + harness real-DB block scenario.
- **Slice 8 — Admin convergence + cleanup**. `/resources` = thin admin variant; delete dead
  `Resource*`/`Object*` bodies; update `concerns.md`.

**Dependency order (hard edges):** 1 → {2, 3} → 4; 5 independent (any time after 1); 6 → 7; 8
last. Each FE slice is Vitest-tested in its PR; the cross-cutting Playwright e2e is owned by
Slice 8 (route-parity assertion) and authored post-deploy.

## Child-spec template

Each `<slice>.md` must include every section (sections that don't apply state "N/A — <reason>",
never omit silently):

1. **Goal & scope** — what the slice delivers, what it does *not*, parent sections it conforms to.
2. **UI samples** — wireframes of the affected screen, before/after; sample payloads for BE-only.
3. **Data & API contracts** — exact TS interfaces / JSON shapes / endpoint signatures / config
   deltas; versioning + back-compat.
4. **DB migrations** — exact Flyway `V<n>__*.sql` if any; else "None". Verify Flyway head before
   numbering (head **V148**, next **V149**).
5. **File-by-file code changes** — every file created/modified, functions/components, exact paths,
   registration/wiring steps.
6. **Test plan** — Vitest unit (FE), worker unit with mocked JdbcTemplate/QueryEngine (BE),
   kelta-test-harness integration (if DB-constraint-sensitive), post-deploy Playwright e2e.
7. **Docs to update** — specific rows in `status.md` / `conventions.md` / `architecture.md` /
   `integrations.md` (per CLAUDE.md Rule 6).
8. **Risks & open questions** — fragile/oversized files (`concerns.md`), sequencing deps,
   decisions needing the user.

## Docs to update (per CLAUDE.md Rule 6)

- `.claude/docs/status.md` — add a "Unified Record Experience" row; move inline-edit / event-script
  / optimistic-lock capabilities out of the gap columns as each slice ships.
- `.claude/docs/conventions.md` — the `FieldControl` registry contract; the client rule `SCRIPT`
  kind + event names; the ETag/`If-Match` convention.
- `.claude/docs/architecture.md` — record-event server hook order; 409 conflict path.
- `.claude/docs/concerns.md` — large-file notes for the folded components; script-governor abuse
  surface.
- `CLAUDE.md` reference-doc table — add a row pointing at `specs/_archive/unified-record/`; correct the
  Flyway-head line (code head is **V148**, doc says V147).
- `project_outsystems_roadmap.md` (memory) — record the unified-record slices.
