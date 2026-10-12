# Slice 1 — Conditional Visibility

> Child spec of [App Platform (Phase 3)](./README.md), authored with the implementation
> (same PR as slice 2). Frontend-only; not security-typed. Visibility is client-side UX
> — Cerbos/FLS/per-page authz remain the only data gates (a hidden widget's data was
> never fetched with different authority).

## 1. Goal & scope

Every widget gains a universal, bindable `visible` prop. Runtime: `renderTree`'s
`NodeRenderer` resolves it with the node's other props and skips the whole subtree when
it resolves hidden. Editor: the canvas node ghosts (opacity + eye-off badge) for a
literal `false` and shows a "conditional" badge when bound (the editor scope can't
evaluate it); the node stays selectable/deletable. Inspector: a "Visibility" row on
every widget — checkbox with the standard `fx` literal↔expression toggle — with zero
per-descriptor schema edits. **Not delivered:** visibility on menu items or layout
`span` interactions (a hidden grid child simply doesn't render; siblings reflow).

## 2. UI samples

Inspector bottom group `VISIBILITY`: `[✓] Visible  [fx]` — toggling fx swaps to the
expression editor (`vars.showDetails`, `IF(vars.count > 0, true, false)`). Canvas node
with `visible:false`: 50% opacity + `👁̸ hidden` chip; with a binding: normal opacity +
`👁̸ conditional` chip.

## 3. Data & API contracts

- `visible` lives in `node.props` like any prop (`boolean | Binding`); **absent ⇒
  visible** — zero behavior change for every existing page. No schema/contract change
  (props are open maps end-to-end; the render contract passes `config` verbatim).
- Hidden semantics (`model/visibility.ts` `isHiddenValue`): hidden iff the resolved
  value is `false | 'false' | 0 | '' | null`. A bound-but-unresolvable expression
  resolves `null` (2d contract) ⇒ hidden — fail-closed for "show when X" conditions.
- Runtime check only when the raw prop is present (`'visible' in node.props`), so
  legacy nodes never pay the check semantics.
- Editor never hides — `renderNode(mode:'editor')` renders normally; the ghost/badge is
  `SelectableNode` chrome driven by the RAW prop (`false` literal vs `isBinding`).

## 4. DB migrations

None.

## 5. File-by-file code changes

`model/visibility.ts` (new, +test) · `widgets/renderTree.tsx` (runtime skip) ·
`canvas/SelectableNode.tsx` (ghost + badge) · `inspector/Inspector.tsx` (universal
Visibility row via `BindableField` + `BooleanField`, display default checked) ·
`en.json` (`builder.inspector.visible*`, `builder.inspector.group.visibility`).

## 6. Test plan

Vitest: `isHiddenValue` semantics; renderTree runtime hides `visible:false` and a
false-resolving binding, renders absent/`true`/truthy-binding, editor mode never hides;
Inspector renders the row for a schema-less widget and writes `props.visible`;
SelectableNode ghost/badge for literal-false and binding. Playwright post-deploy: bind
a button's visibility to a variable, toggle it via a `setVar` action, assert
disappearance. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md page-builder row · memory.

## 8. Risks & open questions

- Hidden-by-null (unresolvable binding) may surprise authors who typo a var name — the
  dev-mode resolver warn (2d) is the debugging affordance; acceptable.
- `repeater` children re-resolve per row; `visible` participates per row for free
  (worth an explicit test if repeater-level hiding gets demanded — out of scope now).
