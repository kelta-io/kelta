/**
 * The client-side action runtime (slice 2e). Pure async/sync functions — NO React — so each action type
 * is directly unit-testable with a mocked {@link ActionRuntimeContext}. The React binding (pulling
 * apiClient/navigate/queryClient/toast from context) lives in {@link ./useActionRunner}.
 *
 * For every action, bindable values (`input` / `attributes` / `params` / `message` / `url` / `recordId`
 * / `value`) are resolved against the captured event `scope` via the 2d {@link resolveBindings}/
 * {@link resolveValue} BEFORE dispatch — `{$bind}` markers never reach an endpoint. `navigate`/`openUrl`
 * targets additionally pass the {@link assertSafeUrl} URL scheme allow-list.
 *
 * `executeActions` runs a list sequentially; the first action that throws stops the chain (the caller —
 * `useActionRunner` — surfaces one error toast). The flow path reads the execution id from `data.id`
 * (`unwrapResource(json).id`), matching the verified `FlowExecutionController` JSON:API response shape.
 */
import type { NavigateFunction } from 'react-router-dom'
import type { QueryClient } from '@tanstack/react-query'
import type { ApiClient } from '@/services/apiClient'
import type { ToastType } from '@/utils/toast'
import { unwrapResource } from '@/utils/jsonapi'
import type { PageAction, PropValue } from '../model/pageModel'
import { isBinding } from '../model/pageModel'
import { resolveBindings, resolveBinding } from '../model/resolveBindings'
import type { BindingScope } from '../model/bindingScope'
import { assertSafeUrl } from './urlSafety'

/** Poll cadence + ceiling for an awaited `runFlow` (parent §3.4: every 1.5 s up to 60 s). */
const POLL_INTERVAL_MS = 1500
const POLL_TIMEOUT_MS = 60_000

/** Flow execution statuses that end a poll (parent §3.4 / `FlowExecutionData.isTerminal()`). */
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
/** Of the terminal statuses, these mean the flow did NOT succeed → reject so the chain stops. */
const FAILED_STATUSES = new Set(['FAILED', 'CANCELLED'])

/** Everything the runtime needs to dispatch actions. Built once per `run(...)` by useActionRunner. */
export interface ActionRuntimeContext {
  apiClient: ApiClient
  navigate: NavigateFunction
  queryClient: QueryClient
  /** sonner-backed toast — `showToast` from `@/utils/toast`. */
  showToast: (message: string, type: ToastType) => void
  /** Update a page variable (2d `usePageVariables` store). */
  setVar: (name: string, value: unknown) => void
  /** React Query key for a named data source (2d). `refreshData` invalidates it. */
  dataSourceQueryKey: (name: string) => unknown[]
  /** Binding scope captured at fire time: `{ record, vars, data, page, item }`. */
  scope: BindingScope
  tenantSlug: string
}

/** Resolve a single possibly-bound value against the scope (a literal passes through). */
function resolveValue(value: PropValue | undefined, scope: BindingScope): unknown {
  if (value === undefined) return undefined
  return isBinding(value) ? resolveBinding(value, scope) : value
}

/** Resolve a possibly-bound value to a string (for `url` / `recordId` / `message` / nav params). */
function resolveToString(value: PropValue | undefined, scope: BindingScope): string {
  const resolved = resolveValue(value, scope)
  return resolved == null ? '' : String(resolved)
}

/** Build `to` with resolved query params appended (literal `to` + bindable `params`). */
function buildNavigateTarget(
  to: string,
  params: Record<string, PropValue> | undefined,
  scope: BindingScope
): string {
  if (!params || Object.keys(params).length === 0) return to
  const search = new URLSearchParams()
  for (const [key, raw] of Object.entries(params)) {
    const resolved = resolveValue(raw, scope)
    if (resolved != null && resolved !== '') {
      search.append(key, String(resolved))
    }
  }
  const query = search.toString()
  if (!query) return to
  return to.includes('?') ? `${to}&${query}` : `${to}?${query}`
}

/**
 * Poll a flow execution until a terminal status (or timeout). Resolves on `COMPLETED`; rejects on
 * `FAILED`/`CANCELLED` (so an awaited chain stops) or on timeout. Exported for direct unit testing.
 */
export async function pollFlowExecution(
  apiClient: ApiClient,
  executionId: string,
  opts?: { intervalMs?: number; timeoutMs?: number }
): Promise<{ status: string }> {
  const intervalMs = opts?.intervalMs ?? POLL_INTERVAL_MS
  const timeoutMs = opts?.timeoutMs ?? POLL_TIMEOUT_MS
  const deadline = Date.now() + timeoutMs

  for (;;) {
    const resp = await apiClient.fetch(`/api/flows/executions/${encodeURIComponent(executionId)}`, {
      method: 'GET',
    })
    const json = await resp.json()
    const status = String(
      (unwrapResource(json as Record<string, unknown>) as { status?: unknown }).status ?? ''
    )

    if (TERMINAL_STATUSES.has(status)) {
      if (FAILED_STATUSES.has(status)) {
        throw new Error(`Flow execution ${status.toLowerCase()}`)
      }
      return { status }
    }
    if (Date.now() + intervalMs > deadline) {
      throw new Error('Flow execution timed out')
    }
    await delay(intervalMs)
  }
}

/** Promise-based delay — uses the global timer so vitest fake timers can advance it. */
function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Run one action: resolve its bindable values against `ctx.scope`, then dispatch. */
export async function executeAction(action: PageAction, ctx: ActionRuntimeContext): Promise<void> {
  switch (action.action) {
    case 'runFlow': {
      const input = resolveBindings(action.input ?? {}, ctx.scope)
      const resp = await ctx.apiClient.fetch(
        `/api/flows/${encodeURIComponent(action.flowId)}/execute`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ input }),
        }
      )
      if (!resp.ok) {
        throw new Error(`Flow execution failed to start (${resp.status})`)
      }
      const json = await resp.json()
      const executionId = String(
        (unwrapResource(json as Record<string, unknown>) as { id?: unknown }).id ?? ''
      )
      if (action.awaitResult) {
        if (!executionId) throw new Error('Flow execution returned no id')
        await pollFlowExecution(ctx.apiClient, executionId)
      }
      return
    }

    case 'navigate': {
      const target = buildNavigateTarget(action.to, action.params, ctx.scope)
      assertSafeUrl(target)
      if (action.newTab) {
        window.open(target, '_blank', 'noopener')
      } else {
        ctx.navigate(target)
      }
      return
    }

    case 'openUrl': {
      const url = resolveToString(action.url, ctx.scope)
      assertSafeUrl(url)
      if (action.newTab) {
        window.open(url, '_blank', 'noopener')
      } else {
        window.location.assign(url)
      }
      return
    }

    case 'createRecord': {
      const attrs = resolveBindings(action.attributes ?? {}, ctx.scope)
      await ctx.apiClient.postResource(`/api/${action.collection}`, attrs)
      return
    }

    case 'updateRecord': {
      const attrs = resolveBindings(action.attributes ?? {}, ctx.scope)
      const recordId = resolveToString(action.recordId, ctx.scope)
      if (!recordId) throw new Error('updateRecord requires a recordId')
      await ctx.apiClient.patchResource(
        `/api/${action.collection}/${encodeURIComponent(recordId)}`,
        attrs
      )
      return
    }

    case 'refreshData': {
      await ctx.queryClient.invalidateQueries({
        queryKey: ctx.dataSourceQueryKey(action.dataSource),
      })
      return
    }

    case 'setVar': {
      ctx.setVar(action.name, resolveValue(action.value, ctx.scope))
      return
    }

    case 'showToast': {
      ctx.showToast(resolveToString(action.message, ctx.scope), action.level)
      return
    }
  }
}

/**
 * Run an ordered action list sequentially. The first action that throws stops the chain and the error
 * propagates to the caller (which surfaces one error toast). Resolves when all actions complete.
 */
export async function executeActions(
  actions: PageAction[],
  ctx: ActionRuntimeContext
): Promise<void> {
  for (const action of actions) {
    await executeAction(action, ctx)
  }
}
