# 2c — Layout engine + dnd-kit canvas

> **Slice:** 2c (FE — the **Layout & grid** axis). **Parent:**
> [`../page-builder-parity.md`](../page-builder-parity.md). This child spec **extends, never
> contradicts** the parent's [The shared model](../page-builder-parity.md#the-shared-model),
> [Inspector + canvas](../page-builder-parity.md#inspector--canvas), and the **Key architecture
> decision: DnD = `@dnd-kit`** (parent §"Key architecture decisions"). It **consumes** the contracts
> defined in [`2a-widget-registry.md`](./2a-widget-registry.md): `WidgetDescriptor` / `PropFieldSchema`
> / `WidgetRenderProps` (`widgets/types.ts`), the `widgetRegistry` singleton + plugin shim, the shared
> `RenderTree`/`renderNode` path, and the v2 `PageComponent` (`id`/`type`/`props`/`events?`/`span?`/
> `children?`) defined in `model/pageModel.ts`. 2a left `treeOps.ts` with `insertNode`/`removeNode`/
> `updateProps` implemented and `moveNode`/`setSpan` as `notImplemented` stubs — **2c finishes them**
> and adds `model/migrate.ts`.
>
> Source-verified against the codebase on 2026-06-22 (Flyway head V146; this slice adds **no
> migration**). If code and this doc disagree, trust the code and fix this doc.

---

## 1. Goal & scope

### What this slice delivers

Replaces the **vertical-stack-only** canvas with a real **layout engine**. Today the builder canvas
(`Canvas` in `PageBuilderPage.tsx` ~698) is a flat `flex flex-col gap-2` list; drops **always append to
root** (`handleAddComponent` ~1374 pushes onto the top-level array), there is no nesting beyond what a
hand-edited tree provides, and the stored `position` (`ComponentPosition {row, column, width, height}`,
~72) is **written but completely ignored** by both the canvas and the runtime renderer. Drag is native
HTML5 (`onDragStart`/`onDragOver`/`onDrop` + `dataTransfer.getData('componentType')`).

This slice ships:

1. **Four new layout widgets** (descriptors registered in `widgets/builtins/`):
   - `grid` — a 12-column CSS-grid container; children are placed by their per-child `span`.
   - `row` — a horizontal flex/grid row; children flow left-to-right and carry `span`.
   - `column` — a vertical container (a grid cell); holds a stack of children, itself spanning N of 12.
   - `divider` — a leaf horizontal rule (no children, no span semantics of its own).
2. **Per-child responsive `span`** — `ResponsiveSpan {base, sm?, md?, lg?}` (each `1..12`), already on the
   v2 `PageComponent` from 2a. Mapped to Tailwind `col-span-{n}` + breakpoint prefixes
   (`sm:col-span-{n}` …). Authored by dragging a resize handle that **snaps to the 12-col grid** (no pixel
   coordinates are ever stored — `span` is the only layout state).
3. **`@dnd-kit` canvas** — a single `DndContext` (PointerSensor + KeyboardSensor for a11y). Palette items
   become **draggable sources**; **every container** (`grid`/`row`/`column`/`card`/`container` + the root)
   is a **droppable** with its own per-container `SortableContext` (strategy: `rectSortingStrategy`,
   collision: `closestCenter`) so children reorder within their parent and move **between** containers.
4. **`SelectableNode`** wrapper — selection outline, a drag handle (`GripVertical`), and (for children of a
   `grid`/`row`) **resize handles** on the right edge that drive `setSpan`.
5. **`treeOps` completion** — `moveNode` + `setSpan` implemented (2a shipped `insertNode`/`removeNode`/
   `updateProps`). Every drop/move/resize routes through `treeOps`, **fixing "drop only appends to root"**:
   a drop targets a specific container + index.
6. **`model/migrate.ts`** — converts a legacy tree (nodes carrying `position {row,column,width,height}`)
   into a `grid`-rooted container/grid tree with `span` (`width` → `span.base`, grouped by `row`). Run once
   on load when a page lacks `schemaVersion: 2`.
7. **`PageBuilderPage.tsx` Canvas swap** — the native-DnD `Canvas` is replaced by the new
   `canvas/Canvas.tsx`; `handleAddComponent` and the new move/resize handlers call `treeOps`.
8. **The canonical `handleSavePage` / `mergeConfig` rewrite** — **2c OWNS this** (parent
   §"Save & persistence (v2 round-trip)"). 2c is the first slice that must persist a page-level sibling
   (`schemaVersion: 2`, stamped on migration), so it widens `mergeConfig` to overlay
   `variables`/`dataSources`/`access`/`schemaVersion` **and** changes the save call site to pass the full
   set. Widening `mergeConfig`'s accepted keys is **useless unless the call passes them** — this is the
   exact silent-drop bug class the parent flags. See §5.5 + the new **§5.9 save-path before→after**.
9. **The unsaved-changes guard** — 2c owns resolving the existing `handleBackToList` TODO
   (`// Could show a confirmation dialog here`) and a `beforeunload` guard (parent §"Parity gaps NOT
   covered" puts it in 2c scope). See §5.10.
10. **Deprecating the create-form layout select** — the `layoutType` select (single/sidebar/grid →
    `config.layout.type`) is orphaned by the widget-based layout; 2c documents `config.layout` as inert
    legacy. See §5.11.
11. **`@dnd-kit/core` + `@dnd-kit/sortable`** added to `kelta-ui/app` (the parent-mandated single new dep,
    **scoped to the page canvas only**).

### What this slice explicitly does NOT do

| Out of scope | Lands in |
|--------------|----------|
| Schema-driven inspector / palette-from-registry (the palette stays the 2a registry-derived array; the inspector still edits props) | 2b |
| Bindings, `resolveBindings`, `interpolate`, data sources, `field-value`/`list`/`repeater` | 2d |
| Events/actions authoring + action runtime (`onClick` etc.) | 2e |
| Typed form widgets (`dropdown`/`datepicker`/`lookup`/…) replacing text-only inputs | 2f |
| `chart`/`tabs`/`nav`/`icon`/`link` widgets | 2g |
| Migrating `PageLayoutsPage`/`MenuBuilderPage`/`FlowDesignerPage` off native DnD — **out of scope by parent decree** (they stay on native HTML5 DnD; only the *page canvas* adopts `@dnd-kit`) | — |
| Any backend/render-contract change | 1g (already) |

### Parent-doc sections this slice conforms to

- **The shared model** — uses `PageComponent`/`ResponsiveSpan` as defined; `treeOps`
  (`insertNode`/`moveNode`/`removeNode`/`updateProps`/`setSpan`) and `migrate` are the parent-named modules.
- **Inspector + canvas** — `canvas/Canvas.tsx` + `canvas/dnd/*`, `DndContext` (PointerSensor +
  KeyboardSensor), palette draggable sources, per-container `SortableContext` (`closestCenter`), drops/moves
  → `treeOps`, grid-span resize snapping to the 12-col grid (no pixel coords), `SelectableNode` wrapper.
- **Key architecture decision: DnD = `@dnd-kit/core` + `@dnd-kit/sortable`** (one new dep, canvas only);
  `PageLayoutsPage` stays on native DnD.

### Acceptance criteria

- Dragging a palette item onto a **column/row/grid/card/container** inserts the new node **inside that
  container at the drop index** — not at root. Dropping on empty canvas appends to root (back-compat).
- Dragging an existing node **between** containers moves it (one `moveNode`); dragging within a container
  reorders it (one `moveNode` with same parent, new index). No node is ever duplicated or lost.
- A child of a `grid`/`row` shows a resize handle; dragging it changes the child's `span.base` in **integer
  12-col steps** (snap), persisted as `span`. No `{row,column,width,height}` pixel/position data is written.
- `span` renders as Tailwind `col-span-{base}` plus `sm:`/`md:`/`lg:` prefixes when those keys are set; a
  `grid` is `grid grid-cols-12`.
- A legacy page (nodes with `position`, no `schemaVersion`) loads via `migrate()` into a `grid > column >
  widget` tree and renders identically-or-better; re-saving stamps `schemaVersion: 2` and drops `position`.
- Keyboard DnD works: focus a node's drag handle, `Space` to lift, arrow keys to move between
  positions/containers, `Space` to drop, `Esc` to cancel (dnd-kit `KeyboardSensor` defaults).
- Editor preview (`RenderTree mode="editor"`) and runtime (`RenderTree mode="runtime"`) render the same
  `grid`/`row`/`column`/`divider` + `span` identically (one descriptor `Render` each — the 2a de-dup
  guarantee holds).
- `/verify` green (lint + typecheck + `format:check` + `test:coverage` ≥ existing kelta-ui gate).

---

## 2. UI samples

### 2.1 Canvas with the 12-col grid (after 2c)

```
┌─ Palette ───────┐ ┌─ Canvas (DndContext root droppable) ───────────────────────┐ ┌─ Inspector ─┐
│ ▦ Grid   ▭ Row  │ │  grid · grid-cols-12                                        │ │  column     │
│ ▯ Column ─ Divid│ │  ┌───────────────────────────────────────────────────────┐ │ │  span.base 6│
│ H Heading T Text│ │  │ ░ column (span 6)        ⠿  │ ░ column (span 6)     ⠿  │ │ │  span.md  4 │
│ B Button I Image│ │  │  ┌──────────────────────┐    │  ┌────────────────────┐  │ │ │  …          │
│ F Form  ⊞ Table │ │  │  │ Heading "Orders"  ⠿  │    │  │ Table (orders)  ⠿  │◀▶│ │ │             │
│ ▢ Card  ◻ Cont. │ │  │  └──────────────────────┘    │  └────────────────────┘  │ │ │             │
│                 │ │  │  ┌──────────────────────┐    │       resize handle ──▲  │ │ │             │
│  (drag a tile   │ │  │  │ Text "Recent…"    ⠿  │    │       (snaps 1..12)      │ │ │             │
│   into a column)│ │  │  └──────────────────────┘    │                          │ │ │             │
│                 │ │  └───────────────────────────────────────────────────────┘ │ │             │
└─────────────────┘ └────────────────────────────────────────────────────────────┘ └─────────────┘
   ⠿ = drag handle (GripVertical)   ◀▶ / ▲ = grid-span resize handle (right edge)
   ░ = column drop-zone tint        selected node = primary outline + ring
```

### 2.2 Dragging a widget from the palette into a column

```
1. Pointer down on palette tile "Heading"        → useDraggable id="palette:heading"
                                                    data = { source:'palette', widgetType:'heading' }
2. Drag over the left column                      → that column's droppable id="container:col-1" lights
                                                    (closestCenter picks the nearest sortable slot)
   ┌ column (span 6) ──────────────┐
   │ Heading "Orders"              │
   │ ▒▒▒▒ insert here (index 1) ▒▒▒│ ← SortableContext shows the gap at the drop index
   │ Text "Recent…"                │
   └───────────────────────────────┘
3. Drop                                           → onDragEnd:
                                                    insertNode(tree, newNode('heading'),
                                                               parentId='col-1', index=1)
                                                    (NOT appended to root — the 2c fix)
```

`DragOverlay` renders a ghost of the dragged tile/node during the drag (dnd-kit `DragOverlay`), so the
original stays in place until drop.

### 2.3 Reorder within a row (between-container move too)

```
Before (row, 3 children):     drag "C" up over "A":      After onDragEnd:
  ┌ row ───────────────┐        insertion line above A     ┌ row ───────────────┐
  │ [A] [B] [C]        │   →    ┌ row ──────────────┐   →   │ [C] [A] [B]        │
  └────────────────────┘        │ |[A] [B] [C]      │       └────────────────────┘
                                └────────────────────┘    moveNode(tree, 'C', parentId='row-1', index=0)

Between containers (column-1 → column-2):
  moveNode(tree, 'C', toParentId='col-2', index=0)   (removeNode under col-1, insert under col-2)
```

`active.data.current = { source:'node', nodeId, parentId }`,
`over.data.current = { container: <droppableId>, index: <slotIndex> }` — `onDragEnd` derives a single
`moveNode` (or `insertNode` for palette sources). Same-parent + same-index is a **no-op** (guarded).

### 2.4 Resize handle snapping spans

```
column (span.base = 6) — drag the right-edge handle leftward:

  grid is 12 cols → each col ≈ canvasWidth/12 px.
  delta_px accumulated → delta_cols = round(delta_px / colWidth)   ← SNAP to integer cols
  newBase = clamp(6 + delta_cols, 1, 12)

  drag left ~2 cols ─────────────►  span.base 6 → 4    setSpan(tree, 'col-1', { ...span, base: 4 })

  ┌ col (6) ──────────┐ resize    ┌ col (4) ──┐
  │                 ◀▶│  ───────► │         ◀▶│      grid auto-reflows; sibling reflows to fill.
  └───────────────────┘           └───────────┘      Only `span` is stored — never px/row/column.
```

The handle reads the **container's measured width** (via the dnd `rect` or a `ResizeObserver` ref) only to
compute the **column width for snapping** — the measured pixels are never persisted.

### 2.5 Keyboard-drag a11y

```
Tab → focus a node's drag handle (button, aria-roledescription="draggable", tabIndex=0)
Space/Enter  → "lift" (dnd-kit KeyboardSensor activates; announces "Picked up <type>")
Arrow Up/Down → move within the SortableContext (reorder); announcer reads new index
Arrow Left/Right (custom keyboardCoordinates) → hop to the sibling container at the same level
Space/Enter  → drop (announces "Dropped over <container> position N")
Esc → cancel (announces "Movement cancelled")
```

`DndContext` is given `accessibility={{ announcements, screenReaderInstructions }}` with Kelta-worded
strings so screen-reader users get position feedback. The drag handle is a real `<button>` (focusable,
`aria-label="Reorder <type>"`).

### 2.6 Sample tree JSON — `grid > column(span) > widget`

```json
[
  {
    "id": "g1",
    "type": "grid",
    "props": {},
    "children": [
      {
        "id": "col-1",
        "type": "column",
        "props": {},
        "span": { "base": 12, "md": 6 },
        "children": [
          { "id": "h1", "type": "heading", "props": { "text": "Orders", "level": "h1" } },
          { "id": "t1", "type": "text", "props": { "content": "Recent orders below." } }
        ]
      },
      {
        "id": "col-2",
        "type": "column",
        "props": {},
        "span": { "base": 12, "md": 6 },
        "children": [
          { "id": "tb1", "type": "table",
            "props": { "dataView": { "collection": "orders", "fields": ["id", "status", "total"], "limit": 25 } } }
        ]
      }
    ]
  },
  { "id": "d1", "type": "divider", "props": {} }
]
```

Stored at `config.components` with `config.schemaVersion: 2`. `span` is absent on `grid`/`divider` and
on leaf children of a `column` (a `column` stacks its children full-width). Optional `events?` round-trips
untouched (authored in 2e).

### 2.7 Legacy → v2 migration (`migrate.ts`)

```
LEGACY (schemaVersion absent, every node has position {row,column,width,height}):
[
  { "id":"a", "type":"heading", "props":{...}, "position":{ "row":0, "column":0, "width":12, "height":1 } },
  { "id":"b", "type":"text",    "props":{...}, "position":{ "row":1, "column":0, "width":6,  "height":1 } },
  { "id":"c", "type":"image",   "props":{...}, "position":{ "row":1, "column":6, "width":6,  "height":1 } }
]

migrate(legacy) →  group by row, wrap each node in a column carrying span.base = width:
[
  { "id":"<gen>", "type":"grid", "props":{}, "children":[
      { "id":"<gen>", "type":"column", "props":{}, "span":{ "base":12 }, "children":[ {a} ] },
      { "id":"<gen>", "type":"column", "props":{}, "span":{ "base":6  }, "children":[ {b} ] },
      { "id":"<gen>", "type":"column", "props":{}, "span":{ "base":6  }, "children":[ {c} ] }
  ]}
]
```

`position` is stripped from leaf nodes after wrapping. A node already lacking `position` (a 2a-created
node) is wrapped in a single full-width `column` (`span.base = 12`). Idempotent: re-running on a v2 tree
(`schemaVersion: 2`) returns it unchanged.

---

## 3. Data & API contracts

All TS under `kelta-ui/app/src/pages/PageBuilderPage/`. **No backend/API/render-contract change** in 2c —
`grid`/`row`/`column`/`divider`/`span` all nest inside the existing `config` JSON and round-trip through the
pass-through render service untouched.

### 3.1 `ResponsiveSpan` + Tailwind mapping

`ResponsiveSpan` is already defined in `model/pageModel.ts` (2a, §3.1): `{ base:number; sm?:number;
md?:number; lg?:number }`, each `1..12`. 2c adds the class mapping, in `canvas/spanClasses.ts`:

```ts
import type { ResponsiveSpan } from '../model/pageModel'

/** Full literal class names so Tailwind's JIT scanner can see them (no dynamic string interpolation). */
const COL_SPAN: Record<number, string> = {
  1: 'col-span-1', 2: 'col-span-2', 3: 'col-span-3', 4: 'col-span-4',
  5: 'col-span-5', 6: 'col-span-6', 7: 'col-span-7', 8: 'col-span-8',
  9: 'col-span-9', 10: 'col-span-10', 11: 'col-span-11', 12: 'col-span-12',
}
const COL_SPAN_SM: Record<number, string> = {
  1: 'sm:col-span-1', 2: 'sm:col-span-2', 3: 'sm:col-span-3', 4: 'sm:col-span-4',
  5: 'sm:col-span-5', 6: 'sm:col-span-6', 7: 'sm:col-span-7', 8: 'sm:col-span-8',
  9: 'sm:col-span-9', 10: 'sm:col-span-10', 11: 'sm:col-span-11', 12: 'sm:col-span-12',
}
const COL_SPAN_MD: Record<number, string> = { /* md:col-span-1 … md:col-span-12 */ }
const COL_SPAN_LG: Record<number, string> = { /* lg:col-span-1 … lg:col-span-12 */ }

export const GRID_CONTAINER_CLASS = 'grid grid-cols-12 gap-4'

/** Clamp to the valid 1..12 grid range. */
export function clampSpan(n: number): number {
  return Math.min(12, Math.max(1, Math.round(n)))
}

/**
 * Map a span to its Tailwind classes. `base` defaults to 12 (full width) when no span is set —
 * matching a plain stacked child. Breakpoint keys are additive (base + any of sm/md/lg present).
 */
export function spanToClasses(span: ResponsiveSpan | undefined): string {
  if (!span) return COL_SPAN[12]
  const out = [COL_SPAN[clampSpan(span.base)]]
  if (span.sm != null) out.push(COL_SPAN_SM[clampSpan(span.sm)])
  if (span.md != null) out.push(COL_SPAN_MD[clampSpan(span.md)])
  if (span.lg != null) out.push(COL_SPAN_LG[clampSpan(span.lg)])
  return out.join(' ')
}
```

> **Tailwind JIT caveat (load-bearing):** the maps use **full literal class strings** because Tailwind v4's
> scanner cannot see `` `col-span-${n}` ``. The 48 literals (4 breakpoints × 12) are emitted exactly so the
> classes survive the build. The two-breakpoint names (`sm`/`md`/`lg`) align with the `@kelta/components`
> `ResponsiveBreakpoints` semantics (mobile 768 / tablet 1024 / desktop 1280) — `sm`≈tablet, `md`/`lg`≈
> desktop tiers; the grid is mobile-first (`base` = all widths, prefixes override upward).

### 3.2 `treeOps.ts` — full implementation (2a stubs finished)

2a shipped `insertNode` / `removeNode` / `updateProps` (and `notImplemented` stubs for `moveNode` /
`setSpan`). 2c implements the latter two and hardens the no-op guards. All functions are **pure** and
return a **new immutable tree** (never mutate input). Signatures:

```ts
import type { PageComponent, ResponsiveSpan } from './pageModel'

/** Insert `node` under `parentId` (or root when null) at `index` (or append when index omitted/out of range). */
export function insertNode(
  tree: PageComponent[],
  node: PageComponent,
  parentId: string | null,
  index?: number,
): PageComponent[]

/** Remove the node with `id` anywhere in the tree. No-op (returns same-shaped new tree) if not found. */
export function removeNode(tree: PageComponent[], id: string): PageComponent[]

/** Shallow-merge `patch` into the target node's `props`. */
export function updateProps(
  tree: PageComponent[],
  id: string,
  patch: Record<string, unknown>,
): PageComponent[]

/**
 * Move `id` to live under `toParentId` (or root when null) at `index`.
 * Implemented as remove-then-insert with two guards:
 *  - no-op if the move is to the SAME parent + SAME resulting index (returns the input tree reference),
 *  - reject (throw) if `toParentId` is `id` or a DESCENDANT of `id` (would orphan the subtree).
 * Index is interpreted AFTER removal when moving within the same parent (so dragging down by one is stable).
 */
export function moveNode(
  tree: PageComponent[],
  id: string,
  toParentId: string | null,
  index: number,
): PageComponent[]

/** Set (or clear) the responsive span on a node. `undefined` removes the key entirely. */
export function setSpan(
  tree: PageComponent[],
  id: string,
  span: ResponsiveSpan | undefined,
): PageComponent[]
```

Internal helpers (not exported): `findNode(tree, id): { node; parentId } | null`,
`isDescendant(tree, ancestorId, maybeChildId): boolean`, `mapTree(tree, fn)` recursive rebuilder.

```ts
export function moveNode(
  tree: PageComponent[],
  id: string,
  toParentId: string | null,
  index: number,
): PageComponent[] {
  const found = findNode(tree, id)
  if (!found) return tree
  if (toParentId === id || isDescendant(tree, id, toParentId)) {
    throw new Error(`moveNode: cannot move ${id} into itself or a descendant`)
  }
  // No-op guard: same parent + index already equals the target slot.
  if (found.parentId === toParentId) {
    const siblings = childrenOf(tree, toParentId)
    const currentIndex = siblings.findIndex((c) => c.id === id)
    const target = Math.min(index, siblings.length - 1)
    if (currentIndex === target) return tree
  }
  const node = found.node
  const without = removeNode(tree, id)
  return insertNode(without, node, toParentId, index)
}

export function setSpan(
  tree: PageComponent[],
  id: string,
  span: ResponsiveSpan | undefined,
): PageComponent[] {
  return mapTree(tree, (n) =>
    n.id === id ? (span === undefined ? omitSpan(n) : { ...n, span }) : n,
  )
}
```

### 3.3 DnD data payloads (`canvas/dnd/types.ts`)

```ts
import type { PageComponent } from '../../model/pageModel'

/** Attached to useDraggable for a palette tile. */
export interface PaletteDragData {
  source: 'palette'
  widgetType: string
}

/** Attached to useSortable for an existing node in the canvas. */
export interface NodeDragData {
  source: 'node'
  nodeId: string
  parentId: string | null
}

export type DragData = PaletteDragData | NodeDragData

/** Attached to each droppable container + each sortable slot, read on drag-over/end. */
export interface DropData {
  /** The container node id this droppable belongs to (null = root canvas). */
  container: string | null
  /** Slot index within that container (for sortable items); containers expose their own length as fallback. */
  index: number
}

/** Stable dnd id conventions (so onDragEnd can route without a registry lookup). */
export const PALETTE_ID = (type: string) => `palette:${type}`        // draggable
export const NODE_ID = (id: string) => `node:${id}`                  // sortable item
export const CONTAINER_ID = (id: string | null) => `container:${id ?? 'root'}` // droppable
```

`onDragEnd(event)` logic (in `canvas/dnd/useCanvasDnd.ts`):

```
const a = event.active.data.current as DragData
const o = event.over?.data.current as DropData | undefined
if (!o) return                                   // dropped on nothing
if (a.source === 'palette') {
  insertNode(tree, makeNode(a.widgetType), o.container, o.index)   // descriptor.defaultProps applied
} else {
  moveNode(tree, a.nodeId, o.container, o.index)                   // no-op guard inside
}
```

Sensors: `useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }))`. Collision:
`closestCenter`. Each container wraps its children in
`<SortableContext items={childIds} strategy={rectSortingStrategy}>`.

### 3.4 Resize payload (`canvas/dnd/useSpanResize.ts`)

Resize is **not** a dnd-kit drag (it would conflict with sort) — it's a lightweight pointer handler on the
handle:

```ts
interface SpanResizeArgs {
  nodeId: string
  currentBase: number          // span.base (defaults 12)
  gridWidthPx: number          // measured container width (NOT persisted)
  breakpoint?: 'base' | 'sm' | 'md' | 'lg' // which key the inspector targets; canvas handle edits 'base'
}
// onPointerMove: deltaCols = Math.round(deltaPx / (gridWidthPx / 12))
//                next = clampSpan(currentBase + deltaCols)
// onPointerUp:   if (next !== currentBase) setSpan(tree, nodeId, { ...span, [breakpoint]: next })
```

Only the integer column delta is ever written; `gridWidthPx` is read transiently for snapping.

### 3.5 Widget descriptors — the 4 layout builtins

Each is a `WidgetDescriptor` (2a `widgets/types.ts`). `Render` is the single editor+runtime path
(`mode` only affects empty-state placeholders, matching the 2a `card`/`container` convention).

```tsx
// widgets/builtins/grid.tsx
import { GRID_CONTAINER_CLASS, spanToClasses } from '../../canvas/spanClasses'
export const gridWidget: WidgetDescriptor = {
  type: 'grid', label: 'Grid', icon: '▦', category: 'layout', acceptsChildren: true,
  defaultProps: {},
  propSchema: [], // 2c adds no editable props on the grid itself; children carry span (kind:'span' in 2b)
  Render: ({ node, renderChild }) => (
    <div className={GRID_CONTAINER_CLASS} data-testid="page-node-grid">
      {(node.children ?? []).map((c) => (
        <div key={c.id} className={spanToClasses(c.span)}>{renderChild(c)}</div>
      ))}
    </div>
  ),
}
```

```tsx
// widgets/builtins/column.tsx — a vertical stack; itself spans N of 12 when inside a grid/row.
export const columnWidget: WidgetDescriptor = {
  type: 'column', label: 'Column', icon: '▯', category: 'layout', acceptsChildren: true,
  defaultProps: {},
  propSchema: [{ key: 'span', label: 'Width', kind: 'span', group: 'Layout' }], // editor uses span kind (2b)
  Render: ({ node, renderChild, mode }) => {
    const kids = node.children ?? []
    if (kids.length === 0 && mode === 'editor') {
      return <div className="min-h-[48px] rounded border border-dashed border-border"
                  data-testid="page-node-column" />
    }
    return (
      <div className="flex flex-col gap-4" data-testid="page-node-column">
        {kids.map((c) => renderChild(c))}
      </div>
    )
  },
}
```

```tsx
// widgets/builtins/row.tsx — horizontal grid; children placed by their span on a 12-col track.
export const rowWidget: WidgetDescriptor = {
  type: 'row', label: 'Row', icon: '▭', category: 'layout', acceptsChildren: true,
  defaultProps: {},
  propSchema: [],
  Render: ({ node, renderChild }) => (
    <div className={GRID_CONTAINER_CLASS} data-testid="page-node-row">
      {(node.children ?? []).map((c) => (
        <div key={c.id} className={spanToClasses(c.span)}>{renderChild(c)}</div>
      ))}
    </div>
  ),
}
```

```tsx
// widgets/builtins/divider.tsx — leaf, no children, no span.
export const dividerWidget: WidgetDescriptor = {
  type: 'divider', label: 'Divider', icon: '─', category: 'layout', acceptsChildren: false,
  defaultProps: {},
  propSchema: [],
  Render: () => <hr className="my-4 border-border" data-testid="page-node-divider" />,
}
```

> `grid` and `row` share the `grid grid-cols-12` track (a `row` is a single-line conceptual grid; both
> place children by `span`). The semantic distinction is authoring intent + palette label; rendering is the
> same 12-col CSS grid. A `column` is the standard nesting cell that itself carries a `span`.

### 3.6 `migrate.ts` contract

```ts
import type { PageComponent } from './pageModel'

interface LegacyPosition { row: number; column: number; width: number; height: number }
type LegacyNode = PageComponent & { position?: LegacyPosition }

/**
 * Convert a legacy component tree (nodes with `position`) into a v2 grid/column tree with `span`.
 * - Top-level legacy nodes are grouped by `position.row`; each becomes a `column` (span.base = width,
 *   clamped 1..12) inside a single root `grid`.
 * - Nodes WITHOUT `position` (already v2-ish) are each wrapped in a full-width column (span.base = 12).
 * - `position` is stripped from every node.
 * - Children recurse (legacy nesting is rare but handled).
 * Idempotent: a tree where `schemaVersion === 2` (caller-checked) is returned untouched.
 */
export function migrateTree(components: LegacyNode[]): PageComponent[]

/** True if any node carries a legacy `position` (⇒ needs migration). */
export function needsMigration(components: PageComponent[]): boolean
```

`PageBuilderPage` calls, on load: if `config.schemaVersion !== 2 && needsMigration(components)`, run
`migrateTree` and set state; the migrated tree is persisted (with `schemaVersion: 2`) on the next save via
the **2c-widened `mergeConfig` + widened `handleSavePage` call site (§5.9)** — note that `mergeConfig` and
the save call are widened **here in 2c** (2a's `mergeConfig` only overlaid `components`/`layout`; the
`PageConfig.schemaVersion?: 2` field is the marker 2c becomes the first to write).

### 3.7 `PageComponent` / `ComponentPosition` — what changes

No new model type is introduced. 2c **stops writing** `position`: `handleAddComponent` already dropped it in
2a (§5.3 of 2a). `ComponentPosition` remains the deprecated legacy interface (exported from
`model/pageModel.ts`, 2a §3.1) used **only** by `migrate.ts`'s `LegacyPosition`. The active model is the 2a
v2 `PageComponent` with `span?`/`children?`.

---

## 4. DB migrations

**None — stored in `ui-pages.config` JSON, no DDL.** `grid`/`row`/`column`/`divider` nodes, `span`, and
`schemaVersion: 2` all nest inside the existing `config` JSON column; the render service is a pass-through
(parent §"Backend changes"). No Flyway version consumed (head remains **V146**), no NATS subject or payload
change.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/` unless noted. New canvas code lives in
`pages/PageBuilderPage/canvas/`.

### 5.1 New — `pages/PageBuilderPage/canvas/`

| File | Contents |
|------|----------|
| `canvas/Canvas.tsx` | The new canvas. Wraps the tree in one `<DndContext>` (PointerSensor + KeyboardSensor, `closestCenter`, Kelta `accessibility` announcements) + `<DragOverlay>`. Renders the tree through `renderNode` (2a) but wraps each node in `<SelectableNode>`; each container node also wraps its children in a `<SortableContext>` (via `<CanvasContainer>`). Owns selection state passthrough (`selectedId`/`onSelect`/`onDelete` props preserved from the old `Canvas` so `PageBuilderPage` wiring barely changes). Root is a droppable (`CONTAINER_ID(null)`). Empty-state copy unchanged (`builder.pages.canvasEmpty`/`canvasHint`). `data-testid="page-canvas"` preserved. |
| `canvas/SelectableNode.tsx` | Wraps one node: `useSortable({ id: NODE_ID(node.id), data: NodeDragData })`. Renders the selection outline + ring (ports the existing `Canvas.renderComponent` chrome classes), a drag handle `<button>` (`GripVertical`, `aria-label="Reorder {type}"`, gets `listeners`/`attributes`), the delete `×` button (preserved `data-testid="delete-component-{id}"`), the "Custom" badge keyed on `componentRegistry.hasPageComponent(type)` (unchanged semantics), and — when the node is a child of a `grid`/`row` — the resize handle (`useSpanResize`). Body = `renderNode(node, { mode:'editor', tenantSlug, scope:{} })`. Preserves `data-testid="canvas-component-{id}"`. |
| `canvas/CanvasContainer.tsx` | For a container node (`grid`/`row`/`column`/`card`/`container`): `useDroppable({ id: CONTAINER_ID(node.id), data: DropData })` + `<SortableContext items={childIds} strategy={rectSortingStrategy}>` wrapping the children (each a `<SelectableNode>`). Shows the drop-zone tint + insertion gap on `isOver`. The container's own visual shell comes from its descriptor `Render`; this component supplies the dnd plumbing around the children slot. |
| `canvas/spanClasses.ts` | §3.1 — `spanToClasses`, `clampSpan`, `GRID_CONTAINER_CLASS`, the 48 literal class maps. |
| `canvas/dnd/types.ts` | §3.3 — `PaletteDragData`/`NodeDragData`/`DragData`/`DropData` + id helpers (`PALETTE_ID`/`NODE_ID`/`CONTAINER_ID`). |
| `canvas/dnd/useCanvasDnd.ts` | `useSensors` config, `onDragStart`/`onDragOver`/`onDragEnd` handlers, the palette-vs-node routing (§3.3) calling `insertNode`/`moveNode`, the active-drag state for `DragOverlay`, and the announcements object. Returns `{ sensors, onDragStart, onDragEnd, activeNode }`. |
| `canvas/dnd/useSpanResize.ts` | §3.4 — pointer-based span resize hook returning `{ handleProps }`; computes integer col delta from measured grid width, calls `setSpan` on pointer-up. |
| `canvas/dnd/announcements.ts` | Kelta-worded `Announcements` + `screenReaderInstructions` strings for the `DndContext accessibility` prop (a11y position feedback). |

### 5.2 New — `pages/PageBuilderPage/widgets/builtins/`

| File | Contents |
|------|----------|
| `widgets/builtins/grid.tsx` | §3.5 `gridWidget` (`grid grid-cols-12`, children by `span`). |
| `widgets/builtins/row.tsx` | §3.5 `rowWidget`. |
| `widgets/builtins/column.tsx` | §3.5 `columnWidget` (vertical stack, editable `span`, empty placeholder in editor). |
| `widgets/builtins/divider.tsx` | §3.5 `dividerWidget` (leaf `<hr>`). |

Register all four in `widgets/builtins/index.ts` `registerBuiltins()` (added to the 2a list of 8 → 12
built-ins). They sort into the `layout` category, so 2b's `listByCategory('layout')` palette picks them up;
in 2c the palette is still the registry-derived array (2a §5.3b), so they appear automatically once
registered.

### 5.3 Complete — `pages/PageBuilderPage/model/treeOps.ts`

Replace the 2a `notImplemented('moveNode — 2c')` / `notImplemented('setSpan — 2c')` stub bodies with the
§3.2 implementations + the internal helpers (`findNode`, `isDescendant`, `childrenOf`, `mapTree`,
`omitSpan`). `insertNode`/`removeNode`/`updateProps` are unchanged (already implemented in 2a). The 2a
`treeOps.test.ts` asserts the stubs throw — 2c flips those to behavioral assertions (§6.1).

### 5.4 New — `pages/PageBuilderPage/model/migrate.ts`

§3.6 — `migrateTree`, `needsMigration`, `LegacyPosition`/`LegacyNode` local types, and the row-grouping +
column-wrapping logic. Pure, unit-tested.

### 5.5 Refactor — `pages/PageBuilderPage/PageBuilderPage.tsx`

**(a) Swap the canvas.** Delete the in-file native-DnD `Canvas` (`~688–872`: `handleDragOver`/
`handleDragLeave`/`handleDrop`/`handleKeyDown`/`renderComponent` and the `onDragOver`/`onDrop` wiring) and
the `CanvasProps.onDrop`/`getPageComponent` plumbing. Import the new canvas:

```tsx
// BEFORE: function Canvas({ components, selectedId, onSelect, onDrop, onDelete, getPageComponent }) {…native DnD…}
// AFTER:
import { Canvas } from './canvas/Canvas'
```

Call site (~1605):

```tsx
// BEFORE: <Canvas components={…} selectedId={…} onSelect={…} onDrop={handleCanvasDrop} onDelete={…} getPageComponent={getPageComponent} />
// AFTER:
<Canvas
  components={components}
  selectedId={selectedComponentId}
  onSelect={handleSelectComponent}
  onChange={handleTreeChange}   // tree mutations from dnd/resize flow back through one callback
  onDelete={handleDeleteComponent}
  tenantSlug={tenantSlug}
/>
```

**(b) Palette becomes a draggable source.** `ComponentPalette` (~190) keeps its registry-derived
`AVAILABLE_COMPONENTS` (2a) and its `onAddComponent` click (still valid — clicking appends to root via
`handleAddComponent`). Replace the native `draggable`/`onDragStart` on each tile (~210) with a small
`PaletteDraggable` wrapper using `useDraggable({ id: PALETTE_ID(type), data: { source:'palette',
widgetType: type } })`. The palette is rendered **inside** the canvas's `DndContext` (lift the
`ComponentPalette` into `Canvas`, or pass the palette as a child of the `DndContext` — see Canvas.tsx
layout). Remove `handleDragStart`/`handleCanvasDrop`/`draggedComponentType` state (~1369–1396) — replaced
by `useCanvasDnd`.

**(c) Tree-mutation callback.** Add a single `handleTreeChange(next: PageComponent[])` that
`setComponents(next)` + `setHasUnsavedChanges(true)`; the canvas computes `next` via `treeOps` and calls it
on every drop/move/resize. `handleAddComponent` (~1374) stays (palette **click** path) but is rewritten to
use `insertNode` so click-add and the descriptor defaults agree:

```ts
const handleAddComponent = useCallback((componentType: string) => {
  const descriptor = widgetRegistry.get(componentType)
  const node: PageComponent = { id: generateId(), type: componentType, props: { ...descriptor.defaultProps } }
  setComponents((prev) => insertNode(prev, node, null)) // append to root
  setSelectedComponentId(node.id)
  setHasUnsavedChanges(true)
}, [])
```

**(d) Migration on load.** Where the page's components are read into state (the `useEffect` that seeds
`components` from `readComponents(currentPage)`), wrap:

```ts
const raw = readComponents(currentPage)
const cfg = readConfig(currentPage)
const seeded = cfg.schemaVersion !== 2 && needsMigration(raw) ? migrateTree(raw) : raw
setComponents(seeded)
```

**(d.1) Persist the migration result on save — the canonical save-path rewrite (2c OWNS this).** See
**§5.9** for the full before→after. In short: today `handleSavePage` (~1428) calls
`mergeConfig(readConfig(currentPage), { components })` and `mergeConfig` (`pageConfig.ts`) only overlays
the keys it is **passed**. So `schemaVersion` (and every future page-level sibling) is **silently dropped
on save** unless the CALL passes it — widening `mergeConfig`'s accepted keys alone does nothing. 2c
therefore changes **both**: (1) widen `mergeConfig` to overlay `variables`/`dataSources`/`access`/
`schemaVersion`, **and** (2) change the one save call site to pass the full current set —
`mergeConfig(readConfig(currentPage), { components, variables, dataSources, schemaVersion: 2 })`. 2c only
*reads/writes* `components` + `schemaVersion` today; `variables`/`dataSources` are passed as their current
(possibly `undefined`) state so the call shape is final and 2d/1h only extend the values, not re-touch the
call site. The mutation **payload** (not just `mergeConfig`'s return) must be asserted to contain
`schemaVersion` (§6.7).

**(e) `tenantSlug`.** Thread it from the route (the builder runs under `/:tenantSlug/setup/...`) into
`Canvas` (used by `renderNode` for plugin/data nodes). If unavailable, pass `''` (2a parity note).

### 5.6 No change — runtime renderer

`pages/app/CustomPage/PageTreeRenderer.tsx` is **already** the thin `<RenderTree mode="runtime">` wrapper
(2a §5.4). The new `grid`/`row`/`column`/`divider` descriptors render through it automatically once
registered — **no edit needed here**. Runtime `span` is honored because the `grid`/`row` descriptor `Render`
applies `spanToClasses(child.span)` (the same code editor uses). This preserves the 2a de-dup guarantee.

### 5.7 Dependency add — `kelta-ui/app/package.json`

Add to `dependencies` (exact pins; current `@dnd-kit` majors as of 2026-06-22):

```jsonc
"@dnd-kit/core": "^6.3.1",
"@dnd-kit/sortable": "^10.0.0",
"@dnd-kit/utilities": "^3.2.2"   // peer of sortable, used for CSS.Transform on the drag handle
```

> Confirmed **no `@dnd-kit/*` present** today in `kelta-ui/app/package.json`, the `@kelta/*` packages, or
> the lockfile. This is the parent-mandated single new dep, **scoped to the page canvas only** — no other
> surface (`PageLayoutsPage`/`MenuBuilderPage`/`FlowDesignerPage`) is migrated. Run `npm install` in
> `kelta-ui/app` to refresh the lockfile in the same PR. Verify the bundle-size budget (these three add
> ~30–40 kB gz) is within the kelta-ui build gate.

### 5.9 Rewrite — the save path (`PageBuilderPage.tsx` `handleSavePage` + `pageConfig.ts` `mergeConfig`) — **2c OWNS this (BLOCKER)**

Parent §"Save & persistence (v2 round-trip)" names this the **#1 correctness risk**. 2c is the first slice
that must persist a page-level sibling (`schemaVersion: 2`), so it lands the canonical rewrite. **Both
sides must change** — widening `mergeConfig`'s accepted keys is **useless unless the save CALL passes
them** (the exact silent-drop bug class the parent flags: a key not passed is never overlaid, so it never
reaches the `config` JSON).

**Canonical storage reminder (parent "Page-level config (v2) → Canonical storage"):** the tree lives at
`config.components`; `schemaVersion: 2` is a **sibling of `components`** inside `config` (**NO `config.tree`
wrapper**). `mergeConfig` overlays onto the flat `config` object — every key it writes is a direct child of
`config`.

**`pageConfig.ts` — `PageConfig` + `mergeConfig` widened:**

```ts
// BEFORE (verified ~lines 11–44):
export interface PageConfig {
  layout?: PageLayout
  components?: PageComponent[]
}
export function mergeConfig(
  existing: PageConfig,
  changes: { components?: PageComponent[]; layout?: PageLayout }
): PageConfig {
  const merged: PageConfig = { ...existing }
  if (changes.layout !== undefined) merged.layout = changes.layout
  if (changes.components !== undefined) merged.components = changes.components
  return merged
}

// AFTER (2c widens both the type and the overlay set — schemaVersion + the page-level siblings):
export interface PageConfig {
  layout?: PageLayout            // inert legacy (see §5.11) — kept for back-compat, not authored
  components?: PageComponent[]
  variables?: PageVariable[]     // sibling of components (2d populates; 2c passes through)
  dataSources?: PageDataSource[] // sibling of components (2d populates; 2c passes through)
  access?: { requiredPermission?: string } // sibling (1h populates; 2c passes through)
  schemaVersion?: 2              // sibling marker — 2c is the first writer
}
export function mergeConfig(
  existing: PageConfig,
  changes: {
    components?: PageComponent[]
    layout?: PageLayout
    variables?: PageVariable[]
    dataSources?: PageDataSource[]
    access?: { requiredPermission?: string }
    schemaVersion?: 2
  }
): PageConfig {
  const merged: PageConfig = { ...existing }
  if (changes.layout !== undefined) merged.layout = changes.layout
  if (changes.components !== undefined) merged.components = changes.components
  if (changes.variables !== undefined) merged.variables = changes.variables
  if (changes.dataSources !== undefined) merged.dataSources = changes.dataSources
  if (changes.access !== undefined) merged.access = changes.access
  if (changes.schemaVersion !== undefined) merged.schemaVersion = changes.schemaVersion
  return merged
}
```

> `PageVariable`/`PageDataSource` are the parent §"Page-level config (v2)" types; 2c imports/forward-declares
> them (2d gives them their authored shape). 2c only *writes* values for `components` + `schemaVersion`; the
> rest are overlaid only when their (2d/1h-owned) state is defined — but the call site passes them now so the
> shape is final.

**`PageBuilderPage.tsx` — `handleSavePage` (~1428) call site widened:**

```ts
// BEFORE (verified ~1428–1438):
const handleSavePage = useCallback(() => {
  if (!editingPageId) return
  updateMutation.mutate({
    id: editingPageId,
    data: {
      config: mergeConfig(readConfig(currentPage), { components }),  // ← drops schemaVersion/siblings
    } as unknown as Partial<UIPage>,
  })
}, [editingPageId, components, currentPage, updateMutation])

// AFTER (2c passes the full current set — schemaVersion:2 is now persisted; 2d fills variables/dataSources,
// 1h fills access — they only widen the VALUES, never re-touch this call site):
const handleSavePage = useCallback(() => {
  if (!editingPageId) return
  updateMutation.mutate({
    id: editingPageId,
    data: {
      config: mergeConfig(readConfig(currentPage), {
        components,
        variables,        // current page-variable state (undefined until 2d) — passed so it round-trips
        dataSources,      // current data-source state (undefined until 2d)
        schemaVersion: 2, // 2c stamps v2 on every save (migration is now persisted)
      }),
    } as unknown as Partial<UIPage>,
  })
}, [editingPageId, components, variables, dataSources, currentPage, updateMutation])
```

**Why both edits are load-bearing:** widening `mergeConfig` without widening the call leaves the new keys
unreachable (the call still passes only `{ components }`, so `mergeConfig` overlays nothing new) — the page
saves, `schemaVersion` is dropped, and the legacy page **re-migrates on every reload** (the migration is
never persisted). The test (§6.7) asserts the `updateMutation.mutate` **payload** contains `schemaVersion:
2`, not merely that `mergeConfig` *can* return it — 2d/1h extend that same payload assertion for their keys.

### 5.10 Resolve — the unsaved-changes guard (`handleBackToList` + `beforeunload`) — **2c OWNS this**

Parent §"Parity gaps NOT covered" places the unsaved-changes guard **in 2c scope** ("today a
`// Could show a confirmation dialog here` TODO"). dnd-kit makes more canvas state losable (reorders, span
resizes, drop-into-container moves are all `setHasUnsavedChanges(true)` mutations), so 2c finishes it rather
than deferring.

```ts
// BEFORE (verified ~1357–1366):
const handleBackToList = useCallback(() => {
  if (hasUnsavedChanges) {
    // Could show a confirmation dialog here   ← empty TODO
  }
  setViewMode('list')
  setEditingPageId(null)
  setComponents([])
  setSelectedComponentId(null)
  setHasUnsavedChanges(false)
}, [hasUnsavedChanges])

// AFTER: confirm before discarding in-builder; bail if the author cancels.
const handleBackToList = useCallback(() => {
  if (hasUnsavedChanges && !window.confirm(t('builder.pages.unsavedConfirm'))) {
    return // stay in the builder; nothing is reset
  }
  setViewMode('list')
  setEditingPageId(null)
  setComponents([])
  setSelectedComponentId(null)
  setHasUnsavedChanges(false)
}, [hasUnsavedChanges, t])
```

Plus a native tab-close / refresh guard while editing with unsaved changes:

```ts
useEffect(() => {
  if (!hasUnsavedChanges) return
  const onBeforeUnload = (e: BeforeUnloadEvent) => {
    e.preventDefault()
    e.returnValue = '' // browsers show their own native prompt; the string is ignored
  }
  window.addEventListener('beforeunload', onBeforeUnload)
  return () => window.removeEventListener('beforeunload', onBeforeUnload)
}, [hasUnsavedChanges])
```

> `window.confirm`/`beforeunload` is the minimal in-scope guard (a styled modal is a later polish — not
> required for 2c). The confirm copy is i18n'd (`builder.pages.unsavedConfirm`, §5.8/§7). Test: with
> `hasUnsavedChanges` true, a mocked `window.confirm → false` keeps `viewMode === 'edit'` (no reset);
> `→ true` resets to the list (§6.7).

### 5.11 Deprecate — the create-form `layoutType` select (`config.layout` → inert legacy) — **2c OWNS the decision**

The create/edit form's `layoutType` `<select>` (single/sidebar/grid, `data-testid="page-layout-select"`,
~1082–1093) writes `config.layout = { type }` via `handleSubmit`/`mergeConfig`. The new **widget-based**
layout (`grid`/`row`/`column` + per-child `span`) makes `config.layout.type` **orphaned** — nothing in the
canvas, the descriptors, or the runtime renderer reads it. 2c must pick one and spec it; **decision: keep
the field but document `config.layout` as inert legacy** (do **not** rip the select out in 2c):

- **Why not remove now:** the select is also the page's create-time form field; deleting it touches the
  create flow + its tests, which is out of the canvas axis. Removal is a clean follow-up once no page reads
  `config.layout`.
- **What 2c does:** (a) `mergeConfig` still overlays `layout` (preserved on round-trip — never dropped),
  so existing pages keep their stored `layout` untouched; (b) the canvas/runtime **ignore** `config.layout`
  entirely (layout is expressed by the widget tree + `span`); (c) the `PageConfig.layout` field carries a
  `// inert legacy` comment (§5.9) alongside `schemaVersion: 2`; (d) `conventions.md` + `concerns.md` record
  `config.layout` as deprecated/inert (a future slice may remove the select). No runtime behavior depends on
  `layoutType` after 2c.

This is the explicit answer to "deprecate/remove the select **or** document `config.layout` as inert legacy"
— 2c documents it inert (lowest-risk), and flags removal as deferred.

### 5.8 Docs (same PR) — see §7.

---

## 6. Test plan

Vitest + Testing Library + (where fetch is involved) MSW, matching the existing idiom in
`PageBuilderPage.test.tsx` and `pageConfig.test.ts`. **Playwright e2e is post-deploy only** (project
convention) — the canvas drag/reorder/resize e2e runs once deployed; no new e2e file in this slice.

### 6.1 `model/treeOps.test.ts` (extend the 2a file)

- `insertNode`: append to root (no index), insert at a specific root index, nested insert by `parentId`,
  index out-of-range → append; input tree not mutated (immutability assertion via deep-freeze).
- `removeNode`: remove at root, remove deeply nested, remove non-existent → returns an equivalent tree
  (no-op), originals unmutated.
- `updateProps`: shallow-merge patch, no-op on missing id.
- `setSpan`: set span on a node, overwrite, clear with `undefined` (key removed), no-op on missing id.
- `moveNode`:
  - reorder within the same parent (down-by-one is stable — index interpreted after removal),
  - move **into another container** at index 0 / middle / end,
  - move to root,
  - **no-op guard**: same parent + same index returns the **input reference** (assert `===`),
  - **cycle guard**: moving a node into itself or a descendant **throws** (assert the error).
- Flip the 2a "stub throws `notImplemented`" assertions for `moveNode`/`setSpan` to behavioral tests.

### 6.2 `model/migrate.test.ts` (new)

- `needsMigration`: true when any node has `position`, false otherwise (and false for a v2 tree).
- `migrateTree`: legacy nodes grouped by `row` → one `grid` of `column`s with `span.base = width`
  (clamped); `position` stripped from leaves; a node without `position` → full-width column; nested legacy
  children recurse.
- `width` clamping: `width:0`/`width:99` → `span.base` clamped to `1`/`12`.
- **Real legacy `config` fixture (not only synthetic `position` nodes).** Build the fixture from an actual
  page `config` shape as the worker stores it — `{ layout: { type:'single' }, components: [...legacy nodes
  with position...] }` (no `schemaVersion`) — and run `readComponents(page)` → `migrateTree(...)` so the
  test exercises the same read path `PageBuilderPage` uses on load (catches a wrapper/shape mismatch a
  hand-rolled `position[]` array would miss). Assert the migrated tree is a `grid > column > widget`
  structure and `config.layout` is left untouched by the migration (it is inert legacy, §5.11 — `migrate`
  only rewrites `components`).
- **Idempotency = fixpoint (assert deep-equal, both directions).** `migrateTree(migrateTree(legacy))`
  **deep-equals** `migrateTree(legacy)` (running twice changes nothing). Separately, a tree already at
  `schemaVersion: 2` is returned by the caller-guard untouched: assert `needsMigration(v2Tree) === false`
  and that the load path (`cfg.schemaVersion === 2`) skips `migrateTree` entirely (no re-wrap, no extra
  `grid` layer — the classic double-migration bug).
- **migrate → save → reload round-trip (deep-equal).** Simulate the full persistence cycle with the §5.9
  save path: `migrateTree(legacyComponents)` → `mergeConfig(readConfig(page), { components: migrated,
  schemaVersion: 2 })` → read the resulting `config` back via `readComponents` / `readConfig`. Assert the
  reloaded `components` **deep-equals** the migrated tree (no node dropped, no `position` resurrected, no
  `span` lost during the `mergeConfig` overlay) **and** `config.schemaVersion === 2`. This is the regression
  guard for "a node silently dropped during the `mergeConfig` save" — the exact failure the parent's
  save-round-trip mandate targets.

### 6.3 `canvas/spanClasses.test.ts` (new)

- `spanToClasses(undefined)` → `'col-span-12'`.
- `{ base:6 }` → `'col-span-6'`; `{ base:12, md:6 }` → `'col-span-12 md:col-span-6'`; all four breakpoints
  present → 4 space-joined classes in `base sm md lg` order.
- `clampSpan` clamps + rounds (`0→1`, `13→12`, `5.6→6`).
- Sanity: every emitted class is one of the 48 literals (guards against accidental template interpolation).

### 6.4 `canvas/Canvas.interaction.test.tsx` (new — dnd-kit interaction)

Uses `@testing-library/user-event` + dnd-kit's `KeyboardSensor` for deterministic drags (pointer DnD is
flaky in jsdom; the standard pattern is to drive dnd-kit via **keyboard**: focus handle → `Space` → arrows →
`Space`). Per the dnd-kit testing guidance, assert the **resulting tree** (via the `onChange` spy), not pixel
positions.

- **Drop from palette into a column:** render a tree with one empty `column`; keyboard-drag the palette
  `heading` draggable over the column; assert `onChange` fired with a tree where the column has a `heading`
  child at the expected index (not at root) — proves the "no longer appends to root" fix.
- **Reorder within a row:** 3 children `[A,B,C]`; keyboard-drag `C` to index 0; assert `onChange` tree =
  `[C,A,B]` (one `moveNode`).
- **Move between containers:** drag a child from `col-1` to `col-2`; assert it left col-1 and entered col-2
  (no duplication — node count constant).
- **No-op guard:** drag a node and drop it back in place; assert `onChange` is **not** called (or called
  with the identical reference).
- **Selection + delete chrome preserved:** clicking a node fires `onSelect`; the `×` button fires
  `onDelete`; `canvas-component-{id}` / `delete-component-{id}` testids still present.
- **a11y:** the drag handle is a `<button>` with `aria-label`; the `DndContext` exposes the announcer
  region (assert `role="status"`/`aria-live` region exists).

### 6.4b `canvas/dnd/useCanvasDnd.test.ts` (new — routing unit, not interaction)

Unit-test the `onDragEnd` **routing branches** directly (no DOM drag — call the handler with synthetic
`DragEndEvent`-shaped `active`/`over` payloads), complementing the keyboard interaction test in §6.4
(which exercises the real dnd-kit pipeline). This pins the palette-vs-node decision logic in isolation:

- **Palette-source branch:** `active.data.current = { source:'palette', widgetType:'heading' }`, `over` a
  container → asserts `insertNode` is called with the dropped container id + slot index and a node built
  from `widgetRegistry.get('heading').defaultProps` (NOT `moveNode`, NOT root).
- **Node-source branch:** `active.data.current = { source:'node', nodeId, parentId }`, `over` a different
  container → asserts `moveNode(tree, nodeId, over.container, over.index)` (NOT `insertNode`).
- **Dropped on nothing:** `over == null` → handler returns early; neither `insertNode` nor `moveNode` runs;
  `onChange` not called.
- **No-op guard surfaced:** node dropped back in place (`moveNode` returns the input reference) → `onChange`
  is not invoked (or invoked with the identical reference) — proves the §3.2 guard is honored at the hook
  boundary, not just inside `treeOps`.
- **`onChange` payload:** on a real move/insert, `onChange` receives the new tree from `treeOps` (spy the
  `insertNode`/`moveNode` result).

### 6.5 `canvas/useSpanResize.test.ts` (new)

Drive the resize hook with synthetic pointer deltas + a fixed `gridWidthPx` (e.g. 1200 → colWidth 100);
assert: `+250px` → `+3` cols → `setSpan` base `6→9` (snapped); `-700px` → clamp at `1`; sub-half-column
delta (`+40px`) → **no** `setSpan` (rounds to 0). Persists only `span` (no px).

### 6.6 `widgets/builtins/layout.parity.test.tsx` (new)

- `grid`/`row` render `grid grid-cols-12` and wrap each child in `spanToClasses(child.span)`.
- `column` stacks children (`flex flex-col gap-4`), shows the dashed placeholder when empty **in editor
  mode** only.
- `divider` renders `<hr data-testid="page-node-divider">`.
- **Editor vs runtime identical:** render a `grid > column(span) > heading` tree via `RenderTree
  mode="editor"` and `mode="runtime"`; assert identical DOM/classes for the layout nodes (extends the 2a
  `renderTree.test.tsx` cross-mode assertion).

### 6.7 Extend `PageBuilderPage.test.tsx`

- Palette now lists 12 items including `palette-item-grid`/`row`/`column`/`divider`.
- Click-adding a `grid` applies its `defaultProps` and appends to root via `insertNode`.
- **Save-payload assertion (the §5.9 BLOCKER fix):** after any edit, assert the `updateMutation.mutate`
  **payload** (intercepted via the MSW handler or a `mutate` spy — NOT just `mergeConfig`'s return value)
  has `data.config.schemaVersion === 2`. This is the direct guard for the silent-drop bug: a widened
  `mergeConfig` that isn't passed `schemaVersion` would still produce a payload **without** it, and this
  test would fail. (2d extends this same payload assertion to `variables`/`dataSources`; 1h to `access`.)
- **Migration on load + persisted on save (round-trip):** seed `currentPage` with a legacy
  `position`-bearing `config` (no `schemaVersion`); assert the canvas renders a migrated `grid`/`column`
  structure (testids present), that saving sends `config.schemaVersion: 2` **and** the migrated
  `config.components` (no node dropped), and that a subsequent reload does **not** re-migrate
  (`needsMigration` false on the saved tree).
- **Unsaved-changes guard (§5.10):** with `hasUnsavedChanges` true, mock `window.confirm`:
  `→ false` keeps `viewMode === 'edit'` (no state reset, `data-testid="page-canvas"` still present);
  `→ true` returns to the list view. Assert the confirm message uses the i18n key (not a hardcoded string).
- **Layout select inert (§5.11):** the `page-layout-select` still renders in the create/edit form and its
  value round-trips through `mergeConfig` (preserved, never dropped), but changing it does **not** alter the
  rendered canvas layout (the widget tree owns layout) — a light assertion that `config.layout` is inert.
- Existing preview/selection tests stay green (same testids; the canvas swap preserves
  `page-canvas`/`canvas-component-*`/`delete-component-*`).

### 6.8 Extend `pageConfig.test.ts`

2c widens `mergeConfig` (§5.9), so its test grows with the new overlay keys:

- `mergeConfig` overlays `schemaVersion: 2` while **preserving** existing untouched keys
  (`components`/`layout` — the 2a-covered keys stay green).
- `mergeConfig` overlays `variables`/`dataSources`/`access` when passed, and **leaves them untouched** when
  a change object omits them (the silent-drop guard at the helper level: omitted key ⇒ existing value
  preserved, never wiped).
- A change object passing only `{ components }` (the legacy 2a shape) still does **not** invent
  `schemaVersion`/siblings — proving the helper is additive and the *call site* (§5.9) is what introduces
  `schemaVersion: 2`. (Pairs with the §6.7 payload assertion that the call site now does pass it.)

### 6.9 kelta-test-harness

**N/A — FE-only, no DB write path.** No new server constraint is exercised (the tree round-trips through the
existing `config` JSON pass-through unchanged), so the DB-constraint integration guard
([real-DB guard](../../testing.md#real-db-guard-for-constraint-bearing-writes)) does not apply here.

---

## 6b. i18n — new canvas/layout strings

Per parent §i18n ("each FE slice owns its strings") and CLAUDE.md: **no hardcoded English** — all new
user-facing strings go through `useI18n`/`t()` with `builder.*` keys, added to the locale catalog(s) in the
**same PR**. The current builder is fully `useI18n`-driven; 2c keeps it so. Strings 2c introduces:

| Key | Surface | Example copy |
|-----|---------|--------------|
| `builder.pages.layoutWidgets.grid` | Palette tile label (`grid`) | "Grid" |
| `builder.pages.layoutWidgets.row` | Palette tile label (`row`) | "Row" |
| `builder.pages.layoutWidgets.column` | Palette tile label (`column`) | "Column" |
| `builder.pages.layoutWidgets.divider` | Palette tile label (`divider`) | "Divider" |
| `builder.pages.layoutCategory` | Palette `layout` category header | "Layout" |
| `builder.pages.columnEmpty` | Empty-column placeholder (editor) | "Drop a widget here" |
| `builder.pages.spanLabel` | Inspector/handle `span` (Width) label | "Width" |
| `builder.pages.resizeAria` | Resize-handle `aria-label` | "Resize column width" |
| `builder.pages.reorderAria` | Drag-handle `aria-label` (`{type}` interpolated) | "Reorder {type}" |
| `builder.pages.unsavedConfirm` | §5.10 unsaved-changes confirm dialog | "You have unsaved changes. Discard them?" |
| `builder.pages.dnd.*` (picked/dropped/cancelled/over) | `DndContext` `accessibility.announcements` (`canvas/dnd/announcements.ts`) | "Picked up {type}", "Dropped over {container}, position {n}", "Movement cancelled" |

> The descriptor `label`s shown in §3.5 ("Grid"/"Row"/"Column"/"Divider") are **display strings**: the
> palette renders them via `t(builder.pages.layoutWidgets.<type>)`, not the raw `descriptor.label` literal
> (descriptor labels stay English fallbacks). The a11y announcement strings in
> `canvas/dnd/announcements.ts` (§5.1) are i18n'd too — they are not exempt because they are
> screen-reader-only. A test asserts the unsaved-confirm and at least one announcement read from `t()` (not
> a literal), matching the existing builder i18n test idiom.

---

## 7. Docs to update (same PR)

Per CLAUDE.md Rule 6, these ship **in the same PR** as the code.

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` (Page builder / screen builder row) | Add: "slice 2c — **layout engine + `@dnd-kit` canvas**: new `grid`/`row`/`column`/`divider` layout widgets; per-child responsive `span {base,sm,md,lg}` (Tailwind `col-span-*` + breakpoints); the native-HTML5 stack-only canvas is replaced by a `DndContext` (Pointer + Keyboard sensors) with per-container `SortableContext` reorder, **drop-into-container** (no longer appends to root), and **grid-span resize** snapping to the 12-col grid; `model/treeOps` (`moveNode`/`setSpan`) completed and `model/migrate.ts` converts legacy `position` trees to `grid`/`column`+`span`. The ignored `ComponentPosition {row,column,width,height}` is now migrated away and no longer written." Move "responsive grid / drop-into-container / reorder / resize" **out of the gap column**. |
| `.claude/docs/conventions.md` | In the page-config v2 contract section (stubbed in 2a, fleshed in 2d): document `span: {base,sm,md,lg}` (1..12, mobile-first), the `grid`/`row`/`column`/`divider` layout vocabulary, the `schemaVersion: 2` marker + the v1(`position`)→v2(`grid`/`span`) `migrate` back-compat rule, and the **DnD convention**: the page canvas uses `@dnd-kit` (scoped) — `PageLayoutsPage`/`MenuBuilderPage` remain native HTML5 DnD (do not migrate them). State that the only persisted layout state is `span` (no pixel/row/column coords). **Document the save-path rule (§5.9):** page-level siblings (`schemaVersion`/`variables`/`dataSources`/`access`) persist **only** if the `handleSavePage` call passes them to `mergeConfig` — widening `mergeConfig`'s keys alone silently drops them. **Document `config.layout` as inert legacy (§5.11):** the create-form `layoutType` select is orphaned by the widget tree; `config.layout` is preserved on round-trip but read by nothing (removal deferred). |
| `.claude/docs/playbooks.md` ("Add a page component / widget" recipe, added in 2a) | Add a note: a **layout** (container) widget sets `acceptsChildren: true`, renders `node.children` via `renderChild`, and (for grid-track containers) wraps each child in `spanToClasses(child.span)`; the canvas auto-makes any `acceptsChildren` node a droppable + `SortableContext`. |
| `.claude/docs/concerns.md` (Large files table) | `PageBuilderPage.tsx`: note 2c **net-shrinks** it again by extracting the canvas (`~688–872` native-DnD `Canvas` + drag handlers) into `canvas/*`. Record the **new `@dnd-kit` dependency** as a watch item (bundle size; pointer-DnD jsdom-test flakiness mitigated by keyboard-driven interaction tests). Record **`config.layout` as inert/deprecated** (the `layoutType` select is orphaned; removal deferred — §5.11) so a future slice can safely delete the select. Note the **save-path silent-drop class** (§5.9) is now closed for `schemaVersion` but the `mergeConfig`-must-be-passed-the-key invariant must hold for every future page-level sibling. |
| `CLAUDE.md` Stack table (Frontend build / deps) | No version-table row needed for a feature dep, but if the table tracks notable FE libs, add `@dnd-kit` (page-canvas DnD). Otherwise leave; the dep is recorded in `package.json` + this spec. |
| i18n locale catalog(s) (the `builder.*` message source the builder's `useI18n` reads) | Add every new `builder.pages.*` key from §6b (layout widget labels, `layoutCategory`, `columnEmpty`, `spanLabel`, `resizeAria`, `reorderAria`, `unsavedConfirm`, `builder.pages.dnd.*` announcements) to each maintained locale. No hardcoded English in components — strings flow through `t()`. |
| `project_outsystems_roadmap.md` (memory) | Record slice 2c shipped under Rec 1 (page-builder parity, Layout & grid axis). |

---

## 8. Risks & open questions

- **`@dnd-kit` is a new runtime dependency.** Mitigation: scoped to the page canvas only (parent decree);
  three small packages (~30–40 kB gz); pin exact majors (`core ^6`, `sortable ^10`, `utilities ^3`). Verify
  the kelta-ui build/bundle gate still passes. Do **not** migrate `PageLayoutsPage`/`MenuBuilderPage`/
  `FlowDesignerPage` — they stay native HTML5 DnD; mixing the two libs in one tree is the trap to avoid.
- **Pointer DnD is flaky in jsdom.** Mitigation: interaction tests drive dnd-kit via the **KeyboardSensor**
  (focus handle → Space → arrows → Space) and assert the **resulting tree** through the `onChange` spy, not
  pixels — the standard dnd-kit testing approach. Pointer-drag fidelity is covered post-deploy by Playwright.
- **Tailwind JIT cannot see dynamic `col-span-${n}`.** Mitigation: `spanClasses.ts` emits the **48 full
  literal class names**; `spanClasses.test.ts` asserts every produced class is one of those literals so an
  accidental template-string regression fails CI rather than silently dropping the class at build time.
- **Resize must store only `span`, never pixels.** The historical bug was the ignored
  `ComponentPosition {row,column,width,height}`. `useSpanResize` reads the measured grid width transiently
  to compute an **integer column delta** and persists only `span`. A test asserts no px value is written.
  Open question for the user: should the canvas resize handle edit `span.base` only (current design — the
  inspector edits `sm`/`md`/`lg` per breakpoint in 2b), or expose a breakpoint toggle on the handle itself?
  **Recommended:** handle edits `base`; breakpoint overrides via the inspector (keeps the canvas gesture
  simple).
- **`grid` vs `row` render the same 12-col CSS grid.** They differ only in palette label/authoring intent.
  Open question: is a distinct `row` widget worth shipping, or should `row` be dropped and `grid` + per-child
  `span` cover all cases? **Recommended:** keep both for OutSystems-parity familiarity (named `row`/`column`
  vocabulary), but flag for the user — collapsing to `grid`-only is a cheap simplification if preferred.
- **Migration determinism for already-published legacy pages.** `migrateTree` groups by `position.row`;
  pages authored before `position` was meaningfully set (all `row:0`) collapse to a single full-width
  column-per-node grid — acceptable (renders as today's stack). Idempotency + the `schemaVersion: 2` stamp
  prevent re-migration. Migration runs **in the builder on load** (not server-side) so a page is only
  rewritten when an author opens + saves it — no mass backfill, no migration job.
- **Save-path is the #1 correctness risk (parent decree) — closed in 2c.** Widening `mergeConfig` without
  widening the `handleSavePage` call would silently drop `schemaVersion`, re-migrating the page on every
  reload. 2c changes **both** (§5.9) and asserts the **mutation payload** (§6.7), not just `mergeConfig`'s
  return. The invariant — *every page-level sibling must be passed at the call site* — is documented for
  2d/1h to extend (`variables`/`dataSources`/`access`).
- **Create-form `layoutType` select is orphaned (decided: deprecate-in-place, removal deferred).** The
  widget tree + `span` now own layout, so `config.layout.type` is read by nothing. 2c keeps the select and
  marks `config.layout` inert legacy (§5.11) rather than ripping it out (the select is also the create-time
  form field; removal touches the create flow + tests — out of the canvas axis). **Open question for the
  user:** remove the select entirely in a follow-up, or keep it as a no-op page hint? **Recommended:** keep
  it inert now, schedule removal once no page reads `config.layout`.
- **Sequencing.** 2c hard-depends on **2a** (`widgets/*`, `model/*`, `RenderTree`, `treeOps` stubs) being
  merged. It is independent of 2b (inspector) — the 2c `column` `span` `propSchema` field uses the `kind:'span'`
  control that **2b** builds; in 2c the inspector still edits props via the legacy `PropertyPanel`, so canvas
  resize (not the inspector) is the `span` authoring path until 2b lands. Land order: 2a → (2b ∥ 2c). 2d/2e
  build on the same canvas but don't block 2c.
