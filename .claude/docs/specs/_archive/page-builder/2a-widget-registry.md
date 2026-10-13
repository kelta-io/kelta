# 2a — Widget registry + shared render module + model

> **Slice:** 2a (FE foundation keystone). **Axis:** foundation.
> **Parent:** [`../page-builder-parity.md`](../../page-builder-parity.md). This child spec **extends, never
> contradicts** the parent's [The shared model](../../page-builder-parity.md#the-shared-model),
> [Widget registry](../../page-builder-parity.md#widget-registry), and
> [Page-level config (v2)](../../page-builder-parity.md#page-level-config-v2) sections. The
> `WidgetDescriptor` / `PropFieldSchema` / `PageComponent` v2 / `Binding` / `PropValue` types are
> **defined here once** for the whole effort; later slices consume them.
> Source-verified against the codebase on 2026-06-22 (Flyway head V146; this slice adds no migration).
> If code and this doc disagree, trust the code and fix this doc.

---

## 1. Goal & scope

### What this slice delivers

The keystone **pure refactor** of the page-builder front end. Today the 8 built-in component types
are hardcoded across **five per-type `switch`/conditional sites** that must be kept in lockstep:

| # | Site | File | Lines (2026-06-22) |
|---|------|------|--------------------|
| 1 | `AVAILABLE_COMPONENTS` palette array | `PageBuilderPage.tsx` | ~101–110 |
| 2 | `PropertyPanel` per-type `&&` blocks | `PageBuilderPage.tsx` | ~301–479 |
| 3 | `Canvas.renderComponent` per-type previews | `PageBuilderPage.tsx` | ~798–838 |
| 4 | `Preview.renderPreviewComponent` `switch` | `PageBuilderPage.tsx` | ~521–631 |
| 5 | `PageNodeRenderer` runtime `switch` + `DataTableNode`/`FormNode` | `PageTreeRenderer.tsx` | ~238–304 |

This slice introduces:

1. **`widgets/types.ts`** — the `WidgetDescriptor` + `PropFieldSchema` + `WidgetRenderProps` contract.
2. **`widgets/registry.ts`** — the `widgetRegistry` singleton (`register` / `get` / `list` /
   `listByCategory`), with a **plugin fallback shim**: `get(type)` wraps any plugin component from the
   existing `componentRegistry.getPageComponent(type)` in a synthetic descriptor, so plugins keep
   working with **zero changes**, and an **unknown-type default descriptor** so neither builder nor
   runtime special-case "unknown type" anymore.
3. **`widgets/renderTree.tsx`** — `RenderTree` + `renderNode`: the **single** render path shared by the
   editor preview (`mode:'editor'`) and the runtime (`mode:'runtime'`).
4. **`widgets/builtins/*.tsx`** — one descriptor per existing built-in type
   (`heading`, `text`, `button`, `image`, `form`, `table`, `card`, `container`), migrated **at parity**
   (no behavior change — same DOM, same `data-testid`s, same data-fetch path). Type strings are
   load-bearing: the bound grid registers as `type:'table'` and the form as `type:'form'` (the legacy
   strings already in saved pages — do **not** rename to `data-table`). `form`'s palette category is
   `input` (parent §"Widget registry"), so the `input` palette category is **non-empty from 2a onward**.
5. **`model/pageModel.ts`** — the v2 `PageComponent` / `Binding` / `PropValue` / `PageAction` /
   `EventHandlers` / `ResponsiveSpan` types (defined now; `events`/`span`/bindings are *carried through*
   the model but not yet authored — that's 2b–2e).
6. **`model/treeOps.ts`** — pure tree mutations. This slice ships **working**
   `insertNode`/`removeNode`/`updateProps` (used by the existing builder add/delete/prop handlers) so the
   refactor compiles and `treeOps` is unit-tested. `moveNode`/`setSpan` ship as **no-op stubs that return
   the input tree unchanged** (finished in **2c** alongside the dnd canvas) — they do **not** throw.
   **Guarantee:** no code path reaches `moveNode`/`setSpan` before 2c — there is no reorder/resize/span UI
   yet, and the 2b inspector edits props via `updateProps` only, never `moveNode`/`setSpan`.

It then **refactors the two render surfaces to share one module**:

- `PageBuilderPage.tsx`'s `Preview` (and the `Canvas` per-type preview blocks) → render via
  `<RenderTree mode="editor">`.
- `PageTreeRenderer.tsx` → becomes a thin `<RenderTree mode="runtime">` wrapper. The `DataTableNode`
  and `FormNode` logic is **moved verbatim** into the `table` and `form` builtin descriptors (so the
  Cerbos/FLS-enforcing JSON:API fetch path is preserved exactly).

And **folds the plugin registry in** via the fallback shim (no change to `componentRegistry.ts` or the
`PageComponentProps` contract).

> **The slice ships in two phases (split for safe rollout — parent §"Rollout & rollback"):**
> - **Phase A** — introduce `model/` + `widgets/` + `RenderTree` + the 8 builtin descriptors *behind the
>   registry*, parity-tested with a **golden snapshot** (Vitest/Playwright DOM snapshot) of all 8 built-ins
>   rendered from a fixed JSON fixture, captured **before** the refactor. Nothing user-visible flips yet.
> - **Phase B** — flip `PageBuilderPage.tsx`'s preview/canvas and `PageTreeRenderer.tsx`/`CustomPage` to
>   consume `RenderTree`. The **runtime** flip is gated behind a **system feature flag** (platform already
>   has `kelta.config.feature.changed` infra) with fallback to the legacy `PageNodeRenderer`; the legacy
>   renderer stays in the tree until 2a soaks. The single deliberate behavior change (runtime `heading`
>   honoring `level` instead of hardcoded `<h2>`) is a **reviewed snapshot diff**, not a silent change.
> - **Rollback trigger:** a spike in page-render error rate or unknown-widget-fallback rate → flip the flag
>   back to `PageNodeRenderer` (see §8).
>
> **Hard edge — 1g ships before 2a.** 2a is FE-only and **tolerates a v1 render contract**: when 1g's
> `version "2.0"` siblings aren't present, `CustomPage` passes `scope={}` and everything renders as today
> (see §3.6). The flag gating keeps `main` from ever being half-broken.

### What this slice explicitly does NOT do

| Out of scope | Lands in |
|--------------|----------|
| New widgets (grid/row/column/divider/chart/tabs/nav/icon/link/list/repeater/field-value, typed inputs) | 2c / 2f / 2g |
| Bindings **UI** + expression resolution (`BindableField`, `resolveBindings`, `interpolate`, data sources) | 2d |
| Events/actions authoring + action runtime | 2e |
| dnd-kit canvas (drop-into-container, reorder, resize, `span`) + full `treeOps`/`migrate` | 2c |
| Inspector redesign (schema-driven `Inspector` looping over `propSchema`, palette from registry) | 2b |

> **Important:** the descriptor's `propSchema` field **is defined and populated here** for every built-in
> (so 2b can build the schema-driven inspector by looping over it). But in 2a the existing hardcoded
> `PropertyPanel` keeps editing props — it is **not** deleted until 2b. 2a is the data plumbing; 2b is
> the consumer. This keeps 2a a true no-behavior-change refactor.

### Acceptance criteria

- The builder palette, editor preview, and runtime renderer all resolve component types through
  `widgetRegistry` — there is **one** lookup table, not five.
- Editor preview and runtime render **the same tree identically** (same descriptor `Render`), proven by a
  Vitest test that renders the same JSON in both modes and asserts equivalent output.
- All 8 built-ins render byte-for-byte as before (same `data-testid`, same classes) — proven against a
  **golden snapshot** of all 8 built-ins captured from a fixed JSON fixture **before** the refactor
  (Phase A); the only allowed diff is the reviewed runtime `heading` `level` fix (Phase B).
- The runtime render flip (`CustomPage` → `RenderTree`) is **behind a system feature flag** with fallback
  to the legacy `PageNodeRenderer`; the legacy renderer stays in the tree until 2a soaks.
- Plugin page components registered via `componentRegistry.registerPageComponent(...)` still render in
  builder canvas, preview, and runtime with **zero plugin code changes**; `PageComponentProps` is
  unchanged (and the shim hands plugins **binding-resolved** props — see §3.4).
- Unknown component types render the existing "Unknown component: <type>" fallback (now via the default
  descriptor, not a `default:` switch arm).
- The `form` built-in registers under palette category **`input`** (not `data`); the `input` palette
  category is non-empty from 2a.
- `/verify` green (lint + typecheck + `test:coverage` ≥ existing kelta-ui gate).

---

## 2. UI samples

### 2.1 Sample `WidgetDescriptor` — `heading` (concrete TS)

```tsx
// widgets/builtins/heading.tsx
import type { WidgetDescriptor } from '../types'

export const headingWidget: WidgetDescriptor = {
  type: 'heading',
  label: 'Heading',
  icon: 'H',
  category: 'content',
  acceptsChildren: false,
  defaultProps: { text: 'Heading', level: 'h1' },
  // propSchema is DEFINED here for 2b; 2a's PropertyPanel still does the editing.
  propSchema: [
    { key: 'text', label: 'Text', kind: 'text', bindable: true, group: 'Content' },
    {
      key: 'level',
      label: 'Level',
      kind: 'select',
      options: [
        { value: 'h1', label: 'H1' },
        { value: 'h2', label: 'H2' },
        { value: 'h3', label: 'H3' },
        { value: 'h4', label: 'H4' },
      ],
      group: 'Content',
    },
  ],
  // Render is the SINGLE source of truth used by editor preview AND runtime.
  Render: ({ node }) => {
    const level = (node.props.level as string) || 'h1'
    const text = (node.props.text as string) || 'Heading'
    const HeadingTag = level as keyof JSX.IntrinsicElements
    return (
      <HeadingTag className="m-0 text-foreground" data-testid="page-node-heading">
        {text}
      </HeadingTag>
    )
  },
}
```

> Parity note: the **editor preview** (`Preview.renderPreviewComponent`) today renders `heading` exactly
> like this (`<HeadingTag className="m-0 text-foreground">`). The **runtime** (`PageNodeRenderer`) today
> hardcodes `<h2 className="text-2xl …">` regardless of `level`. The descriptor unifies on the *editor*
> behavior (honor `level`), which is a strict improvement and the de-dup goal — call this out in the PR;
> the runtime parity test asserts the new (level-honoring) output and a migration note is added to
> `status.md`. All other built-ins are byte-identical across the two surfaces today and migrate cleanly.

### 2.2 Sample `WidgetDescriptor` — `table` (concrete TS, moves `DataTableNode` in)

```tsx
// widgets/builtins/table.tsx
import type { WidgetDescriptor } from '../types'
import { DataTableNode, readDataView } from './dataTableNode' // DataTableNode moved out of PageTreeRenderer

export const tableWidget: WidgetDescriptor = {
  type: 'table',
  label: 'Table',
  icon: '⊞',
  category: 'data',
  acceptsChildren: false,
  defaultProps: {},
  propSchema: [
    { key: 'dataView.collection', label: 'Collection', kind: 'collection-picker', group: 'Data' },
    { key: 'dataView.fields', label: 'Columns', kind: 'field-picker', dependsOnCollection: true, group: 'Data' },
    { key: 'dataView.limit', label: 'Row limit', kind: 'number', group: 'Data' },
  ],
  Render: ({ node, mode }) => {
    // Editor preview historically showed a placeholder box; runtime fetched live rows.
    // Parity: keep BOTH behaviors keyed on mode (see §5.4 "preserve mode-specific table behavior").
    if (mode === 'editor') {
      return (
        <div className="w-full" data-testid="preview-table-placeholder">
          {/* …existing Grid3x3 placeholder box… */}
        </div>
      )
    }
    return <DataTableNode dataView={readDataView(node.props)} />
  },
}
```

> The Cerbos/FLS-enforcing fetch (`apiClient.getList('/api/{collection}?page[size]=…')`) inside
> `DataTableNode` is **moved verbatim** — not rewritten — so server-side field-stripping is preserved.
> Same for `FormNode` → `form` widget (`apiClient.postResource('/api/{collection}', values)`).

### 2.3 Sample component-tree JSON (v2-compatible, what `config.components` holds)

```json
[
  { "id": "c1", "type": "heading", "props": { "text": "Orders", "level": "h1" } },
  { "id": "c2", "type": "text", "props": { "content": "Recent orders below." } },
  {
    "id": "c3",
    "type": "container",
    "props": {},
    "children": [
      {
        "id": "c4",
        "type": "table",
        "props": { "dataView": { "collection": "orders", "fields": ["id", "status", "total"], "limit": 25 } }
      }
    ]
  },
  { "id": "c5", "type": "acme_kpi_widget", "props": { "metric": "mrr" } }
]
```

`c5` resolves through the plugin fallback shim (`componentRegistry.getPageComponent('acme_kpi_widget')`).
The optional v2 fields (`events?`, `span?`) are absent here and round-trip untouched.

### 2.4 Before / after render path

```
TODAY (5 duplicated switch sites, two render surfaces):

  Palette ─────────► AVAILABLE_COMPONENTS[]            (PageBuilderPage.tsx ~101)
  Inspector ───────► PropertyPanel  type==='heading' ? …  (PageBuilderPage.tsx ~301)
  Editor preview ──► Canvas.renderComponent  switch(type) (PageBuilderPage.tsx ~798)
                     Preview.renderPreviewComponent switch (PageBuilderPage.tsx ~521)
  Runtime ─────────► PageNodeRenderer switch(type) + DataTableNode/FormNode (PageTreeRenderer.tsx ~238)
                     └─ default: componentRegistry.getPageComponent(type)

  → 5 lists hand-synced; editor preview and runtime DUPLICATE the per-type JSX.


AFTER 2a (one registry, one render module):

                        ┌───────────────────────────┐
                        │   widgetRegistry (single) │
                        │  type → WidgetDescriptor  │
                        │  .get() falls back to     │
                        │  plugin componentRegistry │
                        │  then default descriptor  │
                        └───────────┬───────────────┘
                                    │ list / listByCategory / get
            ┌───────────────────────┼───────────────────────┐
            │                       │                        │
       Palette*               Inspector**            RenderTree (shared)
   (still array in 2a,    (still PropertyPanel    mode:'editor' → builder preview/canvas
    becomes registry      in 2a, becomes          mode:'runtime' → CustomPage
    loop in 2b)           propSchema loop in 2b)  ── one renderNode() looks up descriptor,
                                                     calls descriptor.Render(props)
```

No visual redesign in this slice — pixels are identical (modulo the heading-`level` runtime fix noted
in §2.1).

---

## 3. Data & API contracts

All TS lives under `kelta-ui/app/src/pages/PageBuilderPage/`. No backend/API change in 2a.

### 3.1 `model/pageModel.ts` — v2 model (matches parent §"The shared model")

```ts
/** A bound value: {{...}} convention. `mode:'path'` walks scope dot-paths; `mode:'expr'` evaluates. */
export interface Binding {
  $bind: string
  mode?: 'path' | 'expr'
}

export type PropValue =
  | string
  | number
  | boolean
  | null
  | Binding
  | PropValue[]
  | { [key: string]: PropValue }

export function isBinding(v: unknown): v is Binding {
  return typeof v === 'object' && v !== null && typeof (v as Binding).$bind === 'string'
}

export type PageAction =
  | { action: 'runFlow'; flowId: string; input?: Record<string, PropValue>; awaitResult?: boolean }
  | { action: 'navigate'; to: string; params?: Record<string, PropValue>; newTab?: boolean }
  | { action: 'openUrl'; url: PropValue; newTab?: boolean }
  | { action: 'createRecord' | 'updateRecord'; collection: string; attributes: Record<string, PropValue>; recordId?: PropValue }
  | { action: 'refreshData'; dataSource: string }
  | { action: 'setVar'; name: string; value: PropValue }
  | { action: 'showToast'; level: 'info' | 'success' | 'error'; message: PropValue }

export type EventHandlers = Partial<
  Record<'onClick' | 'onChange' | 'onSubmit' | 'onLoad', PageAction[]>
>

export interface ResponsiveSpan {
  base: number // 1..12
  sm?: number
  md?: number
  lg?: number
}

/**
 * v2 component node. `position` (legacy {row,column,width,height}) is DROPPED from the active model —
 * `migrate.ts` (2c) converts legacy trees. In 2a, `position` is tolerated on read (ignored) so existing
 * saved pages load; new nodes are created without it.
 */
export interface PageComponent {
  id: string
  type: string
  props: Record<string, PropValue>
  events?: EventHandlers
  span?: ResponsiveSpan
  children?: PageComponent[]
}
```

> **Compat with the existing `PageComponent` (PageBuilderPage.tsx ~61).** The current interface is
> `{ id; type; props: Record<string, unknown>; children?; position: ComponentPosition }` with `position`
> **required**. The v2 type makes `position` absent and `props` typed as `Record<string, PropValue>`
> (a narrowing of `unknown`). To avoid a big-bang rename in 2a, the existing
> `export interface PageComponent` in `PageBuilderPage.tsx` is **replaced by a re-export** of the model
> type (`export type { PageComponent } from './model/pageModel'`), and `ComponentPosition` is kept as a
> deprecated legacy interface only used by `migrate.ts`/back-compat readers. `handleAddComponent` stops
> writing `position` (see §5.3).

### 3.2 `widgets/types.ts` — descriptor + prop-schema contract (matches parent §"Widget registry")

```ts
import type { ComponentType, ReactNode } from 'react'
import type { PageComponent, EventHandlers } from '../model/pageModel'

export type WidgetCategory = 'layout' | 'content' | 'data' | 'input' | 'navigation'

export type PropFieldKind =
  | 'text'
  | 'textarea'
  | 'number'
  | 'boolean'
  | 'select'
  | 'color'
  | 'collection-picker'
  | 'field-picker'
  | 'expression'
  | 'event-list'
  | 'span'
  | 'children'

/** One editable property, consumed by the schema-driven inspector in 2b. */
export interface PropFieldSchema {
  /** Dot-path into props, e.g. 'text' or 'dataView.collection'. */
  key: string
  label: string
  kind: PropFieldKind
  /** For kind:'select'. */
  options?: Array<{ value: string; label: string }>
  /** If true, the inspector wraps this field in BindableField (fx toggle) in 2d. */
  bindable?: boolean
  /** Inspector section grouping. */
  group?: string
  /** field-picker fields depend on a sibling collection-picker value. */
  dependsOnCollection?: boolean
}

/** Props handed to every descriptor's Render — the SAME object in editor and runtime. */
export interface WidgetRenderProps {
  /**
   * **Resolved-node invariant (authoritative — parent §"Widget registry"):** `node.props` is **always
   * fully binding-resolved** before it reaches `Render`. In 2a the resolver is an **identity no-op**;
   * 2d makes it real. Descriptors **must NOT** call `resolveBindings` themselves — the *only* exception
   * is `list`/`repeater` (2d), which re-resolves its children under each per-row `item` scope. So a
   * descriptor never sees a `$bind` marker in `node.props`.
   */
  node: PageComponent
  /** Binding scope; `{}` in 2a (bindings resolve starting 2d). */
  scope: Record<string, unknown>
  mode: 'editor' | 'runtime'
  tenantSlug: string
  /**
   * Render a child node through the shared path (used by container/card and later list/repeater).
   * Signature is `(child, scope?) => ReactNode` **from 2a onward** — the optional `scope` lets a
   * repeater (2d) pass an `item`-augmented scope; omitted ⇒ inherit the current scope.
   * (`scope` is typed `Record<string, unknown>` here; 2d aliases this as `BindingScope`.)
   */
  renderChild: (child: PageComponent, scope?: Record<string, unknown>) => ReactNode
}

export interface WidgetDescriptor {
  type: string
  label: string
  /** Short glyph or lucide icon name; rendered by palette (kept as today's single-char glyphs in 2a). */
  icon: string
  category: WidgetCategory
  /** Defaults applied when a node of this type is added. */
  defaultProps: Record<string, unknown>
  /** Editable props (consumed by 2b inspector). */
  propSchema: PropFieldSchema[]
  /** Whether this widget renders `node.children`. */
  acceptsChildren?: boolean
  /**
   * Events this widget can emit. **Declared in 2a** so the 2b inspector's `kind:'event-list'` field can
   * render one tabbed editor over exactly these handlers, and the 2e runtime can wire only these.
   * Defined here, consumed by 2b (`event-list` field) and 2e (runtime). E.g. `button` → `['onClick']`.
   */
  supportedEvents?: Array<keyof EventHandlers>
  Render: ComponentType<WidgetRenderProps>
}
```

### 3.3 `widgets/registry.ts` — registry API + plugin fallback shim

```ts
import type { ComponentType } from 'react'
import { componentRegistry, type PageComponentProps } from '@/services/componentRegistry'
import type { WidgetDescriptor, WidgetCategory } from './types'

class WidgetRegistry {
  private widgets = new Map<string, WidgetDescriptor>()

  register(descriptor: WidgetDescriptor): void {
    this.widgets.set(descriptor.type, descriptor)
  }

  /**
   * Resolve a descriptor for a type. Resolution order:
   *  1. a registered built-in,
   *  2. a plugin page component (wrapped in a synthetic descriptor — plugins keep working unchanged),
   *  3. the unknown-type default descriptor.
   * Never returns undefined — callers (builder + runtime) stop special-casing missing types.
   */
  get(type: string): WidgetDescriptor {
    const builtin = this.widgets.get(type)
    if (builtin) return builtin

    const PluginComp = componentRegistry.getPageComponent(type)
    if (PluginComp) return wrapPluginComponent(type, PluginComp)

    return defaultDescriptor(type)
  }

  /** True if a real descriptor exists (built-in or plugin) — used to flag "custom" in the canvas. */
  has(type: string): boolean {
    return this.widgets.has(type) || componentRegistry.hasPageComponent(type)
  }

  list(): WidgetDescriptor[] {
    return [...this.widgets.values()]
  }

  listByCategory(category: WidgetCategory): WidgetDescriptor[] {
    return this.list().filter((w) => w.category === category)
  }

  /** Test helper. */
  clear(): void {
    this.widgets.clear()
  }
}

/** Wrap a plugin component (PageComponentProps contract UNCHANGED) in a descriptor. */
function wrapPluginComponent(
  type: string,
  PluginComp: ComponentType<PageComponentProps>,
): WidgetDescriptor {
  return {
    type,
    label: type,
    icon: '◆',
    category: 'content',
    defaultProps: {},
    propSchema: [],
    Render: ({ node, tenantSlug }) =>
      // EXACT call shape preserved from PageTreeRenderer/Canvas/Preview today:
      // <Comp config={node.props} tenantSlug={tenantSlug} />
      <PluginComp config={node.props as Record<string, unknown>} tenantSlug={tenantSlug} />,
  }
}

function defaultDescriptor(type: string): WidgetDescriptor {
  return {
    type,
    label: type,
    icon: '?',
    category: 'content',
    defaultProps: {},
    propSchema: [],
    Render: () => (
      <div className="text-xs text-muted-foreground" data-testid="page-node-unknown">
        Unknown component: {type}
      </div>
    ),
  }
}

export const widgetRegistry = new WidgetRegistry()

/** Eagerly register all built-ins. Imported once at module load (see registerBuiltins.ts). */
export { registerBuiltins } from './builtins'
```

> **The plugin contract `PageComponentProps` (`componentRegistry.ts` ~50) is NOT modified.** The shim
> calls `<PluginComp config={node.props} tenantSlug={tenantSlug} />` — the identical signature used today
> by `PageNodeRenderer` (`React.createElement(Comp, { config: props, tenantSlug })`),
> `Canvas.renderComponent` (`<CustomComponent config={comp.props} />`), and
> `Preview.renderPreviewComponent` (`<CustomComponent config={comp.props} />`). Note today's builder
> sites omit `tenantSlug`; the shim passes it (harmless — it's optional-in-practice and the runtime
> already passes it). This is a strict superset, not a contract change. **`node.props` here is already
> binding-resolved** — `renderNode` resolves before calling `Render` (§3.4), so plugins always receive
> resolved values; in 2a the resolver is identity, so this is identical to today.

### 3.4 `widgets/renderTree.tsx` — the single render path

**Resolved-node invariant (authoritative — parent §"Widget registry").** `renderNode` resolves
`node.props` against the current `scope` **before** handing the node to `descriptor.Render`. So the
`node` a descriptor receives is **always fully binding-resolved** — a descriptor never sees a `$bind`
marker and **must NOT** call `resolveBindings` itself. The single exception is `list`/`repeater` (2d),
which calls `renderChild(child, itemScope)` to re-resolve its subtree per row. In 2a the resolver is an
**identity no-op** (`scope` is `{}`, props pass through untouched), so this is a pure-structure change
that 2d turns real with a one-line swap. `renderChild` takes an **optional `scope`** from 2a onward; when
omitted it inherits the node's scope.

```ts
import type { ReactNode } from 'react'
import type { PageComponent } from '../model/pageModel'
import { widgetRegistry } from './registry'

export interface RenderTreeProps {
  components: PageComponent[]
  mode: 'editor' | 'runtime'
  tenantSlug: string
  /** Binding scope; defaults to {} (bindings resolve from 2d). */
  scope?: Record<string, unknown>
}

/**
 * 2a identity resolver. Returns props untouched. 2d replaces this with real `$bind` resolution against
 * `scope`. Centralizing it here is what makes the resolved-node invariant hold for every descriptor.
 */
function resolveBindings(
  props: PageComponent['props'],
  _scope: Record<string, unknown>,
): PageComponent['props'] {
  return props // 2a: no-op. 2d: walk props, resolve {$bind} against scope.
}

/** Render one node: look up descriptor, resolve props against scope, call descriptor.Render. */
export function renderNode(
  node: PageComponent,
  ctx: { mode: 'editor' | 'runtime'; tenantSlug: string; scope: Record<string, unknown> },
): ReactNode {
  const descriptor = widgetRegistry.get(node.type) // never undefined
  // Resolve props ONCE, here — descriptors receive a fully-resolved node (resolved-node invariant).
  const resolvedNode = { ...node, props: resolveBindings(node.props, ctx.scope) }
  // renderChild accepts an optional scope (used by list/repeater in 2d); omitted ⇒ inherit ctx.scope.
  const renderChild = (child: PageComponent, scope?: Record<string, unknown>) =>
    renderNode(child, scope ? { ...ctx, scope } : ctx)
  return (
    <descriptor.Render
      key={node.id}
      node={resolvedNode}
      scope={ctx.scope}
      mode={ctx.mode}
      tenantSlug={ctx.tenantSlug}
      renderChild={renderChild}
    />
  )
}

export function RenderTree({ components, mode, tenantSlug, scope = {} }: RenderTreeProps): ReactNode {
  return components.map((node) => renderNode(node, { mode, tenantSlug, scope }))
}
```

> **Plugin shim receives RESOLVED props (intentional).** Because resolution happens in `renderNode`
> *before* `descriptor.Render`, the plugin fallback shim (§3.3) hands plugin components fully-resolved
> `config={node.props}` — never `$bind` markers. This is a deliberate part of the **"zero plugin changes"**
> guarantee: plugins authored against the v1 `PageComponentProps` contract keep receiving plain values and
> need no binding-awareness. In 2a the resolver is identity, so this is observably identical to today; from
> 2d on, plugins transparently benefit from binding resolution.

### 3.5 Extended `PageConfig` (still inside the `config` JSON column)

`pageConfig.ts` extends `PageConfig` per parent §"Page-level config (v2)". The new fields are **optional**
and absent on v1 pages — 2a reads/round-trips them but does not yet author or consume them (variables &
data sources flow in 2d, gated on the 1g render contract v2 actually surfacing them).

```ts
import type { PageComponent, PropValue } from './model/pageModel'

export interface PageVariable {
  name: string
  type: 'string' | 'number' | 'boolean' | 'json'
  default?: PropValue
}

export interface PageDataSource {
  name: string
  collection: string
  fields?: string[]
  filter?: Record<string, unknown>
  sort?: string[]
  limit?: number
  mode: 'list' | 'single'
  recordId?: PropValue
}

export interface PageConfig {
  layout?: PageLayout
  components?: PageComponent[]
  variables?: PageVariable[]   // NEW (2a defines; 2d authors/consumes)
  dataSources?: PageDataSource[] // NEW (2a defines; 2d authors/consumes)
  schemaVersion?: 2            // NEW marker; absent ⇒ v1, present ⇒ v2 (set when builder saves under 2c+)
}
```

`readComponents`/`readConfig` are unchanged in behavior. `mergeConfig` is widened to overlay
`variables`/`dataSources`/`schemaVersion` (preserving untouched keys) so future slices can persist them:

```ts
export function mergeConfig(
  existing: PageConfig,
  changes: Partial<Pick<PageConfig, 'components' | 'layout' | 'variables' | 'dataSources' | 'schemaVersion'>>,
): PageConfig {
  const merged: PageConfig = { ...existing }
  for (const k of ['layout', 'components', 'variables', 'dataSources', 'schemaVersion'] as const) {
    if (changes[k] !== undefined) (merged as Record<string, unknown>)[k] = changes[k]
  }
  return merged
}
```

### 3.6 Dependency on 1g (hard edge — 1g ships before 2a, but 2a tolerates a v1 contract)

Per the parent dependency order, **1g ships before 2a** (`1g → 2a`). But the edge is a *sequencing*
edge, not a build-break: **2a is FE-only and tolerates a v1 render contract**. The 1g slice (backend)
bumps the render contract to `version "2.0"` and surfaces sibling `variables`/`dataSources`. 2a's
`RenderTree` accepts a `scope` prop (defaulting `{}`) so that when 2d wires those in, no `RenderTree`
signature changes. **If the runtime is still on the v1 contract, `CustomPage` simply passes `scope={}` and
every node renders with an empty scope — identical to today** (the 2a resolver is identity anyway, so an
empty scope changes nothing). This — together with the runtime feature flag (§5.4) — is what guarantees
`main` is never half-broken regardless of 1g/2a merge order.

---

## 4. DB migrations

**None — FE only.** Everything new nests inside the existing `ui-pages.config` JSON column (no DDL, no
Flyway version consumed; head remains V146). No NATS subject or payload change.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/`.

### 5.1 New files — `model/`

| File | Contents |
|------|----------|
| `pages/PageBuilderPage/model/pageModel.ts` | The v2 types from §3.1: `Binding`, `PropValue`, `isBinding`, `PageAction`, `EventHandlers`, `ResponsiveSpan`, `PageComponent`. Keep a deprecated `ComponentPosition` (`{row,column,width,height}`) export used only by back-compat readers. |
| `pages/PageBuilderPage/model/treeOps.ts` | Pure tree mutations. **Implement now** (used by the refactored builder handlers): `insertNode(tree, node, parentId?, index?)`, `removeNode(tree, id)`, `updateProps(tree, id, patch)`. **Stub now, finish in 2c** (canvas dnd): `moveNode(tree, id, toParentId, index)`, `setSpan(tree, id, span)` — ship as typed **no-op stubs that return the input tree unchanged** (so the surface exists and is import-stable) — they do **not** throw. **No code path reaches them before 2c**: there is no reorder/resize/span UI yet, and 2b's inspector mutates props via `updateProps` only. All return new trees (immutable). |

### 5.2 New files — `widgets/`

| File | Contents |
|------|----------|
| `pages/PageBuilderPage/widgets/types.ts` | §3.2 — `WidgetDescriptor`, `PropFieldSchema`, `WidgetRenderProps`, `WidgetCategory`, `PropFieldKind`. |
| `pages/PageBuilderPage/widgets/registry.ts` | §3.3 — `widgetRegistry` singleton + `wrapPluginComponent` + `defaultDescriptor`. |
| `pages/PageBuilderPage/widgets/renderTree.tsx` | §3.4 — `RenderTree` + `renderNode`. |
| `pages/PageBuilderPage/widgets/builtins/index.ts` | `registerBuiltins()` — calls `widgetRegistry.register(...)` for all 8. Imported once (from `App.tsx` bootstrap or a `widgets/registerBuiltins.ts` side-effect import in `PageBuilderPage.tsx` **and** `CustomPage.tsx`, so both surfaces populate the registry). |
| `pages/PageBuilderPage/widgets/builtins/heading.tsx` | §2.1 — honors `level`, `props.text`. `data-testid="page-node-heading"`. |
| `pages/PageBuilderPage/widgets/builtins/text.tsx` | Renders `props.content` (falling back to `props.text`, matching the runtime's existing `asString(props.content, asString(props.text))`). `data-testid="page-node-text"`. |
| `pages/PageBuilderPage/widgets/builtins/button.tsx` | Label `props.label`, variant `props.variant` (primary/secondary/danger styling from preview), and `props.href` → `<a>` else `<button>` (runtime behavior). `data-testid="page-node-button"`. Declares **`supportedEvents:['onClick']`** (consumed by 2b's `event-list` field + 2e runtime; no event wiring in 2a). |
| `pages/PageBuilderPage/widgets/builtins/image.tsx` | `props.src`/`props.alt`; placeholder box when no `src` (editor) / `<img>` (both). `data-testid="page-node-image"`. |
| `pages/PageBuilderPage/widgets/builtins/card.tsx` | `acceptsChildren:true`; `<div className="rounded-lg border …">{children.map(renderChild)}</div>`. `data-testid="page-node-card"`. |
| `pages/PageBuilderPage/widgets/builtins/container.tsx` | `acceptsChildren:true`; renders `node.children` via `renderChild`, placeholder when empty (editor). `data-testid="page-node-container"`. |
| `pages/PageBuilderPage/widgets/builtins/dataTableNode.tsx` | **Moved verbatim** from `PageTreeRenderer.tsx`: `DataTableNode`, `readDataView`, `asString`, `asStringList`, `MAX_TABLE_ROWS`, `DataViewConfig`. Same `apiClient.getList('/api/{collection}?page[size]=…')` fetch (Cerbos/FLS preserved). `data-testid="page-node-table"`. |
| `pages/PageBuilderPage/widgets/builtins/table.tsx` | §2.2 — `Render` keyed on `mode`: `editor` → placeholder box (matching today's `Preview`/`Canvas`), `runtime` → `<DataTableNode>`. `propSchema` for the `dataView` fields. |
| `pages/PageBuilderPage/widgets/builtins/formNode.tsx` | **Moved verbatim** from `PageTreeRenderer.tsx`: `FormNode` (the `apiClient.postResource('/api/{collection}', values)` create path). `data-testid="page-node-form"`/`form-submit`/`form-success`/`form-error`. |
| `pages/PageBuilderPage/widgets/builtins/form.tsx` | `category: 'input'` (**not `data`** — parent §"Widget registry"; this makes the `input` palette category non-empty from 2a). `Render` keyed on `mode`: `editor` → placeholder box, `runtime` → `<FormNode>`. `propSchema` for collection/fields. |

### 5.3 Refactor — `pages/PageBuilderPage/PageBuilderPage.tsx`

Delete the 4 builder switch sites; wire palette/preview/canvas to the registry + `RenderTree`. Key edits:

**(a) Replace the local `PageComponent`/`ComponentPosition` interfaces** with re-exports so external
importers (`pageConfig.ts`, tests, SDK) keep working:

```ts
// BEFORE (~61–77): full interface definitions of PageComponent + ComponentPosition
// AFTER:
export type { PageComponent } from './model/pageModel'
export type { ComponentPosition } from './model/pageModel' // deprecated, back-compat only
```

**(b) `AVAILABLE_COMPONENTS` (~101)** — keep the array in 2a (palette becomes registry-driven in 2b), but
source labels/icons from the registry to start the de-dup:

```ts
// AFTER: derive from the registry instead of a second hand-maintained list
const AVAILABLE_COMPONENTS = widgetRegistry.list().map((w) => ({
  type: w.type, label: w.label, icon: w.icon,
}))
```

> **i18n (parent §i18n).** Any **new** user-facing string introduced by 2a — palette item labels and the
> new `input` **palette-category** label (and any other category headers the palette surfaces) — goes
> through `useI18n`/`t()` with `builder.*` keys; never hardcode English. The builder is already fully
> `useI18n`-driven; descriptor `label`/category strings are rendered through `t()` at the palette call
> site (the descriptor stores the i18n key or a stable label that the palette resolves via `t()`).

**(c) `Preview.renderPreviewComponent` (~505–632)** — delete the entire `switch` (incl. the plugin
`getPageComponent` special-case and `default:`). Replace the preview body:

```tsx
// BEFORE: components.map(renderPreviewComponent)  with a 110-line switch
// AFTER:
<div className="flex flex-col gap-4">
  <RenderTree components={components} mode="editor" tenantSlug={tenantSlug} />
</div>
```

The `getPageComponent` prop on `Preview`/`Canvas` is **removed** — plugin resolution now happens inside
`widgetRegistry.get()`. (Drop the `usePlugins()` `getPageComponent` wiring at the call sites ~1611/1622.)

**(d) `Canvas.renderComponent` (~734–842)** — keep the selection/delete chrome (outline, ×, custom badge),
but replace the inner per-type preview block (~798–838 and the `CustomComponent` branch) with a single
`RenderTree`/`renderNode` call for the node's body:

```tsx
// BEFORE: isCustomComponent ? <CustomComponent .../> : <>{comp.type === 'heading' && …}</>
// AFTER:
<div className="text-sm text-foreground">{renderNode(comp, { mode: 'editor', tenantSlug, scope: {} })}</div>
```

The "Custom" badge now keys off `widgetRegistry.has(comp.type) && !isBuiltinType(comp.type)` (or simply
`componentRegistry.hasPageComponent(comp.type)` — unchanged semantics).

**(e) `handleAddComponent` (~1374)** — apply `defaultProps` from the descriptor and stop writing `position`:

```ts
// AFTER:
const descriptor = widgetRegistry.get(componentType)
const newComponent: PageComponent = {
  id: generateId(),
  type: componentType,
  props: { ...descriptor.defaultProps },
}
```

**(f)** `PropertyPanel` (~233–483) is **left as-is in 2a** (the parent sequencing keeps the hardcoded
inspector until 2b deletes it and replaces it with the `propSchema` loop). Add a `// TODO(2b): replace with
schema-driven Inspector looping over widgetRegistry.get(type).propSchema` marker.

**(g)** Add `tenantSlug` to the editor: read it from the existing route/`useParams` or `usePlugins`/auth
context the page already has, threaded into `Preview`/`Canvas` (builder runs under `/:tenantSlug/setup/...`).
If unavailable in 2a, pass `''` — editor preview built-ins don't depend on `tenantSlug`; only plugin
components receive it (and they handle empty slugs today).

### 5.4 Refactor — `pages/app/CustomPage/PageTreeRenderer.tsx` (flag-gated, legacy kept)

The runtime flip is the highest-blast-radius change (every custom page, all tenants, one merge), so it is
**gated behind a system feature flag with a fallback to the legacy `PageNodeRenderer`** (parent §"Rollout
& rollback"). `DataTableNode`/`FormNode`/helpers are **copied** into `widgets/builtins/` (the descriptors'
verbatim source); the **legacy `PageNodeRenderer` switch + its `DataTableNode`/`FormNode` stay in this
file** until 2a soaks behind the flag, so a flag flip reverts cleanly with zero redeploy. `PageTreeRenderer`
becomes a **flag selector**:

```tsx
import { RenderTree } from '@/pages/PageBuilderPage/widgets/renderTree'
import { registerBuiltins } from '@/pages/PageBuilderPage/widgets/builtins'
import type { PageComponent } from '@/pages/PageBuilderPage/model/pageModel'
import { useFeatureFlag } from '@/hooks/useFeatureFlag' // existing kelta.config.feature infra

registerBuiltins() // idempotent; safe to call from both surfaces

export type PageNode = PageComponent // back-compat alias for CustomPage's import

export function PageTreeRenderer({
  components,
  tenantSlug,
}: {
  components: PageNode[]
  tenantSlug: string
}): React.ReactElement {
  // Flag OFF (default until soak) → legacy renderer; flag ON → shared RenderTree.
  const useRenderTree = useFeatureFlag('page-builder.render-tree-v2')
  if (!components || components.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground" data-testid="page-empty">
        This page has no content yet.
      </p>
    )
  }
  if (!useRenderTree) {
    return <LegacyPageTreeRenderer components={components} tenantSlug={tenantSlug} /> // kept verbatim
  }
  return (
    <div className="flex flex-col gap-4" data-testid="page-tree">
      <RenderTree components={components} mode="runtime" tenantSlug={tenantSlug} />
    </div>
  )
}
```

`LegacyPageTreeRenderer` is **today's `PageNodeRenderer` switch + `DataTableNode`/`FormNode`/`readDataView`/
`renderChildren`, kept in place** (renamed only). It is **deleted in a follow-up cleanup once 2a has soaked
behind the flag** — not in this PR. `CustomPage.tsx` is unchanged (still imports `PageTreeRenderer` +
`PageNode`) — the `PageNode` alias preserves its `import { PageTreeRenderer, type PageNode }`.

> **Editor preview is not flag-gated.** The Phase-B editor flip (`PageBuilderPage.tsx` preview/canvas →
> `RenderTree`, §5.3) is internal to the builder and covered by the golden snapshot; only the **runtime**
> path (published end-user pages) rides the flag, because that is where a regression reaches real users.

> **De-dup guarantee (explicit):** once the runtime flag is **on**, the editor preview
> (`RenderTree mode="editor"` in `PageBuilderPage.tsx`) and the runtime (`RenderTree mode="runtime"` in
> `PageTreeRenderer.tsx`) call the **same `renderNode` → same descriptor `Render`**. There is exactly
> **one** module that knows how each widget renders (the kept `LegacyPageTreeRenderer` is dead code behind
> the flag, removed after soak). The only intentional per-surface difference is the `mode` flag the
> `table`/`form` descriptors read to choose placeholder (editor) vs live data fetch (runtime) — which
> reproduces today's behavior exactly.

### 5.5 Refactor — `pages/PageBuilderPage/pageConfig.ts`

- Extend `PageConfig` with `variables?`/`dataSources?`/`schemaVersion?` (§3.5).
- Widen `mergeConfig` to overlay the new keys (§3.5).
- `readComponents`/`readConfig` unchanged. Update the import of `PageComponent` to come from
  `./model/pageModel` (it currently imports from `./PageBuilderPage`, which now re-exports the model type
  — either works; prefer the model source).

### 5.6 Registry bootstrap

`registerBuiltins()` must run before either surface renders. Call it as a side-effect:
- in `widgets/builtins/index.ts`, export `registerBuiltins()` (idempotent — guards against double-register).
- invoke it at module scope in both `PageBuilderPage.tsx` and `PageTreeRenderer.tsx` (or once in
  `App.tsx`). Idempotency: `register()` overwrites by `type`, and `registerBuiltins` is wrapped in a
  `let done = false` guard.

---

## 6. Test plan

Vitest + Testing Library + MSW, matching the existing idiom in `PageBuilderPage.test.tsx` (MSW `server`
from `vitest.setup`, `createTestWrapper`) and `pageConfig.test.ts` (pure-function tests).

The suite mirrors the **two-phase split** (§1): the golden snapshot is the gate for Phase A; the flag
fallback is the gate for Phase B.

### 6.0 New — `widgets/builtins.golden.test.tsx` (the Phase-A parity gate — BLOCKER for rollout)

The safety net for the whole refactor. **Capture a golden snapshot BEFORE the refactor:** render all 8
built-ins from a single fixed JSON fixture (`__fixtures__/golden-page.json` — one node of each type:
`heading`/`text`/`button`/`image`/`form`/`table`/`card`/`container`, plus a plugin node and an
unknown-type node) through **today's** render path and commit the serialized DOM as the snapshot baseline
(Vitest `toMatchSnapshot`, or a Playwright component-DOM snapshot if richer fidelity is needed).

- **Phase A** introduces `widgets/*` behind the registry; this test re-renders the same fixture through
  `RenderTree` (both `mode`) and asserts it matches the pre-refactor snapshot **byte-for-byte**, with
  **exactly one reviewed diff**: the runtime `heading` now emits `<h1>`/`<h3>`/… per `level` instead of a
  hardcoded `<h2>`. That diff is approved in the PR (snapshot updated deliberately, called out in the
  description) — it is **not** silent. Every other built-in is unchanged.
- The fixture and snapshot live with the test so 2b–2g re-run it unchanged to prove later slices don't
  regress built-in rendering (2d explicitly re-runs it to prove the now-real resolver is identity on
  literal props).

### 6.0b New — `PageTreeRenderer.flag.test.tsx` (Phase-B fallback gate)

Asserts the runtime flip is reversible: with the system feature flag **on**, `CustomPage`/runtime renders
via `RenderTree`; with it **off**, it falls back to the **legacy `PageNodeRenderer`** and produces the
pre-2a output (same fixture/snapshot as today, including the legacy hardcoded-`h2` heading). Mock the
feature-flag hook both ways. This proves the rollback trigger (§8) actually rolls back.

### 6.1 New — `widgets/registry.test.ts`

- `register` + `get` returns the registered built-in descriptor by type.
- `list` / `listByCategory` return the registered set filtered by `category`.
- **Plugin fallback shim:** `componentRegistry.registerPageComponent('acme', Stub)`; `widgetRegistry.get('acme')`
  returns a descriptor whose `Render` mounts `Stub` with `config={node.props}` + `tenantSlug` (assert the
  `PageComponentProps` shape is passed unchanged). `has('acme') === true`.
- **Unknown-type default descriptor:** `get('nope')` returns a descriptor rendering
  `data-testid="page-node-unknown"` with text `Unknown component: nope`; `has('nope') === false`.
- `clear()` resets built-ins (test isolation).

### 6.2 New — `widgets/renderTree.test.tsx`

- **Editor vs runtime identical:** render the §2.3 tree (minus data widgets) with `mode="editor"` and
  `mode="runtime"`; assert the produced DOM for `heading`/`text`/`button`/`image`/`card`/`container` is
  equivalent (snapshot or per-node `data-testid` assertions).
- **Data widgets mock `apiClient`:** mock `useApi().apiClient.getList` (MSW `/api/orders`) and assert
  `table` in `runtime` mode fetches and renders rows; in `editor` mode renders the placeholder (no fetch).
- `form` in `runtime` mode renders one input per `dataView.fields`, submits via `postResource` (MSW assert
  the POST body), shows `form-success`.
- Plugin node inside the tree renders through the shim.

### 6.3 New — `widgets/builtins.parity.test.tsx`

One test per migrated built-in asserting it renders as before (same `data-testid` + key classes/text):
`heading` (honors `level` — the one intentional runtime change, asserted on the new output),
`text`, `button` (href→`<a>`, no-href→`<button>`), `image` (src + placeholder), `card`/`container`
(children via `renderChild`), `table` (placeholder/editor + fetch/runtime), `form` (placeholder + create).

### 6.4 New — `model/treeOps.test.ts`

`insertNode`/`removeNode`/`updateProps` immutability + correctness (append, nested insert by `parentId`,
remove by id at any depth, prop patch merge). `moveNode`/`setSpan` assert they are **no-op stubs that
return the input tree unchanged** (not throwing) — so 2c can flip them from no-op to real behavior, and a
test documents that 2a never relies on them.

### 6.5 Extend — `PageBuilderPage.test.tsx`

- Palette still renders the 8 items (`palette-item-heading` … now sourced from the registry).
- Adding a component applies `defaultProps` (e.g. add `heading` → preview shows "Heading").
- Preview overlay renders via `RenderTree` (existing preview tests keep passing — same `data-testid`s).
- A plugin page component registered before mount still shows the "Custom" badge in the canvas and renders
  in preview (port the existing custom-component tests to the shim).

### 6.6 Extend — `pageConfig.test.ts`

- `mergeConfig` overlays `variables`/`dataSources`/`schemaVersion` while preserving `components`/`layout`.
- `readComponents`/`readConfig` unchanged (existing tests stay green; the `comp()` helper drops `position`
  to match the v2 model, or keeps it to prove `position` is tolerated on read).

### 6.7 e2e

Playwright e2e is **post-deploy only** (project convention) — the parent spec's positive page-render e2e
runs once deployed. No new e2e file in this slice; the refactor is covered by the Vitest golden-snapshot +
parity suite (§6.0). Because the runtime flip rides a **feature flag** (default off until soak), the
post-deploy e2e first runs against the legacy path, then is re-run with the flag flipped on as part of the
soak before the legacy renderer is removed.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` (line ~48, "Page builder / screen builder" row) | Add: "slice 2a — **widget descriptor registry + shared `RenderTree`**: the 5 hardcoded per-type switch sites (`AVAILABLE_COMPONENTS`, `PropertyPanel`, `Canvas.renderComponent`, `Preview.renderPreviewComponent`, `PageNodeRenderer`) collapse to one `widgetRegistry` (`widgets/registry.ts`) + one shared render module (`widgets/renderTree.tsx`) used by both builder preview and runtime; 8 built-ins migrated to descriptors at parity; plugin `componentRegistry` folded in via a fallback shim (zero plugin changes); `model/pageModel.ts` defines the v2 `PageComponent`/`Binding`/`PropValue` types; runtime `heading` now honors `level` (was hardcoded `h2`) — a reviewed golden-snapshot diff; the runtime flip ships **behind a feature flag** with fallback to the legacy `PageNodeRenderer` until soak." Keep the gap column ("typed/validated form fields", "per-page Cerbos authz") unchanged — those move out in 2f/1h. |
| `.claude/docs/playbooks.md` | **Add a new recipe "Add a page component / widget"** (there is none today; recipes 3 and 6 are the closest). The registration step changes from *editing five switches* to *one descriptor*: create `widgets/builtins/<type>.tsx` exporting a `WidgetDescriptor`, register it in `widgets/builtins/index.ts` `registerBuiltins()`. Note plugins still use `componentRegistry.registerPageComponent(...)` and are auto-wrapped by the registry shim — no builtin needed. |
| `.claude/docs/conventions.md` | If/when it documents page components or the `componentRegistry`: add the **widget descriptor** as the canonical extension point (descriptor over switch), and the rule that editor preview + runtime must go through `RenderTree` (never re-implement per-type JSX). Document the v2 `config` shape stub (`variables`/`dataSources`/`schemaVersion`, `$bind` marker) as defined-but-not-yet-authored, deferring the full contract write-up to 2d. |
| `.claude/docs/concerns.md` ("Large files needing decomposition" table, ~23) | Update the `PageBuilderPage.tsx` situation: it is **not** currently in that table; note in the same PR that this slice shrinks it from ~1832 lines by extracting the render path (preview/canvas body, the 4 switch sites) into `widgets/*`. Once 2b extracts the inspector, add a tracking note if it's still oversized. **Also note the temporary `LegacyPageTreeRenderer` kept behind the `page-builder.render-tree-v2` feature flag** (dead code until the post-soak cleanup removes it) so it isn't mistaken for live duplication. |

Per CLAUDE.md Rule 6 these doc edits ship **in the same PR** as the code.

---

## 8. Risks & open questions

- **`PageBuilderPage.tsx` size (1832 lines).** It is large but **not** currently listed in
  `concerns.md`'s "Large files" table (which tops out at `SystemCollectionDefinitions.java` @ 1,434).
  This refactor is net-shrinking: deleting the 4 switch sites and the per-type preview JSX (~300+ lines)
  and moving render logic to `widgets/*`. 2b (inspector extraction) shrinks it further. **Mitigation:** do
  the extraction widget-by-widget with the parity suite green at each step; don't combine with a visual
  redesign.
- **Plugin fallback shim must NOT change `PageComponentProps`.** The risk is "improving" the contract while
  wrapping. The shim's `Render` calls `<PluginComp config={node.props} tenantSlug={tenantSlug} />` —
  exactly the three existing call shapes (`PageNodeRenderer`/`Canvas`/`Preview`). `componentRegistry.ts`
  and `types/plugin.ts` are **untouched**. A registry test asserts the exact props handed to a stub plugin.
  The only superset is that builder sites now also pass `tenantSlug` (previously omitted) — verify no
  plugin in the repo asserts `tenantSlug` is absent (it's an optional-in-practice nav hint).
- **Runtime `table`/`form` data-fetch behavior must be preserved verbatim.** `DataTableNode`/`FormNode`
  are **moved, not rewritten** — same `apiClient.getList`/`postResource` calls so the gateway+worker keep
  enforcing Cerbos/FLS (denied fields stripped server-side). The `mode` flag preserves the
  editor-placeholder vs runtime-fetch split exactly. **Open question:** should the editor preview *also*
  fetch live data (it doesn't today)? Decision: **no** in 2a (keep parity); revisit in 2d when bindings/data
  sources exist and a live preview is meaningful.
- **Intentional behavior change: runtime `heading` now honors `level`.** Today `PageNodeRenderer` renders
  every `heading` as `<h2 className="text-2xl …">`; the editor preview honors `level`. Unifying on the
  descriptor makes the runtime honor `level` too — a strict improvement and the whole point of de-dup, but
  it *is* a render-output change for already-published pages with non-`h2` headings. It surfaces as the
  **one reviewed diff in the golden snapshot** (§6.0) — not a silent change — and is called out in the PR +
  `status.md`. **Open question for the user:** acceptable, or should we freeze runtime headings at `h2` for
  back-compat? (Recommended: take the fix.)
- **Rollout & rollback (BLOCKER for safe rollout).** The runtime flip touches **every** custom page across
  all tenants in one merge. Mitigations, all required: (1) the slice is **split** — Phase A introduces
  `widgets/*`/`RenderTree`/builtins behind the registry with the **golden snapshot** captured before the
  refactor as the gate; Phase B flips the surfaces. (2) The **runtime** flip is gated behind a **system
  feature flag** (`page-builder.render-tree-v2`, default off) with fallback to the legacy `PageNodeRenderer`,
  which is **kept in the tree until 2a soaks** (deleted in a follow-up cleanup). (3) **Rollback trigger:** a
  spike in page-render error rate or in unknown-widget fallback renders → flip the flag back to the legacy
  renderer (instant, no redeploy). A `PageTreeRenderer.flag.test.tsx` proves both branches render and the
  fallback reproduces pre-2a output.
- **Registry bootstrap ordering.** Both surfaces must call `registerBuiltins()` before render.
  `registerBuiltins` is idempotent (guard flag); calling it at module scope in `PageBuilderPage.tsx` and
  `PageTreeRenderer.tsx` (or once in `App.tsx`) covers both. Tests must `clear()` + `registerBuiltins()` in
  `beforeEach` for isolation.
- **`PageComponent` interface relocation.** Moving `PageComponent` out of `PageBuilderPage.tsx` into
  `model/pageModel.ts` (and re-exporting) touches every importer (`pageConfig.ts`, tests, possibly
  `@kelta/sdk` typings via `as unknown as import('@kelta/sdk').UIPage` casts). The re-export keeps the
  public import path stable; verify the SDK `UIPage` cast in the mutation handlers still typechecks (it's
  already a double-cast, so it's tolerant).
- **Sequencing — 2a is the hard prerequisite.** 2b (inspector/palette from `propSchema`/registry), 2c
  (canvas/treeOps/span), 2d (bindings/scope), 2e (events), 2f (typed forms) all build on `widgets/*`,
  `model/*`, and `RenderTree`. Land 2a first (and ideally 1g for the contract siblings). Nothing else in the
  parent plan should start until the registry + `RenderTree` are merged and green.
