/**
 * useListParams — where a record list keeps its page / pageSize / sort / filter / view state.
 *
 * The state is always a set of list URL params in the `listUrlState` format, so every list
 * reads and writes it the same way; only where those params live differs:
 *
 * - `'sync'` — the URL itself (URL-as-state: deep links, back/forward, shareable views).
 *   Writes replace the current history entry.
 * - `'seed'` — seeded once from the URL on mount, then kept in component state; the URL is
 *   never written (a "View All" deep link still opens filtered).
 *
 * Writes go through {@link applyListParamUpdates}, which drops the defaults (`page=1`,
 * `pageSize=25`) so URLs stay clean in both modes.
 */
import { useCallback, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { parseListViewParams } from './listUrlState'
import { DEFAULT_PAGE_SIZE } from './listViewState'

/** Where the list state lives — see the module comment. */
export type ListUrlStateMode = 'sync' | 'seed'

/** Param name → new value; `undefined` removes the param. */
export type ListParamUpdates = Record<string, string | undefined>

export type ParsedListParams = ReturnType<typeof parseListViewParams>

export interface UseListParamsReturn {
  /** The raw params (the URL in `'sync'` mode, the local copy in `'seed'` mode). */
  searchParams: URLSearchParams
  /** The parsed list state. */
  params: ParsedListParams
  /** Apply updates (see {@link applyListParamUpdates}). */
  update: (updates: ListParamUpdates) => void
}

/** A copy of `prev` with `updates` applied and the default `page` / `pageSize` removed. */
export function applyListParamUpdates(
  prev: URLSearchParams,
  updates: ListParamUpdates
): URLSearchParams {
  const next = new URLSearchParams(prev)
  for (const [key, value] of Object.entries(updates)) {
    if (value === undefined) {
      next.delete(key)
    } else {
      next.set(key, value)
    }
  }
  if (next.get('page') === '1') next.delete('page')
  if (next.get('pageSize') === String(DEFAULT_PAGE_SIZE)) next.delete('pageSize')
  return next
}

export function useListParams(mode: ListUrlStateMode): UseListParamsReturn {
  const [urlParams, setUrlParams] = useSearchParams()
  // Read once: in 'seed' mode the URL only provides the starting state.
  const [localParams, setLocalParams] = useState(() => new URLSearchParams(urlParams))
  const searchParams = mode === 'sync' ? urlParams : localParams

  const params = useMemo(() => parseListViewParams(searchParams), [searchParams])

  const update = useCallback(
    (updates: ListParamUpdates) => {
      if (mode === 'sync') {
        setUrlParams((prev) => applyListParamUpdates(prev, updates), { replace: true })
      } else {
        setLocalParams((prev) => applyListParamUpdates(prev, updates))
      }
    },
    [mode, setUrlParams]
  )

  return { searchParams, params, update }
}
