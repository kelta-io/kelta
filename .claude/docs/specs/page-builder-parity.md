# Page Builder → OutSystems Parity — Parent Spec

> **Status:** parent planning spec. This is the authoritative shared contract for the page-builder
> parity effort. Each slice in the [Slice plan](#slice-plan) is expanded into its own detailed
> feature spec under [`page-builder/`](./page-builder/). Child specs **extend, never contradict**
> the [Reuse Map](#reuse-map), [shared model](#the-shared-model), [widget registry](#widget-registry),
> and [config-JSON v2 schema](#page-level-config-v2) below.
>
> Source-verified against the codebase on 2026-06-22 (Flyway head V146). If code and this doc
> disagree, trust the code and fix this doc.

## How to use this document

This parent spec defines the cross-cutting architecture once. The child specs in
[`./page-builder/`](./page-builder/) each cover one PR-sized slice with acceptance criteria, UI
samples, exact contracts, DB migrations (or an explicit "none"), and file-by-file code changes —
per the [Child-spec template](#child-spec-template). Read this parent first; every child references
it.

| Slice | Child spec | Axis |
|-------|-----------|------|
| 1g — Render contract v2 | [page-builder/1g-render-contract-v2.md](./page-builder/1g-render-contract-v2.md) | foundation (backend) |
| 2a — Widget registry + shared render | [page-builder/2a-widget-registry.md](./page-builder/2a-widget-registry.md) | foundation (FE) |
| 2b — Schema-driven inspector + palette | [page-builder/2b-inspector-palette.md](./page-builder/2b-inspector-palette.md) | foundation (FE) |
| 2c — Layout engine + dnd-kit canvas | [page-builder/2c-layout-engine.md](./page-builder/2c-layout-engine.md) | **Layout & grid** |
| 2d — Data binding & expressions | [page-builder/2d-binding-expressions.md](./page-builder/2d-binding-expressions.md) | **Data binding** |
| 2e — Events & actions | [page-builder/2e-events-actions.md](./page-builder/2e-events-actions.md) | **Logic & events** |
| 2f — Typed form widgets | [page-builder/2f-typed-forms.md](./page-builder/2f-typed-forms.md) | **Widgets/typed forms** |
| 2g — Widget breadth | [page-builder/2g-widget-breadth.md](./page-builder/2g-widget-breadth.md) | **Widgets** |
| 1h — Per-page authz (optional) | [page-builder/1h-per-page-authz.md](./page-builder/1h-per-page-authz.md) | backend |

## Context

The last big dev push (OutSystems-parity roadmap, Recs 1–10) shipped a *functional* page
builder — but "functional" means **read-a-table + create-a-record**. The current designer
(`app.kelta.io/<tenant>/setup/pages`) is a thin shell: 8 static primitives (heading, text,
button, image, form, table, card, container), a **vertical-stack-only** canvas (the `position`
field is stored but ignored), native HTML5 drag that only appends to root, static props with no
expressions, no page state/logic, buttons that only do `href`, and text-only form inputs. Against
OutSystems' layout engine + data binding + reactive logic + widget library, it falls well short.

This effort closes the gap across **all four axes**:
1. **Layout & responsive grid** — grid/row/column containers, responsive spans, drop-into-container,
   reorder, resize.
2. **Data binding & expressions** — page variables, on-load data sources, bind *any* prop to a
   record field / expression (`{{record.name}}`, `IF(...)`).
3. **Logic & events/actions** — wire events (button click → run flow / navigate / create-update
   record / refresh data / set var).
4. **Widget library + typed forms** — a real widget set and typed/validated inputs
   (date/number/picklist/lookup) instead of text-only.

**Intended outcome:** a builder whose authored pages express genuine app behavior, with the runtime
renderer and editor preview sharing one widget-render path, and all data access still flowing
through the authorized JSON:API so Cerbos/FLS stay enforced server-side.

## Key architecture decisions (verified against the code)

- **The central move is a *widget descriptor registry*.** Today the component types are hardcoded in
  **5 per-type `switch`/conditional sites** kept in lockstep: `AVAILABLE_COMPONENTS` palette,
  `PropertyPanel`, `Canvas.renderComponent`, `Preview.renderPreviewComponent` (all in
  [PageBuilderPage.tsx](../../../kelta-ui/app/src/pages/PageBuilderPage/PageBuilderPage.tsx)), and
  `PageNodeRenderer` in
  [PageTreeRenderer.tsx](../../../kelta-ui/app/src/pages/app/CustomPage/PageTreeRenderer.tsx).
  Replace all five with **one registry** (`type → { label, icon, category, defaultProps, propSchema,
  Render }`). Palette, inspector, editor preview, and runtime renderer all become schema-driven
  loops. This is the keystone — every axis hangs off it.
- **One shared render module** for editor preview *and* runtime (they duplicate today).
- **Backend stays a pass-through.** The whole tree already lives in the `ui-pages.config` JSON
  column; the render service returns it as-is. Keep that — **no server-side binding resolution**
  (would fetch records outside the FLS path and leak read-denied fields) and **no Flyway migration**
  (everything new nests inside `config`). Verified: flow-execute endpoint already exists; typed-form
  field metadata already served by existing endpoints.
- **Reuse-first** (most reuse is already built — see Reuse Map). Do not reinvent expression eval,
  typed inputs, bound tables, the expression picker, or layouts.
- **DnD: adopt `@dnd-kit/core` + `@dnd-kit/sortable`** (one new dep in `kelta-ui/app`), scoped to the
  page canvas only. `PageLayoutsPage` stays on native DnD (no forced migration).

## Reuse Map

Use these — do not rebuild.

| Need | Reuse | Path |
|------|-------|------|
| Expression evaluation | `FormulaEvaluator.evaluate(expr, flatScope)`, `extractFieldRefs` | `kelta-web/packages/formula/src/` |
| Bound data table | `DataTable` (resourceName, columns, filters, pagination) | `kelta-web/packages/components/src/` |
| Typed create/edit form | `ResourceForm` (schema-driven, Zod, typed inputs, field authz) | `kelta-web/packages/components/src/` |
| Responsive layout chrome | `PageLayout`/`*ColumnLayout`, `ResponsiveBreakpoints`, `RuleEngine`/`useLayoutRules` | `kelta-web/packages/components/src/` |
| Read-only bound value + 21 field types | `FieldRenderer` (plugin-overridable) | `kelta-ui/app/src/components/FieldRenderer/` |
| Expression/field picker (literal↔expr) | `FieldExpressionPicker` (FieldsTab + FunctionsTab, StaticNamespace roots, emits dot-paths/fn stubs) | `kelta-ui/app/src/components/FieldExpressionPicker/` |
| Typed inputs | `LookupSelect`, `MultiPicklistSelect`, `RichTextEditor`, `InlineEditCell` | `kelta-ui/app/src/components/` |
| Variable insertion | `VariablePicker` | `kelta-ui/app/src/components/VariablePicker/` |
| Collection/field schema fetch | `useCollectionSchema` → `fetchCollectionSchema` (`GET /api/collections/{name}?include=fields`); `CollectionStoreContext` | `kelta-ui/app/src/hooks/useCollectionSchema.ts` |
| Plugin widget fallback | `componentRegistry.getPageComponent(type)` | `kelta-ui/app/src/services/componentRegistry.ts` |
| Run a flow from an event | `POST /api/flows/{flowId}/execute` body `{ input }` (async → poll `GET /api/flows/executions/{id}`) | `kelta-worker/.../controller/FlowExecutionController.java` |
| Create/update record from an event | JSON:API `POST /api/{collection}` / `PATCH /api/{collection}/{id}` (Cerbos/write-FLS enforced) | existing |
| Form field types/picklists | `GET /api/fields?filter[collectionId][EQ]=…`, `GET /api/picklist-values?…`; `FieldType` enum | `runtime-core/.../model/FieldType.java` |

Already-present deps usable by widgets: `recharts`, `@tiptap/*`, `@tanstack/react-virtual`,
`radix-ui` (tabs/dropdown/checkbox/datepicker popover), `sonner`, `zod`.

## The shared model

New dir `kelta-ui/app/src/pages/PageBuilderPage/model/`:

- **`pageModel.ts`** — the v2 component/page model:
  ```ts
  type Binding = { $bind: string; mode?: 'path' | 'expr' }  // {{...}} convention
  type PropValue = string|number|boolean|null | Binding | PropValue[] | { [k:string]: PropValue }
  function isBinding(v): v is Binding
  type PageAction =
    | { action:'runFlow'; flowId:string; input?:Record<string,PropValue>; awaitResult?:boolean }
    | { action:'navigate'; to:string; params?:Record<string,PropValue>; newTab?:boolean }
    | { action:'openUrl'; url:PropValue; newTab?:boolean }
    | { action:'createRecord'|'updateRecord'; collection:string; attributes:Record<string,PropValue>; recordId?:PropValue }
    | { action:'refreshData'; dataSource:string }
    | { action:'setVar'; name:string; value:PropValue }
    | { action:'showToast'; level:'info'|'success'|'error'; message:PropValue }
  type EventHandlers = Partial<Record<'onClick'|'onChange'|'onSubmit'|'onLoad', PageAction[]>>
  interface ResponsiveSpan { base:number; sm?:number; md?:number; lg?:number }  // 1..12
  interface PageComponent { id; type; props:Record<string,PropValue>; events?:EventHandlers;
                            span?:ResponsiveSpan; children?:PageComponent[] }  // `position` is dropped from the active model BY slices 2a/2c (tolerated/ignored on read until then)
  ```
- **`bindingScope.ts` / `resolveBindings.ts` / `interpolate.ts`** — scope `{ record, vars, page, item }`.
  `mode:'path'` → `getPath(scope, "record.name")` walker (the formula parser is flat-key only, so
  dotted paths resolve here). `mode:'expr'` → flatten referenced leaves (via `extractFieldRefs`) then
  `FormulaEvaluator.evaluate`. `interpolate("Hi {{record.name}}", scope)` for templated strings.
- **`treeOps.ts`** — pure tree mutations: `insertNode`, `moveNode`, `removeNode`, `updateProps`,
  `setSpan` (unit-tested; fixes "drop only appends to root").
- **`migrate.ts`** — legacy `position {row,column,width,height}` → container/grid tree (back-compat).

### Page-level config (v2)

Extend [pageConfig.ts](../../../kelta-ui/app/src/pages/PageBuilderPage/pageConfig.ts), still inside
the `config` JSON column:
```ts
interface PageVariable { name; type:'string'|'number'|'boolean'|'json'; default?:PropValue }
interface PageDataSource { name; collection; fields?; filter?; sort?; limit?; mode:'list'|'single'; recordId?:PropValue }
interface PageConfig { layout?; components?; variables?:PageVariable[]; dataSources?:PageDataSource[]; access?:{requiredPermission?:string}; schemaVersion?:2 }
```
**Canonical storage (authoritative — all slices must agree):** the component tree lives at
**`config.components`** — the location the existing builder already writes and `CustomPage` already
reads. There is **NO `config.tree` wrapper.** `variables`, `dataSources`, `access`, and
`schemaVersion` are **siblings of `components`** inside `config`. The render contract's `tree` field
(below) carries the whole `config` map verbatim, so `tree.components` resolves; `variables`/`dataSources`
are *also* surfaced as separate contract fields read from `config.*` top-level. Per-node `events` and
`span` nest inside the `components` tree (so they persist with it); the page-level siblings
(`variables`/`dataSources`/`access`/`schemaVersion`) do **not** persist unless the save call passes
them — see [Save & persistence](#save--persistence-v2-round-trip).

Binding namespace (authoritative): `record` (current record / repeat row via `item`), `vars` (page
variables), `data.<sourceName>` (on-load data source result), `page` (route params/meta), `item`
(per-row scope inside `list`/`repeater`). `$bind` is the single expression marker; everything else is
a literal. The server never parses `$bind` — it round-trips untouched.

## Widget registry

New dir `kelta-ui/app/src/pages/PageBuilderPage/widgets/`:
- **`types.ts`** — `WidgetDescriptor { type, label, icon, category, defaultProps, propSchema:PropFieldSchema[], acceptsChildren?, supportedEvents?:Array<keyof EventHandlers>, Render }`
  and `PropFieldSchema { key, label, kind, options?, bindable?, group?, dependsOnCollection? }` where
  `kind ∈ text|textarea|number|boolean|select|color|collection-picker|field-picker|expression|event-list|span|children`.
  `supportedEvents` is declared here (in 2a) so the inspector's `event-list` field can render one
  tabbed editor over the whole `EventHandlers` (see Inspector below) — 2e only adds the runtime.
- **`registry.ts`** — `widgetRegistry` singleton (`register`, `get`, `list`, `listByCategory`).
  `get(type)` falls back to wrapping `componentRegistry.getPageComponent(type)` in a synthetic
  descriptor (plugins keep working with **zero changes**; the builder/runtime stop special-casing
  "unknown type").
- **`renderTree.tsx`** — `RenderTree({ components, scope, mode, tenantSlug })` + `renderNode`. The
  **single** render path: look up descriptor, resolve `node.props`, call
  `descriptor.Render({ node, scope, mode, tenantSlug, renderChild })`. Used by editor preview
  (`mode:'editor'`) and runtime (`mode:'runtime'`).
  **Resolved-node invariant (authoritative):** `WidgetRenderProps.node.props` handed to `Render` is
  **always fully binding-resolved** (in 2a the resolver is an identity no-op; 2d makes it real).
  Descriptors **must not** call `resolveBindings` themselves — the *only* exception is `list`/`repeater`,
  which re-resolves its children under each per-row `item` scope. `renderChild` has signature
  `(child: PageComponent, scope?: BindingScope) => ReactNode` from 2a onward (the optional `scope` lets
  a repeater pass an `item`-augmented scope). The plugin shim therefore passes **resolved** props to
  plugin components (never `$bind` markers) — an intentional part of the "zero plugin changes" guarantee.
- **`builtins/*.tsx`** — one descriptor per widget. v1 set, each wrapping a reused component where one
  exists. (Type strings are load-bearing — `table`/`form` keep their legacy strings for back-compat
  with already-saved pages; do **not** rename to `data-table`.)
  - Layout: `grid` (12-col), `row`, `column`, `container`, `card`, `divider`.
  - Content: `heading`, `text`, `image`, `icon`, `link`, `button`.
  - Data: `table` (→ `DataTable`), `list`/`repeater` (binds an array source, renders children per
    `item`), `field-value` (→ `FieldRenderer`), `chart` (→ `recharts`).
  - Input: `form` (→ `ResourceForm`; **category `input`** — fixed in 2a, not moved later), `text-input`,
    `number-input`, `checkbox`, `dropdown` (picklist via schema), `datepicker`, `lookup` (→ `LookupSelect`),
    `multi-picklist` (→ `MultiPicklistSelect`), `rich-text` (→ `RichTextEditor`).
  - Navigation: `nav` (→ `Navigation`), `tabs` (→ radix).

## Inspector + canvas

- `inspector/Inspector.tsx` + `inspector/fields/*` — schema-driven property editor looping over
  `descriptor.propSchema`, grouped by `group`. Each `bindable` field wraps in `BindableField` (the
  `fx` literal↔expr toggle → opens `FieldExpressionPicker` with `record`/`vars`/`page` namespaces,
  stores `{ $bind }` holding a **bare** token; `{{…}}` is added only for display). **`EventListField`
  is one field of `kind:'event-list'` with `key:'events'`** — it edits the whole `node.events`
  (`EventHandlers`) as tabs over `descriptor.supportedEvents`, each tab an ordered `PageAction[]`
  (add/remove/reorder; flow picker from `/api/flows`). 2b ships the editor shell; 2e wires the runtime.
- `canvas/Canvas.tsx` + `canvas/dnd/*` — `@dnd-kit` `DndContext` (PointerSensor + KeyboardSensor).
  Palette items are draggable sources; every container is a droppable with a per-container
  `SortableContext` for reorder (`closestCenter`). Drops/moves call `treeOps`. Grid-span resize drags
  a child's handle and snaps `span` to the 12-col grid (no pixel coords). `SelectableNode` wraps each
  node (selection outline, drag handle, resize handles).

## Backend changes (small — pass-through only)

- **Render contract v2** —
  [PageRenderContract.java](../../../kelta-worker/src/main/java/io/kelta/worker/service/PageRenderContract.java) /
  [PageRenderService.java](../../../kelta-worker/src/main/java/io/kelta/worker/service/PageRenderService.java):
  bump `CONTRACT_VERSION` `"1.0"→"2.0"`; surface richer config:
  ```java
  record PageRenderContract(String version, String slug, String title, String path,
      List<Map<String,Object>> variables, List<Map<String,Object>> dataSources, Map<String,Object> tree)
  ```
  `render()` parses `config` once; **`tree` = the whole `config` map verbatim** (so the FE reads
  `tree.components` exactly as today — there is **no `config.tree` nesting**); `variables` =
  `config.variables` (List) else `[]`; `dataSources` = `config.dataSources` else `[]`. Null-safe; both
  a v1 page (no `variables`/`dataSources` keys) and a v2 page yield `version:"2.0"` with the arrays
  empty-or-populated and `tree:<wholeConfig>`. **Remains a pass-through — no binding/dataSource
  resolution server-side.** `CustomPage` reads `tree.components` plus the sibling `variables`/`dataSources`
  contract fields.
- **Per-page authz (optional, slice 1h).** In `render()`, if `config.access.requiredPermission` is
  present, check it via the same in-controller Cerbos system-permission mechanism `/api/admin/**`
  uses; deny → `Optional.empty()` → controller returns **404** (matches existing unknown-slug
  privacy). Absent key ⇒ unchanged published+active+tenant gate. **No DB column, no migration, no
  NATS change.**
- **No change** to NATS (`UIPageConfigEventPublisher`/`UIPageSlugHook` already correct), the flow
  endpoint, or field-metadata/picklist endpoints.

## Save & persistence (v2 round-trip)

**Mandatory — this is the #1 correctness risk.** The current `handleSavePage`
([PageBuilderPage.tsx](../../../kelta-ui/app/src/pages/PageBuilderPage/PageBuilderPage.tsx)) saves only
`mergeConfig(readConfig(currentPage), { components })`, and `mergeConfig` only overlays a key when it
is passed. So any page-level sibling (`variables`/`dataSources`/`access`/`schemaVersion`) is **silently
dropped on save** unless the save call passes it — the exact bug class the original `components`/`layout`
drop (slice 1b) already hit.

- **Slice 2c owns the canonical `handleSavePage` rewrite** (first slice that must persist
  `schemaVersion:2` on migration): widen `mergeConfig` to overlay `variables`/`dataSources`/`access`/
  `schemaVersion`, and change the one save call site to pass the full current set:
  `mergeConfig(readConfig(currentPage), { components, variables, dataSources, schemaVersion: 2 })`.
- **2d** adds `variables`/`dataSources` to that same call; **1h** adds `access`.
- Per-node `events`/`span` ride inside `components` and need no extra wiring.
- **Test the mutation payload, not just `mergeConfig`'s return** — assert the `updateMutation.mutate`
  body contains every v2 key (see Testing → save round-trip).

## Security — binding & action output safety

New surfaces (bindings into the DOM, event-driven navigation/writes) require:
- **URL scheme allow-list (2e + 2g).** `link.href`, `image.src`, and the `navigate`/`openUrl` action
  targets are author/data-controlled (a `{ $bind }` can resolve to `javascript:`/`data:`). Reject any
  scheme not in `{http, https, mailto, tel, relative}` before `assign`/`open`/render. Add a blocked-`javascript:`
  test.
- **Text bindings** are auto-escaped by React (safe). **`rich-text`/HTML output (2f)** must pass the
  existing sanitizer (same one `FieldRenderer`'s `rich_text` path uses) — never `dangerouslySetInnerHTML`
  on unsanitized bound HTML.
- **Expression engine.** `@kelta/formula` is a custom AST evaluator with no `eval`/`Function`/`window`/
  `globalThis` reach — state this guarantee in `conventions.md`. `getPath` (2d) must skip
  `__proto__`/`constructor`/`prototype` tokens (return null) to avoid prototype-chain traversal.
- **Abuse/governor.** `runFlow`/`createRecord`/`updateRecord` from a *published end-user* page ride
  Cerbos/write-FLS (authz preserved) but consume per-tenant API governor quota per click — note in
  `concerns.md`.

## DoS / fan-out caps (2d)

- `MAX_PAGE_DATA_SOURCES` (e.g. 12) — a page declaring more is rejected in the builder (each source
  fires its own on-load fetch).
- **Repeater render cap** (e.g. 200 rows) with a "showing N of M" truncation — `list`/`repeater` must
  not render an unbounded child subtree ×N. (Existing `MAX_TABLE_ROWS=100`, `MAX_HTTP_PAGE_SIZE=200`
  cap the bound table; the repeater needs its own cap.)
- Each page view consuming governor quota proportional to its data sources is expected — document it.

## Rollout & rollback

- **Feature-flag the new runtime render path.** 2a replaces `PageTreeRenderer` for **every** custom
  page across all tenants in one merge. Gate `CustomPage`'s use of `RenderTree` behind a system feature
  flag (platform already has `kelta.config.feature.changed` infra) with fallback to the legacy
  `PageNodeRenderer`; keep the legacy renderer in the tree until 2a soaks. The "move
  `DataTableNode`/`FormNode` verbatim into descriptors" approach must preserve a clean revert.
- **2a is split:** (a) introduce `model/` + `widgets/` + `RenderTree` + builtins behind the registry,
  parity-tested with a **golden snapshot** of all 8 widgets captured *before* the refactor; (b) flip
  the runtime/builder to consume it, gated on the parity suite green. The one deliberate behavior change
  — runtime `heading` honoring `level` instead of hardcoded `<h2>` — must show as a reviewed snapshot diff.
- **Rollback trigger:** page-render error rate / a spike in unknown-widget fallbacks → flip the flag.

## End-to-end e2e ownership

The full flow must have ONE owned spec — not a phantom "covered by the parent." (The original
premise that a new route can't get e2e in its introducing PR was wrong: the PR's E2E job builds
HEAD. The spec now runs there, authoring the page through the `ui-pages` JSON:API rather than
drag-and-drop — see `testing.md` → End-to-end.) **Add
`e2e-tests/.../page-builder-v2.spec.ts`, owned by the terminal behavior slice (2e):** palette → drop into a grid column → bind a prop → wire a button
`onClick` event → save → publish → open the runtime page → **assert a mutation succeeds** (a record is
created via the button event and persists), not merely that the page renders. 1h adds a negative
authz case (denied user → 404).

## Parity gaps NOT covered in v1 (explicitly deferred)

Tracked so they're acknowledged, not silently missing: **conditional visibility / `If` container**
(show widget when an expression is true), **computed page variables** (`vars` derived from an expression,
not just a literal `default`), **runtime list filter/sort UI** on `table`/`list`, **form-level
validation-summary** beyond per-field Zod, and editor affordances **undo/redo, copy/paste, multi-select**.
The **unsaved-changes guard** (today a `// Could show a confirmation dialog here` TODO) is in scope for 2c.

## i18n

All new user-facing strings (palette categories, inspector groups/field labels, the 8 action-type labels,
empty/error/loading states, the 1h 404 hint) go through `useI18n`/`t()` with `builder.*` keys — the
current builder is fully `useI18n`-driven; do not hardcode English. Each FE slice owns its strings.

## Child-spec template

Each `page-builder/<slice>.md` must include every section (sections that don't apply state
"N/A — <reason>", never omit silently):

1. **Goal & scope** — what the slice delivers, what it does *not*, parent-doc sections it conforms to.
2. **UI samples** — ASCII/markdown wireframes of inspector/palette/canvas interactions and runtime
   output; sample component trees as JSON; before/after of the affected screen. (Backend-only:
   sample request/response payloads.)
3. **Data & API contracts** — exact TS interfaces, JSON shapes, endpoint signatures, render-contract
   delta, `config` JSON schema delta; versioning + back-compat.
4. **DB migrations** — exact Flyway `V<n>__*.sql` if any; else **"None — stored in `ui-pages.config`
   JSON, no DDL"**. Verify Flyway head before numbering (head **V146**, next **V147**).
5. **File-by-file code changes** — every file created/modified, the specific functions/components,
   before→after sketches, exact paths, and registration/wiring steps.
6. **Test plan** — Vitest unit (FE), worker unit with mocked JdbcTemplate/QueryEngine (BE),
   kelta-test-harness integration test (if DB-constraint-sensitive), post-deploy Playwright e2e.
7. **Docs to update** — specific rows in `status.md` / `conventions.md` / `architecture.md` /
   `integrations.md` (per CLAUDE.md Rule 6).
8. **Risks & open questions** — fragile/oversized files (`concerns.md`), sequencing deps, decisions
   needing the user.

## Slice plan

Backend keystone first (zero FE dependency, additive); FE slices build on the registry.

- **1g — Render contract v2** (backend). New `PageRenderContract` shape, `version "2.0"`, `tree`=whole
  config, v1/v2 both empty-or-populated arrays. `CustomPage` reads siblings. Worker unit test (mocked
  QueryEngine) **+ a kelta-test-harness round-trip scenario** (PATCH a v2 `config` → GET render → assert
  `variables`/`dataSources` surface from real Postgres — mock tests can't see a serialization drop).
- **2a — Widget registry + shared render module + model** (FE). `model/*`, `widgets/types.ts` (incl.
  `supportedEvents`), `registry.ts`, `renderTree.tsx` (resolved-node invariant; identity resolver);
  migrate existing 8 types to descriptors (`table`/`form` keep legacy strings; `form` category `input`).
  **Split into (a) introduce registry+RenderTree behind a flag with a golden snapshot of all 8 widgets
  captured before the refactor; (b) flip `PageTreeRenderer`/preview to consume it, gated behind the
  feature flag with fallback to legacy `PageNodeRenderer`.** The one deliberate change (runtime `heading`
  honors `level`) is a reviewed snapshot diff. **Hard edge: 1g before 2a.**
- **2b — Schema-driven inspector + palette** (FE). `inspector/*` (incl. `event-list` field = whole
  `node.events` over `descriptor.supportedEvents`); palette from `widgetRegistry.listByCategory()`.
  Delete the 4 hardcoded builder switch sites. All new strings via `useI18n`.
- **2c — Layout engine + dnd-kit canvas** (FE). `canvas/*`, grid/row/column/divider widgets, responsive
  `span`, drop-into-container, reorder, resize, `treeOps` (finalize), `migrate.ts`. Add `@dnd-kit/*` dep.
  **Owns the `handleSavePage`/`mergeConfig` rewrite to persist `schemaVersion`/page-level siblings (see
  Save & persistence)**, the **unsaved-changes guard**, and **deprecating the create-form layout select**
  (`config.layout` becomes inert legacy). `migrate.ts` tested against a **real legacy page `config`** +
  idempotency + migrate→save→reload round-trip.
- **2d — Data binding & expressions** (FE). `BindableField` finalize + `fx` via `FieldExpressionPicker`;
  `bindingScope`/`resolveBindings`/`interpolate` (make the resolver real; `getPath` skips
  `__proto__`/`constructor`/`prototype`); **owns creating the page-settings drawer + Variables + Data
  sources sections**; on-load fetch hook over JSON:API with `MAX_PAGE_DATA_SOURCES` + repeater render cap;
  `field-value`, `list`/`repeater`. Extends the 2c save call with `variables`/`dataSources`. **Re-runs
  2a's builtin parity suite unchanged** to prove the now-real resolver is identity on literal props.
- **2e — Events & actions** (FE). `EventListField` runtime; action runtime (`runFlow` →
  `/api/flows/{id}/execute` + optional poll reading `data.id`; `navigate`/`openUrl` with **URL scheme
  allow-list**; `refreshData`/`setVar`/`showToast`; `createRecord`/`updateRecord` → JSON:API). **Owns the
  post-deploy `page-builder-v2.spec.ts` e2e** (asserts a mutation, not just render).
- **2f — Typed form widgets** (FE). `form` → `ResourceForm`; standalone typed inputs (`dropdown`,
  `datepicker`, `checkbox`, `number-input`, `lookup`, `multi-picklist`) from `useCollectionSchema` +
  picklist endpoint. `rich-text` output sanitized. Replaces text-only `FormNode`.
- **2g — Widget breadth** (FE). `chart`, `tabs`, `nav`, `icon`, `link` (+`image`) — `link.href`/`image.src`
  scheme-validated (same allow-list as 2e).
- **1h — Per-page authz** (backend, optional, last). Config-driven `requiredPermission` gate (404-not-403)
  + inspector Access field (into 2d's drawer). Worker unit test (allow/deny/absent) **+ a kelta-test-harness
  `PageRenderAuthzScenarioTest`** (granted vs denied profile under real RLS → 200 vs 404).

Dependency order (hard edges): **1g → 2a → {2b, 2c} → 2d → {2e, 2f, 2g}**; 1h after 1g (any time). 2a
must tolerate a v1 contract (renders with empty scope) and is flag-gated so `main` is never half-broken.
Ship 1g first, then 2a, behind the flag, soaked before the runtime flip.

## Critical files

**Refactor:** [PageBuilderPage.tsx](../../../kelta-ui/app/src/pages/PageBuilderPage/PageBuilderPage.tsx)
(→ thin shell),
[PageTreeRenderer.tsx](../../../kelta-ui/app/src/pages/app/CustomPage/PageTreeRenderer.tsx)
(→ `<RenderTree>`), [pageConfig.ts](../../../kelta-ui/app/src/pages/PageBuilderPage/pageConfig.ts),
[CustomPage.tsx](../../../kelta-ui/app/src/pages/app/CustomPage/CustomPage.tsx),
[PageRenderContract.java](../../../kelta-worker/src/main/java/io/kelta/worker/service/PageRenderContract.java),
[PageRenderService.java](../../../kelta-worker/src/main/java/io/kelta/worker/service/PageRenderService.java).

**Create (FE):** `model/{pageModel,bindingScope,resolveBindings,interpolate,treeOps,migrate}.ts`,
`widgets/{types,registry,renderTree}.tsx` + `widgets/builtins/*`, `inspector/*`, `canvas/*`,
`hooks/{usePageDataSources,usePageVariables}.ts` — all under
`kelta-ui/app/src/pages/PageBuilderPage/`.

## Docs to update (per CLAUDE.md Rule 6)

- `.claude/docs/status.md` — page-builder row: add slices; move "typed/validated form fields" (and
  per-page authz if 1h ships) out of the gap column.
- `.claude/docs/conventions.md` — document page config v2 as a contract: `$bind` marker + namespace,
  `variables`/`dataSources`/`events`/`span` shape, v1→v2 back-compat rule.
- `.claude/docs/architecture.md` — `PageRenderService` row: new contract shape, pass-through-preserves-FLS
  note, the `requiredPermission` gate (if 1h).
- `CLAUDE.md` reference-doc table — add a row pointing at `.claude/docs/specs/`.
- `project_outsystems_roadmap.md` (memory) — record the builder-parity slices under Rec 1.
