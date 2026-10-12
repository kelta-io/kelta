/**
 * useObjectPermissions — the object-level subset of useCollectionPermissions. It observes the
 * shared `/api/me/permissions` cache entry instead of reading it once, so a caller that rendered
 * before the shell's fetch landed (RelatedList) re-renders with the real flags.
 */
import React from 'react'
import { describe, it, expect } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useObjectPermissions } from './useObjectPermissions'
import { MY_PERMISSIONS_QUERY_KEY } from './useSystemPermissions'

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const hook = renderHook(() => useObjectPermissions('accounts'), { wrapper })
  return { queryClient, ...hook }
}

describe('useObjectPermissions', () => {
  it('is loading with permissive defaults until the permissions arrive, then re-renders', async () => {
    const { queryClient, result } = setup()
    expect(result.current.isLoading).toBe(true)
    expect(result.current.permissions.canDelete).toBe(true)

    act(() => {
      queryClient.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
        systemPermissions: {},
        objectPermissions: {
          accounts: { canCreate: false, canRead: true, canEdit: true, canDelete: false },
        },
        fieldPermissions: {},
      })
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.permissions).toEqual({
      canCreate: false,
      canRead: true,
      canEdit: true,
      canDelete: false,
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
