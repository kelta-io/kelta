/**
 * useActionRunner tests. Mounts the hook inside the app providers (Router/QueryClient + the sonner
 * `<Toaster />`) with a
 * mocked `useApi` so we can control the apiClient. Asserts: empty/undefined lists are a no-op; a
 * showToast action fires a toast; a rejecting action surfaces ONE error toast and does not throw out of
 * `run`.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { Toaster } from 'sonner'
import type { ApiClient } from '@/services/apiClient'
import { useActionRunner } from './useActionRunner'

const apiClientMock: Partial<ApiClient> = {}
vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: apiClientMock }),
}))

function makeWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <I18nProvider>
          <>
            {children}
            <Toaster />
          </>
        </I18nProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}

describe('useActionRunner', () => {
  beforeEach(() => {
    for (const key of Object.keys(apiClientMock)) {
      delete (apiClientMock as Record<string, unknown>)[key]
    }
  })

  it('run([]) and run(undefined) are no-ops (no toast)', async () => {
    const postResource = vi.fn()
    apiClientMock.postResource = postResource
    const { result } = renderHook(() => useActionRunner(), { wrapper: makeWrapper() })
    await act(async () => {
      await result.current.run([], {})
      await result.current.run(undefined, {})
    })
    expect(postResource).not.toHaveBeenCalled()
    expect(document.querySelector('[data-sonner-toast]')).toBeNull()
  })

  it('fires a success toast for a showToast action', async () => {
    const { result } = renderHook(() => useActionRunner(), { wrapper: makeWrapper() })
    await act(async () => {
      await result.current.run([{ action: 'showToast', level: 'success', message: 'Hi' }], {})
    })
    await waitFor(() =>
      expect(document.querySelector('[data-sonner-toast][data-type="success"]')).not.toBeNull()
    )
    expect(document.querySelector('[data-sonner-toast]')?.textContent).toContain('Hi')
  })

  it('surfaces ONE error toast when an action rejects and does not throw out of run', async () => {
    apiClientMock.postResource = vi.fn().mockRejectedValue(new Error('Boom'))
    const { result } = renderHook(() => useActionRunner(), { wrapper: makeWrapper() })
    await act(async () => {
      await result.current.run(
        [{ action: 'createRecord', collection: 'orders', attributes: {} }],
        {}
      )
    })
    await waitFor(() =>
      expect(document.querySelector('[data-sonner-toast][data-type="error"]')).not.toBeNull()
    )
    const errors = document.querySelectorAll('[data-sonner-toast][data-type="error"]')
    expect(errors.length).toBe(1)
    expect(errors[0].textContent).toContain('Boom')
  })
})
