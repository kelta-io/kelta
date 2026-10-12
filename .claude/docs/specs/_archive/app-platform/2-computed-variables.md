# Slice 2 — Computed Variables

> Child spec of [App Platform (Phase 3)](./README.md), authored with the implementation
> (same PR as slice 1). Frontend-only; not security-typed. Same trust model as 2d
> bindings: computed values are client-side derivations; every data read stays on the
> authorized JSON:API path.

## 1. Goal & scope

`PageVariable` gains `kind?: 'static' | 'computed'` (absent = static) and
`expression?: string`. Computed variables evaluate over the live static
variables (bare-identifier expressions — see Contracts), re-evaluate automatically when inputs change, and
feed `scope.vars` transparently — bindings, visibility (slice 1), and actions read them
like any variable. `setVar` targeting a computed variable is rejected (no write, error
toast). The page-settings Variables section authors the kind + expression. **Not
delivered:** async/awaited computations, computed variables inside data-source
bindings (data sources keep seeing static vars only — see Risks), editor-preview
evaluation (preview renders defaults as today).

## 2. UI samples

Variables row grows a kind select: `Static | Computed`. Computed rows swap the Default
input for an Expression input (`a + b`, `IF(count > 0, "some", "none")` — bare
identifiers, see Contracts); type select stays as declared intent.

## 3. Data & API contracts

- Persistence: `config.variables[]` entries carry `kind`/`expression`; the render
  contract already passes `variables` verbatim (no backend change).
- Evaluation (`model/computedVars.ts` `evaluateComputedVariables(variables, scope)`):
  - **Expressions use BARE identifiers** (`a + 1`) — verified: the `@kelta/formula`
    parser is flat-key only and a dotted `vars.a` throws at the `.` (⇒ `null`). The 2d
    flat-scope bridge spreads `vars` leaves, so bare names read static vars and
    computed siblings. **Data-source results are not readable in computed expressions
    in v1** (nested access is inexpressible in the engine; path-mode prop bindings
    remain the way to read `data.*`).
  - Dependency graph from `extractExpressionRefs` (the 2d evaluator's
    `extractFieldRefs` behind a throw-safe export): a computed var depends on the bare
    `<name>` of a computed sibling.
  - Kahn topological order; members of a cycle evaluate to `null` + one dev warn.
  - Each expression evaluates via the existing `resolveBinding({$bind, mode:'expr'})`
    against a progressive scope (`vars` = static ∪ already-computed) — same flat-scope
    bridge, same never-throws/`null`-on-error semantics as 2d.
- Runtime integration (`CustomPage`): `computed = useMemo(evaluate…, [variables,
  vars, data, page])`; `scope.vars = {...vars, ...computed}` (computed shadows a
  same-named static var — the editor should prevent duplicates; last-wins matches
  `usePageVariables` seeding). Re-evaluation is synchronous per render — bounded by the
  2d caps (≤12 sources / ≤200 rows).
- `usePageVariables`: computed variables are excluded from state seeding; `setVar` on a
  computed name is a no-op + `toast.error` (single guard site — covers the 2e `setVar`
  action and any future caller).

## 4. DB migrations

None.

## 5. File-by-file code changes

`pageConfig.ts` (`kind`/`expression`) · `model/computedVars.ts` (new, +test) ·
`model/resolveBindings.ts` (`extractExpressionRefs` export) · `hooks/usePageVariables.ts`
(computed exclusion + setVar guard, +tests) · `pages/app/CustomPage/CustomPage.tsx`
(merge computed into scope) · `inspector/pageSettings/VariablesSection.tsx` (kind
select + expression input, +test cases) · `en.json` (`builder.variables.kind*`,
`expression*`).

## 6. Test plan

Vitest: chain evaluation (`b = a+1`, `c = b*2`), data-source dependency, cycle → both
`null`, malformed expression → `null`, static shadowing order, `usePageVariables`
rejects computed `setVar` (state unchanged), VariablesSection authors kind/expression.
Playwright post-deploy: computed var bound to a heading updates when a `setVar` action
changes its input. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md page-builder row · memory.

## 8. Risks & open questions

- Data sources evaluate their filter/recordId bindings against static vars only —
  letting them read computed vars whose inputs are data would be a fetch↔derive cycle.
  Revisit only with a real use case.
- No memoization per variable (whole map recomputes per input change) — trivial at page
  scale; add per-var memo only if profiling ever demands it.
