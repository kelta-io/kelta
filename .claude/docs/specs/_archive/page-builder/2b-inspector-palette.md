# 2b — Schema-driven inspector + palette

> **Slice:** 2b (FE foundation). **Axis:** foundation.
> **Parent:** [`../page-builder-parity.md`](../../page-builder-parity.md). This child spec **extends, never
> contradicts** the parent's [Widget registry](../../page-builder-parity.md#widget-registry),
> [Inspector + canvas](../../page-builder-parity.md#inspector--canvas), and
> [Child-spec template](../../page-builder-parity.md#child-spec-template) sections.
> **Builds on:** [`2a-widget-registry.md`](./2a-widget-registry.md) — consumes the `WidgetDescriptor`,
> `PropFieldSchema`, `PropFieldKind`, `WidgetCategory`, and `widgetRegistry` defined there. The
> `model/pageModel.ts` types (`PageComponent`/`Binding`/`PropValue`/`PageAction`/`EventHandlers`) are
> **defined in 2a**; this slice only consumes them.
> Source-verified against the codebase on 2026-06-22 (Flyway head V146; this slice adds no migration).
> If code and this doc disagree, trust the code and fix this doc.

---

## 1. Goal & scope

### What this slice delivers

This slice makes the **inspector and palette schema-driven**, finishing the consumption side of the 2a
registry:

1. **Palette from the registry.** Replace the hand-maintained `AVAILABLE_COMPONENTS` array
   (`PageBuilderPage.tsx` ~101) and the `ComponentPalette` component (~190) with a palette that renders
   from `widgetRegistry.listByCategory(...)`, grouped by `WidgetCategory`
   (`layout` · `content` · `data` · `input` · `navigation`).
2. **Schema-driven inspector.** Replace the per-type `PropertyPanel` (`PageBuilderPage.tsx` ~233, the
   `component.type === '…' && (…)` blocks at ~301–479) with `inspector/Inspector.tsx`, which loops over
   `widgetRegistry.get(node.type).propSchema` and maps each `PropFieldSchema.kind` to a field-editor
   component, grouping fields by `PropFieldSchema.group`.
3. **One field-editor component per `PropFieldKind`** under `inspector/fields/*`:
   `text` · `textarea` · `number` · `boolean` · `select` · `color` · `collection-picker` ·
   `field-picker` · `expression` · `event-list` · `span` · `children`.
4. **`inspector/BindableField.tsx`** — the `fx` literal↔expression toggle shell that wraps any
   `bindable` field. It writes a literal `PropValue` in literal mode and a `{ $bind, mode:'expr' }`
   `Binding` in expression mode, opening `FieldExpressionPicker` to author the expression.
5. **`inspector/fields/EventListField.tsx` (SHELL)** — **one** `kind:'event-list'` field (`key:'events'`)
   that edits the whole `node.events` (`EventHandlers`), rendered as **tabs over
   `descriptor.supportedEvents`** (declared on the descriptor in 2a), each tab an ordered `PageAction[]`
   you add / remove / reorder with a type selector. The **action runtime** (executing
   `runFlow`/`navigate`/etc.) is **out of scope — lands in 2e**; this slice defines the authoring
   surface and its `onChange` write contract only.
6. **Delete the 4 hardcoded builder switch sites.** Per 2a the render switches were already migrated to
   descriptors; here the **inspector + palette** consumption is finalized so the only remaining
   per-type knowledge lives in `widgets/builtins/*` descriptors.

### What this slice explicitly does NOT do

| Out of scope | Lands in |
|--------------|----------|
| Binding **resolution** (`resolveBindings`/`interpolate`/`bindingScope`), live `record`/`vars`/`data` scope, the `expression`/`BindableField` runtime that actually evaluates `{ $bind }` | **2d** |
| `EventListField` **action runtime** (dispatching `runFlow`→`/api/flows/{id}/execute`, `navigate`, `createRecord`, etc.) | **2e** |
| dnd-kit canvas, drop-into-container, reorder, resize; the `span` field-editor's *visual grid* wiring (the `span` field-editor component itself ships here as a numeric editor, but the canvas resize handles are 2c) | **2c** |
| New widgets (grid/row/column/divider/chart/tabs/nav/icon/link/list/repeater/field-value, typed inputs) | 2c / 2f / 2g |
| `model/*` type definitions (defined in 2a) | (already 2a) |

> **Consumed-from boundary (explicit).** The `expression` field kind and the `fx` toggle in
> `BindableField` **produce** `{ $bind, mode:'expr' }` values and persist them to `props[key]`. They do
> **not** resolve or evaluate those values — that is 2d. Likewise `EventListField` **produces** the whole
> `EventHandlers` map (per-event `PageAction[]`) and persists it wholesale to `node.events`; executing
> those actions is 2e. This slice's
> contract is purely *authoring + value-write*; the descriptor `Render` functions in 2a continue to
> render the literal/placeholder output until 2d/2e wire resolution and runtime in.

### Parent-doc sections this conforms to

- [Widget registry](../../page-builder-parity.md#widget-registry) — `PropFieldKind` enumeration,
  `PropFieldSchema` shape, `listByCategory`.
- [Inspector + canvas](../../page-builder-parity.md#inspector--canvas) — "schema-driven property editor
  looping over `descriptor.propSchema`, grouped by `group`", "each `bindable` field wraps in
  `BindableField`", "`EventListField` edits `PageAction[]` (add/remove/reorder)".
- [Reuse Map](../../page-builder-parity.md#reuse-map) — `FieldExpressionPicker`, `VariablePicker`,
  `useCollectionSchema`, `CollectionStoreContext`.

### Acceptance criteria

- The palette renders every registered built-in by reading `widgetRegistry.list()` /
  `listByCategory(...)`; there is **no** standalone `AVAILABLE_COMPONENTS` array left in
  `PageBuilderPage.tsx`. Adding a component still calls the existing `handleAddComponent` path.
- Selecting a node renders an inspector whose fields are produced **only** by looping over that node's
  `descriptor.propSchema` — there are **zero** `component.type === '…'` conditionals left in
  `PageBuilderPage.tsx`.
- Each `PropFieldKind` maps to exactly one field-editor component under `inspector/fields/`.
- A `bindable` field shows an `fx` toggle; toggling to expression mode and inserting an expression writes
  `props[key] = { $bind: '<expr>', mode: 'expr' }`; toggling back to literal restores a scalar write.
  Proven by Vitest.
- `EventListField` presents one tab per `descriptor.supportedEvents` entry, and within the active tab can
  add, remove, and reorder `PageAction` rows; it writes the whole `node.events` (`EventHandlers`) map
  wholesale. Proven by Vitest (runtime execution is asserted in 2e, not here).
- Existing builder behavior is preserved at parity for the 8 built-ins: editing text/level/content/
  label/variant/src/alt/collection/fields/limit produces the same `props` (and `props.dataView`) shape as
  the old `PropertyPanel`. Existing `PageBuilderPage.test.tsx` prop-edit assertions pass (with `data-testid`
  updated to the new scheme — see §5.6).
- All new inspector/palette chrome (group + field labels, palette category headers, empty-state and hint
  strings) is rendered through `useI18n`/`t()` with `builder.*` keys — no hardcoded English (§3.7).
- `/verify` green (lint + typecheck + `test:coverage` ≥ existing kelta-ui gate).

---

## 2. UI samples

### 2.1 Palette — grouped by category (replaces `ComponentPalette`)

After 2a, the 8 built-ins carry a `category`. The palette renders one section per non-empty category, in
declaration order (`layout`, `content`, `data`, `input`, `navigation`):

```
┌─ Components ───────────────────────────────┐
│                                            │
│  LAYOUT                                    │
│  ┌──────┐ ┌──────┐                         │
│  │  ▢   │ │  ◻   │                         │   ← card, container
│  │ Card │ │Cont… │                         │
│  └──────┘ └──────┘                         │
│                                            │
│  CONTENT                                   │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐       │
│  │  H   │ │  T   │ │  B   │ │  I   │       │   ← heading, text, button, image
│  │Head… │ │ Text │ │Butt… │ │Image │       │
│  └──────┘ └──────┘ └──────┘ └──────┘       │
│                                            │
│  DATA                                      │
│  ┌──────┐                                  │
│  │  ⊞   │                                  │   ← table
│  │Table │                                  │
│  └──────┘                                  │
│                                            │
│  INPUT                                     │
│  ┌──────┐                                  │
│  │  F   │                                  │   ← form (category `input` per 2a §5.2)
│  │ Form │                                  │
│  └──────┘                                  │
│                                            │
└────────────────────────────────────────────┘
```

- Each tile keeps today's affordances: `draggable`, `onDragStart(comp.type)`, `onClick → onAddComponent(comp.type)`,
  `aria-label="Add <label> component"`, and **`data-testid="palette-item-<type>"`** (unchanged — existing
  e2e/Vitest selectors keep working).
- Section header `data-testid="palette-category-<category>"`.
- Empty categories are omitted. In 2a's 8-widget set the categories are `layout` (card, container),
  `content` (heading, text, button, image), `data` (table), and `input` (`form` — **category `input`**
  per 2a §5.2, the fixed assignment). So `layout`/`content`/`data`/`input` all render; only `navigation`
  is empty until 2f/2g add widgets.

### 2.2 Inspector — `heading` (text + level)

```
┌─ Properties ───────────────────────────────┐
│ Component Type   heading                    │   ← read-only (data-testid="property-type")
│ ID               comp_… (disabled)          │   ← read-only (data-testid="property-id")
│                                             │
│ ── Content ──────────────────────────────── │   ← group header (PropFieldSchema.group)
│ Text                              [ fx ]    │   ← bindable → fx toggle (BindableField)
│ ┌─────────────────────────────────────────┐ │
│ │ Orders                                  │ │   ← TextField, data-testid="property-field-text"
│ └─────────────────────────────────────────┘ │
│                                             │
│ Level                                       │   ← not bindable, no fx
│ ┌─────────────────────────────────────────┐ │
│ │ H1                                   ▼  │ │   ← SelectField, data-testid="property-field-level"
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 2.3 Inspector — `button` with events

`button`'s descriptor (extended in this slice — see §5.3) adds one `kind:'event-list'` prop with
`key:'events'`. The `EventListField` shell renders a tab per `descriptor.supportedEvents` entry (for
`button`, just `onClick`) and the action rows inside the active tab:

```
┌─ Properties ───────────────────────────────┐
│ Component Type   button                     │
│ ── Content ──────────────────────────────── │
│ Label                             [ fx ]    │   ← bindable
│ ┌─────────────────────────────────────────┐ │
│ │ Save                                    │ │
│ └─────────────────────────────────────────┘ │
│ Variant                                     │
│ ┌─────────────────────────────────────────┐ │
│ │ Primary                              ▼  │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ ── Events ────────────────────────────────  │   ← EventListField, data-testid="property-field-events"
│ ┌ On Click ┐──────────────────────────────  │   ← tabs over descriptor.supportedEvents (here: onClick)
│ │ ⠿  Run Flow      ▼   [flow: create_…▼]│ ✕ │   ← row 0: drag handle · action type · params · remove
│ ├───────────────────────────────────────┤   │
│ │ ⠿  Show Toast    ▼   [success] [Done!]│ ✕ │   ← row 1
│ └───────────────────────────────────────┘   │
│ [ + Add action ]                            │   ← data-testid="event-add-onClick"
└─────────────────────────────────────────────┘
```

- Each row: drag handle (`⠿`, reorder), an action-type `<Select>` (`runFlow`/`navigate`/`openUrl`/
  `createRecord`/`updateRecord`/`refreshData`/`setVar`/`showToast` from `PageAction`), action-specific
  param inputs, and a remove `✕`. Row testid `event-row-onClick-<index>`; remove `event-remove-onClick-<index>`.
- **Param inputs render but their values are stored verbatim into the `PageAction` object** — the runtime
  that *consumes* them is 2e. The flow picker for `runFlow` lists flows from `/api/flows` (the same source
  the parent spec names); in 2b a plain `<Select>` of flow `{id,name}` is sufficient (full async picker can
  harden in 2e).

### 2.4 Inspector — `table` (collection + field pickers)

`table`'s `propSchema` (defined in 2a §5.2) is:
`dataView.collection` → `collection-picker`, `dataView.fields` → `field-picker` (`dependsOnCollection`),
`dataView.limit` → `number`.

```
┌─ Properties ───────────────────────────────┐
│ Component Type   table                      │
│ ── Data ─────────────────────────────────── │
│ Collection                                  │
│ ┌─────────────────────────────────────────┐ │
│ │ Orders                               ▼  │ │   ← CollectionPickerField, data-testid="property-field-dataView.collection"
│ └─────────────────────────────────────────┘ │   (lists CollectionStore.collections)
│                                             │
│ Columns                                     │
│ ┌─────────────────────────────────────────┐ │
│ │ ☑ id        ☑ status     ☑ total       │ │   ← FieldPickerField (multi-check of the collection's fields)
│ │ ☐ createdAt ☐ customerId                │ │   data-testid="property-field-dataView.fields"
│ └─────────────────────────────────────────┘ │   (disabled with hint until a collection is chosen)
│                                             │
│ Row limit                                   │
│ ┌─────────────────────────────────────────┐ │
│ │ 25                                      │ │   ← NumberField, data-testid="property-field-dataView.limit"
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

- `field-picker` reads the sibling collection value at the schema's *parent dot-path* (for key
  `dataView.fields`, the sibling collection lives at `dataView.collection`) and calls
  `useCollectionSchema(collectionName)` to list checkboxes. When no collection is selected it renders a
  disabled state with the hint "Select a collection first." (matches `dependsOnCollection: true`). That
  hint — like every visible string here — is resolved via `t('builder.…')`, not a hardcoded literal (§3.7).

### 2.5 `fx` literal↔expression toggle states (`BindableField`)

```
LITERAL MODE (default)                         EXPRESSION MODE (after clicking fx)
┌─────────────────────────────┐                ┌─────────────────────────────┐
│ Text                 [ fx ] │                │ Text                 [·fx·] │  ← toggle active (filled)
│ ┌─────────────────────────┐ │                │ ┌─────────────────────────┐ │
│ │ Orders                  │ │   click fx →   │ │ {{record.name}}     [✎] │ │  ← shows the $bind string,
│ └─────────────────────────┘ │                │ └─────────────────────────┘ │     read-only chip + edit (✎)
│  writes props.text="Orders" │   ← click fx → │  writes props.text =        │     button opens the picker
└─────────────────────────────┘                │   { $bind:"record.name",    │
                                               │     mode:"expr" }           │
                                               └─────────────────────────────┘
```

Write transitions (the load-bearing contract):

| User action | `onChange(key, value)` payload written to `props[key]` |
|-------------|--------------------------------------------------------|
| Type "Orders" in literal mode | `"Orders"` (raw scalar) |
| Click `fx` (literal → expr) with no expr yet | `{ $bind: "", mode: "expr" }` (empty binding; picker opens) |
| Insert `record.name` via `FieldExpressionPicker` | `{ $bind: "record.name", mode: "expr" }` |
| Click `fx` again (expr → literal) | the descriptor's `defaultProps[key]` if present, else `""` (binding dropped) |

- The `fx` button is rendered **only** when `PropFieldSchema.bindable === true`. Non-bindable fields
  (`level`, `variant`, `dataView.limit`, …) render the bare editor with no toggle.
- `data-testid`s: toggle `bindable-fx-<key>`, expr chip `bindable-expr-<key>`, edit button
  `bindable-edit-<key>`.

### 2.6 Before / after of the affected screen

```
TODAY (PageBuilderPage.tsx)                  AFTER 2b
────────────────────────────                 ─────────────────────────────
ComponentPalette                             <Palette>                         reads widgetRegistry.listByCategory()
  └ AVAILABLE_COMPONENTS[]  (hardcoded 8)       └ section per category
PropertyPanel                                <Inspector node=…>                loops widgetRegistry.get(type).propSchema
  └ type==='heading' && (…text/level…)          └ {schema.map(f => <Field kind=f.kind/>)}
  └ type==='text'    && (…)                          ├ TextField / SelectField / NumberField / …
  └ type==='button'  && (…)                          ├ wrapped in <BindableField> when f.bindable
  └ (table|form)     && (…dataView…)                  └ EventListField for kind:'event-list'
```

### 2.7 Sample node JSON after authoring (what `config.components` holds)

```json
[
  { "id": "c1", "type": "heading", "props": { "text": { "$bind": "record.name", "mode": "expr" }, "level": "h1" } },
  {
    "id": "c2",
    "type": "button",
    "props": { "label": "Save", "variant": "primary" },
    "events": {
      "onClick": [
        { "action": "runFlow", "flowId": "flow_abc", "input": {}, "awaitResult": true },
        { "action": "showToast", "level": "success", "message": "Saved!" }
      ]
    }
  },
  { "id": "c3", "type": "table", "props": { "dataView": { "collection": "orders", "fields": ["id", "status"], "limit": 25 } } }
]
```

`c1.props.text` is a `Binding` (round-trips untouched through the server; resolved in 2d). `c2.events.onClick`
is a `PageAction[]` (executed in 2e). `c3.props.dataView` matches the existing table data-source shape exactly.

---

## 3. Data & API contracts

All TS lives under `kelta-ui/app/src/pages/PageBuilderPage/`. **No backend/API change in 2b.** The only
network reads are the **already-existing** collection/field metadata fetches (`useCollectionSchema`,
`CollectionStoreContext`) and, for the flow picker, the existing `GET /api/flows` list.

### 3.0 Types consumed from 2a (not redefined here)

```ts
// from '../model/pageModel' (2a)
import type { PageComponent, PropValue, Binding, PageAction, EventHandlers } from '../model/pageModel'
import { isBinding } from '../model/pageModel'
// from '../widgets/types' (2a)
import type { PropFieldSchema, PropFieldKind, WidgetCategory } from '../widgets/types'
// from '../widgets/registry' (2a)
import { widgetRegistry } from '../widgets/registry'
```

### 3.1 Common field-editor props (the shared contract)

Every `inspector/fields/*` component implements the same base prop shape so `Inspector` can render them
uniformly. The **value-write contract** is: a field receives the *current value at its key* and an
`onChange(value)` that writes the **whole value** for that key — `Inspector` is responsible for splicing it
into `props` at the (possibly dotted) `schema.key` via a `setByPath` helper.

```ts
// inspector/fields/types.ts
import type { PropValue } from '../../model/pageModel'
import type { PropFieldSchema } from '../../widgets/types'

/** Base props handed to every field-editor component. */
export interface FieldEditorProps<V = PropValue> {
  schema: PropFieldSchema
  /** Current value at schema.key (already read out of props via getByPath). May be undefined. */
  value: V | undefined
  /**
   * Write the new value for this field. Inspector splices it back into props at schema.key.
   * For literal editors this is a scalar; BindableField may pass a Binding.
   */
  onChange: (value: V) => void
  /** The node being edited — needed by field-picker to read a sibling collection value. */
  node: PageComponent
  /** Stable id/testid base, e.g. `property-field-${schema.key}`. */
  fieldId: string
}
```

`Inspector` computes `value` with a `getByPath(node.props, schema.key)` and writes with
`setByPath(node.props, schema.key, v)` (pure, immutable; lives in `inspector/propPath.ts`). These two
helpers handle the `dataView.collection`-style dotted keys (a thin walker, **not** the binding resolver —
binding resolution is 2d and operates on a live scope, this only walks the static `props` object).

```ts
// inspector/propPath.ts
export function getByPath(obj: Record<string, unknown>, path: string): unknown
export function setByPath(
  obj: Record<string, PropValue>,
  path: string,
  value: PropValue,
): Record<string, PropValue> // returns a new props object (immutable)
export function deleteByPath(
  obj: Record<string, PropValue>,
  path: string,
): Record<string, PropValue>
```

### 3.2 `inspector/fields/*` — per-kind components and value-write contracts

Each maps one `PropFieldKind`. All use shared primitives from `@/components/ui/*`
(`Input`, `Textarea`, `Select*`, `Label`, `Checkbox`) — **verified to exist** and exported from
`kelta-ui/app/src/components/ui/`.

```tsx
// inspector/fields/TextField.tsx
import { Input } from '@/components/ui/input'
import type { FieldEditorProps } from './types'

export function TextField({ value, onChange, fieldId }: FieldEditorProps): React.ReactElement {
  return (
    <Input
      value={typeof value === 'string' ? value : ''}
      onChange={(e) => onChange(e.target.value)}      // writes a raw string scalar
      data-testid={fieldId}
    />
  )
}
```

```tsx
// inspector/fields/TextareaField.tsx  (kind:'textarea')
import { Textarea } from '@/components/ui/textarea'
// onChange(e.target.value) — raw string. (Replaces the old property-content <textarea>.)
```

```tsx
// inspector/fields/NumberField.tsx  (kind:'number')
import { Input } from '@/components/ui/input'
// <Input type="number" .../>; onChange writes `e.target.value === '' ? undefined : Number(e.target.value)`
// (matches the existing limit editor which stores `undefined` when blank, NOT 0).
```

```tsx
// inspector/fields/BooleanField.tsx  (kind:'boolean')
import { Checkbox } from '@/components/ui/checkbox'
// onCheckedChange(checked => onChange(!!checked)) — writes a boolean.
```

```tsx
// inspector/fields/SelectField.tsx  (kind:'select')
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import type { FieldEditorProps } from './types'

export function SelectField({ schema, value, onChange, fieldId }: FieldEditorProps): React.ReactElement {
  return (
    <Select value={typeof value === 'string' ? value : ''} onValueChange={(v) => onChange(v)}>
      <SelectTrigger data-testid={fieldId}><SelectValue /></SelectTrigger>
      <SelectContent>
        {(schema.options ?? []).map((o) => (
          <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
// onChange writes the selected option's `value` string. Reads schema.options from PropFieldSchema.
```

```tsx
// inspector/fields/ColorField.tsx  (kind:'color')
import { Input } from '@/components/ui/input'
// Native <Input type="color"> + a paired text Input for hex entry; onChange writes the hex string.
```

```tsx
// inspector/fields/CollectionPickerField.tsx  (kind:'collection-picker')
import { useCollectionStore } from '@/context/CollectionStoreContext'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import type { FieldEditorProps } from './types'

export function CollectionPickerField({ value, onChange, fieldId }: FieldEditorProps): React.ReactElement {
  const { collections } = useCollectionStore()   // verified: CollectionStoreValue.collections: CollectionSchema[]
  return (
    <Select value={typeof value === 'string' ? value : ''} onValueChange={(v) => onChange(v)}>
      <SelectTrigger data-testid={fieldId}><SelectValue placeholder="Select a collection" /></SelectTrigger>
      <SelectContent>
        {collections.map((c) => (
          <SelectItem key={c.id} value={c.name}>{c.displayName}</SelectItem>   // stores the NAME (dataView.collection holds a name)
        ))}
      </SelectContent>
    </Select>
  )
}
// onChange writes the collection NAME string (parity with today's free-text property-collection input).
```

```tsx
// inspector/fields/FieldPickerField.tsx  (kind:'field-picker', dependsOnCollection)
import { useCollectionSchema } from '@/hooks/useCollectionSchema'
import { Checkbox } from '@/components/ui/checkbox'
import { getByPath } from '../propPath'
import type { FieldEditorProps } from './types'

export function FieldPickerField({ schema, value, onChange, node, fieldId }: FieldEditorProps): React.ReactElement {
  // Sibling collection lives at the parent dot-path's `.collection` — for key 'dataView.fields'
  // the collection is at 'dataView.collection'. Derive it generically:
  const parent = schema.key.includes('.') ? schema.key.slice(0, schema.key.lastIndexOf('.')) : ''
  const collectionKey = parent ? `${parent}.collection` : 'collection'
  const collectionName = getByPath(node.props, collectionKey) as string | undefined
  const { fields, isLoading } = useCollectionSchema(collectionName)   // verified hook: returns { fields, isLoading, ... }

  const selected = Array.isArray(value) ? (value as string[]) : []
  const toggle = (name: string) =>
    onChange(selected.includes(name) ? selected.filter((f) => f !== name) : [...selected, name])

  if (!collectionName) {
    return <p className="text-xs text-muted-foreground" data-testid={`${fieldId}-empty`}>Select a collection first.</p>
  }
  if (isLoading) return <p className="text-xs text-muted-foreground">Loading…</p>
  return (
    <div data-testid={fieldId} className="flex flex-col gap-1">
      {fields.map((f) => (
        <label key={f.name} className="flex items-center gap-2 text-xs">
          <Checkbox checked={selected.includes(f.name)} onCheckedChange={() => toggle(f.name)} />
          {f.displayName ?? f.name}
        </label>
      ))}
    </div>
  )
}
// onChange writes a string[] of field names (parity with today's comma-split property-columns value).
```

```tsx
// inspector/fields/ExpressionField.tsx  (kind:'expression')
// A first-class expression editor (distinct from BindableField, which wraps a *literal* editor with an
// fx toggle). It always edits a Binding and is the editor BindableField delegates to in expr mode.
import { useState } from 'react'
import { FieldExpressionPicker } from '@/components/FieldExpressionPicker'
import { VariablePicker } from '@/components/VariablePicker'
import { useCollectionStore } from '@/context/CollectionStoreContext'
import { Button } from '@/components/ui/button'
import type { Binding, PropValue } from '../../model/pageModel'
import { isBinding } from '../../model/pageModel'
import type { FieldEditorProps } from './types'

export function ExpressionField({ value, onChange, fieldId }: FieldEditorProps): React.ReactElement {
  const [open, setOpen] = useState(false)
  const binding: Binding | null = isBinding(value) ? value : null
  // NOTE: FieldExpressionPicker takes a rootCollectionId (a UUID), NOT a name, and its onInsert emits a
  // BARE token (dot-path or fn stub) — the CALLER wraps {{…}}. The binding namespace (record/vars/page)
  // for the page builder is provided as staticNamespaces here (2d swaps in the real page scope; in 2b we
  // pass the page-builder namespace stubs so the picker is usable). rootCollectionId is null in 2b unless
  // a page-level "record collection" is configured (that wiring is 2d's PageDataSource work).
  return (
    <div className="flex items-center gap-2" data-testid={fieldId}>
      <code className="flex-1 rounded bg-muted px-2 py-1 font-mono text-xs">
        {binding ? `{{${binding.$bind}}}` : '—'}
      </code>
      <VariablePicker
        variables={PAGE_SCOPE_VARIABLES /* {record,vars,page} stubs; real list in 2d */}
        onPick={(token) => onChange({ $bind: stripMergeTags(token), mode: 'expr' } as PropValue)}
        raw
      />
      <Button size="sm" variant="outline" type="button" onClick={() => setOpen(true)}>fx</Button>
      <FieldExpressionPicker
        open={open}
        onOpenChange={setOpen}
        rootCollectionId={null}
        staticNamespaces={PAGE_STATIC_NAMESPACES /* record/vars/page */}
        mode="expression"
        onInsert={(token) => onChange({ $bind: token, mode: 'expr' } as PropValue)}  // caller stores bare token
      />
    </div>
  )
}
// onChange ALWAYS writes a Binding ({ $bind, mode:'expr' }). 2d makes PAGE_SCOPE_VARIABLES/PAGE_STATIC_NAMESPACES
// reflect the page's variables + on-load data sources; in 2b they are the static record/vars/page roots.
```

> **Verified `FieldExpressionPicker` contract** (`components/FieldExpressionPicker/FieldExpressionPicker.tsx`):
> props are `open`, `onOpenChange(open)`, `rootCollectionId: string | null`, `staticNamespaces?: StaticNamespace[]`,
> `mode?: 'expression' | 'path-only'`, `allowedTypes?: FieldType[]`, `onInsert(token: string)`, `title?`, `testId?`.
> **`onInsert` emits the bare assembled token** (a dot-path like `account_id.name`, or a function stub like
> `IF(${condition}, ${then}, ${else})`) — the Javadoc states *"Callers wrap with `{{…}}` if they need merge-tag
> syntax."* So `ExpressionField`/`BindableField` store the bare token as `binding.$bind` and render `{{…}}` only
> for display. `StaticNamespace` = `{ name, label, fields: { name, displayName, type }[] }`.

> **Verified `VariablePicker` contract** (`components/VariablePicker/VariablePicker.tsx`): props
> `variables: VariableNode[]` (`{ path, label?, type? }`), `onPick(token)`, `trigger?`, `raw?`. With `raw`
> it emits the bare path; otherwise it emits `${path}`. We pass `raw` and store the path as `$bind`.

```tsx
// inspector/fields/SpanField.tsx  (kind:'span')
// Edits ResponsiveSpan { base, sm?, md?, lg? } as four 1..12 number inputs. onChange writes the ResponsiveSpan
// object to node.span (NOT node.props — Inspector special-cases kind:'span' to target node.span). The CANVAS
// resize handles that also set span are 2c; this is the inspector-side numeric editor only.
```

```tsx
// inspector/fields/ChildrenField.tsx  (kind:'children')
// A read-only summary of node.children (count + reorder is the canvas's job in 2c). In 2b it renders a small
// "N child components — edit on canvas" hint so acceptsChildren widgets (card/container) show *something*
// in the inspector. No write contract (children are edited on the canvas). data-testid="property-field-children".
```

### 3.3 `inspector/BindableField.tsx` — the `fx` toggle shell

`BindableField` **wraps** a literal field editor. It owns the literal↔expression mode decision based on
whether the current value `isBinding(value)`, renders the `fx` toggle, and delegates to either the literal
editor (passed as `children` / a render-prop) or `ExpressionField`.

```tsx
// inspector/BindableField.tsx
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ExpressionField } from './fields/ExpressionField'
import type { Binding, PropValue } from '../model/pageModel'
import { isBinding } from '../model/pageModel'
import type { PropFieldSchema } from '../widgets/types'
import type { PageComponent } from '../model/pageModel'

export interface BindableFieldProps {
  schema: PropFieldSchema
  value: PropValue | undefined
  onChange: (value: PropValue) => void
  node: PageComponent
  fieldId: string
  /** The literal editor to show in literal mode (TextField/NumberField/etc.), already bound to value/onChange. */
  renderLiteral: (args: { value: PropValue | undefined; onChange: (v: PropValue) => void }) => React.ReactNode
  /** Fallback literal to write when switching expr → literal (descriptor defaultProps[key] ?? ''). */
  literalDefault: PropValue
}

export function BindableField({
  schema, value, onChange, node, fieldId, renderLiteral, literalDefault,
}: BindableFieldProps): React.ReactElement {
  const isExpr = isBinding(value)
  const toggle = () => {
    if (isExpr) {
      onChange(literalDefault)                          // expr → literal: drop the binding
    } else {
      onChange({ $bind: '', mode: 'expr' } as Binding)  // literal → expr: start an empty binding
    }
  }
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground">{schema.label}</span>
        <Button
          type="button" size="sm" variant="ghost"
          className={cn('h-5 px-1 text-[10px]', isExpr && 'bg-primary/10 text-primary')}
          onClick={toggle}
          data-testid={`bindable-fx-${schema.key}`}
        >
          fx
        </Button>
      </div>
      {isExpr ? (
        <ExpressionField schema={schema} value={value} onChange={onChange} node={node} fieldId={`property-field-${schema.key}`} />
      ) : (
        renderLiteral({ value, onChange })
      )}
    </div>
  )
}
```

**Write contract summary (the load-bearing part):**

| Mode | Editor shown | `onChange` writes |
|------|--------------|-------------------|
| literal | `renderLiteral(...)` (e.g. `TextField`) | a scalar `PropValue` (string/number/boolean) |
| expression | `ExpressionField` | a `Binding` `{ $bind, mode:'expr' }` |
| toggle literal→expr | — | `{ $bind: '', mode:'expr' }` then picker fills `$bind` |
| toggle expr→literal | — | `literalDefault` (= `descriptor.defaultProps[key] ?? ''`) |

`Inspector` decides per field: if `schema.bindable`, render `<BindableField renderLiteral={…literal editor for kind…}>`;
otherwise render the literal editor directly with no toggle. (Non-bindable kinds — `select`, `boolean`,
`collection-picker`, `field-picker`, `event-list`, `span`, `children` — are never wrapped.)

### 3.4 `inspector/fields/EventListField.tsx` — SHELL (authoring only; runtime in 2e)

```tsx
// inspector/fields/EventListField.tsx
import type { PageAction, EventHandlers } from '../../model/pageModel'
import type { PageComponent } from '../../model/pageModel'

export interface EventListFieldProps {
  /**
   * The events this widget supports (descriptor.supportedEvents, declared on the descriptor in 2a),
   * e.g. ['onClick']. Renders one tab per entry; the active tab edits that event's PageAction[].
   */
  supportedEvents: Array<keyof EventHandlers>
  /** Current value of node.events (the whole EventHandlers map). May be undefined. */
  value: EventHandlers | undefined
  /** Writes the whole EventHandlers map back to node.events (NOT a single handler). */
  onChange: (events: EventHandlers) => void
  node: PageComponent
  fieldId: string
}
```

The field renders a tab strip over `supportedEvents`; the active tab's rows operate on
`value?.[activeEvent] ?? []`. Operations (pure transforms over the active event's `PageAction[]`; the
**runtime that executes** these actions is 2e). Each writes the **whole** `EventHandlers` map back via
`onChange` — never a single handler. Let `next` be the transformed `PageAction[]` for the active event:

| Op | Active event's `PageAction[]` (`actions = value?.[activeEvent] ?? []`) |
|----|-------------------------------|
| Add | `[...actions, defaultAction(selectedType)]` where `defaultAction('runFlow') = { action:'runFlow', flowId:'', input:{} }`, etc. |
| Remove i | `actions.filter((_, idx) => idx !== i)` |
| Reorder i→j | array move (immutable) |
| Edit a param | `actions.map((a, idx) => idx === i ? { ...a, [field]: newValue } : a)` |

Each op then writes `onChange({ ...value, [activeEvent]: next })`, **dropping the key** when `next` is
empty (so empty events don't bloat the config). `Inspector` maps `kind:'event-list'` to `EventListField`,
passing `supportedEvents = descriptor.supportedEvents ?? []` and `value = node.events`, and writing the
returned map via `onChange({ events })` (deleting `node.events` entirely when the map is empty).

> **Consumed-from 2e marker (in the file header):**
> `// EventListField authors PageAction[] only. The action RUNTIME (dispatching runFlow → /api/flows/{id}/execute,`
> `// navigate, createRecord, etc.) lands in slice 2e. Do not add execution here.`

### 3.5 `inspector/Inspector.tsx` — the schema-driven panel

```tsx
// inspector/Inspector.tsx (signature)
export interface InspectorProps {
  /** The selected node, or null. */
  node: PageComponent | null
  /** Patch the node (props/events/span). Mirrors today's PropertyPanel onChange(updates: Partial<PageComponent>). */
  onChange: (updates: Partial<PageComponent>) => void
}
```

Render algorithm:

1. `null` node → the existing "Select a component" empty state (`data-testid="property-panel"`).
2. Read `descriptor = widgetRegistry.get(node.type)` (never undefined — plugin/default fallback from 2a).
3. Render the read-only Component Type + ID rows (unchanged; `data-testid="property-type"`/`"property-id"`).
4. Group `descriptor.propSchema` by `PropFieldSchema.group` (ungrouped fields go under a default section,
   preserving array order). Render a group header per group.
5. For each `PropFieldSchema`, pick the editor by `kind`:
   - `span` → write target is `node.span`; call `onChange({ span })`.
   - `event-list` → `EventListField`; **does not read `node.props`** — pass
     `supportedEvents = descriptor.supportedEvents ?? []` and `value = node.events`; on change call
     `onChange({ events })` with the whole returned `EventHandlers` map (the single `event-list` field has
     `key:'events'`, but the value lives on `node.events`, not `node.props.events`).
   - `children` → read-only summary; no write.
   - everything else → write target is `node.props[key]`; compute `value = getByPath(node.props, key)` and
     `onChange({ props: setByPath(node.props, key, v) })`. If `schema.bindable`, wrap the literal editor in
     `<BindableField>`; else render the literal editor directly.

This is the **single** place that knows the kind→editor mapping. Adding a new `PropFieldKind` = adding one
`inspector/fields/*` component + one `case` in the `Inspector` switch (documented as a playbook recipe — §7).

### 3.6 Versioning / back-compat

- **No schema/config change.** Values authored here (`Binding` in `props`, `PageAction[]` in `events`,
  `ResponsiveSpan` in `span`) all nest inside the existing `ui-pages.config` JSON and round-trip untouched
  through the server (parent: "The server never parses `$bind`").
- **Reading legacy props:** a literal-valued prop (string/number) renders in literal mode; a `Binding`-valued
  prop renders in expr mode. `isBinding(value)` is the sole discriminator (defined in 2a).
- **`PropertyPanel` removal** is internal; the inspector container keeps `data-testid="property-panel"` so
  outer-shell selectors are stable. The per-field testids change from `property-<x>` to
  `property-field-<key>` (see §5.6 for the migration of existing tests).

### 3.7 i18n — all new chrome strings go through `useI18n`/`t()`

Every user-visible string introduced by this slice is localized via `useI18n()` / `t('builder.…')` with
`builder.*` keys — **never hardcode English**, matching the existing UI convention (parent §i18n). This
covers, at minimum:

- **Inspector group headers** — the `PropFieldSchema.group` value is a translation **key** (e.g.
  `builder.inspector.group.content`), resolved via `t()` at render; the group strings in the §2 mockups
  ("Content", "Data", "Events", "Layout") are the rendered English, not literals in code.
- **Field labels** — `PropFieldSchema.label` is likewise a `builder.*` key resolved with `t()`.
- **Palette category headers** — `t('builder.palette.category.<category>')` for `layout`/`content`/
  `data`/`input`/`navigation`; the tile `aria-label` ("Add … component") uses a parameterized
  `builder.palette.addAria` key.
- **Empty-state / hint strings** — the inspector empty state ("Select a component"), the
  "No editable properties" hint, the `field-picker` "Select a collection first." hint, and the
  `children` "N child components — edit on canvas" summary all read from `builder.*` keys (the literal
  English shown in the §3.2 code snippets is illustrative — wrap each in `t()` in the implementation).
- **Event-list chrome** — the action-type select option labels, the "Add action" button, and the
  per-event tab labels (`builder.events.<eventName>`, e.g. `builder.events.onClick`) go through `t()`.

`data-testid`s remain the stable, untranslated English identifiers (`property-field-<key>`,
`palette-category-<category>`, etc.) so tests are locale-independent; only the **displayed text** is
localized.

---

## 4. DB migrations

**None — stored in `ui-pages.config` JSON, no DDL.** Everything authored by this slice (`Binding` values,
`PageAction[]`, `ResponsiveSpan`) nests inside the existing `config` column. No Flyway version consumed
(head remains V146). No NATS subject or payload change.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/pages/PageBuilderPage/` unless noted.

### 5.1 New files — `inspector/`

| File | Contents |
|------|----------|
| `inspector/Inspector.tsx` | §3.5 — the schema-driven panel. Loops `descriptor.propSchema`, groups by `group`, maps `kind`→editor, wraps `bindable` fields in `BindableField`. Keeps the `property-panel` / `property-type` / `property-id` testids and the empty-state. |
| `inspector/BindableField.tsx` | §3.3 — the `fx` literal↔expr shell. |
| `inspector/propPath.ts` | §3.1 — `getByPath` / `setByPath` / `deleteByPath` (immutable dotted-key walkers over `props`). |
| `inspector/fields/types.ts` | §3.1 — `FieldEditorProps<V>`. |
| `inspector/fields/index.ts` | Barrel: re-export all field components + a `FIELD_EDITORS: Record<PropFieldKind, …>` map (or the `Inspector` switch imports them directly). |
| `inspector/fields/TextField.tsx` | §3.2 — `kind:'text'` → `Input`; writes string. |
| `inspector/fields/TextareaField.tsx` | `kind:'textarea'` → `Textarea`; writes string. |
| `inspector/fields/NumberField.tsx` | `kind:'number'` → `Input type=number`; writes `number \| undefined` (blank ⇒ `undefined`, matching today's limit editor). |
| `inspector/fields/BooleanField.tsx` | `kind:'boolean'` → `Checkbox`; writes boolean. |
| `inspector/fields/SelectField.tsx` | §3.2 — `kind:'select'` → `Select` over `schema.options`; writes the option value. |
| `inspector/fields/ColorField.tsx` | `kind:'color'` → native color `Input` + hex text; writes hex string. |
| `inspector/fields/CollectionPickerField.tsx` | §3.2 — `kind:'collection-picker'` → `Select` over `useCollectionStore().collections`; writes the collection **name**. |
| `inspector/fields/FieldPickerField.tsx` | §3.2 — `kind:'field-picker'` → multi-`Checkbox` over `useCollectionSchema(siblingCollection).fields`; writes `string[]`; disabled hint when no collection. |
| `inspector/fields/ExpressionField.tsx` | §3.2 — `kind:'expression'` → `FieldExpressionPicker` + `VariablePicker`; always writes a `Binding`. |
| `inspector/fields/EventListField.tsx` | §3.4 — `kind:'event-list'` SHELL; one field (`key:'events'`) editing the whole `node.events` as tabs over `descriptor.supportedEvents`; writes the wholesale `EventHandlers` map. Runtime is 2e. |
| `inspector/fields/SpanField.tsx` | `kind:'span'` → four 1..12 number inputs; writes `ResponsiveSpan` to `node.span`. Canvas resize is 2c. |
| `inspector/fields/ChildrenField.tsx` | `kind:'children'` → read-only child-count summary; no write (children edited on canvas, 2c). |

### 5.2 New file — `Palette.tsx` (replaces `ComponentPalette`)

`pages/PageBuilderPage/Palette.tsx`:

```tsx
export interface PaletteProps {
  onDragStart: (componentType: string) => void
  onAddComponent: (componentType: string) => void
}
```

- Reads `widgetRegistry.list()`, groups by `category` (declaration order: layout→content→data→input→navigation;
  in the 8-widget set `input` is **non-empty** — it holds `form` per 2a §5.2 — and only `navigation` is empty),
  renders a `palette-category-<category>` section per non-empty category, and the existing per-tile markup
  (`palette-item-<type>`, `draggable`, `onDragStart`, `onClick`, `aria-label`). Icons/labels come from the
  descriptor (`w.icon` / `w.label`).
- Keeps the `component-palette` container testid + the `builder.pages.components` heading. Category
  headers and tile `aria-label`s are localized via `t('builder.palette.*')` (§3.7), not hardcoded English.

### 5.3 Refactor — `PageBuilderPage.tsx`

**(a) Delete `AVAILABLE_COMPONENTS`** (~101–110) and the `ComponentPalette` component (~185–223). Replace the
call site (~1604):

```tsx
// BEFORE: <ComponentPalette onDragStart={handleDragStart} onAddComponent={handleAddComponent} />
// AFTER:
<Palette onDragStart={handleDragStart} onAddComponent={handleAddComponent} />
```

**(b) Delete `PropertyPanel`** (~225–483, including the `heading`/`text`/`button`/`image`/`table`/`form`
`&&` blocks and the `handleDataViewChange` helper). Replace the call site (~1613):

```tsx
// BEFORE: <PropertyPanel component={selectedComponent} onChange={handleComponentChange} />
// AFTER:
<Inspector node={selectedComponent} onChange={handleComponentChange} />
```

`handleComponentChange` is **unchanged** — it already accepts `Partial<PageComponent>` and merges via
`{ ...comp, ...updates }`, so `Inspector`'s `onChange({ props })` / `onChange({ events })` / `onChange({ span })`
all work without modification.

**(c) Extend two built-in descriptors with the new prop kinds** (these descriptors live in
`widgets/builtins/*` from 2a; 2b adds the schema entries that exercise `event-list` and `span`/`children`):

- `widgets/builtins/button.tsx` — append **one** entry to `propSchema`:
  `{ key: 'events', label: 'Events', kind: 'event-list', group: 'Events' }`, and ensure the descriptor
  declares `supportedEvents: ['onClick']` (the 2a field that the `event-list` editor tabs over). The
  single field edits the whole `node.events`; per-event tabs come from `supportedEvents`, not from
  multiple `event-list` schema entries.
- `widgets/builtins/card.tsx` & `container.tsx` — append
  `{ key: 'children', label: 'Children', kind: 'children', group: 'Layout' }` (read-only summary in 2b;
  full edit on the canvas in 2c). Optionally add a `span` field here too, but the **`span` editor wiring is
  primarily 2c's** — gate it behind 2c if the canvas isn't present, or ship the inspector-side `SpanField`
  now and let 2c add the canvas handles (recommended: ship `SpanField` now; it writes `node.span` which
  round-trips harmlessly).

> These are the **only** descriptor edits in 2b. The `text`/`level`/`content`/`label`/`variant`/`src`/`alt`/
> `dataView.*` schema entries already exist from 2a (2a §2.1/§2.2 + §5.2). 2b consumes them; it does not
> re-declare them.

**(d)** Remove the now-unused `lucide-react` imports that only served `PropertyPanel`/`ComponentPalette`
icon glyphs if they're no longer referenced (the `Preview`/`Canvas` icon usage was already removed in 2a).
Run lint to catch dead imports.

### 5.4 No change — `pages/app/CustomPage/PageTreeRenderer.tsx`

Runtime rendering is unaffected by 2b (inspector/palette are builder-only). `PageTreeRenderer` already became
a thin `<RenderTree mode="runtime">` wrapper in 2a. **No edits in this slice.** (Listed explicitly so reviewers
don't expect a change.)

### 5.5 No change — `pageConfig.ts`

`PageConfig` (incl. `variables`/`dataSources`/`schemaVersion`) and `mergeConfig` were extended in 2a. 2b authors
`events`/`span`/`Binding` values **inside** the component tree, which already round-trips via the existing
`components` key. **No edits.** (Listed explicitly.)

### 5.6 Test-selector migration (existing tests)

The existing `PageBuilderPage.test.tsx` prop-edit assertions reference the old per-type testids
(`property-text`, `property-level`, `property-content`, `property-label`, `property-variant`, `property-src`,
`property-alt`, `property-collection`, `property-columns`, `property-limit`). The new scheme is
`property-field-<key>`:

| Old testid | New testid |
|-----------|-----------|
| `property-text` | `property-field-text` |
| `property-level` | `property-field-level` |
| `property-content` | `property-field-content` |
| `property-label` | `property-field-label` |
| `property-variant` | `property-field-variant` |
| `property-src` | `property-field-src` |
| `property-alt` | `property-field-alt` |
| `property-collection` | `property-field-dataView.collection` |
| `property-columns` | `property-field-dataView.fields` |
| `property-limit` | `property-field-dataView.limit` |

Update these in the same PR. `palette-item-<type>`, `property-panel`, `property-id`, `property-type` are
**unchanged**.

### 5.7 Registry bootstrap (no new work)

`registerBuiltins()` already runs at module scope (2a §5.6). `Inspector`/`Palette` call
`widgetRegistry.get`/`list` at render time, after bootstrap — no extra wiring. Tests call
`widgetRegistry.clear()` + `registerBuiltins()` in `beforeEach` for isolation (2a §6 idiom).

---

## 6. Test plan

Vitest + Testing Library + MSW, matching the existing idiom in `PageBuilderPage.test.tsx` (MSW `server` from
`vitest.setup`, `createTestWrapper`) and `pageConfig.test.ts` (pure-function tests). Field components that
read collection metadata mount inside the test wrapper that provides `CollectionStoreProvider` /
`ApiContext` (MSW-backed `/api/collections`), as the existing `FieldExpressionPicker`/`FieldsTab` tests do.

### 6.1 New — `inspector/propPath.test.ts`

- `getByPath`/`setByPath`/`deleteByPath` on flat (`text`) and nested (`dataView.collection`) keys;
  immutability (input object not mutated; returns a new object); creating intermediate objects when absent.

### 6.2 New — `inspector/fields/fields.test.tsx` (one block per kind)

For each `PropFieldKind`, mount the field with a `vi.fn()` `onChange` and assert the **value-write contract**:

| Kind | Assertion |
|------|-----------|
| `text` | typing "Orders" → `onChange('Orders')` |
| `textarea` | typing multiline → `onChange(<string>)` |
| `number` | typing 25 → `onChange(25)`; clearing → `onChange(undefined)` |
| `boolean` | checking → `onChange(true)`; unchecking → `onChange(false)` |
| `select` | choosing 'h2' from `schema.options` → `onChange('h2')` |
| `color` | entering `#ff0000` → `onChange('#ff0000')` |
| `collection-picker` | with MSW collections, choosing "Orders" → `onChange('orders')` (the **name**) |
| `field-picker` | with a selected sibling collection, toggling `status` → `onChange(['status'])`; with **no** collection → renders `…-empty` "Select a collection first." and does not crash |
| `expression` | inserting via `FieldExpressionPicker` `onInsert('record.name')` → `onChange({ $bind:'record.name', mode:'expr' })` (assert the **bare token** is stored, `{{}}` only displayed) |
| `span` | editing base→6 → `onChange({ base: 6 })` |
| `children` | renders the child-count summary read-only; no `onChange` |

### 6.3 New — `inspector/BindableField.test.tsx`

- **literal → expr write:** render with a string value, click `bindable-fx-<key>` → `onChange({ $bind:'', mode:'expr' })`;
  then drive the inner `ExpressionField` insert → `onChange({ $bind:'record.name', mode:'expr' })`.
- **expr → literal write:** render with `{ $bind:'record.name', mode:'expr' }`, click `fx` →
  `onChange(literalDefault)` (assert it equals `descriptor.defaultProps[key] ?? ''`).
- **fx hidden for non-bindable:** a field with `bindable:false` renders no `bindable-fx-*` toggle.
- **mode discrimination:** a `Binding` value mounts in expr mode (shows `bindable-expr-<key>` chip); a scalar
  mounts in literal mode (shows the literal editor).

### 6.4 New — `inspector/fields/EventListField.test.tsx`

Mount with `supportedEvents={['onClick']}` and a `value` of the whole `EventHandlers` map. Every assertion
checks that `onChange` receives the **wholesale `EventHandlers` map**, not a bare `PageAction[]`.

- **tabs:** with `supportedEvents={['onClick','onChange']}`, two tabs render; switching tabs targets the
  other event's actions.
- **add:** click `event-add-onClick`, pick `runFlow` → `onChange({ onClick: [{ action:'runFlow', flowId:'', input:{} }] })`.
- **remove:** with `value={{ onClick: [a0, a1] }}`, click `event-remove-onClick-0` → `onChange({ onClick: [a1] })`.
- **reorder:** move row 0→1 → `onChange({ onClick: [a1, a0] })`.
- **edit a param:** set `showToast` message → `onChange({ onClick: [{ ...a0, message: 'Done!' }] })` (action updates verbatim).
- **drops empty event:** removing the last action of an event → `onChange({})` (the `onClick` key is dropped, not left as `[]`).
- **runtime is NOT exercised here** (no flow execution) — that is asserted in 2e. A comment in the test names
  the boundary.

### 6.5 New — `inspector/Inspector.test.tsx`

- Loops `propSchema`: a `heading` node renders `property-field-text` + `property-field-level`, grouped under
  the "Content" header; a `table` node renders `dataView.collection`/`dataView.fields`/`dataView.limit` under "Data".
- `bindable` field (`heading.text`) shows `bindable-fx-text`; non-bindable (`heading.level`) does not.
- Editing `text` calls `onChange({ props: { ...prev, text: 'X' } })` (parity with old `PropertyPanel`).
- Editing `dataView.collection` calls `onChange({ props: { dataView: { ...prev.dataView, collection: 'orders' } } })`.
- `span`/`event-list` write to `onChange({ span })` / `onChange({ events })`, not `props`.
- `null` node → empty state with `property-panel` testid.
- Unknown/plugin type (no propSchema) → renders the read-only type/ID rows + an "No editable properties" hint,
  no crash.

### 6.6 New — `Palette.test.tsx`

- Renders one `palette-category-<category>` per non-empty category and a `palette-item-<type>` per built-in
  (all 8 present). Asserts the category grouping: `layout` (card, container), `content` (heading, text,
  button, image), `data` (table), and `input` (**form** — proves the `input` section is non-empty and that
  `palette-item-form` renders inside `palette-category-input`, not `palette-category-data`); `navigation` is
  absent (empty category omitted).
- Clicking `palette-item-heading` calls `onAddComponent('heading')`; `onDragStart` fires on drag start.
- A plugin component registered via `componentRegistry.registerPageComponent` does **not** appear in the
  palette (palette lists `widgetRegistry.list()` built-ins only — plugins are added to the canvas via the
  existing add flow / 2a shim, not the palette; assert this to lock the behavior).

### 6.7 Extend — `PageBuilderPage.test.tsx`

- Update selectors per §5.6 (`property-field-*`).
- Adding a component then editing it through the new `Inspector` produces the same `components` state shape
  the save path persists (e.g. add `heading`, type "Orders" → saved `props.text === 'Orders'`).
- Authoring a button `onClick` action persists `events.onClick` in the saved config.
- The `table`/`form` collection+columns edits produce the same `props.dataView` shape as before (parity).

### 6.8 e2e

Playwright e2e is **post-deploy only** (project convention). The parent spec's builder e2e covers
add-component → edit-property → save once deployed. No new e2e file in this slice; the inspector/palette are
covered by the Vitest suites above.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` (line ~48, "Page builder / screen builder" row) | Append: "slice 2b — **schema-driven inspector + palette**: the palette renders from `widgetRegistry.listByCategory()` (grouped by category) and the inspector (`inspector/Inspector.tsx`) loops over `descriptor.propSchema`, mapping each `PropFieldKind` to a field editor under `inspector/fields/*`; the hardcoded `AVAILABLE_COMPONENTS` array, `ComponentPalette`, and per-type `PropertyPanel` blocks are deleted. `bindable` props get an `fx` literal↔expression toggle (`BindableField`) that writes `{ $bind, mode:'expr' }` via `FieldExpressionPicker`; `EventListField` authors `PageAction[]` into `node.events` (add/remove/reorder) — **binding resolution lands in 2d, action runtime in 2e**." Keep the gap column unchanged ("typed/validated form fields", "per-page Cerbos authz" still move out in 2f/1h). |
| `.claude/docs/playbooks.md` | **Add to the "Add a page component / widget" recipe** (created in 2a) a new sub-step: *"Add a new inspector field kind."* Steps: (1) add the kind to `PropFieldKind` in `widgets/types.ts`; (2) create `inspector/fields/<Kind>Field.tsx` implementing `FieldEditorProps` with its `onChange` write contract; (3) add a `case` in `inspector/Inspector.tsx`'s kind→editor switch; (4) if it should support `fx`, mark the schema field `bindable: true` (Inspector auto-wraps in `BindableField`); (5) add a Vitest block to `inspector/fields/fields.test.tsx`; (6) localize every visible string the editor renders via `t('builder.*')` — never hardcode English. Note that authoring an `event-list`/`expression` value only *writes* the model — the runtime that consumes it is 2e/2d. |
| `.claude/docs/conventions.md` | If/when it documents page components: add the rule that **inspector property editors are schema-driven** — never add a per-type conditional to the inspector; add a `PropFieldSchema` entry to the widget descriptor and (if a new kind) a field editor. Cross-reference the `$bind` marker (`{ $bind, mode }`) as the single bound-value convention (full contract deferred to 2d). Note that all inspector/palette chrome strings (group/field labels, category headers, empty states) are localized via `useI18n`/`t()` with `builder.*` keys — never hardcoded English (§3.7). |
| `.claude/docs/concerns.md` ("Large files" context) | Note that 2b further shrinks `PageBuilderPage.tsx` by deleting `PropertyPanel` (~250 lines) and `ComponentPalette` (~40 lines) and extracting them to `inspector/*` + `Palette.tsx`. If the file is still oversized after 2b, add a tracking note. |

Per CLAUDE.md Rule 6 these doc edits ship **in the same PR** as the code.

---

## 8. Risks & open questions

- **`FieldExpressionPicker` takes a collection UUID, the page builder has none (yet).** Verified:
  `rootCollectionId: string | null` is a collection **id**, and the picker's cascading field discovery
  (`FieldsTab`) resolves relationships against the `CollectionStore`. In 2b the page builder has no bound
  "record collection" (that's a `PageDataSource`, authored in 2d), so `ExpressionField` passes
  `rootCollectionId={null}` and exposes the `record`/`vars`/`page` roots via `staticNamespaces`. **Open
  question:** the `record` namespace's leaf fields aren't known until 2d wires a page data source — in 2b the
  expression editor can still author free-form `$bind` strings (the picker also lets the user type), but the
  field-cascade is limited to declared namespaces. **Decision:** ship 2b with namespace-only expression
  authoring; 2d feeds the real `rootCollectionId` (from the page's primary data source) + live variable list.
  Flag in the PR so the user knows the expr picker gets richer in 2d.
- **`onInsert` emits a bare token, not `{{…}}`.** Verified from the Javadoc ("Callers wrap with `{{…}}`"). We
  store the bare token as `binding.$bind` and render `{{…}}` only for display. A test asserts the stored value
  is bare — getting this wrong would double-wrap (`{{{{…}}}}`) once 2d's resolver prepends `{{`. Locked by §6.2.
- **`EventListField` is a shell — do not build runtime here.** The temptation is to make the action rows
  "work" (run the flow on click) while building the editor. That belongs in 2e. The file header comment and a
  test-boundary note guard this. Authoring-only keeps 2b shippable and reviewable.
- **`span`/`children` editors straddle 2b/2c.** `SpanField` (inspector numeric editor) ships here; the canvas
  resize handles that *also* write `node.span` ship in 2c. `ChildrenField` is read-only here; child reorder is
  2c's canvas. Both write/round-trip harmlessly via the existing `components` key, so shipping the inspector
  side early carries no risk. **Decision:** ship both inspector-side now (recommended) — they're inert until
  2c adds the canvas counterpart.
- **Testid scheme change touches existing tests.** Moving from `property-<x>` to `property-field-<key>` (§5.6)
  is a mechanical migration; the container testids (`property-panel`/`property-type`/`property-id`) and
  `palette-item-<type>` are preserved to minimize churn and keep e2e selectors stable.
- **`PageBuilderPage.tsx` size.** This slice net-shrinks it (deleting `PropertyPanel` ~250 lines +
  `ComponentPalette` ~40 lines, extracting to `inspector/*`/`Palette.tsx`). It is **not** currently in
  `concerns.md`'s "Large files" table. Do the extraction with the parity suite green; don't combine with the
  2c canvas redesign.
- **Sequencing.** 2b hard-depends on 2a (`widgetRegistry`, `propSchema`, `model/*`). 2b is a prerequisite for
  a *usable* builder but not for 2c/2d/2e's other axes — those depend on `widgets/*` and `model/*` (2a), not on
  the inspector. The `fx` toggle's *resolution* (2d) and the event *runtime* (2e) consume what 2b authors, so
  land 2b before 2d/2e but it can land in parallel with 2c.
