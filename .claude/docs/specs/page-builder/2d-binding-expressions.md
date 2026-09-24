# Slice 2d — Data binding & expressions

> Child spec of [page-builder-parity.md](../page-builder-parity.md). **Extends, never contradicts**
> the parent's [The shared model](../page-builder-parity.md#the-shared-model) (bindings/scope),
> [Page-level config (v2)](../page-builder-parity.md#page-level-config-v2), and
> [Widget registry](../page-builder-parity.md#widget-registry). Consumes the types defined once in
> [2a — Widget registry](./2a-widget-registry.md) (`WidgetDescriptor`, `WidgetRenderProps`,
> `PageComponent`, `Binding`, `PropValue`, `RenderTree`/`renderNode`) and the render-contract siblings
> surfaced by [1g — Render contract v2](./1g-render-contract-v2.md) (`variables` / `dataSources`).
>
> Source-verified against the codebase on 2026-06-22 (Flyway head **V146**, next **V147**; this slice
> adds **no** migration). If code and this doc disagree, **trust the code and fix this doc.**

**Axis:** Data binding. **Depends on:** 2a (model + registry + `RenderTree`), and benefits from 1g
(contract siblings) but tolerates its absence (`scope.data` / `vars` simply stay empty). **Blocks:** none
hard, but 2e (events) reuses the same scope + `interpolate` for action params.

---

## 1. Goal & scope

### What this slice delivers

The **binding/expression layer** that turns the static-props builder into one where *any* prop can be
bound to a record field, a page variable, or an on-load data-source result, and evaluated **client-side**
through the existing `@kelta/formula` engine and the `FieldExpressionPicker`.

Concretely:

1. **`model/bindingScope.ts`** — the `BindingScope` type `{ record, vars, page, item, data }` and a
   `getPath(scope, "record.name")` dot-walker (because the formula parser is **flat-key only** — see
   §3.1, verified).
2. **`model/resolveBindings.ts`** — `resolveBindings(props, scope)` that recursively replaces every
   `Binding` (`{ $bind, mode }`) in a props object:
   - `mode:'path'` → `getPath(scope, expr)` walker (dotted access like `record.name`,
     `data.accounts[0].name`).
   - `mode:'expr'` → **flatten** the leaves the expression references (`evaluator.extractFieldRefs(expr)`
     → `getPath` each) into a flat `Record<string, unknown>`, then `evaluator.evaluate(expr, flat)`.
     This is the **flat-scope bridge** to `@kelta/formula`.
3. **`model/interpolate.ts`** — `interpolate("Hi {{record.name}}", scope)` for `{{…}}`-templated strings
   (the same `{{…}}` merge-tag convention `FieldExpressionPicker` already emits).
4. **`BindableField` finalization** (the `fx` literal↔expr toggle stubbed in 2b) — stores
   `{ $bind, mode }` and opens `FieldExpressionPicker` with `record` / `vars` / `page` / `data` namespace
   roots. The render path resolves it via `RenderTree`'s `resolveBindings(props, scope)`.
5. **Page `variables`** — a `usePageVariables(contract.variables)` state hook (read/seed defaults/`setVar`)
   feeding `scope.vars`.
6. **Page `dataSources`** — a `usePageDataSources(contract.dataSources, scope)` hook that fans out
   `apiClient.getList` / `apiClient.getOne` per source on page load (the **same authorized JSON:API path**
   `DataTableNode` uses today), populating `scope.data.<name>`.
7. **New data widgets:**
   - **`field-value`** — renders a single bound value through `FieldRenderer` (read-only, 21 field types,
     FLS-stripped server-side).
   - **`list` / `repeater`** — binds an **array** source and renders its children once per row under a
     per-row `item` scope (capped at `MAX_REPEATER_ROWS`, §3.9).
8. **Page-settings drawer (NEW host surface — 2d owns it).** The current builder has only a flat
   create/edit page form, so 2d **builds the page-settings drawer shell** plus the **Variables** section
   and the **Data sources** section inside it (§2.3, §5.7). 1h later adds its Access field into this same
   drawer. The Data-sources section enforces `MAX_PAGE_DATA_SOURCES` (§3.9).

The runtime `table` / `form` widgets (migrated in 2a) **keep** their own JSON:API fetch — `dataSources`
**generalize** that fetch so *arbitrary* props can be bound, they do not replace it.

### What this slice explicitly does NOT do

| Out of scope | Lands in |
|--------------|----------|
| `EventListField` UI + the action runtime (`runFlow`/`navigate`/`setVar`/…) | 2e |
| dnd-kit canvas, `treeOps.moveNode`/`setSpan`, responsive `span` resize, `migrate.ts` | 2c |
| Typed/validated form inputs (`dropdown`/`datepicker`/`lookup`/…) replacing text-only `FormNode` | 2f |
| `chart` / `tabs` / `nav` / `icon` widgets | 2g |
| Per-page Cerbos authz gate | 1h |
| **Any server-side binding/dataSource resolution** | **never — see §3.7 (CRITICAL)** |

> **Inspector dependency.** `BindableField` and the schema-driven `Inspector` (looping over
> `descriptor.propSchema`, wrapping `bindable` fields) are introduced in **2b** as a literal-only
> toggle stub. 2d **finalizes** `BindableField` to (a) store the `{ $bind, mode }` shape and (b) open
> `FieldExpressionPicker`. If 2b's `Inspector` has not landed, 2d ships `ExpressionField` + `BindableField`
> standalone and the existing hardcoded `PropertyPanel` keeps editing non-bindable props.

### Conforms to (parent-doc sections)

- §"The shared model" — `Binding = { $bind; mode?: 'path' | 'expr' }`, the scope `{ record, vars, page,
  item, data }`, `resolveBindings` / `interpolate`, the flat-scope formula bridge.
- §"Page-level config (v2) → Canonical storage" — the component tree lives at **`config.components`**;
  `variables`/`dataSources` are **siblings** in `config` (**no `config.tree` wrapper**); the binding
  namespace authority (`record` / `vars` / `data.<name>` / `page` / `item`); `$bind` is the **single**
  expression marker and **the server never parses it**. `PageVariable` / `PageDataSource` shapes.
- §"Widget registry → resolved-node invariant" — `node.props` handed to `Render` is **already fully
  resolved**; descriptors do **not** re-resolve (only `list`/`repeater` re-resolves the per-row `item`
  scope for its children via `renderChild(child, scope?)` — the 2a-defined signature). `field-value` and
  `list`/`repeater` descriptors.
- §"Security" — `getPath` skips `__proto__`/`constructor`/`prototype`; client-side-only resolution keeps
  Cerbos/FLS the only data gate.
- §"DoS / fan-out caps (2d)" — `MAX_PAGE_DATA_SOURCES` (12) + repeater render cap (200) + per-page-view
  governor-quota note.
- §"Slice plan → 2d" — 2d owns creating the page-settings drawer + Variables + Data-sources sections,
  extends 2c's save call with `variables`/`dataSources`, and re-runs 2a's parity suite unchanged.

### Acceptance criteria

- A `heading` whose `text` prop is `{ $bind: "record.name", mode: "path" }` renders the record's `name`
  at runtime; the inspector shows the `fx` toggle in expr state and re-opening the picker round-trips the
  path.
- `{ $bind: "IF(vars.count > 0, 'Has rows', 'Empty')", mode: "expr" }` evaluates through `@kelta/formula`
  via the flat-scope bridge and renders the right branch.
- An interpolated string prop `"Showing {{data.accounts[0].name}}"` renders the resolved value inline.
- A page with a `dataSources` entry `{ name:'accounts', collection:'accounts', mode:'list', limit:25 }`
  fetches via `apiClient.getList('/api/accounts?page[size]=25')` on load and a bound `table`/`list`
  reads `scope.data.accounts`.
- A `repeater` bound to `data.accounts` renders its child subtree once per row, each row resolving
  `item.<field>`.
- A `field-value` bound to `record.email` renders through `FieldRenderer type="email"`.
- Missing path (`record.nope`) resolves to `null` (not a throw); a malformed expr resolves to `null`
  and logs once (does not crash the tree).
- **No server call resolves a binding** — every record read is a JSON:API request from the client, so
  Cerbos/FLS stay enforced server-side (proven by the §6 tests asserting fetch URLs, not server resolution).
- **Caps enforced (§3.9):** a page cannot declare more than `MAX_PAGE_DATA_SOURCES` (12) sources in the
  builder; a `repeater`/`list` renders at most `MAX_REPEATER_ROWS` (200) rows with a "showing N of M" note.
- **Parity guard:** re-running **2a's builtin parity / golden-snapshot suite UNCHANGED** after 2d lands
  produces byte-identical snapshots for all 8 built-ins — proving the now-real `resolveBindings` is an
  **identity on literal (non-bound) props** (see §6.10c).
- `/verify` green (lint + typecheck + `test:coverage` ≥ the kelta-ui gate).

---

## 2. UI samples

### 2.1 The `fx` toggle on a heading's `text` prop (inspector)

`BindableField` wraps any `propSchema` entry with `bindable: true`. The little `fx` button toggles between
**literal** (a plain input) and **expression** (a read-only chip + "Edit" that opens the
`FieldExpressionPicker`). Verified behavior of the picker: `onInsert(token)` gives the **dot-path** (e.g.
`account_id.name`) or a **function stub** (e.g. `IF(${cond}, ${then}, ${else})`); the caller wraps with
`{{…}}` for templates or stores the raw token as `$bind`.

```
Literal mode (default):                  Expression mode (fx active):
┌─ Text ───────────────── [fx] ┐         ┌─ Text ───────────────── [fx●]┐
│ ┌──────────────────────────┐ │         │ ┌──────────────────────┐ ✎    │
│ │ Orders                   │ │   ──►   │ │ {{record.name}}      │ Edit │
│ └──────────────────────────┘ │         │ └──────────────────────┘      │
└──────────────────────────────┘         │ mode: path                    │
                                          └───────────────────────────────┘
   stores: props.text = "Orders"             stores:
                                             props.text = { $bind: "record.name", mode: "path" }
```

Clicking **Edit** opens the picker:

```
┌─ Insert field or expression ───────────────────────────────────────────┐
│ Selected:  {{record.name}}                          [Cancel] [Insert]   │
├──────────┬──────────────────┬──────────────────┬───────────────────────┤
│ Fields   │ Available        │ Account          │  (cascades on          │
│ Funcs    │ variables        │ • name   string  │   relationship rows)   │
│          │ • record ▸       │ • email  string  │                        │
│          │ • vars   ▸       │ • owner_id ▸     │                        │
│          │ • page   ▸       │ • created_at ... │                        │
│          │ • data   ▸       │                  │                        │
└──────────┴──────────────────┴──────────────────┴───────────────────────┘
```

The four roots (`record` / `vars` / `page` / `data`) are passed as `staticNamespaces`; `record` *also*
cascades into the current collection's real schema via `rootCollectionId` (so `record.account_id.name`
deep paths work through the existing cascading column picker). Picking a function switches to expr `mode`.

### 2.2 Expression picker bound to `record` / `vars` / `data`

`ExpressionField` constructs the `staticNamespaces` for the picker:

```
record  → cascades into the page's bound collection schema (rootCollectionId)
vars    → StaticNamespace { name:'vars',  label:'Page variables', fields: [ {name:'count', type:'number'}, … ] }
page    → StaticNamespace { name:'page',  label:'Route / page',    fields: [ {name:'slug', type:'string'}, {name:'params', type:'json'} ] }
data    → StaticNamespace { name:'data',  label:'Data sources',    fields: [ {name:'accounts', type:'json'}, … ] }   // from contract.dataSources names
item    → only inside a list/repeater subtree (the inspector adds it when editing a descendant of a repeater)
```

`vars` leaf list is derived from `contract.variables` (name + type); `data` leaf list from
`contract.dataSources` (name, type `json`). Both are zero-config for the author — they reflect what the
page already declares.

### 2.3 Page-settings drawer (NEW in 2d) — Variables + Data sources sections

> **Host-surface ownership (MAJOR — 2d creates this).** The current builder has **only a flat
> create/edit page form** (title/slug/layout fields) — there is **no page-settings drawer** and no
> Variables or Data-sources UI, and **no earlier slice creates them** (2a is registry/render, 2b is the
> per-node inspector, 2c is canvas/layout + the save rewrite). So 2d **owns building the page-settings
> drawer shell** and **both** the **Variables** section and the **Data sources** section inside it. 1h
> later adds its single Access field into this same drawer — it does not create the drawer.

The drawer is opened from a **"Page settings"** button in the builder toolbar and holds three stacked
sections (Access added by 1h):

```
┌─ Page settings ───────────────────────────────────────────────── [ × ] ┐
│                                                                          │
│  ▸ Variables                                            [+ Add variable] │
│    ┌──────────────────────────────────────────────────────────────────┐ │
│    │ Name [ count ]   Type ( number ▾ )   Default [ 0 ]      [ Remove ] │ │
│    └──────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ▸ Data sources                                          [+ Add source]  │
│    (the Data-sources editor below)                                       │
│                                                                          │
│  ▸ Access            (added by slice 1h — not in 2d)                     │
└──────────────────────────────────────────────────────────────────────────┘
```

Both sections are **pure config**, persisted into `config.variables` / `config.dataSources` (siblings of
`config.components`; round-trip through 1g untouched). The **Data sources** section:

```
┌─ Data sources ──────────────────────────────────────── [+ Add source] ┐
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ Name        [ accounts        ]   Mode  ( ● List  ○ Single )        │ │
│ │ Collection  [ accounts      ▾ ]   Limit [ 25 ]                       │ │
│ │ Fields      [ name × ] [ email × ] [ + ]                            │ │
│ │ Filter      { status: { $bind: "vars.statusFilter", mode:"path" } } │ │
│ │                                                          [ Remove ]  │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ Single-mode adds:  Record id  [fx] { $bind: "page.params.id", path }    │
└─────────────────────────────────────────────────────────────────────────┘
```

On load the page fetches each source (list → `getList`, single → `getOne`) and exposes the result at
`scope.data.<name>`. Bound props/widgets read it; `refreshData` (2e) re-runs one source.

### 2.4 A `repeater` rendering children per row (runtime)

```
Tree:                                         Runtime output (data.accounts = [{name:'Acme'},{name:'Globex'}]):
repeater (source = {{data.accounts}})          ┌─ card ─────────────┐   ┌─ card ─────────────┐
  └ card                                        │ Acme               │   │ Globex             │
      └ heading (text = {{item.name}})          │ View ▸             │   │ View ▸             │
      └ button  (label = "View")                └────────────────────┘   └────────────────────┘
```

Each iteration renders the **child subtree** with `scope.item = row` (and `scope.record = row` aliased,
so child bindings can use either `item.name` or `record.name` inside a row — `record` is the natural
"current row" inside a repeat, per parent §"Page-level config (v2)").

### 2.5 Sample component-tree JSON (what `config.components` holds)

```json
[
  { "id": "h1", "type": "heading",
    "props": { "text": { "$bind": "data.accounts[0].name", "mode": "path" }, "level": "h1" } },

  { "id": "fv1", "type": "field-value",
    "props": { "source": { "$bind": "record.email", "mode": "path" }, "fieldType": "email" } },

  { "id": "rep1", "type": "repeater",
    "props": { "source": { "$bind": "data.accounts", "mode": "path" } },
    "children": [
      { "id": "rc1", "type": "card", "props": {}, "children": [
        { "id": "rh1", "type": "heading",
          "props": { "text": { "$bind": "item.name", "mode": "path" }, "level": "h3" } },
        { "id": "rt1", "type": "text",
          "props": { "content": "Status: {{item.status}}" } }
      ]}
    ]
  },

  { "id": "txt1", "type": "text",
    "props": { "content": { "$bind": "IF(vars.count > 0, 'Has accounts', 'No accounts')", "mode": "expr" } } }
]
```

Stored `config` JSON (canonical storage — `components`, `variables`, `dataSources` are all
**siblings** inside `config`; there is **no `config.tree` wrapper**, per parent §"Page-level config (v2)
→ Canonical storage"):

```json
{
  "schemaVersion": 2,
  "variables": [ { "name": "count", "type": "number", "default": 0 } ],
  "dataSources": [
    { "name": "accounts", "collection": "accounts", "mode": "list",
      "fields": ["name", "email", "status"], "limit": 25 }
  ],
  "components": [ /* the array above */ ]
}
```

> **Storage vs. render contract.** The component tree is persisted at **`config.components`** — the
> location the existing builder already writes and `CustomPage` already reads. The 1g render contract
> carries the *whole* `config` map verbatim in its `tree` field, so at the contract layer
> `contract.tree.components` resolves to this same array; `variables`/`dataSources` are *also* surfaced as
> separate contract fields read from `config.*` top-level. 2d reads/writes `config.components` /
> `config.variables` / `config.dataSources` — never a `config.tree.*` path.

All `$bind` markers round-trip through the server **verbatim** — the server resolves nothing (1g §3.4).

---

## 3. Data & API contracts

All TS lives under `kelta-ui/app/src/pages/PageBuilderPage/`. **No backend/API change in 2d** (data flows
through the existing JSON:API; the render contract was finalized in 1g).

### 3.1 The `@kelta/formula` public API (verified — quoted) and the flat-key limitation

From `kelta-web/packages/formula/src/index.ts` and `FormulaEvaluator.ts` (real, unmodified):

```ts
// FormulaEvaluator.ts — the methods 2d uses:
evaluate(expression: string, fieldValues: Record<string, unknown>): unknown
extractFieldRefs(expression: string): string[]
validate(expression: string): void               // throws FormulaException on parse error
// constructor(opts?: { cacheMaxSize?: number; functions?: FormulaFunction[] })
```

> **The flat-key limitation (load-bearing, verified in `parser.ts`).** `FormulaParser.parseIdentifierOrFunction`
> (parser.ts:179–213) consumes only `isLetterOrDigit(c) || c === '_'` and **stops at `.`**. So an
> identifier is a single flat segment producing `{ kind: 'fieldRef', fieldName: <segment> }`. Consequences:
> - `record.name` does **not** evaluate natively — the parser reads `record` as a `fieldRef`, then hits `.`
>   and throws `Unexpected character at position N: '.'`.
> - `evaluateAst` for a `fieldRef` does a **flat** lookup `context.fieldValues[node.fieldName]` and returns
>   `null` when the key is `undefined` (ast.ts:33–36) — i.e. **missing leaf → null**, never a throw.
>
> This is precisely why 2d splits resolution into two modes:
> - **`mode:'path'`** — dotted/indexed access (`record.name`, `data.accounts[0].name`) is handled by a
>   hand-written `getPath` **walker** in `bindingScope.ts`, never by the formula parser.
> - **`mode:'expr'`** — we **flatten** the leaves the expression references into a flat map keyed by the
>   *exact identifier the parser sees*, then call `evaluate`. Because the parser can't read `record.name`
>   as one identifier, expr authors reference **flat** identifiers (e.g. `count`, or function calls over
>   them); `ExpressionField` maps a picked dotted path to a flat alias when inserting into an expr (see
>   §3.4). For the common case the picker emits flat leaf names directly into expressions.

### 3.2 `model/bindingScope.ts` — scope + `getPath` walker

```ts
import type { PropValue } from './pageModel'

/**
 * The binding scope (parent §"The shared model"). All five roots are optional; absent roots resolve
 * to undefined → null. `record` is the current record OR the current repeat row (aliased to `item`).
 */
export interface BindingScope {
  /** Current record / current repeat row. */
  record?: Record<string, unknown>
  /** Page variables (usePageVariables). */
  vars?: Record<string, unknown>
  /** Route params + page meta: { slug, path, params }. */
  page?: { slug?: string; path?: string; params?: Record<string, string> }
  /** Per-row scope inside list/repeater (also aliased into `record`). */
  item?: Record<string, unknown>
  /** On-load data-source results keyed by source name (usePageDataSources). */
  data?: Record<string, unknown>
}

export const EMPTY_SCOPE: BindingScope = {}

/** Tokens that could traverse the prototype chain — refused (return null) per parent §"Security". */
const UNSAFE_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

/**
 * Walk a dotted/indexed path against the scope. Supports `a.b`, `a[0]`, `a.b[2].c`.
 * Returns `null` for any missing segment (never throws) — matches the formula engine's
 * missing-leaf semantics so `mode:'path'` and `mode:'expr'` agree on absent values.
 * Prototype-pollution guard (parent §"Security"): any `__proto__`/`constructor`/`prototype` token
 * short-circuits to null so a bound path can never reach the prototype chain.
 */
export function getPath(scope: BindingScope, path: string): unknown {
  if (!path) return null
  // Tokenize "a.b[0].c" → ['a','b','0','c']
  const tokens = path
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')
    .filter((t) => t.length > 0)
  let cur: unknown = scope
  for (const tok of tokens) {
    if (UNSAFE_KEYS.has(tok)) return null   // refuse prototype-chain traversal
    if (cur == null) return null
    if (typeof cur !== 'object') return null
    cur = (cur as Record<string, unknown>)[tok]
  }
  return cur === undefined ? null : cur
}

/** The set of valid root identifiers — used to build picker namespaces and validate `$bind`. */
export const SCOPE_ROOTS = ['record', 'vars', 'page', 'item', 'data'] as const
export type ScopeRoot = (typeof SCOPE_ROOTS)[number]
```

### 3.3 `model/resolveBindings.ts` — recursive prop resolution + the flat-scope bridge

```ts
import { FormulaEvaluator } from '@kelta/formula'
import { isBinding, type Binding, type PropValue } from './pageModel'
import { getPath, type BindingScope } from './bindingScope'

/** One shared evaluator (caches parsed ASTs; cheap to reuse). */
const evaluator = new FormulaEvaluator()

/** Resolve a single binding against the scope. Never throws — falls back to null + a single warn. */
export function resolveBinding(binding: Binding, scope: BindingScope): unknown {
  const expr = binding.$bind
  const mode = binding.mode ?? 'path'
  try {
    if (mode === 'path') {
      return getPath(scope, expr)
    }
    // mode === 'expr': flatten referenced leaves → call FormulaEvaluator.evaluate.
    return evaluator.evaluate(expr, flattenScopeForExpr(expr, scope))
  } catch (err) {
    if (import.meta.env?.DEV) {
      // eslint-disable-next-line no-console
      console.warn(`[resolveBinding] "${expr}" (${mode}) failed:`, err)
    }
    return null
  }
}

/**
 * The flat-scope bridge to @kelta/formula. The parser is flat-key only (it cannot read `record.name`
 * as one identifier — see §3.1), so for `mode:'expr'` we:
 *   1. ask the evaluator which identifiers the expression references (extractFieldRefs),
 *   2. resolve each via getPath against the scope (so even a flat `count` or a pre-aliased leaf works),
 *   3. hand back a flat Record<string, unknown> keyed by those exact identifiers.
 * Identifiers that are not present resolve to null (engine treats undefined as null anyway).
 */
export function flattenScopeForExpr(expr: string, scope: BindingScope): Record<string, unknown> {
  const flat: Record<string, unknown> = {}
  let refs: string[]
  try {
    refs = evaluator.extractFieldRefs(expr)
  } catch {
    refs = []
  }
  for (const ref of refs) {
    flat[ref] = getPath(scope, ref)
  }
  return flat
}

/**
 * Deep-resolve every Binding in a props object. Leaves literals untouched; recurses into arrays and
 * plain objects. Returns a NEW object (does not mutate). Used by renderNode before calling descriptor.Render.
 */
export function resolveBindings(
  props: Record<string, PropValue>,
  scope: BindingScope,
): Record<string, unknown> {
  return resolveValue(props, scope) as Record<string, unknown>
}

function resolveValue(value: PropValue, scope: BindingScope): unknown {
  if (isBinding(value)) return resolveBinding(value, scope)
  if (Array.isArray(value)) return value.map((v) => resolveValue(v, scope))
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value)) out[k] = resolveValue(v as PropValue, scope)
    return out
  }
  return value // string | number | boolean | null
}
```

> **Note on flat identifiers in expr mode.** Because the parser can't tokenize `record.name`, an expr
> like `IF(record.name = "x", …)` would *throw at parse* (caught → null). Two supported expr authoring
> shapes: (a) flat scope leaves the page declares directly — `vars` and `data` source names are flat
> single-segment identifiers (`count`, `accounts`), so `IF(count > 0, …)` works natively; (b) when a
> dotted record path is needed inside an expression, `ExpressionField` inserts a **flat alias** token and
> records the alias→path mapping (see §3.4) so the flatten step resolves it. The common authored case is
> flat. `mode:'path'` is the right tool for raw dotted access — keep expr for IF/logic over leaves.

### 3.4 `model/interpolate.ts` — `{{…}}` templated strings

```ts
import { resolveBinding } from './resolveBindings'
import type { BindingScope } from './bindingScope'
import type { Binding } from './pageModel'

const TEMPLATE_RE = /\{\{\s*([^}]+?)\s*\}\}/g

/**
 * Replace every {{ expr }} occurrence in a template string with its resolved value.
 * A token defaults to mode:'path'; a token prefixed `=` (e.g. {{= IF(count>0,'a','b') }}) is mode:'expr'.
 * Non-string resolved values are stringified (null/undefined → ''). Plain strings pass through unchanged.
 */
export function interpolate(template: string, scope: BindingScope): string {
  if (!template.includes('{{')) return template
  return template.replace(TEMPLATE_RE, (_match, raw: string) => {
    const isExpr = raw.startsWith('=')
    const binding: Binding = { $bind: isExpr ? raw.slice(1).trim() : raw.trim(), mode: isExpr ? 'expr' : 'path' }
    const value = resolveBinding(binding, scope)
    return value == null ? '' : String(value)
  })
}

/** True when a string contains at least one {{…}} token (used by widgets to decide whether to interpolate). */
export function isTemplate(value: unknown): value is string {
  return typeof value === 'string' && value.includes('{{')
}
```

> Widgets that render free text (`heading.text`, `text.content`, `button.label`) call `interpolate(value,
> scope)` on the **resolved** prop so authors can mix literals + tags (`"Showing {{data.accounts[0].name}}"`)
> without flipping to full expr mode. `resolveBindings` handles the structured `$bind` form;
> `interpolate` handles inline tags in literal strings. Both share `resolveBinding`.

### 3.5 `BindableField` / `ExpressionField` contract (inspector)

```ts
// inspector/fields/ExpressionField.tsx
import type { Binding, PropValue } from '../../model/pageModel'
import type { StaticNamespace } from '@/components/FieldExpressionPicker'

export interface ExpressionFieldProps {
  value: PropValue                      // literal OR { $bind, mode }
  onChange: (next: PropValue) => void
  /** Collection the page's `record` is bound to, for the picker's cascading columns. null ⇒ namespaces only. */
  rootCollectionId: string | null
  /** vars/page/data (and item inside a repeat) as static namespaces. */
  namespaces: StaticNamespace[]
  label: string
  testId?: string
}
```

`BindableField` (the 2b wrapper) renders the literal input by default and, when the `fx` toggle is on,
delegates to `ExpressionField`. Storage shape (matches parent §"The shared model"):

- **Field-path token** picked → `{ $bind: "<dot.path>", mode: "path" }`.
- **Function/expression** picked (or the user types one) → `{ $bind: "<expr>", mode: "expr" }`.
- Toggling `fx` **off** converts back to a literal (`""` or the descriptor default).

The picker is the existing `FieldExpressionPicker` (`onInsert(token)` → wrap into `$bind`); `namespaces`
are built by `buildScopeNamespaces(contract)` (a small helper in `hooks/usePageBindingContext.ts`) from
`contract.variables` + `contract.dataSources` + the static `page` set.

### 3.6 `PageVariable` / `PageDataSource` (re-affirm parent shapes; defined in 2a's `pageConfig.ts`)

These are **already defined** in `pageConfig.ts` per 2a §3.5 / parent §"Page-level config (v2)". 2d
**consumes** them (does not redefine). Quoted for reference:

```ts
export interface PageVariable {
  name: string
  type: 'string' | 'number' | 'boolean' | 'json'
  default?: PropValue
}

export interface PageDataSource {
  name: string
  collection: string
  fields?: string[]
  filter?: Record<string, unknown>      // values MAY be Bindings ({ $bind, mode }) — resolved client-side
  sort?: string[]
  limit?: number
  mode: 'list' | 'single'
  recordId?: PropValue                  // single-mode: a Binding or literal id
}
```

### 3.7 On-load fetch contract (CRITICAL — client-side, authorized JSON:API only)

`usePageDataSources` resolves each source's dynamic config against the **current scope** (so `filter` /
`recordId` bindings work), then fetches through `apiClient` — the **same authorized path**
`DataTableNode` uses, so Cerbos/FLS strip denied fields **server-side**.

```ts
// hooks/usePageDataSources.ts (contract sketch)
//
// For each PageDataSource:
//   mode 'list'   → apiClient.getList<Record<string,unknown>>(buildListUrl(src, scope))
//   mode 'single' → apiClient.getOne<Record<string,unknown>>(`/api/${src.collection}/${resolvedId}`)
//
// buildListUrl composes the authorized JSON:API query string:
//   /api/{collection}?page[size]={limit ?? 25}
//     [&fields[{collection}]={fields.join(',')}]
//     [&filter[{k}][EQ]={resolvedValue} ...]      // filter values run through resolveBindings(scope)
//     [&sort={sort.join(',')}]
//
// Result is exposed at scope.data[src.name]:
//   list   → the T[] array
//   single → the single object (or null on 404)
//
// Fetch via @tanstack/react-query (queryKey ['page-data', slug, src.name, resolvedConfigHash]); list
// page size is clamped to the server's MAX_HTTP_PAGE_SIZE (200) defensively. No new endpoint.
```

**Hard rules (verified against parent §"Backend changes" + 1g §8):**

- **The server never resolves `$bind` and never fetches a dataSource.** Resolution + fetch are **100%
  client-side**. The render contract round-trips `variables`/`dataSources`/`tree` opaquely (1g).
- Every record read is an `apiClient` call to `/api/{collection}…` → gateway → worker → Cerbos route
  authz + `CerbosFieldSecurityAdvice` FLS. There is **no** alternate read path. This is the security
  invariant the whole effort preserves.
- `filter` / `recordId` bindings are resolved **client-side** and sent as **query params** — the worker
  still applies tenant RLS + Cerbos on top, so a malicious bound filter cannot widen access.

### 3.8 New widget descriptors (parent §"Widget registry")

```ts
// widgets/builtins/field-value.tsx — read-only single bound value via FieldRenderer
export const fieldValueWidget: WidgetDescriptor = {
  type: 'field-value',
  label: 'Field value', icon: '◇', category: 'data', acceptsChildren: false,
  defaultProps: { source: '', fieldType: 'string' },
  propSchema: [
    { key: 'source',    label: 'Value',      kind: 'expression', bindable: true, group: 'Data' },
    { key: 'fieldType', label: 'Render as',  kind: 'select', options: FIELD_TYPE_OPTIONS, group: 'Data' },
  ],
  Render: ({ node }) => {
    // node.props is ALREADY fully resolved by renderNode (parent §"Widget registry → resolved-node
    // invariant"). Descriptors must NOT re-resolve — just read the resolved values.
    return <FieldRenderer type={(node.props.fieldType as FieldType) ?? 'string'} value={node.props.source} />
  },
}

// widgets/builtins/list.tsx — registered for BOTH 'list' and 'repeater' (aliases)
export const repeaterWidget: WidgetDescriptor = {
  type: 'repeater',
  label: 'Repeater', icon: '▦', category: 'data', acceptsChildren: true,
  defaultProps: { source: { $bind: '', mode: 'path' } },
  propSchema: [
    { key: 'source', label: 'Items (array)', kind: 'expression', bindable: true, group: 'Data' },
  ],
  Render: ({ node, scope, renderChild }) => {
    // node.props.source is ALREADY resolved (at the repeater's own scope) by renderNode — the resolved-node
    // invariant means the repeater does NOT call resolveBindings on its own props (parent §"Widget registry
    // → resolved-node invariant"). It is the ONE descriptor allowed to re-resolve, and ONLY for the per-row
    // `item` scope it builds for its children — which it does via renderChild(child, rowScope), NOT by
    // calling resolveBindings here.
    const source = node.props.source
    const rows = Array.isArray(source) ? source : []
    const visible = rows.slice(0, MAX_REPEATER_ROWS)          // DoS cap — §3.9
    return (
      <div data-testid="page-node-repeater" className="flex flex-col gap-4">
        {visible.map((row, i) => {
          // Per-row scope: item = row, and record aliased to the row (parent §"binding namespace").
          // renderChild(child, rowScope) re-runs renderNode under rowScope, so each child's props are
          // resolved against the row — the ONLY re-resolution the repeater triggers.
          const rowScope: BindingScope = { ...scope, item: row as Record<string, unknown>, record: row as Record<string, unknown> }
          return (
            <RepeatRow key={(row as { id?: string })?.id ?? i} node={node} scope={rowScope} renderChild={renderChild} />
          )
        })}
        {rows.length > MAX_REPEATER_ROWS && (
          <div data-testid="page-node-repeater-truncated" className="text-sm text-muted-foreground">
            {t('builder.repeater.truncated', { shown: MAX_REPEATER_ROWS, total: rows.length })}
          </div>
        )}
      </div>
    )
  },
}
```

> **Per-row scope propagation.** `renderChild` from 2a's `WidgetRenderProps` **already** has the signature
> `(child: PageComponent, scope?: BindingScope) => ReactNode` — the optional `scope` override is defined in
> 2a (parent §"Widget registry → resolved-node invariant"), **not** widened here. The repeater renders each
> child under `rowScope` by calling `renderChild(child, rowScope)`, which re-runs `renderNode` under that
> scope so the child's props resolve against the row. 2d does **not** change the `renderChild` signature;
> it only makes the resolver inside `renderNode` real (§5.4). `list` is registered as an alias of `repeater`
> (same descriptor, `type:'list'`).

### 3.9 DoS / fan-out caps (parent §"DoS / fan-out caps (2d)")

Two hard caps owned by this slice, plus the governor-quota note:

```ts
// model/limits.ts (new — small constants module, imported by the data-source panel + repeater)
/** A page may declare at most this many on-load data sources; each fires its own fetch. */
export const MAX_PAGE_DATA_SOURCES = 12
/** A repeater/list renders at most this many rows; the rest are truncated with a "showing N of M" note. */
export const MAX_REPEATER_ROWS = 200
```

- **`MAX_PAGE_DATA_SOURCES` (12)** — enforced **in the builder** Data-sources panel (§5.7): the
  `[+ Add source]` button disables once 12 sources exist, with an inline `t('builder.data.maxSources', …)`
  message. The on-load hook (`usePageDataSources`) also slices defensively to the cap so a hand-edited
  `config` with more sources cannot fan out unbounded fetches.
- **Repeater render cap (`MAX_REPEATER_ROWS`, 200)** — the `repeater`/`list` descriptor (§3.8) renders at
  most 200 rows and shows a `data-testid="page-node-repeater-truncated"` "showing N of M" line for the
  remainder. This is the repeater's own cap; the bound `table` widget keeps its independent
  `MAX_TABLE_ROWS=100` / `MAX_HTTP_PAGE_SIZE=200` clamps (parent).
- **Governor-quota note.** Each page view consumes per-tenant API governor quota **proportional to its
  declared data sources** (one authorized JSON:API GET per source on load, plus any `refreshData` from
  2e). This is expected and documented in §8 — a page with N sources costs ≥N reads per view.

---

## 4. DB migrations

**None — stored in `ui-pages.config` JSON, no DDL.**

Everything new (`variables` / `dataSources` / `$bind` markers) nests inside the existing `ui-pages.config`
JSON column (declared `FieldDefinition.json("config")`,
`runtime-core/.../model/system/SystemCollectionDefinitions.java`). No new column, no Flyway version
consumed; head remains **V146** (next available V147, **unused** by this slice). No NATS subject/payload
change — this is a client-side render concern, and the write path (`UIPageConfigEventPublisher` /
`UIPageSlugHook`) already broadcasts `config` changes wholesale.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/`.

### 5.1 New — `pages/PageBuilderPage/model/`

| File | Contents |
|------|----------|
| `model/bindingScope.ts` | §3.2 — `BindingScope`, `EMPTY_SCOPE`, `getPath` walker (dot + `[n]` indexing, missing → null), `SCOPE_ROOTS` / `ScopeRoot`. |
| `model/resolveBindings.ts` | §3.3 — `resolveBinding`, `flattenScopeForExpr` (the flat-scope bridge to `@kelta/formula`), `resolveBindings` (deep, immutable), private `resolveValue`. Owns the single `new FormulaEvaluator()`. |
| `model/interpolate.ts` | §3.4 — `interpolate(template, scope)` (`{{…}}`, `{{= expr }}` for expr mode), `isTemplate`. |

> `model/pageModel.ts` (`Binding`, `PropValue`, `isBinding`) already exists from 2a — **imported**, not
> modified. `resolveBindings.ts` is the module 2a's `renderTree.tsx` referenced as a "no-op shim" (2a §3.4);
> 2d provides the real implementation.

### 5.2 New — `pages/PageBuilderPage/hooks/`

| File | Contents |
|------|----------|
| `hooks/usePageVariables.ts` | `usePageVariables(variables: PageVariable[]): { vars: Record<string,unknown>; setVar(name, value): void; reset(): void }`. Seeds state from each `PageVariable.default` (coerced by `type`); `setVar` updates one (used by 2e's `setVar` action); memoized. Feeds `scope.vars`. |
| `hooks/usePageDataSources.ts` | §3.7 — `usePageDataSources(dataSources: PageDataSource[], scope: BindingScope): { data: Record<string,unknown>; refresh(name): void; isLoading: boolean }`. One `useQuery` per source via a stable `useQueries`; `buildListUrl`/single-id resolved through `resolveBindings(src.filter/recordId, scope)`. Uses `apiClient.getList`/`getOne`. Feeds `scope.data`. Exposes `refresh(name)` (invalidates that source's query — wired to 2e `refreshData`). |
| `hooks/usePageBindingContext.ts` | Assembles the live `BindingScope` for a page render: merges `record` (if the page is record-bound), `vars` (from `usePageVariables`), `data` (from `usePageDataSources`), `page` (route params from `useParams`). Also exports `buildScopeNamespaces(contract): StaticNamespace[]` for the inspector picker (§3.5). |

### 5.3 New — `pages/PageBuilderPage/widgets/builtins/`

| File | Contents |
|------|----------|
| `widgets/builtins/field-value.tsx` | §3.8 — `fieldValueWidget` (→ `FieldRenderer`). `FieldRendererProps` verified: `{ type, value, fieldName?, displayName?, tenantSlug?, targetCollection?, displayLabel?, className?, truncate? }`. `data-testid="page-node-field-value"`. |
| `widgets/builtins/list.tsx` | §3.8 — `repeaterWidget` + `RepeatRow` (renders the node's children under `rowScope` via `renderChild`). Register under **both** `repeater` and `list` in `registerBuiltins()`. `data-testid="page-node-repeater"`. |

`widgets/builtins/index.ts` (`registerBuiltins()` from 2a) gains:
`widgetRegistry.register(fieldValueWidget)`, `widgetRegistry.register(repeaterWidget)`, and a `list` alias
(`widgetRegistry.register({ ...repeaterWidget, type: 'list', label: 'List' })`).

### 5.4 Modify — `pages/PageBuilderPage/widgets/renderTree.tsx` (make the resolver real)

**One** surgical change to the 2a module: turn 2a's identity-no-op resolver into the real
`resolveBindings`. The `renderChild` signature and the `RenderTree` `scope` prop are **already defined in
2a** (`(child, scope?) => ReactNode`, `scope` defaulted `{}`) — 2d does **not** widen them.

**Resolve bindings before `Render`.** Replace the 2a no-op call with the real one:

```tsx
// BEFORE (2a): resolveBindings is an identity no-op shim; props pass through untouched.
// AFTER (2d): the same call site, now backed by the real implementation from model/resolveBindings.
import { resolveBindings } from '../model/resolveBindings'
// …inside renderNode, build a resolved node so descriptors read plain (already-resolved) values:
const resolvedProps = resolveBindings(node.props, ctx.scope)
const resolvedNode = { ...node, props: resolvedProps }
return <descriptor.Render key={node.id} node={resolvedNode} scope={ctx.scope} mode={ctx.mode}
                          tenantSlug={ctx.tenantSlug} renderChild={renderChild} />
```

> **Resolved-node invariant (align with 2a / parent §"Widget registry → resolved-node invariant").**
> `node.props` handed to every descriptor's `Render` is **already fully resolved** against the current
> scope. Descriptors **must not** call `resolveBindings` again — `field-value` reads `node.props.source`
> directly, and the **only** exception is the `list`/`repeater` descriptor, which re-resolves **solely the
> per-row `item` scope for its children** by calling `renderChild(child, rowScope)` (which re-runs
> `renderNode` under `rowScope`). The repeater reads its own array from the already-resolved
> `node.props.source`; it never re-runs `resolveBindings` on its own props. `scope` is still passed to
> `Render` so the repeater can build `rowScope`.

The 2a-defined `renderChild` — `(child: PageComponent, childScope: BindingScope = ctx.scope) =>
renderNode(child, { ...ctx, scope: childScope })` — already carries the optional per-row scope override;
2d relies on it unchanged. `RenderTree`'s `scope` prop (accepted in 2a, defaulted `{}`) is now wired from
`CustomPage`/preview with the live scope.

### 5.5 Finalize — `inspector/fields/ExpressionField.tsx` + `BindableField`

- **`inspector/fields/ExpressionField.tsx`** (new) — §3.5. Renders the chip + Edit button; opens
  `FieldExpressionPicker` (`open`, `rootCollectionId`, `staticNamespaces`, `mode`, `onInsert`). On
  `onInsert(token)`: if the active tab produced a function stub (or the user is in expr mode) →
  `{ $bind: token, mode: 'expr' }`; else `{ $bind: token, mode: 'path' }`. Shows the current `$bind`
  in a `code` chip; a "Clear" reverts to literal.
- **`inspector/fields/BindableField.tsx`** (finalize the 2b stub) — keep the `fx` toggle; in expr state
  delegate to `ExpressionField` (was a literal-only placeholder in 2b). Wire `rootCollectionId` +
  `namespaces` from `usePageBindingContext` via inspector context. The 2b literal branch is unchanged.

> If 2b has not yet landed `BindableField`, ship both files here; the schema-driven `Inspector` consuming
> `propSchema.bindable` is still 2b's responsibility — 2d only guarantees `BindableField` is binding-aware.

### 5.6 Modify — `pages/app/CustomPage/CustomPage.tsx` (wire scope at runtime)

- Read the 1g contract siblings: `contract.variables`, `contract.dataSources` (already typed by 1g).
- Build the scope:
  ```tsx
  const { vars, setVar } = usePageVariables(contract.variables ?? [])
  const { data } = usePageDataSources(contract.dataSources ?? [], { vars, page })   // dataSources may read vars/page
  const scope: BindingScope = { vars, data, page: { slug: pageSlug, params } }
  ```
- Pass `scope` to the renderer: `<PageTreeRenderer components={contract.tree?.components ?? []}
  tenantSlug={tenantSlug ?? ''} scope={scope} />`. `PageTreeRenderer` (the thin 2a wrapper) forwards
  `scope` to `<RenderTree scope={scope} … />`.
- **No binding resolution or data fetch happens server-side** — all of the above is client React.

### 5.7 Modify — `pages/PageBuilderPage/PageBuilderPage.tsx` + new `inspector/pageSettings/` (drawer + panels)

2d **creates the page-settings drawer host surface** — it does not exist today (the builder has only the
flat create/edit page form; no earlier slice adds a drawer — see §2.3). New components under
`inspector/pageSettings/`:

| File | Contents |
|------|----------|
| `inspector/pageSettings/PageSettingsDrawer.tsx` | The drawer **shell** opened from a new **"Page settings"** toolbar button. Stacks the Variables + Data-sources sections; reserves a slot 1h fills with its Access field. Reads/writes `config.variables` / `config.dataSources` via the page-config state. |
| `inspector/pageSettings/VariablesSection.tsx` | Editor for `config.variables` (`PageVariable[]`): add/remove rows, name + `type` select + `default`. (§2.3 wireframe.) |
| `inspector/pageSettings/DataSourcesSection.tsx` | Editor for `config.dataSources` (`PageDataSource[]`) (§2.3): name, mode (List/Single), collection, fields, limit, bound filter, single-mode record id. **Enforces `MAX_PAGE_DATA_SOURCES` (§3.9):** `[+ Add source]` disables at 12 with an inline `t('builder.data.maxSources')` message. |

`PageBuilderPage.tsx` wires the **"Page settings"** button → `PageSettingsDrawer`, and passes the editor
preview a **design-time scope**: `vars` seeded from variable defaults, `data` empty arrays by default;
an opt-in "Preview with live data" toggle may call `usePageDataSources` — default **off** to keep parity
with 2a's no-live-fetch-in-editor decision.

> Extract these into `inspector/pageSettings/` rather than inlining — `PageBuilderPage.tsx` is already
> large (§8). The drawer is the single host for page-level config so 1h can drop in Access cleanly.

#### Save call — extend 2c's `handleSavePage` (correction owned with 2c)

2c **owns** the canonical `handleSavePage`/`mergeConfig` rewrite (parent §"Save & persistence") that
widens `mergeConfig` to overlay page-level siblings and passes the full set:
`mergeConfig(readConfig(currentPage), { components, variables, dataSources, schemaVersion: 2 })`. **2d
extends that same call to include its keys (`variables`/`dataSources`)** — it does not re-do the rewrite,
it only ensures `variables`/`dataSources` are in the passed set so the drawer's edits persist (otherwise
`mergeConfig` silently drops them — the exact bug class the parent calls out). A **mutation-payload test**
(§6.10b) asserts the `updateMutation.mutate` body carries `variables` and `dataSources`, not just that
`mergeConfig` returns them.

### 5.8 No backend changes

Confirmed: data flows through the existing JSON:API (`apiClient.getList`/`getOne`/`postResource`), the
1g render contract already surfaces `variables`/`dataSources`, and there is no new endpoint, gateway
route, NATS subject, or migration.

### 5.9 i18n — new strings (parent §"i18n": all via `useI18n`/`t()`, `builder.*` keys)

Every new user-facing string in 2d's UI (the page-settings drawer, Variables/Data-sources sections, the
binding `fx` field, and the repeater truncation note) goes through `useI18n`/`t()` — **no hardcoded
English** (the builder is already fully `useI18n`-driven). 2d owns these keys (illustrative set):

| Key | Use |
|-----|-----|
| `builder.pageSettings.title` / `.open` | Drawer title + "Page settings" toolbar button |
| `builder.variables.title` / `.add` / `.remove` / `.name` / `.type` / `.default` | Variables section |
| `builder.data.title` / `.add` / `.remove` / `.mode.list` / `.mode.single` / `.collection` / `.limit` / `.fields` / `.filter` / `.recordId` | Data-sources section |
| `builder.data.maxSources` | `MAX_PAGE_DATA_SOURCES` cap message (§3.9) |
| `builder.binding.fx` / `.edit` / `.clear` / `.expression` / `.literal` / `.mode.path` / `.mode.expr` | `BindableField`/`ExpressionField` chrome |
| `builder.repeater.truncated` | Repeater "showing N of M" note (§3.8/§3.9) |

---

## 6. Test plan

Vitest + Testing Library + MSW, matching the existing idiom (`pageConfig.test.ts` for pure functions;
`PageBuilderPage.test.tsx` / `PageTreeRenderer` tests for render with MSW `server`). Pure model tests need
no DOM.

### 6.1 New — `model/resolveBindings.test.ts`

- **Literal passthrough:** `resolveBindings({ a: 'x', b: 3, c: true, d: null }, scope)` returns the same
  values (no `$bind`).
- **Path mode — flat:** `{ $bind: 'record.name', mode: 'path' }` against `{ record: { name: 'Acme' } }`
  → `'Acme'`.
- **Path mode — nested + index:** `{ $bind: 'data.accounts[0].name', mode: 'path' }` against
  `{ data: { accounts: [{ name: 'Acme' }] } }` → `'Acme'`.
- **Missing path → null:** `{ $bind: 'record.nope', mode: 'path' }` → `null`; `record.a.b.c` where `a` is
  undefined → `null` (no throw).
- **Expr mode via formula (flat-scope bridge):** `{ $bind: 'IF(count > 0, "yes", "no")', mode: 'expr' }`
  against `{ vars: { count: 2 } }` — assert `flattenScopeForExpr` produces `{ count: 2 }` (since
  `extractFieldRefs('IF(count > 0, …)')` → `['count']` and `getPath(scope,'count')` walks `scope.count`…
  **note** the bridge resolves the *bare* ref against the scope root) → result `'yes'`. Also test the
  explicit-vars form the picker emits.
- **Expr mode — referenced leaf missing → null inside formula:** `{ $bind: 'count + 1', mode: 'expr' }`
  with empty scope → `extractFieldRefs` `['count']`, `getPath` → null, engine `toDouble(null)=0` → `1`
  (proves missing-leaf = null = 0-in-arithmetic, matching `ast.toDouble`).
- **Malformed expr → null + warn (dev):** `{ $bind: 'IF(', mode: 'expr' }` → `null`, no throw.
- **Deep recursion:** a props object with a `Binding` nested inside an array and an object resolves each.
- **Default mode is path:** `{ $bind: 'vars.x' }` (no `mode`) behaves as path.

### 6.2 New — `model/interpolate.test.ts`

- **Plain string passthrough:** `interpolate('hello', scope)` → `'hello'` (no `{{`).
- **Single tag (path):** `interpolate('Hi {{record.name}}', { record: { name: 'Ada' } })` → `'Hi Ada'`.
- **Indexed tag:** `interpolate('{{data.accounts[0].name}}', { data: { accounts: [{ name: 'Acme' }] } })`
  → `'Acme'`.
- **Expr tag (`{{= …}}`):** `interpolate('{{= IF(count > 0, "a", "b") }}', { vars: { count: 1 } })` → `'a'`.
- **Missing → empty string:** `interpolate('x {{record.nope}} y', scope)` → `'x  y'`.
- **Multiple tags + literal mix** in one string.

### 6.3 New — `model/bindingScope.test.ts`

- `getPath` dot, `[n]`, mixed, missing-root, null-midway, empty-path → null; non-object midway → null.
- **Prototype-pollution guard:** `getPath(scope, '__proto__')`, `'constructor'`, `'a.constructor.prototype'`
  all return `null` (never reach the prototype chain) — parent §"Security".

### 6.4 New — `widgets/builtins/field-value.test.tsx`

- Renders `FieldRenderer` with `type` from `fieldType` and `value` from the resolved `source` binding
  (`record.email` path) — assert the email is shown via `data-testid="page-node-field-value"` wrapping a
  `FieldRenderer` output; defaults to `type='string'` when `fieldType` absent.

### 6.5 New — `widgets/builtins/list.test.tsx` (repeater)

- Bound to `data.accounts` (2 rows) → renders the child subtree **twice**; each row's `{{item.name}}` and
  `{{item.status}}` resolve to that row's values (assert two `page-node-repeater` children with distinct
  text). `record.name` inside a row also resolves (record-aliased-to-row).
- Empty/absent array → renders zero rows (no crash).
- `list` alias renders identically to `repeater`.
- **Render cap (§3.9):** an array of >200 rows renders exactly `MAX_REPEATER_ROWS` (200) child subtrees and
  shows the `page-node-repeater-truncated` "showing 200 of N" line; ≤200 renders no truncation note.

### 6.6 New — `widgets/renderTree.binding.test.tsx`

- A `heading` with `text = { $bind: 'record.name', mode: 'path' }` rendered via `RenderTree mode="runtime"`
  with `scope.record` shows the name. The **same tree** in `mode="editor"` with a design-time scope shows
  the same value (de-dup guarantee from 2a preserved under binding).
- A `text` with `content = "Showing {{data.x}}"` interpolates.

### 6.7 New — `hooks/usePageDataSources.test.tsx`

- **Authorized JSON:API path (security-critical):** a `list` source mocks MSW `/api/accounts` and asserts
  the **request URL** is `/api/accounts?page[size]=25` (the same authorized path `DataTableNode` uses) and
  that `data.accounts` is populated. **Assert no server-side binding resolution** — the test proves the
  fetch is a normal `apiClient.getList`, i.e. Cerbos/FLS apply server-side (denied fields would be absent).
- **Single mode:** `mode:'single'` with `recordId = { $bind: 'page.params.id', mode:'path' }` resolves the
  id client-side and fetches `/api/accounts/{id}` via `getOne`; 404 → `data.x = null`.
- **Bound filter resolved client-side:** `filter: { status: { $bind:'vars.s', mode:'path' } }` with
  `vars.s='open'` → request includes `filter[status][EQ]=open` (resolved on the client, sent as a query
  param; worker still enforces RLS/Cerbos).
- **`refresh(name)`** invalidates and refetches one source.

### 6.8 New — `hooks/usePageVariables.test.tsx`

- Seeds `vars` from `default` per `type` (number/string/boolean/json); `setVar` updates one; `reset`
  restores defaults.

### 6.9 New — `inspector/fields/ExpressionField.test.tsx`

- `fx` on → opens `FieldExpressionPicker`; `onInsert('account_id.name')` → `onChange({ $bind:
  'account_id.name', mode: 'path' })`; a function stub → `mode: 'expr'`; "Clear" → literal.
- Re-opening with an existing `{ $bind }` shows the chip and round-trips.

### 6.10 New — `inspector/pageSettings/PageSettingsDrawer.test.tsx`

- Opening the **"Page settings"** drawer shows the **Variables** and **Data sources** sections (the drawer
  is created in 2d — assert both render).
- Variables section: add/remove a variable, edit name/type/default → reflected in `config.variables`.
- Data sources section: add/remove a source; **`MAX_PAGE_DATA_SOURCES` cap** — with 12 sources the
  `[+ Add source]` button is disabled and the `builder.data.maxSources` message shows.

### 6.10b New — `PageBuilderPage.save.test.tsx` (mutation payload)

- **Save round-trip (correction owned with 2c):** after editing variables + data sources in the drawer,
  trigger save and assert the **`updateMutation.mutate` body** (not just `mergeConfig`'s return) contains
  both `variables` and `dataSources` (plus `components`/`schemaVersion` from 2c) — proving 2d's keys are in
  the passed set so they are not silently dropped on save.

### 6.10c — Parity guard (re-run 2a's suite UNCHANGED)

- **Builtin parity / golden-snapshot identity (correction 7):** re-run **2a's** builtin parity /
  golden-snapshot suite **UNCHANGED** after 2d lands. Because every literal (non-`$bind`) prop is unchanged
  by the now-real `resolveBindings` (literals pass through `resolveValue` untouched), all 8 built-ins must
  produce byte-identical snapshots — proving the real resolver is an **identity on literal props**. Any
  diff is a regression in the resolver, not an intended change. (No new snapshot file; this re-runs the
  existing 2a suite as-is.)

### 6.11 e2e (post-deploy, Playwright — project convention)

A bound page (heading `{{record.name}}` or a `repeater` over a `dataSource`) renders the live value after
deploy; a denied field bound via `field-value` shows empty (server stripped it) — proving FLS holds end to
end. No new e2e file authored in this slice (e2e is post-deploy per convention); covered by the Vitest
suite above pre-merge.

> **DB-constraint test-gap guard:** N/A — this slice adds no write path, DDL, or constraint. It is a
> read/render concern; the on-load fetch is a plain authorized GET. The
> [guard](../../testing.md#real-db-guard-for-constraint-bearing-writes)
> applies to constraint-bearing writes, which this is not.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/conventions.md` | Document the **page config v2 binding contract** (the parent owns the high-level shape; this slice makes it real): `$bind` is the single expression marker; `mode:'path'` walks scope dot/`[n]` paths via `getPath` (because `@kelta/formula`'s parser is flat-key only — cite the limitation), `mode:'expr'` flattens referenced leaves then calls `FormulaEvaluator.evaluate`; `{{…}}` interpolation in literal strings (`{{= expr }}` for expr mode); the namespace authority `record`/`vars`/`page`/`item`/`data`. State the **client-side-only** resolution rule and that the server round-trips `$bind` untouched (preserves Cerbos/FLS). |
| `.claude/docs/status.md` | Page-builder row: move **data binding & on-load data sources** out of the gap column → "slice 2d: any prop bindable to `record`/`vars`/`data` via `$bind` (`path`/`expr`), page `variables` + on-load `dataSources` (client-side authorized JSON:API fetch), `field-value` + `list`/`repeater` widgets. Binding resolution is client-side only — server resolves nothing (FLS preserved)." Keep events/typed-forms/per-page-authz in the gap (2e/2f/1h). |
| `.claude/docs/architecture.md` | `PageRenderService` / custom-page data-flow row: note that bindings + dataSources resolve **client-side** against the authorized JSON:API path; the render contract stays a pure pass-through (no server-side `$bind`/dataSource evaluation) so Cerbos route authz + `CerbosFieldSecurityAdvice` FLS remain the only data gate. |
| `.claude/docs/playbooks.md` | Extend the "Add a page component / widget" recipe (added in 2a) with the **bindable prop** step: mark a `PropFieldSchema` `bindable: true` and `kind:'expression'`; read **already-resolved** props in `Render` (`node.props` is resolved by `renderNode` — the resolved-node invariant; descriptors do **not** call `resolveBindings`); the **only** per-row exception is `list`/`repeater`, which renders children under a row scope via `renderChild(child, rowScope)`. Note `dataSources` for on-load data and the `usePageBindingContext` scope assembly. |

Per CLAUDE.md Rule 6 these doc edits ship **in the same PR** as the code.

---

## 8. Risks & open questions

- **Expr-mode dotted access is intentionally limited (verified constraint).** `@kelta/formula`'s parser is
  flat-key only (`parser.ts` `parseIdentifierOrFunction` stops at `.`), so `IF(record.name = "x", …)`
  throws at parse and resolves to `null`. The design routes **all dotted access through `mode:'path'`**
  and keeps `mode:'expr'` for logic over **flat** leaves (`vars.*`/`data.*` names are single segments;
  raw record leaves can be aliased by `ExpressionField`). **Open question for the user:** is flat-leaf
  expr sufficient for v1, or should we add a pre-pass that rewrites dotted refs in expressions to flat
  aliases (a small transform over `extractFieldRefs` + source substitution) so authors can write
  `record.name` inside `IF(...)` directly? **Recommendation:** ship flat-leaf expr + `path` for dotted now;
  add the alias pre-pass only if authors hit it. (Do **not** extend the formula parser — it's shared with
  the backend formula-field engine and must stay parity-locked.)
- **Client-side resolution is the security boundary — do not regress it.** The single most important
  invariant: **no `$bind` or dataSource is resolved/fetched server-side.** Every record read is an
  `apiClient` GET to `/api/{collection}…` so the gateway+worker enforce Cerbos route authz and FLS. A
  reviewer must reject any change that resolves a binding in `PageRenderService` or fetches a dataSource
  server-side (1g §8 says the same). `usePageDataSources.test` asserts the fetch URL is the authorized
  path; keep that test load-bearing.
- **`renderChild`'s scope override is 2a's, not a 2d widening.** The optional `scope` arg on
  `WidgetRenderProps.renderChild` (`(child, scope?) => ReactNode`) is **defined in 2a** (parent §"Widget
  registry → resolved-node invariant"); 2d uses it unchanged and does **not** alter the interface. The
  repeater **requires** it; containers/cards omit it (back-compat). Land 2d's `renderTree.tsx` change (the
  identity-resolver → real-resolver flip) with the **2a parity tests re-run unchanged** (§6.10c) to prove
  containers/cards and all literal props are unaffected. **Sequencing:** 2d depends on 2a's
  `renderTree`/registry/model and benefits from 2b's `BindableField`; if 2b is not merged, ship
  `BindableField`+`ExpressionField` here.
- **Page-settings drawer is a new host surface 2d owns.** No earlier slice creates a page-settings
  drawer, Variables UI, or Data-sources UI — the builder has only the flat create/edit page form. 2d
  builds the drawer shell + both sections (§2.3, §5.7); 1h later drops its Access field into the same
  drawer. Keep them in `inspector/pageSettings/` so `PageBuilderPage.tsx` (already large) does not bloat.
- **Governor-quota consumption per page view.** Each declared on-load data source costs one authorized
  JSON:API GET per page view (plus 2e `refreshData` re-fetches), so a page with N sources consumes ≥N
  reads of per-tenant API governor quota per view. `MAX_PAGE_DATA_SOURCES` (12) bounds the fan-out;
  editor live-data preview stays **off by default** to avoid surprise authoring-time consumption. Noted in
  `concerns.md` per the parent §"Security → Abuse/governor".
- **`flattenScopeForExpr` semantics for non-flat refs.** `extractFieldRefs` returns the flat identifiers
  the parser saw; `getPath(scope, ref)` resolves a *single-segment* ref against the scope root (e.g.
  `count` → `scope.count`, which is **undefined** unless the author aliased a var to a bare name). The
  natural authored shape is `vars` flattened into the eval scope: `ExpressionField` should, for expr mode,
  flatten `vars`/`data`/`item`/`record` **leaves** into the eval map (so `count` means `vars.count`). The
  spec's `flattenScopeForExpr` uses `getPath` for parity with `path` mode; **open decision:** also spread
  `scope.vars`/`scope.item`/`scope.record` top-level into the flat map so bare leaf names resolve without
  a `vars.` prefix (matches how formula-field expressions reference plain field names). **Recommendation:**
  spread `record`+`item`+`vars` leaves (then overlay `extractFieldRefs` getPath results) so authored
  expressions read like field formulas. Pin this in `resolveBindings.test.ts`.
- **Editor live-data preview is off by default.** Per 2a's decision, the editor does not fetch live data
  (parity). 2d adds an opt-in "Preview with live data" toggle; default off avoids surprise reads and
  governor-limit consumption while authoring. Confirm with the user whether on-by-default is desired.
- **`PageBuilderPage.tsx` size.** Adding the Data/Variables config panels grows the (already large) file;
  prefer extracting them into `inspector/pageSettings/` components. Not currently flagged in
  `concerns.md`, but keep the additions modular so 2b/2c extractions stay clean.
- **No JPA / records rule (Rule 2) and NATS rule (Rule 1) untouched** — this slice is FE-only; it adds no
  backend model, repository, or system-collection hook, so neither rule is engaged.
