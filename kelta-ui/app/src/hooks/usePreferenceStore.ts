import { useCallback, useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'
import { useMyIdentity } from './useMyIdentity'

/**
 * Server-backed per-user preference storage (app-data-entry slice 1) over the
 * `user-ui-preferences` system collection — one row per (userId, prefType, prefKey)
 * holding a JSON value. Rows are owner-scoped server-side (user-ui-preferences ownership metadata).
 *
 * Degrades gracefully: with no resolvable identity (logged out, offline, test render)
 * the store is localStorage-only via the caller-supplied `localKey`, which also serves
 * as the warm cache and the one-time migration source for pre-existing local data.
 */

interface PreferenceRow {
  id: string
  userId: string
  prefType: string
  prefKey: string
  value: unknown
}

export interface PreferenceValue<T> {
  /** Server value once loaded; null when the row does not exist (yet). */
  value: T | null
  /** True once the server answered (or identity is unavailable and only local applies). */
  isLoaded: boolean
  /** Upserts the server row (fire-and-forget) and mirrors to localStorage. */
  save: (value: T) => void
}

function readLocal<T>(localKey: string | undefined): T | null {
  if (!localKey) return null
  try {
    const raw = localStorage.getItem(localKey)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}

function writeLocal(localKey: string | undefined, value: unknown): void {
  if (!localKey) return
  try {
    localStorage.setItem(localKey, JSON.stringify(value))
  } catch {
    // localStorage full or unavailable
  }
}

export function usePreferenceValue<T>(
  prefType: string,
  prefKey: string,
  options?: { localKey?: string }
): PreferenceValue<T> {
  const { apiClient } = useApi()
  const { identity, isLoading: identityLoading } = useMyIdentity()
  const queryClient = useQueryClient()
  const localKey = options?.localKey
  const userId = identity?.userId
  const rowIdRef = useRef<string | null>(null)

  const queryKey = ['user-preference', userId, prefType, prefKey]
  const { data, isSuccess } = useQuery({
    queryKey,
    enabled: !!userId,
    staleTime: 5 * 60 * 1000,
    queryFn: async (): Promise<{ row: PreferenceRow | null }> => {
      try {
        const rows = await apiClient.getList<PreferenceRow>(
          `/api/user-ui-preferences?filter[userId][eq]=${encodeURIComponent(userId!)}` +
            `&filter[prefType][eq]=${encodeURIComponent(prefType)}` +
            `&filter[prefKey][eq]=${encodeURIComponent(prefKey)}&page[size]=1`
        )
        return { row: rows?.[0] ?? null }
      } catch {
        return { row: null }
      }
    },
  })
  if (data?.row) {
    rowIdRef.current = data.row.id
  }

  const save = useCallback(
    (value: T) => {
      writeLocal(localKey, value)
      if (!userId) return
      // Optimistic cache so consumers see the new value immediately.
      queryClient.setQueryData(queryKey, {
        row: { id: rowIdRef.current ?? '', userId, prefType, prefKey, value },
      })
      const write = async () => {
        try {
          if (rowIdRef.current) {
            await apiClient.patchResource(`/api/user-ui-preferences/${rowIdRef.current}`, {
              data: { type: 'user-ui-preferences', attributes: { value } },
            })
          } else {
            const created = await apiClient.postResource<{ id?: string }>(
              '/api/user-ui-preferences',
              {
                data: {
                  type: 'user-ui-preferences',
                  attributes: { userId, prefType, prefKey, value },
                },
              }
            )
            if (created && typeof created.id === 'string') {
              rowIdRef.current = created.id
            }
          }
        } catch {
          // Server write failed (offline, guard, etc.) — localStorage already holds
          // the value; the next successful load reconciles.
        }
      }
      void write()
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [apiClient, queryClient, userId, prefType, prefKey, localKey]
  )

  const serverValue = data?.row ? (data.row.value as T) : null
  const identitySettled = !identityLoading && !userId
  return {
    value: serverValue ?? (identitySettled ? readLocal<T>(localKey) : null),
    isLoaded: isSuccess || identitySettled,
    save,
  }
}
