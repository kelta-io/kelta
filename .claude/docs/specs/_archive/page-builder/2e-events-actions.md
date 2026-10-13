# 2e — Events & actions

> **Slice:** 2e (FE). **Axis:** Logic & events.
> **Parent:** [`../page-builder-parity.md`](../../page-builder-parity.md). This child spec **extends, never
> contradicts** the parent's [The shared model](../../page-builder-parity.md#the-shared-model) (the
> `PageAction` union + `EventHandlers`) and [Reuse Map](../../page-builder-parity.md#reuse-map) (flow
> execute/poll endpoints, JSON:API create/update). It consumes the types defined once in
> [`2a-widget-registry.md`](./2a-widget-registry.md#31-modelpagemodelts--v2-model-matches-parent-the-shared-model)
> (`PageAction`, `EventHandlers`, `Binding`, `PropValue`, `PageComponent`) — **2e does not redefine them**;
> it only adds the runtime executor and finalizes the editor.
> Source-verified against the codebase on 2026-06-22 (Flyway head V146; **this slice adds no migration**).
> If code and this doc disagree, trust the code and fix this doc.

---

## 1. Goal & scope

### What this slice delivers

Wire page **events** to **actions**. Today a `button` node can only carry a `props.href` and the runtime
renders it as an `<a>` (verified: `PageTreeRenderer.tsx` `PageNodeRenderer` `case 'button'`). There is **no
event model and no action runtime** — clicking a button does nothing beyond following an href. 2e closes the
"Logic & events/actions" axis:

1. **Finalize the inspector `EventListField`** (`inspector/fields/EventListField.tsx`). 2b ships its editor
   **shell** as the canonical model: **ONE** `kind:'event-list'` prop with **`key:'events'`** whose value is
   the whole `node.events` (`EventHandlers`), tabbed over **`descriptor.supportedEvents`** (declared on
   `WidgetDescriptor` in 2a) — **not** a `key:'onClick'` field and **not** a separate ad-hoc `supportedEvents`
   field introduced here. 2e fills the shell in: per-event tabs (filtered to `descriptor.supportedEvents`,
   e.g. `onClick`/`onChange`/`onSubmit`/`onLoad`), an **ordered** `PageAction[]` list per event with **add /
   remove / reorder**, and a **per-action sub-form** keyed on `action`:
   - `runFlow` → flow picker (`GET /api/flows`) + input-mapping rows (`key` → literal-or-`{$bind}` value)
     + `awaitResult` toggle.
   - `navigate` → `to` (route path) + `params` rows + `newTab` toggle.
   - `openUrl` → `url` (bindable) + `newTab` toggle.
   - `createRecord` / `updateRecord` → collection picker + `attributes` rows (+ `recordId` for update).
   - `refreshData` → `dataSource` picker (names from `config.dataSources`).
   - `setVar` → `name` picker (from `config.variables`) + `value`.
   - `showToast` → `level` select (`info`/`success`/`error`) + `message`.

2. **The client-side action runtime** (`runtime/executeAction.ts` + `runtime/useActionRunner.ts`). One
   `executeActions(actions, ctx)` entry point runs an ordered `PageAction[]` **sequentially**, resolving each
   action's bindable values (`input`/`attributes`/`params`/`message`/`url`/`recordId`/`value`) through the
   **2d** `resolveBindings`/`getPath` against the event scope, then dispatching:
   - `runFlow` → `POST /api/flows/{flowId}/execute` with `{ input: <resolved> }`; if `awaitResult`, poll
     `GET /api/flows/executions/{id}` until terminal.
   - `navigate` → react-router `navigate(...)`; `openUrl` → `window.open` / `location.assign`.
   - `createRecord` → `apiClient.postResource('/api/{collection}', attrs)`; `updateRecord` →
     `apiClient.patchResource('/api/{collection}/{id}', attrs)` (Cerbos + write-FLS enforced **server-side** —
     the runtime never bypasses the authorized JSON:API path).
   - `refreshData` → invalidate the React Query key for the named data source (re-runs the 2d on-load fetch).
   - `setVar` → update page-variable state (the 2d `usePageVariables` store).
   - `showToast` → `useToast().showToast(message, level)` (sonner-backed).

3. **Wire the `button` and `form` widget descriptors to fire events.** `button`'s `onClick` runs
   `events.onClick`; `form`'s `onSubmit` runs `events.onSubmit` (in addition to its existing create path);
   `onLoad` runs once on page mount; `onChange` is wired on input widgets (delivered fully in 2f, stubbed
   here). Firing happens **only in `mode:'runtime'`** — the editor preview never executes actions.

4. **Own the full-flow post-deploy e2e** (`e2e-tests/tests/page-builder-v2.spec.ts`). As the terminal
   behavior slice (parent §"End-to-end e2e ownership"/"Slice plan → 2e"), 2e authors the one owned spec — a
   real mutation, not just render — as a **named deliverable** done post-deploy before the feature is declared
   complete. See §6.5; **1h** adds the negative authz case.

### What this slice explicitly does NOT do

| Out of scope | Lands in |
|--------------|----------|
| `BindableField` / `fx` toggle / `resolveBindings` / `getPath` / `interpolate` / binding scope | **2d** (2e *consumes* them) |
| Page variables store + on-load data-source fetch hooks (`usePageVariables`, `usePageDataSources`) | **2d** (2e *reads/writes* them) |
| `EventListField` editor **shell** + the `kind:'event-list'` prop wiring into the inspector loop | **2b** (2e *finalizes* the body) |
| Typed inputs that emit `onChange` (`text-input`/`dropdown`/`datepicker`/…) | **2f** (2e leaves `onChange` plumbed but unfired by built-ins) |
| Any backend change to the flow / JSON:API endpoints | N/A — actions ride existing endpoints |
| Server-side action execution | N/A — all actions are client-side; server stays a pass-through (parent §"Backend changes") |

### Parent-doc sections this conforms to

- [The shared model → `PageAction` / `EventHandlers`](../../page-builder-parity.md#the-shared-model) — the union
  is reproduced **verbatim** in §3.1; 2e adds no new action kinds.
- [Reuse Map](../../page-builder-parity.md#reuse-map) rows: *Run a flow from an event*
  (`POST /api/flows/{flowId}/execute` → poll `GET /api/flows/executions/{id}`), *Create/update record from an
  event* (JSON:API `POST`/`PATCH`), *Variable insertion* / *Expression picker* (input-mapping value editor).
- [`2d` binding resolution](./2a-widget-registry.md) — action values may be `{$bind}` and resolve against the
  event scope `{ record, vars, data, page, item }`.

### Acceptance criteria

- A `button` node with `events.onClick = [{action:'showToast', level:'success', message:'Hi'}]` shows a
  success toast on click in the runtime (`CustomPage`), and **does nothing** in the editor preview.
- `runFlow` posts `{ input: <resolved> }` to `/api/flows/{flowId}/execute`; with `awaitResult:true` it polls
  `/api/flows/executions/{id}` every 1.5 s until status is terminal (`COMPLETED`/`FAILED`/`CANCELLED`), then
  resolves; without it, it fires-and-forgets after the 202-style RUNNING response.
- `createRecord`/`updateRecord` call `apiClient.postResource`/`patchResource` against `/api/{collection}` —
  the authorized JSON:API path (a Cerbos/write-FLS denial surfaces as an error toast, not a bypass).
- Bindable action values (`{$bind:'record.id', mode:'path'}` etc.) resolve against the click scope before the
  request fires (verified by a unit test with a stubbed scope).
- Actions in a list run **in order**; an awaited action that rejects **stops the chain** and surfaces an error
  toast (subsequent actions do not run).
- The `EventListField` round-trips `EventHandlers` through the existing builder save path (into the `config`
  JSON column) with no schema/migration change; reorder/add/remove mutate `node.events` immutably.
- `/verify` green (lint + typecheck + `test:coverage` ≥ the existing kelta-ui gate).

---

## 2. UI samples

### 2.1 Inspector — `EventListField` for a `button`'s `onClick`

The inspector (2b) loops over `descriptor.propSchema`; the `button` descriptor declares an `{ key:'events',
kind:'event-list' }` field, which renders the `EventListField` below. Event tabs are filtered to the events a
widget supports (`button` → `onClick`; `form` → `onSubmit`; any → `onLoad`).

```
┌─ Inspector ─ button ───────────────────────────────┐
│ Content                                             │
│   Label      [ Place order            ]             │
│   Variant    [ primary    ▾]                        │
│ ─────────────────────────────────────────────────  │
│ Events                                              │
│   ┌ onClick ┬ onLoad ┐                              │  ← event tabs (widget-supported only)
│   │ ▣ Actions (run in order)                        │
│   │ ┌───────────────────────────────────────────┐  │
│   │ │ ⠿ 1. Create record · orders          ✎  ✕ │  │  ← ⠿ drag handle (reorder), ✎ edit, ✕ remove
│   │ │ ⠿ 2. Run flow · Notify Ops           ✎  ✕ │  │
│   │ │ ⠿ 3. Show toast · success            ✎  ✕ │  │
│   │ └───────────────────────────────────────────┘  │
│   │ [ + Add action ▾ ]                              │  ← menu: runFlow / navigate / openUrl /
│   └───────────────────────────────────────────────┘  │     createRecord / updateRecord / refreshData /
│                                                     │     setVar / showToast
└─────────────────────────────────────────────────────┘
```

### 2.2 Per-action sub-form — `runFlow` with input mapping

Clicking ✎ on a `runFlow` action expands its sub-form. The flow picker is a `select` populated from
`GET /api/flows` (id → name). Each input-mapping row's **value** cell is a `BindableField` (2d): the `fx`
toggle flips a literal to `{$bind, mode:'expr'}` via `FieldExpressionPicker`.

```
┌─ Edit action · Run flow ───────────────────────────┐
│ Flow        [ Notify Ops (flow-7c2…)   ▾]          │  ← options from GET /api/flows
│ Await result  [✓]   (poll until the flow finishes)  │  ← awaitResult
│ Inputs (→ $.input.<key>)                            │
│   ┌ key ──────────┬ value ───────────────┬──┐      │
│   │ orderId       │ fx {{record.id}}      │ ✕│      │  ← value is BindableField; fx = expr binding
│   │ priority      │ "high"                │ ✕│      │  ← literal
│   └───────────────┴───────────────────────┴──┘      │
│   [ + Add input ]                                   │
│                                            [ Done ] │
└─────────────────────────────────────────────────────┘
```

> **Double-wrap is handled by the runtime, not the editor.** The editor stores the **inner** map
> (`{ orderId: {$bind…}, priority:'high' }`) as `action.input`. `executeAction` sends
> `{ input: <resolved action.input> }` as the HTTP body — i.e. it adds the outer wrap. So the flow reads
> `$.input.orderId` exactly as documented in `integrations.md` → "the double-wrap rule". The editor never asks
> the author to wrap twice.

### 2.3 Per-action sub-forms — the other action kinds (abbreviated)

```
navigate     to [ /app/p/orders   ]   newTab [ ]   params: key→value rows (bindable)
openUrl      url [ fx {{record.link}} ]   newTab [✓]
createRecord collection [ orders ▾ ]   attributes: key→value rows (bindable)
updateRecord collection [ orders ▾ ]   recordId [ fx {{record.id}} ]   attributes: key→value rows
refreshData  dataSource [ ordersList ▾ ]            ← names from config.dataSources
setVar       name [ selectedId ▾ ]   value [ fx {{record.id}} ]   ← name from config.variables
showToast    level [ success ▾ ]   message [ Saved! ]   (message bindable)
```

### 2.4 Sample tree JSON with `events.onClick`

What `config.components` holds for a button that creates a record, runs a flow, then toasts. The optional v2
`events` key round-trips untouched through the server (parent §"Backend stays a pass-through").

```json
{
  "id": "b1",
  "type": "button",
  "props": { "label": "Place order", "variant": "primary" },
  "events": {
    "onClick": [
      {
        "action": "createRecord",
        "collection": "orders",
        "attributes": {
          "customer": { "$bind": "vars.customerId", "mode": "path" },
          "status": "NEW"
        }
      },
      {
        "action": "runFlow",
        "flowId": "flow-7c2a",
        "awaitResult": true,
        "input": { "orderId": { "$bind": "record.id", "mode": "path" } }
      },
      { "action": "showToast", "level": "success", "message": "Order placed" }
    ]
  }
}
```

### 2.5 Runtime sequence — click → resolve → execute → toast

```
USER clicks <button data-testid="page-node-button"> in CustomPage (mode:'runtime')
        │
        ▼
button descriptor onClick → useActionRunner().run(node.events.onClick, scope)
        │
        ▼  executeActions([...], ctx) — sequential
   ┌────────────────────────────────────────────────────────────────────┐
   │ 1. createRecord                                                     │
   │    resolveBindings(action.attributes, scope) → { customer:'u-9',…}  │  (2d)
   │    apiClient.postResource('/api/orders', {customer:'u-9',status…})  │  → JSON:API (Cerbos/FLS)
   │    ↳ on reject: showToast(err.message,'error'); STOP chain          │
   │                                                                     │
   │ 2. runFlow (awaitResult:true)                                       │
   │    resolveBindings(action.input, scope) → { orderId:'ord-42' }      │
   │    apiClient.fetch('/api/flows/flow-7c2a/execute', POST,            │
   │        body { input:{ orderId:'ord-42' } })                         │  → { data:{ id, attrs:{status:RUNNING}}}
   │    id = unwrapResource(json).id                                     │
   │    poll GET /api/flows/executions/{id} @1.5s until status terminal  │  → COMPLETED/FAILED/CANCELLED
   │    ↳ FAILED ⇒ reject ⇒ error toast; STOP chain                      │
   │                                                                     │
   │ 3. showToast('Order placed','success')  → useToast().showToast(…)   │  → sonner
   └────────────────────────────────────────────────────────────────────┘
```

### 2.6 Before / after (the `button` widget)

```
BEFORE 2e: button renders <a href> or <button> with NO onClick behavior
           (PageTreeRenderer case 'button' — href only). events is unmodelled.

AFTER 2e:  button descriptor reads node.events.onClick; in runtime mode attaches
           onClick={() => run(events.onClick, scope)}. href still works (an href
           button with no onClick is unchanged). Editor preview: inert (no fire).
```

---

## 3. Data & API contracts

All TS lives under `kelta-ui/app/src/pages/PageBuilderPage/`. **No backend/API change in 2e** — every action
rides an endpoint that already exists.

### 3.1 `PageAction` union + `EventHandlers` (defined in 2a; reproduced here — authoritative match to parent)

These types are **declared in `model/pageModel.ts` by 2a**; 2e imports them. Reproduced verbatim so this spec
is self-contained — **if this drifts from `pageModel.ts`, the code wins.**

```ts
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
```

`PropValue` includes `Binding` (`{ $bind: string; mode?: 'path' | 'expr' }`) — any value cell in an action may
hold a literal or a binding (resolved in 2d).

### 3.2 `runtime/executeAction.ts` — the action runtime

```ts
import type { NavigateFunction } from 'react-router-dom'
import type { QueryClient } from '@tanstack/react-query'
import type { ApiClient } from '@/services/apiClient'
import type { ToastType } from '@/components/Toast/Toast'
import type { PageAction, PropValue } from '../model/pageModel'
import { resolveBindings } from '../model/resolveBindings' // 2d
import { unwrapResource } from '@/utils/jsonapi'

/** Everything the runtime needs to dispatch actions. Built once by useActionRunner (§3.3). */
export interface ActionRuntimeContext {
  apiClient: ApiClient
  navigate: NavigateFunction
  queryClient: QueryClient
  /** sonner-backed toast — exactly useToast().showToast. */
  showToast: (message: string, type: ToastType) => void
  /** Update a page variable (2d store). */
  setVar: (name: string, value: PropValue) => void
  /** React Query key prefix for a named data source (2d). refreshData invalidates it. */
  dataSourceQueryKey: (name: string) => unknown[]
  /** Binding scope captured at fire time: { record, vars, data, page, item }. */
  scope: Record<string, unknown>
  tenantSlug: string
}

/** Run one action. Resolves its bindable values against ctx.scope, then dispatches. */
export async function executeAction(action: PageAction, ctx: ActionRuntimeContext): Promise<void>

/**
 * Run an ordered action list sequentially. The first action that throws stops the chain and the
 * error is surfaced (an error toast) by the caller. Returns when all actions complete.
 */
export async function executeActions(actions: PageAction[], ctx: ActionRuntimeContext): Promise<void>

/** Poll a flow execution to terminal. Exported for direct unit testing. */
export async function pollFlowExecution(
  apiClient: ApiClient,
  executionId: string,
  opts?: { intervalMs?: number; timeoutMs?: number },
): Promise<{ status: string }>
```

**Per-action dispatch contracts (exact):**

| `action` | Resolved values | Dispatch |
|----------|-----------------|----------|
| `runFlow` | `input` → `resolveBindings(action.input ?? {}, scope)` | `apiClient.fetch('/api/flows/${flowId}/execute', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ input }) })`; read id via `unwrapResource(await resp.json()).id`; if `awaitResult` → `pollFlowExecution(apiClient, id)` |
| `navigate` | `params` → resolved, appended as query string | **`assertSafeUrl(toWithParams)` first** (see below); `newTab` → `window.open(toWithParams, '_blank')` else `navigate(toWithParams)` |
| `openUrl` | `url` → resolved string | **`assertSafeUrl(url)` first** (see below); `newTab` → `window.open(url, '_blank', 'noopener')` else `window.location.assign(url)` |
| `createRecord` | `attributes` → resolved | `apiClient.postResource('/api/${collection}', attrs)` |
| `updateRecord` | `attributes` + `recordId` → resolved | `apiClient.patchResource('/api/${collection}/${recordId}', attrs)` |
| `refreshData` | — | `queryClient.invalidateQueries({ queryKey: ctx.dataSourceQueryKey(action.dataSource) })` |
| `setVar` | `value` → resolved | `ctx.setVar(action.name, value)` |
| `showToast` | `message` → resolved string | `ctx.showToast(message, action.level)` |

> **URL scheme allow-list (SECURITY — parent §"Security — binding & action output safety").** `navigate.to`
> and `openUrl.url` are author- **and** data-controlled: a `{$bind}` can resolve at fire time to a
> `javascript:` or `data:` URL, which would execute as XSS the moment it reaches `window.location.assign` /
> `window.open` / the router. Before any of those calls, `executeAction` runs `assertSafeUrl(resolved)`, which
> parses the (already-resolved) target and **rejects any scheme not in `{ http, https, mailto, tel, relative }`**
> (relative = no scheme, i.e. an in-app path like `/app/p/orders`). A rejected URL throws → the chain stops and
> an error toast surfaces (the action does **not** navigate/open). `assertSafeUrl` lives in
> `runtime/executeAction.ts` (a small pure helper, exported for unit testing). Because resolution happens
> first, the check sees the **final** string a `{$bind}` produced — not the literal `{$bind}` marker. (This is
> the 2e half of the shared allow-list; 2g applies the same list to `link.href`/`image.src`.)
>
> **Binding resolution boundary (two distinct resolutions — do not conflate).** There are two `{$bind}`
> resolutions, owned by different layers:
> 1. **Widget props** (`node.props`) are resolved **before** they reach the descriptor's `Render` — this is the
>    parent's **resolved-node invariant** (parent §"Widget registry"): `WidgetRenderProps.node.props` is
>    **always** fully binding-resolved by `RenderTree`/`renderNode` (identity no-op in 2a, real in 2d).
>    Descriptors (including `button`/`form` here) **must not** call `resolveBindings` on their own props — so
>    the `scope` 2e captures at fire time is read from already-resolved props/state, not re-resolved.
> 2. **Action values** (`input`/`attributes`/`params`/`message`/`url`/`recordId`/`value`) are resolved by the
>    **2e action runtime, client-side, at fire time**, against the **event scope** — *not* by `RenderTree`,
>    because an action's bindings (e.g. `{$bind:'record.id'}`) must resolve against the scope live at click,
>    which a static prop pass can't capture. `resolveBindings` (2d) walks the value tree and replaces every
>    `{$bind}` leaf with its scope value (`mode:'path'` → `getPath`, `mode:'expr'` → `FormulaEvaluator`).
>    `executeAction` calls it on the action's value object **once** before dispatch; literals pass through
>    untouched. If 2d has not merged, `executeAction` imports a no-op `resolveBindings` shim (identity) so 2e is
>    independently testable — swapping in the real resolver is a single import change.
>
> So: props arrive resolved (2a invariant, server never resolves — pass-through only); action `{$bind}` values
> are resolved client-side by 2e against the event scope.

### 3.3 `runtime/useActionRunner.ts` — the React binding

```ts
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import { useToast } from '@/components/Toast/Toast'
import type { PageAction } from '../model/pageModel'

/** Returns a stable `run(actions, scope)` that builds an ActionRuntimeContext and executes the list. */
export function useActionRunner(opts: {
  tenantSlug: string
  setVar: (name: string, value: unknown) => void
  dataSourceQueryKey: (name: string) => unknown[]
}): { run: (actions: PageAction[] | undefined, scope: Record<string, unknown>) => Promise<void> }
```

`run` is a no-op when `actions` is empty/undefined. It wraps `executeActions` in a try/catch that calls
`showToast(err.message, 'error')` on rejection (the single error surface for the whole chain).

### 3.4 Verified flow endpoint — request / response / poll (quoted from real code)

**Execute** — `FlowExecutionController.executeFlow` (`@PostMapping("/{flowId}/execute")`):

Request body (the runtime sends the `input` form — the double-wrap outer layer):
```jsonc
{ "input": { "orderId": "ord-42" } }   // controller reads body.input → state.input → flow reads $.input.orderId
```

Response (`JsonApiResponseBuilder.single("flow-executions", resultExecutionId, attrs)` where
`attrs = { flowId, status: "RUNNING" }`):
```jsonc
{ "data": { "type": "flow-executions", "id": "<executionId>", "attributes": { "flowId": "…", "status": "RUNNING" } } }
```
> The async response is **always `200 OK` with `status:"RUNNING"`** (the controller starts the execution via
> `flowEngine.startExecution(...)` and returns immediately — it does not wait). The execution id is
> `data.id` — `unwrapResource(json).id`.

**Poll** — `getExecution` (`@GetMapping("/executions/{executionId}")`): returns
`JsonApiResponseBuilder.single("flow-executions", id, execMap)` where `execMap` includes
`status`, `stateData`, `errorMessage`, `completedAt`, … :
```jsonc
{ "data": { "type": "flow-executions", "id": "<executionId>",
            "attributes": { "status": "COMPLETED", "stateData": {…}, "errorMessage": null, … } } }
```
Terminal statuses (from `DebugTab` / `FlowExecutionData.isTerminal()`): **`COMPLETED` / `FAILED` /
`CANCELLED`**; in-flight: `RUNNING` / `WAITING`. `pollFlowExecution` reads
`unwrapResource(await resp.json()).status` and resolves when terminal, rejects on `FAILED`/`CANCELLED` (so the
chain stops), or rejects on `timeoutMs` (default 60 s, interval 1.5 s).

> **Bug to fix while here (low-risk, in-scope-adjacent):** the existing admin pages (`FlowsPage.tsx` line ~119,
> `FlowDesignerPage.tsx` line ~159) read `(await resp.json()).executionId` from the execute response — but the
> controller returns JSON:API `data.id`, **not** a top-level `executionId`. The new runtime reads it correctly
> via `unwrapResource(json).id`. We do **not** rewrite the admin pages in this slice (out of scope), but a
> follow-up chip is flagged so they stop relying on a field that isn't there.

**Gateway / authz:** `/api/flows/**` is a **static route** registered in
`RouteConfigService.registerStaticRoutes()` (`{"flows", "/api/flows/**", "flows"}`), so it gets only the blanket
`API_ACCESS` check at the gateway (not per-resource Cerbos) — the same path the existing FlowsPage execute uses.
No new route or authz change is needed.

### 3.5 JSON:API create / update (verified FE methods)

- **Create:** `apiClient.postResource('/api/${collection}', attrs)` — auto-wraps the plain attrs object into
  `{ data: { type, attributes } }` and unwraps the response (`apiClient.ts` `postResource`). This is exactly
  the path `PageTreeRenderer`'s `FormNode` already uses for create.
- **Update:** `apiClient.patchResource('/api/${collection}/${recordId}', attrs)` — auto-wraps with the id
  extracted from the URL (`apiClient.ts` `patchResource`). (No existing page-builder caller today; this is the
  update equivalent of the create path.)
- Both go through the gateway → worker JSON:API, so **Cerbos route authz + write-side FLS
  (`CerbosFieldWriteSecurityAdvice`) are enforced server-side** — a write-denied field or record surfaces as a
  4xx that the runtime turns into an error toast. The runtime never constructs raw SQL or bypasses the filter.

### 3.6 Config / contract delta

**None.** `events` is an **optional** key on `PageComponent` (defined in 2a), nested inside the existing
`ui-pages.config` JSON column. The render contract (1g) already round-trips the whole tree; `events`
round-trips with it. No new `PageConfig` top-level field, no NATS subject, no render-contract field. v1 pages
(no `events`) are unaffected — a node without `events` simply has no handlers.

---

## 4. DB migrations

**None — stored in `ui-pages.config` JSON, no DDL.** Confirmed per this slice: every action dispatches to an
**already-existing** endpoint (`POST /api/flows/{id}/execute`, `GET /api/flows/executions/{id}`, JSON:API
`POST`/`PATCH /api/{collection}`); `events` nests in the existing `config` column. Flyway head remains **V146**;
no version consumed. No NATS subject or payload change.

---

## 5. File-by-file code changes

All paths under `kelta-ui/app/src/pages/PageBuilderPage/` unless noted.

### 5.1 New — runtime executor

| File | Contents |
|------|----------|
| `runtime/executeAction.ts` | §3.2 — `ActionRuntimeContext`, `executeAction`, `executeActions` (sequential; first reject stops the chain), `pollFlowExecution`, and **`assertSafeUrl(url)`** (URL scheme allow-list — throws on a scheme not in `{http,https,mailto,tel,relative}`; called before every `navigate`/`openUrl` dispatch). Imports `resolveBindings` (2d), `unwrapResource` (`@/utils/jsonapi`). No React here — pure async/sync functions for direct unit testing. |
| `runtime/useActionRunner.ts` | §3.3 — `useActionRunner({ tenantSlug, setVar, dataSourceQueryKey })` → `{ run }`. Pulls `apiClient` from `useApi()`, `navigate` from `useNavigate()`, `queryClient` from `useQueryClient()`, `showToast` from `useToast()`; assembles `ActionRuntimeContext` per `run(actions, scope)` call; wraps `executeActions` in try/catch → error toast. |

### 5.2 Finalize — inspector `EventListField`

| File | Contents |
|------|----------|
| `inspector/fields/EventListField.tsx` | **2b ships the shell; 2e fills the body.** This is the **single** `kind:'event-list'` field with **`key:'events'`** — its `value` is the whole `node.events`. Props (matching the shell 2b wired): `{ value: EventHandlers \| undefined; onChange: (next: EventHandlers) => void; supportedEvents: Array<keyof EventHandlers>; collections: …; flows: …; dataSources: string[]; variables: string[] }`, where `supportedEvents` is the descriptor's `descriptor.supportedEvents` (declared in 2a) that the inspector loop passes through — **not** a field re-declared in 2e. Renders event tabs (filtered to `descriptor.supportedEvents`), a sortable `PageAction[]` list per event (add via "+ Add action" menu, remove, **reorder**), and the per-action sub-form (§5.3). All mutations are immutable (`treeOps`-style local helpers `addAction`/`removeAction`/`moveAction`/`updateAction`). Emits the whole `EventHandlers` up via `onChange` → the inspector writes it to `node.events` through the existing `updateProps`-equivalent save path. |
| `inspector/fields/actions/ActionEditor.tsx` | Dispatches on `action.action` to the right sub-form; renders the add-action menu (the 8 kinds). |
| `inspector/fields/actions/RunFlowForm.tsx` | Flow picker (`select` from `GET /api/flows` via a `useFlows()` query — id→name), `awaitResult` toggle, input-mapping rows (key text + value `BindableField`). |
| `inspector/fields/actions/NavigateForm.tsx` | `to` text, `params` rows (bindable), `newTab` toggle. |
| `inspector/fields/actions/OpenUrlForm.tsx` | `url` `BindableField`, `newTab` toggle. |
| `inspector/fields/actions/RecordForm.tsx` | Used for `createRecord`/`updateRecord`: collection picker (reuse `useCollectionSchema` collection list), `attributes` rows (bindable), `recordId` `BindableField` (update only). |
| `inspector/fields/actions/RefreshDataForm.tsx` | `dataSource` `select` from `dataSources` (config.dataSources names). |
| `inspector/fields/actions/SetVarForm.tsx` | `name` `select` from `variables`, `value` `BindableField`. |
| `inspector/fields/actions/ShowToastForm.tsx` | `level` `select` (info/success/error), `message` `BindableField`. |
| `inspector/hooks/useFlows.ts` | `useQuery(['flows'], () => apiClient.getList('/api/flows'))` → `{ id, name }[]` for the flow picker. (Reuses `apiClient.getList`; `/api/flows` is the existing static route.) |

> **Reuse, don't reinvent, the value editor.** Every "value" cell (input-mapping value, attribute value,
> `message`, `url`, `recordId`, `setVar` value) is a **`BindableField`** (2d) — the `fx` toggle + literal text
> input. The `EventListField` does not implement its own expression UI; it composes `BindableField`. (Per the
> Reuse Map / Pitfall "❌ Fork a new shared form component".)
>
> **i18n (mandatory — parent §"i18n").** Every new user-facing string in 2e goes through `useI18n`/`t()` with
> `builder.*` keys — **no hardcoded English** (the builder is fully `useI18n`-driven). 2e owns these strings:
> - **The 8 action-type labels** (in the "+ Add action" menu and each action row title):
>   `builder.actions.runFlow` / `navigate` / `openUrl` / `createRecord` / `updateRecord` / `refreshData` /
>   `setVar` / `showToast`.
> - **Per-sub-form labels** (one `builder.actions.<kind>.<field>` namespace), e.g. `RunFlowForm` "Flow",
>   "Await result", "Inputs", "Add input"; `NavigateForm` "To", "Params", "New tab"; `OpenUrlForm` "URL",
>   "New tab"; `RecordForm` "Collection", "Attributes", "Record ID"; `RefreshDataForm` "Data source";
>   `SetVarForm` "Name", "Value"; `ShowToastForm` "Level", "Message" — plus the shared "Events" tab/section
>   label, "Actions (run in order)", "Add action", "Edit action", "Done", and the toast-level option labels
>   (`info`/`success`/`error`). Each sub-form file pulls `t()` from `useI18n`; the EventListField test asserts
>   no raw English literal leaks (consistent with the existing builder i18n tests).

### 5.3 Wire widget descriptors to fire events (built-ins from 2a)

| File | Change |
|------|--------|
| `widgets/builtins/button.tsx` | Ensure the descriptor carries a `propSchema` entry `{ key:'events', label:'Events', kind:'event-list' }` and `supportedEvents:['onClick']` on the **descriptor** (the `WidgetDescriptor.supportedEvents` field declared in 2a — the inspector reads it to filter the `event-list` tabs). In `Render`, when `mode==='runtime'` and `node.events?.onClick`, attach `onClick={(e)=>{ if(!href) e.preventDefault(); void run(node.events!.onClick!, scope) }}`. `run` comes from `useActionRunner` (the descriptor's `Render` is a component, so it may call the hook). `mode==='editor'` → no handler (inert). href-only buttons unchanged. |
| `widgets/builtins/form.tsx` | `supportedEvents:['onSubmit']`. In `Render` (runtime), after the existing create mutation succeeds (or instead, if `events.onSubmit` is set), run `events.onSubmit`. Decision: if `onSubmit` actions exist, they run **after** the built-in create resolves (so `record` in scope reflects the created row); a form with no `onSubmit` behaves exactly as today. |
| `widgets/builtins/index.ts` | No registration change — descriptors already registered in 2a; only their internals change. |

> **`supportedEvents` lives on the descriptor** (`WidgetDescriptor.supportedEvents?: Array<keyof
> EventHandlers>`, **already declared in 2a** — see parent §"Widget registry"), read by the inspector's
> single `event-list` field to filter tabs. 2e does **not** re-add it to `widgets/types.ts`; it only sets the
> value on the built-in descriptors it wires (`button` → `['onClick']`, `form` → `['onSubmit']`). The default
> when a descriptor omits it is `['onClick','onLoad']` (applied in the registry/inspector, per 2a). **No
> `types.ts` change in 2e.**

### 5.4 Page mount — `onLoad`

| File | Change |
|------|--------|
| `pages/app/CustomPage/CustomPage.tsx` (or the `RenderTree` runtime host) | After the tree mounts and the 2d data sources resolve, run any root-level `onLoad` actions once (page-level `events.onLoad`, if the page config carries it) via `useActionRunner`. Guard with a `useRef` so it fires once per mount. (If page-level events aren't modelled yet, this is a no-op hook call — `events.onLoad` on individual nodes fires from their descriptor's mount effect; v1 scope: node-level `onLoad` on `container`/`form` only, page-level deferred to whoever adds a page-events panel.) |

### 5.5 Type touch

**None.** `WidgetDescriptor.supportedEvents?: Array<keyof EventHandlers>` is **already declared in 2a** (parent
§"Widget registry") and the `kind:'event-list'`/`key:'events'` `PropFieldSchema` is wired by 2b. 2e adds no
field to `widgets/types.ts`; it only consumes these. (Previously this section claimed 2e adds `supportedEvents`
to `WidgetDescriptor` — corrected: 2a owns it.)

---

## 6. Test plan

Vitest + Testing Library + MSW, matching the existing idiom (`PageBuilderPage.test.tsx` uses the MSW `server`
from `vitest.setup` + `createTestWrapper`; `apiClient.test.ts` mocks the axios instance). React Query in tests
uses a `QueryClient` with `retry:false`.

### 6.1 New — `runtime/executeAction.test.ts` (per action type; mock `apiClient`/router/toast)

A fake `ActionRuntimeContext` with `vi.fn()` for `apiClient.fetch`/`postResource`/`patchResource`, `navigate`,
`queryClient.invalidateQueries`, `showToast`, `setVar`, and a fixed `scope`.

- **`showToast`** → calls `ctx.showToast('Saved', 'success')` (and resolves a `{$bind}` message against scope).
- **`setVar`** → calls `ctx.setVar('selectedId', <resolved value>)`.
- **`navigate`** → `navigate('/app/p/orders?status=NEW')` when `params` resolve; `newTab` → `window.open` spy.
- **`openUrl`** → `window.open(url,'_blank','noopener')` / `location.assign` per `newTab`.
- **URL scheme allow-list (SECURITY)** → an `openUrl`/`navigate` whose resolved target is
  `javascript:alert(1)` (and `data:text/html,…`) **throws/rejects** (`assertSafeUrl` blocks it): assert
  `window.open`/`location.assign`/`navigate` were **never** called and the action rejected. A `{$bind}` that
  resolves to a `javascript:` URL is likewise blocked (resolution runs first, so the check sees the final
  string). Allowed schemes (`https://…`, `mailto:…`, `tel:…`, relative `/app/p/orders`) pass through and
  dispatch normally.
- **`createRecord`** → `apiClient.postResource('/api/orders', {customer:'u-9', status:'NEW'})` with bindings
  resolved against scope; assert the exact resolved body.
- **`updateRecord`** → `apiClient.patchResource('/api/orders/ord-42', {...})` with `recordId` resolved.
- **`refreshData`** → `queryClient.invalidateQueries({ queryKey: dataSourceQueryKey('ordersList') })`.
- **`runFlow` (fire-and-forget)** → `apiClient.fetch('/api/flows/flow-7c2a/execute', POST)` with
  body `{ input: { orderId:'ord-42' } }` (assert the **outer wrap is added** and bindings resolved); resolves
  without polling when `awaitResult` is falsy.
- **`runFlow` async + poll** → execute returns `{data:{id:'ex-1',attributes:{status:'RUNNING'}}}`; first poll
  `GET /api/flows/executions/ex-1` → `RUNNING`, second → `COMPLETED`; assert `pollFlowExecution` resolves after
  the second poll. Use fake timers (`vi.useFakeTimers()` + `advanceTimersByTimeAsync`) for the 1.5 s interval.
  A `FAILED`/`CANCELLED` poll → `executeAction` rejects.
- **`executeActions` ordering** → three `vi.fn` actions run in order; a rejecting awaited action **stops the
  chain** (subsequent action not called) and the rejection propagates to the caller.

### 6.2 New — `inspector/fields/EventListField.test.tsx` (editing)

- Renders only `supportedEvents` tabs (`button` → `onClick` + `onLoad`; not `onSubmit`).
- "+ Add action" → pick `showToast` → `onChange` emits `{ onClick:[{action:'showToast',level:'info',message:''}] }`.
- Edit a `runFlow` action: flow picker lists `GET /api/flows` (MSW), selecting one sets `flowId`; adding an
  input row + typing a key/value emits the updated `input` map; toggling `awaitResult` sets the flag.
- **Reorder** (drag or up/down control) swaps two actions' order in the emitted array.
- **Remove** drops the action; emitting `{ onClick: [] }` (or removing the event key) is asserted.
- A bindable value cell flips to a `{$bind, mode:'expr'}` via the `fx` toggle (BindableField integration —
  stub `FieldExpressionPicker` if 2d not present; assert the emitted `{$bind}` shape).

### 6.3 New — `runtime/useActionRunner.test.tsx`

- `run([])` / `run(undefined)` is a no-op (no fetch, no toast).
- `run([{action:'showToast',...}], scope)` mounted in a test component with `ApiContext`/`ToastProvider`/Router
  wrappers fires the toast.
- A rejecting action surfaces **one** error toast with `err.message` and does not throw out of `run`.

### 6.4 Extend — widget parity / behavior tests

- `widgets/builtins/button.test.tsx`: in `mode:'runtime'`, clicking the button runs `events.onClick`
  (`run` spy called with the action list + scope); in `mode:'editor'`, clicking is inert (`run` not called).
  An href button with no `onClick` still renders `<a>` and navigates normally (parity).
- `widgets/builtins/form.test.tsx`: submitting runs the create mutation, then `events.onSubmit` (order
  asserted via a spy sequence); a form with no `onSubmit` behaves as before (parity with 2a/1f).

### 6.5 e2e (post-deploy, Playwright) — **2e owns `page-builder-v2.spec.ts` (named deliverable)**

Per the parent §"End-to-end e2e ownership" and §"Slice plan → 2e", **2e is the terminal behavior slice and
OWNS the one full-flow e2e spec** — it is **not** "covered by the parent." Because the admin route is absent
until deployed, e2e is **post-deploy**, but the spec is a **named deliverable authored post-deploy, before the
feature is declared done** (not optional, not deferrable).

**Deliverable:** `e2e-tests/tests/page-builder-v2.spec.ts` — the complete authored-page flow:
**palette → drop a widget into a grid column → bind a prop → wire a button `onClick` event → save → publish →
open the runtime page → assert a record is created via the button event and persists.** The assertion is a
**real mutation** (the row created by the `onClick` `createRecord` action is queried back via the JSON:API and
confirmed to persist), **not merely that the page renders**. (A `showToast` may accompany it, but the
load-bearing assertion is the persisted record.)

This is the same `page-builder-v2.spec.ts` the parent names; earlier FE slices (2a–2d) leave it to this slice.
**1h** later adds a **negative authz case** to the same suite (a denied user → 404). Runs against the deployed
env, not in this PR's CI.

---

## 7. Docs to update (same PR)

| Doc | Change |
|-----|--------|
| `.claude/docs/status.md` (Page builder / screen builder row, line ~48) | Add slice 2e: "**events & actions**: page widgets fire ordered `PageAction[]` per event (`onClick`/`onSubmit`/`onLoad`/`onChange`) via the client-side action runtime (`runtime/executeAction.ts`) — `runFlow` (`POST /api/flows/{id}/execute` `{input}` → optional poll `GET /api/flows/executions/{id}` to terminal), `navigate`/`openUrl` (router/window), `createRecord`/`updateRecord` (JSON:API `POST`/`PATCH`, Cerbos+write-FLS enforced), `refreshData` (invalidate the named data-source query), `setVar`, `showToast` (sonner); `inspector/fields/EventListField` authors them (add/remove/reorder + per-action sub-forms); action values may be `{$bind}` resolved (2d). Move 'buttons only do href' out of the gap column." Leave 'typed/validated form fields' (2f) and 'per-page Cerbos authz' (1h) in the gap column. |
| `.claude/docs/integrations.md` (Flows section, ~line 104) | Add a short subsection **"Page-event → flow framing"**: page-builder events that run a flow use the **same** `POST /api/flows/{flowId}/execute` double-wrap rule — the editor stores the **inner** input map on `action.input`, and `executeAction` sends `{ input: <resolved action.input> }`, so the flow reads `$.input.<key>` (cross-reference the existing "double-wrap rule"). Note the async response is `status:"RUNNING"` and `awaitResult` polls `GET /api/flows/executions/{id}` to a terminal status (`COMPLETED`/`FAILED`/`CANCELLED`). This is the **only** new integration framing 2e introduces (no new endpoint/subject). |
| `.claude/docs/conventions.md` | If/when it documents page config v2 (deferred to 2d): add that `events` is an optional `EventHandlers` key on a node, `$bind` values inside action inputs/attributes follow the same binding contract, and the runtime never executes actions in `mode:'editor'`. |
| `.claude/docs/concerns.md` | Note (test-gap row): action runtime correctness (esp. `runFlow` poll + chain-stop-on-reject, and the `assertSafeUrl` scheme allow-list) is covered only by Vitest with mocked `apiClient`/timers; the 2e-owned post-deploy Playwright spec `e2e-tests/tests/page-builder-v2.spec.ts` (§6.5) is the real-path guard — it asserts a **persisted record** from a button event, not just render — mirroring the "DB-constraint test gap" memory (mocks can hide endpoint-shape drift, e.g. the existing `executionId` vs `data.id` read). |

Per CLAUDE.md Rule 6 these doc edits ship **in the same PR** as the code.

---

## 8. Risks & open questions

- **Sequencing — 2e depends on 2a, 2b, and 2d.** It consumes the model (`PageAction`/`EventHandlers` from 2a),
  the inspector shell + `kind:'event-list'` wiring (2b), and binding resolution + the page-variable / data-source
  stores (2d: `resolveBindings`, `BindableField`, `usePageVariables`, `usePageDataSources`). **Mitigation:**
  `executeAction` imports an identity `resolveBindings` shim if 2d hasn't merged (swap is one import), and the
  `EventListField` value cells fall back to a plain text input if `BindableField` is absent — so 2e is buildable
  and unit-testable ahead of full 2d, but should land **after** 2d for the bindable value editors to work.
- **Existing admin pages read a field the controller doesn't return.** `FlowsPage.tsx`/`FlowDesignerPage.tsx`
  read `(await resp.json()).executionId` from the execute response, but the worker returns JSON:API `data.id`
  (verified in `FlowExecutionController.executeFlow`). The **new** runtime reads it correctly via
  `unwrapResource(json).id`. Out of scope to fix the admin pages here, but flagged as a follow-up (a stale read
  that "works" only because those pages don't strictly depend on the navigation that uses it). **Open question:**
  fold the admin-page fix into this PR or a separate chore? (Recommend separate — keeps 2e a pure FE-feature PR.)
- **Action chain semantics — stop vs continue on error.** Decision in this spec: an **awaited** action that
  rejects **stops the chain** and shows one error toast. A fire-and-forget `runFlow` (`awaitResult:false`) that
  fails server-side won't stop the chain (we never await it). **Open question for the user:** is "stop on first
  error" the right default, or should each action be independently best-effort? (Recommend stop-on-error — least
  surprising for create-then-flow sequences.)
- **`runFlow` poll cost.** `awaitResult` polls every 1.5 s up to 60 s. A long-running flow will time out the
  await (reject → error toast) even though the flow keeps running server-side. **Mitigation:** document that
  `awaitResult` is for short flows; default to fire-and-forget. The poll interval/timeout are constants in
  `executeAction.ts` (not yet configurable per action) — a future `timeoutMs` on the `runFlow` action is a clean
  extension. **Open question:** expose a per-action timeout in the editor now, or defer? (Recommend defer.)
- **URL scheme allow-list (SECURITY — MAJOR).** `navigate.to` and `openUrl.url` are author- **and**
  data-controlled (a `{$bind}` can resolve to `javascript:`/`data:`, i.e. stored XSS that fires on click). 2e
  guards both with `assertSafeUrl` (§3.2): before `window.location.assign`/`window.open`/the router runs, the
  resolved target's scheme must be in `{http, https, mailto, tel, relative}`; anything else throws → the chain
  stops and an error toast surfaces. The check runs **after** binding resolution, so it sees the final string a
  `{$bind}` produced. A Vitest case asserts a `javascript:` URL is blocked (§6.1). This is the 2e half of the
  shared allow-list (parent §"Security — binding & action output safety"); 2g applies the same list to
  `link.href`/`image.src`. Without it, a published end-user page could execute attacker-controlled script.
- **`navigate` target validation (functional).** Beyond the scheme allow-list, `navigate.to` is authored
  free-text; a bad in-app path renders the router's not-found. No allow-list of page slugs in 2e (the editor
  could populate a slug picker from `GET /api/pages` later). **Mitigation:** treat `to` as a literal-or-binding
  string; document that it's the in-app route path (e.g. `/:tenant/app/p/<slug>`), not an absolute URL —
  absolute/external URLs use `openUrl`.
- **Editor must never fire actions.** The single biggest correctness risk: the descriptor `Render` is shared by
  editor preview and runtime (2a). Guard is the `mode` flag — handlers attach **only** when `mode==='runtime'`.
  A button-descriptor test asserts the editor click is inert. (Same discipline as the `table`/`form`
  placeholder-vs-fetch split in 2a.)
- **File size.** 2e adds `runtime/*` and `inspector/fields/actions/*` as **new small files** rather than growing
  `PageBuilderPage.tsx` — net-neutral on the oversized-file concern (the inspector itself already moved out in
  2b). No file is pushed past the `concerns.md` threshold.
