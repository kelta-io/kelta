# 2g — Widget breadth

> **Slice:** 2g (FE). **Axis:** Widgets.
> **Parent:** [`../page-builder-parity.md`](../../page-builder-parity.md). This child spec **extends, never
> contradicts** the parent's [Widget registry](../../page-builder-parity.md#widget-registry) (the v1 widget
> set) and [Reuse Map](../../page-builder-parity.md#reuse-map). The `WidgetDescriptor` / `PropFieldSchema` /
> `WidgetRenderProps` / `PageComponent` v2 / `Binding` / `PropValue` types are **defined once in 2a**
> ([`./2a-widget-registry.md`](./2a-widget-registry.md) §3.2) — this slice consumes them and adds new
> built-in descriptors only.
> Source-verified against the codebase on 2026-06-22 (Flyway head V146; **this slice adds no migration**).
> If code and this doc disagree, trust the code and fix this doc.

---

## 1. Goal & scope

### What this slice delivers

The remaining **breadth** of the v1 widget set from the parent registry — the presentational and
structural widgets that are not data-binding (2d), event (2e), or typed-form (2f) work. Each is a
**first-class `WidgetDescriptor`** under `widgets/builtins/` (one file per widget), with `type`,
`label`, `icon`, `category`, `defaultProps`, `propSchema`, and a **single `Render`** used by both the
editor preview (`mode:'editor'`) and the runtime (`mode:'runtime'`) through the shared `RenderTree`
from 2a. No widget here is a plugin shim — they are built-ins registered in `registerBuiltins()`.

Widgets added in this slice:

| Widget | `type` | Category | Wraps / built on | New in 2g |
|--------|--------|----------|------------------|-----------|
| Chart | `chart` | `data` | `recharts` (`^3.7.0`, already a dep) — `BarChart`/`LineChart`/`PieChart` | descriptor + Render |
| Tabs | `tabs` | `navigation` | local `@/components/ui/tabs` (radix `Tabs` wrapper) | descriptor + Render |
| Nav | `nav` | `navigation` | `@kelta/components` `Navigation` (exported) | descriptor + Render |
| Icon | `icon` | `content` | `lucide-react` (`^0.563.0`) `icons` map (name → component) | descriptor + Render |
| Link | `link` | `content` | `react-router-dom` `Link` / `<a>` | descriptor + Render |
| Image (polish) | `image` | `content` | native `<img>` | **upgrades** the 2a `image` built-in |

`chart` binds to a **page data source array** (the `data.<sourceName>` namespace introduced in
parent §"Page-level config (v2)" and authored in 2d); `tabs` introduces a **per-tab children-container
model** so each tab hosts a sub-tree rendered through `renderChild`; `nav` renders a menu (static items,
or items from a bound array); `icon`/`link`/`image` are leaf presentational widgets with **bindable**
props where it makes sense (icon name, link label/target, image src/alt).

### What this slice explicitly does NOT do

| Out of scope | Lands in / owned by |
|--------------|---------------------|
| The `BindableField` `fx` toggle UI + `resolveBindings`/`interpolate`/scope machinery | 2d (this slice **declares** `bindable: true` on prop schemas; the actual resolution is 2d's `RenderTree` change — see §3.7) |
| Page `dataSources` authoring + on-load fetch hook (`usePageDataSources`) that fills `data.<name>` | 2d (chart **consumes** a resolved array from scope; it does not fetch) |
| Events/actions on `link`/`nav`/`button` (click → navigate/runFlow) authoring + runtime | 2e (`link` uses a plain href/router target here; `PageAction` wiring is 2e) |
| Typed form inputs (`dropdown`/`datepicker`/`lookup`/`multi-picklist`/`number-input`/`checkbox`) and `form` → `ResourceForm` | 2f |
| Schema-driven `Inspector` looping over `propSchema` (incl. a chart series editor, nav-items editor, icon picker) | 2b (this slice **populates** `propSchema`; 2b renders it. Until 2b ships, the legacy `PropertyPanel` edits these via raw JSON/dataView fields — see §8) |
| dnd-kit canvas drop-into-container / reorder / `span` for tab children | 2c (`tabs` children use the same `PageComponent[]` tree `treeOps` already mutate) |
| Any backend / API / Flyway / NATS change | **None** — all widgets are presentational; tree round-trips inside `ui-pages.config` |

### Parent-doc sections this conforms to

- **Widget registry** — adds exactly the breadth members named in the parent's built-in list:
  Content `icon`, `link`, `image`; Data `chart`; Navigation `nav` (→ `Navigation`), `tabs` (→ radix).
- **Reuse Map** — `chart` uses the already-present `recharts`; `nav` uses `@kelta/components`
  `Navigation`; `tabs` uses the in-repo radix `Tabs` wrapper (`@/components/ui/tabs`).
- **The shared model** — `tabs` children are `PageComponent[]`; chart binds to `data.<source>` via the
  `Binding` (`$bind`) convention. No new model types.

### Acceptance criteria

- `widgetRegistry.list()` includes `chart`, `tabs`, `nav`, `icon`, `link`, and the upgraded `image`,
  each in its declared category; palette (2b) and `RenderTree` resolve them with **no switch**.
- A `chart` node with `props.dataView` bound to a resolved `data.<name>` array renders a recharts
  bar/line/pie per `props.chartType`, mapping `props.xKey` / `props.series[]` to axes/series.
- A `tabs` node renders a radix tab list from `props.tabs[]` and, per active tab, renders that tab's
  `children` sub-tree through `renderChild` — switching tabs swaps the rendered sub-tree.
- A `nav` node renders `Navigation` from `props.items[]` (static) or a bound array.
- An `icon` node renders the named lucide icon (`props.name`), size/color from props; unknown name →
  graceful fallback (no crash).
- A `link` node renders an in-app `react-router-dom` `Link` for an internal `to`, or an `<a>` for an
  external `href`, with a bindable label.
- `image` honors bindable `src`/`alt` and an `objectFit` prop (`cover`/`contain`/`fill`/`none`).
- A `link.href` / `image.src` resolving to a non-allow-listed scheme (`javascript:`, `data:`) is
  **neutralized** before render via the shared `safeUrl` allow-list (`{http,https,mailto,tel,relative}`,
  same as 2e); proven by a blocked-`javascript:` test (§3.9, §6.5–6.7).
- All new user-facing strings + empty states ("No data", "No menu items", "No tabs configured.",
  "No image source") go through `useI18n`/`t()` with `builder.widget.*` keys — no hardcoded English (§3.10).
- Editor preview and runtime render each widget through the **same descriptor `Render`** (one path),
  reading **already-resolved** props (the 2a resolved-node invariant — 2g never calls `resolveBindings`);
  proven by Vitest rendering the same JSON in both modes.
- `/verify` green (lint + typecheck + `test:coverage` ≥ existing kelta-ui gate).

---

## 2. UI samples

### 2.1 Palette (after 2b registry-driven palette; categories from `listByCategory`)

```
┌─ Widgets ─────────────────────────────────────────────┐
│  CONTENT                                               │
│   [H] Heading   [¶] Text   [▭] Button                  │
│   [★] Icon      [↗] Link   [▦] Image                   │   ← Icon / Link new; Image polished
│  DATA                                                  │
│   [⊞] Table     [∿] Chart  [≣] List   [{}] Field       │   ← Chart new
│  NAVIGATION                                            │
│   [☰] Nav       [⊓] Tabs                               │   ← Nav / Tabs new
│  LAYOUT                                                │
│   [▤] Grid  [▭] Row  [▯] Column  [▢] Container  …      │
└───────────────────────────────────────────────────────┘
```

Icons are kept as the single-char glyphs the 2a palette already uses (the palette renders
`descriptor.icon` as text). The lucide *icon library by name* is used only **inside** the `icon`
widget's `Render`, not for the palette glyph.

### 2.2 Chart bound to a data source — inspector + runtime

Inspector (schema-driven, rendered by 2b from `chart`'s `propSchema`):

```
┌─ Inspector · Chart ───────────────────────────────────┐
│ DATA                                                   │
│  Data source        [ data.orders ▾ ]   (fx)           │  ← bindable: { $bind:'data.orders' }
│  Chart type         ( ) Bar  (•) Line  ( ) Pie         │
│  X axis key         [ month        ]                    │
│  Series                                                 │
│   ┌───────────────────────────────────────────────┐    │
│   │ key: total    label: Revenue   color: ████  ✕  │    │   ← series[] editor (2b)
│   │ key: count    label: Orders    color: ████  ✕  │    │
│   │ [ + Add series ]                               │    │
│   └───────────────────────────────────────────────┘    │
│ APPEARANCE                                             │
│  Height (px)        [ 300 ]                             │
│  Show legend        [✓]   Show grid [✓]                 │
└────────────────────────────────────────────────────────┘
```

Runtime output (a `line` chart over the resolved `data.orders` array):

```
 Revenue / Orders                                  ── Revenue  ── Orders
 1200 ┤                               ╭───────●
  900 ┤                     ╭────────╯
  600 ┤        ╭───────────╯
  300 ┤●──────╯
    0 ┼──────┬──────┬──────┬──────┬──────┬──────
       Jan    Feb    Mar    Apr    May    Jun
```

The array comes from scope (`data.orders`), resolved by 2d's `usePageDataSources` → `resolveBindings`.
In **editor** mode with no live data, the chart renders a small sample/placeholder series so the
designer sees a shape (see §3.1 `EDITOR_SAMPLE`). No fetch happens inside the widget.

### 2.3 Tabs with child containers — canvas + tree

Canvas (editor): selecting the active tab shows that tab's drop-zone sub-tree.

```
┌─ Tabs ────────────────────────────────────────────────┐
│ [ Overview ]  Details    Activity         (+ add tab)  │   ← radix tab list (props.tabs[])
├────────────────────────────────────────────────────────┤
│  ┌── drop zone: tab "Overview".children ───────────┐   │
│  │  [Heading]  Orders                              │   │   ← renderChild over children sub-tree
│  │  [Table]    orders                              │   │
│  └─────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

### 2.4 Nav — runtime

```
┌──────────────────────────────────────────────┐
│ Home    Orders    Customers ▾    Settings     │   ← Navigation, orientation:'horizontal'
│                    ├ All customers             │
│                    └ Segments                  │
└──────────────────────────────────────────────┘
```

### 2.5 Sample component-tree JSON (what `config.components` holds)

**Chart** (bound to a data source named `orders`):

```json
{
  "id": "ch1",
  "type": "chart",
  "props": {
    "dataView": { "$bind": "data.orders", "mode": "path" },
    "chartType": "line",
    "xKey": "month",
    "series": [
      { "key": "total", "label": "Revenue", "color": "#3B82F6" },
      { "key": "count", "label": "Orders", "color": "#06B6D4" }
    ],
    "height": 300,
    "showLegend": true,
    "showGrid": true
  }
}
```

**Tabs** (per-tab `children` sub-trees):

```json
{
  "id": "tb1",
  "type": "tabs",
  "props": {
    "tabs": [
      { "value": "overview", "label": "Overview" },
      { "value": "details", "label": "Details" }
    ],
    "defaultTab": "overview"
  },
  "children": [
    {
      "id": "tab-overview",
      "type": "tab-panel",
      "props": { "value": "overview" },
      "children": [
        { "id": "h1", "type": "heading", "props": { "text": "Orders", "level": "h2" } },
        { "id": "t1", "type": "table", "props": { "dataView": { "collection": "orders", "fields": ["id", "status", "total"], "limit": 25 } } }
      ]
    },
    {
      "id": "tab-details",
      "type": "tab-panel",
      "props": { "value": "details" },
      "children": [
        { "id": "txt1", "type": "text", "props": { "content": "Detail content." } }
      ]
    }
  ]
}
```

> **Tabs children model (authoritative).** A `tabs` node owns N **`tab-panel`** child nodes, one per
> entry in `props.tabs[]`, matched by `value`. Each `tab-panel` is a normal container node (its own
> `children` are an ordinary sub-tree, drop-target in 2c). `tab-panel` is registered as an internal
> built-in (`category:'layout'`, **not** shown in the palette — see §3.2) so `widgetRegistry.get` never
> falls through to the unknown descriptor for it. This keeps the whole structure inside the standard
> `PageComponent[]` tree that `treeOps` already mutate; no special "tabs only" tree shape.

**Nav** (static items):

```json
{
  "id": "nv1",
  "type": "nav",
  "props": {
    "orientation": "horizontal",
    "items": [
      { "id": "home", "label": "Home", "path": "/app/p/home" },
      { "id": "orders", "label": "Orders", "path": "/app/p/orders" }
    ]
  }
}
```

**Icon / Link / Image:**

```json
[
  { "id": "ic1", "type": "icon", "props": { "name": "shopping-cart", "size": 20, "color": "#3B82F6" } },
  { "id": "lk1", "type": "link", "props": { "label": "View orders", "to": "/app/p/orders" } },
  { "id": "lk2", "type": "link", "props": { "label": "Docs", "href": "https://kelta.io/docs", "newTab": true } },
  { "id": "im1", "type": "image", "props": { "src": { "$bind": "record.logoUrl", "mode": "path" }, "alt": "Logo", "objectFit": "contain" } }
]
```

### 2.6 Before / after

```
BEFORE 2g (after 2f): registry has heading/text/button/image/card/container/grid/row/column/divider/
                      table/list/repeater/field-value/form + typed inputs + nav?/tabs? absent or
                      stubbed; no chart; image is the bare 2a built-in (src/alt only).

AFTER 2g: + chart (recharts, bound array) + tabs (radix, per-tab children) + nav (@kelta/components
          Navigation) + icon (lucide by name) + link (router/href, bindable) + image polish
          (bindable src/alt + object-fit). All first-class descriptors; one Render per widget;
          editor and runtime share it.
```

---

## 3. Data & API contracts

All TS lives under `kelta-ui/app/src/pages/PageBuilderPage/`. **No backend / API / `config`-schema
change in 2g** beyond using `props` shapes already permitted by the v2 `PageComponent` (`props:
Record<string, PropValue>`, `children?: PageComponent[]`). The new prop shapes below are plain
`PropValue` data inside `props` and round-trip untouched (the server never parses them).

### 3.1 `chart` descriptor (concrete TS)

```tsx
// widgets/builtins/chart.tsx
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  LineChart,
  Line,
  PieChart,
  Pie,
  Cell,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from 'recharts'
import type { WidgetDescriptor } from '../types'

/** One plotted series, mapped to a numeric key in each row of the bound array. */
interface ChartSeries {
  key: string
  label?: string
  color?: string
}

const DEFAULT_COLORS = ['#3B82F6', '#06B6D4', '#8B5CF6', '#10B981', '#F59E0B', '#EF4444']

/** Editor placeholder so the designer sees a shape with no live data source. */
const EDITOR_SAMPLE = [
  { __x: 'A', total: 12, count: 4 },
  { __x: 'B', total: 19, count: 7 },
  { __x: 'C', total: 9, count: 3 },
  { __x: 'D', total: 22, count: 9 },
]

function asArray(v: unknown): Array<Record<string, unknown>> {
  return Array.isArray(v) ? (v as Array<Record<string, unknown>>) : []
}

export const chartWidget: WidgetDescriptor = {
  type: 'chart',
  label: 'Chart',
  icon: '∿',
  category: 'data',
  acceptsChildren: false,
  defaultProps: {
    chartType: 'bar',
    xKey: '',
    series: [],
    height: 300,
    showLegend: true,
    showGrid: true,
  },
  propSchema: [
    // `dataView` is bindable to a page data source array, e.g. { $bind:'data.orders', mode:'path' }.
    { key: 'dataView', label: 'Data source', kind: 'expression', bindable: true, group: 'Data' },
    {
      key: 'chartType',
      label: 'Chart type',
      kind: 'select',
      options: [
        { value: 'bar', label: 'Bar' },
        { value: 'line', label: 'Line' },
        { value: 'pie', label: 'Pie' },
      ],
      group: 'Data',
    },
    { key: 'xKey', label: 'X axis key', kind: 'text', group: 'Data' },
    // `series` is edited by a dedicated array editor the 2b inspector renders for kind:'series'.
    { key: 'series', label: 'Series', kind: 'expression', group: 'Data' },
    { key: 'height', label: 'Height (px)', kind: 'number', group: 'Appearance' },
    { key: 'showLegend', label: 'Show legend', kind: 'boolean', group: 'Appearance' },
    { key: 'showGrid', label: 'Show grid', kind: 'boolean', group: 'Appearance' },
  ],
  Render: ({ node, mode }) => {
    const props = node.props as Record<string, unknown>
    const chartType = (props.chartType as string) || 'bar'
    const xKey = (props.xKey as string) || (mode === 'editor' ? '__x' : '')
    const series = Array.isArray(props.series) ? (props.series as unknown as ChartSeries[]) : []
    const height = typeof props.height === 'number' ? props.height : 300
    const showLegend = props.showLegend !== false
    const showGrid = props.showGrid !== false

    // In 2d, `dataView` ({$bind:'data.orders'}) is resolved to an array before Render is called.
    // 2g consumes the resolved value. With none (or in editor), fall back to EDITOR_SAMPLE.
    const resolved = asArray(props.dataView)
    const data = resolved.length > 0 ? resolved : mode === 'editor' ? EDITOR_SAMPLE : []
    const effectiveSeries: ChartSeries[] =
      series.length > 0
        ? series
        : mode === 'editor'
          ? [
              { key: 'total', label: 'Total', color: DEFAULT_COLORS[0] },
              { key: 'count', label: 'Count', color: DEFAULT_COLORS[1] },
            ]
          : []

    if (data.length === 0) {
      return (
        <div
          className="flex h-[var(--chart-h)] w-full items-center justify-center rounded-lg border border-dashed border-border text-sm text-muted-foreground"
          style={{ ['--chart-h' as string]: `${height}px` }}
          data-testid="page-node-chart-empty"
        >
          No data
        </div>
      )
    }

    return (
      <div className="w-full" data-testid="page-node-chart" data-chart-type={chartType}>
        <ResponsiveContainer width="100%" height={height}>
          {chartType === 'pie' ? (
            <PieChart>
              {showLegend && <Legend />}
              <Tooltip />
              <Pie
                data={data}
                dataKey={effectiveSeries[0]?.key ?? 'value'}
                nameKey={xKey}
                cx="50%"
                cy="50%"
                outerRadius="80%"
              >
                {data.map((_, i) => (
                  <Cell key={i} fill={DEFAULT_COLORS[i % DEFAULT_COLORS.length]} />
                ))}
              </Pie>
            </PieChart>
          ) : chartType === 'line' ? (
            <LineChart data={data}>
              {showGrid && <CartesianGrid strokeDasharray="3 3" className="stroke-border" />}
              <XAxis dataKey={xKey} className="text-xs" />
              <YAxis className="text-xs" />
              <Tooltip />
              {showLegend && <Legend />}
              {effectiveSeries.map((s, i) => (
                <Line
                  key={s.key}
                  type="monotone"
                  dataKey={s.key}
                  name={s.label ?? s.key}
                  stroke={s.color ?? DEFAULT_COLORS[i % DEFAULT_COLORS.length]}
                  dot={false}
                />
              ))}
            </LineChart>
          ) : (
            <BarChart data={data}>
              {showGrid && <CartesianGrid strokeDasharray="3 3" className="stroke-border" />}
              <XAxis dataKey={xKey} className="text-xs" />
              <YAxis className="text-xs" />
              <Tooltip />
              {showLegend && <Legend />}
              {effectiveSeries.map((s, i) => (
                <Bar
                  key={s.key}
                  dataKey={s.key}
                  name={s.label ?? s.key}
                  fill={s.color ?? DEFAULT_COLORS[i % DEFAULT_COLORS.length]}
                />
              ))}
            </BarChart>
          )}
        </ResponsiveContainer>
      </div>
    )
  },
}
```

> **Chart data-binding contract.** The chart is **pure**: it never fetches. Its `dataView` prop is a
> binding (`{ $bind:'data.<sourceName>', mode:'path' }`) that 2d's `resolveBindings(props, scope)`
> replaces — **before `Render` runs** — with the array result of the page data source (filled by
> `usePageDataSources` in `CustomPage` / preview). Each array element is one row (a flat record); `xKey`
> names the category/x field, and each `series[].key` names a numeric field on the row. This matches the
> recharts contract used in `MonitoringOverviewPage.tsx` (`<LineChart data={data}><XAxis dataKey=… />
> <Line dataKey=… />`). Until 2d resolves bindings, `props.dataView` is the literal `Binding` object →
> `asArray` returns `[]` → editor shows the sample, runtime shows "No data". This degrades safely and
> needs no change when 2d lands.

### 3.2 `tabs` + `tab-panel` descriptors (concrete TS)

```tsx
// widgets/builtins/tabs.tsx
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import type { WidgetDescriptor } from '../types'
import type { PageComponent } from '../../model/pageModel'

interface TabDef {
  value: string
  label: string
}

function readTabs(props: Record<string, unknown>): TabDef[] {
  return Array.isArray(props.tabs)
    ? (props.tabs as unknown as TabDef[]).filter((t) => t && typeof t.value === 'string')
    : []
}

/** Find the tab-panel child whose props.value matches a tab. */
function panelFor(children: PageComponent[] | undefined, value: string): PageComponent | undefined {
  return (children ?? []).find(
    (c) => c.type === 'tab-panel' && (c.props as Record<string, unknown>).value === value,
  )
}

export const tabsWidget: WidgetDescriptor = {
  type: 'tabs',
  label: 'Tabs',
  icon: '⊓',
  category: 'navigation',
  acceptsChildren: true, // children are tab-panel nodes
  defaultProps: {
    tabs: [
      { value: 'tab-1', label: 'Tab one' },
      { value: 'tab-2', label: 'Tab two' },
    ],
    defaultTab: 'tab-1',
  },
  propSchema: [
    // 2b renders a tab-list editor for kind:'expression' here (add/remove/rename/reorder tabs,
    // keeping a matching tab-panel child in sync via treeOps).
    { key: 'tabs', label: 'Tabs', kind: 'expression', group: 'Tabs' },
    { key: 'defaultTab', label: 'Default tab', kind: 'text', group: 'Tabs' },
  ],
  Render: ({ node, renderChild }) => {
    const props = node.props as Record<string, unknown>
    const tabs = readTabs(props)
    if (tabs.length === 0) {
      return (
        <div className="text-xs text-muted-foreground" data-testid="page-node-tabs-empty">
          No tabs configured.
        </div>
      )
    }
    const defaultTab = (props.defaultTab as string) || tabs[0].value
    return (
      <Tabs defaultValue={defaultTab} className="w-full" data-testid="page-node-tabs">
        <TabsList>
          {tabs.map((t) => (
            <TabsTrigger key={t.value} value={t.value} data-testid={`tab-trigger-${t.value}`}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        {tabs.map((t) => {
          const panel = panelFor(node.children, t.value)
          return (
            <TabsContent key={t.value} value={t.value} data-testid={`tab-content-${t.value}`}>
              {panel
                ? (panel.children ?? []).map((child) => renderChild(child))
                : null}
            </TabsContent>
          )
        })}
      </Tabs>
    )
  },
}

/**
 * Internal container node, one per tab. NOT shown in the palette (see registerBuiltins).
 * It only renders its children when rendered standalone (e.g. via treeOps moves in the canvas);
 * the tabs Render reaches into panel.children directly. Registered so widgetRegistry.get never
 * falls through to the unknown descriptor for 'tab-panel'.
 */
export const tabPanelWidget: WidgetDescriptor = {
  type: 'tab-panel',
  label: 'Tab panel',
  icon: '▢',
  category: 'layout',
  acceptsChildren: true,
  defaultProps: { value: '' },
  propSchema: [{ key: 'value', label: 'Tab value', kind: 'text', group: 'Tab' }],
  Render: ({ node, renderChild }) => (
    <div data-testid="page-node-tab-panel">
      {(node.children ?? []).map((child) => renderChild(child))}
    </div>
  ),
}
```

> **Tabs children model.** `tabs.children` is an array of `tab-panel` nodes keyed by `value`; each
> `tab-panel.children` is the per-tab sub-tree. The `tabs` `Render` renders the radix list from
> `props.tabs[]` and, per tab, looks up the matching `tab-panel` and maps its `children` through the
> shared `renderChild`. This keeps everything in the standard `PageComponent[]` tree (2c's `treeOps`
> insert/move/remove operate on `tab-panel.children` like any container). When a tab is added/removed in
> the inspector (2b), the editor keeps `props.tabs[]` and the `tab-panel` children in sync.

### 3.3 `nav` descriptor (concrete TS, wraps `@kelta/components` `Navigation`)

```tsx
// widgets/builtins/nav.tsx
import { Navigation, type MenuItem } from '@kelta/components'
import type { WidgetDescriptor } from '../types'

function readItems(props: Record<string, unknown>): MenuItem[] {
  // items may be a literal array (authored) or a 2d-resolved bound array.
  return Array.isArray(props.items) ? (props.items as unknown as MenuItem[]) : []
}

export const navWidget: WidgetDescriptor = {
  type: 'nav',
  label: 'Nav',
  icon: '☰',
  category: 'navigation',
  acceptsChildren: false,
  defaultProps: {
    orientation: 'horizontal',
    items: [],
  },
  propSchema: [
    {
      key: 'orientation',
      label: 'Orientation',
      kind: 'select',
      options: [
        { value: 'horizontal', label: 'Horizontal' },
        { value: 'vertical', label: 'Vertical' },
      ],
      group: 'Nav',
    },
    // 2b renders a menu-items editor for kind:'expression' (add/remove items with id/label/path).
    { key: 'items', label: 'Menu items', kind: 'expression', bindable: true, group: 'Nav' },
  ],
  Render: ({ node }) => {
    const props = node.props as Record<string, unknown>
    const items = readItems(props)
    const orientation = props.orientation === 'vertical' ? 'vertical' : 'horizontal'
    if (items.length === 0) {
      return (
        <div className="text-xs text-muted-foreground" data-testid="page-node-nav-empty">
          No menu items.
        </div>
      )
    }
    return (
      <Navigation
        items={items}
        orientation={orientation}
        testId="page-node-nav"
      />
    )
  },
}
```

> `Navigation` (verified export from `@kelta/components` `index.ts`) integrates with `react-router-dom`
> (`useNavigate`/`useLocation`) and filters by `currentUser.roles`. 2g passes static `items` and
> `orientation`; `currentUser` is left default (`null` → no role filtering) since page-builder nav is
> public-page chrome. `MenuItem` (`{ id, label, path?, icon?, children?, roles?, onClick? }`) is the
> authored shape. `Navigation` renders its own `data-testid` from `testId` — we pass `page-node-nav`.

### 3.4 `icon` descriptor (concrete TS, lucide by name)

```tsx
// widgets/builtins/icon.tsx
import { icons, HelpCircle } from 'lucide-react'
import type { WidgetDescriptor } from '../types'

/** lucide exports an `icons` map keyed by PascalCase name; convert kebab/snake input. */
function toPascal(name: string): string {
  return name
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join('')
}

export const iconWidget: WidgetDescriptor = {
  type: 'icon',
  label: 'Icon',
  icon: '★',
  category: 'content',
  acceptsChildren: false,
  defaultProps: { name: 'star', size: 20, color: '' },
  propSchema: [
    { key: 'name', label: 'Icon name', kind: 'text', bindable: true, group: 'Icon' },
    { key: 'size', label: 'Size (px)', kind: 'number', group: 'Icon' },
    { key: 'color', label: 'Color', kind: 'color', group: 'Icon' },
  ],
  Render: ({ node }) => {
    const props = node.props as Record<string, unknown>
    const rawName = (props.name as string) || 'star'
    const size = typeof props.size === 'number' ? props.size : 20
    const color = (props.color as string) || undefined
    // icons is keyed by PascalCase; fall back to HelpCircle for an unknown name (no crash).
    const LucideIcon = icons[toPascal(rawName) as keyof typeof icons] ?? HelpCircle
    return (
      <LucideIcon
        size={size}
        color={color}
        className={color ? undefined : 'text-foreground'}
        data-testid="page-node-icon"
        aria-hidden="true"
      />
    )
  },
}
```

> Verified: `lucide-react@^0.563.0` exports an `icons` object (`name → component`) and named PascalCase
> components (e.g. `HelpCircle`). `dynamicIconImports` is **not** exported in this version, so the static
> `icons` map is the correct by-name lookup (no async/dynamic-import path). Unknown names degrade to
> `HelpCircle` rather than throwing.

### 3.5 `link` descriptor (concrete TS, router/href, bindable)

```tsx
// widgets/builtins/link.tsx
import { Link } from 'react-router-dom'
import type { WidgetDescriptor } from '../types'
import { safeUrl } from '../urlSafety'

export const linkWidget: WidgetDescriptor = {
  type: 'link',
  label: 'Link',
  icon: '↗',
  category: 'content',
  acceptsChildren: false,
  defaultProps: { label: 'Link', to: '', href: '', newTab: false },
  propSchema: [
    { key: 'label', label: 'Label', kind: 'text', bindable: true, group: 'Link' },
    { key: 'to', label: 'Internal route', kind: 'text', bindable: true, group: 'Link' },
    { key: 'href', label: 'External URL', kind: 'text', bindable: true, group: 'Link' },
    { key: 'newTab', label: 'Open in new tab', kind: 'boolean', group: 'Link' },
  ],
  Render: ({ node, mode }) => {
    const props = node.props as Record<string, unknown>
    const label = (props.label as string) || 'Link'
    const to = (props.to as string) || ''
    // `href` is author/data-controlled (a {$bind} can resolve to `javascript:`/`data:`); scheme-validate
    // against the shared allow-list ({http, https, mailto, tel, relative}) before rendering <a href>.
    // safeUrl returns '' for a rejected scheme, which neutralizes the link (renders inert, no navigation).
    const href = safeUrl((props.href as string) || '')
    const newTab = props.newTab === true
    const className =
      'text-primary underline-offset-2 hover:underline focus-visible:outline-ring'

    // External href wins when set (and passed the scheme check); else an internal router link.
    if (href) {
      return (
        <a
          href={href}
          target={newTab ? '_blank' : undefined}
          rel={newTab ? 'noopener noreferrer' : undefined}
          className={className}
          data-testid="page-node-link"
        >
          {label}
        </a>
      )
    }
    // In editor mode, never navigate — render a non-navigating anchor so canvas clicks select.
    if (mode === 'editor' || !to) {
      return (
        <a href={to || '#'} className={className} data-testid="page-node-link" onClick={(e) => e.preventDefault()}>
          {label}
        </a>
      )
    }
    return (
      <Link to={to} target={newTab ? '_blank' : undefined} className={className} data-testid="page-node-link">
        {label}
      </Link>
    )
  },
}
```

> `link` here is **navigation-by-target only** (router `to` / external `href`). Wiring a `link`/`button`
> click to a `PageAction` (`runFlow`/`createRecord`/…) is **2e** — out of scope. In `editor` mode the
> link never navigates (prevents the canvas from leaving the builder). `to`/`href`/`label` are bindable
> so 2d can drive e.g. `to: {{ '/app/p/' & record.slug }}`. **Security:** because `href` is
> author/data-controlled, it is passed through `safeUrl` (§3.9) which rejects any scheme outside
> `{http, https, mailto, tel, relative}` — a bound `javascript:` / `data:` href is neutralized to `''`
> (inert link), reusing the same allow-list as 2e's `navigate`/`openUrl`.

### 3.6 `image` polish (upgrades the 2a built-in)

```tsx
// widgets/builtins/image.tsx  (REPLACES the bare 2a image built-in)
import type { WidgetDescriptor } from '../types'
import { safeUrl } from '../urlSafety'

const OBJECT_FIT = ['cover', 'contain', 'fill', 'none'] as const
type ObjectFit = (typeof OBJECT_FIT)[number]

export const imageWidget: WidgetDescriptor = {
  type: 'image',
  label: 'Image',
  icon: '▦',
  category: 'content',
  acceptsChildren: false,
  defaultProps: { src: '', alt: 'Image', objectFit: 'cover' },
  propSchema: [
    { key: 'src', label: 'Source URL', kind: 'text', bindable: true, group: 'Image' },
    { key: 'alt', label: 'Alt text', kind: 'text', bindable: true, group: 'Image' },
    {
      key: 'objectFit',
      label: 'Fit',
      kind: 'select',
      options: OBJECT_FIT.map((v) => ({ value: v, label: v })),
      group: 'Image',
    },
  ],
  Render: ({ node, mode }) => {
    const props = node.props as Record<string, unknown>
    // `src` is author/data-controlled (bindable); scheme-validate before rendering <img src> so a bound
    // `javascript:`/`data:` URL is neutralized to '' (→ placeholder/empty), per §3.9. Same allow-list as link.
    const src = safeUrl((props.src as string) || '')
    const alt = (props.alt as string) || 'Image'
    const fit = (OBJECT_FIT as readonly string[]).includes(props.objectFit as string)
      ? (props.objectFit as ObjectFit)
      : 'cover'
    // No src: placeholder in editor (matches 2a), empty in runtime.
    if (!src) {
      if (mode === 'editor') {
        return (
          <div
            className="flex h-32 w-full items-center justify-center rounded-lg border border-dashed border-border text-xs text-muted-foreground"
            data-testid="page-node-image-placeholder"
          >
            No image source
          </div>
        )
      }
      return null
    }
    return (
      <img
        src={src}
        alt={alt}
        className="max-w-full rounded-lg"
        style={{ objectFit: fit }}
        data-testid="page-node-image"
      />
    )
  },
}
```

> Polish over the 2a `image` built-in (which had `src`/`alt` only and a fixed placeholder): `src`/`alt`
> are now declared **`bindable`** (so 2d can drive `src: {{record.logoUrl}}`), and an `objectFit` select
> controls CSS `object-fit`. `data-testid` unchanged (`page-node-image` / `page-node-image-placeholder`)
> so 2a parity tests stay green. **Security:** `src` is scheme-validated via `safeUrl` (§3.9) before it
> reaches `<img>`; a bound `javascript:`/`data:` URL is rejected to `''` → the no-src branch (placeholder
> in editor, `null` in runtime), so it can never become a live `<img src>` attribute.

### 3.7 Binding & scope dependency (note, not a blocker)

These widgets declare `bindable: true` on the relevant `propSchema` entries (chart `dataView`, nav
`items`, icon `name`, link `label`/`to`/`href`, image `src`/`alt`).

> **Resolved-node invariant (per 2a).** Every 2g descriptor `Render` receives props that are **already
> resolved** by the time it runs — `chart`'s `dataView`/bound `series`, and `nav`/`tabs`' bound props all
> arrive as their final array/string values, never as live `Binding` objects, exactly per the parent's
> authoritative [resolved-node invariant](../../page-builder-parity.md#widget-registry) (`renderTree.tsx`)
> and 2a §3.4's `renderNode`. The parent states descriptors **must not** call `resolveBindings`
> themselves (the sole exception is `list`/`repeater` re-resolving children under each per-row `item`
> scope — **none of the 2g widgets are that exception**). **So 2g descriptors do NOT call
> `resolveBindings` themselves** — resolution is the single reserved call point in 2d's `renderNode`
> (`resolveBindings(node.props, scope)` *before* `descriptor.Render`). The 2g `Render` bodies only read
> `node.props`; they contain no scope plumbing, no `resolveBindings` import, and no binding-aware
> branching beyond the degrade-safe type guards below.

In 2a/2g (pre-2d) the resolver is the identity no-op shim, so a literal `Binding` object can still be
present in `node.props`; once 2d makes the resolver real, the same `Render` reads the resolved value with
**no edit**. With an unresolved `Binding` object present (pre-2d), every widget degrades gracefully:

| Widget | Unresolved-binding behavior |
|--------|-----------------------------|
| `chart` | `asArray(Binding)` → `[]` → editor sample / runtime "No data" |
| `nav` | `readItems(Binding)` → `[]` → "No menu items" |
| `icon` | non-string name → falls back to `star`/`HelpCircle` |
| `link` | non-string label/to → `'Link'` / `''`; rejected/`javascript:` href (§3.9) → `''` (renders inert) |
| `image` | non-string src, or rejected/`javascript:` src (§3.9) → placeholder (editor) / `null` (runtime) |

No `renderTree`/registry/types signature changes are needed in 2g (the resolved-node invariant is owned
by 2a's `renderNode`; 2g consumes it).

### 3.8 No new model / config / endpoint types

- `WidgetDescriptor` / `PropFieldSchema` / `PropFieldKind` / `WidgetRenderProps` — **unchanged** from
  2a §3.2. The chart series, tab list, and nav items are stored as ordinary `PropValue` arrays inside
  `props`; the 2b inspector renders bespoke editors for them by keying on `kind` + `key` (an inspector
  concern, not a registry-type change). If 2b needs distinct prop-field kinds (e.g. `'series'`,
  `'tab-list'`, `'menu-items'`), those are added to `PropFieldKind` **in 2b**, not here — 2g uses the
  existing `'expression'` kind as the schema placeholder so the descriptors compile against the 2a types.
- `PageComponent`, `PageConfig` — **unchanged**. `tabs.children`/`tab-panel.children` use the existing
  `children?: PageComponent[]`.
- Render contract / `PageRenderService` — **unchanged** (pure pass-through; tree round-trips in `config`).

### 3.9 URL scheme allow-list — `link.href` / `image.src` (security)

`link.href` and `image.src` accept author- and **data-controlled** URLs: both props are `bindable: true`,
so a `{ $bind }` (resolved by 2d before `Render`) can evaluate to a hostile `javascript:` or `data:` URL.
Rendering such a value straight into an `<a href>` / `<img src>` is an XSS / data-exfiltration vector.
Per the parent's [Security — binding & action output safety](../../page-builder-parity.md#security--binding--action-output-safety),
both props are passed through a shared **scheme allow-list** — `{ http, https, mailto, tel, relative }` —
**before render**, rejecting everything else (notably `javascript:` and `data:`). This is the **same
allow-list 2e applies** to its `navigate`/`openUrl` action targets; 2g and 2e share one `safeUrl` helper
so the rule has a single source of truth.

```tsx
// widgets/urlSafety.ts  (shared by link/image here and by 2e's navigate/openUrl)
const ALLOWED_SCHEMES = new Set(['http:', 'https:', 'mailto:', 'tel:'])

/**
 * Returns `url` if it is safe to place in an `href`/`src`, else `''`.
 * Allows the {http, https, mailto, tel} schemes plus scheme-relative/relative URLs
 * (no scheme — in-app paths like `/app/p/orders`, `#anchor`, `./img.png`). Rejects
 * `javascript:`, `data:`, `vbscript:`, `file:`, and any other absolute scheme.
 */
export function safeUrl(url: string): string {
  const trimmed = url.trim()
  if (trimmed === '') return ''
  // A relative/anchor/path URL has no scheme prefix → allowed (the `relative` member of the allow-list).
  // Match an explicit scheme: leading `<scheme>:` per RFC 3986 (letter, then letters/digits/+/-/.).
  const schemeMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(trimmed)
  if (!schemeMatch) return trimmed // relative — no scheme to reject
  const scheme = schemeMatch[1].toLowerCase() + ':'
  return ALLOWED_SCHEMES.has(scheme) ? trimmed : ''
}
```

> **Why a shared helper, not per-widget inline checks.** 2e's `navigate`/`openUrl` must apply the identical
> rule; duplicating the scheme list invites drift (a scheme allowed in one path but blocked in another).
> `widgets/urlSafety.ts` is the single allow-list both slices import. `link` and `image` call `safeUrl`
> at the top of `Render`; a rejected URL collapses to `''`, which routes through each widget's existing
> empty-`href`/empty-`src` branch (inert link / placeholder-or-`null` image) — **no crash, no live hostile
> attribute, no new error surface**. The check runs *after* 2d resolves a binding, so it catches both
> literal author input and `{$bind}`-derived URLs.

### 3.10 i18n (new widget strings + empty states)

Per the parent's [i18n](../../page-builder-parity.md#i18n) rule, **every new user-facing string this slice
introduces goes through `useI18n`/`t()` with `builder.*` keys** — the builder is fully `useI18n`-driven;
do not hardcode the English literals shown in the §3 code (they are illustrative). The 2g surface strings
are the widget **empty states**, plus the new inspector labels (rendered by 2b from `propSchema.label`,
which 2b funnels through `t()` — 2g supplies stable keys via the schema). The code samples above inline
the English for readability; the shipped `Render`/`propSchema` resolve these keys instead:

| Key | English (default) | Used by |
|-----|-------------------|---------|
| `builder.widget.chart.empty` | "No data" | `chart` runtime empty state (`page-node-chart-empty`) |
| `builder.widget.nav.empty` | "No menu items." | `nav` empty state (`page-node-nav-empty`) |
| `builder.widget.tabs.empty` | "No tabs configured." | `tabs` empty state (`page-node-tabs-empty`) |
| `builder.widget.image.empty` | "No image source" | `image` editor placeholder (`page-node-image-placeholder`) |
| `builder.widget.{chart,tabs,nav,icon,link,image}.label` | "Chart" / "Tabs" / "Nav" / "Icon" / "Link" / "Image" | palette + inspector titles (`descriptor.label`) |
| `builder.widget.*.field.*` | the `propSchema[].label` strings (e.g. "Data source", "Chart type", "Menu items", "Icon name", "External URL", "Fit") | 2b inspector field labels |

> **Pattern.** Each widget `Render` calls `useI18n()` (or receives `t` via `WidgetRenderProps`, consistent
> with how 2a's builtins localize) and emits `t('builder.widget.<type>.empty')` for its empty state rather
> than a literal. `descriptor.label` and `propSchema[].label` stay as the **default key fallbacks** the 2b
> inspector localizes (2b owns funneling schema labels through `t()`; 2g owns adding the keys + defaults to
> the `builder.*` catalog). New keys are added to the same `useI18n` message catalog the existing builder
> strings live in, in this PR. No string this slice adds is hardcoded English in shipped code.

---

## 4. DB migrations

**None — FE only.** Every widget's data nests inside the existing `ui-pages.config` JSON column
(component `props` / `children`); there is no DDL, no Flyway version consumed (head remains **V146**,
next new migration **V147**), and no NATS subject or payload change. The render contract is untouched.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/pages/PageBuilderPage/`. **One file per widget** under
`widgets/builtins/`, the registration edit, plus one shared `widgets/urlSafety.ts` helper (§3.9). No
edits to `renderTree.tsx`, `registry.ts`, or `types.ts` (the 2a contracts already support these
descriptors; the resolved-node invariant lives in 2a's `renderNode` and 2g consumes it unchanged).

### 5.1 New files — `widgets/builtins/` (+ shared `widgets/urlSafety.ts`)

| File | Contents |
|------|----------|
| `widgets/builtins/chart.tsx` | §3.1 — `chartWidget` descriptor. `recharts` `Bar`/`Line`/`Pie` keyed on `props.chartType`; `props.dataView` (resolved array, 2d) → `data`; `props.xKey` → axis; `props.series[]` → series. Editor sample fallback. `data-testid="page-node-chart"` / `page-node-chart-empty`. |
| `widgets/builtins/tabs.tsx` | §3.2 — `tabsWidget` **and** `tabPanelWidget`. Radix `Tabs`/`TabsList`/`TabsTrigger`/`TabsContent` from `@/components/ui/tabs`; per-tab `renderChild` over the matching `tab-panel.children`. `data-testid="page-node-tabs"` / `tab-trigger-<v>` / `tab-content-<v>` / `page-node-tab-panel`. |
| `widgets/builtins/nav.tsx` | §3.3 — `navWidget`. Wraps `@kelta/components` `Navigation` with `items`/`orientation`. `testId="page-node-nav"`. |
| `widgets/builtins/icon.tsx` | §3.4 — `iconWidget`. `lucide-react` `icons[PascalName]` with `HelpCircle` fallback; `size`/`color`. `data-testid="page-node-icon"`. |
| `widgets/builtins/link.tsx` | §3.5 — `linkWidget`. `react-router-dom` `Link` for `to`, `<a>` for external `href`; editor mode inert. `data-testid="page-node-link"`. |
| `widgets/builtins/image.tsx` | §3.6 — `imageWidget`, **replacing** the 2a bare image built-in. Adds `objectFit`; `src`/`alt` bindable; `src` passed through `safeUrl`. `data-testid="page-node-image"` / `page-node-image-placeholder`. |
| `widgets/urlSafety.ts` | §3.9 — shared `safeUrl(url)` scheme allow-list (`{http, https, mailto, tel, relative}`; rejects `javascript:`/`data:` → `''`). Imported by `link`/`image` here and by 2e's `navigate`/`openUrl`. (If 2e ships first and already created it, 2g imports the existing module — single source of truth; do not fork.) |

> If 2a already created `widgets/builtins/image.tsx`, this slice **edits** it to the §3.6 version (adds
> `objectFit`, marks `src`/`alt` bindable, routes `src` through `safeUrl`, keeps the existing
> `data-testid`s). The 2a image parity test stays green; new tests assert `objectFit` and that a
> `javascript:` `src` is neutralized.

### 5.2 Registration — `widgets/builtins/index.ts`

Extend `registerBuiltins()` (from 2a §5.6) to register the new descriptors. Keep idempotency (the 2a
`let done = false` guard). `tab-panel` is registered so `widgetRegistry.get('tab-panel')` resolves, but
is **excluded from the palette** by 2b (the palette in 2b sources from `listByCategory`; add a
`paletteHidden?: boolean` only if 2b needs it — otherwise 2b filters `type==='tab-panel'`). 2g’s job is
registration; the palette filter is a 2b concern. Image is replaced (same `type:'image'` overwrites the
2a registration — `register()` is overwrite-by-`type`).

```ts
// widgets/builtins/index.ts  (additions)
import { chartWidget } from './chart'
import { tabsWidget, tabPanelWidget } from './tabs'
import { navWidget } from './nav'
import { iconWidget } from './icon'
import { linkWidget } from './link'
import { imageWidget } from './image' // upgraded (replaces 2a image)

let done = false
export function registerBuiltins(): void {
  if (done) return
  done = true
  // …2a built-ins (heading/text/button/card/container/table/form)…
  widgetRegistry.register(imageWidget) // upgraded image
  // 2c layout (grid/row/column/divider), 2d (list/repeater/field-value), 2f (typed inputs)…
  widgetRegistry.register(chartWidget)
  widgetRegistry.register(tabsWidget)
  widgetRegistry.register(tabPanelWidget)
  widgetRegistry.register(navWidget)
  widgetRegistry.register(iconWidget)
  widgetRegistry.register(linkWidget)
}
```

### 5.3 No edits to shared infra

- `widgets/renderTree.tsx` — **no change** (the `renderChild` plumbing the tabs/container widgets use is
  already provided by 2a `WidgetRenderProps.renderChild`).
- `widgets/registry.ts`, `widgets/types.ts` — **no change** (descriptors fit the 2a contracts; new prop
  arrays are plain `PropValue`).
- `PageBuilderPage.tsx` / `PageTreeRenderer.tsx` — **no change** (both already render via `RenderTree`
  after 2a; new types resolve through the registry).
- `componentRegistry.ts` — **no change** (these are built-ins, not plugin shims — explicitly first-class
  descriptors per the slice scope).

### 5.4 Registration sequencing note

This slice **depends on 2a** (`widgets/{types,registry,renderTree}.tsx` + `registerBuiltins()`). It is
independent of 2b/2c/2d/2e/2f at the descriptor level: the widgets render literal `props` today and
resolved `props` once 2d/2e land, with **no edits** required in those slices. The series/tab/nav inline
editors are 2b inspector work; until then the legacy `PropertyPanel` (still present per 2a §5.3(f)) edits
these via its raw `dataView`/JSON fields, which is enough to demo each widget.

---

## 6. Test plan

Vitest + Testing Library, matching the existing kelta-ui idiom (`PageBuilderPage.test.tsx`,
`pageConfig.test.ts`) and the 2a `widgets/*.test` suites. Each test calls `widgetRegistry.clear()` +
`registerBuiltins()` in `beforeEach` for isolation (per 2a §8). **One spec per widget**, each rendering
**from the descriptor** through `renderNode`/`<RenderTree>` (not the component directly) so the
single-render-path guarantee is exercised. Tests render within the builder's i18n provider (as 2a's
suites do) so the localized empty states (§3.10) resolve; assert empty states by `data-testid`, not by
the English literal, so the i18n indirection stays test-stable.

### 6.1 `widgets/builtins/chart.test.tsx`

- Renders from descriptor: `chart` node with `chartType:'bar'`, `xKey:'month'`,
  `series:[{key:'total'}]`, and `props.dataView` set to a literal array → asserts
  `data-testid="page-node-chart"`, `data-chart-type="bar"`, and that recharts mounts (mock
  `ResponsiveContainer` to a fixed size in jsdom, as `MonitoringOverviewPage` tests do, since
  `ResponsiveContainer` needs a measured box).
- **Binds data:** passing a resolved 3-row array renders the series; `chartType:'line'` →
  `data-chart-type="line"`; `chartType:'pie'` → `PieChart` path.
- **Editor sample fallback:** `mode="editor"` with no `dataView` renders the sample (non-empty), not
  "No data". `mode="runtime"` with no data → `page-node-chart-empty` "No data".
- Unresolved `Binding` object as `dataView` → treated as empty (degrade-safe).

### 6.2 `widgets/builtins/tabs.test.tsx`

- Renders the tab list from `props.tabs[]` (`tab-trigger-overview`, `tab-trigger-details`).
- **Tab switching:** default tab's `tab-content-overview` is visible; clicking `tab-trigger-details`
  shows `tab-content-details` and hides overview (radix `data-[state=inactive]:hidden`).
- **Per-tab children:** the active tab renders its `tab-panel.children` sub-tree via `renderChild`
  (assert a child `heading`/`text` `data-testid` appears under the active `tab-content`).
- Empty `tabs` → `page-node-tabs-empty`.

### 6.3 `widgets/builtins/nav.test.tsx`

- Renders `Navigation` from `props.items[]` with `data-testid="page-node-nav"`; item labels present.
- `orientation:'vertical'` passes through (assert `Navigation` receives it — class/aria).
- Empty items → `page-node-nav-empty`. Wrap in `MemoryRouter` (Navigation uses `react-router-dom`).

### 6.4 `widgets/builtins/icon.test.tsx`

- Known name (`'star'` / `'shopping-cart'`) renders an svg (`page-node-icon`), `size`/`color` applied.
- Kebab/snake → PascalCase conversion works (`'shopping-cart'` resolves).
- **Unknown name** (`'not-a-real-icon'`) renders the `HelpCircle` fallback, no throw.

### 6.5 `widgets/builtins/link.test.tsx`

- External `href` → `<a target/rel>`; internal `to` (runtime) → router `Link` (assert `href` attr).
- `newTab:true` → `target="_blank"` + `rel="noopener noreferrer"`.
- **Editor mode** internal `to` → inert anchor (click `preventDefault`, no navigation). Wrap in
  `MemoryRouter`.
- **Security — scheme allow-list:** `href:'javascript:alert(1)'` is **neutralized** — `safeUrl` rejects
  it to `''`, so no `<a>` carries a `javascript:` href (the node falls through to the inert internal
  branch). Same for `href:'data:text/html,...'`. A `mailto:`/`tel:` href is **allowed**.

### 6.6 `widgets/builtins/image.test.tsx`

- `src` set → `<img src alt>` with `style.objectFit` from `props.objectFit`; invalid fit → `cover`.
- No `src`, `mode="editor"` → `page-node-image-placeholder`; `mode="runtime"` → renders nothing.
- Parity: `data-testid`s unchanged from 2a.
- **Security — scheme allow-list:** `src:'javascript:...'` / `src:'data:image/svg+xml,...'` is rejected by
  `safeUrl` → no `<img src>` renders; the node falls through to the no-src branch (placeholder in editor,
  `null` in runtime). An `https:` `src` renders normally.

### 6.7 `widgets/urlSafety.test.ts` (scheme allow-list)

- Allows `http:`/`https:`/`mailto:`/`tel:` and relative URLs (`/app/p/x`, `#a`, `./img.png`, ``) → returned
  unchanged.
- **Rejects** `javascript:`, `data:`, `vbscript:`, `file:` (and mixed-case `JavaScript:`) → returns `''`.
- Whitespace-padded `  javascript:…  ` still rejected (trim-then-check).

### 6.8 `widgets/builtins/index.test.ts` (registration)

- After `registerBuiltins()`, `widgetRegistry.get('chart'|'tabs'|'tab-panel'|'nav'|'icon'|'link'|'image')`
  each returns a real descriptor (not the unknown default); `listByCategory('navigation')` includes
  `nav` + `tabs`; `listByCategory('data')` includes `chart`; `image` is the upgraded descriptor
  (has an `objectFit` prop-schema entry).

### 6.9 Shared render-path test (extend `widgets/renderTree.test.tsx` from 2a)

- Render a tree containing `chart`/`tabs`/`nav`/`icon`/`link`/`image` in both `mode:'editor'` and
  `mode:'runtime'`; assert each resolves through the **same descriptor `Render`** (no unknown
  fallback), confirming the editor-preview/runtime de-dup holds for the breadth widgets.

### 6.10 e2e

Playwright e2e is **post-deploy only** (project convention). Once deployed, the parent spec's
page-render e2e is extended with: author a page with a `chart` bound to a data source, a `tabs` widget
with child containers, and a `nav`; publish; load `/:tenant/app/p/<slug>`; assert the chart svg renders,
clicking a tab swaps content, and nav links navigate. No new e2e file ships in this slice; the breadth is
covered by the Vitest suites above.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` (line ~48, "Page builder / screen builder" row) | Add: "slice 2g — **widget breadth**: `chart` (recharts bar/line/pie bound to a page data-source array via `props.dataView` `$bind`), `tabs` (radix `@/components/ui/tabs`, per-tab `tab-panel` child containers rendered through the shared `renderChild`), `nav` (`@kelta/components` `Navigation`), `icon` (lucide-react `icons` map by name, `HelpCircle` fallback), `link` (router `Link` / external `<a>`, bindable label/target), and `image` polish (bindable `src`/`alt` + `object-fit`) — all first-class `WidgetDescriptor`s in `widgets/builtins/`, one `Render` shared by editor preview and runtime; no backend change." |
| `.claude/docs/playbooks.md` | Extend the 2a "Add a page component / widget" recipe with the **breadth widget** examples: a widget that wraps a reused component (`nav` → `Navigation`, `tabs` → `@/components/ui/tabs`), a widget that consumes a bound data-source array (`chart`), and the **internal-container pattern** (`tab-panel` registered but palette-hidden, children rendered via `renderChild`). |
| `.claude/docs/conventions.md` | If/when it documents page widgets: note (a) breadth widgets are **first-class descriptors**, never plugin shims; (b) the **chart data-binding contract** (pure widget; `dataView` is a `{$bind:'data.<source>'}` resolved to an array by 2d before `Render`; `xKey` + `series[].key` map rows to axes/series — widgets never fetch); (c) the **tabs children model** (`tab-panel` child per tab, sub-tree via `renderChild`); (d) the **URL scheme allow-list** (`link.href`/`image.src` pass through the shared `widgets/urlSafety.ts` `safeUrl`, same `{http,https,mailto,tel,relative}` list as 2e's `navigate`/`openUrl` — `javascript:`/`data:` rejected); (e) new widget strings + empty states go through `useI18n`/`t()` with `builder.widget.*` keys (no hardcoded English). |

Per CLAUDE.md Rule 6 these doc edits ship **in the same PR** as the code. No `architecture.md` /
`integrations.md` change (no backend, API, NATS, or external-integration surface touched — state
"N/A — FE-only, no backend/integration change" in the PR description). The new `builder.widget.*` i18n
keys (§3.10) are added to the builder's `useI18n` message catalog in this PR.

---

## 8. Risks & open questions

- **URL injection via bound `href`/`src` (security).** `link.href`/`image.src` are bindable, so a
  `{$bind}` can resolve to `javascript:`/`data:` — rendering it into `<a href>`/`<img src>` is XSS.
  **Mitigation (§3.9):** both props pass through the shared `widgets/urlSafety.ts` `safeUrl` allow-list
  (`{http,https,mailto,tel,relative}`, identical to 2e's `navigate`/`openUrl`) before render; a rejected
  scheme collapses to `''` (inert link / placeholder image). A **blocked-`javascript:` test** is required
  (link 6.5 + image 6.6 + the `urlSafety` unit spec 6.7). **Sequencing note:** if 2e ships `urlSafety.ts`
  first, 2g imports it — do not fork the scheme list (drift risk).
- **`recharts` `ResponsiveContainer` in jsdom.** It needs a measured parent box; in tests (per
  `MonitoringOverviewPage` precedent) mock `ResponsiveContainer` to a fixed width/height so the chart
  mounts. **Mitigation:** a shared test helper that stubs `ResponsiveContainer` to render children at a
  fixed size; assert on the inner chart’s `data-testid`/series, not pixel geometry.
- **Chart binding ordering vs 2d.** `chart` reads an **already-resolved** array from `props.dataView`.
  If 2g ships **before** 2d, the binding object is never resolved → chart always shows the editor sample
  / runtime "No data". This is degrade-safe and visible, but the *useful* chart (live data) only works
  once 2d's `resolveBindings` call point is live. **Open question for sequencing:** ship 2g after 2d so
  charts are immediately bindable, or before (presentational-only, live binding arrives with 2d)? Either
  compiles and tests pass; **recommend after 2d** for a complete demo.
- **`tab-panel` palette visibility.** `tab-panel` must be registered (so `widgetRegistry.get` resolves
  it) but **not** offered in the palette as a standalone widget. 2g registers it; the palette filter is a
  **2b** concern (`listByCategory` filter / optional `paletteHidden`). If 2b has not yet landed, the
  legacy palette array (2a §5.3(b)) does not include `tab-panel` (it derives from a curated list), so no
  leak today. **Track:** ensure 2b's registry-driven palette excludes `type==='tab-panel'`.
- **Series / tab-list / nav-items editors are 2b.** 2g declares these as `kind:'expression'` schema
  entries so the descriptors compile against the 2a `PropFieldKind` union. The **rich editors** (add/remove
  series, add/rename/reorder tabs keeping `tab-panel` children in sync, menu-item editor) are inspector
  work in 2b — which may add dedicated `PropFieldKind`s (`'series'`/`'tab-list'`/`'menu-items'`). Until
  then, editing happens via raw JSON in the legacy `PropertyPanel`. **Open question:** add those
  `PropFieldKind`s now (in 2g `types.ts`) or in 2b? **Recommend 2b** (keep 2g additive-only, no shared
  contract change) — noted so 2b owns it.
- **`Navigation` is router- and role-aware.** It calls `useNavigate`/`useLocation`, so `nav` only
  renders inside a router (always true on `CustomPage` runtime and the builder; tests wrap in
  `MemoryRouter`). Role filtering uses `currentUser.roles`; 2g passes no `currentUser` (→ no filtering),
  which is correct for public-page nav. Authoring role-gated nav items is a later enhancement, not 2g.
- **`lucide-react` `icons` map size.** Importing the full `icons` object pulls the icon registry; this is
  already how named lucide imports work across the app and tree-shakes per-named-component, but the
  `icons` map is a barrel. **Mitigation:** acceptable for a builder widget (the page already imports many
  lucide icons); if bundle size regresses materially, switch the `icon` widget to a curated allow-list
  map of the ~50 most-used icons (a follow-up, not a blocker). `dynamicIconImports` is **not** available
  in `^0.563.0`, so async lazy-loading is not an option here.
- **`PageBuilderPage.tsx` size.** 2g adds **no** code to `PageBuilderPage.tsx` (all new code is in
  `widgets/builtins/*`), so it does not regress the file-size concern (`concerns.md`); if anything it
  reinforces the descriptor-per-file pattern that shrank it in 2a.
- **Sequencing.** Hard prerequisite: **2a** (registry + `RenderTree` + `registerBuiltins`). Soft: **2d**
  (chart live data), **2b** (rich inline editors for series/tabs/nav). Independent of 2c/2e/2f. Ship 2g
  after 2a (and ideally 2d/2b for full UX); it is additive and independently shippable behind the
  registry.
