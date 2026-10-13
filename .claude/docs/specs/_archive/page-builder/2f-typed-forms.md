# 2f — Typed form widgets

> **Slice:** 2f (FE). **Axis:** Widgets / typed forms.
> **Parent:** [`../page-builder-parity.md`](../../page-builder-parity.md). This child spec **extends, never
> contradicts** the parent's [Reuse Map](../../page-builder-parity.md#reuse-map) (ResourceForm / typed inputs /
> FieldType rows) and [Widget registry](../../page-builder-parity.md#widget-registry) (Input widgets row).
> **Depends on:** **2a** (the `WidgetDescriptor`/`PropFieldSchema`/`PageComponent` v2 contract, the
> `widgetRegistry`, the shared `RenderTree`, and the `form`/`table` builtins that moved out of
> `PageTreeRenderer`) and, for default values, **2d** (`{ $bind }` → `resolveBindings`/`bindingScope`).
> Source-verified against the codebase on 2026-06-22 (Flyway head V146; this slice adds no migration).
> If code and this doc disagree, trust the code and fix this doc.
>
> **Superseded for the `form` widget (2026-10-12).** The `ResourceForm` design below never worked at
> runtime: kelta-ui mounts no `KeltaProvider` (the §3.4 "verified" claim was wrong), so `ResourceForm`'s
> `useKeltaClient()` threw and every page with a `form` widget fell to the app's root error boundary. The
> widget now renders the app's shared `RecordForm` with `presentation="embedded"` (no page chrome, never
> navigates; after a save the page's tables refetch, `events.onSubmit` runs with the saved record as
> `record`, and a create resets). The saved config is unchanged — `dataView.collection`, `recordId`
> (present → edit), `mode` (`edit` with no record id shows "no record selected"), `readOnly` — plus the
> 1f `dataView.fields` subset, now honoured again and exposed as a field picker.
> `registerFormFieldRenderers.ts` / `formFieldRenderers.tsx` were removed (the rich controls come from
> `components/fieldControl`). Sections describing `ResourceForm`, Zod and `setComponentRegistry` are
> historical. The standalone typed inputs are unaffected.

---

## 1. Goal & scope

### What this slice delivers

Makes page-builder forms **typed and validated** instead of the text-only inputs shipped in slice 1f.

After 2a, the `form` builtin (`widgets/builtins/form.tsx`) wraps the **verbatim-moved** `FormNode` — a flat
list of `<input type="text">` that POSTs raw strings via `apiClient.postResource('/api/{collection}', values)`.
This slice **replaces that body** so the `form` widget renders through the `@kelta/components`
**`ResourceForm`** — schema-driven create/edit, Zod validation, one typed input per `FieldType`, field-level
authz, and a pluggable field-renderer registry — bound to a collection.

It also adds the parent's **Input-category standalone widgets**, each mapping a `FieldType` → the correct
control and sourcing its options from `useCollectionSchema` + the picklist endpoint:

| Widget | Control reused | FieldType(s) it targets |
|--------|----------------|-------------------------|
| `text-input` | shadcn `Input` (`@/components/ui/input`) | `string`, `phone`, `email`, `url`, `external_id`, `auto_number` |
| `number-input` | shadcn `Input type="number"` | `number`, `currency`, `percent` |
| `checkbox` | shadcn `Checkbox` (`@/components/ui/checkbox`) | `boolean` |
| `dropdown` | native `<select>` (picklist via schema) | `picklist` |
| `datepicker` | `Input type="date"` / `type="datetime-local"` | `date`, `datetime` |
| `lookup` | `LookupSelect` (`@/components/LookupSelect`) | `reference`, `lookup`, `master_detail` |
| `multi-picklist` | `MultiPicklistSelect` (`@/components/MultiPicklistSelect`) | `multi_picklist` |
| `rich-text` | `RichTextEditor` (`@/components/RichTextEditor`) | `rich_text` |

Each standalone input:

- binds to a `{ collection, field }` pair, reads the field's `FieldType` from `useCollectionSchema`, and
  renders the matching control (the same control the existing `ObjectFormPage` renders for that type);
- sources `dropdown`/`multi-picklist` options from `enumValues` (resolved via the picklist endpoint, FIELD or
  GLOBAL source, exactly as `ObjectFormPage` does today), and `lookup` options via `useCollectionSchema`'s
  reference resolution;
- accepts a `{ $bind }` **default value** (resolved by 2d's `resolveBindings` against the page scope —
  `record`/`vars`/`data.<src>`/`page`/`item`);
- writes its value into page state (a `vars`-backed binding target) so 2e events / the `form` submit can read
  it; the standalone inputs are **uncontrolled-by-default** local-state inputs that mirror into the binding
  target, matching how `ObjectFormPage` lifts `onChange(field.name, value)`.

Submission stays on the **authorized JSON:API path**: `ResourceForm` POSTs/PATCHes through the
`@kelta/sdk` client (`client.resource(name).create/update`), and standalone-input + `form` flows go through
`apiClient.postResource`/`patchResource`. The gateway + worker enforce Cerbos and write-FLS, and the **worker
validates required/type/unique on write** — client (Zod) validation is **advisory UX only** and never the
source of truth.

All new widgets register in `widgets/builtins/` and are resolved through the single `widgetRegistry` +
`RenderTree` introduced in 2a.

**Category — `input`.** The `form` widget (→ `ResourceForm`) **and** all eight standalone inputs are
`WidgetDescriptor.category: 'input'`, consistent with 2a (where `form`'s category was **fixed to `input` and
not moved later**) and the parent [Widget registry](../../page-builder-parity.md#widget-registry) Input row. The
`input` category is therefore already **non-empty from 2a** (the placeholder `form` builtin shipped under it);
2f only populates it with the typed controls. `widgetRegistry.listByCategory('input')` returns `form` + the
eight inputs (§6.4).

### What this slice explicitly does NOT do

| Out of scope | Lands in |
|--------------|----------|
| Bindings **UI** (`BindableField`/`fx` toggle), `resolveBindings`/`bindingScope`/`interpolate` impl | 2d (consumed here for default values) |
| Events/actions authoring + action runtime (`onSubmit` → run flow, `onChange` → setVar) | 2e (the `form`/inputs expose the event hooks; wiring is 2e) |
| The schema-driven `Inspector` shell + palette-from-registry | 2b (this slice only adds `propSchema` arrays + a few new `PropFieldKind`s) |
| Non-input widget breadth (`chart`, `tabs`, `nav`, `icon`, `link`, `image` polish) | 2g |
| Server-side validation changes | N/A — the worker already validates required/type/unique on write; unchanged |

### Parent-doc sections this conforms to

- **Reuse Map** — `ResourceForm` (schema-driven, Zod, typed inputs, field authz), `LookupSelect` /
  `MultiPicklistSelect` / `RichTextEditor` typed inputs, `useCollectionSchema` → `fetchCollectionSchema`,
  `FieldType` enum, `GET /api/fields…` / `GET /api/picklist-values?…`, and the JSON:API create/update path.
- **Widget registry → Input widgets** — `form` (→ `ResourceForm`), `text-input`, `number-input`, `checkbox`,
  `dropdown`, `datepicker`, `lookup` (→ `LookupSelect`), `multi-picklist` (→ `MultiPicklistSelect`),
  `rich-text` (→ `RichTextEditor`).
- **2a contract** — `WidgetDescriptor` / `PropFieldSchema` / `WidgetRenderProps` / `PageComponent` v2.
- **2d contract** — `{ $bind }` default values resolved through `resolveBindings(props, scope)`.

### Acceptance criteria

- The `form` widget, in `mode:'runtime'`, renders via `ResourceForm` bound to `props.dataView.collection`:
  typed inputs per `FieldType`, Zod validation messages, field-level authz (denied fields not shown), create
  (`recordId` absent) and edit (`recordId` bound) modes. The old text-only `FormNode` is **deleted**.
- Each standalone input widget maps its bound field's `FieldType` → the correct control (per the §3.1 table),
  matching what `ObjectFormPage` renders for that type today.
- `dropdown` and `multi-picklist` populate options from the picklist endpoint (FIELD when the field owns the
  values, GLOBAL when `fieldTypeConfig.globalPicklistId` is set), exactly as `ObjectFormPage` resolves them.
- `lookup` resolves reference options from the target collection.
- Every input accepts a `{ $bind }` default that resolves through 2d's scope.
- **Rich-text / HTML output is sanitized** through the same sanitizer `FieldRenderer`'s `rich_text` path uses;
  no `dangerouslySetInnerHTML` on unsanitized bound HTML (a `<script>`/`onerror` payload is stripped before
  render — §6.2a).
- All widgets are registered in `widgets/builtins/index.ts` `registerBuiltins()` and resolve through
  `widgetRegistry` + `RenderTree` (no new switch site).
- All new user-facing strings (empty/unconfigured, advisory required, "Select…" placeholder, submit toast,
  widget/prop labels) go through `useI18n` — no hardcoded literals (§3.6).
- Vitest: per-input (type→control, picklist options, validation) + form-submit (mock `apiClient`/SDK client).
- `status.md`: "typed/validated form fields" moves **out** of the page-builder gap column.
- `/verify` green (lint + typecheck + `test:coverage` ≥ existing kelta-ui gate).

---

## 2. UI samples

### 2.1 A `form` widget bound to a collection (runtime) — typed inputs

`form` node bound to `orders` (fields: `status` picklist, `dueDate` date, `customer` lookup, `total`
currency, `notes` rich_text):

```
┌─ form (orders) ───────────────────────────────────────────────┐
│  Status *           [ Open                       ▼ ]   ← dropdown (picklist enumValues)
│  Due date           [ 2026-06-22            📅 ]        ← datepicker (type="date")
│  Customer *         [ Acme Corp                  ▼ ]   ← LookupSelect (searchable)
│  Total              [ 1250.00                     ]    ← number-input (currency)
│  Notes              ┌───────────────────────────────┐ ← RichTextEditor
│                     │ B  I  U  • 1.  🔗  </>        │
│                     │ Ship by end of week.          │
│                     └───────────────────────────────┘
│                                                                │
│         [ Cancel ]                       [ Create ]            │
└────────────────────────────────────────────────────────────────┘
```

- Validation is live: clearing `Status` (required) shows `kelta-resource-form__error-message` "Required";
  submit is still attempted server-side (worker is the source of truth).
- A read-denied field (e.g. `internalScore`) is **absent** — `ResourceForm.accessibleFields` filters it via
  `schema.authz.fieldLevel` before render (and the worker would strip it regardless).

Before → after (the `form` widget body):

```
BEFORE 2f (the verbatim-moved FormNode from 1f/2a):
  fields.map(f => <input type="text" value={values[f]} onChange=… />)
  → every field is a bare text box; numbers, dates, picklists, lookups all typed as free text;
    no validation; POST { status:"Open", total:"1250.00", dueDate:"2026-06-22" } as strings.

AFTER 2f:
  <ResourceForm resourceName={collection} recordId={resolvedRecordId} onSave=… onCancel=… />
  → typed control per FieldType (dropdown / datepicker / LookupSelect / number / RichText), Zod
    validation, field authz; ResourceForm.transformFormData coerces number/boolean/json before the
    SDK create/update call.
```

### 2.2 Inspector data-source config (form widget)

The 2b inspector loops over the descriptor's `propSchema`; the `form` descriptor exposes:

```
┌─ Inspector · form ───────────────────────────┐
│ Data                                          │
│   Collection      [ orders            ▼ ]     │  collection-picker → props.dataView.collection
│   Mode            ( ) Create  (•) Edit        │  select → props.mode  ('create' | 'edit')
│   Record id        fx [ {{record.id}}    ]    │  expression (bindable) → props.recordId  (edit mode)
│   Read-only       [ ] off                     │  boolean → props.readOnly
│ Behaviour                                     │
│   On submit       [ + Add action ]            │  event-list → events.onSubmit  (authored in 2e)
└───────────────────────────────────────────────┘
```

A standalone input's inspector:

```
┌─ Inspector · dropdown ───────────────────────┐
│ Data                                          │
│   Collection      [ orders            ▼ ]     │  collection-picker → props.collection
│   Field           [ status            ▼ ]     │  field-picker (dependsOnCollection) → props.field
│   Default value    fx [ {{vars.status}}  ]    │  expression (bindable) → props.defaultValue
│   Required        [x] on                      │  boolean → props.required (advisory; worker enforces)
└───────────────────────────────────────────────┘
```

> The `field-picker` for a `dropdown` filters the collection's fields to `picklist` type; `multi-picklist`
> to `multi_picklist`; `lookup` to the reference types; `datepicker` to `date`/`datetime`; etc. (filter hint
> carried on `PropFieldSchema.fieldTypeFilter`, §3.3). In 2f the existing 2b inspector is reused; this slice
> only supplies the schemas.

### 2.3 Sample component tree JSON

```json
[
  {
    "id": "f1",
    "type": "form",
    "props": {
      "dataView": { "collection": "orders" },
      "mode": "edit",
      "recordId": { "$bind": "record.id", "mode": "path" },
      "readOnly": false
    },
    "events": { "onSubmit": [{ "action": "showToast", "level": "success", "message": "Saved" }] }
  },
  {
    "id": "d1",
    "type": "dropdown",
    "props": {
      "collection": "orders",
      "field": "status",
      "defaultValue": { "$bind": "vars.defaultStatus", "mode": "path" },
      "required": true
    }
  },
  {
    "id": "dt1",
    "type": "datepicker",
    "props": { "collection": "orders", "field": "dueDate" }
  },
  {
    "id": "lk1",
    "type": "lookup",
    "props": { "collection": "orders", "field": "customer", "required": true }
  }
]
```

The `events.onSubmit` array round-trips untouched in 2f (authored/run in 2e). `recordId`/`defaultValue` are
`{ $bind }` values resolved by 2d's `resolveBindings(props, scope)` inside `renderNode` **before** the
descriptor's `Render` runs — so per the 2a resolved-node invariant the descriptor receives **already-resolved**
plain values and 2f never calls `resolveBindings` itself (§3.5).

### 2.4 Before / after render path

```
TODAY (after 2a, before 2f):
  form  builtin → mode:'runtime' → <FormNode dataView=…/>   (text-only <input>, POST raw strings)
  inputs: none (no standalone input widgets exist)

AFTER 2f:
  form  builtin → mode:'runtime' → <ResourceFormWidget collection=… recordId=… />
                                     └─ <ResourceForm/> (@kelta/components: typed inputs, Zod, authz)
  dropdown/datepicker/checkbox/number-input/text-input/lookup/multi-picklist/rich-text builtins
     → each <…InputWidget collection field defaultValue/> → useCollectionSchema → typed control
```

No change to the editor-mode placeholder behaviour established in 2a: `mode:'editor'` still renders a static
placeholder box for `form` (and for each input, a disabled preview of the control) so the canvas needs no live
data. Live data fetch happens only at `mode:'runtime'`.

---

## 3. Data & API contracts

All TS lives under `kelta-ui/app/src/pages/PageBuilderPage/`. **No backend/API change in 2f** — every field's
type/picklist/reference metadata is already served by existing endpoints (see §4).

### 3.1 `FieldType` → input-widget → control mapping (authoritative)

The UI normalizes backend `FieldType` (uppercase) to lowercase via `useCollectionSchema`'s
`normalizeFieldType` (`DOUBLE/INTEGER/LONG→number`, `REFERENCE/LOOKUP→master_detail`, etc.). The mapping below
is over the **UI `FieldType`** union from `useCollectionSchema.ts` and mirrors the controls `ObjectFormPage`
renders today (verified at `ObjectFormPage.tsx` ~191–308).

| Backend `FieldType` (enum) | UI type (`useCollectionSchema`) | `form` (ResourceForm) control | Standalone widget | Standalone control |
|----------------------------|----------------------------------|-------------------------------|-------------------|--------------------|
| `STRING`, `TEXT` | `string` | `<input type="text">` | `text-input` | `Input` |
| `PHONE` | `phone` | `<input type="tel">` | `text-input` | `Input` |
| `EMAIL` | `email` | `<input type="email">` | `text-input` | `Input type="email"` |
| `URL` | `url` | `<input type="url">` | `text-input` | `Input type="url"` |
| `EXTERNAL_ID` | `external_id` | `<input type="text">` | `text-input` | `Input` |
| `AUTO_NUMBER` | `auto_number` | `<input type="text">` | `text-input` (read-only) | `Input disabled` |
| `INTEGER`/`LONG`/`DOUBLE` | `number` | `<input type="number">` | `number-input` | `Input type="number"` |
| `CURRENCY` | `currency` | `<input type="number">` | `number-input` | `Input type="number" step="0.01"` |
| `PERCENT` | `percent` | `<input type="number">` | `number-input` | `Input type="number"` |
| `BOOLEAN` | `boolean` | `<input type="checkbox">` | `checkbox` | `Checkbox` |
| `DATE` | `date` | `<input type="date">` | `datepicker` | `Input type="date"` |
| `DATETIME` | `datetime` | `<input type="datetime-local">` | `datepicker` | `Input type="datetime-local"` |
| `PICKLIST` | `picklist` | `<select>` (`enumValues`) | `dropdown` | native `<select>` |
| `MULTI_PICKLIST` | `multi_picklist` | (custom renderer) | `multi-picklist` | `MultiPicklistSelect` |
| `REFERENCE`/`LOOKUP`/`MASTER_DETAIL` | `master_detail` (+ `reference`/`lookup`) | text (default) → custom renderer | `lookup` | `LookupSelect` |
| `RICH_TEXT` | `rich_text` | `<textarea>` | `rich-text` | `RichTextEditor` |
| `JSON`/`ARRAY`/`GEOLOCATION` | `json` | `<textarea>` (JSON) | *(use `form`; no standalone in v1)* | — |
| `ENCRYPTED` | `encrypted` | `<input type="password">` | *(use `form`)* | — |
| `FORMULA`/`ROLLUP_SUMMARY`/`VECTOR` | `formula`/`rollup_summary`/— | **not editable** (computed) | excluded from `field-picker` | — |

> **Closing the ResourceForm gap via the field-renderer registry.** `ResourceForm`'s built-in
> `renderDefaultFieldInput` (verified at `ResourceForm.tsx` ~556–626) is **plain HTML**: `picklist` falls
> through to `<input type="text">`, `multi_picklist` has no case, and `reference`/`lookup`/`master_detail`
> render a text box with a placeholder. To make the **`form` widget** as rich as the standalone inputs, 2f
> registers app-side field renderers via `ResourceForm.setComponentRegistry(...)` (the exported
> `ComponentRegistryInterface` with `getFieldRenderer(fieldType)`/`hasFieldRenderer(fieldType)`), wiring the
> same `LookupSelect` / `MultiPicklistSelect` / native `<select>` / `RichTextEditor` controls in for
> `picklist`/`multi_picklist`/`reference`/`lookup`/`master_detail`/`rich_text`. `setComponentRegistry` is
> called once at page-builder bootstrap (see §5.6). This reuses the **existing pluggable field-renderer
> registry** rather than forking `ResourceForm`.

> **SECURITY — sanitize rich-text / HTML output (parent §"Security — binding & action output safety").** The
> `rich-text` widget — and **any** path that displays HTML-bearing field output (e.g. a `field-value`/registry
> renderer for a `rich_text` field, or a `{ $bind }` that resolves to HTML) — MUST pass that HTML through the
> **existing sanitizer** before it reaches the DOM: the **same** sanitizer `FieldRenderer`'s `rich_text` path
> uses today (reuse it; do not introduce a second sanitizer or relax its allow-list). **Never**
> `dangerouslySetInnerHTML` on unsanitized bound/authored HTML. Plain text bindings are auto-escaped by React
> and need no sanitizer; this rule is specifically for HTML-bearing output. The `RichTextEditor` *edit* control
> emits HTML on `onChange`; the worker remains the source of truth on write, but any **render** of that HTML
> (preview, read-only, bound display) goes through the sanitizer. A test asserts a `<script>`/`onerror`
> payload bound into a `rich_text` field is stripped before render (§6.2a).

### 3.2 Schema + picklist fetch contracts (already served — reused verbatim)

- **Collection schema** — `useCollectionSchema(collectionName)` → `fetchCollectionSchema` →
  `GET /api/collections/{name}?include=fields`. Returns `CollectionSchema { id, name, displayName,
  displayFieldName?, fields: FieldDefinition[] }`. Each `FieldDefinition` carries `type` (normalized),
  `required`, `referenceTarget`/`referenceCollectionId`, `fieldTypeConfig` (JSONB; object **or** JSON string),
  and `enumValues?`/`lookupOptions?` (populated at runtime by form pages — and by these widgets).
- **Picklist values** — `GET /api/picklist-values?filter[picklistSourceId][eq]=<id>&filter[picklistSourceType][eq]=<FIELD|GLOBAL>`.
  Source resolution (verified at `ObjectFormPage.tsx` ~838–865): parse `field.fieldTypeConfig` (handling both
  object and string); if `config.globalPicklistId` is present use `sourceId = globalPicklistId`,
  `sourceType = 'GLOBAL'`; else `sourceId = field.id`, `sourceType = 'FIELD'`. Response rows are
  `PicklistValueDto { value, label, isDefault, isActive, sortOrder }`; keep `isActive`, sort by `sortOrder`,
  map to `value`. **This logic is extracted into a shared hook** (§5.4 `usePicklistOptions`) so the `dropdown`,
  `multi-picklist`, and the `form` widget's field-renderer registry all share one implementation (no
  duplication of the resolution code across three sites).
- **Lookup options** — reference fields resolve their option list from the target collection
  (`referenceCollectionId`/`referenceTarget`) via the existing `useLookupDisplayMap` hook
  (`@/hooks/useLookupDisplayMap`, verified used by `ObjectFormPage`), producing `LookupOption { id, label }[]`.

> No new endpoint, query param, or response shape. The `?filter[…][eq]` casing (`[eq]`, lowercase) matches the
> existing call sites — keep it.

### 3.3 New widget contracts — descriptors + `propSchema`

The standalone inputs share a common props shape:

```ts
// widgets/builtins/inputs/types.ts
import type { PropValue } from '../../../model/pageModel'

/** Shared props for every standalone typed input widget. */
export interface InputWidgetProps {
  /** Collection whose schema supplies the field's FieldType + options. */
  collection: string
  /** Field name on that collection. */
  field: string
  /** {{$bind}} default value (resolved by 2d before Render); literal otherwise. */
  defaultValue?: PropValue
  /** Advisory client-side required flag; the worker is the source of truth on write. */
  required?: boolean
  /** Render disabled. */
  readOnly?: boolean
  /** Placeholder for text-like controls. */
  placeholder?: string
}
```

`propSchema` for the standalone inputs (consumed by the 2b inspector). One new `PropFieldKind` is needed and
one optional hint added to `PropFieldSchema` — both **additive** to the 2a `types.ts`:

```ts
// widgets/types.ts — additive deltas (do not break 2a)
export type PropFieldKind =
  | 'text' | 'textarea' | 'number' | 'boolean' | 'select' | 'color'
  | 'collection-picker' | 'field-picker' | 'expression' | 'event-list' | 'span' | 'children'
  // (no new kind required — field-picker + collection-picker already exist from 2a)

export interface PropFieldSchema {
  key: string
  label: string
  kind: PropFieldKind
  options?: Array<{ value: string; label: string }>
  bindable?: boolean
  group?: string
  dependsOnCollection?: boolean
  /** NEW (2f): when kind:'field-picker', restrict the field list to these UI FieldTypes. */
  fieldTypeFilter?: import('../hooks/useCollectionSchema').FieldType[]
}
```

`dropdown` descriptor (representative):

```tsx
// widgets/builtins/inputs/dropdown.tsx
import type { WidgetDescriptor } from '../../types'
import { DropdownInput } from './DropdownInput'

export const dropdownWidget: WidgetDescriptor = {
  type: 'dropdown',
  label: 'Dropdown',
  icon: '▾',
  category: 'input',
  acceptsChildren: false,
  defaultProps: { collection: '', field: '', required: false },
  propSchema: [
    { key: 'collection', label: 'Collection', kind: 'collection-picker', group: 'Data' },
    { key: 'field', label: 'Field', kind: 'field-picker', dependsOnCollection: true,
      fieldTypeFilter: ['picklist'], group: 'Data' },
    { key: 'defaultValue', label: 'Default value', kind: 'expression', bindable: true, group: 'Data' },
    { key: 'required', label: 'Required', kind: 'boolean', group: 'Data' },
  ],
  Render: ({ node }) => <DropdownInput {...(node.props as never)} />,
}
```

The other input descriptors are identical in shape, differing only in `type`/`label`/`icon`, the
`fieldTypeFilter`, and the inner control component:

| Widget `type` | `fieldTypeFilter` | inner component |
|---------------|-------------------|-----------------|
| `text-input` | `['string','phone','email','url','external_id','auto_number']` | `TextInput` |
| `number-input` | `['number','currency','percent']` | `NumberInput` |
| `checkbox` | `['boolean']` | `CheckboxInput` |
| `dropdown` | `['picklist']` | `DropdownInput` |
| `datepicker` | `['date','datetime']` | `DatePickerInput` |
| `lookup` | `['master_detail','lookup','reference']` | `LookupInput` (→ `LookupSelect`) |
| `multi-picklist` | `['multi_picklist']` | `MultiPicklistInput` (→ `MultiPicklistSelect`) |
| `rich-text` | `['rich_text']` | `RichTextInput` (→ `RichTextEditor`) |

`form` descriptor (replaces the 2a placeholder/`FormNode` body):

```tsx
// widgets/builtins/form.tsx (2f rewrite of the runtime branch)
export const formWidget: WidgetDescriptor = {
  type: 'form',
  label: 'Form',
  icon: '▤',
  category: 'input',
  acceptsChildren: false,
  defaultProps: { dataView: { collection: '' }, mode: 'create', readOnly: false },
  propSchema: [
    { key: 'dataView.collection', label: 'Collection', kind: 'collection-picker', group: 'Data' },
    { key: 'mode', label: 'Mode', kind: 'select',
      options: [{ value: 'create', label: 'Create' }, { value: 'edit', label: 'Edit' }], group: 'Data' },
    { key: 'recordId', label: 'Record id', kind: 'expression', bindable: true, group: 'Data' },
    { key: 'readOnly', label: 'Read-only', kind: 'boolean', group: 'Data' },
  ],
  Render: ({ node, mode }) =>
    mode === 'editor'
      ? <FormPlaceholder collection={readDataView(node.props).collection} />
      : <ResourceFormWidget node={node} />,
}
```

### 3.4 `ResourceForm` props used (verified — `ResourceForm.tsx` ~210 / `types.ts`)

```ts
interface ResourceFormProps {
  resourceName: string                       // ← props.dataView.collection
  recordId?: string                          // ← resolved props.recordId (edit mode); absent ⇒ create
  onSave: (data: unknown) => void            // ← fire events.onSubmit (2e) / showToast; invalidate
  onCancel: () => void                       // ← no-op / clear in builder runtime
  initialValues?: Record<string, unknown>    // ← from resolved defaults / data source (2d)
  readOnly?: boolean                         // ← props.readOnly
  className?: string                         // ← 'kelta-page-form'
}
```

`ResourceForm` internally: fetches schema via `client.discover()`, builds the Zod schema with `buildZodSchema`
(verified `ResourceForm.tsx` ~41–146 — `boolean`→`z.boolean`, `number/currency/percent`→preprocessed
`z.number`, `date/datetime`→date-parse refine, `email`/`url`/`json` refines, `reference/lookup/master_detail`
→`z.string`), filters fields by `schema.authz.fieldLevel` (`hasFieldAccess`), and on submit runs
`transformFormData` (number/boolean/json coercion) then `client.resource(name).create/update`. **We pass props
only — no fork.** The richer controls (picklist/lookup/multi/rich-text) come from the field-renderer registry
wired in §3.1/§5.6.

> **[Wrong — see the banner: kelta-ui mounts no `KeltaProvider`.]**
> **`ResourceForm` needs a `KeltaProvider` (`useKeltaClient`).** `@kelta/components` is already mounted under
> a `KeltaProvider` in kelta-ui (verified: `main.tsx`, `ObjectFormPage`, `ObjectDetailPage`,
> `CollectionsPage`, … all consume it). The page-builder runtime (`CustomPage`) and editor render inside the
> app shell that already provides it, so `ResourceFormWidget` mounts `<ResourceForm/>` directly. If a render
> surface is found to be outside the provider, wrap that subtree in `KeltaProvider` (see Risks §8).

### 3.5 Default-value resolution (depends on 2d) — resolved-node invariant

**2f descriptors honor the 2a [resolved-node invariant](../../page-builder-parity.md#widget-registry) and do NOT
call `resolveBindings` themselves.** Per that invariant, `WidgetRenderProps.node.props` handed to a
descriptor's `Render` is **always fully binding-resolved** by `renderNode` (`widgets/renderTree.tsx`) before
`Render` runs — in 2a via the identity no-op, in 2d via the real `resolveBindings(node.props, scope)`. So
every `{ $bind }` value a 2f widget cares about — a standalone input's `defaultValue`, and the `form`'s
`recordId` and any bound props (`mode`/`readOnly`) — **arrives already resolved to a plain literal** at
`Render`. A 2f descriptor only:

- declares those props `bindable` in its `propSchema` (so 2b's inspector offers the `fx` toggle), and
- **reads the resolved literal** (`props.defaultValue`, `props.recordId`) — never the `{ $bind }` marker.

2f adds **no** call to `resolveBindings`/`interpolate`/`bindingScope`; that is the resolver's job in
`renderNode`, not the widget's (the *only* descriptor the parent permits to re-resolve is `list`/`repeater`
under a per-row `item` scope — not a 2f widget). If 2d has not yet shipped on a branch, `resolveBindings` is
the 2a no-op pass-through and a `{ $bind }` props value is treated as a literal — the input shows no default
(and `form` no `recordId`) rather than crashing. The standalone control's local-state **seed** is therefore
read directly from the already-resolved `props.defaultValue`.

### 3.6 i18n — all new user-facing strings via `useI18n`

Per the parent slice plan (2b: "All new strings via `useI18n`"), **every** user-facing string 2f introduces is
added through the existing `useI18n` hook with a key — **no hardcoded English literals** in the new widgets,
controls, or descriptors. This covers:

- **Empty / unconfigured states** — the standalone-input "no field configured" box and the `FormPlaceholder`
  text (`pageBuilder.input.noFieldConfigured`, `pageBuilder.form.placeholder`).
- **Control affordances** — the `dropdown`/`form`-select "Select…" empty option
  (`pageBuilder.input.selectPlaceholder`); reuse the existing key if `ObjectFormPage` already has one rather
  than minting a duplicate.
- **Error / validation state** — the advisory required label surfaced on a standalone input
  (`pageBuilder.input.required`). The `form` widget's validation messages come from `ResourceForm`'s own
  (already-localized) Zod copy — **not** re-implemented here.
- **Feedback** — the default submit toast (`pageBuilder.form.saved`) fired by `onSave` (placeholder until 2e
  wires `events.onSubmit`).
- **Descriptor `label`s / palette** — widget labels (`Dropdown`, `Date picker`, …) and `propSchema` field
  `label`s shown by the 2b inspector are resolved through `useI18n` at the inspector/palette render site (the
  2b mechanism), so the descriptor `label`/propSchema `label` strings are i18n **keys/source** consumed by 2b,
  not raw display literals baked into the runtime.

New keys land in the existing kelta-ui locale catalog alongside the page-builder keys 2a/2b added. A test
asserts the empty/required/placeholder strings render via `useI18n` (no raw literal in the DOM-under-mock).

### 3.7 `config` JSON schema delta

**None.** All new props (`collection`, `field`, `defaultValue`, `mode`, `recordId`, `readOnly`, `required`,
`placeholder`) nest inside each component's existing `props` object in `config.components`. `events`/`span`
are the 2a optional fields, untouched. No `PageConfig` top-level change.

---

## 4. DB migrations

**None.** Field metadata (type, `required`, `enumValues`/picklists, `referenceTarget`,
`referenceCollectionId`, `fieldTypeConfig`) is **already served by existing endpoints** —
`GET /api/collections/{name}?include=fields` and `GET /api/picklist-values?filter[…]` — and consumed today by
`ObjectFormPage`/`ResourceFormPage`. This slice is **front-end only**: the widget props nest in the existing
`ui-pages.config` JSON column (no DDL, no Flyway version consumed; head remains **V146**). No NATS subject or
payload change. Confirmed explicitly: there is nothing new to migrate.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/pages/PageBuilderPage/` unless noted.

### 5.1 New — standalone input widgets

| File | Contents |
|------|----------|
| `widgets/builtins/inputs/types.ts` | `InputWidgetProps` (§3.3). |
| `widgets/builtins/inputs/useFieldDef.ts` | Hook: `useFieldDef(collection, field) → { fieldDef?, isLoading }` — wraps `useCollectionSchema(collection)` and finds the field by `name`. Single place that maps a `{collection,field}` pair → its `FieldDefinition` (type + config). |
| `widgets/builtins/inputs/usePicklistOptions.ts` | Hook extracting the `ObjectFormPage` picklist-source resolution (FIELD vs GLOBAL via `fieldTypeConfig.globalPicklistId`) + the `/api/picklist-values?filter[…]` fetch → sorted active `value[]`. Used by `DropdownInput`, `MultiPicklistInput`, and the `form` field-renderer registry. |
| `widgets/builtins/inputs/useLookupOptions.ts` | Thin wrapper over the existing `useLookupDisplayMap` (`@/hooks/useLookupDisplayMap`) → `LookupOption[]` for one reference field. |
| `widgets/builtins/inputs/TextInput.tsx` | `Input` (`@/components/ui/input`); `type` chosen from `fieldDef.type` (`email`/`url`/`tel`/`text`); `auto_number` → disabled. Local state seeded from resolved `defaultValue`; mirrors to binding target. `data-testid="page-input-text"`. |
| `widgets/builtins/inputs/NumberInput.tsx` | `Input type="number"`; `currency` → `step="0.01"`. `data-testid="page-input-number"`. |
| `widgets/builtins/inputs/CheckboxInput.tsx` | `Checkbox` (`@/components/ui/checkbox`) + label. `data-testid="page-input-checkbox"`. |
| `widgets/builtins/inputs/DropdownInput.tsx` | Native `<select>` populated from `usePicklistOptions`; "Select…" empty option; matches `ObjectFormPage` ~232–253 markup. `data-testid="page-input-dropdown"`. |
| `widgets/builtins/inputs/DatePickerInput.tsx` | `Input type="date"` (or `datetime-local` when `fieldDef.type==='datetime'`). `data-testid="page-input-date"`. |
| `widgets/builtins/inputs/LookupInput.tsx` | `LookupSelect` (`@/components/LookupSelect`) fed by `useLookupOptions`. `data-testid="page-input-lookup"`. |
| `widgets/builtins/inputs/MultiPicklistInput.tsx` | `MultiPicklistSelect` (`@/components/MultiPicklistSelect`) fed by `usePicklistOptions`; value normalized via the exported `normalizeMultiPicklistValue`. `data-testid="page-input-multipicklist"`. |
| `widgets/builtins/inputs/RichTextInput.tsx` | `RichTextEditor` (`@/components/RichTextEditor`); `value`/`onChange(html)`. `data-testid="page-input-richtext"`. |
| `widgets/builtins/inputs/{textInput,numberInput,checkbox,dropdown,datepicker,lookup,multiPicklist,richText}.tsx` | One `WidgetDescriptor` per widget (§3.3 shape), each importing its `*Input` control. |
| `widgets/builtins/inputs/index.ts` | `registerInputBuiltins()` — `widgetRegistry.register(...)` for all 8 input descriptors. |

Each `*Input` control:
- calls `useFieldDef(props.collection, props.field)`; while loading shows a disabled skeleton control;
- on missing collection/field shows a dashed "no field configured" box (matching the `FormNode`/`DataTableNode`
  empty-state idiom), its label via `useI18n` (`pageBuilder.input.noFieldConfigured`) — no hardcoded literal
  (§3.6);
- seeds local state from the **already-resolved** `props.defaultValue` (literal post-2d);
- in `mode:'editor'` renders **disabled** (preview-only); in `mode:'runtime'` is interactive and mirrors its
  value into the page binding target (`vars.<id>` convention from 2d) so 2e events / a sibling `form` can read
  it.

### 5.2 Rewrite — `widgets/builtins/form.tsx` (the primary reuse)

Replace the 2a runtime branch (`<FormNode/>`) with `<ResourceFormWidget/>`:

```tsx
// widgets/builtins/form.tsx
import { ResourceForm } from '@kelta/components'
import { readDataView } from './dataTableNode' // shared reader moved in 2a
import { useApi } from '@/context/ApiContext'
import { toast } from 'sonner'
import type { WidgetRenderProps } from '../types'

function ResourceFormWidget({ node }: { node: WidgetRenderProps['node'] }) {
  const collection = readDataView(node.props).collection
  const recordId = typeof node.props.recordId === 'string' ? node.props.recordId : undefined // resolved by 2d
  const readOnly = node.props.readOnly === true
  const queryClient = useQueryClient()
  if (!collection) return <FormPlaceholder collection={undefined} />
  return (
    <ResourceForm
      className="kelta-page-form"
      resourceName={collection}
      recordId={recordId}
      readOnly={readOnly}
      onSave={() => {
        void queryClient.invalidateQueries({ queryKey: ['page-table', collection] })
        // events.onSubmit runtime is wired in 2e; 2f fires a default success toast.
        toast.success(t('pageBuilder.form.saved')) // i18n via useI18n (§3.6) — no raw literal
      }}
      onCancel={() => { /* builder runtime: no-op */ }}
      data-testid="page-node-form"
    />
  )
}
```

- `FormNode` (the verbatim-moved text-only form from 2a, in `widgets/builtins/formNode.tsx`) is **deleted**,
  along with its file. Its `data-testid`s (`page-node-form`, `form-submit`, `form-success`, `form-error`) are
  superseded by `ResourceForm`'s markup (`kelta-resource-form__*` + the `[data-field]`/submit button); the
  parity tests are updated to the `ResourceForm` selectors (§6).
- `readDataView` stays (shared with `table`); `FormPlaceholder` (editor-mode static box) is added in
  `form.tsx`.

### 5.3 Removal of old text-only `FormNode` logic

- Delete `widgets/builtins/formNode.tsx` (created in 2a as the verbatim move).
- Remove its export from `widgets/builtins/index.ts` and its import in `form.tsx`.
- Grep-verify no other importer references `FormNode` (it only existed inside `PageTreeRenderer` pre-2a, moved
  to a builtin in 2a, removed here). The runtime now has **zero** bespoke form markup — all forms go through
  `ResourceForm`.

### 5.4 Shared hooks (extraction, not duplication)

`usePicklistOptions` and `useLookupOptions` (§5.1) extract logic that **already exists** inline in
`ObjectFormPage.tsx` and `ResourceFormPage.tsx`. Per the component-reuse rule (do not fork), 2f:
- creates the hooks under `widgets/builtins/inputs/`;
- has the new input widgets + the `form` field-renderer registry consume them;
- leaves `ObjectFormPage`/`ResourceFormPage` untouched in this slice (a follow-up may migrate them to the
  shared hooks — flagged, not done here, to keep the slice scoped).

### 5.5 `widgets/builtins/index.ts` — registration

```ts
// AFTER (2f): register the new input widgets alongside the 2a builtins
import { registerInputBuiltins } from './inputs'
export function registerBuiltins(): void {
  if (done) return
  done = true
  // …2a built-ins (heading/text/button/image/card/container/table/form)…
  registerInputBuiltins() // text-input, number-input, checkbox, dropdown, datepicker, lookup, multi-picklist, rich-text
}
```

`registerBuiltins` stays idempotent (the 2a `done` guard). Because both render surfaces already call
`registerBuiltins()` (2a §5.6), the input widgets light up in the builder palette, editor preview, and runtime
with no extra wiring.

### 5.6 `ResourceForm` field-renderer registry bootstrap

Add `widgets/builtins/registerFormFieldRenderers.ts`:

```ts
import { setComponentRegistry } from '@kelta/components'
import { DropdownInput, MultiPicklistInput, LookupInput, RichTextInput } from './inputs'
// Wrap each input control to the @kelta/components FieldRendererProps signature
// ({ value, field, onChange, readOnly }) and expose them by FieldType.
setComponentRegistry({
  hasFieldRenderer: (t) => ['picklist','multi_picklist','reference','lookup','master_detail','rich_text'].includes(t),
  getFieldRenderer: (t) => FIELD_RENDERERS[t],
})
```

Called once at page-builder bootstrap (same module-scope spot as `registerBuiltins()` in `PageBuilderPage.tsx`
and `PageTreeRenderer.tsx`, guarded for idempotency). This upgrades the **`form` widget's** picklist / lookup /
multi-picklist / rich-text inputs to the rich controls — reusing the existing pluggable registry rather than
modifying `ResourceForm`.

> **Coexistence check:** if the app has *already* called `setComponentRegistry` elsewhere for plugin field
> renderers, do not clobber it — the bootstrap merges (delegates unknown types to the prior registry). Verified
> the kelta-ui app does not currently call `@kelta/components`' `setComponentRegistry` (it wires its own
> `componentRegistry` for page/plugin components, a different object), so 2f is the first caller; the merge
> guard is defensive.

### 5.7 No-touch files

`registry.ts`, `renderTree.tsx`, `types.ts` (except the additive `fieldTypeFilter`), `model/*`,
`PageRenderService.java`/`PageRenderContract.java`, `componentRegistry.ts`, and the picklist/field endpoints are
**unchanged**. `ResourceForm.tsx` is **not forked** — only consumed + extended via its public
`setComponentRegistry` seam.

---

## 6. Test plan

Vitest + Testing Library + MSW, matching the existing idiom (`PageBuilderPage.test.tsx` MSW `server`,
`LookupSelect.test.tsx`/`MultiPicklistSelect.test.tsx` interaction tests, `ResourceForm.test.tsx`).

### 6.1 New — `widgets/builtins/inputs/inputWidgets.test.tsx` (per-input)

For each of the 8 input widgets, with `useCollectionSchema` mocked (MSW
`/api/collections/orders?include=fields`):

- **type → control:** mounting the widget bound to a field of the matching `FieldType` renders the expected
  control (assert by `data-testid`): `dropdown`→native `<select>`, `datepicker`→`input[type=date]`
  (and `datetime`→`datetime-local`), `checkbox`→`Checkbox`, `number-input`→`input[type=number]`,
  `text-input`→`input[type=email|url|tel|text]` per field type, `lookup`→`LookupSelect` trigger
  (`*-trigger`), `multi-picklist`→`MultiPicklistSelect`, `rich-text`→`RichTextEditor`.
- **picklist options:** mock `/api/picklist-values?filter[picklistSourceId][eq]=…&filter[picklistSourceType][eq]=FIELD`
  → `dropdown` renders one `<option>` per active value in `sortOrder` order; a field whose
  `fieldTypeConfig.globalPicklistId` is set fetches with `…[picklistSourceType][eq]=GLOBAL` and `sourceId` =
  the global id (assert the request URL). `multi-picklist` lists the same options as checkboxes.
- **validation (advisory):** a `required` `dropdown`/`text-input` left empty shows the advisory required state
  but the test asserts the value still POSTs (server is the source of truth) — i.e. client validation does
  **not** block submission silently.
- **default value:** `props.defaultValue` (literal, post-2d) seeds the control's initial value.
- **editor vs runtime:** `mode:'editor'` renders the control **disabled** and performs **no** picklist/lookup
  fetch; `mode:'runtime'` is interactive and fetches.
- **i18n (§3.6):** the empty "no field configured" state, the advisory required label, and the "Select…"
  placeholder render via `useI18n` keys (no raw English literal in the mocked DOM).

### 6.2 New — `widgets/builtins/form.test.tsx` (form submit)

- Renders `form` bound to `orders` in `mode:'runtime'` → mounts `ResourceForm`; with the schema mocked, assert
  typed controls appear (a `<select>` for the `status` picklist via the registry renderer, a `date` input for
  `dueDate`, a `LookupSelect` for `customer`).
- **submit (mock apiClient/SDK client):** fill required fields, submit → assert the `@kelta/sdk` client
  `resource('orders').create(...)` (or `apiClient.postResource`) is called with **coerced** values
  (`total` as number, not string), then `onSave` fires the success toast.
- **edit mode:** with `recordId` resolved, `ResourceForm` fetches the record and submits via `update`.
- **field authz:** a field present in `schema.authz.fieldLevel` the user lacks is **not** rendered.
- **empty/placeholder:** `mode:'editor'` (or no `collection`) renders `FormPlaceholder`, no fetch.

### 6.2a New — rich-text output sanitization (SECURITY)

In `widgets/builtins/inputs/inputWidgets.test.tsx` (or a focused `richTextSanitize.test.tsx`):

- **sanitizer runs on HTML render:** bind a `rich_text` field whose value contains a hostile payload
  (`<img src=x onerror="alert(1)">` and `<script>alert(1)</script>`) and render the read-only / bound display
  path → assert the `onerror`/`<script>` is **stripped** (not present in the DOM) and benign markup
  (`<b>`/`<a href>`) survives — i.e. the **same** sanitizer `FieldRenderer`'s `rich_text` path uses was
  applied.
- **no raw `dangerouslySetInnerHTML`:** assert the rendered subtree never injects the unsanitized string
  verbatim (a `javascript:`-href / inline handler from the bound HTML does not reach the DOM).

### 6.3 New — `widgets/builtins/inputs/usePicklistOptions.test.ts`

Pure-ish hook test (renderHook + MSW): FIELD vs GLOBAL source resolution from `fieldTypeConfig` (object **and**
JSON-string forms), `isActive` filtering, `sortOrder` ordering, empty-on-error fallback (matches
`ObjectFormPage` behaviour).

### 6.4 Extend — `widgets/registry.test.ts` / `renderTree.test.tsx`

- `widgetRegistry.listByCategory('input')` returns all 8 new widgets (+ `form`).
- `RenderTree` renders an input node end-to-end through the shared path (descriptor lookup → control).

### 6.5 e2e (post-deploy only — project convention)

Playwright, run after deploy (not in this PR): on a published page with a `form` bound to a collection, fill a
picklist + date + lookup, submit, and assert the record is created (and a denied field is absent). Covered by
the parent spec's positive page-render e2e; no new e2e file lands in this slice.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` (page-builder row, line ~48) | **Move "typed/validated form fields (text-only inputs today)" OUT of the gap column.** Add to the built list: "slice 2f — **typed form widgets**: the `form` widget renders via `@kelta/components` `ResourceForm` (typed input per `FieldType`, Zod validation, field-level authz, pluggable field-renderer registry wired to `LookupSelect`/`MultiPicklistSelect`/native `<select>`/`RichTextEditor`); standalone Input-category widgets `text-input`/`number-input`/`checkbox`/`dropdown`/`datepicker`/`lookup`/`multi-picklist`/`rich-text` map a `FieldType`→control, sourcing picklist options from `GET /api/picklist-values?filter[…]` (FIELD/GLOBAL) and reference options from the target collection; the text-only `FormNode` is removed; submit stays on the authorized JSON:API path (Cerbos/write-FLS server-side, worker validates required/type/unique)." Leave "per-page Cerbos authz" in the gap column (1h). |
| `.claude/docs/playbooks.md` | Extend the 2a "Add a page component / widget" recipe with the **typed-input sub-pattern**: a standalone input widget is a `WidgetDescriptor` (category `input`) whose `Render` reads `{collection,field}` via `useCollectionSchema`/`useFieldDef`, maps the `FieldType` to a control, and (for picklist/lookup) sources options from `usePicklistOptions`/`useLookupOptions`. For the `form` widget, prefer extending `ResourceForm` via `setComponentRegistry` over forking it. |
| `.claude/docs/conventions.md` | If/where it documents page widgets: add the rule that typed inputs **reuse** `LookupSelect`/`MultiPicklistSelect`/`RichTextEditor`/`ResourceForm` (never re-implement), the picklist source-resolution convention (`fieldTypeConfig.globalPicklistId` → GLOBAL else FIELD), that **client validation is advisory** — the worker validates required/type/unique on write, that **HTML-bearing output (`rich_text`) is sanitized via the same sanitizer `FieldRenderer` uses** (never `dangerouslySetInnerHTML` on unsanitized bound HTML — §3.1/§8), and that **all new user-facing widget strings go through `useI18n`** (§3.6). |
| `.claude/docs/integrations.md` | Confirm/cross-link the picklist + collection-schema fetch contracts as the canonical source for form field metadata (no new integration; reference existing endpoints). |

Per CLAUDE.md Rule 6 these doc edits ship **in the same PR** as the code.

---

## 8. Risks & open questions

- **`ResourceForm` runs on `@kelta/sdk`'s `client`, not the kelta-ui `apiClient`.** `ResourceForm` fetches its
  schema via `client.discover()` and submits via `client.resource(name).create/update` (verified
  `ResourceForm.tsx` ~232/296/323) — a **different** client than the input widgets' `useApi().apiClient`. Both
  ultimately hit the same authorized JSON:API through the gateway (Cerbos/FLS enforced), so security is
  preserved, but tests must mock the **SDK client** for the `form` path and `apiClient` for the standalone
  inputs. **Mitigation:** the `form.test.tsx` mocks `useKeltaClient`/the SDK client; input tests mock
  `apiClient`. Confirm the `KeltaProvider`'s client and `ApiContext`'s `apiClient` are pointed at the same
  base/auth (they are in the app shell).
- **`KeltaProvider` must wrap the render surface.** `ResourceForm` calls `useKeltaClient()`/`useCurrentUser()`;
  both throw outside a `KeltaProvider`. The app shell already provides it (verified `main.tsx` and sibling
  pages), and both the editor and `CustomPage` render inside the shell. **Open question:** does the page-builder
  **editor preview** (`PageBuilderPage` `Preview`, `mode:'editor'`) sit inside the provider? It renders only the
  `FormPlaceholder` in editor mode (no `ResourceForm` mount), so it does **not** need the provider — but verify
  before relying on it; if a live editor preview is ever added, wrap that subtree.
- **`ResourceForm` default controls are weaker than the standalone inputs.** Its built-in
  `renderDefaultFieldInput` renders `picklist`/`reference`/`multi_picklist` as plain text/`<input>` (verified
  ~556–626). Without the §5.6 field-renderer registry, the `form` widget would be *less* typed than the
  standalone `dropdown`/`lookup`. **Mitigation:** the registry wiring is **required**, not optional — it's part
  of acceptance. A `form.test.tsx` case asserts the picklist field renders a `<select>` (registry-backed), not
  a text box.
- **Picklist source-resolution logic is duplicated in `ObjectFormPage`/`ResourceFormPage`.** 2f extracts it
  into `usePicklistOptions` and uses the hook in the new widgets, but does **not** refactor the two existing
  pages in this slice (scope control). **Flagged** as a follow-up so the inline copies don't drift from the
  shared hook.
- **Computed/uneditable fields.** `formula`/`rollup_summary`/`vector`/`auto_number` must be excluded from a
  form's editable set and from the `field-picker` for inputs. `ResourceForm` does not special-case these; the
  `form` widget relies on the worker rejecting writes to computed fields, and the `field-picker` `fieldTypeFilter`
  keeps them out of standalone-input binding. **Decision:** exclude computed types client-side via
  `fieldTypeFilter`; `auto_number` is bindable but rendered disabled.
- **Rich-text / HTML output is XSS-sensitive (SECURITY).** `rich_text` field values are author/data-controlled
  HTML and a `{ $bind }` can resolve to a hostile string. Any **render** of HTML-bearing output (the
  `rich-text` widget's read-only/bound display, the `form`'s `rich_text` registry renderer, a bound
  `field-value`) MUST pass through the **same sanitizer** `FieldRenderer`'s `rich_text` path uses today — never
  `dangerouslySetInnerHTML` on unsanitized HTML (parent §"Security — binding & action output safety", §3.1
  note here). **Mitigation:** reuse the existing sanitizer (one allow-list, no fork); §6.2a asserts a
  `<script>`/`onerror` payload is stripped before render. Plain-text bindings are React-escaped and exempt.

- **`fieldTypeConfig` polymorphism.** It arrives as a parsed object **or** a JSON string (JSONB serialization
  path); `usePicklistOptions` must handle both (the `ObjectFormPage` code does — copy that handling exactly).
- **Sequencing.** 2f hard-depends on **2a** (registry/RenderTree/`form` builtin/`readDataView`) and
  soft-depends on **2d** (`{ $bind }` default-value resolution — degrades to literal if absent). It should land
  after 2a and ideally after 2d. 2b (inspector) is independent: 2f only **supplies** `propSchema` arrays; the
  inspector that renders them is 2b's. Events (`onSubmit`/`onChange` runtime) are 2e — 2f fires a default
  success toast as a placeholder until 2e wires real `events.onSubmit` actions.
