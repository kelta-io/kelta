/**
 * React binding for the action runtime (slice 2e §3.3). Assembles an {@link ActionRuntimeContext} from
 * app contexts (`apiClient`, `navigate`, `queryClient`) and the sonner `showToast` helper, plus the page-level deps passed in
 * (`tenantSlug`, `setVar`, `dataSourceQueryKey`), and returns a stable `run(actions, scope)`.
 *
 * `run` is a no-op when `actions` is empty/undefined. It wraps `executeActions` in a try/catch that fires
 * exactly ONE error toast (`showToast(err.message, 'error')`) on rejection — the single error surface for
 * the whole chain — and never throws out of `run`.
 */
import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import { showToast } from '@/utils/toast'
import type { PageAction } from '../model/pageModel'
import type { BindingScope } from '../model/bindingScope'
import { executeActions, type ActionRuntimeContext } from './executeAction'
import { usePageRuntime } from './PageRuntimeContext'

export interface UseActionRunnerReturn {
  run: (actions: PageAction[] | undefined, scope: BindingScope) => Promise<void>
}

export interface UseActionRunnerOptions {
  tenantSlug?: string
  setVar?: (name: string, value: unknown) => void
  dataSourceQueryKey?: (name: string) => unknown[]
}

/**
 * Returns a stable `run(actions, scope)`. Page-level deps default to the {@link usePageRuntime} context so
 * a widget descriptor can call this with no args; an explicit host (CustomPage) may also pass them in.
 */
export function useActionRunner(opts: UseActionRunnerOptions = {}): UseActionRunnerReturn {
  const { apiClient } = useApi()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const runtime = usePageRuntime()

  const tenantSlug = opts.tenantSlug ?? runtime.tenantSlug
  const setVar = opts.setVar ?? runtime.setVar
  const dataSourceQueryKey = opts.dataSourceQueryKey ?? runtime.dataSourceQueryKey

  const run = useCallback(
    async (actions: PageAction[] | undefined, scope: BindingScope): Promise<void> => {
      if (!actions || actions.length === 0) return
      const ctx: ActionRuntimeContext = {
        apiClient,
        navigate,
        queryClient,
        showToast,
        setVar,
        dataSourceQueryKey,
        scope,
        tenantSlug,
      }
      try {
        await executeActions(actions, ctx)
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Action failed'
        showToast(message, 'error')
      }
    },
    [apiClient, navigate, queryClient, setVar, dataSourceQueryKey, tenantSlug]
  )

  return { run }
}
