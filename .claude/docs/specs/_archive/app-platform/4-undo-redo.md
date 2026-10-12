# Slice 4 — Builder Undo/Redo

> Child spec of [App Platform (Phase 3)](./README.md), authored with the implementation
> (own PR). Frontend-only; not security-typed. Closes Phase 3.

## 1. Goal & scope

A real editing history in the page builder: undo/redo over the **authored artifact**
`{components, variables, dataSources}` (selection, preview, drawer-open and other UI
state excluded). Cmd/Ctrl+Z undoes, Shift+Cmd/Ctrl+Z redoes (both no-op while focus is
in an input/textarea/contenteditable — native text undo wins); toolbar Undo/Redo
buttons with disabled states. Depth cap 50; rapid changes within 400ms coalesce into
one entry (typing bursts in the inspector collapse; discrete canvas ops stay separate).
History resets on page open; the unsaved-changes flag stays truthful — undoing back to
the last-saved snapshot clears it. **Not delivered:** cross-page history, redo
preservation across save, named checkpoints, history for `access`/`isHomePage` (rare,
drawer-authored — excluded to keep the snapshot the canvas-editing artifact).

## 2. UI samples

Toolbar (left of Preview): `[↶] [↷]` icon buttons, disabled at the history edges.
Keyboard: `⌘Z` / `⇧⌘Z` (Ctrl on Windows/Linux).

## 3. Data & API contracts

- `hooks/useEditorHistory.ts` — a **generic, dependency-free** hook (no import cycle
  with the page): `reset(baseline)`, `record(snapshot)` (deep-clones via
  `structuredClone`; skips when JSON-identical to the top entry; coalesces within
  400ms by replacing the top; truncates the redo tail; caps at 50 dropping the oldest),
  `undo()`/`redo()` → snapshot or null, `canUndo`/`canRedo`.
- Recording is **effect-based** in `PageBuilderPage`: one effect watches
  `[components, pageVariables, pageDataSources]` and defers `record` by a 0ms timer
  (cleanup cancels — same-render bursts collapse; no sync setState in the effect). An
  `applyingHistoryRef` guard keeps undo/redo applications from re-recording. The
  identical-snapshot guard makes the seed-after-reset echo a no-op.
- Applying a snapshot sets the three states, clears selection, and recomputes
  `hasUnsavedChanges` against the last-saved snapshot (`JSON` compare; refs updated on
  page seed and save success).
- `reset(baseline)` runs at the page-seed sites (open + create-then-edit). Save keeps
  the history (only the saved-marker moves) — undo past a save stays possible.

## 4. DB migrations

None.

## 5. File-by-file code changes

`PageBuilderPage/hooks/useEditorHistory.ts` (new, +tests) · `PageBuilderPage.tsx`
(record effect, undo/redo handlers + keyboard, toolbar buttons, seed/save markers) ·
`en.json` (`builder.pages.undo`/`redo`).

## 6. Test plan

Vitest: hook — record/undo/redo round-trip, redo-tail truncation on new record,
capacity cap drops oldest, 400ms coalescing replaces the top (fake time),
identical-snapshot skip, reset clears both directions, clone isolation (mutating a
returned snapshot never corrupts history). Page-level: add component → undo removes it
→ redo restores it → buttons' disabled states track the edges (existing
`PageBuilderPage.test.tsx` harness). Playwright post-deploy: canvas edit → ⌘Z →
assert removal. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED (Phase 3 complete) · status.md · memory.

## 8. Risks & open questions

- `structuredClone` of a 50-deep history on a large page is bounded (~100KB config ×
  50 worst-case); measured trivial. Revisit with structural sharing only if profiling
  demands.
- Coalescing is time-based, not semantic — two distinct edits within 400ms merge into
  one undo step. Accepted trade-off for collapsing keystroke bursts without wiring
  every call site.
