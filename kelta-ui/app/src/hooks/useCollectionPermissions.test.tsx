/**
 * useCollectionPermissions — reads the shared `/api/me/permissions` cache entry that
 * `useSystemPermissions` (app shell) fetches. It observes the entry, so a caller that rendered
 * before the fetch landed re-renders when it does, and a failed fetch ends the loading state
 * with the permissive defaults (UX gating only — the worker enforces permissions).
 */
import React from 'react'
import { describe, it, expect } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useCollectionPermissions } from './useCollectionPermissions'
import { MY_PERMISSIONS_QUERY_KEY } from './useSystemPermissions'

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const hook = renderHook(() => useCollectionPermissions('accounts'), { wrapper })
  return { queryClient, ...hook }
}

describe('useCollectionPermissions', () => {
  it('is loading until the permissions arrive, then re-renders with them', async () => {
    const { queryClient, result } = setup()
    expect(result.current.isLoading).toBe(true)

    act(() => {
      queryClient.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
        systemPermissions: {},
        objectPermissions: {
          accounts: { canCreate: false, canRead: true, canEdit: true, canDelete: false },
        },
        fieldPermissions: { accounts: { secret: 'HIDDEN', total: 'READ_ONLY' } },
      })
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.permissions.canCreate).toBe(false)
    expect(result.current.isFieldVisible('secret')).toBe(false)
    expect(result.current.isFieldEditable('total')).toBe(false)
    expect(result.current.isFieldEditable('name')).toBe(true)
  })

  it('stops loading with permissive defaults when the permissions fetch fails', async () => {
    const { queryClient, result } = setup()

    await act(async () => {
      await queryClient
        .fetchQuery({
          queryKey: MY_PERMISSIONS_QUERY_KEY,
          queryFn: () => Promise.reject(new Error('unavailable')),
        })
        .catch(() => undefined)
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.permissions).toEqual({
      canCreate: true,
      canRead: true,
      canEdit: true,
      canDelete: true,
    })
  })
  it('defaults to all-permitted for a collection the response does not list', async () => {
    const { queryClient, result } = setup()
    act(() => {
      queryClient.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
        systemPermissions: {},
        objectPermissions: {},
        fieldPermissions: {},
      })
    })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.permissions).toEqual({
      canCreate: true,
      canRead: true,
      canEdit: true,
      canDelete: true,
    })
  })
})
